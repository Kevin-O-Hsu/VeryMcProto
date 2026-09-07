# 23 · Syncmatica 实现总览

> ✅ **syncmatica（投影共享）已 100% 完整实现**。本文档从「待移植逐阶段作战手册」改写为「实现总览」——记录实际包结构、文件清单、已落地的关键实现决策与完成状态。
> 架构见 [20](20-syncmatica-architecture.md)；协议见 [21](21-syncmatica-protocol.md)；Mixin 迁移实现见 [22](22-syncmatica-mixin-migration.md)；测试见 [24](24-syncmatica-testing-guide.md)。
> 原版源码根：`OriginImpl/syncmatica-LTS-1.21.11/src/main/java/ch/endte/syncmatica/`（下文 `ORIGIN/`）。
> Paper 实现包根：`src/main/java/verymc/top/veryMcProto/mod/syncmatica/`（下文 `PAPER/`）。

---

## 1. 实际包结构（`mod/syncmatica/`）

```
mod/syncmatica/
├── Feature.java                    # 9 个协议特性枚举（CORE / DISPLAY_NAME / CORE_EX / VERSION / MODIFY …）
├── SyncmaticaContext.java          # 容器：聚合 files / comMan / synMan / quota / debugService / playerIdentifierProvider / fs
├── SyncmaticaReference.java        # 常量：MOD_ID / NETWORK_ID / MOD_VERSION / LITEMATIC_SUBDIR / CONFIG_FILE_NAME / PLACEMENTS_FILE_NAME
├── app/
│   └── SyncmaticaModule.java       # 装配入口（单例 enable(plugin) / disable；注册通道 + 玩家监听 + 双保险握手）
├── communication/
│   ├── CommunicationManager.java       # 包派发 + metadata/position 编解码 + downloadState/modifyState 状态表
│   ├── ServerCommunicationManager.java # 服务端 4 类一次性请求处理 + 握手管理 + broadcastTargets
│   ├── ExchangeTarget.java             # 玩家连接封装 + ongoingExchanges + FeatureSet + sendPacket（NMS 直发）
│   ├── FeatureSet.java                 # Feature 集合 + 版本默认集映射（"0.1"→{CORE}）
│   ├── MessageType.java                # SUCCESS / INFO / WARNING / ERROR
│   └── exchange/
│       ├── Exchange.java               # 会话接口（6 方法）
│       ├── AbstractExchange.java       # 状态机基类（finished/success + close/succeed/checkUUID）
│       ├── VersionHandshakeServer.java # 版本握手（REGISTER_VERSION → FEATURE → CONFIRM_USER）
│       ├── FeatureExchange.java        # Feature 协商
│       ├── UploadExchange.java         # 文件上传（服务端发送，stop-and-wait 16KB/片）
│       ├── DownloadExchange.java       # 文件下载（服务端接收 + MD5 校验 + 配额检查）
│       └── ModifyExchangeServer.java   # 放置修改（占锁防并发）
├── data/
│   ├── IFileStorage.java
│   ├── FileStorage.java                # 内容寻址 .litematic 存储（<hash>.litematic 天然去重）
│   ├── LocalLitematicState.java        # 4 态枚举（NO_LOCAL / PRESENT / DOWNLOADING / DESYNC）
│   ├── ServerPlacement.java            # 放置元数据全字段 + Feature 条件字段 + toJson/fromJson + peek 修正
│   ├── ServerPosition.java             # 原点坐标（BlockPos + dimensionId）
│   ├── SyncmaticManager.java           # placement 注册表 + placements.json 原子写（backupAndReplace）
│   └── litematica/                     # peek 元数据读取/修正（服务端用文件 peek 校正 metadata）
│       ├── FileType.java
│       ├── Schema.java
│       ├── SchematicMetadata.java
│       └── SchematicSchema.java
├── extended_core/
│   ├── PlayerIdentifier.java
│   ├── PlayerIdentifierProvider.java
│   ├── SubRegionData.java
│   └── SubRegionPlacementModification.java
├── network/
│   ├── PacketType.java                 # 18 逻辑消息枚举（⚠️ request_download / mesage 拼写照抄原版）
│   └── SyncmaticaHandler.java          # C2S 收发 + [Identifier][body] 解析 + 软禁用门控
├── service/
│   ├── IService.java
│   ├── AbstractService.java
│   ├── IServiceConfiguration.java
│   ├── JsonConfiguration.java          # Gson 配置（try/catch + hadError 机制）
│   ├── DebugService.java               # 逐包日志（修正原版 doPackageLogging→doPacketLogging + 默认值统一 false）
│   └── QuotaService.java               # 上传配额（progress Map，默认关闭 / 40MB）
├── command/
│   └── SyncmaticaCommand.java          # /syncmatica 命令树（status/save/reload/enable/disable/load/debug）
└── util/
    ├── StringTools.java
    ├── SyncmaticaDebug.java            # 调试分类 trace（master + 分类正交，持久化到 syncmatica-config.json）
    ├── SyncmaticaLog.java              # JUL 日志 shim
    └── SyncmaticaUtil.java             # backupAndReplace 原子写等工具
```

---

## 2. 关键实现决策（已落地）

