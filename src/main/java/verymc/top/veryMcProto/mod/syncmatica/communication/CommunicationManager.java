package verymc.top.veryMcProto.mod.syncmatica.communication;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.mod.syncmatica.Feature;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.DownloadExchange;
import verymc.top.veryMcProto.mod.syncmatica.communication.exchange.Exchange;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPlacement;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifier;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifierProvider;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.SubRegionData;
import verymc.top.veryMcProto.mod.syncmatica.extended_core.SubRegionPlacementModification;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import io.netty.buffer.Unpooled;

/**
 * 通信管理器抽象基类（移植自 {@code ch.endte.syncmatica.communication.CommunicationManager}）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>{@link #onPacket}：把包派发给该 target 的活跃 Exchange 链（{@link Exchange#checkPacket} 命中者），
 *       无人认领则走抽象 {@link #handle}（一次性请求）；</li>
 *   <li>{@link #putMetaData} / {@link #putPositionData} / {@link #receiveMetaData} / {@link #receivePositionData}：
 *       placement metadata 的 FriendlyByteBuf 编解码（Feature 条件字段，docs/21 §4）；</li>
 *   <li>exchange 生命周期调度（{@link #startExchange} / {@link #notifyClose}）；</li>
 *   <li>下载/修改状态表（{@code downloadState} / {@code modifyState}）。</li>
 * </ul>
 *
 * <p>子类 {@link ServerCommunicationManager} 实现服务端 {@link #handle} / {@link #handleExchange}。
 */
public abstract class CommunicationManager
{
    protected int PACKET_MAX_STRING_SIZE = FriendlyByteBuf.MAX_STRING_LENGTH;
    protected final Collection<ExchangeTarget> broadcastTargets;

    protected final Map<UUID, Boolean> downloadState;
    protected final Map<UUID, Exchange> modifyState;

    protected SyncmaticaContext context;

    protected static final Rotation[] rotOrdinals = Rotation.values();
    protected static final Mirror[] mirOrdinals = Mirror.values();

    protected CommunicationManager()
    {
        // 并发集合：C2S 包回调（ProtocolChannel→PluginMessageListener，可能非主线程）与握手/命令（主线程）并发访问——
        // CopyOnWriteArrayList/ConcurrentHashMap 消除竞态；单线程下行为与 ArrayList/HashMap 等价（迭代保持插入顺序）。
        broadcastTargets = new CopyOnWriteArrayList<>();
        downloadState = new ConcurrentHashMap<>();
        modifyState = new ConcurrentHashMap<>();
    }

    public boolean handlePacket(final PacketType type) { return PacketType.containsType(type); }

    public void onPacket(final ExchangeTarget source, final PacketType type, final FriendlyByteBuf packetBuf)
    {
        context.getDebugService().logReceivePacket(type);
        Exchange handler = null;
        final Collection<Exchange> potentialMessageTarget = source.getExchanges();
        if (potentialMessageTarget != null)
        {
            for (final Exchange target : potentialMessageTarget)
            {
                if (target.checkPacket(type, packetBuf))
                {
                    target.handle(type, packetBuf);
                    handler = target;
                    break;
                }
            }
        }
        if (handler == null)
        {
            SyncmaticaDebug.log(SyncmaticaDebug.Cat.PACKET, "[syncm] onPacket: 无 exchange 认领 " + type
                    + " → 走一次性 handle（来自 " + source.getPersistentName() + "）");
            handle(source, type, packetBuf);
        }
        else
        {
            SyncmaticaDebug.log(SyncmaticaDebug.Cat.PACKET, "[syncm] onPacket: exchange " + handler.getClass().getSimpleName()
                    + " 认领 " + type + "（来自 " + source.getPersistentName() + "）finished=" + handler.isFinished());
            if (handler.isFinished())
            {
                notifyClose(handler);
            }
        }
    }

    // will get called for every packet not handled by an exchange
    protected abstract void handle(ExchangeTarget source, PacketType type, FriendlyByteBuf packetBuf);

    // will get called for every finished exchange (successful or not)
    protected abstract void handleExchange(Exchange exchange);

    public void sendMetaData(final ServerPlacement metaData, final ExchangeTarget target)
    {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        putMetaData(metaData, buf, target);
        target.sendPacket(PacketType.REGISTER_METADATA, buf, context);
    }

