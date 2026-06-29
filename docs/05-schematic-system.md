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
├── selection/                        Box / AreaSelection / BoxSliced / SelectionManager（几何）
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

> 见 [02](02-network-protocol.md) §5。每个 16KiB slice 装进一个 Payload，若该 Payload 仍超网络单包上限（S2C 1MiB / plugin messaging 32KiB），再由 PacketSplitter 切。

### 2.3 两级分包示意

```
Litematic 文件 (5 MiB)
  │ SchematicBuffer 切片（16 KiB）
  ├─ Slice[0] ─▶ PacketSplitter ─▶ Packet(s)（每片通常 1 个网络包）
  ├─ Slice[1] ─▶ PacketSplitter ─▶ Packet(s)
  ├─ ...
  └─ Slice[319] ─▶ ...
```

> **Paper 迁移**：两级分包**全可照抄**（纯 Java）。唯一改动：第二级的 PacketSplitter 分片常量受 plugin messaging 32KiB 限制（见 [02](02-network-protocol.md) §5.2 / [07](07-migration-architecture.md)）。

---

## 3. 传输协议：四阶段（`LitematicaSchematic` 传输方法）

> `LitematicaSchematic.java:995-1140`（发送）+ 接收端。通过 `servux:litematic_data` 通道，packetType 用 `*_RESPONSE_*` 系列，`Task` 字段区分阶段。

### 3.1 服务端→客户端发送投影文件

```
阶段1  TransmitStart:
  Task=Litematic-TransmitStart, FileName, FileType, SliceKey(=sessionKey), TotalSlices, TotalSize
阶段2  TransmitData（循环每片）:
  Task=Litematic-TransmitData, Slice=<n>, Size=<bytes>, Data=<byte[]>
阶段3  TransmitEnd:
  Task=Litematic-TransmitEnd
（异常）TransmitCancel: 取消
```

### 3.2 客户端→服务端上传投影（反向同理）

服务端 `LitematicsDataProvider` 收到 `TransmitStart` → `SchematicBufferManager.createBuffer`；`TransmitData` → `receiveSlice`；`TransmitEnd` → 组装成文件 → `LitematicaSchematic.createFromFile` 加载。

### 3.3 序列化字节流

```java
// ServuxLitematicaHandler.java:196-199
FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
buffer.writeVarInt(packet.getTransactionId());
buffer.writeNbt(packet.getCompound());           // NBT → ByteBuf
PacketSplitter.send(this, buffer, player, player.connection);
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

> `selection/Box.java`（224 行）+ `AreaSelection.java`（447 行）+ `BoxSliced.java` 等。

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
- `BoxSliced`：在某轴上切片（Litematica 逐层渲染用）。

> **可移植性**：仅依赖 `BlockPos`/`Vec3i`/`Direction`/`BoundingBox`（NMS 值类型），逻辑纯，照抄。

---

## 6. 放置系统：`SchematicPlacement` + 粘贴

> `placement/SchematicPlacement.java`（604 行）+ `SubRegionPlacement.java`（216 行）。

- `SchematicPlacement.createFromNbt(tags)`：从客户端粘贴请求解析（含旋转 `Rotation` / 镜像 `Mirror` / 各子区域位置 / 是否忽略实体）。
- `pasteTo(ServerLevel, ReplaceBehavior, PasteLayerBehavior, LayerRange)`：核心粘贴——遍历每个 Region 的每个方块，应用旋转/镜像变换，按 `ReplaceBehavior` 写世界。
- **镜像修复的内联点**：粘贴时对箱子/铁轨/楼梯的 `mirror`/`rotate` 结果做修正（替代 [04](04-mixin-analysis.md) 的 `MixinChestBlock`/`MixinRailBlocks`/`MixinStairsBlock`）。
- `SchematicPlacingUtils`：放置辅助（含原版方块放置校验 `PlacementHandler`）。

### 粘贴请求处理（`LitematicsDataProvider.handleClientPasteRequest`）

```
客户端发 C2S_PASTE_REQUEST: { Task:"LitematicaPaste", Schematics, Origin, ReplaceMode, PasteLayerBehavior, RenderLayerRange }
  → 权限检查（hasPermissionsForPaste）+ 创造模式检查
  → SchematicPlacement.createFromNbt(...)
  → placement.pasteTo(player.level(), replaceMode, layerBehavior, layerRange)
```

> **Paper 迁移**：粘贴是**写世界**（`ServerLevel.setBlock`），NMS 直连。`ReplaceBehavior`/`PasteLayerBehavior`/`LayerRange` 是 Servux 自定义枚举/类，照抄。镜像修复内联进 `pasteTo`。

---

## 7. 可移植性总表

| 组件 | 行数 | NMS 依赖 | 迁移方式 |
|---|---|---|---|
| `LitematicaBitArray` | 113 | ✅ 无 | **照抄** |
| `SchematicBuffer` / `Manager` | 154+143 | ✅ 无 | **照抄** |
| `Box` / `AreaSelection` / `BoxSliced` | ~700 | 值类型 | **照抄** |
| `SchematicMetadata` / `SchematicSchema` | ~380 | 值类型 | **照抄** |
| `LitematicaBlockStateContainer` | 195 | `RegistryAccess`（解析 palette） | 照抄 + registry 改参数传入 |
| `Palette`（Linear/HashMap） | ~280 | `BlockState` + `CrudeIncrementalIntIdentityHashBiMap` | 照抄（保留 NMS `BlockState`） |
| `LitematicaSchematic` | 2261 | NBT/BlockState/Entity/World | 照抄 + NMS 直连 |
| `SchematicPlacement` 粘贴 | ~800 | `ServerLevel.setBlock` + 镜像修正 | 照抄 + 内联镜像修复 |
| `SchematicConversionMaps` | 264 | 数据版本转换 | 照抄 |

> **结论**：整个投影子系统是 **"纯算法骨架 + NMS 数据接口"** 的典型。骨架（占 ~60% 代码量）照抄；接口层用 paperweight NMS 直连 `BlockState`/`CompoundTag`/`NbtIo`/`ServerLevel`，**几乎不需要反射**。这是移植中"代码量最大但单点难度最低"的部分。

---

## 8. 与网络层的衔接（移植注意）

- 投影传输走 `servux:litematic_data` 通道（[03](03-dataproviders-detail.md) §Litematics）。
- 大投影 = SchematicBuffer(16KiB) → PacketSplitter(网络包) 两级分包（[02](02-network-protocol.md) §5）。
- Paper 端若 plugin messaging 限制 32KiB：每个 16KiB slice 仍在限制内，**单 slice 不会再触发 PacketSplitter 二次分包**（16KiB < 32KiB），反而简化——但仍保留 PacketSplitter 作为保险（应对极端情况）。
- session key（`SliceKey`）需在插件侧维护 `Map<UUID, Long>` 映射，与原版 `SchematicBufferManager.playerMap` 一致。
