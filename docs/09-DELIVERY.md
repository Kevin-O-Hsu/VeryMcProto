# 09 · VeryMcProto 移植交付说明（注意事项 / 与原版差异 / 降级）

> 本文档是 VeryMcProto（Servux 协议 Mod → Paper 1.21.11 插件移植）的**交付说明**：架构决策、移植进度、1.21.11 API 坑全集、与原版 Servux 的差异/适配点、降级清单、防御性要点、验收指南。
>
> 配合阅读：[`00-INDEX.md`](00-INDEX.md)、[`07-migration-architecture.md`](07-migration-architecture.md)、[`08-implementation-plan.md`](08-implementation-plan.md)。

---

## 1. 架构总览：framework / mod 分层（核心设计）

为支持**后续迁移其他 Fabric 协议 mod**（不止 Servux），项目严格分层：

```
verymc.top.veryMcProto/
├── VeryMcProto.java              JavaPlugin 主类（框架入口 + 装配 + 注册 mod 模块）
├── Reference.java                框架级全局常量/句柄（plugin / logger / MC_VERSION）
│
├── framework/                    ★ 协议 mod 移植框架（与具体 mod 无关，所有 mod 复用）
│   ├── network/                  通用网络层
│   │   ├── ProtocolChannel       单 plugin messaging 通道封装（register/send/客户端监听检测）
│   │   ├── ChannelManager        全局通道注册表
│   │   ├── PacketSplitter        应用层分包器（ConcurrentHashMap + synchronized，方案A 常量 32000）
│   │   ├── FriendlyByteBufs      byte[]↔FriendlyByteBuf 桥接 + encodePayload
│   │   ├── IServerPayloadData    协议数据接口（照抄）
│   │   ├── IPluginServerPlayHandler  一条通道收发抽象（Paper 版，去 Fabric）
│   │   └── ServerPlayHandler     handler 注册表（联动 ChannelManager）
│   ├── dataproviders/            Provider 契约
│   │   ├── IDataProvider / DataProviderBase / DataProviderManager
│   ├── settings/                 配置项系统（Int/Bool/String/StringList/List + 回调）
│   ├── event/LifecycleBridge     Bukkit 事件→Provider 生命周期（Join/Quit/Respawn/ServerLoad/tick）
│   ├── nms/Nms                   Bukkit↔NMS 转换（CraftPlayer/CraftWorld/CraftServer）
│   ├── permission/Perms          权限（替代 fabric-permissions-api）
│   ├── reflect/Reflect           NMS 反射（线程安全缓存 + 防御 getOr/trySet）
│   ├── util/JsonUtils, StringUtils  Gson 配置 / i18n（Component.translatable 兜底）
│   └── ModModule                 协议 mod 模块抽象（onRegister 注册 provider）
│
└── mod/servux/                   ★ 首个 mod：Servux
    ├── ServuxReference           Servux 常量（5 条通道真实网络名 + MOD_STRING）
    ├── ServuxLog                 debug 日志（ConfigProvider.hasDebugMode）
    ├── app/ServuxModule          implements ModModule（注册 6 个 Provider）
    ├── network/                  5 条通道 Packet + Handler（ServuxHudPacket/... ）
    ├── dataproviders/            ConfigProvider(servux_main) + Hud/Entities/Tweaks/Structure/Litematics
    ├── loggers/                  TPS / MobCap 采集
    ├── util/nbt/NbtView          NBT 视图（反射绕开 Mixin）
    ├── util/MathUtils
    ├── command/ServuxCommand     /servux 命令
    └── schematic/                Litematica 投影系统（阶段6）
```

**新增 Fabric 协议 mod 的步骤**：在 `mod/<newmod>/` 下实现 `XxxModule implements ModModule`（注册该 mod 的 provider），在主类 `onEnable` 调 `new XxxModule().onRegister(...)`。复用 `framework/` 全部基础设施（网络/配置/事件/反射/命令）。**无需改框架**。

