# 生命周期层 + 4 Provider 迁移笔记 (lifecycle-dataproviders)

> 模块范围: `event/`（分发器 Dispatcher）+ `servux/`（回调实现 Listener）+ `interfaces/`（回调接口）+ `dataproviders/` 下除 `HudDataProvider` 外的全部（`DataProviderBase`/`IDataProvider`/`DataProviderManager`/`ServuxConfigProvider`/`EntitiesDataProvider`/`TweaksDataProvider`/`StructureDataProvider`/`LitematicsDataProvider`）。
> 目标包根: `verymc.top.veryMcProto`，子包按 CLAUDE.md 架构表映射。

---

## 一、总览：生命周期分发架构（Fabric → Paper）

Servux 用一套「接口 + 单例 Dispatcher + Listener 回调」实现服务器/玩家生命周期分发。在 Fabric 里，Dispatcher 的 `onXxx()` 方法由 **Mixin 钩子**（`MixinMinecraftServer` / `MixinPlayerManager` 等）在 NMS 关键点调用；在 Paper 上没有 Mixin，这些钩子改由 **Bukkit 事件**驱动。

```
[Mixins]                       [Paper]
ServerHandler  ←─ MixinMinecraftServer         ←─ Bukkit: ServerLoadEvent, Plugin disable, ResourcesReloadedEvent
PlayerHandler  ←─ MixinPlayerManager           ←─ Bukkit: AsyncPlayerPreLoginEvent, PlayerJoinEvent, PlayerRespawnEvent,
                                                  PlayerCommandSendEvent (op?), PlayerQuitEvent
ServerInitHandler ←─ ModInitializer.onInitialize / Servux.onInitialize() ←─ JavaPlugin.onEnable()
        │
        ▼
分发到 List<I*Listener>
  ServerListener  (IServerListener)   → DataProviderManager 配置读写 + onCaptureImmutable + HudDataProvider.checkWorldSeed
  PlayerListener  (IPlayerListener)   → 各 Provider 的 sendMetadata / register / removePlayer
  ServuxInitHandler (IServerInitHandler) → 注册 6 个 Provider + 把 ServerListener/PlayerListener 挂到 Dispatcher
```

### Bukkit 事件映射表（迁移时直接按下表实现 Dispatcher.onXxx 的触发源）

| Dispatcher 方法 (event 包) | 原 Fabric Mixin 触发点 | Paper 触发事件 |
|---|---|---|
| `ServerHandler.onServerStarting(server)` | `MixinMinecraftServer.onServerStarted` 起始 | `ServerLoadEvent`（`ServerLoadEvent.LoadType.STARTED`）|
| `ServerHandler.onServerStarted(server)` | `MixinMinecraftServer` 完成启动 | 同上（顺序：starting→started 可在 onEnable 末尾 / ServerLoadEvent 复刻）|
| `ServerHandler.onServerResourceReloadPre(server, rm)` | `MixinServerReloadResources` | Bukkit 无直接等价；可用 `ServerResourcesReloadedEvent`（Paper，仅 Post）|
| `ServerHandler.onServerResourceReloadPost(server, rm, ok)` | 同上 | `ServerResourcesReloadedEvent`（Post）|
| `ServerHandler.onServerStopping(server)` | `MixinMinecraftServer.onStopped`/stop | `Plugin.disable()` / `onDisable()` |
| `ServerHandler.onServerStopped(server)` | server kill 前 | `onDisable()` |
| `PlayerHandler.onClientConnect(addr, profile, result)` | `MixinPlayerManager` connect 阶段 | `AsyncPlayerPreLoginEvent` |
| `PlayerHandler.onPlayerJoin(addr, profile, player)` | `MixinPlayerManager.onPlayerJoin` | `PlayerJoinEvent` |
| `PlayerHandler.onPlayerRespawn(new, old)` | `MixinPlayerManager.respawn` | `PlayerRespawnEvent` |
| `PlayerHandler.onPlayerOp/DeOp(profile, uuid, player)` | `MixinPlayerManager.op/deOp` | `PlayerCommandPreprocessEvent`? → 实际用 `PlayerCommandSendEvent` 不可靠；**降级**：监听 OP 变更无直接事件，可用 Paper 的 `PlayerOpStatusEvent`? 不存在。建议用调度轮询 `player.isOp()` 或 `ServerOperator` 间接；**最低优先级：略过 op 分发**（Servux 只用于权限刷新，Paper 用 LuckPerms/Bukkit hasPermission 即时查询即可）|
| `PlayerHandler.onPlayerLeave(player)` | `MixinPlayerManager.remove` | `PlayerQuitEvent` |
| `ServerInitHandler.onServerInit()` | `Servux.onInitialize()`（ModInit）| `JavaPlugin.onEnable()` |

> 注：当前 `ServerListener`/`PlayerListener` 实际只覆写了 `onPlayerJoin`/`onPlayerLeave`/6 个 server 方法；op/respawn/clientConnect 在 Listener 里是空实现（default no-op）。Paper 迁移可只接上表实际用到的事件。

---

## 二、文件清单表

| 原版文件 (相对 servux 根) | 行数 | 建议目标包 | NMS 依赖 | Mixin/AW 依赖 | 迁移方式 |
|---|---|---|---|---|---|
| `event/ServerHandler.java` | 107 | `event/` | MinecraftServer, ResourceManager | 无（Dispatcher 自身；触发由 Mixin→Bukkit事件）| 适配（改触发源为 Bukkit 事件）|
| `event/PlayerHandler.java` | 108 | `event/` | ServerPlayer, NameAndId, GameProfile, Component, SocketAddress | 无 | 适配 |
| `event/ServerInitHandler.java` | 35 | `event/` | 无 | 无 | 照抄（触发点改 onEnable）|
| `servux/ServerListener.java` | 58 | `event/` 或 `lifecycle/` | MinecraftServer, ResourceManager, RegistryAccess | 无 | 适配（注册到 Bukkit 事件回调里）|
| `servux/PlayerListener.java` | 78 | `event/` 或 `lifecycle/` | ServerPlayer, GameProfile, SocketAddress | 无 | 适配（→ Bukkit PlayerJoin/QuitEvent）|
| `servux/ServuxInitHandler.java` | 24 | 主类 onEnable 内联 | 无 | 无 | 照抄（搬进 JavaPlugin.onEnable）|
| `interfaces/IServerListener.java` | 44 | `interfaces/` | MinecraftServer, ResourceManager | 无 | 照抄（去 NMS 依赖，换 Bukkit 类型或保留 NMS Server）|
| `interfaces/IPlayerListener.java` | 19 | `interfaces/` | ServerPlayer, NameAndId, GameProfile, Component, SocketAddress, UUID | 无 | 适配（精简到 join/leave/respawn）|
| `interfaces/IServerManager.java` | 7 | `interfaces/` | 无 | 无 | 照抄 |
| `interfaces/IPlayerManager.java` | 7 | `interfaces/` | 无 | 无 | 照抄 |
| `interfaces/IServerInitHandler.java` | 6 | `interfaces/` | 无 | 无 | 照抄 |
| `interfaces/IServerInitDispatcher.java` | 6 | `interfaces/` | 无 | 无 | 照抄 |
| `interfaces/IServerCommand.java` | 16 | `interfaces/` 或 `command/` | CommandDispatcher, CommandBuildContext, CommandSourceStack, Commands | 无 | 适配（改用 Paper Brigadier / `CommandManager`）|
| `interfaces/IThreadTaskBase.java` | 33 | `util/` | 无 | 无 | 照抄 |
| `interfaces/AsyncThreadTaskBase.java` | 42 | `util/` | 无 | 无 | 照抄 |
| `interfaces/DefaultThreadTaskBase.java` | 40 | `util/` | 无 | 无 | 照抄 |
| `interfaces/IThreadDaemonExecutor.java` | 33 | `util/` | 无 | 无 | 照抄 |
| `interfaces/IThreadDaemonHandler.java` | 42 | `util/` | 无 | 无 | 照抄 |
| `dataproviders/IDataProvider.java` | 143 | `dataproviders/` | Identifier, MinecraftServer, ServerPlayer, ProfilerFiller, IPluginServerPlayHandler | 无 | 适配（Identifier→ResourceLocation, IServuxSetting 等）|
| `dataproviders/DataProviderBase.java` | 121 | `dataproviders/` | Identifier | 无 | 照抄（NMS Identifier→ResourceLocation）|
| `dataproviders/DataProviderManager.java` | 305 | `dataproviders/` | RegistryAccess.Frozen, MinecraftServer, ProfilerFiller | 无（Reference.DEFAULT_CONFIG_DIR 是 Fabric 配置目录常量）| 适配（configDir 改 `getDataFolder()`，ProfilerFiller 去；tick 由 BukkitScheduler 调）|
| `dataproviders/ServuxConfigProvider.java` | 157 | `dataproviders/` | CommandSourceStack, Component, Identifier | 无 | 适配（权限→hasPermission, i18n/语言）|
| `dataproviders/EntitiesDataProvider.java` | 300 | `dataproviders/` | CompoundTag, ListTag, BlockPos, Identifier, ServerPlayer, Entity, EntityType, BlockEntity, NbtView, RegistryAccess, Permissions(COMMANDS_GAMEMASTER) | 无（fixAllayGathering 的 Mixin 在别处）| 适配（权限改 hasPermission；saveWithFullMetadata 保留 NMS）|
| `dataproviders/TweaksDataProvider.java` | 373 | `dataproviders/` | CompoundTag, ListTag, BlockPos, Identifier, ServerPlayer, Entity, EntityType, BlockEntity, ItemStack, DataComponents, NbtView, InventoryUtils | Mixin `MixinItemStack`/`MixinHopper`（堆叠修复）实现 provider 的开关 | 适配（堆叠 Mixin 降级，见风险点）|
| `dataproviders/StructureDataProvider.java` | 557 | `dataproviders/` | ChunkPos, Level, ServerLevel, ServerPlayer, ChunkAccess, LevelChunk, ChunkStatus, Structure, StructureStart, TerrainAdjustment, StructurePieceSerializationContext, BuiltInRegistries, ServerGamePacketListenerImpl, ListTag, CompoundTag, fastutil LongSet/LongOpenHashSet/LongIterator | `IMixinChunk`/`ChunkAccess.getAllReferences()`（Accessor→需反射/NMS 直访）| 适配（getAllReferences 反射/NMS 直访；MixinServerChunkLoadingManager watch chunk → Bukkit ChunkEvent）|
| `dataproviders/LitematicsDataProvider.java` | 424 | `dataproviders/` | CompoundTag, ListTag, BlockPos, Identifier, ServerLevel, ServerPlayer, Entity, EntityType, BlockEntity, LevelChunk, ChunkPos, Vec3, AABB, RegistryAccess, NbtView, NbtUtils, PositionUtils | 镜像修复 Mixin（Chest/Rail/Stairs）受 provider 3 个 bool 开关控制 | 适配（镜像 Mixin→粘贴代码内联修正；pasteTo 是核心）|

