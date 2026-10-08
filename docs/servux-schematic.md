# Servux 投影（Litematica Schematic）子系统

> Litematica 投影是 Servux **最大、最复杂**的子系统（约 9000 行）。本文是 as-built 规范：数据结构（§1）、C2S 上传传输（§2–§3）、NBT 序列化（§4）、几何（§5）、放置粘贴与实体修复族（§6）、Task 调度器（§9 ⭐）。
> Provider 入口见 [servux-providers.md](servux-providers.md) §Litematics；分包机制见 [architecture.md](architecture.md) §3.3；通道 wire 与版本约束见 [servux-protocol.md](servux-protocol.md) §8.5。
> 原版目录：`OriginImpl/servux-LTS-26.2/src/main/java/fi/dy/masa/servux/schematic/`（含 `container/`、`placement/`、`selection/`、`conversion/`；原 `transmit/` 已随安全修复删除）。

---

## 0. 子系统全景

```
schematic/
├── LitematicaSchematic.java          投影主类—— Region 组织、NBT 读写、粘贴
├── SchematicMetadata.java            元数据（作者/时间/尺寸/区域名…）
├── SchematicSchema.java              版本信息（record）
├── conversion/SchematicConversionMaps.java  老版本数据转换
├── container/                        ★ 纯算法压缩：BitArray + Palette + Container
├── placement/                        SchematicPlacement / SubRegionPlacement（旋转/镜像/定位 + 粘贴）
└── selection/                        Box / AreaSelection（几何）
（scheduler/ 五类任务不在本包——mod/servux/scheduler/，见 §9）
```

**两个核心好消息**：
1. `container/`（压缩算法）+ `selection/`（几何）**几乎全是纯 Java**，可近乎照抄。
2. 唯一 NMS 依赖在"方块状态 ↔ NBT"的 palette 解析（需 `RegistryAccess`）和"粘贴时写世界"。

> **已移除功能（守卫注记，勿恢复）**：原 `transmit/`（`SchematicBuffer`/`SchematicBufferManager`）与 `Litematic-Transmit*` 接收链已于 2026-10 随安全修复整链物理删除（客户端可控 `FileName` 路径穿越任意写/删/读回 + OOM 向量，上游同判禁用；S2C `sendTransmitFile` 死信链 2026-09 先行删除——stock 客户端无接收端）。恢复走 git revert。历史四阶段帧 wire 记载见 git 历史。

---

## 1. 数据结构：Region + BitArray + Palette

### 1.1 投影 = 多个 Region

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

仅依赖 `long[]`，零 NMS。底层字段：`longArray` / `bitsPerEntry`（随 palette 大小动态 2..32）/ `maxEntryValue`（(1<<bits)-1）/ `arraySize`。

- 原理：把"每个方块的 palette 索引"按 `bitsPerEntry` 位紧密打包进 `long[]`（与原版 chunk 的 paletted container 同思路，独立实现）。
- `setAt(index, value)` / `getAt(index)`：位运算跨 1~2 个 long 读写。
- `roundUp(value, interval)` 纯数学。

### 1.3 Palette 调色板（两种实现，按 bits 自动切换）

| 实现 | 适用 | 底层 | 复杂度 |
|---|---|---|---|
| `LitematicaBlockStatePaletteLinear` | bits ≤ 4（palette ≤16） | `BlockState[]` 线性搜索 | O(n) |
| `LitematicaBlockStatePaletteHashMap` | bits > 4 | `CrudeIncrementalIntIdentityHashBiMap<BlockState>`（NMS） | O(1) |

**动态扩展**（`LitematicaBlockStateContainer.setBits`）：palette 增长 → 提升 bits → 重建 BitArray + 迁移旧数据 + 迁移 palette。

> ⚠️ `BlockState` 是 NMS 类型。用 paperweight userdev 直接用 NMS 的它（保真度最高）——粘贴、序列化、镜像修复都依赖它。

