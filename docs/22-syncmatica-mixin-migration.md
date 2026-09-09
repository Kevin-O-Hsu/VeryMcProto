# 22 · Syncmatica Mixin 分析与 Paper 迁移实现记录

> **文档定位**：syncmatica（投影共享）**迁移已完成**，本文是 Mixin → Paper 迁移的【已落地实现记录】，非方案/建议。
> 对应实现代码：`src/main/java/verymc/top/veryMcProto/mod/syncmatica/`（实际包结构见 §10）。
> 原版根目录：`OriginImpl/syncmatica-LTS-1.21.11/src/main/java/ch/endte/syncmatica/`（含 `mixin/` 9 个服务端/客户端 Mixin + `litematica_mixin/` 10 个 GUI Mixin）。
> 相关：架构见 [20](20-syncmatica-architecture.md)；协议字段见 [21](21-syncmatica-protocol.md)；实施记录见 [23](23-syncmatica-implementation-plan.md)；测试见 [24](24-syncmatica-testing-guide.md)；项目总览见 [../CLAUDE.md](../CLAUDE.md)。

---

## 0. 迁移总览（已落地）

syncmatica 原版共 **19 个 Mixin**（`mixin/` 9 + `litematica_mixin/` 10），无 AccessWidener。Paper 无 Mixin 运行时，**服务端真正需要替换的 Mixin 仅 5 个**，全部用 **Bukkit 事件 / Brigadier 命令 / plugin messaging listener / 写死 dedicated** 替代——**无一处「改服务端行为」类降级**（对比 Servux 的 EasyPlace / UpdateSuppression / 潜影盒堆叠，syncmatica 不改任何原版服务端逻辑，纯协议层 + 文件 I/O，不需要 PacketEvents / 反射改 NMS 行为）。

复杂度显著低于 Servux。**已 100% 完整移植**：握手 / 上传 / 下载 / 修改 / 删除 / 持久化 / 多玩家共享全功能实测通过。

---

## 1. Mixin 总清单与已落地处置

### 1.1 `mixin/`（核心，9 个）—— 逐项处置已落地

| Mixin | 配置分组 | 已落地处置 | 说明 |
|---|---|---|---|
| `MixinMinecraftServer` | 通用（client+server） | ✅ **Bukkit 事件 + 写死 dedicated** | 服务端启停生命周期（§2.1） |
| `MixinPlayerManager` | 通用 | ✅ **合并进 `PlayerJoinEvent`** | 玩家进服（§2.2） |
| `MixinServerPlayNetworkHandler` | 通用 | ✅ **`PluginMessageListener` + `Map<UUID, ExchangeTarget>`** | 包路由 + ExchangeTarget 桥接（§2.3） |
| `MixinServerCommonNetworkHandler` | 通用 | ✅ **冗余，未实现** | 兜底包路由，Paper 单 listener 已覆盖（§2.4） |
| `MixinCommandManager` | 通用 | ✅ **plugin.yml + Bukkit `CommandExecutor`** | 命令注册（§7） |
| `MixinIntegratedServer` | **client** | ⛔ **不移植** | 单机/Open-to-LAN，Paper 无此概念 |
| `MixinClientCommonNetworkHandler` | client | ⛔ **不移植** | 纯客户端 |
| `MixinClientPlayNetworkHandler` | client | ⛔ **不移植** | 纯客户端 |
| `MixinMinecraftClient` | client | ⛔ **不移植** | 纯客户端 |

### 1.2 `litematica_mixin/`（GUI，10 个）—— 全部不移植

| Mixin | 已落地处置 |
|---|---|
| `MixinButtonBase` / `MixinGuiBase` / `MixinGuiMainMenu` / `MixinGuiPlacementConfiguration` / `MixinSchematicHolder` / `MixinSchematicPlacement` / `MixinSchematicPlacementManager` / `MixinSubregionPlacement` / `MixinWidgetListSchematicPlacement` / `MixinWidgetSchematicPlacement` | ⛔ **全部不移植** |

全部是注入 `fi.dy.masa.litematica.*` 客户端 GUI 类（给 Litematica 菜单加「Share / 服务端投影列表」按钮）。Paper 服务端无 GUI，全删。

### 1.3 三段式处置汇总

| 类别 | 数量 | 已落地处置 | 对应 Servux 经验 |
|---|---|---|---|
| **生命周期触发** | 3（MinecraftServer / PlayerManager / CommandManager） | Bukkit 事件 / 写死 / plugin.yml | 同 Servux（[04](04-mixin-analysis.md) 同类） |
| **包路由 + 状态挂载** | 2（ServerPlay/ServerCommon NetworkHandler） | plugin messaging listener + `Map<UUID, ExchangeTarget>` | 类似 Servux 但无 IServerPlay mixin |
| **客户端 / 单机** | 4 + 10 | 全删 | 同 Servux 客户端 mixin |

> 对比 Servux 的 26 Mixin + 2 AW：syncmatica 服务端真正替换的 mixin 仅 **5 个**，且无 AccessWidener、无「改服务端行为」类。

