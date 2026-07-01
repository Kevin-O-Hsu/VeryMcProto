# 22 · Syncmatica Mixin 分析与 Paper 迁移方案

> 原版根目录：`OriginImpl/syncmatica-LTS-1.21.11/src/main/java/ch/endte/syncmatica/`
> 相关：架构见 [20](20-syncmatica-architecture.md)；协议字段见 [21](21-syncmatica-protocol.md)；实施计划见 [23](23-syncmatica-implementation-plan.md)。
> Mixin 配置：`src/main/resources/syncmatica.mixin.json`（核心）+ `syncmatica.litematica_mixin.json`（GUI）。
> 构建配置：`build.gradle` + `fabric.mod.json`（`environment: "*"` 双端；`litematica/malilib` 仅 suggests）。

---

## 1. Mixin 总清单与处置分类

syncmatica 共 **19 个 Mixin**，分两个配置文件。**Paper 无 Mixin 运行时**，逐个按下表处置：

### 1.1 `syncmatica.mixin.json`（核心，9 个）

| Mixin | 配置分组 | 处置 | 说明 |
|---|---|---|---|
| `MixinMinecraftServer` | 通用（client+server） | ✅ **Bukkit 事件** | 服务端启停生命周期（§2.1） |
| `MixinPlayerManager` | 通用 | ✅ **Bukkit 事件** | 玩家进服（§2.2） |
| `MixinServerPlayNetworkHandler` | 通用 | ✅ **Bukkit 事件 + Map** | 包路由 + ExchangeTarget 桥接（§2.3） |
| `MixinServerCommonNetworkHandler` | 通用 | ✅ **冗余，合并** | 兜底包路由，Paper 单 listener 即可（§2.4） |
| `MixinCommandManager` | 通用 | ✅ **Paper Brigadier** | 命令注册（§2.5） |
| `MixinIntegratedServer` | **client** | ⛔ **不移植** | 单机/Open-to-LAN，Paper 无此概念 |
| `MixinClientCommonNetworkHandler` | client | ⛔ **不移植** | 纯客户端 |
| `MixinClientPlayNetworkHandler` | client | ⛔ **不移植** | 纯客户端 |
| `MixinMinecraftClient` | client | ⛔ **不移植** | 纯客户端 |

### 1.2 `syncmatica.litematica_mixin.json`（GUI，10 个）

| Mixin | 处置 |
|---|---|
| `MixinButtonBase` / `MixinGuiBase` / `MixinGuiMainMenu` / `MixinGuiPlacementConfiguration` / `MixinSchematicHolder` / `MixinSchematicPlacement` / `MixinSchematicPlacementManager` / `MixinSubregionPlacement` / `MixinWidgetListSchematicPlacement` / `MixinWidgetSchematicPlacement` | ⛔ **全部不移植** |

全部是注入 `fi.dy.masa.litematica.*` 客户端 GUI 类（给 Litematica 菜单加「Share / 服务端投影列表」按钮）。Paper 服务端无 GUI，全删。

### 1.3 三段式处置汇总

| 类别 | 数量 | 处置 | 对应 Servux 经验 |
|---|---|---|---|
| **生命周期触发** | 3（MinecraftServer / PlayerManager / CommandManager） | Bukkit 事件 / Brigadier | 同 Servux（[04](04-mixin-analysis.md) 同类） |
| **包路由 + 状态挂载** | 2（ServerPlay/ServerCommon NetworkHandler） | plugin messaging listener + `Map<UUID, ExchangeTarget>` | 类似 Servux 但无 IServerPlay mixin |
| **客户端 / 单机** | 4 + 10 | 全删 | 同 Servux 客户端 mixin |

> 对比 Servux 的 26 Mixin + 2 AW：syncmatica 服务端真正要替换的 mixin 仅 **5 个**，且无 AccessWidener、无「改服务端行为」类（EasyPlace 那种）——**复杂度显著低于 Servux**。

---

## 2. 服务端 Mixin 逐项分析

### 2.1 MixinMinecraftServer —— 服务端总生命周期

`mixin/MixinMinecraftServer.java`，`@Mixin(MinecraftServer.class)`。

