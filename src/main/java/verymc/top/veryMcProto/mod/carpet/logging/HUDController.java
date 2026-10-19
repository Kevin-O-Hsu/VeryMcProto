package verymc.top.veryMcProto.mod.carpet.logging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.world.level.Level;

import verymc.top.veryMcProto.framework.nms.Nms;

/**
 * HUD 投递器（克隆上游 {@code carpet/logging/HUDController.java} 26.2 树的 P4 面——
 * tps/mobcaps 两行 + footer flush；counter/packets 行与 scarpet 头脚不移植）。
 *
 * <p>与上游的偏差（对账登记，docs/carpet/architecture.md）：
 * <ul>
 *   <li><b>投递面</b>：上游直发 NMS {@code ClientboundTabListPacket}（:109-113，tab 头=scarpet 保留、
 *       脚=logger 内容）；B 侧 = Bukkit {@code setPlayerListFooter(String)}（服务端侧同效 vanilla 包，
 *       paper-api 26.2 实 jar 实证存在）——<b>只写脚、零 header 触碰</b>（scarpet 头机制不移植，
 *       他插件 header 不覆写）；§ 码 String 为该 API 的原生输入；</li>
 *   <li><b>节流面</b>：上游每 tick 被调 + 内部 {@code %20} 门（:72）；B 侧以
 *       {@code runTaskTimer(period 20)} 直接承载同拍率（初相 20 = 首帧不抢跑）；</li>
 *   <li><b>缓冲键</b>：上游以 ServerPlayer 实例为键（:38）；B 侧以玩家<b>名</b>为键——Bukkit Player
 *       引用不持有（reload 防泄漏纪律），flush 时经 {@code getPlayerExact} 复查在线；</li>
 *   <li>行分隔：上游以 {@code Component.literal("\n")} 组件分隔（:58）；B 侧行列表 flush 时
 *       {@code String.join("\n")} 等价承载。</li>
 * </ul>
 */
public final class HUDController
{
    /** HUD 行缓冲：玩家名 → 行列表（每周期重建，用后即弃——上游 :38 player_huds 同构）。 */
    private static final Map<String, List<String>> playerHuds = new HashMap<>();

    private static BukkitRunnable task;

    private HUDController() { }

    /** 追加一条 HUD 行（上游 addMessage :49-61）。 */
    public static void addMessage(Player player, String message)
    {
        if (player == null)
        {
            return;
        }
        playerHuds.computeIfAbsent(player.getName(), k -> new ArrayList<>()).add(message);
    }

    /** 清玩家 footer（上游 clearPlayer :63-67 发空 TabListPacket）。 */
    public static void clearPlayer(Player player)
    {
        player.setPlayerListFooter("");
    }

    /**
     * 周期刷新（上游 update_hud :70-115 的 P4 面）：快捷位为 false 的行整段跳过（上游 :82/:85 门），
     * 只对有行的玩家写 footer。
     */
    public static void updateHud(MinecraftServer server)
    {
        playerHuds.clear();

        if (LoggerRegistry.__tps)
        {
            LoggerRegistry.getLogger("tps").log(() -> sendTpsDisplay(server));
        }
        if (LoggerRegistry.__mobcaps)
        {
            LoggerRegistry.getLogger("mobcaps").log((option, player) ->
                    new String[]{SpawnReport.printMobcapsForDimension(server, dimensionFor(option, player))});
        }

        for (Map.Entry<String, List<String>> entry : playerHuds.entrySet())
        {
            Player target = lookupPlayer(entry.getKey());
            if (target != null)
            {
                target.setPlayerListFooter(String.join("\n", entry.getValue()));
            }
        }
    }

    /** mobcaps option → 维度（上游 :86-92 switch：dynamic 与未知 option 落玩家当前维度）。 */
    private static ResourceKey<Level> dimensionFor(String option, Player player)
    {
        return switch (option == null ? "" : option)
        {
            case "overworld" -> Level.OVERWORLD;
            case "nether" -> Level.NETHER;
            case "end" -> Level.END;
            default -> Nms.toNms(player).level().dimension();
        };
    }

    /**
     * tps 行（上游 send_tps_display :116-129 公式逐字）：MSPT = 平均 tick 纳秒/毫秒；TPS =
     * 1000/max(sprint?0:目标毫秒, MSPT)；frozen → 0；色 = MSPT 对目标毫秒的热力色。
     */
    private static String[] sendTpsDisplay(MinecraftServer server)
    {
        double mspt = (double) server.getAverageTickTimeNanos() / TimeUnit.MILLISECONDS.toNanos(1L);
        ServerTickRateManager trm = server.tickRateManager();

        double tps = 1000.0D / Math.max(trm.isSprinting() ? 0.0D : trm.millisecondsPerTick(), mspt);
        if (trm.isFrozen())
        {
            tps = 0;
        }
        String color = SpawnReport.heatmapColor(mspt, trm.millisecondsPerTick());
        return new String[]{"§7 TPS: " + color + String.format(Locale.US, "%.1f", tps)
                + "§7  MSPT: " + color + String.format(Locale.US, "%.1f", mspt)};
    }

    /** 启动 20 tick 周期任务（CarpetModule.onRegister 调用；ActionPackManager 任务范式）。 */
    public static void start(Plugin plugin)
    {
        stopTask();
        task = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                updateHud(Nms.server());
            }
        };
        task.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * 停用清理（CarpetModule.disable 调用，须先于 {@link LoggerRegistry#stopLoggers()}——订阅名册仍存活）：
     * 停任务 → 清在线订阅者 footer（上游 clearPlayer 同构，防禁用后僵尸帧）→ 清缓冲。
     */
    public static void shutdown()
    {
        stopTask();
        try
        {
            for (Player online : Bukkit.getOnlinePlayers())
            {
                if (LoggerRegistry.getPlayerSubscriptions(online.getName()) != null)
                {
                    online.setPlayerListFooter("");
                }
            }
        }
        catch (Throwable ignored)
        {
            // disable 链上的防御面（无 Bukkit 运行时不炸）
        }
        playerHuds.clear();
    }

    private static void stopTask()
    {
        if (task != null)
        {
            task.cancel();
            task = null;
        }
    }

    private static Player lookupPlayer(String name)
    {
        try
        {
            return Bukkit.getPlayerExact(name);
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