> 说明：`event/`/`servux/`/`interfaces/` 全部 **0 Mixin 依赖**（Dispatcher/Listener 自身）；Mixin 全在「触发 Dispatcher 的 NMS 钩子」与「Provider 开关所对应的行为改 Mixin」上。Provider 自身的 `registerHandler/unregisterHandler` 走 `ServerPlayHandler`（网络层，另模块），非 Mixin。

---

## 三、逐文件详解

### 1. `event/ServerHandler.java` (107 行)
- **职责**: IServerManager 单例实现；持有 `List<IServerListener> handlers`，把 6 个生命周期事件 fan-out 给所有注册的 listener。
- **关键字段**:
  - `private static final ServerHandler INSTANCE = new ServerHandler()` (单例)
  - `private final List<IServerListener> handlers = new ArrayList<>()`
- **关键方法**:
  - `public static IServerManager getInstance()` (L19)
  - `registerServerHandler(IServerListener)` (L22) / `unregisterServerHandler(IServerListener)` (L31) — 去重 add / remove
  - `onServerStarting(MinecraftServer)` (L37) — 遍历调 `handler.onServerStarting`
  - `onServerStarted(MinecraftServer)` (L49)
  - `onServerResourceReloadPre(MinecraftServer, ResourceManager)` (L61)
  - `onServerResourceReloadPost(MinecraftServer, ResourceManager, boolean success)` (L73)
  - `onServerStopping(MinecraftServer)` (L85)
  - `onServerStopped(MinecraftServer)` (L97)
- **NMS 依赖**: `net.minecraft.server.MinecraftServer`, `net.minecraft.server.packs.resources.ResourceManager`
- **Mixin/Accessor 依赖**: 无（Dispatcher 本体）
- **迁移方式**: 适配。Dispatcher 类结构照抄，但 6 个 `onXxx()` 的**调用方**由 Mixin 改为 Bukkit 事件监听器（主类或独立 `LifecycleListener implements Listener`）。`MinecraftServer` 参数在 Paper 可保留（通过 `Bukkit.getServer()` → CraftServer 的 `getServer()` 反射拿 NMS MinecraftServer），或换成 Bukkit `Server`。资源重载 Pre/Post：Paper 仅有 `ServerResourcesReloadedEvent`（Post），Pre 无等价 → 合并到 Post 或略过 Pre。
- **歧义/风险点**: Fabric 里 starting 与 started 是两个时机（readFromConfig vs writeToConfig+captureImmutable）；Paper 的 `ServerLoadEvent` 只在 STARTED 后触发一次，需手动分两步在 `onEnable` 模拟 starting、在 `ServerLoadEvent` 模拟 started，否则 `ServerListener` 的「先读后写」语义会丢。

### 2. `event/PlayerHandler.java` (108 行)
- **职责**: IPlayerManager 单例；持有 `List<IPlayerListener> handlers`，把 6 个玩家事件 fan-out。
- **关键字段**: `INSTANCE` 单例 + `handlers` 列表
- **关键方法**:
  - `getInstance()` (L20)
  - `registerPlayerHandler` / `unregisterPlayerHandler` (L23/L32)
  - `onClientConnect(SocketAddress addr, NameAndId profile, @Nullable Component result)` (L38)
  - `onPlayerJoin(SocketAddress addr, GameProfile profile, ServerPlayer player)` (L50)
  - `onPlayerRespawn(ServerPlayer newPlayer, ServerPlayer oldPlayer)` (L62)
  - `onPlayerOp(NameAndId profile, UUID uuid, @Nullable ServerPlayer player)` (L74)
  - `onPlayerDeOp(NameAndId profile, UUID uuid, @Nullable ServerPlayer player)` (L86)
  - `onPlayerLeave(ServerPlayer player)` (L98)
- **NMS 依赖**: `ServerPlayer`, `net.minecraft.server.players.NameAndId`（1.21 Mojang 名；旧版为 `UserWhiteListEntry`/`GameProfile`），`com.mojang.authlib.GameProfile`, `net.minecraft.network.chat.Component`, `java.net.SocketAddress`
- **迁移方式**: 适配。结构照抄，触发改 Bukkit 事件。`NameAndId` 是 NMS 类型（Mojang `NameAndId`）；Paper 上 Bukkit 事件不直接带它 → 适配时把 `onClientConnect`/`onPlayerOp` 的参数改为从 `Player` 推导（`player.getName()`/`player.getUniqueId()`）或干脆省略（见风险点）。
- **歧义/风险点**: `NameAndId` 在 Paper API 层无对应；建议接口精简。OP/DeOP 在 Bukkit 无专用事件，Paper 迁移**降级**：op 分发本就空实现，直接略过；respawn 用 `PlayerRespawnEvent`（注意 Servux 的 newPlayer/oldPlayer 双引用语义 → Bukkit 只给当前 player，old 不可得，需自行缓存）。

### 3. `event/ServerInitHandler.java` (35 行)
- **职责**: IServerInitDispatcher 单例；持有 `List<IServerInitHandler>`，`onServerInit()` 触发一次（注册 Provider + 挂 Listener）。
- **关键字段**: `INSTANCE`, `handlers`
- **关键方法**: `getInstance()` (L12), `registerServerInitHandler` (L16), `onServerInit()` (L25)
- **NMS 依赖**: 无
- **迁移方式**: 照抄。Paper 上 `onServerInit()` 在 `JavaPlugin.onEnable()` 中直接调用（或直接内联，因为整个项目只有 `ServuxInitHandler` 一个 IServerInitHandler 实现）。
- **歧义/风险点**: 无。

