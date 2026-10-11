package verymc.top.veryMcProto.mod.jeirecipebridge.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import verymc.top.veryMcProto.mod.jeirecipebridge.config.JeiConfiguration;

/**
 * /jei 命令（mod 层，Paper 新增——原版 JEIRecipeBridge 无命令/无配置）。
 *
 * <p>子命令 {@code enable|disable} 切换模块 enabled 并持久化到 {@code jei-recipe-bridge.json}：
 * <ul>
 *   <li>{@code enable} —— 恢复进服配方同步；</li>
 *   <li>{@code disable} —— 停止进服推送（通道仍声明、不踢人，与 syncmatica 软禁用的"通道保留"语义一致）。</li>
 * </ul>
 * 无参显示当前状态 + 用法。
 *
 * <p><b>权限</b>：{@code jei.command}（plugin.yml default: op）——JEI 是纯管理命令，单权限即足够
 * （无 syncmatica 那样的普通玩家子命令如 {@code load}）。
 *
 * <p><b>生效范围</b>：仅影响<b>此后</b>进服的玩家——disable 不中断已在线玩家的同步（配方同步是进服一次性推送，
 * 无持续连接需断开；这与 syncmatica 的"中断进行中传输"不同）。
 */
public class JeiCommand implements CommandExecutor, TabCompleter
{
    private static final String PERMISSION = "jei.command";
    private static final String USAGE = "§e/jei §7enable|disable";

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

        if (args.length == 0)
        {
            sendStatus(sender, config);
            return true;
        }

        switch (args[0].toLowerCase())
        {
            case "enable" ->
            {
                if (config.isEnabled()) { sender.sendMessage("§eJEI recipe sync is already enabled."); return true; }
                config.setEnabled(true);
                sender.sendMessage("§aJEI recipe sync enabled §7(players joining afterwards will receive recipe sync)");
            }
            case "disable" ->
            {
                if (!config.isEnabled()) { sender.sendMessage("§eJEI recipe sync is already disabled."); return true; }
                config.setEnabled(false);
                sender.sendMessage("§eJEI recipe sync disabled §7(join-time push stopped; channel kept, no kick; online players unaffected)");
            }
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void sendStatus(final CommandSender sender, final JeiConfiguration config)
    {
        sender.sendMessage("§6JEI Recipe Bridge status:");
        sender.sendMessage(" §7recipe sync: §f" + (config.isEnabled() ? "§aEnabled (ON)" : "§cDisabled (OFF)"));
        sender.sendMessage(" §7config: §f" + config.getConfigFile().getFileName());
        sender.sendMessage("§7Usage: " + USAGE);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args)
    {
        final List<String> out = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission(PERMISSION))
        {
            for (final String s : List.of("enable", "disable"))
            {
                if (s.startsWith(args[0].toLowerCase())) { out.add(s); }
            }
        }
        return out;
    }
}
