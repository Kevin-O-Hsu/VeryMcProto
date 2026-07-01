# 20 · Syncmatica 架构总览（已实现）

> **状态**：syncmatica（投影共享）已在 Paper 1.21.11 上完整实现，所有协议路径（握手 / 分享 / 下载 / 修改 / 删除 / 持久化 / 多玩家广播 / 软禁用）均经实测。
> **本文描述实际架构**，对应代码 `src/main/java/verymc/top/veryMcProto/mod/syncmatica/`；原版对照 `OriginImpl/syncmatica-LTS-1.21.11/`（下文简写 ORIGIN/）。
> 相关文档：网络协议与 Exchange 状态机见 [21](21-syncmatica-protocol.md)；Mixin 分析与迁移方案见 [22](22-syncmatica-mixin-migration.md)；实施计划见 [23](23-syncmatica-implementation-plan.md)；测试见 [24](24-syncmatica-testing-guide.md)。同步阅读 [../CLAUDE.md](../CLAUDE.md) 与本项目 Servux 移植文档（[01](01-servux-architecture.md)～[11](11-schematic-migration-plan.md)）——syncmatica 与 Servux 共享同一套 `framework/network` 网络框架。

---

## 0. 一句话定位

**Syncmatica 是一个「投影共享」协议 Mod**：让多个玩家在同一个服务端上**共享 Litematica 投影**——任何玩家上传一份 `.litematic` 到服务端，服务端作为**中央仓库**存储它，并广播给所有在线玩家；玩家可以下载、查看、并协同修改这份投影的放置位置（origin / 旋转 / 镜像）。

> 客户端仍是 **syncmatica 自己的 Fabric 客户端 Mod**（它注入 Litematica 的 GUI，在「Load Schematic」列表里显示服务端投影）。本移植在 Paper 服务端复刻 syncmatica 期待的**网络协议 + 中央仓库语义**，使「syncmatica 客户端 + Paper 服务端」等价于「syncmatica 客户端 + syncmatica 服务端」。

**与 Servux 的关键区别**：Servux 是「服务端→客户端」的**单向数据广播**（推 TPS / 实体 / 结构框给 masa 客户端）；syncmatica 是「客户端⇄服务端⇄客户端」的**双向、有状态、多玩家共享**协议。这一差异决定了 syncmatica 的架构与 Servux 截然不同（见 §1），也决定了它**不走 Servux 的 DataProviderManager 推送模型**，而由独立的 `SyncmaticaModule` 装配（见 §3）。

---

## 1. 与 Servux 的本质差异（心智模型）

读者已熟悉本项目 Servux 移植，故用一张对比表点出 syncmatica 的独特性——**理解这些差异是理解后续所有架构决策的出发点**：

| 维度 | Servux | Syncmatica | 影响 |
|---|---|---|---|
| **服务端角色** | 数据采集器 + 单向广播者 | **投影文件中央仓库**（存储/中转/共享） | syncmatica 需要**文件 I/O + 持久化注册表**，Servux 基本无状态 |
| **通信模型** | Provider 事件驱动**推送**（`DataProviderManager`） | Exchange **请求-应答会话**（多步状态机） | syncmatica 独立 enable，不能套 provider 模板（见 §3） |
| **物理通道** | **5 条** `servux:*`（每功能一条 custom payload） | **1 条** `syncmatica:main`（C2S/S2C 共用） | 单通道 + 第一字段逻辑分派，包体结构是复合 `[Identifier][body]` |
| **逻辑消息** | 每通道内 `packetType` VarInt 区分 | **18 个 PacketType**（= 18 个逻辑 Identifier） | syncmatica 包体 = `[逻辑通道 Identifier][body]` 复合结构（见 §5） |
| **大包分片** | `PacketSplitter` 透明流式重组（首包写总长，连续流） | **应用层 stop-and-wait 应答式**（SEND↔RECEIVED 逐片，每片带 UUID） | **不复用** Servux 的 `PacketSplitter`，在 Upload/DownloadExchange 内自写分片 |
| **服务端状态** | 几乎无状态（除 schematic） | **强状态**：placement 表 + 文件存储 + 配额 + 修改锁 + 握手进度 | 需 `placements.json` 持久化 + 每玩家会话管理 |
| **配置/数据** | `servux.json`（开关） | `syncmatica-config.json`（quota/debug）+ `placements.json`（投影表）+ `syncmatics/*.litematic`（文件） | 三套落盘，原子写策略见 §6 / [22](22-syncmatica-mixin-migration.md) |
| **装配入口** | `onRegister(DataProviderManager)` 走框架 provider 模型 | `SyncmaticaModule.enable(plugin)` 独立装配 | 见 §3 |

