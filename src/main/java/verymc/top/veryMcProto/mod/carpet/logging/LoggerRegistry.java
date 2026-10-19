package verymc.top.veryMcProto.mod.carpet.logging;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import verymc.top.veryMcProto.Reference;

/**
 * logger 注册表（克隆上游 {@code carpet/logging/LoggerRegistry.java} 1.21.11 树，逐段同构——对照行号见
 * 各方法注释；注册面锚 :40-48 为 P4 子集：仅 tps/mobcaps 两条 HUD 型，五条 chat 型与 counter/packets
 * 见 scope §4 后置/不做判定）。
 *
 * <p>与上游的偏差（对账登记，docs/carpet.md）：
 * <ul>
 *   <li><b>删 {@code seenPlayers}/{@code firstTime}</b>——其唯一消费点（上游 {@code Logger.onPlayerConnect}
 *       :215-226 的 defaultLoggers 首连施用）已随跨会话持久化模型迁至 {@link LoggerListener}
 *       （每 join 墓碑感知重评估），无第二消费方；</li>
 *   <li><b>删 {@code togglePlayerSubscription}</b>（上游 :91-103）——上游唯一调用点 = flat 命令形状
 *       {@code /log <name>} 切换分支（LogCommand.java:182），该形状已被 subscribe/clear 命令形替代
 *       （phases §6 交付物①），B 侧永久无消费面（零调用死面不照抄，AGENTS.md §6 教训 2）；</li>
 *   <li>玩家名键保留上游形态（HashMap 容 null option 值——tps 默认 option 即 null，上游 :70 同构）；
 *       跨会话持久层（UUID 键 + 名字快照）在 {@link LogSubscriptionStore}，与本内存册经
 *       {@link LoggerListener} join 谓词会合。</li>
 * </ul>
 */
public final class LoggerRegistry
{
    /** logger 名 → Logger（上游 :17）。 */
    private static final Map<String, Logger> loggerRegistry = new HashMap<>();

    /** 玩家名 →（logger 名 → option）（上游 :19）。 */
    private static final Map<String, Map<String, String>> playerSubscriptions = new HashMap<>();

    // 快捷位（上游 :21-29 子集）：setAccess 经 Logger 持有的本类 Field 反射写入——保留上游机制（:117-129）
    public static boolean __tps;
    public static boolean __mobcaps;

    private LoggerRegistry() { }

    /** 生命周期入口（上游 :31-36；无扩展注册面——scarpet 不移植）。 */
    public static void initLoggers()
    {
        stopLoggers();
        registerLoggers();
    }

    /** 注册面（上游 :38-50 的 P4 子集）。 */
    static void registerLoggers()
    {
        // 上游 :44 / :47 逐字（tps 无默认 option 无选项；mobcaps 默认 dynamic 四选项）
        registerLogger("tps", HUDLogger.stardardHUDLogger("tps", null, null));
        registerLogger("mobcaps", HUDLogger.stardardHUDLogger("mobcaps", "dynamic",
                new String[]{"dynamic", "overworld", "nether", "end"}));
    }

    /** 取 logger，无则 null（上游 :55）。 */
    public static Logger getLogger(String name)
    {
        return loggerRegistry.get(name);
    }

    /** 全部 logger 名（上游 :60）——命令补全与列表用。 */
    public static Set<String> getLoggerNames()
    {
        return loggerRegistry.keySet();
    }

    /** 订阅（上游 :65-72）：option 为 null 归一为 logger 默认值（tps 恒 null、mobcaps "dynamic"）。 */
    public static void subscribePlayer(String playerName, String logName, String option)
    {
        playerSubscriptions.computeIfAbsent(playerName, k -> new HashMap<>());
        Logger log = loggerRegistry.get(logName);
        if (option == null)
        {
            option = log.getDefault();
        }
        playerSubscriptions.get(playerName).put(logName, option);
        log.addPlayer(playerName, option);
    }

    /** 退订（上游 :77-86）——清空即删玩家键（上游 :84 同构；跨会话墓碑在 store 层）。 */
    public static void unsubscribePlayer(String playerName, String logName)
    {
        if (playerSubscriptions.containsKey(playerName))
        {
            Map<String, String> subscriptions = playerSubscriptions.get(playerName);
            subscriptions.remove(logName);
            loggerRegistry.get(logName).removePlayer(playerName);
            if (subscriptions.isEmpty())
            {
                playerSubscriptions.remove(playerName);
            }
        }
    }

    /** 玩家当前订阅表（上游 :108-115；无订阅返回 null——上游同构，命令层需自备空表兜底）。 */
    public static Map<String, String> getPlayerSubscriptions(String playerName)
    {
        return playerSubscriptions.get(playerName);
    }

    /** 快捷位同步（上游 :117-129）——Field 反射写本类静态 boolean，保留上游机制。 */
    protected static void setAccess(Logger logger)
    {
        boolean value = logger.hasOnlineSubscribers();
        try
        {
            Field f = logger.getField();
            f.setBoolean(null, value);
        }
        catch (IllegalAccessException e)
        {
            Reference.logger().severe("[Carpet] Cannot change logger quick access field");
        }
    }

    /** 注册 logger（上游 :133-137）。 */
    public static void registerLogger(String name, Logger logger)
    {
        loggerRegistry.put(name, logger);
        setAccess(logger);
    }

    /** 服务停止全清（上游 :141-150）——显式订阅的跨会话存活由 store 承载，不随此丢失。 */
    public static void stopLoggers()
    {
        for (Logger log : loggerRegistry.values())
        {
            log.serverStopped();
        }
        loggerRegistry.clear();
        playerSubscriptions.clear();
    }

    /** 进服广播（上游 :151-164 减 seenPlayers/firstTime，见类 javadoc）——桶迁移。 */
    public static void playerConnected(String playerName)
    {
        for (Logger log : loggerRegistry.values())
        {
            log.onPlayerConnect(playerName);
        }
    }

    /** 退服广播（上游 :166-172）——桶迁移。 */
    public static void playerDisconnected(String playerName)
    {
        for (Logger log : loggerRegistry.values())
        {
            log.onPlayerDisconnect(playerName);
        }
    }
}