---

## 2. 移植进度

| 阶段 | 内容 | 状态 | 编译 |
|---|---|---|---|
| 0 | 环境/构建（paperweight userdev + reobf） | ✅ 完成 | ✅ |
| 1 | 框架网络层（ProtocolChannel/ChannelManager/PacketSplitter/Handler） | ✅ 完成 | ✅ |
| 2 | 架构层（Provider框架/生命周期事件桥/settings/ConfigProvider/命令/权限） | ✅ 完成 | ✅ |
| 3 | **HUD Provider (M1)**：元数据/出生点/天气/配方/TPS/MobCap | ✅ 完成 | ✅ |
| 4 | Entities / Tweaks Provider | ✅ 完成 | ✅ |
| 5 | Structures Provider（周期扫描） | ✅ 完成 | ✅ |
| 6 | Litematics（精简：握手+实体查询，投影投递/粘贴降级） | ✅ 完成（精简） | ✅ |
| 7 | 打磨/降级 + 交付 MD | ✅ 交付（见 §6 降级、§9 限制） | ✅ |

**M1（HUD 协议跑通）代码已就绪**：MiniHUD 客户端连 Paper 测试服，应能显示世界元数据/出生点/天气/TPS/MobCap（需实测，见 §8）。

---

## 3. 核心架构决策

### 3.1 网络层：plugin messaging（方案 A）+ 客户端监听检测
- Servux 5 条通道 = Bukkit plugin messaging channel（`servux:hud_metadata` 等字符串名）。`onPluginMessageReceived` 的 `byte[]` = `FriendlyByteBuf` 裸字节，原版 `fromPacket/toPacket` 逻辑零改动复用。
- **S2C 分片常量 32000**（plugin messaging 单包上限 32768，留余量），`PacketSplitter` 照抄并改此常量。
- **客户端支持检测**（替代原版 `ServerPlayNetworking.canSend`）：`ProtocolChannel.send` 检查 `player.getListeningPluginChannels().contains(channel)`。Fabric 客户端装了 masa mod 才会 MC|Register 声明监听 `servux:*`；未装的玩家永远 false → send 返回 false → 失败计数 → 标记 invalid（不刷屏）。比原版 canSend 更可靠。
- **不踢玩家**：plugin messaging 注册的通道由 Paper 内置路由，不会因"未知 payload"踢人（这是选 plugin messaging 而非裸 NMS 发包的根本理由）。

### 3.2 生命周期：Bukkit 事件 + tick 调度（替代 Mixin）
- `ServerLoadEvent` → `onCaptureImmutable(registryAccess)` + `readFromConfig` + `writeToConfig`（对应原版 onServerStarting+Started；ServerLoadEvent 仅触发一次，故合并；不区分 STARTED/RELOAD，两者都重读配置幂等）。
- `PlayerJoinEvent`/`PlayerQuitEvent`/`PlayerRespawnEvent` → provider 统一钩子 `onPlayerJoin/Quit/Respawn`（框架增强，原版在 PlayerListener 按 provider 类型分发）。
- `BukkitRunnable.runTaskTimer(1L)` 每 tick → `tickProviders`。

### 3.3 Mixin 三段式处置（无 Mixin 运行时）
- **读私有字段** → 反射（`Reflect`，缓存 + 防御）。如 `ServerTickRateManager.remainingSprintTicks`、`TagValueOutput.output`。
- **采集触发/生命周期** → Bukkit 事件 / 周期扫描（如天气周期读、Structures 周期扫描）。
- **改服务端行为**（EasyPlace/UpdateSuppression/潜影盒堆叠/Allay）→ 降级省略（见 §6）。

---

## 4. ⚠️ 1.21.11 API 坑全集（移植/维护必读）