    public void putMetaData(final ServerPlacement metaData, final FriendlyByteBuf buf, final ExchangeTarget exchangeTarget)
    {
        buf.writeUUID(metaData.getId());
        buf.writeUtf(metaData.getFileName());
        buf.writeUUID(metaData.getHash());

        if (exchangeTarget.getFeatureSet().hasFeature(Feature.DISPLAY_NAME))
        {
            buf.writeUtf(metaData.getName());
        }

        if (exchangeTarget.getFeatureSet().hasFeature(Feature.CORE_EX))
        {
            buf.writeUUID(metaData.getOwner().uuid);
            buf.writeUtf(metaData.getOwner().getName());
            buf.writeUUID(metaData.getLastModifiedBy().uuid);
            buf.writeUtf(metaData.getLastModifiedBy().getName());
        }

        if (exchangeTarget.getFeatureSet().hasFeature(Feature.VERSION)) {
            buf.writeVarInt(metaData.getLitematicVersion());
            buf.writeVarInt(metaData.getDataVersion());
        }

        putPositionData(metaData, buf, exchangeTarget);
    }

    public void putPositionData(final ServerPlacement metaData, final FriendlyByteBuf buf, final ExchangeTarget exchangeTarget)
    {
        buf.writeBlockPos(metaData.getPosition());
        buf.writeUtf(metaData.getDimension());
        // one of the rare use cases for ordinal
        // transmitting the information of a non-modifying enum to another
        // instance of this application with no regard to the persistence
        // of the ordinal values over time
        buf.writeInt(metaData.getRotation().ordinal());
        buf.writeInt(metaData.getMirror().ordinal());

        if (exchangeTarget.getFeatureSet().hasFeature(Feature.CORE_EX))
        {
            if (metaData.getSubRegionData().getModificationData() == null)
            {
                buf.writeInt(0);

                return;
            }

            final Collection<SubRegionPlacementModification> regionData = metaData.getSubRegionData().getModificationData().values();
            buf.writeInt(regionData.size());

            for (final SubRegionPlacementModification subPlacement : regionData)
            {
                buf.writeUtf(subPlacement.name);
                buf.writeBlockPos(subPlacement.position);
                buf.writeInt(subPlacement.rotation.ordinal());
                buf.writeInt(subPlacement.mirror.ordinal());
            }
        }
    }

    public ServerPlacement receiveMetaData(final FriendlyByteBuf buf, final ExchangeTarget exchangeTarget)
    {
        final UUID id = buf.readUUID();

        final String fileName = buf.readUtf(PACKET_MAX_STRING_SIZE);
        final UUID hash = buf.readUUID();

        PlayerIdentifier owner = PlayerIdentifier.MISSING_PLAYER;
        PlayerIdentifier lastModifiedBy = PlayerIdentifier.MISSING_PLAYER;

        String displayName;
        if (exchangeTarget.getFeatureSet().hasFeature(Feature.DISPLAY_NAME))
        {
            displayName = buf.readUtf(PACKET_MAX_STRING_SIZE);
        }
        else
        {
            displayName = fileName;
        }

        if (exchangeTarget.getFeatureSet().hasFeature(Feature.CORE_EX))
        {
            final PlayerIdentifierProvider provider = context.getPlayerIdentifierProvider();
            owner = provider.createOrGet(
                    buf.readUUID(),
                    buf.readUtf(PACKET_MAX_STRING_SIZE)
            );
            lastModifiedBy = provider.createOrGet(
                    buf.readUUID(),
                    buf.readUtf(PACKET_MAX_STRING_SIZE)
            );
        }

        ServerPlacement placement;
        int litematicVersion;
        int dataVersion;

        if (exchangeTarget.getFeatureSet().hasFeature(Feature.VERSION)) {
            litematicVersion = buf.readVarInt();
            dataVersion = buf.readVarInt();
            placement = new ServerPlacement(id, ServerPlacement.normalizeFileName(fileName), displayName, hash, owner, litematicVersion, dataVersion);
        } else {
            placement = new ServerPlacement(id, ServerPlacement.normalizeFileName(fileName), displayName, hash, owner);
        }

        placement.setLastModifiedBy(lastModifiedBy);

        receivePositionData(placement, buf, exchangeTarget);

        return placement;
    }

