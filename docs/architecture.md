# 全局架构 · 网络框架 · Mixin→Paper 迁移参考（26.1.2 线）

> 本文是本线全局层的唯一权威：分层架构与框架契约（§1–§2）、网络框架与 S2C 投递路径（§3，含 **NMS 实测坑清单 §3.6**）、生命周期（§4）、**Mixin→Paper 统一处置矩阵（§5，唯一权威）**、Fabric↔Paper 逐域对照（§6）、构建与工具链（§7）、防御性编程要点（§8）、验证与保真度（§9）。
> **字节上限裸值唯一权威在本文 §3**（域文档只写行为语义+指针）；Servux 通道 wire 细节见 [servux/protocol.md](servux/protocol.md)；各 Provider 数据内容见 [servux/providers.md](servux/providers.md)；syncmatica/JEI 见各自文档；命令/权限/配置/升级 SOP 见 [operations.md](operations.md)；仓库权威说明见 [../AGENTS.md](../AGENTS.md)。
> 历史文档（NN- 编号时代原件）已冻结归档于 [archive/](archive/README.md)——本文由 01/04/07/09 四件蒸馏归并而成（映射见 [index.md](index.md) 历史锚点映射表）。

---

## 1. 分层架构

### 1.1 原版 Servux 顶层结构（对照源）

```
fi.dy.masa.servux/
├── Servux.java                 ← ModInitializer 入口（onInitialize）
├── Reference.java              ← 常量（MOD_ID/MC_VERSION/配置目录/DEV_DEBUG）
├── servux/                     ← 真正的业务监听实现（ServerListener / PlayerListener / ServuxInitHandler）
├── event/                      ← 事件分发器（ServerHandler / PlayerHandler / ServerInitHandler）+ 接口
├── interfaces/                 ← 抽象接口（IServerListener / IPlayerListener / IServerInitHandler / 线程任务等）
├── dataproviders/              ← ★ 核心：6 个 Provider + DataProviderManager + 接口/基类
├── network/                    ← ★ 网络层（见 servux/protocol.md）：ServerPlayHandler / PacketSplitter / IPluginServerPlayHandler / packet/*
├── loggers/                    ← HUD 子系统：TPS / MobCap 数据采集
├── schematic/                  ← Litematica 投影系统（见 servux/schematic.md）
├── settings/                   ← 配置项系统（AbstractServuxSetting + 各类型）
├── commands/                   ← /servux 命令（CommandProvider + ServuxCommand）
├── util/                       ← 工具（NbtUtils/NbtView/JsonUtils/PositionUtils/i18n/log/...）
└── mixin/                      ← 26 个 Mixin（处置矩阵见本文 §5）
```

**核心三层**：`dataproviders/`（业务层）· `network/`（协议层）· `event/ + servux/ + mixin/`（生命周期层）。

### 1.2 目标架构与技术栈决策（as-built）

| 决策 | 选择 | 理由 |
|---|---|---|
| 构建 | **paperweight `userdev` 2.0.0-beta.23** + `run-paper 3.1.0` | 提供完整 Mojang NMS 映射；26.1 起 **reobf 废除**，产物即 Mojang 映射 jar，标准 Paper 直接加载 |
| 网络收发 | **plugin messaging channel**（主）+ NMS `ClientboundCustomPayloadPacket`（大包/直发） | plugin messaging 内置路由不踢玩家、API 简单；NMS 直发承载超限大包（JEI 配方、syncmatica S2C、servux 声明滞后兜底） |
| 数据采集 | **NMS 直连**（主）+ 反射（辅，仅 4-5 点） | 绝大多数 Servux 采集 API 是公开方法；反射点见 §3.6 |
| NBT | **NMS `CompoundTag`/`NbtIo`** | 与原版字节级一致；26.1 业务包另按 DataTag 线格式封装（见 [servux/protocol.md](servux/protocol.md) §5） |
| 权限 | Bukkit `Permission`（+ LuckPerms 兼容） | 无新依赖；servux 权限节点原样保留 |
| Mixin | **不使用** | Paper 无 Mixin 运行时 |

### 1.3 实际包结构（as-built）

```
verymc.top.veryMcProto/
├── VeryMcProto.java              JavaPlugin 主类（框架入口 + 装配 + 依次注册四个 mod + 命令）
├── Reference.java                框架级常量（MC_VERSION / PLUGIN_VERSION——类加载时读 version.properties）
│
├── framework/                    ★ 协议 mod 移植框架（与具体 mod 无关，所有 mod 复用）
│   ├── network/                  通道封装（ChannelManager/ProtocolChannel + C2S 证明兜底）、分片（PacketSplitter）、
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
    ├── syncmatica/               app/SyncmaticaModule + communication/（+exchange/）+ data/（+litematica/）
    │                             + extended_core/ + network/ + service/ + util/
    └── carpet/                   CarpetModule + settings/ + patches/ + helpers/ + network/ + logging/ + command/ + util/
```

