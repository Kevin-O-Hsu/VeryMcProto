package verymc.top.veryMcProto.mod.syncmatica.app;

import java.nio.file.Path;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.framework.network.ServerPlayHandler;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaReference;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.communication.ServerCommunicationManager;
import verymc.top.veryMcProto.mod.syncmatica.data.FileStorage;
import verymc.top.veryMcProto.mod.syncmatica.data.SyncmaticManager;
import verymc.top.veryMcProto.mod.syncmatica.network.SyncmaticaHandler;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;

/**
 * Syncmatica 协议 mod 装配模块（Paper 新增，对应原版 {@code MixinMinecraftServer}/{@code Syncmatica.initServer}）。
 *
 * <p>不实现 {@code framework.ModModule}（那是 Servux 的 provider 模型）；syncmatica 是 exchange 模型，
 * 由本类直接装配：构造 {@link SyncmaticaContext} → 注册 {@code syncmatica:main} 通道 → 注册玩家进出服监听。
 *
 * <p>由主类 {@code VeryMcProto.onEnable} / {@code onDisable} 调用。命令注册在主类（需 {@code getCommand}）。
 *
 * <p><b>防御性</b>：enable/disable 全程 try-catch（主类已包一层，此处 listener 回调内再包一层，避免单玩家异常影响其他）。
 */
public class SyncmaticaModule
{
    private static SyncmaticaModule instance;

    private SyncmaticaContext context;
    private SyncmaticaHandler handler;
    private Listener playerListener;

    public static SyncmaticaModule getInstance() { return instance; }

    public SyncmaticaContext getContext() { return context; }

    public void enable(final JavaPlugin plugin)
    {
        instance = this;

        final Path dataFolder = plugin.getDataFolder().toPath();
        final Path litematicFolder = dataFolder.resolve(SyncmaticaReference.LITEMATIC_SUBDIR);
        final Path configFolder = dataFolder; // 配置直接放插件根目录

        final FileStorage fileStorage = new FileStorage(litematicFolder);
        final ServerCommunicationManager comMan = new ServerCommunicationManager();
        final SyncmaticManager synMan = new SyncmaticManager();
        context = new SyncmaticaContext(plugin, fileStorage, comMan, synMan, litematicFolder, configFolder);

        // 启动（加载 placements.json + 启动 service）
        context.startup();

        // 注册 syncmatica:main 通道 + handler（incoming + outgoing）
        handler = new SyncmaticaHandler(context);
        ServerPlayHandler.getInstance().registerServerPlayHandler(handler);

        // 玩家进出服监听（对应原版 MixinPlayerManager.placeNewPlayer + MixinServerPlayNetworkHandler.onDisconnect）
        final ServerCommunicationManager fComMan = comMan;
        playerListener = new Listener()
        {
            @EventHandler
            public void onJoin(final PlayerJoinEvent e)
            {
                try
                {
                    SyncmaticaDebug.log(SyncmaticaDebug.Cat.LIFECYCLE, "[syncm] PlayerJoinEvent: " + e.getPlayer().getName()
                            + "（注册 target；握手双保险：onJoin 延迟 40t 主路径 + onPlayerRegisterChannel 兜底）");
                    final ExchangeTarget target = fComMan.getOrCreateTarget(e.getPlayer());
                    fComMan.onPlayerJoin(target);

                    // ★ 命门修复：把握手从「仅依赖 onPlayerRegisterChannel」改为双保险（与 servux HudDataProvider 一致）。
                    // 1.21.x Fabric 客户端（syncmatica 经 PayloadTypeRegistry.playS2C() 声明通道）不通过 Bukkit
                    // 旧式 MC|Register 机制声明 → PlayerRegisterChannelEvent 不触发 → 单路径下 tryStartHandshake
                    // 永不调用 → syncmatica 完全不可用（实测：玩家进服后无任何 HANDSHAKE 日志）。
                    // 主路径改为 onPlayerJoin 延迟 40t（2s，等 configuration phase 完成、客户端 codec 就绪），
                    // onPlayerRegisterChannel 保留为加速/兜底（若事件触发则立即握手，tryStartHandshake 幂等）。
                    final org.bukkit.entity.Player bukkitPlayer = e.getPlayer();
                    new BukkitRunnable()
                    {
                        @Override
                        public void run()
                        {
                            if (!bukkitPlayer.isOnline())
                            {
                                return; // 玩家在延迟窗口内离线，放弃（避免孤儿 exchange）
                            }
                            try
                            {
                                fComMan.tryStartHandshake(target);
                            }
                            catch (Exception ex)
                            {
                                SyncmaticaLog.error("syncmatica 延迟握手失败 for {}", ex, bukkitPlayer.getName());
                            }
                        }
                    }.runTaskLater(context.getPlugin(), 40L);
                }
                catch (Exception ex)
                {
                    SyncmaticaLog.error("syncmatica onPlayerJoin failed for {}", ex, e.getPlayer().getName());
                }
            }

            @EventHandler
            public void onQuit(final PlayerQuitEvent e)
            {
                try
                {
                    SyncmaticaDebug.log(SyncmaticaDebug.Cat.LIFECYCLE, "[syncm] PlayerQuitEvent: " + e.getPlayer().getName() + " → onPlayerLeave");
                    final UUID id = e.getPlayer().getUniqueId();
                    final ExchangeTarget target = fComMan.getTarget(id);
                    if (target != null)
                    {
                        fComMan.onPlayerLeave(target);
                    }
                }
                catch (Exception ex)
                {
                    SyncmaticaLog.error("syncmatica onPlayerLeave failed", ex);
                }
            }

            @EventHandler
            public void onRegisterChannel(final PlayerRegisterChannelEvent e)
            {
                // 兜底/加速路径：若客户端通过 MC|Register 声明了 syncmatica:main（旧式协商），立即握手。
                // 注意：1.21.x Fabric 客户端（PayloadTypeRegistry.playS2C() 新式协商）通常【不】触发本事件，
                // 故握手的主路径在 onJoin 的 40t 延迟（见上），此处仅作加速/兜底。tryStartHandshake 幂等，
                // 两路径安全共存。
                if (!SyncmaticaReference.NETWORK_ID.toString().equals(e.getChannel()))
                {
                    return;
                }
                try
                {
                    SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] onPlayerRegisterChannel: " + e.getPlayer().getName()
                            + " 声明 syncmatica:main → tryStartHandshake");
                    final ExchangeTarget target = fComMan.getOrCreateTarget(e.getPlayer());
                    fComMan.tryStartHandshake(target);
                }
                catch (Exception ex)
                {
                    SyncmaticaLog.error("syncmatica onPlayerRegisterChannel failed for {}", ex, e.getPlayer().getName());
                }
            }
        };
        Bukkit.getPluginManager().registerEvents(playerListener, plugin);

        SyncmaticaLog.info("Syncmatica 模块已启用（通道 {} / 投影目录 {}）", SyncmaticaReference.NETWORK_ID, litematicFolder);
    }

    public void disable()
    {
        try
        {
            if (context != null)
            {
                context.shutdown();
            }
        }
        catch (Exception e)
        {
            SyncmaticaLog.error("syncmatica context.shutdown failed", e);
        }
        try
        {
            if (handler != null)
            {
                ServerPlayHandler.getInstance().unregisterServerPlayHandler(handler);
            }
        }
        catch (Exception e)
        {
            SyncmaticaLog.error("syncmatica unregister handler failed", e);
        }
    }
}
