# Syncmatica 架构总览（含 Mixin→Bukkit 迁移记录）

> **状态**：syncmatica（投影共享）已完整实现，所有协议路径（握手 / 分享 / 下载 / 修改 / 删除 / 持久化 / 多玩家广播 / 软禁用）均经实测（26.1→26.2 wire 零变化）。
> **本文描述实际架构**，对应代码 `src/main/java/verymc/top/veryMcProto/mod/syncmatica/`；原版对照 `OriginImpl/syncmatica-LTS-26.2/`。§2 包结构全树与 §10 迁移记录 = 原版→Paper 的逐项权威。
> 相关文档：网络协议与 Exchange 状态机见 [syncmatica-protocol.md](syncmatica-protocol.md)；测试见 [syncmatica-testing.md](syncmatica-testing.md)；运维命令/配置见 [operations.md](operations.md)；全局框架见 [architecture.md](architecture.md)——syncmatica 与 Servux 共享同一套 `framework/network`。

---

## 0. 一句话定位

**Syncmatica 是一个「投影共享」协议 Mod**：让多个玩家在同一个服务端上**共享 Litematica 投影**——任何玩家上传一份 `.litematic` 到服务端，服务端作为**中央仓库**存储它，并广播给所有在线玩家；玩家可以下载、查看、并协同修改这份投影的放置位置（origin / 旋转 / 镜像）。

> 客户端仍是 **syncmatica 自己的 Fabric 客户端 Mod**（它注入 Litematica 的 GUI，在「Load Schematic」列表里显示服务端投影）。本移植在 Paper 服务端复刻 syncmatica 期待的**网络协议 + 中央仓库语义**。

**与 Servux 的关键区别**：Servux 是「服务端→客户端」的**单向数据广播**；syncmatica 是「客户端⇄服务端⇄客户端」的**双向、有状态、多玩家共享**协议。这决定了它**不走 Servux 的 DataProviderManager 推送模型**，而由独立的 `SyncmaticaModule` 装配（见 §3）。

---

## 1. 与 Servux 的本质差异（心智模型）

| 维度 | Servux | Syncmatica | 影响 |
|---|---|---|---|
| **服务端角色** | 数据采集器 + 单向广播者 | **投影文件中央仓库**（存储/中转/共享） | syncmatica 需要**文件 I/O + 持久化注册表**，Servux 基本无状态 |
| **通信模型** | Provider 事件驱动**推送**（`DataProviderManager`） | Exchange **请求-应答会话**（多步状态机） | syncmatica 独立 enable，不能套 provider 模板（见 §3.1） |
| **物理通道** | **5 条** `servux:*`（每功能一条 custom payload） | **1 条** `syncmatica:main`（C2S/S2C 共用） | 单通道 + 第一字段逻辑分派，包体结构是复合 `[Identifier][body]` |
| **逻辑消息** | 每通道内 `packetType` VarInt 区分 | **18 个 PacketType**（= 18 个逻辑 Identifier） | 包体 = `[逻辑通道 Identifier][body]` 复合结构（见 §5） |
| **大包分片** | `PacketSplitter` 透明流式重组（首包写总长，连续流） | **应用层 stop-and-wait 应答式**（SEND↔RECEIVED 逐片，每片带 UUID） | **不复用** `PacketSplitter`，在 Upload/DownloadExchange 内自写分片 |
| **服务端状态** | 几乎无状态（除 schematic） | **强状态**：placement 表 + 文件存储 + 配额 + 修改锁 + 握手进度 | 需 `placements.json` 持久化 + 每玩家会话管理 |
| **配置/数据** | `servux.json`（开关） | `syncmatica-config.json`（quota/debug）+ `placements.json`（投影表）+ `syncmatics/*.litematic`（文件） | 三套落盘，原子写策略见 §6 / §10.5 |
| **装配入口** | `onRegister(DataProviderManager)` 走框架 provider 模型 | `SyncmaticaModule.enable(plugin)` 独立装配 | 见 §3.2 |

> ⚠️ **最大心智陷阱**：不要把 syncmatica 当成「又一个 Servux provider」。它是一个**有状态的多玩家协同协议**，核心复杂度在 Exchange 会话状态机与文件中转，而非数据采集。

---

## 2. 实际包结构（mod/syncmatica/ 全树 · 含逐文件迁移标注 · 唯一权威）

> 全树 **43 个 Java 文件**；`←` 注释 = 架构角色 + **迁移处置**（照抄/修正/差异——自原版逐文件对照得出）。

