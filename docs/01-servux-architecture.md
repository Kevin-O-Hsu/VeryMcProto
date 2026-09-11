# 01 · Servux 原版架构总览

> 原版根目录：`OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/`
> 相关：网络协议见 [02](02-network-protocol.md)；各 Provider 数据内容见 [03](03-dataproviders-detail.md)；Mixin 触发点见 [04](04-mixin-analysis.md)；架构对照与降级矩阵见 [07](07-migration-architecture.md)。

---

## 1. 顶层结构（包与职责）

```
fi.dy.masa.servux/
├── Servux.java                 ← ModInitializer 入口（onInitialize）
├── Reference.java              ← 常量（MOD_ID/MC_VERSION/配置目录/DEV_DEBUG）
├── servux/                     ← 真正的业务监听实现（ServerListener / PlayerListener / ServuxInitHandler）
├── event/                      ← 事件分发器（ServerHandler / PlayerHandler / ServerInitHandler）+ 接口
├── interfaces/                 ← 抽象接口（IServerListener / IPlayerListener / IServerInitHandler / 线程任务等）
├── dataproviders/              ← ★ 核心：6 个 Provider + DataProviderManager + 接口/基类
├── network/                    ← ★ 网络层（见 02）：ServerPlayHandler / PacketSplitter / IPluginServerPlayHandler / packet/*
├── loggers/                    ← HUD 子系统：TPS / MobCap 数据采集
├── schematic/                  ← Litematica 投影系统（见 05）
├── settings/                   ← 配置项系统（AbstractServuxSetting + 各类型）
├── commands/                   ← /servux 命令（CommandProvider + ServuxCommand）
├── util/                       ← 工具（NbtUtils/NbtView/JsonUtils/PositionUtils/i18n/log/...）
└── mixin/                      ← 26 个 Mixin（见 04）
```

**核心三层**：
1. **`dataproviders/`** —— 业务层：每条协议的数据采集与回应
2. **`network/`** —— 协议层：通道注册、Payload 编解码、分包（[02](02-network-protocol.md)）
3. **`event/` + `servux/` + `mixin/`** —— 生命周期层：用 Mixin 钩住 NMS 生命周期，触发 provider 的注册/收发/卸载

---

## 2. 启动与生命周期流程

> 入口：`Servux.onInitialize()`（`Servux.java:17`）

```
JVM 加载 Mod
  └─ Servux.onInitialize()
       ├─ ServerInitHandler.getInstance().registerServerInitHandler(new ServuxInitHandler())   // 登记"服务端初始化"回调
       └─ CommandProvider.getInstance().registerCommand(new ServuxCommand())                   // 登记 /servux 命令

（稍后）MixinMinecraftDedicatedServer.<init> @TAIL                                          // mixin/server/MixinMinecraftDedicatedServer.java
  └─ ServerInitHandler.onServerInit()
       └─ ServuxInitHandler.onServerInit()                                                    // servux/ServuxInitHandler.java:11
            ├─ DataProviderManager.registerDataProvider(ServuxConfigProvider)   ─┐
            ├─ DataProviderManager.registerDataProvider(StructureDataProvider)    │ 注册 6 个 provider
            ├─ DataProviderManager.registerDataProvider(HudDataProvider)          │ （含 servux_main 配置主 provider）
            ├─ DataProviderManager.registerDataProvider(LitematicsDataProvider)   │
            ├─ DataProviderManager.registerDataProvider(EntitiesDataProvider)     │
            ├─ DataProviderManager.registerDataProvider(TweaksDataProvider)      ─┘
            ├─ ServerHandler.registerServerHandler(new ServerListener())         // 服务端生命周期
            └─ PlayerHandler.registerPlayerHandler(new PlayerListener())         // 玩家生命周期

（命令注册）MixinCommandManager.<init> @INVOKE(AFTER WhitelistCommand.register)               // mixin/server/MixinCommandManager.java
  └─ CommandProvider.registerCommands(dispatcher) → 注册 /servux 命令树

（服务端运行）MixinMinecraftServer 钩多个点                                                   // mixin/server/MixinMinecraftServer.java
  ├─ runServer @INVOKE(initServer)  → ServerHandler.onServerStarting()
  │     └─ ServerListener.onServerStarting → DataProviderManager.readFromConfig()             // 读 servux.json，按开关启用 provider
  ├─ runServer @INVOKE(buildServerStatus) → ServerHandler.onServerStarted()
  │     └─ ServerListener.onServerStarted → writeToConfig() + onCaptureImmutable(registryAccess) + Hud.checkWorldSeed()
  ├─ tickServer @RETURN(ordinal=1) → DataProviderManager.tickProviders(server, tick, profiler)// 每 tick 调度 provider
  ├─ reloadResources @HEAD/TAIL   → onServerResourceReloadPre/Post                            // /reload 触发重读配置
  └─ stopServer @HEAD/TAIL        → onServerStopping(onTickEndPre + writeToConfig) / onServerStopped(onTickEndPost)

（早期）MixinMain.main @INVOKE(AFTER LevelStorageAccess.saveDataTag)                           // mixin/server/MixinMain.java
  └─ DataProviderManager.onCaptureImmutable(immutable)  // @Local 捕获 RegistryAccess.Frozen
```

