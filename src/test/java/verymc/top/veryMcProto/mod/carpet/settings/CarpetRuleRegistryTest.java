package verymc.top.veryMcProto.mod.carpet.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则注册表面锁单测（判据③：/carpet list 输出与注册表一致——本测试锁死注册表本身的
 * 名称/默认值/选项域/严格性，list 输出直接由 getRulesSorted 派生）。
 * 默认值与选项域 = 上游 carpet/CarpetSettings.java 逐字（对照行号见 CarpetSettings 注释）。
 */
class CarpetRuleRegistryTest
{
    @TempDir
    Path dataFolder;

    private SettingsManager manager;

    @BeforeEach
    void setUp()
    {
        manager = new SettingsManager("26.2", "carpet", "Carpet Mod", dataFolder);
        manager.parseSettingsClass(CarpetSettings.class);
        resetAllRules();
    }

    @AfterEach
    void tearDown()
    {
        resetAllRules();
    }

    private void resetAllRules()
    {
        for (ParsedRule<?> rule : manager.getRules())
        {
            rule.resetToDefault(null);
        }
    }

    @Test
    void exactlyEighteenRulesRegistered()
    {
        Set<String> names = manager.getRules().stream().map(r -> r.name).collect(Collectors.toSet());
        assertEquals(Set.of(
                "carpetCommandPermissionLevel",
                "language",
                "superSecretSetting",
                "explosionNoBlockDamage",
                "customMOTD",
                "cleanLogs",
                "commandTick",
                "perfPermissionLevel",
                // 假人家族 3 条（scope §5.1，上游 :595-602）
                "commandPlayer",
                "allowSpawningOfflinePlayers",
                "allowListingFakePlayers",
                // 日志 2 条（scope §5.1，上游 :453-463）
                "commandLog",
                "defaultLoggers",
                // 命令族 5 条（scope §5.1，上游 :411-412/:465-490）
                "commandSpawn",
                "commandDistance",
                "commandInfo",
                "commandPerimeterInfo",
                "commandDraw"), names,
                "8 foundation + 3 fake-player + 2 log + 5 command-family rules (18 total)");
    }

    @Test
    void upstreamDefaultsAndTypes()
    {
        assertEquals("en_us", manager.getRule("language").defaultValue);
        assertEquals(String.class, manager.getRule("language").type);
        assertEquals("ops", manager.getRule("carpetCommandPermissionLevel").defaultValue);
        assertEquals(false, manager.getRule("superSecretSetting").defaultValue);
        assertEquals(Boolean.class, manager.getRule("superSecretSetting").type);
        assertEquals(false, manager.getRule("explosionNoBlockDamage").defaultValue);
        assertEquals("_", manager.getRule("customMOTD").defaultValue);
        assertEquals("ops", manager.getRule("commandTick").defaultValue);
        assertEquals(4, manager.getRule("perfPermissionLevel").defaultValue);
        assertEquals(Integer.class, manager.getRule("perfPermissionLevel").type);
        assertEquals(false, manager.getRule("cleanLogs").defaultValue);
        // 假人家族（上游 :595-602 逐字）
        assertEquals("ops", manager.getRule("commandPlayer").defaultValue);
        assertEquals(String.class, manager.getRule("commandPlayer").type);
        assertEquals(true, manager.getRule("allowSpawningOfflinePlayers").defaultValue);
        assertEquals(Boolean.class, manager.getRule("allowSpawningOfflinePlayers").type);
        assertEquals(false, manager.getRule("allowListingFakePlayers").defaultValue);
        assertEquals(Boolean.class, manager.getRule("allowListingFakePlayers").type);
        // 日志家族（上游 :453-463 逐字）
        assertEquals("true", manager.getRule("commandLog").defaultValue);
        assertEquals(String.class, manager.getRule("commandLog").type);
        assertEquals("none", manager.getRule("defaultLoggers").defaultValue);
        assertEquals(String.class, manager.getRule("defaultLoggers").type);
        // 命令族（上游 :411-412/:465-490 逐字）
        assertEquals("ops", manager.getRule("commandSpawn").defaultValue);
        assertEquals(String.class, manager.getRule("commandSpawn").type);
        assertEquals("true", manager.getRule("commandDistance").defaultValue);
        assertEquals(String.class, manager.getRule("commandDistance").type);
        assertEquals("true", manager.getRule("commandInfo").defaultValue);
        assertEquals(String.class, manager.getRule("commandInfo").type);
        assertEquals("true", manager.getRule("commandPerimeterInfo").defaultValue);
        assertEquals(String.class, manager.getRule("commandPerimeterInfo").type);
        assertEquals("ops", manager.getRule("commandDraw").defaultValue);
        assertEquals(String.class, manager.getRule("commandDraw").type);
    }