**新增协议 mod 的步骤**：在 `mod/<newmod>/` 实现 `ModModule`（或自管装配如 syncmatica/jei），在主类 `onEnable` 注册，在 `plugin.yml` 加命令/权限——无需改动框架（详见 [../AGENTS.md](../AGENTS.md) §代码架构）。

### 1.4 启动流程（Paper 版，as-built）

```
VeryMcProto.onEnable():
  1. 初始化框架（ChannelManager / DataProviderManager / LifecycleBridge）
  2. 依次注册 servux / jei / syncmatica / carpet 四个模块（各自 try-catch，单点失败降级不阻断）
  3. 注册 /servux /jei /syncmatica /carpet 命令
  4. ServerLoadEvent(STARTED) 后捕获 RegistryAccess → DataProviderManager.onCaptureImmutable
     + readFromConfig（servux.json 加载）
  5. LifecycleBridge：PlayerJoin/Quit/Respawn/RegisterChannel 事件桥 + runTaskTimer 每 tick tickProviders

onPlayerJoin（PlayerJoinEvent）:
  对每个 enabled provider → provider.onPlayerJoin(player)（40t 延迟 sendMetadata + onPlayerRegisterChannel 兜底）

onDisable():
  各模块 disable（syncmatica placements.json 原子落盘、scheduler clearTasks、carpet kill 全假人等）
```

---

## 2. 框架契约（Provider 体系）

### 2.1 `DataProviderManager` —— 注册表/调度器/配置中枢

原版 `dataproviders/DataProviderManager.java`（305 行），单例 `INSTANCE`。核心数据结构：

```java
// DataProviderManager.java:31-33
protected final HashMap<String, IDataProvider> providers = new HashMap<>();      // 名字 → provider
protected ImmutableList<IDataProvider> providersImmutable = ImmutableList.of();  // 快照（遍历用）
protected ArrayList<IDataProvider> providersTicking = new ArrayList<>();         // 需要 tick 的子集
protected RegistryAccess.Frozen immutable = RegistryAccess.EMPTY;                // 早期捕获的注册表
```

| 方法 | 作用 | 行号 |
|---|---|---|
| `registerDataProvider(provider)` | 按名（lowercase）登记，去重 | :47 |
| `setProviderEnabled(name/provider, bool)` | 启用/禁用：调 `provider.setEnabled` + `updatePacketHandlerRegistration` + 维护 `providersTicking` | :67/:73 |
| `updatePacketHandlerRegistration(provider)` | 启用→`registerHandler()`；禁用→`unregisterHandler()` | :124 |
| `tickProviders(server, tick, profiler)` | 遍历 ticking，按 `tick % provider.getTickInterval() == 0` 调 `provider.tick()` | :102 |
| `readFromConfig()` | 读 `servux.json`：先读各 settings，再读 `DataProviderToggles` 决定启停 | :204 |
| `writeToConfig()` | 写 `servux.json` | :260 |
| `onCaptureImmutable(registry)` | 缓存 `RegistryAccess.Frozen`（给 Litematic palette 解析用） | :136 |
| `getSettingByName(name)` | 跨 provider 查 setting（支持 `provider:setting` 格式） | :167 |

配置文件 `servux.json` 结构（路径 = `plugins/VeryMcProto/servux.json`，`servux_main` 永远强制 true `:243`）：

```jsonc
{
  "DataProviderToggles": { "servux_main": true, "hud_data": true, "entity_data": false,
                           "tweaks_data": false, "structure_bounding_boxes": true, "litematic_data": true },
  "servux_main": { "permission_level": 0, "permission_level_admin": 3, "easy_place_permission_level": 0 },
  "hud_data":    { "permission_level": 0, "update_interval": 40, "share_seed": false, "loggers_enabled": false },
  "structure_bounding_boxes": { "update_interval": 100, "structure_whitelist_enabled": false },
  "litematic_data": { "permission_level": 0, "paste_permission_level": 2 }
}
```

### 2.2 `IDataProvider` / `DataProviderBase` —— Provider 契约

```java
String getName();                 // 逻辑名（config key，如 "hud_data"）—— ≠ 网络通道名
Identifier getNetworkChannel();   // ★ 网络通道（如 servux:hud_metadata）
int getProtocolVersion();         // 协议版本（客户端协商，26.1 真值见 servux/protocol.md §5）
boolean isEnabled(); void setEnabled(boolean);
void registerHandler();           // 启用：注册通道 + payload + receiver
void unregisterHandler();         // 禁用：反注册
default boolean shouldTick(); int getTickInterval();   // 默认间隔 40t
default IPluginServerPlayHandler<?> getPacketHandler();
boolean isPlayerRegistered(ServerPlayer);              // 订阅状态
boolean hasPermission(ServerPlayer);
void onTickEndPre(); void onTickEndPost();
JsonObject toJson(); void fromJson(JsonObject);        // settings 序列化
List<IServuxSetting<?>> getSettings();
```

`DataProviderBase` 抽象基类构造固化元信息：`(name, channel, protocolVersion, defaultPerm 0-4, permNode, description)`；`toJson/fromJson` 通用实现遍历 `getSettings()`。**这套接口/基类是纯抽象，几乎照抄**（NMS 类型经 paperweight 可直接引用；`hasPermission` 换 Bukkit 权限）。

