package verymc.top.veryMcProto.mod.servux.schematic.container;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LitematicaBitArray 位级打包 round-trip 单测（schematic 存储核心算法）。
 *
 * <p>覆盖：palette 索引按 {@code bitsPerEntry} 打包进 {@code long[]}。含条目内取值、
 * 跨 64-bit long 边界条目（getAt/setAt 的跨 long 分支，最易错）、邻居不被污染、
 * maxEntryValue 位掩码、roundUp 对齐。
 *
 * <p>纯算法（零 NMS 运行时逻辑）；仅经 LitematicaBitArray 构造间接依赖 commons-lang3 {@code Validate}
 * （paperDevBundle 传递）。
 */
class LitematicaBitArrayTest
{
    @Test
    void setGet_roundTrip_variousBitsPerEntry()
    {
        for (int bits : new int[] { 1, 2, 4, 5, 8, 15 })
        {
            int max = (1 << bits) - 1;
            long size = 200;
            LitematicaBitArray arr = new LitematicaBitArray(bits, size);
            Random rng = new Random(42L + bits);
            int[] values = new int[(int) size];
            for (int i = 0; i < size; i++)
            {
                values[i] = rng.nextInt(max + 1);
                arr.setAt(i, values[i]);
            }
            for (int i = 0; i < size; i++)
            {
                assertEquals(values[i], arr.getAt(i), "bits=" + bits + " index=" + i);
            }
        }
    }

    @Test
    void setGet_crossesLongBoundary_doesNotCorruptNeighbors()
    {
        // bits=5：index 12 起始 bit = 60，跨 64-bit long 边界（60..64），走 setAt/getAt 的跨 long 分支
        LitematicaBitArray arr = new LitematicaBitArray(5, 64);
        arr.setAt(12, 31);
        assertEquals(31, arr.getAt(12));
        // 邻居条目不被跨 long 写入污染
        arr.setAt(11, 1);
        arr.setAt(13, 2);
        assertEquals(1, arr.getAt(11));
        assertEquals(31, arr.getAt(12));
        assertEquals(2, arr.getAt(13));
    }

    @Test
    void maxEntryValue_bitmaskAtMax()
    {
        LitematicaBitArray arr = new LitematicaBitArray(4, 10);
        arr.setAt(0, 15); // 4-bit 最大值（位掩码上限）
        assertEquals(15, arr.getAt(0));
    }

    @Test
    void roundUp_alignsToInterval()
    {
        assertEquals(64L, LitematicaBitArray.roundUp(1L, 64L));
        assertEquals(64L, LitematicaBitArray.roundUp(64L, 64L));
        assertEquals(128L, LitematicaBitArray.roundUp(65L, 64L));
        assertEquals(0L, LitematicaBitArray.roundUp(10L, 0L), "interval=0 短路返回 0");
    }
}
