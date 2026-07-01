package verymc.top.veryMcProto.mod.syncmatica.communication;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import org.bukkit.entity.Player;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.mod.syncmatica.Feature;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.Exchange;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.DownloadExchange;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.ModifyExchangeServer;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.UploadExchange;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.VersionHandshakeServer;
import verymc.top.veryMcProto.mod.syncmatica.data.LocalLitematicState;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPlacement;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifier;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import io.netty.buffer.Unpooled;

/**
 * 服务端通信管理器（移植自 {@code ch.endte.syncmatica.communication.ServerCommunicationManager}）。
 *
 * <p>实现抽象 {@link #handle}（4 类一次性请求：REQUEST_LITEMATIC / REGISTER_METADATA / REMOVE_SYNCMATIC / MODIFY_REQUEST）
 * 与 {@link #handleExchange}（exchange 完成后广播）。
 *
 * <p><b>Paper 适配</b>：
 * <ul>
 *   <li>去掉原版 {@code Map<ExchangeTarget, ServerPlayer> playerMap}（{@link ExchangeTarget} 已持 Bukkit {@link Player}），
 *       改用 {@code Map<UUID, ExchangeTarget> targets} 按玩家索引 + {@link #getOrCreateTarget}；</li>
 *   <li>{@code getGameProfile} 内联为 {@code createOrGet(uuid, name)}（从 ExchangeTarget 取）；</li>
 *   <li>{@code player.displayClientMessage(Component)} → {@link Player#sendMessage}（Bukkit）。</li>
 * </ul>
 */
public class ServerCommunicationManager extends CommunicationManager
{
    private final Map<UUID, List<ServerPlacement>> downloadingFile = new HashMap<>();
    // Paper：ExchangeTarget 已持 Player，不再需要原版 Map<ExchangeTarget, ServerPlayer> playerMap；
    // 改用 uuid→target 索引以便按玩家查找（替代原版 MixinServerPlayNetworkHandler 的懒加载）。
    private final Map<UUID, ExchangeTarget> targets = new HashMap<>();

    public ServerCommunicationManager() { super(); }

    /** 按玩家获取或创建 ExchangeTarget（替代原版 MixinServerPlayNetworkHandler 的懒加载）。 */
    public ExchangeTarget getOrCreateTarget(final Player player)
    {
        return targets.computeIfAbsent(player.getUniqueId(), id -> new ExchangeTarget(player));
    }

    public ExchangeTarget getTarget(final UUID playerId)
    {
        return targets.get(playerId);
    }

    public void sendMessage(final ExchangeTarget client, final MessageType msgType, final String identifier)
    {
        if (client.getFeatureSet() != null && client.getFeatureSet().hasFeature(Feature.MESSAGE))
        {
            final FriendlyByteBuf newPacketBuf = new FriendlyByteBuf(Unpooled.buffer());
            newPacketBuf.writeUtf(msgType.toString());
            newPacketBuf.writeUtf(identifier);
            client.sendPacket(PacketType.MESSAGE, newPacketBuf, context);
        }
        else
        {
            // Paper：原版 player.displayClientMessage(Component.nullToEmpty(...)) → Bukkit Player.sendMessage
            final Player player = client.getPlayer();
            if (player != null && player.isOnline())
            {
                player.sendMessage("Syncmatica " + msgType.toString() + " " + identifier);
            }
        }
    }

