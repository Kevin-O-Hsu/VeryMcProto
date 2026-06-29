# schematic-world 迁移笔记

> 模块: 投影主类 + 元数据 + Schema + 放置粘贴 + 文件传输协议
> 原版源码根: `OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/`
> 目标包根: `verymc.top.veryMcProto`
> 目标 MC: Paper **1.21.11** (paperweight userdev, Mojang 全映射 NMS), Java 21

---

## 文件清单表

| 原版文件(相对servux根) | 行数 | 建议目标包 | NMS依赖 | Mixin/AW依赖 | 迁移方式 |
|---|---|---|---|---|---|
| `schematic/LitematicaSchematic.java` | 2260 | `schematic/` | 重 (CompoundTag/ListTag/LongArrayTag/Level/ServerLevel/BlockState/BlockEntity/ScheduledTick/SharedConstants/BuiltInRegistries/Identifier/NbtUtils.readBlockState/Registries/HolderGetter/...) | 1 (IMixinWorldTickScheduler) | 适配 (去Fabric; ticks采集改反射/NMS直连; network收发改Paper messenger) |
| `schematic/SchematicMetadata.java` | 365 | `schematic/` | 轻 (CompoundTag/Vec3i/BlockPos) | 0 | 照抄(去Fabric注解) |
| `schematic/SchematicSchema.java` | 10 | `schematic/` | 无 | 0 | 照抄(纯record) |
| `schematic/placement/SchematicPlacement.java` | 603 | `schematic/placement/` | 轻 (BlockPos/Mirror/Rotation/ServerLevel/CompoundTag/ChunkPos) | 0 | 照抄+适配(pasteTo委托SchematicPlacingUtils) |
| `schematic/placement/SubRegionPlacement.java` | 215 | `schematic/placement/` | 中 (BlockPos/Mirror/Rotation/StreamCodec/Codec/ByteBufCodecs) | 0 | 照抄(CODEC/PACKET_CODEC照抄; 注意PACKET_CODEC当前未被发送路径实际使用) |
| `schematic/transmit/SchematicBuffer.java` | 153 | `schematic/transmit/` | 轻 (仅 FileType; NMS无) | 0 | 照抄 |
| `schematic/transmit/SchematicBufferManager.java` | 142 | `schematic/transmit/` | 中 (CompoundTag/ServerPlayer; 依赖 LitematicsDataProvider/TransmitDir) | 0 | 适配(去Fabric; 依赖LitematicsDataProvider) |
| **(关联)** `util/SchematicPlacingUtils.java` | 553 | `schematic/` 或 `util/` | 重 (ServerLevel/BlockState/BlockEntity/Container/ChestBlock/ChestType/Direction/Painting/ItemFrame/Display/LevelTicks/ScheduledTick/Vec3) | 1 (IWorldUpdateSuppressor via WorldUtils) | 适配(WorldUtils.setShouldPreventBlockUpdates需用反射替代; 镜像修复内联点在此) |
| **(关联)** `util/WorldUtils.java` | 16 | `schematic/` 或 `util/` | 中 (Level + IWorldUpdateSuppressor) | 1 (IWorldUpdateSuppressor) | 反射(替换Mixin接口为反射Level字段) |
| **(关联)** `util/data/FileType.java` | 93 | `schematic/` 或 `util/data/` | 轻 (StringRepresentable) | 0 | 照抄 |
| **(关联)** `network/packet/ServuxLitematicaPacket.java` | 522 | `network/p` | 中 (FriendlyByteBuf/CompoundTag/NbtAccounter/BlockPos/ChunkPos/StreamCodec/CustomPacketPayload) | 0 | 适配(toPacket/fromPacket照抄; Payload record去@Environment; CHANNEL_ID见下) |
| **(关联)** `network/packet/ServuxLitematicaHandler.java` | 226 | `network/` | 中 (ServerPlayNetworking/FriendlyByteBuf/Identifier/ServerPlayer) | 0 | 适配(ServerPlayNetworking→Paper Messenger + PacketSplitter) |