| 注入点 | 目标 / at | 调用 | Paper 替代 |
|---|---|---|---|
| `@Inject onServerStarting` | `runServer` @INVOKE `initServer()` | `Reference.setDedicatedServer(true)`（`:24-32`） | 写死 dedicated=true |
| `@Inject onServerStarted` | `runServer` @INVOKE `buildServerStatus` ordinal 0 | **`Syncmatica.initServer(ServerCommMgr, FileStorage, SynMgr, !isDedicated, worldPath).startup()`**（`:52-58`） | **`ServerLoadEvent`**（或 `onEnable`）里构造 Context + `startup()` |
| `@Inject onServerStopped` | `stopServer` @TAIL | `Syncmatica.shutdown()`（`:67-68`） | **`onDisable` / `PluginDisableEvent`** |

`initServer` 内部（`Syncmatica.java:125-144`）：构造 `Context(isServer=true, SERVER_PATH, worldPath)` → `Context` 构造函数装配 quota/debug/加载 config → `SyncmaticaCommand.updateSyncmaticDir` 扫文件 → `.startup()`（注册 receiver + `synMan.loadServer` 读 placements.json）。**Paper 把这整段放进 `ServerLoadEvent` listener**。

### 2.2 MixinPlayerManager —— 玩家进服首包

`mixin/MixinPlayerManager.java`，`@Mixin(PlayerList.class)`。

| 注入点 | 目标 / at | 调用 | Paper 替代 |
|---|---|---|---|
| `@Inject eventOnPlayerJoin` | `placeNewPlayer` @TAIL | `ServerPlayHandler.encodeSyncData(REGISTER_VERSION[MOD_VERSION], player)`（`:36-40`）—— 服务端先报版本 | **合并进 `PlayerJoinEvent`**（由 VersionHandshakeServer.init 发，可省此 mixin 的单独发包） |
| `@Inject eventOnPlayerLeave` | `remove` @HEAD | **空方法**（注释「// Something we need to do here?」，`:44-48`） | 无逻辑，无需对应 |

> 原版 `placeNewPlayer` 发的 `REGISTER_VERSION` 与 `VersionHandshakeServer.init` 发的 `REGISTER_VERSION` **重复**（玩家进服会收到两次版本包）。Paper 移植只保留 exchange 的那次即可。

### 2.3 MixinServerPlayNetworkHandler —— 包路由 + ExchangeTarget（最关键）

`mixin/MixinServerPlayNetworkHandler.java`，`@Mixin(ServerGamePacketListenerImpl.class) priority=1001 implements IServerPlay`。

| 注入点 | 目标 / at | 调用 | Paper 替代 |
|---|---|---|---|
| `@Inject onConnect` | `<init>` @TAIL | `operateComms(sm -> sm.onPlayerJoin(getExchangeTarget(), player))`（`:40`） | **`PlayerJoinEvent`** → `ServerCommMgr.onPlayerJoin(target, player)` |
| `@Inject onDisconnected` | `onDisconnect` @HEAD | `operateComms(sm -> sm.onPlayerLeave(getExchangeTarget()))`（`:46`） | **`PlayerQuitEvent`** → `ServerCommMgr.onPlayerLeave(target)` |
| `@Inject onCustomPayload` | `handleCustomPayload` @HEAD cancellable | namespace==syncmatica → `decodeSyncData(payload.data(), this)` + `ci.cancel()`（`:58-59`） | **`Messenger.registerIncomingPluginChannel("syncmatica:main", listener)`** |
| `@Unique operateComms(Consumer)` | — | 懒加载 comManager + null 安全（`:67-82`） | **直接持有 comManager 引用**（Paper 恒已 init，无需 Consumer 包装） |
| `@Unique getExchangeTarget()` | — | 懒加载 `new ExchangeTarget(handler)`（`:84-92`） | **`Map<UUID, ExchangeTarget>`**（见 §4.4） |

> 原版注释（`:49-50`）：「FAPI networking 太慢注册 receiver，所以直接 Mixin 截包」。Paper 的 `Messenger` 通道映射即原版 custom payload（与 Servux 一致，见 [CLAUDE.md](../CLAUDE.md) §1），`byte[]` 即 `FriendlyByteBuf` 裸字节——**单 listener 即可，不需要 mixin 截包**。

