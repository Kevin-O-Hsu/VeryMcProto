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
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.framework.network.PacketSplitter;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;

/**
 * Litematics 通道收发 Handler（mod 层）。移植自原版 {@code ServuxLitematicaHandler}（去 Fabric + networkHandler 形参）。
 *
 * <p>通道 servux:litematics，协议版本 1。收 C2S（metadata / block entity / entity / 批量 / 投影粘贴分片）
 * → 分发到 {@link LitematicsDataProvider}；发 S2C 响应（plugin messaging，大包走 PacketSplitter）。
 *
 * <p><b>投影粘贴</b>：客户端上传的投影 NBT（{@code PACKET_C2S_NBT_RESPONSE_DATA} 分片）走 PacketSplitter.receive 重组，
 * 组装完成后无条件走 LitematicsDataProvider.handleClientPasteRequest 直接粘贴（上游 0.9.5 同构）。
 * <b>C2S 文件接收链（Litematic-Transmit* → receiveFileTransmit 落盘）已于 2026-10 随安全修复整链移除</b>：
 * 客户端可控 FileName 直达 Path.of/dir.resolve 无包含性检查，构成路径穿越任意写/删/读回原语（上游公告漏洞同源）；
 * 修复版 stock 客户端亦不再发送上传帧。恢复走 git revert 该修复 commit。
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

    /** 玩家退出：丢弃其进行中的分片上传（会话键 + 重组会话 + buffer 一起清，防 TTL 窗口内重进复用键命中僵尸会话）。 */
    public void onPlayerQuit(UUID uuid)
    {
        Long key = this.readingSessionKeys.remove(uuid);

        if (key != null)
        {
            PacketSplitter.discardSession(key);
        }
    }

    @Override
    public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player)
    {
        ServuxLitematicaPacket packet = ServuxLitematicaPacket.fromPacket(data);
        if (packet == null) { return; }
        ServuxDebug.log(ServuxDebug.Cat.PACKET, "C2S litematics ← " + player.getName().getString() + " type=" + packet.getType());
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

                ServuxDebug.log(ServuxDebug.Cat.PACKET, "decodeServerData litematics: 收到投影分片 size=" + packet.getTotalSize() + " key=" + readingSessionKey);

                try
                {
                    FriendlyByteBuf fullPacket = PacketSplitter.receive(this, readingSessionKey, packet.getBuffer());

                    if (fullPacket != null)
                    {
                        ServuxDebug.log(ServuxDebug.Cat.PACKET, "decodeServerData litematics: 投影完整包 size=" + fullPacket.readableBytes() + " key=" + readingSessionKey);
                        try
                        {
                            this.readingSessionKeys.remove(uuid);
                            // 上游 0.9.5 ServuxLitematicaHandler:139-153 同构：重组完成无条件走 paste 受理
                            //（Task 门控在 Provider：非 LitematicaPaste 静默忽略）；Transmit* 接收链已随安全修复移除
                            LitematicsDataProvider.INSTANCE.handleClientPasteRequest(player, fullPacket.readVarInt(), (CompoundTag) fullPacket.readNbt(NbtAccounter.unlimitedHeap()));
                        }
                        catch (Exception e)
                        {
                            Reference.logger().warning("ServuxLitematicaHandler#decodeServerData: 投影完整包解析失败: " + e.getMessage());
                        }
                    }
                }
                catch (IllegalArgumentException | NullPointerException e)
                {
                    // 上游 packet/ServuxLitematicaHandler.java:162-170 同构：分片坏流终态——清键让下次上传换新会话键
                    //（僵尸会话本体由 receive 异常路径移除 / TTL 驱逐兜底）。注意残余分片会逐片「新建会话→坏头→再抛」，
                    // 每片一条 warning——日志放大上界=同帧残余分片数，可接受（malilib 发送为同步突发，残余窗口极小）。
                    Reference.logger().warning("ServuxLitematicaHandler#decodeServerData: PacketSplitter 分片异常，废弃会话 key=" + readingSessionKey + ": " + e.getMessage());
                    this.readingSessionKeys.remove(uuid);
                }
            }
            default -> Reference.logger().warning("ServuxLitematicaHandler#decodeServerData: 无效 packetType " + packet.getPacketType()
                    + " from " + player.getName().getString() + ", size=" + packet.getTotalSize());
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
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData litematics → " + player.getName().getString()
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
                ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData litematics → " + player.getName().getString()
                        + " 连续 " + MAX_FAILURES + " 次发送失败，触发 onPacketFailure（可能未安装 Litematica）");
                LitematicsDataProvider.INSTANCE.onPacketFailure(player);
            }
            else { this.failures.put(id, count); }
        }
    }
}
