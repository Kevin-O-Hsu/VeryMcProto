# 24 · Syncmatica 客户端兼容测试指南

> **实现状态**：syncmatica（投影共享）已 **100% 完整实现并可测试**。本文档从「待移植蓝图」改写为「已实现实测指南」——所有命令/权限/配置/日志/协议流程均与真实代码逐一对齐。
>
> **代码定位**：`src/main/java/verymc/top/veryMcProto/mod/syncmatica/`（命令 `command/SyncmaticaCommand.java`、配置 `SyncmaticaReference.java`、握手 `communication/exchange/VersionHandshakeServer.java`、上传/下载 `UploadExchange.java`/`DownloadExchange.java`、持久化 `data/SyncmaticManager.java`、配额 `service/QuotaService.java`、调试 `service/DebugService.java` + `util/SyncmaticaDebug.java`）；权限注册 `src/main/resources/plugin.yml`；客户端对照 `OriginImpl/syncmatica-LTS-1.21.11/`。
>
> **关联文档**：实施蓝图 [23](23-syncmatica-implementation-plan.md)；架构/协议/迁移 [20](20-syncmatica-architecture.md)/[21](21-syncmatica-protocol.md)/[22](22-syncmatica-mixin-migration.md)；姊妹（Servux 客户端测试）[10](10-testing-guide.md)；项目总览 [../CLAUDE.md](../CLAUDE.md)。

---

## 0. 测试目标与判官逻辑

**核心验收闭环**：syncmatica 客户端进服 → **握手成功** → **分享投影**（C2S 上传）→ 其他客户端**下载**（S2C 下发）→ **修改放置**（位置同步）→ **删除** → 多端状态一致 + **重启持久化**。

**判官逻辑**：syncmatica 的功能是否正常，**两件事是真相**——
1. **服务端调试日志的包流**（`/syncmatica debug on` + `cat all`，见 §2.3），看收发链路是否完整。
2. **服务端文件落地**（`syncmatics/<hash>.litematic` 投影文件 + `placements.json` placement 注册表）。

客户端 GUI 行为只是表象。遇到「客户端没反应」先查服务端日志收没收到包（`onPacket` / `Sending packet`），再查文件落地，最后才看客户端（同 Servux [10](10-testing-guide.md) §2 判官逻辑）。

---

## 1. 测试环境准备

### 1.1 服务端

| 项 | 要求 |
|---|---|
| Paper | 1.21.11（与本项目一致，`api-version: 1.21`） |
| 插件 | VeryMcProto（含 syncmatica 模块，`POSTWORLD` 加载） |
| Java | 21 |
| 配置 | `plugins/VeryMcProto/syncmatica-config.json`（首次启动自动生成）：`{ quota: {enabled: false, limit: 40000000}, debug: {doPacketLogging: false} }` |
| 端口 | 默认 25565；客户端直连 |

启动：`./gradlew runServer`（开发期，2G 堆）或部署 reobf jar 到正式服。

> **配额默认值**（`QuotaService`）：`enabled=false`、`limit=40000000`（约 40 MB）。**调试默认值**（`DebugService`）：`doPacketLogging=false`（生产静默）。两者皆可运行时改，详见 §10/§2.3。

### 1.2 客户端

| Mod | 版本（来自 `fabric.mod.json` suggests） | 用途 |
|---|---|---|
| **Minecraft Fabric** | 1.21.11 + Fabric Loader | 基础 |
| **syncmatica** | LTS 1.21.11（`OriginImpl/syncmatica-LTS-1.21.11/` 对应版本） | 协议客户端（注入 Litematica GUI） |
| **Litematica** | `>=0.25.4- <0.27.0`（建议用 syncmatica 兼容的 LTS fork，如 sakura-ryoko） | 投影客户端 |
| **Malilib** | `>=0.27.4- <0.28.0`（Litematica 前置） | Litematica 前置 |

> ⚠️ syncmatica `fabric.mod.json` 的 `breaks` 声明：malilib `<0.27.4-` / litematica `<0.25.4-` 会冲突。务必用足版本的 LTS fork。

