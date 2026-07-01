package verymc.top.veryMcProto.mod.syncmatica.data;

/**
 * 投影文件本地状态（移植自 {@code ch.endte.syncmatica.data.LocalLitematicState}）。
 *
 * <p>{@code FileStorage.getLocalState} 的返回值，决定服务端对某 placement 的文件是否就绪 / 可下载。
 */
public enum LocalLitematicState
{
    NO_LOCAL_LITEMATIC(true, false),
    LOCAL_LITEMATIC_DESYNC(true, false),
    DOWNLOADING_LITEMATIC(false, false),
    LOCAL_LITEMATIC_PRESENT(false, true);

    private final boolean downloadReady;
    private final boolean fileReady;

    LocalLitematicState(final boolean downloadReady, final boolean fileReady)
    {
        this.downloadReady = downloadReady;
        this.fileReady = fileReady;
    }

    public boolean isReadyForDownload()
    {
        return downloadReady;
    }

    public boolean isLocalFileReady()
    {
        return fileReady;
    }
}
