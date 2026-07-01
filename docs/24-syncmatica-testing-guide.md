# 24 · Syncmatica 客户端兼容测试指南

> 本文档是 **syncmatica 移植 → Paper 1.21.11** 的客户端兼容实测指南。
> 实施蓝图与里程碑见 [23](23-syncmatica-implementation-plan.md)；架构/协议/迁移见 [20](20-syncmatica-architecture.md)/[21](21-syncmatica-protocol.md)/[22](22-syncmatica-mixin-migration.md)。
> 对照参考：Servux 客户端测试方法论见 [10](10-testing-guide.md)。

---

## 0. 测试目标与判官逻辑

**核心验收闭环**（[23](23-syncmatica-implementation-plan.md) §7 里程碑 M3–M6）：syncmatica 客户端进服 → **握手成功** → **分享投影**（C2S 上传）→ 其他客户端**下载**（S2C 下发）→ **修改放置**（位置同步）→ 多端状态一致。

**判官逻辑**：syncmatica 的功能是否正常，**看服务端日志的包流**（开启 `DebugService`，[22](22-syncmatica-mixin-migration.md) §8.2）+ **看服务端文件落地**（`syncmatics/<hash>.litematic` + `placements.json`）。客户端 GUI 行为是表象，协议包流与持久化才是真相——遇到「客户端没反应」先查服务端日志收没收到包，再查文件落地，最后才看客户端（参见 Servux [10](10-testing-guide.md) §2 判官逻辑）。

---

## 1. 测试环境准备

### 1.1 服务端

| 项 | 要求 |
|---|---|
| Paper | 1.21.11（与本项目一致） |
| 插件 | VeryMcProto（含 syncmatica 移植，P0–P9 完成） |
| Java | 21 |
| 配置 | `getDataFolder()/syncmatica-config.json`（首次启动自动生成）：`{ quota: {enabled: false, limit: 40000000}, debug: {doPacketLogging: true} }`——**测试期开 `doPacketLogging: true`** 看包流 |
| 端口 | 默认 25565；客户端直连 |

启动：`./gradlew runServer`（开发期）或部署 reobf jar 到正式服。

### 1.2 客户端

| Mod | 版本（来自 `fabric.mod.json` suggests） | 用途 |
|---|---|---|
| **Minecraft Fabric** | 1.21.11 + Fabric Loader | 基础 |
| **syncmatica** | LTS 1.21.11（`OriginImpl/syncmatica-LTS-1.21.11/` 对应版本） | 协议客户端（注入 Litematica GUI） |
| **Litematica** | `>=0.25.4- <0.27.0`（建议用 syncmatica 兼容的 LTS fork，如 sakura-ryoko） | 投影客户端 |
| **Malilib** | `>=0.27.4- <0.28.0`（Litematica 依赖） | Litematica 前置 |

> ⚠️ syncmatica `fabric.mod.json` 的 `breaks` 声明：malilib `<0.27.4-` / litematica `<0.25.4-` 会冲突。务必用足版本的 LTS fork。原版 `build.gradle` 用 `com.github.sakura-ryoko:malilib/litematica`，测试客户端建议同源。

**至少 2 个客户端账号**（用于多玩家协同测试，§11）。单机多开或两台机器均可。

### 1.3 投影文件准备

- 准备 2–3 个不同大小的 `.litematic` 文件（小：< 16KB 单片；中：~100KB 多片；大：> 1MB 测分片稳定性）。
- 用 Litematica 客户端在单机先做好投影，导出为 `.litematic`。

---

## 2. 启动检查（M2）

**操作**：启动 Paper 服务端（`runServer` 或正式服）。

**验证**：

| 检查项 | 期望 | 失败排查 |
|---|---|---|
| 控制台无异常 | `onEnable` 完成，无 stacktrace | 查 `SyncmaticaApp` 初始化；`placements.json` 读写权限 |
| 通道注册日志 | `[NETWORK] registerIncoming OK syncmatica:main` + `registerOutgoing OK` | `framework.debug.Debug` 的 NETWORK 分类 |
| 目录创建 | `plugins/VeryMcProto/syncmatics/` 目录存在 | `SyncmaticaContext` 的 `litematicFolder` 创建逻辑 |
| 配置生成 | `plugins/VeryMcProto/syncmatica-config.json` 存在（含 quota/debug） | `Context.loadConfiguration` |
| placements.json | 存在（空表 `{"placements":[]}` 或已有数据） | `SyncmaticManager.loadServer` |

---

## 3. 握手测试（M3）

**操作**：syncmatica 客户端进服。

**验证**（服务端 `doPacketLogging=true` 日志）：