> 这些是实际编译验证踩过的坑，升级 MC 版本时务必重新核对。

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **ResourceLocation 改名 Identifier** | `net.minecraft.resources.ResourceLocation` 找不到 | 用 `net.minecraft.resources.Identifier`（1.21.5+ Mojang 重命名，致敬 Yarn）。原版 Servux 用的就是 Identifier |
| **ResourceKey.location() 改名** | `.location()` 不存在 | 用 `.identifier()`（`dimension().identifier()`、`recipeEntry.id().identifier()` 等） |
| **CompoundTag.getAllKeys()** | 找不到 | 用 `keySet()` |
| **player.serverLevel()** | 不存在 | 用 `(ServerLevel) player.level()` |
| **ServerLevel.getSharedSpawnPos()** | 不存在 | 用 Bukkit `world.getWorld().getSpawnLocation()` → BlockPos |
| **LevelData 天气方法** | `getLevelData().getClearWeatherTime()` 找不到（LevelData 基类无） | cast `(ServerLevelData) overworld.getLevelData()` 再调天气方法 |
| **CommandSourceStack.hasPermission(int)** | 签名变成 (Permission,String) | 不用；op 等级改 `PlayerList.ops()` 反射或 Bukkit `isOp()`（Perms 用 isOp 二分） |
| **PlayerList.ops() / ServerPlayer.getServer()** | 漂移 | Perms 用 `Nms.server()` + `isOp()` 规避 |
| **MinecraftServer.getProfiler()** | 不存在 | `IDataProvider.tick` **去掉 ProfilerFiller 形参**（profiler 仅性能分析，去掉不影响功能） |
| **ServerLoadEvent.LoadType.STARTED** | 枚举值名漂移 | 去掉类型判断，处理所有 ServerLoadEvent |
| **CompoundTag 1.21.5+ 语义** | `getXxx` 返回 Optional | 用 `getBooleanOr/getStringOr/getIntOr/...` 或 `.orElse()`；`putXxx` 返回 void（非链式）；`merge` 返回 void/CompoundTag |
| **FriendlyByteBuf.readNbt()** | 返回 `CompoundTag`（非 Optional） | 直接用；大包用 `readNbt(NbtAccounter.unlimitedHeap())` |
| **List.copyOf(enum[])** | 不接受数组 | 用 `Arrays.asList(values())`（ImmutableList.copyOf 已移除） |
| **commons-lang3 Fraction** | 不保证暴露 | MathUtils 去掉 Fraction 重载 |
| **@NotNull/@Environment 注解** | Fabric 专有 | 全部删除 |

---

## 5. 与原版 Servux 的差异 / 适配点（逐通道）

### 5.1 HUD（servux:hud_metadata，协议版本 2）
- `Reference` → `ServuxReference`；`Permissions.check` → `Perms.check`。
- `registerHandler`：删除 `registerPlayPayload/registerPlayReceiver`（Paper plugin messaging 注册即收发），仅 `ServerPlayHandler.registerServerPlayHandler(HANDLER)` + `setRegistered(true)`。
- `sendMetadata`：删除 NMS `networkHandler` 重载分支，统一走 plugin messaging（`HANDLER.sendPlayPayload`）。
- `tick`：去掉 `ProfilerFiller` 形参与 `profiler.push/pop`。
- **天气采集**：原版 Mixin `advanceWeatherCycle` → `tickWeather`，Paper 改 **tick 内周期读** `ServerLevelData` 天气计时（`getClearWeatherTime/getRainTime/getThunderTime` + `isRaining/isThundering`）。
- **出生点**：原版 Mixin 回填 `setSpawnPos`，Paper 用 `updateSpawnFromServer`（Bukkit `World.getSpawnLocation`）。
- **onPlayerJoin 握手**：plugin messaging 通道握手需时间，延迟 40t（2s）后 `sendMetadata`（原版走 NMS 首发保真）。

