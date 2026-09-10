package verymc.top.veryMcProto.mod.servux.util;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.mod.servux.util.Log;
import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;
import verymc.top.veryMcProto.mod.servux.schematic.LitematicaSchematic;
import verymc.top.veryMcProto.mod.servux.schematic.LitematicaSchematic.EntityInfo;
import verymc.top.veryMcProto.mod.servux.schematic.container.LitematicaBlockStateContainer;
import verymc.top.veryMcProto.mod.servux.schematic.placement.SchematicPlacement;
import verymc.top.veryMcProto.mod.servux.schematic.placement.SubRegionPlacement;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtUtils;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtView;
import verymc.top.veryMcProto.mod.servux.util.position.PositionUtils;

public class SchematicPlacingUtils
{
    // 实体位置修复族 NBT 键——逐字对齐上游 NbtKeys.java（OriginImpl/servux-LTS-26.1/
    // src/main/java/fi/dy/masa/servux/util/nbt/NbtKeys.java）：POS=:12、ATTACHED_BLOCK_POS=:81、
    // LEASH=:111、HOME_RADIUS=:150、HOME_POS=:151。"leash" 自 1.21.5 起为全小写（旧版 "Leash"），
    // 键拼错无任何报错、修复整条静默失效——由 EntityPastePositionFixTest 字面串用例互锁。
    private static final String KEY_POS = "Pos";
    private static final String KEY_ATTACHED_BLOCK_POS = "block_pos";
    private static final String KEY_LEASH = "leash";
    private static final String KEY_HOME_RADIUS = "home_radius";
    private static final String KEY_HOME_POS = "home_pos";

    public static boolean placeToWorldWithinChunk(Level world,
                                                  ChunkPos chunkPos,
                                                  SchematicPlacement schematicPlacement,
                                                  ReplaceBehavior replace,
                                                  PasteLayerBehavior layerBehavior,
                                                  @Nullable LayerRange layerRange,
                                                  boolean notifyNeighbors)
    {
        LitematicaSchematic schematic = schematicPlacement.getSchematic();
        Set<String> regionsTouchingChunk = schematicPlacement.getRegionsTouchingChunk(chunkPos.x(), chunkPos.z());
        BlockPos origin = schematicPlacement.getOrigin();
        boolean allSuccess = true;

        try
        {
            if (notifyNeighbors == false)
            {
                WorldUtils.setShouldPreventBlockUpdates(world, true);
            }

            for (String regionName : regionsTouchingChunk)
            {
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);

                if (container == null)
                {
                    allSuccess = false;
                    continue;
                }

                SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(regionName);

                if (placement.isEnabled())
                {
                    Map<BlockPos, CompoundTag> blockEntityMap = schematic.getBlockEntityMapForRegion(regionName);
                    Map<BlockPos, ScheduledTick<@NotNull Block>> scheduledBlockTicks = schematic.getScheduledBlockTicksForRegion(regionName);
                    Map<BlockPos, ScheduledTick<@NotNull Fluid>> scheduledFluidTicks = schematic.getScheduledFluidTicksForRegion(regionName);

                    if (placeBlocksWithinChunk(world, chunkPos, regionName, container, blockEntityMap,
                                               origin, schematicPlacement, placement, scheduledBlockTicks,
                                               scheduledFluidTicks, replace, layerBehavior, layerRange, notifyNeighbors) == false)
                    {
                        allSuccess = false;
                        Log.warn("Invalid/missing schematic data in schematic '{}' for sub-region '{}'", schematic.getMetadata().getName(), regionName);
                    }

                    List<EntityInfo> entityList = schematic.getEntityListForRegion(regionName);

                    if (schematicPlacement.ignoreEntities() == false &&
                        placement.ignoreEntities() == false && entityList != null)
                    {
                        placeEntitiesToWorldWithinChunk(world, chunkPos, entityList, origin, schematicPlacement, placement, layerBehavior, layerRange);
                    }
                }
            }
        }
        finally
        {
            WorldUtils.setShouldPreventBlockUpdates(world, false);
        }

