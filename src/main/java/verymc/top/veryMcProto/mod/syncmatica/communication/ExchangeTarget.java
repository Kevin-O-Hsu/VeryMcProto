package verymc.top.veryMcProto.mod.syncmatica.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import net.minecraft.network.FriendlyByteBuf;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.framework.network.ChannelManager;
import verymc.top.veryMcProto.framework.network.FriendlyByteBufs;
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
 *   <li>{@code sendPacket} 走 {@link ChannelManager#send}（plugin messaging），构造 {@code [Identifier][body]} 复合包体
 *       （docs/21 §1.2 命门——单物理通道 + 第一字段逻辑 PacketType）。</li>
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
     * 发送一个逻辑包（构造 {@code [Identifier][body]} 复合包体，走 {@code syncmatica:main} 物理通道）。
     *
     * <p>对应原版 {@code sendPacket} + {@code ServerPlayHandler.encodeSyncData}。
     * {@code byteBuf} 的内容即 body（不释放原 buf，调用方管理）。
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

        // 构造 [逻辑通道 Identifier][body] 复合包体
        final byte[] body = FriendlyByteBufs.readableBytes(byteBuf);
        final FriendlyByteBuf out = FriendlyByteBufs.buffer(body.length + 8);
        out.writeIdentifier(type.getId());
        out.writeBytes(body);
        final byte[] bytes = FriendlyByteBufs.extractAndRelease(out);

        final boolean ok = ChannelManager.instance().send(SyncmaticaReference.NETWORK_ID, player, bytes);
        final boolean listening = player.getListeningPluginChannels().contains(SyncmaticaReference.NETWORK_ID.toString());
        SyncmaticaDebug.log(SyncmaticaDebug.Cat.NETWORK, "[syncm] sendPacket " + type + " → " + persistentName
                + " bodyBytes=" + body.length + " ok=" + ok + " listening=" + listening);
        if (!ok)
        {
            // 防御性：发送失败（玩家离线 / 通道未注册 outgoing）记录，避免静默丢包
            SyncmaticaLog.warn("ExchangeTarget#sendPacket(): failed to send {} to {} (offline or channel not registered?)",
                               type.getId(), persistentName);
        }
    }

    // removed equals code due to issues with Collection.contains
    public FeatureSet getFeatureSet() { return features; }

    public void setFeatureSet(final FeatureSet f) { features = f; }

    public Collection<Exchange> getExchanges() { return ongoingExchanges; }

    public String getPersistentName() { return persistentName; }
}