---

## 2. 服务端 Mixin 逐项已落地分析

### 2.1 MixinMinecraftServer —— 服务端总生命周期

`mixin/MixinMinecraftServer.java`，`@Mixin(MinecraftServer.class)`。3 个 `@Inject`。

| 原版注入点 | 目标 / at | 原版调用 | Paper 已落地替代 |
|---|---|---|---|
| `onServerStarting` | `runServer` @INVOKE `initServer()` | `Reference.setDedicatedServer(true)` 等 | **写死 dedicated=true**（`SyncmaticaContext.isServer()=true`、`isClient/isIntegratedServer=false` 恒成立） |
| `onServerStarted` | `runServer` @INVOKE `buildServerStatus` ordinal 0 | **`Syncmatica.initServer(ServerCommMgr, FileStorage, SynMgr, !isDedicated, worldPath).startup()`** | **`SyncmaticaModule.enable`**（构造 `SyncmaticaContext` + `context.startup()`；由主类 `VeryMcProto.onEnable` 调用） |
| `onServerStopped` | `stopServer` @TAIL | `Syncmatica.shutdown()` | **`SyncmaticaModule.disable`**（`context.shutdown()` + 注销 handler；由主类 `onDisable` 调用） |

**已落地装配**（`SyncmaticaModule.enable`，对应原版 `Syncmatica.initServer` + `Context.startup`）：

```java
final Path dataFolder = plugin.getDataFolder().toPath();
final Path litematicFolder = dataFolder.resolve(SyncmaticaReference.LITEMATIC_SUBDIR); // plugins/VeryMcProto/syncmatics/
final Path configFolder = dataFolder; // 配置直接放插件根目录
final FileStorage fileStorage = new FileStorage(litematicFolder);
final ServerCommunicationManager comMan = new ServerCommunicationManager();
final SyncmaticManager synMan = new SyncmaticManager();
context = new SyncmaticaContext(plugin, fileStorage, comMan, synMan, litematicFolder, configFolder);
context.startup();  // quota/debug/synMan.startup → loadServer 读 placements.json
handler = new SyncmaticaHandler(context);
ServerPlayHandler.getInstance().registerServerPlayHandler(handler);  // → ChannelManager.register(syncmatica:main)
```

### 2.2 MixinPlayerManager —— 玩家进服

`mixin/MixinPlayerManager.java`，`@Mixin(PlayerList.class)`。

| 原版注入点 | 目标 / at | 原版调用 | Paper 已落地替代 |
|---|---|---|---|
| `eventOnPlayerJoin` | `placeNewPlayer` @TAIL | `ServerPlayHandler.encodeSyncData(REGISTER_VERSION[MOD_VERSION], player)`—— 服务端先报版本 | **不单独发**（合并进 `PlayerJoinEvent`，握手由 `tryStartHandshake` 统一发起 VersionHandshakeServer） |
| `eventOnPlayerLeave` | `remove` @HEAD | **空方法**（原版注释「// Something we need to do here?」） | 无逻辑，无需对应 |

> 原版 `placeNewPlayer` 发的 `REGISTER_VERSION` 与 exchange 握手的版本包**重复**。Paper 移植**只保留 exchange 的那次**——`PlayerJoinEvent` 里仅注册 target（`getOrCreateTarget` + `onPlayerJoin` 登记），握手延后到 `tryStartHandshake`（见 §3 命门）。

### 2.3 MixinServerPlayNetworkHandler —— 包路由 + ExchangeTarget（最关键）

`mixin/MixinServerPlayNetworkHandler.java`，`@Mixin(ServerGamePacketListenerImpl.class) priority=1001 implements IServerPlay`。

| 原版注入点 | 目标 / at | 原版调用 | Paper 已落地替代 |
|---|---|---|---|
| `onConnect` | `<init>` @TAIL | `operateComms(sm -> sm.onPlayerJoin(getExchangeTarget(), player))` | **`PlayerJoinEvent`** → `getOrCreateTarget(player)` + `onPlayerJoin(target)`（仅登记；握手延后，见 §3） |
| `onDisconnected` | `onDisconnect` @HEAD | `operateComms(sm -> sm.onPlayerLeave(getExchangeTarget()))` | **`PlayerQuitEvent`** → `getTarget(uuid)` + `onPlayerLeave(target)` |
| `onCustomPayload` | `handleCustomPayload` @HEAD cancellable | namespace==syncmatica → `decodeSyncData(payload.data(), this)` + cancel | **`SyncmaticaHandler.receivePlayPayload`**（经 `ServerPlayHandler.registerServerPlayHandler` → `ChannelManager` 自动注册 `syncmatica:main` incoming） |
| `@Unique operateComms(Consumer)` | — | 懒加载 comManager + null 安全 | **直接持有 comManager 引用**（Paper 恒已 init，无 Consumer 包装） |
| `@Unique getExchangeTarget()` | — | 懒加载 `new ExchangeTarget(handler)` | **`Map<UUID, ExchangeTarget> targets`**（`getOrCreateTarget`，§4.4） |