### 4. `servux/ServerListener.java` (58 行)
- **职责**: IServerListener 实现；把服务器生命周期翻译成 DataProviderManager 的配置读写 + 不可变快照捕获 + HUD 世界种子检查。
- **关键方法**:
  - `onServerStarting(server)` (L14) → `DataProviderManager.INSTANCE.readFromConfig()`
  - `onServerStarted(server)` (L20) → `writeToConfig()` + `onCaptureImmutable(server.registryAccess())` + 若 HudDataProvider 启用则 `checkWorldSeed(server)`
  - `onServerResourceReloadPre(server, rm)` (L32) → `readFromConfig()`
  - `onServerResourceReloadPost(server, rm, success)` (L40) → `writeToConfig()` + `onCaptureImmutable(server.registryAccess())` + `ServuxConfigProvider.INSTANCE.registerHandler()`
  - `onServerStopping(server)` (L47) → `onServerTickEndPre()` + `writeToConfig()`
  - `onServerStopped(server)` (L54) → `onServerTickEndPost()`
- **NMS 依赖**: `MinecraftServer.registryAccess()` → `RegistryAccess.Frozen`, `ResourceManager`
- **迁移方式**: 适配。逻辑照抄，但被调时机由 Mixin → Bukkit 事件。`server.registryAccess()` 在 Paper 可用反射（`CraftServer.getServer().registryAccess()`）。
- **歧义/风险点**: `onCaptureImmutable` 捕获的 `RegistryAccess.Frozen` 用于 Entities/Litematics 的 `NbtView.getWriter(registryAccess)`、HUD 的 `Recipe.CODEC.encodeStart(NbtOps.INSTANCE, ...)`。**必须在 `onServerStarted` 之后捕获**，否则 `Recipe.CODEC` 编码会拿到空注册表。

### 5. `servux/PlayerListener.java` (78 行)
- **职责**: IPlayerListener 实现；把玩家 join/leave 翻译成 5 个 Provider 的握手/注销。
- **关键方法**:
  - `onPlayerJoin(addr, profile, player)` (L12): 对每个启用的 Provider 调 `sendMetadata(player)`（HUD/Entities/Litematics/Tweaks）或 `register(player)`（Structure）。顺序：HUD → Structure → Entities → Litematics → Tweaks。
  - `onPlayerLeave(player)` (L46): 对每个启用的 Provider 调 `removePlayer(player)`（HUD/Entities/Litematics/Tweaks）或 `unregister(player)`（Structure）。
- **NMS 依赖**: `ServerPlayer`, `GameProfile`, `SocketAddress`
- **迁移方式**: 适配。→ `PlayerJoinEvent`（注意 Servux 在玩家刚连入即 sendMetadata，Bukkit `PlayerJoinEvent` 时机略晚但仍可用；Fabric 的「第一次 sendMetadata 失败」问题 → Bukkit 同理，建议在 join 后延迟 1-2 tick 重发，或依赖客户端的 metadata 请求 C2S 触发）和 `PlayerQuitEvent`。
- **歧义/风险点**: Provider 的 `isEnabled()` 检查必须在 join 时重新评估（config reload 后可能变化）。`StructureDataProvider.register` 与 `sendMetadata` 语义不同（register 还做初始范围同步）。

### 6. `servux/ServuxInitHandler.java` (24 行)
- **职责**: IServerInitHandler 实现；**注册 6 个 Provider**（顺序：ServuxConfig → Structure → HUD → Litematics → Entities → Tweaks；`DebugDataProvider` 注释掉）+ 把 `ServerListener`/`PlayerListener` 挂到对应 Dispatcher。
- **关键方法**: `onServerInit()` (L11):
  - `DataProviderManager.INSTANCE.registerDataProvider(X.INSTANCE)` × 6
  - `ServerHandler.getInstance().registerServerHandler(new ServerListener())`
  - `PlayerHandler.getInstance().registerPlayerHandler(new PlayerListener())`
- **NMS 依赖**: 无
- **迁移方式**: 照抄（建议直接搬进 `JavaPlugin.onEnable()`：注册 Provider → new Listener 挂 Bukkit/PluginManager）。
- **歧义/风险点**: Provider 注册顺序影响 `providersImmutable` 列表顺序（`getAllProviders()` 命令展示顺序）。`servux_main` 不可被禁用（见 DataProviderManager.readFromConfig L243）。

### 7. `interfaces/IServerListener.java` (44 行)
- **职责**: 服务器生命周期回调接口（6 个 default no-op 方法）。
- **方法签名**:
  - `default void onServerStarting(MinecraftServer server)`
  - `default void onServerStarted(MinecraftServer server)`
  - `default void onServerResourceReloadPre(MinecraftServer server, ResourceManager resourceManager)`
  - `default void onServerResourceReloadPost(MinecraftServer server, ResourceManager resourceManager, boolean success)`
  - `default void onServerStopping(MinecraftServer server)`
  - `default void onServerStopped(MinecraftServer server)`
- **NMS 依赖**: `MinecraftServer`, `ResourceManager`
- **迁移方式**: 照抄（保留 NMS 类型以便传 server/registryAccess）；或把 `MinecraftServer` 换成 Bukkit `Server` 并在需要 registryAccess 处单独获取。
- **歧义/风险点**: 无。

### 8. `interfaces/IPlayerListener.java` (19 行)
- **职责**: 玩家生命周期回调接口（6 个 default no-op）。
- **方法签名**:
  - `default void onClientConnect(SocketAddress addr, NameAndId profile, Component result)`
  - `default void onPlayerJoin(SocketAddress addr, GameProfile profile, ServerPlayer player)`
  - `default void onPlayerRespawn(ServerPlayer newPlayer, ServerPlayer oldPlayer)`
  - `default void onPlayerOp(NameAndId profile, UUID uuid, @Nullable ServerPlayer player)`
  - `default void onPlayerDeOp(NameAndId profile, UUID uuid, @Nullable ServerPlayer player)`
  - `default void onPlayerLeave(ServerPlayer player)`
- **NMS 依赖**: `NameAndId`, `Component`, `GameProfile`, `ServerPlayer`, `SocketAddress`, `UUID`
- **迁移方式**: 适配（精简：移除 `onClientConnect`/`onPlayerOp`/`onPlayerDeOp` 或改成 `(Player)`；`NameAndId` 换成 Bukkit 类型）。
- **歧义/风险点**: 实际仅 join/leave 被覆写；其余可删。

### 9–11. `interfaces/IServerManager.java` / `IPlayerManager.java` / `IServerInitHandler.java` / `IServerInitDispatcher.java` (各 6–7 行)
- **职责**: Dispatcher 注册接口。`IServerManager.{register,unregister}ServerHandler(IServerListener)`；`IPlayerManager.{register,unregister}PlayerHandler(IPlayerListener)`；`IServerInitHandler.onServerInit()`；`IServerInitDispatcher.registerServerInitHandler(IServerInitHandler)`。
- **NMS 依赖**: 无
- **迁移方式**: 照抄。
- **歧义/风险点**: 无。

### 12. `interfaces/IServerCommand.java` (16 行)
- **职责**: 命令注册接口（Fabric 用 NMS Brigadier）。
- **方法签名**: `void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment)`
- **NMS 依赖**: `com.mojang.brigadier.CommandDispatcher`, `net.minecraft.commands.{CommandBuildContext, CommandSourceStack, Commands}`
- **迁移方式**: 适配。Paper 用 `io.papermc.paper.command.brigadier.CommandManager`（Paper 1.20.6+ Brigadier API）或 `PluginCommand`/`CommandExecutor`。NMS Brigadier 在 paperweight userdev 下仍可直接用，但 Paper 推荐用其 Brigadier API。
- **歧义/风险点**: 若想用 Paper 的 `LifecycleEvent` 注册命令，需 `PaperPluginMeta`/`CommandManager.register(...)`。命令模块非本模块重点，仅列接口。