```
mod/syncmatica/
├── SyncmaticaContext.java         ← ★ 领域根容器：聚合 files/comMan/synMan/quota/debug + 配置 + 生命周期（迁移：去客户端分支；protocolEnabled 软禁用）
├── SyncmaticaReference.java       ← 常量（MOD_ID / NETWORK_ID / 文件名 / MOD_VERSION=插件版本）
├── Feature.java                   ← 9 个 Feature 枚举（协议特性协商，见 §5.4）（照抄）
│
├── app/
│   └── SyncmaticaModule.java      ← ★ 装配入口（单例）：enable/disable + 双保险握手 + 玩家监听（Bukkit 事件 listener，替代原版 5 个服务端 Mixin）
│
├── network/                       ← 【传输层】单通道 handler + 18 PacketType
│   ├── SyncmaticaHandler.java     ← 实现 IPluginServerPlayHandler：解析 [Identifier][body] → onPacket（encodeWithSplitter 空）
│   └── PacketType.java            ← 18 个逻辑消息类型（照抄；⚠️ request_download / mesage 拼写照抄原版）
│
├── communication/                 ← 【会话层】Exchange 多步请求-应答状态机
│   ├── CommunicationManager.java       ← 抽象基类：onPacket 派发 + metadata/position 编解码 + exchange 调度
│   ├── ServerCommunicationManager.java ← ★ 服务端实现：onPlayerJoin/Leave + handle(4 类一次性请求) + handleExchange(广播) + tryStartHandshake + targets Map + suspendAll
│   ├── ExchangeTarget.java             ← 「一个连接」的抽象：持 Player + ongoingExchanges 列表 + FeatureSet + sendPacket（S2C 默认 NMS DiscardedPayload 直发）
│   ├── FeatureSet.java                 ← Feature 集合序列化（\n 分隔字符串）（照抄）
│   ├── MessageType.java                ← SUCCESS/INFO/WARNING/ERROR（MESSAGE 包用）（照抄）
│   └── exchange/
│       ├── Exchange.java               ← 接口（照抄）
│       ├── AbstractExchange.java       ← ★ 状态机基类：finished/success 状态 + checkUUID peek（照抄）
│       ├── VersionHandshakeServer.java ← 进服握手：版本 → Feature 协商 → CONFIRM_USER 全量下发（照抄）
│       ├── FeatureExchange.java        ← Feature 协商抽象基类（FEATURE_REQUEST / FEATURE）（照抄）
│       ├── DownloadExchange.java       ← 接收文件方：REQUEST → 收 SEND 分片 → 回 RECEIVED → 校验 MD5（照抄；上传配额检查在此）
│       ├── UploadExchange.java         ← 发送文件方：收 REQUEST/RECEIVED → 发 SEND 分片 → 发 FINISHED（照抄；16KB stop-and-wait）
│       └── ModifyExchangeServer.java   ← 修改锁：MODIFY_REQUEST → ACCEPT 占锁 → MODIFY_FINISH 应用 + 广播（照抄）
│
├── data/                          ← 【数据层】placement 注册表 + 文件存储 + 持久化
│   ├── ServerPlacement.java          ← ★ 核心数据模型：一个投影放置的全部元数据（纯 JSON 序列化）（照抄；去 matList 死字段 + correctMetadataFromPeek）
│   ├── SyncmaticManager.java         ← placement 注册表：Map<UUID,ServerPlacement> + loadServer/saveServer（照抄；saveServer 原子写 .new→.bak→current；loadServer peek 修正）
│   ├── IFileStorage.java / FileStorage.java  ← 投影文件存储：<hash>.litematic 内容寻址 + LocalLitematicState 判定（照抄；去 isServer 分支；恒 hash 命名）
│   ├── LocalLitematicState.java      ← 4 态枚举：NO_LOCAL / DESYNC / DOWNLOADING / PRESENT（照抄）
│   ├── ServerPosition.java           ← origin 坐标（BlockPos + dimensionId）（照抄）
│   └── litematica/                   ← 投影文件 peek（照抄自原版 litematica/schematic/；SchematicMetadata/SchematicSchema/Schema/FileType）
│
├── extended_core/                 ← CORE_EX feature 的扩展数据（照抄）
│   ├── PlayerIdentifier.java            ← 玩家标识（uuid + bufferedName），MISSING_PLAYER 占位
│   ├── PlayerIdentifierProvider.java    ← uuid→PlayerIdentifier 归一化 map（内存级）
│   ├── SubRegionData.java               ← 子区域修改集合（isModified + Map<name, Modification>）
│   └── SubRegionPlacementModification.java ← 单个子区域覆盖（name/position/rotation/mirror）
│
├── service/                       ← 【服务层】可配置的横切服务
│   ├── IService / AbstractService / IServiceConfiguration / JsonConfiguration  ← 抽象 + Gson 配置回调（照抄，含 hadError 机制）
│   ├── QuotaService.java          ← 每玩家上传字节配额（DownloadExchange 查询）（照抄；progress 不持久化；senderName 解耦）
│   └── DebugService.java          ← 收发包计数日志（照抄 + 修正原版 doPackageLogging→doPacketLogging 拼写/默认值 bug；与 SyncmaticaDebug 联动持久化）
│
├── command/
│   └── SyncmaticaCommand.java     ← /syncmatica 命令树（load_all/load_each + status/save/reload/enable/disable/debug）
│
└── util/
    ├── SyncmaticaUtil.java        ← MD5→UUID（createChecksum）+ litematicPeek + backupAndReplace（原子写）+ 文件名消毒
    ├── StringTools.java           ← 字符串工具
    ├── SyncmaticaLog.java         ← JUL 日志门面（替代原版 SLF4J）
    └── SyncmaticaDebug.java       ← 分类调试日志（6 分类：lifecycle/handshake/network/packet/exchange/data；与 config 的 "debugLog" 段持久化）
```

> 与 Servux 共享 `framework/network`（`ChannelManager` / `ServerPlayHandler` / `IPluginServerPlayHandler` / `FriendlyByteBufs`）、`framework/debug/DebugSystem`、`framework/nms/Nms`、`framework/util`。**未新增 framework 类**。

**核心四层**：

1. **传输层** `network/` —— 单通道 handler 收发（复用 framework，见 §8）
2. **会话层** `communication/` —— Exchange 状态机 + CommunicationManager 派发（**syncmatica 独有**）
3. **数据层** `data/` + `extended_core/` —— placement 模型 + 文件存储 + 持久化（纯 Java + Gson）
4. **生命周期层** `app/SyncmaticaModule` + `SyncmaticaContext` —— Bukkit 事件驱动装配（替代原版 5 个 Mixin）

---

## 3. 装配生命周期（SyncmaticaModule + Context）

### 3.1 为什么不走 framework DataProviderManager

Servux 每个 provider 实现 `IDataProvider` 并经 `onRegister(DataProviderManager)` 接入推送模型；syncmatica 的通信本质是**跨多包、有状态、双向的 Exchange 会话**，与 provider「事件→推送一帧」模型根本不同。故 `SyncmaticaModule` 不实现 `framework.ModModule`，而是由主类 `VeryMcProto.onEnable/onDisable` 直接调用其 `enable(plugin)` / `disable()`，独立完成：构造 Context → 注册通道 → 注册玩家监听。命令注册留在主类（需 `getCommand`）。

