# 23 · Syncmatica 移植实施蓝图

> 本文档是 **syncmatica LTS 1.21.11 → Paper 1.21.11** 移植的**逐阶段作战手册**，供 auto mode 自动推进。
> 架构见 [20](20-syncmatica-architecture.md)；协议见 [21](21-syncmatica-protocol.md)；Mixin 与迁移方案见 [22](22-syncmatica-mixin-migration.md)；测试见 [24](24-syncmatica-testing-guide.md)。
> 原版源码根：`OriginImpl/syncmatica-LTS-1.21.11/src/main/java/ch/endte/syncmatica/`（下文简写为 `ORIGIN/`）。
> Paper 目标包根：`src/main/java/verymc/top/veryMcProto/mod/syncmatica/`（下文简写为 `PAPER/`）。

---

## 0. Context（前置状态与目标）

**前置**：Servux（5 通道 + schematic + EasyPlace）与 JEI Recipe Bridge 已实现并实测通过；`framework/network`（ChannelManager / ProtocolChannel / ServerPlayHandler / FriendlyByteBufs / PacketSplitter）、`framework/debug`、`framework/reflect`、`framework/permission` 均已就绪。

**目标**：在 Paper 服务端复刻 syncmatica 的「投影共享中央仓库」——单通道 `syncmatica:main` + 18 PacketType + Exchange 会话层 + 文件存储 + 持久化。客户端仍是 syncmatica Fabric 客户端（+ Litematica）。

**规模估算**：约 35–40 个 Java 文件，大部分**近乎照抄**原版（纯 Java + Gson + NMS 直连，无 Mixin）。**复杂度显著低于 Servux schematic 子系统**（无循环依赖、无 DataFixer、无 BitArray/Palette 压缩）。唯一全新设计是 `SyncmaticaHandler`（单通道 PacketType 派发）与 `ExchangeTarget` 改造。

---

## 1. 总体规则（auto mode 硬约束）

1. **编译门**：每阶段结束 `./gradlew compileJava` 必须 EXIT=0 才进下一阶段；失败当场修复，禁止带错推进。
2. **照抄优先**：data / extended_core / service / communication / exchange / util 逐字节照抄原版；仅以下强制改造：
   - 包名 `ch.endte.syncmatica` → `verymc.top.veryMcProto.mod.syncmatica`；
   - `ExchangeTarget` 改持 `Player`（[22](22-syncmatica-mixin-migration.md) §4.3）；
   - `SyncmaticaPacket` 收发对接 `framework/network`（[22](22-syncmatica-mixin-migration.md) §4.2）；
   - 路径全改 `getDataFolder()`（[22](22-syncmatica-mixin-migration.md) §5）；
   - `DebugService` 修正 2 处拼写/默认值 bug（[22](22-syncmatica-mixin-migration.md) §8.2）；
   - `ServerPlacement` 去掉 `matList` 字段与相关方法（[22](22-syncmatica-mixin-migration.md) §9）。
3. **日志**：原版 `Syncmatica.LOGGER.warn/error/info("...{}...", args)`（log4j，支持 `{}` 占位）→ 复用 `mod/servux/util/Log.java` shim（已承接 SLF4J→JUL），机械替换；或对接 `framework/debug/Debug`。
4. **桩标注**：若某阶段需引入但暂不回填的方法，保留原版签名 + `// TODO P{n}: 回填（原版 ORIGIN/...:行号）`，便于 grep。
5. **提交 checkpoint**：P0–P9 每阶段完成 `git commit`，结尾 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。
6. **复用 framework**：`framework.network.{ChannelManager, ProtocolChannel, ServerPlayHandler, FriendlyByteBufs}`、`framework.permission.Perms`、`framework.debug.Debug`。**不复用** `PacketSplitter` / `IServerPayloadData`（[22](22-syncmatica-mixin-migration.md) §4.5）。
7. **不引入新依赖**：`build.gradle.kts` 不改；仅 `plugin.yml` 加 `commands: syncmatica` + `permissions`（[22](22-syncmatica-mixin-migration.md) §11）。

---

## 2. 关键技术决策（汇总，出处见前文）

