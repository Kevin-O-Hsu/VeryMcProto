# 11 · Litematica 投影子系统移植蓝图（文件投递 + 投影粘贴）

> ✅ **schematic 子系统已完整移植并实测通过**（文件投递 + 投影粘贴全功能，详见 [05](05-schematic-system.md)）；本文档作为历史逐阶段蓝图保留（P0–P9 文件清单 + 降级点 + 编译门），不再代表待办工作。
> 原版对照见 [`05-schematic-system.md`](05-schematic-system.md)；测试见 [`10`](10-testing-guide.md)。
> 原版源码根：`OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/`（下文简写为 `ORIGIN/`）。
> Paper 实现包根：`src/main/java/verymc/top/veryMcProto/mod/servux/`（下文简写为 `PAPER/`）。

---

## 0. Context

`servux:litematics` 通道已实现握手 + 单/批量 NBT 查询（[`10`](10-testing-guide.md) §5.3 实测通过）。本文档落地两个降级功能（[`05`](05-schematic-system.md) §5.4）：

1. **文件投递（S2C）**：服务端 `.litematic` → 客户端（`LitematicaSchematic.sendTransmitFile`，公开 API，需命令触发）。
2. **投影粘贴（C2S 上传 + 放置）**：客户端上传 → `SchematicBufferManager` 组装 → `createFromFile` 加载 → `SchematicPlacement.pasteTo()` 写世界。

用户决策：**完整照抄原版**（保真度优先）；**本蓝图持久化 + auto mode 逐阶段自动推进**。约 8000 行。

---

## 1. 总体规则（auto mode 硬约束）

1. **编译门**：每阶段结束 `./gradlew compileJava` 必须 EXIT=0 才进下一阶段；失败当场修复，禁止带错推进。
2. **照抄优先**：容器/几何/元数据/`SchematicPlacingUtils` 逐字节照抄原版；仅 3 类强制降级（Mixin/DataFixer/IMixinWorldTickScheduler）。
3. **包名替换**：`fi.dy.masa.servux` → `verymc.top.veryMcProto.mod.servux`；`Servux.LOGGER` / `Reference.logger()` → 优先 `ServuxLog.debug`，告警/错误用 `Reference.logger()`（照已有文件，如 `LitematicsDataProvider`）。
4. **桩标注**：桩方法保留原版签名 + `// TODO P{n}: 回填（原版 ORIGIN/...:行号）`，便于 grep 定位回填。
5. **提交 checkpoint**：P0–P9 每阶段完成（必含 P5/P6/P8/P9）`git commit`，结尾 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。
6. **任务跟踪**：用 `TaskList` 查进度（#26 蓝图 / #27 P0 / #28 P1 / ... #36 P9），按 ID 顺序推进。
7. **复用基础设施**：`framework/network/PacketSplitter.{send,receive}`、`framework/reflect/Reflect`、`mod/servux/util/nbt/NbtView`、`framework/dataproviders/DataProviderManager.getRegistryManager()`、`framework/permission/Perms`、`framework/debug/Debug`。

---

## 2. 关键技术决策（已验证）

