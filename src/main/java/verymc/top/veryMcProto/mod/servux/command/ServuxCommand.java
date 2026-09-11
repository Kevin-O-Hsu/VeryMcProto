package verymc.top.veryMcProto.mod.servux.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.mod.servux.dataproviders.ConfigProvider;
import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;

/**
 * /servux 命令（mod 层）。移植自原版 {@code ServuxCommand}（Brigadier）→ Bukkit {@link CommandExecutor}/{@link TabCompleter}。
 *
 * <p>子命令：reload / save / set / info / list / enable / disable / search / debug / litematic。
 * 权限树对齐上游 ServuxCommand:41-90：根节点 {@code servux.commands}（上游根 requires level 4 的
 * Bukkit 近似，default: op）+ 每子命令独立节点 {@code servux.commands.<sub>}（search 复用 .list，
 * 上游 :90）；旧单节点 {@code servux.command} 经 plugin.yml children 映射自动继承新树（兼容既有授权）。
 * enable/disable/debug/litematic 为我方扩展子命令（挂同名扩展节点，无上游对应）。
 *
 * <p>持久化语义：{@code set} 对齐上游 configModify :307 纯内存（显式 {@code /servux save} 落盘）；
 * enable/disable/debug 为我方扩展，保留切换即时落盘。
 */
public class ServuxCommand implements CommandExecutor, TabCompleter
{
    private static final String PERM_ROOT = "servux.commands";
    private static final String USAGE = "§e/servux §7reload|save|set|info|list|enable|disable|search|debug|litematic";