| 项 | 处置 | 依据 |
|---|---|---|
| 物理包体结构 | `[Identifier][body]`，照抄 `SyncmaticaPacket.fromPacket/toPacket` | [21](21-syncmatica-protocol.md) §1.2 / [22](22-syncmatica-mixin-migration.md) §4.2 |
| 通道注册 | 单通道 `syncmatica:main`，`ChannelManager.register` | [22](22-syncmatica-mixin-migration.md) §4.1 |
| ExchangeTarget | 持 `Player`，`Map<UUID, ExchangeTarget>` 管理（无 IServerPlay mixin） | [22](22-syncmatica-mixin-migration.md) §4.3-4.4 |
| 文件分片 | 自写 stop-and-wait（16KB/片），**不复用 PacketSplitter** | [21](21-syncmatica-protocol.md) §6 / [22](22-syncmatica-mixin-migration.md) §4.5 |
| hash 算法 | MD5 → `UUID.nameUUIDFromBytes`（type-3 UUID） | [21](21-syncmatica-protocol.md) §7.3 |
| 持久化路径 | `getDataFolder()/syncmatics/<hash>.litematic` + `placements.json` + 原子写 | [22](22-syncmatica-mixin-migration.md) §5 |
| Feature 协商 | 服务端声明全集（`Arrays.asList(Feature.values())`） | [20](20-syncmatica-architecture.md) §4 / [21](21-syncmatica-protocol.md) §3.2 |
| material | 全删（含 `ServerPlacement.matList` 字段） | [22](22-syncmatica-mixin-migration.md) §9 |
| RedirectFileStorage | 不移植（服务端纯 FileStorage） | [22](22-syncmatica-mixin-migration.md) §9 |
| 客户端 exchange（4 个） | 不移植类，但服务端 `handle` 须回应其包 | [21](21-syncmatica-protocol.md) §5.5 |
| 生命周期 | Mixin → Bukkit 事件（ServerLoadEvent/PlayerJoin/PlayerQuit/PluginMessage） | [22](22-syncmatica-mixin-migration.md) §3 |
| 权限 | `PermsWrap` → `player.hasPermission(node)` | [22](22-syncmatica-mixin-migration.md) §6 |
| DebugService | 修正 `doPacketLogging`/`doPackageLogging` 拼写 + 默认值统一 false | [22](22-syncmatica-mixin-migration.md) §8.2 |

---

## 3. 依赖与编译顺序

syncmatica **无循环依赖**（对比 Servux schematic 的四元循环，本项是线性的层次依赖）：

```
P0 util/extended_core 叶子（零业务依赖）
  ↑
P1 litematica peek（依赖 P0 util）
  ↑
P2 data 核心模型（ServerPlacement/FileStorage，依赖 P0 + P1 peek）
  ↑
P3 service（Quota/Debug，纯 Java）
  ↑
P4 Context 容器（聚合 P2/P3 + 通信/管理器引用，先留 setContext 钩子）
  ↑
P5 网络传输层（SyncmaticaPacket + SyncmaticaHandler）
  ↑
P6 exchange（依赖 CommunicationManager 抽象基类的 putMetaData 等）
  ↑
P7 通信管理器（CommunicationManager 抽象 + ServerCommunicationManager 子类，后者 new exchange）
  ↑
P8 数据管理器 + 命令（SyncmaticManager 持久化 + Brigadier）
  ↑
P9 装配（SyncmaticaApp：onEnable/onDisable + 事件 listener + plugin.yml）
```

> **唯一编译期互依**：`CommunicationManager`（抽象基类，含 `putMetaData`/`putPositionData`/`onPacket`）↔ `exchange/*`（子类 `handle` 调基类的 `receivePositionData` 等）。Java 同模块一起编译无碍，不需桩破解。`Context` ↔ 各 manager 的 `setContext` 双向引用同理。

---

## 4. 逐阶段清单