> 原版注释「FAPI networking 太慢注册 receiver，所以直接 Mixin 截包」。Paper 的 `Messenger` 通道映射即原版 custom payload（与 Servux 一致，见 [CLAUDE.md](../CLAUDE.md) §1），`byte[]` 即 `FriendlyByteBuf` 裸字节——**单一 handler 收全部 `syncmatica:main` 包，不需要 mixin 截包**。

### 2.4 MixinServerCommonNetworkHandler —— 兜底包路由（冗余）

`mixin/MixinServerCommonNetworkHandler.java`，`@Mixin(ServerCommonPacketListenerImpl.class)`。注入 `handleCustomPayload @HEAD`，逻辑与 2.3 相同，原版注释说明是「防 Mojang 把 `handleCustomPayload` 上移到 common 基类」的兜底。

**已落地处置：完全不实现**——`SyncmaticaHandler` 单一入口已覆盖所有 `syncmatica:main` 包，无基类上移风险。

### 2.5 MixinCommandManager —— 命令注册

`mixin/MixinCommandManager.java`，`@Mixin(Commands.class)`。两条 `@Inject`（dedicated `AFTER WhitelistCommand.register` + integrated `AFTER PublishCommand.register`）都调 `SyncmaticaCommand.INSTANCE.register(dispatcher, registryAccess, environment)`。

**已落地处置**：dedicated 恒成立，原版双注入合并为 **plugin.yml `commands: syncmatica` 注册 + 主类 `getCommand("syncmatica").setExecutor(new SyncmaticaCommand(ctx))`**（Bukkit `CommandExecutor`/`TabCompleter`，非 Brigadier；与 `ServuxCommand` 同模式）。命令树见 §7。

---

## 3. 生命周期迁移总表（已落地）

| 原版 Mixin 时机 | 原版触发动作 | Paper 已落地事件 / 钩子 | 实现代码 |
|---|---|---|---|
| `MinecraftServer.runServer` @INVOKE(initServer) | 设 dedicated 标志 | **写死**（`SyncmaticaContext.isServer()=true`） | `SyncmaticaContext` |
| `MinecraftServer.runServer` @INVOKE(buildServerStatus) | **initServer + Context.startup** | **主类 `onEnable` → `SyncmaticaModule.enable`** | `VeryMcProto` / `SyncmaticaModule.enable` |
| `MinecraftServer.stopServer` @TAIL | **shutdown + saveServer** | **主类 `onDisable` → `SyncmaticaModule.disable`** | `VeryMcProto` / `SyncmaticaModule.disable` |
| `PlayerList.placeNewPlayer` @TAIL | 发 REGISTER_VERSION（冗余） | **不单独发**（合并进 exchange 握手） | — |
| `ServerGamePacketListenerImpl.<init>` @TAIL | **onPlayerJoin（握手）** | **`PlayerJoinEvent`**（仅登记 target；握手延后 40t） | `SyncmaticaModule.onJoin` |
| `ServerGamePacketListenerImpl.onDisconnect` @HEAD | **onPlayerLeave** | **`PlayerQuitEvent`** | `SyncmaticaModule.onQuit` |
| `ServerGamePacketListenerImpl.handleCustomPayload` @HEAD | **包路由** | **`SyncmaticaHandler.receivePlayPayload`**（plugin messaging listener） | `SyncmaticaHandler` |
| `Commands.<init>` | 注册命令 | **plugin.yml + Bukkit `CommandExecutor`** | `plugin.yml` / `VeryMcProto.onEnable` / `SyncmaticaCommand` |

> 与 Servux 的生命周期迁移完全同构（参见 [07](07-migration-architecture.md) §生命周期），复用 `framework/network` 的事件分发模式。

### 3.1 握手双保险命门（实测关键修复）

原版 `MixinServerPlayNetworkHandler.onConnect` 可在 `<init>` @TAIL 立即 `onPlayerJoin` + 发 `REGISTER_VERSION`——可行是因为 Fabric 配置阶段（configuration phase）已完成通道声明。

Paper 下 `PlayerJoinEvent` 时客户端 codec **尚未就绪**，立即推 `REGISTER_VERSION` 会握手失败并残留 exchange。**已落地双保险**（`SyncmaticaModule.enable` 注册的 listener）：

1. **主路径**：`PlayerJoinEvent` → `getOrCreateTarget` + `onPlayerJoin`（仅登记）→ `runTaskLater(40t)`（2s，等 configuration phase 完成、客户端 codec 就绪）→ **`tryStartHandshake`**（`ServerCommunicationManager`，幂等：已在 `broadcastTargets` 或已有进行中 `VersionHandshakeServer` 则跳过）。
2. **兜底/加速**：`PlayerRegisterChannelEvent`（channel == `syncmatica:main`）→ 立即 `tryStartHandshake`（旧式 MC|Register 协商；1.21 Fabric 客户端经 `PayloadTypeRegistry.playS2C()` 声明通道**通常不触发**此事件，故仅作加速/兜底）。

