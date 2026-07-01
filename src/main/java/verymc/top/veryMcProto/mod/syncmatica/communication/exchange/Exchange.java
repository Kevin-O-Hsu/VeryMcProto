package verymc.top.veryMcProto.mod.syncmatica.communication.exchange;

import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Exchange：跨多包的双端通信会话（移植自 {@code ch.endte.syncmatica.communication.exchange.Exchange}）。
 *
 * <p>一个实例只代表通信的<b>本端半边</b>，挂在 {@link ExchangeTarget#getExchanges()} 列表上。
 * {@link #checkPacket} 无副作用（peek），{@link #handle} 才真正处理（消费包内容）。
 */
public interface Exchange
{
    ExchangeTarget getPartner();

    SyncmaticaContext getContext();

    boolean checkPacket(PacketType type, FriendlyByteBuf packetBuf);

    void handle(PacketType type, FriendlyByteBuf packetBuf);

    boolean isFinished();

    boolean isSuccessful();

    void close(boolean notifyPartner);

    void init();
}
