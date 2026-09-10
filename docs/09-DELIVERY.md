# 09 · VeryMcProto 移植交付说明（注意事项 / 与原版差异 / 降级）

> 本文档是 VeryMcProto（Servux 协议 Mod → Paper 插件移植）的**交付说明**：架构决策、移植进度、API 坑全集、与原版 Servux 的差异/适配点、降级清单、防御性要点、验收指南。
>
> ⚠️ **版本口径**：§1–§9 主体写于 **1.21.11 线**（含 §8 验收指南 / §9 已知限制），系历史交付记录；当前开发线为 **26.1.2**，26.1 变化（reobf 废除、DataTag 载体、协议 v3/v2、MOD_STRING 硬门禁等）以 **§26.1.x 增补章节为准**——两处冲突时一律以 §26.1.x 为当前真值。
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
| 6 | **Litematics (M2)**：握手 + 实体/方块实体/批量查询 + 投影粘贴（schematic 全套；S2C 投递死信链已删） | ✅ 完成 | ✅ |
| 7 | 打磨/降级 + 交付 MD | ✅ 交付（见 §6 降级、§9 限制） | ✅ |

**M1（HUD 协议跑通）代码已就绪**：MiniHUD 客户端连 Paper 测试服，应能显示世界元数据/出生点/天气/TPS/MobCap（需实测，见 §8）。

---

## 3. 核心架构决策

### 3.1 网络层：plugin messaging（方案 A）+ 客户端监听检测
- Servux 5 条通道 = Bukkit plugin messaging channel（`servux:hud_metadata` 等字符串名）。`onPluginMessageReceived` 的 `byte[]` = `FriendlyByteBuf` 裸字节，原版 `fromPacket/toPacket` 逻辑零改动复用。
- **S2C 分片常量 32000**（防御原版客户端 ClientboundCustomPayload 32767 字节解码上限，留余量给 VarInt 头；注：1.21.x Bukkit `MAX_MESSAGE_SIZE` 已上调至 ~1MiB，真正瓶颈是客户端 32767 而非 Bukkit），`PacketSplitter` 照抄并改此常量。
- **客户端支持检测**（替代原版 `ServerPlayNetworking.canSend`）：`ProtocolChannel.send` 检查 `player.getListeningPluginChannels().contains(channel)`。Fabric 客户端装了 masa mod 才会 MC|Register 声明监听 `servux:*`；未装的玩家永远 false → send 返回 false → 失败计数 → 标记 invalid（不刷屏）。比原版 canSend 更可靠。
- **不踢玩家**：plugin messaging 注册的通道由 Paper 内置路由，不会因"未知 payload"踢人（这是选 plugin messaging 而非裸 NMS 发包的根本理由）。

### 3.2 生命周期：Bukkit 事件 + tick 调度（替代 Mixin）
- `ServerLoadEvent` → `onCaptureImmutable(registryAccess)` + `readFromConfig` + `writeToConfig`（对应原版 onServerStarting+Started；ServerLoadEvent 仅触发一次，故合并；不区分 STARTED/RELOAD，两者都重读配置幂等）。
- `PlayerJoinEvent`/`PlayerQuitEvent`/`PlayerRespawnEvent` → provider 统一钩子 `onPlayerJoin/Quit/Respawn`（框架增强，原版在 PlayerListener 按 provider 类型分发）。
- `BukkitRunnable.runTaskTimer(1L)` 每 tick → `tickProviders`。

### 3.3 Mixin 三段式处置（无 Mixin 运行时）
- **读私有字段** → 反射（`Reflect`，缓存 + 防御）。如 `ServerTickRateManager.remainingSprintTicks`、`TagValueOutput.output`。
- **采集触发/生命周期** → Bukkit 事件 / 周期扫描（如天气周期读、Structures 周期扫描）。
- **改服务端行为**（EasyPlace ✅ 已实现[PacketEvents]；UpdateSuppression/Allay 降级省略，见 §6）；潜影盒堆叠不可能实现（已删代码，见 §6）。

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
| **Messenger.MAX_MESSAGE_SIZE 不再是 32768** | 旧文档（docs/02/05/06/07 等）曾称 32768（32KiB），CLAUDE.md §1 已更正 | 1.21.x 已上调（Spigot API `1048576`≈1MiB）；**真 S2C 瓶颈是原版客户端对 ClientboundCustomPayload（未知通道 discarded 解码）的 32767 字节上限**（超过客户端断连）。PacketSplitter S2C 分片 32000 仍正确（防御 32767，余量充足）。**注意**：客户端**已注册 codec 的已知通道**不受 32767 限（Fabric API 把 `fabric:recipe_sync` 注册为 64MB large payload——见 docs/30 §6），JEI 配方大包单发因此安全 |

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
- **潜影盒堆叠——不可能实现，已删除**：原版 Mixin 改 `ItemStack.getMaxStackSize()` / `HopperBlockEntity` 全局行为，Paper 无 Mixin 无法等价。已从 provider 删除 `stackable_shulkers` 系列 setting 与 `stackingShulkers/stackingShulkersMax` 元数据下发（避免客户端 tweakeroo 自动开堆叠渲染而服务端不配合 → 不一致）。
- 修正原版 `ResponseS2CData` L120/121 重复赋值 bug。

### 5.4 Structures（servux:structures，协议版本 2）
- **触发**：原版 Mixin `markChunkPendingToSend` → `onStartedWatchingChunk` 精确触发；Paper 改 **周期扫描**（tick 内遍历玩家 view distance 区块，去重发送）。
- NMS 结构采集（paperweight 直连，不需反射）：`ChunkAccess.getAllReferences/getStartForStructure/StructureStart.createTag/StructurePieceSerializationContext.fromLevel`。
- 黑白名单 setting 保留；timeout 简化为周期全量刷新。