**至少 2 个客户端账号**（用于多玩家协同测试，§11）。单机多开或两台机器均可。

### 1.3 权限赋予

真实权限节点（`plugin.yml`）：

| 节点 | default | 覆盖命令 |
|---|---|---|
| `syncmatica.command` | `true`（全员） | `/syncmatica` 基础入口 |
| `syncmatica.command.admin` | `op` | `/syncmatica status\|save\|reload\|enable\|disable` |
| `syncmatica.command.load` | `true` | `/syncmatica load`（全量加载） |
| `syncmatica.command.load_each` | `true` | `/syncmatica load <file>`（单个加载） |
| `syncmatica.command.debug` | `op` | `/syncmatica debug [...]` |

测试期给 OP 即可全覆盖（`/op <玩家>`）；要测权限边界则用 LuckPerms 精细分发。

### 1.4 投影文件准备

- 准备 2–3 个不同大小的 `.litematic`（小：< 16 KB 单片；中：~100 KB 多片；大：> 1 MB 测分片稳定性）。
- 用 Litematica 客户端在单机先做好投影，导出为 `.litematic`。

---

## 2. 启动与调试准备

### 2.1 启动检查

**操作**：启动 Paper 服务端。

**验证**：

| 检查项 | 期望 | 失败排查 |
|---|---|---|
| 控制台无异常 | `onEnable` 完成，无 stacktrace | 查 `SyncmaticaContext` 初始化；`syncmatica-config.json` 读写权限 |
| 通道注册 | `syncmatica:main` 通道 incoming/outgoing 注册成功（`/syncmatica status` 可见协议状态） | `SyncmaticaReference.NETWORK_ID`；plugin messaging 通道注册 |
| 目录创建 | `plugins/VeryMcProto/syncmatics/` 目录存在 | `SyncmaticaContext.getLitematicFolder()` |
| 配置生成 | `plugins/VeryMcProto/syncmatica-config.json` 存在（含 `quota`/`debug`） | `SyncmaticaContext.loadConfiguration()` |
| placements.json | `plugins/VeryMcProto/placements.json` 存在（空表 `{"placements":[]}` 或已有数据） | `SyncmaticManager.loadServer()` |

### 2.2 真实命令总览

`/syncmatica status|save|reload|enable|disable|load [file]|debug [...]`（命令帮助字串：`SyncmaticaCommand.USAGE`）。

| 子命令 | 权限 | 行为 |
|---|---|---|
| `status` | `admin` | 显示协议启停状态 + 调试状态行（`SyncmaticaDebug.statusLine()`）+ 配置文件名 |
| `save` | `admin` | `context.saveConfiguration()`——把当前 quota/debug 配置 + 调试 master/分类写入 `syncmatica-config.json` |
| `reload` | `admin` | `context.loadConfiguration()`——从 `syncmatica-config.json` 重读配置 |
| `enable` / `disable` | `admin` | **软禁用/启用协议**：调 `context.resumeProtocol()`/`suspendProtocol()`。**不重启插件**，只切换协议处理开关；enable 会 `reconnectOnlinePlayers()`（在线玩家重新握手），disable 中断进行中的传输但**不踢玩家**（通道保留） |
| `load` | `load` | peek `syncmatics/` 目录下所有 `<hash>.litematic`（**文件名须为 UUID**），未注册的逐个建 `ServerPlacement`（owner=发送方玩家，origin=玩家脚下）并 `addPlacement` 广播 |
| `load <file>` | `load_each` | 加载指定单个 `<file>.litematic` |
| `debug [...]` | `debug` | 调试日志宏开关热切换（见 §2.3） |

> **Tab 补全**：一级 `status/save/reload/enable/disable/load/debug`；`load <file>` 列目录内未注册文件（去 `.litematic` 后缀）；`debug <on/off/cat/status/s2c>`；`debug s2c <nms/msg>`；`debug cat <all/none/分类名>`。

### 2.3 调试开关体系（关键，排错前提）

syncmatica 有**两套独立**调试日志系统，互不替代，需配合开：

