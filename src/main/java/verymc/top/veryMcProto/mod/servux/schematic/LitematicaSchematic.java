package verymc.top.veryMcProto.mod.servux.schematic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import verymc.top.veryMcProto.mod.servux.util.Log;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;
import verymc.top.veryMcProto.mod.servux.network.ServuxLitematicaHandler;
import verymc.top.veryMcProto.mod.servux.network.ServuxLitematicaPacket;
import verymc.top.veryMcProto.mod.servux.schematic.container.ILitematicaBlockStatePalette;
import verymc.top.veryMcProto.mod.servux.schematic.container.LitematicaBlockStateContainer;
import verymc.top.veryMcProto.mod.servux.schematic.placement.SchematicPlacement;
import verymc.top.veryMcProto.mod.servux.schematic.placement.SubRegionPlacement;
import verymc.top.veryMcProto.mod.servux.schematic.selection.AreaSelection;
import verymc.top.veryMcProto.mod.servux.schematic.selection.Box;
import verymc.top.veryMcProto.mod.servux.util.*;
import verymc.top.veryMcProto.mod.servux.util.data.Constants;
import verymc.top.veryMcProto.mod.servux.util.data.FileType;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtUtils;
import verymc.top.veryMcProto.mod.servux.util.nbt.NbtView;
import verymc.top.veryMcProto.mod.servux.util.position.PositionUtils;
import verymc.top.veryMcProto.mod.servux.schematic.transmit.SchematicBuffer;
import verymc.top.veryMcProto.mod.servux.schematic.transmit.SchematicBufferManager;

public class LitematicaSchematic
{
    public static final String FILE_EXTENSION = ".litematic";
    public static final int MINECRAFT_DATA_VERSION_1_12   = 1139; // MC 1.12
    public static final int MINECRAFT_DATA_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();
    public static final int SCHEMATIC_VERSION = 7;
    // This is basically a "sub-version" for the schematic version,
    // intended to help with possible data fix needs that are discovered.
    public static final int SCHEMATIC_VERSION_SUB = 1; // Bump to one after the sleeping entity position fix

    public final Map<String, LitematicaBlockStateContainer> blockContainers = new HashMap<>();
    public final Map<String, Map<BlockPos, CompoundTag>> tileEntities = new HashMap<>();
    public final Map<String, Map<BlockPos, ScheduledTick<@NotNull Block>>> pendingBlockTicks = new HashMap<>();
    public final Map<String, Map<BlockPos, ScheduledTick<@NotNull Fluid>>> pendingFluidTicks = new HashMap<>();
    public final Map<String, List<EntityInfo>> entities = new HashMap<>();
    public final Map<String, BlockPos> subRegionPositions = new HashMap<>();
    public final Map<String, BlockPos> subRegionSizes = new HashMap<>();
    public final SchematicMetadata metadata = new SchematicMetadata();
    private int totalBlocksReadFromWorld;
    @Nullable private final Path schematicFile;
    private final FileType schematicType;


    public LitematicaSchematic(CompoundTag nbtCompound) throws CommandSyntaxException
    {
        this.readFromNBT(nbtCompound, false);
        this.schematicFile = Path.of("/");
        this.schematicType = FileType.LITEMATICA_SCHEMATIC;
    }

    private LitematicaSchematic(@Nullable Path file)
    {
        this(file, FileType.LITEMATICA_SCHEMATIC);
    }

    private LitematicaSchematic(@Nullable Path file, FileType schematicType)
    {
        this.schematicFile = file;
        this.schematicType = schematicType;
    }

    @Nullable
    public Path getFile()
    {
        return this.schematicFile;
    }

    public Vec3i getTotalSize()
    {
        return this.metadata.getEnclosingSize();
    }

    public int getTotalBlocksReadFromWorld()
    {
        return this.totalBlocksReadFromWorld;
    }

    public SchematicMetadata getMetadata()
    {
        return this.metadata;
    }

    public int getSubRegionCount()
    {
        return this.blockContainers.size();
    }

    @Nullable
    public BlockPos getSubRegionPosition(String areaName)
    {
        return this.subRegionPositions.get(areaName);
    }

    public Map<String, BlockPos> getAreaPositions()
    {
        ImmutableMap.Builder<@NotNull String, @NotNull BlockPos> builder = ImmutableMap.builder();

        for (String name : this.subRegionPositions.keySet())
        {
            BlockPos pos = this.subRegionPositions.get(name);
            builder.put(name, pos);
        }

        return builder.build();
    }

    public Map<String, BlockPos> getAreaSizes()
    {
        ImmutableMap.Builder<@NotNull String, @NotNull BlockPos> builder = ImmutableMap.builder();

        for (String name : this.subRegionSizes.keySet())
        {
            BlockPos pos = this.subRegionSizes.get(name);
            builder.put(name, pos);
        }

        return builder.build();
    }

    @Nullable
    public BlockPos getAreaSize(String regionName)
    {
        return this.subRegionSizes.get(regionName);
    }

    public Map<String, Box> getAreas()
    {
        ImmutableMap.Builder<@NotNull String, @NotNull Box> builder = ImmutableMap.builder();

        for (String name : this.subRegionPositions.keySet())
        {
            BlockPos pos = this.subRegionPositions.get(name);
            BlockPos posEndRel = PositionUtils.getRelativeEndPositionFromAreaSize(this.subRegionSizes.get(name));
            Box box = new Box(pos, pos.offset(posEndRel), name);
            builder.put(name, box);
        }

        return builder.build();
    }