### 1.4 `LitematicaBlockStateContainer` —— BitArray + Palette 整合

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

> **可移植性**：核心算法纯；唯一 NMS 接触点是 `createFrom()` / `readFromNBT()` 里用 `RegistryAccess.Frozen` 解析 palette NBT → BlockState（registry 改参数传入，由框架在 `ServerLoadEvent` 后捕获，见 [architecture.md](architecture.md) §2.3）。

### 1.5 体积一致性预检（我方增强——上游无对应检查）

paste 上传链（`ServuxLitematicaHandler` → `SchematicPlacement.createFromNbt` → `readFromNBT` → `readSubRegionsFromNBT`）在每 region 的 `createFrom` 之前做**声明体积 vs BlockStates 实际容量**校验：

```java
// LitematicaSchematic.readSubRegionsFromNBT（palette 转换后、createFrom 前）
final int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1)); // 与 createFrom 内部逐字同式
final long totalVolume = (long) size.getX() * size.getY() * size.getZ();
final long capacityBits = (long) blockStateArr.length * 64L;
if (totalVolume < 0 || totalVolume > capacityBits / bits) { error("servux.litematics.error.schematic_load.region_volume_exceeds_blockstates"); }
```

- **动机**：`Size` 键完全客户端可控且与 `BlockStates` 实长可任意脱钩（声明 20000³ 只给 1 个 long）。虚假大体积直达粘贴层 = `PasteTask` 按 volume 迭代的巨量格位 CPU DoS（`TaskScheduler` 对 `execute()` 无 per-task 兜底），且执行期 `onResize` 全量重分配可达 OOM。上游 `readFromData` 的 `catch(OutOfMemoryError)` 是客户端读无限本地文件的承重防御（且对 int 溢出同样接不住），我方网络入口已有 64MB 双闸（见 [servux-protocol.md](servux-protocol.md) §5.5），照抄属死防御——**勿随上游模板搬运**。
- **公式细节**：`bits` 与 `LitematicaBlockStateContainer.createFrom` 内部逐字同式（预检容量 ≡ 容器容量）；**floor 除法**（整数等价于 `volume × bits ≤ capacityBits`，零误拒零溢出）——勿用 `BitArray.roundUp`（对 volume=0 会算出 requiredLongs=1 误拒合法空形态）；`totalVolume < 0` 守卫接住 `(long)` 三乘的 2^64 回绕（Position/Size 相减可为负分量——注意 `[2^30]³` 回绕值为 **0** 而非负数，两条件都不命中，构造溢出测试用例需用 `[MAX_VALUE]³`——积回绕为 `2^63+2^32-1` 负 long）。
- **拒绝语义**：整体拒（抛 `CommandSyntaxException`，经 `SchematicPlacement.createFromNbt` 包 `RuntimeException` → handler 外层 `catch(Exception)` warn 日志）——与 `readFromNBT` 的 version 错误同构；区别于结构残缺 region 的**静默跳过**（Position/Size 缺失，上游同源）。恶意坏包（体积配比不自洽）= 整体拒，两类失败勿混淆。
- **传递性背书**：C2S 侧 BlockStates 实长受 DataTagIo 的 NbtAccounter 64MB 配额封顶（先记账后分配）→ 合法流内声明体积上限自动 ≤ 2^32/bits ≈ 2^31 格位——预检只需处理"脱钩"（声明大、数据小），无需独立绝对上限常量。
- 单测：`SchematicPlacementGuardTest#oversizedRegionRejectedByVolumeConsistencyGuard`（两条件各锁一例：1e6 volume vs 32 容量 / `[MAX_VALUE]³` 负回绕）。

---

## 2. 传输系统：C2S 上传（两级形态）

> **这是移植最容易踩坑的部分**：投影上传用了**两级**分包，方向为 C2S（客户端→服务端）。