### P0 — util + extended_core + 枚举叶子
- **原版 → Paper 文件映射**：
  - `ORIGIN/util/SyncmaticaUtil.java` → `PAPER/util/SyncmaticaUtil.java`（MD5→UUID `createChecksum` / `litematicPeek` / `backupAndReplace` / `sanitizeUnicodeFileName`）
  - `ORIGIN/util/StringTools.java` → `PAPER/util/StringTools.java`（`getModVersion`，Paper 改读 plugin `getDescription().getVersion()`）
  - `ORIGIN/extended_core/PlayerIdentifier.java` → `PAPER/extended_core/PlayerIdentifier.java`
  - `ORIGIN/extended_core/PlayerIdentifierProvider.java` → `PAPER/extended_core/PlayerIdentifierProvider.java`
  - `ORIGIN/extended_core/SubRegionPlacementModification.java` → `PAPER/extended_core/SubRegionPlacementModification.java`
  - `ORIGIN/extended_core/SubRegionData.java` → `PAPER/extended_core/SubRegionData.java`
  - `ORIGIN/data/ServerPosition.java` → `PAPER/data/ServerPosition.java`
  - `ORIGIN/data/LocalLitematicState.java` → `PAPER/data/LocalLitematicState.java`
  - `ORIGIN/communication/MessageType.java` → `PAPER/communication/MessageType.java`
  - `ORIGIN/network/PacketType.java` → `PAPER/network/PacketType.java`（**注意 path 拼写 `request_download`/`mesage` 照抄**）
  - `ORIGIN/Feature.java` → `PAPER/network/Feature.java`（或 `PAPER/Feature.java`）
  - `ORIGIN/communication/FeatureSet.java` → `PAPER/communication/FeatureSet.java`
- **适配**：仅改 `package` + import。`SyncmaticaUtil.createChecksum` 用 JDK `MessageDigest`（无 NMS）；`ServerPosition` 用 NMS `BlockPos`/`GlobalPos`（paperweight 直连）。
- **门**：`./gradlew compileJava`

### P1 — litematica peek 解析（命令 load 用）
- **原版 → Paper 文件映射**（`PAPER/data/litematica/`）：
  - `ORIGIN/litematica/schematic/FileType.java`
  - `ORIGIN/litematica/schematic/Schema.java`
  - `ORIGIN/litematica/schematic/SchematicMetadata.java`
  - `ORIGIN/litematica/schematic/SchematicSchema.java`
- **适配**：这些是 syncmatica **自带**的轻量 `.litematic` 元数据解析（**不依赖 litematica mod**），仅读 NBT 头部（Metadata + Schema），照抄。`SchematicMetadata` 含 `getName`/`getTime`/`getAuthor` 等；`SchematicSchema` 含版本字段。
- **门**：`compileJava`

### P2 — 数据核心模型
- **原版 → Paper 文件映射**：
  - `ORIGIN/data/ServerPlacement.java` → `PAPER/data/ServerPlacement.java`
  - `ORIGIN/data/IFileStorage.java` → `PAPER/data/IFileStorage.java`
  - `ORIGIN/data/FileStorage.java` → `PAPER/data/FileStorage.java`
  - **不移植**：`ORIGIN/data/RedirectFileStorage.java`（客户端装饰器）
- **适配**：
  - `ServerPlacement`：**删除 `matList` 字段 + `getMaterialList()`/`setMaterialList()`** + 去 `import material.*`；其余字段/构造器/`toJson`/`fromJson`/`generateHash`/`move`/`normalizeFileName` 照抄。
  - `FileStorage`：`getSchematicPath` 服务端分支 `<litematicFolder>/<hash>.litematic` 照抄；`hashCompare`/`createLocalLitematic`/`getLocalLitematic` 照抄。`getDownloadState` 委托 `context.getCommunicationManager()`（P7 才有，本阶段 `context` 字段先留，编译不报错因 `IFileStorage.setContext(Context)`）。
- **门**：`compileJava`（本阶段 `Context` 若已声明则需 P4 先行；为避免顺序耦合，可把 `Context` 的最小桩在 P2 一并引入，P4 回填——见 P4）。

### P3 — 服务层（service）
- **原版 → Paper 文件映射**（`PAPER/service/`）：
  - `IService.java` / `AbstractService.java` / `IServiceConfiguration.java` / `JsonConfiguration.java`（抽象，照抄）
  - `QuotaService.java`（照抄：`IS_ENABLED_DEFAULT=false` / `QUOTA_LIMIT_DEFAULT=40_000_000` / `isOverQuota` / `progressQuota`）
  - `DebugService.java`（照抄 + **修正**：字段名统一 `doPacketLogging`、配置 key 统一 `doPacketLogging`、默认值统一 `false`）
