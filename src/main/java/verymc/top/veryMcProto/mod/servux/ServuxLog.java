package verymc.top.veryMcProto.mod.servux;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.servux.dataproviders.ConfigProvider;

/**
 * Servux mod 调试日志工具（mod 层）。替代原版 {@code Servux.debugLog}。
 *
 * <p>开关来自 {@link ConfigProvider#hasDebugMode()}（servux_main 的 debug_log setting 或 DEV_DEBUG）。
 * 走框架 {@link Reference#logger()}（jul）。
 */
public final class ServuxLog
{
    private ServuxLog() { }

    public static void debug(String msg)
    {
        try
        {
            if (ConfigProvider.INSTANCE.hasDebugMode())
            {
                Reference.logger().info("[Servux] " + msg);
            }
        }
        catch (Throwable ignored) { }
    }
}