```
Litematic 文件 (5 MiB)
  │ 客户端切片（16 KiB，对端约定）
  ├─ Slice[0] ─▶ PacketSplitter 帧化 ─▶ Packet(s)（每片通常 1 个网络包）
  ├─ Slice[1] ─▶ ...
  └─ Slice[319] ─▶ ...
  我方接收侧：ServuxLitematicaHandler 每流生成 session key → PacketSplitter.receive 重组（连续流不串台）
```

- **第一级（对端约定）**：客户端按 **16KiB** 切片上传（原 `SchematicBuffer.BUFFER_SIZE=16384` 的 wire 约定；与 syncmatica 的 `UploadExchange.BUFFER_SIZE` 无关）。
- **第二级（网络层）**：每个 slice 装进一个 `*_DATA` Payload；16KiB 远小于客户端 32767 解码上限与 Bukkit 1MiB 上限，**单 slice 通常不触发二次分包**，但 PacketSplitter 重组路径保留作为保险。
- 重组完成后无条件走 `handleClientPasteRequest`（§6）；收端缓冲上限 64MB（见 [architecture.md](architecture.md) §3.4）。

---

## 3. 上传 wire 格式（现行）

26.1 起批量重组体 NBT 载体为 malilib **DataTag 格式**，且**无 type VarInt / transactionId 前缀**、按 NBT `"Task"` 字符串路由：

```java
// 我方 ServuxLitematicaHandler（C2S 重组入口，与 malilib DataTagIo 逐字节兼容）
CompoundTag nbt = DataTagIo.readTag(fullPacket);   // [int32 大端 压缩长][GZIP(具名根 NBT 流)]
// 按 nbt.getStringOr("Task", ...) 路由（现存路由仅 LitematicaPaste——Transmit* 已随安全修复移除）
```

DataTag 格式细节与配额闸见 [servux-protocol.md](servux-protocol.md) §5.3/§5.5。

---

## 4. 序列化：NBT 文件格式

### 4.1 顶层 NBT

```java
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
  TileEntities: [ ... ]                             // 方块实体 NBT
  Entities: [ ... ]                                 // 实体 NBT
  PendingBlockTicks: [ ... ]                        // 调度 tick
  Position: [x,y,z]                                 // 区域原点
  Size: [x,y,z]                                     // 区域尺寸
}
```

### 4.3 文件存储（GZIP）

`NbtIo.writeCompressed(tag, file)`（MC 原生 GZIP），读取自动解压。

> **Paper 迁移**：`CompoundTag`/`ListTag`/`LongArrayTag`/`NbtIo` 全是 NMS（paperweight 直连）。**保真度最高是直接用 NMS 的 NbtIo**（与原版字节级一致，Litematica 客户端可直接读）；Bukkit `PersistentDataContainer` 或 NBT-API 库不用。

---

## 5. 几何系统：`Box` / `AreaSelection`（★纯 Java）

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
- 上游的 `BoxSliced` / `SelectionManager` / `SelectionMode` / `AreaSelectionSimple` 为零引用死代码，已物理删除。
- **可移植性**：仅依赖 `BlockPos`/`Vec3i`/`Direction`/`BoundingBox`（NMS 值类型），逻辑纯，照抄。

---

## 6. 放置系统：`SchematicPlacement` + 粘贴

- `SchematicPlacement.createFromNbt(tags)`：从客户端粘贴请求解析（含旋转 `Rotation` / 镜像 `Mirror` / 各子区域位置 / 是否忽略实体）。
- 粘贴核心：遍历每个 Region 的每个方块，应用旋转/镜像变换，按 `ReplaceBehavior` 写世界——任务化后逐 chunk 由 `PasteTask` 调 `SchematicPlacingUtils.placeToWorldWithinChunk` 执行（§9；原 `pasteTo` 同步直放已随上游 `@Deprecated` 删除，**单 placement 字段**——上游两调用点恒 singletonList，multimap 泛化按「上游零调用点的泛化即裁」先例裁）。
- **镜像修复的内联点**：粘贴时对箱子/铁轨/楼梯的 `mirror`/`rotate` 结果做修正（`fixChestMirror` / `fixRailRotations` / `fixStairs_mirror` settings；替代上游 `MixinChestBlock`/`MixinRailBlocks`/`MixinStairsBlock`，见 [architecture.md](architecture.md) §5.2）。
- `SchematicPlacingUtils`：放置辅助（含原版方块放置校验 `PlacementHandler`）。

