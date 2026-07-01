package verymc.top.veryMcProto.mod.servux.util;

import verymc.top.veryMcProto.Reference;

/**
 * SLF4J 风格 logger shim（JUL 后端，支持 {@code {}} 占位符）—— <b>仅业务日志</b>。
 *
 * <p>移植期兼容原版 {@code Servux.LOGGER.warn/error/info(msg, args)} 调用——Paper 端 {@link Reference#logger()}
 * 返回 JUL {@code java.util.logging.Logger}，不支持 SLF4J 占位符重载，故用本 shim 统一承接，避免逐处手动拼接。
 *
 * <p><b>职责边界</b>：本类只承载 <b>业务日志</b>（warn / error / info）——告警、错误、关键事件。
 * <b>调试日志</b>（受分类开关控制）请走 {@link verymc.top.veryMcProto.mod.servux.ServuxDebug}：
 * {@code ServuxDebug.log(ServuxDebug.Cat.XXX, "...")}。原 {@code debugLog} 已删除（曾是无分类后门——
 * 走 JUL {@code fine}，{@code /servux debug cat none} 后仍输出，违背分类管控）；schematic 系统的调试日志
 * 已迁至 {@code ServuxDebug.log(Cat.SCHEMATIC, ...)}。
 */
public final class Log
{
    private Log() {}

    private static String fmt(String msg, Object... args)
    {
        if (args == null || args.length == 0)
        {
            return msg;
        }

        for (Object a : args)
        {
            int i = msg.indexOf("{}");
            if (i < 0)
            {
                break;
            }
            msg = msg.substring(0, i) + (a == null ? "null" : a.toString()) + msg.substring(i + 2);
        }

        return msg;
    }

    public static void warn(String msg, Object... args) { Reference.logger().warning(fmt(msg, args)); }
    public static void error(String msg, Object... args) { Reference.logger().severe(fmt(msg, args)); }
    public static void info(String msg, Object... args) { Reference.logger().info(fmt(msg, args)); }
}
