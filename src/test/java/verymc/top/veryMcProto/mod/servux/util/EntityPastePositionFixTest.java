package verymc.top.veryMcProto.mod.servux.util;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;

import verymc.top.veryMcProto.mod.servux.util.data.Constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchematicPlacingUtils#applyEntityPastePositionFixes} 纯函数单测（无需起服务端；
 * NMS 类经 paperDevBundle 在纯 JVM 可用——DataTagIoTest 同型）。
 *
 * <p>被测函数逐字对齐上游 servux-LTS-26.1 SchematicPlacingUtils.java:446-513 的实体位置
 * 修复族 ①-⑤（⑥ Leashable tick 需实体实例，不在纯函数面，走实机验收）。输入一律用
 * <b>字面串</b>构造（不引用生产常量），与生产键常量互为独立轨道——任一侧拼错（典型：
 * "leash" 1.21.5 起为小写）必被本测试捕获，杜绝同源共错。
 *
 * <p>已知不可观测限制：Pos 已等于目标时"跳写"与"重写同值"在 NBT 层不可区分——
 * Vec3.equals 为逐分量 Double.compare，唯一可区分输入是真实粘贴路径产不出的非规范
 * NaN 位型（-0.0 被 Double.compare 判不等、走写分支），故不设"相等跳写"用例。
 */
class EntityPastePositionFixTest
{
    /** 目标坐标：含 .5 小数与负 Z，钉死 (int) 朝零截断语义（上游同 cast）。 */
    private static final double TX = 10.5;
    private static final double TY = 64.0;
    private static final double TZ = -5.5;

    private static final int OFF_X = 100;
    private static final int OFF_Y = 10;
    private static final int OFF_Z = -50;

    private static void apply(CompoundTag tag)
    {
        SchematicPlacingUtils.applyEntityPastePositionFixes(tag, TX, TY, TZ, OFF_X, OFF_Y, OFF_Z);
    }

    /** 写入 vanilla 实体 NBT 形态的 Pos（双精度×3 列表）。 */
    private static void putPosList(CompoundTag tag, double x, double y, double z)
    {
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
    }

    /** ① 一切实体（非悬挂）Pos 重写为世界目标 + 写出 wire 形状（ListTag 双精度×3）。 */
    @Test
    void posRewrittenForNonHangingEntity()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:cow");
        putPosList(tag, 1.5, 2.5, 3.5); // 陈旧（区域相对）Pos

        apply(tag);