### 3.2 SyncmaticaModule 单例装配

```
VeryMcProto.onEnable
  └─ SyncmaticaModule.enable(plugin)
       ├─ 构造组件
       │    ├─ FileStorage(litematicFolder = <dataFolder>/syncmatics)
       │    ├─ ServerCommunicationManager
       │    └─ SyncmaticManager
       ├─ new SyncmaticaContext(plugin, files, comMan, synMan, litematicFolder, configFolder=dataFolder)
       │    └─ 注入反向引用 + 构造 QuotaService/DebugService/PlayerIdentifierProvider
       │       + FileStorage.setDownloadStateProvider(comMan.getDownloadState)  ← 函数式注入
       │       + loadConfiguration()  ← 读 syncmatica-config.json
       ├─ context.startup()
       │    └─ quota.startup() / debugService.startup() / synMan.startup()
       │       └─ synMan.startup() → loadServer()  ← 读 placements.json 恢复投影表（见 §6.2）
       ├─ handler = new SyncmaticaHandler(context)
       ├─ ServerPlayHandler.getInstance().registerServerPlayHandler(handler)  ← 注册 syncmatica:main 通道
       └─ Bukkit 注册 PlayerJoin / PlayerQuit / PlayerRegisterChannel 监听

VeryMcProto.onDisable
  └─ SyncmaticaModule.disable()
       ├─ context.shutdown()
       │    └─ saveConfiguration() / quota.shutdown() / debugService.shutdown() / synMan.shutdown()
       │       └─ synMan.shutdown() → saveServer()  ← 原子写 placements.json（见 §6.2）
       └─ ServerPlayHandler.unregisterServerPlayHandler(handler)
```

### 3.3 双保险握手机制（命门）⭐

`ServerCommunicationManager.onPlayerJoin` **不**立即发起握手——`PlayerJoinEvent` 时客户端 codec 尚未就绪，立即推 `REGISTER_VERSION` 会握手失败并残留 exchange。握手由两条路径触发：

1. **主路径**：`PlayerJoinEvent` → `getOrCreateTarget` + `onPlayerJoin`（仅登记）→ `runTaskLater(40t)`（2s，等 configuration phase 完成、客户端 codec 就绪）→ **`tryStartHandshake`**（幂等：已在 `broadcastTargets` 或已有进行中 `VersionHandshakeServer` 则跳过）。
2. **兜底/主力**：`PlayerRegisterChannelEvent`（channel == `syncmatica:main`）→ 立即 `tryStartHandshake`（实测 Fabric 客户端进服后**会**触发本事件，但时序可晚于 40t 主路径探针 0~2s+——主路径被守卫拦截时，本路径是声明晚到场景的握手发起主力）。

`tryStartHandshake` 幂等，两路径安全共存。`/syncmatica enable`（恢复协议）对在线玩家走同一 40t 延迟握手路径（`reconnectOnlinePlayers`）。

> 🔧 **disable→enable 陈旧握手卡死修复（2026-10，勿随模板回退）**：`suspendAll` 旧实现只遍历 `broadcastTargets`（VHS 握手**成功后**才入集）且 `close(false)` 后不移出 `getExchanges()`（唯一移出点 `notifyClose` 不被触发）——disable 瞬间握手中途的玩家被漏扫，其 VHS 以未 finished 残留；enable 后 `reconnectOnlinePlayers` → `getOrCreateTarget`（computeIfAbsent 复用同一 target）→ `tryStartHandshake` 的 `instanceof VersionHandshakeServer` 跳过检查不辨死活 → 永久跳过，该玩家 syncmatica 不可用直到重进服。修复 = suspendAll 遍历 `targets.values()` 全集 + 每个 exchange close 后显式移出列表（刻意不走 `notifyClose`——保留不触发 `handleExchange` 副作用的静默语义）。**收敛无需客户端配合**：上游 `ClientCommunicationManager.handle` 对无主 `REGISTER_VERSION` 自带重握手路径（clear + 新建 VHC），VHC `checkPacket` 无条件匹配、FEATURE 轮重入幂等（时序详见 [syncmatica-protocol.md](syncmatica-protocol.md) §3.4）。

---

## 4. Context 容器模型

`SyncmaticaContext.java` 是 syncmatica 的「领域根」，聚合所有子系统并管理配置与生命周期。

| 字段 | 类型 | 职责 |
|---|---|---|
| `plugin` | `Plugin` | Bukkit 插件句柄（调度任务用） |
| `files` | `IFileStorage` | 投影文件存储（`FileStorage`） |
| `comMan` | `CommunicationManager` | 通信管理器（`ServerCommunicationManager`） |
| `synMan` | `SyncmaticManager` | placement 注册表 |
| `quota` | `QuotaService` | 上传配额 |
| `debugService` | `DebugService` | 收发包日志 |
| `playerIdentifierProvider` | `PlayerIdentifierProvider` | 玩家标识归一化 |
| `fs` | `FeatureSet` | **自身**声明的特性集（懒加载，默认 = 全部 Feature） |
| `protocolEnabled` | `volatile boolean` | 协议软禁用标志（`/syncmatica enable\|disable`，不持久化） |
| `litematicFolder` / `configFolder` | `Path` | 投影文件目录 / 配置目录 |

**关键方法**：