### 13. `interfaces/IThreadTaskBase.java` + `AsyncThreadTaskBase` + `DefaultThreadTaskBase` + `IThreadDaemonExecutor` + `IThreadDaemonHandler.java` (共 ~190 行)
- **职责**: 异步/后台任务队列框架（`isFinished/finish/runAsync/run`，Daemon 线程 `start/stop/reset/addTask/getNextTask/getTaskInterval/processTask`）。供 `schematic/transmit/SchematicBufferManager` 分片发送用。
- **关键字段**: `AsyncThreadTaskBase.finished = new AtomicBoolean(false)`；同 `DefaultThreadTaskBase`。
- **NMS 依赖**: 无（纯 JDK `CompletableFuture`/`AtomicBoolean`/`Queue`）
- **迁移方式**: 照抄。注意 `AsyncThreadTaskBase.runAsync()` 与 `DefaultThreadTaskBase.run()` 是 abstract，子类在 schematic 模块。
- **歧义/风险点**: 异步任务在 Paper 主线程外执行时**禁止直接调 Bukkit API**（非线程安全）；schematic 分片发送如需调 `player.sendPluginMessage` 必须切回主线程（`Bukkit.getScheduler().runTask(plugin, ...)`）。这是该框架迁移的最大隐患。

### 14. `dataproviders/IDataProvider.java` (143 行)
- **职责**: Provider 统一接口（元信息 + 启停 + tick + 权限 + 配置序列化）。
- **关键方法签名**:
  - `String getName()` / `String getDescription()` / `Identifier getNetworkChannel()` / `int getProtocolVersion()`
  - `boolean isEnabled()` / `void setEnabled(boolean)` / `boolean isRegistered()` / `void setRegistered(boolean)`
  - `void registerHandler()` / `void unregisterHandler()`
  - `default boolean shouldTick() {return false;}` / `int getTickInterval()` / `default void tick(MinecraftServer server, int tickCounter, ProfilerFiller profiler)`
  - `default IPluginServerPlayHandler<?> getPacketHandler()`
  - `boolean isPlayerRegistered(ServerPlayer player)` / `boolean hasPermission(ServerPlayer player)`
  - `void onTickEndPre()` / `void onTickEndPost()`
  - `JsonObject toJson()` / `void fromJson(JsonObject obj)` / `List<IServuxSetting<?>> getSettings()`
- **NMS 依赖**: `Identifier`(=ResourceLocation), `MinecraftServer`, `ServerPlayer`, `ProfilerFiller`, `IPluginServerPlayHandler`(网络层)
- **迁移方式**: 适配。`Identifier`→`net.minecraft.resources.ResourceLocation`（paperweight 直用）；`ProfilerFiller` 在 Paper 无暴露 → 改用空实现或删除参数（tick 的 profiler 仅用于性能分析，可去）。
- **歧义/风险点**: `tick(MinecraftServer, tickCounter, ProfilerFiller)` 的 tickCounter（自 server 启动的 tick 计数）在 Bukkit 用 `(int)(Bukkit.getCurrentTick())` 或自维护计数；DataProviderManager.tickProviders 按 `tickCounter % getTickInterval()` 调度。

### 15. `dataproviders/DataProviderBase.java` (121 行)
- **职责**: IDataProvider 抽象基类；封装 name/channel/version/perm/enabled/playRegistered/tickRate + 基于 `getSettings()` 的 toJson/fromJson 序列化。
- **关键字段**:
  - `protected final Identifier networkChannel`
  - `protected final String name`
  - `protected final String permNode`
  - `protected final String description`
  - `protected final int protocolVersion`
  - `protected final int defaultPerm`（构造里 `defaultPerm > -1 && defaultPerm < 5 ? defaultPerm : 0` 钳制）
  - `protected boolean enabled`
  - `protected boolean playRegistered`
  - `private int tickRate = 40`
- **关键方法**:
  - 构造 `DataProviderBase(name, channel, protocolVersion, defaultPerm, permNode, description)` (L21)
  - `setTickRate(int)` (L79): `Math.max(tickRate, 1)`
  - `getTickInterval()` (L85) 返回 `tickRate`
  - `getSettings()` (L91) 返回 `List.of()`（子类覆写）
  - `toJson()` (L97): 遍历 settings `object.add(setting.name(), setting.writeToJson())`
  - `fromJson(JsonObject)` (L110): 遍历 settings `setting.readFromJson(obj.get(name))`
- **NMS 依赖**: `Identifier`
- **迁移方式**: 照抄（`Identifier`→`ResourceLocation`）。`defaultPerm` 字段被存但实际权限逻辑各 Provider 用各自 setting 的 permissionLevel；可保留。
- **歧义/风险点**: `toJson`/`fromJson` 的 settings 序列化机制是 Provider 配置落盘的核心，迁移后 settings 模块（另模块）的 `IServuxSetting.writeToJson/readFromJson` 必须配套实现。

### 16. `dataproviders/DataProviderManager.java` (305 行)
- **职责**: 全局 Provider 注册中心 + 配置文件（servux.json）读写 + tick 调度 + 不可变注册表快照。
- **关键字段**:
  - `public static final DataProviderManager INSTANCE`
  - `HashMap<String, IDataProvider> providers`（name 小写 → provider）
  - `ImmutableList<IDataProvider> providersImmutable`
  - `ArrayList<IDataProvider> providersTicking`
  - `protected Path configDir = null`
  - `protected RegistryAccess.Frozen immutable = RegistryAccess.EMPTY`
- **关键方法**:
  - `registerDataProvider(IDataProvider)` (L47): 小写 name 去重 add + `providersImmutable = ImmutableList.copyOf(...)`
  - `setProviderEnabled(String, boolean)` (L67) / `setProviderEnabled(IDataProvider, boolean)` (L73): 若启用或状态变化 → `setEnabled` + `updatePacketHandlerRegistration` + 维护 `providersTicking`
  - `tickProviders(MinecraftServer, tickCounter, ProfilerFiller)` (L102): 对 `providersTicking` 按 `tickCounter % getTickInterval()==0` 调 `provider.tick`
  - `registerEnabledPacketHandlers()` (L116) / `updatePacketHandlerRegistration(provider)` (L124): enabled→registerHandler 否则 unregisterHandler
  - `onCaptureImmutable(RegistryAccess.Frozen)` (L136) / `getRegistryManager()` (L141)
  - `onServerTickEndPre()` (L146) / `onServerTickEndPost()` (L154): 对所有 provider 调同名
  - `getProviderByName(String)` (L162) / `getSettingByName(String)` (L167)（支持 `provider:setting` 拆分）
  - `readFromConfig()` (L204): 解析 `servux.json`，根 `DataProviderToggles` 子对象控制 enabled；每个 provider 节点 `fromJson`；**`servux_main` 强制保持启用**（L243）；无配置时除 `debug_data` 外全部启用
  - `writeToConfig()` (L260): 写 `DataProviderToggles` + 每个 provider `toJson`
  - `getConfigFile()` (L284): `configDir.resolve("servux.json")`，`configDir` 缺省取 `Reference.DEFAULT_CONFIG_DIR`，不存在则 `Files.createDirectory`
- **NMS 依赖**: `RegistryAccess.Frozen`, `RegistryAccess.EMPTY`, `MinecraftServer`, `ProfilerFiller`
- **Mixin/AW**: 无（`Reference.DEFAULT_CONFIG_DIR` 是 Fabric 的 `FabricLoader.getInstance().getConfigDir()`）
- **迁移方式**: 适配。
  - `configDir` → `plugin.getDataFolder().toPath()`（Paper 保证插件目录存在）
  - `getConfigFile()` → `new File(getDataFolder(), "servux.json").toPath()`
  - `tickProviders` → 由 `BukkitScheduler.runTaskTimer` 驱动，tickCounter 用自维护或 `Bukkit.getCurrentTick()`
  - `ProfilerFiller` 参数删除
  - `Reference.DEFAULT_CONFIG_DIR`/`DEFAULT_RUN_DIR` → 插件目录
- **歧义/风险点**:
  1. `servux_main` 不可禁用的硬编码逻辑必须保留，否则配置 provider 自己被关 → 命令失效。
  2. `readFromConfig` 在 provider 注册**之前**调用会读到空 `providersImmutable`（顺序敏感）；Paper onEnable 顺序须严格：`ServuxInitHandler`（注册）→ `ServerListener.onServerStarting`（readFromConfig）→ `onServerStarted`（writeToConfig + captureImmutable）。
  3. `RegistryAccess.EMPTY` 静态字段在 Paper NMS 可用，但要在 `onCaptureImmutable` 后才用真值。