**(a) `SyncmaticaDebug`（分类日志门面，主用）**——`/syncmatica debug`，输出前缀 `[DBG/syncmatica/<cat>]`，便于与 servux `[DBG/servux/...]]` 区分 grep。

- `/syncmatica debug on` / `off`：master 总开关（默认 `false`）。
- `/syncmatica debug cat all` / `none` / `<分类>`：分类切换（**master 与分类正交，两者皆开才输出**）。
- `/syncmatica debug status`：查看当前状态行。
- 分类（`SyncmaticaDebug.Cat`）：`lifecycle`（生命周期）、`handshake`（握手）、`network`（网络层）、`packet`（包派发）、`exchange`（Exchange 生命周期）、`data`（文件/placement 读写）。
- **持久化**：master + 分类各自独立保存到 `syncmatica-config.json`，**重启后恢复**（`saveConfiguration`）。

**测前推荐**：`/syncmatica debug on` + `/syncmatica debug cat all`（或单独 `handshake`/`network`/`packet`）。

**(b) `DebugService.doPacketLogging`（包级 INFO 日志）**——记录每包收发：

- 输出格式（收）：`Syncmatica - received packet:[type=XXX]`；发：`Sending packet[type=XXX] to ExchangeTarget[id=...]`。
- 默认 `false`（生产静默）；**改法**：编辑 `syncmatica-config.json` 的 `debug.doPacketLogging=true` 后 `/syncmatica reload`（**注意**：原版 `DebugService` 字段拼写曾为 `doPackageLogging`，Paper 版统一修正为 `doPacketLogging`，配置文件以真实拼写为准）。
- 与 `SyncmaticaDebug` 区别：`SyncmaticaDebug` 是分类的细粒度 trace；`doPacketLogging` 是无分类的逐包 INFO 记录。排错通常用前者足矣，后者用于核对每包 PacketType 字串。

> **syncmatica 与 servux 调试完全独立**：`/syncmatica debug` 只控制 syncmatica 自己的日志，绝不影响 servux（反之亦然）。两套状态互不串扰。

**(c) S2C 发送路径诊断开关**——`/syncmatica debug s2c <nms|msg>`：

- 切换 `ExchangeTarget.S2C_VIA_NMS`（true = NMS `ClientboundCustomPayloadPacket` 直发；false = Bukkit plugin messaging）。**不持久化**（纯路由诊断开关）。
- 用途：当怀疑「客户端收不到 S2C 包」时，在两条路径间切换排查（见 §12 症状表）。

---

## 3. 握手测试

**操作**：syncmatica 客户端进服（进服即自动握手，由 `ServerCommunicationManager.onPlayerJoin` → `tryStartHandshake` 触发）。

**验证**（服务端日志，`SyncmaticaDebug` HANDSHAKE 分类）：

```
[DBG/syncmatica/handshake] VersionHandshakeServer.init: 推 REGISTER_VERSION[服务端版本=1.0.0] → <玩家>
[DBG/syncmatica/handshake] VersionHandshakeServer: 收到客户端 REGISTER_VERSION[版本=<客户端版本>] ← <玩家>
（服务端 MOD_VERSION="1.0.0" 命中"非 0.1.x"分支 → FeatureSet.fromVersionString 返回 null → 触发 FEATURE 交换，双方用全集 FeatureSet）
[DBG/syncmatica/handshake] VersionHandshakeServer: fromVersionString 返回 null → requestFeatureSet（FEATURE 交换）
（FEATURE 交换完成后）
[DBG/syncmatica/handshake] VersionHandshakeServer.onFeatureSetReceive: 推 CONFIRM_USER[placementCount=N] → <玩家>
<玩家> 已加入 broadcastTargets（共 N+1 个）
```

同时 INFO 级日志：`Syncmatica client joining with local version 1.0.0 and client version <客户端版本>`。

**客户端侧**：进服无报错；Litematica 主菜单的「服务端投影」入口可见（即使列表为空）。

**成功判定**：`CONFIRM_USER` 下发且玩家进入 `broadcastTargets`。

