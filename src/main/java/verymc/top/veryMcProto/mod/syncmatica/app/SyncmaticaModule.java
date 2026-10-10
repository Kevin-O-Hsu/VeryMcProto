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

                    // ★ 认知修正（静态全链实证，勿按旧注释回改）：本 40t 延迟探针是 syncmatica 握手的【唯一引导路径】。
                    // 旧注释「1.21.x 客户端不通过 MC|Register 声明 → 事件不触发」是错误归因——Paper 1.21.11 的
                    // minecraft:register → pluginMessagerChannels → PlayerRegisterChannelEvent 链路完整（与 26.x 同构）；
                    // 真因是【因果方向】：syncmatica 客户端的 syncmatica:main receiver 仅在握手 CONFIRM_USER 之后注册
                    // （VersionHandshakeClient:65 → Context.startup():150-156 → registerReceivers():186，全树唯一链），
                    // 声明是握手完成的【下游产物】（晚探针 2-3 RTT）而非独立事件。因此：
                    //   ① 客户端上下文惰性创建、纯被动等探针（VersionHandshakeClient.init() = await message from server），
                    //      无探针则握手永不开始、声明永不发生；
                    //   ② tryStartHandshake 入口加 canSend/getListeningPluginChannels 前置守卫 = 自引用死锁
                    //      （拦探针 → 永不声明 → 兜底事件永不触发 → mod 客户端整体不可用）——【禁止回移 26.x 317a9a1 守卫，
                    //      该 commit 在 main 线有同型死锁风险】；
                    //   ③ vanilla 客户端收 ~40B 探针被静默丢弃（不踢人），其悬挂 VHS 由 onPlayerLeave 清理。
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
                            if (!context.isProtocolEnabled())
                            {
                                SyncmaticaDebug.log(SyncmaticaDebug.Cat.HANDSHAKE, "[syncm] 跳过握手（协议已禁用）: " + bukkitPlayer.getName());
                                return;
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
                // 兜底/加速路径：客户端声明 syncmatica:main 时立即握手。
                // 事实链（静态实证）：本事件【确实会触发】（Paper 1.21.11 的 register → 集合 → 事件链路完整），
                // 但 syncmatica 客户端的声明只发生在握手 CONFIRM_USER 之后（receiver 晚注册，见 onJoin 注释）——
                // 即事件到达时握手早已由 40t 探针完成，tryStartHandshake 幂等跳过（旧注释「1.21 不触发」为时序错觉：
                // 事件晚到 ≠ 不触发）。保留本路径覆盖 enable 后重连等声明先于调用到达的场景。两路径安全共存。
                if (!SyncmaticaReference.NETWORK_ID.toString().equals(e.getChannel()))
                {
                    return;
                }
                if (!context.isProtocolEnabled())
                {
                    return; // 协议已禁用，不握手
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

    /**
     * 恢复协议后对在线玩家重新发起握手（{@code /syncmatica enable} 用）。
     *
     * <p>对每个在线玩家延迟 40t 握手（等 codec 就绪，与 onJoin 主路径一致）。{@code tryStartHandshake} 幂等。
     */
    public void reconnectOnlinePlayers()
    {
        if (context == null) { return; }
        final ServerCommunicationManager comMan = (ServerCommunicationManager) context.getCommunicationManager();
        for (final org.bukkit.entity.Player player : context.getPlugin().getServer().getOnlinePlayers())
        {
            new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    if (!player.isOnline() || !context.isProtocolEnabled()) { return; }
                    try
                    {
                        final ExchangeTarget target = comMan.getOrCreateTarget(player);
                        comMan.tryStartHandshake(target);
                    }
                    catch (Exception ex)
                    {
                        SyncmaticaLog.error("syncmatica resumeProtocol 握手失败 for {}", ex, player.getName());
                    }
                }
            }.runTaskLater(context.getPlugin(), 40L);
        }
    }
}