- `startup()` / `shutdown()`：编排各 service 启停 + `synMan` 载入/保存 + 配置读写。
- `getFeatureSet()`：懒加载全集——配合 `MOD_VERSION`=插件版本（带 `-b` 后缀永不命中 `FeatureSet.fromVersionString` 版本正则）触发 FEATURE 交换，使双方用全集 FeatureSet（MODIFY/DISPLAY_NAME/CORE_EX/VERSION 全开）。
- `checkPartnerVersion(version)`：**仅拒绝 `"0.0.1"`**，其余全放行——版本兼容性实际靠 FeatureSet 协商。
- `loadConfiguration()` / `saveConfiguration()`：读/写 `syncmatica-config.json`，按 service 的 `configKey`（`quota` / `debug`）分段装配；额外保存 `SyncmaticaDebug` 状态到顶层 `"debugLog"` 子对象。
- `suspendProtocol()` / `resumeProtocol()`：软禁用——`suspendAll()` 关闭**全部在线玩家**的进行中 exchange（2026-10 修复语义见 §3.3）+ 清空 `broadcastTargets`，但**通道仍注册**（避免 Paper 踢人）；`resumeProtocol()` 仅翻标志，在线玩家重握手由 `reconnectOnlinePlayers` 负责。

**Paper 适配**：去掉原版 `Reference.isClient()/isIntegratedServer()/isOpenToLan()` 分支（恒 dedicated server）；`FileStorage` 与 `IService` 不再 `setContext`，改用函数式注入（`setDownloadStateProvider`）解耦。

---

## 5. Exchange 会话层（核心设计）

### 5.1 两层架构

```
┌─────────────────────────────────────────────────────────────────┐
│ 传输层 network/SyncmaticaHandler                                  │
│   物理通道 syncmatica:main（1 条，C2S + S2C 共用）                │
│   收：receivePlayPayload → readIdentifier 得 PacketType           │
│        → 读 body → comMan.onPacket(target, type, body)            │
│   发：ExchangeTarget.sendPacket(type, buf)                       │
│        → 构造 [Identifier][body] → NMS DiscardedPayload 直发       │
└──────────────────────────────┬──────────────────────────────────┘
                               │  (source, PacketType, FriendlyByteBuf)
┌──────────────────────────────▼──────────────────────────────────┐
│ 会话层 communication/                                            │
│   CommunicationManager.onPacket(source, type, buf)：             │
│     ① 遍历 source.getExchanges()，找 checkPacket 命中的 → handle  │
│     ② 无人认领 → 抽象 handle(source, type, buf)（一次性请求）      │
│     ③ handle 后若 exchange.isFinished() → notifyClose → handleExchange │
└─────────────────────────────────────────────────────────────────┘
```

- **传输层**只负责把 `byte[]` 解析为 `(PacketType, body)` 路由给 `CommunicationManager`（`SyncmaticaHandler`）。
- **会话层**把包派发给两类处理者：
  - **Exchange（多步会话）**：挂在 `ExchangeTarget.ongoingExchanges` 列表上，每个 Exchange 用 `checkPacket` 判断「这个包归不归我」（通常匹配包头 UUID），命中则 `handle` 推进状态机。
  - **一次性请求**：不被任何 exchange 认领的包，走 `ServerCommunicationManager.handle(...)`，处理 `REQUEST_LITEMATIC` / `REGISTER_METADATA` / `REMOVE_SYNCMATIC` / `MODIFY_REQUEST` 四类。

### 5.2 AbstractExchange 状态机基类

- **状态**：`finished` / `success`（boolean）。
- `close(notifyPartner)`：先置 `finished=true;success=false` 再 `onClose()`，`notifyPartner=true` 则 `sendCancelPacket()`。
- `succeed()`：置 `finished=true;success=true` 再 `onClose()`（**成功路径不发 cancel**）。
- `checkUUID(buf, targetId)`：**peek 式**——记录 readerIndex → 读 UUID → 回退（不消费），供 `checkPacket` 无副作用判定；`handle` 第一行通常 `readUUID()` 真正消费。

**6 个具体 Exchange**（服务端实际实现的；原版另有 4 个客户端 exchange 不移植，但服务端须正确回应它们发出的包）：

| Exchange | 一句话职责 |
|---|---|
| **VersionHandshakeServer** | 进服握手：发版本 → 协商 Feature → 发 CONFIRM_USER（全量 placement metadata） |
| **FeatureExchange**（抽象） | Feature 协商（FEATURE_REQUEST / FEATURE），VersionHandshakeServer 继承它 |
| **DownloadExchange** | 服务端作接收方：客户端分享时收 SEND 分片 → 回 RECEIVED → 校验 MD5→UUID == hash |
| **UploadExchange** | 服务端作发送方：客户端请求下载时收 REQUEST/RECEIVED → 发 SEND 分片 → 发 FINISHED |
| **ModifyExchangeServer** | 修改锁：MODIFY_REQUEST → ACCEPT 占锁 → MODIFY_FINISH 应用 position + 广播 |

> 每个 Exchange 的**完整状态机表 + 每个包的字段读写顺序**见 [syncmatica-protocol.md](syncmatica-protocol.md) §5。

### 5.3 ExchangeTarget —— 「一个连接」的桥接

每个玩家一个，生命周期 = 玩家连接。

| 字段 | 职责 |
|---|---|
| `player` / `playerId` | Bukkit `Player` + UUID（替代原版 NMS `ServerGamePacketListenerImpl`，无需 Mixin） |
| `persistentName` | `player.getUniqueId().toString()`（QuotaService 按此记账） |
| `features` | 握手后填的 `FeatureSet` |
| `ongoingExchanges` | `List<Exchange>`（按注册顺序，路由时遍历） |

`sendPacket(type, buf, context)`：构造 `[Identifier][body]` 复合包体 → 默认走 **NMS `DiscardedPayload` 直发**（见 §8 命门）。

### 5.4 Feature 协商

9 个 `Feature`：`CORE` / `FEATURE` / `MODIFY` / `MESSAGE` / `QUOTA` / `DEBUG` / `CORE_EX` / `VERSION` / `DISPLAY_NAME`。

