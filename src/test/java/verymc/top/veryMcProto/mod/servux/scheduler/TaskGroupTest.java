package verymc.top.veryMcProto.mod.servux.scheduler;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;
import verymc.top.veryMcProto.mod.servux.schematic.selection.Box;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task 组（type 14-17）形状黄金样本单测——全部以 <b>26.1 客户端真实编码形状</b>为固定样本（非自编自解）：
 * <ul>
 *   <li>Box 线格式 = 客户端 {@code Box.CODEC}（RecordCodecBuilder: pos1/pos2 = BlockPos.CODEC，malilib DataOps
 *       INT_STREAM → IntArrayTag[x,y,z]，name = string）——B 轮字节码级实证；</li>
 *   <li>type 16 进度/完成帧（InfoHudTaskSync 组包 ↔ litematica InfoHudSync 读端对偶）；</li>
 *   <li>{@code Type} 字面量必须为枚举常量名 "REMAINING_CHUNKS"（客户端 valueOf 无容错，未知值抛异常）。</li>
 * </ul>
 */
class TaskGroupTest
{
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft()
    {
        // 26.1：ChunkPos 的 <clinit> 静态链（ChunkPyramid → ChunkStatus → 注册表）要求 MC bootstrap，
        // 纯 JVM 测试需先行引导（原版服务端启动序：tryDetectVersion → bootStrap；服务端运行时天然已引导）
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 客户端形状的 Box NBT：{pos1:[x,y,z], pos2:[x,y,z], name:"..."}（IntArrayTag 而非 long/compound）。 */
    private static CompoundTag clientShapeBox(int x1, int y1, int z1, int x2, int y2, int z2, String name)
    {
        CompoundTag tag = new CompoundTag();
        tag.put("pos1", new IntArrayTag(new int[] { x1, y1, z1 }));
        tag.put("pos2", new IntArrayTag(new int[] { x2, y2, z2 }));
        tag.putString("name", name);
        return tag;
    }

    @org.junit.jupiter.api.Test
    void decodeBoxClientGoldenShape()
    {
        Box box = LitematicsDataProvider.decodeBox(clientShapeBox(1, 64, 2, 10, 70, 12, "box1"));

        assertNotNull(box);
        assertEquals(1, box.getPos1().getX());
        assertEquals(64, box.getPos1().getY());
        assertEquals(2, box.getPos1().getZ());
        assertEquals(10, box.getPos2().getX());
        assertEquals(70, box.getPos2().getY());
        assertEquals(12, box.getPos2().getZ());
        assertEquals("box1", box.getName());

        // 非法形状（缺 pos2 / 维度错误）拒绝为 null
        CompoundTag bad = clientShapeBox(0, 0, 0, 1, 1, 1, "bad");
        bad.remove("pos2");
        assertNull(LitematicsDataProvider.decodeBox(bad));

        CompoundTag shortArray = clientShapeBox(0, 0, 0, 1, 1, 1, "bad");
        shortArray.put("pos1", new IntArrayTag(new int[] { 1, 2 }));
        assertNull(LitematicsDataProvider.decodeBox(shortArray));
    }

    @org.junit.jupiter.api.Test
    void progressFrameMatchesClientReaderShape()
    {
        List<ChunkPos> pending = List.of(new ChunkPos(100, 200), new ChunkPos(-5, 7), new ChunkPos(0, 0));
        CompoundTag frame = InfoHudTaskSync.progressFrame("Fill", pending);

        // 顶层：InfoHudComplete=false + InfoHudSync 列表
        assertFalse(frame.getBooleanOr("InfoHudComplete", true));
        ListTag syncList = frame.getListOrEmpty("InfoHudSync");
        assertEquals(1, syncList.size());

        // 条目：Type 字面量 = 枚举常量名（客户端 InfoHudSyncType.valueOf 无容错——锁死防漂移）
        CompoundTag entry = syncList.getCompoundOrEmpty(0);
        assertTrue(InfoHudTaskSync.isRemainingChunksType(entry),
                "Type 必须为枚举常量名 REMAINING_CHUNKS，实际: " + entry.getStringOr("Type", ""));

        // Data：标题行（cx=-1）+ 每区块一行（cx/cz = 区块坐标），rc = 待处理总数
        ListTag data = entry.getListOrEmpty("Data");
        assertEquals(1 + pending.size(), data.size());
        CompoundTag titleRow = data.getCompoundOrEmpty(0);
        assertEquals("Fill", titleRow.getStringOr("n", ""));
        assertEquals(pending.size(), titleRow.getIntOr("rc", -1));
        assertEquals(-1, titleRow.getIntOr("cx", 0));
        assertEquals(-1, titleRow.getIntOr("cz", 0));
        CompoundTag firstChunkRow = data.getCompoundOrEmpty(1);
        assertEquals(100, firstChunkRow.getIntOr("cx", -999));
        assertEquals(200, firstChunkRow.getIntOr("cz", -999));
    }

    @org.junit.jupiter.api.Test
    void progressFrameCapsAtTenChunkLines()
    {
        List<ChunkPos> pending = new java.util.ArrayList<>();
        for (int i = 0; i < 25; ++i)
        {
            pending.add(new ChunkPos(i, -i));
        }

        CompoundTag frame = InfoHudTaskSync.progressFrame("Delete", pending);
        ListTag data = frame.getListOrEmpty("InfoHudSync").getCompoundOrEmpty(0).getListOrEmpty("Data");

        assertEquals(11, data.size(), "标题行 + 最多 10 个区块坐标行");
        assertEquals(25, data.getCompoundOrEmpty(0).getIntOr("rc", -1), "rc = 待处理总数（非展示行数）");
        assertEquals("Delete", data.getCompoundOrEmpty(0).getStringOr("n", ""));
    }

    @org.junit.jupiter.api.Test
    void completeFrameHasNoInfoHudSyncKey()
    {
        CompoundTag frame = InfoHudTaskSync.completeFrame();

        // 完成帧仅 InfoHudComplete=true、不带 InfoHudSync 键（客户端缺键回空列表，InfoHudSync.java:97 路径）
        assertTrue(frame.getBooleanOr("InfoHudComplete", false));
        assertFalse(frame.contains("InfoHudSync"), "完成帧不得携带 InfoHudSync 键");
    }
}