```
S2C send  packet[type=REGISTER_VERSION] → 客户端          ← VersionHandshakeServer.init
C2S recv  packet[type=REGISTER_VERSION] ← 客户端          ← 客户端回版本
（若版本未命中默认集，多一组 FEATURE_REQUEST/FEATURE 往返）
S2C send  packet[type=CONFIRM_USER]   → 客户端            ← 下发全量 placement（首次为 0 个）
```

**客户端侧**：进服无报错；Litematica 主菜单的「服务端投影」入口可见（即使列表为空）。

**失败排查**：
- 客户端进服即踢 / 无握手包 → 检查 `syncmatica:main` 通道是否注册 outgoing；`SyncmaticaPacket` 的 `[Identifier][body]` 解析是否正确（[21](21-syncmatica-protocol.md) §1.2）。
- 收到 REGISTER_VERSION 但无 CONFIRM_USER → `VersionHandshakeServer.handle` 的 `fromVersionString` / FeatureSet 逻辑；`checkPartnerVersion` 是否误拒（仅应拒 `"0.0.1"`）。
- 客户端报「不兼容版本」→ 客户端 syncmatica 版本与服务端 MOD_VERSION 协商失败；确认服务端 `getFeatureSet()` 声明全集。

---

## 4. 分享投影测试（M4）

**操作**：客户端 A 加载一个 `.litematic` 到 Litematica，通过 syncmatica 提供的「Share」入口（Litematica 放置列表中对某 placement 的操作按钮）分享。

> GUI 入口：syncmatica 通过 `litematica_mixin`（`MixinWidgetSchematicPlacement` / `ButtonListenerShare` 等）在 Litematica 的放置列表加「Share」按钮。具体位置以客户端实际 GUI 为准。

**验证**（服务端日志 + 文件）：

```
C2S recv  packet[type=REGISTER_METADATA] ← 客户端A         ← 含 placement metadata
（服务端本地无该 hash 文件）
S2C send  packet[type=REQUEST_LITEMATIC] → 客户端A         ← DownloadExchange.init 要文件
C2S recv  packet[type=SEND_LITEMATIC]   ← 客户端A（16KB 片）
S2C send  packet[type=RECEIVED_LITEMATIC] → 客户端A        ← 应答
... （循环至文件传完）
C2S recv  packet[type=FINISHED_LITEMATIC] ← 客户端A
（MD5 校验通过 → succeed）
S2C send  packet[type=REGISTER_METADATA] → 所有 broadcastTargets  ← 广播给其他在线客户端
```

**文件落地**：
- `plugins/VeryMcProto/syncmatics/<hash>.litematic` 存在，大小与源文件一致。
- `plugins/VeryMcProto/placements.json` 新增一条 placement 记录（含 id/file_name/hash/origin/owner 等）。

**客户端 B（若在线）**：自动收到 REGISTER_METADATA，Litematica 放置列表出现该服务端投影。

**失败排查**：
- 收到 REGISTER_METADATA 但无 REQUEST_LITEMATIC → `ServerCommunicationManager.handle` 的 `getLocalState` 判定；`FileStorage.getLocalState` 是否误判文件已存在。
- 文件传一半中断 → 检查 `DownloadExchange` 的 stop-and-wait 是否正确应答 RECEIVED；MD5 校验失败会 `close(false)` 删文件。
- 文件落地但 hash 不匹配 → `SyncmaticaUtil.createChecksum` 必须是 MD5→type-3 UUID（[21](21-syncmatica-protocol.md) §7.3）；检查是否误用其他算法。
- 分享成功但其他客户端没收到广播 → 确认分享者在 `broadcastTargets`（握手成功才加入）；`addPlacement` 的广播循环。

---

## 5. 下载投影测试（M5）

**操作**：客户端 B 在 Litematica「服务端投影」列表选中 A 分享的投影，点 Load（下载到本地）。

**验证**（服务端日志）：

```
C2S recv  packet[type=REQUEST_LITEMATIC] ← 客户端B（注：path=syncmatica:request_download）
（服务端本地有文件）
S2C send  packet[type=SEND_LITEMATIC]   → 客户端B（16KB 片）
C2S recv  packet[type=RECEIVED_LITEMATIC] ← 客户端B
... （循环至传完）
S2C send  packet[type=FINISHED_LITEMATIC] → 客户端B
```

**客户端 B**：Litematica 加载该投影，可正常预览/放置。

**失败排查**：
- 客户端点 Load 无反应 → 服务端是否收到 `request_download`（注意 path 拼写）；`ServerCommunicationManager.handle(REQUEST_LITEMATIC)` 的 placement 查找（按 id）。
- 下载后客户端校验失败 → 同 §4 的 hash 算法检查。
- **注意**：S2C 下发（UploadExchange）**不查配额**，即便 quota 启用也不应拦截下载（[22](22-syncmatica-mixin-migration.md) §8.1）。

---

## 6. 修改放置测试（M6）