        return allSuccess;
    }

    public static boolean placeBlocksWithinChunk(Level world, ChunkPos chunkPos, String regionName,
                                                 LitematicaBlockStateContainer container,
                                                 Map<BlockPos, CompoundTag> blockEntityMap,
                                                 BlockPos origin,
                                                 SchematicPlacement schematicPlacement,
                                                 SubRegionPlacement placement,
                                                 @Nullable Map<BlockPos, ScheduledTick<Block>> scheduledBlockTicks,
                                                 @Nullable Map<BlockPos, ScheduledTick<Fluid>> scheduledFluidTicks,
                                                 ReplaceBehavior replace,
                                                 PasteLayerBehavior layerBehavior,
                                                 @Nullable LayerRange layerRange,
                                                 boolean notifyNeighbors)
    {
        IntBoundingBox bounds = schematicPlacement.getBoxWithinChunkForRegion(regionName, chunkPos.x(), chunkPos.z());
        Vec3i regionSize = schematicPlacement.getSchematic().getAreaSize(regionName);

        if (bounds == null || container == null || blockEntityMap == null || regionSize == null)
        {
            return false;
        }

        BlockPos regionPos = placement.getPos();

        // These are the untransformed relative positions
        BlockPos posEndRel = (new BlockPos(PositionUtils.getRelativeEndPositionFromAreaSize(regionSize))).offset(regionPos);
        BlockPos posMinRel = PositionUtils.getMinCorner(regionPos, posEndRel);

        // The transformed sub-region origin position
        BlockPos regionPosTransformed = PositionUtils.getTransformedBlockPos(regionPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());

        // The relative offset of the affected region's corners, to the sub-region's origin corner
        BlockPos boxMinRel = new BlockPos(bounds.minX() - origin.getX() - regionPosTransformed.getX(), 0, bounds.minZ() - origin.getZ() - regionPosTransformed.getZ());
        BlockPos boxMaxRel = new BlockPos(bounds.maxX() - origin.getX() - regionPosTransformed.getX(), 0, bounds.maxZ() - origin.getZ() - regionPosTransformed.getZ());

        // Reverse transform that relative offset, to get the untransformed orientation's offsets
        boxMinRel = PositionUtils.getReverseTransformedBlockPos(boxMinRel, placement.getMirror(), placement.getRotation());
        boxMaxRel = PositionUtils.getReverseTransformedBlockPos(boxMaxRel, placement.getMirror(), placement.getRotation());

        boxMinRel = PositionUtils.getReverseTransformedBlockPos(boxMinRel, schematicPlacement.getMirror(), schematicPlacement.getRotation());
        boxMaxRel = PositionUtils.getReverseTransformedBlockPos(boxMaxRel, schematicPlacement.getMirror(), schematicPlacement.getRotation());

        // Get the offset relative to the sub-region's minimum corner, instead of the origin corner (which can be at any corner)
        boxMinRel = boxMinRel.subtract(posMinRel.subtract(regionPos));
        boxMaxRel = boxMaxRel.subtract(posMinRel.subtract(regionPos));

        BlockPos posMin = PositionUtils.getMinCorner(boxMinRel, boxMaxRel);
        BlockPos posMax = PositionUtils.getMaxCorner(boxMinRel, boxMaxRel);

        final int startX = posMin.getX();
        final int startZ = posMin.getZ();
        final int endX = posMax.getX();
        final int endZ = posMax.getZ();

        final int startY = 0;
        final int endY = Math.abs(regionSize.getY()) - 1;
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();


        if (startX < 0 || startZ < 0 || endX >= container.getSize().getX() || endZ >= container.getSize().getZ())
        {
            ServuxDebug.log(ServuxDebug.Cat.SCHEMATIC, String.format("OUT OF BOUNDS - region: %s, sx: %d, sz: %d, ex: %d, ez: %d - size x: %d z: %d",
                              regionName, startX, startZ, endX, endZ, container.getSize().getX(), container.getSize().getZ()));
            return false;
        }

        final Rotation rotationCombined = schematicPlacement.getRotation().getRotated(placement.getRotation());
        final Mirror mirrorMain = schematicPlacement.getMirror();
        final BlockState barrier = Blocks.BARRIER.defaultBlockState();
        Mirror mirrorSub = placement.getMirror();
        final boolean ignoreInventories = false;

        if (mirrorSub != Mirror.NONE &&
            (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90 ||
            schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90))
        {
            mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        final int posMinRelMinusRegX = posMinRel.getX() - regionPos.getX();
        final int posMinRelMinusRegY = posMinRel.getY() - regionPos.getY();
        final int posMinRelMinusRegZ = posMinRel.getZ() - regionPos.getZ();

        for (int y = startY; y <= endY; ++y)
        {
            for (int z = startZ; z <= endZ; ++z)
            {
                for (int x = startX; x <= endX; ++x)
                {
                    BlockState state = container.get(x, y, z);

                    if (state.getBlock() == Blocks.STRUCTURE_VOID)
                    {
                        continue;
                    }

                    posMutable.set(x, y, z);
                    CompoundTag teNBT = blockEntityMap.get(posMutable);
                    BlockPos origPos = posMutable.immutable();

                    posMutable.set(posMinRelMinusRegX + x,
                                   posMinRelMinusRegY + y,
                                   posMinRelMinusRegZ + z);

                    BlockPos pos = PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
                    pos = pos.offset(regionPosTransformed).offset(origin);

                    if (!shouldPasteBlock(pos, layerBehavior, layerRange))
                    {
//                        Log.error("placeBlocksWithinChunk(): Skipping block at pos [{}]", pos.toShortString());
                        continue;
                    }

                    BlockState stateOld = world.getBlockState(pos);

                    if ((replace == ReplaceBehavior.NONE && stateOld.isAir() == false) ||
                        (replace == ReplaceBehavior.WITH_NON_AIR && state.isAir() == true))
                    {
                        continue;
                    }

                    // Fix inventory of adjacent chest sides when mirrored
                    if (state.hasBlockEntity() && state.is(Blocks.CHEST) &&
                        !ignoreInventories && mirrorMain != Mirror.NONE &&
                        !(state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) &&
                        LitematicsDataProvider.INSTANCE.isEnabled() &&
                        LitematicsDataProvider.INSTANCE.fixChestMirror.getValue())
                    {
                        Direction facing = state.getValue(ChestBlock.FACING);
                        Direction.Axis axis = facing.getAxis();
                        ChestType type = state.getValue(ChestBlock.TYPE).getOpposite();

                        if (mirrorMain != Mirror.NONE && axis != Direction.Axis.Y)
                        {
                            Direction facingAdj = type == ChestType.LEFT ? facing.getCounterClockWise(Direction.Axis.Y) : facing.getClockWise(Direction.Axis.Y);
                            BlockPos posAdj = origPos.relative(facingAdj);
                            teNBT = blockEntityMap.getOrDefault(posAdj, teNBT).copy();
                        }
                    }

                    if (mirrorMain != Mirror.NONE) { state = state.mirror(mirrorMain); }
                    if (mirrorSub != Mirror.NONE)  { state = state.mirror(mirrorSub); }
                    if (rotationCombined != Rotation.NONE) { state = state.rotate(rotationCombined); }

                    BlockEntity te = world.getBlockEntity(pos);

                    if (te != null)
                    {
                        if (te instanceof Container)
                        {
                            ((Container) te).clearContent();
                        }

                        world.setBlock(pos, barrier, 0x14);
                    }

                    if (world.setBlock(pos, state, 0x12) && teNBT != null)
                    {
                        te = world.getBlockEntity(pos);

                        if (te != null)
                        {
                            teNBT = teNBT.copy();
                            teNBT.putInt("x", pos.getX());
                            teNBT.putInt("y", pos.getY());
                            teNBT.putInt("z", pos.getZ());

                            if (ignoreInventories)
                            {
                                teNBT.remove("Items");
                            }

                            try
                            {
                                NbtView view = NbtView.getReader(teNBT, world.registryAccess());
                                te.loadWithComponents(view.getReader());

                                if (ignoreInventories && te instanceof Container)
                                {
                                    ((Container) te).clearContent();
                                }
                            }
                            catch (Exception e)
                            {
                                Log.warn("Failed to load BlockEntity data for {} @ {}", state, pos);
                            }
                        }
                    }
                }
            }
        }

        if (world instanceof ServerLevel serverWorld)
        {
            IntBoundingBox box = new IntBoundingBox(startX, startY, startZ, endX, endY, endZ);

            if (scheduledBlockTicks != null && scheduledBlockTicks.isEmpty() == false)
            {
                LevelTicks<Block> scheduler = serverWorld.getBlockTicks();

                for (Map.Entry<BlockPos, ScheduledTick<Block>> entry : scheduledBlockTicks.entrySet())
                {
                    BlockPos pos = entry.getKey();

                    if (box.containsPos(pos))
                    {
                        posMutable.set(posMinRelMinusRegX + pos.getX(),
                                       posMinRelMinusRegY + pos.getY(),
                                       posMinRelMinusRegZ + pos.getZ());

                        pos = PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
                        pos = pos.offset(regionPosTransformed).offset(origin);
                        ScheduledTick<Block> tick = entry.getValue();

                        if (world.getBlockState(pos).getBlock() == tick.type())
                        {
                            scheduler.schedule(new ScheduledTick<>(tick.type(), pos, tick.triggerTick(), tick.priority(), tick.subTickOrder()));
                        }
                    }
                }
            }

            if (scheduledFluidTicks != null && scheduledFluidTicks.isEmpty() == false)
            {
                LevelTicks<Fluid> scheduler = serverWorld.getFluidTicks();

                for (Map.Entry<BlockPos, ScheduledTick<Fluid>> entry : scheduledFluidTicks.entrySet())
                {
                    BlockPos pos = entry.getKey();

                    if (box.containsPos(pos))
                    {
                        posMutable.set(posMinRelMinusRegX + pos.getX(),
                                       posMinRelMinusRegY + pos.getY(),
                                       posMinRelMinusRegZ + pos.getZ());

                        pos = PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
                        pos = pos.offset(regionPosTransformed).offset(origin);
                        ScheduledTick<Fluid> tick = entry.getValue();

                        if (world.getBlockState(pos).getFluidState().getType() == tick.type())
                        {
                            scheduler.schedule(new ScheduledTick<>(tick.type(), pos, tick.triggerTick(), tick.priority(), tick.subTickOrder()));
                        }
                    }
                }
            }
        }

        if (notifyNeighbors)
        {
            for (int y = startY; y <= endY; ++y)
            {
                for (int z = startZ; z <= endZ; ++z)
                {
                    for (int x = startX; x <= endX; ++x)
                    {
                        posMutable.set(posMinRelMinusRegX + x,
                                       posMinRelMinusRegY + y,
                                       posMinRelMinusRegZ + z);
                        BlockPos pos = PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
                        pos = pos.offset(regionPosTransformed).offset(origin);
                        world.updateNeighborsAt(pos, world.getBlockState(pos).getBlock());
                    }
                }
            }
        }

        return true;
    }

    public static void placeEntitiesToWorldWithinChunk(Level world, ChunkPos chunkPos,
                                                       List<EntityInfo> entityList,
                                                       BlockPos origin,
                                                       SchematicPlacement schematicPlacement,
                                                       SubRegionPlacement placement,
                                                       PasteLayerBehavior layerBehavior,
                                                       @Nullable LayerRange layerRange)
    {
        BlockPos regionPos = placement.getPos();

        if (entityList == null)
        {
            return;
        }

        BlockPos regionPosRelTransformed = PositionUtils.getTransformedBlockPos(regionPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());
        final int offX = regionPosRelTransformed.getX() + origin.getX();
        final int offY = regionPosRelTransformed.getY() + origin.getY();
        final int offZ = regionPosRelTransformed.getZ() + origin.getZ();
        final double minX = (chunkPos.x() << 4);
        final double minZ = (chunkPos.z() << 4);
        final double maxX = (chunkPos.x() << 4) + 16;
        final double maxZ = (chunkPos.z() << 4) + 16;

        final Rotation rotationCombined = schematicPlacement.getRotation().getRotated(placement.getRotation());
        final Mirror mirrorMain = schematicPlacement.getMirror();
        Mirror mirrorSub = placement.getMirror();

        if (mirrorSub != Mirror.NONE &&
            (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90 ||
            schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90))
        {
            mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        for (EntityInfo info : entityList)
        {
            Vec3 pos = info.posVec;
            pos = PositionUtils.getTransformedPosition(pos, schematicPlacement.getMirror(), schematicPlacement.getRotation());
            pos = PositionUtils.getTransformedPosition(pos, placement.getMirror(), placement.getRotation());
            double x = pos.x + offX;
            double y = pos.y + offY;
            double z = pos.z + offZ;
            float[] origRot = new float[2];

            if (!shouldPasteEntity(new Vec3(x, y, z), layerBehavior, layerRange))
            {
//                Log.error("placeEntitiesToWorldWithinChunk(): Skipping Entity at pos [{}]", pos.toString());
                continue;
            }

            if (x >= minX && x < maxX && z >= minZ && z < maxZ)
            {
                CompoundTag tag = info.nbt.copy();

                // 粘贴前实体位置修复族（Pos/TileX/block_pos/leash/home_pos，对齐上游 :446-513）
                applyEntityPastePositionFixes(tag, x, y, z, offX, offY, offZ);

                ListTag rotation = tag.getListOrEmpty("Rotation");
                origRot[0] = rotation.getFloatOr(0, 0f);
                origRot[1] = rotation.getFloatOr(1, 0f);

                Entity entity = EntityUtils.createEntityAndPassengersFromNBT(tag, world);

                if (entity != null)
                {
                    rotateEntity(entity, x, y, z, rotationCombined, mirrorMain, mirrorSub);

                    // Update the sleeping position to the current position
                    if (entity instanceof LivingEntity living && living.isSleeping())
                    {
                        living.setSleepingPos(BlockPos.containing(x, y, z));
                    }

                    // Hack fix to fix the painting position offsets.
                    // The vanilla code will end up moving the position by one in two of the orientations,
                    // because it sets the hanging position to the given position (floored)
                    // and then it offsets the position from the hanging position
                    // by 0.5 or 1.0 blocks depending on the painting size.
                    if (entity instanceof Painting paintingEntity)
                    {
                        Direction right = paintingEntity.getDirection().getCounterClockWise();

                        if ((paintingEntity.getVariant().value().width() % 2) == 0 &&
                            right.getAxisDirection() == AxisDirection.POSITIVE)
                        {
                            x -= 1.0 * right.getStepX();
                            z -= 1.0 * right.getStepZ();
                        }

                        if ((paintingEntity.getVariant().value().height() % 2) == 0)
                        {
                            y -= 1.0;
                        }

                        entity.setPos(x, y, z);
                    }
                    if (entity instanceof ItemFrame frameEntity)
                    {
                        if (frameEntity.getYRot() != origRot[0] && (frameEntity.getXRot() == 90.0F || frameEntity.getXRot() == -90.0F))
                        {
                            // Fix Yaw only if Pitch is +/- 90.0F (Floor, Ceiling mounted)
                            frameEntity.setYRot(origRot[0]);
                        }
                    }

                    EntityUtils.spawnEntityAndPassengersInWorld(entity, world);

                    if (entity instanceof Display || entity instanceof Leashable)
                    {
                        entity.tick(); // Required to set the full data for rendering
                    }
                }
            }
        }
    }

    /**
     * 粘贴前实体 NBT 位置修复族——逐字对齐上游 servux-LTS-26.1
     * SchematicPlacingUtils.placeEntitiesToWorldWithinChunk（OriginImpl/servux-LTS-26.1/
     * src/main/java/fi/dy/masa/servux/util/SchematicPlacingUtils.java:446-513）：
     * <ol>
     * <li>一切实体：Pos 缺失或不等于世界目标坐标则重写为目标（vanilla 按 NBT Pos 构造实体；
     *     悬挂类载入期依赖正确锚点消除 "invalid hanging position" 告警；修复后的 p 亦是
     *     ② TileX/Y/Z 的数据源，恒为世界目标）。</li>
     * <li>四种悬挂类（glow_item_frame / item_frame / leash_knot / painting）无条件写
     *     TileX/Y/Z = (int) 目标坐标。</li>
     * <li>同分支 block_pos（ATTACHED_BLOCK_POS，1.21.5+）：缺失或不等于目标
     *     BlockPos((int)x, (int)y, (int)z) 则重写。</li>
     * <li>leash（拴绳结，存区域相对 BlockPos；键 1.21.5 起为小写）：非 null 且非 ZERO 哨兵
     *     则平移 off*（UUID 侧上游自认不可修，不触碰）。</li>
     * <li>home_pos / home_radius：home_pos 非 null 且非 ZERO 哨兵、且 home_radius &gt; 0
     *     （缺省 -1）才平移 off*；home_radius 值本身不改写（上游刻意形态）。</li>
     * </ol>
     *
     * <p>offX/offY/offZ 语义（本类 placeEntitiesToWorldWithinChunk 头部计算 ≡ 上游 :406-408）：
     * 变换后区域原点 + 粘贴原点；leash/home 锚点只平移、不随 mirror/rotation 旋转——上游同源形态。
     *
     * <p><b>有意偏差（顺序合并）</b>：上游 leash/home 修复位于 Rotation 读取（上游 :480-482）之后，
     * 本方法把 ①-⑤ 收敛为单次调用、整体前置于 Rotation 读取之前——被修复键集
     * （Pos/TileX/block_pos/leash/home_*）与 Rotation 键无交集，origRot 唯一消费点
     * （本类 ItemFrame yaw 修正 / 上游 :551-558）不读写被修复键，行为等价。
     *
     * <p>注：五个键统一走 {@code tag.read(KEY, CODEC).orElse(null)} 直调（Pos 用 Vec3.CODEC，
     * block_pos/leash/home_pos 用 BlockPos.CODEC）——缺失/畸形键一律 null，与上游
     * getCodec(...).orElse(null) 逐字同构；不依赖 NbtUtils.readEntityPositionFromTag
     * （其 getId() 守卫恒 false，恒返 null——2026-09-10 实测发现，缺陷详情见 docs/09 §26.1.6）。
     */
    public static void applyEntityPastePositionFixes(CompoundTag tag, double x, double y, double z,
                                                     int offX, int offY, int offZ)
    {
        String id = tag.getStringOr("id", "");

        // Entity Pos Fix（读取不用 NbtUtils.readEntityPositionFromTag：其守卫用 ListTag.getId()
        // ——恒返列表自身类型 9——比对 TAG_DOUBLE(6)，恒 false → 恒返 null；codec 读取与
        // block_pos/leash/home 同型 orElse(null) 直调，亦同上游 :446-447 弃用自家 NbtUtils
        // 改走 DataTypeUtils 读取之决策）
        Vec3 p = tag.read(KEY_POS, Vec3.CODEC).orElse(null);
        Vec3 pn = new Vec3(x, y, z);

        if (p == null || (!p.equals(pn)))
        {
            p = pn;
            NbtUtils.putVec3dCodec(tag, pn, KEY_POS);
        }

        // Avoid warning about invalid hanging position.
        // Note that this position isn't technically correct, but it only needs to be within 16 blocks
        // of the entity position to avoid the warning.
        if (id.equals("minecraft:glow_item_frame") ||
            id.equals("minecraft:item_frame") ||
            id.equals("minecraft:leash_knot") ||
            id.equals("minecraft:painting"))
        {
            tag.putInt("TileX", (int) p.x);
            tag.putInt("TileY", (int) p.y);
            tag.putInt("TileZ", (int) p.z);

            // Block-Attached Pos (1.21.5+) Fix
            BlockPos ps = tag.read(KEY_ATTACHED_BLOCK_POS, BlockPos.CODEC).orElse(null);
            BlockPos nps = new BlockPos((int) x, (int) y, (int) z);

            if (ps == null || (!ps.equals(nps)))
            {
                NbtUtils.putPosCodec(tag, nps, KEY_ATTACHED_BLOCK_POS);
            }
        }

        // Leash-Knot fix (we can't fix the UUID part, unless the other Mob has the
        // *exact same* UUID in the Schematic World) -- "leash" 键 1.21.5 起为小写。
        BlockPos lp = tag.read(KEY_LEASH, BlockPos.CODEC).orElse(null);

        if (lp != null && !lp.equals(BlockPos.ZERO))
        {
            NbtUtils.putPosCodec(tag, new BlockPos(lp.getX() + offX, lp.getY() + offY, lp.getZ() + offZ), KEY_LEASH);
        }

        // Home Pos fix -- home_radius <= 0（含缺失）时刻意不修正（上游原样形态）。
        BlockPos hp = tag.read(KEY_HOME_POS, BlockPos.CODEC).orElse(null);

        if (hp != null && !hp.equals(BlockPos.ZERO))
        {
            int hr = tag.getIntOr(KEY_HOME_RADIUS, -1);

            if (hr > 0)
            {
                NbtUtils.putPosCodec(tag, new BlockPos(hp.getX() + offX, hp.getY() + offY, hp.getZ() + offZ), KEY_HOME_POS);
            }
        }
    }

    public static void rotateEntity(Entity entity, double x, double y, double z,
                                    Rotation rotationCombined, Mirror mirrorMain, Mirror mirrorSub)
    {
        float rotationYaw = entity.getYRot();

        if (mirrorMain != Mirror.NONE)         { rotationYaw = entity.mirror(mirrorMain); }
        if (mirrorSub != Mirror.NONE)          { rotationYaw = entity.mirror(mirrorSub); }
        if (rotationCombined != Rotation.NONE) { rotationYaw += entity.getYRot() - entity.rotate(rotationCombined); }

        entity.snapTo(x, y, z, rotationYaw, entity.getXRot());
        EntityUtils.setEntityRotations(entity, rotationYaw, entity.getXRot());
    }


    public static boolean shouldPasteBlock(BlockPos pos, PasteLayerBehavior layerBehavior, @Nullable LayerRange layerRange)
    {
        if (layerBehavior == PasteLayerBehavior.ALL || layerRange == null)
        {
            return true;
        }

        return layerRange.isPositionWithinRange(pos);
    }

    public static boolean shouldPasteEntity(Vec3 pos, PasteLayerBehavior layerBehavior, @Nullable LayerRange layerRange)
    {
        if (layerBehavior == PasteLayerBehavior.ALL || layerRange == null)
        {
            return true;
        }

        return layerRange.isPositionWithinRange((int) pos.x(), (int) pos.y(), (int) pos.z());
    }
}
