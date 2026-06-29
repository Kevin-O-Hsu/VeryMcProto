package verymc.top.veryMcProto.mod.servux.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.mod.servux.dataproviders.ConfigProvider;

/**
 * /servux 命令（mod 层）。移植自原版 {@code ServuxCommand}（Brigadier）→ Bukkit {@link CommandExecutor}/{@link TabCompleter}。
 *
 * <p>子命令：reload / save / set / info / list / enable / disable / search。
 * 权限节点 {@code servux.command}（plugin.yml default: op）。
 */
public class ServuxCommand implements CommandExecutor, TabCompleter
{
    private static final String USAGE = "§e/servux §7reload|save|set|info|list|enable|disable|search";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        if (!sender.hasPermission("servux.command"))
        {
            sender.sendMessage("§c权限不足。");
            return true;
        }
        if (args.length == 0)
        {
            sender.sendMessage(USAGE);
            return true;
        }

        try
        {
            switch (args[0].toLowerCase())
            {
                case "reload" -> ConfigProvider.INSTANCE.doReloadConfig(sender);
                case "save" -> ConfigProvider.INSTANCE.doSaveConfig(sender);
                case "set" -> handleSet(sender, args);
                case "info" -> handleInfo(sender, args);
                case "list" -> handleList(sender, args);
                case "enable" -> handleToggle(sender, args, true);
                case "disable" -> handleToggle(sender, args, false);
                case "search" -> handleSearch(sender, args);
                default -> sender.sendMessage(USAGE);
            }
        }
        catch (Exception e)
        {
            sender.sendMessage("§c命令执行异常: " + e.getMessage());
        }
        return true;
    }

    private void handleSet(CommandSender sender, String[] args)
    {
        if (args.length < 3) { sender.sendMessage("§e/servux set <provider:setting|setting> <value>"); return; }
        String name = args[1];
        String value = args[2];
        IServuxSetting<?> setting = DataProviderManager.INSTANCE.getSettingByName(name);
        if (setting == null) { sender.sendMessage("§c未找到设置: " + name); return; }
        try
        {
            setting.setValueFromString(value);
            DataProviderManager.INSTANCE.writeToConfig();
            sender.sendMessage("§a已设置 " + setting.qualifiedName() + " §7=§f " + setting.valueToString(setting.getValue()));
        }
        catch (CommandSyntaxException e)
        {
            sender.sendMessage("§c无效值: " + e.getMessage());
        }
    }

    private void handleInfo(CommandSender sender, String[] args)
    {
        if (args.length < 2) { sender.sendMessage("§e/servux info <provider:setting|setting>"); return; }
        IServuxSetting<?> setting = DataProviderManager.INSTANCE.getSettingByName(args[1]);
        if (setting == null) { sender.sendMessage("§c未找到设置: " + args[1]); return; }
        sender.sendMessage("§6" + setting.qualifiedName() + " §7: §f" + setting.valueToString(setting.getValue())
                + " §7(默认 " + setting.valueToString(setting.getDefaultValue()) + "§7)");
    }

    private void handleList(CommandSender sender, String[] args)
    {
        sender.sendMessage("§6Providers:");
        for (var p : DataProviderManager.INSTANCE.getAllProviders())
        {
            sender.sendMessage(" §7- §f" + p.getName() + " §7[" + (p.isEnabled() ? "§a启用" : "§c禁用") + "§7] §8" + p.getDescription());
        }
    }

    private void handleToggle(CommandSender sender, String[] args, boolean enable)
    {
        if (args.length < 2) { sender.sendMessage("§e/servux " + (enable ? "enable" : "disable") + " <provider>"); return; }
        boolean ok = DataProviderManager.INSTANCE.setProviderEnabled(args[1].toLowerCase(), enable);
        if (ok)
        {
            DataProviderManager.INSTANCE.writeToConfig();
            sender.sendMessage("§a" + args[1] + " 已" + (enable ? "启用" : "禁用"));
        }
        else { sender.sendMessage("§c未找到 provider: " + args[1]); }
    }

    private void handleSearch(CommandSender sender, String[] args)
    {
        if (args.length < 2) { sender.sendMessage("§e/servux search <关键词>"); return; }
        String q = args[1].toLowerCase();
        sender.sendMessage("§6匹配的设置:");
        for (var p : DataProviderManager.INSTANCE.getAllProviders())
        {
            for (IServuxSetting<?> s : p.getSettings())
            {
                if (s.qualifiedName().toLowerCase().contains(q))
                {
                    sender.sendMessage(" §7- §f" + s.qualifiedName() + " §8= " + s.valueToString(s.getValue()));
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
    {
        List<String> out = new ArrayList<>();
        String typed = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        if (args.length == 1)
        {
            for (String s : List.of("reload", "save", "set", "info", "list", "enable", "disable", "search"))
            {
                if (s.startsWith(typed)) { out.add(s); }
            }
        }
        else if (args.length == 2)
        {
            String sub = args[0].toLowerCase();
            if (sub.equals("enable") || sub.equals("disable"))
            {
                for (var p : DataProviderManager.INSTANCE.getAllProviders())
                {
                    if (p.getName().toLowerCase().startsWith(typed)) { out.add(p.getName()); }
                }
            }
            else if (sub.equals("set") || sub.equals("info"))
            {
                for (var p : DataProviderManager.INSTANCE.getAllProviders())
                {
                    for (IServuxSetting<?> s : p.getSettings())
                    {
                        if (s.qualifiedName().toLowerCase().startsWith(typed)) { out.add(s.qualifiedName()); }
                    }
                }
            }
        }
        else if (args.length == 3 && args[0].equalsIgnoreCase("set"))
        {
            IServuxSetting<?> setting = DataProviderManager.INSTANCE.getSettingByName(args[1]);
            if (setting != null)
            {
                for (String ex : setting.examples())
                {
                    if (ex.toLowerCase().startsWith(typed)) { out.add(ex); }
                }
            }
        }
        return out;
    }
}