    public void receivePositionData(final ServerPlacement placement, final FriendlyByteBuf buf, final ExchangeTarget exchangeTarget)
    {
        final BlockPos pos = buf.readBlockPos();
        final String dimensionId = buf.readUtf(PACKET_MAX_STRING_SIZE);
        final Rotation rot = safeRotation(buf.readInt());
        final Mirror mir = safeMirror(buf.readInt());
        placement.move(dimensionId, pos, rot, mir);

        if (exchangeTarget.getFeatureSet().hasFeature(Feature.CORE_EX))
        {
            final SubRegionData subRegionData = placement.getSubRegionData();
            subRegionData.reset();
            final int limit = buf.readInt();
            for (int i = 0; i < limit; i++)
            {
                subRegionData.modify(
                        buf.readUtf(PACKET_MAX_STRING_SIZE),
                        buf.readBlockPos(),
                        safeRotation(buf.readInt()),
                        safeMirror(buf.readInt())
                );
            }
        }
    }

    public void download(final ServerPlacement syncmatic, final ExchangeTarget source) throws NoSuchAlgorithmException, IOException
    {
        if (!context.getFileStorage().getLocalState(syncmatic).isReadyForDownload())
        {
            // 逻辑正确：未就绪则抛异常（!ready ⇒ throw）。原版作者英文注释 "forgot a negation here" 字面像
            // "忘了取反"，但此处语义自洽、与下方异常文案 "is not ready for download" 一致——勿误改。
            throw new IllegalArgumentException(syncmatic.toString() + " is not ready for download local state is: " + context.getFileStorage().getLocalState(syncmatic).toString());
        }
        final Path toDownload = context.getFileStorage().createLocalLitematic(syncmatic);
        final Exchange downloadExchange = new DownloadExchange(syncmatic, toDownload, source, context);
        setDownloadState(syncmatic, true);
        startExchange(downloadExchange);
    }

    public void setDownloadState(final ServerPlacement syncmatic, final boolean b) { downloadState.put(syncmatic.getHash(), b); }

    public boolean getDownloadState(final ServerPlacement syncmatic) { return downloadState.getOrDefault(syncmatic.getHash(), false); }

    public void setModifier(final ServerPlacement syncmatic, final Exchange exchange) { modifyState.put(syncmatic.getHash(), exchange); }

    public Exchange getModifier(final ServerPlacement syncmatic) { return modifyState.get(syncmatic.getHash()); }

    public void startExchange(final Exchange newExchange)
    {
        if (!broadcastTargets.contains(newExchange.getPartner()))
        {
            throw new IllegalArgumentException(newExchange.getPartner().toString() + " is not a valid ExchangeTarget");
        }
        startExchangeUnchecked(newExchange);
    }

    protected void startExchangeUnchecked(final Exchange newExchange)
    {
        newExchange.getPartner().getExchanges().add(newExchange);
        newExchange.init();
        if (newExchange.isFinished())
        {
            notifyClose(newExchange);
        }
    }

    public void setContext(final SyncmaticaContext con)
    {
        if (context == null)
        {
            context = con;
        }
        else
        {
            throw new SyncmaticaContext.DuplicateContextAssignmentException("Duplicate Context Assignment");
        }
    }

    public void notifyClose(final Exchange e)
    {
        e.getPartner().getExchanges().remove(e);
        handleExchange(e);
    }

    /** 读 Rotation ordinal 并校验范围；恶意客户端越界值抛 IllegalArgumentException（被上游 ProtocolChannel 的 try 兜底，包丢弃——与原隐式 AIOOBE 表面行为一致，仅把数组越界显式化便于诊断）。 */
    private static Rotation safeRotation(final int ordinal)
    {
        if (ordinal < 0 || ordinal >= rotOrdinals.length)
        {
            throw new IllegalArgumentException("Invalid Rotation ordinal: " + ordinal + " (valid 0.." + (rotOrdinals.length - 1) + ")");
        }
        return rotOrdinals[ordinal];
    }

    /** 读 Mirror ordinal 并校验范围；同 {@link #safeRotation}。 */
    private static Mirror safeMirror(final int ordinal)
    {
        if (ordinal < 0 || ordinal >= mirOrdinals.length)
        {
            throw new IllegalArgumentException("Invalid Mirror ordinal: " + ordinal + " (valid 0.." + (mirOrdinals.length - 1) + ")");
        }
        return mirOrdinals[ordinal];
    }
}
