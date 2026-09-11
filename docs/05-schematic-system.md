# 05 · Litematica 投影系统技术细节

> Litematica 投影是 Servux **最大、最复杂**的子系统（约 9000 行）。本文聚焦移植关心的：数据结构、传输协议、序列化、纯算法可移植性。
> 原版目录：`OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/schematic/`（含 `container/`、`placement/`、`selection/`、`transmit/`、`conversion/`）。
> Provider 入口见 [03](03-dataproviders-detail.md) §Litematics；分包机制见 [02](02-network-protocol.md) §PacketSplitter。

---

## 0. 子系统全景

```
schematic/
├── LitematicaSchematic.java          投影主类（2261 行）—— Region 组织、NBT 读写、文件传输、粘贴
├── SchematicMetadata.java            元数据（作者/时间/尺寸/区域名…）
├── SchematicSchema.java              版本信息（record）
├── conversion/SchematicConversionMaps.java  老版本数据转换
├── container/                        ★ 纯算法压缩：BitArray + Palette + Container
├── placement/                        SchematicPlacement / SubRegionPlacement（旋转/镜像/定位 + 粘贴）
├── selection/                        Box / AreaSelection（几何）
└── transmit/                         ★ 纯 Java 分片：SchematicBuffer / SchematicBufferManager
```

**两个核心好消息**：
1. `container/`（压缩算法）+ `transmit/`（分片）+ `selection/`（几何）**几乎全是纯 Java**，可近乎照抄。
2. 唯一 NMS 依赖在"方块状态 ↔ NBT"的 palette 解析（需 `RegistryAccess`）和"粘贴时写世界"。

---

## 1. 数据结构：Region + BitArray + Palette

### 1.1 投影 = 多个 Region

> `LitematicaSchematic.java:78-85`

```java
public final Map<String, LitematicaBlockStateContainer> blockContainers;   // 区域名 → 方块容器（压缩）
public final Map<String, Map<BlockPos, CompoundTag>>     tileEntities;     // 方块实体 NBT
public final Map<String, Map<BlockPos, ScheduledTick<Block>>> pendingBlockTicks;  // 调度 tick
public final Map<String, Map<BlockPos, ScheduledTick<Fluid>>> pendingFluidTicks;
public final Map<String, List<EntityInfo>>               entities;         // 实体
public final Map<String, BlockPos>                       subRegionPositions;
public final Map<String, BlockPos>                       subRegionSizes;
```

### 1.2 `LitematicaBitArray` —— 位级压缩（★纯算法，照抄）

> `container/LitematicaBitArray.java`（113 行）。仅依赖 `long[]`，零 NMS。

```java
private final long[] longArray;        // 底层存储
private final int bitsPerEntry;        // 每条目位数（随 palette 大小动态 2..32）
private final long maxEntryValue;      // (1 << bits) - 1
private final long arraySize;          // 条目总数
```
- 原理：把"每个方块的 palette 索引"按 `bitsPerEntry` 位紧密打包进 `long[]`（与原版 chunk 的 paletted container 同思路，但独立实现）。
- `setAt(index, value)` / `getAt(index)`：用位运算跨 1~2 个 long 读写。
- `roundUp(value, interval)` 纯数学。

### 1.3 Palette 调色板（两种实现，按 bits 自动切换）

> `container/ILitematicaBlockStatePalette.java`（接口）+ 两个实现

| 实现 | 适用 | 底层 | 复杂度 |
|---|---|---|---|
| `LitematicaBlockStatePaletteLinear` | bits ≤ 4（palette ≤16） | `BlockState[]` 线性搜索 | O(n) |
| `LitematicaBlockStatePaletteHashMap` | bits > 4 | `CrudeIncrementalIntIdentityHashBiMap<BlockState>`（NMS） | O(1) |

**动态扩展**（`LitematicaBlockStateContainer.setBits`）：palette 增长 → 提升 bits → 重建 BitArray + 迁移旧数据 + 迁移 palette。

> ⚠️ `BlockState` 是 NMS 类型（`net.minecraft.world.level.block.state.BlockState`）。Paper 用 paperweight userdev 可直接用 NMS 的它（保真度最高），或换 Bukkit `BlockData`（需双向转换，工作量大）。**推荐保留 NMS `BlockState`**——粘贴、序列化、镜像修复都依赖它。

### 1.4 `LitematicaBlockStateContainer` —— BitArray + Palette 整合

> `container/LitematicaBlockStateContainer.java`（195 行）