### 2.4 MixinServerCommonNetworkHandler —— 兜底包路由（冗余）

`mixin/MixinServerCommonNetworkHandler.java`，`@Mixin(ServerCommonPacketListenerImpl.class)`。注入 `handleCustomPayload @HEAD`，与 2.3 逻辑相同，注释（`:17-18`）说明是「防 Mojang 把 `handleCustomPayload` 上移到 common 基类」的兜底。

**Paper 处置：完全不实现**——`Messenger` 单一入口已覆盖所有 `syncmatica:main` 包，无基类上移风险。

### 2.5 MixinCommandManager —— 命令注册

`mixin/MixinCommandManager.java`，`@Mixin(Commands.class)`。两条 `@Inject`（dedicated `AFTER WhitelistCommand.register` + integrated `AFTER PublishCommand.register`）都调 `SyncmaticaCommand.INSTANCE.register(dispatcher, registryAccess, environment)`（`:27,36`）。

**Paper 处置**：dedicated 恒成立，只保留一条 → **Paper Brigadier**（`LifecycleEventManager` 注册 `/syncmatica load`，见 §7）。本项目 Servux 已有 `mod/servux/command/` 可参考。

---

## 3. 生命周期迁移总表

| 原版 Mixin 时机 | 触发动作 | Paper 事件 / 钩子 |
|---|---|---|
| `MinecraftServer.runServer` @INVOKE(initServer) | 设 dedicated 标志 | 写死（Paper 恒 dedicated） |
| `MinecraftServer.runServer` @INVOKE(buildServerStatus) | **initServer + Context.startup** | **`ServerLoadEvent`** |
| `MinecraftServer.stopServer` @TAIL | **shutdown + saveServer** | **`JavaPlugin.onDisable`** |
| `PlayerList.placeNewPlayer` @TAIL | 发 REGISTER_VERSION（冗余） | 合并进 `PlayerJoinEvent` |
| `ServerGamePacketListenerImpl.<init>` @TAIL | **onPlayerJoin（握手）** | **`PlayerJoinEvent`** |
| `ServerGamePacketListenerImpl.onDisconnect` @HEAD | **onPlayerLeave** | **`PlayerQuitEvent`** |
| `ServerGamePacketListenerImpl.handleCustomPayload` @HEAD | **包路由** | **`PluginMessageListener.onPluginMessageReceived`**（`syncmatica:main`） |
| `Commands.<init>` | 注册命令 | Paper Brigadier `LifecycleEventManager` |

> 与 Servux 的生命周期迁移完全同构（参见 [07](07-migration-architecture.md) §生命周期），可直接复用 `framework/event` 的事件分发模式。

---

## 4. 网络层迁移

### 4.1 通道注册

```java
// onEnable / ServerLoadEvent
Identifier SYNCMATICA_MAIN = Identifier.fromNamespaceAndPath("syncmatica", "main");
SyncmaticaHandler handler = new SyncmaticaHandler(context);  // 实现 IPluginServerPlayHandler
ServerPlayHandler.getInstance().registerServerPlayHandler(handler);
// → ChannelManager.register(syncmatica:main) 自动 incoming + outgoing
```

复用 `framework.network.ChannelManager` + `ProtocolChannel` + `ServerPlayHandler`（见 [20](20-syncmatica-architecture.md) §9）。**单通道，一次注册**。

### 4.2 SyncmaticaPacket 收发适配（核心）

syncmatica 的 handler 实现 `IPluginServerPlayHandler`，但**不复用** `IServerPayloadData` / `decodeServerData`（那是 Servux 的 per-通道模型）。只实现 `receivePlayPayload` + `getPayloadChannel` + `encodeWithSplitter`（后者 syncmatica 用不到，给空/默认）：