> ⚠️ **最大心智陷阱**：不要把 syncmatica 当成「又一个 Servux provider」。它是一个**有状态的多玩家协同协议**，核心复杂度在 Exchange 会话状态机与文件中转，而非数据采集。

---

## 2. 实际包结构（mod/syncmatica/ 全树）

```
mod/syncmatica/
├── SyncmaticaContext.java         ← ★ 领域根容器：聚合 files/comMan/synMan/quota/debug + 配置 + 生命周期
├── SyncmaticaReference.java       ← 常量（MOD_ID / NETWORK_ID / 文件名 / MOD_VERSION="1.0.0"）
├── Feature.java                   ← 9 个 Feature 枚举（协议特性协商，见 §5.3）
│
├── app/
│   └── SyncmaticaModule.java      ← ★ 装配入口（单例）：enable/disable + 双保险握手 + 玩家监听
│
├── network/                       ← 【传输层】单通道 handler + 18 PacketType
│   ├── SyncmaticaHandler.java     ← 实现 IPluginServerPlayHandler：解析 [Identifier][body] → onPacket
│   └── PacketType.java            ← 18 个逻辑消息类型（含 request_download / mesage 拼写陷阱）
│
├── communication/                 ← 【会话层】Exchange 多步请求-应答状态机
│   ├── CommunicationManager.java       ← 抽象基类：onPacket 派发 + metadata/position 编解码 + exchange 调度
│   ├── ServerCommunicationManager.java ← ★ 服务端实现：onPlayerJoin/Leave + handle(4 类一次性请求) + handleExchange(广播)
│   ├── ExchangeTarget.java             ← 「一个连接」的抽象：持 Player + ongoingExchanges 列表 + FeatureSet + sendPacket（NMS DiscardedPayload 直发）
│   ├── FeatureSet.java                 ← Feature 集合序列化（\n 分隔字符串）
│   ├── MessageType.java                ← SUCCESS/INFO/WARNING/ERROR（MESSAGE 包用）
│   └── exchange/
│       ├── Exchange.java               ← 接口
│       ├── AbstractExchange.java       ← ★ 状态机基类：finished/success 状态 + checkUUID peek
│       ├── VersionHandshakeServer.java ← 进服握手：版本 → Feature 协商 → CONFIRM_USER 全量下发
│       ├── FeatureExchange.java        ← Feature 协商抽象基类（FEATURE_REQUEST / FEATURE）
│       ├── DownloadExchange.java       ← 接收文件方：REQUEST → 收 SEND 分片 → 回 RECEIVED → 校验 MD5
│       ├── UploadExchange.java         ← 发送文件方：收 REQUEST/RECEIVED → 发 SEND 分片 → 发 FINISHED
│       └── ModifyExchangeServer.java   ← 修改锁：MODIFY_REQUEST → ACCEPT 占锁 → MODIFY_FINISH 应用 + 广播
│
├── data/                          ← 【数据层】placement 注册表 + 文件存储 + 持久化
│   ├── ServerPlacement.java          ← ★ 核心数据模型：一个投影放置的全部元数据（纯 JSON 序列化）
│   ├── SyncmaticManager.java         ← placement 注册表：Map<UUID,ServerPlacement> + loadServer/saveServer（原子写）
│   ├── IFileStorage.java / FileStorage.java  ← 投影文件存储：<hash>.litematic 内容寻址 + LocalLitematicState 判定
│   ├── LocalLitematicState.java      ← 4 态枚举：NO_LOCAL / DESYNC / DOWNLOADING / PRESENT
│   ├── ServerPosition.java           ← origin 坐标（BlockPos + dimensionId）
│   └── litematica/                   ← 投影文件 peek（SchematicMetadata / SchematicSchema / Schema / FileType）
│
├── extended_core/                 ← CORE_EX feature 的扩展数据
│   ├── PlayerIdentifier.java            ← 玩家标识（uuid + bufferedName），MISSING_PLAYER 占位
│   ├── PlayerIdentifierProvider.java    ← uuid→PlayerIdentifier 归一化 map（内存级）
│   ├── SubRegionData.java               ← 子区域修改集合（isModified + Map<name, Modification>）
│   └── SubRegionPlacementModification.java ← 单个子区域覆盖（name/position/rotation/mirror）
│
├── service/                       ← 【服务层】可配置的横切服务
│   ├── IService / AbstractService / IServiceConfiguration / JsonConfiguration  ← 抽象 + Gson 配置回调
│   ├── QuotaService.java          ← 每玩家上传字节配额（DownloadExchange 查询）
│   └── DebugService.java          ← 收发包计数日志（与 SyncmaticaDebug 联动持久化）
│
├── command/
│   └── SyncmaticaCommand.java     ← /syncmatica 命令树（load / save / reload / enable / disable / debug ...）
│
└── util/
    ├── SyncmaticaUtil.java        ← MD5→UUID（createChecksum）+ litematicPeek + backupAndReplace（原子写）+ 文件名消毒
    ├── StringTools.java           ← 字符串工具
    ├── SyncmaticaLog.java         ← JUL 日志门面（替代原版 SLF4J）
    └── SyncmaticaDebug.java       ← 分类调试日志（与 config 的 "debugLog" 段持久化）
```

