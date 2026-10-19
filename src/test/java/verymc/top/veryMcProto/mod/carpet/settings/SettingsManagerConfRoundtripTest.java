package verymc.top.veryMcProto.mod.carpet.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import verymc.top.veryMcProto.mod.carpet.CarpetSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * carpet.conf 持久化往返单测（判据②：setDefault → 落盘 → 重启读回一致）。
 * 形态先例 = JeiConfigMigrationTest（@TempDir + 重新构造读回）；静态规则字段跨实例共享，
 * 每例前后全量复位（loadConfigurationFromConf 自带复位，AfterEach 兜底防污染其他测试类）。
 */
class SettingsManagerConfRoundtripTest
{
    @TempDir
    Path dataFolder;

    private SettingsManager manager;

    @BeforeEach
    void setUp()
    {
        manager = newManager();
        resetAllRules();
    }

    @AfterEach
    void tearDown()
    {
        resetAllRules();
    }

    private SettingsManager newManager()
    {
        SettingsManager sm = new SettingsManager("1.4.194", "carpet", "Carpet Mod", dataFolder);
        sm.parseSettingsClass(CarpetSettings.class);
        return sm;
    }

    private void resetAllRules()
    {
        for (ParsedRule<?> rule : manager.getRules())
        {
            rule.resetToDefault(null);
        }
    }

    @Test
    void setDefaultPersistsToFileWithUpstreamLineFormat()
    {
        assertTrue(manager.setDefault(null, manager.getRule("explosionNoBlockDamage"), "true"));

        Path conf = dataFolder.resolve("carpet.conf");
        assertTrue(Files.exists(conf), "carpet.conf should be written by setDefault");
        List<String> lines;
        try
        {
            lines = Files.readAllLines(conf);
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }
        assertTrue(lines.contains("explosionNoBlockDamage true"), "upstream key-value line format: " + lines);
    }

    @Test
    void setDefaultAppliesImmediatelyAndSurvivesRestartRoundtrip()
    {
        // 判据②：live set（即时生效）+ 落盘
        assertTrue(manager.setDefault(null, manager.getRule("explosionNoBlockDamage"), "true"));
        assertTrue(manager.setDefault(null, manager.getRule("customMOTD"), "round trip value"));
        assertEquals(true, CarpetSettings.explosionNoBlockDamage, "setDefault applies immediately (upstream live set)");
        assertEquals("round trip value", CarpetSettings.customMOTD);

        // 重启读回：全新 manager 实例（复刻插件重启——conf 仍指同一目录）
        SettingsManager reloaded = newManager();
        reloaded.loadConfigurationFromConf();
        assertEquals(true, CarpetSettings.explosionNoBlockDamage, "roundtrip: value restored from carpet.conf");
        assertEquals("round trip value", CarpetSettings.customMOTD, "roundtrip: value-with-spaces preserved via split(2)");
    }

    @Test
    void removeDefaultDeletesOverrideAndResetsToDefault()
    {
        assertTrue(manager.setDefault(null, manager.getRule("explosionNoBlockDamage"), "true"));
        assertTrue(manager.removeDefault(null, manager.getRule("explosionNoBlockDamage")));

        assertEquals(false, CarpetSettings.explosionNoBlockDamage, "removeDefault resets runtime value (upstream resetToDefault)");
        SettingsManager reloaded = newManager();
        reloaded.loadConfigurationFromConf();
        assertEquals(false, CarpetSettings.explosionNoBlockDamage, "override gone from conf after removeDefault");
    }

    @Test
    void confNeverCreatedProactivelyOnLoad()
    {
        // 上游服务端语义：conf 缺失 = 空结果，从不主动创建（首次写盘仅由 setDefault/removeDefault 触发）
        manager.loadConfigurationFromConf();
        assertFalse(Files.exists(dataFolder.resolve("carpet.conf")));
    }

    @Test
    void lockedLineLocksManagerAndDisablesCommandRules()
    {
        writeConf("locked\nexplosionNoBlockDamage true\n");

        SettingsManager reloaded = newManager();
        reloaded.loadConfigurationFromConf();

        assertTrue(reloaded.locked(), "'locked' line locks the manager (upstream semantics)");
        assertEquals("false", CarpetSettings.commandTick, "COMMAND-category rule with 'false' suggestion disabled when locked (disableBooleanCommands)");
        assertEquals(true, CarpetSettings.explosionNoBlockDamage, "other entries still applied before locked takes effect");
        assertFalse(reloaded.setDefault(null, reloaded.getRule("customMOTD"), "x"), "setDefault rejected while locked");
    }

    @Test
    void unknownRuleKeyIsSkippedWithoutFailure()
    {
        writeConf("notARealRule 1\nexplosionNoBlockDamage true\n");

        SettingsManager reloaded = newManager();
        reloaded.loadConfigurationFromConf();

        assertEquals(true, CarpetSettings.explosionNoBlockDamage, "valid entries still load");
        assertEquals(false, CarpetSettings.cleanLogs, "unknown key ignored, other values untouched");
    }

    @Test
    void commentLinesAndLeadingHashValuesFollowUpstreamQuirk()
    {
        // 注释行跳过（上游 :448-451 逐字怪癖）
        writeConf("# a comment line\nexplosionNoBlockDamage true\n");

        SettingsManager reloaded = newManager();
        reloaded.loadConfigurationFromConf();
        assertEquals(true, CarpetSettings.explosionNoBlockDamage);

        // 上游继承缺陷：值首字符 '#' 的条目重读时被吞（result.isEmpty() 分支外的 || fields[1].startsWith("#")）——
        // 断言锁定该行为防止未来"修复"漂移（偏差清单已登记）
        resetAllRules();
        writeConf("customMOTD #tag\n");
        SettingsManager quirk = newManager();
        quirk.loadConfigurationFromConf();
        assertEquals("_", CarpetSettings.customMOTD, "upstream-inherited quirk: leading-# value swallowed on reread");
    }

    @Test
    void strictValidationRejectsValueOutsideOptions()
    {
        assertFalse(manager.setDefault(null, manager.getRule("language"), "xx_XX"), "strict option list rejects unknown language");
        assertEquals("en_us", CarpetSettings.language, "rejected value does not stick");
        assertFalse(manager.setDefault(null, manager.getRule("perfPermissionLevel"), "3"), "perfPermissionLevel options are {2,4}");
        assertEquals(4, CarpetSettings.perfPermissionLevel);
    }

    private void writeConf(String content)
    {
        try
        {
            Files.write(dataFolder.resolve("carpet.conf"), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }
    }
}