    @Test
    void upstreamOptionDomains()
    {
        assertEquals(List.of("en_us", "fr_fr", "es_ar", "pt_br", "zh_cn", "zh_tw"), manager.getRule("language").options);
        assertTrue(manager.getRule("language").isStrict);

        assertEquals(List.of("ops", "2", "4"), manager.getRule("carpetCommandPermissionLevel").options);

        // boolean 规则 options 自动推断 [true, false]（上游 ParsedRule.java:211-213）
        assertEquals(List.of("true", "false"), manager.getRule("explosionNoBlockDamage").options);

        // commandTick：String + COMMAND 类目无显式 options → 推断 CommandLevel.OPTIONS（上游 :214-217）
        assertEquals(Validator.CommandLevel.OPTIONS, manager.getRule("commandTick").options);
        assertTrue(manager.getRule("commandTick").options.contains("false"), "locked 时 disableBooleanCommands 需要 'false' 建议");

        // commandPlayer：String + COMMAND 类目同款推断
        assertEquals(Validator.CommandLevel.OPTIONS, manager.getRule("commandPlayer").options);

        assertEquals(List.of("2", "4"), manager.getRule("perfPermissionLevel").options);

        assertEquals(List.of("_"), manager.getRule("customMOTD").options);
        assertFalse(manager.getRule("customMOTD").isStrict, "customMOTD is strict=false (any value)");

        // boolean 规则 options 自动推断 [true, false]
        assertEquals(List.of("true", "false"), manager.getRule("allowSpawningOfflinePlayers").options);
        assertEquals(List.of("true", "false"), manager.getRule("allowListingFakePlayers").options);

        // commandLog = String + COMMAND 类目 → 推断 CommandLevel.OPTIONS（commandTick/commandPlayer 同款）
        assertEquals(Validator.CommandLevel.OPTIONS, manager.getRule("commandLog").options);
        // defaultLoggers：显式 options（上游 :460 逐字含复合值 "mobcaps,tps"）+ 非 strict
        assertEquals(List.of("none", "tps", "mobcaps,tps"), manager.getRule("defaultLoggers").options);
        assertFalse(manager.getRule("defaultLoggers").isStrict, "defaultLoggers is strict=false (csv values)");

        // 五条 command 规则均为 String + COMMAND 类目无显式 options → 推断 CommandLevel.OPTIONS
        //（上游 :412/:470/:480/:487/:490 均无 options，commandTick/commandPlayer/commandLog 同款推断）
        assertEquals(Validator.CommandLevel.OPTIONS, manager.getRule("commandSpawn").options);
        assertEquals(Validator.CommandLevel.OPTIONS, manager.getRule("commandDistance").options);
        assertEquals(Validator.CommandLevel.OPTIONS, manager.getRule("commandInfo").options);
        assertEquals(Validator.CommandLevel.OPTIONS, manager.getRule("commandPerimeterInfo").options);
        assertEquals(Validator.CommandLevel.OPTIONS, manager.getRule("commandDraw").options);
    }

    @Test
    void sortedViewMatchesRegistryAndIsNameOrdered()
    {
        List<String> sortedNames = manager.getRulesSorted().stream().map(r -> r.name).toList();
        List<String> expected = sortedNames.stream().sorted().toList();
        assertEquals(expected, sortedNames, "getRulesSorted = registry view ordered by name (list/tab face)");
        assertEquals(manager.getRules().size(), sortedNames.size());
    }
}