| 项 | 实际实现 | 详见 |
|---|---|---|
| 装配方式 | `SyncmaticaModule.enable(plugin)` 单例装配（**不走 framework DataProviderManager**——Exchange 会话模型自管通道注册 + 玩家监听）；主类 `onEnable` 注册 | [20](20-syncmatica-architecture.md) §装配 |
| 握手机制 | **双保险**：`onPlayerJoin` 延迟 40t 主路径 + `onPlayerRegisterChannel` 兜底（幂等 `tryStartHandshake`） | [22](22-syncmatica-mixin-migration.md) §3 |
| 物理包体 | `[Identifier][body]` 复合结构，`SyncmaticaHandler.receivePlayPayload` 解析 | [21](21-syncmatica-protocol.md) §1 |
| 通道注册 | 单通道 `syncmatica:main`，复用 framework `ServerPlayHandler.registerServerPlayHandler` | [22](22-syncmatica-mixin-migration.md) §4 |
| ExchangeTarget | 持 `Player`（非原版 mixin 接口）+ `Map<UUID, ExchangeTarget>` 管理 | [22](22-syncmatica-mixin-migration.md) §4.3 |
| S2C 路径 | 默认 **NMS `DiscardedPayload` 直发**（`S2C_VIA_NMS=true`，plugin messaging wire 对纯 Fabric 客户端不可达）；`/syncmatica debug s2c msg` 可切回对比 | [21](21-syncmatica-protocol.md) §1 |
| 文件分片 | 自写 stop-and-wait（`BUFFER_SIZE=16384`，每片确认），**不复用 PacketSplitter** | [21](21-syncmatica-protocol.md) §6 |
| hash 算法 | MD5 → `UUID.nameUUIDFromBytes`（type-3 UUID），内容寻址键 + 去重 | [21](21-syncmatica-protocol.md) §7 |
| Feature 协商 | MOD_VERSION=插件版本（`-b` 后缀永不命中版本正则）触发 FEATURE 交换；`FeatureSet` 序列化为 `\n` 分隔名 | [21](21-syncmatica-protocol.md) §3 |
| 持久化路径 | `plugins/VeryMcProto/syncmatics/<hash>.litematic` + `placements.json` + `syncmatica-config.json`，原子写 | [22](22-syncmatica-mixin-migration.md) §5 |
| 权限 | 5 个真实节点（`plugin.yml`，default true/op 分级） | [22](22-syncmatica-mixin-migration.md) §6 |
| 命令 | `/syncmatica status\|save\|reload\|enable\|disable\|load [file]\|debug [...]` | [22](22-syncmatica-mixin-migration.md) §7 |
| 调试体系 | `SyncmaticaDebug`（master + 分类正交，持久化）+ `DebugService`（逐包 INFO 日志）两套独立 | [22](22-syncmatica-mixin-migration.md) §8 |
| 配额 | `QuotaService`（默认关闭，限额 40MB，超额中断下载） | [22](22-syncmatica-mixin-migration.md) §8 |
| material 死代码 | `ServerPlacement.matList` 字段 + `SyncmaticMaterialList` 全删 | [22](22-syncmatica-mixin-migration.md) §9 |
| RedirectFileStorage | 不移植（服务端纯 `FileStorage`） | [22](22-syncmatica-mixin-migration.md) §9 |
| 客户端 exchange | 3 个客户端 exchange 类不移植，服务端 `handle` 回应其包 | [22](22-syncmatica-mixin-migration.md) §9 |
| 依赖 | `build.gradle.kts` 不为 syncmatica 改动（纯 Java + Gson + NMS） | [22](22-syncmatica-mixin-migration.md) §11 |

---

## 3. 完成状态

| 功能 | 状态 | 关键类 |
|---|---|---|
| 版本握手 + Feature 协商 | ✅ | `VersionHandshakeServer` + `FeatureExchange` |
| 投影上传（C2S → 服务端） | ✅ | `UploadExchange`（stop-and-wait 16KB） |
| 投影下载（服务端 → C2S） | ✅ | `DownloadExchange`（MD5 校验 + 配额） |
| 放置位置修改 | ✅ | `ModifyExchangeServer`（占锁防并发） |
| 投影删除 + 广播 | ✅ | `REMOVE_SYNCMATIC` + `broadcastTargets` |
| 投影注册/广播 | ✅ | `REGISTER_METADATA` + `CONFIRM_USER` |
| 上传配额 | ✅ | `QuotaService`（默认关闭，40MB） |
| 调试日志 | ✅ | `SyncmaticaDebug` + `DebugService`（修正原版 2 处 bug） |
| 文件存储（内容寻址） | ✅ | `FileStorage` + `LocalLitematicState` |
| 持久化（原子写） | ✅ | `SyncmaticManager`（placements.json backupAndReplace） |
| 服务端 peek 修正 metadata | ✅ | `SyncmaticManager` + `correctMetadataFromPeek` |
| 软禁用协议 | ✅ | `suspendProtocol` / `resumeProtocol`（`/syncmatica enable\|disable`） |
| 命令系统 | ✅ | `SyncmaticaCommand`（7 子命令 + tab 补全） |

**降级/已删**：material（死代码全删）、RedirectFileStorage（不移植）、客户端 3 个 exchange 类（不移植，服务端回应其包）、`isClient()`/`isIntegratedServer()` 分支（Paper 恒 dedicated server，删除）。

---

## 4. 文档导航

| 想了解 | 看哪份 |
|---|---|
| 整体架构 / 与 Servux 差异 / Context 容器 / Exchange 模型 | [20-syncmatica-architecture.md](20-syncmatica-architecture.md) |
| 协议字段 / 18 PacketType / Exchange 状态机 / 分片 / hash | [21-syncmatica-protocol.md](21-syncmatica-protocol.md) |
| Mixin → Bukkit 已落地映射 / 网络/持久化/权限/命令迁移 / 降级矩阵 | [22-syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md) |
| 客户端兼容测试 / 排错 / 症状表 | [24-syncmatica-testing-guide.md](24-syncmatica-testing-guide.md) |
