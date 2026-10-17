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
    void exactlyEightFoundationRulesRegistered()
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
                "perfPermissionLevel"), names, "P1 foundation rule set (scope §5.1)");
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

        assertEquals(List.of("2", "4"), manager.getRule("perfPermissionLevel").options);

        assertEquals(List.of("_"), manager.getRule("customMOTD").options);
        assertFalse(manager.getRule("customMOTD").isStrict, "customMOTD is strict=false (any value)");
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