> **移植要点**：上述所有"由 Mixin 触发"的节点，在 Paper 上改用 **Bukkit 事件 + 调度器**等价触发，详见 [07](07-migration-architecture.md) §7.2 生命周期。

---

## 3. `DataProviderManager` —— Provider 注册表/调度器/配置中枢

> 原版：`dataproviders/DataProviderManager.java`（305 行）。单例 `INSTANCE`。

### 3.1 核心数据结构

```java
// DataProviderManager.java:31-33
protected final HashMap<String, IDataProvider> providers = new HashMap<>();      // 名字 → provider
protected ImmutableList<IDataProvider> providersImmutable = ImmutableList.of();  // 快照（遍历用）
protected ArrayList<IDataProvider> providersTicking = new ArrayList<>();         // 需要 tick 的子集
protected RegistryAccess.Frozen immutable = RegistryAccess.EMPTY;                // 早期捕获的注册表
```

### 3.2 关键方法

| 方法 | 作用 | 行号 |
|---|---|---|
| `registerDataProvider(provider)` | 按名（lowercase）登记，去重 | :47 |
| `setProviderEnabled(name/provider, bool)` | 启用/禁用：调 `provider.setEnabled` + `updatePacketHandlerRegistration` + 维护 `providersTicking` | :67/:73 |
| `updatePacketHandlerRegistration(provider)` | 启用→`registerHandler()`（注册通道）；禁用→`unregisterHandler()` | :124 |
| `tickProviders(server, tick, profiler)` | 遍历 ticking，按 `tick % provider.getTickInterval() == 0` 调 `provider.tick()` | :102 |
| `readFromConfig()` | 读 `servux.json`：先读各 provider 的 settings，再读 `DataProviderToggles` 决定启停 | :204 |
| `writeToConfig()` | 写 `servux.json`：`DataProviderToggles` + 各 provider 的 settings JSON | :260 |
| `onCaptureImmutable(registry)` | 缓存 `RegistryAccess.Frozen`（给 Litematic palette 解析方块用） | :136 |
| `getSettingByName(name)` | 跨 provider 查 setting（支持 `provider:setting` 格式，给 /servux set 用） | :167 |

### 3.3 配置文件 `servux.json` 结构

```jsonc
{
  "DataProviderToggles": {            // 各 provider 启停开关
    "servux_main": true,              // ← servux_main 永远被强制 true（:243）
    "hud_data": true,
    "entity_data": false,
    "tweaks_data": false,
    "structure_bounding_boxes": true,
    "litematic_data": true
  },
  "servux_main":    { "permission_level": 0, "permission_level_admin": 3, "easy_place_permission_level": 0, ... },
  "hud_data":       { "permission_level": 0, "update_interval": 40, "share_seed": false, "loggers_enabled": false, ... },
  "structure_bounding_boxes": { "update_interval": 100, "structure_whitelist_enabled": false, ... },
  "litematic_data": { "permission_level": 0, "paste_permission_level": 2, ... },
  "entity_data":    { ... },
  "tweaks_data":    { ... }
}
```