### 2.3 配置项系统（`settings/`）

每个 Provider 持有一组 `IServuxSetting<?>`（构造时 `List.of(...)`），由 Manager 统一持久化：`ServuxBoolSetting` / `ServuxIntSetting`（min/max 校验）/ `ServuxStringSetting` / `ServuxStringListSetting`；变更回调 `IServuxSettingCallback.onValueChanged`；`/servux set` 经 `getSettingByName` 定位。**纯 Java，照抄**。

### 2.4 全局配置主 Provider：`ServuxConfigProvider`（`servux_main`）

唯一 `getName()="servux_main"` 且永不被禁用的 provider；不对应独立网络通道（`registerHandler` NO-OP），承载全局配置：`permission_level` / `permission_level_admin` / `permission_level_easy_place` + `easy_place_validator_enabled` / `default_language` / `debug_log`。

### 2.5 Reference / i18n / 日志

- `MOD_STRING` 必须保持 fabric 前缀伪装——`servux-fabric-<精确上游 MC 版本>-<插件版本>`（`MOD_TYPE` 恒 `"fabric"`；26.1 客户端按 `startsWith("servux-fabric-<精确上游id>")` 硬门禁，`"paper"` 前缀会被四通道静默拒绝）。
- 配置目录 = `plugin.getDataFolder()`；日志 = `JavaPlugin.getLogger()`（SLF4J 占位符用 `Log.java` shim 机械替换）；i18n 硬编码中英文消息（masa 客户端不关心服务端消息语言）。

---

## 3. 网络框架与 S2C 投递路径 ⭐

### 3.1 通道注册（plugin messaging）

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

> **关键**：`onPluginMessageReceived` 的 `bytes` 直接喂给原版 `ServuxXxxPacket.fromPacket(FriendlyByteBuf)` 即可，**协议解码逻辑零改动**。Paper 原版服务端对未注册 custom payload 会**踢玩家**（"Invalid payload"）；plugin messaging 注册的通道由 Paper 内置路由、不踢人——这是用 `Messenger.registerIncomingPluginChannel` 收 C2S 的根本理由（见 [../AGENTS.md](../AGENTS.md) §1「C2S 接收命门」）。

### 3.2 字节限制真实模型与两种投递路径

- Bukkit `Messenger.MAX_MESSAGE_SIZE` = **1048576（~1MiB）**（1.21.x 起；旧文档称 32768 已过时）。
- **真正的 S2C 瓶颈是原版客户端对 `ClientboundCustomPayload` 的 32767 字节解码上限**（未知通道 discarded 解码；超过客户端断连）。客户端**已注册 codec 的已知通道**不受此限（Fabric API 上限 64MB——`fabric:recipe_sync` 即此类，JEI 配方大包单发因此安全）。

**路径 A（servux 主路径）**：plugin messaging + `PacketSplitter` 分片——S2C 分片常量 `MAX_TOTAL_PER_PACKET_S2C = 32_000` / `MAX_PAYLOAD_PER_PACKET_S2C = 31_995`（留余量给 VarInt 头，防御 32767）。另设 **16MB 重组上限预检**（26.1 客户端 malilib 重组上限 16MB；`PacketSplitter.MAX_REASSEMBLY_SIZE_S2C`，`send` 入口超限整帧拒发 + warn——上游无此预检，我方增强，勿随模板回退）。
**路径 B（大包直发）**：NMS `new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))` 直发——JEI 配方包（常超 1MiB）、syncmatica S2C（plugin messaging wire 对纯 Fabric 客户端不可达）已采用；servux 的**同通道 C2S 证明兜底**亦走此路径（Paper 声明簿记滞后时改走 NMS 直发；证明集合随 `PlayerQuitEvent` 清除；未发过 C2S 的玩家构造性不受兜底影响——见 [../AGENTS.md](../AGENTS.md) §1）。

**决策（as-built）**：servux 走 A + 兜底 B；jei / syncmatica 以 B 为主。两路径按通道选择、共存。

### 3.3 字节级可行性验证

- ✅ `byte[]` ↔ `FriendlyByteBuf`：`Unpooled.wrappedBuffer(bytes)` 包装、`buf.getBytes(...)`/`buf.array()` 取出。
- ✅ `CompoundTag` 读写：`FriendlyByteBuf.writeNbt(tag)` / `readNbt()`，NMS 直连。
- ✅ VarInt / 通道命名（`namespace:key` 规则）。
- ✅ 26.1 DataTag 线格式：`mod/servux/util/nbt/DataTagIo.java` 与 NMS `NbtIo` 输出逐字节兼容（配单测，见 [servux/protocol.md](servux/protocol.md) §5）。

### 3.4 Payload record 照抄

`ServuxHudPacket.Payload`（及另 4 条通道 Payload）在 Paper 端**去掉 `@Environment(EnvType.SERVER)`** 后近乎原样可用——`CustomPacketPayload.Type`、`StreamCodec`、`FriendlyByteBuf` 全是 NMS。plugin messaging 收发 `byte[]` 时，Payload record 主要用于 NMS 直发与协议定义文档化。