### 5.2 Entities（servux:entity_data，协议版本 1）
- 方块实体 NBT：`be.saveWithFullMetadata(registryAccess)`（NMS 公开，照抄）。
- 实体 NBT：`NbtView.getWriter` + `entity.saveWithoutId`（NbtView 重写绕开 IMixinNbtWriteView，反射 `TagValueOutput.output`）。
- `hasNbtQueryPermission`：原版 NMS `Permissions.COMMANDS_GAMEMASTER` → 按 op level 2（等价 `/data get` 默认）。

### 5.3 Tweaks（servux:tweaks，协议版本 1）
- NBT 查询复用 `EntitiesDataProvider` 权限方法。
- 元数据下发 `stackingShulkers/stackingShulkersMax` 保留。
- **降级**：潜影盒"可堆叠"服务端行为省略（仅下发元数据让客户端配合显示，服务端不真堆叠）。
- 修正原版 `ResponseS2CData` L120/121 重复赋值 bug。

### 5.4 Structures（servux:structures，协议版本 2）
- **触发**：原版 Mixin `markChunkPendingToSend` → `onStartedWatchingChunk` 精确触发；Paper 改 **周期扫描**（tick 内遍历玩家 view distance 区块，去重发送）。
- NMS 结构采集（paperweight 直连，不需反射）：`ChunkAccess.getAllReferences/getStartForStructure/StructureStart.createTag/StructurePieceSerializationContext.fromLevel`。
- 黑白名单 setting 保留；timeout 简化为周期全量刷新。

### 5.5 Litematics（servux:litematics，协议版本 1）—— ⚠️ 精简移植（投影降级）
- **元数据握手 / 方块实体 NBT 查询 / 实体 NBT 查询 / 批量实体查询（onBulkEntityRequest）**：✅ 正常实现（复用 Entities 的 `NbtView` + `be.saveWithFullMetadata` / `entity.saveWithoutId` + 玩家背包/末影箱权限过滤；批量查询拼 ListTag 走 PacketSplitter 分包）。
- **协议帧（toPacket/fromPacket）**：✅ 照抄原版（含四阶段 TransmitStart/Data/End/Cancel + SliceKey + CHANNEL_ID=servux:litematics + 协议版本1）。
- **投影文件投递（客户端上传 .litematic）+ 粘贴（pasteTo）**：❌ **降级**——schematic 系统（BitArray/Palette/LitematicaSchematic/placement，~9000 行）本次**未移植**（单 session 工作量限制）。Handler 收到上传分片用 `PacketSplitter.receive` 重组后【不】加载为投影（`ServuxLog.debug` 记录忽略）；粘贴请求静默忽略。
- **效果**：Litematica 客户端能握手 + 查询实体/方块实体/批量实体；**上传投影 / 粘贴投影降级（不生效）**。
- **待移植**（M2 完整）：`schematic/` 全套（container/transmit/selection/conversion/LitematicaSchematic/placement）+ `NbtUtils` + `util/data/tag` 子系统，迁移笔记见 [`docs/research/01-schematic-algo.md`](research/01-schematic-algo.md) 与 [`02-schematic-world.md`](research/02-schematic-world.md)。
- **subagent 实现的不确定点（需实测验证）**：① 批量实体查询的 AABB 边界（+1 偏移推测）；② 批量实体 `Pos` NBT 字段（DoubleTag ListTag 顺序/键）；③ onPlayerJoin 同步 sendMetadata（握手可能首次失败，靠客户端 C2S 重请求恢复）。

---

## 6. 降级清单（Paper 无 Mixin 的功能）

| 功能 | 原版实现 | Paper 处置 | 影响 |
|---|---|---|---|
| **EasyPlace**（Tweakeroo 精确放置） | Mixin BlockItem/NetworkHandler | 省略（选做：PacketEvents 拦截） | 协议非必需 |
| **UpdateSuppression** | Mixin Level/WorldChunk | 省略 | 协议非必需 |
| **潜影盒可堆叠** | Mixin ItemStack/Hopper | 省略（仅下发元数据） | 客户端配合显示，服务端不堆叠 |
| **Allay 收集修复** | Mixin Mob/ItemEntity/Allay | 省略 | 影响小 |
| **镜像修复**（箱子/铁轨/楼梯） | Mixin Block | 内联到粘贴代码 | Litematica 粘贴时修正 |
| **/data get 权限覆盖** | Mixin NetworkHandler | 用 Bukkit op 权限 | 等价 |