### 17. `dataproviders/ServuxConfigProvider.java` (157 行)
- **职责**: `servux_main` provider；管理全局配置 + i18n 默认语言 + EasyPlace 权限 + debug 开关。**不注册网络通道**（super 用了 `servux:main` channel 但只作元信息，无 packet handler）。
- **关键 settings**:
  - `ServuxIntSetting permission_level` ("permission_level", 默认 0, max 4, min 0)
  - `ServuxIntSetting permission_level_admin` (3,4,0)
  - `ServuxIntSetting permission_level_easy_place` (0,4,0)
  - `ServuxBoolSetting easy_place_validator_enabled` (默认 true)
  - `ServuxStringSetting default_language`（默认 `i18nManager.DEFAULT_LANG`，允许值 `List.of(DEFAULT_LANG)`，`setValue` 时校验语言键存在并 `LANG.setLang`）
  - `ServuxBoolSetting debug_log` (默认 false)
- **构造**: `super("servux_main", Identifier.fromNamespaceAndPath("servux","main"), protocolVersion=1, defaultPerm=0, permNode="servux.main", "The Servux Main configuration data provider")`
- **关键方法**:
  - `registerHandler()` (L78): `defaultLanguage.updateExamples(LANG.getLanguageKeys())`
  - `unregisterHandler()` (L87): no-op
  - `isPlayerRegistered(player)` (L93): 恒 true
  - `doReloadConfig(CommandSourceStack source)` (L98) → `DataProviderManager.readFromConfig()` + 发消息
  - `doSaveConfig(CommandSourceStack source)` (L104) → `writeToConfig()`
  - `hasDebugMode()` (L110) → `debugLog.getValue() || Reference.DEV_DEBUG`
  - `hasPermission(player)` (L116) → `Permissions.check(player, "servux.main.admin", adminPermissionLevel)`
  - `hasPermission_EasyPlace(player)` (L126) → `Permissions.check(player, "servux.main.easy_place", easyPlacePermissionLevel)`
  - `isEasyPlaceValidatorEnabled()` / `getDefaultLanguage()`
- **NMS 依赖**: `CommandSourceStack`, `Component`, `Identifier`
- **权限节点**: `servux.main`（base，构造 permNode）、`servux.main.admin`、`servux.main.easy_place`
- **迁移方式**: 适配。`Permissions.check` → `player.hasPermission(node)`（默认等级 0-4 映射：4=OP，0=全员，LuckPerms 配置）。`CommandSourceStack` → Bukkit `CommandSender`。`i18nManager`（另模块）保留。
- **歧义/风险点**: EasyPlace 的 validator / 权限在 Paper 上无对应服务端 Mixin 行为（见 CLAUDE.md §3 降级），permission_level_easy_place / easy_place_validator_enabled **存配置但无实际生效点**（除非另做 PacketEvents）。default_language 的 `setValue` 抛 `SimpleCommandExceptionType` → 改 Bukkit `IllegalArgumentException`。

### 18. `dataproviders/EntitiesDataProvider.java` (300 行)
- **职责**: `entity_data`（通道 `servux:entity_data`，协议 v1）；响应客户端的方块实体 NBT 查询与实体 NBT 查询；含 Allay 修复开关 + 玩家背包/末影箱权限。
- **关键 settings**:
  - `permission_level` (0,4,0)
  - `nbt_query_override` (默认 false)
  - `nbt_query_permission_level` (2,4,0)
  - `fix_allay_gathering` (默认 true)
  - `nbt_allow_player_inventory` (默认 true)
  - `nbt_allow_player_ender_items` (默认 true)
  - `player_inventory_permission_level` (2,4,0)
  - `player_ender_items_permission_level` (2,4,0)
- **关键字段**: `INSTANCE`, `HANDLER = ServuxEntitiesHandler.getInstance()`, `CompoundTag metadata`
- **构造**: `super("entity_data", CHANNEL_ID, PROTOCOL_VERSION=1, 0, "servux.provider.entity_data", ...)`
- **关键方法**:
  - `registerHandler()` (L73): `ServerPlayHandler.registerServerPlayHandler(HANDLER)` + `HANDLER.registerPlayPayload(Payload.ID, Payload.CODEC, BOTH_SERVER)` + `registerPlayReceiver(ID, HANDLER::receivePlayPayload)`
  - `unregisterHandler()` (L87)
  - `sendMetadata(player)` (L105): 权限检查 → `HANDLER.sendPlayPayload(player.connection, new Payload(MetadataResponse(metadata)))`（connection 为 null 时退回 sendPlayPayload(player,...)）
  - `onPacketFailure(player)` (L129) → `setPlayerInvalid`
  - `removePlayer(player)` (L134) → `removeInvalidPlayer`
  - `onBlockEntityRequest(player, BlockPos pos)` (L157): `be.saveWithFullMetadata(player.registryAccess())` → `SimpleBlockResponse(pos, nbt)`
  - `onEntityRequest(player, int entityId)` (L171): `player.level().getEntity(entityId)` → `NbtView.getWriter(registryAccess)` → `entity.saveWithoutId(view.getWriter())` → `view.readNbt()`；玩家实体时按权限移除 `Inventory`/`EnderItems`（put 空 ListTag）；`nbt.putString("id", id.toString())` → `SimpleEntityResponse(entityId, nbt)`
  - `hasNbtQueryOverride()` / `hasFixAllayGathering()` / `hasNbtQueryPermission(player)` (L238): 若 override → `Permissions.check(player, permNode+".nbt_query_override", nbtQueryPermissionLevel)`；否则 `player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`
  - `hasPlayerInventoryPermission(player)` (L258): `nbtAllowPlayerInventory && Permissions.check(..., permNode+".nbt_allow_player_inventory", playerInventoryPermissionLevel)`
  - `hasPlayerEnderItemsPermission(player)` (L273)
  - `hasPermission(player)` (L284) → `Permissions.check(player, permNode, permissionLevel)`
- **NMS 依赖**: `CompoundTag`, `ListTag`, `BlockPos`, `Identifier`, `ServerPlayer`, `Entity`, `EntityType`, `BlockEntity`, `NbtView`(util/nbt), `RegistryAccess`, `net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER`, `ServuxEntitiesHandler/Packet`(网络层)
- **Mixin/AW**: 无直接。`fix_allay_gathering` 由 `MixinMob*`（Allay 修复，CLAUDE.md §2 第三类）消费 → Paper 降级/省略。
- **权限节点**: `servux.provider.entity_data` + `.nbt_query_override` + `.nbt_allow_player_inventory` + `.nbt_allow_player_ender_items`
- **协议字节布局**: 该类不含 packet 编解码；payload 定义在 `ServuxEntitiesPacket`（另模块）。此处仅调 `SimpleBlockResponse(pos,nbt)` / `SimpleEntityResponse(id,nbt)` / `MetadataResponse(nbt)` 工厂。
- **迁移方式**: 适配。核心数据采集 `be.saveWithFullMetadata(registryAccess)` / `entity.saveWithoutId(...)` 是 NMS 调用，paperweight userdev 下可直接用；`player.permissions().hasPermission(COMMANDS_GAMEMASTER)` → 反射 `CraftHumanEntity.getHandle().hasPermissions(...)` 或简化为 OP 判断。
- **歧义/风险点**:
  1. `entity.saveWithoutId` + `NbtView`（util/nbt/，1.21.11 NbtRead/WriteView 抽象，依赖 Mixin `IMixinNbtReadView/WriteView`？需查 nbt 模块）——若 NbtView 依赖 Mixin → 需反射替换。
  2. `player.permissions()` 是 NMS `ServerPlayer` 的方法（`net.minecraft.server.level.ServerPlayer#permissions`），Paper NMS 可直访。
  3. 玩家 Inventory/EnderItems 移除用 `nbt.remove` + `nbt.put("Inventory", new ListTag())`（1.21.11 putXxx 返回 void，已正确）。

