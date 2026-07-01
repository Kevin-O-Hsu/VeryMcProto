package verymc.top.veryMcProto.mod.syncmatica.communication.exchange;

import java.util.Collection;
import net.minecraft.network.FriendlyByteBuf;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaReference;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.communication.FeatureSet;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPlacement;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import io.netty.buffer.Unpooled;

/**
 * 服务端版本握手 Exchange（移植自 {@code VersionHandshakeServer}）。
 *
 * <p>玩家进服时由 {@code ServerCommunicationManager.onPlayerJoin} 创建。流程：
 * 发 {@code REGISTER_VERSION}[服务端版本] → 收客户端版本 → {@code checkPartnerVersion} + {@code FeatureSet.fromVersionString}
 * → 命中默认集则 setFeatureSet，否则 {@code requestFeatureSet}（FEATURE 交换）→ {@code onFeatureSetReceive}
 * 发 {@code CONFIRM_USER}[placementCount + 全量 metadata] → {@code succeed}。
 */
public class VersionHandshakeServer extends FeatureExchange
{
    private String partnerVersion;
    public VersionHandshakeServer(final ExchangeTarget partner, final SyncmaticaContext con) { super(partner, con); }

    @Override
    public boolean checkPacket(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        return type.equals(PacketType.REGISTER_VERSION)
                || super.checkPacket(type, packetBuf);
    }

    @Override
    public void handle(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        if (type.equals(PacketType.REGISTER_VERSION))
        {
            partnerVersion = packetBuf.readUtf(PACKET_MAX_STRING_SIZE);
            if (!getContext().checkPartnerVersion(partnerVersion))
            {
                SyncmaticaLog.warn("Denying syncmatica join due to outdated client with local version {} and client version {} from partner {}",
                        SyncmaticaReference.MOD_VERSION, partnerVersion, getPartner().getPersistentName());
                // same as client - avoid further packets
                close(false);
                return;
            }
            final FeatureSet fs = FeatureSet.fromVersionString(partnerVersion);
            if (fs == null)
            {
                requestFeatureSet();
            }
            else
            {
                getPartner().setFeatureSet(fs);
                onFeatureSetReceive();
            }
        }
        else
        {
            super.handle(type, packetBuf);
        }
    }

    @Override
    public void onFeatureSetReceive()
    {
        SyncmaticaLog.info("Syncmatica client joining with local version {} and client version {}",
                SyncmaticaReference.MOD_VERSION, partnerVersion);
        final FriendlyByteBuf newBuf = new FriendlyByteBuf(Unpooled.buffer());
        final Collection<ServerPlacement> l = getContext().getSyncmaticManager().getAll();
        newBuf.writeInt(l.size());
        for (final ServerPlacement p : l)
        {
            getManager().putMetaData(p, newBuf, getPartner());
        }
        getPartner().sendPacket(PacketType.CONFIRM_USER, newBuf, getContext());
        succeed();
    }

    @Override
    public void init()
    {
        final FriendlyByteBuf newBuf = new FriendlyByteBuf(Unpooled.buffer());
        newBuf.writeUtf(SyncmaticaReference.MOD_VERSION);
        getPartner().sendPacket(PacketType.REGISTER_VERSION, newBuf, getContext());
    }
}
