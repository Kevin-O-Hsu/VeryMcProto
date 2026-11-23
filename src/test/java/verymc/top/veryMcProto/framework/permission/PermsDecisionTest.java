package verymc.top.veryMcProto.framework.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 权限判定公式真值表单测（纯函数 {@link Perms#decide}，不依赖 Bukkit / NMS 对象）。
 *
 * <p>公式 = 上游 Fabric API {@code fabric-permission-api-v1} 的
 * {@code PermissionContextOwner.checkPermission(Identifier, PermissionLevel)} 回退语义：
 * 显式设置优先；无显式设置时玩家等级不低于要求等级才放行。
 */
class PermsDecisionTest
{
    @Test
    void explicitTrueOverridesLowLevel()
    {
        // 显式为真压过低等级：等级 0（不够 4）仍放行
        assertTrue(Perms.decide(true, true, 0, 4));
        assertTrue(Perms.decide(true, true, 2, 3));
    }

    @Test
    void explicitFalseOverridesHighLevel()
    {
        // 显式为假压过高等级：等级 4（够 0）仍拒绝
        assertFalse(Perms.decide(true, false, 4, 0));
        assertFalse(Perms.decide(true, false, 3, 1));
    }

    @Test
    void noExplicitSufficientLevelPasses()
    {
        // 无显式时等级够则过（含 level 0 全员恒过的退化分支：playerLevel >= 0 恒真）
        assertTrue(Perms.decide(false, false, 2, 2));
        assertTrue(Perms.decide(false, false, 4, 3));
        assertTrue(Perms.decide(false, false, 0, 0));
    }

    @Test
    void noExplicitInsufficientLevelDenied()
    {
        // 无显式时等级不够则拒
        assertFalse(Perms.decide(false, false, 1, 2));
        assertFalse(Perms.decide(false, false, 0, 1));
        assertFalse(Perms.decide(false, false, 3, 4));
    }
}
