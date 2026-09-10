package verymc.top.veryMcProto.mod.servux.dataproviders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * {@link StructureDataProvider#splitStructuresBySize(ListTag, int, int)} 条目级分批纯函数单测。
 *
 * <p>覆盖上游 sendStructures :568-604 语义四要素：①总量+padding ≤ maxSize 单批直通（:568，含 == 边界）；
 * ②逐条累计 {@code >=} maxSize 即 flush（:586）；③首条无条件入列（:586 的 !isEmpty() 前置）；
 * ④空条目跳过（:582）+ 收尾 flush（:598）+ 条目总数守恒。
 */
class StructureBatchSplitTest
{
    private static final int PADDING = StructureDataProvider.STRUCTURE_BATCH_PADDING;

    /** 造约 approxBytes 大小的条目（putString 单键，sizeInBytes ≈ 字节数 + 少量头开销）。 */
    private static CompoundTag tagOfSize(int approxBytes)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("payload", "a".repeat(approxBytes));
        return tag;
    }

    private static ListTag listOf(CompoundTag... tags)
    {
        ListTag list = new ListTag();
        for (CompoundTag t : tags) { list.add(t); }
        return list;
    }

    @Test
    void singleBatchWhenTotalWithinLimit()
    {
        // ①总量 + padding ≤ maxSize → 单批直通（等价上游单帧分支）
        ListTag list = listOf(tagOfSize(100), tagOfSize(100));
        List<ListTag> batches = StructureDataProvider.splitStructuresBySize(list, 1_000_000, PADDING);

        assertEquals(1, batches.size());
        assertEquals(list, batches.get(0));
    }

    @Test
    void boundaryExactlyEqualsIsSingleBatch()
    {
        // ①边界：总量 + padding == maxSize 仍单批（上游 <= 判定，恰好相等放行）
        CompoundTag entry = tagOfSize(5_000);
        ListTag list = listOf(entry);
        int maxSize = list.sizeInBytes() + PADDING;

        List<ListTag> batches = StructureDataProvider.splitStructuresBySize(list, maxSize, PADDING);
        assertEquals(1, batches.size());
    }

    @Test
    void flushesWhenAccumulatedSizeReachesLimit()
    {
        // ②③逐条累计 >= 即 flush；首条无条件入列——entry1/entry2 各 10KB，maxSize 压到 12KB
        //（12KB - padding = 8KB < 第二条 10KB）→ 第一条独立成批，第二条收尾 flush
        CompoundTag e1 = tagOfSize(10_000);
        CompoundTag e2 = tagOfSize(10_000);
        ListTag list = listOf(e1, e2);

        List<ListTag> batches = StructureDataProvider.splitStructuresBySize(list, 12_000, PADDING);

        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).size());
        assertEquals(1, batches.get(1).size());
        // 每批自身不超限（padding 已计入判定）
        for (ListTag batch : batches)
        {
            assertTrue(batch.sizeInBytes() + PADDING <= 12_000 + e1.sizeInBytes());
        }
    }

    @Test
    void oversizedFirstEntryStillEntersBatch()
    {
        // ③首条无条件入列：单条 entry 自身已超 maxSize，仍完整成批（上游不拆单条）
        CompoundTag big = tagOfSize(50_000);
        ListTag list = listOf(big);

        List<ListTag> batches = StructureDataProvider.splitStructuresBySize(list, 10_000, PADDING);

        assertEquals(1, batches.size());
        assertEquals(1, batches.get(0).size());
    }

    @Test
    void skipsEmptyEntriesAndConservesCount()
    {
        // ④空条目跳过 + 收尾 flush + 条目守恒
        CompoundTag e1 = tagOfSize(10_000);
        CompoundTag empty = new CompoundTag();
        CompoundTag e2 = tagOfSize(10_000);
        CompoundTag e3 = tagOfSize(10_000);
        ListTag list = listOf(e1, empty, e2, e3);

        List<ListTag> batches = StructureDataProvider.splitStructuresBySize(list, 12_000, PADDING);

        int total = batches.stream().mapToInt(ListTag::size).sum();
        assertEquals(3, total, "空条目被跳过，非空条目总数守恒");
        for (ListTag batch : batches)
        {
            for (int i = 0; i < batch.size(); i++)
            {
                assertTrue(!batch.getCompoundOrEmpty(i).isEmpty(), "空条目不得进入任何批");
            }
        }
    }
}
