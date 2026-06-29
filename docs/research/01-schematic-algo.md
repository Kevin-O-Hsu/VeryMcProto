# schematic-algo 迁移笔记

> 模块范围：`schematic/container`、`schematic/transmit`、`schematic/selection`、`schematic/conversion`
> 原版源码根：`I:/Programming/VeryMcProto/OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux`
> 目标包根：`verymc.top.veryMcProto.schematic.*`
> 平台：Paper 1.21.11 + paperweight userdev（Mojang 全映射 `net.minecraft.*`，无 Mixin/AW 运行时）

专项结论（先看）：本模块**几乎全栈照抄**。NMS 接触点集中在 3 处——
1. **`CrudeIncrementalIntIdentityHashBiMap<BlockState>`**（PaletteHashMap 的核心 map，NMS 公开类，`create(int)`/`getId`/`add`/`byId`/`size`/`clear` 全公开，无反射）。
2. **`NbtUtils.readBlockState(HolderGetter<Block>, CompoundTag)` / `NbtUtils.writeBlockState(BlockState)`** + 获取 `HolderGetter<Block>` 的来源 `DataProviderManager.INSTANCE.getRegistryManager().lookupOrThrow(Registries.BLOCK)`（Paper 上 `RegistryAccess.Frozen` 来自 `Bukkit.getServer().getServer()` 或 `MinecraftServer.getServer().registryAccess()`）。
3. **`FriendlyByteBuf` + `Unpooled.wrappedBuffer`**（`convertVarIntByteArrayToPackedLongArray` 把 Sponge VarInt 字节流转 packed long，可直接照抄）。

位运算（`LitematicaBitArray`）与几何（`Box`/`AreaSelection`）100% 纯算法，可逐行照抄。分片/并发（`SchematicBuffer`/`SchematicBufferManager`）用 `AtomicInteger`/`ConcurrentHashMap`，纯 JDK，照抄即可——**但要注意 `writeFile`/`finishBuffer` 里的 `LitematicaSchematic.createFromFile` 与 `LitematicsDataProvider.INSTANCE.getTransmitDir()` 属于别的模块，迁移时需对接 Paper 的数据目录与 NBT 文件读入**。

---

## 文件清单表

| 原版文件(相对servux根) | 行数 | 建议目标包 | NMS依赖 | Mixin/AW依赖 | 迁移方式 |
|---|---|---|---|---|---|
| schematic/container/ILitematicaBlockStatePalette.java | 35 | `schematic.container` | `BlockState`(NMS),`ListTag`(NMS) | 无 | 照抄(去注解) |
| schematic/container/ILitematicaBlockStatePaletteResizer.java | 8 | `schematic.container` | `BlockState`(NMS) | 无 | 照抄(去注解) |
| schematic/container/LitematicaBitArray.java | 112 | `schematic.container` | 无(仅 apache `Validate`) | 无 | 照抄(纯算法) |
| schematic/container/LitematicaBlockStateContainer.java | 194 | `schematic.container` | `BlockPos`,`Vec3i`,`ListTag`,`FriendlyByteBuf`,`BlockState`,`Blocks`(NMS) + `io.netty.buffer.Unpooled` | 无 | 照抄(NMS直引用) |
| schematic/container/LitematicaBlockStatePaletteHashMap.java | 130 | `schematic.container` | `CrudeIncrementalIntIdentityHashBiMap`,`HolderGetter`,`Registries`,`CompoundTag`,`ListTag`,`NbtUtils`,`Block`,`BlockState`(NMS) | 无 | 适配(Registry 来源改 Paper) |
| schematic/container/LitematicaBlockStatePaletteLinear.java | 150 | `schematic.container` | `HolderGetter`,`Registries`,`CompoundTag`,`ListTag`,`NbtUtils`,`Block`,`BlockState`(NMS) | 无 | 适配(Registry 来源改 Paper) |
| schematic/conversion/SchematicConversionMaps.java | 263 | `schematic.conversion` | `DataFixers`,`References`,`NbtOps`,`Dynamic`,`CompoundTag`,`ListTag`,`StringTag`(NMS) + `com.mojang.datafixers.*` | 无 | 适配(Servux→Paper logger;依赖 util.nbt.NbtUtils + LitematicaSchematic 常量) |
| schematic/selection/AreaSelection.java | 446 | `schematic.selection` | `BlockPos`,`Direction`(NMS) + guava `ImmutableList/Map` + gson + apache `Pair` | 无 | 照抄(去注解;依赖 util.JsonUtils + util.position.PositionUtils) |
| schematic/selection/AreaSelectionSimple.java | 122 | `schematic.selection` | `BlockPos`(NMS) + gson | 无 | 照抄 |
| schematic/selection/Box.java | 223 | `schematic.selection` | `BlockPos`,`BoundingBox`(NMS, `net.minecraft.world.level.levelgen.structure.BoundingBox`) + gson | 无 | 照抄(依赖 util.JsonUtils + util.position.PositionUtils) |
| schematic/selection/BoxSliced.java | 70 | `schematic.selection` | `Direction`(NMS) | 无 | 照抄 |
| schematic/selection/SelectionManager.java | 64 | `schematic.selection` | 无(纯 JDK Path/Map + gson) | 无 | 照抄(依赖 util.JsonUtils) |
| schematic/selection/SelectionMode.java | 60 | `schematic.selection` | 无 | 无 | 照抄(translationKey 服务端可保留或省略) |
| schematic/transmit/SchematicBuffer.java | 153 | `schematic.transmit` | 无(纯 JDK nio/atomic + util.data.FileType) | 无 | 照抄(logger 改 Paper) |
| schematic/transmit/SchematicBufferManager.java | 142 | `schematic.transmit` | `CompoundTag`,`ServerPlayer`(NMS) + `ConcurrentHashMap` | 无 | 适配(ServerPlayer→Paper;对接 LitematicaData/LitematicaSchematic 模块) |