**核心四层**：

1. **传输层** `network/` —— 单通道 handler 收发（复用 framework `ServerPlayHandler` / `FriendlyByteBufs`，见 §8）
2. **会话层** `communication/` —— Exchange 状态机 + CommunicationManager 派发（**syncmatica 独有**）
3. **数据层** `data/` + `extended_core/` —— placement 模型 + 文件存储 + 持久化（纯 Java + Gson）
4. **生命周期层** `app/SyncmaticaModule` + `SyncmaticaContext` —— Bukkit 事件驱动装配（替代原版 5 个 Mixin）

---

## 3. 装配生命周期（SyncmaticaModule + Context）

### 3.1 为什么不走 framework DataProviderManager

Servux 每个 provider 实现 `IDataProvider` 并经 `onRegister(DataProviderManager)` 接入推送模型；syncmatica 的通信本质是**跨多包、有状态、双向的 Exchange 会话**，与 provider「事件→推送一帧」模型根本不同。故 `SyncmaticaModule` 不实现 `framework.ModModule`，而是由主类 `VeryMcProto.onEnable/onDisable` 直接调用其 `enable(plugin)` / `disable()`，独立完成：构造 Context → 注册通道 → 注册玩家监听。命令注册留在主类（需 `getCommand`）。

### 3.2 SyncmaticaModule 单例装配

`app/SyncmaticaModule.java`（`getInstance()` 单例）：

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

### 3.3 双保险握手机制（命门）

`ServerCommunicationManager.onPlayerJoin` **不**立即发起握手——`PlayerJoinEvent` 时客户端 codec 尚未就绪，立即推 `REGISTER_VERSION` 会握手失败并残留 exchange。握手由两条路径触发（见 `SyncmaticaModule.java` 注释）：

- **主路径**：`PlayerJoinEvent` → `runTaskLater(40t)`（2s，等 configuration phase 完成、客户端 codec 就绪）→ `tryStartHandshake(target)`。
- **兜底/加速**：`PlayerRegisterChannelEvent`（旧式 `MC|Register`，1.21 Fabric 客户端通常**不**触发）→ `tryStartHandshake(target)`。

`tryStartHandshake` **幂等**：已在 `broadcastTargets`（握手已完成）或已有进行中的 `VersionHandshakeServer` 则跳过。`/syncmatica enable` 恢复协议后由 `reconnectOnlinePlayers()` 对每个在线玩家延迟 40t 重新握手。

> 与 Servux `HudDataProvider` 的握手策略一致（onJoin 延迟 + RegisterChannel 兜底），是 1.21.x Fabric 客户端兼容的通用范式。

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
| `protocolEnabled` | `volatile boolean` | 协议软禁用标志（`/syncmatica enable|disable`，不持久化） |
| `litematicFolder` / `configFolder` | `Path` | 投影文件目录 / 配置目录 |

**关键方法**：