| 项 | 处置 | 依据 |
|---|---|---|
| `RegistryAccess` | 调用方 `DataProviderManager.INSTANCE.getRegistryManager()` → `.lookupOrThrow(Registries.BLOCK)` 取 `HolderGetter<Block>` 传给 palette | `DataProviderManager.getRegistryManager()` 已存在 |
| 原版 `NbtView`（Mixin） | **复用** `PAPER/util/nbt/NbtView.java`（已去 Mixin），不重写 | 已移植 |
| `SchematicConversionMaps`（DataFixer） | **降级跳过**：`readFromNBT` 构造 `enableFixers=false`（原版行 93），6 处调用全在 `if(enableFixers)` 守卫内 → fixer 方法体 no-op/`return input`，删 import | 行 1208/1215/1254/1261 守卫 |
| `IMixinWorldTickScheduler` | **降级**：`getTicksFromScheduler`（行 678/681/692）返回空，删 import。该方法是"保存投影读世界 tick"，粘贴读投影不涉及 | 行 49 import |
| `WorldUtils.setShouldPreventBlockUpdates`（Mixin） | **反射降级**：`Reflect.tryField(Level, "suppressPhysics"/候选名)`，失败 no-op；`setBlock(pos,state,flags)` flags 兜底控邻居更新 | 17 行类依赖 Mixin |
| 铁轨/楼梯镜像 | **降级**：原版 Mixin，靠 `BlockState.mirror()/rotate()` 自身（可能不完美）；箱子镜像修复已内联 `SchematicPlacingUtils` + `fixChestMirror` setting，照抄 | CLAUDE.md §3 |
| `PlacementHandler` | **跳过**：`SchematicPlacingUtils` 不引用它（已 Grep 确认），EasyPlace 专用 | — |
| `BlockState` | 保留 NMS `net.minecraft.world.level.block.state.BlockState` | 保真度 |

---

## 3. 循环依赖破解

`selection/AreaSelection` ↔ `placement/SchematicPlacement` ↔ `schematic/LitematicaSchematic` ↔ `util/position/PositionUtils`（仅 `getTransformedPlacementPosition` 接 placement）形成闭环，无法线性独立编译。

- **P3**：`PositionUtils` 引入时 `getTransformedPlacementPosition` 用桩（`return pos`，删 placement import）→ util 层独立编译。
- **P5（高危）**：`AreaSelection` + `SubRegionPlacement` + `SchematicPlacement` + `LitematicaSchematic`(桩) + `SchematicMetadata` + `SchematicSchema` **同批 6 文件提交**，缺一不可编译。
- **LitematicaSchematic 桩版**：删 2 import + 降级 `getTicksFromScheduler`/6 fixer 方法体 + `placeToWorld*`/`sendTransmitFile`/`receiveFileTransmit` 桩。P6/P7/P8 回填。

---

## 4. 逐阶段清单

### P0 — util/data 叶子 【#27】
- **原版 → Paper 文件映射**：
  - `ORIGIN/util/data/Constants.java` → `PAPER/util/data/Constants.java`
  - `ORIGIN/util/data/FileType.java` → `PAPER/util/data/FileType.java`
  - `ORIGIN/util/data/Schema.java` → `PAPER/util/data/Schema.java`
- **适配**：仅改 `package` 声明。Constants 零依赖；FileType/Schema 依赖 NMS `StringRepresentable`（paperweight 直连），照抄。
- **门**：`./gradlew compileJava`

### P1 — container 容器层 【#28】
- **原版 → Paper 文件映射**（包 `PAPER/schematic/container/`，6 文件）：
  - `LitematicaBitArray.java`（113，零 NMS）
  - `ILitematicaBlockStatePalette.java`（36，接口）
  - `ILitematicaBlockStatePaletteResizer.java`（9，接口）
  - `LitematicaBlockStatePaletteLinear.java`（151）
  - `LitematicaBlockStatePaletteHashMap.java`（131，用 `CrudeIncrementalIntIdentityHashBiMap`）
  - `LitematicaBlockStateContainer.java`（195）
- **适配**：palette 解析的 `RegistryAccess` 改 `HolderGetter<Block>` 参数传入（原版若直接 `DataProviderManager.getRegistryManager()`，改为调用方传入）。其余照抄。
- **门**：`compileJava`

### P2 — 几何叶子 + IntBoundingBox 【#29】
- **原版 → Paper 文件映射**：
  - `ORIGIN/schematic/selection/Box.java` → `PAPER/schematic/selection/Box.java`（224）
  - `ORIGIN/schematic/selection/BoxSliced.java` → `PAPER/schematic/selection/BoxSliced.java`（71）
  - `ORIGIN/schematic/selection/SelectionMode.java` → `PAPER/schematic/selection/SelectionMode.java`（61）
  - `ORIGIN/util/IntBoundingBox.java` → `PAPER/util/IntBoundingBox.java`（183）
  - `ORIGIN/util/JsonUtils.java` → `PAPER/util/JsonUtils.java`（确认依赖：仅 GSON 则照抄；若引用其他 util 类则评估）
