# 20 · Syncmatica 原版架构总览

> 原版根目录：`OriginImpl/syncmatica-LTS-1.21.11/src/main/java/ch/endte/syncmatica/`
> 相关：网络协议与 Exchange 状态机见 [21](21-syncmatica-protocol.md)；Mixin 分析与迁移方案见 [22](22-syncmatica-mixin-migration.md)；实施计划见 [23](23-syncmatica-implementation-plan.md)；测试见 [24](24-syncmatica-testing-guide.md)。
> **同步阅读**：本项目已完成的 Servux 移植文档（[01](01-servux-architecture.md)～[11](11-schematic-migration-plan.md)）—— syncmatica 与 Servux 共享同一套 `framework/network` 网络框架，许多概念可对照。

---

## 0. 一句话定位

**Syncmatica 是一个「投影共享」协议 Mod**：让多个玩家在同一个服务端上**共享 Litematica 投影**——任何玩家上传一份 `.litematic` 到服务端，服务端作为**中央仓库**存储它，并广播给所有在线玩家；玩家可以下载、查看、并协同修改这份投影的放置位置（origin / 旋转 / 镜像）。

> 客户端仍是 **syncmatica 自己的 Fabric 客户端 Mod**（它注入 Litematica 的 GUI，在「Load Schematic」列表里显示服务端投影）。我们要在 Paper 服务端复刻 syncmatica 期待的**网络协议 + 中央仓库语义**，使「syncmatica 客户端 + Paper 服务端」等价于「syncmatica 客户端 + syncmatica 服务端」。

**与 Servux 的关键区别**：Servux 是「服务端→客户端」的**单向数据广播**（推 TPS / 实体 / 结构框给 masa 客户端）；syncmatica 是「客户端⇄服务端⇄客户端」的**双向、有状态、多玩家共享**协议。这一差异决定了 syncmatica 的架构与 Servux 截然不同（见 §1）。

---

## 1. 与 Servux 的本质差异（先建立心智模型）

读者已熟悉本项目 Servux 移植，故先用一张对比表点出 syncmatica 的独特性——**理解这些差异是后续所有移植决策的出发点**：

| 维度 | Servux | Syncmatica | 移植影响 |
|---|---|---|---|
| **服务端角色** | 数据采集器 + 单向广播者 | **投影文件中央仓库**（存储/中转/共享） | syncmatica 需要**文件 I/O + 持久化注册表**，Servux 基本无状态 |
| **通信模型** | Provider 事件驱动**推送** | Exchange **请求-应答会话**（多步状态机） | syncmatica 要实现 exchange 状态机，不能套 provider 模板 |
| **物理通道** | **5 条** `servux:*`（每功能一条 custom payload） | **1 条** `syncmatica:main`（C2S/S2C 共用） | 单通道 + 第一字段逻辑分派，复用框架时要注意包体结构差异 |
| **逻辑消息** | 每通道内 `packetType` VarInt 区分 | **18 个 PacketType**（= 18 个逻辑 Identifier） | syncmatica 包体 = `[逻辑通道 Identifier][body]` 复合结构 |
| **大包分片** | `PacketSplitter` 透明流式重组（首包写总长，连续流） | **应用层 stop-and-wait 应答式**（SEND↔RECEIVED 逐片，每片带 UUID） | **不能复用** Servux 的 `PacketSplitter`，须自写 exchange 级分片 |
| **服务端状态** | 几乎无状态（除 schematic） | **强状态**：placement 表 + 文件存储 + 配额 + 修改锁 + 握手进度 | 需 `placements.json` 持久化 + 每玩家会话管理 |
| **配置/数据** | `servux.json`（开关） | `config.json`（quota/debug）+ `placements.json`（投影表）+ `syncmatics/*.litematic`（文件） | 三套落盘，路径与原子写策略见 [22](22-syncmatica-mixin-migration.md) §持久化映射 |
| **Mixin 数量** | 26 + 2 AW | 9 通用（5 服务端 + 4 客户端）+ 10 `litematica_mixin`（纯客户端 GUI） | 服务端真正要替换的仅 **5 个**，比 Servux 少 |
| **客户端依赖** | masa 全家桶（MiniHUD/Litematica/Tweakeroo） | **syncmatica 客户端**（注入 Litematica GUI）+ Litematica | 测试时客户端要装 syncmatica + litematica 两个 mod |

> ⚠️ **最大心智陷阱**：不要把 syncmatica 当成「又一个 Servux provider」。它是一个**有状态的多玩家协同协议**，核心复杂度在 Exchange 会话状态机与文件中转，而非数据采集。

---

## 2. 顶层结构（包与职责）

