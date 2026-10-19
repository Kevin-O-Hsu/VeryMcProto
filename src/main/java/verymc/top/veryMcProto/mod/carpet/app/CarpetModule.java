package verymc.top.veryMcProto.mod.carpet.app;

import org.bukkit.Bukkit;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.ModModule;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.framework.network.ChannelManager;
import verymc.top.veryMcProto.mod.carpet.CarpetReference;
import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.listener.CarpetRuleListener;
import verymc.top.veryMcProto.mod.carpet.logging.CleanLogsFilter;
import verymc.top.veryMcProto.mod.carpet.network.CarpetHelloHandler;
import verymc.top.veryMcProto.mod.carpet.settings.SettingsManager;

/**
 * Carpet 模块装配（mod 层，第四模块——gnembon/fabric-carpet 26.2 线移植 Phase 1「模块地基」）。
 *
 * <p>上游是<b>行为注入型</b> Fabric mod（171 服务端 Mixin），本移植按 scope 判定面走事件/等价路径
 * （docs/carpet/architecture.md）。装配链：
 * SettingsManager 单例 → 解析规则 → conf 载入 → perf 权限应用 → 事件监听 → cleanLogs 过滤器。
 *
 * <p>生命周期：{@link #onRegister} 由主类 onEnable 第四 try-catch 块调用；{@link #disable()} 由
 * onDisable 调用（CleanLogsFilter 为 log4j 全局资源，必须显式摘除防插件 reload 类加载器泄漏）。
 */
public final class CarpetModule implements ModModule
{
    private static volatile SettingsManager settingsManager;
    /** carpet:hello 协议处理器（Phase 3；disable 置 null——null = 已卸载）。 */
    private static volatile CarpetHelloHandler carpetHelloHandler;
    /** /log 订阅持久册（Phase 4；disable 置 null）。 */
    private static volatile verymc.top.veryMcProto.mod.carpet.logging.LogSubscriptionStore logStore;

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
        // manager 形参按 ModModule 契约接收但不使用（诚实降级，见 docs/carpet/architecture.md）
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

        // 假人子系统（Phase 2）：侧表 tick 驱动 + quit/respawn/velocity 事件处理
        Bukkit.getPluginManager().registerEvents(new verymc.top.veryMcProto.mod.carpet.listener.FakePlayerListener(), Reference.plugin());
        verymc.top.veryMcProto.mod.carpet.helpers.ActionPackManager.start(Reference.plugin());