```java
protected LitematicaBitArray storage;
protected ILitematicaBlockStatePalette palette;
protected final Vec3i size;
protected final long totalVolume;

// 索引（Y-X-Z 行主序）
protected int getIndex(int x, int y, int z) { return (y * sizeLayer) + z * sizeX + x; }

public BlockState get(int x, int y, int z) {
    BlockState s = palette.getBlockState(storage.getAt(getIndex(x,y,z)));
    return s == null ? AIR_BLOCK_STATE : s;
}
```

> **可移植性**：核心算法纯；唯一 NMS 接触点是 `createFrom()` / `readFromNBT()` 里用 `DataProviderManager.INSTANCE.getRegistryManager()`（`RegistryAccess.Frozen`）解析 palette NBT → BlockState。移植时把 registry 改成参数传入（`plugin`/`server` 提供）。

---

## 2. 传输系统：两级分包

> **这是移植最容易踩坑的部分**：Servux 对大投影文件用了**两级**分包。

### 2.1 第一级：`SchematicBuffer`（应用层，16KiB/片）

> `transmit/SchematicBuffer.java`（154 行）+ `SchematicBufferManager.java`（143 行）。**纯 Java**（`byte[]` + `AtomicInteger` + `ConcurrentHashMap`）。

```java
public static final int BUFFER_SIZE = 16384;   // 16 KiB / slice
private final Slice[] buffer;                   // 分片数组（按编号定位）
private final AtomicInteger receivedSlices;     // 已接收计数

public record Slice(byte[] data, int size) {}

public void receiveSlice(int number, Slice slice) {       // 乱序安全
    if (number >= 0 && number < totalExpectedSlices && buffer[number] == null) {
        buffer[number] = slice; receivedSlices.incrementAndGet();
    }
}
```

`SchematicBufferManager`：`ConcurrentHashMap<Long, SchematicBuffer>`（sessionKey→buffer）+ `ConcurrentHashMap<Long, CompoundTag>`（placement NBT）+ `ConcurrentHashMap<UUID, Long>`（玩家→session）。

### 2.2 第二级：`PacketSplitter`（网络层，~1MiB/包）

> 见 [02](02-network-protocol.md) §5。每个 16KiB slice 装进一个 Payload，若该 Payload 仍超网络单包上限，再由 PacketSplitter 切（26.1 线该两级形态仅为对端 C2S 上传约定——我方接收侧仅重组，见 §2.3 注记；S2C 发送侧死信链已删）。

### 2.3 两级分包示意

```
Litematic 文件 (5 MiB)
  │ SchematicBuffer 切片（16 KiB）
  ├─ Slice[0] ─▶ PacketSplitter ─▶ Packet(s)（每片通常 1 个网络包）
  ├─ Slice[1] ─▶ PacketSplitter ─▶ Packet(s)
  ├─ ...
  └─ Slice[319] ─▶ ...
```

> **Paper 迁移**：两级分包**全可照抄**（纯 Java）。唯一改动：第二级的 PacketSplitter 分片常量防御客户端 32767 解码上限（见 [02](02-network-protocol.md) §5.2 / [07](07-migration-architecture.md) §2.2）。
>
> **方向注记（2026-09 后）**：上图为上游四阶段协议的两级分包形态。26.1 线我方 **S2C 发送侧死信链已删**（客户端无接收端，见 §3）；16KiB 切片（`SchematicBuffer.BUFFER_SIZE`）现为对端 C2S 上传约定，我方接收侧仅重组、不切片。

---

## 3. 传输协议：四阶段帧（C2S 上传侧）

> 26.1 线现状：**S2C 发送侧（`sendTransmitFile` + `/servux litematic transmit`）死信链已删（2026-09）**——stock 26.1 客户端 `ServuxLitematicaHandler.handleBulkData` 的 Transmit 分流整块注释（一切帧坠入仅认 `BulkEntityReply` 的 `handleBulkEntityData`，静默丢弃无日志），上游服务端同方法 `@Deprecated(forRemoval=true)` 零调用点，且注释块引用 DataTag 迁移前变量名（取消注释无法编译）——服务端无法单方面修复，物理删除死链、恢复走 git revert。**C2S 上传侧为我方激活的协议面超集**（上游 `handleBulkData` 同为注释死路；客户端 `sliceForServux` 调用点也被上游注释，对 stock 客户端不可达），路由完整保留（`LitematicsDataProvider:479-484` 活/死错位声明）。

### 3.1 四阶段帧定义（两侧共用，保留于 `ServuxLitematicaPacket`）

```
阶段1  TransmitStart:
  Task=Litematic-TransmitStart, FileName, FileType, SliceKey(=sessionKey), TotalSlices, TotalSize
阶段2  TransmitData（循环每片）:
  Task=Litematic-TransmitData, Slice=<n>, Size=<bytes>, Data=<byte[]>
阶段3  TransmitEnd:
  Task=Litematic-TransmitEnd
（异常）TransmitCancel: 取消
```