**操作**：客户端 A（或 B）在 Litematica 放置配置里移动 / 旋转 / 镜像该服务端投影，确认提交。

**验证**（服务端日志）：

```
C2S recv  packet[type=MODIFY_REQUEST]  ← 客户端A（placement uuid）
S2C send  packet[type=MODIFY_REQUEST_ACCEPT] → 客户端A     ← 占锁成功
（客户端调整位置后提交）
C2S recv  packet[type=MODIFY_FINISH]   ← 客户端A（uuid + positionData）
S2C send  packet[type=MODIFY]          → 所有 broadcastTargets（有 MODIFY feature）
```

**客户端 B**：自动同步看到投影位置变更。

**placements.json**：该 placement 的 origin/rotation/mirror 字段更新（`lastModifiedBy` 若 ≠ owner 也写入）。

**失败排查**：
- MODIFY_REQUEST 收到但回 DENY → 检查 `ModifyExchangeServer.init` 的 placement 查找 + `modifyState` 锁（是否已有他人占用未释放）。
- MODIFY_FINISH 收到但未广播 → `handleExchange(ModifyExchangeServer)` 的广播循环；客户端 B 是否在 broadcastTargets。
- 客户端 B 不同步 → B 是否声明了 MODIFY feature（完整版客户端应有）；若无，服务端应退化发 REMOVE_SYNCMATIC + REGISTER_METADATA（[21](21-syncmatica-protocol.md) §5.3）。
- **并发修改**：A 改时 B 也请求 → B 应收到 DENY（锁被 A 占）。验证 `modifyState` 单写锁语义。

---

## 7. 删除投影测试

**操作**：客户端在服务端投影列表删除某投影（或服务端命令删除）。

**验证**：

```
C2S recv  packet[type=REMOVE_SYNCMATIC] ← 客户端（placement uuid）
S2C send  packet[type=REMOVE_SYNCMATIC] → 所有 broadcastTargets
```

**placements.json**：该 placement 记录移除。**文件 `<hash>.litematic` 是否删除**？——原版 `removePlacement` **只删 placement 记录，不删文件**（文件可能被其他 placement 共享 hash，或留作缓存）。验证 Paper 行为一致。

**客户端 B**：投影从列表消失。

---

## 8. 命令 load 测试（M8）

**操作**：把一个 `.litematic` 放入 `plugins/VeryMcProto/syncmatics/`，执行：
- `/syncmatica load`（加载目录下所有未注册的）
- `/syncmatica load <文件名>`（加载指定一个）

**验证**：
- 命令返回加载计数。
- `placements.json` 新增记录。
- 所有在线客户端收到 REGISTER_METADATA（投影出现在列表）。
- 权限：无 `syncmatica.command.load` 权限的玩家执行被拒。

**失败排查**：
- 文件未识别 → `SyncmaticaUtil.litematicPeek` 解析（`litematica/schematic/` peek 类）；文件名是否需 `.litematic` 后缀。
- 加载但未广播 → `comms.addPlacement` 的广播（需有在线握手客户端）。

---

## 9. 持久化测试（M7）

**操作**：
1. 分享若干投影（§4）。
2. **重启服务端**（`/stop` → 重启）。
3. 客户端重连。

**验证**：
- 重启后 `placements.json` 完整保留所有 placement。
- 客户端重连握手时，CONFIRM_USER 下发全部历史 placement（日志见 `send packet[type=CONFIRM_USER]` + placement 计数）。
- 客户端列表显示全部投影，可正常下载（文件仍在 `syncmatics/`）。

**失败排查**：
- 重启后 placement 丢失 → `SyncmaticManager.loadServer` 的 JSON 反序列化；`placements.json` 字段顺序是否与原版一致（[21](21-syncmatica-protocol.md) §7.1）。
- 重启后文件丢失 → 文件是否真的写到了 `syncmatics/`（§4 验证过）；是否被误删。
- `isDirty` 自愈误触发 → `fromJson` 的字段修正逻辑；首次加载若 `display_name` 缺失会 `dirty=true` 回写，属正常。

---

## 10. 配额测试

**准备**：`syncmatica-config.json` 设 `{ quota: { enabled: true, limit: 50000 } }`（50KB，便于测试）。

**操作**：客户端分享一个 > 50KB 的投影。

**验证**：
- 传到 ~50KB 时，服务端日志见 DownloadExchange `close(true)` + 发 `MESSAGE(ERROR)`（path=`syncmatica:mesage`）。
- 客户端收到配额超限提示。
- 文件未落地（`close(false)` 路径删下载文件）或落地不完整被删。
- `progress` Map 记账（但失败不累计，仅成功才 `progressQuota`）。

**边界**：
- 下载（S2C，UploadExchange）**不受配额限制**——即便 quota 启用，客户端 Load 不应被拦。
- 重启后 `progress` 清零（不持久化）。