### 6.1 实体位置修复族（`applyEntityPastePositionFixes`）

粘贴实体前对实体 NBT 做六方面位置修复（逐字对齐上游 `SchematicPlacingUtils.java:446-513 + :562-565`）：

1. **一切实体 Pos 缺失或 ≠ 世界目标即重写**（vanilla 按 NBT Pos 构造实体；悬挂类消除载入期 "invalid hanging position" 告警；修复后的 p 是后续条目的数据源）。
2. **四悬挂类**（glow_item_frame/item_frame/leash_knot/painting）恒写 `TileX/Y/Z=(int)` 目标。
3. **同分支 block_pos**（缺失或 ≠ `BlockPos((int)x,(int)y,(int)z)` 重写）。
4. **leash**（键小写、拼错静默失效；区域相对值 + `off*` 平移、ZERO 哨兵跳过；UUID 上游自认不可修、不触碰）。
5. **home_pos** 同式平移但 `home_radius>0` 才生效、radius 值不改写（上游刻意形态）。
6. **spawn 后 tick 条件**扩 `Display || Leashable`。

`off*` = 变换后区域原点+粘贴原点；leash/home 锚点只平移不随 mirror/rotate 旋转（上游同源）。**有意偏差（顺序合并）**：上游 ④⑤ 在 Rotation 读之后，我方收敛为单方法整体前置于 Rotation 读取之前——被修复键集（Pos/TileX/block_pos/leash/home_*）与 Rotation 键无交、`origRot` 唯一消费点（ItemFrame yaw 修正）不触被修复键，行为等价。

**已知局限（登记工单）**：`NbtUtils.readEntityPositionFromTag` 守卫用 `ListTag.getId()`（恒返列表自身类型 9）比对 `TAG_DOUBLE(6)`，恒 false → 恒返 null——Pos 读取已改与 block_pos/leash/home 同型 `tag.read(KEY, Vec3.CODEC).orElse(null)` 直调（≡ 上游弃用自家 NbtUtils 改走 DataTypeUtils 之决策）；该 NbtUtils 缺陷修复后零存活调用点，另议处置。

### 6.2 粘贴请求处理（`LitematicsDataProvider.handleClientPasteRequest`）

```
客户端发 C2S_PASTE_REQUEST: { Task:"LitematicaPaste", Schematics, Origin, ReplaceMode, PasteLayerBehavior, RenderLayerRange }
  → 权限检查（hasPermissionsForPaste）+ 创造模式检查 + isPlayerRegistered + 空门
  → SchematicPlacement.createFromNbt(...)
  → new PasteTask 登记 TaskScheduler 分 tick 粘贴（§9）
  → 解析 Interval/ChangedBlocksOnly/IgnoreBlocks/IgnoreEntities（三布尔存而不用，上游同源 TODO）
```

---

## 7. 可移植性总表

| 组件 | NMS 依赖 | 迁移方式 |
|---|---|---|
| `LitematicaBitArray` | ✅ 无 | **照抄** |
| `Box` / `AreaSelection` | 值类型 | **照抄**（上游零引用死代码已删） |
| `SchematicMetadata` / `SchematicSchema` | 值类型 | **照抄** |
| `LitematicaBlockStateContainer` | `RegistryAccess`（解析 palette） | 照抄 + registry 改参数传入 |
| `Palette`（Linear/HashMap） | `BlockState` + `CrudeIncrementalIntIdentityHashBiMap` | 照抄（保留 NMS `BlockState`） |
| `LitematicaSchematic` | NBT/BlockState/Entity/World | 桩版分阶段回填 + NMS 直连 |
| `SchematicPlacement` 粘贴 | `ServerLevel.setBlock` + 镜像修正 | 照抄 + 内联镜像修复 |
| `SchematicConversionMaps` | 数据版本转换 | 照抄（`readFromNBT(enableFixers=false)` 守卫下零影响） |
| `IMixinWorldTickScheduler`（保存投影读 tick） | — | no-op 降级（粘贴不需要） |
| `WorldUtils` | — | no-op（靠 `setBlock` 的 flags 控制邻居更新） |