其中 **4 个直接影响协议字段编码**（决定 metadata/position 包哪些可选字段）：

| Feature | 影响的字段 |
|---|---|
| `DISPLAY_NAME` | metadata 增 `writeUtf(displayName)` |
| `CORE_EX` | metadata 增 owner/lastModifiedBy；position 增 subregion 列表；modify 增 lastModifiedBy |
| `VERSION` | metadata 增 `writeVarInt(litematicVersion)` + `writeVarInt(dataVersion)` |
| `MODIFY` | 决定修改走 `MODIFY_REQUEST/ACCEPT/FINISH` 还是退化到 `REMOVE_SYNCMATIC` |

`FeatureSet` 序列化 = `\n` 分隔的 Feature 名字符串（**不是位图**）。本服务端声明全集，握手后双方用全集编码。完整握手流程见 [syncmatica-protocol.md](syncmatica-protocol.md) §3。

---

## 6. 数据模型

### 6.1 ServerPlacement（核心数据模型）

一个投影放置的全部元数据。**纯 JSON 序列化，无 NBT**。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `UUID`（final） | placement 唯一标识（随机生成或客户端指定） |
| `hashValue` | `UUID`（final） | **文件内容**的 MD5→type-3 UUID（内容寻址键，非文件名） |
| `file` / `fileName` | `Path` / `String` | 原始文件路径 / 基础文件名 |
| `displayName` | `String` | litematic 内的 Display Name（`DISPLAY_NAME` feature） |
| `owner` / `lastModifiedBy` | `PlayerIdentifier` | 分享者 / 最后修改者（构造时 `lastModifiedBy = owner`） |
| `origin` | `ServerPosition` | 放置原点（BlockPos + dimension） |
| `rotation` / `mirror` | `Rotation` / `Mirror` | 旋转 / 镜像（传输用 ordinal） |
| `subRegionData` | `SubRegionData` | 子区域覆盖（`CORE_EX`） |
| `litematicVersion` / `dataVersion` | `int` | 版本元数据（`VERSION`，默认 -1） |
| `dirty` | `boolean` | 自愈标志（加载时若修正了字段则置位 → 触发回写） |

> **已删除原版 `matList` 字段**（原版死代码，见 §10.7 降级矩阵）。

`toJson()` / `fromJson()` 字段顺序与 `isDirty()` 自愈逻辑见 [syncmatica-protocol.md](syncmatica-protocol.md) §4。**hash 算法** = `SyncmaticaUtil.createChecksum`（MD5 → `UUID.nameUUIDFromBytes`）—— 客户端会校验，**不能改算法**。服务端加载时 `correctMetadataFromPeek(litematicFolder)` 用文件 peek 修正 displayName/version。

### 6.2 SyncmaticManager（注册表 + 持久化）

- 内部 `Map<UUID, ServerPlacement> schematics`（**key = placement.id**）。
- `addPlacement` / `removePlacement` / `getPlacement(id)` / `hasPlacementHash(hash)`（后者 O(n) 遍历）。
- **每次变更即落盘**：`updateServerPlacement()` → 立即 `saveServer()`。
- `startup()` → `loadServer()`：读 `placements.json`，含 `isDirty()` 自愈回写。
- `shutdown()` → `saveServer()`：**原子写**——写 `placements.json.new` → `SyncmaticaUtil.backupAndReplace(.bak, current, .new)`。

### 6.3 FileStorage（投影文件存储）

- 存储目录 = `<dataFolder>/syncmatics/`；服务端命名 = `<hashValue>.litematic`（内容寻址，**天然去重**）。
- `getLocalState(placement)`：5 步判定 → 4 态枚举 `LocalLitematicState`。
- `createLocalLitematic(placement)`：建空文件供下载写入（非临时文件）。
- `hashCompare`：MD5 校验 + `(placement → lastModified)` 缓存避免重复算 hash。
- `downloadStateProvider`：由 `CommunicationManager.getDownloadState` 函数式注入。

### 6.4 PlayerIdentifier 体系（CORE_EX）

- `PlayerIdentifier`：`uuid` + `bufferedPlayerName`，`MISSING_PLAYER` 占位。**无 equals/hashCode**（对象身份相等）——故 `PlayerIdentifierProvider.createOrGet` 的归一化是关键（同一 uuid 必须返回同一实例，否则 `owner.equals(lastModifiedBy)` 永远 false）。
- `PlayerIdentifierProvider`：内存 `Map<UUID, PlayerIdentifier>`（**不持久化**，随 placement JSON 落盘 uuid+name，重启重建）。

---

## 7. 服务层（service/）

横切的可配置服务，统一经 `IService` / `AbstractService` / `IServiceConfiguration` / `JsonConfiguration` 抽象（**完整照抄**，保留扩展性），配置落盘到 `syncmatica-config.json` 各自的 `configKey` 段：

| 服务 | configKey | 职责 |
|---|---|---|
| **QuotaService** | `quota` | 每玩家上传字节配额；DownloadExchange 每收一片 `bytesSent += size` 并查 `isOverQuota`，超限则 close + ERROR；`progressQuota` 在成功后累计。不持久化配额计数（重启清零） |
| **DebugService** | `debug` | 收发包计数日志（`logSendPacket` / `logReceivePacket`），与 `SyncmaticaDebug` 分类日志联动；后者状态额外持久化到 config 顶层 `"debugLog"` 段 |

`JsonConfiguration`（Gson 回调式配置实现）：读时 try/catch 任何异常并设 `wasError=true`（供 `Context.loadConfigurationForService` 判断是否需回写默认值）。