**失败排查**：
- 客户端进服即踢 / 无握手包 → 检查 `syncmatica:main` 通道 outgoing 注册；`SyncmaticaHandler.receivePlayPayload` 的 `[Identifier][body]` 解析（[21](21-syncmatica-protocol.md) §1.2）；`/syncmatica debug s2c msg`（切 plugin messaging）后重测。
- 收到 REGISTER_VERSION 但 `Denying syncmatica join due to outdated client` → `VersionHandshakeServer.handle` 的 `checkPartnerVersion` 拒绝（仅应拒 `"0.0.1"`，其它放行）；确认服务端 `MOD_VERSION` 与客户端版本兼容。
- 收到 REGISTER_VERSION 但无 FEATURE/CONFIRM_USER → `FeatureSet.fromVersionString` / `requestFeatureSet` 链路；FeatureExchange 是否 `succeed`。
- 客户端报「不兼容版本」→ 服务端 `MOD_VERSION="1.0.0"` 触发 FEATURE 交换应使用全集 FeatureSet；确认 `getFeatureSet()` 声明完整（MODIFY/DISPLAY_NAME/CORE_EX/VERSION 全开）。

---

## 4. 分享投影测试（C2S 上传）

**操作**：客户端 A 加载一个 `.litematic` 到 Litematica，通过 syncmatica 提供的「Share」入口（Litematica 放置列表中对某 placement 的操作按钮）分享。

> GUI 入口：syncmatica 通过 `litematica_mixin`（`MixinWidgetSchematicPlacement` / `ButtonListenerShare` 等）在 Litematica 的放置列表加「Share」按钮。具体位置以客户端实际 GUI 为准。

**协议流程**（服务端 `SyncmaticaDebug` PACKET/EXCHANGE 分类 + 文件落地）：

```
[received packet] REGISTER_METADATA ← 客户端A（含 placement metadata + hash）
（服务端本地无该 hash 文件 → 启 DownloadExchange 拉文件）
[Sending packet]   REQUEST_LITEMATIC → 客户端A        ← DownloadExchange.init
[received packet]  SEND_LITEMATIC   ← 客户端A（每片 16384 字节）
[Sending packet]   RECEIVED_LITEMATIC → 客户端A       ← 应答
... （stop-and-wait 循环至文件传完）
[received packet]  FINISHED_LITEMATIC ← 客户端A
（MD5 校验：UUID.nameUUIDFromBytes(md5.digest()) == placement.hash → succeed）
（成功后广播给其它在线客户端）
[Sending packet]   REGISTER_METADATA → 所有 broadcastTargets（除 A 外）
```

**文件落地**：
- `plugins/VeryMcProto/syncmatics/<hash>.litematic` 存在，大小与源文件一致。
- `plugins/VeryMcProto/placements.json` 新增一条 placement 记录（含 `id`/`file_name`/`hash`/`origin`/`owner` 等）。

**客户端 B（若在线且已握手）**：自动收到 REGISTER_METADATA，Litematica 放置列表出现该服务端投影。

**成功判定**：文件落地 + hash 匹配 + 广播到其他 broadcastTargets + `placements.json` 立即落盘。

**失败排查**：
- 收到 REGISTER_METADATA 但无 REQUEST_LITEMATIC → `ServerCommunicationManager.handle(REGISTER_METADATA)` 的 `getLocalState` 判定；`FileStorage.getLocalState` 是否误判文件已存在。
- 文件传一半中断 → `DownloadExchange` stop-and-wait 应答链（每片回 RECEIVED_LITEMATIC）；MD5 校验失败会 `close(false)` 删下载文件。
- 文件落地但 hash 不匹配 → `SyncmaticaUtil.createChecksum` 必须是 MD5→type-3 UUID（`UUID.nameUUIDFromBytes`，[21](21-syncmatica-protocol.md) §7.3）；检查是否误用其他算法。
- 分享成功但其他客户端没收到广播 → 确认 A 与 B 均在 `broadcastTargets`（握手成功才加入）；`addPlacement` 的广播循环是否覆盖全集合。