### 5.5 Litematics（servux:litematics，协议版本 2）—— ✅ 全功能（含投影粘贴；S2C 投递已移除）
- **元数据握手 / 方块实体 NBT 查询 / 实体 NBT 查询 / 批量实体查询（onBulkEntityRequest）**：✅ 实现（复用 Entities 的 `NbtView` + `be.saveWithFullMetadata` / `entity.saveWithoutId` + 玩家背包/末影箱权限过滤；批量查询拼 ListTag 走 PacketSplitter 分包）。
- **协议帧（toPacket/fromPacket）**：✅ 照抄原版（四阶段 TransmitStart/Data/End/Cancel 帧定义保留作 C2S 接收路由 + SliceKey + CHANNEL_ID=servux:litematics + 协议版本 2）。
- **投影粘贴（C2S 上传 .litematic，任务化）**：✅ **已实现**（schematic 子系统 `mod/servux/schematic/` 全套移植，详见 [05](05-schematic-system.md)）。客户端上传分片经 `ServuxLitematicaHandler` 重组 → `handleBulkData` 分流：`Litematic-Transmit*` 走 `LitematicaSchematic.receiveFileTransmit` 落盘到 `schematics/` + 粘贴（**该分流对 stock 26.1 客户端不可达**——客户端 `sliceForServux` 调用点整段注释，我方接收路由属协议面超集保留）；活主路 `LitematicaPaste` 走 `LitematicsDataProvider.handleClientPasteRequest` 加载 `SchematicPlacement` 后创建 `PasteTask`（上游 TaskPasteSchematicPerChunkDirect 形态）登记 TaskScheduler 分 tick 粘贴（含 ReplaceMode / PasteLayerBehavior / LayerRange / Interval / 三个忽略布尔，type 16 进度/完成帧随任务下发，需创造模式 + paste 权限）——同步 `pasteTo` 直放已随上游注释停用删除（2026-09-08，见 §26.1.6）。
- **S2C 文件投递命令**：⛔ **已移除（2026-09）**——26.1 stock 客户端 `handleBulkData` 的 Transmit 分流整块注释（无接收端，帧被静默丢弃），上游 `sendTransmitFile` 亦 `@Deprecated(forRemoval)` 零调用点。死信链（`/servux litematic transmit` + `sendTransmitFile` + 文件字节级 16MB 门禁）已物理删除，恢复走 git revert。
- **降级点**（schematic 边缘能力，不影响粘贴主链路）：从世界选区创建/采集投影（保存侧）、Sponge/Vanilla structure 格式导入、DataFixer 旧版转换——servux 服务端只消费现成 .litematic，这些原版保存/转换 API 保留签名返回默认值。迁移笔记见 [`research/01-schematic-algo.md`](research/01-schematic-algo.md) 与 [`02-schematic-world.md`](research/02-schematic-world.md)。

---

## 6. 降级清单（Paper 无 Mixin 的功能）

| 功能 | 原版实现 | Paper 处置 | 影响 |
|---|---|---|---|
| **EasyPlace**（Tweakeroo 精确放置） | Mixin BlockItem/NetworkHandler | ✅ 已实现（「改写放行」：PacketEvents `EasyPlaceListener` 改写 cursor 放行 + `EasyPlaceFixListener` 在 BlockPlaceEvent 修正） | 需服务器装 packetevents 插件；床/门双半格朝向不修正（降级） |
| **UpdateSuppression** | Mixin Level/WorldChunk | 省略 | 协议非必需 |
| **潜影盒可堆叠** | Mixin ItemStack/Hopper | ⛔ 不可能实现（已删代码） | 改 NMS 全局方法行为，Paper 无等价；不下发元数据避免客户端误判 |
| **Allay 收集修复** | Mixin Mob/ItemEntity/Allay | 省略 | 影响小 |
| **镜像修复**（箱子/铁轨/楼梯） | Mixin Block | 内联到粘贴代码 | Litematica 粘贴时修正 |
| **/data get 权限覆盖** | Mixin NetworkHandler | 用 Bukkit op 权限 | 等价 |

---

## 7. 防御性编程要点

- **所有装配 try-catch**：主类 onEnable/onDisable、LifecycleBridge 事件分发、tickProviders，单点异常不影响整体。
- **反射防御**：`Reflect.getOr(obj, field, default)` 失败返回默认值（TPS 的 `remainingSprintTicks` 漂移返回 0，NbtView 的 `output` 失败返回 null）。
- **并发安全**：`PacketSplitter.READING_SESSIONS` 用 `ConcurrentHashMap` + `ReadingSession.receive` 加 `synchronized`；坏包立即丢弃 session。
- **大包保护**：`ProtocolChannel.send` 拒绝超 `Messenger.MAX_MESSAGE_SIZE` 的包（应走 PacketSplitter；真正 S2C 瓶颈是客户端 32767 字节上限）；PacketSplitter `receive` 校验 `maxLength`。
- **客户端未装 mod**：`getListeningPluginChannels` 检测 + MAX_FAILURES 计数 → invalid，不刷屏。
- **配置原子写**：`JsonUtils.writeJsonToFileAsPath` 用 `.tmp` + move 原子写。
- **RegistryAccess 时机**：必须在 `ServerLoadEvent(STARTED)` 后捕获（否则 Recipe/NbtView/palette 拿空注册表）。

---

## 8. 验收指南

### 8.1 构建
```bash
./gradlew build      # 产出 reobf jar（build/libs/VeryMcProto-1.21.11-b1.jar，标准 Paper 可加载）
./gradlew runServer  # 本地起 1.21.11 测试服（2G 堆）
```

### 8.2 M1 验收（HUD）
1. 把 jar 放 `plugins/`，启动 Paper 1.21.11。
2. `/plugins` 见 VeryMcProto 已加载；控制台见 `框架就绪` + `已注册协议 mod: servux`。
3. `/servux list` 列出 6 个 provider。（1.21.11 时代行为——26.1 起已对齐上游：`list` 列**全部 settings 现值**，providers 经 `list <provider>` 过滤。）
4. Fabric 客户端（装 MiniHUD）连入，观察 HUD 显示世界信息/出生点/天气/TPS/MobCap。
5. `/servux set hud_data:share_seed true` + `/servux set hud_data:loggers_enabled true` 测试配置 + TPS/MobCap。（26.1 起 `set` 对齐上游为纯内存——验证持久化需追加 `/servux save`。）
6. `/servux reload` 重读 `plugins/VeryMcProto/servux.json`。

### 8.3 抓包保真对照（关键）
用 Paper 日志 / Wireshark 比对 Fabric+Servux 与 Paper+VeryMcProto 的 `servux:*` 字节流，确认字节级一致（尤其元数据 NBT 字段）。

---

## 9. 已知限制 + 后续工作