> **DebugService 两处原版 bug 修正**：字段默认值 `doPacketLogging = true` → **统一 false**（生产不应默认开 INFO 级包日志）；配置 key `"doPackageLogging"`（拼写错误，Package）→ **统一 `"doPacketLogging"`**（字段名 = key）。调用点：`logReceivePacket(type)` 在 `CommunicationManager.onPacket` 开头；`logSendPacket(type, id)` 在 `ExchangeTarget.sendPacket`。**注意**：DebugService（包级 INFO 日志）与 `SyncmaticaDebug`（mod 独立的分类调试系统，master + 6 分类，`/syncmatica debug cat` 控制）**是两套独立机制**。

---

## 8. framework 复用边界

| framework 类 | syncmatica 用法 | 说明 |
|---|---|---|
| `framework.network.ChannelManager` | ✅ **直接用** | 注册 `syncmatica:main` 一条通道（incoming + outgoing），plugin messaging fallback 路径 |
| `framework.network.ServerPlayHandler` | ✅ **直接用** | handler 注册表，`SyncmaticaHandler` 经它注册到 `syncmatica:main` |
| `framework.network.FriendlyByteBufs` | ✅ **直接用** | `byte[]` ↔ `FriendlyByteBuf` 桥接（`readableBytes` / `buffer` / `extractAndRelease`） |
| `framework.network.IPluginServerPlayHandler` | ✅ **实现** | `SyncmaticaHandler` 实现它，`receivePlayPayload` 里做 PacketType 派发；`encodeWithSplitter` 空实现（不用 PacketSplitter） |
| `framework.nms.Nms` / `framework.reflect` | ✅ **直接用** | `Nms.toNms(player)` 拿 `ServerPlayer` 直发 `ClientboundCustomPayloadPacket` |
| `framework.network.PacketSplitter` | ❌ **不用** | servux 透明流式重组；syncmatica 是 exchange 级 **stop-and-wait 应答式分片**——模型不同，分片在 UploadExchange/DownloadExchange 内自写 |
| `framework.dataproviders.DataProviderManager` / `IDataProvider` | ❌ **不用** | provider 推送模型不适合 exchange 会话（见 §3.1） |

> 🔑 **物理包体是复合结构** `[逻辑通道 Identifier][body]`（对应原版 `SyncmaticaPacket.toPacket` = `writeIdentifier(channel) + writeBytes(body)`）。收发照抄原版，**切勿**像 Servux 那样把 `byte[]` 直接当 body——这是 syncmatica 网络层的最大坑点。
>
> 🔑 **S2C 路径命门（实测）**：plugin messaging（`sendPluginMessage`）的 S2C wire 格式，纯 Fabric 客户端（syncmatica）**收不到**——客户端零响应、零 C2S 回包。而 NMS `new ClientboundCustomPayloadPacket(new DiscardedPayload(syncmatica:main, bytes))` 经 `ServerPlayer.connection.send` 投递，已被 JEI 配方同步路径验证 fabric 客户端可解码（机制见 [architecture.md](architecture.md) §3.2）。故 S2C 默认走 NMS 直发（`S2C_VIA_NMS=true`）；plugin messaging 仅作诊断 fallback（`/syncmatica debug s2c msg` 切换）。`DiscardedPayload` 本身即 vanilla payload 类型，不会被强转拒绝（自定义 Payload record 会 ClassCastException）。

---

## 9. 术语表

| 术语 | 含义 |
|---|---|
| **Exchange** | syncmatica 的「一个跨多包、有明确目标的双端通信」抽象。一个实例只代表通信的**本端半边**，挂在一个 `ExchangeTarget` 上 |
| **ExchangeTarget** | 「一个连接」的抽象（服务端 = 一个玩家），持 `ongoingExchanges` 列表 + `FeatureSet` + `sendPacket` |
| **PacketType** | 18 个逻辑消息类型之一（如 `REGISTER_METADATA`），每个对应一个 `Identifier` |
| **placement / ServerPlacement** | 服务端存储的一个投影放置（含文件 hash + origin + 旋转镜像 + owner 等） |
| **hash** | 投影文件内容的 MD5 → type-3 UUID，用作内容寻址键与去重 |
| **Feature / FeatureSet** | 协议特性（9 个枚举）与其集合；握手时协商，决定 metadata 编码哪些可选字段 |
| **broadcastTargets** | 已完成握手的 `ExchangeTarget` 集合，placement 变更时广播给全部 |
| **modifier / modifyState** | 当前正在修改某 placement 的 Exchange（占锁），保证同一时刻只有一人能改 |
| **CONFIRM_USER** | 握手成功包，服务端在握手末尾下发**全量 placement metadata** |

---

## 10. Mixin→Bukkit 迁移与上游差异（as-built）

### 10.1 Mixin 总清单与处置

syncmatica 原版共 **19 个 Mixin**（`mixin/` 9 + `litematica_mixin/` 10），无 AccessWidener。服务端真正需要替换的仅 **5 个**，全部用 Bukkit 事件 / Bukkit 命令 / plugin messaging listener / 写死 dedicated 替代——**无一处「改服务端行为」类降级**（对比 Servux 的 EasyPlace / UpdateSuppression / 潜影盒堆叠：不改任何原版服务端逻辑，纯协议层 + 文件 I/O，不需要 PacketEvents / 反射改 NMS 行为）：

| Mixin | 处置 |
|---|---|
| `MixinMinecraftServer` | ✅ Bukkit 事件 + 写死 dedicated（§3.2 装配） |
| `MixinPlayerManager` | ✅ 合并进 `PlayerJoinEvent`（原版 `placeNewPlayer` 发的 `REGISTER_VERSION` 与 exchange 握手版本包重复，只保留 exchange 的那次） |
| `MixinServerPlayNetworkHandler` | ✅ `PluginMessageListener` + `Map<UUID, ExchangeTarget>`（原版注释「FAPI networking 太慢注册 receiver，所以直接 Mixin 截包」——Paper 的 Messenger 通道映射即原版 custom payload，单 handler 收全部 `syncmatica:main` 包） |
| `MixinServerCommonNetworkHandler` | ✅ 冗余未实现（原版防 Mojang 上移 handleCustomPayload 的兜底；单 listener 已覆盖） |
| `MixinCommandManager` | ✅ plugin.yml + Bukkit `CommandExecutor`（原版双注入合并——dedicated 恒成立） |
| `MixinIntegratedServer` / 3 个 Client Mixin / `litematica_mixin/` 10 个 | ⛔ 不移植（单机/纯客户端/GUI） |