### 3.5 数据采集迁移范式

**NMS 直连（绝大多数）**：paperweight dev bundle 可直接 import——`MinecraftServer` / `ServerLevel` / `ServerPlayer`、`getSeed()` / `recipeAccess()` / `getChunkSource().getLastSpawnState()`、`Recipe.CODEC.encodeStart(NbtOps.INSTANCE, ...)`、`BlockEntity.saveWithFullMetadata(registryAccess)` / `Entity.saveWithoutId(...)`、`ChunkAccess.getAllReferences()` / `StructureStart.createTag(ctx, pos)`。原版要 Mixin/AW 才能访问的，反而因 paperweight 完整映射而简化。

**反射（仅 4-5 点）**：`framework/reflect/Reflect`（线程安全缓存 + 防御式降级）——`ServerTickRateManager.remainingSprintTicks`（private）、`NaturalSpawner.MAGIC_NUMBER`（=289，可直接硬编码+注释标来源）、`TagValueInput/TagValueOutput` 内部字段（`NbtView` 重写绕开）。

**触发点迁移（Mixin → 事件/周期）**：

| 采集触发 | Fabric（Mixin） | Paper（事件/调度，as-built） |
|---|---|---|
| 天气变化 | `MixinServerLevel.advanceWeatherCycle` | tick 内周期读（26.1：`ServerLevel.getWeatherData()`） |
| 出生点变化 | `MixinServerLevel.setRespawnData` | `updateSpawnFromServer`（Bukkit `World.getSpawnLocation`）周期同步 |
| 结构观察 | `MixinChunkLoadingManager.markChunkPendingToSend` | 周期扫描玩家 view distance 内区块（`update_interval` 默认 40t；结构引用是 LongSet，查找 O(1)） |
| TPS/MobCap | `tick` 内采集 | `BukkitScheduler` tick 任务 |
| 客户端就绪 | （原版无此问题） | `PlayerRegisterChannelEvent`（configuration phase 完成的可靠信号） |

### 3.6 ⚠️ NMS 实测坑清单（移植/维护必读）

> 实际编译验证踩过的坑（1.21.11 线成文，26.1 漂移实测标注；升级 MC 版本时务必重新核对）。

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **ResourceLocation 改名 Identifier** | `net.minecraft.resources.ResourceLocation` 找不到 | 用 `net.minecraft.resources.Identifier`（1.21.5+ Mojang 重命名） |
| **ResourceKey.location() 改名** | `.location()` 不存在 | 用 `.identifier()` |
| **CompoundTag.getAllKeys()** | 找不到 | 用 `keySet()` |
| **player.serverLevel()** | 不存在 | 用 `(ServerLevel) player.level()` |
| **ServerLevel.getSharedSpawnPos()** | 不存在 | 用 Bukkit `world.getWorld().getSpawnLocation()` → BlockPos |
| **LevelData 天气方法** | `getLevelData().getClearWeatherTime()` 找不到 | 26.1 走 `ServerLevel.getWeatherData()`（`world/level/saveddata/WeatherData`，同名方法保留） |
| **CommandSourceStack.hasPermission(int)** | 签名变成 (Permission,String) | op 等级改 `PlayerList.ops()` 反射或 Bukkit `isOp()`（Perms 用 isOp 二分） |
| **MinecraftServer.getProfiler()** | 不存在 | `IDataProvider.tick` **去掉 ProfilerFiller 形参** |
| **ServerLoadEvent.LoadType.STARTED** | 枚举值名漂移 | 去掉类型判断，处理所有 ServerLoadEvent |
| **CompoundTag 1.21.5+ 语义** | `getXxx` 返回 Optional | 用 `getBooleanOr/getStringOr/getIntOr/...` 或 `.orElse()`；`putXxx` 返回 void（非链式） |
| **FriendlyByteBuf.readNbt()** | 返回 `CompoundTag`（非 Optional） | 直接用；大包用 `readNbt(NbtAccounter.unlimitedHeap())` |
| **List.copyOf(enum[])** | 不接受数组 | 用 `Arrays.asList(values())` |
| **@NotNull/@Environment 注解** | Fabric 专有 | 全部删除 |
| **Messenger.MAX_MESSAGE_SIZE 不再是 32768** | 旧文档曾称 32KiB | 1.21.x 已上调（~1MiB）；真 S2C 瓶颈是客户端 32767（§3.2） |
| **ChunkPos 变 record**（26.1 实测） | `pos.x/pos.z` 不存在 | `pos.x()/pos.z()`；`new ChunkPos(long)` → `ChunkPos.unpack(long)`；`asLong/toLong` → `pack` |
| **displayClientMessage**（26.1 实测） | 双参重载消失 | `sendSystemMessage(Component)`（单参，无 overlay 位） |
| **测试环境**（26.1 实测） | 纯 JVM 单测 NPE | `Reference.logger()` 回退用 JUL（无 Bukkit 类） |

