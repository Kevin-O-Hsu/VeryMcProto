package verymc.top.veryMcProto.mod.servux.schematic.placement;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.StringTag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import verymc.top.veryMcProto.mod.servux.schematic.LitematicaSchematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubRegions 坏条目守卫单测（对齐上游 SchematicPlacement:1210/:1258 的
 * {@code entry.contains("Pos", Constants.NBT.TAG_INT_ARRAY)} 跳过语义）。
 *
 * <p>守卫动机：缺 Pos（TAG_INT_ARRAY）的条目经 {@code NbtUtils.readBlockPosFromIntArray}
 * 得 null origin 入 map，NPE 不在解析时（两重载的 try/catch 拦不住）而在下游几何计算
 * （getSubRegionBoxes → PositionUtils.getTransformedBlockPos 首行 {@code pos.getX()} 解引用）。
 * 修复前一条坏 SubRegion 即炸掉整个 placement；修复后坏条目整条跳过、好条目完好。
 */
class SchematicPlacementGuardTest
{
    @BeforeAll
    static void bootstrapMinecraft()
    {
        // 26.1：NBT/注册表 <clinit> 链要求 MC bootstrap（同 TaskGroupTest 前置范式）
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 合法 region 条目：{Pos: int[3], Name: "..."}。 */
    private static CompoundTag regionEntry(String name, int x, int y, int z)
    {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", new IntArrayTag(new int[] { x, y, z }));
        tag.putString("Name", name);
        return tag;
    }

    /** 坏 region 条目：缺 Pos。 */
    private static CompoundTag regionEntryNoPos(String name)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    /** 坏 region 条目：Pos 键存在但非 TAG_INT_ARRAY。 */
    private static CompoundTag regionEntryWrongPosType(String name)
    {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", StringTag.valueOf("not-an-int-array"));
        tag.putString("Name", name);
        return tag;
    }

    /** 最小可解析 Schematics 桩（{@code new LitematicaSchematic} 要求 Version ∈ [1,7]，空 compound 会抛 no_schematic_version）。 */
    private static CompoundTag schematicStub()
    {
        CompoundTag schematics = new CompoundTag();
        schematics.putInt("Version", LitematicaSchematic.SCHEMATIC_VERSION);
        return schematics;
    }

    /** 顶层 tags（重载一：内含 Schematics 桩）。 */
    private static CompoundTag tagsWithSubRegions(CompoundTag subRegions)
    {
        CompoundTag tags = new CompoundTag();
        tags.put("Schematics", schematicStub());
        tags.put("Origin", new IntArrayTag(new int[] { 0, 64, 0 }));
        tags.putString("Name", "guard-test");
        tags.put("SubRegions", subRegions);
        return tags;
    }

    /** 混合好/坏条目的 SubRegions。 */
    private static CompoundTag mixedSubRegions()
    {
        CompoundTag subRegions = new CompoundTag();
        subRegions.put("good1", regionEntry("r1", 1, 2, 3));
        subRegions.put("badNoPos", regionEntryNoPos("bad1"));
        subRegions.put("badWrongType", regionEntryWrongPosType("bad2"));
        subRegions.put("good2", regionEntry("r2", 7, 8, 9));
        return subRegions;
    }

    @Test
    void overloadFromTagsSkipsBadEntriesAndKeepsGood()
    {
        SchematicPlacement placement = SchematicPlacement.createFromNbt(tagsWithSubRegions(mixedSubRegions()));

        assertNotNull(placement.getRelativeSubRegionPlacement("good1"), "好条目必须保留");
        assertNotNull(placement.getRelativeSubRegionPlacement("good2"), "好条目必须保留");
        assertNull(placement.getRelativeSubRegionPlacement("badNoPos"), "缺 Pos 条目必须跳过（上游 :1210 continue）");
        assertNull(placement.getRelativeSubRegionPlacement("badWrongType"), "Pos 非 TAG_INT_ARRAY 条目必须跳过");
        assertEquals(2, placement.getAllSubRegionsPlacements().size());
    }

    @Test
    void overloadFromSchematicSkipsBadEntriesAndKeepsGood() throws CommandSyntaxException
    {
        LitematicaSchematic schematic = new LitematicaSchematic(schematicStub());

        CompoundTag tags = new CompoundTag();
        tags.put("Origin", new IntArrayTag(new int[] { 0, 64, 0 }));
        tags.putString("Name", "guard-test");
        tags.put("SubRegions", mixedSubRegions());

        SchematicPlacement placement = SchematicPlacement.createFromNbt(schematic, tags);

        assertNotNull(placement.getRelativeSubRegionPlacement("good1"), "好条目必须保留");
        assertNotNull(placement.getRelativeSubRegionPlacement("good2"), "好条目必须保留");
        assertNull(placement.getRelativeSubRegionPlacement("badNoPos"), "缺 Pos 条目必须跳过（上游 :1258 continue）");
        assertNull(placement.getRelativeSubRegionPlacement("badWrongType"), "Pos 非 TAG_INT_ARRAY 条目必须跳过");
        assertEquals(2, placement.getAllSubRegionsPlacements().size());
    }

    @Test
    void allBadEntriesYieldEmptyMapNotNpe()
    {
        CompoundTag subRegions = new CompoundTag();
        subRegions.put("bad1", regionEntryNoPos("bad1"));
        subRegions.put("bad2", regionEntryWrongPosType("bad2"));

        SchematicPlacement placement = SchematicPlacement.createFromNbt(tagsWithSubRegions(subRegions));

        // 全坏 → 空 map（下游 updateEnclosingBox 不设框、粘贴 region 循环空转——上游同语义，无 NPE 路径）
        assertTrue(placement.getAllSubRegionsPlacements().isEmpty());
    }
}