### 10.2 生命周期迁移总表

| 原版 Mixin 时机 | 原版触发动作 | Paper 已落地（实现代码） |
|---|---|---|
| `MinecraftServer.runServer` @INVOKE(initServer) | 设 dedicated 标志 | 写死（`SyncmaticaContext.isServer()=true`） |
| `MinecraftServer.runServer` @INVOKE(buildServerStatus) | initServer + Context.startup | 主类 `onEnable` → `SyncmaticaModule.enable`（§3.2） |
| `MinecraftServer.stopServer` @TAIL | shutdown + saveServer | 主类 `onDisable` → `SyncmaticaModule.disable` |
| `ServerGamePacketListenerImpl.<init>` @TAIL | onPlayerJoin（握手） | `PlayerJoinEvent`（仅登记 target；握手延后，§3.3） |
| `ServerGamePacketListenerImpl.onDisconnect` @HEAD | onPlayerLeave | `PlayerQuitEvent` |
| `ServerGamePacketListenerImpl.handleCustomPayload` @HEAD | 包路由 | `SyncmaticaHandler.receivePlayPayload`（plugin messaging listener） |
| `Commands.<init>` | 注册命令 | plugin.yml + Bukkit `CommandExecutor` |

### 10.3 网络层迁移

见 §8（通道注册 / 复合包体 / S2C 路径 / ExchangeTarget 改造为持 `Player` + `Map<UUID, ExchangeTarget>` 管理，无 IServerPlay mixin 接口）。文件分片自写 stop-and-wait（16KB，每片确认），**不复用 PacketSplitter**（§8 表）。

### 10.4 持久化路径映射

原版路径依赖 Fabric `GAME_ROOT` / `worldFolder`；Paper 用 `getDataFolder()`（常量在 `SyncmaticaReference`）：

| 用途 | 原版路径 | Paper 路径 |
|---|---|---|
| 投影文件存储 | `<服务端根>/syncmatics/<hash>.litematic` | `plugins/VeryMcProto/syncmatics/<hash>.litematic` |
| placement 注册表 | `<世界目录>/syncmatica/placements.json` | `plugins/VeryMcProto/placements.json`（+ `.bak`/`.new` 原子写照搬） |
| 服务端配置（quota + debug） | `<世界目录>/syncmatica/config.json` | `plugins/VeryMcProto/syncmatica-config.json` |

**关键约束**：`<hash>.litematic` 命名规则保留（hash 是跨端内容寻址键）；`placements.json` JSON 字段顺序与含义逐字段一致；`loadServer` 读 JSON 后调 `ServerPlacement.correctMetadataFromPeek`（原版在 `fromJson` 内 `isServer()` 分支，抽出），dirty 则立即 re-save。**多世界考量**：原版按「当前世界目录」存配置，切世界换路径；Paper 用 `getDataFolder()` 跨世界共享同一份投影库——符合「服务端中央仓库」语义。

### 10.5 权限与命令

权限：原版 `PermsWrap`（fabric-permissions-api 薄封装）→ `player.hasPermission(node)`；plugin.yml 5 节点见 [operations.md](operations.md) §2。命令：原版只有 `load`（`load_all` + `load_each`），Paper 扩展 status/save/reload/enable/disable/debug 运维子命令（树见 [operations.md](operations.md) §1.4）。

`loadEach` 核心逻辑：`SyncmaticaUtil.litematicPeek(path)` 读 `SchematicMetadata` + `SchematicSchema`（不入 NMS）→ `new ServerPlacement(randomUUID, path, filename, owner)` → `move(玩家当前位置, NONE, NONE)` → `setMetadata/setSchema` → `comms.addPlacement`（注册 + 广播）。`updateSyncmaticDir` 仅列未加载文件（文件名去掉 `.litematic` 后须为合法 UUID 且 `hasPlacementHash` 为 false）。

### 10.6 服务层与依赖

服务层见 §7。依赖变更：`fabric-networking-api-v1` / `fabric-permissions-api` / `fabric-resource-loader-v1` / `modmenu` 全删除；`litematica`/`malilib` 不需要（服务端代码不引用其类——`data/litematica/` peek 类是自带轻量解析）。**syncmatica 移植不引入任何新外部依赖**，`build.gradle.kts` 未为 syncmatica 改动。

### 10.7 降级矩阵（已落地）

| 功能 | 处置 | 原因 / 影响 |
|---|---|---|
| **`material/`（材料配送）** | ⛔ 不移植，字段一起去掉 | 死代码：原版仅 `ServerPlacement.matList` 字段持有，无 exchange / 无 PacketType / 无命令 / 无持久化引用。已删字段 + `getMaterialList`/`setMaterialList` 方法 |
| **`RedirectFileStorage`** | ⛔ 不移植 | 客户端装饰器（外部文件重定向免拷贝）；服务端纯 `FileStorage` 即可 |
| **`extended_core/`（CORE_EX）** | ✅ 照抄 | owner / lastModifiedBy / subregion 共享，是协议字段（影响 metadata 编码），必须实现 |
| **`litematica/schematic/`（peek）** | ✅ 照抄（落地为 `data/litematica/`） | `SchematicMetadata`/`SchematicSchema`/`Schema`/`FileType` 是自带轻量 litematic 解析，命令 `load` 需要。Schema 版本表整表照抄上游（升级时随上游增删，见 [operations.md](operations.md) §6.4） |
| **版本协商（VERSION feature）** | ✅ 照抄 | `MOD_VERSION`=插件版本触发 FEATURE 交换使双方用全集 FeatureSet |
| **客户端 exchange（3 个）** | ⛔ 不实现类，但服务端 `handle` 须回应其包 | 见 [syncmatica-protocol.md](syncmatica-protocol.md) §5.5 |
| **`Reference.isClient/isIntegratedServer/isOpenToLan` 分支** | ⚠️ 简化删除 | Paper 恒 dedicated server |
| **`/syncmatica load` 以外的原版命令** | — | 原版本就没有；扩展为运维子命令 |

