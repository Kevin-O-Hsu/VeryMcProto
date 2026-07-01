package verymc.top.veryMcProto.mod.syncmatica.communication.exchange;

import java.io.*;
import java.nio.file.Path;
import net.minecraft.network.FriendlyByteBuf;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPlacement;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import io.netty.buffer.Unpooled;

/**
 * 文件发送方 Exchange（移植自 {@code UploadExchange}）。
 *
 * <p>与 {@link DownloadExchange} 配对。{@code BUFFER_SIZE=16384}（32767 是 custom payload 上限，取半留余）。
 * 严格 stop-and-wait：收 REQUEST/RECEIVED → {@code send()} 读 16KB → 发 SEND_LITEMATIC → 等下一 RECEIVED；
 * 读到 EOF → 发 FINISHED → {@code succeed}。{@code init} 首次不等 RECEIVED 直接发第一片。
 *
 * <p>不查配额（仅 DownloadExchange 查；S2C 下发方向不限流）。
 */
public class UploadExchange extends AbstractExchange
{
    // The maximum buffer size for CustomPayloadPackets is actually 32767
    // so 32768 is a bad value to send - thus adjusted it to 16384 - exactly halved
    private static final int BUFFER_SIZE = 16384;
    private final ServerPlacement toUpload;
    private final InputStream inputStream;
    private final byte[] buffer = new byte[BUFFER_SIZE];

    public UploadExchange(final ServerPlacement syncmatic, final Path uploadFile, final ExchangeTarget partner, final SyncmaticaContext con) throws FileNotFoundException
    {
        super(partner, con);
        toUpload = syncmatic;
        inputStream = new FileInputStream(uploadFile.toFile());
    }

    @Override
    public boolean checkPacket(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        if (type.equals(PacketType.RECEIVED_LITEMATIC)
                || type.equals(PacketType.CANCEL_LITEMATIC))
        {
            return checkUUID(packetBuf, toUpload.getId());
        }
        return false;
    }

    @Override
    public void handle(final PacketType type, final FriendlyByteBuf packetBuf)
    {

        packetBuf.readUUID(); // uncertain if the data has to be consumed
        if (type.equals(PacketType.RECEIVED_LITEMATIC))
        {
            send();
        }
        if (type.equals(PacketType.CANCEL_LITEMATIC))
        {
            close(false);
        }
    }

    private void send()
    {
        // might fail when an empty file is attempted to be transmitted
        final int bytesRead;
        try
        {
            bytesRead = inputStream.read(buffer);
        }
        catch (final IOException e)
        {
            SyncmaticaLog.error("UploadExchange#send(): IOException reading input", e);
            close(true);
            return;
        }
        if (bytesRead == -1)
        {
            sendFinish();
        }
        else
        {
            sendData(bytesRead);
        }
    }

    private void sendData(final int bytesRead)
    {
        final FriendlyByteBuf packetByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        packetByteBuf.writeUUID(toUpload.getId());
        packetByteBuf.writeInt(bytesRead);
        packetByteBuf.writeBytes(buffer, 0, bytesRead);
        getPartner().sendPacket(PacketType.SEND_LITEMATIC, packetByteBuf, getContext());
    }

    private void sendFinish()
    {
        final FriendlyByteBuf packetByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        packetByteBuf.writeUUID(toUpload.getId());
        getPartner().sendPacket(PacketType.FINISHED_LITEMATIC, packetByteBuf, getContext());
        succeed();
    }

    @Override
    public void init() { send(); }

    @Override
    protected void onClose()
    {
        try
        {
            inputStream.close();
        }
        catch (final IOException e)
        {
            SyncmaticaLog.error("UploadExchange#onClose(): failed to close input stream", e);
        }
    }

    @Override
    protected void sendCancelPacket()
    {
        final FriendlyByteBuf packetByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        packetByteBuf.writeUUID(toUpload.getId());
        getPartner().sendPacket(PacketType.CANCEL_LITEMATIC, packetByteBuf, getContext());
    }
}
