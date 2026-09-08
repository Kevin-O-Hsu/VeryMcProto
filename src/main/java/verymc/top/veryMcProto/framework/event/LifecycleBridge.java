package verymc.top.veryMcProto.framework.event;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.framework.dataproviders.IDataProvider;
import verymc.top.veryMcProto.framework.debug.FrameworkDebug;
import verymc.top.veryMcProto.framework.network.ChannelManager;
import verymc.top.veryMcProto.framework.nms.Nms;

/**
 * Bukkit 事件 → Provider 生命周期桥（框架层）。
 *
 * <p>替代原版由 Mixin 触发的 {@code ServerHandler} / {@code PlayerHandler} 分发：
 * <ul>
 *   <li>{@link ServerLoadEvent}(STARTED) → onCaptureImmutable(registryAccess) + readFromConfig + writeToConfig
 *       （对应原版 onServerStarting + onServerStarted；Paper ServerLoadEvent 只触发一次，故在此合并）；</li>
 *   <li>{@link PlayerJoinEvent}/{@link PlayerQuitEvent}/{@link PlayerRespawnEvent} → provider.onPlayerJoin/Quit/Respawn
 *       （对应原版 MixinPlayerManager 钩子）；</li>
 *   <li>每 tick {@link BukkitRunnable} → {@link DataProviderManager#tickProviders}（对应 MixinMinecraftServer.tickServer）。</li>
 * </ul>
 *
 * <p><b>防御性</b>：所有分发均包 try-catch，单个 provider 异常不影响其他 provider / 服务端。
 */
public class LifecycleBridge implements Listener
{
    private final Plugin plugin;
    private BukkitRunnable tickTask;
    private int tickCounter = 0;

    public LifecycleBridge(Plugin plugin)
    {
        this.plugin = plugin;
    }