---

## 11. 关键决策速查

| 项 | 实际实现 | 详锚 |
|---|---|---|
| 装配方式 | `SyncmaticaModule.enable(plugin)` 单例装配（不走 DataProviderManager） | §3.1–3.2 |
| 握手机制 | 双保险：onPlayerJoin 延迟 40t 主路径 + onPlayerRegisterChannel 兜底（幂等 tryStartHandshake） | §3.3 |
| 物理包体 | `[Identifier][body]` 复合结构 | §8 |
| 通道注册 | 单通道 `syncmatica:main`，复用 framework ServerPlayHandler | §3.2 |
| ExchangeTarget | 持 `Player` + `Map<UUID, ExchangeTarget>` 管理 | §5.3 |
| S2C 路径 | 默认 NMS `DiscardedPayload` 直发；`/syncmatica debug s2c msg` 切回对比 | §8 |
| 文件分片 | 自写 stop-and-wait（`BUFFER_SIZE=16384`，每片确认），不复用 PacketSplitter | §8 |
| hash 算法 | MD5 → `UUID.nameUUIDFromBytes`（type-3 UUID），内容寻址 + 去重 | §6.1 |
| Feature 协商 | MOD_VERSION=插件版本触发 FEATURE 交换；FeatureSet 序列化为 `\n` 分隔名 | §5.4 |
| 持久化路径 | `syncmatics/<hash>.litematic` + `placements.json` + `syncmatica-config.json`，原子写 | §10.4 |
| 权限/命令 | 5 节点 + `/syncmatica` 树 | [operations.md](operations.md) §1.4/§2 |
| 调试体系 | `SyncmaticaDebug`（master + 分类正交，持久化）+ `DebugService`（逐包 INFO）两套独立 | §7 |
| 配额 | QuotaService（默认关闭，限额 40MB，超额中断下载） | §7 |
| material 死代码 | `matList` 字段 + `SyncmaticaMaterialList` 全删 | §10.7 |
| 依赖 | `build.gradle.kts` 不为 syncmatica 改动 | §10.6 |

---

## 12. 迁移命门清单（维护必读）

1. **物理包体 `[Identifier][body]`**：收发照抄 `SyncmaticaPacket.fromPacket/toPacket`，勿当 Servux 处理（§8）。
2. **ExchangeTarget 用 `Map<UUID, ExchangeTarget>` 管理**，无 IServerPlay mixin（§5.3）。
3. **文件分片自写**，不复用 `PacketSplitter`（§8）。
4. **S2C 默认 NMS `DiscardedPayload` 直发**——plugin messaging 的 wire 纯 Fabric 客户端收不到（实测，§8）。
5. **握手双保险**：`PlayerJoinEvent` 延迟 40t 主路径 + `PlayerRegisterChannelEvent` 兜底；`tryStartHandshake` 幂等（§3.3）。
6. **路径全改 `getDataFolder()`**，但 `<hash>.litematic` 命名 + `placements.json` 字段 + 原子写照搬（§10.4）。
7. **material 死代码连字段一起去掉**（§10.7）。
8. **DebugService 修正 2 处拼写/默认值 bug**；注意与 `SyncmaticaDebug` 是两套独立机制（§7）。
9. **CORE_EX / VERSION / DISPLAY_NAME / MODIFY 四个 feature 必须实现**（影响字段编码，§5.4）。
10. **checkPacket peek / handle 消费两段式**必须复刻（多 exchange 路由正确性，见 [syncmatica-protocol.md](syncmatica-protocol.md) §5/§9）。
11. **不引入新依赖**，`build.gradle.kts` 不改（§10.6）。

---

## 13. 已知局限（工单登记）

> 按文档约定（[index.md](index.md)），各域文档「已知局限」节是行为级工单的唯一登记处；升级窗口消费的观察项统一在 [operations.md](operations.md) §6.9。

| 项 | 说明 | 状态 |
|---|---|---|
| **`SyncmaticaUtil.readNbtFromFile` gzip bomb 面** | `NbtIo.readCompressed(..., NbtAccounter.unlimitedHeap())`——输入为客户端上传落盘的 `.litematic`（启动 `correctMetadataFromPeek` / op 命令触发 peek），理论 gzip bomb 面（不达 `LitematicaSchematic`，仅 metadata peek）；宜换 `FILE_MAX_BYTES`（512MB）式配额 | 独立工单 |
| **僵尸 VHS 周期重试器** | enable 后正常对局中客户端停止应答的残留 `VersionHandshakeServer`——§3.3 修复覆盖 disable→enable 场景，不覆盖对局中断连场景（`onPlayerLeave` 移除 target 才重建） | 独立工单 |

---

> **相关**：[syncmatica-protocol.md](syncmatica-protocol.md)（协议字段/状态机/分片/hash）· [syncmatica-testing.md](syncmatica-testing.md)（客户端兼容测试/排错）· [architecture.md](architecture.md)（全局框架）· [operations.md](operations.md)（命令/配置/排错）· [index.md](index.md)