**统计**：15 个文件，共约 2170 行。

---

## 逐文件详解

### ILitematicaBlockStatePalette.java (35行)
- 职责：调色板（BlockState↔int id 双向映射）的对外接口。
- 关键方法（接口）：
  - `int idFor(BlockState state)` —— 取 id，不存在则分配
  - `@Nullable BlockState getBlockState(int indexKey)`
  - `int getPaletteSize()`
  - `void readFromNBT(ListTag tagList)` / `ListTag writeToNBT()`
  - `boolean setMapping(List<BlockState> list)` —— 从文件读入时整表替换映射
- NMS依赖：`net.minecraft.world.level.block.state.BlockState`、`net.minecraft.nbt.ListTag`。
- Mixin/AW：无。
- 迁移方式：**照抄**（去掉 `javax.annotation.Nullable` 可保留或换 `org.jetbrains.annotations.Nullable`）。
- 风险点：无。

### ILitematicaBlockStatePaletteResizer.java (8行)
- 职责：调色板位数不够、需要扩容时的回调接口（由 Container 实现）。
- 关键方法：`int onResize(int bits, BlockState state)` —— 返回新 id。
- NMS依赖：`BlockState`。
- Mixin/AW：无。
- 迁移方式：**照抄**。
- 风险点：无。

### LitematicaBitArray.java (112行)
- 职责：packed `long[]` 位的紧凑存储，把 N 个 `bitsPerEntry` 位的小整数紧凑存进 long 数组。**纯算法，逐行照抄**。
- 关键字段：
  - `private final long[] longArray` —— 底层数据
  - `private final int bitsPerEntry`
  - `private final long maxEntryValue = (1L << bitsPerEntry) - 1L` —— 单条目掩码
  - `private final long arraySize` —— 条目数（**非** longArray 长度）
- 关键方法：
  - `LitematicaBitArray(int bitsPerEntryIn, long arraySizeIn)` (L21) → 委托三参构造
  - `LitematicaBitArray(int bitsPerEntryIn, long arraySizeIn, @Nullable long[] longArrayIn)` (L26)
    - `Validate.inclusiveBetween(1L, 32L, bitsPerEntryIn)` —— bitsPerEntry 限定 [1,32]
    - 自建时长度 = `roundUp(arraySizeIn * bitsPerEntryIn, 64L) / 64L`
  - `void setAt(long index, int value)` (L43) —— 位写入，含跨 long 边界（`startArrIndex != endArrIndex`）的双段写入。**边界校验被注释掉了**（L45-46），迁移可保留注释状态（与原版一致，性能优先）。
    - 核心位运算：`longArray[startArrIndex] = longArray[startArrIndex] & ~(maxEntryValue << startBitOffset) | ((long)value & maxEntryValue) << startBitOffset;`
    - 跨界补写：`longArray[endArrIndex] = longArray[endArrIndex] >>> j1 << j1 | ((long)value & maxEntryValue) >> endOffset;`（`j1 = bitsPerEntry - endOffset`，`endOffset = 64 - startBitOffset`）
  - `int getAt(long index)` (L61) —— 对称读取，单段/双段分支。
  - `long[] getBackingLongArray()` (L80)
  - `long size()` (L85) → arraySize
  - `static long roundUp(long value, long interval)` (L90) —— 向上取整到 interval 倍数，**注意 value==0 返回 interval**（非 0），负数会翻转 interval 符号。
- NMS依赖：**无**（仅 `org.apache.commons.lang3.Validate`）。
- Mixin/AW：无。
- 迁移方式：**照抄（纯算法）**。
- 风险点：
  - `bitsPerEntryIn` 上限是 **32**（不是 NMS chunk 的 16/15）；调用方（Container.setBits）的 bits 来自 `Math.max(2, 32 - numberOfLeadingZeros(paletteSize-1))`，paletteSize 上限受 BlockState 总数约束，实际不会到 32，但 `maxEntryValue` 在 bits=32 时 = `(1L<<32)-1`，依赖 long 算术，**必须保留 long**（不要改 int）。
  - `arraySize`/`index` 是 `long`（非 int），照抄时不要擅自降级为 int——`index * bitsPerEntry` 可能溢出 int。
  - `setAt`/`getAt` 的边界校验被注释掉：原版刻意如此，迁移保持一致即可（参数非法会静默写坏相邻条目而非抛异常）。

### LitematicaBlockStateContainer.java (194行)
- 职责：单个区块层的 BlockState 容器 = `LitematicaBitArray` 存储 + 调色板（Linear≤4bit / HashMap>4bit）+ 自动 resize；同时实现 `ILitematicaBlockStatePaletteResizer`。
- 关键字段：
  - `public static final BlockState AIR_BLOCK_STATE = Blocks.AIR.defaultBlockState()` (NMS)
  - `protected LitematicaBitArray storage`
  - `protected ILitematicaBlockStatePalette palette`
  - `protected final Vec3i size` (NMS), `final int sizeX/sizeY/sizeZ`, `final int sizeLayer = sizeX*sizeZ`, `final long totalVolume`
  - `protected int bits`
  - `protected long[] blockCounts = new long[0]` —— 仅 Sponge 临时支持用
