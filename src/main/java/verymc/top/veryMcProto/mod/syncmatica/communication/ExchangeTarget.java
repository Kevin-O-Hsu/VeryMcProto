package verymc.top.veryMcProto.mod.syncmatica.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.server.level.ServerPlayer;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.framework.network.ChannelManager;
import verymc.top.veryMcProto.framework.network.FriendlyByteBufs;
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaReference;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.Exchange;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;

/**
 * 「一个连接」的抽象（移植自 {@code ch.endte.syncmatica.communication.ExchangeTarget}）。
 *
 * <p>服务端 = 一个在线玩家。生命周期 = 玩家连接（onPlayerLeave 时从 {@code ServerCommunicationManager} 移除）。
 * 持 {@code ongoingExchanges} 列表 + {@link FeatureSet}，{@code sendPacket} 走 plugin messaging。
 *
 * <p><b>Paper 适配</b>：
 * <ul>
 *   <li>原版持 {@code ServerGamePacketListenerImpl}（NMS，需 Mixin 注入）→ 改持 {@link Player}（Bukkit）；</li>
 *   <li>{@code persistentName} = {@code player.getUniqueId().toString()}（原版 {@code getStringUUID()}）；</li>
 *   <li>{@code sendPacket} 默认走 <b>NMS {@code DiscardedPayload} 直发</b>（{@link #S2C_VIA_NMS}，同 JEI Recipe Bridge），
 *       构造 {@code [Identifier][body]} 复合包体（docs/21 §1.2 命门——单物理通道 + 第一字段逻辑 PacketType）；
 *       plugin messaging 仅作 fallback——纯 Fabric 客户端（syncmatica）收不到 plugin messaging 的 wire（实测）。</li>
 * </ul>
 */
public class ExchangeTarget
{
    private final Player player;
    private final UUID playerId;
    private final String persistentName;

    private FeatureSet features;
    private final List<Exchange> ongoingExchanges = new ArrayList<>(); // implicitly relies on priority

    public ExchangeTarget(final Player player)
    {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.persistentName = player.getUniqueId().toString();
    }

    public Player getPlayer() { return player; }

    public UUID getPlayerId() { return playerId; }

    /**
     * S2C 发送路径开关（诊断用，运行时可经 {@code /syncmatica debug s2c nms|msg} 切换）。
     *
     * <p><b>命门（实测）</b>：plugin messaging（{@code sendPluginMessage}）的 S2C wire 格式，纯 Fabric 客户端
     * （syncmatica）收不到——客户端零响应、零 C2S 回包、连原版 Fabric syncmatica 服务端正常的同一客户端
     * 进我们服也不回。而 NMS {@link DiscardedPayload} 直发（同 {@code RecipeSyncHandler.sendPayload} 路径）
     * 已验证 fabric 客户端可解码（JEI Recipe Bridge 实测通过）。故 S2C 默认走 NMS 直发；plugin messaging
     * 仅作对比 fallback 保留。具体差异（plugin messaging wire vs NMS wire）尚待 wire 抓包确认，但实证上
     * NMS 直发是唯一让 Fabric 客户端响应的路径。
     */
    public static volatile boolean S2C_VIA_NMS = true;

