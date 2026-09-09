package verymc.top.veryMcProto.mod.jei.network;

import java.util.function.Consumer;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * JEI 模块 S2C 直发器（mod 层）。构造 {@code new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))}
 * 经 {@code ServerPlayer.connection.send} 投递——与 Paper 自身放行路径逐字同构。
 *
 * <p><b>为何不走 {@code ChannelManager.send}</b>：其一，配方包常超 1MiB，{@code ProtocolChannel.send}
 * 对超 {@code Messenger.MAX_MESSAGE_SIZE} 的包硬拒；其二，jei:* S2C 通道客户端永不发 C2S，
 * 「同通道 C2S 证明兜底」不可用，而 Paper 对未声明通道的 {@code sendPluginMessage} 会静默丢弃——
 * NMS 直发无此门控。生产先例：{@code ExchangeTarget.sendViaNms} / 原 {@code RecipeSyncHandler.sendPayload}。
 *
 * <p><b>尺寸模型（docs/30 详述）</b>：32767 上限仅适用于客户端<b>未知通道</b>的 discarded 解码；
 * {@code fabric:recipe_sync} 是 Fabric API 客户端已注册 codec 的已知通道（上游注册上限 64MB），
 * 单包大 payload 安全（26.1.2 实机验证）。jei:* S2C 恒小包。
 */
public final class JeiPacketSender
{
    private JeiPacketSender() { }

    /** 编码 + 直发。buffer 生命周期本方法内闭环（finally release）。 */
    public static void send(ServerPlayer player, Identifier channel, Consumer<RegistryFriendlyByteBuf> encoder)
    {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try
        {
            encoder.accept(buf);
            byte[] bytes = new byte[buf.writerIndex()];
            buf.getBytes(0, bytes);
            send(player, channel, bytes);
        }
        finally
        {
            buf.release();
        }
    }

    /** 裸字节直发（调用方已编码完毕——配方同步等大包路径，避免二次拷贝）。 */
    public static void send(ServerPlayer player, Identifier channel, byte[] bytes)
    {
        player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(channel, bytes)));
    }

    /** 发送一个 S2C 包对象（通道 + 编码来自包自身）。 */
    public static void sendPacketToClient(ServerPlayer player, JeiS2CPacket packet)
    {
        send(player, packet.channelId(), packet::encode);
    }
}