- **适配**：纯 Java + Gson，零 NMS。
- **门**：`compileJava`

### P4 — Context 容器
- **原版 → Paper 文件映射**：
  - `ORIGIN/Context.java` → `PAPER/context/SyncmaticaContext.java`
  - `ORIGIN/Syncmatica.java`（静态门面）→ 拆分：常量（`NETWORK_ID`/`SERVER_PATH`/`syncmaticaId`）并入 `SyncmaticaContext` 或 `Reference`；`initServer`/`shutdown` 逻辑并入 P9 的 `SyncmaticaApp`。
- **适配**：
  - 删除所有 `Reference.isClient()`/`isIntegratedServer()`/`isOpenToLan()` 分支（Paper 恒 dedicated）；
  - `registerReceivers()` 改为 `ChannelManager.register(syncmatica:main, handler)`（handler 在 P5/P9 注入，本阶段留空或参数传入）；
  - `loadConfiguration()` 照抄（Gson 读 `config.json` 的 quota/debug）；
  - `litematicFolder` = `plugin.getDataFolder().resolve("syncmatics")`。
- **门**：`compileJava`

### P5 — 网络传输层
- **原版 → Paper 文件映射**：
  - `ORIGIN/network/SyncmaticaPacket.java` → `PAPER/network/SyncmaticaPacket.java`（保留 `fromPacket`/`toPacket` 的 `[Identifier][body]` 逻辑；`Payload` record 可简化为纯工具，因 Paper 不走 `CustomPacketPayload` 而走 plugin messaging）
  - `ORIGIN/network/handler/ServerPlayHandler.java` → **改造**为 `PAPER/network/SyncmaticaHandler.java`（实现 `IPluginServerPlayHandler`）
- **适配 `SyncmaticaHandler`**：
  ```java
  public class SyncmaticaHandler implements IPluginServerPlayHandler {
      public Identifier getPayloadChannel() { return SYNCMATICA_MAIN; }  // syncmatica:main
      public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player) {
          Identifier logic = data.readIdentifier();
          PacketType type = PacketType.getType(logic);
          FriendlyByteBuf body = new FriendlyByteBuf(data.readBytes(data.readableBytes()));
          ExchangeTarget t = comMan.getTarget(player.getUUID());  // P7
          comMan.onPacket(t, type, body);
      }
      // sendSyncPacket 走 ChannelManager.send（[22] §4.2）
  }
  ```
- **门**：`compileJava`（依赖 P7 的 `comMan`，可先留字段 + setter，P7 注入）。

### P6 — Exchange 会话层
- **原版 → Paper 文件映射**（`PAPER/communication/exchange/`）：
  - `Exchange.java` / `AbstractExchange.java`（接口 + 状态机基类，含 `checkUUID` peek）
  - `FeatureExchange.java`（Feature 协商抽象）
  - `VersionHandshakeServer.java`（版本握手）
  - `DownloadExchange.java` / `UploadExchange.java`（文件传输对，16KB stop-and-wait）
  - `ModifyExchangeServer.java`（修改锁）
  - **不移植**：`VersionHandshakeClient` / `ModifyExchangeClient` / `ShareLitematicExchange`（客户端，但服务端 `handle` 须回应其包）
- **适配**：近乎照抄。`ExchangeTarget.sendPacket` 的调用点不变（P7 改造 ExchangeTarget 内部实现）。`DownloadExchange` 的 quota 检查照抄（`getContext().getQuotaService().isOverQuota`）。
- **门**：`compileJava`（依赖 `CommunicationManager` 基类的 `putMetaData`/`receivePositionData`/`setDownloadState` 等，P7 提供基类后编译；本阶段可与 P7 同批提交）。

### P7 — 通信管理器（核心）
- **原版 → Paper 文件映射**（`PAPER/communication/`）：
  - `ORIGIN/communication/ExchangeTarget.java` → **改造**：`ServerGamePacketListenerImpl` 字段 → `org.bukkit.entity.Player`；`persistentName` = `player.getUniqueId().toString()`；`sendPacket` 走 `ChannelManager.send`（[22](22-syncmatica-mixin-migration.md) §4.2-4.3）
  - `ORIGIN/communication/CommunicationManager.java`（抽象基类：`onPacket`/`putMetaData`/`putPositionData`/`receiveMetaData`/`receivePositionData`/`download`/`startExchange` 等，照抄）
  - `ORIGIN/communication/ServerCommunicationManager.java`（服务端实现：`onPlayerJoin`/`onPlayerLeave`/`handle`/`handleExchange`/`addPlacement`/`cancelShare`，照抄）