- **Litematics（M2）**：投影系统（schematic/）代码量最大（~9000 行），阶段6 完成度见实际进度。
- **通道名纠偏**：CLAUDE.md / docs 表格的通道名（`tweaks_data`/`structure_bounding_boxes`/`litematic_data`）是 **provider 逻辑名**，真实网络名（源码 CHANNEL_ID 实证）是 `servux:tweaks`/`servux:structures`/`servux:litematics`。本移植用真实网络名。
- **MOD_STRING**：~~`servux-paper-1.21.11-b1`（= MOD_ID-平台-插件版本...客户端仅前缀识别不做分段解析）~~ **已被 26.1 推翻（见 §26.1.2 第 2 条）**：26.1 客户端四通道硬门禁校验 `startsWith("servux-fabric-<精确上游id>")`，`paper` 前缀会被整通道静默拒绝——`MOD_TYPE` 恒 `fabric` 伪装 + 精确补丁版本注入，当前真值 `servux-fabric-26.1.2-b3`。
- **方案 B（NMS 发包）预留**：当前方案 A（plugin messaging，S2C 分片 32000）。Payload record 保留，后续大包（Recipe/Litematic）可升级方案 B（NMS `ClientboundCustomPayloadPacket` 保 1MiB 分片）。
- ** Structures 性能**：周期扫描玩家 view distance 区块，玩家多时 CPU 占用；默认 update_interval=100t（5s）+ 只扫 view distance 内 + 去重。

---

## 10. 实测调试修复记录（2026-06，首次 runServer 验收）

> 首次 `runServer` + Fabric 客户端实测发现一批网络层阻断 / 健壮性问题，均已修复。本节记录根因与修复，供验收与后续维护参照。
> 修复均经 4-agent workflow 对抗审查验证（命门调研 + 代码审查 + 对抗性质疑）。

### 10.1 ⚠️ 致命 BUG：所有 servux:* 通道「未注册」（S2C 全部发送失败）

- **症状**：玩家进服后日志狂刷 `sendPlayPayload: 通道未注册 servux:hud_metadata`（5 条通道全部），HUD/Structures 周期推送全失败，客户端收不到任何数据。
- **根因**：`framework/network/ServerPlayHandler.registerServerPlayHandler` 注册通道时**漏调** `handler.setPlayRegistered(channel)`。plugin messaging 通道其实已通过 `Messenger` 注册成功（`outgoing=true`），但 handler 的镜像标志 `payloadRegistered` 恒为 `false` → `sendPlayPayload` 第一道门 `isPlayRegistered()` 永远 false → 整个 S2C 发送在第一道检查就被拦。
  - 注：`DataProviderBase` 有自己的 provider 级 `playRegistered`（`setRegistered(true)`，registerHandler 里设），与 handler 级 `payloadRegistered` 是**两个独立标志**。BUG 本质：只设了 provider 级，handler 级被框架遗漏。
- **修复**：
  - `ServerPlayHandler.registerServerPlayHandler` 末尾补 `handler.setPlayRegistered(channel)`（标志管理收归框架层，对应原版 `registerPlayPayload` 成功后的 setPlayRegistered）。
  - `ServerPlayHandler.unregisterServerPlayHandler` 补 `handler.clearPlayRegistered(channel)`（Paper plugin messaging 通道可反复 register/unregister，比原版 fabric `PayloadTypeRegistry` 语义更干净；`IPluginServerPlayHandler` 新增 `default clearPlayRegistered`，5 个 handler 各覆写）。
- **验证**：workflow 对抗审查确认修复正确充分，disable→enable 循环状态自洽（unregister 在 `existing==handler` 守卫下真删 + 清标志，re-register 的 `putIfAbsent` 命中空槽重新 setPlayRegistered）。

### 10.2 连带 BUG：失败计数 off-by-one + 触发后不清零

- **根因**：5 个 `Servux*Handler.encodeServerData` 的失败计数 `if(!containsKey)put(1); else if(get>MAX)onPacketFailure; else put(get+1)`：
  - `MAX_FAILURES=4` 但实际要**连续失败 6 次**才触发（off-by-one，与常量名/Javadoc/日志宣称的 4 次不符）。
  - 触发 `onPacketFailure` 后 `failures` map 不清零 → 之后每次失败都**重复触发** onPacketFailure/unregister（虽幂等，是噪声）+ 玩家条目常驻（内存泄漏）+ 无自愈。
- **修复**：5 个 handler 统一改为 `int count = failures.getOrDefault(id,0)+1; if(count>=MAX_FAILURES){ failures.remove(id); onPacketFailure(...); } else failures.put(id,count);` —— 第 4 次失败即触发 + 触发后清零，语义与常量名一致。

### 10.3 🔑 命门：1.20.2+ configuration phase 导致 join 时无法识别「客户端装了 mod」

- **现象/风险**：1.20.2+ Mojang 引入 configuration phase（Login 与 Play 之间），客户端声明监听通道（`minecraft:register`）的包在 configuration phase **之后**才到达。故 `PlayerJoinEvent` 时 `player.getListeningPluginChannels()` **通常为空**，且**无固定 N-tick 保证**（SpigotMC 实证：1.20.1 可用的 brand/channel 检测在 1.20.2 失效）。
- **原代码隐患**：HUD `onPlayerJoin` 固定延迟 40t（2s）后 sendMetadata——是「时间猜」而非「事件等」，慢客户端/重连/mod 延迟初始化时首包可能失败。
- **修复（事件驱动握手）**：
  - 框架 `IDataProvider` 新增 `default void onPlayerRegisterChannel(player, channel)`；`LifecycleBridge` 监听 `PlayerRegisterChannelEvent` 分发给 enabled providers。
  - `HudDataProvider` 覆写：客户端声明 `servux:hud_metadata` 时立即 `sendMetadata`（configuration phase 后的可靠信号，取代仅靠 40t 延迟；sendMetadata 幂等且会 `removeInvalidPlayer` 清标记）。
  - 保留 `onPlayerJoin` 40t 兜底 + 客户端主动 C2S `PACKET_C2S_METADATA_REQUEST` 自愈（原版握手语义）。
- **架构**：握手钩子在框架层（`IDataProvider`/`LifecycleBridge`），具体响应在 mod 层（`HudDataProvider`），保持 framework/mod 分层（便于后续迁移其他 mod）。

### 10.4 Structures 周期扫描门控（已废除，2026-09-08）