- `startup()` / `shutdown()`：编排各 service 启停 + `synMan` 载入/保存 + 配置读写。
- `getFeatureSet()`：懒加载 `Arrays.asList(Feature.values())`——声明全集，配合 `MOD_VERSION="1.0.0"`（非 `"0.1.x"`）触发 FEATURE 交换，使双方用全集编码（MODIFY/DISPLAY_NAME/CORE_EX/VERSION 全开）。
- `checkPartnerVersion(version)`：**仅拒绝 `"0.0.1"`**，其余全放行——版本兼容性实际靠 FeatureSet 协商。
- `loadConfiguration()` / `saveConfiguration()`：读/写 `syncmatica-config.json`，按 service 的 `configKey`（`quota` / `debug`）分段装配；额外保存 `SyncmaticaDebug` 状态到顶层 `"debugLog"` 子对象。
- `suspendProtocol()` / `resumeProtocol()`：软禁用——`suspendAll()` 关闭进行中 exchange + 清空 `broadcastTargets`，但**通道仍注册**（避免 Paper 踢人）；`resumeProtocol()` 仅翻标志，在线玩家重握手由 `SyncmaticaModule.reconnectOnlinePlayers` 负责。

**Paper 适配**：去掉原版 `Reference.isClient()/isIntegratedServer()/isOpenToLan()` 分支（恒 dedicated server）；`FileStorage` 与 `IService` 不再 `setContext`，改用函数式注入（`setDownloadStateProvider`）解耦。

---

## 5. Exchange 会话层（核心设计）

这是 syncmatica 与 Servux 最大的架构差异，也是协议的主要复杂度所在。

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

`communication/exchange/AbstractExchange.java`：

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

> 每个 Exchange 的**完整状态机表 + 每个包的字段读写顺序**见 [21](21-syncmatica-protocol.md) §Exchange 状态机。

### 5.3 ExchangeTarget —— 「一个连接」的桥接

`communication/ExchangeTarget.java`，每个玩家一个，生命周期 = 玩家连接。

| 字段 | 职责 |
|---|---|
| `player` / `playerId` | Bukkit `Player` + UUID（替代原版 NMS `ServerGamePacketListenerImpl`，无需 Mixin） |
| `persistentName` | `player.getUniqueId().toString()`（QuotaService 按此记账） |
| `features` | 握手后填的 `FeatureSet` |
| `ongoingExchanges` | `List<Exchange>`（按注册顺序，路由时遍历） |

`sendPacket(type, buf, context)`：构造 `[Identifier][body]` 复合包体 → 默认走 **NMS `DiscardedPayload` 直发**（`S2C_VIA_NMS=true`，同 JEI Recipe Bridge 路径）。

> **S2C 路径命门（实测）**：plugin messaging（`sendPluginMessage`）的 S2C wire 格式，纯 Fabric 客户端（syncmatica）**收不到**——客户端零响应、零 C2S 回包。而 NMS `new ClientboundCustomPayloadPacket(new DiscardedPayload(syncmatica:main, bytes))` 经 `ServerPlayer.connection.send` 投递，已被 JEI Recipe Bridge 验证 fabric 客户端可解码。故 S2C 默认走 NMS 直发；plugin messaging 仅作诊断 fallback（`/syncmatica debug s2c msg` 切换）。

### 5.4 Feature 协商

9 个 `Feature`（`Feature.java`）：`CORE` / `FEATURE` / `MODIFY` / `MESSAGE` / `QUOTA` / `DEBUG` / `CORE_EX` / `VERSION` / `DISPLAY_NAME`。

其中 **4 个直接影响协议字段编码**（决定 metadata/position 包哪些可选字段）：

| Feature | 影响的字段 |
|---|---|
| `DISPLAY_NAME` | metadata 增 `writeUtf(displayName)` |
| `CORE_EX` | metadata 增 owner/lastModifiedBy；position 增 subregion 列表；modify 增 lastModifiedBy |
| `VERSION` | metadata 增 `writeVarInt(litematicVersion)` + `writeVarInt(dataVersion)` |
| `MODIFY` | 决定修改走 `MODIFY_REQUEST/ACCEPT/FINISH` 还是退化到 `REMOVE_SYNCMATIC` |

`FeatureSet` 序列化 = `\n` 分隔的 Feature 名字符串（**不是位图**）。本服务端声明全集，握手后双方用全集编码。完整握手流程见 [21](21-syncmatica-protocol.md) §Feature 协商。