```java
// 收（C2S）
public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player) {
    // data 是 [Identifier][body] 复合结构（见 21 §1.2）
    Identifier logicChannel = data.readIdentifier();           // → PacketType
    PacketType type = PacketType.getType(logicChannel);
    FriendlyByteBuf body = new FriendlyByteBuf(data.readBytes(data.readableBytes()));
    ExchangeTarget target = comMan.getOrCreateTarget(player);   // §4.4
    comMan.onPacket(target, type, body);                        // → exchange 派发
}

// 发（S2C）—— ExchangeTarget.sendPacket 内部
void sendPacket(PacketType type, FriendlyByteBuf body, Player player) {
    FriendlyByteBuf out = FriendlyByteBufs.buffer();
    out.writeIdentifier(type.getId());        // 逻辑通道
    out.writeBytes(FriendlyByteBufs.readableBytes(body));  // body
    byte[] bytes = FriendlyByteBufs.extractAndRelease(out);
    ChannelManager.instance().send(SYNCMATICA_MAIN, player, bytes);
}
```

> 🔑 **命门**：`byte[]` = `[Identifier][body]`，照抄 `SyncmaticaPacket.fromPacket/toPacket`（`SyncmaticaPacket.java:39-48`）。**切勿**像 Servux 那样把 byte[] 直接当 body。

### 4.3 ExchangeTarget 改造

原版 `ExchangeTarget` 持 `ServerGamePacketListenerImpl`（需 mixin 注入）。Paper 改为持 `Player`（或 `UUID`）：

| 原版字段 | Paper 字段 |
|---|---|
| `ServerGamePacketListenerImpl serverPlayNetworkHandler` | `Player player`（或 `UUID playerId` + 懒解析） |
| `persistentName = player.getStringUUID()` | `player.getUniqueId().toString()` |
| `ongoingExchanges: List<Exchange>` | 照抄 |
| `features: FeatureSet` | 照抄 |
| `sendPacket` 走 `ServerPlayHandler.encodeSyncData` | 走 `ChannelManager.send`（§4.2） |

### 4.4 IServerPlay mixin 接口 → Map

原版用 `MixinServerPlayNetworkHandler implements IServerPlay` 给每个 `ServerGamePacketListenerImpl` 实例挂 `@Unique` 字段（`exTarget` + `comManager`），并 `operateComms(Consumer)` 做 null 安全延迟解析（`network/actor/IServerPlay.java`）。

**Paper 完全不需要 mixin**——`ServerCommunicationManager` 内部维护两个 Map：

```java
private final Map<UUID, ExchangeTarget> targets = new ConcurrentHashMap<>();  // playerId → target
private final Map<UUID, ServerPlayer> playerMap = new ConcurrentHashMap<>();  // 同原版 playerMap
```

- `PlayerJoinEvent` → `getOrCreateTarget(player)` 创建并 `onPlayerJoin`
- `PlayerQuitEvent` → `onPlayerLeave(target)` + `targets.remove(uuid)`
- `operateComms` 的 Consumer 包装**不需要**（Paper 恒已 init context）

### 4.5 文件分片自写（不复用 PacketSplitter）

`UploadExchange` / `DownloadExchange` 的 16KB stop-and-wait 分片逻辑**近乎照抄原版**（`communication/exchange/DownloadExchange.java` / `UploadExchange.java`），仅把 `ExchangeTarget.sendPacket` 的实现改成走 `ChannelManager`。**不能**用 `framework.network.PacketSplitter`（模型不同，见 [21](21-syncmatica-protocol.md) §6）。

---

## 5. 持久化路径映射

原版路径依赖 Fabric `GAME_ROOT` / `worldFolder`。Paper 用 `getDataFolder()`（`plugins/VeryMcProto/`）：

| 原版路径 | 原版值 | Paper 建议 |
|---|---|---|
| 投影文件存储 `SERVER_PATH` | `<服务端根>/syncmatics/<hash>.litematic` | `getDataFolder().resolve("syncmatics/")`（隔离插件目录，避免污染服务端根） |
| 配置/注册表目录 `getConfigFolder()` | `<世界目录>/syncmatica/` | `getDataFolder()`（插件目录即配置根） |
| `placements.json` | `<世界目录>/syncmatica/placements.json`（+ `.bak`/`.new` 原子写） | `getDataFolder()/placements.json`，**照搬 `backupAndReplace` 原子写** |
| `config.json` | `<世界目录>/syncmatica/config.json`（quota + debug） | `getDataFolder()/syncmatica-config.json`（或合并进插件主配置） |
| 旧版迁移源 `config/syncmatica/` | 全局 config 目录 | Paper 无等价，**省略迁移逻辑**（全新部署） |

