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
| **Messenger.MAX_MESSAGE_SIZE 不再是 32768** | 旧文档（docs/02/05/06/07 等）曾称 32768（32KiB），CLAUDE.md §1 已更正 | 1.21.x 已上调（Spigot API `1048576`≈1MiB）；**真 S2C 瓶颈是原版客户端 ClientboundCustomPayload 32767 字节解码上限**（超过客户端断连）。PacketSplitter S2C 分片 32000 仍正确（防御 32767，余量充足） |

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
| **EasyPlace**（Tweakeroo 精确放置） | Mixin BlockItem/NetworkHandler | ✅ 已实现（PacketEvents `EasyPlaceListener`） | 需服务器装 packetevents 插件 |
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

### 10.4 Structures 周期扫描门控

- `StructureDataProvider.rescanAndSend` 顶部加门控：客户端未声明 `servux:structures`（`getListeningPluginChannels` 不含）则跳过采集（不 createTag / 不遍历区块），避免重活白干 + 发送失败累计计数误注销。

### 10.5 标志对称 + 文档勘误

- `DataProviderManager.updatePacketHandlerRegistration` 禁用分支补 `provider.setRegistered(false)`，与 `registerHandler` 内 `setRegistered(true)` 对称，消除 provider 级标志「撒谎」。
- **`MAX_MESSAGE_SIZE` 文档勘误**：1.21.x Bukkit `Messenger.MAX_MESSAGE_SIZE` 已上调至 ~1MiB（Spigot API `1048576`），旧文档（docs/02/05/06/07、CLAUDE.md §1 旧版）称 `32768` 已过时。**真正 S2C 瓶颈是原版客户端 ClientboundCustomPayload 32767 字节解码上限**。PacketSplitter S2C 分片 32000 仍正确（防御 32767）。已更正 CLAUDE.md §1 + 本文档 §4。`docs/research/*` 为历史研究笔记，保留原貌。

### 10.6 验收关键注意（实测前必读）

1. **客户端必须装 masa mod**（MiniHUD/Litematica/Tweakeroo + servux 协议）：plugin messaging ↔ vanilla custom payload 互通**当且仅当**客户端通过 1.20.5+ `PayloadTypeRegistry.playS2C()` 注册通道 id（masa mod 这么做了）。原版/未装 mod 客户端收不到 servux 数据。
2. **勿对原版客户端推测性发 S2C**：1.20.5+ payload registry 下，原版客户端收到未注册 ResourceLocation 的 custom payload **可能断连**（disconnect）也可能静默丢弃——此点 1.21.11 未完全明确（Fabric 倾向丢弃，NeoForge/vanilla 倾向断连）。当前代码用 `getListeningPluginChannels` 门控 + 失败计数规避，但**玩家 join 后首包（40t 延迟的 sendMetadata）仍可能在客户端声明通道前发出**。混合玩家群体（部分原版）需实测确认无断连；若发现问题，可把 HUD 的 onPlayerJoin 40t 兜底改为纯事件驱动（仅 onPlayerRegisterChannel 触发）。
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
- 命令：`/servux debug [on|off|all|none|cat <name>|status]`（命令切换**不持久**；持久用 `/servux set servux_main:debug_log true` + reload）。
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
