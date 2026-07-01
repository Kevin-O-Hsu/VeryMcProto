# 03 · 五个 DataProvider 协议数据内容与数据采集

> 本文回答每个 Provider：**采集什么数据、NBT 字段长什么样、用什么 NMS API 采集、Paper 迁移难度**。
> 原版目录：`OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/dataproviders/` 与 `loggers/`。
> 网络协议帧见 [02](02-network-protocol.md)；采集触发点（Mixin）见 [04](04-mixin-analysis.md)；迁移方案见 [07](07-migration-architecture.md)。

---

## 0. 速查表：5 个 Provider 的数据采集与迁移难度

| Provider | 通道(网络名) | 协议版本 | 主要采集数据 | Paper 数据来源 | 迁移难度 |
|---|---|---|---|---|---|
| `HudDataProvider` | `servux:hud_metadata` | **2** | 元数据/出生点/天气/配方/TPS·MobCap | 大部分 Bukkit API；TPS/MobCap 需 NMS | 中 |
| `EntitiesDataProvider` | `servux:entity_data` | 1 | 方块实体/实体 NBT 查询（含玩家背包过滤） | NMS `saveWithFullMetadata` / `saveWithoutId` | 中 |
| `TweaksDataProvider` | `servux:tweaks` | 1 | tweak 元数据 + NBT 查询（复用 Entities 逻辑） | 同上 | 低 |
| `StructureDataProvider` | `servux:structures` | **2** | 原版结构边界框 | **纯 NMS**（`ChunkAccess.getAllReferences`/`StructureStart.createTag`） | **高** |
| `LitematicsDataProvider` | `servux:litematics` | 1 | 投影投递/粘贴/批量实体 | NMS（投影系统见 [05](05-schematic-system.md)） | 高 |

---

## 1. `HudDataProvider`（配 MiniHUD）⭐ 最核心

> 原版：`dataproviders/HudDataProvider.java`（795 行）。协议通道 `servux:hud_metadata`，协议版本 **2**。

### 1.1 回应的 4 类请求 + 1 类主动推送（packetType）

客户端发起的 C2S 请求 → 服务端 S2C 回应：

| C2S 请求 (type) | S2C 回应 (type) | 服务端方法 | 回应 NBT 字段 |
|---|---|---|---|
| `C2S_METADATA_REQUEST(2)` | `S2C_METADATA(1)` | `sendMetadata(player)` | 见 §1.2 |
| `C2S_SPAWN_DATA_REQUEST(4)` | `S2C_SPAWN_DATA(3)` | `refreshSpawnMetadata` | 出生点 + 种子 |
| `C2S_RECIPE_MANAGER_REQUEST(6)` | `S2C_NBT_RESPONSE_START(10)` + `…_DATA(11)`（分包） | `refreshRecipeManager` | 配方列表（大包） |
| `C2S_DATA_LOGGER_REQUEST(8)` | （订阅）`S2C_DATA_LOGGER_TICK(7)`（周期推送） | `refreshLoggers` | TPS / MobCap |
| —（服务端主动） | `S2C_WEATHER_TICK(5)` | `refreshWeatherData` | 天气 |

### 1.2 `metadata`（元数据，握手首包）

> `HudDataProvider.java:79-88`（构造时填），`sendMetadata` 时整包 merge 下发。

```
CompoundTag metadata:
  name           = "hud_data"            (provider 名)
  id             = "servux:hud_metadata" (通道网络名)
  version        = 2                     (协议版本)
  servux         = "servux-fabric-1.21.11-0.9.4"  (MOD_STRING，移植改 Paper 标识)
  spawnDimension = "minecraft:overworld" (出生点维度 ResourceLocation)
  spawnPosX/Y/Z  = <int>                 (出生点坐标)
  [worldSeed]    = <long>                (仅 share_seed 且玩家有 seed 权限时)
  [Loggers]      = { tps:false, mob_caps:false }  (仅 loggers_enabled 时)
```

### 1.3 出生点数据（`refreshSpawnMetadata`，`:510-537`）

```
id, servux, version, spawnDimension, spawnPosX/Y/Z
+ worldSeed (权限 controlled)
```
- 采集：`spawnPos`（`GlobalPos`）由 `MixinMinecraftServer.prepareLevels` / `MixinServerWorld.setRespawnData` 在出生点变化时回填 `HudDataProvider.setSpawnPos`。
- 种子：`server.overworld().getSeed()`（`checkWorldSeed`，`:725`）。

