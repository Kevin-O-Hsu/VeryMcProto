package verymc.top.veryMcProto.mod.servux.dataproviders;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import verymc.top.veryMcProto.framework.dataproviders.DataProviderBase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2S 注册版本门禁判定单测（纯 JVM，CompoundTag 构造不需 Bootstrap）。
 *
 * <p>覆盖上游 register() 门禁语句（HudDataProvider:411，五 Provider 同构）：
 * {@code tags == null || tags.getIntOrDefault("version", -1) < protocolVersion}。
 * 比较符必须是<b>严格小于</b>——26.1 合法客户端常量与我方相等（3/2/2/3/2），高版本客户端
 * （snapshot）&gt; 我方须放行（客户端随后按自身 != 校验自行退网），误写 == / &gt;= 会拒合法客户端。
 */
class DataProviderVersionGateTest
{
    private static CompoundTag tagsWithVersion(int version)
    {
        CompoundTag tags = new CompoundTag();
        tags.putInt("version", version);
        return tags;
    }

    @Test
    void nullTags_isTooLow()
    {
        // tags 缺失视同版本过低（上游同语义：连拒绝提示都发）
        assertTrue(DataProviderBase.isVersionTooLow(null, 3));
    }

    @Test
    void missingVersionKey_defaultsToMinusOne_isTooLow()
    {
        // version 字段缺失 → 默认 -1 → 低于任何协议版本 → 拒绝
        assertTrue(DataProviderBase.isVersionTooLow(new CompoundTag(), 2));
    }

    @Test
    void versionBelowRequired_isTooLow()
    {
        // 1.21.11 客户端全线低于我方（hud 2<3 / entities 1<2 等）→ 拒绝
        assertTrue(DataProviderBase.isVersionTooLow(tagsWithVersion(2), 3));
        assertTrue(DataProviderBase.isVersionTooLow(tagsWithVersion(1), 2));
    }

    @Test
    void versionEqualRequired_passes()
    {
        // 26.1 合法客户端常量与我方相等（3/2/2/3/2）→ 必须放行（严格 <，误写 != / >= 即误拒）
        assertFalse(DataProviderBase.isVersionTooLow(tagsWithVersion(3), 3));
        assertFalse(DataProviderBase.isVersionTooLow(tagsWithVersion(2), 2));
    }

    @Test
    void versionAboveRequired_passes()
    {
        // snapshot 客户端 version 高于我方 → 放行（与上游一致；客户端随后自行按 != 退网）
        assertFalse(DataProviderBase.isVersionTooLow(tagsWithVersion(4), 3));
    }
}
