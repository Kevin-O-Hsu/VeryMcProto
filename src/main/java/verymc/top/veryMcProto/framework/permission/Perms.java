package verymc.top.veryMcProto.framework.permission;

import org.bukkit.entity.Player;

import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.framework.debug.FrameworkDebug;

/**
 * 权限工具（框架层）。对齐上游 26.3 判定公式：Fabric API {@code fabric-permission-api-v1} 的
 * {@code PermissionContextOwner.checkPermission(Identifier, PermissionLevel)}。
 *
 * <p>{@link #check(ServerPlayer, String, int)} 语义（上游公式的 Bukkit 等价物，
 * 出处 {@code PermissionContextOwner.java:91-93} 与 {@code :119-122}）：
 * <ol>
 *   <li>若玩家被<b>显式授予 / 拒绝</b>该节点（{@code isPermissionSet}，Bukkit 权限插件的
 *       grant/deny = 上游 {@code PermissionEvents.ON_REQUEST} 显式应答的平台等价物），
 *       以该结果为准；</li>
 *   <li>否则按等级回退：玩家 op 等级（0-4，{@link #level}）不低于要求等级才放行
 *       （即上游 {@code permissionLevel.isEqualOrHigherThan(defaultPermissionLevel)} 的同款整数比较）。</li>
 * </ol>
 *
 * <p>玩家等级经公开 NMS 接口读取（{@code ServerPlayer.permissions()} →
 * {@code MinecraftServer.getProfilePermissions} → {@code LevelBasedPermissionSet.level()}），
 * 非 op 玩家恒为 0（{@code LevelBasedPermissionSet.ALL}），无反射。
 */
public final class Perms
{
    private Perms() { }

    /**
     * 玩家 op 等级（0-4）。{@code player == null} 返回 0。
     *
     * <p>方法链（26.3 公开 NMS 接口，无反射）：{@code player.level().getServer()}（{@code Level.getServer()}，
     * {@code Level.java:928}）→ {@code getProfilePermissions(player.nameAndId())}（{@code MinecraftServer}，
     * 26.3 {@code MinecraftServer.java:2598}）→ {@code LevelBasedPermissionSet.level().id()}（{@code PermissionLevel.id()}）。
     * 非 op 玩家走 {@code getProfilePermissions} 的 else 分支 → {@code LevelBasedPermissionSet.ALL}
     * → id 0；op 玩家为 ops.json 条目里的等级（0-4）。
     */
    public static int level(ServerPlayer player)
    {
        if (player == null)
        {
            return 0;
        }
        return player.level().getServer().getProfilePermissions(player.nameAndId()).level().id();
    }

    /**
     * 判定公式纯函数（上游 {@code PermissionContextOwner.checkPermission(Identifier, PermissionLevel)}
     * 的回退公式）：显式设置优先；无显式设置时玩家等级不低于要求等级才放行。
     */
    public static boolean decide(boolean explicitlySet, boolean explicitValue, int playerLevel, int requiredLevel)
    {
        return explicitlySet ? explicitValue : playerLevel >= requiredLevel;
    }

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

            // 显式权限优先（Bukkit 权限插件 grant/deny 过的节点 = 上游 ON_REQUEST 显式应答的等价物）
            if (bukkit != null && bukkit.isPermissionSet(node))
            {
                result = bukkit.hasPermission(node);
                reason = "explicit";
            }
            else
            {
                // 等级回退：玩家 op 等级不低于要求等级（上游 checkPermission(Identifier, PermissionLevel) 公式）
                result = decide(false, false, level(player), level);
                reason = "level";
            }
        }
        catch (Exception e)
        {
            result = level <= 0;
            reason = "exception:" + e.getClass().getSimpleName();
        }
        FrameworkDebug.log("permission", "check " + player.getName().getString()
                + " node=" + node + " level=" + level + " -> " + result + " (" + reason + ")");
        return result;
    }
}