**核心 5 文件 (schematic/* + placement/*) 合计: 3596 行。** 加关联文件总计约 4836 行。
迁移方式分布（核心5文件）: 照抄 1 (SchematicSchema), 照抄+适配 3 (SchematicMetadata/SchematicPlacement/SubRegionPlacement), 适配 1 (LitematicaSchematic)。transmit 子包: 照抄 1, 适配 1。

---

## 逐文件详解

### LitematicaSchematic.java (2260行)

- **职责**: Litematica 投影主类。Region 组织（多 sub-region）、世界读取(take)、世界写入(place/paste)、NBT 读写（顶层+Region）、Litematic/Sponge/VanillaStructure 多格式、文件传输四阶段（send/receive）。

#### 关键字段（实例，全 public final Map）
- `Map<String, LitematicaBlockStateContainer> blockContainers` — 每个 Region 的方块容器（BitArray+Palette）
- `Map<String, Map<BlockPos, CompoundTag>> tileEntities` — 每个 Region 的方块实体 NBT（NMS `BlockPos`/`CompoundTag`）
- `Map<String, Map<BlockPos, ScheduledTick<Block>>> pendingBlockTicks` — 计划 tick（NMS `ScheduledTick<Block>`）
- `Map<String, Map<BlockPos, ScheduledTick<Fluid>>> pendingFluidTicks`
- `Map<String, List<EntityInfo>> entities` — 实体
- `Map<String, BlockPos> subRegionPositions` — Region 相对原点位置
- `Map<String, BlockPos> subRegionSizes` — Region 尺寸
- `SchematicMetadata metadata`
- `int totalBlocksReadFromWorld`
- `Path schematicFile` (nullable)
- `FileType schematicType`

静态常量:
- `String FILE_EXTENSION = ".litematic"`
- `int MINECRAFT_DATA_VERSION_1_12 = 1139`
- `int MINECRAFT_DATA_VERSION = SharedConstants.getCurrentVersion().dataVersion().version()` ← NMS
- `int SCHEMATIC_VERSION = 7` (顶层 `Version`)
- `int SCHEMATIC_VERSION_SUB = 1` (顶层 `SubVersion`)

#### 顶层 NBT 格式（`writeToNBT` / `readFromNBT`）
顶层 CompoundTag:
- `int "MinecraftDataVersion"`
- `int "Version"` (=7)
- `int "SubVersion"` (=1)
- `CompoundTag "Metadata"` ← SchematicMetadata.writeToNBT
- `CompoundTag "Regions"` ← writeSubRegionsToNBT（key=regionName）

Region NBT（`writeSubRegionsToNBT`, key=regionName）:
- `ListTag "BlockStatePalette"` = `blockContainer.getPalette().writeToNBT()`
- `LongArrayTag "BlockStates"` = `new LongArrayTag(container.getBackingLongArray())`
- `ListTag "TileEntities"` = tileMap.values() 直接 addAll（每条是完整 BE NBT，含 x/y/z）
- `ListTag "PendingBlockTicks"`（v≥3）: 每条 `{String "Block", int "Priority", long "SubTick", int "Time", int x/y/z}`
- `ListTag "PendingFluidTicks"`（v≥5）: 同上，key=`"Fluid"`
- `ListTag "Entities"`（可选）: 每条直接是 entity NBT（含 id/Pos）
- `CompoundTag "Position"` = `NbtUtils.createBlockPosTag(subRegionPositions)`
- `CompoundTag "Size"` = `NbtUtils.createBlockPosTag(subRegionSizes)`

读 Region（`readSubRegionsFromNBT`）按 version 分支: v≥2 用新格式 TileEntities/Entities；v==1 用 `_v1` 旧格式（TileNBT/EntityData 子tag）；v≥3 读 PendingBlockTicks；v≥5 读 PendingFluidTicks；BlockStates 必须是 LongArrayTag。Palette 经 `convertBlockStatePalette_to_1_20_5` 数据修复后 `LitematicaBlockStateContainer.createFrom(palette, blockStateArr, size)`。

#### 关键方法（签名 + 行号）
- `public LitematicaSchematic(CompoundTag nbtCompound) throws CommandSyntaxException` (L91) — 从 NBT 构造，调用 readFromNBT(nbt,false)
- `@Nullable static createFromWorld(Level world, AreaSelection area, SchematicSaveInfo info, String author)` (L189) — 从世界读：setSubRegionPositions/Sizes + takeBlocksFromWorld + (可选)takeEntitiesFromWorld + 填充 metadata
- `public boolean placeToWorld(Level world, SchematicPlacement sp, boolean notifyNeighbors[, boolean ignoreEntities])` (L229/L234) — 全量放置入口；`WorldUtils.setShouldPreventBlockUpdates(world,true)` 包裹；遍历 enabled SubRegionPlacement 调 placeBlocksToWorld/placeEntitiesToWorld
- `private boolean placeBlocksToWorld(...)` (L277) — **逐方块放置**；旋转/镜像组合逻辑；先清 Container、setBlock(barrier,0x14)、再 setBlock(state,0x12)、teNBT 写 x/y/z 后 `te.loadWithComponents(NbtView.getReader(teNBT, world.registryAccess()).getReader())`；**已注释掉 scheduledTicks 部署与 notifyNeighbors（约 L424-L470）**——注意：实际生效路径是 `SchematicPlacingUtils.placeToWorldWithinChunk`（见下），此 placeToWorld 是旧/备用路径
- `private void takeBlocksFromWorld(Level world, List<Box> boxes, SchematicSaveInfo info)` (L606) — 遍历 box，逐位置 `world.getBlockState` → container.set；hasBlockEntity → `te.saveWithFullMetadata(world.registryAccess())` + `NbtUtils.writeBlockPosToTag`；**ticks 采集**（L671-L683）:`((IMixinWorldTickScheduler<Block>) serverWorld.getBlockTicks()).servux_getChunkTickSchedulers()` ← **Mixin 接口**，调 `getTicksFromScheduler` 过滤 IntBoundingBox 内 tick → 相对坐标 + `triggerTick() - currentTick`
- `private <T> void getTicksFromScheduler(Long2ObjectMap<LevelChunkTicks<T>>, Map, IntBoundingBox, BlockPos minCorner, long currentTick)` (L692)
- `private <T> void addRelativeTickToMap(...)` (L721) — `new ScheduledTick<>(type, relativePos, triggerTick-currentTick, priority, subTickOrder)`
- `public void sendTransmitFile(CompoundTag nbtIn, long sessionKey, ServerPlayer player)` (L995) — **服务端→客户端文件下发四阶段**（见协议字节布局）
- `static Pair<LitematicaSchematic,CompoundTag> receiveFileTransmit(CompoundTag nbt, ServerPlayer player)` (L1072) — **服务端接收客户端上传的四阶段**（见协议字节布局）
- `private boolean readFromNBT(CompoundTag, boolean enableFixers)` (L1142) — 校验 Version∈[1,7]，读 Metadata/Regions
- `private void readSubRegionsFromNBT(...)` (L1189)
- `protected boolean readPaletteFromLitematicaFormatTag(ListTag, ILitematicaBlockStatePalette)` (L1310) — `DataProviderManager.INSTANCE.getRegistryManager().lookupOrThrow(Registries.BLOCK)` + `net.minecraft.nbt.NbtUtils.readBlockState(lookup, tag)`
- `boolean readFromSpongeSchematic(String, CompoundTag)` (L1566) / `readFromVanillaStructure(String, CompoundTag)` (L1642) — 格式转换（数据修复走 SchematicConversionMaps）
- `boolean writeToFile(Path dir, String name, boolean override[, boolean downgrade])` (L2090) — `NbtUtils.writeCompressed(writeToNBT(), file)`
- `boolean readFromFile()` (L2140) / `readFromFile(FileType)` (L2145)
- `static LitematicaSchematic createFromFile(Path dir, String name[, FileType])` (L2204)
- 内部 record/class: `EntityInfo` (L2219: `Vec3 posVec; CompoundTag nbt`; 构造时若含 SleepingX/Y/Z 则 floor 覆盖); `SchematicSaveInfo` (L2236: visibleOnly/includeSupportBlocks/ignoreEntities/fromSchematicWorld)

#### NMS 依赖（LitematicaSchematic）
- `net.minecraft.SharedConstants` (.getCurrentVersion().dataVersion().version())
- `net.minecraft.nbt.{CompoundTag, ListTag, LongArrayTag, Tag}` + 静态 `net.minecraft.nbt.NbtUtils.readBlockState`
- `net.minecraft.core.{BlockPos, Vec3i, SectionPos, HolderGetter, Direction}`
- `net.minecraft.core.registries.{BuiltInRegistries, Registries}` / `net.minecraft.core.Registry`
- `net.minecraft.resources.Identifier` (=ResourceLocation)
- `net.minecraft.server.level.ServerLevel` / `ServerPlayer`
- `net.minecraft.world.level.{Level, ChunkPos}` / `block.state.BlockState` / `block.entity.BlockEntity` / `block.{Blocks, Mirror, Rotation}` / `tags.BlockTags`
- `net.minecraft.world.{Container}` / `entity.{Entity, EntityType, decoration.HangingEntity}` / `phys.Vec3`
- `net.minecraft.world.ticks.{LevelChunkTicks, ScheduledTick, TickPriority}`
- `net.minecraft.network.chat.Component`
- `com.mojang.brigadier.exceptions.{CommandSyntaxException, SimpleCommandExceptionType}`

#### Mixin/Accessor 依赖
- `IMixinWorldTickScheduler<T>` (字段访问: `servux_getChunkTickSchedulers()`) ← **反射替代**: Paper 上需反射访问 `ServerLevel.blockTicks`/`fluidTicks` 内部的 chunk→LevelChunkTicks 映射。**这是 ticks 采集的唯一 Mixin 点**，纯 Paper API 无法触达，必须反射。

#### 迁移方式: 适配
- 主体算法（Region 组织、NBT 编解码、palette/tick/entity 读写）**照抄**（去 Fabric import 即可）。
- **网络收发**: `ServuxLitematicaHandler.getInstance().encodeServerData(...)` → Paper `Messenger`/`player.connection.send(ClientboundCustomPayloadPacket)`；`sendTransmitFile` 的 S2C 大包（单 slice=16KiB 但聚合后经 PacketSplitter）若走 plugin messaging 需确保总包 ≤ 32KiB 或改走 NMS 直发（见协议布局风险点）。
- **ticks 采集** Mixin → 反射 `ServerLevel` tick schedulers。
- `placeToWorld` 旧路径的 scheduledTicks 部署块已注释，主用 `SchematicPlacingUtils`。
- `RegistryAccess` 依赖: `world.registryAccess()` 用于 BE `saveWithFullMetadata` / `loadWithComponents`（NbtView）；`DataProviderManager.INSTANCE.getRegistryManager()` 用于 palette 解析（Paper 上换成 `Bukkit.getServer()`/level 的 RegistryAccess）。

#### 风险点
- `CompoundTag` Optional 化: 此文件大量用 `getStringOr/getIntOr/getLongOr/getCompoundOrEmpty/getListOrEmpty/getBooleanOr/getByteArray/getIntArray`（已是 1.21 风格，照抄即可）；注意 `putXxx` 返回 void 非链式。
- `tag.get(regionName).getId() == Constants.NBT.TAG_COMPOUND` / `nbtBase.getId() == TAG_LONG_ARRAY`：1.21 `Tag.getId()` 仍可用。
- `ScheduledTick` 构造签名 `(T type, BlockPos pos, long triggerTick, TickPriority priority, long subTickOrder)` — 照抄，注意 1.21 是 long triggerTick。
- `Identifier.tryParse` / `EntityType.getKey` — 照抄。

---

### SchematicMetadata.java (365行)

- **职责**: 投影元数据（Name/Author/Description/EnclosingSize/时间戳/版本/RegionCount/TotalVolume/TotalBlocks/缩略图）。

#### 关键字段
- `String name="?"`, `author="?"`, `description=""`
- `Vec3i enclosingSize=Vec3i.ZERO` (NMS)
- `long timeCreated=0`, `timeModified=0`
- `int minecraftDataVersion`, `schematicVersion`
- `Schema schema`, `FileType type`
- `int regionCount=0`, `entityCount=0`, `blockEntityCount=0`
- `int totalVolume=-1`, `totalBlocks=-1`
- `boolean modifiedSinceSaved=false`
- `int[] thumbnailPixelData` (nullable)

#### NBT 格式（`writeToNBT`/`readFromNBT`）
写入: `Name`,`Author`,`Description`(String); `RegionCount`,`TotalVolume`,`TotalBlocks`(int, 有条件); `TimeCreated`,`TimeModified`(long); `EnclosingSize`=CompoundTag(BlockPos tag via NbtUtils.createBlockPosTag(Vec3i)); `PreviewImageData`(int[], 可选)。
读取: 用 `getStringOr/getIntOr/getLongOr`；`EnclosingSize` 走 `NbtUtils.readVec3iFromTag`。

#### 关键方法
- getter/setter 全套（照抄）
- `setMinecraftDataVersion(int)` (L239) — 同时 `Schema.getSchemaByDataVersion(...)`
- `copyFrom(SchematicMetadata)` (L255)
- `CompoundTag writeToNBT()` (L284) / `void readFromNBT(CompoundTag)` (L327)
- `getEnclosingSizeAsBlockPos()` (L87) — `new BlockPos(Vec3i)`

#### NMS 依赖
- `net.minecraft.core.{BlockPos, Vec3i}` / `net.minecraft.nbt.CompoundTag`

#### 迁移方式: 照抄（去 Fabric 注解）
无 Mixin。NbtUtils 依赖（createBlockPosTag/readVec3iFromTag）需配套迁移 util/nbt 包。低风险。

---

### SchematicSchema.java (10行)

```java
public record SchematicSchema(int litematicVersion, int minecraftDataVersion) {
    @Override public String toString() { return "V" + litematicVersion() + " / DataVersion " + minecraftDataVersion(); }
}
```
- **职责**: 仅打印用的版本信息 record。
- **NMS 依赖**: 无。
- **迁移方式: 照抄**（原样复制，零改动）。

---

### SchematicPlacement.java (603行)

- **职责**: 整个投影在世界中的放置（原点+全局旋转/镜像+各 SubRegionPlacement）。提供 chunk→region 查询、box 计算、以及**粘贴入口 `pasteTo`**。

#### 关键字段
- `static Set<Integer> USED_COLORS`
- `Map<String, SubRegionPlacement> relativeSubRegionPlacements`
- `int subRegionCount`
- `LitematicaSchematic schematic`
- `BlockPos origin`, `String name`
- `Rotation rotation=Rotation.NONE`, `Mirror mirror=Mirror.NONE` (NMS)
- `boolean ignoreEntities`, `regionPlacementsModified`
- `int coordinateLockMask`
- `Box enclosingBox` (nullable)

#### 关键方法
- `static createFor(LitematicaSchematic, BlockPos origin, String name, boolean ignoreEntities)` (L49)
- `static createFromNbt(CompoundTag tags)` (L57) — **从 NBT 重建**（内含 `new LitematicaSchematic(tags.getCompoundOrEmpty("Schematics"))`）；读 `Origin`(readBlockPosFromIntArray)、`Name`、`Mirror`=Mirror.values()[int]、`Rotation`=Rotation.values()[int]；遍历 `SubRegions` 子 map 读各 SubRegionPlacement。**注意**: 有第二个重载 `createFromNbt(LitematicaSchematic schematic, CompoundTag)` (L89) 逻辑相同但不内嵌 schematic。
- `ImmutableMap getEnabledRelativeSubRegionPlacements()` (L189)
- `ImmutableMap<String,Box> getSubRegionBoxes(RequiredEnabled)` (L252) — 关键 box 计算: `getTransformedBlockPos(boxOriginRelative, this.mirror, this.rotation).offset(origin)` + pos2 双重 transform
- `Set<String> getRegionsTouchingChunk(int chunkX, int chunkZ)` (L317)
- `ImmutableMap<String,IntBoundingBox> getBoxesWithinChunk(...)` (L345)
- `IntBoundingBox getBoxWithinChunkForRegion(String, int chunkX, int chunkZ)` (L352)
- `Set<ChunkPos> getTouchedChunks()` (L358) / `getTouchedChunksForRegion` (L363)
- `void pasteTo(ServerLevel serverWorld, ReplaceBehavior replaceBehavior, PasteLayerBehavior layerBehavior, @Nullable LayerRange layerRange)` (L587) — **粘贴主入口**: `getEnclosingBox().toVanilla().intersectingChunks().forEach(cp -> SchematicPlacingUtils.placeToWorldWithinChunk(serverWorld, cp, this, replace, layerBehavior, layerRange, false))`

#### NMS 依赖
- `net.minecraft.core.BlockPos`, `net.minecraft.server.level.ServerLevel`, `net.minecraft.world.level.{ChunkPos}`, `net.minecraft.world.level.block.{Mirror, Rotation}`, `net.minecraft.nbt.CompoundTag`

#### 迁移方式: 照抄 + 适配
- NBT 读写、box 计算照抄。
- `pasteTo` 委托 `SchematicPlacingUtils`（必须一起迁移）。
- `createFromNbt(CompoundTag)` 内嵌构造 `LitematicaSchematic` 抛 `CommandSyntaxException` 被 catch 转 RuntimeException。
- 依赖: `PositionUtils`(util)、`IntBoundingBox`(util)、`Box`(selection)、`NbtUtils`、`ReplaceBehavior`/`PasteLayerBehavior`/`LayerRange`(util)。
- 注释里大量"// Marks the currently touched chunks"留空（原 Litematica 客户端的 PlacementManager 钩子），Paper 端可省略。

#### 风险点
- `Rotation.values()[tags.getIntOr("Rotation",0)]` / `Mirror.values()[...]` 序号映射必须与原版 enum 顺序一致（照抄即可，NMS enum 顺序稳定）。

---

### SubRegionPlacement.java (215行)

- **职责**: 单个 sub-region 的放置（相对 pos + 自身 rotation/mirror + enabled/ignoreEntities）。含 Codec + StreamCodec。

#### 关键字段
- `String name`, `BlockPos defaultPos`(final), `BlockPos pos`
- `Rotation rotation=Rotation.NONE`, `Mirror mirror=Mirror.NONE` (NMS, public)
- `boolean enabled=true` (public), `boolean renderingEnabled=false`
- `boolean ignoreEntities` (public), `int coordinateLockMask`

#### Codec / StreamCodec
- `Codec<SubRegionPlacement> CODEC` (L22, RecordCodecBuilder): fields `Name`(STRING), `DefaultPos`(BlockPos.CODEC), `Pos`(BlockPos.CODEC), `Rotation`(Rotation.CODEC), `Mirror`(Mirror.CODEC), `Enabled`/`RenderingEnabled`/`IgnoreEntities`(BOOL), `CoordinateLockMask`(INT)
- `StreamCodec<ByteBuf, Mirror> BLOCK_MIRROR_PACKET_CODEC` = `ByteBufCodecs.STRING_UTF8.map(Mirror::valueOf, Mirror::getSerializedName)`
- `StreamCodec<ByteBuf, SubRegionPlacement> PACKET_CODEC` (L36): encode/decode 顺序 = Name(UTF8) → DefaultPos(STREAM_CODEC) → Pos(STREAM_CODEC) → Rotation(STREAM_CODEC) → Mirror(BLOCK_MIRROR_PACKET_CODEC) → Enabled(BOOL) → RenderingEnabled(BOOL) → IgnoreEntities(BOOL) → CoordinateLockMask(INT)
  - **注意**: 该 PACKET_CODEC 定义存在，但发送路径实际未使用它（网络走 ServuxLitematicaPacket 的 CompoundTag）。保留以备 / 数据完整性。

#### 关键方法
- `SubRegionPlacement(BlockPos pos, String name)` (L79) — pos=defaultPos
- private 全参构造 (L86) — 供 Codec/StreamCodec
- `matchesRequirement(RequiredEnabled)` (L108) — 服务端不支持 RENDERING_ENABLED（日志 warn 返回 false）
- `isRegionPlacementModified(BlockPos originalPosition)` (L178)
- `JsonObject toJson()` (L187) — pos/name/rotation/mirror/locked_coords/enabled/rendering_enabled/ignore_entities
- `enum RequiredEnabled { ANY, PLACEMENT_ENABLED, RENDERING_ENABLED }` (L209)

#### NMS 依赖
- `net.minecraft.core.BlockPos`, `net.minecraft.world.level.block.{Mirror, Rotation}`
- `net.minecraft.network.codec.{ByteBufCodecs, StreamCodec}`, `io.netty.buffer.ByteBuf`
- `com.mojang.serialization.Codec` + `codecs.{PrimitiveCodec, RecordCodecBuilder}`

#### 迁移方式: 照抄（去 Fabric import）
Codec/StreamCodec 在 Paper paperweight userdev 下可直接引用（NMS）。`Rotation.CODEC`/`BlockPos.CODEC`/`Rotation.STREAM_CODEC`/`BlockPos.STREAM_CODEC` 均为 NMS 静态字段，照抄。

---

### SchematicBuffer.java (153行) — 传输协议核心

- **职责**: 单次文件传输的服务端内存缓冲（按 slice 序号收齐后写盘）。**纯算法，零 NMS（仅依赖 FileType）**。

#### 关键字段/常量
- `static final int BUFFER_SIZE = 16384` (16 KiB 分片)
- `String name`, `FileType type`
- `Slice[] buffer` (按序号)
- `int totalExpectedSlices`, `long totalExpectedSize`
- `AtomicInteger receivedSlices`

#### 关键方法
- `receiveSlice(int number, Slice)` (L60) — 仅当 slot 为 null 才填充并 increment（去重/幂等）
- `boolean isComplete()` = `receivedSlices == totalExpectedSlices`
- `Path writeFile(Path dir)` (L77) — **顺序写盘**（按 buffer 数组顺序写每个 slice.data[0..size]）；写后校验 `Files.size == totalExpectedSize`，不符则删除并返回 null；成功后 `buffer=null`
- `record Slice(byte[] data, int size)` (L152)
- `Path getFileName()` (L46) — name 不含扩展名则加 `FileType.getFileExt(type)`

#### NMS 依赖: 无（仅 java.nio + FileType + AtomicInteger）

#### 迁移方式: 照抄
零改动。**这是迁移最干净的类**。

#### 风险点
- `BUFFER_SIZE=16384` 与 Bukkit plugin messaging 32KiB 上限: 单 slice 16KiB + NBT 包装 + VarInt 头通常 < 32KiB，但**经 PacketSplitter 聚合的大 NBT 包**仍可能超限（见下协议风险）。注意 SchematicBuffer 的 16KiB 是应用层文件分片，与 PacketSplitter 的网络分片是两层。

---

### SchematicBufferManager.java (142行)

- **职责**: 按 sessionKey(long) 管理多个 SchematicBuffer + 可选 NBT(PlacementData) + player↔session 映射；finish 时写盘并 createFromFile 回读成 LitematicaSchematic。

#### 关键字段
- `ConcurrentHashMap<Long, SchematicBuffer> fileBuffers`
- `ConcurrentHashMap<Long, CompoundTag> optionalNbt`
- `ConcurrentHashMap<UUID, Long> playerMap`
- 构造: `new ConcurrentHashMap<>(16, 0.9f, 1)`

#### 关键方法
- `createBuffer(name, totalSlices, totalSize, type, sessionKey, optional, player)` (L39) — 防重复 key；`optional.copy()` 存；playerMap.put(uuid, key)
- `CompoundTag getOptionalNbt(long key)` (L68) — 无则空 CompoundTag
- `receiveSlice(long key, int slice, byte[] data, int size)` (L78)
- `cancelBuffer(long key)` (L90) — remove buffer + optionalNbt
- `removePlayer(ServerPlayer)` (L104) — 查 key→cancelBuffer→remove player
- `LitematicaSchematic finishBuffer(long key, Path dir)` (L116) — dir null 时用 `LitematicsDataProvider.INSTANCE.getTransmitDir()`；`buffer.writeFile(dir)`；`LitematicaSchematic.createFromFile(dir, buffer.getName(), buffer.getType())`；cancelBuffer

#### NMS 依赖
- `net.minecraft.nbt.CompoundTag`, `net.minecraft.server.level.ServerPlayer`
- 依赖: `LitematicsDataProvider.INSTANCE`（getTransmitDir）、`LitematicaSchematic`

#### 迁移方式: 适配
- 去 Fabric。`LitematicsDataProvider` 需迁移（提供 transmitDir 与 bufferManager 单例）。
- `ServerPlayer` → Paper `Player`/NMS `ServerPlayer`（取 UUID 可用 Bukkit Player.getUniqueId()）。
- 其余照抄。

#### 风险点
- 线程模型: 用 ConcurrentHashMap 但 receiveSlice 路径在 netty 线程，Paper 上 plugin messaging 收包在主线程，需确认一致性。`sessionKey` 由客户端 generate（TransmitStart 携带）或服务端 generate（C2S 上传时由 Handler 的 `readingSessionKeys` 生成）——两套 key 体系要分清（见协议字节布局）。

---

## 文件传输四阶段协议（TransmitStart / Data / End / Cancel）

> 这是本模块的协议命门。Servux 用 `ServuxLitematicaPacket` + `PacketSplitter` 把大 CompoundTag 切片下发；CompoundTag 内的 `Task` 字段区分四阶段。

### 传输载体
- 通道: `servux:litematic_data`（**ServuxLitematicaHandler.CHANNEL_ID = Identifier("servux","litematics")**，PROTOCOL_VERSION = 1；但 CLAUDE.md 标注 litematic 通道为 `servux:litematic_data`——以原版 Handler L43 `Identifier.fromNamespaceAndPath("servux","litematics")` 为准，**迁移时核对实际通道名**）
- Payload record: `ServuxLitematicaPacket.Payload implements CustomPacketPayload`（L501）；`ID = new Type<>(CHANNEL_ID)`；`CODEC = CustomPacketPayload.codec(Payload::write, Payload::new)`
- PacketSplitter: S2C/C2S 分片常量需按 CLAUDE.md 改 ≤32000（若全程 plugin messaging）

### 四阶段 Task 字段（CompoundTag 内）

**A. 服务端→客户端下发（sendTransmitFile, L995-1070）** —— 把服务端 .litematic 文件发给客户端:
1. **Litematic-TransmitStart** (L1014): `{Task:"Litematic-TransmitStart", FileName:String, FileType:FileType.CODEC.store, SliceKey:long(sessionKey), TotalSlices:int, TotalSize:long, [PlacementData:CompoundTag 可选]}`
2. **Litematic-TransmitData** (L1036, 循环): 每片 `{Task:"Litematic-TransmitData", SliceKey:long, Slice:int(=totalSlices), Size:int(bytesRead), Data:byte[]}`
3. **Litematic-TransmitEnd** (L1068): `{Task:"Litematic-TransmitEnd", SliceKey:long}`（移除 Slice/Size/Data）
4. **Litematic-TransmitCancel** (异常, L1057): `{Task:"Litematic-TransmitCancel", SliceKey:long}`

注意: sendTransmitFile 全程复用同一个 `output` CompoundTag（remove/put 复位），经 `ServuxLitematicaHandler.encodeServerData(player, ResponseC2SStart(output))` 发出——**所有阶段包都走 `ResponseC2SStart` 类型**(即 PACKET_C2S_NBT_RESPONSE_START=12)！这是个命名陷阱: 方法名叫 ResponseC2SStart 但用于所有四阶段下发。Handler 对 PACKET_S2C_NBT_RESPONSE_START 走 PacketSplitter.send（L194-199），对其他类型直接 sendPlayPayload。

**B. 客户端→服务端上传（receiveFileTransmit, L1072-1140）** —— 接收客户端 .litematic:
1. **Litematic-TransmitStart**: 读 FileType/FileName/TotalSlices/TotalSize → `manager.createBuffer(name, totalSlices, totalSize, type, key, PlacementData, player)`
2. **Litematic-TransmitData**: `{Slice:int, Size:int, Data:byte[]}` → `manager.receiveSlice(key, slice, data, size)`
3. **Litematic-TransmitCancel**: `manager.cancelBuffer(key)`
4. **Litematic-TransmitEnd**: `manager.finishBuffer(key, transmitDir)` → 返回 `Pair<LitematicaSchematic, optionalNbt>`，成功后 `handleClientPasteRequestPair`

**关键: C2S 上传的 sessionKey 来源**——`ServuxLitematicaHandler.decodeServerData` (L88-126) 在收到 `PACKET_C2S_NBT_RESPONSE_DATA` 时，**服务端**用 `RandomSource.create(Util.getMillis()).nextLong()` 为该 player 生成 readingSessionKey（存 `readingSessionKeys`），用 `PacketSplitter.receive(this, readingSessionKey, buffer)` 重组大包，重组完成后 `readVarInt()`(=type) + `readNbt()`(=CompoundTag) → `handleBulkData(player, type, nbt)` → 按 nbt.getString("Task") 分发到 `LitematicaSchematic.receiveFileTransmit`。**即: C2S 文件分片走 PacketSplitter 二次重组**（外层是 PacketSplitter 切的网络包，内层 Task 是应用层文件分片 Litematic-TransmitData 的 Slice）。注意此处的 key 与 nbt 里的 SliceKey 是两个层次。

### 协议字节布局（ServuxLitematicaPacket.toPacket/fromPacket）
所有包开头: `VarInt packetType` (Type 枚举值)。然后按类型:
- `PACKET_C2S_METADATA_REQUEST`(2) / `PACKET_S2C_METADATA`(1): `writeNbt(CompoundTag)` → 即 Litematic 四阶段 Task 包作为 Metadata 类 NBT 载荷下发（**实际 sendTransmitFile 用 ResponseC2SStart=12 走 Splitter，而非直接 Metadata**）
- `PACKET_C2S_BLOCK_ENTITY_REQUEST`(3): `VarInt transactionId`(旧兼容), `BlockPos pos`
- `PACKET_C2S_ENTITY_REQUEST`(4): `VarInt transactionId`, `VarInt entityId`
- `PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE`(5): `BlockPos`, `Nbt`
- `PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE`(6): `VarInt entityId`, `Nbt`
- `PACKET_C2S_BULK_ENTITY_NBT_REQUEST`(7): `ChunkPos`, `Nbt`
- `PACKET_S2C_NBT_RESPONSE_DATA`(11) / `PACKET_C2S_NBT_RESPONSE_DATA`(13): `writeBytes(buffer)` (裸字节 slice，无长度前缀，读端 `readBytes(readableBytes())`)
- `PACKET_S2C_NBT_RESPONSE_START`(10) / `PACKET_C2S_NBT_RESPONSE_START`(12): `writeNbt(nbt)`（**走 PacketSplitter**）

Type 枚举值（必须照抄，client 依赖）: S2C_METADATA=1, C2S_METADATA_REQUEST=2, C2S_BLOCK_ENTITY_REQUEST=3, C2S_ENTITY_REQUEST=4, S2C_BLOCK_NBT_RESPONSE_SIMPLE=5, S2C_ENTITY_NBT_RESPONSE_SIMPLE=6, C2S_BULK_ENTITY_NBT_REQUEST=7, S2C_NBT_RESPONSE_START=10, S2C_NBT_RESPONSE_DATA=11, C2S_NBT_RESPONSE_START=12, C2S_NBT_RESPONSE_DATA=13。

---

## placeToWorld / pasteTo 写世界 & 镜像修复内联点（SchematicPlacingUtils）

> 真正生效的粘贴路径是 `SchematicPlacement.pasteTo` → `SchematicPlacingUtils.placeToWorldWithinChunk`（按 chunk 迭代），而非 LitematicaSchematic.placeToWorld（旧路径，tick/neighbor 部署已注释）。

### placeToWorldWithinChunk 流程（L47-109）
1. `schematicPlacement.getRegionsTouchingChunk(x,z)`
2. `WorldUtils.setShouldPreventBlockUpdates(world, true)` ← **IWorldUpdateSuppressor Mixin**（Paper 需反射）
3. 对每个 region: `placeBlocksWithinChunk` + `placeEntitiesToWorldWithinChunk`
4. finally `setShouldPreventBlockUpdates(world, false)`

### placeBlocksWithinChunk（L111-380）核心
- box/相对坐标 + 反向 transform 计算 chunk 内的 `[startX..endX][startY..endY][startZ..endZ]`
- 旋转组合: `rotationCombined = schematicPlacement.getRotation().getRotated(placement.getRotation())`；**mirrorSub 在主旋转为 90/270 时 FRONT_BACK↔LEFT_RIGHT 翻转**（L183-188，照抄！）
- 逐方块: STRUCTURE_VOID 跳过；按 replace 策略(NONE/WITH_NON_AIR)跳过；`state.mirror(mirrorMain)`/`state.mirror(mirrorSub)`/`state.rotate(rotationCombined)`；先 setBlock(barrier, 0x14) 清旧 Container，再 setBlock(state, 0x12)；BE NBT 写 x/y/z 后 `te.loadWithComponents(NbtView.getReader(teNBT, registryAccess).getReader())`
- **BE load 用 `NbtView`**（util/nbt）+ `BlockEntity.loadWithComponents(Reader)` — 1.21 API，照抄
- scheduledBlockTicks/FluidTicks 部署（L303-358）: 仅当该位置当前 block == tick.type() 才 `scheduler.schedule(new ScheduledTick(...))`；fluids 同理
- notifyNeighbors: `world.updateNeighborsAt(pos, block)`（默认 false）

### 镜像修复内联点（箱子, L232-249）
**这是 CLAUDE.md §3 提到的"箱子镜像修复内联点"** —— 在 `placeBlocksWithinChunk` 内联，不走 Mixin:
```java
if (state.hasBlockEntity() && state.is(Blocks.CHEST) && !ignoreInventories &&
    mirrorMain != Mirror.NONE && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE &&
    LitematicsDataProvider.INSTANCE.isEnabled() && LitematicsDataProvider.INSTANCE.fixChestMirror.getValue()) {
    Direction facing = state.getValue(ChestBlock.FACING);
    Direction.Axis axis = facing.getAxis();
    ChestType type = state.getValue(ChestBlock.TYPE).getOpposite();
    if (axis != Direction.Axis.Y) {
        Direction facingAdj = type==ChestType.LEFT ? facing.getCounterClockWise(Y) : facing.getClockWise(Y);
        BlockPos posAdj = origPos.relative(facingAdj);
        teNBT = blockEntityMap.getOrDefault(posAdj, teNBT).copy();
    }
}
```
> 用相邻格的 BE NBT 修正被镜像后双联箱的物品归属。配置开关 `LitematicsDataProvider.INSTANCE.fixChestMirror`。**注意**: 铁轨/楼梯 180° 镜像修复（CLAUDE.md §3 提及）在当前 LTS 源码中**未见专门内联代码**——只看到箱子修复；铁轨/楼梯依赖 `state.mirror(mirrorMain)` 的原版 BlockState 行为。迁移时确认是否需要补 rail/stairs 修复。

### placeEntitiesToWorldWithinChunk（L382-518）内联修复点
- item_frame/glow_item_frame/leash_knot/painting: 写 TileX/Y/Z 避免 hanging 位置警告（L441-458）
- **Painting 偏移修复**（L482-499, 内联非 Mixin）: 偶数 width 且 right.axisDirection==POSITIVE → `x -= right.stepX; z -= right.stepZ`；偶数 height → `y -= 1.0`；`entity.setPos`
- ItemFrame Yaw 修复（L500-507）: pitch=±90 时恢复 origRot[0]
- Display 实体: `entity.tick()`（L511）刷新渲染数据
- LivingEntity sleeping: `setSleepingPos`

### rotateEntity（L520-531）
mirrorMain/mirrorSub/rotationCombined 组合 yaw → `entity.snapTo(x,y,z,yaw,pitch)` + `EntityUtils.setEntityRotations`

### NMS 依赖（SchematicPlacingUtils）
- `ServerLevel`/`Level`/`ChunkPos`/`BlockPos`/`Vec3i`/`Vec3`/`Direction`/`AxisDirection`
- `BlockState`/`BlockEntity`/`Blocks`/`Mirror`/`Rotation`/`ChestBlock`/`ChestType`
- `Container`/`Entity`/`LivingEntity`/`Display`/`ItemFrame`/`Painting`
- `ScheduledTick`/`LevelTicks`/`Fluid`
- `CompoundTag`/`ListTag`

### Mixin/Accessor 依赖（SchematicPlacingUtils）
- `IWorldUpdateSuppressor`（via `WorldUtils`）← **Paper 反射替代**

### 迁移方式: 适配
- 镜像修复（箱子/painting/itemframe）**已内联，照抄即可，无需 Mixin**。
- `WorldUtils.setShouldPreventBlockUpdates` Mixin → **反射**: Paper 无该 Mixin，需反射访问/设置 Level 的 update suppression 字段（1.21 `Level` 无公开字段；可反射 `ServerLevel.handled`/或走 `CraftWorld` 暂时无法；**最务实: 降级省略 update suppression**，改用 `setBlock(pos, state, flags)` 的 flags 控制通知——原代码用 0x12=NOTIFY|NO_NEIGHBOR_DROPS，0x14=barrier）。**风险点**: 省略 suppression 会导致放置时触发大量方块更新/流水/树更新。

---

## WorldUtils.java (16行) — 反射替代点
```java
((IWorldUpdateSuppressor) world).servux_getShouldPreventBlockUpdates();
((IWorldUpdateSuppressor) world).servux_setShouldPreventBlockUpdates(preventUpdates);
```
- Mixin 接口 `IWorldUpdateSuppressor` 给 `Level` 加 `servux_shouldPreventBlockUpdates` 字段，被 `LevelChunk.setBlockState` 检查跳过更新。
- **Paper 迁移**: 无 Mixin → 三选一: (a) 反射 Level 私有字段（1.21 可能无等价公开/私有 flag，需探测）; (b) 改用 setBlock flags 控制通知（部分等效）; (c) **降级省略**（最务实，接受放置副作用）。参考姊妹项目 VeryMcBot 的反射范式。

---

## FileType.java (93行) — 照抄
`enum FileType implements StringRepresentable`，含 `INVALID/UNKNOWN/JSON/LITEMATICA_SCHEMATIC/SCHEMATICA_SCHEMATIC/SPONGE_SCHEMATIC/VANILLA_STRUCTURE`。`CODEC = StringRepresentable.fromEnum(FileType::values)`。`getFileExt`/`getString`/`fromName`/`fromFile`。NMS: 仅 `net.minecraft.util.StringRepresentable`。**迁移: 照抄**。

---

## RegistryAccess 依赖汇总
本模块对 RegistryAccess 的依赖贯穿:
1. `world.registryAccess()` — BE save (`saveWithFullMetadata`)、BE load (`NbtView.getReader(teNBT, world.registryAccess())`)、entity save (`NbtView.getWriter(world.registryAccess())`)
2. `DataProviderManager.INSTANCE.getRegistryManager()` — palette 解析 (`lookupOrThrow(Registries.BLOCK)`)、tick registry (`BuiltInRegistries.BLOCK`/`.FLUID`)
3. `SchematicConversionMaps.updateBlockStates/updateEntity/updateBlockEntity` — 数据修复（需 MCDF/SharedConstants）

**Paper 迁移**: `world.registryAccess()` 在 NMS `ServerLevel` 直接可用（paperweight userdev 照抄）。`DataProviderManager.getRegistryManager()` 在 Paper 端换成 `Bukkit.getServer()` 或 `CraftRegistry`/level 的 `RegistryAccess`。`BuiltInRegistries` 直接可用。

---

## 总迁移方式分布（核心 + 关联文件）

| 方式 | 文件 |
|---|---|
| **照抄** | SchematicSchema, SchematicMetadata, SubRegionPlacement(去注解), SchematicBuffer, FileType |
| **照抄+适配** | SchematicPlacement, SchematicBufferManager |
| **适配** | LitematicaSchematic, SchematicPlacingUtils, ServuxLitematicaPacket, ServuxLitematicaHandler |
| **反射** | WorldUtils (IWorldUpdateSuppressor → 反射/降级) |
| **事件** | (本模块无生命周期 Mixin，ticks 采集的 IMixinWorldTickScheduler 归反射) |
| **降级** | (可选) update suppression 省略 |

---

## 3 个最关键实现风险点

1. **文件传输双 sessionKey + PacketSplitter 嵌套**（schematic-world 协议命门）: C2S 上传走「PacketSplitter 网络层重组 → 内层 Litematic-TransmitData 应用层 Slice 重组」两层；外层 key 由服务端 `RandomSource(Util.getMillis())` 生成存 `readingSessionKeys`，内层 SliceKey 由 nbt 携带。**Bukkit plugin messaging 32KiB 上限**要求 PacketSplitter 的分片常量改 ≤32000；S2C 大文件若超 32KiB 需改走 NMS `ClientboundCustomPayloadPacket` 直发。命名陷阱: sendTransmitFile 所有阶段都用 `ResponseC2SStart`(type=12)。

2. **ticks 采集 Mixin + RegistryAccess**（IMixinWorldTickScheduler + DataProviderManager）: `takeBlocksFromWorld` 用 `IMixinWorldTickScheduler.servux_getChunkTickSchedulers()` 读 LevelChunkTicks 映射——纯 Paper API 无法触达，必须反射 `ServerLevel.blockTicks/fluidTicks`。palette 解析依赖 `RegistryAccess.lookupOrThrow(Registries.BLOCK)`，Paper 端需从 `ServerLevel`/`Bukkit.getServer()` 取。

3. **placeToWorld 写世界 + update suppression Mixin**（IWorldUpdateSuppressor）: 粘贴用 `WorldUtils.setShouldPreventBlockUpdates` 抑制方块更新（防止放置时流水/树/连锁），由 Mixin 给 Level 加字段。Paper 无 Mixin → 反射或**降级省略**（接受副作用）。镜像修复（箱子/painting/itemframe）已内联可照抄；铁轨/楼梯 180° 镜像在 LTS 源码未见专门处理，依赖 `state.mirror()` 原版行为。