- **适配**：Box 用 NMS `BoundingBox`/`BlockPos`；IntBoundingBox 用 NMS Codec。均不依赖 placement，照抄。
- **门**：`compileJava`

### P3 — util 核心（含桩）【#30】
- **原版 → Paper 文件映射**：
  - `ORIGIN/util/nbt/NbtUtils.java` → `PAPER/util/nbt/NbtUtils.java`（360，依赖 `Constants`@P0 + NMS）
  - `ORIGIN/util/position/PositionUtils.java` → `PAPER/util/position/PositionUtils.java`（1315，**桩**）
  - `ORIGIN/util/EntityUtils.java` → `PAPER/util/EntityUtils.java`（193，含 `NOT_PLAYER`/`createEntityAndPassengersFromNBT`/`spawnEntityAndPassengersInWorld`/`setEntityRotations`）
  - `ORIGIN/util/WorldUtils.java` → `PAPER/util/WorldUtils.java`（**反射降级**）
  - `ORIGIN/util/LayerRange.java` → `PAPER/util/LayerRange.java`（若引用 `LayerMode` 则一并移植）
  - `ORIGIN/util/ReplaceBehavior.java` → `PAPER/util/ReplaceBehavior.java`
  - `ORIGIN/util/PasteLayerBehavior.java` → `PAPER/util/PasteLayerBehavior.java`
- **适配**：
  - `PositionUtils.getTransformedPlacementPosition(BlockPos, SchematicPlacement, SubRegionPlacement)`（原版行 425）→ 桩 `return pos;`，**删 placement import**，加 `// TODO P6: 回填`。其余方法全照抄。
  - `WorldUtils.setShouldPreventBlockUpdates(Level, boolean)` → `Reflect.tryField(Level.class, "suppressPhysics")` 等多候选名，命中则 set，否则 no-op。
- **门**：`compileJava`

### P4 — 轻量 selection 【#31】
- **原版 → Paper 文件映射**（包 `PAPER/schematic/selection/`）：
  - `ORIGIN/schematic/selection/AreaSelectionSimple.java` → `AreaSelectionSimple.java`（123）
  - `ORIGIN/schematic/selection/SelectionManager.java` → `SelectionManager.java`（65）
- **适配**：仅依赖 Box/PositionUtils（不依赖 placement），照抄。**不**引入 `AreaSelection`（留 P5）。
- **门**：`compileJava`

### P5★ — 闭环（一次性引入，高危）【#32】
- **原版 → Paper 文件映射**（6 文件同批）：
  - `ORIGIN/schematic/placement/SubRegionPlacement.java` → `PAPER/schematic/placement/SubRegionPlacement.java`（216）
  - `ORIGIN/schematic/placement/SchematicPlacement.java` → `PAPER/schematic/placement/SchematicPlacement.java`（604）
  - `ORIGIN/schematic/SchematicMetadata.java` → `PAPER/schematic/SchematicMetadata.java`（366）
  - `ORIGIN/schematic/SchematicSchema.java` → `PAPER/schematic/SchematicSchema.java`（11）
  - `ORIGIN/schematic/selection/AreaSelection.java` → `PAPER/schematic/selection/AreaSelection.java`（447）
  - `ORIGIN/schematic/LitematicaSchematic.java` → `PAPER/schematic/LitematicaSchematic.java`（2261，**桩版**）
