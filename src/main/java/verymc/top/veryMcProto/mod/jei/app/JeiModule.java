package verymc.top.veryMcProto.mod.jei.app;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.framework.network.ChannelManager;
import verymc.top.veryMcProto.mod.jei.JeiReference;
import verymc.top.veryMcProto.mod.jei.config.JeiConfiguration;
import verymc.top.veryMcProto.mod.jei.network.JeiServerPlayHandler;
import verymc.top.veryMcProto.mod.jei.network.RecipeSyncJoinOrderer;
import verymc.top.veryMcProto.mod.jei.recipesync.RecipeSyncService;

/**
 * JEI 协议模块装配（mod 层，自管形态——仿 {@code SyncmaticaModule}，黄金模板锁定）。
 * 上游 = mezz/JustEnoughItems 26.1 分支（JEI 29.37.0），实现其服务端完整协议：
 * <ul>
 *   <li><b>配方同步层</b>：fabric:recipe_sync（Fabric API wire，RegisterChannel 触发 + 进服时序整形
 *       {@link RecipeSyncJoinOrderer}——payload 先于 UpdateRecipesPacket 上线）+
 *       neoforge:recipe_content（join + brand 触发，沿用已验证行为）；</li>
 *   <li><b>jei:* 通道层</b>：8 条 C2S（cheat 给/删/热键栏 + 配方转移 ×4 变体）+ 2 条 S2C
 *       （cheat_permission / recipe_transfer_result）——客户端 {@code isJeiOnServer()} 依赖服务端
 *       声明 {@code jei:delete_player_item} 等通道，故全部经 {@code ChannelManager} 成对注册。</li>
 * </ul>
 *
 * <p><b>无 per-player 状态</b>：cheat 是无状态请求-应答、transferId 是客户端侧关联键——
 * 不需要 PlayerQuitEvent 清理（与 servux/syncmatica 的会话型模块不同）。
 */
public final class JeiModule
{
    private static volatile boolean enabled = false;

    private JeiModule() { }

    /** 主类 onEnable 调用。装配顺序：配置 → 通道注册 → 事件监听。 */
    public static void enable(JavaPlugin plugin)
    {
        // 1. 配置（构造即注册单例；含旧 jei-recipe-bridge.json 的 enabled 迁移）
        new JeiConfiguration(plugin.getDataFolder().toPath());

        // 2. jei:* C2S 通道注册（incoming 接收 + outgoing 声明成对——声明让客户端 canSend=true，
        //    isJeiOnServer 门禁解锁；缺一即对应功能静默退网）
        for (Identifier channel : JeiReference.C2S_CHANNELS)
        {
            ChannelManager.INSTANCE.register(channel, JeiServerPlayHandler.forChannel(channel));
        }

        // 3. 配方同步两条 S2C 通道的出站声明（沿用现状，声明无害；实际发送走 NMS 直发不经 Messenger）
        Plugin self = plugin;
        try
        {
            var messenger = plugin.getServer().getMessenger();
            messenger.registerOutgoingPluginChannel(self, JeiReference.CHANNEL_FABRIC_RECIPE_SYNC.toString());
            messenger.registerOutgoingPluginChannel(self, JeiReference.CHANNEL_NEOFORGE_RECIPE_CONTENT.toString());
        }
        catch (Exception e)
        {
            Reference.logger().warning("[JEI] 配方通道出站声明失败（不影响 NMS 直发）: " + e.getMessage());
        }

        // 4. 事件监听（fabric 腿 RegisterChannel + neoforge 腿 join brand）
        plugin.getServer().getPluginManager().registerEvents(new JeiListener(), plugin);

        enabled = true;
        Reference.logger().info("[JEI] 模块已启用（上游 mezz/JustEnoughItems 26.1 · " + JeiReference.C2S_CHANNELS.length + " 条 jei:* C2S 通道）");
    }

    /** 主类 onDisable 调用（须在 {@code ChannelManager.unregisterAll} 之前）。 */
    public static void disable()
    {
        if (!enabled)
        {
            return;
        }
        enabled = false;
        for (Identifier channel : JeiReference.C2S_CHANNELS)
        {
            ChannelManager.INSTANCE.unregister(channel);
        }
        // recipe 两条出站通道经 Messenger 直注的，随插件卸载由 Bukkit 自动回收
        Reference.logger().info("[JEI] 模块已卸载。");
    }