- 关键方法：
  - 构造链：`(int,int,int)` (L27) → 默认 bits=2；`(Vec3i,int,long[])` (L32)；`(int,int,int,int,long[])` (L37) → `setBits`
  - `BlockState get(int x,int y,int z)` (L64) → `palette.getBlockState(storage.getAt(getIndex(...)))`，null 落回 AIR
  - `void set(int x,int y,int z, BlockState)` (L70) → `palette.idFor` + `storage.setAt`
  - `protected int getIndex(int x,int y,int z)` (L82) → `(y*sizeLayer) + z*sizeX + x`（**Y 优先、X 最末**的索引顺序，迁移务必照抄，否则与 Litematica 客户端字节序不符）
  - `protected void setBits(int bitsIn, @Nullable long[] backingLongArray)` (L87)
    - bits≤4 → `LitematicaBlockStatePaletteLinear(max(2,bits))`；否则 `LitematicaBlockStatePaletteHashMap`
    - 先 `palette.idFor(AIR_BLOCK_STATE)` 占位 id=0
  - `@Override int onResize(int bits, BlockState state)` (L117) —— 调色板扩容核心：新建更大 bits 的 storage+palette，**逐条目 `newStorage.setAt(i, oldStorage.getAt(i))` 重灌**，再 `palette.readFromNBT(oldPalette.writeToNBT())` 迁移调色板，最后 `idFor(state)`。
  - `static LitematicaBlockStateContainer createFrom(ListTag palette, long[] blockStates, BlockPos size)` (L147) —— 从 Litematica NBT 构建；`bits = max(2, 32 - numberOfLeadingZeros(palette.size()-1))`
  - `static @Nullable LitematicaBlockStateContainer createContainer(int paletteSize, byte[] blockData, Vec3i size)` (L156) —— 从 Sponge VarInt 字节构建
  - `static SpongeBlockstateConverterResults convertVarIntByteArrayToPackedLongArray(Vec3i size, int bits, byte[] blockStates)` (L166)
    - `FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(blockStates))` —— **关键 NMS 接触点**：用 NMS `FriendlyByteBuf` 读 VarInt
    - 循环 `id = buf.readVarInt()` → `bitArray.setAt(i, id)` → `blockCounts[id]++`
    - 返回 `(backingArray, blockCounts)`
  - 内部类 `SpongeBlockstateConverterResults` (L183) —— `long[] backingArray; long[] blockCounts`
- NMS依赖：`net.minecraft.core.BlockPos`、`net.minecraft.core.Vec3i`、`net.minecraft.nbt.ListTag`、`net.minecraft.network.FriendlyByteBuf`、`net.minecraft.world.level.block.Blocks`、`net.minecraft.world.level.block.state.BlockState`；Netty `io.netty.buffer.Unpooled`（Paper 运行时自带）。
- Mixin/AW：无。
- 迁移方式：**照抄（NMS 直引用，paperweight userdev 下可直接 import）**。
- 风险点：
  - `readVarInt()` 来自 `FriendlyByteBuf`，注意它**自维护读指针**——`Unpooled.wrappedBuffer` 的 buffer 读完后无需释放（Netty wrapped 不持有），但严谨起见迁移可包 `buf.release()`（原版没写，存在轻微泄漏；可保留原状以一致）。
  - `getIndex` 的 YXZ 顺序是协议契约的一部分，绝不可改。
  - `onResize` 重灌是 O(n) 且在 `idFor` 写路径上同步发生，迁移到 Paper 后若在大体积投影粘贴热路径，注意性能（但与原版一致即可，无需优化）。

### LitematicaBlockStatePaletteHashMap.java (130行)
- 职责：>4 bit 时的调色板实现，基于 NMS `CrudeIncrementalIntIdentityHashBiMap`。
- 关键字段：
  - `private final CrudeIncrementalIntIdentityHashBiMap<@NotNull BlockState> statePaletteMap` (NMS)
  - `private final ILitematicaBlockStatePaletteResizer paletteResizer`
  - `private final int bits`
- 关键方法：
  - 构造 (L23)：`statePaletteMap = CrudeIncrementalIntIdentityHashBiMap.create(1 << bitsIn)` —— **NMS 关键接触点**，`create(int)` 静态工厂，全公开。
  - `int idFor(BlockState state)` (L31)：`getId(state)`，-1 则 `add(state)`；若 `i >= (1<<bits)` 触发 `paletteResizer.onResize(bits+1, state)`。
  - `@Nullable BlockState getBlockState(int indexKey)` (L50) → `statePaletteMap.byId(indexKey)`
  - `int getPaletteSize()` (L56) → `statePaletteMap.size()`
  - `private void requestNewId(BlockState state)` (L61) —— readFromNBT 用：`add` 后若越界则 `onResize`，重灌后若 `newId<=origId` 再 `add` 一次。
  - `void readFromNBT(ListTag tagList)` (L77)
    - `HolderGetter<Block> lookup = DataProviderManager.INSTANCE.getRegistryManager().lookupOrThrow(Registries.BLOCK)` —— **Registry 来源**，迁移改 Paper 的 `MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BLOCK)` 或等价。
    - 遍历：`CompoundTag tag = tagList.getCompoundOrEmpty(i)` → `NbtUtils.readBlockState(lookup, tag)`；首项（i==0）若已是 AIR 则跳过（保持 AIR 占 id=0）。
  - `ListTag writeToNBT()` (L98)：遍历 id，`NbtUtils.writeBlockState(state)`（state 为 null 落 AIR）。
  - `boolean setMapping(List<BlockState> list)` (L119)：`clear()` 后逐个 `add`。
