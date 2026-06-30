package verymc.top.veryMcProto.mod.servux.util;

import verymc.top.veryMcProto.Reference;

/**
 * SLF4J 风格 logger shim（JUL 后端，支持 {@code {}} 占位符）。
 *
 * <p>移植期兼容原版 {@code Servux.LOGGER.warn/error/info(msg, args)} 与 {@code Servux.debugLog(msg, args)}
 * 调用——Paper 端 {@link Reference#logger()} 返回 JUL {@code java.util.logging.Logger}，不支持 SLF4J 占位符重载，
 * 故用本 shim 统一承接，避免逐处手动拼接。
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
    public static void debugLog(String msg, Object... args) { Reference.logger().fine(fmt(msg, args)); }
}
