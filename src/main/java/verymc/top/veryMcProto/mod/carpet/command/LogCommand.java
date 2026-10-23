package verymc.top.veryMcProto.mod.carpet.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.logging.Logger;
import verymc.top.veryMcProto.mod.carpet.logging.LogSubscriptionStore;
import verymc.top.veryMcProto.mod.carpet.logging.LoggerRegistry;
import verymc.top.veryMcProto.mod.carpet.util.CommandHelper;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * /log 命令（对照上游 {@code carpet/commands/LogCommand.java} 26.2 树；Paper Brigadier 载体——
 * {@code PlayerCommand} 范式，plugin.yml 无条目）。
 *
 * <p><b>命令形状</b>（subscribe/clear 形——phases §6 交付物① 定义，非上游 flat 树）：
 * <pre>/log                     订阅状态列表（仅玩家；上游 listLogs :69-135 结构）
 * /log subscribe &lt;logger&gt; [option]   显式订阅（option 缺省 = logger 默认值；重复订阅覆写）
 * /log clear               退订全部
 * /log clear &lt;logger&gt;       单项退订（校验 registry，上游 :159-163 拒绝文案）</pre>
 *
 * <p><b>与上游的偏差</b>（登记，docs/carpet/architecture.md）：
 * <ul>
 *   <li>上游 flat 树（{@code /log <name> [option] [player]} toggle 形 + clear [player] 定向）→
 *       subscribe/clear 形、自用面（无 {@code <player>} 定向参数、无 toggle）；</li>
 *   <li>上游 listLogs 为可点击/悬停组件（:104-131 "!" 点击 + "^" 悬停）→ B 侧纯文本 + 命令提示行
 *       （假人面偏差 #28 Messenger 替换范式同判）；列表经 Bukkit {@code sendMessage(String)} 发送（§ 码解析）；</li>
 *   <li>console / 非玩家对任意子命令统一回执拒绝（上游裸 /log "For players only" :71-79 同款文案——
 *       其余子命令上游经 getPlayerByName(null) 恒失败 "No player specified"，B 侧自用面无目标参，
 *       统一为同一句拒绝）；</li>
 *   <li>每次订阅变更同步 {@link LogSubscriptionStore}（显式 + 墓碑，跨会话持久判据②）。</li>
 * </ul>
 */
public class LogCommand
{
    /** 建树（CarpetModule 经 LifecycleEvents.COMMANDS 注册；store 经闭包注入）。 */
    public static LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> build(LogSubscriptionStore store)
    {
        return literal("log")
                .requires(source -> CommandHelper.canUseCommand(nms(source), CarpetSettings.commandLog))
                .executes(c -> listLogs(nms(c.getSource())))
                .then(literal("subscribe")
                        .then(argument("logger", StringArgumentType.word())
                                .suggests((c, b) -> suggest(sortedLoggerNames(), b))
                                .executes(c -> subscribe(nms(c.getSource()), store, getString(c, "logger"), null))
                                .then(argument("option", StringArgumentType.string())
                                        .suggests((c, b) -> suggest(loggerOptions(c), b))
                                        .executes(c -> subscribe(nms(c.getSource()), store,
                                                getString(c, "logger"), getString(c, "option"))))))
                .then(literal("clear")
                        .executes(c -> clearAll(nms(c.getSource()), store))
                        .then(argument("logger", StringArgumentType.word())
                                .suggests((c, b) -> suggest(sortedLoggerNames(), b))
                                .executes(c -> clearOne(nms(c.getSource()), store, getString(c, "logger")))))
                .build();
    }

    /** NMS source cast（Paper 接口的实现即 NMS CommandSourceStack，PlayerCommand 同款单点）。 */
    private static CommandSourceStack nms(io.papermc.paper.command.brigadier.CommandSourceStack source)
    {
        return (CommandSourceStack) source;
    }