```
ch.endte.syncmatica/
├── ModInit.java                ← ModInitializer 入口（onInitialize → preInit 注册 Payload）
├── Syncmatica.java             ← 静态门面：preInit / initServer / initClient / shutdown + 通道 ID 常量
├── Reference.java              ← 常量（MOD_ID / GAME_ROOT / CONFIG_ROOT / 环境标志）
├── Context.java                ← ★ 核心容器：聚合 fileStorage/comMan/synMan/quota/debug + 配置加载 + 生命周期
├── Feature.java                ← 9 个 Feature 枚举（协议特性协商，见 §5.3 / [21](21-syncmatica-protocol.md)）
│
├── network/                    ← 【传输层】单通道 Payload 包装 + handler 收发
│   ├── SyncmaticaPacket.java       ← Payload record：包体 = [Identifier 逻辑通道][body bytes]
│   ├── PacketType.java             ← 18 个逻辑 PacketType（含 request_download/mesage 拼写陷阱）
│   ├── handler/ServerPlayHandler   ← 服务端收发（receiveSyncPayload / encodeSyncData / sendSyncPacket）
│   ├── handler/ClientPlayHandler   ← 客户端收发（不移植）
│   └── actor/                      ← IServerPlay / IClientPlay 接口 + ActorClientPlayHandler（客户端，不移植）
│
├── communication/              ← 【会话层】Exchange 多步请求-应答状态机
│   ├── CommunicationManager.java   ← 抽象基类：onPacket 派发 + metadata/position 编解码 + exchange 调度
│   ├── ServerCommunicationManager  ← ★ 服务端实现：onPlayerJoin/Leave + handle(4 类一次性请求) + handleExchange(广播)
│   ├── ClientCommunicationManager  ← 客户端实现（不移植，但对照字段语义）
│   ├── ExchangeTarget.java         ← 「一个连接」的抽象：持 ongoingExchanges 列表 + FeatureSet + sendPacket
│   ├── FeatureSet.java             ← Feature 集合的序列化（\n 分隔字符串）+ 版本默认集查表
│   ├── MessageType.java            ← SUCCESS/INFO/WARNING/ERROR（MESSAGE 包用）
│   └── exchange/                   ← 8 个 Exchange 子类（见 §5.2 / [21](21-syncmatica-protocol.md)）
│       ├── Exchange.java / AbstractExchange.java     ← 接口 + 状态机基类
│       ├── VersionHandshakeServer / Client           ← 版本 + Feature 握手（服务端必须实现前者）
│       ├── FeatureExchange.java                      ← Feature 协商抽象基类
│       ├── DownloadExchange / UploadExchange         ← 文件传输对（stop-and-wait 分片，双向都用）
│       ├── ModifyExchangeServer / Client             ← 放置修改锁（服务端必须实现前者）
│       └── ShareLitematicExchange.java               ← 客户端分享（不移植，但服务端要回应它的包）
│
├── data/                       ← 【数据层】placement 注册表 + 文件存储 + 持久化
│   ├── ServerPlacement.java        ← ★ 核心数据模型：一个投影放置的全部元数据（JSON 序列化）
│   ├── SyncmaticManager.java       ← placement 注册表：add/remove/get + loadServer/saveServer（placements.json）
│   ├── IFileStorage / FileStorage  ← 投影文件存储：<hash>.litematic + LocalLitematicState 判定 + hashCompare
│   ├── RedirectFileStorage.java    ← 装饰器：外部文件重定向（客户端用，服务端可不移植）
│   ├── LocalLitematicState.java    ← 4 态枚举：NO_LOCAL / DESYNC / DOWNLOADING / PRESENT
│   └── ServerPosition.java         ← origin 坐标（BlockPos + dimensionId）
│
├── extended_core/              ← CORE_EX feature 的扩展数据
│   ├── PlayerIdentifier.java           ← 玩家标识（uuid + bufferedName），MISSING_PLAYER 占位
│   ├── PlayerIdentifierProvider.java   ← uuid→PlayerIdentifier 归一化 map（内存级）
│   ├── SubRegionData.java              ← 子区域修改集合（isModified + Map<name, Modification>）
│   └── SubRegionPlacementModification.java ← 单个子区域覆盖（name/position/rotation/mirror）
│
├── service/                    ← 【服务层】可配置的横切服务（配额 / 调试）
│   ├── IService / AbstractService / IServiceConfiguration / JsonConfiguration  ← 抽象 + Gson 配置回调
│   ├── QuotaService.java          ← 每玩家上传字节配额（仅 DownloadExchange 查询，不持久化）
│   └── DebugService.java          ← 收发包计数日志（有两个拼写/默认值 bug，移植需修正）
│
├── command/                    ← /syncmatica 命令（仅 load 一个子命令）
│   ├── SyncmaticaCommand.java     ← load_all / load_each：从磁盘注册 .litematic 为 placement
│   ├── IServerCommand.java        ← 命令注册接口（Paper 不需要此抽象）
│   └── PermsWrap.java             ← fabric-permissions-api 薄封装（→ Bukkit hasPermission）
│
├── material/                   ← ⛔ 死代码/半成品（不移植，见 §8）
│   ├── SyncmaticaMaterialList / SyncmaticaMaterialEntry / DeliveryPosition
│
├── mixin/                      ← 9 个核心 Mixin（见 §3 / [22](22-syncmatica-mixin-migration.md)）
├── litematica_mixin/           ← ⛔ 10 个纯客户端 GUI Mixin（不移植）
└── util/                       ← SyncmaticaUtil（MD5→UUID / 文件 peek / backupAndReplace）+ StringTools
```