（历史注记：S2C 发送方向曾按此帧表由命令触发 `sendTransmitFile` 16KiB 切片投递并经 `PacketSplitter` 二级分包，2026-09 随 26.1 客户端接收端死路确认后物理删除。）

### 3.2 客户端→服务端上传（我方激活的超集路由）

我方 `ServuxLitematicaHandler.handleBulkData` 按 NBT `"Task"` 字符串路由：`Litematic-Transmit*` → `LitematicaSchematic.receiveFileTransmit` → `SchematicBufferManager.createBuffer` / `receiveSlice` / `finishBuffer` 重组落盘 `schematics/`；粘贴受理 `LitematicsDataProvider.handleClientPasteRequestPair`。**活主路**为 `LitematicaPaste` 批量路由 → `handleClientPasteRequest` → `PasteTask` 分 tick 粘贴（见 [09](09-DELIVERY.md) §5.5）。

### 3.3 序列化字节流（26.1 线格式）

26.1 起批量重组体 NBT 载体从 vanilla `writeNbt` 切换为 malilib **DataTag 格式**，且**无 type VarInt / transactionId 前缀**、按 NBT `"Task"` 字符串路由（1.21.11 旧线为 `writeVarInt(transactionId) + writeNbt`——旧描述已过时）：

```java
// 我方 ServuxLitematicaHandler（C2S 重组入口，与 malilib DataTagIo 逐字节兼容）
CompoundTag nbt = DataTagIo.readTag(fullPacket);   // [int32 大端 压缩长][GZIP(具名根 NBT 流)]
// 按 nbt.getStringOr("Task", ...) 路由（LitematicaPaste / Litematic-Transmit*）
```

---

## 4. 序列化：NBT 文件格式

### 4.1 顶层 NBT

```java
// LitematicaSchematic.writeToNBT() :875-886
{
  MinecraftDataVersion: <int>,
  Version: <SCHEMATIC_VERSION>,
  SubVersion: <SCHEMATIC_VERSION_SUB>,
  Metadata: { ... },                  // SchematicMetadata
  Regions: { <regionName>: <见 4.2>, ... }
}
```

### 4.2 每个 Region 的 NBT

```text
{
  BlockStatePalette: [ {Name, Properties}, ... ]   // palette 列表
  BlockStates: <long[]>                            // BitArray 底层
  TileEntities: [ ... ]                            // 方块实体 NBT
  Entities: [ ... ]                                // 实体 NBT
  PendingBlockTicks: [ ... ]                       // 调度 tick
  Position: [x,y,z]                                // 区域原点
  Size: [x,y,z]                                    // 区域尺寸
}
```

### 4.3 文件存储（GZIP）

```java
// NbtUtils.writeCompressed(tag, path) → NbtIo.writeCompressed(tag, file)   // MC 原生 GZIP
```
读取自动解压（`NbtUtils.readNbtFromFileAsPath`）。

> **Paper 迁移**：`CompoundTag`/`ListTag`/`LongArrayTag`/`NbtIo` 全是 NMS（paperweight 直连）。也可用 Bukkit `PersistentDataContainer` 或 NBT-API 库，但**保真度最高是直接用 NMS 的 NbtIo**（与原版字节级一致，Litematica 客户端可直接读）。

---

## 5. 几何系统：`Box` / `AreaSelection`（★纯 Java）

> `selection/Box.java`（224 行）+ `AreaSelection.java`（447 行）。上游的 `BoxSliced` / `SelectionManager` / `SelectionMode` / `AreaSelectionSimple` 为零引用死代码，已于 2026-09 物理删除。

```java
// Box —— 两角点 + 尺寸
class Box { BlockPos pos1, pos2, size; String name; Corner selectedCorner; }
// AreaSelection —— 多个 Box + 原点
class AreaSelection {
    Map<String, Box> subRegionBoxes;
    BlockPos calculatedOrigin;   // 自动 = 所有 Box 的 min corner
    BlockPos explicitOrigin;     // 显式覆盖
}
```
- `Box.toVanilla()` → NMS `BoundingBox`（min/max 角）。
- `AreaSelection.getEffectiveOrigin()` / `moveEntireSelectionTo` / `moveSelectedElement`。

> **可移植性**：仅依赖 `BlockPos`/`Vec3i`/`Direction`/`BoundingBox`（NMS 值类型），逻辑纯，照抄。

---

## 6. 放置系统：`SchematicPlacement` + 粘贴

> `placement/SchematicPlacement.java`（604 行）+ `SubRegionPlacement.java`（216 行）。

