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
            sender.sendMessage("§cInsufficient permissions.");
            return true;
        }

        final JeiConfiguration config = JeiConfiguration.getInstance();
        if (config == null)
        {
            sender.sendMessage("§cJEI module not loaded.");
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
                if (config.isEnabled()) { sender.sendMessage("§eJEI module is already enabled."); return true; }
                config.setEnabled(true);
                sender.sendMessage("§aJEI module enabled §7(recipe sync + jei channel interaction)");
            }
            case "disable" ->
            {
                if (!config.isEnabled()) { sender.sendMessage("§eJEI module is already disabled."); return true; }
                config.setEnabled(false);
                sender.sendMessage("§eJEI module disabled §7(recipe push stopped; channels kept, no kicks; online players unaffected)");
            }
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void sendStatus(final CommandSender sender, final JeiConfiguration config)
    {
        sender.sendMessage("§6JEI protocol module status:");
        sender.sendMessage(" §7Module: §f" + (config.isEnabled() ? "§aEnabled (ON)" : "§cDisabled (OFF)"));
        sender.sendMessage(" §7cheat - op permission level: §f" + bool(config.isCheatModeEnabledForOp()));
        sender.sendMessage(" §7cheat - creative mode: §f" + bool(config.isCheatModeEnabledForCreative()));
        sender.sendMessage(" §7cheat - /give permission: §f" + bool(config.isCheatModeEnabledForGive()));
        sender.sendMessage(" §7Config: §f" + config.getConfigFile().getFileName());
        sender.sendMessage("§7Usage: " + USAGE);
    }

    private static String bool(boolean value)
    {
        return value ? "§aEnabled" : "§cDisabled";
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
