package verymc.top.veryMcProto.mod.servux.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
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
import verymc.top.veryMcProto.mod.servux.util.nbt.DataTagIo;

/**
 * Litematics 通道收发 Handler（mod 层）。移植自原版 {@code ServuxLitematicaHandler}（去 Fabric + networkHandler 形参）。
 *
 * <p>通道 servux:litematics，协议版本 {@value ServuxLitematicaPacket#PROTOCOL_VERSION}。收 C2S（metadata / block entity / entity / 批量 / 投影粘贴分片）
 * → 分发到 {@link LitematicsDataProvider}；发 S2C 响应（plugin messaging，大包走 PacketSplitter）。
 *
 * <p><b>投影粘贴</b>：客户端上传的投影 NBT（{@code PACKET_C2S_NBT_RESPONSE_DATA} 分片）走 PacketSplitter.receive 重组，
 * 组装完成后无条件走 LitematicsDataProvider.handleClientPasteRequest 任务化受理（PasteTask 分 tick 粘贴，上游 0.10.7 同构）。
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

    /** 失败计数 +1（上游 tickFailures 字面）：超限回调 onPacketFailure 且不清零。 */
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
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "tickFailures litematics → " + player.getName().getString()
                    + " 超过 " + this.maxFailures() + " 次失败，触发 onPacketFailure");
            LitematicsDataProvider.INSTANCE.onPacketFailure(player);
        }
        else
        {
            this.failures.put(uuid, this.failures.get(uuid) + 1);
        }
    }

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

        if (!LitematicsDataProvider.INSTANCE.isEnabled() || !this.checkFailures(player)) { return; }

        switch (packet.getType())
        {
            case PACKET_C2S_METADATA_REQUEST ->
            {
                if (LitematicsDataProvider.INSTANCE.isPlayerRegistered(player))
                {
                    LitematicsDataProvider.INSTANCE.unregister(player);
                }
                LitematicsDataProvider.INSTANCE.register(player, packet.getCompound());
            }
            case PACKET_C2S_UNREGISTER_REPLY -> LitematicsDataProvider.INSTANCE.unregister(player);
            case PACKET_C2S_BLOCK_ENTITY_REQUEST -> LitematicsDataProvider.INSTANCE.onBlockEntityRequest(player, packet.getPos());
            case PACKET_C2S_ENTITY_REQUEST -> LitematicsDataProvider.INSTANCE.onEntityRequest(player, packet.getEntityId());
            case PACKET_C2S_BULK_ENTITY_NBT_REQUEST -> LitematicsDataProvider.INSTANCE.onBulkEntityRequest(player, packet.getChunkPos(), packet.getCompound());
            case PACKET_C2S_TASK_REQUEST -> LitematicsDataProvider.INSTANCE.onTaskRequest(player, packet.getCompound());
            case PACKET_S2C_TASK_RESPONSE, PACKET_S2C_TASK_STATUS_SYNC, PACKET_C2S_TASK_CANCEL ->
            {
                // 上游同源忽略：type 15 客户端接收端被 TODO 注释（服务端无发送场景）；type 16 为 S2C 下行，
                // 服务端收到即异常方向；type 17 上游 handler 分支亦注释（客户端 sendServuxTaskCancel 整体注释）。
                Reference.logger().warning("ServuxLitematicaHandler#decodeServerData: 收到非预期 task 包 type="
                        + packet.getPacketType() + " from " + player.getName().getString() + "（上游同源忽略）");
            }
            case PACKET_C2S_NBT_RESPONSE_DATA ->
            {
                // 上游 :116-119 字面：分片回执（上传体）入口的名册门——未注册（含被版本门禁拒绝）玩家不分片重组
                if (!LitematicsDataProvider.INSTANCE.isPlayerRegistered(player))
                {
                    return;
                }

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
                            // 26.1：重组整体为 DataTag 帧，且无 type VarInt 前缀——按 NBT "Task" 字符串路由
                            CompoundTag nbt = DataTagIo.readTag(fullPacket);

                            if (nbt != null)
                            {
                                ServuxDebug.log(ServuxDebug.Cat.PACKET, "decodeServerData litematics: DataTag 解析成功 keys=" + nbt.keySet()
                                        + " Task=" + nbt.getStringOr("Task", "(无)"));
                                // 上游 0.10.7 ServuxLitematicaHandler:201 同构：重组完成无条件走 paste 受理
                                //（Task 门控在 Provider：非 LitematicaPaste 静默忽略）；Transmit* 接收链已随安全修复移除
                                LitematicsDataProvider.INSTANCE.handleClientPasteRequest(player, nbt);
                            }
                            else
                            {
                                Reference.logger().warning("ServuxLitematicaHandler#decodeServerData: 投影完整包 DataTag 解析返回 null（size=" + fullPacket.readableBytes()
                                        + " key=" + readingSessionKey + "——长度/解压/NBT 失败，详见上方 DataTagIo 告警）");
                            }
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
                    //（僵尸会话本体由 receive 异常路径移除 / TTL 驱逐兜底）
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
        if (!LitematicsDataProvider.INSTANCE.isEnabled() || !this.checkFailures(player)) { return; }

        ServuxLitematicaPacket packet = (ServuxLitematicaPacket) data;

        if (packet.getType().equals(ServuxLitematicaPacket.Type.PACKET_S2C_NBT_RESPONSE_START) || packet.getType().equals(ServuxLitematicaPacket.Type.PACKET_C2S_NBT_RESPONSE_START))
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData litematics → " + player.getName().getString()
                    + " type=" + packet.getType() + " → PacketSplitter 分包");
            // 大包（26.1：重组整体为 DataTag 帧，无 transactionId 前缀）
            var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            DataTagIo.writeTag(buf, packet.getCompound());
            PacketSplitter.send(this, buf, player);
        }
        else if (!this.sendPlayPayload(player, packet))
        {
            // 发送失败 → tickFailures 计数（上游字面；超限由 onPacketFailure 处理，不清零）
            this.tickFailures(player);
        }
    }
}
