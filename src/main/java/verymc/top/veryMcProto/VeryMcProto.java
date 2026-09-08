package verymc.top.veryMcProto;

import org.bukkit.plugin.java.JavaPlugin;

import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.framework.event.LifecycleBridge;
import verymc.top.veryMcProto.framework.network.ChannelManager;

/**
 * VeryMcProto 主类 —— Fabric 协议 Mod 的 Paper 插件移植框架入口。
 *
 * <p>本类是<b>框架层</b>入口，与具体协议 mod 解耦：{@link #onEnable()} 装配框架基础设施
 * （网络层 / Provider 注册表 / 配置 / 生命周期事件 / tick 调度），再依次加载各协议 mod 模块
 * （首个为 {@code mod/servux}）。新增 Fabric 协议时，只需实现 {@code framework.ModModule} 并在此注册。
 *
 * <p><b>防御性</b>：所有装配均包 try-catch；任何模块初始化失败只记录日志、降级跳过，绝不影响服务端启动。
 */
public final class VeryMcProto extends JavaPlugin
{
    private LifecycleBridge lifecycleBridge;

    @Override
    public void onEnable()
    {
        Reference.init(this);
        Reference.logger().info("[" + Reference.PLUGIN_NAME + "] 启动中 (MC " + Reference.MC_VERSION + ", " + Reference.PLATFORM + ")...");

        try
        {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs())
            {
                Reference.logger().warning("无法创建插件数据目录: " + getDataFolder());
            }
        }
        catch (Exception e)
        {
            Reference.logger().warning("创建插件数据目录失败: " + e.getMessage());
        }

        try
        {
            // 框架初始化
            ChannelManager.INSTANCE.init(this);
            DataProviderManager.INSTANCE.setConfigDir(getDataFolder().toPath());

            // 生命周期事件 + tick 调度
            this.lifecycleBridge = new LifecycleBridge(this);
            getServer().getPluginManager().registerEvents(lifecycleBridge, this);
            lifecycleBridge.start();

            // 注册协议 mod 模块（首个：Servux）
            try
            {
                new verymc.top.veryMcProto.mod.servux.app.ServuxModule().onRegister(DataProviderManager.INSTANCE);
                Reference.logger().info("[" + Reference.PLUGIN_NAME + "] 已注册协议 mod: servux");
            }
            catch (Exception ex)
            {
                Reference.logger().severe("[" + Reference.PLUGIN_NAME + "] 注册 servux 模块失败: " + ex.getMessage());
            }

            // 注册协议 mod 模块（第二个：JEI Recipe Bridge —— 玩家进服时把服务端配方表同步给 JEI 客户端）
            try
            {
                new verymc.top.veryMcProto.mod.jeirecipebridge.app.JeiRecipeBridgeModule().onRegister(DataProviderManager.INSTANCE);
                Reference.logger().info("[" + Reference.PLUGIN_NAME + "] 已注册协议 mod: jei_recipe_bridge");
            }
            catch (Exception ex)
            {
                Reference.logger().severe("[" + Reference.PLUGIN_NAME + "] 注册 jei_recipe_bridge 模块失败: " + ex.getMessage());
            }

            // 注册协议 mod 模块（第三个：Syncmatica —— 投影共享中央仓库，单通道 + Exchange 会话）
            try
            {
                new verymc.top.veryMcProto.mod.syncmatica.app.SyncmaticaModule().enable(this);
                Reference.logger().info("[" + Reference.PLUGIN_NAME + "] 已注册协议 mod: syncmatica");
            }
            catch (Exception ex)
            {
                Reference.logger().severe("[" + Reference.PLUGIN_NAME + "] 注册 syncmatica 模块失败: " + ex.getMessage());
            }

            // 注册 /syncmatica 命令
            try
            {
                var syncmCmd = getCommand("syncmatica");
                if (syncmCmd != null)
                {
                    var syncmCtx = verymc.top.veryMcProto.mod.syncmatica.app.SyncmaticaModule.getInstance().getContext();
                    if (syncmCtx != null)
                    {
                        var syncmExecutor = new verymc.top.veryMcProto.mod.syncmatica.command.SyncmaticaCommand(syncmCtx);
                        syncmCmd.setExecutor(syncmExecutor);
                        syncmCmd.setTabCompleter(syncmExecutor);
                    }
                }
            }
            catch (Exception ex)
            {
                Reference.logger().warning("[" + Reference.PLUGIN_NAME + "] 注册 /syncmatica 命令失败: " + ex.getMessage());
            }

            // 注册 /servux 命令
            try
            {
                var cmd = getCommand("servux");
                if (cmd != null)
                {
                    var executor = new verymc.top.veryMcProto.mod.servux.command.ServuxCommand();
                    cmd.setExecutor(executor);
                    cmd.setTabCompleter(executor);
                }
            }
            catch (Exception ex)
            {
                Reference.logger().warning("[" + Reference.PLUGIN_NAME + "] 注册 /servux 命令失败: " + ex.getMessage());
            }

            // 注册 /jei 命令（JEI Recipe Bridge：启用/禁用进服配方同步）
            try
            {
                var jeiCmd = getCommand("jei");
                if (jeiCmd != null)
                {
                    var executor = new verymc.top.veryMcProto.mod.jeirecipebridge.command.JeiCommand();
                    jeiCmd.setExecutor(executor);
                    jeiCmd.setTabCompleter(executor);
                }
            }
            catch (Exception ex)
            {
                Reference.logger().warning("[" + Reference.PLUGIN_NAME + "] 注册 /jei 命令失败: " + ex.getMessage());
            }

            Reference.logger().info("[" + Reference.PLUGIN_NAME + "] 框架就绪。");
        }
        catch (Exception e)
        {
            Reference.logger().severe("[" + Reference.PLUGIN_NAME + "] 框架初始化失败: " + e.getMessage());
        }
    }

    @Override
    public void onDisable()
    {
        try
        {
            if (lifecycleBridge != null)
            {
                lifecycleBridge.stop();
            }
            // task 组调度器清理须在 onServerTickEndPre() 之前（否则停服先空跑一轮任务再清）
            verymc.top.veryMcProto.mod.servux.scheduler.TaskScheduler.getInstance().clearTasks();
            DataProviderManager.INSTANCE.writeToConfig();
            DataProviderManager.INSTANCE.onServerTickEndPre();
            // syncmatica 卸载（shutdown 保存 placements.json + 注销 handler；须在 ChannelManager.unregisterAll 前）
            try
            {
                verymc.top.veryMcProto.mod.syncmatica.app.SyncmaticaModule.getInstance().disable();
            }
            catch (Exception e) { Reference.logger().warning("[" + Reference.PLUGIN_NAME + "] syncmatica disable 异常（placements.json shutdown 保存可能失败）: " + e.getMessage()); }
            ChannelManager.INSTANCE.unregisterAll();
        }
        catch (Exception e)
        {
            Reference.logger().warning("[" + Reference.PLUGIN_NAME + "] 卸载过程异常: " + e.getMessage());
        }
        Reference.logger().info("[" + Reference.PLUGIN_NAME + "] 已卸载。");
    }
}