> **关键**：`<hash>.litematic` 命名规则必须保留（hash 是跨客户端/服务端的内容寻址键）；`placements.json` 的 JSON 字段顺序与含义必须与原版逐字段一致（客户端虽不读此文件，但服务端重启自愈逻辑依赖）；`saveServer` 的 `.new → .bak → current` 原子替换照搬（`util/SyncmaticaUtil.backupAndReplace`）。

> **多世界考量**：原版按「当前世界目录」存配置，切世界会换路径。Paper 用 `getDataFolder()` 则**跨世界共享**同一份投影库——这其实更符合「服务端中央仓库」语义（投影不属于某个世界）。若需按世界隔离，可改用 `Bukkit.getWorlds().get(0).getWorldFolder()`，但不推荐。

---

## 6. 权限迁移

原版 `command/PermsWrap.java` 是 `me.lucko:fabric-permissions-api` 的薄封装（`PermsWrap.check(node, level)` → `Predicate<CommandSourceStack>`）。**Paper 直接用 Bukkit 权限**：

```java
// 原版
PermsWrap.check("syncmatica.command.load", PermissionLevel.ALL)
// Paper
player.hasPermission("syncmatica.command.load")
```

权限节点（默认全员，`plugin.yml` 设 `default: true`）：

| 节点 | 用途 |
|---|---|
| `syncmatica.command` | `/syncmatica` 根 |
| `syncmatica.command.load` | `load` 子命令 |
| `syncmatica.command.load_each` | `load <file>` |

> 与 Servux 权限迁移同构（[CLAUDE.md](../CLAUDE.md) §5）。可对接 LuckPerms / Vault，无需额外依赖。

---

## 7. 命令迁移

原版命令树**只有 `load`**（`load_all` + `load_each` 两个分支，`command/SyncmaticaCommand.java:46-73`）。无 save / upload / list——上传/下载/修改/删除全走协议 exchange，命令仅用于「从磁盘把已有 `.litematic` 注册为 placement」。

**Paper Brigadier 实现**（参考 `mod/servux/command/`）：

```
/syncmatica                                  (requires syncmatica.command)
  └── load                                   (requires syncmatica.command.load)
       ├── [无参]   doLoadAll                扫 syncmatics/*.litematic 全部注册
       └── <file>   doLoadEach               注册指定单个文件（tab 补全文件名列表）
```

`loadEach` 核心逻辑（`SyncmaticaCommand.java:188-201`）：`new ServerPlacement(randomUUID, path, filename, owner)` → `move(玩家当前位置, NONE, NONE)` → `setMetadata/setSchema`（从 `litematicPeek` 读）→ `comms.addPlacement(target, placement)`（注册 + 广播）。

> `SyncmaticaUtil.litematicPeek`（读 `.litematic` 的 metadata + schema，不入 NMS）可近乎照抄。`SchematicMetadata` / `SchematicSchema`（`litematica/schematic/`）是 syncmatica 自带的轻量解析类（不依赖 litematica mod），照抄。

---

## 8. 服务层迁移（QuotaService / DebugService）

`service/` 整个包是纯 Java + Gson，**无 NMS 依赖，近乎照抄**。但需修正原版两个 bug：

### 8.1 QuotaService（配额）

| 项 | 值 |
|---|---|
| 默认启用 | `false`（`QuotaService.java:10`，默认关闭） |
| 默认上限 | `40_000_000` 字节 / 玩家（40MB，`:11`） |
| config key | `"quota"`（`:41`） |
| 配置字段 | `enabled`(bool) / `limit`(int) |
| 生效点 | 仅 `DownloadExchange`（客户端→服务端上传方向），`UploadExchange` 不查 |
| 持久化 | **不持久化**（`progress` Map 重启清零） |

`isOverQuota(sender, newData)` / `progressQuota(sender, newData)` 照抄（`QuotaService.java:17-31`）。

### 8.2 DebugService（调试日志）—— 含两处 bug

| 项 | 值 | 问题 |
|---|---|---|
| 字段 `doPacketLogging` | 默认 `true`（`DebugService.java:8`） | ⚠️ 与配置默认值 `false`（`:33`）**不一致**：首次无配置时字段 true，落盘后下次启动读 false 才关 |
| 配置 key | `"doPackageLogging"`（`:33,45`） | ⚠️ **拼写不一致**（字段 `doPacketLogging` vs key `doPackageLogging`，多了 `k`/少了 `t`） |