**核心四层**（移植时按此分层实现）：

1. **传输层** `network/` —— 单通道 Payload 收发（复用本项目 `framework/network`，见 §9）
2. **会话层** `communication/` —— Exchange 状态机 + CommunicationManager 派发（**syncmatica 独有，全新实现**）
3. **数据层** `data/` + `extended_core/` —— placement 模型 + 文件存储 + 持久化（纯 Java + Gson，近乎照抄）
4. **生命周期层** `mixin/` + `Context` —— 5 个服务端 Mixin → Bukkit 事件替换

---

## 3. 启动与生命周期流程

> 入口链：`ModInit.onInitialize()`（`ModInit.java:12`）→ `Syncmatica.preInit()`（`Syncmatica.java:37`）

```
[Mod 加载期]
  ModInit.onInitialize()
    └─ Syncmatica.preInit()                                         // Syncmatica.java:37-43
       └─ PayloadTypeRegistry.playC2S/S2C 注册 SyncmaticaPacket.Payload
          (ID = Syncmatica.NETWORK_ID = "syncmatica:main")          // Syncmatica.java:29,40-41
   ── Paper 对应：ChannelManager.register(syncmatica:main) 一次性注册 incoming+outgoing

[服务器启动]  ← MixinMinecraftServer 钩 runServer（mixin/MixinMinecraftServer.java）
  @INVOKE(initServer)       → Reference.setDedicatedServer(true)                    // :24-32
  @INVOKE(buildServerStatus)→ Syncmatica.initServer(...).startup()                  // :52-58  ★核心
    │
    ├─ Syncmatica.initServer(comms, fileStorage, synMgr, isIntegrated, worldPath)   // Syncmatica.java:125-144
    │    └─ new Context(fs, comms, synMgr, isServer=true, SERVER_PATH, integrated, worldPath)  // Context.java:49-93
    │         ├─ fs.setContext / comMan.setContext / synMan.setContext(this)        // 注入反向引用
    │         ├─ quota = new QuotaService()                          // 仅 server（Context.java:67）
    │         ├─ playerIdentifierProvider = new PlayerIdentifierProvider(this)
    │         ├─ debugService = new DebugService()
    │         ├─ Files.createDirectory(litematicFolder = <worldPath>/syncmatics)   // Context.java:77-83
    │         └─ loadConfiguration()                                 // Context.java:255-322
    │              └─ 读 <worldPath>/syncmatica/config.json → quota.configure + debug.configure
    │
    ├─ SyncmaticaCommand.INSTANCE.updateSyncmaticDir(ctx)           // 扫 syncmatics/*.litematic peek 元数据
    │
    └─ ctx.startup()                                                // Context.java:150-156
         ├─ registerReceivers()  → 注册 C2S global receiver（syncmatica:main → ServerPlayHandler::receiveSyncPayload）
         ├─ startupServices()    → quota.startup() / debug.startup()（均空实现）
         ├─ isStarted = true
         └─ synMan.startup()     → SyncmaticManager.loadServer()    // 读 placements.json 恢复投影表（见 §6.2）

[命令注册]  ← MixinCommandManager 钩 Commands.<init> AFTER WhitelistCommand.register（mixin/MixinCommandManager.java:27）
  └─ SyncmaticaCommand.register(dispatcher, ...)   注册 /syncmatica load ...

[玩家进服握手]  ← MixinPlayerManager @placeNewPlayer TAIL + MixinServerPlayNetworkHandler @<init> TAIL（见 §7.1）

[玩家离服]  ← MixinServerPlayNetworkHandler @onDisconnect HEAD（见 §7.6）

[服务器关闭]  ← MixinMinecraftServer @stopServer TAIL
  └─ Syncmatica.shutdown()                                         // Syncmatica.java:76-87
       └─ ctx.shutdown()  → synMan.shutdown() → SyncmaticManager.saveServer()  // 原子写 placements.json
```