- 原设计（已删除）：`StructureDataProvider.rescanAndSend` 顶部按 `getListeningPluginChannels` 早退，理由是「避免重活白干 + 发送失败累计计数误注销」。该前提已被 Paper 26.1.2 反编译证伪：能进 `rescanAndSend` 的必是发过 C2S REGISTER 的玩家（= 装有 MiniHUD、能解码），其「未声明」只是 Paper 声明簿记滞后；投递由框架层「同通道 C2S 证明」兜底（`ProtocolChannel.send` NMS 路径，见 §10.6.2）保证，失败计数也只在真实发送异常时累计。上游 servux 无此门。同批删除 `register()` 中不可达的「重复 REGISTER 跳过」分支（唯一调用方 `ServuxStructuresHandler` 恒先 unregister，上游语义 = 每次 REGISTER 全量应答）。

### 10.5 标志对称 + 文档勘误

- `DataProviderManager.updatePacketHandlerRegistration` 禁用分支补 `provider.setRegistered(false)`，与 `registerHandler` 内 `setRegistered(true)` 对称，消除 provider 级标志「撒谎」。
- **`MAX_MESSAGE_SIZE` 文档勘误**：1.21.x Bukkit `Messenger.MAX_MESSAGE_SIZE` 已上调至 ~1MiB（Spigot API `1048576`），旧文档（docs/02/05/06/07、CLAUDE.md §1 旧版）称 `32768` 已过时。**真正 S2C 瓶颈是原版客户端 ClientboundCustomPayload 32767 字节解码上限**。PacketSplitter S2C 分片 32000 仍正确（防御 32767）。已更正 CLAUDE.md §1 + 本文档 §4。`docs/research/*` 为历史研究笔记，保留原貌。

### 10.6 验收关键注意（实测前必读）

1. **客户端必须装 masa mod**（MiniHUD/Litematica/Tweakeroo + servux 协议）：plugin messaging ↔ vanilla custom payload 互通**当且仅当**客户端通过 1.20.5+ `PayloadTypeRegistry.playS2C()` 注册通道 id（masa mod 这么做了）。原版/未装 mod 客户端收不到 servux 数据。
2. **原版客户端 S2C 安全性（2026-09-08 裁决，取代旧「推测性发 S2C」风险注记）**：Paper `CraftPlayer.sendPluginMessage` 按 `channels().contains(channel)` 门控、未声明即静默丢弃（26.1.2 反编译实锤）——这曾吞掉 minihud structures 的进服首握手回复（其客户端 metadata 接受窗口是单次的：`DataStorage.java:288` 开门 → 首个 `%20` tick `:804` 关门，错过即恒 not_connected 直到手动 toggle）。修复：`ProtocolChannel.send` 引入**同通道 C2S 证明兜底**——只对**在本通道发过 C2S 的玩家**（= 装有对应 mod，能发即能收）在 Paper 声明簿记未跟上时走 NMS `new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))` 直发（与 Paper 自身放行路径逐字同构；证明集合随 PlayerQuitEvent 清除）。未发过 C2S 的玩家（vanilla / 未装 mod）**构造性不受兜底影响**，仍走 sendPluginMessage 由 Paper 丢弃——设计**不押注**「原版客户端对未知通道 S2C 的行为」（旧记述「NeoForge/vanilla 倾向断连」系未实证断言：服务端侧对称机制 `CustomPacketPayload.codec` 的 fallback 把未知 id 解码为 DiscardedPayload 后忽略，vanilla 大概率静默丢弃、断连风险主要在 NeoForge 网络层；仍保留为待实测项）。**已知限制**：REGISTER 回复 RTT 超过客户端剩余 `%20` 窗口（概率 ≈ RTT/1000ms）时仍需手动 toggle——与上游 Fabric servux 同源，不劣于上游。附带语义收敛：syncmatica `S2C_VIA_NMS=false` 诊断对照组在「未声明且已证明」场景也会经框架兜底走 NMS（两路 wire 逐字等价、均可达）。
3. **握手时序**：HUD 现有三道保障（onPlayerJoin 40t / onPlayerRegisterChannel 事件 / 客户端 C2S 主动请求），首包可靠性已大幅提升。
4. **C2S 不踢人**：5 条通道均 `registerIncomingPluginChannel`，Paper 内置路由，客户端 C2S 不会因「Invalid payload」被踢。
5. **互通性未真机验证**：workflow 调研确认方案可行（`blocksRuntime=false`），但 Fabric+masa 客户端 ↔ Paper 1.21.11 的端到端字节级 round-trip **仍需实测确认**（FabricMC #4430 是求助帖非权威结论；其作者曾反映「能发不能收」，masa mod 因正确注册 PayloadTypeRegistry.playS2C 而可用）。
6. **可能残留的待实测点**：批量实体查询 AABB 边界、批量实体 Pos NBT 字段、NbtView 反射 `output` 字段运行时验证（子 agent 实现的不确定点，见 §5.5）。

### 10.7 调试系统 + 握手缺陷修复（运行时宏开关）

**背景**：实测进服后 MiniHUD 能看到 servux 版本号（HUD metadata 已送达客户端），但 HUD sync / entity sync 始终 `not_enabled`。定位此问题需丰富的运行时调试数据；同时静态分析发现 entity/tweaks/litematics 三通道存在握手缺陷。

**A. 统一调试门面 `framework/debug/Debug.java`（框架层，全 mod 共用）**
- 运行时可热切换的**宏开关**（`Debug.master`，`volatile`），无需重编译。旧实现开关碎片化（`Reference.DEV_DEBUG` / `ServuxReference.DEV_DEBUG` 为编译期常量、`ServuxLog` 与 `DataProviderManager.debugLog` 各走各的）统一收敛于此。
- **8 分类**避免全开刷屏：`LIFECYCLE / HANDSHAKE / NETWORK / PACKET / TICK / PERMISSION / PROVIDER / CONFIG`。
- 开关来源优先级：命令 `/servux debug`（即时）> 配置 `servux_main:debug_log`（`onServerLoad` 同步）> 编译期 `Reference.DEV_DEBUG`（兜底）。
- 命令：`/servux debug [on|off|all|none|cat <name>|status]`（命令切换**不持久**；持久用 `/servux set servux_main:debug_log true` + reload）。（注：后续实现已升级——`/servux debug` 切换经 `persistDebug` **即时持久化**到 servux.json，无需再 set+reload。）
- 日志点覆盖全数据流：握手（`onPlayerJoin` / `onPlayerRegisterChannel` + `sendMetadata` 摘要）、网络（`ProtocolChannel.send` 各失败原因 + 字节数 + C2S 接收）、包（packet type / bytes / ok / 失败计数触发）、周期 tick、权限判定、provider 状态机、配置加载汇总。
- `ServuxLog.debug` / 原 `DataProviderManager.debugLog` 统一委托 Debug；新增 `IDataProvider.onConfigLoaded()` 框架钩子，让 `ConfigProvider` 在配置加载后同步 Debug（框架层不依赖具体 mod）。

