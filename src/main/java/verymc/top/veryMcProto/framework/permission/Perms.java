package verymc.top.veryMcProto.framework.permission;

import org.bukkit.entity.Player;

import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.mod.servux.ServuxDebug;

/**
 * 权限工具（框架层）。替代原版 {@code me.lucko.fabric.api.permissions.v0.Permissions}。
 *
 * <p>{@link #check(ServerPlayer, String, int)} 语义对齐原版 {@code Permissions.check(player, node, defaultLevel)}：
 * <ol>
 *   <li>若玩家被<b>显式授予 / 拒绝</b>该 Bukkit 权限节点（{@code isPermissionSet}，LuckPerms / attachment），
 *       以该结果为准；</li>
 *   <li>否则 {@code level <= 0} → 全员放行；</li>
 *   <li>否则按 op 判断（{@link Player#isOp()}）。op 默认等级 4，满足 servux 所有
 *       {@code permission_level}（2/3/4 管理类）；非 op 满足 level 0。</li>
 * </ul>
 *
 * <p><b>注</b>：1.21.5+ 的 {@code CommandSourceStack.hasPermission(int)} 与 {@code PlayerList.ops()}
 * 均已漂移（前者签名改 {@code (Permission, String)}，后者方法名变），细分 op 等级需反射访问 ops 列表。
 * servux 场景下 {@code permission_level} 仅区分「全员(0) / 管理(>=1)」，Bukkit op 二分已足够，
 * 且更稳定（不依赖易漂移的 NMS op API）。需要细分时由 LuckPerms 权限节点精确控制。
 */
public final class Perms
{
    private Perms() { }

    public static boolean check(ServerPlayer player, String node, int level)
    {
        if (player == null)
        {
            return false;
        }
        boolean result;
        String reason;
        try
        {
            Player bukkit = player.getBukkitEntity();

            // 显式权限优先（LuckPerms / permission attachment 设置过的节点）
            if (bukkit != null && bukkit.isPermissionSet(node))
            {
                result = bukkit.hasPermission(node);
                reason = "explicit";
            }
            else if (level <= 0)
            {
                result = true; // 全员
                reason = "level<=0";
            }
            else
            {
                // op 满足 level >= 1（servux 的管理类 permission_level 2/3/4）
                result = bukkit != null && bukkit.isOp();
                reason = "op";
            }
        }
        catch (Exception e)
        {
            result = level <= 0;
            reason = "exception:" + e.getClass().getSimpleName();
        }
        ServuxDebug.log(ServuxDebug.Cat.PERMISSION, "check " + player.getName().getString()
                + " node=" + node + " level=" + level + " → " + result + " (" + reason + ")");
        return result;
    }
}