> **Paper 迁移要点**：`initServer+startup` 整段对应 `ServerLoadEvent`（或 `onEnable`）；`shutdown` 对应 `onDisable`；命令注册走 Paper Brigadier。5 个 Mixin 的逐项 Bukkit 事件映射见 [22](22-syncmatica-mixin-migration.md) §1。

---

## 4. Context —— 核心容器

`Context.java` 是 syncmatica 的「领域根」，聚合所有子系统并管理配置与生命周期。

| 字段（`Context.java:25-38`） | 类型 | 职责 |
|---|---|---|
| `files` | `IFileStorage` | 投影文件存储（服务端为 `FileStorage`） |
| `comMan` | `CommunicationManager` | 通信管理器（服务端为 `ServerCommunicationManager`） |
| `synMan` | `SyncmaticManager` | placement 注册表 |
| `quota` | `QuotaService` | 上传配额（**仅 server** 创建，client 为 null） |
| `debugService` | `DebugService` | 收发包日志 |
| `playerIdentifierProvider` | `PlayerIdentifierProvider` | 玩家标识归一化 |
| `fs`（FeatureSet） | `FeatureSet` | **自身**声明的特性集（懒加载，默认 = 全部 Feature，`Context.java:146-148`） |
| `server` / `integratedServer` | `boolean` | 上下文类型标志 |
| `litematicFolder` / `worldFolder` | `Path` | 投影文件目录 / 世界目录 |

**关键方法**：
- `startup()` / `shutdown()`（`:150-164`）：注册 receiver + 启停服务 + synMan 载入/保存。
- `getFeatureSet()`（`:119-124`）：懒加载 `Arrays.asList(Feature.values())`——**Paper 服务端建议照此声明全集**，使协议字段按最全格式编码（见 [21](21-syncmatica-protocol.md) §Feature 协商）。
- `checkPartnerVersion(version)`（`:213-215`）：**仅拒绝 `"0.0.1"`**，其余全放行——版本兼容性实际靠 FeatureSet 协商，不靠版本号。
- `loadConfiguration()`（`:255-322`）：读 `config.json` 的 `quota` / `debug` 两个子对象，按 service 的 `getConfigKey` 分段装配；缺失或出错则写默认值并标记回写。

> Paper 移植：`Context` 可近乎照抄为 POJO 容器，去掉 `Reference.isClient()/isIntegratedServer()` 分支（Paper 恒为 dedicated server），`registerReceivers()` 替换为 `ChannelManager.register(...)`。

---

## 5. 通信层：Exchange 会话模型（核心设计）

这是 syncmatica 与 Servux 最大的架构差异，也是移植的主要工作量所在。

### 5.1 两层架构

```
┌─────────────────────────────────────────────────────────────────┐
│ 传输层 network/                                                  │
│   物理通道 syncmatica:main（1 条，C2S + S2C 共用）                │
│   SyncmaticaPacket.Payload：包体 = [Identifier 逻辑通道][body]    │
│   收：receiveSyncPayload → decodeSyncData → onPacket(...)         │
│   发：ExchangeTarget.sendPacket(type, buf) → encodeSyncData       │
└──────────────────────────────┬──────────────────────────────────┘
                               │  (source, PacketType, FriendlyByteBuf)
┌──────────────────────────────▼──────────────────────────────────┐
│ 会话层 communication/                                            │
│   CommunicationManager.onPacket(source, type, buf)：             │
│     ① 遍历 source.getExchanges()，找 checkPacket 命中的 → handle  │
│     ② 无人认领 → 抽象 handle(source, type, buf)（一次性请求）      │
│     ③ handle 后若 exchange.isFinished() → notifyClose            │
└─────────────────────────────────────────────────────────────────┘
```

- **传输层**只负责「把 byte[] 路由到 CommunicationManager」（对应 Paper 的 `ProtocolChannel` + 一个 syncmatica handler）。
- **会话层**把包派发给两类处理者：
  - **Exchange（多步会话）**：挂在 `ExchangeTarget.ongoingExchanges` 列表上，每个 Exchange 用 `checkPacket` 判断「这个包归不归我」（通常是匹配包头 UUID），命中则 `handle` 推进状态机。
  - **一次性请求**：不被任何 exchange 认领的包，走 `ServerCommunicationManager.handle(...)`（`ServerCommunicationManager.java:88-184`），处理 `REQUEST_LITEMATIC` / `REGISTER_METADATA` / `REMOVE_SYNCMATIC` / `MODIFY_REQUEST` 四类。

