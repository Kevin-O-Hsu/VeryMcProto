package verymc.top.veryMcProto.mod.servux.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.debug.Debug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.framework.network.PacketSplitter;
import verymc.top.veryMcProto.mod.servux.ServuxLog;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;
import verymc.top.veryMcProto.mod.servux.schematic.LitematicaSchematic;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Litematics 通道收发 Handler（mod 层）。移植自原版 {@code ServuxLitematicaHandler}（去 Fabric + networkHandler 形参）。
 *
 * <p>通道 servux:litematics，协议版本 1。收 C2S（metadata / block entity / entity / 批量 / 投影投递分片）
 * → 分发到 {@link LitematicsDataProvider}；发 S2C 响应（plugin messaging，大包走 PacketSplitter）。
 *
 * <p><b>降级</b>：客户端上传投影 NBT（{@code PACKET_C2S_NBT_RESPONSE_DATA} 分片）走 PacketSplitter.receive 重组，
 * 但组装完成后【不】加载为 LitematicaSchematic（schematic 系统未移植），仅 ServuxLog.debug 记录已降级忽略。
 */
public class ServuxLitematicaHandler implements IPluginServerPlayHandler
{
    private static final ServuxLitematicaHandler INSTANCE = new ServuxLitematicaHandler();
    public static ServuxLitematicaHandler getInstance() { return INSTANCE; }

    public static final Identifier CHANNEL_ID = ServuxReference.CHANNEL_LITEMATICS;

