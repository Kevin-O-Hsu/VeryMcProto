package verymc.top.veryMcProto.mod.servux.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.framework.network.PacketSplitter;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.dataproviders.HudDataProvider;
import verymc.top.veryMcProto.mod.servux.util.nbt.DataTagIo;

/**
 * HUD 通道收发 Handler（mod 层）。移植自原版 {@code ServuxHudHandler}（去 Fabric + networkHandler 形参）。
 *
 * <p>收（C2S）：{@link #receivePlayPayload} 还原 {@link ServuxHudPacket} → {@link #decodeServerData} 分发到
 * {@link HudDataProvider} 的各 refresh* 方法。
 *
 * <p>发（S2C）：{@link #encodeServerData} 普通包走 {@link #sendPlayPayload}（plugin messaging）；
 * 大包（{@code PACKET_S2C_NBT_RESPONSE_START}）走 {@link PacketSplitter} 分片，每片经
 * {@link #encodeWithSplitter} 包装成 {@code ResponseS2CData} 发送。
 *
 * <p><b>失败计数（上游 tickFailures/checkFailures 语义，ServuxHudHandler:169-205 字面）</b>：deny 检疫
 * 与 S2C 发送失败共用同一份计数；超限（{@code > maxFailures() = 2}）回调 {@link HudDataProvider#onPacketFailure}
 * 且<b>不清零</b>（重置仅在 resetFailures，由 unregister/removePlayer[quit] 触发）；decode/encode 入口的
 * {@link #checkFailures} 闸静默丢弃越限玩家的后续包。
 */
public class ServuxHudHandler implements IPluginServerPlayHandler
{
    private static final ServuxHudHandler INSTANCE = new ServuxHudHandler();

    public static ServuxHudHandler getInstance() { return INSTANCE; }

    public static final Identifier CHANNEL_ID = ServuxReference.CHANNEL_HUD;

    private boolean payloadRegistered = false;
    private final Map<UUID, Integer> failures = new HashMap<>();
    private final Map<UUID, Long> readingSessionKeys = new HashMap<>();

    /** HUD 通道的接收 session key 映射（按玩家 UUID）；供 PacketSplitter C2S 大包重组用。 */
    public Map<UUID, Long> getReadingSessionKeys() { return this.readingSessionKeys; }

    @Override public Identifier getPayloadChannel() { return CHANNEL_ID; }

    @Override
    public boolean isPlayRegistered(Identifier channel)
    {
        return channel.equals(CHANNEL_ID) && this.payloadRegistered;
    }

    @Override
    public void setPlayRegistered(Identifier channel)
    {
        if (channel.equals(CHANNEL_ID)) { this.payloadRegistered = true; }
    }

    @Override
    public void clearPlayRegistered(Identifier channel)
    {
        if (channel.equals(CHANNEL_ID)) { this.payloadRegistered = false; }
    }

    @Override
    public void reset(Identifier channel)
    {
        if (channel.equals(CHANNEL_ID)) { this.failures.clear(); }
    }

    public void resetFailures(Identifier channel, ServerPlayer player)
    {
        if (channel.equals(CHANNEL_ID)) { this.failures.remove(player.getUUID()); }
    }

    /** 入口闸（上游 checkFailures:177-180 字面）：失败计数越限（&gt; maxFailures()）后丢弃该玩家本通道后续包。 */
    @Override
    public boolean checkFailures(ServerPlayer player)
    {
        return !(this.failures.getOrDefault(player.getUUID(), 0) > this.maxFailures());
    }