`tryStartHandshake` 幂等，两路径安全共存。`/syncmatica enable`（恢复协议）对在线玩家走同一 40t 延迟握手路径（`SyncmaticaModule.reconnectOnlinePlayers`）。

---

## 4. 网络层迁移（已落地）

### 4.1 通道注册（已落地）

```java
// SyncmaticaModule.enable
handler = new SyncmaticaHandler(context);                       // 实现 IPluginServerPlayHandler
ServerPlayHandler.getInstance().registerServerPlayHandler(handler);
// → ChannelManager.register(syncmatica:main) 自动 incoming + outgoing
```

复用 `framework.network.ChannelManager` + `ServerPlayHandler`（与 Servux 共享）。**单通道，一次注册**（`SyncmaticaReference.NETWORK_ID = Identifier.fromNamespaceAndPath("syncmatica", "main")`）。`disable` 时 `unregisterServerPlayHandler(handler)` 反注册。

### 4.2 SyncmaticaPacket 收发（已落地，命门）

`SyncmaticaHandler` 实现 `IPluginServerPlayHandler`，但**不复用** `IServerPayloadData` / `decodeServerData`（那是 Servux 的 per-通道模型）。只实现 `receivePlayPayload` + `getPayloadChannel` + `setPlayRegistered/clearPlayRegistered/reset`（注册状态机）+ `encodeWithSplitter`（syncmatica 用不到，给空）。

收（C2S，`SyncmaticaHandler.receivePlayPayload`）—— 解析 `[Identifier][body]` 复合包体：

```java
final Identifier logicChannel = data.readIdentifier();   // → PacketType（docs/21 §1.2 命门）
final PacketType type = PacketType.getType(logicChannel);
final FriendlyByteBuf body = new FriendlyByteBuf(data.readBytes(data.readableBytes()));
final ExchangeTarget target = comMan.getOrCreateTarget(player.getBukkitEntity());
comMan.onPacket(target, type, body);                     // → exchange 派发
```

发（S2C，`ExchangeTarget.sendPacket`）—— 构造 `[Identifier][body]` 复合包体：

```java
final byte[] body = FriendlyByteBufs.readableBytes(byteBuf);
final FriendlyByteBuf out = FriendlyByteBufs.buffer(body.length + 8);
out.writeIdentifier(type.getId());          // 逻辑通道
out.writeBytes(body);                       // body
final byte[] bytes = FriendlyByteBufs.extractAndRelease(out);
// → S2C 路径分发（NMS 或 plugin messaging，见 §4.3）
```

> 🔑 **命门**：`byte[]` = `[Identifier][body]`，照抄 `SyncmaticaPacket.fromPacket/toPacket`。**切勿**像 Servux 那样把 `byte[]` 直接当 body。

### 4.3 ExchangeTarget 改造 + S2C 路径（已落地，关键差异）

原版 `ExchangeTarget` 持 `ServerGamePacketListenerImpl`（需 mixin 注入）。Paper 改为持 **Bukkit `Player`**：

| 原版字段 | Paper 已落地字段 |
|---|---|
| `ServerGamePacketListenerImpl serverPlayNetworkHandler` | `Player player` + `UUID playerId` |
| `persistentName = player.getStringUUID()` | `player.getUniqueId().toString()` |
| `ongoingExchanges: List<Exchange>` | 照抄 |
| `features: FeatureSet` | 照抄 |
| `sendPacket` 走 `ServerPlayHandler.encodeSyncData` | **默认走 NMS `DiscardedPayload` 直发**（见下） |

**S2C 路径命门**（`ExchangeTarget.S2C_VIA_NMS = true`，实测关键）：

plugin messaging（`sendPluginMessage`）的 S2C wire 格式，纯 Fabric 客户端（syncmatica）**收不到**——客户端零响应、零 C2S 回包。而 NMS `ClientboundCustomPayloadPacket(new DiscardedPayload(syncmatica:main, bytes))` 直发（同 JEI 模块 `JeiPacketSender.send` 路径，原 `RecipeSyncHandler.sendPayload`——2026-09 上游重做后易主；JEI 配方同步实测通过）已验证 fabric 客户端可解码。故 **S2C 默认走 NMS 直发**；plugin messaging 仅作 fallback 保留，可经 `/syncmatica debug s2c msg` 切回对比。

```java
if (S2C_VIA_NMS) {
    final ServerPlayer nms = Nms.toNms(player);
    nms.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(SyncmaticaReference.NETWORK_ID, bytes)));
} else {
    ChannelManager.instance().send(SyncmaticaReference.NETWORK_ID, player, bytes);
}
```

> `DiscardedPayload` 本身即 vanilla payload 类型，序列化时由 Paper 注册的 codec 原样写出 bytes，不会被强转拒绝（与「自定义 Payload record」不同——后者触发 ClassCastException，见 `IPluginServerPlayHandler#sendPlayPayload` 注释）。客户端用其自行注册的 `SyncmaticaPacket.Payload.CODEC` 解码 payload data = `[Identifier][body]`，与原版 Fabric 服务端发的 wire 一致。

### 4.4 IServerPlay mixin 接口 → Map（已落地）

