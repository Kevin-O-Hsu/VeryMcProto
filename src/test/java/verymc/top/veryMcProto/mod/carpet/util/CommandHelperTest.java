package verymc.top.veryMcProto.mod.carpet.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CommandHelper.parseLevel 纯函数矩阵（上游 carpet/utils/CommandHelper.canUseCommand:52-57 的
 * 级别映射域）。canUseCommand 本体依赖 NMS/Bukkit 运行时，不入纯 JVM 单测面。
 */
class CommandHelperTest
{
    @Test
    void opsMapsToGamemastersLevelTwo()
    {
        assertEquals(2, CommandHelper.parseLevel("ops"));
    }

    @Test
    void numericLevelsMapDirectly()
    {
        assertEquals(0, CommandHelper.parseLevel("0"));
        assertEquals(1, CommandHelper.parseLevel("1"));
        assertEquals(2, CommandHelper.parseLevel("2"));
        assertEquals(3, CommandHelper.parseLevel("3"));
        assertEquals(4, CommandHelper.parseLevel("4"));
    }

    @Test
    void everythingElseIsInvalid()
    {
        // true/false 由 canUseCommand 在 sender 分派前短路，不属级别域；大小写敏感（上游 switch 同判）
        assertEquals(-1, CommandHelper.parseLevel("true"));
        assertEquals(-1, CommandHelper.parseLevel("false"));
        assertEquals(-1, CommandHelper.parseLevel("5"));
        assertEquals(-1, CommandHelper.parseLevel(""));
        assertEquals(-1, CommandHelper.parseLevel("OPS"));
        assertEquals(-1, CommandHelper.parseLevel("ops "));
        assertEquals(-1, CommandHelper.parseLevel(null));
    }
}