---

## 6. 数据模型

### 6.1 ServerPlacement（核心数据模型）

`data/ServerPlacement.java` —— 一个投影放置的全部元数据。**纯 JSON 序列化，无 NBT**。

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

> **已删除原版 `matList` 字段**（原版死代码，无 exchange/协议/命令/持久化引用，见 [22](22-syncmatica-mixin-migration.md) §material）。

`toJson()` / `fromJson()` 字段顺序与 `isDirty()` 自愈逻辑见 [21](21-syncmatica-protocol.md) §数据序列化。**hash 算法** = `SyncmaticaUtil.createChecksum`（MD5 → `UUID.nameUUIDFromBytes`）—— 客户端会校验，**不能改算法**。服务端加载时 `correctMetadataFromPeek(litematicFolder)` 用文件 peek 修正 displayName/version（原版 `fromJson` 内 `context.isServer()` 分支，Paper 抽出解耦 Context）。

### 6.2 SyncmaticManager（注册表 + 持久化）

`data/SyncmaticManager.java`：

- 内部 `Map<UUID, ServerPlacement> schematics`（**key = placement.id**）。
- `addPlacement` / `removePlacement` / `getPlacement(id)` / `hasPlacementHash(hash)`（后者 O(n) 遍历）。
- **每次变更即落盘**：`updateServerPlacement()` → 若 server 则立即 `saveServer()`。
- `startup()` → `loadServer()`：读 `<configFolder>/placements.json`，含 `isDirty()` 自愈回写。
- `shutdown()` → `saveServer()`：**原子写**——写 `placements.json.new` → `SyncmaticaUtil.backupAndReplace(.bak, current, .new)`（current → .bak，.new → current）。

### 6.3 FileStorage（投影文件存储）

`data/FileStorage.java`：

- 存储目录 = `<dataFolder>/syncmatics/`。
- **服务端命名 = `<hashValue>.litematic`**（按 hash 内容寻址，**天然去重**）。
- `getLocalState(placement)`：5 步判定 → 4 态枚举 `LocalLitematicState`（`NO_LOCAL_LITEMATIC` / `LOCAL_LITEMATIC_DESYNC` / `DOWNLOADING_LITEMATIC` / `LOCAL_LITEMATIC_PRESENT`）。
- `createLocalLitematic(placement)`：建空文件供下载写入（非临时文件）。
- `hashCompare`：MD5 校验 + `(placement → lastModified)` 缓存避免重复算 hash。
- `downloadStateProvider`：由 `CommunicationManager.getDownloadState` 函数式注入（替代原版 `context.getCommunicationManager().getDownloadState`，解耦 Context 启动顺序）。

### 6.4 PlayerIdentifier 体系（CORE_EX）

- `PlayerIdentifier`：`uuid` + `bufferedPlayerName`，`MISSING_PLAYER` 占位。**无 equals/hashCode**（用对象身份相等）——故 `PlayerIdentifierProvider.createOrGet` 的归一化是关键（同一 uuid 必须返回同一实例，否则 `owner.equals(lastModifiedBy)` 永远 false）。
- `PlayerIdentifierProvider`：内存 `Map<UUID, PlayerIdentifier>`（**不持久化**，随 placement JSON 落盘 uuid+name，重启重建）。

---

## 7. 服务层（service/）

横切的可配置服务，统一经 `IService` / `AbstractService` / `IServiceConfiguration` / `JsonConfiguration` 抽象，配置落盘到 `syncmatica-config.json` 各自的 `configKey` 段：

| 服务 | configKey | 职责 |
|---|---|---|
| **QuotaService** | `quota` | 每玩家上传字节配额；DownloadExchange 每收一片 `bytesSent += size` 并查 `isOverQuota`，超限则 close + ERROR；`progressQuota` 在成功后累计。不持久化配额计数（重启清零） |
| **DebugService** | `debug` | 收发包计数日志（`logSendPacket` / `logReceivePacket`），与 `SyncmaticaDebug` 分类日志联动；后者状态额外持久化到 config 顶层 `"debugLog"` 段 |

> 原版 DebugService 有拼写/默认值 bug，本移植已修正（详见 [22](22-syncmatica-mixin-migration.md)）。

---

## 8. framework 复用边界

