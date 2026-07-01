# 04 · Mixin / AccessWidener 逐项分析与迁移去向

> Servux 共 **25 个 Mixin**（`mixins.servux.json` 注册，实际类 26 个含 IMixin 双件）+ **2 个 AccessWidener 字段**（`servux.accesswidener`）。
> **Paper 无 Mixin 运行时，全部不能照抄**。本文逐项给出：目标、注入手法、归属功能、迁移分类、Paper 去向。
> 配套：数据采集细节见 [03](03-dataproviders-detail.md)；降级方案见 [07](07-migration-architecture.md) §降级矩阵。

---

## 0. 迁移分类速查（四大类）

| 分类 | 数量 | 含义 | Paper 处置 |
|---|---|---|---|
| **A. 反射替代**（读私有字段） | ~4 | 读 NMS 私有字段供数据采集 | paperweight NMS 反射（仿 VeryMcBot `reflect/`） |
| **B. 事件/调度替代**（采集触发/生命周期） | ~7 | Mixin 钩 NMS 生命周期点触发逻辑 | Bukkit 事件 + `BukkitRunnable` 调度 |
| **C. 降级/省略**（改服务端行为） | ~9 | 改变 NMS 行为逻辑（放置/抑制/堆叠/镜像） | 降级省略 / PacketEvents / 投影代码内联修正 |
| **D. 调试** | 1 | 仅 DEV_DEBUG 用 | 省略 |
| **AW. AccessWidener** | 2 | 暴露私有字段 | 反射 |

---

## 1. block 包（5 个）—— 几乎全是 Litematica 镜像修复（C 类）

| Mixin | 目标 NMS | 注入 | 归属 | 迁移 |
|---|---|---|---|---|
| `MixinBlock_UpdateSuppression` | `Block` | `@Inject("popResource", HEAD, cancellable)` 抑制下掉落 | UpdateSuppression | **C 省略** |
| `MixinChestBlock` | `ChestBlock` | `@Inject("mirror", HEAD, cancellable)` 修双联箱镜像 | Litematics | **C 内联**：粘贴时修正 |
| `MixinHopperBlockEntity` | `HopperBlockEntity` | `@WrapOperation("inventoryFull"/"isFullContainer" getMaxStackSize)` + `@Inject("canMergeItems")` 修可堆叠潜影盒漏斗行为 (priority 999) | Tweaks | **C 不可能实现**（与 MixinItemStack 配套改 NMS 全局方法行为；单独做无意义） |
| `MixinRailBlocks` | `RailBlock`/`DetectorRailBlock`/`PoweredRailBlock` | `@Inject("rotate", HEAD, cancellable)` 修铁轨 180° 旋转 | Litematics | **C 内联** |
| `MixinStairsBlock` | `StairBlock` | `@Inject("mirror", HEAD, cancellable)` 修楼梯 X 轴镜像 + 形状翻转 | Litematics | **C 内联** |

> **迁移说明**：箱子/铁轨/楼梯的"镜像修复"只在 **Litematica 投影粘贴**（`placement.pasteTo` → 旋转/镜像变换方块状态）时才需要。Paper 端把对应修正逻辑**内联进 `SchematicPlacement` / `SchematicPlacingUtils` 的方块状态变换代码**（纯 BlockState 计算，不走 Mixin）。详见 [05](05-schematic-system.md) §放置与 [07](07-migration-architecture.md) §降级矩阵。

---

## 2. debug 包（1 个，D 类）

| Mixin | 目标 | 注入 | 迁移 |
|---|---|---|---|
| `MixinSharedConstants` | `SharedConstants` | `@Inject("<clinit>", TAIL)` + `@Shadow @Mutable` 设 `IS_RUNNING_IN_IDE=true`（仅 `DEV_DEBUG`） | **D 省略** |

> 依赖 AW 的 `SharedConstants.DEBUG_ENABLED` (mutable)。生产无用，省略。

---

## 3. entity 包（3 个）—— Allay 收集修复（C 类）

| Mixin | 目标 | 注入 | 归属 | 迁移 |
|---|---|---|---|---|
| `MixinAllayEntity` | `Allay` | `@WrapOperation("wantsToPickUp" GameRules.get)` 强制 true 绕过规则 | Entities | **C 省略** |
| `MixinItemEntity` | `ItemEntity` | `@Inject("hurtServer", HEAD)` + `@WrapOperation` 绕过伤害规则 | Entities | **C 省略** |
| `MixinMobEntity` | `Mob` | `@Inject("aiStep", HEAD)` + `@WrapOperation` 绕过 Allay AI 规则 | Entities | **C 省略** |

> 三个 Mixin 协同修复"Allay 在 `doMobSpawning=false` 时仍收集物品"。属服务端行为改造，Paper 省略（可选后续用实体目标事件模拟）。

---

## 4. item 包（2 个）—— EasyPlace + 潜影盒堆叠（C 类）