        ListTag pos = tag.getListOrEmpty("Pos");
        assertEquals(Constants.NBT.TAG_DOUBLE, pos.identifyRawElementType(), "Pos wire 形状必须保持双精度列表");
        assertEquals(3, pos.size());
        assertEquals(TX, pos.getDoubleOr(0, 0));
        assertEquals(TY, pos.getDoubleOr(1, 0));
        assertEquals(TZ, pos.getDoubleOr(2, 0));
    }

    /** ①' Pos 键缺失 → 兜底写目标（p == null 分支）。 */
    @Test
    void posMissingFallsBackToTarget()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:cow");

        apply(tag);

        // 读取走与生产同型的 codec Optional（NbtUtils.readEntityPositionFromTag 有恒 null 前置缺陷）
        assertEquals(new Vec3(TX, TY, TZ), tag.read("Pos", Vec3.CODEC).orElse(null));
    }

    /** ② 悬挂类 TileX/Y/Z 三键恒取世界目标 + (int) 朝零截断（-5.5 → -5，非 floor 的 -6）。 */
    @Test
    void hangingTileAlwaysTargetWithTruncation()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:item_frame");
        putPosList(tag, 1.0, 2.0, 3.0);
        tag.putInt("TileX", 1); // 陈旧锚
        tag.putInt("TileY", 2);
        tag.putInt("TileZ", 3);

        apply(tag);

        assertEquals(10, tag.getIntOr("TileX", -999));
        assertEquals(64, tag.getIntOr("TileY", -999));
        assertEquals(-5, tag.getIntOr("TileZ", -999));
    }

    /** ③ 四悬挂 id 逐一：block_pos 缺失写入目标 + 陈旧改写目标。 */
    @Test
    void hangingBlockPosWrittenAndRewrittenForFourIds()
    {
        String[] ids = {"minecraft:glow_item_frame", "minecraft:item_frame",
                        "minecraft:leash_knot", "minecraft:painting"};

        for (String id : ids)
        {
            CompoundTag missing = new CompoundTag();
            missing.putString("id", id);
            putPosList(missing, 1.0, 2.0, 3.0);

            apply(missing);

            assertEquals(new BlockPos(10, 64, -5), missing.read("block_pos", BlockPos.CODEC).orElse(null), id);

            CompoundTag stale = new CompoundTag();
            stale.putString("id", id);
            putPosList(stale, 1.0, 2.0, 3.0);
            stale.store("block_pos", BlockPos.CODEC, new BlockPos(1, 2, 3));

            apply(stale);

            assertEquals(new BlockPos(10, 64, -5), stale.read("block_pos", BlockPos.CODEC).orElse(null), id);
        }
    }

    /** ③' 负控：非悬挂实体 block_pos/TileX 均不触碰（Pos 仍重写——①覆盖一切实体）。 */
    @Test
    void nonHangingEntityBlockPosAndTileUntouched()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:armor_stand");
        putPosList(tag, 1.0, 2.0, 3.0);
        tag.store("block_pos", BlockPos.CODEC, new BlockPos(1, 2, 3));

        apply(tag);

        assertEquals(new BlockPos(1, 2, 3), tag.read("block_pos", BlockPos.CODEC).orElse(null));
        assertFalse(tag.contains("TileX"));
        assertFalse(tag.contains("TileY"));
        assertFalse(tag.contains("TileZ"));
        assertEquals(TX, tag.getListOrEmpty("Pos").getDoubleOr(0, 0));
    }

    /** ④ leash 区域相对值 + off* 平移。 */
    @Test
    void leashOffsetApplied()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:cow");
        tag.store("leash", BlockPos.CODEC, new BlockPos(2, 3, 4));

        apply(tag);

        assertEquals(new BlockPos(102, 13, -46), tag.read("leash", BlockPos.CODEC).orElse(null));
    }

    /** ④' leash 缺失 → 不产生键。 */
    @Test
    void leashMissingStaysAbsent()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:cow");

        apply(tag);

        assertFalse(tag.contains("leash"));
    }

    /** ④'' leash == ZERO 哨兵 → 跳过不平移（上游刻意形态）。 */
    @Test
    void leashZeroSentinelSkipped()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:cow");
        tag.store("leash", BlockPos.CODEC, BlockPos.ZERO);

        apply(tag);

        assertEquals(BlockPos.ZERO, tag.read("leash", BlockPos.CODEC).orElse(null));
    }

    /** ⑤ home_pos 全守卫枚举：radius>0 平移 / radius=0 不动 / radius 缺省(-1) 不动 / hp=ZERO 不动 / hp 缺失不产键。 */
    @Test
    void homePosGuardedRadiusAllBranches()
    {
        CompoundTag active = new CompoundTag();
        active.putString("id", "minecraft:cow");
        active.store("home_pos", BlockPos.CODEC, new BlockPos(5, 5, 5));
        active.putInt("home_radius", 8);

        apply(active);

        assertEquals(new BlockPos(105, 15, -45), active.read("home_pos", BlockPos.CODEC).orElse(null));

        CompoundTag zeroRadius = new CompoundTag();
        zeroRadius.putString("id", "minecraft:cow");
        zeroRadius.store("home_pos", BlockPos.CODEC, new BlockPos(5, 5, 5));
        zeroRadius.putInt("home_radius", 0);

        apply(zeroRadius);

        assertEquals(new BlockPos(5, 5, 5), zeroRadius.read("home_pos", BlockPos.CODEC).orElse(null), "radius=0 刻意不修正");

        CompoundTag noRadius = new CompoundTag();
        noRadius.putString("id", "minecraft:cow");
        noRadius.store("home_pos", BlockPos.CODEC, new BlockPos(5, 5, 5));

        apply(noRadius);

        assertEquals(new BlockPos(5, 5, 5), noRadius.read("home_pos", BlockPos.CODEC).orElse(null), "radius 缺省(-1) 刻意不修正");

        CompoundTag zeroPos = new CompoundTag();
        zeroPos.putString("id", "minecraft:cow");
        zeroPos.store("home_pos", BlockPos.CODEC, BlockPos.ZERO);
        zeroPos.putInt("home_radius", 8);

        apply(zeroPos);

        assertEquals(BlockPos.ZERO, zeroPos.read("home_pos", BlockPos.CODEC).orElse(null), "home_pos ZERO 哨兵跳过");

        CompoundTag noPos = new CompoundTag();
        noPos.putString("id", "minecraft:cow");
        noPos.putInt("home_radius", 8);

        apply(noPos);

        assertFalse(noPos.contains("home_pos"), "home_pos 缺失不产生键");
    }

    /** ⑤' 生效分支中 home_radius 值本身保持不改写。 */
    @Test
    void homeRadiusValuePreserved()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:cow");
        tag.store("home_pos", BlockPos.CODEC, new BlockPos(5, 5, 5));
        tag.putInt("home_radius", 8);

        apply(tag);

        assertEquals(8, tag.getIntOr("home_radius", -1));
    }

    /** ⑧ 全字段单趟集成 + 非 S11 边界锁：UUID 键逐字节不动（上游自认不可修，我方不触碰）。 */
    @Test
    void fullFieldIntegrationWithUuidUntouched()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:item_frame");
        putPosList(tag, 1.0, 2.0, 3.0);
        tag.putInt("TileX", 1);
        tag.putInt("TileY", 2);
        tag.putInt("TileZ", 3);
        tag.store("block_pos", BlockPos.CODEC, new BlockPos(1, 2, 3));
        tag.store("leash", BlockPos.CODEC, new BlockPos(2, 3, 4));
        tag.store("home_pos", BlockPos.CODEC, new BlockPos(5, 5, 5));
        tag.putInt("home_radius", 8);
        tag.putIntArray("UUID", new int[] {1, 2, 3, 4});

        apply(tag);

        assertEquals(TX, tag.getListOrEmpty("Pos").getDoubleOr(0, 0));
        assertEquals(10, tag.getIntOr("TileX", -999));
        assertEquals(64, tag.getIntOr("TileY", -999));
        assertEquals(-5, tag.getIntOr("TileZ", -999));
        assertEquals(new BlockPos(10, 64, -5), tag.read("block_pos", BlockPos.CODEC).orElse(null));
        assertEquals(new BlockPos(102, 13, -46), tag.read("leash", BlockPos.CODEC).orElse(null));
        assertEquals(new BlockPos(105, 15, -45), tag.read("home_pos", BlockPos.CODEC).orElse(null));
        assertTrue(java.util.Arrays.equals(new int[] {1, 2, 3, 4},
                                           tag.getIntArray("UUID").orElse(new int[0])), "UUID 不在本修复族范围");
    }
}