### 5.2 Exchange 抽象与生命周期

`Exchange` 接口（`Exchange.java:18-50`）核心方法：

| 方法 | 职责 |
|---|---|
| `checkPacket(type, buf)` | **无副作用**判断是否处理（用 `AbstractExchange.checkUUID` peek UUID，读后回退 readerIndex，`AbstractExchange.java:67-73`） |
| `handle(type, buf)` | 实际处理（**第一行通常 `readUUID()` 真正消费掉** peek 过的 UUID） |
| `init()` | exchange 启动（通常发第一个包） |
| `isFinished()` / `isSuccessful()` | 状态查询 |
| `close(notifyPartner)` | 外部取消；`notifyPartner=true` 则发 cancel 包 |

`AbstractExchange`（`AbstractExchange.java`）状态机：`close()` 先置 `finished=true;success=false` 再 `onClose()` + 可选 `sendCancelPacket()`；`succeed()` 置 `finished=true;success=true` 再 `onClose()`（**成功路径不发 cancel**）。

**8 个 Exchange 子类**（服务端必须实现的标 ✅）：

| Exchange | 上下文 | 移植 | 一句话职责 |
|---|---|---|---|
| **VersionHandshakeServer** | 服务端 | ✅ | 进服握手：发版本 → 协商 Feature → 发 CONFIRM_USER（全量 placement） |
| VersionHandshakeClient | 客户端 | ⛔ | 握手客户端半边 |
| FeatureExchange（抽象） | 双端 | ✅ | Feature 协商（FEATURE_REQUEST / FEATURE） |
| **DownloadExchange** | 双端 | ✅ | 接收文件方：发 REQUEST_LITEMATIC → 收 SEND 分片 → 回 RECEIVED → 校验 MD5 |
| **UploadExchange** | 双端 | ✅ | 发送文件方：收 REQUEST/RECEIVED → 发 SEND 分片 → 发 FINISHED |
| **ModifyExchangeServer** | 服务端 | ✅ | 修改锁：MODIFY_REQUEST → ACCEPT（占锁）→ MODIFY_FINISH（应用 + 广播） |
| ModifyExchangeClient | 客户端 | ⛔ | 修改发起方（服务端要回应它的包） |
| ShareLitematicExchange | 客户端 | ⛔ | 分享发起方（服务端 `handle` 要回应 REGISTER_METADATA/REQUEST_LITEMATIC） |

> 每个 Exchange 的**完整状态机表 + 每个包的字段读写顺序**见 [21](21-syncmatica-protocol.md) §Exchange 状态机。

### 5.3 ExchangeTarget —— 「一个连接」的桥接

`ExchangeTarget`（`ExchangeTarget.java`）是通信层的中心抽象，**每个玩家一个**，生命周期 = 玩家连接生命周期。

| 字段（`:22-27`） | 职责 |
|---|---|
| `serverPlayNetworkHandler` / `clientPlayNetworkHandler` | 一端非 null（服务端持 `ServerGamePacketListenerImpl`） |
| `persistentName` | 服务端 = 玩家 UUID 字符串（QuotaService 按此记账，`:40`） |
| `features` | 握手后填的 `FeatureSet` |
| `ongoingExchanges` | `List<Exchange>`（**按注册顺序**，路由时遍历，`:27`） |

`sendPacket(type, buf, context)`（`:48-72`）：把 `(逻辑通道 Identifier, body)` 包成 `SyncmaticaPacket` → 走 `ServerPlayHandler.encodeSyncData` → 原版 `ClientboundCustomPayloadPacket` 发出。

> Paper 移植：`ExchangeTarget` 改为持 `Player`（或 `UUID`），`sendPacket` 改为走 `ChannelManager.send(syncmatica:main, player, bytes)`。原版 `IServerPlay` mixin 接口（`network/actor/IServerPlay.java`，仅 2 个方法 `syncmatica$getExchangeTarget` / `syncmatica$operateComms`）在 Paper 上**不需要 mixin**——直接用 `Map<UUID, ExchangeTarget>` 在 `ServerCommunicationManager` 内管理即可。

### 5.4 Feature 协商

9 个 `Feature`（`Feature.java:3-15`）：`CORE` / `FEATURE` / `MODIFY` / `MESSAGE` / `QUOTA` / `DEBUG` / `CORE_EX` / `VERSION` / `DISPLAY_NAME`。

其中 **4 个直接影响协议字段编码**（决定 metadata/position 包哪些可选字段）：