| Mixin | 目标 | 注入 | 归属 | 迁移 |
|---|---|---|---|---|
| `MixinBlockItem_EasyPlace` | `BlockItem` | `@Inject("getPlacementState", HEAD, cancellable)` (priority 1010)：权限检查 + `PlacementHandler.applyPlacementProtocolV3` | EasyPlace | **C ✅ 已实现**（`EasyPlaceListener` PacketEvents 拦截 `PLAYER_BLOCK_PLACEMENT` + 手动复刻 place 副作用） |
| `MixinItemStack` | `ItemStack` | `@Inject("getMaxStackSize", RETURN, cancellable)` 空潜影盒可堆叠 | Tweaks | **C 不可能实现**（见下） |

> **EasyPlace 迁移**：✅ **已实现**（`EasyPlaceListener`）。PacketEvents 拦截 `use_item_on`，取消包后用 `applyPlacementProtocolV3` 解码精确状态，再手动 `setBlock` / `setPlacedBy`（初始化方块实体）/ 放置音效 / 物品消耗 / `send(ClientboundBlockChangedAckPacket)` 回 ack。详见 [07](07-migration-architecture.md) §降级矩阵。
>
> **潜影盒堆叠——不可能实现**：`MixinItemStack` 改 `ItemStack.getMaxStackSize()` 全局返回值（空潜影盒返回 64），`MixinHopperBlockEntity` 配套改漏斗三方法（`inventoryFull`/`isFullContainer`/`canMergeItems`）。这是改 NMS 核心方法的全局行为，Paper 无 Mixin 运行时无任何 API 等价：反射改不了方法返回值；Bukkit 事件在服务端 `maxStackSize=1` 前提下无法模拟合并（`count < maxStackSize` 恒失败）；设 `MAX_STACK_SIZE` 组件是 per-item 且污染序列化。**已从 `TweaksDataProvider` 删除全部相关遗留代码（setting / 元数据下发 / 死方法），不下发 `stackingShulkers` 元数据**——否则客户端 tweakeroo 据 `EntityDataManager.checkTweaksConfigs` 自动开堆叠渲染而服务端不配合 → 不一致。

---

## 5. nbt 包（2 个，A 类）—— NBT 视图内部访问

| Mixin | 目标 | 注入 | 用途 | 迁移 |
|---|---|---|---|---|
| `IMixinNbtReadView` | `TagValueInput`(interface mixin) | `@Accessor("context")` / `@Accessor("input")` | QueryNbt：取 NBT 读视图的 `ValueInputContextHelper` 与底层 `CompoundTag` | **A 反射**（或改用直接 NBT API） |
| `IMixinNbtWriteView` | `TagValueOutput`(interface mixin) | `@Accessor("ops")` / `@Accessor("output")` | QueryNbt：取写视图的 `DynamicOps` 与 `CompoundTag` | **A 反射** |

> 这两个是 `util/nbt/NbtView.java` 的底层——`NbtView.getWriter(registryAccess)` 给 Entities/Litematics 的 `entity.saveWithoutId` 用。**Paper 迁移**：`NbtView` 可重写为直接用 `Entity.saveWithoutId(CompoundTag)` / `TagValueOutput`（paperweight 直连），绕开这两个 Accessor；或反射访问同名字段。

---

## 6. network 包（2 个）—— EasyPlace + QueryNbt 权限覆盖（C 类）

| Mixin | 目标 | 注入 | 归属 | 迁移 |
|---|---|---|---|---|
| `MixinServerPlayNetworkHandler_EasyPlace` | `ServerGamePacketListenerImpl` | `@WrapOperation("handleUseItemOn" Vec3.subtract)` 强制 `Vec3.ZERO` 去掉命中位置校验 (priority 1010) | EasyPlace | **C ✅ 已实现**（取消包后由 `EasyPlaceListener` 手动 `send(ClientboundBlockChangedAckPacket)` 回 ack） |
| `MixinServerPlayNetworkHandler_QueryNbt` | `ServerGamePacketListenerImpl` | `@WrapOperation("handleBlockEntityTagQuery"/"handleEntityTagQuery" PermissionSet.hasPermission)` 改用 `EntitiesDataProvider.hasNbtQueryPermission` (priority 1005) | QueryNbt | **C**：Paper 的 `/data get` 权限本就受 Bukkit 控制，可省略（或用 Paper 权限） |

---

## 7. server 包（6 个）—— 生命周期与采集触发（B 类为主）⭐

