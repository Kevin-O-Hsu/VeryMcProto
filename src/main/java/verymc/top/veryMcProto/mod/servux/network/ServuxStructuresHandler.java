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
import verymc.top.veryMcProto.mod.servux.dataproviders.StructureDataProvider;
import verymc.top.veryMcProto.mod.servux.util.nbt.DataTagIo;

/**
 * Structures 通道收发 Handler（mod 层）。移植自原版 {@code ServuxStructuresHandler}（去 Fabric + networkHandler 形参）。
 * 通道 servux:structures，协议版本 {@value ServuxStructuresPacket#PROTOCOL_VERSION}。
 *
 * <p>收（C2S）：{@link #receivePlayPayload} 还原 {@link ServuxStructuresPacket} → {@link #decodeServerData} 分发：
 * <ul>
 *   <li>{@code PACKET_C2S_STRUCTURES_REGISTER/UNREGISTER} → {@link StructureDataProvider} 注册/注销</li>
 * </ul>
 *
 * <p>发（S2C）：{@link #encodeServerData} 普通包走 {@link #sendPlayPayload}（plugin messaging）；
 * 大包（{@code PACKET_S2C_STRUCTURE_DATA_START}）走 {@link PacketSplitter} 分片，每片经
 * {@link #encodeWithSplitter} 包装成 {@code PACKET_S2C_STRUCTURE_DATA} 发送。
 *
 * <p><b>失败计数（上游 tickFailures/checkFailures 语义）</b>：deny 检疫与 S2C 发送失败共用同一份计数；
 * 超限（&gt; maxFailures() = 2）回调 {@link StructureDataProvider#onPacketFailure}（= unregister，
 * 传递性复现上游 :197-199 失败后清零怪癖）。
 */
public class ServuxStructuresHandler implements IPluginServerPlayHandler
{
    private static final ServuxStructuresHandler INSTANCE = new ServuxStructuresHandler();

    public static ServuxStructuresHandler getInstance() { return INSTANCE; }

    public static final Identifier CHANNEL_ID = ServuxReference.CHANNEL_STRUCTURES;

    private boolean payloadRegistered = false;
    private final Map<UUID, Integer> failures = new HashMap<>();

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

    /** 入口闸（上游 checkFailures 字面）：失败计数越限（&gt; maxFailures() = 2）后丢弃该玩家后续包。 */
    @Override
    public boolean checkFailures(ServerPlayer player)
    {
        return !(this.failures.getOrDefault(player.getUUID(), 0) > this.maxFailures());
    }

    /** 失败计数 +1（上游 tickFailures 字面）：超限回调 onPacketFailure（= unregister，含 resetFailures）且计数被其清零。 */
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
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "tickFailures structures → " + player.getName().getString()
                    + " 超过 " + this.maxFailures() + " 次失败，触发 onPacketFailure（注销订阅）");
            StructureDataProvider.INSTANCE.onPacketFailure(player);
        }
        else
        {
            this.failures.put(uuid, this.failures.get(uuid) + 1);
        }
    }

    @Override
    public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player)
    {
        ServuxStructuresPacket packet = ServuxStructuresPacket.fromPacket(data);
        if (packet == null)
        {
            return;
        }
        ServuxDebug.log(ServuxDebug.Cat.PACKET, "C2S structures ← " + player.getName().getString() + " type=" + packet.getType());
        this.decodeServerData(CHANNEL_ID, player, packet);
    }

    @Override
    public <P extends IServerPayloadData> void decodeServerData(Identifier channel, ServerPlayer player, P data)
    {
        ServuxStructuresPacket packet = (ServuxStructuresPacket) data;

        if (!channel.equals(CHANNEL_ID))
        {
            return;
        }

        if (!StructureDataProvider.INSTANCE.isEnabled() || !this.checkFailures(player))
        {
            return;
        }

        switch (packet.getType())
        {
            // 仅 NBT 类型包来自 MiniHUD（Structures 通道不走 PacketSplitter 接收）
            case PACKET_C2S_STRUCTURES_REGISTER ->
            {
                ServuxDebug.log(ServuxDebug.Cat.PACKET, "decodeStructuresPacket(): 收到 Structures Register from " + player.getName().getString());
                // 上游 :87-96 字面：恒先 unregister（出册+resetFailures）再带 tags 注册（版本门禁 + 权限 + 入册 + 全量应答）
                StructureDataProvider.INSTANCE.unregister(player);
                StructureDataProvider.INSTANCE.register(player, packet.getCompound());
            }
            case PACKET_C2S_STRUCTURES_UNREGISTER ->
            {
                ServuxDebug.log(ServuxDebug.Cat.PACKET, "decodeStructuresPacket(): 收到 Structures Un-Register from " + player.getName().getString());
                StructureDataProvider.INSTANCE.unregister(player);
            }
            // 26.1：type 10/11/12（spawn/weather）已从本通道删除——spawn/天气元数据完全收敛到 HUD 通道
            default -> Reference.logger().warning("decodeStructuresPacket(): 无效 packetType " + packet.getPacketType()
                    + " from " + player.getName().getString() + ", size=" + packet.getTotalSize());
        }
    }

    @Override
    public void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buffer)
    {
        // 每片包装成 PACKET_S2C_STRUCTURE_DATA 发送
        this.sendPlayPayload(player, new ServuxStructuresPacket(ServuxStructuresPacket.Type.PACKET_S2C_STRUCTURE_DATA, buffer));
    }

    @Override
    public <P extends IServerPayloadData> void encodeServerData(ServerPlayer player, P data)
    {
        if (!StructureDataProvider.INSTANCE.isEnabled() || !this.checkFailures(player)) { return; }

        ServuxStructuresPacket packet = (ServuxStructuresPacket) data;

        if (packet.getType().equals(ServuxStructuresPacket.Type.PACKET_S2C_STRUCTURE_DATA_START))
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData structures → " + player.getName().getString()
                    + " type=" + packet.getType() + " → PacketSplitter 分包");
            // 大包（26.1：重组整体为 DataTag 帧——本通道仅此一处 DataTag，包帧本身仍 vanilla/裸字节）
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            DataTagIo.writeTag(buffer, packet.getCompound());
            PacketSplitter.send(this, buffer, player);
        }
        else if (!this.sendPlayPayload(player, packet))
        {
            // 发送失败 → tickFailures 计数（超限经 onPacketFailure=unregister 注销订阅并清零计数）
            this.tickFailures(player);
        }
    }
}