syncmatica 与 Servux 共享 `framework/network`（servux 移植沉淀的通用网络层）。复用边界：

| framework 类 | syncmatica 用法 | 说明 |
|---|---|---|
| `framework.network.ChannelManager` | ✅ **直接用** | 注册 `syncmatica:main` 一条通道（incoming + outgoing），plugin messaging fallback 路径 |
| `framework.network.ServerPlayHandler` | ✅ **直接用** | handler 注册表，`SyncmaticaHandler` 经它注册到 `syncmatica:main` |
| `framework.network.FriendlyByteBufs` | ✅ **直接用** | `byte[]` ↔ `FriendlyByteBuf` 桥接（`readableBytes` / `buffer` / `extractAndRelease`） |
| `framework.network.IPluginServerPlayHandler` | ✅ **实现** | `SyncmaticaHandler` 实现它，`receivePlayPayload` 里做 PacketType 派发；`encodeWithSplitter` 空实现（不用 PacketSplitter） |
| `framework.nms.Nms` / `framework.reflect` | ✅ **直接用** | `Nms.toNms(player)` 拿 `ServerPlayer` 直发 `ClientboundCustomPayloadPacket` |
| `framework.network.PacketSplitter` | ❌ **不用** | servux 透明流式重组（首包写总长 VarInt + 连续流）；syncmatica 是 exchange 级 **stop-and-wait 应答式分片**（SEND↔RECEIVED 逐片 + UUID 匹配）——模型不同，分片在 UploadExchange/DownloadExchange 内自写 |
| `framework.dataproviders.DataProviderManager` / `IDataProvider` | ❌ **不用** | provider 推送模型不适合 exchange 会话；syncmatica 由 `SyncmaticaModule` 独立 enable（见 §3.1） |

> 🔑 **物理包体是复合结构** `[逻辑通道 Identifier][body]`（对应原版 `SyncmaticaPacket.toPacket` = `writeIdentifier(channel) + writeBytes(body)`）。`SyncmaticaHandler.receivePlayPayload` 照此解析：先 `readIdentifier()` 得 PacketType，再读 body。**这与 Servux（每通道 byte[] 直接是 body）不同**，是 syncmatica 网络层的最大坑点。S2C 端 `ExchangeTarget.sendPacket` 同样构造此复合结构再 NMS 直发。

---

## 9. 术语表

| 术语 | 含义 |
|---|---|
| **Exchange** | syncmatica 的「一个跨多包、有明确目标的双端通信」抽象。一个实例只代表通信的**本端半边**，挂在一个 `ExchangeTarget` 上 |
| **ExchangeTarget** | 「一个连接」的抽象（服务端 = 一个玩家），持 `ongoingExchanges` 列表 + `FeatureSet` + `sendPacket` |
| **PacketType** | 18 个逻辑消息类型之一（如 `REGISTER_METADATA`），每个对应一个 `Identifier`（`syncmatica:register_metadata` 等） |
| **placement / ServerPlacement** | 服务端存储的一个投影放置（含文件 hash + origin + 旋转镜像 + owner 等） |
| **hash** | 投影文件内容的 MD5 → type-3 UUID（`UUID.nameUUIDFromBytes(md5)`），用作内容寻址键与去重 |
| **Feature / FeatureSet** | 协议特性（9 个枚举）与其集合；握手时协商，决定 metadata 编码哪些可选字段 |
| **broadcastTargets** | 已完成握手的 `ExchangeTarget` 集合（`ServerCommunicationManager`），placement 变更时广播给全部 |
| **modifier / modifyState** | 当前正在修改某 placement 的 Exchange（占锁），保证同一时刻只有一人能改 |
| **CONFIRM_USER** | 握手成功包，服务端在握手末尾下发**全量 placement metadata**，客户端据此初始化投影列表 |

---

> **下一步阅读**：
> - 协议字段细节、Exchange 状态机、分片协议 → [21-syncmatica-protocol.md](21-syncmatica-protocol.md)
> - Mixin 逐项 Bukkit 映射、降级矩阵、持久化映射 → [22-syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md)
> - 实施历史与阶段划分 → [23-syncmatica-implementation-plan.md](23-syncmatica-implementation-plan.md)
> - 客户端测试步骤 → [24-syncmatica-testing-guide.md](24-syncmatica-testing-guide.md)