**B. 🔑 握手缺陷修复（entity / tweaks / litematics `not_enabled` 根因）**
- **根因**：`EntitiesDataProvider` / `TweaksDataProvider` / `LitematicsDataProvider` 仅在 `onPlayerJoin` 直推 `sendMetadata`，但 configuration phase 下客户端尚未声明监听对应通道 → `ProtocolChannel.send` 的 `getListeningPluginChannels` 门控返回 false → metadata 被丢弃；三者**此前均无 `onPlayerRegisterChannel` 重写**（仅 Hud 有）→ 客户端就绪后 metadata 永不重发 → 客户端收不到这三条通道的 metadata → 显示 `not_enabled`。
- **修复**：三者补 `onPlayerRegisterChannel`，客户端声明其通道时立即重发 `sendMetadata`（幂等）。HUD 已有该钩子；Structures 握手由 C2S `STRUCTURES_REGISTER` 驱动，`onPlayerRegisterChannel` 仅记录通道声明、不主动推 metadata。
- 与调试目标一致：开 `HANDSHAKE` 日志即可验证 entity/tweaks/litematics 的 metadata 在客户端声明通道后是否 `ok=true`。

**验收方法**：进服后执行 `/servux debug on` + `/servux debug cat all`，重点观察日志：
1. `onPlayerRegisterChannel: <玩家> 声明监听 → servux:entity_data`（确认客户端装了对应 mod 并声明通道）；
2. `entity onPlayerRegisterChannel: ... → 重发 metadata` + `entity sendMetadata → ... ok=true`（确认 metadata 可达）；
3. `send OK servux:entity_data → <玩家> bytes=...`（确认字节实际投递）。
若 `ok=false`，日志会打印 `ProtocolChannel` 的具体失败原因（outgoing 未注册 / 客户端未声明监听 / 超限等）。

---

## §26.1 线迁移实录（1.21.11 → 26.1.2，2026-09）

> 本节是 26.1 迁移的权威记录：构建面变化、协议面 wire 差异（全部对照 `OriginImpl/*-LTS-26.1` 客户端源码逐字实证）、NMS 漂移实测清单。升级到下一版本（26.2+）时按此方法论重做。

### 26.1.1 构建面

| 项 | 1.21.11 | 26.1.2 | 备注 |
|---|---|---|---|
| Java | 21 | **25** | 上游 `servux-LTS-26.1/build.gradle` 明文 "Minecraft 26.1+ uses Java 25"；piston-meta `majorVersion=25` |
| dev bundle | `1.21.11-R0.1-SNAPSHOT` | `26.1.2.build.74-stable` | 26.1 起格式 `<mc>.build.<N>-stable` |
| paperweight | 2.0.0-beta.21 | **2.0.0-beta.23** | 新 bundle 格式跟随 |
| run-paper | 3.0.2 | **3.1.0** | `api.papermc.io/v2` 已下线（sunset），3.1.0 走 Fill v3；且 run-task 3.1.0 要求 Gradle ≥9.7 |
| Gradle wrapper | 9.6.1 | **9.7.1** | run-paper 3.1.0 的插件 API 版本要求 |
| **reobfJar** | 装配进 assemble | **删除** | paperweight 官方文档：26.1 起 Paper 不再支持 Spigot 重映射（Mojang 移除服务端混淆），reobf 插件无法加载；产物 = Mojang 映射 jar |
| api-version | '1.21' | **改 '26.1.2'**（2026-09-10 b2 重发更正） | 初版误保留 '1.21'（仅前向兼容验证）；Modrinth 按 api-version 标注适用版本致错标 1.21 线。官方 1.20.5 起支持三段式、语义 = 低于该值拒载；本插件 MOD_STRING 硬门禁绑死精确补丁，取 '26.1.2' 并入构建终检（api-version ≡ mcVersion） |
| mcVersion | 1.21.11 | **26.1.2** | 必须精确补丁号：客户端 MOD_STRING 门禁 + Fill/dev-bundle/runServer 三处都无裸 "26.1" |

### 26.1.2 协议面 wire 差异（静默失败重灾区，编译器不可见）

1. **协议版本全表**（客户端 `!=` 严格相等，错一个即整通道退网 + UnregisterReply）：
   HUD `ServuxHudPacket.PROTOCOL_VERSION` 2→**3**、Entities 1→**2**、Tweaks 1→**2**、Structures 2→**3**、Litematics 1→**2**。
2. **MOD_STRING 硬门禁**：26.1 客户端四处（minihud HudDataManager:595 / minihud DataStorage:851 / litematica EntityDataManager:544 / tweakeroo EntityDataManager:435）校验 `servux.startsWith("servux-" + MOD_TYPE + "-" + MC_VERSION)`，`MOD_TYPE="fabric"`、`MC_VERSION` = Fabric loader 精确上游 id（26.1.2）。→ `ServuxReference.MOD_TYPE` "paper"→"fabric"（伪装），版本段由 `mcVersion=26.1.2` 注入。1.21.11 客户端只比对版本号不比对前缀，"paper" 才能蒙混——26.1 堵死了。
3. **DataTag 线格式载体**（本轮最大工作量）：业务包 NBT 从 vanilla `writeNbt` 切换为 malilib DataTag：`[int32 大端 压缩后长度][GZIP(具名根 NBT 流)]`，流内 = `[tagType=10][writeUTF("")][条目+TAG_END]`，与 NMS `NbtIo.write(tag, os)` 输出**逐字节兼容**（单测黄金向量实证）→ 实现为 `mod/servux/util/nbt/DataTagIo.java`（~150 行 + 6 项单测），不移植 malilib 18 个 DataTag 类。
   - **分界规则（逐 Type，不是逐通道）**：全通道 metadata 1/2 恒 vanilla NBT；分片 10-13 恒裸字节；其余业务 Type 走 DataTag；**START 大包经 PacketSplitter 的重组整体**也是 DataTag（客户端 `DataByteBufUtils.fromByteBuf` 解析，含 recipe/structures bulk）。
   - 边界：写端恒发 tagType=10 根（上游 EmptyData 的 0x00 单字节根会被 malilib 读端 `readFromNbtStream` 返回 null → 整包丢弃）；读端对 0x00 根容错为空 compound；GZIP 失败（ZipException）回落裸读；长度前缀是**压缩后**字节数、非 VarInt；64MB 上限（SizeTracker.NETWORK_MAX_BYTES）。
   - 幸存者：**Structures 通道包帧全程 vanilla/裸字节**（metadata writeNbt、STRUCTURE_DATA raw）——仅 START 重组整体为 DataTag。