- NMS依赖：`net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap`（**核心**）、`net.minecraft.core.HolderGetter`、`net.minecraft.core.registries.Registries`、`net.minecraft.nbt.CompoundTag`、`net.minecraft.nbt.ListTag`、`net.minecraft.nbt.NbtUtils`、`net.minecraft.world.level.block.Block`、`net.minecraft.world.level.block.state.BlockState`。
- Mixin/AW：无（`CrudeIncrementalIntIdentityHashBiMap` 全公开，无需 AccessWidener）。
- 迁移方式：**适配**——唯一改动是 `DataProviderManager.INSTANCE.getRegistryManager()` 改为 Paper 的 RegistryAccess 来源。其余照抄。
- 风险点：
  - `CrudeIncrementalIntIdentityHashBiMap` 在不同 MC 版本签名可能微调（1.21.11 下 `create/add/byId/getId/size/clear` 均可用），升级版本时要核对。
  - 1.21.11 `CompoundTag`：`getCompoundOrEmpty(i)` 是新 API（替代旧的 `getCompound(i)`），`NbtUtils.readBlockState` 返回 `BlockState`（非 Optional），`writeBlockState` 返回 `CompoundTag`——这些在 1.21.11 dev bundle 下照抄即可。
  - `requestNewId` 里"重灌后再 add"的逻辑依赖 `onResize` 后旧 palette 已被替换；与 Container.onResize 配套，不可单改一处。

### LitematicaBlockStatePaletteLinear.java (150行)
- 职责：≤4 bit（小调色板）的线性数组实现，与 HashMap 版对称。
- 关键字段：
  - `private final BlockState[] states = new BlockState[1 << bitsIn]`
  - `private final ILitematicaBlockStatePaletteResizer resizeHandler`
  - `private final int bits`
  - `private int currentSize`
- 关键方法：
  - `int idFor(BlockState state)` (L31)：线性扫描 `states[i]==state`（**引用相等 ==**，BlockState 在 NMS 是 intern 的单例，== 安全）；满则 `resizeHandler.onResize(bits+1, state)`。
  - `@Nullable BlockState getBlockState(int indexKey)` (L57) —— 越界返回 null
  - `int getPaletteSize()` (L63) → currentSize
  - `private void requestNewId(BlockState state)` (L68) —— readFromNBT 用，越界则 resize 后条件补 add。
  - `void readFromNBT(ListTag tagList)` (L90) —— Registry 来源同 HashMap 版（`lookupOrThrow(Registries.BLOCK)`），`NbtUtils.readBlockState`，首项 AIR 跳过。
  - `ListTag writeToNBT()` (L111)、`boolean setMapping(List<BlockState> list)` (L132) —— list 超过容量返回 false。
- NMS依赖：`HolderGetter`、`Registries`、`CompoundTag`、`ListTag`、`NbtUtils`、`Block`、`BlockState`。
- Mixin/AW：无。
- 迁移方式：**适配**（Registry 来源改 Paper，其余照抄）。
- 风险点：
  - `==` 比较 BlockState 依赖 intern；NMS 中 `BlockState` 经 `Block.BLOCK_STATE_REGISTRY` 去重，== 成立。迁移不要改成 `equals`（虽等价但慢）。
  - 与 HashMap 版的 resize/idFor 行为必须严格对称（两者被同一 Container 通过 `setBits` 切换）。

### SchematicConversionMaps.java (263行)
- 职责：调用原版 **DataFixer** 把旧版本 litematic/sponge 的 BlockName/BlockState/BlockEntity/Entity NBT 升级到当前 MC 版本；并修复 1.19.x litematic 缺失的 `id` tag。
- 关键字段/方法：
  - `public static DataFixer datafixer`（可被外部注入）+ `private static DataFixer getDataFixer()` (L20) → `DataFixers.getDataFixer()` (NMS)
  - `static String updateBlockName(String oldName, int oldVersion)` (L30)
    - `StringTag.valueOf(oldName)` → `getDataFixer().update(References.BLOCK_NAME, new Dynamic<>(NbtOps.INSTANCE, tagStr), oldVersion, LitematicaSchematic.MINECRAFT_DATA_VERSION).getValue().asString().orElse(oldName)`
    - 异常时 warn 并返回原名
  - `static CompoundTag updateBlockStates(CompoundTag, int oldVersion)` (L51) → `References.BLOCK_STATE`
  - `static CompoundTag updateBlockEntity(CompoundTag, int oldVersion)` (L65) → `References.BLOCK_ENTITY`；异常用 `NbtUtils.readBlockPos`（util.nbt.NbtUtils，**非 NMS**）取 pos 打日志
  - `static CompoundTag updateEntity(CompoundTag, int oldVersion)` (L80) → `References.ENTITY`
  - `static CompoundTag checkForIdTag(CompoundTag tags)` (L95) —— **纯启发式**：按特征字段（Bees/TransferCooldown+Items/SkullOwner/Patterns/...）推断缺失的 `id`（beehive/hopper/skull/banner/...），并修 Items 里的 null `tag`。
  - `private static ListTag fixItemsTag(ListTag items)` (L217) —— 递归剔除 null `tag`，修嵌套 `BlockEntityTag.Items`。
