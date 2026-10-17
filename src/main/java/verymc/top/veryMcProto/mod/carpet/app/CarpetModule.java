package verymc.top.veryMcProto.mod.carpet.app;

import org.bukkit.Bukkit;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.ModModule;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.mod.carpet.CarpetReference;
import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.listener.CarpetRuleListener;
import verymc.top.veryMcProto.mod.carpet.logging.CleanLogsFilter;
import verymc.top.veryMcProto.mod.carpet.settings.SettingsManager;

/**
 * Carpet 模块装配（mod 层，第四模块——gnembon/fabric-carpet 26.2 线移植 Phase 1「模块地基」）。
 *
 * <p>上游是<b>行为注入型</b> Fabric mod（171 服务端 Mixin），本移植按 scope 判定面走事件/等价路径
 * （docs/carpet-porting-scope.md、docs/carpet-architecture.md）。装配链：
 * SettingsManager 单例 → 解析规则 → conf 载入 → perf 权限应用 → 事件监听 → cleanLogs 过滤器。
 *
 * <p>生命周期：{@link #onRegister} 由主类 onEnable 第四 try-catch 块调用；{@link #disable()} 由
 * onDisable 调用（CleanLogsFilter 为 log4j 全局资源，必须显式摘除防插件 reload 类加载器泄漏）。
 */
public final class CarpetModule implements ModModule
{
    private static volatile SettingsManager settingsManager;

    @Override
    public String getModId()
    {
        return CarpetReference.MOD_ID;
    }

    @Override
    public String getModString()
    {
        return CarpetReference.MOD_STRING;
    }

    @Override
    public void onRegister(DataProviderManager manager)
    {
        // carpet 无 Provider 概念（上游 SettingsManager 体系与 servux 的 DataProvider 抽象无对应物）——
        // manager 形参按 ModModule 契约接收但不使用（诚实降级，见 docs/carpet-architecture.md）
        SettingsManager sm = new SettingsManager(
                CarpetReference.CARPET_VERSION, CarpetReference.MOD_ID, "Carpet Mod",
                Reference.plugin().getDataFolder().toPath());
        settingsManager = sm;
        sm.parseSettingsClass(CarpetSettings.class);
        sm.loadConfigurationFromConf();

        // perfPermissionLevel 的启动期应用由 loadConfigurationFromConf 内的 resetToDefault →
        // PermissionValidator 副作用承载（上游同构——validator 即副作用载体），此处不再显式调用

        // 行为规则事件挂点（常驻注册 + 事件时读值）
        Bukkit.getPluginManager().registerEvents(new CarpetRuleListener(), Reference.plugin());

        // cleanLogs 日志过滤器（install 幂等）
        CleanLogsFilter.install();
    }

    /** 主类 onDisable 调用（须无异常抛出）。 */
    public static void disable()
    {
        CleanLogsFilter.uninstall();
    }

    public static SettingsManager getSettingsManager()
    {
        return settingsManager;
    }

    /**
     * perfPermissionLevel 应用（上游 = {@code PerfCommand_permissionMixin} @Redirect 换 /perf 的
     * requires；Paper 无 Mixin 无 requires 替换面）。<b>只读登记</b>——零变异，定位
     * {@code minecraft.command.perf} 节点后输出真因果日志（26.2 映射源实证，docs/carpet-architecture.md）：
     * 值 4（默认）= Paper 天然同构（vanilla /perf requires LEVEL_OWNERS 原样保留）；
     * 值 2 = 结构性不可达；玩家聊天命令链（PlayerCommandPreprocessEvent → Commands.performCommand）
     * 直达 brigadier 树、绕过 bukkit commandMap/testPermission，节点授权对玩家无效；
     * /perf 访问控制面 = ops.json 的 op level。
     */
    public static void applyPerfPermission(int value)
    {
        // 纯 JVM 单测环境（Reference.plugin() == null）无 Bukkit 运行时——validator 链在
        // resetToDefault 时会触达此处，跳过登记（上游 validator 的等价副作用是纯静态字段写，无环境依赖）
        if (Reference.plugin() == null)
        {
            return;
        }
        var node = Bukkit.getPluginManager().getPermission("minecraft.command.perf");
        Reference.logger().info("[Carpet] perfPermissionLevel is " + value
                + ". On Paper, value 4 (default) is natively equivalent (vanilla /perf requires permission level 4);"
                + " value 2 cannot be expressed (upstream relaxes it via a Mixin redirect, unavailable in a plugin)."
                + " Control /perf access via op levels (ops.json). The minecraft.command.perf node"
                + (node != null ? " exists (default " + node.getDefault() + ") and only affects commandMap"
                        + " dispatch paths (console/API), not player chat commands."
                        : " is not registered; it would only affect commandMap dispatch paths (console/API),"
                                + " not player chat commands."));
    }
}