### 1.4 天气数据（`refreshWeatherData`，`:539-577`）

```
id, servux
+ SetRaining=<int>, isRaining=<bool>        (降雨剩余 tick，不下雨则只 isRaining=false)
+ SetThundering=<int>, isThundering=<bool>
+ SetClear=<int>                            (晴天剩余 tick，>-1 时)
```
- 采集：`MixinServerWorld.advanceWeatherCycle @INVOKE(setRaining)` → `tickWeather(clearTime, rainTime, thunderTime, isRaining, isThunder)` 缓存当前天气计时。
- **Paper 迁移**：可监听 `WeatherChangeEvent` / 读 `World#getWeatherDuration` 等 API（难度低）。

### 1.5 配方数据（`refreshRecipeManager`，`:579-614`）—— 大包，走分包

```
CompoundTag:
  RecipeManager = ListTag [
    { id_reg=<registry>, id_value=<recipeId>, recipe=<Recipe.CODEC 编码的 NBT> }, ...
  ]
```
- 采集：`player.level().recipeAccess().getRecipes()` → 对每个 `RecipeHolder`，用 `Recipe.CODEC.encodeStart(NbtOps.INSTANCE, recipeEntry.value())` 序列化。
- **Paper 迁移**：`Recipe.CODEC` + `NbtOps` 是 NMS，paperweight 可直连；或遍历 Bukkit `Bukkit.recipeIterator()` 自行拼装（保真度略低）。**必须走 PacketSplitter 分包**（配方表常 > 32KiB）。

### 1.6 DataLogger 子系统（TPS / MobCap）⭐

> `loggers/` 目录。HUD 的 `tick` 里按 `update_interval`（logger 模式 15t）周期采集，对每个订阅玩家发 `S2C_DATA_LOGGER_TICK(7)`。

#### 1.6.1 `DataLoggerTPS`（`loggers/DataLoggerTPS.java:35-55`）

```
CompoundTag:
  mspt       = <double>   每毫秒刻时间
  tps        = <double>   tps = 1000 / max(msPerTick, mspt)
  sprintTicks= <long>     冲刺剩余 tick (来自 remainingSprintTicks 私有字段!)
  frozen     = <bool>     tick 是否冻结
  sprinting  = <bool>
  stepping   = <bool>
```
采集 NMS（**高依赖**）：
```java
ServerTickRateManager tm = server.tickRateManager();
boolean frozen = tm.isFrozen(), sprinting = tm.isSprinting();
double mspt = server.getAverageTickTimeNanos() / 1e6;
double tps  = 1000.0 / Math.max(sprinting ? 0 : tm.millisecondsPerTick(), mspt);
long sprintTicks = ((IMixinServerTickManager) tm).servux_getStringTicks(); // ← Mixin 读私有字段 remainingSprintTicks
```
> **Paper 迁移**：`ServerTickRateManager` 需 NMS 访问（`MinecraftServer#getTickRateManager`）；`remainingSprintTicks` 私有 → **反射**（见 [04](04-mixin-analysis.md) `IMixinServerTickManager`）；`getAverageTickTimeNanos` NMS 公开。Paper 还有 `Bukkit.getTPS()` / `Server#getAverageTickTime` 可部分替代。

#### 1.6.2 `DataLoggerMobCaps`（`loggers/DataLoggerMobCaps.java:24-81`）

```
CompoundTag (每维度):
  WorldTick = <long>                世界游戏时间
  cap_count = 8                     MobCategory 数
  cap_data  = ListTag [             每类：
    { current=<int>, cap=<int> }, ... (如 monster/creature/ambient/water_creature/...)
  ]
```
采集 NMS（**极高依赖**）：
```java
for (ServerLevel world : server.getAllLevels()) {                       // Paper API 可用
    NaturalSpawner.SpawnState info = world.getChunkSource().getLastSpawnState();  // ← NMS
    int spawnableChunks = info.getSpawnableChunkCount();
    int divisor = NaturalSpawner.MAGIC_NUMBER;                          // ← 私有常量! 需 AccessWidener/反射 (=289)
    for (entry : info.getMobCategoryCounts().object2IntEntrySet()) {
        int cap = entry.getKey().getMaxInstancesPerChunk() * spawnableChunks / divisor;
        // current = entry.getIntValue(); cap = 上式
    }
}
```
> **Paper 迁移**：`NaturalSpawner.SpawnState` / `MAGIC_NUMBER`(=289) / `getMobCategoryCounts` 全是 NMS 内部 → **反射访问**（见 [04](04-mixin-analysis.md)）。`server.getAllLevels()` Paper 可遍历 `Bukkit.getWorlds()` 取 NMS `CraftWorld.getHandle()`。