| Mixin | 目标 | 注入 | 触发 | 迁移 |
|---|---|---|---|---|
| `IMixinServerTickManager` | `ServerTickRateManager`(interface) | `@Accessor("remainingSprintTicks")` → `servux_getStringTicks()` | TPS 采集 | **A 反射** |
| `MixinCommandManager` | `Commands` | `@Inject("<init>" @INVOKE AFTER WhitelistCommand.register)` 注册 /servux | 命令注册 | **B**：`plugin.yml` + `CommandExecutor` |
| `MixinMain` | `Main`（服务端引导） | `@Inject("main" @INVOKE AFTER saveDataTag)` + `@Local` 捕获 `RegistryAccess.Frozen` → `onCaptureImmutable` | 捕获注册表 | **B**：`onEnable` 里 `server.registryAccess()` |
| `MixinMinecraftDedicatedServer` | `DedicatedServer` | `@Inject("<init>", TAIL)` → `ServerInitHandler.onServerInit()` | 注册 provider | **B**：`onEnable` 直接注册 |
| `MixinMinecraftServer` | `MinecraftServer` | `@Inject("tickServer" @RETURN ordinal=1)` tickProviders；`prepareLevels` setSpawnPos；`runServer` onServerStarting/Started；`reloadResources` Pre/Post；`stopServer` Pre/Post | 全部生命周期 + tick 调度 | **B**：`ServerLoadEvent` + `BukkitRunnable` tick + reload/stop 钩 |
| `MixinPlayerManager` | `PlayerList` | `@Inject`：`canPlayerLogin`(onClientConnect) / `placeNewPlayer`(join) / `respawn`(respawn) / `op`+`deop`(op/deop) / `remove`(leave) | 玩家事件 | **B**：`AsyncPlayerPreLoginEvent`/`PlayerJoinEvent`/`PlayerRespawnEvent`/op 变更/`PlayerQuitEvent` |

> `MixinMinecraftServer` 与 `MixinPlayerManager` 是**生命周期层的心脏**。Paper 上用 Bukkit 事件 1:1 替换（映射见 [01](01-servux-architecture.md) §10 与 [06](06-fabric-vs-paper.md) §生命周期）。

---

## 8. world 包（5 个）—— 采集 + UpdateSuppression（A/B/C 混合）

| Mixin | 目标 | 注入 | 归属 | 迁移 |
|---|---|---|---|---|
| `IMixinWorldTickScheduler` | `LevelTicks`(interface) | `@Accessor("allContainers")` 取 `Long2ObjectMap<LevelChunkTicks<T>>` | 采集（scheduled tick 数据） | **A 反射**（若需要） |
| `MixinServerChunkLoadingManager` | `ChunkMap` | `@Inject("markChunkPendingToSend", HEAD)` → `StructureDataProvider.onStartedWatchingChunk` | Structures 触发 | **B**：周期扫描玩家观察区块 / Paper chunk 事件 |
| `MixinServerWorld` | `ServerLevel` | `@Inject("setRespawnData", TAIL)` setSpawnPos；`@Inject("advanceWeatherCycle" @INVOKE setRaining)` tickWeather | HUD 出生点/天气采集 | **B**：`World#getSpawnLocation` + `WeatherChangeEvent` |
| `MixinWorld_UpdateSuppression` | `Level` | `implements IWorldUpdateSuppressor` + `@Unique boolean servux_preventBlockUpdates` | UpdateSuppression 状态标记 | **C 省略** |
| `MixinWorldChunk_UpdateSuppression` | `LevelChunk` | `@WrapOperation("setBlockState" slice, isClientSide)` 改为 `shouldPreventBlockUpdates \|\| isClientSide`（抑制更新副作用） | UpdateSuppression | **C 省略** |

---

## 9. AccessWidener（2 个）→ 反射

```text
# servux.accesswidener
mutable    field net/minecraft/SharedConstants DEBUG_ENABLED Z
accessible field net/minecraft/world/level/NaturalSpawner MAGIC_NUMBER I
```

| 字段 | 用途 | 迁移 |
|---|---|---|
| `SharedConstants.DEBUG_ENABLED` (mutable) | `MixinSharedConstants` 设 IDE 调试（DEV_DEBUG only） | 省略（D 类） |
| `NaturalSpawner.MAGIC_NUMBER` (accessible, =**289**) | `DataLoggerMobCaps` 算生物容量除数 `cap = maxInstancesPerChunk * spawnableChunks / 289` | **A 反射**（`Reflect.get(NaturalSpawner.class, "MAGIC_NUMBER")`），或直接硬编码常量 289（已知值，更简单） |

> ⚠️ `MAGIC_NUMBER=289` 是写死的魔数（17×17），**可直接硬编码**避免反射，但注释标明来源（防止版本漂移时 Mojang 改值）。

---

## 10. 迁移工作量预估

| 分类 | 数量 | Paper 实现方式 | 工作量 |
|---|---|---|---|
| A 反射 | ~5（含 2 AW） | `reflect/` 工具类 + 硬编码魔数 | 小 |
| B 事件/调度 | ~9 | Bukkit 事件 + scheduler | 中（逐个映射） |
| C 降级/省略 | ~11 | 多数省略；EasyPlace ✅ 已实现（PacketEvents）；镜像修复内联 | 小（多数直接砍） |
| D 调试 | 1 | 省略 | 0 |
| **合计** | 26 | — | **中等**（B 类是主体，但 1:1 映射清晰） |

> **关键结论**：Mixin 数量看起来吓人（26），但**真正改服务端行为的是 UpdateSuppression 等类（C 类）**（EasyPlace 已用 PacketEvents 实现，不再阻塞），这些功能对“协议能跑起来”非必需，可降级/省略。**A 类（反射）和 B 类（事件）都有干净的 Paper 等价**。完整降级决策见 [07-migration-architecture.md](07-migration-architecture.md) §降级矩阵。
