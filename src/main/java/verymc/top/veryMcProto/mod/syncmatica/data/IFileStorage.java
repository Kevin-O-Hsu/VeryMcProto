package verymc.top.veryMcProto.mod.syncmatica.data;

import java.nio.file.Path;

/**
 * 投影文件存储接口（移植自 {@code ch.endte.syncmatica.data.IFileStorage}）。
 *
 * <p><b>Paper 适配</b>：去掉 {@code setContext(Context)}（原版用于反向注入 Context）——
 * 改由 {@link FileStorage} 构造注入 {@code litematicFolder} + 函数式 downloadStateProvider，解耦 Context。
 */
public interface IFileStorage
{
    LocalLitematicState getLocalState(ServerPlacement placement);

    Path createLocalLitematic(ServerPlacement placement);

    Path getLocalLitematic(ServerPlacement placement);
}
