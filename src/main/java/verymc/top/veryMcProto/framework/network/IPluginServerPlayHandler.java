package verymc.top.veryMcProto.framework.network;

import javax.annotation.Nonnull;
import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;

/**
 * 「一条通道的收发逻辑」抽象（框架层）。移植自原版 {@code fi.dy.masa.servux.network.IPluginServerPlayHandler}，
 * 去掉对 Fabric {@code ServerPlayNetworking} 的依赖，改为走 {@link ChannelManager} / {@link ProtocolChannel}
 * （plugin messaging）。
 *
 * <p>每个 Provider 的 Handler 实现本接口；与原版方法名尽量一致，便于移植时照抄 decode/encode 逻辑。
 *
 * <p><b>关键差异（相对原版）</b>：
 * <ul>
 *   <li>去掉 Fabric {@code ServerPlayNetworking.PlayPayloadHandler} 父接口；</li>
 *   <li>{@code receivePlayPayload} 改为接收 {@link FriendlyByteBuf}（已由 ProtocolChannel 从 byte[] 包装）+ ServerPlayer；</li>
 *   <li>{@code encodeWithSplitter} 去掉 networkHandler 形参（方案 A 不走 NMS 发包）；</li>
 *   <li>{@code sendPlayPayload} 内部 toPacket→byte[]→{@link ChannelManager#send}（plugin messaging）。</li>
 * </ul>
 */
public interface IPluginServerPlayHandler
{
    int FROM_SERVER = 1;
    int TO_SERVER = 2;
    int BOTH_SERVER = 3;
    int TO_CLIENT = 4;
    int FROM_CLIENT = 5;
    int BOTH_CLIENT = 6;

    /** 返回该 handler 的通道 ID（网络名，如 servux:hud_metadata）。 */
    Identifier getPayloadChannel();

    /** 该通道是否已注册。 */
    boolean isPlayRegistered(Identifier channel);

    /** 标记该通道已注册。 */
    void setPlayRegistered(Identifier channel);

    /** 全局重置（如服务端关闭时清失败计数 / 缓冲）。 */
    void reset(Identifier channel);

    /**
     * 收到 C2S。ProtocolChannel 把 byte[] 包装成 FriendlyByteBuf 后调此。
     * <p>实现：fromPacket 还原 → {@link #decodeServerData} 分发。
     */
    void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player);

    // ───── 解码分发（mod 实现）─────
    default void decodeNbtCompound(Identifier channel, ServerPlayer player, CompoundTag data) { }
    default <P extends IServerPayloadData> void decodeServerData(Identifier channel, ServerPlayer player, P data) { }

    // ───── 编码发送（mod 调用）─────
    default void encodeNbtCompound(ServerPlayer player, CompoundTag data) { }
    default <P extends IServerPayloadData> void encodeServerData(ServerPlayer player, P data) { }

    /**
     * 分包单片发送回调。{@link PacketSplitter} 切片后逐片调此；实现里把单片包装成 ResponseS2CData 发送。
     */
    void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buf);

    /**
     * 发送普通 S2C 包（plugin messaging，方案 A）。
     *
     * @return 是否成功投递（用于失败计数）。plugin messaging 无 canSend，此处以
     *         {@link ProtocolChannel#send} 的结果（含客户端是否声明监听该通道）为近似信号。
     */
    default <P extends IServerPayloadData> boolean sendPlayPayload(@Nonnull ServerPlayer player, @Nonnull P data)
    {
        if (!isPlayRegistered(getPayloadChannel()))
        {
            Reference.logger().warning("sendPlayPayload: 通道未注册 " + getPayloadChannel());
            return false;
        }
        Objects.requireNonNull(player, "player");
        byte[] bytes = FriendlyByteBufs.encodePayload(data);
        return ChannelManager.instance().send(getPayloadChannel(), player.getBukkitEntity(), bytes);
    }

    /**
     * 发送裸字节（分包单片用，{@link #encodeWithSplitter} 内部调此）。
     */
    default boolean sendPlayPayload(@Nonnull ServerPlayer player, @Nonnull FriendlyByteBuf buf)
    {
        if (!isPlayRegistered(getPayloadChannel()))
        {
            return false;
        }
        byte[] bytes = FriendlyByteBufs.readableBytes(buf);
        return ChannelManager.instance().send(getPayloadChannel(), player.getBukkitEntity(), bytes);
    }
}