4. **C2S 变化**：
   - `transactionId` 前置 VarInt **整体删除**（请求直读 BlockPos / VarInt entityId / ChunkPos；收端残留吞读会把首字节吃掉 → 全部错位）。
   - 批量重组体（投影上传）无 type VarInt 前缀，改按 NBT `"Task"` 字符串路由（`LitematicaPaste` / `Litematic-Transmit*`）。
   - 新增 `UNREGISTER_REPLY`：HUD=9、Entities=7、Tweaks=7、Litematics=8——服务端 decode→unregister（我方映射为 provider.removePlayer）。
   - METADATA_REQUEST 语义变为「先 unregister 再 register」（再注册）。
5. **枚举增删全表**：HUD +`UNREGISTER_REPLY(9)`；Entities/Tweaks 各 +(7)；Litematica +(8) +task 组 `TASK_REQUEST(14)/TASK_RESPONSE(15)/TASK_STATUS_SYNC(16)/TASK_CANCEL(17)`；**Structures 删 10/11/12**（S2C_SPAWN_METADATA / C2S_REQUEST_SPAWN_METADATA / S2C_WEATHER_DATA——spawn/weather 完全收敛到 HUD 通道，我方 HUD provider 本就承载，仅删 Structures 侧残留分支）。
6. **客户端重组上限 128MB → 16MB**（malilib `PacketSplitter.DEFAULT_MAX_RECEIVE_SIZE`）→ **服务端门禁**：
   - **框架分片入口**（`framework/network/PacketSplitter.MAX_REASSEMBLY_SIZE_S2C = 16_777_216`，量 DataTag 帧总长 = 4 + GZIP = 首包 VarInt `expectedSize`，与客户端严格 `>` 判定同源、恰好相等放行）：`send` 入口在分片循环前对超限帧**整帧拒发**（零分片发出——若无门禁，客户端会销毁重组 session 抛 `PacketSplitterException`，且后续分片以垃圾 expectedSize 重建残留会话污染下一帧，窗口 ≈10–15s）+ warn 日志（log-and-drop）。覆盖 RecipeManager 全量帧 / BulkEntityReply / Structures 全量帧三活跃点 + Entities/Tweaks 两死分支（全部经 `PacketSplitter.send` 单瓶颈）。**上游 servux 26.1 无此预检——我方增强，勿随上游模板回退**（同 `Slice=totalSlices` 前例）。
   - 曾并存的 transmit 文件字节级门禁（`LitematicaSchematic.MAX_TRANSMIT_FILE_SIZE`，含「两级裁分 / 量纲缝隙」论述）随 S2C 投递死信链删除（2026-09，26.1 stock 客户端无接收端）一并成为历史——现存唯一 16MB 服务端预检即上述框架分片入口。
7. **Litematica task 组（14-17）**：26.1 客户端在检测到 servux 服务端后 Fill/Delete 选区**强制**走 `PACKET_C2S_TASK_REQUEST`（无超时回退、无能力探测机制、type 15 的客户端处理本身被上游 TODO 注释）。~~服务端不实现 = 该功能静默不执行（InfoHudSync 渲染空列表、链式 completionListener 不回调；无崩溃无断连）。属上游新功能，超出"迁移"锚点；收到 task 包时明确日志后忽略。后续若要支持：对照 `litematica-LTS-26.1 ToolUtils` + `servux-LTS-26.1 LitematicsDataProvider` 的 task 状态机。~~ **✅ 已实现（2026-09-08，见 §26.1.5/§26.1.6）**——type 14 受理 Fill/Delete（权限 + 创造门 + Box/FillState 解码），Paste 任务经 `LitematicaPaste` 批量路由创建 `PasteTask`；type 16 为三类任务共用进度/完成帧唯一 S2C 出口；type 15 客户端接收端 TODO 故服务端永不发送；type 17 上游同源忽略。
8. **隐私裁剪**（26.1 上游新增，我方 1.21.11 线已内置，无需改动）：查询**他人**玩家实体时按 `nbt_allow_player_inventory` / `player_inventory_permission_level`（ender 同理）清空 `Inventory` / `EnderItems`。
9. **零变化**：JEI payload（`fabric:recipe_sync` / `neoforge:recipe_content`）、syncmatica 全协议（18 PacketType + Exchange + FeatureSet）、PacketSplitter 分片帧（`[VarInt 总长][分片…]`、常量逐字一致）、HUD v3 数据字段（两侧 20 字段名 comm 比对零增删改——差异全在包封层）。（后记：2026-09 JEI 上游更换为 mezz/JustEnoughItems 后完整协议重做，配方同步层 wire 与本节结论仍一致，新增 jei:* 自有 10 通道——见 docs/30。）

### 26.1.3 NMS 漂移实测清单（编译驱动，全库 632 import 仅 39 处断裂）

- **`ChunkPos` 变 record**：`pos.x/pos.z` → `pos.x()/pos.z()`；`new ChunkPos(long)` → `ChunkPos.unpack(long)`；`asLong(x,z)`/`toLong()` → `pack(x,z)`/`pack()`；（`new ChunkPos(BlockPos)` → `containing`，本轮未命中）。
- **天气搬家**：`ServerLevelData.getClearWeatherTime/getRainTime/getThunderTime/isRaining/isThundering` → `ServerLevel.getWeatherData()`（`world/level/saveddata/WeatherData`，同名方法保留）。
- **`displayClientMessage(Component, boolean)`** → **`sendSystemMessage(Component)`**（无 overlay 位）。
- 未漂移（1.21.11 结论仍成立）：`Identifier`、`CompoundTag` Optional 语义、`FriendlyByteBuf`、`DiscardedPayload`、`CustomPacketPayload` 模型；3 处反射串（`remainingSprintTicks` / TagValueInput `"input"` / TagValueOutput `"output"`）经上游 26.1 AccessWidener 实证存活。
- 测试环境注意：`Reference.logger()` 回退从 `Bukkit.getLogger()` 改为 JUL——纯 JVM 单测无 Bukkit 类，旧回退在告警路径会 NPE。

