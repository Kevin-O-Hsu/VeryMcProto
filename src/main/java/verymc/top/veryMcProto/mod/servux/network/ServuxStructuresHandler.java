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
 * 通道 servux:structures，协议版本 2。
 *
 * <p>收（C2S）：{@link #receivePlayPayload} 还原 {@link ServuxStructuresPacket} → {@link #decodeServerData} 分发：
 * <ul>
 *   <li>{@code PACKET_C2S_STRUCTURES_REGISTER/UNREGISTER} → {@link StructureDataProvider} 注册/注销；</li>
 *   <li>{@code PACKET_C2S_REQUEST_SPAWN_METADATA} → 跨通道转发 {@link HudDataProvider#refreshSpawnMetadata}
 *       （原版如此：spawn/天气元数据由 HUD provider 生成，但客户端可能经 structures 通道请求）。</li>
 * </ul>
 *
 * <p>发（S2C）：{@link #encodeServerData} 普通包走 {@link #sendPlayPayload}（plugin messaging）；
 * 大包（{@code PACKET_S2C_STRUCTURE_DATA_START}）走 {@link PacketSplitter} 分片，每片经
 * {@link #encodeWithSplitter} 包装成 {@code PACKET_S2C_STRUCTURE_DATA} 发送。
 *
 * <p><b>失败重试</b>：{@link #sendPlayPayload} 返回 false（客户端未声明监听该通道 = 未装 MiniHUD）
 * 累计 {@value #MAX_FAILURES} 次后注销该玩家结构订阅。
 */
public class ServuxStructuresHandler implements IPluginServerPlayHandler
{
    private static final ServuxStructuresHandler INSTANCE = new ServuxStructuresHandler();

    public static ServuxStructuresHandler getInstance() { return INSTANCE; }

    public static final Identifier CHANNEL_ID = ServuxReference.CHANNEL_STRUCTURES;

    private boolean payloadRegistered = false;
    private final Map<UUID, Integer> failures = new HashMap<>();
    private static final int MAX_FAILURES = 4;

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

        switch (packet.getType())
        {
            // 仅 NBT 类型包来自 MiniHUD（Structures 通道不走 PacketSplitter 接收）
            case PACKET_C2S_STRUCTURES_REGISTER ->
            {
                ServuxDebug.log(ServuxDebug.Cat.PACKET, "decodeStructuresPacket(): 收到 Structures Register from " + player.getName().getString());
                StructureDataProvider.INSTANCE.unregister(player);
                StructureDataProvider.INSTANCE.register(player);
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
        if (!StructureDataProvider.INSTANCE.isEnabled()) { return; }

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
            // 普通包发送失败 → 计数（第 MAX_FAILURES 次触发注销并清零）
            UUID id = player.getUUID();
            int count = this.failures.getOrDefault(id, 0) + 1;

            if (count >= MAX_FAILURES)
            {
                this.failures.remove(id);
                ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData structures → " + player.getName().getString()
                        + " 连续 " + MAX_FAILURES + " 次发送失败，注销该玩家结构订阅（可能未装 MiniHUD）");

                StructureDataProvider.INSTANCE.unregister(player);
            }
            else
            {
                this.failures.put(id, count);
            }
        }
    }
}
