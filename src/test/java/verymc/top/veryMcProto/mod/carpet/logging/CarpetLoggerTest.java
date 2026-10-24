package verymc.top.veryMcProto.mod.carpet.logging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.world.entity.MobCategory;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /log 子系统纯 JVM 单测：注册表订阅语义（含桶迁移——纯 JVM 环境 playerFromName 恒 null
 * = 恒离线桶，恰好使 onPlayerConnect 的 offline→online 迁移可测）、持久册墓碑模型与 round-trip、
 * join 分支矩阵（经 {@link LoggerListener#onPlayerJoin} 静态入口驱动）、defaultLoggers CSV 解析、
 * 热力色阈值与 mobcaps 行格式。
 *
 * <p>命令执行体（Bukkit/Brigadier 对象）与 HUD flush 不在纯 JVM 射程——实机判据承载
 * （runServer：/log 列表/订阅回执/持久往返；clear unknown-logger 的拒绝文案同在实机面）。
 */
class CarpetLoggerTest
{
    @TempDir
    Path dataFolder;

    private LogSubscriptionStore store;
    private String originalDefaultLoggers;

    @BeforeEach
    void setUp()
    {
        LoggerRegistry.initLoggers();
        store = new LogSubscriptionStore(dataFolder);
        store.load();
        originalDefaultLoggers = CarpetSettings.defaultLoggers;
    }

    @AfterEach
    void tearDown()
    {
        CarpetSettings.defaultLoggers = originalDefaultLoggers;
        LoggerRegistry.stopLoggers();
    }

    // ───── 注册表订阅语义（上游 LoggerRegistry.java:65-115 同构）─────

    @Test
    void subscribeNormalizesNullOptionToLoggerDefault()
    {
        LoggerRegistry.subscribePlayer("Steve", "mobcaps", null);
        assertEquals("dynamic", LoggerRegistry.getPlayerSubscriptions("Steve").get("mobcaps"));
        LoggerRegistry.subscribePlayer("Alex", "tps", null);
        // tps 默认 option = null（上游 :44），HashMap 容 null 值——上游 :70 同构
        assertNull(LoggerRegistry.getPlayerSubscriptions("Alex").get("tps"));
        // 纯 JVM 环境 playerFromName 恒 null → 离线桶 → 快捷位 false（在线桶判定的文档化副作用）
        assertFalse(LoggerRegistry.__mobcaps);
        assertFalse(LoggerRegistry.__tps);
    }

    @Test
    void unsubscribeRemovesPlayerKeyWhenEmpty()
    {
        LoggerRegistry.subscribePlayer("Steve", "tps", null);
        LoggerRegistry.unsubscribePlayer("Steve", "tps");
        assertNull(LoggerRegistry.getPlayerSubscriptions("Steve"), "清空即删玩家键（上游 :84）");
    }

    @Test
    void bucketMigrationFlipsQuickAccessBits()
    {
        LoggerRegistry.subscribePlayer("Steve", "mobcaps", "overworld");
        assertFalse(LoggerRegistry.__mobcaps, "纯 JVM 环境订阅落离线桶");
        LoggerRegistry.playerConnected("Steve");
        assertTrue(LoggerRegistry.__mobcaps, "onPlayerConnect 迁移 offline→online（上游 :206-214）");
        LoggerRegistry.playerDisconnected("Steve");
        assertFalse(LoggerRegistry.__mobcaps, "onPlayerDisconnect 迁移 online→offline（上游 :230-240）");
        // 重连再迁移——订阅语义保活
        LoggerRegistry.playerConnected("Steve");
        assertEquals("overworld", LoggerRegistry.getPlayerSubscriptions("Steve").get("mobcaps"));
    }

    // ───── 持久册：round-trip / 哨兵 / 墓碑 / 坏 JSON 容错 ─────

    @Test
    void explicitSubscriptionsRoundTripWithNullSentinel()
    {
        UUID steve = UUID.randomUUID();
        store.markExplicit(steve, "Steve", "tps", null);
        store.markExplicit(steve, "Steve", "mobcaps", "overworld");

        LogSubscriptionStore reloaded = new LogSubscriptionStore(dataFolder);
        reloaded.load();
        LogSubscriptionStore.PlayerRecord record = reloaded.record(steve);
        assertEquals("Steve", record.name);
        assertEquals("", record.subs.get("tps"), "null option 经 \"\" 哨兵持久化（Gson 默认丢 null 值）");
        assertEquals("overworld", record.subs.get("mobcaps"));
        assertTrue(record.off.isEmpty());
    }

    @Test
    void tombstoneLifecycle()
    {
        UUID steve = UUID.randomUUID();
        store.removeExplicit(steve, "Steve", "tps");
        assertTrue(store.record(steve).off.contains("tps"), "显式退订写墓碑");
        assertFalse(store.record(steve).subs.containsKey("tps"));

        store.markExplicit(steve, "Steve", "tps", null);
        assertFalse(store.record(steve).off.contains("tps"), "再订阅幂等清墓碑（防 {subs,off} 矛盾态）");

        LogSubscriptionStore reloaded = new LogSubscriptionStore(dataFolder);
        reloaded.load();
        assertFalse(reloaded.record(steve).off.contains("tps"), "墓碑清除随落盘持久");
    }

    @Test
    void clearAllTombstonesEveryRegisteredLogger()
    {
        UUID steve = UUID.randomUUID();
        store.markExplicit(steve, "Steve", "tps", null);
        store.clearAll(steve, "Steve", LoggerRegistry.getLoggerNames());
        LogSubscriptionStore.PlayerRecord record = store.record(steve);
        assertTrue(record.subs.isEmpty());
        assertEquals(Set.of("tps", "mobcaps"), record.off, "全退订 = 全注册 logger 写墓碑");

        LogSubscriptionStore reloaded = new LogSubscriptionStore(dataFolder);
        reloaded.load();
        assertEquals(Set.of("tps", "mobcaps"), reloaded.record(steve).off);
    }

    @Test
    void corruptJsonStartsEmpty()
    {
        try
        {
            Files.writeString(dataFolder.resolve("carpet-logs.json"), "{ not valid json !!");
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
        LogSubscriptionStore reloaded = new LogSubscriptionStore(dataFolder);
        reloaded.load();
        assertNull(reloaded.record(UUID.randomUUID()), "坏 JSON 按空册起步");
    }

    @Test
    void virginStoreSaveDoesNotCreateFile()
    {
        store.save();
        assertFalse(Files.exists(dataFolder.resolve("carpet-logs.json")),
                "空册且文件不存在时兜底写跳过（文件从不主动创建，carpet.conf 先例）");
    }

    @Test
    void updateNamePersistsRenameSnapshot()
    {
        UUID steve = UUID.randomUUID();
        store.markExplicit(steve, "OldName", "tps", null);
        store.updateName(steve, "NewName");
        LogSubscriptionStore reloaded = new LogSubscriptionStore(dataFolder);
        reloaded.load();
        assertEquals("NewName", reloaded.record(steve).name, "名字快照随 join 更新并落盘");
    }

    // ───── join 分支矩阵（LoggerListener.onPlayerJoin 静态入口）─────

    @Test
    void freshPlayerWithoutRecordGetsDefaultsNotPersisted()
    {
        CarpetSettings.defaultLoggers = "tps";
        UUID steve = UUID.randomUUID();
        LoggerListener.onPlayerJoin("Steve", steve, store);
        assertTrue(LoggerRegistry.getPlayerSubscriptions("Steve").containsKey("tps"));
        assertNull(store.record(steve), "默认订阅永不落盘（每 join 按当前规则值重评估）");
    }

    @Test
    void explicitRecordRestoresUnderCurrentName()
    {
        UUID steve = UUID.randomUUID();
        store.markExplicit(steve, "OldName", "mobcaps", "overworld");
        // 改名重登：当前名不在内存册 → store 经 UUID 载入、以当前名入册
        LoggerListener.onPlayerJoin("NewName", steve, store);
        assertEquals("overworld", LoggerRegistry.getPlayerSubscriptions("NewName").get("mobcaps"));
        assertEquals("NewName", store.record(steve).name);
        assertNull(LoggerRegistry.getPlayerSubscriptions("OldName"), "旧名孤儿条目不匹配（名键语义）");
    }

    @Test
    void tombstoneBlocksDefaultResurrection()
    {
        UUID steve = UUID.randomUUID();
        store.removeExplicit(steve, "Steve", "tps");
        CarpetSettings.defaultLoggers = "tps";
        LoggerListener.onPlayerJoin("Steve", steve, store);
        assertNull(LoggerRegistry.getPlayerSubscriptions("Steve"), "显式退订不被默认施用复活");
    }

    @Test
    void existingExplicitSubscriptionWinsOverDefaults()
    {
        UUID steve = UUID.randomUUID();
        store.markExplicit(steve, "Steve", "mobcaps", "end");
        CarpetSettings.defaultLoggers = "mobcaps";
        LoggerListener.onPlayerJoin("Steve", steve, store);
        assertEquals("end", LoggerRegistry.getPlayerSubscriptions("Steve").get("mobcaps"),
                "显式 option 优先（上游 offline 桶优先 else-if 同位语义）");
    }

    @Test
    void sameSessionRelogKeepsDefaultOriginOffDisk()
    {
        CarpetSettings.defaultLoggers = "tps";
        UUID steve = UUID.randomUUID();
        LoggerListener.onPlayerJoin("Steve", steve, store);
        LoggerRegistry.playerDisconnected("Steve");
        LoggerListener.onPlayerJoin("Steve", steve, store); // 在册路径 = 桶迁移
        assertTrue(LoggerRegistry.getPlayerSubscriptions("Steve").containsKey("tps"));
        assertNull(store.record(steve), "会话内重登默认订阅仍不落盘");
    }

    @Test
    void unknownDefaultLoggerNameSkippedSilently()
    {
        CarpetSettings.defaultLoggers = "none";
        UUID steve = UUID.randomUUID();
        LoggerListener.onPlayerJoin("Steve", steve, store);
        assertNull(LoggerRegistry.getPlayerSubscriptions("Steve"),
                "未知名（含 'none'）经 getLogger==null 自然跳过（上游同判）");
    }

    // ───── defaultLoggers CSV 解析（上游 Logger.java:216-224 逐字语义）─────

    @Test
    void defaultsCsvParsingSupportsLoggerOptionSyntax()
    {
        List<LoggerListener.DefaultEntry> parsed = LoggerListener.parseDefaults("tps,mobcaps overworld");
        Map<String, String> byLogger = new java.util.HashMap<>();
        for (LoggerListener.DefaultEntry entry : parsed)
        {
            byLogger.put(entry.logger(), entry.option());
        }
        assertNull(byLogger.get("tps"), "裸 logger 条目 option = null（施用时取 logger 默认值）");
        assertEquals("overworld", byLogger.get("mobcaps"), "空格 = option 分隔符（split(\" \", 2)）");
        assertEquals(2, parsed.size(), "逗号切分 + HashSet 去重");
    }

    // ───── mobcaps 行格式与热力色（上游 SpawnReporter:99-152 / Messenger:103-123 阈值逐字）─────

    @Test
    void heatmapColorThresholdsMatchUpstream()
    {
        assertEquals("§7", SpawnReport.heatmapColor(-1, 10), "cur<0 = g 灰");
        assertEquals("§2", SpawnReport.heatmapColor(0, 10), ">=0 = e 深绿");
        assertEquals("§2", SpawnReport.heatmapColor(5, 10), "严格 > 0.5r 才升黄（边界值不升）");
        assertEquals("§e", SpawnReport.heatmapColor(6, 10), ">0.5r = y 黄");
        assertEquals("§c", SpawnReport.heatmapColor(9, 10), ">0.8r = r 红");
        assertEquals("§d", SpawnReport.heatmapColor(11, 10), ">r = m 品红");
    }

    @Test
    void mobcapsLineFormatAndFormula()
    {
        assertEquals("§7   --UNAVAILABLE--", SpawnReport.formatMobcaps(null, -1), "不可用文案逐字");
        assertEquals("§7   --UNAVAILABLE--", SpawnReport.formatMobcaps(new Object2IntOpenHashMap<>(), -1));

        Object2IntOpenHashMap<MobCategory> counts = new Object2IntOpenHashMap<>();
        counts.put(MobCategory.MONSTER, 35);
        counts.put(MobCategory.CREATURE, 4);
        int chunkcount = 289;
        String line = SpawnReport.formatMobcaps(counts, chunkcount);

        // 期望上限以与实现同源的公式常量内联计算（公式本身为上游 :119 逐字，由评审锁；本测锁行装配/色码/分隔）
        int monsterMax = (int) (chunkcount * ((double) MobCategory.MONSTER.getMaxInstancesPerChunk() / 289));
        int creatureMax = (int) (chunkcount * ((double) MobCategory.CREATURE.getMaxInstancesPerChunk() / 289));
        // MONSTER 35：35 > 0.8*70=56? 否；> 0.5*70=35? 否（严格 >）→ §2
        assertTrue(line.contains("§2 35§7 /§4 " + monsterMax + "§7 ,"), "MONSTER 段（热力色 §2 + 类别色 §4）");
        // CREATURE 4：≤0.5*10 → §2
        assertTrue(line.contains("§2 4§7 /§2 " + creatureMax + "§7 ,"), "CREATURE 段");
        assertFalse(line.endsWith("§7 ,"), "末分隔符去除（上游 :142）");
        // 未计入类别 cur = -1 → "-"（上游 :132）
        assertTrue(line.contains("§7 -§7 /"), "无计数类别显 '-'");
    }
}