原版用 `MixinServerPlayNetworkHandler implements IServerPlay` 给每个 `ServerGamePacketListenerImpl` 实例挂 `@Unique` 字段（`exTarget` + `comManager`），并 `operateComms(Consumer)` 做 null 安全延迟解析（`network/actor/IServerPlay.java`）。

**Paper 完全无 mixin**——`ServerCommunicationManager` 内部维护 `Map<UUID, ExchangeTarget> targets`：

```java
private final Map<UUID, ExchangeTarget> targets = new HashMap<>();
public ExchangeTarget getOrCreateTarget(Player player) {
    return targets.computeIfAbsent(player.getUniqueId(), id -> new ExchangeTarget(player));
}
```

- `PlayerJoinEvent` → `getOrCreateTarget(player)` + `onPlayerJoin(target)`（仅登记 + 更新 PlayerIdentifierProvider）
- `PlayerQuitEvent` → `getTarget(uuid)` + `onPlayerLeave(target)`（关闭其 exchange 链 + `targets.remove` + `broadcastTargets.remove`）
- `operateComms` 的 Consumer 包装**不需要**（Paper 恒已 init context）

原版 `Map<ExchangeTarget, ServerPlayer> playerMap`（反查 ServerPlayer 取 GameProfile）一并删除——`ExchangeTarget` 已持 `Player`，`createOrGet(uuid, name)` 直接从 target 取。

### 4.5 文件分片自写（已落地，不复用 PacketSplitter）

`UploadExchange` / `DownloadExchange` 的 16KB stop-and-wait 分片逻辑**照抄原版**，仅把 `ExchangeTarget.sendPacket` 改走 §4.3 的 NMS/plugin messaging 路径。**不用** `framework.network.PacketSplitter`（模型不同，见 [21](21-syncmatica-protocol.md) §6）。

---

## 5. 持久化路径映射（已落地）

原版路径依赖 Fabric `GAME_ROOT` / `worldFolder`。Paper 用 `getDataFolder()`（`plugins/VeryMcProto/`）。常量定义在 `SyncmaticaReference`。

| 用途 | 原版路径 | Paper 已落地路径 | 常量 |
|---|---|---|---|
| 投影文件存储 | `<服务端根>/syncmatics/<hash>.litematic` | **`plugins/VeryMcProto/syncmatics/<hash>.litematic`** | `LITEMATIC_SUBDIR="syncmatics"` |
| placement 注册表 | `<世界目录>/syncmatica/placements.json`（+ `.bak`/`.new` 原子写） | **`plugins/VeryMcProto/placements.json`** | `PLACEMENTS_FILE_NAME="placements.json"` |
| 服务端配置（quota + debug） | `<世界目录>/syncmatica/config.json` | **`plugins/VeryMcProto/syncmatica-config.json`** | `CONFIG_FILE_NAME="syncmatica-config.json"` |
| 旧版迁移源 `config/syncmatica/` | 全局 config 目录 | **省略**（Paper 无等价，全新部署） | — |

**关键约束**（已落地）：

- `<hash>.litematic` 命名规则保留（hash 是跨客户端/服务端的内容寻址键；`FileStorage.getSchematicPath` 恒按 hash 命名，去掉原版 `isServer()` 分支）。
- `placements.json` 的 JSON 字段顺序与含义与原版逐字段一致（`{"placements":[...]}`，key=`PLACEMENTS_JSON_KEY`）。
- `SyncmaticManager.saveServer` **原子写**：写 `placements.json.new` → `SyncmaticaUtil.backupAndReplace(backup=.bak, current=placements.json, incoming=.new)`（`.bak → current ← incoming`，照搬原版）。
- `loadServer` 读 JSON 后调 `ServerPlacement.correctMetadataFromPeek(litematicFolder)`（原版在 `fromJson` 内 `context.isServer()` 分支，Paper 抽出），dirty 则立即 re-save。

> **多世界考量**：原版按「当前世界目录」存配置，切世界会换路径。Paper 用 `getDataFolder()` 则**跨世界共享**同一份投影库——符合「服务端中央仓库」语义（投影不属于某个世界）。

---

## 6. 权限迁移（已落地）

原版 `command/PermsWrap.java` 是 `me.lucko:fabric-permissions-api` 的薄封装（`PermsWrap.check(node, level)` → `Predicate<CommandSourceStack>`）。**Paper 直接用 `player.hasPermission(node)`**（可对接 LuckPerms / Vault，无需额外依赖）。

`plugin.yml` 已落地权限节点（`default` 策略）：

| 节点 | default | 用途 |
|---|---|---|
| `syncmatica.command` | **true** | `/syncmatica` 根命令 |
| `syncmatica.command.admin` | **op** | `save` / `reload` / `enable` / `disable` / `status`（配置与协议管理） |
| `syncmatica.command.load` | **true** | `/syncmatica load`（注册磁盘 .litematic 为 placement） |
| `syncmatica.command.load_each` | **true** | `/syncmatica load <file>` |
| `syncmatica.command.debug` | **op** | `/syncmatica debug`（调试日志宏开关，syncmatica 独立状态） |

