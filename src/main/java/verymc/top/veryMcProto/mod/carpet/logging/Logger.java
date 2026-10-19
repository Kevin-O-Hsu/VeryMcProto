package verymc.top.veryMcProto.mod.carpet.logging;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import verymc.top.veryMcProto.Reference;

/**
 * 订阅模型基类（克隆上游 {@code carpet/logging/Logger.java} 26.2 树，逐段同构——对照行号见各方法注释）。
 *
 * <p>与上游的偏差（对账登记，docs/carpet/architecture.md）：
 * <ul>
 *   <li>消息载体 NMS {@code Component[]}（Messenger DSL）→ {@code String[]}（§ 码文本——HUD 侧即
 *       {@code setPlayerListFooter(String)} 的直接输入，chat 侧经 Bukkit {@code sendMessage(String)}
 *       解析 § 码）；色字母 → § 码映射见 {@link SpawnReport}；</li>
 *   <li>{@code playerFromName} 经 {@code Bukkit.getPlayerExact}（无 Bukkit 运行时的纯 JVM 单测环境
 *       返回 null = 恒离线桶，测试面友好）；</li>
 *   <li><b>不照抄上游死成员</b>（上游全树零/本范围外调用点，AGENTS.md §6 教训 2）：
 *       {@code stardardLogger} 工厂（调用点 = 五条 chat 型 logger 注册，后置池）、
 *       {@code log(lMessageIgnorePlayer)}（调用点 = counter/packets 与 TNT 族 helper，均不在范围）、
 *       {@code getAcceptedOption}（上游全树零调用）、4 参 {@code @Deprecated} 构造器——后置 chat 型
 *       logger 落地时回填；</li>
 *   <li>{@code onPlayerConnect} 仅保留 offline→online 桶迁移半边——上游 {@code firstTime} 默认订阅施用
 *       半边（:215-226）已随跨会话持久化模型迁至 {@link LoggerListener}（墓碑感知的逐 join 重评估），
 *       {@code seenPlayers}/{@code firstTime} 因此整体删除（无第二消费方）。</li>
 * </ul>
 */
public class Logger
{
    /** 已订阅且在线的玩家：名 → option（上游 :20-21）。 */
    private final Map<String, String> subscribedOnlinePlayers = new HashMap<>();

    /** 已订阅且离线的玩家（上游 :23-24）——会话内重登经桶迁移保活，上游 :206-214。 */
    private final Map<String, String> subscribedOfflinePlayers = new HashMap<>();

    private final Field acceleratorField;
    private final String logName;
    private final String defaultOption;
    private final String[] options;
    private final boolean strictOptions;

    Logger(Field acceleratorField, String logName, String def, String[] options, boolean strictOptions)
    {
        this.acceleratorField = acceleratorField;
        this.logName = logName;
        this.defaultOption = def;
        this.options = options == null ? new String[0] : options;
        this.strictOptions = strictOptions;
        if (acceleratorField == null)
        {
            Reference.logger().severe("[Carpet] Logger " + logName + " is missing a specified accelerator");
        }
    }

    public String getDefault()
    {
        return defaultOption;
    }

    public String[] getOptions()
    {
        return options;
    }

    public String getLogName()
    {
        return logName;
    }

    /**
     * 订阅玩家（上游 :87-98）：玩家在线入 online 桶，否则入 offline 桶（在线判定经
     * {@link #playerFromName}）。
     */
    public void addPlayer(String playerName, String option)
    {
        if (playerFromName(playerName) != null)
        {
            subscribedOnlinePlayers.put(playerName, option);
        }
        else
        {
            subscribedOfflinePlayers.put(playerName, option);
        }
        LoggerRegistry.setAccess(this);
    }

    /** 退订玩家（上游 :103-108）。 */
    public void removePlayer(String playerName)
    {
        subscribedOnlinePlayers.remove(playerName);
        subscribedOfflinePlayers.remove(playerName);
        LoggerRegistry.setAccess(this);
    }

    /** 是否有在线订阅者（上游 :113-116）——{@link LoggerRegistry#setAccess} 快捷位的数据源。 */
    public boolean hasOnlineSubscribers()
    {
        return !subscribedOnlinePlayers.isEmpty();
    }

    /** 服务停止清空（上游 :118-122）；跨会话持久由 {@link LogSubscriptionStore} 承载（B 侧新增层）。 */
    public void serverStopped()
    {
        subscribedOnlinePlayers.clear();
        subscribedOfflinePlayers.clear();
    }

    public Field getField()
    {
        return acceleratorField;
    }

    /** 按玩家/option 逐订阅者求值消息（上游 :133-147；mobcaps 行用此形态——option 决定维度）。 */
    @FunctionalInterface
    public interface LMessage
    {
        String[] get(String playerOption, Player player);
    }

    public void log(LMessage messagePromise)
    {
        for (Map.Entry<String, String> en : subscribedOnlinePlayers.entrySet())
        {
            Player player = playerFromName(en.getKey());
            if (player != null)
            {
                String[] messages = messagePromise.get(en.getValue(), player);
                if (messages != null)
                {
                    sendPlayerMessage(player, messages);
                }
            }
        }
    }

    /** 消息仅求值一次、全体订阅者同享（上游 :177-189；tps 行用此形态——option 无关）。 */
    public void log(Supplier<String[]> messagePromise)
    {
        String[] cannedMessages = null;
        for (Map.Entry<String, String> en : subscribedOnlinePlayers.entrySet())
        {
            Player player = playerFromName(en.getKey());
            if (player != null)
            {
                if (cannedMessages == null)
                {
                    cannedMessages = messagePromise.get();
                }
                sendPlayerMessage(player, cannedMessages);
            }
        }
    }

    /** 发送消息（上游 :191-194 为系统聊天；HUD 型经 {@link HUDLogger} 覆写进 footer 缓冲）。 */
    public void sendPlayerMessage(Player player, String... messages)
    {
        for (String message : messages)
        {
            player.sendMessage(message);
        }
    }

    /** 名 → 在线 Bukkit Player（上游 :199-202 经 PlayerList；无 Bukkit 运行时 → null = 离线桶）。 */
    protected Player playerFromName(String name)
    {
        try
        {
            return Bukkit.getPlayerExact(name);
        }
        catch (Throwable t)
        {
            return null; // 纯 JVM 单测环境（Bukkit 未初始化）——恒按离线处理
        }
    }

    /** 进服：offline 桶 → online 桶迁移（上游 :206-214 的迁移半边；默认施用半边见类 javadoc 偏差）。 */
    public void onPlayerConnect(String playerName)
    {
        if (subscribedOfflinePlayers.containsKey(playerName))
        {
            subscribedOnlinePlayers.put(playerName, subscribedOfflinePlayers.remove(playerName));
        }
        LoggerRegistry.setAccess(this);
    }

    /** 退服：online 桶 → offline 桶（上游 :230-240）。 */
    public void onPlayerDisconnect(String playerName)
    {
        if (subscribedOnlinePlayers.containsKey(playerName))
        {
            subscribedOfflinePlayers.put(playerName, subscribedOnlinePlayers.remove(playerName));
        }
        LoggerRegistry.setAccess(this);
    }

    /** option 合法性（上游 :248-254）：strict = 必须在 options 内；非 strict = 任意非 null（B 侧命令入参
     * 的 null 缺省已在 subscribePlayer 归一为 logger 默认值，此处的 null 判定与上游同构）。 */
    public boolean isOptionValid(String option)
    {
        if (strictOptions)
        {
            return Arrays.asList(options).contains(option);
        }
        return option != null;
    }
}
