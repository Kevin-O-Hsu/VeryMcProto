package verymc.top.veryMcProto.mod.syncmatica.communication.exchange;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.communication.CommunicationManager;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;

/**
 * Exchange 状态机基类（移植自 {@code ch.endte.syncmatica.communication.exchange.AbstractExchange}）。
 *
 * <p>状态：{@code finished} / {@code success}。{@link #close} 先翻状态再 {@link #onClose} + 可选 cancel；
 * {@link #succeed} 翻成功状态后 {@link #onClose}（不发 cancel）。
 *
 * <p>{@link #checkUUID} 是 peek 式：记录 readerIndex → 读 UUID → 回退（不消费），供 {@link #checkPacket} 无副作用判定。
 * {@code handle} 第一行通常 {@code readUUID()} 真正消费。
 */
public abstract class AbstractExchange implements Exchange
{
    protected int PACKET_MAX_STRING_SIZE = FriendlyByteBuf.MAX_STRING_LENGTH;
    private boolean success = false;
    private boolean finished = false;
    private final ExchangeTarget partner;
    private final SyncmaticaContext context;

    protected AbstractExchange(final ExchangeTarget partner, final SyncmaticaContext con)
    {
        this.partner = partner;
        context = con;
    }

    @Override
    public ExchangeTarget getPartner() {
        return partner;
    }

    @Override
    public SyncmaticaContext getContext() {
        return context;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public boolean isSuccessful() {
        return success;
    }

    @Override
    public void close(final boolean notifyPartner)
    {
        finished = true;
        success = false;
        onClose();
        if (notifyPartner)
        {
            sendCancelPacket();
        }
    }

    public CommunicationManager getManager() { return context.getCommunicationManager(); }

    protected void sendCancelPacket() { }

    protected void onClose() { }

    protected void succeed()
    {
        finished = true;
        success = true;
        onClose();
    }

    protected static boolean checkUUID(final FriendlyByteBuf sourceBuf, final UUID targetId)
    {
        final int r = sourceBuf.readerIndex();
        final UUID sourceId = sourceBuf.readUUID();
        sourceBuf.readerIndex(r);
        return sourceId.equals(targetId);
    }
}
