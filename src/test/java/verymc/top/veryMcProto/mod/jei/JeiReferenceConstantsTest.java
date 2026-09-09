package verymc.top.veryMcProto.mod.jei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * JEI 通道常量对齐断言（单测）。期望值 = 上游 mezz/JustEnoughItems 26.1 分支（commit ccc16e8）
 * 各 payload 类的 {@code CustomPacketPayload.Type} 字面量——防止后续维护中的意外改动
 * （通道 id 错一字 = 客户端整通道静默退网）。
 */
class JeiReferenceConstantsTest
{
    @Test
    void c2sChannelsMirrorUpstream()
    {
        List<String> expected = List.of(
                "jei:request_cheat_permission",
                "jei:give_item_stack",
                "jei:delete_player_item",
                "jei:set_hotbar_item_stack",
                "jei:recipe_transfer_with_result",
                "jei:recipe_transfer_counted_with_result",
                "jei:recipe_transfer",
                "jei:recipe_transfer_counted");
        List<String> actual = java.util.Arrays.stream(JeiReference.C2S_CHANNELS)
                .map(Object::toString)
                .toList();
        assertEquals(expected, actual);
    }

    @Test
    void s2cChannelsMirrorUpstream()
    {
        assertEquals("jei:cheat_permission", JeiReference.CHANNEL_CHEAT_PERMISSION.toString());
        assertEquals("jei:recipe_transfer_result", JeiReference.CHANNEL_RECIPE_TRANSFER_RESULT.toString());
    }

    @Test
    void recipeSyncChannelsMirrorLoaderWire()
    {
        assertEquals("fabric:recipe_sync", JeiReference.CHANNEL_FABRIC_RECIPE_SYNC.toString());
        assertEquals("neoforge:recipe_content", JeiReference.CHANNEL_NEOFORGE_RECIPE_CONTENT.toString());
    }

    @Test
    void configFiles()
    {
        assertEquals("jei.json", JeiReference.CONFIG_FILE_NAME);
        assertEquals("jei-recipe-bridge.json", JeiReference.LEGACY_CONFIG_FILE_NAME);
    }
}