    /** 子命令权限检查（对齐上游每子命令独立节点；search 复用 list 节点）。 */
    private boolean checkSubPerm(CommandSender sender, String sub)
    {
        if (!sender.hasPermission(PERM_ROOT + "." + sub))
        {
            sender.sendMessage("§c权限不足。");
            return false;
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        // 根门双查：新树节点 + 旧单节点（对不解析 plugin.yml children 的权限插件双保险）
        if (!(sender.hasPermission(PERM_ROOT) || sender.hasPermission("servux.command")))
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
                case "reload" -> { if (checkSubPerm(sender, "reload")) { ConfigProvider.INSTANCE.doReloadConfig(sender); } }
                case "save" -> { if (checkSubPerm(sender, "save")) { ConfigProvider.INSTANCE.doSaveConfig(sender); } }
                case "set" -> { if (checkSubPerm(sender, "set")) { handleSet(sender, args); } }
                case "info" -> { if (checkSubPerm(sender, "info")) { handleInfo(sender, args); } }
                case "list" -> { if (checkSubPerm(sender, "list")) { handleList(sender, args); } }
                case "enable" -> { if (checkSubPerm(sender, "enable")) { handleToggle(sender, args, true); } }
                case "disable" -> { if (checkSubPerm(sender, "disable")) { handleToggle(sender, args, false); } }
                case "search" -> { if (checkSubPerm(sender, "list")) { handleSearch(sender, args); } }
                case "debug" -> { if (checkSubPerm(sender, "debug")) { handleDebug(sender, args); } }
                case "litematic" -> { if (checkSubPerm(sender, "litematic")) { handleLitematic(sender, args); } }
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
            // 对齐上游 configModify :302-307：set 纯内存，持久化走显式 /servux save（上游无即时落盘；
            // 优雅停服时 onDisable 仍全量落盘，差异窗口仅 crash 场景）
            setting.setValueFromString(value);
            sender.sendMessage("§a已设置 " + setting.qualifiedName() + " §7=§f " + setting.valueToString(setting.getValue())
                    + " §7(未落盘，用 /servux save 持久化)");
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

    /**
     * 对齐上游 list :73-87：裸 list = 全部 provider 的 settings 平铺现值；{@code list <provider>}
     * = 该 provider 的 settings（上游 getProviderByName 过滤）。providers 概览上游无此形态，不保留。
     */
    private void handleList(CommandSender sender, String[] args)
    {
        List<IServuxSetting<?>> list;
        if (args.length >= 2)
        {
            var provider = DataProviderManager.INSTANCE.getProviderByName(args[1]);
            if (provider.isEmpty()) { sender.sendMessage("§c未找到 provider: " + args[1]); return; }
            list = provider.get().getSettings();
        }
        else
        {
            list = new ArrayList<>();
            for (var p : DataProviderManager.INSTANCE.getAllProviders())
            {
                list.addAll(p.getSettings());
            }
        }
        this.configList(sender, list);
    }

    /**
     * settings 现值平铺（上游 configList :137-176 的 Bukkit 文本退化版）。
     * 同名 setting 跨 provider 时附加 {@code (provider)} 消歧（上游 :145-165）；
     * <b>值字符串 &lt;10 字符才附现值</b>为上游有意怪癖（:168-171，长值刷屏防护）——逐字照抄，勿"修复"。
     */
    private void configList(CommandSender sender, List<IServuxSetting<?>> list)
    {
        if (list.isEmpty()) { sender.sendMessage("§7（无 settings）"); return; }

        Set<String> appearedNames = new HashSet<>();
        Set<String> appearedMultiTimes = new HashSet<>();
        for (IServuxSetting<?> setting : list)
        {
            if (!appearedNames.add(setting.name())) { appearedMultiTimes.add(setting.name()); }
        }

        for (IServuxSetting<?> setting : list)
        {
            String line = "§e§l" + setting.shortDisplayName().getString();
            if (appearedMultiTimes.contains(setting.name()))
            {
                line += " §7(" + setting.dataProvider().getName() + ")";
            }
            String value = setting.valueToString(setting.getValue());
            if (value.length() < 10)
            {
                line += "§f: " + value;
            }
            sender.sendMessage(line);
        }
    }

    private void handleToggle(CommandSender sender, String[] args, boolean enable)
    {
        if (args.length < 2) { sender.sendMessage("§e/servux " + (enable ? "enable" : "disable") + " <provider>"); return; }
        boolean ok = DataProviderManager.INSTANCE.setProviderEnabled(args[1].toLowerCase(), enable);
        if (ok)
        {
            // 我方扩展语义（上游无 enable/disable 子命令）：切换即时落盘——与 set 的上游显式 save 语义并存，属声明性扩展
            DataProviderManager.INSTANCE.writeToConfig();
            sender.sendMessage("§a" + args[1] + " 已" + (enable ? "启用" : "禁用"));
        }
        else { sender.sendMessage("§c未找到 provider: " + args[1]); }
    }

    private void handleSearch(CommandSender sender, String[] args)
    {
        if (args.length < 2) { sender.sendMessage("§e/servux search <关键词>"); return; }

        // 对齐上游 configSearch（ServuxCommand:111-133）：空格分词 AND + 三臂匹配 + 大小写敏感
        // （上游无 toLowerCase 归一）。Bukkit args 重组回 Brigadier 单串语义。
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String[] searchParts = query.split(" ");

        sender.sendMessage("§6匹配的设置:");
        for (var p : DataProviderManager.INSTANCE.getAllProviders())
        {
            for (IServuxSetting<?> s : p.getSettings())
            {
                if (matchesSearch(s, searchParts))
                {
                    sender.sendMessage(" §7- §f" + s.qualifiedName() + " §8= " + s.valueToString(s.getValue()));
                }
            }
        }
    }

    /**
     * 上游三臂匹配（ServuxCommand:119-131）：name / comment / provider 名任一 contains 即该词命中，
     * 全部词命中才保留（AND）。comment 臂降级说明：Paper 服务端无 lang 资产，settings 构造不传
     * comment 时 {@code comment().getString()} 返回翻译键字符串
     * （servux.config.&lt;provider&gt;.&lt;name&gt;.comment）而非注释文本——上游 Fabric 服务端
     * 加载 en_us 后为真实注释，此为已声明平台差异（根治需 settings 全量传 comment，后续工单）。
     */
    private static boolean matchesSearch(IServuxSetting<?> setting, String[] searchParts)
    {
        for (String part : searchParts)
        {
            if (setting.name().contains(part)) { continue; }
            if (setting.comment().getString().contains(part)) { continue; }
            if (setting.dataProvider().getName().contains(part)) { continue; }
            return false;
        }
        return true;
    }

    /**
     * /servux litematic —— Litematica 投影管理（mod 层）。
     * <ul>
     *   <li>{@code list} —— 列出 schematics/ 目录的 .litematic 文件。</li>
     * </ul>
     * 权限：{@code servux.command}（已在 onCommand 检查）+ litematic_data provider 启用。
     * <p>S2C 文件投递（transmit 子命令）已移除：26.1 stock 客户端 {@code handleBulkData} 的
     * Transmit 分流整块注释（上游未实现接收端，一切帧坠入仅认 BulkEntityReply 的
     * {@code handleBulkEntityData} 被静默丢弃），上游服务端 {@code sendTransmitFile} 亦
     * {@code @Deprecated(forRemoval=true)} 零调用点——死信链于 2026-09 物理删除，恢复走 git revert。
     */
    private void handleLitematic(CommandSender sender, String[] args)
    {
        LitematicsDataProvider prov = LitematicsDataProvider.INSTANCE;
        if (!prov.isEnabled()) { sender.sendMessage("§clitematic_data provider 未启用（/servux enable litematic_data）"); return; }
        if (args.length < 2) { sender.sendMessage("§e/servux litematic list"); return; }

        switch (args[1].toLowerCase())
        {
            case "list" ->
            {
                java.nio.file.Path dir = prov.getTransmitDir();
                sender.sendMessage("§6schematics 目录: §f" + dir.toAbsolutePath());
                try (var stream = java.nio.file.Files.list(dir))
                {
                    var files = stream.filter(f -> f.toString().endsWith(".litematic")).sorted().toList();
                    if (files.isEmpty()) { sender.sendMessage(" §7（无 .litematic 文件）"); }
                    for (var f : files) { sender.sendMessage(" §7- §f" + f.getFileName()); }
                }
                catch (Exception e) { sender.sendMessage("§c读取目录失败: " + e.getMessage()); }
            }
            // S2C transmit 死信链已删（26.1 客户端无接收端，见类头 javadoc 注记）——26.1 线仅保留 list。
            default -> sender.sendMessage("§e/servux litematic list");
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
     *   <li>{@code /servux debug cat <name>} —— 切换单个分类（lifecycle/handshake/network/packet/tick/permission/provider/config/easyplace/schematic）。</li>
     * </ul>
     * <p>命令切换<b>即时持久化</b>到 {@code servux.json}（master + 分类各自独立保存），重启后完全恢复。
     */
    private void handleDebug(CommandSender sender, String[] args)
    {
        if (args.length < 2)
        {
            sender.sendMessage("§6调试状态: §f" + ServuxDebug.statusLine());
            sender.sendMessage("§7用法: §f/servux debug <on|off|status>§7 —— master 总开关 / 状态");
            sender.sendMessage("§7用法: §f/servux debug cat <all|none|分类名>§7 —— 分类（master 与分类正交，两者皆开才输出）");
            sender.sendMessage("§7分类: §flifecycle handshake network packet tick permission provider config easyplace schematic");
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub)
        {
            // master 总开关维度：on/off 只管 master 死活，绝不越权动分类（分类是正交的另一维度）。切换即时持久化。
            case "on" ->
            {
                ServuxDebug.setMaster(true);
                String tip = ServuxDebug.active().isEmpty() ? " §7(分类为空，用 §f/servux debug cat all§7 开全分类)" : "";
                sender.sendMessage("§a调试总开关已开启 §7(仅 master): §f" + ServuxDebug.statusLine() + tip);
                persistDebug();
            }
            case "off" -> { ServuxDebug.setMaster(false); sender.sendMessage("§e调试总开关已关闭 §7(仅 master): §f" + ServuxDebug.statusLine()); persistDebug(); }
            case "status" -> sender.sendMessage("§6调试状态: §f" + ServuxDebug.statusLine());
            // 分类维度：全部归到 cat 下。all/none 是 cat 的特殊值（set 语义，全开/清空）；单个 name 走 toggle。切换即时持久化。
            case "cat" ->
            {
                if (args.length < 3) { sender.sendMessage("§e/servux debug cat <all|none|分类名>"); return; }
                String catName = args[2].toLowerCase();
                if (catName.equals("all")) { ServuxDebug.enableAll(); sender.sendMessage("§a已开启全分类: §f" + ServuxDebug.statusLine()); persistDebug(); return; }
                if (catName.equals("none")) { ServuxDebug.clearCats(); sender.sendMessage("§e已清空全分类: §f" + ServuxDebug.statusLine()); persistDebug(); return; }
                ServuxDebug.Cat cat = ServuxDebug.parseCat(args[2]);
                if (cat == null) { sender.sendMessage("§c未知分类: " + args[2] + " §7(all|none|分类名)"); return; }
                boolean now = ServuxDebug.toggle(cat);
                sender.sendMessage("§a分类 " + cat.name().toLowerCase() + " → " + (now ? "§aON" : "§cOFF"));
                sender.sendMessage("§7当前: §f" + ServuxDebug.statusLine());
                persistDebug();
            }
            default -> sender.sendMessage("§c未知子命令: " + sub + " §7(on/off/cat/status)");
        }
    }

    /** 把当前 servux 调试状态（master + 分类）同步回 settings 并即时落盘 servux.json。 */
    private void persistDebug()
    {
        ConfigProvider.INSTANCE.syncFrameworkToSettings();
        DataProviderManager.INSTANCE.writeToConfig();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
    {
        List<String> out = new ArrayList<>();
        String typed = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        if (args.length == 1)
        {
            // 按子命令权限过滤补全（search 复用 list 节点，与 onCommand 分派一致）
            for (String s : List.of("reload", "save", "set", "info", "list", "enable", "disable", "search", "debug", "litematic"))
            {
                String node = s.equals("search") ? "list" : s;
                if (s.startsWith(typed) && sender.hasPermission(PERM_ROOT + "." + node)) { out.add(s); }
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
            else if (sub.equals("litematic"))
            {
                for (String s : List.of("list"))
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
            for (ServuxDebug.Cat c : ServuxDebug.Cat.values())
            {
                String n = c.name().toLowerCase();
                if (n.startsWith(typed)) { out.add(n); }
            }
        }
        return out;
    }
}