| Feature | 影响的字段 |
|---|---|
| `DISPLAY_NAME` | metadata 增 `writeUtf(displayName)` |
| `CORE_EX` | metadata 增 owner/lastModifiedBy（4 字段）；position 增 subregion 列表；modify 增 lastModifiedBy |
| `VERSION` | metadata 增 `writeVarInt(litematicVersion)` + `writeVarInt(dataVersion)` |
| `MODIFY` | 决定修改走 `MODIFY_REQUEST/ACCEPT/FINISH` 还是退化到 `REMOVE_SYNCMATIC` |

`FeatureSet` 序列化 = `\n` 分隔的 Feature 名字符串（`FeatureSet.java:40-49`），**不是位图**。版本默认集仅 `"0.1"→{CORE}` 一条（`:59-62`）。完整握手流程见 [21](21-syncmatica-protocol.md) §Feature 协商。

---

## 6. 数据模型概览

### 6.1 ServerPlacement（核心数据模型）

`data/ServerPlacement.java:22` —— 一个投影放置的全部元数据。**纯 JSON 序列化，无 NBT**。

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
| `matList` | `SyncmaticaMaterialList` | ⛔ 死代码字段（不持久化、不传输，见 §8） |

`toJson()` / `fromJson()`（`:288` / `:325`）字段顺序与 `isDirty()` 自愈逻辑见 [21](21-syncmatica-protocol.md) §数据序列化。**hash 算法** = `SyncmaticaUtil.createChecksum`（MD5 → `UUID.nameUUIDFromBytes`，`util/SyncmaticaUtil.java:34-53`）—— 客户端会校验，**不能改算法**。

### 6.2 SyncmaticManager（注册表 + 持久化）

`data/SyncmaticManager.java:18`：

- 内部 `Map<UUID, ServerPlacement> schematics`（**key = placement.id**，`:21`）
- `addPlacement` / `removePlacement` / `getPlacement(id)` / `hasPlacementHash(hash)`（后者 O(n) 遍历，`:54`）
- **每次变更即落盘**：`updateServerPlacement()`（`:86`）→ 若 server 则立即 `saveServer()`
- `startup()` → `loadServer()`（`:151`）：读 `<worldPath>/syncmatica/placements.json`，含旧路径迁移 + `isDirty()` 自愈回写
- `shutdown()` → `saveServer()`（`:115`）：原子写（`.new` → `.bak` → current，经 `SyncmaticaUtil.backupAndReplace`）

### 6.3 FileStorage（投影文件存储）

`data/FileStorage.java:14`：

- 存储目录 = `context.getLitematicFolder()` = `<worldPath>/syncmatics/`（服务端，`Syncmatica.java:25`）
- **服务端命名 = `<hashValue>.litematic`**（`:160-164`，按 hash 内容寻址，天然去重）
- `getLocalState(placement)`（`:34-51`）：5 步判定 → 4 态枚举 `LocalLitematicState`（`NO_LOCAL_LITEMATIC` / `LOCAL_LITEMATIC_DESYNC` / `DOWNLOADING_LITEMATIC` / `LOCAL_LITEMATIC_PRESENT`）
- `createLocalLitematic(placement)`（`:95`）：建空文件供下载写入（非临时文件）
- `hashCompare`（`:131`）：MD5 校验 + `(placement → lastModified)` 缓存避免重复算 hash

> Paper 持久化路径映射（`getDataFolder()` vs 世界目录）见 [22](22-syncmatica-mixin-migration.md) §持久化映射。

### 6.4 PlayerIdentifier 体系（CORE_EX）

- `PlayerIdentifier`（`extended_core/PlayerIdentifier.java:8`）：`uuid` + `bufferedPlayerName`，`MISSING_PLAYER` 占位。**无 equals/hashCode**（用对象身份相等）——`owner.equals(lastModifiedBy)` 仅当同一实例才 true，故 `createOrGet` 的归一化是关键。
- `PlayerIdentifierProvider`（`extended_core/PlayerIdentifierProvider.java:13`）：内存 `Map<UUID, PlayerIdentifier>`（**不持久化**，随 placement JSON 落盘 uuid+name，重启重建）。服务端**不主动** `updateName`（`:33` 的 `if (!isServer())` 守卫）。

---

## 7. 服务端核心流程（5 大场景调用链）

### 7.1 玩家进服握手

