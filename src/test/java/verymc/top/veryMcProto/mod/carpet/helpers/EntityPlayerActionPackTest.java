package verymc.top.veryMcProto.mod.carpet.helpers;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import verymc.top.veryMcProto.mod.carpet.helpers.EntityPlayerActionPack.Action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Action 工厂语义锁（/player 动作子命令 once|continuous|interval &lt;ticks&gt; 的核心参数语义，
 * 上游 EntityPlayerActionPack.java:555-573）。Action.tick/retry 的执行路径依赖 NMS ServerPlayer
 * （构造/瞄准/挖掘全 NMS 面），不入纯 JVM 单测面——实机判据承载（docs/carpet/architecture.md §12 判据①）；
 * 此处经反射锁定四工厂的计时字段初值（移植最易漂移的 once 达限/interval 节拍/offset 相位语义）。
 */
class EntityPlayerActionPackTest
{
    private static int intField(Action action, String name) throws ReflectiveOperationException
    {
        Field f = Action.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(action);
    }

    private static boolean boolField(Action action, String name) throws ReflectiveOperationException
    {
        Field f = Action.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(action);
    }

    @Test
    void onceFactoryCountsOneTickAndFinishes() throws ReflectiveOperationException
    {
        Action action = Action.once();
        assertEquals(1, action.limit);
        assertEquals(1, action.interval);
        assertEquals(0, action.offset);
        assertEquals(1, intField(action, "next")); // next = interval + offset
        assertEquals(0, intField(action, "count"));
        assertFalse(boolField(action, "isContinuous"));
        assertFalse(action.done);
    }

    @Test
    void continuousFactoryNeverFinishes() throws ReflectiveOperationException
    {
        Action action = Action.continuous();
        assertEquals(-1, action.limit);
        assertEquals(1, action.interval);
        assertEquals(1, intField(action, "next"));
        assertEquals(true, boolField(action, "isContinuous"));
        assertFalse(action.done);
    }

    @Test
    void intervalFactoryTicksEveryN() throws ReflectiveOperationException
    {
        Action action = Action.interval(5);
        assertEquals(-1, action.limit);
        assertEquals(5, action.interval);
        assertEquals(0, action.offset);
        assertEquals(5, intField(action, "next"));
        assertFalse(boolField(action, "isContinuous"));
    }

    @Test
    void intervalOffsetShiftsPhase() throws ReflectiveOperationException
    {
        // offset 平移首个触发点（next = interval + offset），保持节拍不变
        Action action = Action.interval(5, 2);
        assertEquals(5, action.interval);
        assertEquals(2, action.offset);
        assertEquals(7, intField(action, "next"));
    }
}
