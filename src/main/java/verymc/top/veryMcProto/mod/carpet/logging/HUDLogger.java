package verymc.top.veryMcProto.mod.carpet.logging;

import java.lang.reflect.Field;

import org.bukkit.entity.Player;

/**
 * HUD 型 logger（克隆上游 {@code carpet/logging/HUDLogger.java} 1.21.11 树，同构）——消息进
 * {@link HUDController} footer 缓冲而非系统聊天；退订即时清 footer。
 *
 * <p>偏差：上游 4 参 {@code @Deprecated} 构造器不照抄（零调用死面，AGENTS.md §6 教训 2）。
 */
public class HUDLogger extends Logger
{
    static Logger stardardHUDLogger(String logName, String def, String[] options)
    {
        return stardardHUDLogger(logName, def, options, false);
    }

    static Logger stardardHUDLogger(String logName, String def, String[] options, boolean strictOptions)
    {
        try
        {
            return new HUDLogger(LoggerRegistry.class.getField("__" + logName), logName, def, options, strictOptions);
        }
        catch (NoSuchFieldException e)
        {
            // 上游 :24-26 文案逐字（含 "stardard" 拼写——上游原名如此，勿"修复"）
            throw new RuntimeException("Failed to create logger " + logName);
        }
    }

    HUDLogger(Field field, String logName, String def, String[] options, boolean strictOptions)
    {
        super(field, logName, def, options, strictOptions);
    }

    /** 退订即时清 footer（上游 :38-44）——否则退订者 footer 永久残留最后一帧。 */
    @Override
    public void removePlayer(String playerName)
    {
        Player player = playerFromName(playerName);
        if (player != null)
        {
            HUDController.clearPlayer(player);
        }
        super.removePlayer(playerName);
    }

    /** 消息进 HUD 缓冲（上游 :46-50）。 */
    @Override
    public void sendPlayerMessage(Player player, String... messages)
    {
        for (String message : messages)
        {
            HUDController.addMessage(player, message);
        }
    }
}
