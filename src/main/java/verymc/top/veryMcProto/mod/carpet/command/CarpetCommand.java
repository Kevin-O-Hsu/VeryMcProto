package verymc.top.veryMcProto.mod.carpet.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import verymc.top.veryMcProto.mod.carpet.CarpetReference;
import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.app.CarpetModule;
import verymc.top.veryMcProto.mod.carpet.settings.ParsedRule;
import verymc.top.veryMcProto.mod.carpet.settings.SettingsManager;
import verymc.top.veryMcProto.mod.carpet.util.CommandHelper;

/**
 * /carpet 命令（四子命令面 = 本移植的 B 侧命令面约定；上游命令树见
 * {@code carpet/api/settings/SettingsManager.java:597-642}——B 侧差异（弃裸 {@code <rule> [value]}
 * 会话级 set 与 {@code list defaults}/{@code list <tag>} 子面）登记于 docs/carpet.md 偏差清单。
 *
 * <p><b>双层门禁</b>：plugin.yml 静态 {@code carpet.command}（Bukkit 面，default op）+ 运行时动态
 * {@link CommandHelper#canUseCommand}（{@code carpetCommandPermissionLevel} 规则——ops/2/4 级语义经
 * NMS PermissionSet 精确判定，上游 root {@code .requires(canUseCommand && !locked)} 的 Bukkit 形态）。
 * <b>locked（conf 首行 {@code locked}）= 拒绝全部子命令</b>（含 list/query，等价上游整树 requires）。
 *
 * <p>子命令：{@code list}（全量规则 = 注册表视图，判据③）/ {@code setDefault <rule> <value...>}
 * （live set + conf 落盘，即时生效）/ {@code query <rule>}（规则菜单）/ {@code removeDefault <rule>}
 * （conf 覆盖移除 + 复位默认）。裸 {@code /carpet} = 版本行 + 规则计数 + USAGE。
 */