> 配置路径 = `Reference.DEFAULT_CONFIG_DIR`（Fabric 的 `config/`）下 `servux.json`。**移植**：改到 `plugins/VeryMcProto/servux.json`（用 `JavaPlugin.getDataFolder()`）。

---

## 4. `IDataProvider` / `DataProviderBase` —— Provider 契约

> 原版：`dataproviders/IDataProvider.java`（143 行，接口）/ `DataProviderBase.java`（121 行，抽象基类）

### 4.1 `IDataProvider` 接口（核心方法）

```java
String getName();                 // 逻辑名（config key，如 "hud_data"）—— 注意 ≠ 网络通道名
String getDescription();
Identifier getNetworkChannel();   // ★ 网络通道（如 servux:hud_metadata）
int getProtocolVersion();         // 协议版本（客户端协商）
boolean isEnabled(); void setEnabled(boolean);
boolean isRegistered(); void setRegistered(boolean);

void registerHandler();           // 启用：注册通道 + payload + receiver
void unregisterHandler();         // 禁用：反注册

default boolean shouldTick();     // 是否需要周期 tick
int getTickInterval();            // tick 间隔（默认 40）
default void tick(MinecraftServer, int tickCounter, ProfilerFiller);

default IPluginServerPlayHandler<?> getPacketHandler();

boolean isPlayerRegistered(ServerPlayer);   // 玩家是否已"注册"该 provider（订阅）
boolean hasPermission(ServerPlayer);        // 权限检查

void onTickEndPre(); void onTickEndPost();  // 服务端停服前后钩子
JsonObject toJson(); void fromJson(JsonObject);  // settings 序列化
List<IServuxSetting<?>> getSettings();
```

### 4.2 `DataProviderBase` 抽象基类

```java
// DataProviderBase.java:11-29 —— 构造时固化元信息
protected DataProviderBase(String name, Identifier channel, int protocolVersion,
                           int defaultPerm /*0-4*/, String permNode, String description)
```
提供：`enabled` / `playRegistered` / `tickRate`(默认40) 字段；`toJson/fromJson` 通用实现（遍历 `getSettings()`）；`getSettings()` 默认空列表（子类覆盖）。

> **移植要点**：这套接口/基类是**纯抽象，几乎可照抄**（仅把 NMS 类型如 `MinecraftServer`/`ServerPlayer`/`ProfilerFiller` 换成 Paper NMS 等价，或保留 NMS 类型——因为用 paperweight userdev 可直接引用）。`hasPermission` 把 `Permissions.check` 换成 Bukkit 权限。

---

## 5. 生命周期事件分发（Fabric Mixin 驱动）

Servux 用一套**自定义事件总线**（`event/*Handler` 单例 + `interfaces/*` 回调），由 Mixin 在 NMS 生命周期点触发：

| 分发器（单例） | 接口 | 触发的 Mixin | 触发时机 |
|---|---|---|---|
| `ServerInitHandler` | `IServerInitHandler` | `MixinMinecraftDedicatedServer.<init>@TAIL` | 专用服初始化（注册 provider） |
| `ServerHandler` | `IServerListener` | `MixinMinecraftServer.runServer/tickServer/reloadResources/stopServer` | 服务端 starting/started/reload/stopping/stopped |
| `PlayerHandler` | `IPlayerListener` | `MixinPlayerManager.canPlayerLogin/placeNewPlayer/respawn/op/deop/remove` | 玩家 connect/join/respawn/op/deop/leave |

`ServerListener`（`servux/ServerListener.java`）实现的回调链：
- `onServerStarting` → `readFromConfig()`
- `onServerStarted` → `writeToConfig()` + `onCaptureImmutable(server.registryAccess())` + `Hud.checkWorldSeed()`
- `onServerResourceReloadPre` → `readFromConfig()`
- `onServerResourceReloadPost` → `writeToConfig()` + `onCaptureImmutable()` + `ConfigProvider.registerHandler()`
- `onServerStopping` → `onTickEndPre()` + `writeToConfig()`
- `onServerStopped` → `onTickEndPost()`

