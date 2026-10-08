# 全局架构 · 网络框架 · Mixin→Paper 迁移参考

> 本文是全局层的唯一权威：分层架构与框架契约（§1–§2）、网络框架与三种 S2C 投递路径（§3）、生命周期（§4）、Mixin→Paper 统一处置矩阵（§5）、Fabric↔Paper 逐域对照（§6）、构建与工具链（§7）。
> **字节上限裸值唯一权威在本文 §3.4**（域文档只写行为语义+指针）；**Mixin 处置唯一权威在本文 §5**（syncmatica 逐文件迁移标注的唯一权威在 [syncmatica-architecture.md](syncmatica-architecture.md) §2）。
> Servux 通道 wire 细节见 [servux-protocol.md](servux-protocol.md)；各 Provider 数据内容见 [servux-providers.md](servux-providers.md)；syncmatica/JEI 见各自文档；命令/权限/配置/升级 SOP 见 [operations.md](operations.md)；仓库权威说明见 [../AGENTS.md](../AGENTS.md)。

---

## 1. 项目定位与可行性结论

**VeryMcProto 把 Fabric 端独特的 Mod Protocol（协议 Mod）以纯 Paper 插件形式重新实现**。客户端仍是 masa / syncmatica / JEI 的 Fabric Mod；本插件在 Paper 服务端复刻它们期待的网络协议与数据采集，使「Fabric 客户端 + Paper 服务端」像「Fabric 客户端 + 原版服务端 Mod」一样工作。三个 mod（Servux / JEI / Syncmatica）已全部落地并实机验证。