    /** 事件监听器：两条配方同步腿的触发点。 */
    private static final class JeiListener implements Listener
    {
        /**
         * fabric 腿：客户端经 {@code minecraft:register} 声明 {@code fabric:recipe_sync} 时触发
         * （= 客户端能收该通道的正向证据）。对齐上游 Fabric API {@code RecipeSyncImpl.sendRecipes}
         * 的 canSend(player) 门控——只发给声明过能收的客户端（Fabric API 客户端注册 receiver 即声明；
         * vanilla/未装者零打扰）。finally 必经 {@link RecipeSyncJoinOrderer#release}：该声明同时是
         * 进服时序整形器的"证据到达"信号——payload 写先于此提交（主线程同步提交 + eventLoop FIFO
         * ⇒ wire 序恒为 payload → 被扣的 UpdateRecipesPacket，见 docs/30 §5.3）。
         */
        @EventHandler
        public void onPlayerRegisterChannel(PlayerRegisterChannelEvent event)
        {
            if (!JeiReference.CHANNEL_FABRIC_RECIPE_SYNC.toString().equals(event.getChannel()))
            {
                return;
            }
            try
            {
                JeiConfiguration config = JeiConfiguration.getInstance();
                if (config != null && config.isEnabled())
                {
                    ServerPlayer player = Nms.toNms(event.getPlayer());
                    RecipeSyncService.sendFabric(player, player.level().getServer());
                }
            }
            catch (Exception e)
            {
                Reference.logger().warning("[JEI] fabric 配方同步失败 (" + event.getPlayer().getName() + "): " + e.getMessage());
            }
            finally
            {
                // 无论是否实际发送（禁用/空表/异常），证据已到即放行被扣的进服 UpdateRecipesPacket
                RecipeSyncJoinOrderer.release(event.getPlayer().getUniqueId());
            }
        }

        /**
         * config 相位末（进世界前）安装配方时序整形器（fabric 腿）：扣住 placeNewPlayer 的首个
         * UpdateRecipesPacket，等 play register 证据到达后放行——复刻上游 PlayerListMixin 的
         * 「payload 先于 UpdateRecipesPacket 上线」顺序不变量。禁用/异常一律降级为现状时序。
         */
        @EventHandler
        public void onAsyncConfigure(AsyncPlayerConnectionConfigureEvent event)
        {
            try
            {
                JeiConfiguration config = JeiConfiguration.getInstance();
                if (config == null || !config.isEnabled())
                {
                    return;
                }
                RecipeSyncJoinOrderer.install(event.getConnection());
            }
            catch (Throwable t)
            {
                Reference.logger().warning("[JEI] 配方时序整形器装配失败（降级为现状时序）: " + t.getMessage());
            }
        }

        /**
         * neoforge 腿：join + brand 判定（保持已实机验证的旧行为——NeoForge 客户端连 Paper 服
         * 处于 vanilla 模式，其通道声明行为不可依赖）。vanilla / fabric / 未知 brand 零动作零聊天。
         */
        @EventHandler
        public void onJoin(PlayerJoinEvent event)
        {
            try
            {
                JeiConfiguration config = JeiConfiguration.getInstance();
                if (config == null || !config.isEnabled())
                {
                    return;
                }
                Player bukkitPlayer = event.getPlayer();
                String brand = bukkitPlayer.getClientBrandName();
                if (brand == null || !brand.equalsIgnoreCase("neoforge"))
                {
                    return;
                }
                bukkitPlayer.sendMessage("§6VeryMcProto JEI Compat: Syncing Recipes...§r");
                ServerPlayer player = Nms.toNms(bukkitPlayer);
                RecipeSyncService.sendNeoForge(player, player.level().getServer());
            }
            catch (Exception e)
            {
                Reference.logger().warning("[JEI] neoforge 配方同步失败 (" + event.getPlayer().getName() + "): " + e.getMessage());
            }
        }
    }
}
