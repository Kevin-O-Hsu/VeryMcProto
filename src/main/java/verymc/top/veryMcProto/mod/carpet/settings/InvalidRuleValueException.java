package verymc.top.veryMcProto.mod.carpet.settings;

import org.bukkit.command.CommandSender;

/**
 * 规则值非法异常（克隆上游 {@code carpet/api/settings/InvalidRuleValueException.java}，source 类型换 Bukkit）。
 * 无消息构造 = 不向来源发送提示（validator 已自行提示的场景）。
 */
public class InvalidRuleValueException extends Exception
{
    public InvalidRuleValueException(String cause)
    {
        super(cause);
    }

    public InvalidRuleValueException()
    {
        super();
    }

    /** 向来源发送失败提示（有消息时）。 */
    public void notifySource(String ruleName, CommandSender source)
    {
        if (source != null && getMessage() != null)
        {
            source.sendMessage("§cCouldn't set value for rule " + ruleName + ": " + getMessage());
        }
    }
}
