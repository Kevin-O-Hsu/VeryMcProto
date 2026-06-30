package verymc.top.veryMcProto.mod.servux.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.dataproviders.EntitiesDataProvider;

/**
 * Entities 通道收发 Handler（mod 层）。移植自原版 {@code ServuxEntitiesHandler}（去 Fabric + networkHandler 形参）。
 * 通道 servux:entity_data，协议版本 1。收 C2S（metadata / block entity / entity 请求）→ 分发到
 * {@link EntitiesDataProvider}；发 S2C 响应（plugin messaging，大包走 PacketSplitter）。
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
        this.decodeServerData(CHANNEL_ID, player, packet);
    }

    @Override
    public <P extends IServerPayloadData> void decodeServerData(Identifier channel, ServerPlayer player, P data)
    {
        ServuxEntitiesPacket packet = (ServuxEntitiesPacket) data;
        if (!channel.equals(CHANNEL_ID)) { return; }

        switch (packet.getType())
        {
            case PACKET_C2S_METADATA_REQUEST -> EntitiesDataProvider.INSTANCE.sendMetadata(player);
            case PACKET_C2S_BLOCK_ENTITY_REQUEST -> EntitiesDataProvider.INSTANCE.onBlockEntityRequest(player, packet.getPos());
            case PACKET_C2S_ENTITY_REQUEST -> EntitiesDataProvider.INSTANCE.onEntityRequest(player, packet.getEntityId());
            default -> Reference.logger().warning("ServuxEntitiesHandler#decodeServerData: 无效 packetType " + packet.getPacketType()
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

        if (packet.getType().equals(ServuxEntitiesPacket.Type.PACKET_S2C_NBT_RESPONSE_START))
        {
            // 大包：VarInt transactionId + NBT，走 PacketSplitter
            var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(packet.getTransactionId());
            buf.writeNbt(packet.getCompound());
            verymc.top.veryMcProto.framework.network.PacketSplitter.send(this, buf, player);
        }
        else if (!this.sendPlayPayload(player, packet))
        {
            UUID id = player.getUUID();
            int count = this.failures.getOrDefault(id, 0) + 1;
            if (count >= MAX_FAILURES) { this.failures.remove(id); EntitiesDataProvider.INSTANCE.onPacketFailure(player); }
            else { this.failures.put(id, count); }
        }
    }
}