> **方向语义**（命名易混）：syncmatica 原版命名以「服务端视角」——`DownloadExchange` = **服务端从客户端拉文件**（即客户端「分享/上传」走的就是它）；`UploadExchange` = 服务端把文件推给客户端（即客户端「Load/下载」）。下文沿用真实类名。

---

## 5. 下载投影测试（S2C 下发）

**操作**：客户端 B 在 Litematica「服务端投影」列表选中 A 分享的投影，点 Load（下载到本地）。

**协议流程**（服务端日志）：

```
[received packet] REQUEST_LITEMATIC ← 客户端B（注：PacketType 路径 syncmatica:request_download）
（服务端本地有文件 → 启 UploadExchange 推文件）
[Sending packet]   SEND_LITEMATIC   → 客户端B（16384 字节/片，init 首片不等 RECEIVED 直接发）
[received packet] RECEIVED_LITEMATIC ← 客户端B
... （stop-and-wait 至传完）
[Sending packet]   FINISHED_LITEMATIC → 客户端B
```

**客户端 B**：Litematica 加载该投影，可正常预览/放置。

**成功判定**：完整下发 + 客户端加载成功（客户端侧 MD5 校验通过）。

**失败排查**：
- 客户端点 Load 无反应 → 服务端是否收到 `REQUEST_LITEMATIC`；`ServerCommunicationManager.handle(REQUEST_LITEMATIC)` 的 placement 查找（按 placement id）。
- 下载后客户端校验失败 → 同 §4 hash 算法检查（服务端文件 hash 与 metadata.hash 必须一致）。
- 客户端收不到任何 S2C 包 → `/syncmatica debug s2c` 切换 NMS/plugin-messaging 路径重测（见 §12）。
- **注意**：S2C 下发（`UploadExchange`）**不查配额**——即便 quota 启用，客户端 Load 不应被拦截（[22](22-syncmatica-mixin-migration.md) §8.1）。

---

## 6. 修改放置测试

**操作**：客户端 A（或 B）在 Litematica 放置配置里移动 / 旋转 / 镜像该服务端投影，确认提交。

**协议流程**（服务端日志）：

```
[received packet] MODIFY_REQUEST ← 客户端A（placement uuid）
[Sending packet]  MODIFY_REQUEST_ACCEPT → 客户端A        ← 占锁成功（ModifyExchangeServer.init）
（客户端调整位置后提交）
[received packet] MODIFY_FINISH  ← 客户端A（uuid + positionData）
[Sending packet]  MODIFY → 所有 broadcastTargets（声明 MODIFY feature 的客户端）
```

**客户端 B**：自动同步看到投影位置变更。

**placements.json**：该 placement 的 `origin`/`rotation`/`mirror` 字段更新（`lastModifiedBy` 若 ≠ owner 也写入），立即落盘。

**成功判定**：ACCEPT 回 A + MODIFY 广播 + `placements.json` 字段更新。

**失败排查**：
- MODIFY_REQUEST 收到但回 DENY → 检查 `ModifyExchangeServer.init` 的 placement 查找 + `modifyState` 锁（是否已有他人占用未释放）。
- MODIFY_FINISH 收到但未广播 → `handleExchange(ModifyExchangeServer)` 的广播循环；B 是否在 broadcastTargets。
- 客户端 B 不同步 → B 是否声明了 MODIFY feature（完整版客户端应有）；若无，服务端应退化发 REMOVE_SYNCMATIC + REGISTER_METADATA（[21](21-syncmatica-protocol.md) §5.3）。
- **并发修改**：A 改时 B 也请求 → B 应收到 DENY（锁被 A 占）。验证 `modifyState` 单写锁语义。

---

## 7. 删除投影测试

**操作**：客户端在服务端投影列表删除某投影。

**协议流程**：

```
[received packet] REMOVE_SYNCMATIC ← 客户端（placement uuid）
[Sending packet]  REMOVE_SYNCMATIC → 所有 broadcastTargets
```

**placements.json**：该 placement 记录移除（`SyncmaticManager.removePlacement` → `updateServerPlacement` → 立即 `saveServer`）。