- NMS依赖：`net.minecraft.util.datafix.DataFixers`、`net.minecraft.util.datafix.fixes.References`、`net.minecraft.nbt.NbtOps`、`net.minecraft.nbt.CompoundTag`、`net.minecraft.nbt.ListTag`、`net.minecraft.nbt.StringTag`；`com.mojang.datafixers.DataFixer`、`com.mojang.serialization.Dynamic`（DFU，Paper 运行时随 NMS 附带）。
- 跨模块依赖：`fi.dy.masa.servux.Servux`（logger）、`fi.dy.masa.servux.util.nbt.NbtUtils`（util 版，`readBlockPos`）、`fi.dy.masa.servux.schematic.LitematicaSchematic.MINECRAFT_DATA_VERSION` 常量。
- Mixin/AW：无。
- 迁移方式：**适配**——
  - `Servux.LOGGER` → Paper 插件 logger（`JavaPlugin#getLogger` 或 SLF4J）。
  - `LitematicaSchematic.MINECRAFT_DATA_VERSION` = `SharedConstants.getCurrentVersion().dataVersion().version()`，照抄到 Paper 的 `LitematicaSchematic` 对应类（`SharedConstants` 是 NMS 公开类）。
  - `util.nbt.NbtUtils` 属本项目 `nbt/` 包，单独迁移。
  - DFU/NbtOps/References 全部 NMS/DFU 公开 API，无需反射。
- 风险点：
  - `Dynamic.getValue().asString()` 在 1.21.11 返回 `Optional<String>`，`.orElse(oldName)` 正确——照抄。
  - `CompoundTag.contains/getStringOr/getListOrEmpty/getCompoundOrEmpty/putString/put/remove` 全是 1.21.11 新 API（Optional 化后），照抄即可，注意 `putString` 返回 void。
  - DataFixer 调用可能较慢且对大 NBT 有 CPU 成本；`enableFixers` 在 `LitematicaSchematic` 侧控制开关，迁移保留开关。
  - `checkForIdTag` 的启发式表很大且**与具体 vanilla 方块 id 强耦合**，照抄即可，升级 MC 版本时若有方块重命名/移除需复核。

### AreaSelection.java (446行)
- 职责：多子区域选区（一组 `Box` + 名称 + origin），含 origin 计算/移动/序列化。
- 关键字段：
  - `protected final Map<String, Box> subRegionBoxes = new HashMap<>()`
  - `protected String name = "Unnamed"`
  - `protected boolean originSelected`
  - `protected BlockPos calculatedOrigin = BlockPos.ZERO` (NMS)
  - `protected boolean calculatedOriginDirty = true`
  - `@Nullable protected BlockPos explicitOrigin = null`
  - `@Nullable protected String currentBox`
- 关键方法（节选）：
  - `static AreaSelection fromPlacement(SchematicPlacement placement)` (L36) —— 从 placement 构建（依赖 placement 模块）
  - `BlockPos getEffectiveOrigin()` (L97) —— explicit 优先，否则按 dirty 重算 `calculatedOrigin`
  - `void setExplicitOrigin(@Nullable BlockPos)` (L124)
  - `protected void updateCalculatedOrigin()` (L134) → `PositionUtils.getEnclosingAreaCorners(values())`
  - CRUD：`getSubRegionBox/getSelectedSubRegionBox/getAllSubRegionNames/getAllSubRegionBoxes/getAllSubRegions`、`createNewSubRegionBox(BlockPos,String)` (L180)、`addSubRegionBox/removeSubRegionBox/removeAllSubRegionBoxes`
  - `void moveEntireSelectionTo(BlockPos newOrigin, boolean printMessage)` (L257) —— 用 `BlockPos.subtract/offset` 平移所有 box
  - `void moveSelectedElement(Direction direction, int amount)` (L287) —— `BlockPos.relative(direction, amount)`
  - `void setCoordinate(Box, Corner, CoordinateType, int)` (L340) → `PositionUtils.getModifiedPosition`
  - `AreaSelection copy()` (L358) → `fromJson(toJson())`
  - `static AreaSelection fromJson(JsonObject)` (L363) / `JsonObject toJson()` (L412) —— 用 `JsonUtils.hasArray/hasString/blockPosFromJson/blockPosToJson`
- NMS依赖：`net.minecraft.core.BlockPos`、`net.minecraft.core.Direction`；guava `ImmutableList`/`ImmutableMap`；gson；apache `Pair`。
- 跨模块依赖：`schematic.placement.SchematicPlacement` + `SubRegionPlacement.RequiredEnabled`、`util.JsonUtils`、`util.position.PositionUtils`（含内嵌 `CoordinateType`/`Corner`）。
- Mixin/AW：无。
- 迁移方式：**照抄**（去 Fabric 注解；`PositionUtils`/`JsonUtils`/`placement` 由各自模块迁移）。
- 风险点：
  - `moveEntireSelectionTo` 的 `printMessage` 分支在原版只是格式化字符串到局部变量未实际发送（看起来是 TODO/未用），照抄保留。
  - `BlockPos` 是 NMS 不可变值类，`subtract/offset/relative` 都返回新实例——照抄语义即可。