**Paper 移植修正**：统一字段名 = 配置 key = `doPacketLogging`，默认值统一为 `false`（生产环境不应默认开 INFO 级包日志）。

调用点照抄：`logReceivePacket(type)` 在 `CommunicationManager.onPacket` 开头（`CommunicationManager.java:48`）；`logSendPacket(type, id)` 在 `ExchangeTarget.sendPacket`（`ExchangeTarget.java:48-53`）。可对接本项目 `framework/debug/Debug`（统一日志分类）。

### 8.3 抽象层（IService / JsonConfiguration）

`IService` / `AbstractService` / `IServiceConfiguration` / `JsonConfiguration` 是 syncmatica 的「可配置服务」抽象。**可照抄**（纯 Java + Gson 回调式配置），或简化为直接字段 + Gson（若不打算加更多 service）。建议照抄以保留扩展性。

---

## 9. 降级矩阵

| 功能 | 处置 | 原因 / 影响 |
|---|---|---|
| **`material/`（材料配送）** | ⛔ **完全不移植** | 死代码：仅 `ServerPlacement.matList` 持有，无 exchange / 无 PacketType / 无命令 / 无持久化引用。`ServerPlacement` 移植时去掉 `matList` 字段、`getMaterialList`/`setMaterialList` 方法 |
| **`RedirectFileStorage`** | ⚠️ **不移植** | 客户端装饰器（外部文件重定向免拷贝）；服务端纯 `FileStorage` 即可 |
| **`extended_core/`（CORE_EX）** | ✅ **照抄** | owner / lastModifiedBy / subregion 共享，是协议字段（影响 metadata 编码），必须实现 |
| **`litematica/schematic/`（peek）** | ✅ **照抄** | `SchematicMetadata`/`SchematicSchema`/`Schema`/`FileType` 是 syncmatica 自带的轻量 litematic 解析（不依赖 litematica mod），命令 `load` 需要 |
| **版本协商（VERSION feature）** | ✅ **照抄** | `litematicVersion` / `dataVersion` 字段 |
| **客户端 exchange（4 个）** | ⛔ **不实现类，但服务端 `handle` 须回应其包** | 见 [21](21-syncmatica-protocol.md) §5.5 |
| **`Reference.isClient/isIntegratedServer/isOpenToLan` 分支** | ⚠️ **简化删除** | Paper 恒 dedicated server |
| **`/syncmatica load` 以外的命令** | — | 原版本就没有 |

> **无「改服务端行为」类降级**（对比 Servux 的 EasyPlace / UpdateSuppression / 潜影盒堆叠）：syncmatica 不改任何原版服务端逻辑，纯协议层 + 文件 I/O，**不需要 PacketEvents / 反射改 NMS 行为**。

---

## 10. 目标包结构设计

参照现有 `mod/servux/` 与 `mod/jeirecipebridge/` 的分层惯例：