public class CarpetCommand implements CommandExecutor, TabCompleter
{
    private static final String PERMISSION = CarpetReference.COMMAND_PERMISSION;
    private static final String USAGE = "§e/carpet §7list | setDefault <rule> <value> | query <rule> | removeDefault <rule>";

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args)
    {
        if (!sender.hasPermission(PERMISSION))
        {
            sender.sendMessage("§cInsufficient permissions.");
            return true;
        }

        final SettingsManager manager = CarpetModule.getSettingsManager();
        if (manager == null)
        {
            sender.sendMessage("§cCarpet module not loaded.");
            return true;
        }

        // locked 全拒（上游整树 requires !locked 的等价效果）
        if (manager.locked())
        {
            sender.sendMessage("§cCarpet features are locked by the administrator.");
            return true;
        }

        // 动态规则门禁（上游 root .requires(canUseCommand(carpetCommandPermissionLevel)) 同构）
        if (!CommandHelper.canUseCommand(sender, CarpetSettings.carpetCommandPermissionLevel))
        {
            sender.sendMessage("§cInsufficient permission level for /carpet (requires '" + CarpetSettings.carpetCommandPermissionLevel + "').");
            return true;
        }

        if (args.length == 0)
        {
            sender.sendMessage("§6" + manager.fancyName() + " §7" + manager.version() + "§7: §f" + manager.getRules().size() + " rules registered");
            sender.sendMessage(USAGE);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT))
        {
            case "list" ->
            {
                handleList(sender, manager);
                return true;
            }
            case "setdefault" ->
            {
                handleSetDefault(sender, manager, args);
                return true;
            }
            case "query" ->
            {
                handleQuery(sender, manager, args);
                return true;
            }
            case "removedefault" ->
            {
                handleRemoveDefault(sender, manager, args);
                return true;
            }
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void handleList(final CommandSender sender, final SettingsManager manager)
    {
        sender.sendMessage("§6" + manager.fancyName() + " §7" + manager.version() + " §8— all rules:");
        for (final ParsedRule<?> rule : manager.getRulesSorted())
        {
            sender.sendMessage(" §e" + rule.name + "§7: §f" + rule.value() + (rule.isInDefaultValue() ? " §8(default)" : " §6(modified)"));
        }
    }

    private void handleSetDefault(final CommandSender sender, final SettingsManager manager, final String[] args)
    {
        if (args.length < 3)
        {
            sender.sendMessage("§cUsage: /carpet setDefault <rule> <value>");
            return;
        }
        final ParsedRule<?> rule = manager.getRule(args[1]);
        if (rule == null)
        {
            sender.sendMessage("§cUnknown rule: " + args[1]);
            return;
        }
        // Bukkit args 已按空白切分——单空格重组 = 空格规范化输入串（上游 greedyString 的 B 侧保真边界）
        final String value = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        if (manager.setDefault(sender, rule, value))
        {
            sender.sendMessage("§aDefault value of §e" + rule.name + "§a set to §f" + value + "§7 (applied immediately, persisted to " + CarpetReference.CONFIG_FILE_NAME + ")");
        }
        else
        {
            sender.sendMessage("§cCouldn't set value for rule " + rule.name + ": " + value);
        }
    }

    private void handleQuery(final CommandSender sender, final SettingsManager manager, final String[] args)
    {
        if (args.length != 2)
        {
            sender.sendMessage("§cUsage: /carpet query <rule>");
            return;
        }
        final ParsedRule<?> rule = manager.getRule(args[1]);
        if (rule == null)
        {
            sender.sendMessage("§cUnknown rule: " + args[1]);
            return;
        }
        // 上游 displayRuleMenu 的 § 码等价输出
        sender.sendMessage("§6" + rule.name + "§7: §f" + rule.description);
        for (final String extra : rule.extraInfo)
        {
            sender.sendMessage("§8" + extra);
        }
        sender.sendMessage(" §7Categories: §f" + String.join(", ", rule.categories));
        sender.sendMessage(" §7Current value: §f" + rule.value() + (rule.isInDefaultValue() ? " §8(default)" : " §6(modified)")
                + " §7Default: §f" + rule.defaultValue);
        if (!rule.options.isEmpty())
        {
            sender.sendMessage(" §7Options: §f" + String.join("§7, §f", rule.options) + (rule.isStrict ? " §8(strict)" : " §8(any value)"));
        }
        for (final var validator : rule.validators)
        {
            if (validator.description() != null)
            {
                sender.sendMessage(" §8" + validator.description());
            }
        }
    }

    private void handleRemoveDefault(final CommandSender sender, final SettingsManager manager, final String[] args)
    {
        if (args.length != 2)
        {
            sender.sendMessage("§cUsage: /carpet removeDefault <rule>");
            return;
        }
        final ParsedRule<?> rule = manager.getRule(args[1]);
        if (rule == null)
        {
            sender.sendMessage("§cUnknown rule: " + args[1]);
            return;
        }
        if (manager.removeDefault(sender, rule))
        {
            sender.sendMessage("§aDefault override of §e" + rule.name + "§a removed §7(value reset to §f" + rule.defaultValue + "§7)");
        }
        else
        {
            sender.sendMessage("§cCouldn't remove default of rule " + rule.name);
        }
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args)
    {
        final List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERMISSION))
        {
            return out;
        }
        final SettingsManager manager = CarpetModule.getSettingsManager();
        if (manager == null)
        {
            return out;
        }
        if (args.length == 1)
        {
            for (final String sub : List.of("list", "setDefault", "query", "removeDefault"))
            {
                if (sub.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                {
                    out.add(sub);
                }
            }
        }
        else if (args.length == 2 && (args[0].equalsIgnoreCase("setDefault") || args[0].equalsIgnoreCase("query") || args[0].equalsIgnoreCase("removeDefault")))
        {
            for (final ParsedRule<?> rule : manager.getRulesSorted())
            {
                if (rule.name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                {
                    out.add(rule.name);
                }
            }
        }
        else if (args.length == 3 && args[0].equalsIgnoreCase("setDefault"))
        {
            final ParsedRule<?> rule = manager.getRule(args[1]);
            if (rule != null)
            {
                for (final String option : rule.options)
                {
                    if (option.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                    {
                        out.add(option);
                    }
                }
            }
        }
        return out;
    }
}
