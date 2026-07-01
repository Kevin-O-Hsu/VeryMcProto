package verymc.top.veryMcProto.mod.syncmatica.communication.exchange;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.communication.MessageType;
import verymc.top.veryMcProto.mod.syncmatica.communication.ServerCommunicationManager;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPlacement;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import io.netty.buffer.Unpooled;

/**
 * 文件接收方 Exchange（移植自 {@code DownloadExchange}）。
 *
 * <p>服务端从客户端拉文件（REGISTER_METADATA 时本地无文件）；客户端从服务端拉文件（Load）。
 * 严格 stop-and-wait：发 REQUEST_LITEMATIC → 收 SEND_LITEMATIC 片 → 回 RECEIVED_LITEMATIC → ... → 收 FINISHED → 校验 MD5。
 * 配额检查（仅服务端）：每片累加 bytesSent，超额则 close + 发 MESSAGE(ERROR)。
 */
public class DownloadExchange extends AbstractExchange
{
    private final ServerPlacement toDownload;
    private final OutputStream outputStream;
    private final MessageDigest md5;
    private final Path downloadFile;
    private int bytesSent;

    public DownloadExchange(final ServerPlacement syncmatic, final Path downloadFile, final ExchangeTarget partner, final SyncmaticaContext context) throws IOException, NoSuchAlgorithmException
    {
        super(partner, context);
        this.downloadFile = downloadFile;
        final OutputStream os = new FileOutputStream(downloadFile.toFile()); //NOSONAR
        toDownload = syncmatic;
        md5 = MessageDigest.getInstance("MD5");
        outputStream = new DigestOutputStream(os, md5);
    }

    @Override
    public boolean checkPacket(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        if (type.equals(PacketType.SEND_LITEMATIC)
                || type.equals(PacketType.FINISHED_LITEMATIC)
                || type.equals(PacketType.CANCEL_LITEMATIC))
        {
            return checkUUID(packetBuf, toDownload.getId());
        }
        return false;
    }

    @Override
    public void handle(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        packetBuf.readUUID(); //skips the UUID
        if (type.equals(PacketType.SEND_LITEMATIC))
        {
            final int size = packetBuf.readInt();
            bytesSent += size;
            // Paper 适配：QuotaService.isOverQuota 改接收 persistentName（P3 解耦 ExchangeTarget）
            if (getContext().isServer() && getContext().getQuotaService().isOverQuota(getPartner().getPersistentName(), bytesSent))
            {
                close(true);
                ((ServerCommunicationManager) getContext().getCommunicationManager()).sendMessage(
                        getPartner(),
                        MessageType.ERROR,
                        "syncmatica.error.cancelled_transmit_exceed_quota"
                );
            }
            try
            {
                packetBuf.readBytes(outputStream, size);
            }
            catch (final IOException e)
            {
                SyncmaticaLog.error("DownloadExchange: IOException writing received data", e);
                close(true);
                return;
            }
            final FriendlyByteBuf packetByteBuf = new FriendlyByteBuf(Unpooled.buffer());
            packetByteBuf.writeUUID(toDownload.getId());
            getPartner().sendPacket(PacketType.RECEIVED_LITEMATIC, packetByteBuf, getContext());
            return;
        }
        if (type.equals(PacketType.FINISHED_LITEMATIC))
        {
            try
            {
                outputStream.flush();
            }
            catch (final IOException e)
            {
                SyncmaticaLog.error("DownloadExchange: IOException flushing output", e);
                close(false);
                return;
            }
            final UUID downloadHash = UUID.nameUUIDFromBytes(md5.digest());
            if (downloadHash.equals(toDownload.getHash()))
            {
                succeed();
            }
            else
            {
                // no need to notify partner since exchange is closed on partner side
                close(false);
            }
            return;
        }
        if (type.equals(PacketType.CANCEL_LITEMATIC))
        {
            close(false);
        }
    }

    @Override
    public void init()
    {
        final FriendlyByteBuf packetByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        packetByteBuf.writeUUID(toDownload.getId());
        getPartner().sendPacket(PacketType.REQUEST_LITEMATIC, packetByteBuf, getContext());
    }

    @Override
    protected void onClose()
    {
        getManager().setDownloadState(toDownload, false);
        if (getContext().isServer() && isSuccessful())
        {
            getContext().getQuotaService().progressQuota(getPartner().getPersistentName(), bytesSent);
        }
        try
        {
            outputStream.close();
        }
        catch (final IOException e)
        {
            SyncmaticaLog.error("DownloadExchange#onClose(): failed to close output stream", e);
        }
        //        if (!isSuccessful() && downloadFile.exists())
        if (!isSuccessful() && Files.exists(downloadFile))
        {
            try
            {
                //                if (!downloadFile.delete())
                Files.deleteIfExists(downloadFile);
            }
            catch (Exception err) {
                SyncmaticaLog.error("DownloadExchange#onClose(): failed to delete file: {}; exception {}", downloadFile, err.getLocalizedMessage());
            }
        }
    }

    @Override
    protected void sendCancelPacket()
    {
        final FriendlyByteBuf packetByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        packetByteBuf.writeUUID(toDownload.getId());
        getPartner().sendPacket(PacketType.CANCEL_LITEMATIC, packetByteBuf, getContext());
    }

    public ServerPlacement getPlacement() { return toDownload; }
}