> **文件 `<hash>.litematic` 是否删除**？——原版 `removePlacement` **只删 placement 记录，不删文件**（文件可能被其他 placement 共享 hash，或留作缓存）。Paper 行为一致；验证 `syncmatics/` 下文件仍在。

**客户端 B**：投影从列表消失。

**成功判定**：广播 REMOVE + `placements.json` 移除该条 + 文件保留。

---

## 8. 命令 load 测试

**操作**：把一个 `.litematic` 重命名为 `<uuid>.litematic`（文件名必须是 UUID，否则 `updateSyncmaticDir` 跳过）放入 `plugins/VeryMcProto/syncmatics/`，执行：
- `/syncmatica load`（加载目录下所有未注册的，`load_all`）
- `/syncmatica load <文件名去后缀>`（加载指定一个，`load_each`）

> **文件名 UUID 约束**：`updateSyncmaticDir` 用 `UUID.fromString(name)` 校验文件名（去 `.litematic` 后缀），非 UUID 命名会被静默跳过——这是内容寻址约定（hash 即文件名）。

**验证**：
- 命令返回加载计数（`§b<NN>§r Syncmatic file(s) found / loaded.`）。
- `placements.json` 新增记录（owner=发送方玩家；origin=玩家脚下；控制台执行则 owner=随机 UUID、origin=0,0,0）。
- 玩家执行时（有 ExchangeTarget）走 `comms.addPlacement(target, placement)` → 广播 REGISTER_METADATA；控制台执行无 target → 直接 `manager.addPlacement`（玩家进服握手时 CONFIRM_USER 下发）。
- 所有在线已握手客户端收到 REGISTER_METADATA（投影出现在列表）。
- **权限**：无 `syncmatica.command.load` 的玩家执行 `/syncmatica load` 被拒（`§c权限不足。`）；无 `load_each` 执行 `/syncmatica load <file>` 被拒。

**失败排查**：
- 文件未识别 → 文件名是否为合法 UUID；`SyncmaticaUtil.litematicPeek` 解析（`litematica/schematic/` peek 类）；peek 失败的文件不入候选。
- 加载但未广播 → 发送方是否有 ExchangeTarget（需是已握手的在线玩家）；`comms.addPlacement` 的广播循环。

---

## 9. 持久化测试

**操作**：
1. 分享若干投影（§4）。
2. **重启服务端**（`/stop` → 重启）。
3. 客户端重连。

**验证**：
- 重启后 `placements.json` 完整保留所有 placement（原子写：`placements.json.new` → 备份 `placements.json.bak` → 替换 `placements.json`）。
- 启动时 `loadServer` 读 JSON，对每条 placement 调 `correctMetadataFromPeek`（peek 文件修正 metadata）；若任一 `isDirty`（如 metadata 字段缺失被修正），整表回写并 WARN：`loadServer(): Found a dirty placements.json; re-saving with corrections.`（属正常自愈）。
- 客户端重连握手时，CONFIRM_USER 下发全部历史 placement（HANDSHAKE 日志可见 `placementCount=N`）。
- 客户端列表显示全部投影，可正常下载（文件仍在 `syncmatics/`）。

**失败排查**：
- 重启后 placement 丢失 → `SyncmaticManager.loadServer` 的 JSON 反序列化；`placements.json` 字段顺序是否与原版一致（[21](21-syncmatica-protocol.md) §7.1）；`ServerPlacement.fromJson(elem, playerIdentifierProvider)`。
- 重启后文件丢失 → 文件是否真的写到了 `syncmatics/`（§4 验证过）；是否被误删。
- `placements.json.bak` / `.new` 残留 → `SyncmaticaUtil.backupAndReplace` 流程；正常情况替换后只剩 `.bak`。

---

## 10. 配额测试

**准备**：编辑 `syncmatica-config.json` 设 `{ quota: { enabled: true, limit: 50000 } }`（50 KB，便于测试），`/syncmatica reload`。

**操作**：客户端分享一个 > 50 KB 的投影。

**协议流程**（服务端日志）：