```
PlayerJoinEvent（Paper）                                         ← 原 MixinPlayerManager @placeNewPlayer TAIL + MixinServerPlayNetworkHandler @<init> TAIL
  ├─ [可选] 直接发 REGISTER_VERSION[MOD_VERSION] S2C              // MixinPlayerManager.java:36-40（Paper 可由 exchange.init 代替）
  └─ ServerCommunicationManager.onPlayerJoin(target, player)      // ServerCommunicationManager.java:64-71
       ├─ new VersionHandshakeServer(target, ctx)
       ├─ playerMap.put(target, player)
       ├─ PlayerIdentifierProvider.updateName(profile.id, profile.name)
       └─ startExchangeUnchecked(hi)
            └─ VersionHandshakeServer.init() → 发 REGISTER_VERSION[服务端版本]   // VersionHandshakeServer.java:72-77

  [客户端回 REGISTER_VERSION（版本号）]
  → onPacket → VersionHandshakeServer.handle
       ├─ checkPartnerVersion（仅拒 "0.0.1"）
       ├─ FeatureSet.fromVersionString(版本) → 命中默认集则 setFeatureSet
       │    └─ onFeatureSetReceive() → 发 CONFIRM_USER[count + 全量 placement metadata]  // :57-68
       └─ 未命中 → requestFeatureSet()（走 FEATURE_REQUEST / FEATURE 二次协商）
  握手成功 → handleExchange → broadcastTargets.add(target)         // :219-222
```

### 7.2 玩家分享投影（C2S 上传）

```
客户端发 REGISTER_METADATA[metadata]
  → ServerCommunicationManager.handle                              // :114-156
       ├─ receiveMetaData(buf, source) 解析 ServerPlacement
       ├─ 已存在同 id → cancelShare（发 CANCEL_SHARE）
       ├─ owner 缺失 → 用 playerMap 的 GameProfile 覆盖 owner/lastModifiedBy
       ├─ 本地无文件 → download(placement, source)
       │    └─ new DownloadExchange → startExchange
       │         └─ init 发 REQUEST_LITEMATIC
       │              [客户端回 SEND_LITEMATIC 分片] → DownloadExchange.handle
       │                ├─ bytesSent += size; quota.isOverQuota? → close + ERROR
       │                ├─ readBytes 写文件（MD5 DigestOutputStream）
       │                └─ 回 RECEIVED_LITEMATIC
       │              [客户端回 FINISHED_LITEMATIC] → 校验 MD5→UUID == hash → succeed/fail
       │         onClose：setDownloadState(false); 成功则 quota.progressQuota
       │         handleExchange：成功 → addPlacement 广播; 失败 → cancelShare
       └─ 本地已有文件 → addPlacement(source, placement)
            └─ synMan.addPlacement + 向所有 broadcastTargets sendMetaData + saveServer
```

### 7.3 玩家下载投影（S2C 下发）

```
客户端发 REQUEST_LITEMATIC[uuid]
  → ServerCommunicationManager.handle                              // :91-113
       └─ fileStorage.getLocalLitematic(placement) → new UploadExchange → startExchange
            └─ init → send() 读 16KB → 发 SEND_LITEMATIC[uuid, size, bytes]
                 [客户端回 RECEIVED_LITEMATIC] → send() 再读 16KB ...（stop-and-wait）
                 读到 EOF → 发 FINISHED_LITEMATIC → succeed
```

### 7.4 玩家修改放置位置

```
客户端发 MODIFY_REQUEST[uuid]
  → ServerCommunicationManager.handle → new ModifyExchangeServer → startExchange   // :178-183
       init：placement 为 null 或已被他人修改 → close(true)（发 MODIFY_REQUEST_DENY）
             否则 → accept() 发 MODIFY_REQUEST_ACCEPT + setModifier 占锁
  [客户端改完发 MODIFY_FINISH[uuid + positionData]]
  → ModifyExchangeServer.handle → receivePositionData 应用 + setLastModifiedBy → succeed
  → handleExchange(ModifyExchangeServer)                                           // :223-253
       向所有 broadcastTargets 广播：
         ├─ 支持 MODIFY feature → 发 MODIFY[uuid + positionData + (CORE_EX)lastModifiedBy]
         └─ 不支持 → 发 REMOVE_SYNCMATIC + 重新 REGISTER_METADATA（旧式兼容）
```

### 7.5 玩家删除投影

```
客户端发 REMOVE_SYNCMATIC[uuid]
  → ServerCommunicationManager.handle                              // :157-177
       └─ 关闭进行中 modifier + synMan.removePlacement + 向所有广播 REMOVE_SYNCMATIC
```

### 7.6 玩家离服

```
PlayerQuitEvent（Paper）  ← 原 MixinServerPlayNetworkHandler @onDisconnect HEAD
  └─ ServerCommunicationManager.onPlayerLeave(target)              // :73-86
       ├─ 关闭该 target 所有进行中 exchange（close(false) + handleExchange）
       ├─ broadcastTargets.remove(target)
       └─ playerMap.remove(target)
```

---

## 8. 不移植的部分（降级总览）