    /**
     * 发送一个逻辑包（构造 {@code [Identifier][body]} 复合包体，走 {@code syncmatica:main} 物理通道）。
     *
     * <p>对应原版 {@code sendPacket} + {@code ServerPlayHandler.encodeSyncData}。
     * {@code byteBuf} 的内容即 body（不释放原 buf，调用方管理）。
     *
     * <p><b>S2C 路径</b>：默认 NMS {@link DiscardedPayload} 直发（{@link #S2C_VIA_NMS}），可切回 plugin messaging 对比。
     */
    public void sendPacket(final PacketType type, final FriendlyByteBuf byteBuf, final SyncmaticaContext context)
    {
        if (context != null)
        {
            context.getDebugService().logSendPacket(type, persistentName);
        }

        if (type == null)
        {
            SyncmaticaLog.error("ExchangeTarget#sendPacket(): null PacketType for partner {}", persistentName);
            return;
        }

        // 构造 [逻辑通道 Identifier][body] 复合包体（原版 SyncmaticaPacket.toPacket 格式）
        final byte[] body = FriendlyByteBufs.readableBytes(byteBuf);
        final FriendlyByteBuf out = FriendlyByteBufs.buffer(body.length + 8);
        out.writeIdentifier(type.getId());
        out.writeBytes(body);
        final byte[] bytes = FriendlyByteBufs.extractAndRelease(out);

        final boolean listening = player != null
                && player.getListeningPluginChannels().contains(SyncmaticaReference.NETWORK_ID.toString());

        final boolean ok;
        final String route;
        if (S2C_VIA_NMS)
        {
            route = "NMS";
            ok = sendViaNms(bytes);
        }
        else
        {
            route = "pluginMsg";
            ok = ChannelManager.instance().send(SyncmaticaReference.NETWORK_ID, player, bytes);
        }

        SyncmaticaDebug.log(SyncmaticaDebug.Cat.NETWORK, "[syncm] sendPacket(" + route + ") " + type + " → " + persistentName
                + " bytes=" + bytes.length + " ok=" + ok + " listening=" + listening
                + " hex=" + hexPreview(bytes));
        if (!ok)
        {
            SyncmaticaLog.warn("ExchangeTarget#sendPacket(): {} 发送失败 type={} partner={}（见 NETWORK 日志）",
                               route, type.getId(), persistentName);
        }
    }

    /**
     * NMS {@link DiscardedPayload} 直发（绕过 plugin messaging；同 {@code RecipeSyncHandler.sendPayload} 路径）。
     *
     * <p>构造 {@code new ClientboundCustomPayloadPacket(new DiscardedPayload(syncmatica:main, bytes))}，
     * 经 {@code ServerPlayer.connection.send} 投递。DiscardedPayload 本身即 vanilla payload 类型，序列化时
     * 由 Paper 注册的 codec 原样写出 bytes，不会被强转拒绝（与「自定义 Payload record」不同——后者触发
     * ClassCastException，见 {@code IPluginServerPlayHandler#sendPlayPayload} 注释）。
     *
     * <p>客户端侧：用其自行注册的 {@code SyncmaticaPacket.Payload.CODEC}（按 {@code syncmatica:main} 查得）
     * 解码 payload data = {@code [Identifier][body]}，与原版 Fabric 服务端发的 wire 一致。
     */
    private boolean sendViaNms(final byte[] bytes)
    {
        if (player == null || !player.isOnline())
        {
            return false;
        }
        try
        {
            final ServerPlayer nms = Nms.toNms(player);
            nms.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(SyncmaticaReference.NETWORK_ID, bytes)));
            return true;
        }
        catch (Exception e)
        {
            SyncmaticaLog.error("ExchangeTarget#sendViaNms(): NMS 直发异常 partner={} bytes={}", e, persistentName, bytes.length);
            return false;
        }
    }

    /** bytes 前缀 hex 预览（诊断 wire 内容，最多 48 字节）。 */
    private static String hexPreview(final byte[] bytes)
    {
        final int n = Math.min(bytes.length, 48);
        final StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++)
        {
            if (i > 0) { sb.append(' '); }
            sb.append(String.format("%02x", bytes[i] & 0xff));
        }
        if (bytes.length > n) { sb.append("...(+").append(bytes.length - n).append(")"); }
        return sb.toString();
    }

    // removed equals code due to issues with Collection.contains
    public FeatureSet getFeatureSet() { return features; }

    public void setFeatureSet(final FeatureSet f) { features = f; }

    public Collection<Exchange> getExchanges() { return ongoingExchanges; }

    public String getPersistentName() { return persistentName; }
}