```
[received packet] SEND_LITEMATIC ← 客户端（每片累加 bytesSent）
（当 bytesSent 累计 > limit → isOverQuota 返回 true）
DownloadExchange: close(true)（取消包 CANCEL_LITEMATIC）
[Sending packet] MESSAGE → 客户端（MessageType.ERROR + syncmatica.error.cancelled_transmit_exceed_quota）
→ 客户端聊天栏收到 "Syncmatica ERROR syncmatica.error.cancelled_transmit_exceed_quota"
```

**验证**：
- 传到 ~50 KB 时下载中断（`close(true)` 发 CANCEL_LITEMATIC + MESSAGE(ERROR)）。
- 文件未落地（`onClose`：`!isSuccessful() && Files.exists(downloadFile)` → `Files.deleteIfExists`）。
- 客户端收到配额超限提示。
- `progress` Map 记账（但失败不累计——`onClose` 仅 `isSuccessful()` 才 `progressQuota`）。

**边界**：
- **下载（S2C，UploadExchange）不受配额限制**——即便 quota 启用，客户端 Load 不应被拦（`UploadExchange` 无配额查询）。
- 重启后 `progress` 清零（不持久化，仅内存 Map）。
- **默认值**（生产）：`enabled=false`、`limit=40000000`（约 40 MB）。

---

## 11. 多玩家协同测试

**场景**（2+ 客户端 A、B、C 同时在线）：

| 步骤 | 期望 |
|---|---|
| A 分享投影 P | B、C 自动收到 REGISTER_METADATA，列表出现 P（A 进 broadcastTargets 后广播） |
| B 下载 P | A、C 无影响（下载是 B↔服务端私有 UploadExchange） |
| C 修改 P 位置 | A、B 收到 MODIFY 同步；C 修改时 A/B 若同时请求 → 收到 DENY（`modifyState` 锁） |
| A 删除 P | B、C 收到 REMOVE_SYNCMATIC，P 消失 |
| D 中途进服 | 握手后 CONFIRM_USER 下发当前全部 placement（D 看到剩余投影） |
| A 离服 | `onPlayerLeave` 清理 A 的 exchange 链 + 移出 targets/broadcastTargets；A 的进行中修改若未完成 → 释放锁 |

**关键验证点**：
- **修改锁全局唯一**：同一 placement 同一时刻只有一人能改（`modifyState` 按 hash 占锁）。
- **离服清理**：A 离服时其 ongoingExchanges 全部 `close(false)`，不残留（否则下次进服可能锁冲突）。
- **晚加入者补发**：D 进服时通过 CONFIRM_USER 拿到全量，不依赖「在线时才广播」。
- **broadcastTargets 成员资格**：仅握手 `succeed` 的 ExchangeTarget 才加入（`VersionHandshakeServer.succeed` → `handleExchange` 添入）。

---

## 12. 排错流程

遇到功能异常，按此顺序排查（与 Servux [10](10-testing-guide.md) §7 同构）：

1. **协议是否启用**？→ `/syncmatica status` 看协议状态；若 OFF → `/syncmatica enable`（不重启插件，重新握手在线玩家）。
2. **服务端是否收到包**？→ `/syncmatica debug on` + `cat all`（或单独 `network`/`packet`）看日志。没收到 → 通道注册 / `SyncmaticaHandler.receivePlayPayload` 的 `[Identifier][body]` 解析。
3. **包的 PacketType 是否正确**？→ 日志的 `type=` 值；注意路径字串拼写（`syncmatica:request_download` / `syncmatica:mesage`——**原版 MESSAGE 的 path 拼写就是缺 `s` 的 `mesage`**，Paper 版照抄保持兼容）。
4. **exchange 是否派发**？→ `onPacket` 是否找到 handler（`exchange 认领` 日志）；无人认领则走一次性 `handle`（`无 exchange 认领` 日志）。
5. **S2C 包客户端是否收到**？→ `/syncmatica debug s2c` 切 NMS/plugin-messaging 重测（路由诊断）。
6. **文件是否落地**？→ `syncmatics/` 目录 + hash 比对。
7. **placements.json 是否更新**？→ 每次变更即落盘（[20](20-syncmatica-architecture.md) §6.2）。
8. **广播是否发出**？→ `broadcastTargets` 是否含目标客户端（握手成功才加入）。
9. **客户端侧**：syncmatica + litematica + malilib 版本兼容；客户端日志（Fabric Loader.log）。

