package verymc.top.veryMcProto.mod.syncmatica.data;

import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * 投影文件存储（移植自 {@code ch.endte.syncmatica.data.FileStorage}）。
 *
 * <p>服务端按 {@code <hashValue>.litematic} 内容寻址命名（天然去重），存于 {@code litematicFolder}。
 *
 * <p><b>Paper 适配</b>（解耦 Context）：
 * <ul>
 *   <li>构造注入 {@code litematicFolder}（替代 {@code context.getLitematicFolder()}）；</li>
 *   <li>{@code downloadStateProvider}（{@code hash → isDownloading}）由 CommunicationManager 启动后注入
 *       （替代 {@code context.getCommunicationManager().getDownloadState}）；null 时视为无下载中（防御性，避免启动早期 NPE）；</li>
 *   <li>去掉 {@code context.isServer()} 分支（Paper 恒 server，永远走 hash 命名）；</li>
 *   <li>{@code Syncmatica.LOGGER} → {@link SyncmaticaLog}。</li>
 * </ul>
 */
public class FileStorage implements IFileStorage
{

    private final HashMap<ServerPlacement, Long> buffer = new HashMap<>();
    private final Path litematicFolder;
    // 由 CommunicationManager 注入（Context 启动时）；默认返回 false（无下载中）
    private Function<ServerPlacement, Boolean> downloadStateProvider = placement -> false;

    public FileStorage(final Path litematicFolder)
    {
        this.litematicFolder = litematicFolder;
    }

    /** 由 Context 在 CommunicationManager 就绪后注入。 */
    public void setDownloadStateProvider(final Function<ServerPlacement, Boolean> provider)
    {
        this.downloadStateProvider = provider;
    }

    @Override
    public LocalLitematicState getLocalState(final ServerPlacement placement)
    {
        final Path localFile = getSchematicPath(placement);
        if (Files.isRegularFile(localFile))
        {
            if (isDownloading(placement))
            {
                return LocalLitematicState.DOWNLOADING_LITEMATIC;
            }
            if ((buffer.containsKey(placement) && buffer.get(placement) == this.getLastModified(localFile)) || hashCompare(localFile, placement))
            {
                return LocalLitematicState.LOCAL_LITEMATIC_PRESENT;
            }
            return LocalLitematicState.LOCAL_LITEMATIC_DESYNC;
        }
        return LocalLitematicState.NO_LOCAL_LITEMATIC;
    }

    private long getLastModified(Path file)
    {
        if (Files.exists(file))
        {
            try
            {
                return Files.getLastModifiedTime(file).toMillis();
            }
            catch (IOException e)
            {
                SyncmaticaLog.warn("Error getting last modified time of file '{}'; Exception: {}", file.getFileName(), e.getLocalizedMessage());
            }
        }

        // use current time
        return System.currentTimeMillis();
    }

    private boolean isDownloading(final ServerPlacement placement)
    {
        return downloadStateProvider.apply(placement);
    }

    @Override
    public Path getLocalLitematic(final ServerPlacement placement)
    {
        if (getLocalState(placement).isLocalFileReady())
        {
            return getSchematicPath(placement);
        }
        else
        {
            return null;
        }
    }

    // method for creating an empty file for the litematic data
    @Override
    public Path createLocalLitematic(final ServerPlacement placement)
    {
        if (getLocalState(placement).isLocalFileReady())
        {
            throw new IllegalArgumentException("File already ready for placement " + placement.getId());
        }

        final Path file = getSchematicPath(placement);

        try
        {
            if (Files.exists(file))
            {
                Files.deleteIfExists(file);
            }

            Files.createFile(file);
            return file;
        }
        catch (IOException e)
        {
            SyncmaticaLog.error("Exception creating new file: '{}'; Exception: {}", file.getFileName(), e.getLocalizedMessage());
        }

        return file;
    }

    private boolean hashCompare(final Path localFile, final ServerPlacement placement)
    {
        UUID hash = null;
        try
        {
            hash = SyncmaticaUtil.createChecksum(new FileInputStream(localFile.toFile()));
        }
        catch (final Exception e)
        {
            // can be safely ignored since we established that file has been found
            SyncmaticaLog.warn("hashCompare: failed to compute checksum for '{}'; {}", localFile, e.getLocalizedMessage());
        }

        if (hash == null)
        {
            return false;
        }
        if (hash.equals(placement.getHash()))
        {
            buffer.put(placement, this.getLastModified(localFile));
            return true;
        }
        return false;
    }

    private Path getSchematicPath(final ServerPlacement placement)
    {
        // Paper 恒 server：永远按 <hash>.litematic 内容寻址命名（原版 isServer() 分支）
        return litematicFolder.resolve(placement.getHash().toString() + ".litematic");
    }
}