---

## 7. 防御性编程要点

- **所有装配 try-catch**：主类 onEnable/onDisable、LifecycleBridge 事件分发、tickProviders，单点异常不影响整体。
- **反射防御**：`Reflect.getOr(obj, field, default)` 失败返回默认值（TPS 的 `remainingSprintTicks` 漂移返回 0，NbtView 的 `output` 失败返回 null）。
- **并发安全**：`PacketSplitter.READING_SESSIONS` 用 `ConcurrentHashMap` + `ReadingSession.receive` 加 `synchronized`；坏包立即丢弃 session。
- **大包保护**：`ProtocolChannel.send` 拒绝超 32KiB 包（应走 PacketSplitter）；PacketSplitter `receive` 校验 `maxLength`。
- **客户端未装 mod**：`getListeningPluginChannels` 检测 + MAX_FAILURES 计数 → invalid，不刷屏。
- **配置原子写**：`JsonUtils.writeJsonToFileAsPath` 用 `.tmp` + move 原子写。
- **RegistryAccess 时机**：必须在 `ServerLoadEvent(STARTED)` 后捕获（否则 Recipe/NbtView/palette 拿空注册表）。

---

## 8. 验收指南

### 8.1 构建
```bash
./gradlew build      # 产出 reobf jar（build/libs/VeryMcProto-1.0.0.jar，标准 Paper 可加载）
./gradlew runServer  # 本地起 1.21.11 测试服（2G 堆）
```

### 8.2 M1 验收（HUD）
1. 把 jar 放 `plugins/`，启动 Paper 1.21.11。
2. `/plugins` 见 VeryMcProto 已加载；控制台见 `框架就绪` + `已注册协议 mod: servux`。
3. `/servux list` 列出 6 个 provider。
4. Fabric 客户端（装 MiniHUD）连入，观察 HUD 显示世界信息/出生点/天气/TPS/MobCap。
5. `/servux set hud_data:share_seed true` + `/servux set hud_data:loggers_enabled true` 测试配置 + TPS/MobCap。
6. `/servux reload` 重读 `plugins/VeryMcProto/servux.json`。

### 8.3 抓包保真对照（关键）
用 Paper 日志 / Wireshark 比对 Fabric+Servux 与 Paper+VeryMcProto 的 `servux:*` 字节流，确认字节级一致（尤其元数据 NBT 字段）。

---

## 9. 已知限制 + 后续工作

- **Litematics（M2）**：投影系统（schematic/）代码量最大（~9000 行），阶段6 完成度见实际进度。
- **通道名纠偏**：CLAUDE.md / docs 表格的通道名（`tweaks_data`/`structure_bounding_boxes`/`litematic_data`）是 **provider 逻辑名**，真实网络名（源码 CHANNEL_ID 实证）是 `servux:tweaks`/`servux:structures`/`servux:litematics`。本移植用真实网络名。
- **MOD_STRING**：`servux-paper-1.21.11-1.0.0`（保持 `servux-` 前缀供客户端识别；版本协商走各通道 protocol version，不变）。
- **方案 B（NMS 发包）预留**：当前方案 A（plugin messaging，S2C 分片 32000）。Payload record 保留，后续大包（Recipe/Litematic）可升级方案 B（NMS `ClientboundCustomPayloadPacket` 保 1MiB 分片）。
- ** Structures 性能**：周期扫描玩家 view distance 区块，玩家多时 CPU 占用；默认 update_interval=100t（5s）+ 只扫 view distance 内 + 去重。