> **结论**：整个投影子系统是 **"纯算法骨架 + NMS 数据接口"** 的典型。骨架（占 ~60% 代码量）照抄；接口层用 paperweight NMS 直连 `BlockState`/`CompoundTag`/`NbtIo`/`ServerLevel`，**几乎不需要反射**。`LitematicaSchematic` 因 `selection↔placement↔schematic↔PositionUtils` 四元循环依赖，用**桩版**（移除引用未移植类的方法 + 准确注释）分阶段引入、逐步回填。

---

## 8. 与网络层的衔接

- 投影传输走 `servux:litematics` 通道（[servux-providers.md](servux-providers.md) §Litematics；通道 wire 见 [servux-protocol.md](servux-protocol.md) §8.5）。
- 大上传 = 客户端 16KiB 切片 → 我方 PacketSplitter 重组（§2）；S2C 发送侧死链已删，我方仅重组不切片。
- 每流新生成 readingSessionKey、收齐重置——**连续多个独立分包流各自独立重组，不需要分 tick / 延迟发送 workaround**。

---

## 9. Task 调度器与 paste 任务化（as-built）⭐

> 上游 `TaskPasteSchematicPerChunkDirect` 形态：受理→分 tick 执行→InfoHud 状态同步。`mod/servux/scheduler/` 五类：`TaskScheduler` + `LitematicaTask`（抽象基类）+ `FillDeleteTask` + `PasteTask` + `InfoHudTaskSync`。

### 9.1 架构

- `TaskScheduler`：单列表 + 每 tick `runTasks()`（无 synchronized——Paper 单主线程不变式；无双列表——任务 timer 初值 0 等价承载"下 tick 启动"）。
- 基类 `LitematicaTask` 单点承载共享面（timer、pendingChunks 队列、`updateInfoHudLines` 推帧、`stop()` 完成链、UUID 解析发送）；`FillDeleteTask`（25ms 固定预算 / radius 0 / 进度变化门控推帧）与 `PasteTask`（**vanillaTickTime+60ms 动态预算** / **radius 1** 周边加载判定 / **每 tick 无条件推帧**）只在执行体分叉。
- `InfoHudTaskSync`：type 16 组帧（进度帧 `Type="REMAINING_CHUNKS"` 枚举常量名——客户端 `valueOf` 无容错；完成帧仅 `InfoHudComplete=true` 缺 InfoHudSync 键）。Data 每条目均为真实区块坐标（n=任务名/rc=总数随条目携带）——**无 cx=-1 标题条目**：上游模板从不入列，客户端标题由 getFirst() 的 n/rc 合成（曾自创标题行占 maxLines 一位，致待处理 ≥10 时客户端少渲染 1 条坐标行，已修正）。

**接线四处**：Handler type 14 → `onTaskRequest`（15/16/17 上游同源忽略）；Provider settings `permission_level_tasks(0)`+`player_task_feedback(false)` + 权限节点 `servux.provider.litematic_data.task.fill/.delete` + `onTaskStatusSync` 四道门 + Box 手工解码（客户端 wire 形状 `{pos1:int[3],pos2:int[3],name}` IntArrayTag）+ FillState `tags.read(codec)`；`LifecycleBridge.onTick` 前置 `onServerTickEndPre()`（对应上游 Mixin tickServer RETURN）；`VeryMcProto.onDisable` 前置 `clearTasks()`。

