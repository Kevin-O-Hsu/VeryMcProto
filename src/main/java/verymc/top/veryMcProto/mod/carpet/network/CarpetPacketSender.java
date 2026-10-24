package verymc.top.veryMcProto.mod.carpet.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.framework.network.FriendlyByteBufs;
import verymc.top.veryMcProto.mod.carpet.CarpetReference;

/**
 * carpet:hello S2C 直发器（mod 层，carpet:hello 协议）。构造 {@code new ClientboundCustomPayloadPacket(
 * new DiscardedPayload(CARPET_CHANNEL, bytes))} 经 {@code ServerPlayer.connection.send} 投递——
 * 与 Paper 自身放行路径逐字同构（黄金模板 {@code JeiPacketSender.send} 同款）。
 *
 * <p><b>为何全部 S2C 走 NMS 直发（不走 {@code ChannelManager.send} / plugin messaging）</b>：
 * carpet 客户端不经 Fabric networking API 注册 {@code carpet:hello}（上游 codec 由
 * {@code CustomPacketPayload_networkStuffMixin} 直注 vanilla codec 清单，全树零
 * {@code PayloadTypeRegistry}）——客户端从不发 {@code minecraft:register} 声明该通道，
 * Paper 的声明门控（{@code CraftPlayer.sendPluginMessage} 按 {@code channels().contains} 静默丢弃）
 * 使 plugin messaging S2C 恒不可达；且进服首帧 HI 先于任何 C2S，「同通道 C2S 证明兜底」结构性
 * 不可用。NMS 直发无此门控，客户端按通道 id 查其 codec 清单解出 {@code CarpetPayload}（= readNbt），
 * 与上游 Fabric 服务端发的 wire 逐字节一致（上游 {@code ServerNetworkHandler} 各发送点即
 * {@code CarpetPayload} 的 STREAM_CODEC = writeNbt）。
 *
 * <p>帧尺寸恒 KB 级（11 规则全量 < 10KB），零分片需求，不涉 {@code PacketSplitter}。
 */
public final class CarpetPacketSender
{
    private CarpetPacketSender() { }

    /** 编码单 CompoundTag（vanilla {@code writeNbt}——上游 CarpetPayload.write 同式）并 NMS 直发。 */
    public static void send(ServerPlayer player, CompoundTag tag)
    {
        var buf = FriendlyByteBufs.buffer();
        byte[] bytes;
        try
        {
            buf.writeNbt(tag);
            bytes = FriendlyByteBufs.readableBytes(buf);
        }
        finally
        {
            buf.release();
        }
        player.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(CarpetReference.CARPET_CHANNEL, bytes)));
    }
}
