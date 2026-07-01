package verymc.top.veryMcProto.mod.syncmatica.util;

import java.util.logging.Level;
import verymc.top.veryMcProto.Reference;

/**
 * Syncmatica 日志门面（替代原版 {@code ch.endte.syncmatica.Syncmatica.LOGGER}）。
 *
 * <p>原版用 log4j（支持 {@code {}} 占位符多参重载）；Paper 用 JUL（{@code java.util.logging}，不支持 {@code {}} 多参）。
 * 本 shim 承接 SLF4J 风格的 {@code {}} 占位，转发到项目主 logger {@link Reference#logger()}。
 *
 * <p>用法对应：{@code Syncmatica.LOGGER.warn("...{}...", arg)} → {@code SyncmaticaLog.warn("...{}...", arg)}。
 * 同 servux 的 {@code mod/servux/util/Log.java} 设计。
 */
public final class SyncmaticaLog
{
    private SyncmaticaLog() { }

    public static void debug(String msg, Object... args)
    {
        Reference.logger().fine(format(msg, args));
    }

    public static void info(String msg, Object... args)
    {
        Reference.logger().info(format(msg, args));
    }

    public static void warn(String msg, Object... args)
    {
        Reference.logger().warning(format(msg, args));
    }

    public static void error(String msg, Object... args)
    {
        Reference.logger().severe(format(msg, args));
    }

    /** 带异常栈的 error（对应原版 {@code LOGGER.error(msg, exception)}）。 */
    public static void error(String msg, Throwable throwable, Object... args)
    {
        Reference.logger().log(Level.SEVERE, format(msg, args), throwable);
    }

    /** SLF4J 风格 {@code {}} 占位替换。 */
    private static String format(String msg, Object... args)
    {
        if (msg == null)
        {
            return "null";
        }
        if (args == null || args.length == 0)
        {
            return msg;
        }
        final StringBuilder sb = new StringBuilder(msg.length() + 16);
        int argIndex = 0;
        for (int i = 0; i < msg.length(); i++)
        {
            final char c = msg.charAt(i);
            if (c == '{' && i + 1 < msg.length() && msg.charAt(i + 1) == '}' && argIndex < args.length)
            {
                sb.append(args[argIndex++]);
                i++; // 跳过 '}'
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