    /**
     * 失败计数 +1（上游 tickFailures:183-205 字面）：超限时回调 Provider.onPacketFailure 且<b>不清零</b>——
     * 重置仅在 {@link #resetFailures}（unregister / removePlayer[quit] 触发）。注册版本门禁的 deny 分支必调。
     */
    @Override
    public void tickFailures(ServerPlayer player)
    {
        UUID uuid = player.getUUID();

        if (!this.failures.containsKey(uuid))
        {
            this.failures.put(uuid, 1);
        }
        else if (this.failures.get(uuid) > this.maxFailures())
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "tickFailures hud → " + player.getName().getString()
                    + " 超过 " + this.maxFailures() + " 次失败，触发 onPacketFailure（未装 MiniHUD 或版本被拒后反复重试）");
            HudDataProvider.INSTANCE.onPacketFailure(player);
        }
        else
        {
            this.failures.put(uuid, this.failures.get(uuid) + 1);
        }
    }

    @Override
    public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player)
    {
        ServuxHudPacket packet = ServuxHudPacket.fromPacket(data);
        if (packet == null)
        {
            return;
        }
        ServuxDebug.log(ServuxDebug.Cat.PACKET, "C2S hud ← " + player.getName().getString() + " type=" + packet.getType());
        this.decodeServerData(CHANNEL_ID, player, packet);
    }

    @Override
    public <P extends IServerPayloadData> void decodeServerData(Identifier channel, ServerPlayer player, P data)
    {
        ServuxHudPacket packet = (ServuxHudPacket) data;

        if (!channel.equals(CHANNEL_ID))
        {
            return;
        }

        if (!HudDataProvider.INSTANCE.isEnabled() || !this.checkFailures(player))
        {
            return;
        }

        switch (packet.getType())
        {
            case PACKET_C2S_METADATA_REQUEST ->
            {
                // 上游 :86-95 字面：已注册玩家先 unregister（出册+resetFailures），再走带 tags 的注册
                // （版本门禁 + 权限 + 入册 + metadata 应答）
                if (HudDataProvider.INSTANCE.isPlayerRegistered(player))
                {
                    HudDataProvider.INSTANCE.unregister(player);
                }
                HudDataProvider.INSTANCE.register(player, packet.getCompound());
            }
            case PACKET_C2S_UNREGISTER_REPLY -> HudDataProvider.INSTANCE.unregister(player);
            case PACKET_C2S_SPAWN_DATA_REQUEST -> HudDataProvider.INSTANCE.refreshSpawnMetadata(player, packet.getCompound());
            case PACKET_C2S_RECIPE_MANAGER_REQUEST -> HudDataProvider.INSTANCE.refreshRecipeManager(player, packet.getCompound());
            case PACKET_C2S_DATA_LOGGER_REQUEST -> HudDataProvider.INSTANCE.refreshLoggers(player, packet.getCompound());
            default -> Reference.logger().warning("ServuxHudHandler#decodeServerData: 无效 packetType " + packet.getPacketType()
                    + " from " + player.getName().getString() + ", size=" + packet.getTotalSize());
        }
    }

    @Override
    public void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buffer)
    {
        // 每片包装成 ResponseS2CData 发送
        this.sendPlayPayload(player, ServuxHudPacket.ResponseS2CData(buffer));
    }

    @Override
    public <P extends IServerPayloadData> void encodeServerData(ServerPlayer player, P data)
    {
        if (!HudDataProvider.INSTANCE.isEnabled() || !this.checkFailures(player)) { return; }

        ServuxHudPacket packet = (ServuxHudPacket) data;

        // 大包 → PacketSplitter 分片（26.1：重组整体内容为 DataTag 帧，客户端按 DataByteBufUtils 解析）
        if (packet.getType().equals(ServuxHudPacket.Type.PACKET_S2C_NBT_RESPONSE_START))
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData hud → " + player.getName().getString()
                    + " type=" + packet.getType() + " → PacketSplitter 分包");
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            DataTagIo.writeTag(buffer, packet.getCompound());
            PacketSplitter.send(this, buffer, player);
        }
        else if (!this.sendPlayPayload(player, packet))
        {
            // 普通包发送失败 → tickFailures 计数（上游 :169-172 字面；超限由 onPacketFailure 处理，不清零）
            this.tickFailures(player);
        }
    }
}
