package verymc.top.veryMcProto.mod.servux.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.framework.network.PacketSplitter;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.dataproviders.HudDataProvider;

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
 * <p><b>失败重试</b>：{@link #sendPlayPayload} 返回 false（客户端未声明监听该通道 = 未装 MiniHUD 等）
 * 累计 {@value #MAX_FAILURES} 次后调 {@link HudDataProvider#onPacketFailure} 标记 invalid（不刷屏）。
 */
public class ServuxHudHandler implements IPluginServerPlayHandler
{
    private static final ServuxHudHandler INSTANCE = new ServuxHudHandler();

    public static ServuxHudHandler getInstance() { return INSTANCE; }

    public static final Identifier CHANNEL_ID = ServuxReference.CHANNEL_HUD;

    private boolean payloadRegistered = false;
    private final Map<UUID, Integer> failures = new HashMap<>();
    private static final int MAX_FAILURES = 4;
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

    @Override
    public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player)
    {
        ServuxHudPacket packet = ServuxHudPacket.fromPacket(data);
        if (packet == null)
        {
            return;
        }
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

        switch (packet.getType())
        {
            case PACKET_C2S_METADATA_REQUEST -> HudDataProvider.INSTANCE.sendMetadata(player);
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
        if (!HudDataProvider.INSTANCE.isEnabled()) { return; }

        ServuxHudPacket packet = (ServuxHudPacket) data;

        // 大包 → PacketSplitter 分片
        if (packet.getType().equals(ServuxHudPacket.Type.PACKET_S2C_NBT_RESPONSE_START))
        {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            buffer.writeNbt(packet.getCompound());
            PacketSplitter.send(this, buffer, player);
        }
        else if (!this.sendPlayPayload(player, packet))
        {
            // 普通包发送失败 → 计数（第 MAX_FAILURES 次触发 onPacketFailure 并清零，避免重复触发 + 内存泄漏）
            UUID id = player.getUUID();
            int count = this.failures.getOrDefault(id, 0) + 1;

            if (count >= MAX_FAILURES)
            {
                this.failures.remove(id);
                HudDataProvider.INSTANCE.onPacketFailure(player);
            }
            else
            {
                this.failures.put(id, count);
            }
        }
    }
}
