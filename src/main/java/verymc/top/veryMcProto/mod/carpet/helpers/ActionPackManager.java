package verymc.top.veryMcProto.mod.carpet.helpers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.mod.carpet.patches.EntityPlayerMPFake;

/**
 * 动作包侧表与驱动（B 侧新增类——上游载体 = {@code ServerPlayer_actionPackMixin} 对所有
 * {@link ServerPlayer} 注入 {@code actionPack} 字段 + {@code tick} HEAD 钩子，Paper 无 Mixin 的等价物）。
 *
 * <p>三个职责：
 * <ul>
 *   <li><b>取包</b>：{@link #get} 按需创建（computeIfAbsent）——上游"所有 ServerPlayer 都有 pack"
 *       （含真实玩家，shadow 链 {@code copyFrom} 与 op 操控真人两消费面依赖此语义）的侧表等价；</li>
 *   <li><b>驱动</b>：{@link #start} 挂 BukkitRunnable 每 tick 调 {@link #tickAll}（上游 tick HEAD 注入
 *       的调度等价——Bukkit scheduler 阶段早于同 tick 的玩家 tick，运动输入写的消费不错过）；</li>
 *   <li><b>生命周期</b>：上游字段随实体构造创建、随 respawn 新实例归零、随 GC 回收——侧表无此自然
 *       生命周期，由 {@code FakePlayerListener} 的 quit/respawn 事件显式承载（偏差登记）。</li>
 * </ul>
 *
 * <p>并发面：主线程单线程语义（runTaskTimer 同步任务 + Bukkit 命令主线程）下 HashMap 边界自洽。
 * {@link #shutdown} 在插件卸载时 kill 全部假人——假人实体是 NMS 对象，其 {@code tick()} 覆写位于
 * 插件类加载器，插件 reload 后存活假人将触发 NoClassDefFoundError，必须先行移除（偏差登记）。
 */
public final class ActionPackManager
{
    private static final Map<UUID, EntityPlayerActionPack> PACKS = new HashMap<>();
    private static BukkitRunnable tickTask;

    private ActionPackManager() { }

    public static EntityPlayerActionPack get(ServerPlayer player)
    {
        return PACKS.computeIfAbsent(player.getUUID(), k -> new EntityPlayerActionPack(player));
    }

    /** 启动每 tick 驱动（CarpetModule.onRegister 调用；主线程）。 */
    public static void start(Plugin plugin)
    {
        stopTask();
        tickTask = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                tickAll();
            }
        };
        tickTask.runTaskTimer(plugin, 0L, 1L);
    }

    public static void tickAll()
    {
        for (EntityPlayerActionPack pack : PACKS.values())
        {
            try
            {
                pack.onUpdate();
            }
            catch (Exception e)
            {
                Reference.logger().warning("[Carpet] action pack tick exception for player tick driver: " + e);
                break;
            }
        }
    }

    /** 玩家退场清理（PlayerQuitEvent MONITOR——kill/die/idle 踢出/正常登出全路径的统一收口）。 */
    public static void remove(UUID uuid)
    {
        PACKS.remove(uuid);
    }

    /** respawn 动作归零（上游 respawn = 新实例空包；Paper 实例复用，须显式复位对齐语义）。 */
    public static void stopAllFor(UUID uuid)
    {
        EntityPlayerActionPack pack = PACKS.get(uuid);
        if (pack != null)
        {
            pack.stopAll();
        }
    }

    /** 插件卸载清理（CarpetModule.disable 调用）：停驱动 → kill 全假人（防类卸载 NoClassDefFoundError）→ 清侧表。 */
    public static void shutdown()
    {
        stopTask();
        MinecraftServer server = Nms.server();
        if (server != null)
        {
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers()))
            {
                if (player instanceof EntityPlayerMPFake fake)
                {
                    try
                    {
                        fake.kill(Component.literal("Carpet plugin unloaded"));
                    }
                    catch (Exception e)
                    {
                        Reference.logger().warning("[Carpet] failed to kill fake player " + player.getGameProfile().name() + " on shutdown: " + e.getMessage());
                    }
                }
            }
        }
        PACKS.clear();
    }

    private static void stopTask()
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
}