未漂移（结论仍成立）：`Identifier`、`CompoundTag` Optional 语义、`FriendlyByteBuf`、`DiscardedPayload`、`CustomPacketPayload` 模型；3 处反射串（`remainingSprintTicks` / TagValueInput `"input"` / TagValueOutput `"output"`）经上游 26.1 AccessWidener 实证存活。

---

## 4. 生命周期（Fabric Mixin → Bukkit 事件）

原版启动链（`Servux.onInitialize` → MixinMinecraftDedicatedServer `<init>@TAIL` 注册 6 provider → MixinMinecraftServer 各钩 → MixinPlayerManager 玩家事件）。Paper 上全部由 Bukkit 事件等价触发：

| Fabric | 触发方式 | Paper 等价（as-built） |
|---|---|---|
| `ModInitializer.onInitialize()` | JVM 加载 mod | `JavaPlugin.onEnable()` |
| `MixinMinecraftDedicatedServer.<init>@TAIL`（注册 provider） | Mixin | `onEnable` 直接注册 |
| `MixinMinecraftServer.runServer` → onServerStarting/Started | Mixin | `ServerLoadEvent`（不区分 LoadType；STARTED 后捕获 RegistryAccess——**时机命门：早了拿空注册表**） |
| `MixinMinecraftServer.tickServer@RETURN` → tickProviders | Mixin | `runTaskTimer(plugin, task, 0L, 1L)` 每 tick + 内部按 interval 分发 |
| `MixinMinecraftServer.reloadResources` Pre/Post | Mixin | `/servux reload` 命令（自管重读，幂等） |
| `MixinMinecraftServer.stopServer` Pre/Post | Mixin | `PluginDisableEvent` / `onDisable` |
| `MixinMain.main`（捕获 RegistryAccess） | Mixin | `ServerLoadEvent(STARTED)` 后 NMS `registryAccess()` 捕获 |
| `MixinPlayerManager.placeNewPlayer` → onPlayerJoin | Mixin | `PlayerJoinEvent`（40t 延迟握手——plugin messaging 通道握手需时间） |
| `MixinPlayerManager.remove` → onPlayerLeave | Mixin | `PlayerQuitEvent` |
| `MixinPlayerManager.respawn` | Mixin | `PlayerRespawnEvent` |
| `MixinPlayerManager.canPlayerLogin` | Mixin | `AsyncPlayerPreLoginEvent` / `PlayerLoginEvent` |
| `MixinServerLevel.advanceWeatherCycle` / `setRespawnData` | Mixin | 周期读（§3.5） |
| `MixinChunkLoadingManager.markChunkPendingToSend` | Mixin | 周期扫描（§3.5） |
| `MixinCommandManager.<init>`（注册命令） | Mixin | `plugin.yml` `commands:` + `CommandExecutor`/`TabCompleter` |
| （1.20.2+ configuration phase 新增） | — | `PlayerRegisterChannelEvent`（握手可靠信号） |

---

## 5. Mixin→Paper 统一处置矩阵（唯一权威）

> Servux 共 **25 个 Mixin**（注册表计，实际类 26 个含 IMixin 双件）+ **2 个 AccessWidener 字段**；Syncmatica 5 个服务端 Mixin；Carpet 上游 171 个（本线携带子集零 Mixin——patches 三件套纯子类，移植范围终局定案）。逐项全表见归档件 [archive/04-mixin-analysis.md](archive/04-mixin-analysis.md)（Servux）与 [archive/22-syncmatica-mixin-migration.md](archive/22-syncmatica-mixin-migration.md)（Syncmatica）；本节是**决策矩阵**。

### 5.1 四大类处置速查（Servux 26 Mixin + 2 AW）

| 分类 | 数量 | Paper 处置 |
|---|---|---|
| **A. 反射替代**（读私有字段） | ~4 + 2 AW | `framework/reflect/Reflect`（缓存 + 防御）；`MAGIC_NUMBER=289` 直接硬编码 |
| **B. 事件/调度替代**（采集触发/生命周期） | ~9 | Bukkit 事件 + `BukkitRunnable`（§4 映射表） |
| **C. 降级/省略**（改服务端行为） | ~11 | 多数省略；EasyPlace ✅ PacketEvents；镜像修复内联进粘贴代码 |
| **D. 调试** | 1 | 省略（`IS_RUNNING_IN_IDE`，生产无用） |

关键逐项（block 包镜像修复 §1：`MixinChestBlock`/`MixinRailBlocks`/`MixinStairBlock` 只在 Litematica 粘贴时需要 → **内联进 `SchematicPlacingUtils` 方块状态变换**；item 包 §4：`MixinBlockItem_EasyPlace` ✅ 已实现（「改写放行」双挂点，详 §5.2）；`MixinItemStack`+`MixinHopperBlockEntity` 潜影盒堆叠 ⛔ **不可能实现**——改 NMS 全局方法行为，反射改不了返回值、事件在 `maxStackSize=1` 前提下恒失败、组件污染序列化；已删全部代码且**不下发 `stackingShulkers` 元数据**（避免客户端误判）；nbt 包 §5：`IMixinNbtRead/WriteView` → `NbtView` 重写为直接 `Entity.saveWithoutId` / `BlockEntity.saveWithFullMetadata` 绕开；network 包 §6：EasyPlace 网络侧 ✅、QueryNbt 权限覆盖 → Bukkit op 权限等价）。