        // /player 命令（Paper Brigadier API——上游 brigadier 树的 B 侧注册面；requires = commandPlayer
        // 规则门，plugin.yml 无条目）。注册面失败仅降级日志，不影响规则框架
        try
        {
            Reference.plugin().getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(verymc.top.veryMcProto.mod.carpet.command.PlayerCommand.build()));
        }
        catch (Throwable t)
        {
            Reference.logger().severe("[Carpet] failed to register /player command: " + t.getMessage());
        }

        // cleanLogs 日志过滤器（install 幂等）
        CleanLogsFilter.install();

        // carpet:hello 协议（Phase 3）：通道注册（incoming 接收 + outgoing 声明成对，黄金模板
        // JeiModule.enable 注册形态）→ join/quit 监听 → 规则推送回调挂载 → 在线玩家补扫 HI。
        // 插件中途启用（reload）时在线玩家无 join 事件——补发 HI 闭合握手空窗（上游 init 期无在线
        // 玩家、无此路径，B 侧增补，偏差登记 docs/carpet/protocol.md）
        carpetHelloHandler = new CarpetHelloHandler();
        ChannelManager.INSTANCE.register(CarpetReference.CARPET_CHANNEL, carpetHelloHandler);
        carpetHelloHandler.setPlayRegistered(CarpetReference.CARPET_CHANNEL);
        Bukkit.getPluginManager().registerEvents(carpetHelloHandler, Reference.plugin());
        sm.setRuleChangedCallback(carpetHelloHandler::onRuleChanged);
        try
        {
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers())
            {
                carpetHelloHandler.sendHi(Nms.toNms(online));
            }
        }
        catch (Throwable t)
        {
            Reference.logger().warning("[Carpet] failed to send HI to already-online players: " + t.getMessage());
        }

        // /log 日志子系统（Phase 4）：注册 tps/mobcaps 两 logger → 持久册载入 → join/quit 监听 →
        // /log 命令注册（Brigadier，plugin.yml 无条目）→ HUD 20 tick 任务 → 在线玩家补扫（reload 时
        // 无 join 事件，hello 补扫同款 B 侧增补）。整块失败只降级日志，不影响规则框架与其余子系统
        try
        {
            verymc.top.veryMcProto.mod.carpet.logging.LogSubscriptionStore store =
                    new verymc.top.veryMcProto.mod.carpet.logging.LogSubscriptionStore(
                            Reference.plugin().getDataFolder().toPath());
            store.load();
            logStore = store;
            verymc.top.veryMcProto.mod.carpet.logging.LoggerRegistry.initLoggers();
            verymc.top.veryMcProto.mod.carpet.logging.LoggerListener logListener =
                    new verymc.top.veryMcProto.mod.carpet.logging.LoggerListener(store);
            Bukkit.getPluginManager().registerEvents(logListener, Reference.plugin());
            Reference.plugin().getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(
                            verymc.top.veryMcProto.mod.carpet.command.LogCommand.build(store)));
            verymc.top.veryMcProto.mod.carpet.logging.HUDController.start(Reference.plugin());
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers())
            {
                logListener.handleJoin(online);
            }
        }
        catch (Throwable t)
        {
            Reference.logger().severe("[Carpet] failed to start /log subsystem: " + t.getMessage());
        }

        // Phase 5 命令族（纯计算类）：/distance /info /spawn 三条免上下文命令逐条注册（plugin.yml
        // 无条目，Brigadier 自足）。逐命令独立 try-catch——单命令注册失败只降级，不影响其余命令与子系统
        try
        {
            Reference.plugin().getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(
                            verymc.top.veryMcProto.mod.carpet.command.DistanceCommand.build()));
        }
        catch (Throwable t)
        {
            Reference.logger().severe("[Carpet] failed to register /distance command: " + t.getMessage());
        }
        try
        {
            Reference.plugin().getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(
                            verymc.top.veryMcProto.mod.carpet.command.InfoCommand.build()));
        }
        catch (Throwable t)
        {
            Reference.logger().severe("[Carpet] failed to register /info command: " + t.getMessage());
        }
        try
        {
            Reference.plugin().getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(
                            verymc.top.veryMcProto.mod.carpet.command.SpawnCommand.build()));
        }
        catch (Throwable t)
        {
            Reference.logger().severe("[Carpet] failed to register /spawn command: " + t.getMessage());
        }
        // /perimeterinfo 与 /draw：参数树含 BlockState/BlockPredicate/Resource 参数类（上游由 vanilla
        // Commands 构建期注入 CommandBuildContext）——B 侧等价 = CommandBuildContext.simple 公开
        // 静态工厂（registryAccess + VANILLA_SET，javap 实证），commandContext() 懒加载单点
        try
        {
            Reference.plugin().getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(
                            verymc.top.veryMcProto.mod.carpet.command.PerimeterInfoCommand.build(commandContext())));
        }
        catch (Throwable t)
        {
            Reference.logger().severe("[Carpet] failed to register /perimeterinfo command: " + t.getMessage());
        }
        try
        {
            Reference.plugin().getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(
                            verymc.top.veryMcProto.mod.carpet.command.DrawCommand.build(commandContext())));
        }
        catch (Throwable t)
        {
            Reference.logger().severe("[Carpet] failed to register /draw command: " + t.getMessage());
        }
    }

    /** 主类 onDisable 调用（须无异常抛出；须在框架 ChannelManager.unregisterAll 之前——jei 先例）。 */
    public static void disable()
    {
        // carpet:hello 协议清理：注销通道（名册清空随 shutdown；上游 close :173-177 同构）
        try
        {
            if (carpetHelloHandler != null)
            {
                ChannelManager.INSTANCE.unregister(CarpetReference.CARPET_CHANNEL);
                carpetHelloHandler.shutdown();
                carpetHelloHandler = null;
            }
        }
        catch (Exception e)
        {
            Reference.logger().warning("[Carpet] hello handler shutdown exception: " + e.getMessage());
        }
        // 假人子系统清理：kill 全假人（NMS 对象引用插件类，reload 后存活假人将 NoClassDefFoundError）→ 停驱动 → 清侧表
        try
        {
            verymc.top.veryMcProto.mod.carpet.helpers.ActionPackManager.shutdown();
        }
        catch (Exception e)
        {
            Reference.logger().warning("[Carpet] action pack shutdown exception: " + e.getMessage());
        }
        // /log 子系统清理（Phase 4）：停 HUD 任务并清在线订阅者 footer（须先于 stopLoggers——订阅名册
        // 仍存活）→ 持久册兜底落盘（对冲最后一次变更写失败窗口）→ 注册表清空
        try
        {
            verymc.top.veryMcProto.mod.carpet.logging.HUDController.shutdown();
            if (logStore != null)
            {
                logStore.save();
                logStore = null;
            }
            verymc.top.veryMcProto.mod.carpet.logging.LoggerRegistry.stopLoggers();
        }
        catch (Exception e)
        {
            Reference.logger().warning("[Carpet] /log subsystem shutdown exception: " + e.getMessage());
        }
        CleanLogsFilter.uninstall();
    }

    public static SettingsManager getSettingsManager()
    {
        return settingsManager;
    }

    /** CommandBuildContext 懒加载单点（/perimeterinfo /draw 参数类工厂入参；registryAccess 只读快照可安全缓存）。 */
    private static volatile net.minecraft.commands.CommandBuildContext commandBuildContext;

    private static net.minecraft.commands.CommandBuildContext commandContext()
    {
        net.minecraft.commands.CommandBuildContext ctx = commandBuildContext;
        if (ctx == null)
        {
            synchronized (CarpetModule.class)
            {
                if (commandBuildContext == null)
                {
                    commandBuildContext = net.minecraft.commands.CommandBuildContext.simple(
                            Nms.server().registryAccess(),
                            net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
                }
                ctx = commandBuildContext;
            }
        }
        return ctx;
    }

    /**
     * perfPermissionLevel 应用（上游 = {@code PerfCommand_permissionMixin} @Redirect 换 /perf 的
     * requires；Paper 无 Mixin 无 requires 替换面）。<b>只读登记</b>——零变异，定位
     * {@code minecraft.command.perf} 节点后输出真因果日志（26.2 映射源实证，docs/carpet/architecture.md）：
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