### AreaSelectionSimple.java (122行)
- 职责：单 box 选区，继承 `AreaSelection`，把多 box 操作全改 NO-OP，强制恰好一个 box。
- 关键方法：
  - 构造 `(boolean createDefaultBox)` (L12) → `createDefaultBoxIfNeeded()`
  - NO-OP 重写：`setSelectedSubRegionBox/createNewSubRegionBox/addSubRegionBox/removeAllSubRegionBoxes/removeSubRegionBox` 全返回 false/null/void
  - `private void createDefaultBoxIfNeeded()` (L55) —— 保证 `subRegionBoxes.size()==1` 且 `currentBox` 有效
  - `static AreaSelectionSimple fromJson(JsonObject)` (L75) —— 只取 `arr.get(0)`，末尾再 `createDefaultBoxIfNeeded()`
- NMS依赖：`BlockPos`；gson。
- 迁移方式：**照抄**。
- 风险点：`fromJson` 里 `arr.get(0)` 不检查 size，迁移照抄（前面已 `arr.size()>0` 判断）。

### Box.java (223行)
- 职责：单选区盒（pos1/pos2/size/name/selectedCorner），含几何与 JSON 序列化。
- 关键字段：
  - `private BlockPos pos1/pos2` (NMS)
  - `private BlockPos size = BlockPos.ZERO`
  - `private String name = "Unnamed"`
  - `private PositionUtils.Corner selectedCorner = Corner.NONE`
- 关键方法：
  - 构造 `Box()` (L19)、`Box(BlockPos,BlockPos,String)` (L26) → 都调 `updateSize()`
  - `Box copy()` (L35)
  - getter/setter：pos1/pos2/size/name/selectedCorner
  - `private void updateSize()` (L103) → `PositionUtils.getAreaSizeFromRelativeEndPosition(pos2.subtract(pos1))`；全 null→ZERO，单边 null→`(1,1,1)`
  - `BlockPos getPosition(Corner)` (L117)、`int getCoordinate(Corner, CoordinateType)` (L122) switch X/Y/Z
  - `void setCoordinate(int, Corner, CoordinateType)` (L150) → `PositionUtils.getModifiedPosition`
  - `static @Nullable Box fromJson(JsonObject)` (L158) / `@Nullable JsonObject toJson()` (L188) —— 两个 pos 都 null 时返回 null
  - `net.minecraft.world.level.levelgen.structure.BoundingBox toVanilla()` (L207) —— **转 NMS `BoundingBox`**（min/max 三轴），pos1 null 时落 ZERO
  - 注释掉的 `rotate`/`mirror`（依赖 `PositionUtils.getTransformedBlockPos`），迁移可保留注释或后续启用。
- NMS依赖：`net.minecraft.core.BlockPos`、`net.minecraft.world.level.levelgen.structure.BoundingBox`；gson。
- 跨模块依赖：`util.JsonUtils`、`util.position.PositionUtils`。
- Mixin/AW：无。
- 迁移方式：**照抄**。
- 风险点：`toVanilla()` 调用前若 `pos2==null` 会 NPE（原版只保 pos1）；迁移可加 null 防御或照抄（调用方约定）。

### BoxSliced.java (70行)
- 职责：带切片（layer）维度的 Box，用于逐层粘贴/传输。
- 关键字段：
  - `private Direction sliceDirection = Direction.EAST` (NMS)
  - `private int sliceStart = 0`、`sliceEnd = 1`、`sliceCount`
- 关键方法：
  - `int getMaxSliceLength()` (L40) —— 按 `sliceDirection.getAxis()` 返回 size 的 X/Y/Z
  - `setSliceStart(int)` (L56) → `Math.min(v, getMaxSliceLength()-1)`
  - `setSliceEnd(int)` (L62) → `Math.min(v, getMaxSliceLength())`
- NMS依赖：`net.minecraft.core.Direction`。
- 迁移方式：**照抄**。
- 风险点：getMaxSliceLength 的 default 分支返回 1（不应触发，Direction 必有 axis）。

### SelectionManager.java (64行)
- 职责：管理多个命名 `AreaSelection` + 当前选中 + 文件加载（轻量）。
- 关键字段：
  - `private final Map<String, AreaSelection> selections` / `readOnlySelections`
  - `@Nullable private String currentSelectionId`
  - `private SelectionMode mode = SelectionMode.SIMPLE`
- 关键方法：
  - `getCurrentSelectionId()` (L20) —— mode!=NORMAL 返回 null
  - `static @Nullable AreaSelection tryLoadSelectionFromFile(Path)` (L44) → `JsonUtils.parseJsonFileAsPath(file)` + `AreaSelection.fromJson`
  - `void clear()` (L57)
- NMS依赖：**无**（纯 JDK Path/Map + gson via util）。
- 跨模块依赖：`util.JsonUtils.parseJsonFileAsPath`。
- 迁移方式：**照抄**。
- 风险点：原版此 Manager 比较薄（只读/加载为主），编辑能力在客户端；服务端移植可能只需 `tryLoadSelectionFromFile`/`clear`，可酌情精简。

### SelectionMode.java (60行)
- 职责：选区模式枚举（NORMAL/SIMPLE）+ i18n key。
- 关键方法：`getTranslationKey/getDisplayName/cycle(boolean)/fromString(String)`。
- NMS依赖：无。
- 迁移方式：**照抄**（translationKey 服务端无 GUI，可保留字段以维持结构或省略）。
- 风险点：`cycle` 用 `ordinal()` 模 `values().length`，照抄。

