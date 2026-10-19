package verymc.top.veryMcProto.mod.carpet.listener;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import verymc.top.veryMcProto.mod.carpet.helpers.ActionPackManager;
import verymc.top.veryMcProto.mod.carpet.patches.EntityPlayerMPFake;

/**
 * 假人相关事件处理（B 侧新增类——上游对应行为由 Mixin 字段生命周期与
 * {@code Player_fakePlayersMixin} 击退 redirect 承载，Paper 无 Mixin 的等价物，偏差登记）。
 *
 * <ul>
 *   <li><b>quit</b>（MONITOR）：动作包侧表清理——上游 actionPack 字段随实体 GC 自然回收，侧表须显式
 *       移除防泄漏与 stale 引用被 tick 续写；MONITOR = 其他插件处理完再清的时序。kill/die/idle 踢出/
 *       正常登出全路径统一经 PlayerQuitEvent 收口（kill 链走 onDisconnect 完整退出链）。</li>
 *   <li><b>respawn</b>：动作归零——上游 respawn = 新 ServerPlayer 实例天然空包；Paper respawn 实例
 *       复用，须显式 stopAll 复刻"重生后动作清零"上游语义。</li>
 *   <li><b>velocity</b>：击退保真——上游 {@code Player_fakePlayersMixin} 让假人不进
 *       {@code causeExtraKnockback} 的"向目标客户端回发 motion 包 + 回滚服务端速度"分支（假人无客户端，
 *       vanilla 却按客户端权威速度处理 ServerPlayer 目标）。B 侧等价 = 取消该事件
 *       （cancelled 路径跳过回滚三件套，假人保留服务端击退速度）。{@code hurtMarked} 门控把取消面
 *       收窄到攻击击退路径（事件恰在 hurtMarked 置位后 fire），不误伤插件主动 setVelocity。
 *       已知局限：任意伤害同 tick 内插件 setVelocity 的残余误伤窗口（≤1 tick，事件无 cause 字段，
 *       无更窄判别面——偏差清单登记）。</li>
 * </ul>
 */
public class FakePlayerListener implements Listener
{
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        ActionPackManager.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        ActionPackManager.stopAllFor(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event)
    {
        if (!(event.getPlayer() instanceof CraftPlayer craftPlayer))
        {
            return;
        }
        var handle = craftPlayer.getHandle();
        if (handle instanceof EntityPlayerMPFake && handle.hurtMarked)
        {
            event.setCancelled(true);
        }
    }
}
