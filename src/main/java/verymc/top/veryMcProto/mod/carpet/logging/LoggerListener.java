package verymc.top.veryMcProto.mod.carpet.logging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import verymc.top.veryMcProto.framework.nms.Nms;
import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.patches.EntityPlayerMPFake;

/**
 * /log 订阅生命周期监听（B 侧新增类——上游对应挂点为 {@code PlayerList_coreMixin}（进服）与
 * {@code ServerGamePacketListenerImpl_coreMixin}（退服），Paper 无 Mixin 的 Bukkit 事件等价物，偏差登记）。
 *
 * <p><b>join 谓词</b>（钉死，防两种读法分叉）：
 * <ul>
 *   <li>当前名<b>在内存册</b>（{@code getPlayerSubscriptions(name) != null}——playerSubscriptions 主表
 *       即名册：清空即删键、离线桶条目亦在主表镜像）→ 仅桶迁移（会话内重登）；</li>
 *   <li>当前名<b>不在内存册</b>（新玩家 / 重启后首连 / <b>改名重登</b>——旧名孤儿条目不匹配新名）→
 *       store 按 UUID 载入显式订阅 + 以当前名入册 + 名字快照更新；</li>
 *   <li>随后 <b>defaultLoggers 重评估</b>（每次 join，按当前规则值）：对「未订阅且无墓碑」的默认项施用
 *       ——<b>不落盘</b>（显式优先 + 墓碑拦截 + 规则变更对未显式选择者持续生效，三性质见 store javadoc）。</li>
 * </ul>
 *
 * <p><b>假人豁免</b>：join/quit 全跳过（{@code EntityPlayerMPFake} 判定）——假人 HUD 天然 void
 * （FakeClientConnection.send no-op 吸收，上游发往假人的消息同样不可见 = 可见行为等价），而 B 侧持久层
 * 新增故须显式豁免（防 /player 反复 spawn/kill 向 carpet-logs.json 灌永久死记录；shadow 共享真人
 * UUID 亦不触达，防污染真人册——偏差登记）。
 *
 * <p>与上游的其余偏差：{@code onPlayerQuit} = 桶迁移（上游 :230-240 同构），store 不动（显式状态已持久）。
 * {@link #handleJoin} 供 CarpetModule 启用期对在线玩家补扫（reload 时无 join 事件，CarpetHelloHandler
 * 补扫同款 B 侧增补）。
 */
public class LoggerListener implements Listener
{
    private final LogSubscriptionStore store;

    public LoggerListener(LogSubscriptionStore store)
    {
        this.store = store;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        if (Nms.toNms(event.getPlayer()) instanceof EntityPlayerMPFake)
        {
            return;
        }
        onPlayerJoin(event.getPlayer().getName(), event.getPlayer().getUniqueId(), store);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        if (Nms.toNms(event.getPlayer()) instanceof EntityPlayerMPFake)
        {
            return;
        }
        LoggerRegistry.playerDisconnected(event.getPlayer().getName());
    }

    /** 启用期在线玩家补扫入口（等价 join 流程）。 */
    public void handleJoin(org.bukkit.entity.Player player)
    {
        if (Nms.toNms(player) instanceof EntityPlayerMPFake)
        {
            return;
        }
        onPlayerJoin(player.getName(), player.getUniqueId(), store);
    }

    /** join 主流程（静态纯 JVM 可测——测试直接驱动，不依赖 Bukkit 事件）。 */
    static void onPlayerJoin(String name, UUID uuid, LogSubscriptionStore store)
    {
        boolean inRoster = LoggerRegistry.getPlayerSubscriptions(name) != null;
        LogSubscriptionStore.PlayerRecord record = store.record(uuid);
        if (!inRoster)
        {
            if (record != null)
            {
                for (Map.Entry<String, String> sub : record.subs.entrySet())
                {
                    if (LoggerRegistry.getLogger(sub.getKey()) != null)
                    {
                        LoggerRegistry.subscribePlayer(name, sub.getKey(),
                                "".equals(sub.getValue()) ? null : sub.getValue());
                    }
                }
                store.updateName(uuid, name);
            }
        }
        else
        {
            LoggerRegistry.playerConnected(name);
        }
        applyDefaults(name, record, store);
    }

    /** defaultLoggers 重评估（上游 Logger.java:215-226 施用语义的墓碑感知改写——见类 javadoc）。 */
    static void applyDefaults(String name, LogSubscriptionStore.PlayerRecord record, LogSubscriptionStore store)
    {
        Map<String, String> subs = LoggerRegistry.getPlayerSubscriptions(name);
        Set<String> tombstones = record == null ? Set.of() : record.off;
        for (DefaultEntry def : parseDefaults(CarpetSettings.defaultLoggers))
        {
            Logger logger = LoggerRegistry.getLogger(def.logger());
            if (logger == null)
            {
                continue; // "none" 与未知名自然跳过（上游 getLogger==null 同判）
            }
            if (subs != null && subs.containsKey(def.logger()))
            {
                continue; // 显式/既有订阅优先（上游 offline 桶优先 else-if 同位语义）
            }
            if (tombstones.contains(def.logger()))
            {
                continue; // 显式退订墓碑拦截（B 侧持久化新增语义）
            }
            LoggerRegistry.subscribePlayer(name, def.logger(), def.option()); // 不落盘
        }
    }

    /**
     * 默认值 CSV 解析（上游 Logger.java:216-224 逐字语义：split(",") → split(" ", 2)——
     * 条目 = "logger" 或 "logger option"；HashSet 去重上游同款；不 trim（"mobcaps overworld" 的空格
     * 是 option 分隔符，trim 会破坏语义）。
     */
    static List<DefaultEntry> parseDefaults(String raw)
    {
        List<DefaultEntry> list = new ArrayList<>();
        if (raw == null)
        {
            return list;
        }
        for (String str : new HashSet<>(Arrays.asList(raw.split(","))))
        {
            String[] vars = str.split(" ", 2);
            list.add(new DefaultEntry(vars[0], vars.length == 1 ? null : vars[1]));
        }
        return list;
    }

    /** 默认项（logger 名 + option，option null = 该 logger 默认值）。 */
    record DefaultEntry(String logger, String option) { }
}