    public void onPlayerJoin(final ExchangeTarget newPlayer)
    {
        targets.put(newPlayer.getPlayerId(), newPlayer);
        context.getPlayerIdentifierProvider().updateName(newPlayer.getPlayerId(), newPlayer.getPlayer().getName());
        SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] onPlayerJoin: 注册 target " + newPlayer.getPersistentName()
                + "（仅登记；握手由 SyncmaticaModule.onJoin 延迟 40t 发起，见 tryStartHandshake）");
        // 此处【不】立即发起 VersionHandshakeServer——原版可行是因为 Fabric 配置阶段已完成通道声明；
        // Paper 下 PlayerJoinEvent 时客户端 codec 尚未就绪，立即推 REGISTER_VERSION 会握手失败并残留 exchange。
        // 握手发起策略（双保险，见 SyncmaticaModule.onJoin）：
        //   ① 主路径：onJoin → runTaskLater(40t) → tryStartHandshake（等 configuration phase 完成、codec 就绪）；
        //   ② 兜底：onPlayerRegisterChannel(syncmatica:main) → tryStartHandshake（旧式 MC|Register，1.21 通常不触发）。
        // tryStartHandshake 幂等（已在 broadcastTargets / 有进行中的 VersionHandshakeServer 则跳过）。
    }

    /**
     * 幂等发起版本握手（Paper 新增）。
     *
     * <p>由 {@code SyncmaticaModule} 双路径调用：
     * <ul>
     *   <li>主路径——{@code onJoin}（{@link org.bukkit.event.player.PlayerJoinEvent}）延迟 40t 后调用
     *       （等 configuration phase 完成、客户端 codec 就绪）；</li>
     *   <li>兜底——{@code onPlayerRegisterChannel}（{@link org.bukkit.event.player.PlayerRegisterChannelEvent}，
     *       客户端经旧式 MC|Register 声明 {@code syncmatica:main} 时；1.21 Fabric 客户端通常不触发）。</li>
     * </ul>
     * 幂等：已握手成功（在 {@code broadcastTargets}）/ 已有进行中的 VersionHandshakeServer 则跳过。
     */
    public void tryStartHandshake(final ExchangeTarget target)
    {
        if (broadcastTargets.contains(target))
        {
            SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] tryStartHandshake: 跳过 " + target.getPersistentName()
                    + "（已在 broadcastTargets，握手已完成）");
            return; // 已握手成功（VersionHandshakeServer succeed 后加入 broadcastTargets）
        }
        for (final Exchange ex : target.getExchanges())
        {
            if (ex instanceof VersionHandshakeServer)
            {
                SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] tryStartHandshake: 跳过 " + target.getPersistentName()
                        + "（已有进行中的 VersionHandshakeServer）");
                return; // 已有进行中的握手，避免重复发起
            }
        }
        SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] tryStartHandshake: 发起握手 → " + target.getPersistentName()
                + "（客户端已声明 syncmatica:main）");
        SyncmaticaLog.info("syncmatica 发起握手 → {}（客户端已声明 syncmatica:main）", target.getPersistentName());
        final VersionHandshakeServer hi = new VersionHandshakeServer(target, context);
        startExchangeUnchecked(hi);
    }

    public void onPlayerLeave(final ExchangeTarget oldPlayer)
    {
        SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] onPlayerLeave: " + oldPlayer.getPersistentName()
                + "（关闭其 exchange 链 + 移出 targets/broadcastTargets）");
        final Collection<Exchange> potentialMessageTarget = oldPlayer.getExchanges();
        if (potentialMessageTarget != null)
        {
            for (final Exchange target : potentialMessageTarget)
            {
                target.close(false);
                handleExchange(target);
            }
        }
        broadcastTargets.remove(oldPlayer);
        targets.remove(oldPlayer.getPlayerId());
    }

    /**
     * 软禁用辅助（{@code /syncmatica disable} 用）：关闭所有已握手玩家的进行中 exchange
     * （不通知对方、不触发 {@link #handleExchange} 副作用），清空 {@code broadcastTargets}。
     * {@code targets} 保留——玩家仍在服，{@code resumeProtocol} 后复用并重新握手。
     */
    public void suspendAll()
    {
        for (final ExchangeTarget t : new ArrayList<>(broadcastTargets))
        {
            for (final Exchange ex : new ArrayList<>(t.getExchanges()))
            {
                try { ex.close(false); } catch (final Exception ignored) { }
            }
        }
        broadcastTargets.clear();
    }

    @Override
    protected void handle(final ExchangeTarget source, final PacketType type, final FriendlyByteBuf packetBuf)
    {
        if (type.equals(PacketType.REQUEST_LITEMATIC))
        {
            final UUID syncmaticaId = packetBuf.readUUID();
            final ServerPlacement placement = context.getSyncmaticManager().getPlacement(syncmaticaId);
            if (placement == null)
            {
                return;
            }
            final Path toUpload = context.getFileStorage().getLocalLitematic(placement);
            UploadExchange upload;
            try
            {
                upload = new UploadExchange(placement, toUpload, source, context);
            }
            catch (final FileNotFoundException e)
            {
                // should be fine
                SyncmaticaLog.warn("ServerCommunicationManager#handle(REQUEST_LITEMATIC): file not found for placement {}; {}",
                        placement.getId(), e.getLocalizedMessage());
                return;
            }
            startExchange(upload);
            return;
        }
        if (type.equals(PacketType.REGISTER_METADATA))
        {
            final ServerPlacement placement = receiveMetaData(packetBuf, source);
            if (context.getSyncmaticManager().getPlacement(placement.getId()) != null)
            {
                cancelShare(source, placement);

                return;
            }

            // Paper：原版 playerMap.get(source).getGameProfile() → 从 ExchangeTarget 取 uuid + name
            final PlayerIdentifier playerIdentifier = context.getPlayerIdentifierProvider().createOrGet(
                    source.getPlayerId(), source.getPlayer().getName());
            if (!placement.getOwner().equals(playerIdentifier))
            {
                placement.setOwner(playerIdentifier);
                placement.setLastModifiedBy(playerIdentifier);
            }

            if (!context.getFileStorage().getLocalState(placement).isLocalFileReady())
            {
                // special edge case because files are transmitted by placement rather than file names/hashes
                if (context.getFileStorage().getLocalState(placement) == LocalLitematicState.DOWNLOADING_LITEMATIC)
                {
                    downloadingFile.computeIfAbsent(placement.getHash(), key -> new ArrayList<>()).add(placement);
                    return;
                }
                try
                {
                    download(placement, source);
                }
                catch (final Exception e)
                {
                    SyncmaticaLog.error("ServerCommunicationManager#handle(REGISTER_METADATA): download failed", e);
                }

                return;
            }

            addPlacement(source, placement);

            return;
        }
        if (type.equals(PacketType.REMOVE_SYNCMATIC))
        {
            final UUID placementId = packetBuf.readUUID();
            final ServerPlacement placement = context.getSyncmaticManager().getPlacement(placementId);
            if (placement != null)
            {
                final Exchange modifier = getModifier(placement);
                if (modifier != null)
                {
                    modifier.close(true);
                    notifyClose(modifier);
                }
                context.getSyncmaticManager().removePlacement(placement);
                for (final ExchangeTarget client : broadcastTargets)
                {
                    final FriendlyByteBuf newPacketBuf = new FriendlyByteBuf(Unpooled.buffer());
                    newPacketBuf.writeUUID(placement.getId());
                    client.sendPacket(PacketType.REMOVE_SYNCMATIC, newPacketBuf, context);
                }
            }
        }
        if (type.equals(PacketType.MODIFY_REQUEST))
        {
            final UUID placementId = packetBuf.readUUID();
            final ModifyExchangeServer modifier = new ModifyExchangeServer(placementId, source, context);
            startExchange(modifier);
        }
    }

    @Override
    protected void handleExchange(final Exchange exchange)
    {
        if (exchange instanceof DownloadExchange)
        {
            final ServerPlacement p = ((DownloadExchange) exchange).getPlacement();

            if (exchange.isSuccessful())
            {
                addPlacement(exchange.getPartner(), p);
                if (downloadingFile.containsKey(p.getHash()))
                {
                    for (final ServerPlacement placement : downloadingFile.get(p.getHash()))
                    {
                        addPlacement(exchange.getPartner(), placement);
                    }
                }
            }
            else
            {
                cancelShare(exchange.getPartner(), p);
                if (downloadingFile.containsKey(p.getHash()))
                {
                    for (final ServerPlacement placement : downloadingFile.get(p.getHash()))
                    {
                        cancelShare(exchange.getPartner(), placement);
                    }
                }
            }

            downloadingFile.remove(p.getHash());
            return;
        }
        if (exchange instanceof VersionHandshakeServer && exchange.isSuccessful())
        {
            broadcastTargets.add(exchange.getPartner());
            SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] 握手成功 → " + exchange.getPartner().getPersistentName()
                    + " 已加入 broadcastTargets（共 " + broadcastTargets.size() + " 个）");
        }
        else if (exchange instanceof VersionHandshakeServer)
        {
            SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] 握手失败 → " + exchange.getPartner().getPersistentName()
                    + "（未加入 broadcastTargets；版本不兼容或 FeatureSet 交换未完成）");
        }
        if (exchange instanceof ModifyExchangeServer && exchange.isSuccessful())
        {
            final ServerPlacement placement = ((ModifyExchangeServer) exchange).getPlacement();
            for (final ExchangeTarget client : broadcastTargets)
            {
                if (client.getFeatureSet().hasFeature(Feature.MODIFY))
                {
                    // client supports modify so just send modify
                    final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                    buf.writeUUID(placement.getId());
                    putPositionData(placement, buf, client);
                    if (client.getFeatureSet().hasFeature(Feature.CORE_EX))
                    {
                        buf.writeUUID(placement.getLastModifiedBy().uuid);
                        buf.writeUtf(placement.getLastModifiedBy().getName());
                    }
                    client.sendPacket(PacketType.MODIFY, buf, context);
                }
                else
                {
                    // client doesn't support modification so
                    // send data and then
                    final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                    buf.writeUUID(placement.getId());
                    client.sendPacket(PacketType.REMOVE_SYNCMATIC, buf, context);
                    final FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
                    putMetaData(placement, buf2, client);
                    client.sendPacket(PacketType.REGISTER_METADATA, buf2, context);
                }
            }
        }
    }

    public void addPlacement(final ExchangeTarget t, final ServerPlacement placement)
    {
        if (context.getSyncmaticManager().getPlacement(placement.getId()) != null)
        {
            cancelShare(t, placement);
            return;
        }
        context.getSyncmaticManager().addPlacement(placement);
        for (final ExchangeTarget target : broadcastTargets)
        {
            sendMetaData(placement, target);
        }
    }

    private void cancelShare(final ExchangeTarget source, final ServerPlacement placement)
    {
        final FriendlyByteBuf packetByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        packetByteBuf.writeUUID(placement.getId());
        source.sendPacket(PacketType.CANCEL_SHARE, packetByteBuf, context);
    }
}