    @Nullable
    public static LitematicaSchematic createFromWorld(Level world, AreaSelection area, SchematicSaveInfo info,
                                                      String author)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return null;
    }

    public boolean placeToWorld(Level world, SchematicPlacement schematicPlacement, boolean notifyNeighbors)
    {
        return this.placeToWorld(world, schematicPlacement, notifyNeighbors, false);
    }

    public boolean placeToWorld(Level world, SchematicPlacement schematicPlacement, boolean notifyNeighbors, boolean ignoreEntities)
    {
        // TODO P6: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return false;
    }

    private boolean placeBlocksToWorld(Level world, BlockPos origin, BlockPos regionPos, BlockPos regionSize,
                                       SchematicPlacement schematicPlacement, SubRegionPlacement placement,
                                       LitematicaBlockStateContainer container, Map<BlockPos, CompoundTag> tileMap,
                                       @Nullable Map<BlockPos, ScheduledTick<@NotNull Block>> scheduledBlockTicks,
                                       @Nullable Map<BlockPos, ScheduledTick<@NotNull Fluid>> scheduledFluidTicks, boolean notifyNeighbors)
    {
        // TODO P6: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return false;
    }

    private void placeEntitiesToWorld(Level world, BlockPos origin, BlockPos regionPos, BlockPos regionSize, SchematicPlacement schematicPlacement, SubRegionPlacement placement, List<EntityInfo> entityList)
    {
        // TODO P6: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
    }

    private void takeEntitiesFromWorld(Level world, List<Box> boxes, BlockPos origin)
    {
        for (Box box : boxes)
        {
            net.minecraft.world.phys.AABB bb = PositionUtils.createEnclosingAABB(box.getPos1(), box.getPos2());
            BlockPos regionPosAbs = box.getPos1();
            List<EntityInfo> list = new ArrayList<>();
            List<Entity> entities = world.getEntities((Entity) null, bb, EntityUtils.NOT_PLAYER);

            for (Entity entity : entities)
            {
                NbtView view = NbtView.getWriter(world.registryAccess());

                entity.save(view.getWriter());
                CompoundTag tag = view.readNbt();
                Identifier id = EntityType.getKey(entity.getType());

                if (tag != null && id != null)
                {
                    Vec3 posVec = new Vec3(entity.getX() - regionPosAbs.getX(), entity.getY() - regionPosAbs.getY(), entity.getZ() - regionPosAbs.getZ());

                    tag.putString("id", id.toString());
//                    NbtUtils.writeEntityPositionToTag(posVec, tag);
                    NbtUtils.putVec3dCodec(tag, posVec, "Pos");
                    list.add(new EntityInfo(posVec, tag));
                }
            }

            this.entities.put(box.getName(), list);
        }
    }

    public void takeEntitiesFromWorldWithinChunk(Level world, int chunkX, int chunkZ,
                                                 ImmutableMap<@NotNull String, @NotNull IntBoundingBox> volumes, ImmutableMap<@NotNull String, @NotNull Box> boxes,
                                                 Set<UUID> existingEntities, BlockPos origin)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
    }

    @SuppressWarnings("unchecked")
    private void takeBlocksFromWorld(Level world, List<Box> boxes, SchematicSaveInfo info)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
    }

    private <T> void getTicksFromScheduler(Long2ObjectMap<LevelChunkTicks<@NotNull T>> chunkTickSchedulers,
                                           Map<BlockPos, ScheduledTick<@NotNull T>> outputMap,
                                           IntBoundingBox box,
                                           BlockPos minCorner,
                                           final long currentTick)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
    }

    private <T> void addRelativeTickToMap(Map<BlockPos, ScheduledTick<@NotNull T>> outputMap, ScheduledTick<T> tick,
                                          BlockPos minCorner, long currentTick)
    {
        BlockPos pos = tick.pos();
        BlockPos relativePos = new BlockPos(pos.getX() - minCorner.getX(),
                                            pos.getY() - minCorner.getY(),
                                            pos.getZ() - minCorner.getZ());

        ScheduledTick<@NotNull T> newTick = new ScheduledTick<>(tick.type(), relativePos, tick.triggerTick() - currentTick,
                                                                tick.priority(), tick.subTickOrder());

        outputMap.put(relativePos, newTick);
    }

    public static boolean isExposed(Level world, BlockPos pos)
    {
        for (Direction dir : Direction.values())
        {
            BlockPos posAdj = pos.relative(dir);
            BlockState stateAdj = world.getBlockState(posAdj);

            if (stateAdj.canOcclude() == false ||
                stateAdj.isFaceSturdy(world, posAdj, dir.getOpposite()) == false)
            {
                return true;
            }
        }

        return false;
    }

    public static boolean isGravityBlock(BlockState state)
    {
        return state.is(BlockTags.SAND) ||
               state.is(BlockTags.CONCRETE_POWDER) ||
               state.getBlock() == Blocks.GRAVEL;
    }

    public static boolean isGravityBlock(Level world, BlockPos pos)
    {
        return isGravityBlock(world.getBlockState(pos));
    }

    public static boolean supportsExposedBlocks(Level world, BlockPos pos)
    {
        BlockPos posUp = pos.relative(Direction.UP);
        BlockState stateUp = world.getBlockState(posUp);

        while (true)
        {
            if (needsSupportNonGravity(stateUp))
            {
                return true;
            }
            else if (isGravityBlock(stateUp))
            {
                if (isExposed(world, posUp))
                {
                    return true;
                }
            }
            else
            {
                break;
            }

            posUp = posUp.relative(Direction.UP);

            if (posUp.getY() >= world.getMaxY() + 1)
            {
                break;
            }

            stateUp = world.getBlockState(posUp);
        }

        return false;
    }

    public static boolean needsSupportNonGravity(BlockState state)
    {
        Block block = state.getBlock();

        return block == Blocks.REPEATER ||
               block == Blocks.COMPARATOR ||
               block == Blocks.SNOW ||
               block instanceof CarpetBlock; // Moss Carpet is not in the WOOL_CARPETS tag
    }

    public static boolean isSupport(Level world, BlockPos pos)
    {
        // This only needs to return true for blocks that are needed support for another block,
        // and that other block would possibly block visibility to this block, i.e. its side
        // facing this block position is a full opaque square.
        // Apparently there is no method that indicates blocks that need support...
        // so hard coding a bunch of stuff here it is then :<
        BlockPos posUp = pos.relative(Direction.UP);
        BlockState stateUp = world.getBlockState(posUp);

        if (needsSupportNonGravity(stateUp))
        {
            return true;
        }

        return isGravityBlock(stateUp) &&
                (isExposed(world, posUp) || supportsExposedBlocks(world, posUp));
    }

    private void setSubRegionPositions(List<Box> boxes, BlockPos areaOrigin)
    {
        for (Box box : boxes)
        {
            this.subRegionPositions.put(box.getName(), box.getPos1().subtract(areaOrigin));
        }
    }

    private void setSubRegionSizes(List<Box> boxes)
    {
        for (Box box : boxes)
        {
            this.subRegionSizes.put(box.getName(), box.getSize());
        }
    }

    @Nullable
    public LitematicaBlockStateContainer getSubRegionContainer(String regionName)
    {
        return this.blockContainers.get(regionName);
    }

    @Nullable
    public Map<BlockPos, CompoundTag> getBlockEntityMapForRegion(String regionName)
    {
        return this.tileEntities.get(regionName);
    }

    @Nullable
    public List<EntityInfo> getEntityListForRegion(String regionName)
    {
        return this.entities.get(regionName);
    }

    @Nullable
    public Map<BlockPos, ScheduledTick<@NotNull Block>> getScheduledBlockTicksForRegion(String regionName)
    {
        return this.pendingBlockTicks.get(regionName);
    }

    @Nullable
    public Map<BlockPos, ScheduledTick<@NotNull Fluid>> getScheduledFluidTicksForRegion(String regionName)
    {
        return this.pendingFluidTicks.get(regionName);
    }

    private CompoundTag writeToNBT()
    {
        CompoundTag nbt = new CompoundTag();

        nbt.putInt("MinecraftDataVersion", MINECRAFT_DATA_VERSION);
        nbt.putInt("Version", SCHEMATIC_VERSION);
        nbt.putInt("SubVersion", SCHEMATIC_VERSION_SUB);
        nbt.put("Metadata", this.metadata.writeToNBT());
        nbt.put("Regions", this.writeSubRegionsToNBT());

        return nbt;
    }

    private CompoundTag writeSubRegionsToNBT()
    {
        CompoundTag wrapper = new CompoundTag();

        if (this.blockContainers.isEmpty() == false)
        {
            for (String regionName : this.blockContainers.keySet())
            {
                LitematicaBlockStateContainer blockContainer = this.blockContainers.get(regionName);
                Map<BlockPos, CompoundTag> tileMap = this.tileEntities.get(regionName);
                List<EntityInfo> entityList = this.entities.get(regionName);
                Map<BlockPos, ScheduledTick<@NotNull Block>> pendingBlockTicks = this.pendingBlockTicks.get(regionName);
                Map<BlockPos, ScheduledTick<@NotNull Fluid>> pendingFluidTicks = this.pendingFluidTicks.get(regionName);

                CompoundTag tag = new CompoundTag();

                tag.put("BlockStatePalette", blockContainer.getPalette().writeToNBT());
                tag.put("BlockStates", new LongArrayTag(blockContainer.getBackingLongArray()));
                tag.put("TileEntities", this.writeTileEntitiesToNBT(tileMap));

                if (pendingBlockTicks != null)
                {
                    tag.put("PendingBlockTicks", this.writePendingTicksToNBT(pendingBlockTicks, BuiltInRegistries.BLOCK, "Block"));
                }

                if (pendingFluidTicks != null)
                {
                    tag.put("PendingFluidTicks", this.writePendingTicksToNBT(pendingFluidTicks, BuiltInRegistries.FLUID, "Fluid"));
                }

                // The entity list will not exist, if takeEntities is false when creating the schematic
                if (entityList != null)
                {
                    tag.put("Entities", this.writeEntitiesToNBT(entityList));
                }

                BlockPos pos = this.subRegionPositions.get(regionName);
                tag.put("Position", NbtUtils.createBlockPosTag(pos));

                pos = this.subRegionSizes.get(regionName);
                tag.put("Size", NbtUtils.createBlockPosTag(pos));

                wrapper.put(regionName, tag);
            }
        }

        return wrapper;
    }

    private ListTag writeEntitiesToNBT(List<EntityInfo> entityList)
    {
        ListTag tagList = new ListTag();

        if (entityList.isEmpty() == false)
        {
            for (EntityInfo info : entityList)
            {
                tagList.add(info.nbt);
            }
        }

        return tagList;
    }

    private <T> ListTag writePendingTicksToNBT(Map<BlockPos, ScheduledTick<@NotNull T>> tickMap, Registry<@NotNull T> registry, String tagName)
    {
        ListTag tagList = new ListTag();

        if (tickMap.isEmpty() == false)
        {
            for (ScheduledTick<T> entry : tickMap.values())
            {
                T target = entry.type();
                Identifier id = registry.getKey(target);

                if (id != null)
                {
                    CompoundTag tag = new CompoundTag();

                    tag.putString(tagName, id.toString());
                    tag.putInt("Priority", entry.priority().getValue());
                    tag.putLong("SubTick", entry.subTickOrder());
                    tag.putInt("Time", (int) entry.triggerTick());
                    tag.putInt("x", entry.pos().getX());
                    tag.putInt("y", entry.pos().getY());
                    tag.putInt("z", entry.pos().getZ());

                    tagList.add(tag);
                }
            }
        }

        return tagList;
    }

    private ListTag writeTileEntitiesToNBT(Map<BlockPos, CompoundTag> tileMap)
    {
        ListTag tagList = new ListTag();

        if (tileMap.isEmpty() == false)
        {
            tagList.addAll(tileMap.values());
        }

        return tagList;
    }

    public void sendTransmitFile(CompoundTag nbtIn, final long sessionKey, ServerPlayer player)
    {
        final Path file = this.getFile();
        final int bufferSize = SchematicBuffer.BUFFER_SIZE;
        final long totalBytes;
        final int totalSlices;
        try
        {
            totalBytes = Files.size(file);
            totalSlices = (int) ((totalBytes + bufferSize - 1) / bufferSize);
        }
        catch (java.io.IOException e)
        {
            Log.error("sendTransmitFile: Unable to read file size; {}", e.getLocalizedMessage());
            return;
        }

        final FileType sType = this.schematicType;
        final ServerPlayer target = player;
        final long sKey = sessionKey;
        final int tSlices = totalSlices;
        final long tBytes = totalBytes;
        final String fName = file.getFileName().toString();
        final CompoundTag placementData = nbtIn;
        final Path filePath = file;

        // 分 tick 发送：每 tick 一个包（Start / Data×N / End），避免 masa 客户端连续 PacketSplitter 流串台。
        new org.bukkit.scheduler.BukkitRunnable()
        {
            private byte[] buf = new byte[bufferSize];
            private java.io.InputStream is;
            private boolean startSent = false;

            private CompoundTag baseOutput()
            {
                CompoundTag out = new CompoundTag();
                out.putString("FileName", fName);
                out.store("FileType", FileType.CODEC, sType);
                out.putLong("SliceKey", sKey);
                out.putInt("TotalSlices", tSlices);
                out.putLong("TotalSize", tBytes);
                return out;
            }

            @Override
            public void run()
            {
                try
                {
                    if (!startSent)
                    {
                        startSent = true;
                        CompoundTag out = baseOutput();
                        out.putString("Task", "Litematic-TransmitStart");
                        if (placementData != null && !placementData.isEmpty())
                        {
                            out.put("PlacementData", placementData);
                        }
                        ServuxLitematicaHandler.getInstance().encodeServerData(target, ServuxLitematicaPacket.ResponseC2SStart(out));
                        is = Files.newInputStream(filePath);
                        return;
                    }

                    int bytesRead = is.read(buf, 0, bufferSize);

                    if (bytesRead == -1)
                    {
                        CompoundTag out = baseOutput();
                        out.putString("Task", "Litematic-TransmitEnd");
                        ServuxLitematicaHandler.getInstance().encodeServerData(target, ServuxLitematicaPacket.ResponseC2SStart(out));
                        is.close();
                        cancel();
                        return;
                    }

                    CompoundTag out = baseOutput();
                    out.putString("Task", "Litematic-TransmitData");
                    out.putInt("Slice", tSlices);
                    out.putInt("Size", bytesRead);
                    byte[] corrected = new byte[bytesRead];
                    System.arraycopy(buf, 0, corrected, 0, bytesRead);
                    out.putByteArray("Data", corrected);
                    ServuxLitematicaHandler.getInstance().encodeServerData(target, ServuxLitematicaPacket.ResponseC2SStart(out));
                }
                catch (Exception err)
                {
                    CompoundTag out = new CompoundTag();
                    out.putLong("SliceKey", sKey);
                    out.putString("Task", "Litematic-TransmitCancel");
                    ServuxLitematicaHandler.getInstance().encodeServerData(target, ServuxLitematicaPacket.ResponseC2SStart(out));
                    Log.error("sendTransmitFile: Exception reading file; {}", err.getLocalizedMessage());
                    try { if (is != null) is.close(); } catch (Exception ignore) {}
                    cancel();
                }
            }
        }.runTaskTimer(verymc.top.veryMcProto.Reference.plugin(), 0L, 1L);
    }

    public static @Nullable Pair<LitematicaSchematic, CompoundTag> receiveFileTransmit(CompoundTag nbt, ServerPlayer player)
    {
        SchematicBufferManager manager = LitematicsDataProvider.INSTANCE.getBufferManager();
        String task = nbt.getStringOr("Task", "");
        final long key = nbt.getLongOr("SliceKey", -1L);

        if (task.isEmpty() || key == -1L)
        {
            Log.error("receiveFileTransmit: Invalid sessionKey or Task received.");
            return null;
        }

        switch (task)
        {
            case "Litematic-TransmitStart" ->
            {
                FileType type = nbt.read("FileType", FileType.CODEC).orElse(FileType.LITEMATICA_SCHEMATIC);
                String name = nbt.getStringOr("FileName", "default_file");
                final int totalSlices = nbt.getIntOr("TotalSlices", 1);
                final long totalSize = nbt.getLongOr("TotalSize", -1L);
                manager.createBuffer(name, totalSlices, totalSize, type, key, nbt.getCompoundOrEmpty("PlacementData"), player);
            }
            case "Litematic-TransmitData" ->
            {
                final int slice = nbt.getIntOr("Slice", -1);
                final int size = nbt.getIntOr("Size", -1);
                final byte[] data = nbt.getByteArray("Data").orElse(new byte[0]);

                if (slice < 0 || size < 0 || data.length == 0)
                {
                    Log.error("receiveFileTransmit: Invalid Slice Data received for session key [{}]", key);
                    return null;
                }

                manager.receiveSlice(key, slice, data, size);
            }
            case "Litematic-TransmitCancel" ->
            {
                Log.warn("receiveFileTransmit: Cancel received for session key [{}]", key);
                manager.cancelBuffer(key);
            }
            case "Litematic-TransmitEnd" ->
            {
                final int totalSlices = nbt.getIntOr("TotalSlices", -1);
                final long totalSize = nbt.getLongOr("TotalSize", -1L);
                Path dir = LitematicsDataProvider.INSTANCE.getTransmitDir();
                CompoundTag optional = manager.getOptionalNbt(key);
                LitematicaSchematic schematic = manager.finishBuffer(key, dir);
                manager.removePlayer(player);

                if (schematic == null)
                {
                    Log.warn("receiveFileTransmit: Failed to create Schematic for finishing session key [{}]", key);
                    return null;
                }

                Log.debugLog("receiveFileTransmit: Received file {}, [tS: {}, tB: {}]", schematic.getFile().toAbsolutePath().toString(), totalSlices, totalSize);
                return Pair.of(schematic, optional);
            }
            default ->
            {
                Log.error("receiveFileTransmit: Invalid sessionKey or Task received.");
            }
        }

        return null;
    }

    private boolean readFromNBT(CompoundTag nbt, boolean enableFixers) throws CommandSyntaxException
    {
        this.blockContainers.clear();
        this.tileEntities.clear();
        this.entities.clear();
        this.pendingBlockTicks.clear();
        this.subRegionPositions.clear();
        this.subRegionSizes.clear();
        //this.metadata.clearModifiedSinceSaved();

        if (nbt.contains("Version"))
        {
            final int version = nbt.getIntOr("Version", -1);
            final int minecraftDataVersion = nbt.contains("MinecraftDataVersion") ? nbt.getIntOr("MinecraftDataVersion", MINECRAFT_DATA_VERSION_1_12) : SharedConstants.getCurrentVersion().dataVersion().version();

            if (version >= 1 && version <= SCHEMATIC_VERSION)
            {
                this.metadata.readFromNBT(nbt.getCompoundOrEmpty("Metadata"));
                this.metadata.setSchematicVersion(version);
                this.metadata.setMinecraftDataVersion(minecraftDataVersion);
                this.metadata.setFileType(FileType.LITEMATICA_SCHEMATIC);
                this.readSubRegionsFromNBT(nbt.getCompoundOrEmpty("Regions"), version, minecraftDataVersion, enableFixers);

                return true;
            }
            else
            {
                error("servux.litematics.error.schematic_load.unsupported_schematic_version");
            }
        }
        else
        {
            error("servux.litematics.error.schematic_load.no_schematic_version_information");
        }
        return false;
    }

    private void error(String s, Objects... objects) throws CommandSyntaxException
    {
        throw new SimpleCommandExceptionType(Component.translatable(s, (Object[]) objects)).create();
    }

    private void error(String s) throws CommandSyntaxException
    {
        throw new SimpleCommandExceptionType(Component.translatable(s)).create();
    }

    private void readSubRegionsFromNBT(CompoundTag tag, int version, int minecraftDataVersion, boolean enableFixers)
    {
        for (String regionName : tag.keySet())
        {
            if (tag.get(regionName).getId() == Constants.NBT.TAG_COMPOUND)
            {
                CompoundTag regionTag = tag.getCompoundOrEmpty(regionName);
                BlockPos regionPos = NbtUtils.readBlockPos(regionTag.getCompoundOrEmpty("Position"));
                BlockPos regionSize = NbtUtils.readBlockPos(regionTag.getCompoundOrEmpty("Size"));
                Map<BlockPos, CompoundTag> tiles = null;

                if (regionPos != null && regionSize != null)
                {
                    this.subRegionPositions.put(regionName, regionPos);
                    this.subRegionSizes.put(regionName, regionSize);

                    if (version >= 2)
                    {
                        tiles = this.readTileEntitiesFromNBT(regionTag.getListOrEmpty("TileEntities"));
                        if (enableFixers)
                        {
                            tiles = this.convertTileEntities_to_1_20_5(tiles, minecraftDataVersion);
                        }
                        this.tileEntities.put(regionName, tiles);

                        ListTag entities = regionTag.getListOrEmpty("Entities");
                        if (enableFixers)
                        {
                            entities = this.convertEntities_to_1_20_5(entities, minecraftDataVersion);
                        }
                        this.entities.put(regionName, this.readEntitiesFromNBT(entities));
                    }
                    else if (version == 1)
                    {
                        tiles = this.readTileEntitiesFromNBT_v1(regionTag.getListOrEmpty("TileEntities"));
                        this.tileEntities.put(regionName, tiles);
                        this.entities.put(regionName, this.readEntitiesFromNBT_v1(regionTag.getListOrEmpty("Entities")));
                    }

                    if (version >= 3)
                    {
                        ListTag list = regionTag.getListOrEmpty("PendingBlockTicks");
                        this.pendingBlockTicks.put(regionName, this.readPendingTicksFromNBT(list, BuiltInRegistries.BLOCK, "Block", Blocks.AIR));
                    }

                    if (version >= 5)
                    {
                        ListTag list = regionTag.getListOrEmpty("PendingFluidTicks");
                        this.pendingFluidTicks.put(regionName, this.readPendingTicksFromNBT(list, BuiltInRegistries.FLUID, "Fluid", Fluids.EMPTY));
                    }

                    Tag nbtBase = regionTag.get("BlockStates");

                    // There are no convenience methods in NBTTagCompound yet in 1.12, so we'll have to do it the ugly way...
                    if (nbtBase != null && nbtBase.getId() == Constants.NBT.TAG_LONG_ARRAY)
                    {
                        ListTag palette = regionTag.getListOrEmpty("BlockStatePalette");
                        long[] blockStateArr = ((LongArrayTag) nbtBase).getAsLongArray();

                        BlockPos posEndRel = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).offset(regionPos);
                        BlockPos posMin = PositionUtils.getMinCorner(regionPos, posEndRel);
                        BlockPos posMax = PositionUtils.getMaxCorner(regionPos, posEndRel);
                        BlockPos size = posMax.subtract(posMin).offset(1, 1, 1);

//                        palette = this.convertBlockStatePalette_1_12_to_1_13_2(palette, version, minecraftDataVersion);
                        if (enableFixers)
                        {
                            palette = this.convertBlockStatePalette_to_1_20_5(palette, minecraftDataVersion);
                        }

                        LitematicaBlockStateContainer container = LitematicaBlockStateContainer.createFrom(palette, blockStateArr, size);

                        if (minecraftDataVersion < MINECRAFT_DATA_VERSION && enableFixers)
                        {
                            this.postProcessContainerIfNeeded(palette, container, tiles);
                        }

                        this.blockContainers.put(regionName, container);
                    }
                }
            }
        }
    }

    public static boolean isSizeValid(@Nullable Vec3i size)
    {
        return size != null && size.getX() > 0 && size.getY() > 0 && size.getZ() > 0;
    }

    @Nullable
    private static Vec3i readSizeFromTagImpl(CompoundTag tag)
    {
        if (tag.contains("size"))
        {
            ListTag tagList = tag.getListOrEmpty("size");

            if (tagList.size() == 3)
            {
                return new Vec3i(tagList.getIntOr(0, 0), tagList.getIntOr(1, 0), tagList.getIntOr(2, 0));
            }
        }

        return null;
    }

    @Nullable
    public static BlockPos readBlockPosFromNbtList(CompoundTag tag, String tagName)
    {
        if (tag.contains(tagName))
        {
            ListTag tagList = tag.getListOrEmpty(tagName);

            if (tagList.size() == 3)
            {
                return new BlockPos(tagList.getIntOr(0, 0), tagList.getIntOr(1, 0), tagList.getIntOr(2, 0));
            }
        }

        return null;
    }

    protected boolean readPaletteFromLitematicaFormatTag(ListTag tagList, ILitematicaBlockStatePalette palette)
    {
        final int size = tagList.size();
        List<BlockState> list = new ArrayList<>(size);
        HolderGetter<@NotNull Block> lookup = DataProviderManager.INSTANCE.getRegistryManager().lookupOrThrow(Registries.BLOCK);

        for (int id = 0; id < size; ++id)
        {
            CompoundTag tag = tagList.getCompoundOrEmpty(id);
            BlockState state = net.minecraft.nbt.NbtUtils.readBlockState(lookup, tag);
            list.add(state);
        }

        return palette.setMapping(list);
    }

    public static boolean isValidSpongeSchematic(CompoundTag tag)
    {
        // v2 Sponge Schematic
        if (tag.contains("Width") &&
            tag.contains("Height") &&
            tag.contains("Length") &&
            tag.contains("Version") &&
            tag.contains("Palette") &&
            tag.contains("BlockData"))
        {
            return isSizeValid(readSizeFromTagSponge(tag));
        }

        return false;
    }

    public static boolean isValidSpongeSchematicv3(CompoundTag tag)
    {
        // v3 Sponge Schematic
        if (tag.contains("Schematic"))
        {
            CompoundTag nbtV3 = tag.getCompoundOrEmpty("Schematic");

            if (nbtV3.contains("Width") &&
                nbtV3.contains("Height") &&
                nbtV3.contains("Length") &&
                nbtV3.contains("Version") &&
                nbtV3.getIntOr("Version", -1) >= 3 &&
                nbtV3.contains("Blocks") &&
                nbtV3.contains("DataVersion"))
            {
                return isSizeValid(readSizeFromTagSponge(nbtV3));
            }
        }

        return false;
    }

    public static Vec3i readSizeFromTagSponge(CompoundTag tag)
    {
        return new Vec3i(tag.getIntOr("Width", 0), tag.getIntOr("Height", 0), tag.getIntOr("Length", 0));
    }

    protected boolean readSpongePaletteFromTag(CompoundTag tag, ILitematicaBlockStatePalette palette)
    {
        // TODO P-later: 桩化（sponge palette，malilib BlockUtils），原版 ORIGIN/schematic/LitematicaSchematic.java:1369
        return false;
    }

    protected boolean readSpongeBlocksFromTag(CompoundTag tag, String schematicName, Vec3i size, int minecraftDataVersion, int spongeVersion)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return false;
    }

    protected Map<BlockPos, CompoundTag> readSpongeBlockEntitiesFromTag(CompoundTag tag, int spongeVersion)
    {
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        String tagName = spongeVersion == 1 ? "TileEntities" : "BlockEntities";

        if (tag.contains(tagName) == false)
        {
            return blockEntities;
        }

        ListTag tagList = tag.getListOrEmpty(tagName);

        final int size = tagList.size();

        for (int i = 0; i < size; ++i)
        {
            CompoundTag beTag = tagList.getCompoundOrEmpty(i);
            BlockPos pos = NbtUtils.readBlockPosFromArrayTag(beTag, "Pos");

            if (pos != null && beTag.isEmpty() == false)
            {
                beTag.putString("id", beTag.getStringOr("Id", ""));

                // Remove the Sponge tags from the data that is kept in memory
                beTag.remove("Id");
                beTag.remove("Pos");

                if (spongeVersion == 1)
                {
                    beTag.remove("ContentVersion");
                }

                if (spongeVersion >= 3)
                {
                    CompoundTag beData = beTag.getCompoundOrEmpty("Data");
                    blockEntities.put(pos, beData);
                }
                else
                {
                    blockEntities.put(pos, beTag);
                }
            }
        }

        return blockEntities;
    }

    protected List<EntityInfo> readSpongeEntitiesFromTag(CompoundTag tag, Vec3i offset, int spongeVersion)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return java.util.List.of();
    }

    public boolean readFromSpongeSchematic(String name, CompoundTag tag)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return false;
    }

    public boolean readFromVanillaStructure(String name, CompoundTag tag)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return false;
    }

    protected List<EntityInfo> readEntitiesFromVanillaStructure(CompoundTag tag, int minecraftDataVersion)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return java.util.List.of();
    }

    @Nullable
    public static Vec3 readVec3dFromNbtList(@Nullable CompoundTag tag, String tagName)
    {
        if (tag != null && tag.contains(tagName))
        {
            ListTag tagList = tag.getListOrEmpty(tagName);

            if (tagList.getId() == Constants.NBT.TAG_DOUBLE && tagList.size() == 3)
            {
                return new Vec3(tagList.getDoubleOr(0, 0d), tagList.getDoubleOr(1, 0d), tagList.getDoubleOr(2, 0d));
            }
        }

        return null;
    }

    private void postProcessContainerIfNeeded(ListTag palette, LitematicaBlockStateContainer container, @Nullable Map<BlockPos, CompoundTag> tiles)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
    }

    public static List<BlockState> getStatesFromPaletteTag(ListTag palette)
    {
        List<BlockState> states = new ArrayList<>();
        HolderGetter<@NotNull Block> lookup = DataProviderManager.INSTANCE.getRegistryManager().lookupOrThrow(Registries.BLOCK);
        final int size = palette.size();

        for (int i = 0; i < size; ++i)
        {
            CompoundTag tag = palette.getCompoundOrEmpty(i);
            BlockState state = net.minecraft.nbt.NbtUtils.readBlockState(lookup, tag);

            if (i > 0 || state != LitematicaBlockStateContainer.AIR_BLOCK_STATE)
            {
                states.add(state);
            }
        }

        return states;
    }

    private List<EntityInfo> readEntitiesFromNBT(ListTag tagList)
    {
        List<EntityInfo> entityList = new ArrayList<>();
        final int size = tagList.size();

        for (int i = 0; i < size; ++i)
        {
            CompoundTag entityData = tagList.getCompoundOrEmpty(i);
//            Vec3d posVec = NbtUtils.readEntityPositionFromTag(entityData);
            Vec3 posVec = NbtUtils.getVec3dCodec(entityData, "Pos");

            if (posVec != null && entityData.isEmpty() == false)
            {
                entityList.add(new EntityInfo(posVec, entityData));
            }
        }

        return entityList;
    }

    private Map<BlockPos, CompoundTag> readTileEntitiesFromNBT(ListTag tagList)
    {
        Map<BlockPos, CompoundTag> tileMap = new HashMap<>();
        final int size = tagList.size();

        for (int i = 0; i < size; ++i)
        {
            CompoundTag tag = tagList.getCompoundOrEmpty(i);
            BlockPos pos = NbtUtils.readBlockPos(tag);

            if (pos != null && tag.isEmpty() == false)
            {
                tileMap.put(pos, tag);
            }
        }

        return tileMap;
    }

    private <T> Map<BlockPos, ScheduledTick<@NotNull T>> readPendingTicksFromNBT(ListTag tagList, Registry<@NotNull T> registry,
                                                                                 String tagName, T emptyValue)
    {
        Map<BlockPos, ScheduledTick<@NotNull T>> tickMap = new HashMap<>();
        final int size = tagList.size();

        for (int i = 0; i < size; ++i)
        {
            CompoundTag tag = tagList.getCompoundOrEmpty(i);

            if (tag.contains("Time")) // XXX these were accidentally saved as longs in version 3
            {
                T target = null;

                // Don't crash on invalid ResourceLocation in 1.13+
                try
                {
                    target = registry.getValue(Identifier.tryParse(tag.getStringOr(tagName, "")));

                    if (target == null || target == emptyValue)
                    {
                        continue;
                    }
                }
                catch (Exception ignore) {}

                if (target != null)
                {
                    BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
                    TickPriority priority = TickPriority.byValue(tag.getIntOr("Priority", 0));
                    // Note: the time is a relative delay at this point
                    int scheduledTime = tag.getIntOr("Time", 0);
                    long subTick = tag.getLongOr("SubTick", 0L);
                    tickMap.put(pos, new ScheduledTick<>(target, pos, scheduledTime, priority, subTick));
                }
            }
        }

        return tickMap;
    }

    private ListTag convertBlockStatePalette_to_1_20_5(ListTag oldPalette, int minecraftDataVersion)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return oldPalette;
    }

    private Map<BlockPos, CompoundTag> convertTileEntities_to_1_20_5(Map<BlockPos, CompoundTag> oldTE, int minecraftDataVersion)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return oldTE;
    }

    private ListTag convertEntities_to_1_20_5(ListTag oldEntitiesList, int minecraftDataVersion)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return oldEntitiesList;
    }

    private List<EntityInfo> convertSpongeEntities_to_1_20_5(List<EntityInfo> oldEntitiesList, int minecraftDataVersion)
    {
        // TODO P-later: 桩化（降级），原版 ORIGIN/schematic/LitematicaSchematic.java
        return oldEntitiesList;
    }

    private List<EntityInfo> readEntitiesFromNBT_v1(ListTag tagList)
    {
        List<EntityInfo> entityList = new ArrayList<>();
        final int size = tagList.size();

        for (int i = 0; i < size; ++i)
        {
            CompoundTag tag = tagList.getCompoundOrEmpty(i);
            Vec3 posVec = NbtUtils.readVec3d(tag);
//            Vec3d posVec = NbtUtils.getVec3dCodec(tag, "Pos");
            CompoundTag entityData = tag.getCompoundOrEmpty("EntityData");

            if (posVec != null && entityData.isEmpty() == false)
            {
                // Update the correct position to the TileEntity NBT, where it is stored in version 2
//                NbtUtils.writeEntityPositionToTag(posVec, entityData);
                NbtUtils.putVec3dCodec(entityData, posVec, "Pos");
                entityList.add(new EntityInfo(posVec, entityData));
            }
        }

        return entityList;
    }

    private Map<BlockPos, CompoundTag> readTileEntitiesFromNBT_v1(ListTag tagList)
    {
        Map<BlockPos, CompoundTag> tileMap = new HashMap<>();
        final int size = tagList.size();

        for (int i = 0; i < size; ++i)
        {
            CompoundTag tag = tagList.getCompoundOrEmpty(i);
            CompoundTag tileNbt = tag.getCompoundOrEmpty("TileNBT");

            // Note: This within-schematic relative position is not inside the tile tag!
            BlockPos pos = NbtUtils.readBlockPos(tag);

            if (pos != null && tileNbt.isEmpty() == false)
            {
                // Update the correct position to the entity NBT, where it is stored in version 2
                NbtUtils.writeBlockPosToTag(pos, tileNbt);
                tileMap.put(pos, tileNbt);
            }
        }

        return tileMap;
    }

    public boolean writeToFile(Path dir, String fileNameIn, boolean override)
    {
        return this.writeToFile(dir, fileNameIn, override, false);
    }

    public boolean writeToFile(Path dir, String fileNameIn, boolean override, boolean downgrade)
    {
        String fileName = fileNameIn;

        if (fileName.endsWith(FILE_EXTENSION) == false)
        {
            fileName = fileName + FILE_EXTENSION;
        }

        Path fileSchematic = dir.resolve(fileName);

        try
        {
            if (!Files.exists(dir))
            {
                Files.createDirectory(dir);
            }

            if (!Files.isDirectory(dir))
            {
                //InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.error.schematic_write_to_file_failed.directory_creation_failed", dir.toAbsolutePath());
                return false;
            }

            if (override == false && Files.exists(fileSchematic))
            {
                //InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.error.schematic_write_to_file_failed.exists", fileSchematic.toAbsolutePath());
                return false;
            }

            NbtUtils.writeCompressed(this.writeToNBT(), fileSchematic);

            return true;
        }
        catch (Exception e)
        {
            /*
            Litematica.LOGGER.error(StringUtils.translate("litematica.error.schematic_write_to_file_failed.exception", fileSchematic.toAbsolutePath()), e);
             */
        }

        return false;
    }


    public boolean readFromFile()
    {
        return this.readFromFile(this.schematicType);
    }

    private boolean readFromFile(FileType schematicType)
    {
        try
        {
            CompoundTag nbt = readNbtFromFile(this.schematicFile);

            if (nbt != null)
            {
                if (schematicType == FileType.SPONGE_SCHEMATIC)
                {
                    String name = this.schematicFile.getFileName().toString() + " (Converted Sponge)";
                    return this.readFromSpongeSchematic(name, nbt);
                }
                else if (schematicType == FileType.VANILLA_STRUCTURE)
                {
                    String name = this.schematicFile.getFileName().toString() + " (Converted Structure)";
                    return this.readFromVanillaStructure(name, nbt);
                }
                else if (schematicType == FileType.LITEMATICA_SCHEMATIC)
                {
                    return this.readFromNBT(nbt, true);
                }
            }
        }
        catch (Exception e)
        {
            //error("servux.litematics.error.schematic_read_from_file_failed.exception", this.schematicFile.toAbsolutePath());
        }

        return false;
    }

    public static CompoundTag readNbtFromFile(Path file)
    {
        if (file == null)
        {
            //error("servux.litematics.error.schematic_read_from_file_failed.no_file");
            return null;
        }

        if (Files.exists(file) == false || Files.isReadable(file) == false)
        {
            //error("servux.litematics.error.schematic_read_from_file_failed.cant_read", file.toAbsolutePath());
            return null;
        }

        return NbtUtils.readNbtFromFileAsPath(file);
    }

    public static Path fileFromDirAndName(Path dir, String fileName, FileType schematicType)
    {
        if (fileName.endsWith(FILE_EXTENSION) == false && schematicType == FileType.LITEMATICA_SCHEMATIC)
        {
            fileName = fileName + FILE_EXTENSION;
        }

        return dir.resolve(fileName);
    }

    @Nullable
    public static LitematicaSchematic createFromFile(Path dir, String fileName)
    {
        return createFromFile(dir, fileName, FileType.LITEMATICA_SCHEMATIC);
    }

    @Nullable
    public static LitematicaSchematic createFromFile(Path dir, String fileName, FileType schematicType)
    {
        Path file = fileFromDirAndName(dir, fileName, schematicType);
        LitematicaSchematic schematic = new LitematicaSchematic(file, schematicType);

        return schematic.readFromFile(schematicType) ? schematic : null;
    }

    public static class EntityInfo
    {
        public final Vec3 posVec;
        public final CompoundTag nbt;

        public EntityInfo(Vec3 posVec, CompoundTag nbt)
        {
            this.posVec = posVec;

            if (nbt.contains("SleepingX")) { nbt.putInt("SleepingX", Mth.floor(posVec.x)); }
            if (nbt.contains("SleepingY")) { nbt.putInt("SleepingY", Mth.floor(posVec.y)); }
            if (nbt.contains("SleepingZ")) { nbt.putInt("SleepingZ", Mth.floor(posVec.z)); }

            this.nbt = nbt;
        }
    }

    public static class SchematicSaveInfo
    {
        public final boolean visibleOnly;
        public final boolean includeSupportBlocks;
        public final boolean ignoreEntities;
        public final boolean fromSchematicWorld;

        public SchematicSaveInfo(boolean visibleOnly,
                                 boolean ignoreEntities)
        {
            this (visibleOnly, false, ignoreEntities, false);
        }

        public SchematicSaveInfo(boolean visibleOnly,
                                 boolean includeSupportBlocks,
                                 boolean ignoreEntities,
                                 boolean fromSchematicWorld)
        {
            this.visibleOnly = visibleOnly;
            this.includeSupportBlocks = includeSupportBlocks;
            this.ignoreEntities = ignoreEntities;
            this.fromSchematicWorld = fromSchematicWorld;
        }
    }
}