---

## 11. 多玩家协同测试

**场景**（2+ 客户端 A、B、C 同时在线）：

| 步骤 | 期望 |
|---|---|
| A 分享投影 P | B、C 自动收到 REGISTER_METADATA，列表出现 P |
| B 下载 P | A、C 无影响（下载是 B↔服务端私有） |
| C 修改 P 位置 | A、B 收到 MODIFY 同步；C 修改时 A/B 若同时请求 → 收到 DENY |
| A 删除 P | B、C 收到 REMOVE_SYNCMATIC，P 消失 |
| D 中途进服 | 握手后 CONFIRM_USER 下发当前全部 placement（D 看到剩余投影） |
| A 离服 | `onPlayerLeave` 清理 A 的 exchange；A 的进行中修改若未完成 → 释放锁 |

**关键验证点**：
- **修改锁全局唯一**：同一 placement 同一时刻只有一人能改（`modifyState` 按 hash 占锁）。
- **离服清理**：A 离服时其 ongoingExchanges 全部 `close(false)`，不残留（否则下次进服可能锁冲突）。
- **晚加入者补发**：D 进服时通过 CONFIRM_USER 拿到全量，不依赖「在线时才广播」。

---

## 12. 排错流程

遇到功能异常，按此顺序排查（与 Servux [10](10-testing-guide.md) §7 同构）：

1. **服务端是否收到包**？→ `doPacketLogging=true` 看日志。没收到 → 通道注册 / `SyncmaticaHandler.receivePlayPayload` 的 `[Identifier][body]` 解析。
2. **包的 PacketType 是否正确**？→ 日志的 `type=` 值；注意 `request_download`/`mesage` 拼写。
3. **exchange 是否派发**？→ `CommunicationManager.onPacket` 是否找到 handler；`checkPacket` peek UUID 是否正确（未消费 readerIndex）。
4. **文件是否落地**？→ `syncmatics/` 目录 + hash 比对。
5. **placements.json 是否更新**？→ 每次变更即落盘（[20](20-syncmatica-architecture.md) §6.2）。
6. **广播是否发出**？→ `broadcastTargets` 是否含目标客户端（握手成功才加入）。
7. **客户端侧**：syncmatica + litematica 版本兼容；客户端日志（Fabric Loader.log）。

**常见症状→病因**：

| 症状 | 病因 |
|---|---|
| 客户端进服即踢 | `syncmatica:main` 通道未注册 / 包体解析错误导致客户端解码异常 |
| 分享后其他客户端看不到 | 分享者未在 broadcastTargets（握手未完成）/ 广播循环漏 |
| 下载卡住 | stop-and-wait 应答缺失（RECEIVED 未发或客户端等不到下一片） |
| 修改不同步 | MODIFY feature 协商失败 / 广播用错 PacketType |
| 重启后丢失 | placements.json 字段顺序错 / loadServer 反序列化异常 |
| MD5 校验总失败 | hash 算法被改（必须 MD5→type-3 UUID） |

---

## 13. 降级清单（已知不实现的功能）

| 功能 | 状态 | 影响 |
|---|---|---|
| 材料配送（material） | ⛔ 不实现 | 原版就是死代码，无协议、无 GUI 实际接入，客户端无感知 |
| RedirectFileStorage | ⛔ 不实现 | 仅客户端装饰器，服务端不需要 |
| 客户端 GUI 集成 | — | 由 syncmatica 客户端 mod 提供，服务端无关 |
| 单机 / Open-to-LAN | ⛔ 不适用 | Paper 恒 dedicated server |
| `/syncmatica save/upload/list` | — | 原版本就无此命令 |

> **无「改服务端行为」类降级**（对比 Servux EasyPlace / UpdateSuppression）：syncmatica 纯协议层，功能完整性 = 协议保真度，无降级损失。

---

## 14. 结果记录表

每次实测填写，归档到 `docs/research/` 或提交记录：

| 日期 | 构建 commit | 测试场景 (§) | 客户端版本 | 结果 | 日志/截图 | 备注 |
|---|---|---|---|---|---|---|
| YYYY-MM-DD | `<sha>` | M3 握手 | syncmatica-x + litematica-y | ✅/⚠️/❌ | `<path>` | |
| | | M4 分享 | | | | |
| | | M5 下载 | | | | |
| | | M6 修改 | | | | |
| | | M7 持久化 | | | | |
| | | §11 多玩家 | | | | |

> **通过标准**：M3–M6 全 ✅ + §11 多玩家核心场景 ✅ + M7 持久化 ✅ → syncmatica 移植功能可用，可合并主线。

---

> **回到上层**：[00-INDEX.md](00-INDEX.md) · [CLAUDE.md](../CLAUDE.md)