### SchematicBuffer.java (153行)
- 职责：单个投影文件的分片重组缓冲（按 slice number 收齐后落盘）。
- 关键字段：
  - `public static final int BUFFER_SIZE = 16384` —— 单片字节数（**注意：这是参考值，实际 slice 字节由协议决定；Bukkit plugin messaging S2C 上限 32KiB，此 16KiB 在限制内**）
  - `private final String name`、`private final FileType type`
  - `private Slice[] buffer` —— `record Slice(byte[] data, int size)` (L152)
  - `private final int totalExpectedSlices`、`private final long totalExpectedSize`
  - `private final AtomicInteger receivedSlices = new AtomicInteger(0)`
- 关键方法：
  - 构造链 (L22/L27) → 默认 `FileType.LITEMATICA_SCHEMATIC`
  - `Path getFileName()` (L46) —— 用 `FileType.getFileExt(type)` 补扩展名
  - `void receiveSlice(int number, Slice slice)` (L60) —— **原子幂等**：`buffer[number]==null` 时才置入并 `incrementAndGet`（防重复分片重复计数）
  - `boolean isComplete()` (L72) → `receivedSlices.get() == totalExpectedSlices`
  - `Path writeFile(Path dir)` (L77)
    - 先 `isComplete()` 校验，建目录、删旧文件
    - `OutputStream` 按 `buffer` 顺序 `os.write(entry.data(), 0, entry.size())`
    - 写后 `Files.size` 校验 == totalExpectedSize，不符删文件返回 null
    - 成功后 `this.buffer = null`（释放）
- NMS依赖：**无**（纯 JDK nio/atomic + `util.data.FileType`）。
- 跨模块依赖：`Servux.LOGGER`/`Servux.debugLog`（logger）、`util.data.FileType`。
- Mixin/AW：无。
- 迁移方式：**照抄**（logger 改 Paper）。
- 风险点（专项：并发安全）：
  - `receivedSlices` 用 `AtomicInteger`，但 `buffer[number]` 的 check-then-set（`if buffer[number]==null then ...`）**不是原子的**——若同一 slice number 并发到达两次，理论上两次都可能读到 null 然后 `incrementAndGet` 两次，导致 `receivedSlices` 超过 `totalExpectedSlices`。原版依赖"协议层不会并发投递同号 slice"。迁移到 Paper 的 `onPluginMessageReceived` 通常是**单线程事件循环**调度（Paper 主线程或 Netty 线程），但仍建议核对调用方是否串行化；若多玩家/多 session 共享，**应在 receiveSlice 加同步块或改用 `AtomicReferenceArray` + CAS**。
  - `writeFile` 后 `buffer=null`，但 `SchematicBuffer` 对象本身仍可能在 map 中被引用（由 Manager.remove），需配合 Manager 的生命周期。
  - `BUFFER_SIZE=16384` 与 32KiB 上限的关系：见顶层专项提示，S2C 分片常量若走 plugin messaging 需 ≤32000，此处是接收侧缓冲的参考大小，不影响。

### SchematicBufferManager.java (142行)
- 职责：按 `sessionKey`(long) 管理 `SchematicBuffer`，按 player UUID 映射 session，提供 create/receive/finish/cancel/removePlayer。
- 关键字段：
  - `private final ConcurrentHashMap<Long, SchematicBuffer> fileBuffers`
  - `private final ConcurrentHashMap<Long, CompoundTag> optionalNbt`
  - `private final ConcurrentHashMap<UUID, Long> playerMap`
  - 三个 map 都 `new ConcurrentHashMap<>(16, 0.9f, 1)` —— 并发度 1（低写争用场景）
- 关键方法：
  - 构造 (L22)
  - `createBuffer(...)` 重载链 (L29/L34/L39) —— 同 sessionKey 已存在则 warn 拒绝；`optional` 非 null/empty 则 `optional.copy()` 入 optionalNbt；`playerMap.put(player.getUUID(), sessionKey)`
  - `@Nullable SchematicBuffer getBuffer(long)` (L58)
  - `CompoundTag getOptionalNbt(long)` (L68) —— 不存在返回 `new CompoundTag()`
  - `void receiveSlice(long sessionKey, int slice, byte[] dataIn, int size)` (L78) → `buffer.receiveSlice(slice, new Slice(dataIn, size))`
  - `void cancelBuffer(long sessionKey)` (L90) —— remove fileBuffers + optionalNbt
  - `void removePlayer(ServerPlayer player)` (L104) —— UUID→key→cancelBuffer→remove
  - `@Nullable LitematicaSchematic finishBuffer(long sessionKey, @Nullable Path dir)` (L116)
    - dir null → `LitematicsDataProvider.INSTANCE.getTransmitDir()`
    - `buffer.writeFile(dir)` → 失败返回 null
    - `LitematicaSchematic.createFromFile(dir, name, type)` → cancelBuffer → 返回 schematic
