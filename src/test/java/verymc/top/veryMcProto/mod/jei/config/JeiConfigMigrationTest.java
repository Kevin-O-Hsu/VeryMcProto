package verymc.top.veryMcProto.mod.jei.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JEI 配置加载与旧文件迁移单测（纯 JVM 文件 IO）。
 * 默认值对齐上游 ServerConfig：enabled=true、op=true、creative=true、give=false。
 */
class JeiConfigMigrationTest
{
    @TempDir
    Path dataFolder;

    @Test
    void freshDefaultsAndPersist()
    {
        JeiConfiguration config = new JeiConfiguration(dataFolder);
        assertTrue(config.isEnabled());
        assertTrue(config.isCheatModeEnabledForOp());
        assertTrue(config.isCheatModeEnabledForCreative());
        assertFalse(config.isCheatModeEnabledForGive());
        assertTrue(Files.exists(dataFolder.resolve("jei.json")));

        // 重载读回持久化值（含 setEnabled 落盘）
        config.setEnabled(false);
        JeiConfiguration reloaded = new JeiConfiguration(dataFolder);
        assertFalse(reloaded.isEnabled());
    }

    @Test
    void legacyEnabledMigrated() throws IOException
    {
        Files.writeString(dataFolder.resolve("jei-recipe-bridge.json"), "{\n  \"enabled\": false\n}");
        assertFalse(Files.exists(dataFolder.resolve("jei.json")));

        JeiConfiguration config = new JeiConfiguration(dataFolder);

        // 旧 enabled=false 迁移——杜绝"服主 disable 过、升级后静默重置"
        assertFalse(config.isEnabled());
        // 其余键按上游默认
        assertTrue(config.isCheatModeEnabledForOp());
        assertTrue(config.isCheatModeEnabledForCreative());
        assertFalse(config.isCheatModeEnabledForGive());
        // 新文件已落盘，旧文件保留原貌（不删不改）
        assertTrue(Files.exists(dataFolder.resolve("jei.json")));
        assertTrue(Files.exists(dataFolder.resolve("jei-recipe-bridge.json")));
    }

    @Test
    void corruptedNewFileFallsBackToDefaults()
    {
        try
        {
            Files.writeString(dataFolder.resolve("jei.json"), "not-a-json");
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        JeiConfiguration config = new JeiConfiguration(dataFolder);
        assertTrue(config.isEnabled());
        assertTrue(config.isCheatModeEnabledForOp());
    }

    @Test
    void persistedCheatKeysRoundTrip() throws IOException
    {
        Files.writeString(dataFolder.resolve("jei.json"),
                "{\"enabled\": true, \"cheatModeEnabledForOp\": false, \"cheatModeEnabledForCreative\": false, \"cheatModeEnabledForGive\": true}");

        JeiConfiguration config = new JeiConfiguration(dataFolder);
        assertFalse(config.isCheatModeEnabledForOp());
        assertFalse(config.isCheatModeEnabledForCreative());
        assertTrue(config.isCheatModeEnabledForGive());
    }
}
