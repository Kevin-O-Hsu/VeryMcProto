package verymc.top.veryMcProto.mod.servux.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.framework.debug.Debug;
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
    private static final String USAGE = "§e/servux §7reload|save|set|info|list|enable|disable|search|debug";

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
                case "debug" -> handleDebug(sender, args);
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

    /**
     * /servux debug —— 调试宏开关热切换（运行时即时生效，无需重编译/reload）。
     *
     * <p>用法（<b>master 总开关</b>与<b>分类</b>是两个正交维度，各管各的）：
     * <ul>
     *   <li>{@code /servux debug} / {@code status} —— 查看状态；</li>
     *   <li>{@code /servux debug on|off} —— <b>总开关</b>死活（仅 master，<b>不碰分类</b>）；</li>
     *   <li>{@code /servux debug cat all|none} —— 全开/清空<b>分类</b>；</li>
     *   <li>{@code /servux debug cat <name>} —— 切换单个分类（lifecycle/handshake/network/packet/tick/permission/provider/config）。</li>
     * </ul>
     * <p>注：命令切换<b>不持久化</b>（重启/reload 恢复为配置值）；需持久请 {@code /servux set servux_main:debug_log true}。
     */
    private void handleDebug(CommandSender sender, String[] args)
    {
        if (args.length < 2)
        {
            sender.sendMessage("§6调试状态: §f" + Debug.statusLine());
            sender.sendMessage("§7用法: §f/servux debug <on|off|status>§7 —— master 总开关 / 状态");
            sender.sendMessage("§7用法: §f/servux debug cat <all|none|分类名>§7 —— 分类（master 与分类正交，两者皆开才输出）");
            sender.sendMessage("§7分类: §flifecycle handshake network packet tick permission provider config");
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub)
        {
            // master 总开关维度：on/off 只管 master 死活，绝不越权动分类（分类是正交的另一维度）。
            case "on" ->
            {
                Debug.setMaster(true);
                String tip = Debug.active().isEmpty() ? " §7(分类为空，用 §f/servux debug cat all§7 开全分类)" : "";
                sender.sendMessage("§a调试总开关已开启 §7(仅 master): §f" + Debug.statusLine() + tip);
            }
            case "off" -> { Debug.setMaster(false); sender.sendMessage("§e调试总开关已关闭 §7(仅 master): §f" + Debug.statusLine()); }
            case "status" -> sender.sendMessage("§6调试状态: §f" + Debug.statusLine());
            // 分类维度：全部归到 cat 下。all/none 是 cat 的特殊值（set 语义，全开/清空）；单个 name 走 toggle。
            case "cat" ->
            {
                if (args.length < 3) { sender.sendMessage("§e/servux debug cat <all|none|分类名>"); return; }
                String catName = args[2].toLowerCase();
                if (catName.equals("all")) { Debug.enableAll(); sender.sendMessage("§a已开启全分类: §f" + Debug.statusLine()); return; }
                if (catName.equals("none")) { Debug.clearCats(); sender.sendMessage("§e已清空全分类: §f" + Debug.statusLine()); return; }
                Debug.Cat cat = Debug.parseCat(args[2]);
                if (cat == null) { sender.sendMessage("§c未知分类: " + args[2] + " §7(all|none|分类名)"); return; }
                boolean now = Debug.toggle(cat);
                sender.sendMessage("§a分类 " + cat.name().toLowerCase() + " → " + (now ? "§aON" : "§cOFF"));
                sender.sendMessage("§7当前: §f" + Debug.statusLine());
            }
            default -> sender.sendMessage("§c未知子命令: " + sub + " §7(on/off/cat/status)");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
    {
        List<String> out = new ArrayList<>();
        String typed = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        if (args.length == 1)
        {
            for (String s : List.of("reload", "save", "set", "info", "list", "enable", "disable", "search", "debug"))
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
            else if (sub.equals("debug"))
            {
                for (String s : List.of("on", "off", "cat", "status"))
                {
                    if (s.startsWith(typed)) { out.add(s); }
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
        else if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("cat"))
        {
            for (String s : List.of("all", "none"))
            {
                if (s.startsWith(typed)) { out.add(s); }
            }
            for (Debug.Cat c : Debug.Cat.values())
            {
                String n = c.name().toLowerCase();
                if (n.startsWith(typed)) { out.add(n); }
            }
        }
        return out;
    }
}
