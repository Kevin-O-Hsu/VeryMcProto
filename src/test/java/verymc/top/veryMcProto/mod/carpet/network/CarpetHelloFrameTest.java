package verymc.top.veryMcProto.mod.carpet.network;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import verymc.top.veryMcProto.mod.carpet.CarpetReference;
import verymc.top.veryMcProto.mod.carpet.CarpetSettings;
import verymc.top.veryMcProto.mod.carpet.settings.ParsedRule;
import verymc.top.veryMcProto.mod.carpet.settings.Rule;
import verymc.top.veryMcProto.mod.carpet.settings.SettingsManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * carpet:hello 帧构造形状锁（Phase 3 wire 判据）——Rules 帧 / clientCommand 回执 / HI 版本串
 * 的纯函数单测：键名与字段形状对齐上游 {@code carpet/network/ServerNetworkHandler.java}
 * （DataBuilder.withRule :219-239 / handleClientCommand :97-113 / onPlayerJoin :42-44），
 * 防手写帧漂移（客户端对形状错误静默跳过——错一处即规则浏览器空，无任何报错可查）。
 */
class CarpetHelloFrameTest
{
    @TempDir
    Path dataFolder;

    private SettingsManager manager;

    @BeforeEach
    void setUp()
    {
        manager = new SettingsManager(CarpetReference.CARPET_VERSION, CarpetReference.MOD_ID, "Carpet Mod", dataFolder);
        manager.parseSettingsClass(CarpetSettings.class);
        for (ParsedRule<?> rule : manager.getRules())
        {
            rule.resetToDefault(null);
        }
    }

    /** 供撞名用例的第二个同名规则载体（不经 parseSettingsClass——按名去重的注册面会塌缩）。 */
    @SuppressWarnings("unused")
    static class DuplicateRules
    {
        @Rule(desc = "d", category = "feature")
        public static volatile boolean dup = false;
        @Rule(name = "dup", desc = "d", category = "feature")
        public static volatile boolean dupAgain = false;
    }

    @Test
    void rulesFrameShapeMatchesUpstream()
    {
        CompoundTag frame = CarpetHelloHandler.buildRulesFrame(manager.getRules(), manager.identifier());

        assertTrue(frame.contains("Rules"), "frame must carry the Rules key");
        CompoundTag rules = frame.getCompound("Rules").orElseThrow();
        assertEquals(manager.getRules().size(), rules.size(), "full-set scope: every registered rule incl. defaults");
        for (ParsedRule<?> rule : manager.getRules())
        {
            CompoundTag entry = rules.getCompound(rule.name).orElseThrow();
            assertTrue(entry.contains("Value") && entry.contains("Manager") && entry.contains("Rule"),
                    "rule entry shape {Value, Manager, Rule} for " + rule.name);
            assertEquals(rule.name, entry.getString("Rule").orElseThrow());
            assertEquals("carpet", entry.getString("Manager").orElseThrow(),
                    "Manager must be the client-side lookup branch value \"carpet\"");
            assertEquals(String.valueOf(rule.value()), entry.getString("Value").orElseThrow());
        }
    }

    @Test
    void booleanRulesSerializeAsTrueFalseStrings()
    {
        CompoundTag rules = CarpetHelloHandler.buildRulesFrame(manager.getRules(), manager.identifier()).getCompound("Rules").orElseThrow();
        assertEquals("false", rules.getCompound("explosionNoBlockDamage").orElseThrow().getString("Value").orElseThrow());
        assertEquals("true", rules.getCompound("allowSpawningOfflinePlayers").orElseThrow().getString("Value").orElseThrow());
    }

    @Test
    void collidingRuleKeysGetUpstreamSuffixLoop()
    {
        // 上游 :229-232 撞名 while 循环：key 已存在则追加 "2"（多管理器同名规则场景；
        // B 侧单管理器下不可达，本用例经 ParsedRule.of 直构两同名规则锁死该行为）
        ParsedRule<?> first = ParsedRule.of(field("dup"), manager);
        ParsedRule<?> second = ParsedRule.of(field("dupAgain"), manager);
        CompoundTag rules = CarpetHelloHandler.buildRulesFrame(List.of(first, second), manager.identifier()).getCompound("Rules").orElseThrow();
        assertEquals(2, rules.size());
        assertTrue(rules.contains("dup"));
        assertTrue(rules.contains("dup2"), "second same-named rule must land under key + \"2\"");
        assertEquals("dup", rules.getCompound("dup2").orElseThrow().getString("Rule").orElseThrow(),
                "inner Rule key keeps the real name for the client registry lookup");
    }

    @Test
    void clientCommandReplyOmitsEmptyOptionalKeys()
    {
        CompoundTag bare = CarpetHelloHandler.buildClientCommandReply("say hi", null, 0, List.of());
        assertEquals("say hi", bare.getString("id").orElseThrow());
        assertEquals(0, bare.getInt("return").orElseThrow());
        assertFalse(bare.contains("error"), "no error key when no failure");
        assertFalse(bare.contains("output"), "no output key when no lines (upstream :109-112)");

        CompoundTag full = CarpetHelloHandler.buildClientCommandReply(
                "id2", "Unknown command", 0, List.of("line1", "line2"));
        assertEquals("Unknown command", full.getString("error").orElseThrow());
        Tag output = full.get("output");
        assertTrue(output instanceof ListTag, "output must be a string list tag");
        ListTag lines = (ListTag) output;
        assertEquals(2, lines.size());
        assertEquals("line1", lines.getString(0).orElseThrow());
        assertEquals("line2", lines.getString(1).orElseThrow());
    }

    /**
     * HI 版本串守卫：必须逐字等于上游 mod_version（OriginImpl/fabric-carpet-1.21.11/gradle.properties:16）。
     * 误换成插件版本号（mcVersion-bN 之类）必触发客户端 equals 严格比较 warn（CarpetReference javadoc 纪律，
     * 同 servux MOD_STRING 门禁）。
     */
    @Test
    void carpetVersionLiteralGuard()
    {
        assertEquals("1.4.194", CarpetReference.CARPET_VERSION);
        assertEquals("carpet", CarpetReference.MOD_ID);
        assertEquals("69", CarpetReference.KEY_HI);
        assertEquals("420", CarpetReference.KEY_HELLO);
        assertEquals("carpet:hello", CarpetReference.CARPET_CHANNEL.toString());
    }

    private static Field field(String name)
    {
        try
        {
            Field f = DuplicateRules.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
        catch (NoSuchFieldException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