**常见症状→病因**：

| 症状 | 病因 |
|---|---|
| 客户端进服即踢 | `syncmatica:main` 通道未注册 / 包体解析错误导致客户端解码异常；或 S2C 路径与客户端不匹配（试 `/syncmatica debug s2c msg` 切 plugin messaging） |
| 进服无握手包 | 通道 outgoing 未注册；`receivePlayPayload` 解析失败；协议被 `/syncmatica disable` 软禁用（`/syncmatica status` 确认） |
| 分享后其他客户端看不到 | 分享者未在 broadcastTargets（握手未完成 / 失败）/ 广播循环漏 |
| 下载卡住 | stop-and-wait 应答缺失（RECEIVED 未发或客户端等不到下一片）；MD5 不匹配触发 `close(false)` |
| 修改不同步 | MODIFY feature 协商失败 / 广播用错 PacketType / 客户端无 MODIFY feature（应退化 REMOVE+REGISTER） |
| 重启后丢失 | placements.json 字段错 / `loadServer` 反序列化异常 / 文件未真正落地 |
| MD5 校验总失败 | hash 算法被改（必须 `UUID.nameUUIDFromBytes(md5.digest())`，type-3 UUID） |
| 配额未拦截 | `quota.enabled` 是否 true（`/syncmatica status` 看不到，看 `syncmatica-config.json` 或 reload）；仅 DownloadExchange 查询，UploadExchange 不查 |
| `/syncmatica load` 无文件 | 文件名非合法 UUID；peek 失败；文件已在 placements.json 注册 |
| 调试日志不输出 | master 与分类必须**同时**开（`/syncmatica debug on` + `cat all`）；与 servux 调试独立，互不影响 |
| 未知 PacketType 丢弃 | 客户端发了服务端未实现的类型 → `onPacket` 无 handler 命中且非一次性请求 → 静默丢弃；核对 PacketType 集合 |

---

## 13. 降级清单（已知不实现的功能）

| 功能 | 状态 | 影响 |
|---|---|---|
| 材料配送（material） | ⛔ 不实现 | 原版就是死代码，无协议、无 GUI 实际接入，客户端无感知 |
| RedirectFileStorage | ⛔ 不实现 | 仅客户端装饰器，服务端不需要 |
| 客户端 GUI 集成 | — | 由 syncmatica 客户端 mod 提供，服务端无关 |
| 单机 / Open-to-LAN | ⛔ 不适用 | Paper 恒 dedicated server |
| `/syncmatica save/upload/list`（误传的命令） | — | 原版本就无此命令；真实命令见 §2.2 |

> **无「改服务端行为」类降级**（对比 Servux EasyPlace / UpdateSuppression）：syncmatica 纯协议层，功能完整性 = 协议保真度，无降级损失。

---

## 14. 结果记录表

每次实测填写，归档到 `docs/research/` 或提交记录：

| 日期 | 构建 commit | 测试场景 (§) | 客户端版本 | 结果 | 日志/截图 | 备注 |
|---|---|---|---|---|---|---|
| YYYY-MM-DD | `<sha>` | §3 握手 | syncmatica-x + litematica-y | ✅/⚠️/❌ | `<path>` | |
| | | §4 分享（C2S 上传） | | | | |
| | | §5 下载（S2C 下发） | | | | |
| | | §6 修改 | | | | |
| | | §7 删除 | | | | |
| | | §8 load 命令 | | | | |
| | | §9 持久化 | | | | |
| | | §10 配额 | | | | |
| | | §11 多玩家 | | | | |

> **通过标准**：§3–§7 全 ✅ + §9 持久化 ✅ + §11 多玩家核心场景 ✅ → syncmatica 移植功能可用，可合并主线。§8/§10 为辅助验证。

---

> **回到上层**：[00-INDEX.md](00-INDEX.md) · [CLAUDE.md](../CLAUDE.md)