- **LitematicaSchematic 桩版降级清单**（精确）：
  - 删 import：`IMixinWorldTickScheduler`（行 49）、`SchematicConversionMaps`（行 54）
  - 降级方法体（保留签名）：
    - `getTicksFromScheduler(...)`（行 692 定义，行 678/681 调用）→ 方法体 no-op
    - `convertTileEntities_to_1_20_5`（行 1208 调用，定义含 1985）→ `return tiles;`
    - `convertEntities_to_1_20_5`（行 1215 调用，定义含 2009/2033）→ `return entities;`
    - `convertBlockStatePalette_to_1_20_5`（行 1254 调用，定义含 1962）→ `return palette;`
    - `postProcessContainerIfNeeded`（行 1261 调用，定义含 1985）→ no-op
    - `applyBlockStateDataFixers`（定义含 1670）→ no-op / `return input`
  - 桩方法（保留签名 + `// TODO P6/P7/P8: 回填`）：
    - `placeToWorld(...)` / `placeToWorldWithinChunk` 等放置方法（依赖 SchematicPlacingUtils@P6）
    - `sendTransmitFile(...)`（依赖 buffer/Handler@P8）
    - `receiveFileTransmit(...)`（依赖 SchematicBufferManager@P7）
  - `EntityInfo` 内部类照抄（SchematicPlacingUtils@P6 引用）
  - `LitematicaSchematic(CompoundTag)` 构造照抄（`readFromNBT(nbt, false)`，行 91-95）
- **门**：`compileJava`（最易卡；失败严格按降级清单逐项核对）
- **commit**：P5 完成 checkpoint

### P6 — 粘贴写世界核心 【#33】
- **回填**：`PositionUtils.getTransformedPlacementPosition`（行 425）恢复原版实现 + placement import
- **新建**：`ORIGIN/util/SchematicPlacingUtils.java` → `PAPER/util/SchematicPlacingUtils.java`（553，**逐字节照抄**）
- **回填**：`LitematicaSchematic.placeToWorld*` 方法体（调 `SchematicPlacingUtils.placeToWorldWithinChunk`）
- **降级**：铁轨/楼梯镜像靠 `BlockState.mirror()/rotate()`；箱子镜像修复照抄（`fixChestMirror` setting 已在 `LitematicsDataProvider`）
- **门**：`compileJava` + commit

### P7 — transmit 子系统 【#34】
- **新建**：
  - `ORIGIN/schematic/transmit/SchematicBuffer.java` → `PAPER/schematic/transmit/SchematicBuffer.java`（154）
  - `ORIGIN/schematic/transmit/SchematicBufferManager.java` → `PAPER/schematic/transmit/SchematicBufferManager.java`（143）
- **回填**：`LitematicaSchematic.receiveFileTransmit`（原版行 1072-1140）
- **门**：`compileJava`

### P8 — 接通 litematics 降级点 【#35】
- **编辑 `PAPER/dataproviders/LitematicsDataProvider.java`**：
  - 新增字段 `private final SchematicBufferManager bufferManager = new SchematicBufferManager();` + `getBufferManager()`
  - 新增 `getTransmitDir()`：`Reference.plugin().getDataFolder().toPath().resolve("schematics")`，`Files.createDirectories`
  - 新增 `handleClientPasteRequestPair(ServerPlayer, Pair<LitematicaSchematic, CompoundTag>)`（Pair 版）
  - 回填 `handleClientPasteRequest`：`SchematicPlacement.createFromNbt(schematic/tags).pasteTo(level, replace, layer, range)`
- **编辑 `PAPER/network/ServuxLitematicaHandler.java`**：
  - `handleBulkData`：TransmitStart/Data/End/Cancel 四分支 → `LitematicaSchematic.receiveFileTransmit(nbt, player)`（via `getBufferManager()`），返回 Pair 后调 `handleClientPasteRequestPair`；default → `handleClientPasteRequest`
  - `encodeServerData`：`if` 改为 `type==PACKET_S2C_NBT_RESPONSE_START(10) || type==PACKET_C2S_NBT_RESPONSE_START(12)`，统一走 PacketSplitter（buf `writeVarInt(transactionId)+writeNbt`）