### 1.7 HUD 的 settings（权限/节奏）

> `HudDataProvider.java:37-51`

| setting | 默认 | 含义 |
|---|---|---|
| `permission_level` | 0(0-4) | 元数据权限基线 |
| `update_interval` | 40(20-300) | 天气/spawn 刷新 tick |
| `share_weather_status` | false | 是否共享天气 |
| `weather_permission_level` | 0 | 天气权限 |
| `share_seed` | false | 是否共享种子 |
| `seed_permission_level` | 2 | 种子权限 |
| `loggers_enabled` | false | 是否开 logger |
| `loggers_enable_list` | [tps,mob_caps] | 启用的 logger |
| `logger_permission_level` | 0 | logger 权限 |

权限节点：`servux.provider.hud_data` / `.weather` / `.seed` / `.logger` / `.logger.<type>`。

### 1.8 失败重试与 invalid 玩家

`ServuxHudHandler.encodeServerData` 在 `sendPlayPayload` 返回 false（客户端无 MiniHUD/通道未就绪）时累计计数，超过 `MAX_FAILURES=4` 调 `onPacketFailure` → `setPlayerInvalid`（加入 `invalidPlayers`，后续 tick 跳过该玩家，不再刷屏）。`sendMetadata` 时 `removeInvalidPlayer` 恢复。

> **Paper 迁移**：plugin messaging 的 `sendPluginMessage` 不返回成功/失败；需用"客户端是否回 C2S 握手"或 Paper 的通道就绪判断替代；或在玩家 JOIN 后延迟试发 + 重试。

---

## 2. `EntitiesDataProvider`（配 MiniHUD/Tweakeroo 的实体查询）

> 原版：`dataproviders/EntitiesDataProvider.java`（300 行）。协议通道 `servux:entity_data`，协议版本 1。

### 2.1 回应请求

| C2S 请求 | 服务端方法 | 回应内容 |
|---|---|---|
| 元数据请求 | `sendMetadata` | `{name, id, version, servux}` |
| 方块实体 NBT | `onBlockEntityRequest(pos)` | `be.saveWithFullMetadata(registryAccess)` |
| 实体 NBT | `onEntityRequest(entityId)` | 实体 NBT（含玩家背包/末影箱权限过滤） |

### 2.2 关键采集

```java
// 方块实体（:157-169）
BlockEntity be = player.level().getBlockEntity(pos);
CompoundTag nbt = be != null ? be.saveWithFullMetadata(player.registryAccess()) : new CompoundTag();

// 实体（:171-209）
Entity entity = player.level().getEntity(entityId);
NbtView view = NbtView.getWriter(player.level().registryAccess());
entity.saveWithoutId(view.getWriter());
CompoundTag nbt = view.readNbt();
nbt.putString("id", EntityType.getKey(entity.getType()).toString());

// 玩家权限过滤（:191-203）—— 玩家实体时，无背包权限则清空 Inventory/EnderItems
if (entity.getType() == EntityType.PLAYER) {
    if (!hasPlayerInventoryPermission(player)) { nbt.remove("Inventory"); nbt.put("Inventory", new ListTag()); }
    // 末影箱同理
}
```

> **Paper 迁移**：`BlockEntity.saveWithFullMetadata` / `entity.saveWithoutId` / `NbtView` 是 NMS（`NbtView` 走 `util/nbt/NbtView.java`，基于 `TagValueOutput`，见 [04](04-mixin-analysis.md) `IMixinNbtWriteView`）。paperweight 可直连，或反射。玩家背包过滤逻辑纯 NBT 操作，照搬。

### 2.3 权限

`hasNbtQueryPermission(player)`（覆盖原版 `/data get` 权限，见 `MixinServerPlayNetworkHandler_QueryNbt`）。权限节点 `servux.provider.entity_data` + `.inventory`。

---

## 3. `TweaksDataProvider`（配 Tweakeroo）