- NMS依赖：`net.minecraft.nbt.CompoundTag`、`net.minecraft.server.level.ServerPlayer`。
- 跨模块依赖：`Servux.LOGGER`、`dataproviders.LitematicsDataProvider.INSTANCE.getTransmitDir()`、`schematic.LitematicaSchematic.createFromFile`、`util.data.FileType`。
- Mixin/AW：无。
- 迁移方式：**适配**——
  - `ServerPlayer` → Paper 侧：协议收发在 `PluginMessageListener` 拿到 `Player`，可 `((CraftPlayer)player).getHandle()` 转 NMS `ServerPlayer`，或直接用 `Player`+`UUID`（`player.getUniqueId()`）。`getUUID()` 在 NMS 即 `ServerPlayer#getUUID`；Bukkit `Player#getUniqueId()` 等价。**建议 Manager 直接用 `java.util.UUID`，不绑 NMS `ServerPlayer`**——把 NMS 留给真正需要 NMS 的 finish/createFromFile 路径。
  - `LitematicsDataProvider.INSTANCE.getTransmitDir()` → Paper 插件数据目录（`getDataFolder()` 下的子目录）。
  - `LitematicaSchematic.createFromFile` 由 schematic 主模块迁移（NBT 读入 + DataFixer）。
- 风险点（专项：并发安全）：
  - 三 map 用 `ConcurrentHashMap`，但 `createBuffer` 的"containsKey 检查后再 put"**不是原子的**（TOCTOU）；原版接受这个窗口（注释 warn 而非强一致）。若严格防重复，迁移可改 `putIfAbsent` + 判 null。
  - `removePlayer` 的 `containsKey`/`get`/`remove` 序列同样非原子，单玩家重复 quit 事件可能重复 cancel——影响小，照抄。
  - `finishBuffer` 后 `cancelBuffer` 会清掉 buffer；若并发又来一个同 session 的 slice，`receiveSlice` 会因 buffer 不在而 error log——可接受。
  - 内存：未 finish/cancel 的 buffer 会驻留（slice 数据在内存）；迁移应挂 `PlayerQuitEvent` 调 `removePlayer` 防泄漏（原版正是这么用，见主网络模块）。

---

## 协议字节布局说明

本模块内的类**不直接定义网络协议 Payload**——`LitematicaBitArray.getBackingLongArray()` 产出的 `long[]` 与 `palette.writeToNBT()` 产出的 `ListTag` 由上层 `LitematicaSchematic`/`SchematicBufferManager` 装进 litematic NBT 文件，再由网络层（`PacketSplitter`）分片走 `servux:litematic_data` 通道。本模块的"字节契约"是：

- **`LitematicaBlockStateContainer.getIndex(x,y,z)` = `y*sizeLayer + z*sizeX + x`** —— 这是 litematic 客户端期望的 packed long 数组内索引顺序，不可改。
- **`convertVarIntByteArrayToPackedLongArray`**：Sponge 格式是逐方块 VarInt 的 `byte[]`，转 Litematica 的 packed long；VarInt 编解码用 NMS `FriendlyByteBuf`（与 wiki.vg VarInt 一致）。
- **`SchematicBuffer.Slice(byte[] data, int size)`**：分片净荷 = `data` 的前 `size` 字节，按 `number` 顺序拼接还原原文件——与上层 `PacketSplitter` 的 S2C 分片（需 ≤32000）配合。

`CHANNEL_ID`/`PROTOCOL_VERSION`/`Payload record` 不在本模块，归 `network/` + `LitematicsDataProvider`（`servux:litematic_data`，协议版本 1）。

---

## 模块级迁移结论与风险点（Top 5）

1. **Registry 来源改 Paper（2 处调色板 + DataFixer 间接）**：`DataProviderManager.INSTANCE.getRegistryManager().lookupOrThrow(Registries.BLOCK)` → Paper 的 `MinecraftServer.getServer().registryAccess()`（或 `Bukkit.getServer()` 转 CraftServer 取 `getServer().registryAccess()`）。这是仅有的、需要"适配"而非"照抄"的 NMS 接触点。
2. **`CrudeIncrementalIntIdentityHashBiMap` 公开可用，无需反射/AW**：`create/add/byId/getId/size/clear` 全公开。升级 MC 版本时核对签名。
3. **`LitematicaBitArray` 必须保留 long 算术 + long index**：bits=32 时 `maxEntryValue=(1L<<32)-1`、`index*bitsPerEntry` 可能溢出 int——不要擅自降级字段类型；`setAt`/`getAt` 边界校验被注释掉是原版刻意为之。
4. **`getIndex` 的 YXZ 索引顺序是协议契约**，`Box.toVanilla()` 的 NMS `BoundingBox` 转换 —— 照抄，不可改顺序。
5. **并发安全有 TOCTOU 窗口**：`SchematicBuffer.receiveSlice` 的 check-then-set、`SchematicBufferManager.createBuffer/removePlayer` 的 containsKey-then-put 都非原子；原版依赖单线程事件投递。Paper 的 `onPluginMessageReceived` 若可能跨 Netty 线程并发，需在 receiveSlice 加同步或用 CAS；并务必挂 `PlayerQuitEvent`→`removePlayer` 防 buffer 泄漏。

> 迁移方式分布：**照抄 11 个**（含 2 个去注解接口、纯算法 BitArray、几何 Box/AreaSelection 系列、纯 JDK Manager/Buffer/SelectionMode），**适配 4 个**（PaletteHashMap/PaletteLinear 改 Registry 来源；SchematicConversionMaps/SchematicBufferManager 改 logger+Registry/Player+数据目录对接）。**反射 0、事件 0、降级 0**——本模块无需任何 Mixin/反射/降级。