> 与 Servux 权限迁移同构（[CLAUDE.md](../CLAUDE.md) §5）。

---

## 7. 命令迁移（已落地）

原版命令树**只有 `load`**（`load_all` + `load_each` 两个分支）。Paper 移植**在此基础上扩展**配置管理与协议启停子命令（参考 `ServuxCommand` 的运维模式）。

### 7.1 已落地命令树（`SyncmaticaCommand`，Bukkit `CommandExecutor`/`TabCompleter`）

```
/syncmatica                                  (syncmatica.command)
  ├── status                                 (syncmatica.command.admin) 显示协议启停+调试状态+配置文件
  ├── save                                   (.admin)  保存配置到 syncmatica-config.json
  ├── reload                                 (.admin)  从 syncmatica-config.json 重载
  ├── enable                                 (.admin)  恢复协议（通道保留，在线玩家重新握手）
  ├── disable                                (.admin)  软禁用协议（通道保留、玩家不踢、进行中传输中断）
  ├── load                                   (syncmatica.command.load)
  │    ├── [无参]   doLoadAll                扫 syncmatics/*.litematic 全部注册（load_all 权限隐含）
  │    └── <file>   doLoadEach               (load_each) 注册指定文件（tab 补全文件名列表）
  └── debug                                  (syncmatica.command.debug) 调试日志宏开关热切换
       ├── [无参] / status                   查看状态
       ├── on / off                          master 总开关（仅 master，不碰分类）
       ├── cat <all|none|<name>>             分类（lifecycle/handshake/network/packet/exchange/data）
       └── s2c <nms|msg>                     S2C 路径切换（NMS DiscardedPayload / plugin messaging，不持久化）
```

> 上传/下载/修改/删除全走协议 exchange，命令仅用于「从磁盘把已有 `.litematic` 注册为 placement」+ 运维（配置/协议/调试）。

### 7.2 loadEach 核心逻辑（已落地，`SyncmaticaCommand.loadEach`）

`SyncmaticaUtil.litematicPeek(path)` 读 `.litematic` 的 `SchematicMetadata` + `SchematicSchema`（不入 NMS）→ `new ServerPlacement(randomUUID, path, filename, owner)` → `move(玩家当前位置, NONE, NONE)` → `setMetadata/setSchema` → `comms.addPlacement(target, placement)`（注册 + 广播给 `broadcastTargets`）。控制台执行（无发起方 target）直接 `synMan.addPlacement`（玩家进服握手时 `CONFIRM_USER` 下发全部 placement）。

`updateSyncmaticDir` 仅列出未加载的文件：文件名去掉 `.litematic` 后必须是合法 UUID（`<hash>.litematic` 命名）且 `hasPlacementHash` 为 false 才纳入候选。

---

## 8. 服务层迁移（已落地）

`service/` 整个包是纯 Java + Gson，无 NMS 依赖，**近乎照抄**。`IService` / `AbstractService` / `IServiceConfiguration` / `JsonConfiguration` 是 syncmatica 的「可配置服务」抽象，**完整照抄**（保留扩展性）。修正原版两处 bug。

### 8.1 JsonConfiguration（已落地）

Gson 回调式配置实现。读时 try/catch 任何异常并设 `wasError=true`（供 `Context.loadConfigurationForService` 判断是否需回写默认值——`hadError()` 为 true 则触发 `needsRewrite` 落盘修正）。

### 8.2 DebugService（已落地，含两处 bug 修正）

| 项 | 原版 | Paper 修正 |
|---|---|---|
| 字段默认值 | `doPacketLogging = true`（`DebugService.java:8`） | **统一 `false`**（生产不应默认开 INFO 级包日志） |
| 配置 key | `"doPackageLogging"`（**拼写错误**，Package） | **统一 `"doPacketLogging"`**（Packet，字段名 = key） |

调用点照抄：`logReceivePacket(type)` 在 `CommunicationManager.onPacket` 开头；`logSendPacket(type, id)` 在 `ExchangeTarget.sendPacket`。

> **注意**：DebugService 是「包级 INFO 日志」开关（包收发时打印一行）；与 `SyncmaticaDebug`（mod 独立的分类调试系统，master + 6 分类，由 `/syncmatica debug cat` 控制）**是两套独立机制**。`SyncmaticaDebug` 状态经 `SyncmaticaContext.saveConfiguration` 持久化到 `syncmatica-config.json` 的顶层 `"debugLog"` 子对象（master + 分类各自独立保存，重启完全恢复）；DebugService 的 `doPacketLogging` 持久化到 `"debug"` 子对象。

### 8.3 QuotaService（已落地）

| 项 | 值 |
|---|---|
| 默认启用 | `false`（`IS_ENABLED_DEFAULT`） |
| 默认上限 | `40_000_000` 字节 / 玩家（40MB，`QUOTA_LIMIT_DEFAULT`） |
| config key | `"quota"` |
| 配置字段 | `enabled`(bool) / `limit`(int) |
| 生效点 | 仅 `DownloadExchange`（客户端→服务端上传方向），`UploadExchange` 不查 |
| 持久化 | **`progress` Map 不持久化**（重启清零）；`enabled`/`limit` 持久化到 `syncmatica-config.json` 的 `"quota"` 段 |