### 9.2 `FillDeleteTask` 行为真值（对照上游 TaskFillArea/TaskDeleteArea）

分区块队列最近优先（参考点=构造时捕获的原 ServerPlayer 引用，上游冻结语义）+ 每 tick 25ms 预算 + 无进展退出 + currentChunkPos 预检短路；`directFillBox` 逐行照抄（z 外/x 中/y 内层降序、三态替换、容器 clearContent+barrier、flags 0x32、AABB 非玩家 discard）；进度推送仅在进度变化或首推（pendingChunks 空则全程零进度帧）、FINISHED 提前 return 不推末帧；完成链顺序=①消息入缓冲→②Complete 帧→③冲刷缓冲+completed 行（帧先于聊天，门控读活值）；Interval 读作重复周期。中断终行文案由恒 `has completed` 修正为 finished 条件（aborted 行，上游 TaskFeedbackListener + en_us 逐字）。

### 9.3 `PasteTask` 行为真值（对照上游 Direct:51-138）

构造期建队（touchedChunks×getBoxesWithinChunk → LayerRange+世界高度双钳制 count>0 入队，盒子用后即弃）；`ignoreBlocks&&ignoreEntities` 早退返回 true **不置 finished**（→ stop 走 paste.failed 文案，上游同源怪癖）；逐 chunk 调 `SchematicPlacingUtils.placeToWorldWithinChunk`（失败留队下 tick 重试）；受理处即时完成消息删除（上游注释停用，反馈走 stop 链）。

**上游同源 liveness 特性（勿误判为我方 Bug）**：① region 数据损坏的 chunk 无限重试+每 tick 推帧；② 预算取**上一**原版 tick 耗时（服务端持续 >60ms 时 paste 零进展）；③ 单 chunk 内无时间预算（巨型区块单次调用可击穿 60ms）。

### 9.4 有意偏差（相对上游，代码注释已声明）

1. 启动 ≤1 tick 偏移（Bukkit 心跳先于网络包处理，非逐 tick 等价）。
2. 玩家退出**不取消**任务（上游跑完语义，保证世界方块结果一致性；发送路径 UUID 解析 null 即跳过=上游"发死连接静默丢弃"等效）。
3. 停服 clearTasks（良性增量）。
4. 不移植 SEND_COMMAND_FEEDBACK gamerule 翻转（对不发命令的任务零可观测效果）。

### 9.5 验证判据

- `TaskGroupTest` 4 项黄金样本（Box IntArrayTag 形状 + REMAINING_CHUNKS 字面量 + 完成帧缺键 + 10 条上限·无标题行占位）。**纯 JVM 测试需 `SharedConstants.tryDetectVersion()+Bootstrap.bootStrap()` 前置**——`ChunkPos <clinit>` 链到注册表。
- `TaskSchedulerTest` 7 用例（timer 首启/interval 钳制/周期复位/完成移除/同 tick 双任务索引回退/未完成保留+clearTasks）。
- `EntityPastePositionFixTest` 11 用例钉死 §6.1 ①-⑤ 全守卫（字面串输入防同源共错 + Pos wire 形状断言 + 负数 (int) 截断 + UUID 不变断言）；⑥ 与 ③ 墙/地/顶三朝向 item frame 待实机验收。
- **端到端（用户侧最终验收）**：litematica 客户端创造模式选区 Fill/Delete → 观察 InfoHud 剩余区块 HUD 与完成消息；粘贴大投影 → 进度帧分 tick 收敛 + 完成帧清除客户端 HUD renderer。

---

> **相关**：[servux-protocol.md](servux-protocol.md)（通道 wire / type 14-17 帧）· [servux-providers.md](servux-providers.md)（Litematics Provider）· [architecture.md](architecture.md)（分片机制）· [servux-testing.md](servux-testing.md) · [operations.md](operations.md) · [index.md](index.md)
