package verymc.top.veryMcProto.mod.carpet.util;

import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.command.VanillaCommandWrapper;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import verymc.top.veryMcProto.Reference;

/**
 * /carpet 权限判定（克隆上游 {@code carpet/utils/CommandHelper.canUseCommand} :44-60 的 1.21.11 等价物）。
 *
 * <p>上游以 {@code CommandSourceStack} 直判 {@code Commands.LEVEL_*.check(source.permissions())}；
 * B 侧统一经 {@code VanillaCommandWrapper.getListener(sender)} 取等价 CommandSourceStack——
 * 全 sender 类型精确（玩家/命令方块/矿车命令方块/RCON/Console/Proxied 七分支，Paper 自带映射，
 * 无手写矩阵漂移面）。true/false 值在 sender 分派前全局短路（Console × "false" 正确拒绝，上游 :46-51 同构）。
 */
public final class CommandHelper
{
    private CommandHelper() { }

    /** 上游 canUseCommand 同构：level 为 "true"/"false"/"ops"/"0"-"4"。 */
    public static boolean canUseCommand(CommandSender source, String commandLevel)
    {
        // Boolean 短路（上游 :46 与 "true"/"false" case 合并语义）——先于 sender 分派
        switch (commandLevel)
        {
            case "true":
                return true;
            case "false":
                return false;
            default:
                break;
        }
        int level = parseLevel(commandLevel);
        if (level < 0)
        {
            return false; // 上游 default -> false（未知级别串一律拒绝）
        }
        try
        {
            var stack = VanillaCommandWrapper.getListener(source);
            if (stack == null)
            {
                return false;
            }
            return checkLevel(stack.permissions(), level);
        }
        catch (Throwable t)
        {
            Reference.logger().warning("[Carpet] permission check failed for sender " + source.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /** owner 级（op 4）判定（CarpetSettings.CarpetPermissionLevel 命令路径用，上游 LEVEL_OWNERS 同构）。 */
    public static boolean hasOwnerPermission(CommandSender source)
    {
        try
        {
            var stack = VanillaCommandWrapper.getListener(source);
            return stack != null && Commands.LEVEL_OWNERS.check(stack.permissions());
        }
        catch (Throwable t)
        {
            Reference.logger().warning("[Carpet] permission check failed for sender " + source.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * NMS 源直判重载（上游 {@code canUseCommand(CommandSourceStack, String)} :44-60 逐字同构）——
     * /player 等 Paper Brigadier 命令的 requires 谓词供给（{@link CommandSourceStack} 直达，
     * 无需 Bukkit sender 转换）。级别判定与 {@link #canUseCommand(CommandSender, String)} 同一
     * {@code Commands.LEVEL_*.check(permissions())} 事实源。
     */
    public static boolean canUseCommand(CommandSourceStack source, String commandLevel)
    {
        switch (commandLevel)
        {
            case "true":
                return true;
            case "false":
                return false;
            default:
                break;
        }
        int level = parseLevel(commandLevel);
        if (level < 0)
        {
            return false;
        }
        return checkLevel(source.permissions(), level);
    }

    private static boolean checkLevel(net.minecraft.server.permissions.PermissionSet permissions, int level)
    {
        return switch (level)
        {
            case 0 -> Commands.LEVEL_ALL.check(permissions);
            case 1 -> Commands.LEVEL_MODERATORS.check(permissions);
            case 2 -> Commands.LEVEL_GAMEMASTERS.check(permissions);
            case 3 -> Commands.LEVEL_ADMINS.check(permissions);
            case 4 -> Commands.LEVEL_OWNERS.check(permissions);
            default -> false;
        };
    }

    /**
     * 级别串解析（纯函数，单测覆盖）：{@code "ops"} → 2（上游 :52 同判——cheaty 命令典型档）、
     * {@code "0"-"4"} → 0-4、其余（含 "true"/"false"/大小写变体/null）→ -1。
     */
    public static int parseLevel(String level)
    {
        if (level == null)
        {
            return -1;
        }
        return switch (level)
        {
            case "ops" -> 2;
            case "0" -> 0;
            case "1" -> 1;
            case "2" -> 2;
            case "3" -> 3;
            case "4" -> 4;
            default -> -1;
        };
    }
}
