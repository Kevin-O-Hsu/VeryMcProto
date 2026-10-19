package verymc.top.veryMcProto.mod.carpet.settings;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * 规则值验证器（克隆上游 {@code carpet/api/settings/Validator.java} 语义，source 类型换 Bukkit
 * {@link CommandSender}；上游 legacy/api 两代验证器在 B 侧合并为单代——B 无迁移负担）。
 *
 * <p>验证器可在通过时返回修正值（不必是原值）；返回 null = 拒绝本次变更。
 * {@code source == null} 表示 conf 载入路径（上游同语义—— CarpetPermissionLevel 借此放行 conf 侧变更）。
 *
 * <p>内置验证器（上游 {@code carpet/settings/Validator.java} 嵌套类 + {@code api/settings/Validators.java}
 * 的 CommandLevel 就近合并，见各处注释）。
 */
public abstract class Validator<T>
{
    /**
     * 验证新值。
     *
     * @param source 变更来源（命令路径非 null；conf 载入为 null）
     * @param currentRule 被变更的规则
     * @param newValue 类型转换后的新值
     * @param userInput 用户的原始输入串（严格校验据此判定）
     * @return 实际写入值；null = 拒绝
     */
    public abstract T validate(CommandSender source, ParsedRule<T> currentRule, T newValue, String userInput);

    /** 验证器附加说明（失败提示与 query 展示）。 */
    public String description()
    {
        return null;
    }

    /** 验证失败时对来源的提示（上游 notifyFailure 同构，§ 码英文）。 */
    public void notifyFailure(CommandSender source, ParsedRule<T> currentRule, String providedValue)
    {
        if (source != null)
        {
            source.sendMessage("§cWrong value for " + currentRule.name + ": " + providedValue);
            if (description() != null)
            {
                source.sendMessage("§c" + description());
            }
        }
    }

    /**
     * COMMAND 类目规则的自动挂载验证器（上游 {@code carpet/settings/Validator.java} 的 {@code _COMMAND} 同构）。
     * 上游副作用 = {@code notifyPlayersCommandsChanged}（经 server schedule 重发全服命令树）——
     * B 侧等价 = {@link org.bukkit.entity.Player#updateCommands()}（命令路径在主线程，直接刷新）。
     */
    public static class _COMMAND<T> extends Validator<T>
    {
        @Override
        public T validate(CommandSender source, ParsedRule<T> currentRule, T newValue, String userInput)
        {
            if (source != null)
            {
                for (var player : Bukkit.getOnlinePlayers())
                {
                    player.updateCommands();
                }
            }
            return newValue;
        }

        @Override
        public String description()
        {
            return "It has an accompanying command";
        }
    }

    /**
     * 严格选项校验（上游 {@code carpet/settings/Validator.java} 的 {@code StrictValidator} 同构）。
     * 上游置于验证器链索引 0——防止带副作用的验证器在非法输入下先执行。
     */
    public static class StrictValidator<T> extends Validator<T>
    {
        @Override
        public T validate(CommandSender source, ParsedRule<T> currentRule, T newValue, String userInput)
        {
            if (!currentRule.options.contains(userInput))
            {
                if (source != null)
                {
                    source.sendMessage("§cValid options: " + currentRule.options);
                }
                return null;
            }
            return newValue;
        }
    }

    /**
     * 命令级别串校验（上游 {@code carpet/api/settings/Validators.java} 的 {@code CommandLevel} 同构）。
     * COMMAND 类目的 String 规则自动挂载；其 OPTIONS 亦是无显式 options 时该类规则的推断值域。
     */
    public static class CommandLevel extends Validator<String>
    {
        public static final List<String> OPTIONS = List.of("true", "false", "ops", "0", "1", "2", "3", "4");

        @Override
        public String validate(CommandSender source, ParsedRule<String> currentRule, String newValue, String userInput)
        {
            if (!OPTIONS.contains(newValue))
            {
                return null;
            }
            return newValue;
        }

        @Override
        public String description()
        {
            return "Can be limited to 'ops' only, true/false for everyone/no one, or a custom permission level";
        }
    }
}
