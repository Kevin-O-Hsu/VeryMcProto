package verymc.top.veryMcProto.framework.network;

import javax.annotation.Nonnull;
import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;

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

    /**
     * 标记该通道已注销（unregister 时由框架 {@code ServerPlayHandler} 调用）。
     * default 空实现：未覆写的 handler 降级为「不清标志」（功能安全——disabled 时 encodeServerData
     * 会提前 return 不发送；re-register 时 setPlayRegistered 覆盖）。覆写后使 disable/enable 循环状态完全干净。
     */
    default void clearPlayRegistered(Identifier channel) { }

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
     * <p>Paper 的 plugin messaging 通道由 {@code registerOutgoingPluginChannel} 注册，Paper 内部以
     * {@code DiscardedPayload} codec 投递 {@code byte[]}（= {@link FriendlyByteBufs#encodePayload} 的 toPacket
     * 裸字节）。masa 客户端为 {@code servux:*} 注册了 {@code Payload.CODEC}，可正确解码。
     *
     * <p><b>禁用 NMS 直发</b>：曾尝试 {@code player.connection.send(new ClientboundCustomPayloadPacket(payload))}，
     * 但 Paper 已把 {@code servux:*} 的 payload codec 注册为 {@code DiscardedPayload}，自定义 Payload 编码时
     * 强转 DiscardedPayload 失败（ClassCastException → 踢玩家，实测）。覆盖该注册需反射改写 payload registry，
     * 暂不采用。故 S2C 统一走 plugin messaging。
     *
     * @return 是否投递（{@link ChannelManager#send} 结果，用于失败计数）。
     */
    default <P extends IServerPayloadData> boolean sendPlayPayload(@Nonnull ServerPlayer player, @Nonnull P data)
    {
        Identifier ch = getPayloadChannel();
        if (!isPlayRegistered(ch))
        {
            // 真异常：handler 未 setPlayRegistered。setPlayRegistered 修复后正常不触发；保留 warning 以便漏诊时可见。
            Reference.logger().warning("sendPlayPayload: 通道未注册 " + ch + "（handler 未 setPlayRegistered?）");
            return false;
        }
        Objects.requireNonNull(player, "player");
        byte[] bytes = FriendlyByteBufs.encodePayload(data);
        boolean ok = ChannelManager.INSTANCE.send(ch, player.getBukkitEntity(), bytes);
        ServuxDebug.log(ServuxDebug.Cat.PACKET, "sendPlayPayload(pluginMsg) " + ch + " → " + player.getName().getString()
                + " pktType=" + data.getPacketType() + " bytes=" + bytes.length + " ok=" + ok);
        return ok;
    }
}