> 原版：`dataproviders/TweaksDataProvider.java`（373 行）。协议通道 `servux:tweaks`，协议版本 1。

### 3.1 元数据（`:176-199`）

```
name="tweaks_data", id="servux:tweaks", version=1, servux=<MOD_STRING>
```

### 3.2 NBT 查询（复用 Entities 逻辑）

`onBlockEntityRequest` 等与 EntitiesDataProvider 类似，但用 `be.saveWithoutMetadata`（不含位置元数据），权限复用 `EntitiesDataProvider.hasPlayerInventoryPermission`。

### 3.3 潜影盒堆叠——未实现（不可能实现）

原版“空潜影盒可堆叠”由 Mixin 实现（`MixinItemStack.getMaxStackSize` + `MixinHopperBlockEntity` 改 NMS 方法全局返回行为）。Paper 无 Mixin 运行时：反射改不了方法返回值；Bukkit 事件（`InventoryMoveItemEvent` 等）在服务端 `maxStackSize` 仍为 1 的前提下不成立（NMS 内部 `count < maxStackSize` 恒失败）；给物品设 `DataComponents.MAX_STACK_SIZE` 组件是 per-item、影响新生成物品且污染序列化——三条替代路均不通。

故本 provider **不保留**原版 `stackable_shulkers` 系列 setting，也**不下发** `stackingShulkers` / `stackingShulkersMax` 元数据。否则客户端 tweakeroo 会据 `EntityDataManager.checkTweaksConfigs`（`OriginImpl/tweakeroo-*/.../EntityDataManager.java:420`）自动开启客户端堆叠渲染，而服务端无法配合 → 客户端/服务端不一致（堆叠的潜影盒交互时被服务端按原上限拆开）。详见 [04](04-mixin-analysis.md) §1/§4。

---

## 4. `StructureDataProvider`（配 MiniHUD 的结构边界框）⭐ 高难度

> 原版：`dataproviders/StructureDataProvider.java`（557 行）。协议通道 `servux:structures`，协议版本 **2**。

### 4.1 数据内容

```
CompoundTag (发给单个玩家，按其观察的区块):
  Structures = ListTag [
    { <StructureStart.createTag 的原版 NBT>, + "ExpandBox":<bool> }, ...
  ]
  // 原版结构 NBT 形如：
  // { id:"minecraft:village", ChunkX, ChunkZ, BB:[minX,minY,minZ,maxX,maxY,maxZ], Pieces:[...] }
```

### 4.2 采集（**几乎全 NMS**）

触发：`MixinServerChunkLoadingManager.markChunkPendingToSend` → `onStartedWatchingChunk(player, chunk)` → 记录该区块待查询。

```java
// StructureDataProvider.java:358-386 —— 从区块取结构引用
ChunkAccess chunk = world.getChunk(x, z, ChunkStatus.STRUCTURE_REFERENCES, false);   // ← NMS
for (Map.Entry<Structure, LongSet> e : chunk.getAllReferences().entrySet()) {        // ← NMS
    references.merge(e.getKey(), e.getValue(), LongOpenHashSet::new);                // 合并跨区块引用
}

// :439 —— 由引用找结构起点
StructureStart start = chunk.getStartForStructure(structure);                        // ← NMS

// :491-522 —— 序列化结构
StructurePieceSerializationContext ctx = StructurePieceSerializationContext.fromLevel(world);  // ← NMS
CompoundTag nbt = start.createTag(ctx, pos);                                        // ← NMS 原版序列化
nbt.putBoolean("ExpandBox", structure.terrainAdaptation() != TerrainAdjustment.NONE);
Identifier type = BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type());        // ← NMS
```

### 4.3 黑白名单 + 过期刷新

- `shouldSendStructure(id)`：`structure_whitelist_enabled` / `structure_blacklist_enabled` 过滤（`:524-538`）。
- tick（默认 `update_interval`）：对每个注册玩家，刷新其"已观察但超时"的区块结构（`Timeout` 机制，`:290-356`）。

### 4.4 settings

`update_interval`(默认 100) / `timeout` / `structure_whitelist_enabled` + `structure_whitelist` / `structure_blacklist_enabled` + `structure_blacklist`。