- **适配**：
  - `ServerCommunicationManager` 加 `Map<UUID, ExchangeTarget> targets` + `Map<UUID, ServerPlayer> playerMap`（替代原 mixin 挂载）；
  - `onPlayerJoin(target, player)`：原版由 `MixinServerPlayNetworkHandler.<init>` 触发 → Paper 改由 `PlayerJoinEvent` 调用，`getTarget(uuid)` 懒创建。
  - `getGameProfile(target)`：原版 `playerMap.get(target).getGameProfile()` → Paper `Bukkit.getOfflinePlayer(uuid)` 或 `playerMap` 缓存的 GameProfile。
- **门**：`compileJava`（与 P6 同批，合编译）。

### P8 — 数据管理器 + 命令
- **原版 → Paper 文件映射**：
  - `ORIGIN/data/SyncmaticManager.java` → `PAPER/data/SyncmaticManager.java`
  - `ORIGIN/command/SyncmaticaCommand.java` → **改造**为 Paper Brigadier（`PAPER/command/SyncmaticaCommand.java`）
  - **不移植**：`command/IServerCommand.java`（Paper 不需要此抽象）、`command/PermsWrap.java`（→ `framework.permission.Perms` 或直接 `hasPermission`）
- **适配**：
  - `SyncmaticManager.loadServer/saveServer`：路径改 `getDataFolder()/placements.json`；`backupAndReplace` 原子写照抄；旧路径迁移逻辑（`Reference.CONFIG_ROOT`）**删除**。
  - 命令树仅 `load`（`load_all` + `load_each`）：Paper `LifecycleEventManager` 注册；`loadEach` 用 `SyncmaticaUtil.litematicPeek` + `ServerPlacement` + `comms.addPlacement`；权限 `syncmatica.command.load` / `.load_each`。
- **门**：`compileJava`

### P9 — 装配 + 注册 + plugin.yml
- **新建**：
  - `PAPER/app/SyncmaticaApp.java`：`onEnable`（`ServerLoadEvent` listener → 构造 `SyncmaticaContext` + `startup()` + `registerHandler` + 注册 `PlayerJoinEvent`/`PlayerQuitEvent` listener + Brigadier 命令）；`onDisable`（`context.shutdown()` + `ChannelManager.unregister`）。
- **事件 listener**：
  - `ServerLoadEvent` → `context.startup()`（如未在 onEnable 直接调）
  - `PlayerJoinEvent` → `comMan.onPlayerJoin(getOrCreateTarget(player), serverPlayer)`（触发 `VersionHandshakeServer`）
  - `PlayerQuitEvent` → `comMan.onPlayerLeave(getTarget(uuid))`
  - `PluginMessageListener`（`syncmatica:main`）→ 已由 `ProtocolChannel` 内部回调 `SyncmaticaHandler.receivePlayPayload`
- **plugin.yml**：加 `commands: { syncmatica: { description: ..., permission: syncmatica.command } }` + `permissions: { syncmatica.command: { default: true }, syncmatica.command.load: { default: true }, syncmatica.command.load_each: { default: true } }`。
- **门**：`./gradlew build`（完整 reobf jar）+ 启动测试服 `./gradlew runServer` 无异常加载。

---

## 5. 状态表（进度跟踪）