| 模块 | 处置 | 原因 |
|---|---|---|
| **`material/`（3 文件）** | ⛔ **完全不移植** | 死代码/半成品：仅被 `ServerPlacement.matList` 持有，**无 exchange / 无 PacketType / 无命令 / 无持久化引用**（`toJson` 都不写它）。是未完成的客户端「材料配送」残骸。`ServerPlacement` 移植时连 `matList` 字段一并去掉 |
| **`litematica_mixin/`（10 个）** | ⛔ **完全不移植** | 纯客户端 GUI 注入（MixinGuiMainMenu / MixinSchematicPlacement / ...），注入 `fi.dy.masa.litematica.*` 客户端类，Paper 无对应 |
| **`mixin/` 客户端 4 个** | ⛔ **不移植** | `MixinClientCommonNetworkHandler` / `MixinClientPlayNetworkHandler` / `MixinIntegratedServer` / `MixinMinecraftClient`——纯客户端 / 单机 / Open-to-LAN 生命周期 |
| **`network/actor/` 客户端** | ⛔ **不移植** | `IClientPlay` / `ActorClientPlayHandler`——客户端单例，挂 `ClientPacketListener` |
| **`communication/ClientCommunicationManager`** | ⛔ **不移植，但作字段语义对照** | 客户端通信实现；其中 `receiveMetaData` / `receivePositionData` 的字段顺序是服务端 `putMetaData` 的**镜像**，移植时须对照确认 |
| **客户端 4 个 Exchange** | ⛔ **不移植** | VersionHandshakeClient / ModifyExchangeClient / ShareLitematicExchange——但服务端必须正确**回应**它们发出的包 |
| **`RedirectFileStorage`** | ⚠️ **可不移植** | 装饰器，客户端场景用（外部文件重定向免拷贝）；服务端纯 `FileStorage` 即可 |
| **`Reference.isClient/isIntegratedServer/isOpenToLan` 分支** | ⚠️ **简化** | Paper 恒为 dedicated server，所有客户端/单机分支删除 |

> 各降级点的详细论证与 mixin 逐项 Bukkit 映射见 [22](22-syncmatica-mixin-migration.md)。

---

## 9. 与本项目现有框架的复用关系

syncmatica 与 Servux 共享 `framework/network`（servux 移植沉淀的通用网络层）。复用边界：

| 现有框架类 | syncmatica 复用 | 说明 |
|---|---|---|
| `framework.network.ChannelManager` | ✅ **直接用** | 注册 `syncmatica:main` 一条通道（incoming + outgoing） |
| `framework.network.ProtocolChannel` | ✅ **直接用** | `onPluginMessageReceived` 的 `byte[]` → `FriendlyByteBufs.wrap` → 回调 |
| `framework.network.ServerPlayHandler`（framework） | ✅ **直接用** | handler 注册表，联动 ChannelManager |
| `framework.network.FriendlyByteBufs` | ✅ **直接用** | `byte[]` ↔ `FriendlyByteBuf` 桥接 |
| `framework.network.IPluginServerPlayHandler` | ⚠️ **适配** | 接口为 servux「per-通道 IServerPayloadData」设计；syncmatica 实现它，在 `receivePlayPayload` 里做 PacketType 派发（wrap buf → readIdentifier 得 PacketType → 读 body → `onPacket`） |
| `framework.network.PacketSplitter` | ❌ **不用** | servux 透明流式重组（首包写总长 VarInt + 连续流）；syncmatica 是 exchange 级 **stop-and-wait 应答式分片**（SEND↔RECEIVED 逐片 + UUID 匹配）——**模型不同，文件传输分片须在 UploadExchange/DownloadExchange 内自写** |
| `framework.network.IServerPayloadData` | ❌ **不用** | syncmatica 的「packet」是 `SyncmaticaPacket`（逻辑通道+body），非 servux 的 `IServerPayloadData` |

> 🔑 **移植命门**：syncmatica 的**物理包体是复合结构** `[逻辑通道 Identifier][body]`（原版 `SyncmaticaPacket.toPacket` = `writeIdentifier(channel) + writeBytes(body)`，`SyncmaticaPacket.java:44-48`）。Paper 端 `onPluginMessageReceived` 收到的 `byte[]` 即此结构——必须照抄 `SyncmaticaPacket.fromPacket`（`:39-42`）解析：先 `readIdentifier()` 得 PacketType，再读 body。**这与 Servux（每通道 byte[] 直接是 body）不同**，是 syncmatica 网络层的最大坑点。

---

## 10. 术语表

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
> - 阶段划分与文件清单 → [23-syncmatica-implementation-plan.md](23-syncmatica-implementation-plan.md)
> - 客户端测试步骤 → [24-syncmatica-testing-guide.md](24-syncmatica-testing-guide.md)
