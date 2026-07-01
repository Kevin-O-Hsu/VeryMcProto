package verymc.top.veryMcProto.mod.syncmatica.communication.exchange;

import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.communication.FeatureSet;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Feature 协商抽象基类（移植自 {@code ch.endte.syncmatica.communication.exchange.FeatureExchange}）。
 *
 * <p>{@link VersionHandshakeServer} 的父类。处理 FEATURE_REQUEST / FEATURE 包。
 */
public abstract class FeatureExchange extends AbstractExchange
{
    protected FeatureExchange(final ExchangeTarget partner, final SyncmaticaContext con) { super(partner, con); }

    @Override
    public boolean checkPacket(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        return type.equals(PacketType.FEATURE_REQUEST)
                || type.equals(PacketType.FEATURE);
    }

    @Override
    public void handle(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        if (type.equals(PacketType.FEATURE_REQUEST))
        {
            sendFeatures();
        } else if (type.equals(PacketType.FEATURE))
        {
            final FeatureSet fs = FeatureSet.fromString(packetBuf.readUtf(PACKET_MAX_STRING_SIZE));
            getPartner().setFeatureSet(fs);
            onFeatureSetReceive();
        }
    }

    protected void onFeatureSetReceive() { succeed(); }

    public void requestFeatureSet()
    {
        getPartner().sendPacket(PacketType.FEATURE_REQUEST, new FriendlyByteBuf(Unpooled.buffer()), getContext());
    }

    private void sendFeatures()
    {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        final FeatureSet fs = getContext().getFeatureSet();
        buf.writeUtf(fs.toString(), PACKET_MAX_STRING_SIZE);
        getPartner().sendPacket(PacketType.FEATURE, buf, getContext());
    }
}