    /** 订阅状态列表（上游 listLogs :69-135 的纯文本面）。 */
    private static int listLogs(CommandSourceStack source)
    {
        ServerPlayer player;
        try
        {
            player = source.getPlayerOrException();
        }
        catch (CommandSyntaxException e)
        {
            source.sendFailure(Component.literal("For players only"));
            return 0;
        }
        Map<String, String> subs = LoggerRegistry.getPlayerSubscriptions(source.getTextName());
        if (subs == null)
        {
            subs = new HashMap<>();
        }
        List<String> allLogs = sortedLoggerNames();
        List<String> lines = new ArrayList<>();
        lines.add("§f_____________________");
        lines.add("§fAvailable logging options:");
        for (String lname : allLogs)
        {
            Logger logger = LoggerRegistry.getLogger(lname);
            String[] options = logger.getOptions();
            StringBuilder line = new StringBuilder("§f - ").append(lname).append(": ");
            if (options.length == 0)
            {
                // option-less logger（tps）——上游 :97-109 分支同构
                line.append(subs.containsKey(lname) ? "§fSubscribed" : "§7[Subscribe]");
            }
            else
            {
                String contextColor = subs.containsKey(lname) ? "§f" : "§7";
                for (String option : options)
                {
                    if (subs.containsKey(lname) && subs.get(lname).equalsIgnoreCase(option))
                    {
                        line.append("§l[").append(option).append("]§r ");
                    }
                    else
                    {
                        line.append(contextColor).append("[").append(option).append("] ");
                    }
                }
            }
            if (subs.containsKey(lname))
            {
                line.append("§4§l[X]§r (run /log clear ").append(lname).append(")");
            }
            lines.add(line.toString());
        }
        lines.add("§7Usage: /log subscribe <logger> [option] | /log clear [logger]");
        for (String line : lines)
        {
            player.getBukkitEntity().sendMessage(line);
        }
        return 1;
    }

    /** 显式订阅（上游 subscribePlayer :193-221 同位校验链 + store 持久化）。 */
    private static int subscribe(CommandSourceStack source, LogSubscriptionStore store, String loggerName, String option)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            source.sendFailure(Component.literal("For players only"));
            return 0;
        }
        Logger logger = LoggerRegistry.getLogger(loggerName);
        if (logger == null)
        {
            source.sendFailure(Component.literal("Unknown logger: " + loggerName).withStyle(ChatFormatting.BOLD));
            return 0;
        }
        if (!logger.isOptionValid(option))
        {
            source.sendFailure(Component.literal("Invalid option: " + option));
            return 0;
        }
        String name = source.getTextName();
        LoggerRegistry.subscribePlayer(name, loggerName, option);
        // 持久化 = 归一后的生效 option（option==null 时 subscribePlayer 已写 logger 默认值；tps 恒 null → "" 哨兵）
        String effective = LoggerRegistry.getPlayerSubscriptions(name).get(loggerName);
        store.markExplicit(player.getUUID(), name, loggerName, effective);
        final String message = option == null
                ? "Subscribed to " + loggerName
                : "Subscribed to " + loggerName + "(" + option + ")";
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        return 1;
    }

    /** 全退订（上游 unsubFromAll :136-150 同位 + 全量墓碑）。 */
    private static int clearAll(CommandSourceStack source, LogSubscriptionStore store)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            source.sendFailure(Component.literal("For players only"));
            return 0;
        }
        String name = source.getTextName();
        List<String> allLogs = sortedLoggerNames();
        for (String logname : allLogs)
        {
            LoggerRegistry.unsubscribePlayer(name, logname);
        }
        store.clearAll(player.getUUID(), name, allLogs);
        source.sendSuccess(() -> Component.literal("Unsubscribed from all logs")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        return 1;
    }

    /** 单项退订（上游 unsubFromLogger :151-167 同位校验 + 墓碑）。 */
    private static int clearOne(CommandSourceStack source, LogSubscriptionStore store, String loggerName)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            source.sendFailure(Component.literal("For players only"));
            return 0;
        }
        if (LoggerRegistry.getLogger(loggerName) == null)
        {
            source.sendFailure(Component.literal("Unknown logger: " + loggerName).withStyle(ChatFormatting.BOLD));
            return 0;
        }
        String name = source.getTextName();
        LoggerRegistry.unsubscribePlayer(name, loggerName);
        store.removeExplicit(player.getUUID(), name, loggerName);
        source.sendSuccess(() -> Component.literal("Unsubscribed from " + loggerName)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        return 1;
    }

    private static List<String> sortedLoggerNames()
    {
        List<String> names = new ArrayList<>(LoggerRegistry.getLoggerNames());
        Collections.sort(names);
        return names;
    }

    /** option 补全（上游 :49-53——logger 未知时空建议）。 */
    private static String[] loggerOptions(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> c)
    {
        Logger logger = LoggerRegistry.getLogger(getString(c, "logger"));
        return logger == null ? new String[0] : logger.getOptions();
    }
}