### 5.2 功能级决策矩阵

| 功能 | 原实现 | 迁移决策 | 备注 |
|---|---|---|---|
| **HUD 元数据/出生点/天气** | MixinMinecraftServer/ServerLevel | ✅ **做**（事件 + API） | 协议核心 |
| **TPS logger** | IMixinServerTickManager | ✅ **做**（NMS + 反射） | 反射 remainingSprintTicks |
| **MobCap logger** | (AW MAGIC_NUMBER) | ✅ **做**（硬编码 289） | |
| **配方下发** | — | ✅ **做**（NMS Recipe.CODEC） | 大包走 NMS 直发 |
| **实体/方块实体 NBT 查询** | MixinServerPlayNetworkHandler_QueryNbt | ✅ **做**（NMS saveWith*） | 权限走 Bukkit |
| **结构边界框** | MixinChunkLoadingManager | ✅ **做**（周期扫描触发） | 工作量最大 |
| **Litematica 投影粘贴** | MixinChestBlock/Rail/Stairs（镜像） | ✅ **做**（投影照抄 + 镜像修复内联；PasteTask 任务化——见 [servux/schematic.md](servux/schematic.md) §9） | |
| **潜影盒可堆叠** | MixinItemStack/Hopper | ⛔ **不可能实现**（已删代码） | §5.1 |
| **Allay 收集修复** | MixinMob/ItemEntity/Allay | ⚠️ **省略** | 改行为，影响小 |
| **EasyPlace** | MixinBlockItem + NetworkHandler | ✅ **已实现**（PacketEvents） | 详下 |
| **UpdateSuppression** | MixinLevel/LevelChunk/Block | ❌ **省略** | 改行为，无等价 |
| **调试 (IDE 模式)** | MixinSharedConstants | ❌ **省略** | 生产无用 |

**EasyPlace as-built**（「改写放行」双挂点范式）：① `EasyPlaceListener`（PacketEvents netty 线程）拦 `PLAYER_BLOCK_PLACEMENT`：编码包不取消、不读玩家状态，仅把 `cursor.x` 改写回 `relX∈[0,1)`（过 vanilla 逐轴 hitVec 校验，等效上游 NetworkHandler Mixin）+ 登记 pv 到 `EasyPlacePending`（TTL 1000ms）；② `EasyPlaceFixListener`（主线程双挂点）：`BlockCanBuildEvent`（HIGHEST，fire 于 `BlockItem.canPlace` 体内、先于全部放置副作用）以事件 BlockData（= vanilla 候选态，上游 Mixin 注入点 `stateOrig` 的同位等价物）为基座跑 `applyPlacementProtocolV3`——拒绝 → `setBuildable(false)` → vanilla place 在 placeBlock 前整次 FAIL（与上游 `setReturnValue(null)` 同位同效、零残差）；`BlockPlaceEvent`（HIGHEST）退化纯写入。已知有意差异与边缘语义（双半格派生 / 床两半错位 / 修正态 canSurvive+碰撞补查恒生效）详见归档件 04 §4 与 [operations.md](operations.md) 排错节。PacketEvents 类引用隔离在 `EasyPlaceBootstrap`（反射加载 + `catch(Throwable)` 降级）。

### 5.3 Syncmatica 5 Mixin（全部干净替换，无降级）

`MixinMinecraftServer`（生命周期 → Bukkit 事件 + 写死 dedicated）/ `MixinPlayerManager`（进服 → PlayerJoinEvent）/ `MixinServerPlayNetworkHandler`（包路由 + ExchangeTarget → plugin messaging listener + `Map<UUID, ExchangeTarget>`）/ `MixinServerCommonNetworkHandler`（兜底路由，冗余不实现）/ `MixinCommandManager`（命令 → plugin.yml）。**无「改服务端行为」类降级**（对比 Servux）——syncmatica 纯协议层 + 文件 I/O，不需要 PacketEvents / 反射改 NMS。逐项与命门详见 [syncmatica/architecture.md](syncmatica/architecture.md)。

---

## 6. Fabric ↔ Paper 逐域对照（每行可直接当迁移转换规则用）

### 6.1 总览：两个框架的根本差异

| 维度 | Fabric + Servux | Paper 插件 |
|---|---|---|
| **运行模型** | Mod 与服务端**同进程同类加载器**，可直接 `import net.minecraft.*`、用 Mixin 改字节码 | 插件在**隔离类加载器**，NMS 经 paperweight userdev 访问，**无 Mixin** |
| **NMS 可达性** | Loom 提供官方 Mojang 映射 | paperweight userdev 同样提供；**26.1 起产物与运行时同为 Mojang 名**，反射字符串直接用 |
| **改字节码** | Mixin（运行时织入）+ AccessWidener | **无**。只能反射读私有、事件替代钩子、PacketEvents 拦截包 |
| **网络** | `fabric-networking-api-v1`（原版 `CustomPacketPayload` 封装） | plugin messaging channel（映射原版 custom payload）+ NMS `ClientboundCustomPayloadPacket` |
| **生命周期** | Mixin 钩 NMS 生命周期点 | Bukkit 事件 + scheduler |
| **权限** | `fabric-permissions-api`（Lucko） | Bukkit `Permission` / LuckPerms / Vault |
| **构建** | `fabric-loom` | Gradle + `paperweight userdev` + `run-paper`（§7） |