- **回填**：`LitematicaSchematic.sendTransmitFile`（原版行 995-1070：16KiB 分片 + `encodeServerData(player, ResponseC2SStart(output))`）
- **门**：`compileJava` + commit

### P9 — 命令 + 烟测 【#36】
- **编辑 `PAPER/command/ServuxCommand.java`**：加 `litematic paste <file> [player]`（粘贴）与 `litematic transmit <file> [player]`（投递）子命令 + Tab 补全；权限 `servux.command` + provider `.paste`。参照已有子命令模式。
- **门**：`./gradlew build`（reobfJar）+ 客户端烟测：
  - 粘贴：Litematica 加载投影 → 粘贴 → 服务端日志 `handleBulkData` TransmitStart/Data/End → paste 成功聊天回执；世界方块/方块实体正确
  - 投递：`/servux litematic transmit <file>` → 服务端 `sendTransmitFile` → 客户端收到投影
  - 日志判据见 [`10`](10-testing-guide.md) §5.2/§7.2
- **commit**：P9 完成

---

## 5. auto mode 推进硬规则

1. 严格按 P0→P9 顺序；每阶段 `compileJava` EXIT=0 才进下一阶段。
2. P5 必须一次性提交闭环 6 文件。
3. 桩方法保留签名 + `// TODO P{n}: 回填`。
4. 遇 `cannot find symbol` 先查 paperweight 暴露的 1.21.11 NMS 签名，优先对齐 Provider 已有用法（`nbt.getStringOr` 等）。
5. 失败回退：`git checkout` 回上一 checkpoint 重审降级清单（P5 尤甚）。

---

## 6. 验证

- 编译门：每阶段 `compileJava`；P9 `build`（reobfJar，标准 Paper 可加载）。
- 单元级：P5 后 `LitematicaSchematic.createFromFile` 加载测试 `.litematic` 不抛异常（必要时 `onEnable` 临时探针）。
- 端到端烟测（P9）：粘贴 + 投递（判据见上）。

---

## 7. 状态总览（完成后回填）

| 阶段 | 任务 | 状态 | commit | 日期 |
|---|---|---|---|---|
| 蓝图 | #26 | ✅ | cedd1c4 | 2026-06-30 |
| P0 util/data | #27 | ✅ | cedd1c4 | 2026-06-30 |
| P1 container | #28 | ✅ | be11c06 | 2026-06-30 |
| P2 几何+PositionUtils 桩 | #29 | ✅ | cbd0068 | 2026-06-30 |
| P3 util 核心 | #30 | ✅ | bd2a43c | 2026-06-30 |
| P4 → 合并入 P5 | #31 | ✅ | （依赖 AreaSelection） | 2026-06-30 |
| P5 闭环+Schematic 桩 | #32 | ✅ | 4b7e2ff | 2026-07-01 |
| P6 粘贴核心 | #33 | ✅ | ee07391 | 2026-07-01 |
| P7 transmit | #34 | ✅ | bad9748 | 2026-07-01 |
| P8 接通降级点 | #35 | ✅ | ad725f2 | 2026-07-01 |
| P9 命令+build | #36 | ✅ | 2dfd52a | 2026-07-01 |

> 全部编译门通过；P9 `./gradlew build`（reobfJar）BUILD SUCCESSFUL。客户端烟测待用户验证。

---

## 8. 实现完成后文档更新

- [`05`](05-schematic-system.md) §5.4：两降级标记 →「已实现」+ 测试结果。
- [`10`](10-testing-guide.md) §9 记录表：litematics 行勾选；补 paste/transmit 步骤。
- CLAUDE.md 核心约束 §3：更新 EasyPlace/镜像修复/粘贴降级现状。
- [`00-INDEX.md`](00-INDEX.md)：加 docs/11 条目。
