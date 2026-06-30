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
        for (IDataProvider p : DataProviderManager.INSTANCE.getAllProviders())
        {
            if (!p.isEnabled())
            {
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        ServerPlayer player = Nms.toNms(event.getPlayer());
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