    private boolean payloadRegistered = false;
    private final Map<UUID, Integer> failures = new HashMap<>();
    private static final int MAX_FAILURES = 4;
    private final Map<UUID, Long> readingSessionKeys = new HashMap<>();

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
        ServuxLitematicaPacket packet = ServuxLitematicaPacket.fromPacket(data);
        if (packet == null) { return; }
        Debug.log(Debug.Cat.PACKET, "C2S litematics ← " + player.getName().getString() + " type=" + packet.getType());
        this.decodeServerData(CHANNEL_ID, player, packet);
    }

    @Override
    public <P extends IServerPayloadData> void decodeServerData(Identifier channel, ServerPlayer player, P data)
    {
        ServuxLitematicaPacket packet = (ServuxLitematicaPacket) data;
        if (!channel.equals(CHANNEL_ID)) { return; }

        switch (packet.getType())
        {
            case PACKET_C2S_METADATA_REQUEST -> LitematicsDataProvider.INSTANCE.sendMetadata(player);
            case PACKET_C2S_BLOCK_ENTITY_REQUEST -> LitematicsDataProvider.INSTANCE.onBlockEntityRequest(player, packet.getPos());
            case PACKET_C2S_ENTITY_REQUEST -> LitematicsDataProvider.INSTANCE.onEntityRequest(player, packet.getEntityId());
            case PACKET_C2S_BULK_ENTITY_NBT_REQUEST -> LitematicsDataProvider.INSTANCE.onBulkEntityRequest(player, packet.getChunkPos(), packet.getCompound());
            case PACKET_C2S_NBT_RESPONSE_DATA ->
            {
                UUID uuid = player.getUUID();
                long readingSessionKey;

                if (!this.readingSessionKeys.containsKey(uuid))
                {
                    readingSessionKey = RandomSource.create(Util.getMillis()).nextLong();
                    this.readingSessionKeys.put(uuid, readingSessionKey);
                }
                else
                {
                    readingSessionKey = this.readingSessionKeys.get(uuid);
                }

                Debug.log(Debug.Cat.PACKET, "decodeServerData litematics: 收到投影分片 size=" + packet.getTotalSize() + " key=" + readingSessionKey);

                FriendlyByteBuf fullPacket = PacketSplitter.receive(this, readingSessionKey, packet.getBuffer());

                if (fullPacket != null)
                {
                    Debug.log(Debug.Cat.PACKET, "decodeServerData litematics: 投影完整包 size=" + fullPacket.readableBytes() + " key=" + readingSessionKey);
                    try
                    {
                        this.readingSessionKeys.remove(uuid);
                        this.handleBulkData(player, fullPacket.readVarInt(), (CompoundTag) fullPacket.readNbt(NbtAccounter.unlimitedHeap()));
                    }
                    catch (Exception e)
                    {
                        Reference.logger().warning("ServuxLitematicaHandler#decodeServerData: 投影完整包解析失败: " + e.getMessage());
                    }
                }
            }
            default -> Reference.logger().warning("ServuxLitematicaHandler#decodeServerData: 无效 packetType " + packet.getPacketType()
                    + " from " + player.getName().getString() + ", size=" + packet.getTotalSize());
        }
    }

    /**
     * 降级处理：客户端上传的投影 NBT 重组完成后的去向。
     *
     * <p>原版分支：TransmitStart/Data/End/Cancel 走 {@code LitematicaSchematic.receiveFileTransmit}，普通粘贴走
     * {@code handleClientPasteRequest}（加载投影 → placement.pasteTo）。<b>schematic 系统未移植</b>，故全部降级：
     * 仅记录日志，不加载、不粘贴。
     */
    private void handleBulkData(ServerPlayer player, final int type, CompoundTag nbt)
    {
        String task = nbt != null ? nbt.getStringOr("Task", "LitematicaPaste") : "LitematicaPaste";

        switch (task)
        {
            // File-Transmit support（客户端上传投影文件）
            case "Litematic-TransmitStart", "Litematic-TransmitCancel", "Litematic-TransmitData", "Litematic-TransmitEnd" ->
            {
                Pair<LitematicaSchematic, CompoundTag> schemPair = LitematicaSchematic.receiveFileTransmit(nbt, player);

                if (schemPair != null && schemPair.getLeft().getFile() != null)
                {
                    Debug.log(Debug.Cat.PACKET, "handleBulkData(): 收到 litematic " + schemPair.getLeft().getFile().toAbsolutePath().toString() + " from " + player.getName().getString());
                    LitematicsDataProvider.INSTANCE.handleClientPasteRequestPair(player, type, schemPair);
                }
            }
            default -> LitematicsDataProvider.INSTANCE.handleClientPasteRequest(player, type, nbt);
        }
    }

    @Override
    public void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buffer)
    {
        this.sendPlayPayload(player, ServuxLitematicaPacket.ResponseS2CData(buffer));
    }

    @Override
    public <P extends IServerPayloadData> void encodeServerData(ServerPlayer player, P data)
    {
        if (!LitematicsDataProvider.INSTANCE.isEnabled()) { return; }

        ServuxLitematicaPacket packet = (ServuxLitematicaPacket) data;

        if (packet.getType().equals(ServuxLitematicaPacket.Type.PACKET_S2C_NBT_RESPONSE_START) || packet.getType().equals(ServuxLitematicaPacket.Type.PACKET_C2S_NBT_RESPONSE_START))
        {
            Debug.log(Debug.Cat.PACKET, "encodeServerData litematics → " + player.getName().getString()
                    + " type=" + packet.getType() + " → PacketSplitter 分包");
            // 大包：VarInt transactionId + NBT，走 PacketSplitter
            var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(packet.getTransactionId());
            buf.writeNbt(packet.getCompound());
            PacketSplitter.send(this, buf, player);
        }
        else if (!this.sendPlayPayload(player, packet))
        {
            UUID id = player.getUUID();
            int count = this.failures.getOrDefault(id, 0) + 1;
            if (count >= MAX_FAILURES)
            {
                this.failures.remove(id);
                Debug.log(Debug.Cat.PACKET, "encodeServerData litematics → " + player.getName().getString()
                        + " 连续 " + MAX_FAILURES + " 次发送失败，触发 onPacketFailure（可能未安装 Litematica）");
                LitematicsDataProvider.INSTANCE.onPacketFailure(player);
            }
            else { this.failures.put(id, count); }
        }
    }
}