### 26.1.4 实机验证记录（Paper 26.1.2 + Java 25，2026-09-08）

- `./gradlew build`：23/23 单测全绿（PacketSplitter 3 + FeatureSet 4 + LitematicaBitArray 10 + **DataTagIo 6**），产物 `VeryMcProto-26.1.2-b1.jar`（Mojang 映射，无 reobf）。
- `./gradlew runServer`（`JAVA_HOME=F:\jdk` zulu25.0.4.1）：`Starting minecraft server version 26.1.2` → 插件 `v26.1.2-b1` 加载+启用（api-version '1.21' 接受）→ servux / jei_recipe_bridge / syncmatica 三模块注册 → `框架就绪` → `Done (12.334s)`，无 ERROR/SEVERE；PacketEvents 缺席时 EasyPlace 优雅降级日志正常。（历史记录：jei_recipe_bridge 后于 2026-09 上游重做为 jei 模块，见 docs/30。）
- 旧 1.21.11 测试世界保护：`run/server.properties` `level-name=world26` 隔离（旧 world/ 未被触碰），6 个共享配置 `.pre261.bak` 备份，packetevents jar 移出 plugins。
- **客户端互通冒烟（26.1 Fabric 客户端，用户侧最终验收）**：服务端侧已全部验证；协议常量/载体均经客户端源码逐字钉死，但最终裁决需要真实 26.1.2 客户端连服冒烟（HUD 握手 + litematics 握手，docs/10 流程）——无头环境无法运行模组客户端。

### 26.1.5 Litematica task 组（type 14-17）服务端实现（2026-09-08 追加）

> 三轮对抗辩论 + 一次反事实路径熔断（v2 十六类照抄 → v3 三类极简）的产物；协议与可观测行为与上游/客户端逐项等价，裁掉的每一项均验证过"客户端不可观测或上游零调用"。

**架构（`mod/servux/scheduler/` 三类）**：
- `TaskScheduler`：单列表 + 每 tick `runTasks()`（无 synchronized——Paper 单主线程不变式；无双列表——任务 timer 初值 0 等价承载"下 tick 启动"）。
- `FillDeleteTask`：合并上游 TaskBase+TaskProcessChunkBase+MultiPhase+TaskFillArea+TaskDeleteArea。行为真值：分区块队列最近优先（参考点=构造时捕获的原 ServerPlayer 引用，上游冻结语义）+ 每 tick 25ms 预算 + 无进展退出 + currentChunkPos 预检短路；`directFillBox` 逐行照抄（z 外/x 中/y 内层降序、三态替换、容器 clearContent+barrier、flags 0x32、AABB 非玩家 discard）；进度推送仅在进度变化或首推（pendingChunks 空则全程零进度帧）、FINISHED 提前 return 不推末帧；完成链顺序=①消息入缓冲→②Complete 帧→③冲刷缓冲+completed 行（帧先于聊天，门控读活值）；Interval 读作重复周期。
- `InfoHudTaskSync`：type 16 组帧（进度帧 `Type="REMAINING_CHUNKS"` 枚举常量名——客户端 valueOf 无容错；完成帧仅 `InfoHudComplete=true` 缺 InfoHudSync 键）。

**接线四处**：Handler type 14 → `onTaskRequest`（15/16/17 上游同源忽略）；Provider settings `permission_level_tasks(0)`+`player_task_feedback(false)` + 权限节点 `servux.provider.litematic_data.task.fill/.delete` + `onTaskStatusSync` 四道门 + Box 手工解码（客户端 wire 形状 `{pos1:int[3],pos2:int[3],name}` IntArrayTag）+ FillState `tags.read(codec)`；`LifecycleBridge.onTick` 前置 `onServerTickEndPre()`（对应上游 Mixin tickServer RETURN）；`VeryMcProto.onDisable` 前置 `clearTasks()`。

**四项有意偏差（相对上游，代码注释已声明）**：① 启动 ≤1 tick 偏移（Bukkit 心跳先于网络包处理，非逐 tick 等价）；② 玩家退出**不取消**任务（上游跑完语义，保证世界方块结果一致性；发送路径 UUID 解析 null 即跳过=上游"发死连接静默丢弃"等效）；③ 停服 clearTasks（良性增量）；④ 不移植 SEND_COMMAND_FEEDBACK gamerule 翻转（对不发命令的任务零可观测效果，MultiPhase sendCommand 零调用点）。

**验证**：TaskGroupTest 4 项黄金样本（Box IntArrayTag 形状 + REMAINING_CHUNKS 字面量 + 完成帧缺键 + 10 行上限；纯 JVM 测试需 `SharedConstants.tryDetectVersion()+Bootstrap.bootStrap()` 前置——26.1 ChunkPos <clinit> 链到注册表）；build 27/27 全绿；runServer 26.1.2 干净起服（调度器 tick 接线空转无异常）。**真实 Fill/Delete 端到端**：26.1 litematica 客户端创造模式选区 Fill/Delete（ToolUtils 强制走 servux 路径）→ 观察 InfoHud 剩余区块 HUD 与完成消息——用户侧最终验收。

**残留工单（非 task 组锚点）**：~~26.1 客户端 paste 前会置 InfoHudSync 而我方 paste 为同步直放 → 客户端 HUD renderer 滞留（type 12/13 路径）；如需对齐可后续把粘贴迁移到 scheduler 的 TaskPasteSchematicPerChunkDirect 形态~~ **✅ 已收口（2026-09-08，见 §26.1.6）**。

### 26.1.6 paste 任务化——TaskPasteSchematicPerChunkDirect 形态（2026-09-08 追加）

> §26.1.5 残留工单的落地：三轮对抗（R1 六修正 → CF 路径熔断 → R2/R3 收敛）产物。scheduler 由三类扩为**五类**：
> `TaskScheduler` / `LitematicaTask`（新，抽象基类）/ `FillDeleteTask` / `PasteTask`（新）/ `InfoHudTaskSync`。