```
verymc.top.veryMcProto.mod.syncmatica/
├── app/
│   └── SyncmaticaApp.java                  ← 启停入口（onEnable/onDisable 调用），对应 Syncmatica+ModInit
├── context/
│   └── SyncmaticaContext.java              ← Context 容器（照抄，去客户端分支）
├── network/
│   ├── SyncmaticaPacket.java               ← [Identifier][body] 包装（照抄 toPacket/fromPacket）
│   ├── PacketType.java                     ← 18 枚举（照抄，注意 path 拼写）
│   ├── Feature.java                        ← 9 枚举（照抄）
│   └── SyncmaticaHandler.java              ← IPluginServerPlayHandler 实现（receivePlayPayload → onPacket）
├── communication/
│   ├── CommunicationManager.java           ← 抽象基类（照抄 onPacket/metadata 编解码/exchange 调度）
│   ├── ServerCommunicationManager.java     ← 服务端实现（照抄 onPlayerJoin/Leave/handle/handleExchange）
│   ├── ExchangeTarget.java                 ← 改持 Player（§4.3）
│   ├── FeatureSet.java                     ← 照抄
│   ├── MessageType.java                    ← 照抄
│   └── exchange/
│       ├── Exchange.java / AbstractExchange.java    ← 照抄（含 checkUUID peek）
│       ├── VersionHandshakeServer.java                ← 照抄
│       ├── FeatureExchange.java                       ← 照抄
│       ├── DownloadExchange.java / UploadExchange.java ← 照抄（分片 + MD5）
│       └── ModifyExchangeServer.java                  ← 照抄
├── data/
│   ├── ServerPlacement.java                ← 照抄（去 matList 字段）
│   ├── SyncmaticManager.java               ← 照抄（loadServer/saveServer，路径改 getDataFolder）
│   ├── FileStorage.java                    ← 照抄（去 RedirectFileStorage）
│   ├── LocalLitematicState.java            ← 照抄
│   ├── ServerPosition.java                 ← 照抄
│   └── litematica/                         ← 从 litematica/schematic/ 照抄（peek 用）
│       ├── SchematicMetadata.java / SchematicSchema.java / Schema.java / FileType.java
├── extended_core/                          ← 照抄（PlayerIdentifier / SubRegionData / ...）
├── service/
│   ├── QuotaService.java                   ← 照抄
│   ├── DebugService.java                   ← 照抄 + 修正 2 处 bug
│   └── (IService/AbstractService/JsonConfiguration)   ← 照抄或简化
├── command/
│   └── SyncmaticaCommand.java              ← Paper Brigadier（load_all / load_each）
└── util/
    ├── SyncmaticaUtil.java                 ← 照抄（createChecksum MD5→UUID / litematicPeek / backupAndReplace）
    └── Log.java                            ← 复用 servux 的 Log shim（SLF4J→JUL）
```

> 与 Servux 共享 `framework/network`、`framework/debug`、`framework/reflect`、`framework/util`。**不新增 framework 类**（除非 exchange 调度抽象日后需提升为框架，初版放 `mod/syncmatica/communication/` 即可）。

---

## 11. 依赖变更

| 原版依赖 | Paper 处置 |
|---|---|
| `fabric-networking-api-v1` | ✅ 删除——用 `framework/network`（ChannelManager/ProtocolChannel） |
| `fabric-permissions-api`（Lucko） | ✅ 删除——用 `player.hasPermission(...)` |
| `fabric-resource-loader-v1` | ✅ 删除——客户端 GUI 用，服务端不需要 |
| `litematica` / `malilib`（suggests） | ✅ **不需要**——服务端代码不引用其类（`ShareLitematicExchange` 等客户端 exchange 不移植；服务端自带的 `litematica/schematic/` peek 类不依赖 litematica mod） |
| `modmenu`（compileOnly） | ✅ 删除——客户端 mod 列表集成 |
| Gson | ✅ 已有（JDK / Paper 附带） |

> **结论**：syncmatica 移植**不引入任何新外部依赖**，现有 `build.gradle.kts`（servux + jeirecipebridge 已就绪）无需修改。仅 `plugin.yml` 加 `commands: syncmatica` + `permissions` 声明。

---

## 12. 迁移命门清单（实施必读）

1. **物理包体 `[Identifier][body]`**：收发照抄 `SyncmaticaPacket.fromPacket/toPacket`，勿当 Servux 处理（§4.2）。
2. **ExchangeTarget 用 Map 管理**，无 IServerPlay mixin（§4.4）。
3. **文件分片自写**，不复用 `PacketSplitter`（§4.5）。
4. **路径全改 `getDataFolder()`**，但 `<hash>.litematic` 命名 + `placements.json` 字段 + 原子写照搬（§5）。
5. **material 死代码连字段一起去掉**（§9）。
6. **DebugService 修正 2 处拼写/默认值 bug**（§8.2）。
7. **CORE_EX / VERSION / DISPLAY_NAME / MODIFY 四个 feature 必须实现**（影响字段编码，§9）。
8. **checkPacket peek / handle 消费两段式**必须复刻（多 exchange 路由正确性，[21](21-syncmatica-protocol.md) §9）。
9. **不引入新依赖**，`build.gradle.kts` 不改（§11）。

---

> **下一步**：阶段划分、文件清单、编译门 → [23-syncmatica-implementation-plan.md](23-syncmatica-implementation-plan.md)