### 19. `dataproviders/TweaksDataProvider.java` (373 行)
- **职责**: `tweaks_data`（通道 `servux:tweaks_data`，协议 v1）；提供「可堆叠潜影盒」配置（meta 下发）+ 同 Entities 的方块/实体 NBT 查询（saveWithoutMetadata）。**会 tick**（`shouldTick=true`）。
- **关键 settings**:
  - `permission_level` (0,4,0, intCallback)
  - `update_interval` (120,1200,40, intCallback)
  - `stackable_shulkers` (默认 false, boolCallback)
  - `stackable_shulkers_count` (64,99,1, intCallback)
  - `stackable_shulkers_fix` (默认 true, boolCallback)
- **关键字段**: `INSTANCE`, `HANDLER`, `metadata`, `configDirty=false`
- **构造**: `super("tweaks_data", CHANNEL_ID, PROTOCOL_VERSION=1, ...)`; `setTickRate(40)`; `checkTweaksMetadata()`
- **关键方法**:
  - `registerHandler/unregisterHandler` (L80/L94): 同 Entities 模式（Payload.ID/CODEC/BOTH_SERVER）
  - `shouldTick()` (L101) → `isEnabled()`
  - `tick(server, tickCounter, profiler)` (L107): 每 `update_interval` tick 若 `configDirty` → `updateAllTweaks(server)` + 清 dirty
  - `checkTweaksMetadata()` (L137): `shouldEmptyShulkersStack()` 为真时 `metadata.putBoolean("stackingShulkers", true)` + `putInt("stackingShulkersMax", count)`；否则 remove
  - `updateAllTweaks(server)` (L160): 对所有 `isPlayerRegistered` 的 player `sendMetadata`
  - `sendMetadata(player)` (L176): 权限 → `MetadataResponse(metadata)`
  - `onBlockEntityRequest(player,pos)` (L229): `be.saveWithoutMetadata(player.registryAccess())`（**注意：与 Entities 的 saveWithFullMetadata 不同**）→ `SimpleBlockResponse`
  - `onEntityRequest(player,entityId)` (L243): 同 Entities，但复用 `EntitiesDataProvider.INSTANCE.hasPlayerInventoryPermission/EnderItems` 做玩家背包过滤
  - `shouldEmptyShulkersStack()` / `isStackableShulkersFixActive()` / `defaultEmptyShulkersMaxCount()` / `getEmptyShulkersMaxCount(ItemStack)` (L325): `stack.getComponents().getOrDefault(DataComponents.MAX_STACK_SIZE, 1)`
  - `hasPermission(player)` (L336)
  - 内部 `BoolCallbacks`/`IntCallbacks` (L354/L364): 值变化时 `TweaksDataProvider.INSTANCE.configDirty=true`
- **NMS 依赖**: `CompoundTag`, `ListTag`, `BlockPos`, `Identifier`, `MinecraftServer`, `ServerPlayer`, `ProfilerFiller`, `Entity`, `EntityType`, `ItemStack`, `DataComponents`, `BlockEntity`, `NbtView`, `InventoryUtils`(util), `ServuxTweaksHandler/Packet`
- **Mixin/AW**: `stackable_shulkers` 由 `MixinItemStack`/`MixinHopper`（CLAUDE.md §2 第三类）实现真正的堆叠行为。本 provider 仅提供配置 + 元数据下发。
- **权限节点**: `servux.provider.tweaks_data`
- **迁移方式**: 适配。`ItemStack.getComponents().getOrDefault(DataComponents.MAX_STACK_SIZE,1)` 在 paperweight 可直用。`Permissions.check`→`hasPermission`。**堆叠 Mixin 行为在 Paper 降级/省略**（CLAUDE.md §3）——本 provider 仍可正常下发元数据，但服务端不会真把潜影盒堆叠。
- **歧义/风险点**:
  1. `onBlockEntityRequest` 用 `saveWithoutMetadata`（不含位置/id 元数据），与 Entities 的 `saveWithFullMetadata` 区别必须保留。
  2. `configDirty` 的 tick 刷新依赖 DataProviderManager.tickProviders 正常调度。
  3. `DataComponents.MAX_STACK_SIZE` 是 NMS 静态字段，迁移时确认 1.21.11 名。

### 20. `dataproviders/StructureDataProvider.java` (557 行) ⭐ 最复杂
- **职责**: `structure_bounding_boxes`（通道 `servux:structure_bounding_boxes`，协议 v2）；追踪玩家维度/区块变化，扫描区块的 `StructureStart` 引用，序列化结构边界框下发给 MiniHUD。**会 tick**。
- **关键 settings**:
  - `permission_level` (0,4,0)
  - `structures_blacklist_enabled` (默认 false)
  - `structures_whitelist_enabled` (默认 false)
  - `structures_blacklist` (默认 `["minecraft:buried_treasure"]`)
  - `structures_whitelist` (默认 `[]`)
  - `update_interval` (40,1200,1)
  - `timeout` (600,1200,40)
- **关键字段**:
  - `INSTANCE`, `HANDLER`, `metadata`
  - `Map<UUID, PlayerDimensionPosition> registeredPlayers`
  - `Map<UUID, Map<ChunkPos, Timeout>> timeouts`
  - `int retainDistance`
- **构造**: `super("structure_bounding_boxes", CHANNEL_ID, PROTOCOL_VERSION=2, ...)`; metadata 额外 `putInt("timeout", timeout.getValue())`; `setTickRate(40)`
- **关键方法**:
  - `registerHandler/unregisterHandler` (L81/L95): 同模式（Structures Packet）
  - `isPlayerRegistered(player)` (L108) → `registeredPlayers.containsKey(uuid)`
  - `shouldTick()` (L114) → `enabled`
  - `tick(server,tickCounter,profiler)` (L120): 每 `update_interval`：`retainDistance = server.getPlayerList().getViewDistance()+2`；遍历玩家，已注册且无权限→unregister，否则 `checkForDimensionChange` + `refreshTrackedChunks`；最后 `checkForInvalidPlayers`
  - `register(player)` (L188): 权限 → `registeredPlayers.put(uuid, new PlayerDimensionPosition(player))` → 发 Metadata + `initialSyncStructuresToPlayerWithinRange(player, viewDistance+2, tickCounter)`
  - `unregister(player)` (L226): `HANDLER.resetFailures(channel, player)` + `registeredPlayers.remove(uuid)`
  - `onStartedWatchingChunk(player, chunk)` (L178): `addChunkTimeoutIfHasReferences(uuid, chunk, tickCount)` ← **由 Mixin `MixinServerChunkLoadingManager` 在玩家开始观察区块时触发**
  - `initialSyncStructuresToPlayerWithinRange(player, radius, tick)` (L234): center = `player.getLastSectionPos().chunk()` → `getStructureReferencesWithinRange(level, center, radius)` → `sendStructures`
  - `addChunkTimeoutIfHasReferences(uuid, chunk, tick)` (L247): 若 `chunkHasStructureReferences` → 设 `Timeout(tick - timeout)`（预过期，下次 tick 即发送）
  - `checkForDimensionChange(player)` (L261): `playerPos.dimensionChanged(player)` → 清 timeouts + 重设 position
  - `getStructureReferencesFromChunk(cx,cz,world,references)` (L358): `world.getChunk(cx,cz, ChunkStatus.STRUCTURE_REFERENCES, false)` → 遍历 `chunk.getAllReferences()` 合并 LongSet ← **`getAllReferences()` 是 Mixin Accessor 暴露的私有方法**
  - `chunkHasStructureReferences` (L388): 同上但只判非空
  - `getStructureStartsFromReferences(world, references)` (L413): 对每个 reference chunk `chunk.getStartForStructure(structure)` ← NMS `ChunkAccess#getStartForStructure`
  - `getStructureReferencesWithinRange(world, center, radius)` (L452): 双层循环扫描
  - `sendStructures(player, references, tick)` (L468): `getStructureStartsFromReferences` → `addOrRefreshTimeouts` → `getStructureList` → `encodeStructuresPacket(player, new ServuxStructuresPacket(PACKET_S2C_STRUCTURE_DATA_START, nbt))`（nbt 含 `Structures` ListTag）
  - `getStructureList(structures, world)` (L491): `StructurePieceSerializationContext.fromLevel(world)` → 对每个 `StructureStart`: `structure = start.getStructure()`；`structureType = BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type())`；`expandBox = structure.terrainAdaptation() != TerrainAdjustment.NONE`；`shouldSendStructure(structureType)` 过滤；`nbt = start.createTag(ctx, pos)` + `nbt.putBoolean("ExpandBox", expandBox)` + list.add
  - `shouldSendStructure(Identifier)` (L524): whitelist 优先 → 黑名单 → 默认 true