**架构**：基类 `LitematicaTask` 单点承载共享面（timer、pendingChunks 队列、`updateInfoHudLines` 推帧、`stop()` 完成链、UUID 解析发送）——CF 熔断了 v2 的 ITask 接口形态（完成链会写成两份）。`FillDeleteTask`（25ms 固定预算 / radius 0 / 进度变化门控推帧）与 `PasteTask`（**vanillaTickTime+60ms 动态预算** Direct:63-76 / **radius 1** 周边加载判定 / **每 tick 无条件推帧** Direct:98）只在执行体分叉；`TaskScheduler` 三处类型宽化即接入，`runTasks` 逐字节不变。

**PasteTask 行为真值**（对照上游 Direct:51-138 逐项）：构造期建队（touchedChunks×getBoxesWithinChunk → LayerRange+世界高度双钳制 count>0 入队，盒子用后即弃）；`ignoreBlocks&&ignoreEntities` 早退返回 true **不置 finished**（→ stop 走 paste.failed 文案，上游同源怪癖）；逐 chunk 调 `SchematicPlacingUtils.placeToWorldWithinChunk`（失败留队下 tick 重试）；受理层补 `isPlayerRegistered`+空门 + 解析 `Interval/ChangedBlocksOnly/IgnoreBlocks/IgnoreEntities`（上游 :674-678；三布尔存而不用，上游 Direct:107 同源 TODO）+ 删除受理处即时完成消息（上游 :686-690 注释停用，反馈走 stop 链）。

**实体位置修复族（placeEntitiesToWorldWithinChunk 实体 NBT 段，2026-09-10 补齐）**：上游 26.1 在 create 前对实体 NBT 做六方面位置修复（上游 SchematicPlacingUtils.java:446-513 + :562-565），我方 1.21.11 时代移植未随 26.1 线携带（1.21.11 上游 553 行实证零该族——docs/11 P6 已勘误），本次补齐：① 一切实体 Pos 缺失或 ≠ 世界目标即重写（vanilla 按 NBT Pos 构造实体；悬挂类消除载入期 "invalid hanging position" 告警；修复后 p 为 ② 数据源）；② 四悬挂类（glow_item_frame/item_frame/leash_knot/painting）恒写 TileX/Y/Z=(int) 目标；③ 同分支 block_pos（1.21.5+；缺失或 ≠ BlockPos((int)x,(int)y,(int)z) 重写）；④ leash（键 1.21.5 起小写、拼错静默失效；区域相对值 + off* 平移、ZERO 哨兵跳过；UUID 上游自认不可修、我方不触碰=非 S11 边界）；⑤ home_pos 同式平移但 home_radius>0 才生效、radius 值不改写（上游刻意形态）；⑥ spawn 后 tick 条件扩 `Display || Leashable`。off* = 变换后区域原点+粘贴原点（placeEntitiesToWorldWithinChunk 头部计算 ≡ 上游 :406-408）；leash/home 锚点只平移不随 mirror/rotate 旋转（上游同源）。**有意偏差（顺序合并）**：上游 ④⑤ 在 Rotation 读（上游 :480-482）之后，我方收敛为单方法 `SchematicPlacingUtils.applyEntityPastePositionFixes`（①-⑤）整体前置于 Rotation 读取之前——被修复键集（Pos/TileX/block_pos/leash/home_*）与 Rotation 键无交、origRot 唯一消费点（ItemFrame yaw 修正 / 上游 :551-558）不触被修复键，行为等价。验证：EntityPastePositionFixTest 11 用例纯 JVM 钉死 ①-⑤ 全守卫（字面串输入防同源共错 + Pos wire 形状断言 + 负数 (int) 截断 + UUID 不变断言；"Pos 相等跳写"在真实路径不可观测——Vec3.equals 逐分量 Double.compare，唯一可区分输入为非规范 NaN 位型）；⑥ 与 ③ 墙/地/顶三朝向 item frame 待实机验收（本机无 26.1 Fabric 客户端）。**实施期发现的前置缺陷（已绕开、未修）**：`NbtUtils.readEntityPositionFromTag` 守卫用 `ListTag.getId()`（恒返列表自身类型 9）比对 `TAG_DOUBLE(6)`，恒 false → 恒返 null——旧代码因此"永远走 Pos==null 兜底分支"（①② 的运行时表现被部分掩蔽）；Pos 读取改与 block_pos/leash/home 同型 `tag.read(KEY, Vec3.CODEC).orElse(null)` 直调（≡ 上游 :446-447 弃用自家 NbtUtils 改走 DataTypeUtils 之决策）；该 NbtUtils 缺陷修复后零存活调用点，另议处置。

**Fill/Delete 同步变化（有意，B/R2 轮裁定）**：中断终行文案由恒 `has completed` 修正为 finished 条件（aborted 行，上游 TaskFeedbackListener:75 + en_us:154 逐字）——Fill/Delete 的中断仅在插件停用 `clearTasks` 路径可达，用户可见面极窄；feedbackBuffer 仪式随基类化消除（上游 listener 同栈写读）。

**上游同源 liveness 特性（勿误判为我方 Bug）**：① region 数据损坏的 chunk 无限重试+每 tick 推帧（`placeToWorldWithinChunk` 返回 false 留队）；② 预算取**上一**原版 tick 耗时（服务端持续 >60ms 时 paste 零进展）；③ 单 chunk 内无时间预算（巨型区块单次调用可击穿 60ms）。

**有意偏差（相对上游）**：单 placement 字段（上游两调用点恒 singletonList，按"上游零调用点的泛化即裁"先例裁 multimap）；同步 `SchematicPlacement.pasteTo`/`getEnclosingBox`/`Box.toVanilla` 死代码删除（上游 pasteTo 已 @Deprecated "Use Task Scheduler"）。

**验证**：`./gradlew build` 编译门（`getTickTimesNanos`/`getTickCount` NMS 符号实编验证）+ 全量 40/40 单测绿（新增 `TaskSchedulerTest` 7 用例：timer 首启/interval 钳制反射断言/周期复位/完成移除/同 tick 双任务索引回退/未完成保留+clearTasks）。**真实 paste 端到端**（用户侧最终验收）：26.1 litematica 客户端粘贴大投影 → 观察 InfoHud 剩余区块进度帧分 tick 收敛 + 完成帧清除 HUD renderer（同步直放时代的滞留缺陷就此闭合）。
