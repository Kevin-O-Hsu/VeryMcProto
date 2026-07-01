package verymc.top.veryMcProto.mod.syncmatica.util;

import java.util.logging.Level;
import verymc.top.veryMcProto.Reference;

/**
 * Syncmatica 业务日志 shim（替代原版 {@code ch.endte.syncmatica.Syncmatica.LOGGER}）—— <b>仅业务日志</b>。
 *
 * <p>原版用 log4j（支持 {@code {}} 占位符多参重载）；Paper 用 JUL（{@code java.util.logging}，不支持 {@code {}} 多参）。
 * 本 shim 承接 SLF4J 风格的 {@code {}} 占位，转发到项目主 logger {@link Reference#logger()}。
 *
 * <p>用法对应：{@code Syncmatica.LOGGER.warn("...{}...", arg)} → {@code SyncmaticaLog.warn("...{}...", arg)}。
 *
 * <p><b>职责边界</b>：本类只承载 <b>业务日志</b>（warn / error / info）。<b>调试日志</b>（受分类开关控制）请走
 * {@link SyncmaticaDebug}：{@code SyncmaticaDebug.log(SyncmaticaDebug.Cat.XXX, "...")}。原 {@code debug} 方法已删除
 * （曾是无分类后门——走 JUL {@code fine}，{@code /syncmatica debug cat none} 后仍输出，违背分类管控）；
 * 数据/文件相关的调试日志已迁至 {@code SyncmaticaDebug.log(Cat.DATA, ...)}。
 */
public final class SyncmaticaLog
{
    private SyncmaticaLog() { }

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
