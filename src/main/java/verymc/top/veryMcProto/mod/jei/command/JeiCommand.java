package verymc.top.veryMcProto.mod.jei.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import verymc.top.veryMcProto.mod.jei.config.JeiConfiguration;

/**
 * /jei 命令（mod 层，Paper 新增——上游 JEI 是 Fabric mod，服务端配置走 jei-server.properties 文件，无命令）。
 *
 * <p>子命令 {@code status|enable|disable}：status 显示模块与 cheat 三切面状态；enable/disable 切换
 * 模块总开关并持久化到 {@code jei.json}。disable 语义 = 停止配方同步推送 + handler 内丢弃 jei:* C2S
 * （<b>通道保持注册不注销</b>——注销会让客户端后续 C2S 被 Paper 踢人，见 JeiServerPlayHandler javadoc）。
 *
 * <p><b>权限</b>：{@code jei.command}（plugin.yml default: op）。cheat 三布尔仅由配置文件承载
 * （与上游 ServerConfig 的文件管理语义一致，不设管理子命令）。
 *
 * <p><b>生效范围</b>：仅影响<b>此后</b>的同步与交互——在线玩家已同步的配方不回收。
 */
public class JeiCommand implements CommandExecutor, TabCompleter
{
    private static final String PERMISSION = "jei.command";
    private static final String USAGE = "§e/jei §7status|enable|disable";

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args)
    {
        if (!sender.hasPermission(PERMISSION))
        {
            sender.sendMessage("§c权限不足。");
            return true;
        }

        final JeiConfiguration config = JeiConfiguration.getInstance();
        if (config == null)
        {
            sender.sendMessage("§cJEI 模块未加载。");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status"))
        {
            sendStatus(sender, config);
            return true;
        }

        switch (args[0].toLowerCase())
        {
            case "enable" ->
            {
                if (config.isEnabled()) { sender.sendMessage("§eJEI 模块已处于启用状态。"); return true; }
                config.setEnabled(true);
                sender.sendMessage("§aJEI 模块已启用 §7（配方同步 + jei 通道交互）");
            }
            case "disable" ->
            {
                if (!config.isEnabled()) { sender.sendMessage("§eJEI 模块已处于禁用状态。"); return true; }
                config.setEnabled(false);
                sender.sendMessage("§eJEI 模块已禁用 §7（停止配方推送；通道保留、不踢人；在线玩家不受影响）");
            }
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void sendStatus(final CommandSender sender, final JeiConfiguration config)
    {
        sender.sendMessage("§6JEI 协议模块状态:");
        sender.sendMessage(" §7模块: §f" + (config.isEnabled() ? "§a启用 (ON)" : "§c禁用 (OFF)"));
        sender.sendMessage(" §7cheat - op 权限级: §f" + bool(config.isCheatModeEnabledForOp()));
        sender.sendMessage(" §7cheat - 创造模式: §f" + bool(config.isCheatModeEnabledForCreative()));
        sender.sendMessage(" §7cheat - /give 权限: §f" + bool(config.isCheatModeEnabledForGive()));
        sender.sendMessage(" §7配置: §f" + config.getConfigFile().getFileName());
        sender.sendMessage("§7用法: " + USAGE);
    }

    private static String bool(boolean value)
    {
        return value ? "§a开启" : "§c关闭";
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args)
    {
        final List<String> out = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission(PERMISSION))
        {
            for (final String s : List.of("status", "enable", "disable"))
            {
                if (s.startsWith(args[0].toLowerCase())) { out.add(s); }
            }
        }
        return out;
    }
}