- `SchematicPlacement.createFromNbt(tags)`：从客户端粘贴请求解析（含旋转 `Rotation` / 镜像 `Mirror` / 各子区域位置 / 是否忽略实体）。
- `pasteTo(ServerLevel, ReplaceBehavior, PasteLayerBehavior, LayerRange)`：核心粘贴——遍历每个 Region 的每个方块，应用旋转/镜像变换，按 `ReplaceBehavior` 写世界。
- **镜像修复的内联点**：粘贴时对箱子/铁轨/楼梯的 `mirror`/`rotate` 结果做修正（替代 [04](04-mixin-analysis.md) 的 `MixinChestBlock`/`MixinRailBlocks`/`MixinStairsBlock`）。
- `SchematicPlacingUtils`：放置辅助（含原版方块放置校验 `PlacementHandler`）。粘贴前**实体位置修复族**——`applyEntityPastePositionFixes` 逐字对齐上游 :446-513（Pos 全实体重写 / 悬挂类 TileX/Y/Z+block_pos / leash+home_pos 平移；leash/home 修复整体前置于 Rotation 读取，键集离散行为等价，有意偏差），详见 docs/09 §26.1.6。

### 粘贴请求处理（`LitematicsDataProvider.handleClientPasteRequest`）

```
客户端发 C2S_PASTE_REQUEST: { Task:"LitematicaPaste", Schematics, Origin, ReplaceMode, PasteLayerBehavior, RenderLayerRange }
  → 权限检查（hasPermissionsForPaste）+ 创造模式检查
  → SchematicPlacement.createFromNbt(...)
  → new PasteTask 登记 TaskScheduler 分 tick 逐 chunk 调 SchematicPlacingUtils.placeToWorldWithinChunk
    （2026-09-08 任务化，见 docs/09 §26.1.6；pasteTo 直放已随上游 @Deprecated 删除）
```

> **Paper 迁移**：粘贴是**写世界**（`ServerLevel.setBlock`），NMS 直连。`ReplaceBehavior`/`PasteLayerBehavior`/`LayerRange` 是 Servux 自定义枚举/类，照抄。镜像修复内联进 `SchematicPlacingUtils.placeBlocksWithinChunk`（粘贴方块落盘路径；`pasteTo` 已删除）。

---

## 7. 可移植性总表

| 组件 | 行数 | NMS 依赖 | 迁移方式 |
|---|---|---|---|
| `LitematicaBitArray` | 113 | ✅ 无 | **照抄** |
| `SchematicBuffer` / `Manager` | 154+143 | ✅ 无 | **照抄** |
| `Box` / `AreaSelection` | ~700 | 值类型 | **照抄**（上游 BoxSliced/SelectionManager/SelectionMode/AreaSelectionSimple 零引用死代码已删） |
| `SchematicMetadata` / `SchematicSchema` | ~380 | 值类型 | **照抄** |
| `LitematicaBlockStateContainer` | 195 | `RegistryAccess`（解析 palette） | 照抄 + registry 改参数传入 |
| `Palette`（Linear/HashMap） | ~280 | `BlockState` + `CrudeIncrementalIntIdentityHashBiMap` | 照抄（保留 NMS `BlockState`） |
| `LitematicaSchematic` | 2261 | NBT/BlockState/Entity/World | 照抄 + NMS 直连 |
| `SchematicPlacement` 粘贴 | ~800 | `ServerLevel.setBlock` + 镜像修正 | 照抄 + 内联镜像修复 |
| `SchematicConversionMaps` | 264 | 数据版本转换 | 照抄 |

> **结论**：整个投影子系统是 **"纯算法骨架 + NMS 数据接口"** 的典型。骨架（占 ~60% 代码量）照抄；接口层用 paperweight NMS 直连 `BlockState`/`CompoundTag`/`NbtIo`/`ServerLevel`，**几乎不需要反射**。这是移植中"代码量最大但单点难度最低"的部分。

---

## 8. 与网络层的衔接（移植注意）

- 投影传输走 `servux:litematics` 通道（[03](03-dataproviders-detail.md) §Litematics）。
- 大上传 = 客户端 16KiB 切片（SchematicBuffer 约定）→ 我方 PacketSplitter 重组（[02](02-network-protocol.md) §5）；S2C 发送侧死链已删（见 §3 注记），26.1 线我方仅重组不切片。
- 每个 16KiB slice 远小于客户端 32767 解码上限与 Bukkit 1MiB Messenger 上限，**单 slice 不会触发 PacketSplitter 二次分包**，反而简化——但仍保留 PacketSplitter 作为保险（应对极端情况）。
- session key（`SliceKey`）需在插件侧维护 `Map<UUID, Long>` 映射，与原版 `SchematicBufferManager.playerMap` 一致。