- **NMS 依赖**: `ChunkPos`, `Level`, `ServerLevel`, `ServerPlayer`, `ChunkAccess`, `LevelChunk`, `ChunkStatus`(`STRUCTURE_REFERENCES`), `Structure`, `StructureStart`, `TerrainAdjustment`, `StructurePieceSerializationContext`, `BuiltInRegistries.STRUCTURE_TYPE`, `ServerGamePacketListenerImpl`, `ListTag`, `CompoundTag`, fastutil `LongSet/LongOpenHashSet/LongIterator`, `ServuxStructuresHandler/Packet`, `PlayerDimensionPosition`/`Timeout`(util)
- **Mixin/AW**: ⭐ **关键**：
  1. `ChunkAccess.getAllReferences()` — Servux 用 AccessWidener/Mixin 暴露；Paper 需 **NMS 直接调用**（paperweight userdev 下 `ChunkAccess.getAllReferences()` 在 Mojang 映射里是否 public？1.21.11 实际为 public-ish 的内部方法 → 直接调用或反射）
  2. `onStartedWatchingChunk` 由 `MixinServerChunkLoadingManager` 触发 → Paper 改 Bukkit `PlayerChunkLoadEvent`/`ChunkLoadEvent`（或 `PlayerChunkEvent`）替代
  3. `player.getLastSectionPos()` — NMS 方法，Paper 直用
- **权限节点**: `servux.provider.structure_bounding_boxes`
- **协议**: payload 在 `ServuxStructuresPacket`（Type 枚举 `PACKET_S2C_METADATA` / `PACKET_S2C_STRUCTURE_DATA_START`）；本 provider 调 `encodeStructuresPacket(player, packet)`（经 PacketSplitter 分片，大包）。`metadata` 含 `name/id/version/servux/timeout`。
- **迁移方式**: 适配（核心算法照抄）。`getAllReferences()`/`getStartForStructure()` → paperweight userdev 直访（Mojang 映射）；若不可见用 reflect 模块反射。`onStartedWatchingChunk` → Bukkit Chunk 事件。
- **歧义/风险点** ⭐:
  1. **`ChunkAccess.getAllReferences()`** 是本模块最大的 NMS 命门 —— 必须确认 1.21.11 该方法可见性；若私有 → 反射（reflect 模块）。
  2. **`onStartedWatchingChunk` 触发**：Bukkit 的 `PlayerChunkLoadEvent`（Paper）比 Fabric 的 watching-chunk 钩子语义略不同（时机/维度），可能需结合 `player.getLocation()` 过滤。
  3. `StructureStart.createTag(ctx, pos)` 1.21.11 签名（带 ctx+pos）需核对（CLAUDE.md §4 明确点名此签名易漂移）。
  4. `start.getStructure()` 在 C2ME（多线程区块 mod）下可能返回 null（源码 L500 已注释防 null）—— Paper 单线程区块无此问题但仍保留 null 检查。
  5. `retainDistance = viewDistance+2` 决定超时区块清理范围，影响内存与刷新。

### 21. `dataproviders/LitematicsDataProvider.java` (424 行) ⭐
- **职责**: `litematic_data`（通道 `servux:litematic_data`，协议 v1）；响应 Litematica 的方块/实体 NBT 查询 + 批量区块实体/实体查询（粘贴前抓取）+ 接收客户端投影粘贴请求（`pasteTo`）。管理 `SchematicBufferManager`（分片缓冲）+ 传输目录。
- **关键 settings**:
  - `permission_level` (0,4,0)
  - `permission_level_paste` (0,4,0)
  - `fix_rail_rotations` (默认 true, public 字段 `fixRaiLRotations`——注意源码拼写 RaiL)
  - `fix_stairs_mirror` (默认 true, public)
  - `fix_chest_mirror` (默认 true, public)
- **关键字段**:
  - `INSTANCE`, `HANDLER`, `metadata`
  - `SchematicBufferManager bufferManager`
  - `Path transmitDir`（= `Reference.DEFAULT_RUN_DIR.resolve("schematics").normalize()`）
- **构造**: `super("litematic_data", CHANNEL_ID, PROTOCOL_VERSION=1, ...)`; `transmitDir = getTransmitDir()`
- **关键方法**:
  - `registerHandler/unregisterHandler` (L81/L95): 同模式
  - `getBufferManager()` (L107) / `getTransmitDir()` (L112): 传输目录创建（删除同名文件后 mkdir，不可写则 warn）
  - `sendMetadata(player)` (L151): 同模式
  - `onBlockEntityRequest(player,pos)` (L203): `be.saveWithFullMetadata(registryAccess)` → `SimpleBlockResponse`
  - `onEntityRequest(player,entityId)` (L217): 同 Entities（**无** Inventory/EnderItems 过滤）
  - `onBulkEntityRequest(player, ChunkPos, CompoundTag req)` (L243): 权限 → `world.getChunkSource().getChunkNow(x,z)` → 读取 `req.getIntOr("minY",-64)`/`getIntOr("maxY",319)` → `PositionUtils.createEnclosingAABB(pos1,pos2)` → `chunk.getBlockEntitiesPos()` + `world.getEntities(null, bb, EntityUtils.NOT_PLAYER)` → 每个方块实体 `saveWithFullMetadata` 入 `TileEntities` ListTag；每个实体 `saveWithoutId` + `NbtUtils.writeEntityPositionToTag(posVec, entTag)` + `putInt("entityId", id)` 入 `Entities` ListTag → `output{Task:"BulkEntityReply", TileEntities, Entities, chunkX, chunkZ}` → `ResponseS2CStart(output)`（大包，经分片）
  - `handleClientPasteRequest(player, transactionId, tags)` (L335): 权限 + `.paste` 权限 + `player.isCreative()` → `tags.getStringOr("Task","").equals("LitematicaPaste")` → `SchematicPlacement.createFromNbt(tags)` + `ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", NONE.name()))` + `PasteLayerBehavior.fromStringStatic(tags.getStringOr("PasteLayerBehavior", ALL.name()))` + `LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null)` → `placement.pasteTo(player.level(), replaceMode, layerBehavior, layerRange)`
  - `handleClientPasteRequestPair(player, transactionId, Pair<LitematicaSchematic, CompoundTag>)` (L368): 同上但从已分片重组的 `LitematicaSchematic` 创建 placement（`createFromNbt(schematic, tags)`）
  - `hasPermission(player)` (L403) / `hasPermissionsForPaste(player)` (L408): `hasPermission && Permissions.check(player, permNode+".paste", pastePermissionLevel)`