`isOverQuota(senderName, newData)` / `progressQuota(senderName, newData)` 改接收 `String senderName`（原版接收 `ExchangeTarget`，解耦通信层；调用方传 `target.getPersistentName()`）。

---

## 9. 降级矩阵（已落地）

| 功能 | 已落地处置 | 原因 / 影响 |
|---|---|---|
| **`material/`（材料配送）** | ⛔ **不移植，字段一起去掉** | 死代码：原版仅 `ServerPlacement.matList` 字段持有 `SyncmaticaMaterialList`，无 exchange / 无 PacketType / 无命令 / 无持久化引用。Paper `ServerPlacement` 删除 `matList` 字段 + `getMaterialList`/`setMaterialList` 方法 |
| **`RedirectFileStorage`** | ⛔ **不移植** | 客户端装饰器（外部文件重定向免拷贝）；服务端纯 `FileStorage` 即可 |
| **`extended_core/`（CORE_EX）** | ✅ **照抄** | owner / lastModifiedBy / subregion 共享，是协议字段（影响 metadata 编码），必须实现 |
| **`litematica/schematic/`（peek）** | ✅ **照抄**（落地为 `data/litematica/`） | `SchematicMetadata`/`SchematicSchema`/`Schema`/`FileType` 是 syncmatica 自带的轻量 litematic 解析（不依赖 litematica mod），命令 `load` 需要 |
| **版本协商（VERSION feature）** | ✅ **照抄** | `litematicVersion` / `dataVersion` 字段；`MOD_VERSION`=插件版本（`-b` 后缀永不命中版本正则）触发 FEATURE 交换使双方用全集 FeatureSet |
| **客户端 exchange（3 个）** | ⛔ **不实现类，但服务端 `handle` 须回应其包** | `ModifyExchangeClient` / `ShareLitematicExchange` / `VersionHandshakeClient` 不移植；服务端 `ServerCommunicationManager.handle/handleExchange` 照常处理其对应 PacketType（见 [21](21-syncmatica-protocol.md) §5.5） |
| **`Reference.isClient/isIntegratedServer/isOpenToLan` 分支** | ⚠️ **简化删除** | Paper 恒 dedicated server（`SyncmaticaContext.isServer()=true`，其余 false） |
| **`/syncmatica load` 以外的原版命令** | — | 原版本就没有；Paper 扩展了 `status/save/reload/enable/disable/debug`（运维用） |

> **无「改服务端行为」类降级**（对比 Servux 的 EasyPlace / UpdateSuppression / 潜影盒堆叠）：syncmatica 不改任何原版服务端逻辑，纯协议层 + 文件 I/O，**不需要 PacketEvents / 反射改 NMS 行为**。

---

## 10. 实际包结构（已落地）

`src/main/java/verymc/top/veryMcProto/mod/syncmatica/` 全树（44 个 Java 文件）：

```
verymc.top.veryMcProto.mod.syncmatica/
├── Feature.java                            ← 9 枚举（照抄）
├── SyncmaticaContext.java                  ← Context 容器（去客户端分支；protocolEnabled 软禁用）
├── SyncmaticaReference.java                ← 常量（MOD_VERSION=插件版本 / NETWORK_ID / 文件名常量）
├── app/
│   └── SyncmaticaModule.java               ← 启停入口（主类 onEnable/onDisable 调用；Bukkit 事件 listener）
├── command/
│   └── SyncmaticaCommand.java              ← Bukkit CommandExecutor（load_all/load_each + status/save/reload/enable/disable/debug）
├── communication/
│   ├── CommunicationManager.java           ← 抽象基类（onPacket/metadata 编解码/exchange 调度）
│   ├── ExchangeTarget.java                 ← 持 Player；S2C 默认 NMS DiscardedPayload 直发
│   ├── FeatureSet.java                     ← 照抄
│   ├── MessageType.java                    ← 照抄
│   ├── ServerCommunicationManager.java     ← 服务端（onPlayerJoin/Leave/handle/handleExchange + tryStartHandshake + targets Map + suspendAll）
│   └── exchange/
│       ├── Exchange.java / AbstractExchange.java    ← 照抄（含 checkUUID peek）
│       ├── VersionHandshakeServer.java                ← 照抄
│       ├── FeatureExchange.java                       ← 照抄
│       ├── DownloadExchange.java / UploadExchange.java ← 照抄（16KB stop-and-wait 分片 + MD5）
│       └── ModifyExchangeServer.java                  ← 照抄
├── data/
│   ├── FileStorage.java                    ← 照抄（去 isServer 分支；litematicFolder 注入；恒 hash 命名）
│   ├── IFileStorage.java                   ← 照抄
│   ├── LocalLitematicState.java            ← 照抄
│   ├── ServerPlacement.java                ← 照抄（去 matList 字段；+ correctMetadataFromPeek）
│   ├── ServerPosition.java                 ← 照抄
│   ├── SyncmaticManager.java               ← 照抄（saveServer 原子写 .new→.bak→current；loadServer peek 修正）
│   └── litematica/                         ← 从 litematica/schematic/ 照抄（peek 用）
│       ├── FileType.java / Schema.java / SchematicMetadata.java / SchematicSchema.java
├── extended_core/                          ← 照抄
│   ├── PlayerIdentifier.java / PlayerIdentifierProvider.java
│   └── SubRegionData.java / SubRegionPlacementModification.java
├── network/
│   ├── PacketType.java                     ← 18 枚举（照抄）
│   └── SyncmaticaHandler.java              ← IPluginServerPlayHandler 实现（receivePlayPayload → onPacket；encodeWithSplitter 空）
├── service/
│   ├── AbstractService.java / IService.java / IServiceConfiguration.java ← 照抄
│   ├── JsonConfiguration.java              ← 照抄（hadError 机制）
│   ├── DebugService.java                   ← 照抄 + 修正拼写/默认值 bug
│   └── QuotaService.java                   ← 照抄（progress 不持久化；senderName 解耦）
└── util/
    ├── StringTools.java
    ├── SyncmaticaDebug.java                ← mod 独立调试门面（6 分类，持久化到 debugLog 段）
    ├── SyncmaticaLog.java                  ← JUL shim（SLF4J → JUL）
    └── SyncmaticaUtil.java                 ← createChecksum MD5→UUID / litematicPeek / backupAndReplace
```

