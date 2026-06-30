package verymc.top.veryMcProto.mod.servux;

import verymc.top.veryMcProto.framework.debug.Debug;

/**
 * Servux mod 调试日志工具（mod 层）。替代原版 {@code Servux.debugLog}。
 *
 * <p>统一委托框架 {@link Debug#debug(String)}：开关来源收敛到 {@link Debug#master()} 宏开关
 * （命令 {@code /servux debug} 热切换 / 配置 {@code servux_main.debug_log} / 编译期 {@code Reference.DEV_DEBUG}）。
 *
 * <p><b>说明</b>：本类是无分类便捷入口（仅看 master 总开关）；需要分类的新日志点请直接用
 * {@code Debug.log(Cat.XXX, ...)}。
 */
public final class ServuxLog
{
    private ServuxLog() { }

    public static void debug(String msg)
    {
        try { Debug.debug(msg); }
        catch (Throwable ignored) { }
    }
}