- **NMS 依赖**: `CompoundTag`, `ListTag`, `BlockPos`, `Identifier`, `ServerLevel`, `ServerPlayer`, `Entity`, `EntityType`, `LevelChunk`, `ChunkPos`, `Vec3`, `net.minecraft.world.phys.AABB`, `BlockEntity`, `RegistryAccess`, `NbtView`, `NbtUtils`, `PositionUtils`/`EntityUtils`/`StringUtils`(util), `SchematicPlacement`/`LitematicaSchematic`/`SchematicBufferManager`(schematic), `ServuxLitematicaHandler/Packet`, `ReplaceBehavior`/`PasteLayerBehavior`/`LayerRange`(schematic/util)
- **Mixin/AW**: `fixRaiLRotations`/`fixStairMirror`/`fixChestMirror` 由 `MixinChest`/`MixinRail`/`MixinStairs`（CLAUDE.md §3 镜像修复）消费 → Paper 在 `schematic/placement` 的 `SchematicPlacingUtils.pasteTo` 里**内联等价修正**，不走 Mixin。
- **权限节点**: `servux.provider.litematic_data` + `.paste`
- **协议**: payload 在 `ServuxLitematicaPacket`（工厂 `MetadataResponse`/`SimpleBlockResponse`/`SimpleEntityResponse`/`ResponseS2CStart`）。`onBulkEntityRequest` 的 ResponseS2CStart 是大包（一整个区块的所有方块实体+实体 NBT），**必须走 PacketSplitter**（S2C 分片≤32000）。
- **迁移方式**: 适配。数据采集 NMS 直用（paperweight）。`SchematicPlacement.pasteTo` 是 schematic 模块核心（另模块）。`tags.read("RenderLayerRange", LayerRange.CODEC)` 是 1.21.11 CompoundTag 的 codec 读取 API（`CompoundTag#read(String, Codec)`）—— 需核对 1.21.11 存在。
- **歧义/风险点** ⭐:
  1. **`onBulkEntityRequest` 大包**：一区块所有 TE+实体 NBT 极易超 32KiB → PacketSplitter S2C 分片常量必须改 ≤32000（CLAUDE.md §1 命门）。
  2. **`pasteTo` 镜像修复**：3 个 fix 开关在 Mixin 里实现，Paper 必须在粘贴代码里手写等价的铁轨旋转/楼梯/箱子 180° 镜像修正，否则客户端投影粘贴后朝向错误。
  3. **`Reference.DEFAULT_RUN_DIR`** → 插件运行目录；`transmitDir` 用于暂存接收中的分片投影文件。
  4. `player.isCreative()` 限制粘贴——Paper `Player.getGameMode()==CREATIVE`。
  5. `tags.read(key, codec)` 在 1.21.11 是否可用需核对（替代：手动 codec `parse`）。

---

## 四、Provider 统一模式（5 个共通点）

1. **单例 + HANDLER**: 每个 provider `public static final XXX INSTANCE = new XXX()`；`private final static ServuxXxxHandler<Payload> HANDLER = ServuxXxxHandler.getInstance()`。
2. **metadata 握手 CompoundTag**: 构造时填 `name/id/version/servux`（servux=Reference.MOD_STRING）；玩家 join 或被请求时下发 `MetadataResponse(metadata)`。
3. **registerHandler/unregisterHandler 模板**:
   ```
   ServerPlayHandler.getInstance().registerServerPlayHandler(HANDLER);
   if (!isRegistered()) {
       HANDLER.registerPlayPayload(Payload.ID, Payload.CODEC, IPluginServerPlayHandler.BOTH_SERVER);
       setRegistered(true);
   }
   HANDLER.registerPlayReceiver(Payload.ID, HANDLER::receivePlayPayload);
   ```
   → Paper 迁移：Payload.ID/CODEC 是 NMS CustomPacketPayload；`registerPlayPayload` → `Messenger.registerOutgoingPluginChannel`；`registerPlayReceiver` → `Messenger.registerIncomingPluginChannel`（见 CLAUDE.md §1）。
4. **invalidPlayers 机制**: `onPacketFailure` → `setPlayerInvalid(uuid)`；`isPlayerRegistered = !isPlayerInvalid`；join 时 `removeInvalidPlayer`。用于网络失败后停止向该玩家发包。→ Paper 照抄。
5. **权限**: `me.lucko.fabric.api.permissions.v0.Permissions.check(player, node, level)` → `player.hasPermission(node)`（level 0=全员, 4=OP；LuckPerms 可配）。`player.permissions().hasPermission(COMMANDS_GAMEMASTER)`（仅 Entities）→ 反射 NMS 或简化为 `player.isOp()`。

---

## 五、settings 清单汇总（落盘 servux.json 的 key）

| Provider | setting key | 类型 | 默认 | min | max |
|---|---|---|---|---|---|
| servux_main | permission_level | int | 0 | 0 | 4 |
| servux_main | permission_level_admin | int | 3 | 0 | 4 |
| servux_main | permission_level_easy_place | int | 0 | 0 | 4 |
| servux_main | easy_place_validator_enabled | bool | true | | |
| servux_main | default_language | string | DEFAULT_LANG | | |
| servux_main | debug_log | bool | false | | |
| entity_data | permission_level | int | 0 | 0 | 4 |
| entity_data | nbt_query_override | bool | false | | |
| entity_data | nbt_query_permission_level | int | 2 | 0 | 4 |
| entity_data | fix_allay_gathering | bool | true | | |
| entity_data | nbt_allow_player_inventory | bool | true | | |
| entity_data | nbt_allow_player_ender_items | bool | true | | |
| entity_data | player_inventory_permission_level | int | 2 | 0 | 4 |
| entity_data | player_ender_items_permission_level | int | 2 | 0 | 4 |
| tweaks_data | permission_level | int | 0 | 0 | 4 |
| tweaks_data | update_interval | int | 120 | 40 | 1200 |
| tweaks_data | stackable_shulkers | bool | false | | |
| tweaks_data | stackable_shulkers_count | int | 64 | 1 | 99 |
| tweaks_data | stackable_shulkers_fix | bool | true | | |
| structure_bounding_boxes | permission_level | int | 0 | 0 | 4 |
| structure_bounding_boxes | structures_blacklist_enabled | bool | false | | |
| structure_bounding_boxes | structures_whitelist_enabled | bool | false | | |
| structure_bounding_boxes | structures_blacklist | string[] | ["minecraft:buried_treasure"] | | |
| structure_bounding_boxes | structures_whitelist | string[] | [] | | |
| structure_bounding_boxes | update_interval | int | 40 | 1 | 1200 |
| structure_bounding_boxes | timeout | int | 600 | 40 | 1200 |
| litematic_data | permission_level | int | 0 | 0 | 4 |
| litematic_data | permission_level_paste | int | 0 | 0 | 4 |
| litematic_data | fix_rail_rotations | bool | true | | |
| litematic_data | fix_stairs_mirror | bool | true | | |
| litematic_data | fix_chest_mirror | bool | true | | |

> HUD（hud_data）的 settings 不在本模块（另见 hud 模块），但 `PlayerListener`/`ServerListener` 会调它的 `sendMetadata/removePlayer/checkWorldSeed`，故 6 provider 全部参与生命周期。

## 六、权限节点汇总
- `servux.main`（ServuxConfig base permNode）
- `servux.main.admin`、`servux.main.easy_place`
- `servux.provider.entity_data` + `.nbt_query_override` + `.nbt_allow_player_inventory` + `.nbt_allow_player_ender_items`
- `servux.provider.tweaks_data`
- `servux.provider.structure_bounding_boxes`
- `servux.provider.litematic_data` + `.paste`
- HUD: `servux.provider.hud_data` + `.weather` + `.seed` + `.logger` + `.logger.<type>`（另模块）

---

## 七、最关键的 3 个实现风险点（本模块）

1. **`StructureDataProvider` 的 `ChunkAccess.getAllReferences()` + `onStartedWatchingChunk`**：前者是 Mixin/AW 暴露的私有方法，Paper 需 paperweight 直访或 reflect 反射；后者由 `MixinServerChunkLoadingManager` 触发，Bukkit 无完全等价事件（`PlayerChunkLoadEvent` 语义不同），可能影响结构边界框的实时性。
2. **`LitematicsDataProvider` 的两类大包**：`onBulkEntityRequest`（整区块 TE+实体 NBT）与 `handleClientPasteRequest`（接收投影）极易超 Bukkit 32KiB 上限 → PacketSplitter S2C 分片常量必须从 1MiB 改 ≤32000；且 3 个镜像修复 Mixin（Rail/Stairs/Chest）必须在 `SchematicPlacement.pasteTo` 里内联等价逻辑，否则粘贴朝向错误。
3. **生命周期分发的 Bukkit 事件时机**：Fabric 的 `ServerHandler.onServerStarting`（readFromConfig）vs `onServerStarted`（writeToConfig + `onCaptureImmutable(registryAccess)`）是两个精确时机；Paper 的 `ServerLoadEvent` 只在启动完成后触发一次，且 `RegistryAccess.Frozen` 必须在 server 完全启动后捕获（否则 Recipe/NbtView 拿到空注册表）—— 迁移时须严格分两步并保证 Provider 注册在 readFromConfig 之前。另外异步任务框架（IThreadDaemon*）在 Paper 主线程外调 Bukkit API 不安全，需切回主线程。