### 6.2 网络 / 权限 / 配置 / 命令 / NBT / 杂项

| Fabric | 作用 | Paper 等价 |
|---|---|---|
| `PayloadTypeRegistry.playC2S().register(...)` | 注册 C2S payload | `Messenger.registerIncomingPluginChannel(plugin, "servux:xxx", listener)` |
| `PayloadTypeRegistry.playS2C().register(...)` | 注册 S2C payload | `Messenger.registerOutgoingPluginChannel(...)` |
| `ServerPlayNetworking.send(player, payload)` | 发 S2C | `player.sendPluginMessage(...)` 或 NMS 直发（§3.2 路径 B） |
| `ServerPlayNetworking.canSend(player, type)` | 客户端是否支持 | `getListeningPluginChannels().contains` + 同通道 C2S 证明兜底（[../AGENTS.md](../AGENTS.md) §1） |
| `record Payload implements CustomPacketPayload` + `StreamCodec` | 协议帧 | **照抄**（NMS 直连）；或直接序列化到 `byte[]` |
| `FriendlyByteBuf` / `Unpooled` | 字节流 | NMS 同名；`byte[]`↔buf 用 `Unpooled.wrappedBuffer`/`buf.array()` |
| `Permissions.check(player, node, level)` | 权限 | `framework.permission.Perms.check(...)`（节点表见 [operations.md](operations.md) §2） |
| `FabricLoader.getConfigDir()` → `config/servux.json` | 配置 | `plugin.getDataFolder()` → `plugins/VeryMcProto/servux.json` |
| `MixinCommandManager.<init>` 注入 Brigadier | 命令 | `plugin.yml` + `CommandExecutor`/`TabCompleter`（carpet 系用 Paper Brigadier） |
| `net.minecraft.nbt.CompoundTag` / `NbtOps` / `NbtIo` | NBT | NMS 同名（paperweight 直连，保真度最高）；`NbtView` 重写为直接 `saveWithoutId/saveWithFullMetadata` |
| `LogManager.getLogger(MOD_ID)` | 日志 | `plugin.getLogger()`（JUL）；SLF4J 占位符用 `Log.java` shim |
| `SharedConstants.getCurrentVersion()` | 版本 | `Reference.MC_VERSION`（version.properties 注入） |

### 6.3 一页纸转换清单（移植时逐条核对）

```
入口/生命周期    onInitialize → onEnable + Bukkit 事件
provider 注册    MixinDedicatedServer → onEnable 直接 register
tick 调度        MixinMinecraftServer.tickServer → BukkitScheduler runTaskTimer
玩家进退服        MixinPlayerManager → PlayerJoinEvent/PlayerQuitEvent
客户端就绪        （原版无）→ PlayerRegisterChannelEvent
配置路径         config/servux.json → plugins/VeryMcProto/servux.json
权限             Permissions.check → framework.permission.Perms.check
命令             MixinCommandManager → plugin.yml + CommandExecutor（carpet 系 → Paper Brigadier）
网络收(C2S)      ServerPlayNetworking → Messenger.registerIncomingPluginChannel
网络发(S2C)      ServerPlayNetworking.send → sendPluginMessage 或 NMS ClientboundCustomPayloadPacket
字节流            FriendlyByteBuf → NMS FriendlyByteBuf（paperweight 直连）
分包             PacketSplitter → 照抄（S2C 常量 32000/31995，防客户端 32767）
NBT              CompoundTag/NbtIo → NMS 直连
Mixin 读私有     → 反射 / NMS 直接调用（多数已是公开方法）
Mixin 改行为     → 降级省略 / PacketEvents / 投影代码内联
构建             fabric-loom → paperweight userdev + run-paper（26.1 无 reobf）
```

---

## 7. 构建与工具链（26.1.2 as-built）

> 26.1 起 Mojang 移除服务端混淆：**reobf 废除**（paperweight 官方：reobf 插件无法在 Paper 26.1+ 加载），产物即 Mojang 映射 jar。版本唯一来源 `gradle.properties`（`mcVersion` / `buildNumber`）。

```kotlin
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

dependencies {
    paperweight.paperDevBundle("26.1.2.build.74-stable")
    // 26.1 起新格式 <mc>.build.<N>-stable；可选依赖（EasyPlace）：compileOnly packetevents-spigot
}

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

tasks {
    runServer {
        minecraftVersion(providers.gradleProperty("mcVersion").get())
        jvmArgs("-Xms2G", "-Xmx2G")
        runDirectory(file("run-" + providers.gradleProperty("mcVersion").get()))  // 运行目录按线隔离
    }
    processResources {
        // expand 占位符必须显式 inputs.property(...) 参与 up-to-date 跟踪（曾实证陈旧展开事故）
        filesMatching("plugin.yml") { expand(props) }
    }
    // 无 reobfJar；build 挂 verifyVersionInjection 终检版本注入一致性
}
```