| 问题 | 结论 | 依据 |
|---|---|---|
| 网络协议能在 Paper 复刻吗？ | ✅ **能，且无需第三方库** | plugin messaging channel 直接映射原版 `CustomPacketPayload`；`byte[]` = `FriendlyByteBuf` 裸字节。实证 [FabricMC #4430](https://github.com/orgs/FabricMC/discussions/4430)、[Paper plugin-messaging 文档](https://docs.papermc.io/paper/dev/plugin-messaging/) |
| 能访问采集所需的 NMS 吗？ | ✅ **能** | paperweight userdev 提供 Mojang 全映射 `net.minecraft.*`（26.1 起 Mojang 移除服务端混淆，映射即运行时真名）；绝大多数采集 API 是公开/包级方法 |
| Mixin 怎么办？ | ✅ **可控** | 读私有→反射；生命周期→Bukkit 事件；改行为→降级省略/内联/PacketEvents。见 §5 |
| 大包（配方/投影）能发吗？ | ✅ **能** | PacketSplitter 分片（§3.3）；已知通道大包走 NMS 直发（§3.2） |
| 有阻塞性难点吗？ | ⚠️ 仅少量「改服务端行为」特性降级（EasyPlace 已实现） | 见 §5.3 |

**保真度结论**：网络协议字节 100%（同一 `FriendlyByteBuf`/`CompoundTag`/DataTag 线格式）；通道/版本号 100%；数据采集 ≈95%（个别反射字段版本敏感）；服务端行为增强部分降级（EasyPlace ✅ / UpdateSuppression·Allay 省略 / 潜影盒堆叠不可能）。

---

## 2. 分层架构与框架契约

### 2.1 包结构（as-built）

```
verymc.top.veryMcProto/
├── VeryMcProto.java              JavaPlugin 主类（框架入口 + 装配 + 依次注册三个 mod + 命令）
├── Reference.java                框架级常量（MC_VERSION / PLUGIN_VERSION——类加载时读 version.properties）
│
├── framework/                    ★ 协议 mod 移植框架（与具体 mod 无关，所有 mod 复用）
│   ├── network/                  通道封装（ChannelManager/ProtocolChannel）、分片（PacketSplitter）、
│   │                             字节桥接（FriendlyByteBufs）、Handler 注册表（ServerPlayHandler）
│   ├── dataproviders/            Provider 契约（IDataProvider/DataProviderBase/DataProviderManager）
│   ├── event/LifecycleBridge     Bukkit 事件 → Provider 生命周期（Join/Quit/Respawn/ServerLoad/RegisterChannel/tick）
│   ├── debug/DebugSystem         通用调试日志引擎（多 mod 独立实例；FrameworkDebug 门面绑定）
│   ├── permission/Perms          权限（替代 fabric-permissions-api）
│   ├── reflect/Reflect           NMS 反射（线程安全缓存 + 防御式降级）
│   ├── nms/Nms                   Bukkit ↔ NMS 转换
│   ├── settings/                 Servux 配置项体系（Bool/Int/String/StringList/List + 回调）
│   └── util/                     JsonUtils（Gson pretty + 原子写）/ StringUtils
│
└── mod/                          ★ 协议 mod 层（每个被移植的 Fabric 协议 mod 一个目录单元）
    ├── servux/                   app/ServuxModule + command/ + dataproviders/（6 Provider）+ network/（5 Handler+Packet）
    │                             + easyplace/ + loggers/ + scheduler/（task 组五类）+ schematic/ + util/
    ├── jei/                      app/JeiModule + network/（含 RecipeSyncJoinOrderer；payload/ 为其子包、含 legacy/ 子层）
    │                             + transfer/ + cheat/ + recipesync/ + config/ + command/
    └── syncmatica/               app/SyncmaticaModule + communication/（+exchange/）+ data/（+litematica/）
                                  + extended_core/ + network/ + service/ + util/
```

**新增协议 mod 的步骤**：在 `mod/<newmod>/` 实现 `ModModule`（或自管装配如 syncmatica/jei），在主类 `onEnable` 注册，在 `plugin.yml` 加命令/权限——**无需改动框架**。

### 2.2 启动流程（Paper 版，as-built）

```
VeryMcProto.onEnable():
  1. 初始化框架（ChannelManager / DataProviderManager / LifecycleBridge）
  2. 依次注册 servux / jei / syncmatica 三个模块（各自 try-catch，单点失败降级不阻断）
  3. 注册 /servux /jei /syncmatica 命令
  4. ServerLoadEvent(STARTED) 后捕获 RegistryAccess → DataProviderManager.onCaptureImmutable
     + readFromConfig（servux.json 加载）
  5. LifecycleBridge：PlayerJoin/Quit/Respawn/RegisterChannel 事件桥 + runTaskTimer 每 tick tickProviders

onPlayerJoin（PlayerJoinEvent）:
  对每个 enabled provider → provider.onPlayerJoin(player)（40t 延迟 sendMetadata + onPlayerRegisterChannel 兜底）

onDisable():
  各模块 disable（syncmatica placements.json 原子落盘、scheduler clearTasks 等）
```

### 2.3 `DataProviderManager` —— Provider 注册表/调度器/配置中枢

源自原版 `fi.dy.masa.servux.dataproviders.DataProviderManager`，单例。核心数据结构：`providers: HashMap<String, IDataProvider>`（逻辑名→provider）+ `providersImmutable` 快照 + `providersTicking` 子集 + 早期捕获的 `RegistryAccess.Frozen immutable`。

| 方法 | 作用 |
|---|---|
| `registerDataProvider(provider)` | 按名（lowercase）登记，去重 |
| `setProviderEnabled(name/provider, bool)` | 启用/禁用：调 `provider.setEnabled` + `updatePacketHandlerRegistration` + 维护 `providersTicking` |
| `updatePacketHandlerRegistration(provider)` | 启用→`registerHandler()`（注册通道）；禁用→`unregisterHandler()` |
| `tickProviders(server, tick, profiler)` | 遍历 ticking，按 `tick % provider.getTickInterval() == 0` 调 `provider.tick()` |
| `readFromConfig()` / `writeToConfig()` | 读写 `plugins/VeryMcProto/servux.json`：`DataProviderToggles` + 各 provider 的 settings JSON |
| `onCaptureImmutable(registry)` | 缓存 `RegistryAccess.Frozen`（给 Litematic palette 解析方块用）——**时机命门**：必须在 `ServerLoadEvent(STARTED)` 后捕获，早了拿空注册表（Recipe/NbtView/palette 全空） |
| `getSettingByName(name)` | 跨 provider 查 setting（支持 `provider:setting` 格式，给 `/servux set` 用） |

配置文件 `servux.json` 结构：顶层 `DataProviderToggles`（各 provider 启停；`servux_main` 永远强制 true）+ 每个 provider 一节 settings。路径 = `plugin.getDataFolder()`（原版为 Fabric `config/`）。

### 2.4 `IDataProvider` / `DataProviderBase` —— Provider 契约

纯抽象、几乎照抄（仅 NMS 类型经 paperweight 直连；`hasPermission` 换 Bukkit 权限）：

```java
String getName();                 // 逻辑名（config key，如 "hud_data"）—— ≠ 网络通道名
Identifier getNetworkChannel();   // 网络通道（如 servux:hud_metadata）
int getProtocolVersion();         // 协议版本（客户端协商）
void registerHandler();           // 启用：注册通道 + payload + receiver
void unregisterHandler();         // 禁用：反注册
default boolean shouldTick(); int getTickInterval();   // 默认 40
boolean isPlayerRegistered(ServerPlayer);              // 玩家是否已"注册"该 provider（订阅）
boolean hasPermission(ServerPlayer);
void onTickEndPre(); void onTickEndPost();             // 停服前后钩子
JsonObject toJson(); void fromJson(JsonObject);        // settings 序列化
List<IServuxSetting<?>> getSettings();
```

`DataProviderBase` 抽象基类：构造时固化元信息 `(name, channel, protocolVersion, defaultPerm, permNode, description)`；提供 `enabled` / `playRegistered` / `tickRate` 字段与 `toJson/fromJson` 通用实现。

### 2.5 配置项系统（`framework/settings/`）

每个 Provider 持有一组 `IServuxSetting<?>`（`ServuxBoolSetting` / `ServuxIntSetting`（min/max 校验）/ `ServuxStringSetting` / `ServuxStringListSetting` / `ServuxListSetting`），由 `DataProviderManager` 统一持久化。

- 构造：`new ServuxXxxSetting(this, "name", default, [min,max] | [examples], [callback])`
- 变更回调：`IServuxSettingCallback<T>.onValueChanged(setting, old, new)`（如 `loggers_enabled` 变更时重新初始化 logger）
- `setValueNoCallback`：不触发回调地设值（内部逻辑用）
- 命令行 `/servux set <name> <value>` 经 `getSettingByName` 定位并设值

### 2.6 全局配置主 Provider：`ServuxConfigProvider`（`servux_main`）

唯一 `getName()="servux_main"` 且**永不被禁用**的 provider。不注册网络通道（`registerHandler` NO-OP）、不下发网络包，承载全局配置：`permission_level` / `permission_level_admin`（基线权限）、`permission_level_easy_place` + `easy_place_validator_enabled`（EasyPlace 开关）、`default_language`、`debug_log`。提供全局能力查询：`hasDebugMode()` / `hasPermission_EasyPlace(player)` / `isEasyPlaceValidatorEnabled()`。

---

## 3. 网络框架与投递路径 ⭐

### 3.1 协议本质与通道注册

所有被移植的 mod 都用 **Mojang 在 1.20.2+ 引入的原版 `CustomPacketPayload`** 协议——Fabric 的 `ServerPlayNetworking` / `PayloadTypeRegistry` 只是这套原版机制的注册封装。Paper 的 **plugin messaging channel（`namespace:path` 命名）直接映射原版 custom payload 通道**：

```java
// framework/network/ProtocolChannel（形态示意）
public void register() {
    Messenger m = plugin.getServer().getMessenger();
    m.registerIncomingPluginChannel(plugin, channelId, (ch, player, bytes) -> {
        // bytes 就是 FriendlyByteBuf 裸字节（含 VarInt packetType + NBT/buffer）
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        receiver.accept(player, buf);
    });
    m.registerOutgoingPluginChannel(plugin, channelId);
}
```

- `onPluginMessageReceived` 的 `bytes` 直接喂原版 `ServuxXxxPacket.fromPacket(FriendlyByteBuf)` 即可，**协议解码逻辑零改动**。
- **C2S 不踢玩家**：Paper 原版服务端对未注册 custom payload 会踢玩家（"Invalid payload"）；plugin messaging 注册的通道由 Paper 内置路由、不踢人——这是用 `Messenger.registerIncomingPluginChannel` 收 C2S 的根本理由。
- **configuration phase 命门**：期间 `sendPluginMessage` 静默丢弃；`PlayerRegisterChannelEvent`（客户端声明通道 = 装了对应 mod = configuration phase 已完成）是可靠握手信号。
- **客户端支持检测**（替代原版 `ServerPlayNetworking.canSend`）：`ProtocolChannel.send` 检查 `player.getListeningPluginChannels().contains(channel)`；未装 mod 的玩家恒 false → 失败计数（`MAX_FAILURES=2`，超限标记 invalid 不刷屏；重置仅在 unregister / removePlayer）。Fabric 客户端装了 masa mod 才会声明监听 `servux:*`。

### 3.2 三种 S2C 投递路径与同通道 C2S 证明兜底

**路径 A（servux 主路径）**：plugin messaging + `PacketSplitter` 分片（§3.3）。

**路径 B（NMS 直发）**：`nms.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes)))`——JEI 配方包（常超 1MiB）、syncmatica S2C（plugin messaging wire 对纯 Fabric 客户端不可达）的主路径。

**兜底（servux「同通道 C2S 证明」）**：Paper `CraftPlayer.sendPluginMessage` 按 `channels().contains(channel)` 门控、玩家声明包被处理前 S2C **静默丢弃**（26.1.2 反编译实锤；26.2.build.129 源码复核存活 `CraftPlayer.java:2225`）——这曾吞掉 minihud structures 的进服首握手回复（其客户端 metadata 接受窗口是**单次**的：`DataStorage.java:288` 开门 → 首个 `%20` tick `:804` 关门，错过即恒 not_connected 直到手动 toggle；上游行号属 1.21.11 树）。修复：`ProtocolChannel.send` 对**已在本通道发过 C2S 的玩家**（= 装有对应 mod，能发即能收）在 `listening=false` 时改走路径 B 的 NMS 直发（与 Paper 自身放行路径逐字同构；证明集合随 `PlayerQuitEvent` 清除）。未发过 C2S 的玩家（vanilla / 未装 mod）**构造性不受兜底影响**，仍走 sendPluginMessage 由 Paper 丢弃——设计**不押注**「原版客户端对未知通道 S2C 的行为」（服务端侧对称机制 `CustomPacketPayload.codec` 的 fallback 把未知 id 解码为 DiscardedPayload 后忽略，vanilla 大概率静默丢弃、断连风险主要在 NeoForge 网络层）。**已知限制**：REGISTER 回复 RTT 超过客户端剩余 `%20` 窗口时仍需手动 toggle——与上游 Fabric servux 同源，不劣于上游。

> ⚠️ **勿直发自定义 Payload record**：走 NMS 直发必须用字面量 `DiscardedPayload`——自定义 Payload record 会 ClassCastException 踢人。客户端用其自行注册的 codec 解码 payload data，与原版 Fabric 服务端发的 wire 一致。

### 3.3 `PacketSplitter` —— 应用层分包

源自 QuickCarpet（skyrising），Sakura 适配新版 payload。纯算法 + NMS `FriendlyByteBuf`/`Unpooled`，近乎照抄。

**帧布局**（分包后）：
```
包#0: [VarInt 总长度 N][原始字节 0 .. payloadLimit-1]
包#1:                [原始字节 payloadLimit .. 2*payloadLimit-1]
... 直到 offset >= N
```

**发送**（切片）：`for (offset=0; offset<len; offset+=payloadLimit)`，仅首包写 `VarInt len`，每片独立成一个 Payload 包（`encodeWithSplitter` 回调）。

**接收**（重组，`ReadingSession`）：首包读 `expectedSize`（VarInt），校验 `> maxLength` 即抛；收齐 `READING_SESSIONS.remove(key)` 返回完整数据。`READING_SESSIONS` 用 `ConcurrentHashMap` + `ReadingSession.receive` 加 `synchronized`；坏包立即丢弃 session（**并发安全**）。`key` = 预共享随机 long session key（`Random.create(Util.getMeasuringTimeMs()).nextLong()`），每个分片流维护一份映射（Fabric 端 HUD 在 `ServuxHudHandler.readingSessionKeys: Map<UUID, Long>`；litematica 每流新生成、收齐重置——**连续流不串台**）。

**Structures 条目级分批**（对齐上游 `sendStructures :565-604`，在字节分片**之下**的业务层）：register 时读客户端申报 `tags.max_receive_s2c`（TAG_INT，默认 16MB）存名册 entry；发送时总量 + 4096 padding ≤ 上限单帧，否则逐条累计 `>=` 即 flush 多次 `STRUCTURES_DATA_START` 帧（纯函数 `splitStructuresBySize` 配单测）。每业务帧仍走 PacketSplitter 字节分片（两层叠加）；客户端按帧合并非替换。当前线四客户端均无该字段发送点，恒走默认值（机制层对齐、真实环境不可观测）。

### 3.4 字节限制模型（裸值唯一权威）

| 常量/上限 | 值 | 方向与语义 |
|---|---|---|
| Bukkit `Messenger.MAX_MESSAGE_SIZE` | **1048576**（~1MiB） | plugin messaging API 层（1.21.x 起；旧文档称 32768 已过时） |
| 原版客户端 `ClientboundCustomPayload` 解码上限（未知通道 discarded） | **32767** | **真正 S2C 瓶颈**，超过客户端断连；已知通道（客户端已注册 codec，如 `fabric:recipe_sync` 注册为 64MB large payload）不受此限 |
| `PacketSplitter.MAX_TOTAL_PER_PACKET_S2C` | **32_000** | 我方发送单片总上限（防御 32767） |
| `PacketSplitter.MAX_PAYLOAD_PER_PACKET_S2C` | **31_995**（=32000−5） | 留余量给 VarInt 头 |
| `PacketSplitter.DEFAULT_MAX_RECEIVE_SIZE_S2C` | **67_108_864**（64MB） | 我方接收（C2S 上传）缓冲上限 |
| `PacketSplitter.MAX_REASSEMBLY_SIZE_S2C` | **16_777_216**（16MB） | 客户端（malilib，当前线）重组上限——我方发送前预检阈值，`send` 入口严格 `>` 判定（恰好相等放行），超限**整帧拒发** + warn（log-and-drop，有意不限频） |

**16MB 预检覆盖**：被检量 = DataTag 帧化后 buffer 的 `writerIndex()`（= 4 + GZIP 压缩长 = 首包 VarInt 下发、客户端 `expectedSize` 读取的同一个数，三方同源）；超限帧发出去会被客户端销毁重组 session 并抛异常，后续分片还会以垃圾 expectedSize 重建残留会话污染下一帧。覆盖全部 S2C 分片大帧（RecipeManager 全量帧 / BulkEntityReply / Structures 全量帧三活跃点 + 两死分支）。**上游 servux 无此预检——我方增强，勿随上游模板回退**。

三常量方向对照（同名/近名易混）：`MAX_TOTAL_PER_PACKET_S2C`（32,000）= 我方**发送**单片上限；`DEFAULT_MAX_RECEIVE_SIZE_S2C`（64MB）= 我方**接收**缓冲上限；`MAX_REASSEMBLY_SIZE_S2C`（16MB）= **客户端重组**上限（我方发送预检阈值）——与 malilib 客户端侧同名常量数值对齐而与 64MB 同名常量无关。原版另有的 C2S 专用三常量在本实现零引用已删除（C2S/S2C 共用单物理通道）。

C2S 接收方向的解压配额闸（gzip bomb / 长度字段炸弹拦截）见 [servux-protocol.md](servux-protocol.md) 版本约束节（`DataTagIo`，servux 域）。

### 3.5 Payload record 模型

`record Payload(...) implements CustomPacketPayload` + `static Type<Payload> ID`（= 通道 Identifier）+ `static StreamCodec<FriendlyByteBuf, Payload> CODEC`——Paper 端去掉 `@Environment(EnvType.SERVER)` 后近乎原样可用（`CustomPacketPayload.Type`、`StreamCodec`、`FriendlyByteBuf` 全是 NMS，paperweight 直连）。plugin messaging 收发 `byte[]` 时，Payload record 主要用于 NMS 直发与协议定义文档化。

`IServerPayloadData` 协议数据统一抽象（纯接口照抄）：`getVersion()`（各通道真值见 [servux-protocol.md](servux-protocol.md) §2）/ `getPacketType()` / `getTotalSize()` / `isEmpty()` / `toPacket(FriendlyByteBuf)` / `clear()`——**勿在此复述裸值，防版本线演进漂移**。

### 3.6 NMS API 实测坑清单（升级核对参考）

> 1.21.11 线成文、26.1/26.2 漂移已复核合并；升级 MC 版本时逐行重核（升级 SOP 见 [operations.md](operations.md)）。

| 坑 | 正确做法 |
|---|---|
| `ResourceLocation` 改名 `Identifier` | 用 `net.minecraft.resources.Identifier`（1.21.5+ Mojang 重命名） |
| `ResourceKey.location()` 不存在 | 用 `.identifier()` |
| `CompoundTag.getAllKeys()` | 用 `keySet()` |
| `player.serverLevel()` | 用 `(ServerLevel) player.level()` |
| `ServerLevel.getSharedSpawnPos()` | 用 Bukkit `world.getSpawnLocation()` → BlockPos |
| `LevelData` 天气方法 | 直接走 `ServerLevel.getWeatherData()`（`WeatherData` 同名方法保留） |
| `CommandSourceStack.hasPermission(int)` 签名漂移 | 不用；Perms 用 `Nms.server()` + `isOp()` |
| `MinecraftServer.getProfiler()` 不存在 | `IDataProvider.tick` 去掉 `ProfilerFiller` 形参 |
| `ServerLoadEvent.LoadType.STARTED` 枚举漂移 | 去掉类型判断，处理所有 ServerLoadEvent |
| `CompoundTag` Optional 语义 | `getXxx` 返回 Optional → 用 `getBooleanOr/getStringOr/getIntOr/...` 或 `.orElse()`；`putXxx` 返回 void（非链式） |
| `FriendlyByteBuf.readNbt()` | 返回 `CompoundTag`（非 Optional）；大包用 `readNbt(NbtAccounter.unlimitedHeap())` |
| `List.copyOf(enum[])` 不接受数组 | 用 `Arrays.asList(values())` |
| `ChunkPos` 变 record | `pos.x()`/`pos.z()`；`ChunkPos.unpack(long)`；`pack(x,z)`/`pack()`；`ChunkPos.containing(BlockPos)` |
| `displayClientMessage(comp, bool)` | 用 `sendSystemMessage(comp)`（单参） |
| `Messenger.MAX_MESSAGE_SIZE` | 1.21.x 已上调 ~1MiB；真瓶颈是客户端 32767（§3.4） |
| `@NotNull`/`@Environment` 注解 | Fabric 专有，全部删除 |

26.2 线新增漂移：`EntityType.PLAYER` 等常量迁至 `EntityTypes` 常量类；`EntityType.create` 第三参 `new EntitySpawnRequest(EntitySpawnReason.LOAD, true)`；`BlockTags.CONCRETE_POWDER`→`CONCRETE_POWDERS`。

### 3.7 防御性编程要点（框架级）

- **所有装配 try-catch**：主类 onEnable/onDisable、LifecycleBridge 事件分发、tickProviders，单点异常不影响整体。
- **反射防御**：`Reflect.getOr(obj, field, default)` 失败返回默认值（TPS 的 `remainingSprintTicks` 漂移返回 0，NbtView 的 `output` 失败返回 null）。
- **大包保护**：`ProtocolChannel.send` 拒绝超 `Messenger.MAX_MESSAGE_SIZE` 的包（应走 PacketSplitter）。
- **配置原子写**：`JsonUtils.writeJsonToFileAsPath` 用 `.tmp` + move 原子写。

---

## 4. 生命周期（Bukkit 事件 + tick 调度，替代 Mixin）

原版 Servux/syncmatica 用自定义事件总线（`event/*Handler` 单例）由 Mixin 在 NMS 生命周期点触发；Paper 上由 Bukkit 事件 + 调度器 1:1 替换：

| 原版 Mixin 时机 | Paper 等价（as-built） |
|---|---|
| `MixinMinecraftDedicatedServer.<init>@TAIL`（注册 provider） | `onEnable` 直接注册 |
| `MixinMinecraftServer.runServer` → onServerStarting/Started | `ServerLoadEvent`（仅触发一次，不区分 LoadType；读+写配置幂等合并） |
| `MixinMinecraftServer.tickServer@RETURN` → tickProviders | `BukkitRunnable.runTaskTimer(1L)` 每 tick + 内部按 interval 分发 |
| `reloadResources` Pre/Post | `/servux reload` 命令（自管重读） |
| `stopServer` Pre/Post | `PluginDisableEvent` / `onDisable` |
| `MixinMain.main`（捕获 RegistryAccess） | `ServerLoadEvent(STARTED)` 后 `registryAccess()` 捕获（时机命门见 §2.3） |
| `MixinPlayerManager.placeNewPlayer/remove/respawn` | `PlayerJoinEvent` / `PlayerQuitEvent` / `PlayerRespawnEvent`（provider 统一钩子，框架增强） |
| `canPlayerLogin` | `AsyncPlayerPreLoginEvent` / `PlayerLoginEvent` |
| `MixinServerLevel.advanceWeatherCycle`（天气采集） | tick 内周期读天气状态（`ServerLevel.getWeatherData()`） |
| `MixinServerLevel.setRespawnData`（出生点） | `updateSpawnFromServer`（Bukkit `World.getSpawnLocation`）周期同步 |
| `MixinChunkLoadingManager.markChunkPendingToSend`（结构触发） | 周期扫描玩家 view distance 内区块查结构引用（去重发送；性能：限 `update_interval` 默认 40t + 只扫 view distance 内） |
| `MixinCommandManager.<init>`（注册命令） | `plugin.yml` `commands:` + `CommandExecutor`/`TabCompleter` |
| （1.20.2+ configuration phase） | `PlayerRegisterChannelEvent`（客户端声明通道 = 握手可靠信号） |

原版生命周期流程（Mixin 触发链）与 `PlayerListener.onPlayerJoin/onPlayerLeave`（各 provider 握手的总入口）的完整叙述属迁移对照历史，见 git 历史（本文 §6 保留逐域速览）。

---

## 5. Mixin→Paper 统一处置矩阵（唯一权威）

> 判断标准：原版源码里凡是 Mixin 注入（`@Inject`/`@WrapOperation`/`@Redirect`/`@Accessor`/`implements`）的，Paper 上都不能照抄——先判它属于下表哪一类，再选处置方式。**禁止引入 Mixin 依赖**（Paper 无 Mixin 运行时）。

### 5.1 四大类处置

| 分类 | 含义 | Paper 处置 |
|---|---|---|
| **A. 反射替代**（读私有字段） | 读 NMS 私有字段供数据采集 | `framework/reflect/Reflect`（线程安全缓存 + 防御式降级）/ NMS 直接调用 / 硬编码已知魔数 |
| **B. 事件/调度替代**（采集触发/生命周期） | Mixin 钩 NMS 生命周期点触发逻辑 | Bukkit 事件 + `BukkitRunnable` 调度（§4 映射表） |
| **C. 降级/省略**（改服务端行为） | 改变 NMS 行为逻辑 | 降级省略 / PacketEvents / 投影代码内联修正 |
| **D. 调试** | 仅 DEV_DEBUG 用 | 省略 |
| **AW. AccessWidener** | 暴露私有字段 | 反射 |

### 5.2 Servux 逐项清单（26 Mixin + 2 AW）

**block 包（5，几乎全是 Litematica 镜像修复）**：

| Mixin | 归属 | 处置 |
|---|---|---|
| `MixinBlock_UpdateSuppression` | UpdateSuppression | **C 省略** |
| `MixinChestBlock`（mirror） | Litematics | **C 内联**：粘贴时修正（`fixChestMirror`） |
| `MixinHopperBlockEntity` | Tweaks | **C 不可能实现**（潜影盒堆叠，见 §5.3） |
| `MixinRailBlocks`（rotate） | Litematics | **C 内联**（`fixRailRotations`，靠 `BlockState.rotate()` 自身行为，降级可能不完美） |
| `MixinStairsBlock`（mirror） | Litematics | **C 内联**（`fixStairs_mirror`） |

**其他包**：

| Mixin | 归属 | 处置 |
|---|---|---|
| `MixinSharedConstants`（debug） | 调试 | **D 省略** |
| `MixinAllayEntity` / `MixinItemEntity` / `MixinMobEntity` | Allay 收集修复 | **C 省略**（可选后续用实体目标事件模拟） |
| `MixinBlockItem_EasyPlace` | EasyPlace | **C ✅ 已实现**（PacketEvents 双挂点，§5.3） |
| `MixinItemStack`（getMaxStackSize） | 潜影盒堆叠 | **C 不可能实现**（§5.3） |
| `IMixinNbtReadView` / `IMixinNbtWriteView`（Accessor） | NbtView | **A**：重写为直接 `Entity.saveWithoutId(CompoundTag)` / `BlockEntity.saveWithFullMetadata`，绕开 Accessor |
| `MixinServerPlayNetworkHandler_EasyPlace` | EasyPlace | **C ✅ 已实现**（`EasyPlaceListener` 改写 cursor 放行） |
| `MixinServerPlayNetworkHandler_QueryNbt` | /data get 权限 | **C**：Bukkit 权限等价 |
| `IMixinServerTickManager`（Accessor remainingSprintTicks） | TPS 采集 | **A 反射** |
| `MixinCommandManager` / `MixinMain` / `MixinMinecraftDedicatedServer` / `MixinMinecraftServer` / `MixinPlayerManager` | 生命周期 | **B**：§4 映射表 |
| `IMixinWorldTickScheduler`（Accessor allContainers） | 保存投影读 tick | **A 省略**（粘贴不需要） |
| `MixinServerChunkLoadingManager` | Structures 触发 | **B**：周期扫描（§4） |
| `MixinServerWorld` | HUD 出生点/天气 | **B**：§4 |
| `MixinWorld_UpdateSuppression` / `MixinWorldChunk_UpdateSuppression` | UpdateSuppression | **C 省略** |

**AccessWidener（2）**：`SharedConstants.DEBUG_ENABLED`（mutable）→ 省略（D 类）；`NaturalSpawner.MAGIC_NUMBER`（=289，17×17 魔数）→ 反射或直接硬编码 289（注释标明来源，防 Mojang 改值）。

### 5.3 统一降级矩阵（「改服务端行为」类最终裁决）

| 功能 | 处置 | 说明 |
|---|---|---|
| **EasyPlace**（Tweakeroo 精确放置） | ✅ **已实现**（「改写放行」双挂点，PacketEvents） | ① `EasyPlaceListener`（netty 线程）拦原版 `PLAYER_BLOCK_PLACEMENT`：编码包**不取消**、不读任何玩家状态，仅把 `cursor.x` 改写回 `relX∈[0,1)`（过 vanilla 逐轴 hitVec 校验，等效上游 NetworkHandler Mixin；ack 由 vanilla 原生回，**消除 netty 读手持的换手 desync 竞态**）+ 登记 pv 到 `EasyPlacePending`（TTL 1000ms，条目含 canBuild 暂存）；② `EasyPlaceFixListener`（主线程双挂点）：`BlockCanBuildEvent`（HIGHEST，fire 于 `BlockItem.canPlace` 体内、**先于全部放置副作用**）以事件 BlockData（= vanilla 候选态，上游 Mixin 注入点 `stateOrig` 的同位等价物，判定世界为放置前状态）为基座跑 `applyPlacementProtocolV3`——拒绝（null / 修正态 canSurvive 失败 / 实体碰撞失败，后者与 vanilla `canPlace` 逐字同构）→ `setBuildable(false)` → vanilla place 在 placeBlock 前整次 FAIL（与上游 `setReturnValue(null)` 同位同效、零残差）；放行 → 暂存修正态；`BlockPlaceEvent`（HIGHEST）退化纯写入（`setBlock(UPDATE_ALL_IMMEDIATE)` + 双半格 `setPlacedBy` 派生）。PacketEvents 类引用隔离在 `EasyPlaceBootstrap`（反射加载 + `catch(Throwable)` 降级——`try/catch` 抓不到方法解析阶段的类加载失败）。运行时需服务器装 packetevents 插件（`softdepend`），未装优雅跳过。**与上游的有意差异**：门/垂滴叶双半格（`DoubleBlockHalf`）下半修正后以修正态重演 `setPlacedBy` 派生上半（`BlockMultiPlaceEvent` 经父类 HandlerList 派发实收，两半构造性一致）；床编码朝向≠vanilla 且头位可替换时两半错位（已知差异，上游为写入前整体改向）；**修正态 canSurvive + 实体碰撞补查恒生效、不受 `easy_place_validator_enabled` 门控（我方比上游严）**；vanilla 基座检查失败保守不动；恢复 vanilla 距离/保护检查；保护插件重新可见放置事件；canBuild→place 间隙病态扰动 → place 侧 canSurvive 复查失败降级保留 vanilla 态；物品 BLOCK_STATE 组件（pick-block）改写的最终态会被修正覆盖（极边缘）；`EasyPlacePending` TTL 1s 内 stale 条目理论上可命中同位手动放置（罕见、自愈、有界） |
| **潜影盒可堆叠** | ⛔ **不可能实现 + 已删全部代码** | 改 `ItemStack.getMaxStackSize()` / `HopperBlockEntity` 全局行为，Paper 无 Mixin 无等价（反射改不了方法返回值；Bukkit 事件在 `maxStackSize=1` 前提下恒失败；设 `MAX_STACK_SIZE` 组件污染序列化）。`TweaksDataProvider` **不下发** `stackingShulkers` 元数据——否则客户端 tweakeroo 据其自动开堆叠渲染而服务端不配合 → 不一致 |
| **UpdateSuppression** | ⛔ **省略** | 依赖 Mixin 给 `Level`/`LevelChunk` 加接口 + 改 `setBlockState` 副作用 |
| **Allay 收集修复** | ⚠️ **省略** | 影响小 |
| **镜像修复**（箱子/铁轨/楼梯 180°） | ✅ 粘贴时内联 | 靠 `BlockState.mirror()/rotate()` 自身行为，降级可能不完美 |
| **/data get 权限覆盖** | ✅ Bukkit op 权限等价 | — |

### 5.4 Syncmatica 侧（概览）

syncmatica 原版 19 Mixin（`mixin/` 9 + `litematica_mixin/` 10），服务端真正替换仅 **5 个**（MinecraftServer/PlayerManager/ServerPlay·ServerCommon NetworkHandler/CommandManager），全部 Bukkit 事件 / Brigadier→Bukkit 命令 / plugin messaging listener / 写死 dedicated 替代；客户端 4 + GUI 10 全不移植。**无「改服务端行为」类降级**（对比 Servux：不改任何原版服务端逻辑，纯协议层 + 文件 I/O，不需要 PacketEvents）。逐项明细见 [syncmatica-architecture.md](syncmatica-architecture.md)。

---

## 6. Fabric ↔ Paper 逐域对照（参考手册）

> 每行可直接当迁移转换规则用。

### 6.1 根本差异

| 维度 | Fabric + 协议 Mod | Paper 插件 |
|---|---|---|
| 运行模型 | Mod 与服务端同进程同类加载器，可 Mixin 改字节码 | 插件在隔离类加载器，NMS 经 paperweight userdev 访问，**无 Mixin** |
| NMS 可达性 | Loom 提供 Mojang 映射 | paperweight userdev 同样提供；26.1 起产物与运行时同为 Mojang 名，反射字符串直接用 Mojang 名 |
| 改字节码 | Mixin + AccessWidener | 无。反射读私有 / 事件替代钩子 / PacketEvents 拦截包 |
| 网络 | `fabric-networking-api-v1` | plugin messaging channel + NMS `ClientboundCustomPayloadPacket` |
| 生命周期 | Mixin 钩 NMS 生命周期点 | Bukkit 事件 + scheduler |
| 权限 | `fabric-permissions-api`（Lucko） | Bukkit `Permission` / LuckPerms / Vault |
| 构建 | `fabric-loom` | Gradle + paperweight userdev + run-paper（§7） |

### 6.2 网络

| Fabric | Paper 等价 |
|---|---|
| `PayloadTypeRegistry.playC2S().register(Type, codec)` | `Messenger.registerIncomingPluginChannel(plugin, "servux:xxx", listener)` |
| `PayloadTypeRegistry.playS2C().register(Type, codec)` | `Messenger.registerOutgoingPluginChannel(plugin, "servux:xxx")` |
| `ServerPlayNetworking.registerGlobalReceiver(Type, handler)` | 同上 incoming 注册 + `PluginMessageListener` |
| `ServerPlayNetworking.send(player, payload)` | `player.sendPluginMessage(...)` 或 NMS `connection.send(new ClientboundCustomPayloadPacket(...))`（§3.2） |
| `ServerPlayNetworking.canSend(player, type)` | `player.getListeningPluginChannels().contains(channel)` + 同通道 C2S 证明兜底（§3.2） |
| `record Payload implements CustomPacketPayload` + `StreamCodec` | 照抄（NMS 直连；§3.5） |
| `FriendlyByteBuf` / `Unpooled` | NMS 同名；`byte[]`↔buf 用 `Unpooled.wrappedBuffer`/`buf.array()` |
| 单包上限 | Bukkit ~1MiB；真瓶颈客户端 32767 → 分片 32000/31995（§3.4） |
| 未知 payload | Fabric 客户端注册即收；**Paper 注册 incoming channel 即不踢玩家** |

### 6.3 权限 / 配置 / 命令 / NBT / 杂项

| Fabric | Paper 等价 |
|---|---|
| `Permissions.check(player, node, level)` | `framework.permission.Perms.check(...)`（显式设置优先 → `level<=0` 全员 → op 二值；节点表见 [operations.md](operations.md) §2） |
| permission level 0-4 | Bukkit 权限 + `default`（`op` / `true`） |
| `FabricLoader.getConfigDir()` → `config/servux.json` | `plugin.getDataFolder()` → `plugins/VeryMcProto/servux.json` |
| `Reference.DEFAULT_RUN_DIR = getGameDir()` | `Bukkit.getWorldContainer()` |
| Gson 配置 | 保留 Gson（Bukkit 自带）；保持 JSON |
| `filesMatching("fabric.mod.json")` expand | `processResources` expand `plugin.yml` 的 `${version}`（须显式 `inputs.property` 参与 up-to-date，§7） |
| `MixinCommandManager` 注入 Brigadier | `plugin.yml` + `CommandExecutor`/`TabCompleter`（Brigadier `CommandSourceStack` → `CommandSender`；命令树含上游「值 <10 字符才显示」等怪癖） |
| `net.minecraft.nbt.CompoundTag`/`ListTag`/`NbtOps`/`NbtIo` | NMS 同名直连（保真度最高）；Bukkit `PersistentDataContainer` 受限不用 |
| `NbtView`（Servux 自研） | 重写为直接 `Entity.saveWithoutId` / `BlockEntity.saveWithFullMetadata` |
| `LogManager.getLogger`（SLF4J 占位符） | `plugin.getLogger()`（JUL）；占位符用 `mod/servux/util/Log.java` shim 机械替换 |
| `util/i18n/` + lang 资源 | 硬编码中英文消息（客户端不关心服务端消息语言） |
| `SharedConstants.getCurrentVersion()` | `Reference.MC_VERSION`（version.properties 注入） |
| `FabricLoader.getInstance()` | `Bukkit.getServer()` / `plugin.getDataFolder()` |

### 6.4 NMS 采集可达性

绝大多数采集 API paperweight dev bundle **可直接 import**：`ServerLevel.getSeed()` / `recipeAccess()` / `getChunkSource().getLastSpawnState()`、`Recipe.CODEC.encodeStart(NbtOps.INSTANCE, ...)`、`BlockEntity.saveWithFullMetadata` / `Entity.saveWithoutId`、`ChunkAccess.getAllReferences()` / `getStartForStructure()` / `StructureStart.createTag`、`MinecraftServer.registryAccess()`——原版要 Mixin/AW 才能访问的，反而因 paperweight 给了完整映射而简化。仅以下需反射：`ServerTickRateManager.remainingSprintTicks`、`NaturalSpawner.MAGIC_NUMBER`(=289，可硬编码)、`TagValueInput/Output` 私有字段（已重写绕开）、`LevelTicks.allContainers`（已省略）。

### 6.5 一页纸转换清单

```
入口/生命周期    onInitialize → onEnable + Bukkit 事件（§4）
provider 注册    MixinDedicatedServer → onEnable 直接 register
tick 调度        MixinMinecraftServer.tickServer → BukkitScheduler runTaskTimer
玩家进退服        MixinPlayerManager → PlayerJoinEvent/PlayerQuitEvent
客户端就绪        （原版无）→ PlayerRegisterChannelEvent
配置路径         config/servux.json → plugins/VeryMcProto/servux.json
权限             Permissions.check → framework.permission.Perms.check
命令             MixinCommandManager → plugin.yml + CommandExecutor
网络收(C2S)      ServerPlayNetworking → Messenger.registerIncomingPluginChannel
网络发(S2C)      ServerPlayNetworking.send → sendPluginMessage 或 NMS ClientboundCustomPayloadPacket
字节流            FriendlyByteBuf → NMS FriendlyByteBuf（paperweight 直连）
分包             PacketSplitter → 照抄（S2C 常量 32000/31995，防客户端 32767）
NBT              CompoundTag/NbtIo → NMS 直连
Mixin 读私有     → 反射 / NMS 直接调用（多数已是公开方法）
Mixin 改行为     → 降级省略 / PacketEvents / 投影代码内联
构建             fabric-loom → paperweight userdev + run-paper（26.1 起无 reobf）
```

---

## 7. 构建与工具链（as-built）

> 版本唯一来源 `gradle.properties`（`mcVersion` / `buildNumber`）——**本节不复制具体版本号裸值，叙述以属性名指代**（当前线值见 gradle.properties / [../AGENTS.md](../AGENTS.md) §技术栈）。

```kotlin
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "<paperweight 版本>"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

dependencies {
    paperweight.paperDevBundle("<mcVersion>.build.<N>-stable")
    // 26.1 起新格式 <mc>.build.<N>-stable；提供 Mojang 全映射 net.minecraft.* + io.papermc.paper.*
    // 可选依赖（EasyPlace）：compileOnly("com.github.retrooper:packetevents-spigot:<版本>")
    // ——类引用必须隔离到独立引导类（EasyPlaceBootstrap）+ 反射加载 + catch(Throwable)
}

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

tasks {
    runServer { minecraftVersion(providers.gradleProperty("mcVersion")); jvmArgs("-Xms2G", "-Xmx2G") }
    processResources {
        // expand 占位符必须显式 inputs.property(...) 参与 up-to-date 跟踪
        // （历史事故：发版 buildNumber+1 后任务误判 UP-TO-DATE、陈旧展开被打进新 jar）
        filesMatching("plugin.yml") { expand(props) }
    }
    // 无 reobfJar——26.1 起 Mojang 移除服务端混淆，reobf 插件无法在 Paper 26.1+ 加载；
    // 产物即 Mojang 映射 jar，标准 Paper 直接加载。build 挂 verifyVersionInjection 终检：
    // 解包产物 jar 断言内部 plugin.yml / version.properties 与 project.version 一致，不一致即构建失败。
}
```

**plugin.yml**（`api-version` 已模板化 `${mcVersion}`，不再手写；语义 = 低于该值的服务器拒载；本插件 MOD_STRING 硬门禁绑死精确上游 id，见 [servux-protocol.md](servux-protocol.md) 版本约束节）：

```yaml
name: VeryMcProto
version: '${version}'
main: verymc.top.veryMcProto.VeryMcProto
api-version: '${mcVersion}'
load: POSTWORLD
```

JDK 25 工具链经 foojay-resolver-convention 自动解析（探测不到时自动下载）+ 本机 `~/.gradle/gradle.properties` 的 `org.gradle.java.installations.paths`；配置缓存已开启（task 配置 lambda 内不得捕获脚本顶层 `val`，用 task 自身的 `providers` 取值）。版本注入链路（version.properties → Reference 常量 → 各 mod MOD_STRING）见 [../AGENTS.md](../AGENTS.md) §版本系统。

---

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 客户端 32767 解码上限（未知通道） | S2C 大包断连 | 分片 32000/31995 + 16MB 重组预检（§3.4）；已知通道大包走 NMS 直发（§3.2） |
| 客户端未装对应 Mod | 发包失败/无响应 | `MAX_FAILURES` 计数 + invalid 标记；`PlayerRegisterChannelEvent` + C2S 主动请求自愈 |
| MOD_STRING 协议握手字段 | 客户端版本门禁 | `servux-fabric-<mcVersion>-b<buildNumber>`（`MOD_TYPE` 恒 "fabric" 伪装）——真值见 [servux-protocol.md](servux-protocol.md) 版本约束节 |
| NMS 签名随版本漂移 | 升级 MC 时编译失败 | 反射点集中在 `framework/reflect/`；升级按 §3.6 坑清单 + [operations.md](operations.md) 升级 SOP 核对 |
| Structures 周期扫描性能 | 玩家多时 CPU 占用 | 限扫描频率（`update_interval` 默认 40t）；只扫 view distance 内；去重缓存 |
| EasyPlace 依赖 PacketEvents | 未装时功能缺席 | `EasyPlaceBootstrap` 反射加载 + `catch(Throwable)` 优雅跳过，其余通道零影响 |
| 通道名与 provider 名混淆 | 注册错通道 | 用各 Handler 的 `CHANNEL_ID` 常量（网络名），非 provider 名 |

---

> **相关**：[index.md](index.md)（文档地图与约定）· [servux-protocol.md](servux-protocol.md) · [servux-providers.md](servux-providers.md) · [servux-schematic.md](servux-schematic.md) · [servux-testing.md](servux-testing.md) · [syncmatica-architecture.md](syncmatica-architecture.md) · [syncmatica-protocol.md](syncmatica-protocol.md) · [syncmatica-testing.md](syncmatica-testing.md) · [jei.md](jei.md) · [operations.md](operations.md) · [references.md](references.md) · [../AGENTS.md](../AGENTS.md)
