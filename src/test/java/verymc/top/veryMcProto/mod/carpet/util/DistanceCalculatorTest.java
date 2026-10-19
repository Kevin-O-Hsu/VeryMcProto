package verymc.top.veryMcProto.mod.carpet.util;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * /distance 三距离公式纯函数锚（Phase 5 验证方式"实机 + 纯算法单测"——
 * {@link DistanceCalculator#findDistanceBetweenTwoPoints} 返回组件列表、零 source 依赖，
 * 纯 JVM 直调；公式体上游逐字，断言锁 %.2f/%.2f/%.1f 输出面）。
 *
 * <p>setStart/setEnd 状态机（CommandSourceStack 名键控 + Messenger 回执）与命令树为实机覆盖面
 * （NMS source 纯 JVM 不可构造，仓库测试范式为纯函数单测——同判先例 EntityPlayerActionPackTest）。
 */
class DistanceCalculatorTest
{
    @Test
    void sphericalCylindricalManhattanOnPythagoreanTriple()
    {
        List<Component> res = DistanceCalculator.findDistanceBetweenTwoPoints(
                new Vec3(0, 64, 0), new Vec3(3, 64, 4));
        assertEquals(4, res.size());
        assertEquals("Distance between [ 0.0, 64.0, 0.0] and [ 3.0, 64.0, 4.0]:", res.get(0).getString());
        assertEquals(" - Spherical: 5.00", res.get(1).getString());
        assertEquals(" - Cylindrical: 5.00", res.get(2).getString());
        assertEquals(" - Manhattan: 7.0", res.get(3).getString());
    }

    @Test
    void verticalOnlyCollapsesCylindricalToZero()
    {
        List<Component> res = DistanceCalculator.findDistanceBetweenTwoPoints(
                new Vec3(0, 0, 0), new Vec3(0, 10, 0));
        assertEquals(" - Spherical: 10.00", res.get(1).getString());
        assertEquals(" - Cylindrical: 0.00", res.get(2).getString());
        assertEquals(" - Manhattan: 10.0", res.get(3).getString());
    }

    @Test
    void floatDomainSubtractionFormats()
    {
        // dx = Mth.abs((float)0.1-(float)0.3)——float 域减法（上游逐字形态，勿改 Math.abs 双精度）
        List<Component> res = DistanceCalculator.findDistanceBetweenTwoPoints(
                new Vec3(0.1, 0, 0), new Vec3(0.3, 0, 0));
        assertEquals(" - Spherical: 0.20", res.get(1).getString());
        assertEquals(" - Cylindrical: 0.20", res.get(2).getString());
        assertEquals(" - Manhattan: 0.2", res.get(3).getString());
    }

    @Test
    void orderIndependentSymmetry()
    {
        List<Component> a = DistanceCalculator.findDistanceBetweenTwoPoints(
                new Vec3(100.5, -3, 7), new Vec3(-2.5, 42, -70));
        List<Component> b = DistanceCalculator.findDistanceBetweenTwoPoints(
                new Vec3(-2.5, 42, -70), new Vec3(100.5, -3, 7));
        assertEquals(a.get(1).getString(), b.get(1).getString());
        assertEquals(a.get(2).getString(), b.get(2).getString());
        assertEquals(a.get(3).getString(), b.get(3).getString());
    }
}