`PlayerListener`（`servux/PlayerListener.java`）—— **玩家进服/退服是各 provider 握手的总入口**：
- `onPlayerJoin`：对每个 `enabled` 的 provider 调 `sendMetadata(player)`（HUD/Entities/Litematics/Tweaks）或 `register(player)`（Structure）—— 即向客户端下发该通道的"上线握手"。
- `onPlayerLeave`：调 `removePlayer` / `unregister` 清理订阅与失败计数。

> **移植要点**：`PlayerListener.onPlayerJoin/onPlayerLeave` 在 Paper 上 = **`PlayerJoinEvent` / `PlayerQuitEvent`**；`ServerListener` 的各回调 = **`ServerLoadEvent` / `PluginDisableEvent` / Paper 的 reload 钩子**。详见 [07](07-migration-architecture.md) §生命周期迁移。

---

## 6. 配置项系统（`settings/`）

> 原版：`settings/AbstractServuxSetting.java`（144 行）+ `IServuxSetting` + 各类型 + `IServuxSettingCallback`

每个 Provider 持有一组 `IServuxSetting<?>`（在构造时 `List.of(...)`），由 `DataProviderManager` 的 `toJson/fromJson` 统一持久化。

| Setting 类 | 类型 | 备注 |
|---|---|---|
| `ServuxBoolSetting` | `boolean` | 如 `share_seed` / `loggers_enabled` |
| `ServuxIntSetting` | `int`（含 min/max 校验） | 如 `permission_level` / `update_interval` |
| `ServuxStringSetting` | `String`（可带枚举示例） | 如 `default_language` |
| `ServuxStringListSetting` | `List<String>` | 如 `loggers_enable_list` / `structure_whitelist` |
| `ServuxListSetting` | 通用 list | — |

机制：
- 构造：`new ServuxXxxSetting(this, "name", default, [min,max] | [examples], [callback])`
- 变更回调：`IServuxSettingCallback<T>.onValueChanged(setting, old, new)`（如 HUD 的 `BoolCallback` 在 `loggers_enabled` 变更时重新初始化 logger）
- `setValueNoCallback`：不触发回调地设值（内部逻辑用）
- 命令行 `/servux set <name> <value>` 经 `DataProviderManager.getSettingByName` 定位并设值

> **移植要点**：这套 setting 系统是**纯 Java**，可照抄；只需把 `CommandSourceStack`（NMS 命令源）换成本地命令实现。

---

## 7. 命令系统（`commands/`）

> 原版：`commands/ServuxCommand.java`（318 行）+ `CommandProvider` + `ICommandProvider`。

- 注册时机：`MixinCommandManager.<init>` 在原版命令注册（`WhitelistCommand.register` 之后）注入，把 `/servux` 树挂到 dispatcher。
- 主要子命令：
  - `/servux reload` → `ServuxConfigProvider.doReloadConfig`（重读 `servux.json`）
  - `/servux save` → `doSaveConfig`（写 `servux.json`）
  - `/servux set <provider:setting> <value>` → 改 setting
  - `/servux info <setting>` / `/servux list [provider]` / `/servux search <query>` → 查看
  - 启停 provider 经 `DataProviderManager.setProviderEnabled`

> **移植要点**：Paper 用 `plugin.yml` 注册命令别名 + `CommandExecutor`/`TabCompleter`，或 Paper 的 Brigadier（`LifecycleEvent`/`PaperCommandManager`）。命令权限对接 Bukkit 权限。详见 [07](07-migration-architecture.md) §7.6 命令。
>
> **我方对齐状态（26.1 线）**：权限树已对齐上游 `:41-90`——根节点 `servux.commands`（上游 requires level 4 的 Bukkit 近似，default: op）+ 每子命令独立节点 `servux.commands.<sub>`（search 复用 `.list`；旧单节点 `servux.command` 经 plugin.yml children 映射自动继承）；`list` 列全部 settings 现值（含上游"值 <10 字符才显示"怪癖）、`list <provider>` 过滤；`set` 纯内存 + 显式 `/servux save` 落盘（enable/disable/debug/litematic 为我方扩展子命令，保留切换即时落盘）。

