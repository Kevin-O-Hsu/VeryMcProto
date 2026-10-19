package verymc.top.veryMcProto.mod.carpet.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.carpet.CarpetSettings;

/**
 * cleanLogs 规则的 log4j 过滤器（scope §5.1 指定机制——仓内无先例新建；上游真机制 =
 * {@code carpet/mixins/LivingEntity_cleanLogsMixin.java:23-27} 的 {@code hasCustomName()} Redirect 代数）。
 *
 * <p><b>代数（勿改极性）</b>：上游使 die() 内的命名实体死亡日志仅在
 * {@code hasCustomName ∧ cleanLogs ∧ SHOW_DEATH_MESSAGES(实体世界)} 时发出；Paper 侧该日志行为
 * vanilla 原生（{@code LivingEntity} 的 {@code "Named entity ... died"} 行），本过滤器把不满足
 * {@code cleanLogs ∧ 实体世界 gamerule} 的该行 DENY——即上游代数的取反等价。
 * <b>cleanLogs 默认 false = 抑制（“干净日志”）；true = 放行 vanilla 命名死亡日志。</b>
 *
 * <p>匹配面：logger 名 + 消息前缀（26.1 映射源实证 {@code net.minecraft.world.entity.LivingEntity} /
 * {@code "Named entity "}，全 jar 唯一来源）；死亡实体取 {@code LogEvent} 参数[0]（vanilla
 * {@code LOGGER.info("Named entity {} died: {}", this, ...)} 传入实体本体）→ 其世界 gamerule 精确判定
 * （无 anyWorld 近似）；参数缺失/异常 → 保守放行（宁可漏滤不误滤）。
 *
 * <p><b>生命周期</b>：root LoggerConfig {@code addFilter + updateLoggers} 安装、
 * {@code removeFilter + updateLoggers} 摘除（API 事实：addFilter 后必须 updateLoggers 方生效；
 * 不摘除会钉住旧插件类加载器——reload 泄漏）。log4j-core 为 Paper 运行时自带（非可选依赖，
 * dev bundle serverCompileClasspath 编译可达），无需仓库教训 5 的隔离引导。
 */
public final class CleanLogsFilter extends AbstractFilter
{
    private static final String DEATH_LOGGER_NAME = "net.minecraft.world.entity.LivingEntity";
    private static final String DEATH_PREFIX = "Named entity ";

    /** 已安装实例（disable 摘除用；synchronized install/uninstall 防双装叠加）。 */
    private static volatile CleanLogsFilter installed;

    private CleanLogsFilter()
    {
    }

    public static synchronized void install()
    {
        if (installed != null)
        {
            return;
        }
        CleanLogsFilter filter = new CleanLogsFilter();
        try
        {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().addFilter(filter);
            ctx.updateLoggers();
            installed = filter;
        }
        catch (Throwable t)
        {
            Reference.logger().warning("[Carpet] failed to install cleanLogs filter: " + t.getMessage());
        }
    }

    public static synchronized void uninstall()
    {
        CleanLogsFilter filter = installed;
        if (filter == null)
        {
            return;
        }
        installed = null;
        try
        {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeFilter(filter);
            ctx.updateLoggers();
        }
        catch (Throwable t)
        {
            Reference.logger().warning("[Carpet] failed to remove cleanLogs filter (left neutralized): " + t.getMessage());
        }
    }

    @Override
    public Result filter(LogEvent event)
    {
        try
        {
            if (!DEATH_LOGGER_NAME.equals(event.getLoggerName()))
            {
                return Result.NEUTRAL;
            }
            String message = event.getMessage().getFormattedMessage();
            if (message == null || !message.startsWith(DEATH_PREFIX))
            {
                return Result.NEUTRAL;
            }
            // 上游代数：EMIT = cleanLogs ∧ SHOW_DEATH_MESSAGES(实体世界)；DENY = 其否定
            if (CarpetSettings.cleanLogs && entityWorldShowsDeathMessages(event))
            {
                return Result.NEUTRAL;
            }
            return Result.DENY;
        }
        catch (Throwable t)
        {
            return Result.NEUTRAL; // 过滤器自身异常一律放行（保守）
        }
    }

    private static boolean entityWorldShowsDeathMessages(LogEvent event)
    {
        try
        {
            Object[] params = event.getMessage().getParameters();
            if (params != null && params.length > 0 && params[0] instanceof LivingEntity living)
            {
                // 26.1 映射面：getGameRules() 在 ServerLevel（上游 mixin :26 同判——level() instanceof ServerLevel）
                if (living.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)
                {
                    return serverLevel.getGameRules().get(GameRules.SHOW_DEATH_MESSAGES);
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        return true;
    }
}