**26.1 升级构建面实测**（1.21.11 → 26.1.2）：Java 21→**25**（上游明文 "Minecraft 26.1+ uses Java 25"）；dev bundle `26.1.2.build.74-stable`（新格式）；paperweight 2.0.0-beta.21→**beta.23**；run-paper 3.0.2→**3.1.0**（v2 下载 API 下线走 Fill v3）；Gradle wrapper 9.6.1→**9.7.1**；**reobfJar 删除**；`api-version: '26.1.2'` 三段精确式（1.20.5+ 支持补丁段；本插件 MOD_STRING 硬门禁绑死精确补丁，初版误留 '1.21' 曾致 Modrinth 错标）。runServer 运行目录按线隔离（`run-<mcVersion>/`——共享 `run/` 会让两线静默互踩世界与 packetevents 版本）。

> Gradle wrapper 9.7.1；JDK 25 工具链经 foojay-resolver-convention 自动解析（见 [../AGENTS.md](../AGENTS.md) §技术栈与构建）。

---

## 8. 防御性编程要点（as-built）

- **所有装配 try-catch**：主类 onEnable/onDisable、LifecycleBridge 事件分发、tickProviders，单点异常不影响整体。
- **反射防御**：`Reflect.getOr(obj, field, default)` 失败返回默认值。
- **并发安全**：`PacketSplitter.READING_SESSIONS` 用 `ConcurrentHashMap` + `ReadingSession.receive` 加 `synchronized`；坏包立即丢弃 session。
- **大包保护**：`ProtocolChannel.send` 拒绝超 `Messenger.MAX_MESSAGE_SIZE` 的包；`PacketSplitter.send` 入口 16MB 重组上限预检（log-and-drop）。
- **C2S 解析双闸（安全修复）**：`DataTagIo.readTag` GZIP 流式直读 + `NbtAccounter.create(64MB)`——解压域逐结构计数、**先记账后分配**，单闸同时拦截 gzip bomb 与长度字段炸弹；超配额坏包丢弃。上游 malilib `SizeTracker` 同语义——**勿回退为整体解压进内存再解析**（异常三分语义由 `DataTagIoTest` 锁定）。
- **投影体积一致性预检（安全修复）**：`readSubRegionsFromNBT` 每 region 校验声明体积 ≤ BlockStates 实际容量（floor 除法 + 回绕守卫），拒绝脱钩坏包——上游 `catch(OutOfMemoryError)` 是客户端本地文件语境死防御**勿搬运**（详见 [servux/schematic.md](servux/schematic.md)）。
- **客户端未装 mod**：`getListeningPluginChannels` 检测 + MAX_FAILURES 计数 → invalid，不刷屏。
- **配置原子写**：`JsonUtils` 用 `.tmp` + move。
- **RegistryAccess 时机**：必须 `ServerLoadEvent(STARTED)` 后捕获。
- **已知残留面（登记）**：`SyncmaticaUtil.readNbtFromFile` 的 `NbtAccounter.unlimitedHeap()`——输入为客户端上传落盘的 `.litematic`（metadata peek），理论 gzip bomb 面；宜换 `FILE_MAX_BYTES` 式配额。

---

## 9. 验证策略与保真度目标

1. **单元级**：`PacketSplitter` / `FeatureSet` / `LitematicaBitArray` / `DataTagIo` / task 组等纯函数单测（`./gradlew test`）。
2. **协议级**：`runServer` + 真 Fabric 客户端按 [servux/testing.md](servux/testing.md) / [syncmatica/testing.md](syncmatica/testing.md) / [jei/protocol.md](jei/protocol.md) 流程验证。
3. **抓包对照**：比对 Fabric+原版服务端 mod 与 Paper+插件 的字节流。
4. **回归**：跨 MC 小版本验证 NMS 反射点（升级 SOP 见 [operations.md](operations.md) §6）。

| 层 | 保真度 | 说明 |
|---|---|---|
| 网络协议字节 | **100%** | 同一 `FriendlyByteBuf`/`CompoundTag`/DataTag 线格式，字节级一致 |
| 通道/版本号 | **100%** | 通道名、协议版本号与原版逐字对齐（26.1 真值见 [servux/protocol.md](servux/protocol.md) §5） |
| 数据采集 | **≈95%** | 绝大多数 NMS 直连；个别反射字段版本敏感 |
| 服务端行为改造 | **部分降级** | EasyPlace ✅；UpdateSuppression/Allay 省略；潜影盒堆叠不可能（§5） |

> **结论**：对"Fabric 客户端 + Paper 服务端"核心场景（HUD/结构/投影/实体查询/投影共享/配方同步/Carpet 子集）达到与原版**功能等价**；仅少数"服务端行为增强"特性降级，均不影响协议主功能。
