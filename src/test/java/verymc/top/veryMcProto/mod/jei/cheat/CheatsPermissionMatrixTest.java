package verymc.top.veryMcProto.mod.jei.cheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import verymc.top.veryMcProto.mod.jei.network.payload.PacketCheatPermission;

/**
 * cheat 权限矩阵单测（纯函数）。语义 = 上游 {@code ServerCommandUtil.hasPermissionForCheatMode}
 * 的 creative → op → give 短路序；{@code allowedCheatingMethods} 列表序 = 上游
 * {@code PacketCheatPermission.getAllowedCheatingMethods} 的 op → creative → give。
 */
class CheatsPermissionMatrixTest
{
    @Test
    void creativeShortCircuitsFirst()
    {
        assertTrue(Cheats.hasCheatPermission(true, true, false, false, false, false));
        // 创造模式 + creative 开关关闭 → 落到 op/give 判定
        assertFalse(Cheats.hasCheatPermission(false, true, false, false, false, false));
    }

    @Test
    void opTakesPrecedenceOverGive()
    {
        // op 开 + 无 op 权限 + 有 give 权限 → 仍拒绝（op 短路：return isOpLevel）
        assertFalse(Cheats.hasCheatPermission(false, false, true, false, true, true));
        // op 开 + 有 op 权限 → 放行（无论 give）
        assertTrue(Cheats.hasCheatPermission(false, false, true, true, false, false));
    }

    @Test
    void giveOnlyPath()
    {
        assertTrue(Cheats.hasCheatPermission(false, false, false, true, true, true));
        assertFalse(Cheats.hasCheatPermission(false, false, false, true, true, false));
    }

    @Test
    void allSwitchesOffDeniesEveryone()
    {
        assertFalse(Cheats.hasCheatPermission(false, true, false, true, false, true));
    }

    @Test
    void allowedCheatingMethodsOrderAndContent()
    {
        assertEquals(List.of(), PacketCheatPermission.getAllowedCheatingMethods(false, false, false));
        assertEquals(List.of("jei.chat.error.no.cheat.permission.op"),
                PacketCheatPermission.getAllowedCheatingMethods(true, false, false));
        // 上游序：op → creative → give
        assertEquals(List.of(
                "jei.chat.error.no.cheat.permission.op",
                "jei.chat.error.no.cheat.permission.creative",
                "jei.chat.error.no.cheat.permission.give"),
                PacketCheatPermission.getAllowedCheatingMethods(true, true, true));
        assertEquals(List.of("jei.chat.error.no.cheat.permission.creative"),
                PacketCheatPermission.getAllowedCheatingMethods(false, true, false));
    }
}
