package verymc.top.veryMcProto.mod.syncmatica.communication;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import verymc.top.veryMcProto.mod.syncmatica.Feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FeatureSet 字符串编解码 round-trip 单测（纯 JVM，不依赖 NMS 运行时）。
 *
 * <p>覆盖 syncmatica 协议的 Feature 协商基础：FeatureSet 用 {@code \n} 分隔的 Feature 名序列化
 * （<b>非位图</b>），握手时双方交换。本测验证 fromString/toString 对称性、未知名容错、版本默认集。
 */
class FeatureSetTest
{
    @Test
    void roundTrip_allFeatures()
    {
        FeatureSet fs = new FeatureSet(Arrays.asList(Feature.values()));
        String encoded = fs.toString();
        FeatureSet decoded = FeatureSet.fromString(encoded);
        for (Feature f : Feature.values())
        {
            assertTrue(fs.hasFeature(f), "原始应含 " + f);
            assertTrue(decoded.hasFeature(f), "round-trip 后应仍含 " + f);
        }
        assertEquals(encoded, decoded.toString(), "round-trip 后 toString 应一致");
    }

    @Test
    void emptySet_serializesToEmpty_andRoundTrips()
    {
        FeatureSet fs = new FeatureSet(Collections.emptyList());
        assertEquals("", fs.toString());
        FeatureSet decoded = FeatureSet.fromString("");
        for (Feature f : Feature.values())
        {
            assertFalse(decoded.hasFeature(f));
        }
    }

    @Test
    void unknownFeatureNames_areIgnored()
    {
        // 已知名正常解析，未知名静默丢弃（不抛、不污染集合）——与 CommunicationManager 解码对端 FeatureSet 的容错语义一致
        FeatureSet fs = FeatureSet.fromString("CORE\nNOT_A_REAL_FEATURE\nMESSAGE");
        assertTrue(fs.hasFeature(Feature.CORE));
        assertTrue(fs.hasFeature(Feature.MESSAGE));
    }

    @Test
    void fromVersionString_threeSegmentTrimsToKnownPrefix()
    {
        // "0.1.0" 匹配 regex（3 段），逐步裁剪最后段 → "0.1" 命中内置默认集
        FeatureSet fs = FeatureSet.fromVersionString("0.1.0");
        assertNotNull(fs);
        assertTrue(fs.hasFeature(Feature.CORE));
    }

    @Test
    void fromVersionString_twoSegment_returnsNull()
    {
        // regex 要求 3-5 段版本号；"0.1" 仅 2 段不进裁剪循环 → null（即便 map 里有 "0.1" 条目）
        assertNull(FeatureSet.fromVersionString("0.1"));
    }

    @Test
    void fromVersionString_unknownVersion_returnsNull()
    {
        assertNull(FeatureSet.fromVersionString("99.99.99"));
    }
}
