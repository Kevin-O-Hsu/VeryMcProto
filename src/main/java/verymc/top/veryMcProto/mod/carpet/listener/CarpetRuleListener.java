package verymc.top.veryMcProto.mod.carpet.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.server.ServerListPingEvent;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;

/**
 * 行为规则的 Bukkit 事件等价挂点（常驻注册、事件时读当前规则值——{@code setDefault} 的即时生效由
 * 该构造保证，无需 observer 热切换接线）。
 *
 * <ul>
 *   <li><b>explosionNoBlockDamage</b>：上游注入点在 Explosion 本体（{@code Explosion_optimizedTntMixin}
 *       clear 受影响方块列表——实体伤害/击退不受影响）；Bukkit 将爆炸拆为实体/方块两事件，故双挂点
 *       （覆盖床/重生锚爆炸），等价动作 = {@code blockList().clear()}；</li>
 *   <li><b>customMOTD</b>：{@code "_"} 哨兵 = server.properties 原生 MOTD（上游 extra 文案语义），
 *       非 {@code "_"} 改写 ping MOTD。</li>
 * </ul>
 */
public final class CarpetRuleListener implements Listener
{
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event)
    {
        if (CarpetSettings.explosionNoBlockDamage)
        {
            event.blockList().clear();
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event)
    {
        if (CarpetSettings.explosionNoBlockDamage)
        {
            event.blockList().clear();
        }
    }

    @EventHandler
    public void onServerListPing(ServerListPingEvent event)
    {
        if (!"_".equals(CarpetSettings.customMOTD))
        {
            event.setMotd(CarpetSettings.customMOTD);
        }
    }
}