> **Paper 迁移**：**整条最难**。`ChunkAccess.getAllReferences` / `getStartForStructure` / `StructureStart.createTag` / `StructurePieceSerializationContext` 全是 NMS 内部，**Paper 无公开 API**。必须用 paperweight NMS 直接调用（这些是公开/包级方法，paperweight dev bundle 可访问，**不需反射**）。触发点 `onStartedWatchingChunk` 改用 Paper 的 `PlayerChunkLimit`/chunk tracking 事件或周期扫描玩家周围区块。详见 [07](07-migration-architecture.md) §Structures。

---

## 5. `LitematicsDataProvider`（配 Litematica）⭐ 大模块

> 原版：`dataproviders/LitematicsDataProvider.java`（424 行）。协议通道 `servux:litematics`，协议版本 1。
> 投影数据结构与传输协议详见 [05-schematic-system.md](05-schematic-system.md)。本节只列协议操作。

### 5.1 提供的操作（packetType / Task）

| 操作 | 方向 | 内容 |
|---|---|---|
| 元数据握手 | C2S→S2C | `{name, id, version, servux}` |
| 方块实体查询 | C2S(pos)→S2C | `onBlockEntityRequest` → `be.saveWithFullMetadata` |
| 实体查询 | C2S(entityId)→S2C | `onEntityRequest` → 实体 NBT |
| 批量实体查询（大包） | C2S(chunkX,Z,minY,maxY)→S2C | `onBulkEntityRequest` → 区块内全部 TileEntities + Entities |
| 投影文件投递（C2S 上传） | C2S 分片→S2C 应答 | 四阶段（Start/Data/End/Cancel），见 [05](05-schematic-system.md) §传输协议 |
| 粘贴请求 | C2S→执行 | `handleClientPasteRequest` → `SchematicPlacement.pasteTo`（需创造模式 + paste 权限） |

### 5.2 `onBulkEntityRequest`（`:243-333`）

```
output.putString("Task", "BulkEntityReply");
output.put("TileEntities", <ListTag: 区块内所有方块实体 saveWithFullMetadata>);
output.put("Entities", <ListTag: AABB 内所有非玩家实体 NBT>);
// 走 PacketSplitter 分包
```
采集：`chunk.getBlockEntitiesPos()` + `world.getEntities(null, bb, NOT_PLAYER)`。

### 5.3 settings / 权限

`permission_level`(0) + `paste_permission_level`(2)。粘贴需 `player.isCreative()`。节点 `servux.provider.litematic_data` / `.paste`。

---

## 6. 数据采集的 NMS 依赖总览（迁移难度地图）

| 采集项 | NMS API | Paper 可达性 | 处置 |
|---|---|---|---|
| 世界种子 | `ServerLevel.getSeed()` | NMS 公开 | paperweight 直连 |
| 出生点 | `MixinServerWorld.setRespawnData` 回填 | Bukkit `World#getSpawnLocation` | 事件/API |
| 天气 | `ServerLevel` weather 字段 | Bukkit `World` API | API |
| 配方 | `Recipe.CODEC` + `NbtOps` | NMS 公开 | paperweight 直连 |
| TPS | `MinecraftServer.tickRateManager` + `getAverageTickTimeNanos` | NMS 公开 + 私有字段 | 直连 + 反射 `remainingSprintTicks` |
| MobCap | `NaturalSpawner.SpawnState` + `MAGIC_NUMBER` | NMS 内部 | **反射** |
| 结构 | `ChunkAccess.getAllReferences` / `StructureStart.createTag` | NMS（包级方法） | paperweight 直连（不需反射） |
| 方块实体 NBT | `BlockEntity.saveWithFullMetadata` | NMS 公开 | paperweight 直连 |
| 实体 NBT | `Entity.saveWithoutId` + `NbtView` | NMS | paperweight 直连 |
| 区块 TileEntities/Entities | `LevelChunk.getBlockEntitiesPos` / `getEntities` | NMS | paperweight 直连 |
| RegistryAccess | `MinecraftServer.registryAccess()` | NMS 公开 | paperweight 直连 |

> **结论**：除 `MAGIC_NUMBER` / `remainingSprintTicks` / NbtView 内部字段需**反射**外，其余都是 NMS 公开或包级方法，paperweight userdev 的 Mojang dev bundle **可直接调用，无需反射**。这与 [04](04-mixin-analysis.md) 的 Mixin 分类一致——Servux 大量"数据采集 Mixin"在 Paper 上反而是"直接 NMS 调用"，因为 paperweight 给了完整 Mojang 映射访问权。
