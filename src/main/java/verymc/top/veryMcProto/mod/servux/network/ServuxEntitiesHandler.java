package verymc.top.veryMcProto.mod.servux.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.dataproviders.EntitiesDataProvider;

/**
 * Entities 通道收发 Handler（mod 层）。移植自原版 {@code ServuxEntitiesHandler}（去 Fabric + networkHandler 形参），
 * 对齐上游 servux 0.9.9（v2 世代 wire）。通道 servux:entity_data，协议版本 2。收 C2S（metadata /
 * block entity / entity 请求 / 注销回执）→ 分发到 {@link EntitiesDataProvider}；发 S2C 响应（plugin messaging）。
 * 上游 0.9.9 已无 entities 侧 PacketSplitter START 发送路径（bulk S2C 死路），本 Handler 同步移除该分支。
 */
public class ServuxEntitiesHandler implements IPluginServerPlayHandler
{
    private static final ServuxEntitiesHandler INSTANCE = new ServuxEntitiesHandler();
    public static ServuxEntitiesHandler getInstance() { return INSTANCE; }

    public static final Identifier CHANNEL_ID = ServuxReference.CHANNEL_ENTITIES;

    private boolean payloadRegistered = false;
    private final Map<UUID, Integer> failures = new HashMap<>();
    private static final int MAX_FAILURES = 4;
    private final Map<UUID, Long> readingSessionKeys = new HashMap<>();

    public Map<UUID, Long> getReadingSessionKeys() { return this.readingSessionKeys; }

    @Override public Identifier getPayloadChannel() { return CHANNEL_ID; }

    @Override
    public boolean isPlayRegistered(Identifier channel) { return channel.equals(CHANNEL_ID) && this.payloadRegistered; }

    @Override
    public void setPlayRegistered(Identifier channel) { if (channel.equals(CHANNEL_ID)) { this.payloadRegistered = true; } }

    @Override
    public void clearPlayRegistered(Identifier channel) { if (channel.equals(CHANNEL_ID)) { this.payloadRegistered = false; } }

    @Override
    public void reset(Identifier channel) { if (channel.equals(CHANNEL_ID)) { this.failures.clear(); } }

    @Override
    public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player)
    {
        ServuxEntitiesPacket packet = ServuxEntitiesPacket.fromPacket(data);
        if (packet == null) { return; }
        ServuxDebug.log(ServuxDebug.Cat.PACKET, "C2S entity <- " + player.getName().getString() + " type=" + packet.getType());
        this.decodeServerData(CHANNEL_ID, player, packet);
    }

    @Override
    public <P extends IServerPayloadData> void decodeServerData(Identifier channel, ServerPlayer player, P data)
    {
        ServuxEntitiesPacket packet = (ServuxEntitiesPacket) data;
        if (!channel.equals(CHANNEL_ID)) { return; }

        // 观测行：v3 世代 version 为 int；v2 世代为 string（不受支持，静默降级留痕）
        if (packet.getType() == ServuxEntitiesPacket.Type.PACKET_C2S_METADATA_REQUEST)
        {
            int declared = packet.getCompound().getIntOr("version", -1);
            String genInfo = declared != -1
                    ? String.valueOf(declared)
                    : packet.getCompound().getStringOr("version", "absent") + " (v2 generation, unsupported)";
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "entity METADATA_REQUEST <- " + player.getName().getString() + " declaredVersion=" + genInfo);
        }

        switch (packet.getType())
        {
            case PACKET_C2S_METADATA_REQUEST -> EntitiesDataProvider.INSTANCE.register(player); // 上游同构：请求即注册（入册+推送）
            case PACKET_C2S_BLOCK_ENTITY_REQUEST -> EntitiesDataProvider.INSTANCE.onBlockEntityRequest(player, packet.getPos());
            case PACKET_C2S_ENTITY_REQUEST -> EntitiesDataProvider.INSTANCE.onEntityRequest(player, packet.getEntityId());
            case PACKET_C2S_UNREGISTER_REPLY -> EntitiesDataProvider.INSTANCE.removePlayer(player); // v2：客户端失败路径注销回执
            default -> Reference.logger().warning("ServuxEntitiesHandler#decodeServerData: invalid packetType " + packet.getPacketType()
                    + " from " + player.getName().getString() + ", size=" + packet.getTotalSize());
        }
    }

    @Override
    public void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buffer)
    {
        this.sendPlayPayload(player, ServuxEntitiesPacket.ResponseS2CData(buffer));
    }

    @Override
    public <P extends IServerPayloadData> void encodeServerData(ServerPlayer player, P data)
    {
        if (!EntitiesDataProvider.INSTANCE.isEnabled()) { return; }

        ServuxEntitiesPacket packet = (ServuxEntitiesPacket) data;

        // 对齐上游 0.9.9：entities 侧无 PacketSplitter START 发送路径（bulk S2C 上游已死路，
        // ResponseS2CStart/DATA 不再由服务端构造），全部包单帧走 sendPlayPayload
        if (!this.sendPlayPayload(player, packet))
        {
            UUID id = player.getUUID();
            int count = this.failures.getOrDefault(id, 0) + 1;
            if (count >= MAX_FAILURES)
            {
                this.failures.remove(id);
                ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData entity -> " + player.getName().getString()
                        + " exceeded " + MAX_FAILURES + " consecutive send failures, triggering onPacketFailure");
                EntitiesDataProvider.INSTANCE.onPacketFailure(player);
            }
            else { this.failures.put(id, count); }
        }
    }
}