| 阶段 | 内容 | 文件数 | 状态 | 编译门 |
|---|---|---|---|---|
| P0 | util + extended_core + 枚举叶子 | 12 | ⬜ 未开始 | `compileJava` |
| P1 | litematica peek 解析 | 4 | ⬜ 未开始 | `compileJava` |
| P2 | 数据核心模型（ServerPlacement/FileStorage） | 3 | ⬜ 未开始 | `compileJava` |
| P3 | 服务层（Quota/Debug） | 6 | ⬜ 未开始 | `compileJava` |
| P4 | Context 容器 | 1 | ⬜ 未开始 | `compileJava` |
| P5 | 网络传输层（SyncmaticaPacket + Handler） | 2 | ⬜ 未开始 | `compileJava` |
| P6 | Exchange 会话层（6 个 exchange） | 8 | ⬜ 未开始 | `compileJava`（与 P7 合编） |
| P7 | 通信管理器（ExchangeTarget + 2 Manager） | 3 | ⬜ 未开始 | `compileJava`（与 P6 合编） |
| P8 | 数据管理器 + 命令 | 2 | ⬜ 未开始 | `compileJava` |
| P9 | 装配 + 注册 + plugin.yml | 1 + yml | ⬜ 未开始 | `build` + `runServer` |

> 状态标记：⬜ 未开始 / 🔄 进行中 / ✅ 完成 / ⚠️ 阻塞。auto mode 推进时实时更新本表。

---

## 6. 风险点

| 风险 | 等级 | 缓解 |
|---|---|---|
| `SyncmaticaPacket` 的 `[Identifier][body]` 解析与 Servux 习惯不同，易写错 | 🔴 高 | 严格照抄 `fromPacket`/`toPacket`；写单元测试验证 round-trip（构造 type+body → 编码 → 解码 → 比对） |
| `checkPacket` peek UUID 未回退 readerIndex → 多 exchange 路由错位 | 🔴 高 | 照抄 `AbstractExchange.checkUUID`（记录 readerIndex → 读 UUID → 回退）；P6 编译后用多 exchange 场景测试 |
| 文件分片改成批量发送（误以为可优化）→ 客户端卡死 | 🟡 中 | 严格 stop-and-wait，照抄 `UploadExchange.send` 的「等 RECEIVED 才发下一片」；不引入异步批量 |
| `placements.json` 字段顺序与原版不一致 → 自愈逻辑误判 dirty | 🟡 中 | 照抄 `ServerPlacement.toJson` 字段顺序；P8 后做重启持久化测试 |
| hash 算法被「优化」成 SHA/文件名 → 客户端校验失败 | 🟡 中 | 锁定 `SyncmaticaUtil.createChecksum` = MD5 → type-3 UUID，加注释禁止改 |
| CONFIRM_USER 单包过大（placement 极多时超 32767） | 🟢 低 | 原版未分片，照抄；实测若超限再分批发 CONFIRM_USER（后续优化） |
| `getGameProfile` 在 Paper 取不到离线玩家名字 | 🟢 低 | `onPlayerJoin` 时缓存在线玩家的 GameProfile；`PlayerIdentifier` 仅作展示，缺失用 `MISSING_PLAYER` |

---

## 7. 里程碑与验收

| 里程碑 | 验收标准 | 对应测试（[24](24-syncmatica-testing-guide.md)） |
|---|---|---|
| **M1 编译通过** | `./gradlew build` EXIT=0，reobf jar 产出 | — |
| **M2 服务端启动** | `runServer` 无异常，日志见 `syncmatica:main` 通道注册 + `placements.json` 创建 | §2 启动检查 |
| **M3 握手成功** | syncmatica 客户端进服后，日志见 `REGISTER_VERSION` 往返 + `CONFIRM_USER` | §3 握手测试 |
| **M4 分享投影** | 客户端上传 `.litematic` → 服务端 `syncmatics/<hash>.litematic` 落地 + 广播给其他客户端 | §4 分享测试 |
| **M5 下载投影** | 其他客户端点 Load → 收到文件 + Litematica 显示 | §5 下载测试 |
| **M6 修改放置** | 客户端移动/旋转投影 → 广播 MODIFY → 其他客户端同步 | §6 修改测试 |
| **M7 持久化** | 重启服务端 → `placements.json` 恢复 + 客户端重连见全部投影 | §7 持久化测试 |
| **M8 命令 load** | `/syncmatica load` 从磁盘注册 `.litematic` | §8 命令测试 |

> **核心验收**：M3–M6 构成最小可用闭环（握手→分享→下载→修改）。达到 M6 即可宣告 syncmatica 移植功能可用。

---

> **下一步**：客户端测试步骤与排错 → [24-syncmatica-testing-guide.md](24-syncmatica-testing-guide.md)