> 与 Servux 共享 `framework/network`（`ChannelManager` / `ServerPlayHandler` / `IPluginServerPlayHandler` / `FriendlyByteBufs`）、`framework/debug/DebugSystem`、`framework/nms/Nms`、`framework/util`。**未新增 framework 类**。

---

## 11. 依赖变更（已落地）

| 原版依赖 | Paper 处置 |
|---|---|
| `fabric-networking-api-v1` | ✅ 删除——用 `framework/network`（ChannelManager/ServerPlayHandler） |
| `fabric-permissions-api`（Lucko） | ✅ 删除——用 `player.hasPermission(...)` |
| `fabric-resource-loader-v1` | ✅ 删除——客户端 GUI 用，服务端不需要 |
| `litematica` / `malilib`（suggests） | ✅ **不需要**——服务端代码不引用其类（客户端 exchange 不移植；`data/litematica/` peek 类是 syncmatica 自带轻量解析，不依赖 litematica mod） |
| `modmenu`（compileOnly） | ✅ 删除——客户端 mod 列表集成 |
| Gson | ✅ 已有（JDK / Paper 附带） |

> **结论**：syncmatica 移植**不引入任何新外部依赖**，`build.gradle.kts` 未为 syncmatica 改动。`plugin.yml` 加 `commands: syncmatica` + 5 个 `permissions` 声明（§6）。`softdepend: [packetevents]` 仅给 Servux EasyPlace 用，与 syncmatica 无关。

---

## 12. 迁移命门清单（维护必读）

1. **物理包体 `[Identifier][body]`**：收发照抄 `SyncmaticaPacket.fromPacket/toPacket`，勿当 Servux 处理（§4.2）。
2. **ExchangeTarget 用 `Map<UUID, ExchangeTarget>` 管理**，无 IServerPlay mixin（§4.4）。
3. **文件分片自写**，不复用 `PacketSplitter`（§4.5）。
4. **S2C 默认 NMS `DiscardedPayload` 直发**——plugin messaging 的 wire 纯 Fabric 客户端收不到（实测，§4.3）。
5. **握手双保险**：`PlayerJoinEvent` 延迟 40t 主路径 + `PlayerRegisterChannelEvent` 兜底；`tryStartHandshake` 幂等（§3.1）。
6. **路径全改 `getDataFolder()`**，但 `<hash>.litematic` 命名 + `placements.json` 字段 + 原子写照搬（§5）。
7. **material 死代码连字段一起去掉**（§9）。
8. **DebugService 修正 2 处拼写/默认值 bug**；注意与 `SyncmaticaDebug` 是两套独立机制（§8.2）。
9. **CORE_EX / VERSION / DISPLAY_NAME / MODIFY 四个 feature 必须实现**（影响字段编码，§9）。
10. **checkPacket peek / handle 消费两段式**必须复刻（多 exchange 路由正确性，[21](21-syncmatica-protocol.md) §9）。
11. **不引入新依赖**，`build.gradle.kts` 不改（§11）。

---

> **相关**：架构与本质差异见 [20](20-syncmatica-architecture.md)；协议字段与 Exchange 状态机见 [21](21-syncmatica-protocol.md)；阶段实施记录见 [23](23-syncmatica-implementation-plan.md)；客户端兼容测试见 [24](24-syncmatica-testing-guide.md)；项目总览与核心约束见 [../CLAUDE.md](../CLAUDE.md)；原版权威源码见 `OriginImpl/syncmatica-LTS-1.21.11/`。