    /** 启动每 tick 调度（runTaskTimer，1 tick 周期）。 */
    public void start()
    {
        tickTask = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                onTick();
            }
        };
        tickTask.runTaskTimer(plugin, 0L, 1L);
    }

    public void stop()
    {
        if (tickTask != null)
        {
            try
            {
                tickTask.cancel();
            }
            catch (Exception ignored) { }
            tickTask = null;
        }
    }

    private void onTick()
    {
        tickCounter++;
        try
        {
            MinecraftServer server = Nms.server();
            if (server != null)
            {
                // 先跑 tick 末尾前置钩子（task 组调度器驱动——对应上游 MixinMinecraftServer tickServer RETURN
                // 处 runTasks → tickProviders 的顺序），再跑 provider 周期 tick
                try
                {
                    DataProviderManager.INSTANCE.onServerTickEndPre();
                }
                catch (Exception e)
                {
                    Reference.logger().warning("onServerTickEndPre 异常: " + e.getMessage());
                }

                DataProviderManager.INSTANCE.tickProviders(server, tickCounter);
            }
        }
        catch (Exception e)
        {
            Reference.logger().warning("tickProviders 异常: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event)
    {
        // 不区分 STARTED / RELOAD：首次启动与 /reload 都重读配置 + 捕获 RegistryAccess（幂等，均合理）。
        try
        {
            MinecraftServer server = Nms.server();
            FrameworkDebug.log("lifecycle", "onServerLoad: 捕获 RegistryAccess → readFromConfig → writeToConfig");
            // 须在 server 完全启动后捕获 RegistryAccess，否则 Recipe / NbtView / palette 拿空注册表。
            DataProviderManager.INSTANCE.onCaptureImmutable(server.registryAccess());
            DataProviderManager.INSTANCE.readFromConfig();
            DataProviderManager.INSTANCE.writeToConfig();
            Reference.logger().info("[" + Reference.PLUGIN_NAME + "] 服务端启动完成，已捕获 RegistryAccess 并加载 servux.json。");
        }
        catch (Exception ex)
        {
            Reference.logger().warning("[" + Reference.PLUGIN_NAME + "] onServerLoad 异常: " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        ServerPlayer player = Nms.toNms(event.getPlayer());
        // 关键诊断点：configuration phase 下此时 getListeningPluginChannels 通常为空——
        // 客户端的 MC|Register（servux:* 声明）在配置阶段之后才到达，故此时直推 metadata 多半失败。
        FrameworkDebug.log("handshake", "@tick" + tickCounter + " onPlayerJoin: " + player.getName().getString()
                + " | 此时监听通道=" + event.getPlayer().getListeningPluginChannels());
        for (IDataProvider p : DataProviderManager.INSTANCE.getAllProviders())
        {
            if (!p.isEnabled())
            {
                FrameworkDebug.log("handshake", "  skip " + p.getName() + ".onPlayerJoin (provider disabled)");
                continue;
            }
            try
            {
                p.onPlayerJoin(player);
            }
            catch (Exception ex)
            {
                Reference.logger().warning("onPlayerJoin[" + p.getName() + "] 异常: " + ex.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        ServerPlayer player = Nms.toNms(event.getPlayer());
        FrameworkDebug.log("handshake", "onPlayerQuit: " + player.getName().getString() + " → 清理 provider 会话");
        for (IDataProvider p : DataProviderManager.INSTANCE.getAllProviders())
        {
            try
            {
                p.onPlayerQuit(player);
            }
            catch (Exception ex)
            {
                Reference.logger().warning("onPlayerQuit[" + p.getName() + "] 异常: " + ex.getMessage());
            }
        }
        // 通道级清理：撤销该玩家各通道的 C2S 证明（堵「同账号换 vanilla 客户端重进」时兜底误发的边缘）
        try
        {
            ChannelManager.INSTANCE.clearProven(event.getPlayer().getUniqueId());
        }
        catch (Exception ex)
        {
            Reference.logger().warning("onPlayerQuit[ChannelManager.clearProven] 异常: " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        ServerPlayer player = Nms.toNms(event.getPlayer());
        FrameworkDebug.log("handshake", "@tick" + tickCounter + " onPlayerRespawn: " + player.getName().getString()
                + " | 监听通道=" + event.getPlayer().getListeningPluginChannels());
        for (IDataProvider p : DataProviderManager.INSTANCE.getAllProviders())
        {
            if (!p.isEnabled())
            {
                continue;
            }
            try
            {
                p.onPlayerRespawn(player);
            }
            catch (Exception ex)
            {
                Reference.logger().warning("onPlayerRespawn[" + p.getName() + "] 异常: " + ex.getMessage());
            }
        }
    }

    /**
     * 客户端向服务端声明监听某通道时触发（masa mod 在 configuration phase 后注册 servux:*）。
     *
     * <p><b>命门</b>：1.20.2+ 后 {@link org.bukkit.event.player.PlayerJoinEvent} 时 {@code getListeningPluginChannels}
     * 尚为空，无法可靠识别「客户端装了对应 mod」。本事件是 configuration phase 完成后的可靠信号——
     * 分发给各 enabled provider 的 {@link IDataProvider#onPlayerRegisterChannel}，由其决定是否主动推送。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRegisterChannel(PlayerRegisterChannelEvent event)
    {
        String channel = event.getChannel();
        ServerPlayer player = Nms.toNms(event.getPlayer());
        // ★ 最关键诊断点：客户端声明监听某通道 = 装了对应 mod 的可靠信号（configuration phase 完成后到达）。
        // 记录声明的通道名 + 当前全部监听集合，可定位 entity/tweaks/litematics 的 metadata 为何 not_enabled：
        //   - 若客户端从不声明某 servux:* 通道 → 该 mod 未装（如未装 Tweakeroo/实体查询 mod）；
        //   - 若声明了但服务端无 provider 主动响应（sendMetadata）→ 该 provider 缺 onPlayerRegisterChannel 钩子。
        FrameworkDebug.log("handshake", "@tick" + tickCounter + " onPlayerRegisterChannel: " + player.getName().getString()
                + " 声明监听 → " + channel + " | 全部监听=" + event.getPlayer().getListeningPluginChannels());
        for (IDataProvider p : DataProviderManager.INSTANCE.getAllProviders())
        {
            if (!p.isEnabled())
            {
                continue;
            }
            try
            {
                p.onPlayerRegisterChannel(player, channel);
            }
            catch (Exception ex)
            {
                Reference.logger().warning("onPlayerRegisterChannel[" + p.getName() + "] 异常: " + ex.getMessage());
            }
        }
    }
}