---

## 8. 全局配置主 Provider：`ServuxConfigProvider`（`servux_main`）

> 原版：`dataproviders/ServuxConfigProvider.java`（157 行）。**唯一一个 `getName()="servux_main"` 且永不被禁用**的 provider。

它不对应独立网络通道（`getNetworkChannel()` 返回 `servux:main`，但 `registerHandler` 是 NO-OP），而是承载**全局配置**：
- `permission_level` / `permission_level_admin`（基线权限）
- `permission_level_easy_place` + `easy_place_validator_enabled`（EasyPlace 开关）
- `default_language`（i18n，联动 `i18nManager`）
- `debug_log`（调试日志）

提供全局能力查询：`hasDebugMode()` / `hasPermission_EasyPlace(player)` / `isEasyPlaceValidatorEnabled()`。

> **移植要点**：保留为全局配置单例；`hasPermission_EasyPlace` 等对接 Bukkit 权限节点 `servux.main.easy_place`。

---

## 9. i18n / 日志 / Reference

- **`Reference`**（`Reference.java`）：`MOD_ID="servux"`、`MC_VERSION`、`MOD_STRING`（协议握手里的 `servux` 字段值，形如 `servux-fabric-1.21.11-0.9.4`）、`DEV_DEBUG`、`DEFAULT_CONFIG_DIR`（= `FabricLoader.getConfigDir()`）。
  > **移植**：`MOD_STRING` 必须保持 fabric 前缀伪装——`servux-fabric-<精确上游 MC 版本>-<插件版本>`（`MOD_TYPE` 恒 `"fabric"`；26.1 客户端按 `startsWith("servux-fabric-<精确上游id>")` 硬门禁，`"paper"` 前缀会被四通道静默拒绝）；`DEFAULT_CONFIG_DIR` = `plugin.getDataFolder()`。
- **i18n**（`util/i18n/` + `util/i18nLang.java`）：`servux.*` 翻译键，读 `assets/servux/lang/*.json`。
  > **移植**：Paper 插件用自带 lang 文件或直接硬编码中文/英文消息；masa 客户端不关心服务端消息语言。
- **日志**（`util/log/AnsiLogger`）：带 ANSI 颜色的日志器。
  > **移植**：直接用 `JavaPlugin.getLogger()`（SLF4J/Log4j2）。

---

## 10. 架构到 Paper 的映射速览

| Fabric/Servux 概念 | Paper 对应 |
|---|---|
| `ModInitializer.onInitialize` | `JavaPlugin.onEnable` |
| `ServerInitHandler`（Mixin 触发注册 provider） | `onEnable` 里直接注册 |
| Mixin 生命周期钩子 → `ServerHandler`/`PlayerHandler` | Bukkit 事件（`ServerLoadEvent`/`PlayerJoinEvent`/...） |
| `DataProviderManager`（注册表 + 调度 + 配置） | 同结构照抄；tick 用 `BukkitRunnable`/`ServerScheduler` |
| `IDataProvider`/`DataProviderBase` | 纯抽象，照抄（NMS 类型经 paperweight 可用） |
| `servux.json` in `config/` | `plugins/VeryMcProto/servux.json` |
| `settings/` 系统 | 纯 Java，照抄 |
| `/servux`（Mixin 注入 dispatcher） | `plugin.yml` + `CommandExecutor` / Paper Brigadier |
| `fabric-permissions-api` | Bukkit `hasPermission` / LuckPerms |
| Mixin（25 个） | 反射 / 事件 / PacketEvents / 降级（见 [04](04-mixin-analysis.md)） |

完整的逐项迁移设计见 [07-migration-architecture.md](07-migration-architecture.md)。
