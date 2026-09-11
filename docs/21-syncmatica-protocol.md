# 21 · Syncmatica 网络协议详解（已实现）

> **状态**：syncmatica（投影共享）已 100% 完整实现并实测通过。本文是【已实现说明】——所有协议字段、收发路径、Exchange 状态机、分片机制均已落地，对应代码在 `src/main/java/verymc/top/veryMcProto/mod/syncmatica/`。
> **原版对照**：`OriginImpl/syncmatica-LTS-26.1/src/main/java/ch/endte/syncmatica/`（逐行对照的权威实现；26.1 wire 零变化，自 1.21.11 迁移）。
> **相关文档**：架构总览 [20](20-syncmatica-architecture.md)；Mixin→Bukkit 映射与降级 [22](22-syncmatica-mixin-migration.md)；实现总览 [23](23-syncmatica-implementation-plan.md)；测试 [24](24-syncmatica-testing-guide.md)；项目权威说明 [../AGENTS.md](../AGENTS.md)。
> **字段语义对照**：syncmatica 是双端 mod，同仓库的 `communication/ClientCommunicationManager.java` + 各 `*Client` Exchange 即协议接收端。本文所有字段顺序均已对照客户端 `receiveMetaData` / `receivePositionData` 确认一致。

---

## 1. 通道模型：单物理通道 + 逻辑 PacketType 复用

### 1.1 与 Servux 的根本差异

| | Servux | Syncmatica |
|---|---|---|
| 物理通道数 | 5（`servux:main` / `entity_data` / ...） | **1**（`syncmatica:main`，C2S + S2C 共用同一 ID） |
| 包体结构 | `[packetType VarInt][body]`（每通道独立） | **`[逻辑通道 Identifier][body bytes]`**（单通道内复用） |
| 消息区分 | 通道本身就是消息类别 | 第一字段 `Identifier` 决定消息类别（18 种） |

物理通道 `NETWORK_ID = Identifier("syncmatica", "main")`（`SyncmaticaReference.java:29`），C2S 与 S2C 共用。

### 1.2 物理包体复合结构 `[Identifier][body]`

`syncmatica:main` 通道的 `CustomPacketPayload` data 字段 = 一个原版 `SyncmaticaPacket` 序列化：

```
┌─────────────────────────────────────────────────────────┐
│ Clientbound/ServerboundCustomPayloadPacket              │
│   payload.type = syncmatica:main                        │
│   payload.data（FriendlyByteBuf）:                       │
│     ┌──────────────────────────────────────────────┐    │
│     │ [逻辑通道 Identifier][body bytes...]          │    │  ← 原版 SyncmaticaPacket.toPacket
│     └──────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

- 原版 `SyncmaticaPacket.toPacket(output)`：`output.writeIdentifier(channel); output.writeBytes(body.copy())`
- 原版 `SyncmaticaPacket.fromPacket(input)`：`new SyncmaticaPacket(input.readIdentifier(), new FriendlyByteBuf(input.readBytes(readableBytes)))`

> 🔑 **复合包体命门**：物理通道的字节流**不是**「body 直接铺平」，而是 `[Identifier][body]`。解析时**必须先 `readIdentifier()`**（VarInt 长度前缀的 UTF 字符串）得 PacketType，剩余字节才 = body。发送时反向：`buf = writeIdentifier(PacketType.id) + writeBytes(body)`。**切勿**当成 servux 那样「byte[] 直接是 body」处理。

### 1.3 C2S 接收路径（已实现）

`network/SyncmaticaHandler.java`（Paper 新增，实现 framework 的 `IPluginServerPlayHandler`），对应原版 `ServerPlayHandler.receiveSyncPayload` + `IServerPlay` mixin 桥接。`receivePlayPayload(data, player)` 流程（`SyncmaticaHandler.java:65-106`）：

1. **软禁用检查**：`context.isProtocolEnabled()` 为 false → 直接吞包返回（通道仍注册避免 Paper 踢人，但不处理）。
2. **空包防御**：`data` 为 null 或 `readableBytes <= 0` → 丢弃。
3. **读逻辑通道**：`data.readIdentifier()` → 得 `logicChannel`；解析异常 → 警告并丢弃。
4. **PacketType 查表**：`PacketType.getType(logicChannel)`；返回 null（未知 PacketType）→ 警告 + 丢弃。
5. **切出 body**：剩余字节 `new FriendlyByteBuf(data.readBytes(readableBytes()))` 即 body。
6. **派发**：`comMan.getOrCreateTarget(bukkitPlayer)` → `comMan.onPacket(target, type, body)`。

> 无需原版的 `IServerPlay` mixin：Paper 由 framework `ProtocolChannel` 的 `onPluginMessageReceived` 收到 `byte[]`，wrap 成 `FriendlyByteBuf` 后调本 handler。`encodeWithSplitter` 在本 handler 为**空实现**（syncmatica 文件分片走 exchange 自写 stop-and-wait，不用 `PacketSplitter`，见 §6）。

### 1.4 S2C 发送路径（已实现，NMS 直发）

`communication/ExchangeTarget.java` 的 `sendPacket(type, byteBuf, context)`（`ExchangeTarget.java:77-121`）：

1. `context.getDebugService().logSendPacket(type, persistentName)` 记账。
2. **构造复合包体**：`body = readableBytes(byteBuf)`；`out = buffer(body.length+8)`；`out.writeIdentifier(type.getId()); out.writeBytes(body)`；`bytes = extractAndRelease(out)`。
3. **路由选择**（`S2C_VIA_NMS` volatile 开关，默认 `true`）：
   - **NMS 路径**（默认）：`sendViaNms(bytes)`（`:134-151`）→ `Nms.toNms(player).connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(SyncmaticaReference.NETWORK_ID, bytes)))`。
   - **plugin messaging 路径**（fallback）：`ChannelManager.instance().send(NETWORK_ID, player, bytes)`。注：框架层 `ProtocolChannel.send` 引入「同通道 C2S 证明」NMS 兜底（2026-09-08）后，此对照组在「客户端未声明但已在 `syncmatica:main` 发过 C2S」场景会自动走 NMS `DiscardedPayload`（两路 wire 逐字等价、均可达），不再是纯 plugin messaging 对照；仅「已声明」场景仍为真正的 sendPluginMessage 路径。
4. 失败 → 警告日志。

> 🔑 **S2C 走 NMS 直发的实证理由**（`ExchangeTarget.java:57-67` 注释）：plugin messaging（`sendPluginMessage`）的 S2C wire 格式，纯 Fabric 客户端（syncmatica）**收不到**——客户端零响应、零 C2S 回包。而 NMS `DiscardedPayload` 直发（同 JEI 模块 `JeiPacketSender.send` 路径，原 `RecipeSyncHandler.sendPayload`——2026-09 上游重做后易主）已验证 Fabric 客户端可解码（JEI 配方同步实测通过）。`DiscardedPayload` 本身即 vanilla payload 类型，序列化时由 Paper 注册的 codec 原样写出 bytes，不会被强转拒绝。客户端用其自行注册的 `SyncmaticaPacket.Payload.CODEC`（按 `syncmatica:main` 查得）解码 payload data = `[Identifier][body]`，与原版 Fabric 服务端发的 wire 一致。
>
> 可经 `/syncmatica debug s2c nms|msg` 运行时切换路径做对比诊断。

---

## 2. PacketType 全表（18 个逻辑消息）

`network/PacketType.java`。每个枚举对应一个 `Identifier`（`namespace=syncmatica`，path 见下）。**path 与枚举名不严格对应，逐字照抄否则客户端认不出**：

| 枚举名 | Identifier path | 方向 | 用途 |
|---|---|---|---|
| `REGISTER_METADATA` | `syncmatica:register_metadata` | 双向 | 创建/广播一个 placement 的完整 metadata |
| `CANCEL_SHARE` | `syncmatica:cancel_share` | S→C | 分享失败通知（客户端据此取消上传） |
| `REQUEST_LITEMATIC` | `syncmatica:`**`request_download`** ⚠️ | 双向 | 请求下载投影文件（path 拼写与枚举名不同） |
| `SEND_LITEMATIC` | `syncmatica:send_litematic` | 文件发送方→接收方 | 传输一片文件（16KB） |
| `RECEIVED_LITEMATIC` | `syncmatica:received_litematic` | 接收方→发送方 | 确认收到一片，触发下一片（stop-and-wait） |
| `FINISHED_LITEMATIC` | `syncmatica:finished_litematic` | 发送方→接收方 | 文件传输结束标记 |
| `CANCEL_LITEMATIC` | `syncmatica:cancel_litematic` | 双向 | 取消进行中的上传/下载 |
| `REMOVE_SYNCMATIC` | `syncmatica:remove_syncmatic` | 双向 | 删除一个 placement |
| `REGISTER_VERSION` | `syncmatica:register_version` | 双向 | 版本握手（交换 MOD_VERSION） |
| `CONFIRM_USER` | `syncmatica:confirm_user` | S→C | 握手成功，下发全量 placement |
| `FEATURE_REQUEST` | `syncmatica:feature_request` | 双向 | 请求对端 FeatureSet |
| `FEATURE` | `syncmatica:feature` | 双向 | 上报自身 FeatureSet |
| `MODIFY` | `syncmatica:modify` | S→C | 广播 placement 位置变更结果 |
| `MODIFY_REQUEST` | `syncmatica:modify_request` | C→S | 客户端请求修改放置 |
| `MODIFY_REQUEST_DENY` | `syncmatica:modify_request_deny` | S→C | 拒绝修改（被他人占用等） |
| `MODIFY_REQUEST_ACCEPT` | `syncmatica:modify_request_accept` | S→C | 接受修改（占锁成功） |
| `MODIFY_FINISH` | `syncmatica:modify_finish` | C→S | 客户端修改完成，提交最终位置 |
| `MESSAGE` | `syncmatica:`**`mesage`** ⚠️ | 双向 | 显示消息（path 是原版拼写错误，注释「can't fix the typo here lol」`PacketType.java:90-92`） |

> ⚠️ **两个拼写陷阱（原版继承，不可改）**：`REQUEST_LITEMATIC`→`request_download`、`MESSAGE`→`mesage`。改了客户端认不出。本实现的枚举构造已逐字用 path 值（`PacketType.java:29,90`）。

---

## 3. Feature 协商

### 3.1 Feature 枚举（9 项）

`Feature.java`：`CORE` / `FEATURE` / `MODIFY` / `MESSAGE` / `QUOTA` / `DEBUG` / `CORE_EX` / `VERSION` / `DISPLAY_NAME`。其中 **4 个直接影响协议字段编码**（§4）：`DISPLAY_NAME` / `CORE_EX` / `VERSION` / `MODIFY`。

### 3.2 FeatureSet 序列化（已实现）

`communication/FeatureSet.java`。**不是二进制位图**，而是 `\n` 分隔的 Feature 枚举名拼成的**单一 UTF 字符串**：

```
例：完整版 FeatureSet → "CORE\nFEATURE\nMODIFY\nMESSAGE\nQUOTA\nDEBUG\nCORE_EX\nVERSION\nDISPLAY_NAME"
```

- `toString()`（`:46-55`）：`\n` join 各 `feature.toString()`。
- `fromString(s)`（`:35-44`）：按 `\n` split，逐个 `Feature.fromString` 加入集（未知名忽略，不报错）。
- 传输：作为一个 `writeUtf(readUtf)` 字段（`FeatureExchange.java:55,35`），受 `PACKET_MAX_STRING_SIZE = FriendlyByteBuf.MAX_STRING_LENGTH = 32767` 限制。

### 3.3 版本默认集（已实现）

`FeatureSet.java:65-68`：仅一条 `"0.1" → {CORE}`。`fromVersionString(version)`（`:21-33`）用正则 `^\d+(\.\d+){2,4}$` 校验后**逐级去掉末段**查表（`0.1.0` → 查不到 → 去成 `0.1` → 命中 `{CORE}`）；不命中返回 null。

**本实现的关键决策**（`SyncmaticaReference.java:25-26`）：`MOD_VERSION` = 插件版本（`26.1.2-b3` 式，`-b` 构建号后缀永不命中 `^\d+(\.\d+){2,4}$` 正则），使 `fromVersionString` 返回 null → **强制双方走 FEATURE 交换** → 双方用全集 FeatureSet（MODIFY/DISPLAY_NAME/CORE_EX/VERSION 全开）。这保证 metadata/position 编码所有可选字段都下发，功能完整。

> 对端若是真原版 `0.1.x` 客户端，`fromVersionString` 命中 `{CORE}` → 只编码 CORE 子集（无 DISPLAY_NAME/CORE_EX/VERSION/MODIFY 字段），客户端镜像 `receiveMetaData` 缺失字段用默认值兜底，仍能工作。

### 3.4 握手完整时序（已实现）

```
服务端                                          客户端
  │  onPlayerJoin → VersionHandshakeServer.init   │
  │ ──────── REGISTER_VERSION(服务端版本) ──────→  │
  │                                               │  ClientCommMgr.handle 创建 VersionHandshakeClient 并喂包
  │                                               │  checkPartnerVersion + fromVersionString(服务端版本)
  │                                               │    ├ 命中默认集 → setFeatureSet
  │                                               │    └ 未命中 → requestFeatureSet
  │ ←──────── FEATURE_REQUEST（若未命中）────────  │
  │ ────────────── FEATURE(服务端FeatureSet) ───→  │
  │            （或客户端命中默认集，直接回版本）    │
  │ ←──────── REGISTER_VERSION(客户端版本) ──────  │
  │  checkPartnerVersion + fromVersionString(客户端版本)
  │    ├ 命中 → setFeatureSet → onFeatureSetReceive
  │    └ 未命中 → requestFeatureSet（同上 FEATURE 往返）
  │ ───────── CONFIRM_USER(count + 全量 metadata) →│
  │  succeed → broadcastTargets.add(client)        │  receiveMetaData × count → addPlacement → succeed
```

- `checkPartnerVersion(version)`（`SyncmaticaContext`）：**仅拒绝 `"0.0.1"`**（原版行为）。
- `onFeatureSetReceive`（`VersionHandshakeServer.java:73-89`）：发 `CONFIRM_USER` = `writeInt(placementCount)` + 逐个 `putMetaData(p, buf, partner)` → `succeed()`。
- 握手成功后客户端才算「已确认用户」，进入 `broadcastTargets`，此后服务端任何 placement 变更都会广播给它。

---

## 4. Metadata 与 Position 字段布局（已实现）

协议最核心的字段表。`putMetaData` / `putPositionData` 在 `CommunicationManager.java:112-171`，镜像 `receiveMetaData` / `receivePositionData` 在 `:172-300`（上游 26.1 同构 `:86-146` / `:147-224`）。

### 4.1 REGISTER_METADATA / CONFIRM_USER 中的 placement 编码

```
putMetaData(metaData, buf, target)                          // CommunicationManager.java:112-138
├─ writeUUID(id)                          placement UUID
├─ writeUtf(getCleanFileName())           基础文件名（剥路径前缀——ServerPlacement.getCleanFileName 取 `[^/\]+$` 末段，防目录穿越；上游同源，紧邻留有被注释停用的 sanitizeFileName 旧线）
├─ writeUUID(hash)                        文件内容 MD5→UUID
├─ [若 target 有 DISPLAY_NAME]
│   └─ writeUtf(name)                     显示名（litematic 内的 Display Name）
├─ [若 target 有 CORE_EX]
│   ├─ writeUUID(owner.uuid)
│   ├─ writeUtf(owner.name)
│   ├─ writeUUID(lastModifiedBy.uuid)
│   └─ writeUtf(lastModifiedBy.name)
├─ [若 target 有 VERSION]
│   ├─ writeVarInt(litematicVersion)
│   └─ writeVarInt(dataVersion)
└─ putPositionData(metaData, buf, target)                  // 接 §4.2
```

### 4.2 putPositionData（origin + 朝向 + 子区域）

```
putPositionData(metaData, buf, target)                     // CommunicationManager.java:139-171
├─ writeBlockPos(position)                origin 坐标
├─ writeUtf(dimensionId)                  维度（如 "minecraft:overworld"）
├─ writeInt(rotation.ordinal())           Rotation 枚举序号（0..3）
├─ writeInt(mirror.ordinal())             Mirror 枚举序号（0..3）
└─ [若 target 有 CORE_EX]
    ├─ writeInt(regionCount)              子区域修改数（无修改则 0 并 return）
    └─ × regionCount:
        ├─ writeUtf(name)                 子区域名
        ├─ writeBlockPos(position)
        ├─ writeInt(rotation.ordinal())
        └─ writeInt(mirror.ordinal())
```

> ⚠️ **字段顺序依赖握手阶段已确定的 FeatureSet**。`onFeatureSetReceive` 在 `setFeatureSet` **之后**才调（`VersionHandshakeServer.handle` 命中默认集分支 `:60-64`，或 `FeatureExchange.handle` 收 FEATURE 后 `:37-38`），故 `putMetaData` 调用时 FeatureSet 必然已就绪。
>
> ⚠️ **Rotation / Mirror 用 ordinal 传输**（非 name）。原版注释（`CommunicationManager.java:143-146`，上游 26.1 `:118-121` 同文）说明这是有意为之——传输「非修改性枚举」给同应用另一实例，不关心序号持久性。本实现用 `net.minecraft.world.level.block.Rotation` / `Mirror` 的 `values()` 数组（`CommunicationManager.java:51-52`）经 `safeRotation` / `safeMirror`（`:309-325`）校验还原（越界值抛 `IllegalArgumentException` 被 `ProtocolChannel` try 兜底丢弃，与原版隐式 AIOOBE 表面行为一致），枚举顺序与原版一致。

### 4.3 接收镜像（已实现，对照确认）

`receiveMetaData`（`:172-223`）严格按 §4.1 顺序读取，缺失 `DISPLAY_NAME` 时 `displayName = fileName` 兜底（`:189`）；缺失 `CORE_EX` 时 `owner = lastModifiedBy = MISSING_PLAYER`（`:179-180`）；缺失 `VERSION` 时走无版本构造（`:212-214`）。`receivePositionData`（`:224-300`）按 §4.2 顺序，CORE_EX 子区域逐个 `subRegionData.modify(name, pos, rot, mir)`。**`putXxx` 与此镜像逐字段对应**。

---

## 5. Exchange 状态机详解（已实现）

基类 `AbstractExchange`（`communication/exchange/AbstractExchange.java`）：状态 `finished`/`success`；`close(notifyPartner)` 翻失败状态→`onClose`→可选 `sendCancelPacket`；`succeed()` 翻成功状态→`onClose`（不发 cancel）。

> 🔑 **checkUUID peek 两段式**（`AbstractExchange.java:77-83`）：`checkPacket` 用 `checkUUID` 记录 `readerIndex` → 读 UUID → **回退**（不消费），供无副作用判定当前包是否归本 exchange；`handle` 第一行才 `readUUID()` 真正消费。这是同一玩家多个文件传输 exchange 共存时正确路由的关键。

### 5.1 VersionHandshakeServer（版本握手）

`communication/exchange/VersionHandshakeServer.java`，继承 `FeatureExchange`。

| 阶段 | 收/发 | PacketType | body 字段 |
|---|---|---|---|
| `init()` | 发 | `REGISTER_VERSION` | `writeUtf(MOD_VERSION)`（`:92-99`） |
| 收 | 收 | `REGISTER_VERSION` | `readUtf(版本)` → `checkPartnerVersion` → `fromVersionString`：命中默认集则 `setFeatureSet + onFeatureSetReceive`，未命中则 `requestFeatureSet`（`:36-70`） |
| `onFeatureSetReceive` | 发 | `CONFIRM_USER` | `writeInt(placementCount)` + × count `putMetaData`（`:73-89`）→ `succeed()` |

- `checkPacket`：`REGISTER_VERSION` 或 `super.checkPacket`（FEATURE_REQUEST/FEATURE，`:29-33`）。
- 版本不兼容 → `close(false)`（**不发 cancel**，避免给不兼容对端发它不认识的包，`:43-52`）。

### 5.2 FeatureExchange（Feature 协商抽象基类）

`communication/exchange/FeatureExchange.java`，是 `VersionHandshakeServer` 的父类。

| 收/发 | PacketType | body 字段 |
|---|---|---|
| 收 `FEATURE_REQUEST` | → 调 `sendFeatures()`（`:30-32`） | — |
| `sendFeatures()` 发 | `FEATURE` | `writeUtf(featureSet.toString(), MAX_STRING)`（`:50-57`） |
| 收 `FEATURE` | → `readUtf` → `FeatureSet.fromString` → `setFeatureSet` → `onFeatureSetReceive()`（`:33-39`） | — |
| `requestFeatureSet()` 发 | `FEATURE_REQUEST` | 空 body（`:44-48`） |

`onFeatureSetReceive` 默认 `succeed()`，被 `VersionHandshakeServer` 重写为发 CONFIRM_USER。

### 5.3 DownloadExchange ↔ UploadExchange（文件传输对）

**严格 stop-and-wait（请求-应答）分片**。`BUFFER_SIZE = 16384`（`UploadExchange.java:26`，注释：32767 是 custom payload 上限，32768 会超，故取半）。

#### UploadExchange（发送方，`communication/exchange/UploadExchange.java`）

服务端发文件给客户端（Load），或对客户端 REQUEST_LITEMATIC 的回应方向。

| 阶段 | 收/发 | PacketType | body 字段 |
|---|---|---|---|
| 构造 | — | — | 打开 `FileInputStream`（`:31-36`），`buffer = new byte[16384]`（`:29`） |
| `init()` | — | — | 调 `send()`（**首次不等 RECEIVED，直接发第一片**，`:106`） |
| 收 | 收 | `RECEIVED_LITEMATIC` | `readUUID`（消费）→ `send()` 发下一片（`:50-57`） |
| 收 | 收 | `CANCEL_LITEMATIC` | `readUUID`（消费）→ `close(false)`（`:58-61`） |
| `send()` | — | — | `inputStream.read(buffer)`：`-1` → `sendFinish()`；否则 `sendData(n)`（`:64-86`）；IO 异常 → `close(true)` |
| `sendData(n)` | 发 | `SEND_LITEMATIC` | `writeUUID(id)` → `writeInt(n)` → `writeBytes(buffer, 0, n)`（`:88-95`） |
| `sendFinish()` | 发 | `FINISHED_LITEMATIC` | `writeUUID(id)` → `succeed()`（`:97-103`） |
| `onClose` | — | — | 关 inputStream（`:109-119`） |
| `sendCancelPacket` | 发 | `CANCEL_LITEMATIC` | `writeUUID(id)`（`:122-127`） |

> UploadExchange（S2C 下发方向）**不查配额**。

#### DownloadExchange（接收方，`communication/exchange/DownloadExchange.java`）

服务端从客户端拉文件（REGISTER_METADATA 时本地无文件）。stop-and-wait + 配额。

| 阶段 | 收/发 | PacketType | body 字段 |
|---|---|---|---|
| 构造 | — | — | 打开 `FileOutputStream` + `DigestOutputStream(MD5)`（`:37-45`），`bytesSent=0` |
| `init()` | 发 | `REQUEST_LITEMATIC` | `writeUUID(id)`（`:123-128`） |
| 收 | 收 | `SEND_LITEMATIC` | `readUUID`（消费）→ `readInt(size)` → `bytesSent += size` → 配额检查 → `readBytes(outputStream, size)` 写文件 → 回 `RECEIVED_LITEMATIC`（`:60-91`） |
| 收 | 收 | `FINISHED_LITEMATIC` | `readUUID` → flush → `UUID.nameUUIDFromBytes(md5.digest())` 比对 `hash` → 相等 `succeed()` / 不等 `close(false)`（`:92-115`） |
| 收 | 收 | `CANCEL_LITEMATIC` | `close(false)`（`:116-119`） |
| `onClose` | — | — | `setDownloadState(false)`；服务端且成功则 `progressQuota(bytesSent)`；关流；**失败删下载文件**（`:131-158`） |
| `sendCancelPacket` | 发 | `CANCEL_LITEMATIC` | `writeUUID(id)`（`:161-166`） |

**配额检查**（仅服务端 DownloadExchange，`:67-76`）：每收到一片 `bytesSent += size`，若 `QuotaService.isOverQuota(persistentName, bytesSent)` → `close(true)` + 发 `MESSAGE(ERROR, "syncmatica.error.cancelled_transmit_exceed_quota")`。

#### SEND_LITEMATIC 单片 body 字段顺序

```
writeUUID(placementId)     ← checkPacket peek 的那个，handle 第一行 readUUID 消费
writeInt(bytesRead)        ← 本片实际字节数（≤ 16384）
writeBytes(buffer, 0, bytesRead)
```

### 5.4 ModifyExchangeServer（放置修改锁）

`communication/exchange/ModifyExchangeServer.java`。

| 阶段 | 收/发 | PacketType | body 字段 |
|---|---|---|---|
| 构造 | — | — | `(placeId, partner, ctx)`，从 synMan 解出 placement（`:26-31`） |
| `init()` | — | — | placement 为 null 或已有 modifier → `close(true)`（发 DENY）；否则 `accept()`（`:60-70`） |
| `accept()` | 发 | `MODIFY_REQUEST_ACCEPT` | `writeUUID(placement.id)` → `setModifier(placement, this)` 占锁（`:72-78`） |
| 收 | 收 | `MODIFY_FINISH` | `readUUID`（消费）→ `receivePositionData(placement, buf, partner)` 应用位置 → `setLastModifiedBy(玩家)` → `updateServerPlacement` → `succeed()`（`:40-57`） |
| `sendCancelPacket` | 发 | `MODIFY_REQUEST_DENY` | `writeUUID(placementId)`（`:81-86`） |
| `onClose` | — | — | 若当前 modifier 是自己则清锁（`:91-97`） |

成功后由 `ServerCommunicationManager.handleExchange` 广播 `MODIFY`：

```
writeUUID(placement.id)
putPositionData(placement, buf, client)            // §4.2
[若 client 有 CORE_EX]
  writeUUID(lastModifiedBy.uuid)
  writeUtf(lastModifiedBy.name)
```

对**不支持 MODIFY** feature 的客户端，退化兼容：先发 `REMOVE_SYNCMATIC[uuid]`，再发 `REGISTER_METADATA`（重发完整 metadata）。

> 🔧 **我方修复标注（勿随模板回退）**：`modifyState` 锁表随迁移由上游 `HashMap` 改为 `ConcurrentHashMap`
> （`CommunicationManager.java:60`，并发安全决策），由此产生两处与上游的**受控偏差**：
>
> 1. **setModifier null→remove 翻译**：上游「`setModifier(p, null)` 写 null 值 = 解锁」在 CHM 下必抛 NPE
>    （CHM 禁 null 值），曾致四条路径静默失败——MODIFY 成功不广播（`succeed()→onClose` NPE 中断
>    `notifyClose`）、REMOVE 全流程中断（placement 删不掉且不广播，残留幽灵 id 喂养下条）、
>    onPlayerLeave 清理中断（targets 跨重连污染）、suspendAll 锁泄漏（后续 MODIFY 恒 DENY）。修复 =
>    `exchange == null` 时 `modifyState.remove(hash)`——两库全部读点仅 `map.get`，与上游「null 值残留」
>    可观测等价（上游 `CommunicationManager.java:242`）。
> 2. **getModifier null placement 守卫**：`getModifier(null)`（MODIFY_REQUEST 指向不存在的 placement）返回
>    null（无锁）而非 NPE。此为**上游原生缺陷**的本地修复：上游 `null.getHash()` 同样抛 NPE，且
>    `AbstractExchange.close` 中 `onClose` 先于 `sendCancelPacket`，DENY 永不发出（客户端 UI 挂起）、
>    exchange 残留（`startExchangeUnchecked` 先 add 后 init，NPE 逃逸则永不移除）。守卫后该路径完整发出
>    DENY（`sendCancelPacket` 用 `placementId` 构造器字段）并经 `startExchangeUnchecked→notifyClose` 自清理。
>
> 两条 NPE 曾均被 `ProtocolChannel` 的 `catch(Exception)` 吞成零信息日志（NPE 的 `getMessage()` 为 null），
> 该 catch 已改为带堆栈输出。单测 `ModifyStateTest` 固化上述锁语义契约（5 用例，修复前全部 NPE 失败）。
>
> 🔧 **addPlacement null target 守卫（勿随模板回退，2026-09）**：`addPlacement(@Nullable t, placement)`
> 的"已存在"分支对 `t == null` 跳过 `cancelShare`（对 origin 的取消通知）——上游 `cancelShare:274` 裸
> `source.sendPacket` 对 null 即 NPE，且其 console 路径 `fromExistingPlayer(null)` 在 playerMap 非空时
> 必崩（上游自带缺陷，不复刻）。null 语义 = 控制台 `/syncmatica load`（无发起方 target）：注册 + 对
> broadcastTargets 广播照走，仅跳过取消通知。同时 `onPlayerLeave` 的 removes 已 try/finally 化、
> `onPlayerJoin` 先清同 UUID 陈旧 broadcastTargets 项（上游 Mixin 每连接新建的 Bukkit 等价）。

### 5.5 客户端 Exchange（服务端需回应的包）

服务端 `ServerCommunicationManager.handle` 必须正确处理客户端 Exchange 发出的包：

| 客户端 Exchange | 它发出的包 | 服务端处理 |
|---|---|---|
| `VersionHandshakeClient` | `REGISTER_VERSION`（回版本）/ `FEATURE` / `FEATURE_REQUEST` | `VersionHandshakeServer.handle` |
| `ShareLitematicExchange` | `REGISTER_METADATA`（含 metadata）/ 上传时由其内部 UploadExchange 发 `SEND_LITEMATIC` | `handle(REGISTER_METADATA)` + `DownloadExchange` |
| `ModifyExchangeClient` | `MODIFY_REQUEST[uuid]` / `MODIFY_FINISH[uuid + positionData]` | `handle(MODIFY_REQUEST)` → `ModifyExchangeServer` |

---

## 6. 文件分片协议（stop-and-wait）

```
UploadExchange(发送方)                    DownloadExchange(接收方)
   init() → send() 读 16KB
   ─── SEND_LITEMATIC(uuid, size, bytes) ──→   handle: 写文件 + MD5 累计
                                              ←── RECEIVED_LITEMATIC(uuid) ───
   handle RECEIVED → send() 再读 16KB
   ─── SEND_LITEMATIC(uuid, size, bytes) ──→   ...
              （循环直到 inputStream.read() == -1）
   ─── FINISHED_LITEMATIC(uuid) ──────────→   handle: MD5→UUID == hash ?
   succeed()                                       ├ 相等 → succeed()
                                                  └ 不等 → close(false)
```

**关键设计点（已实现）**：

1. **严格请求-应答**：发送方每发一片后**必须等 `RECEIVED_LITEMATIC`** 才发下一片（原版注释：避免压垮对端连接）。**不可改成批量发送**——客户端 `DownloadExchange` 不会主动请求下一片。
2. **首片不等**：`UploadExchange.init` 直接 `send()` 发第一片，不等 RECEIVED（`UploadExchange.java:106`）。
3. **UUID 匹配路由**：每片 body 第一字段是 `placementId`，`checkPacket` 用 `checkUUID` peek 它判断是否归当前 exchange 处理（同一玩家可能同时有多个文件传输 exchange）。
4. **配额检查**（仅服务端 DownloadExchange，§5.3）：每片累加 `bytesSent`，超额 `close(true)` + 发 MESSAGE(ERROR)。`UploadExchange`（S2C）**不查配额**。
5. **hash 校验**（`DownloadExchange.java:104-105`）：`UUID.nameUUIDFromBytes(md5.digest())` 与 `placement.getHash()` 比对。不相等 `close(false)`（不发 cancel，因对端已 FINISHED）。

> ⚠️ **不复用 framework 的 `PacketSplitter`**：那是 Servux 的「首包写总长 VarInt + 连续流 + session key 重组」透明流式模型，接收端被动收齐。syncmatica 是「业务级 stop-and-wait + 显式 RECEIVED 应答 + UUID 路由」——两者不兼容。文件传输分片**在 `UploadExchange`/`DownloadExchange` 内自写**。`SyncmaticaHandler.encodeWithSplitter` 因此是空实现。

---

## 7. hash 算法（MD5 → type-3 UUID）

原版 `util/SyncmaticaUtil.java`：

```java
MessageDigest md5 = MessageDigest.getInstance("MD5");
// 4096 字节缓冲读全文
UUID hash = UUID.nameUUIDFromBytes(md5.digest());   // type-3 UUID
```

- `UUID.nameUUIDFromBytes` = RFC 4122 type-3（基于命名的 MD5），把 16 字节 MD5 直接当 UUID 字节。
- **三处计算**：`ServerPlacement.generateHash`（上传时算）、`FileStorage.hashCompare`（校验时算）、`RedirectFileStorage`（重定向时算）。
- 用途：**内容寻址键 + 去重**（同内容文件 hash 相同，复用存储）+ DownloadExchange 完整性校验（§5.3）。
- 客户端用同一算法校验，**算法不可改**。

---

## 8. 字节限制与适配

| 限制 | 值 | 来源 |
|---|---|---|
| Bukkit `Messenger.MAX_MESSAGE_SIZE` | ~1MiB（1.21.x） | Spigot API 1048576 |
| **客户端 `ClientboundCustomPayload` 解码上限** | **32767** | 原版硬限制（超此客户端断连） |
| syncmatica 文件分片 `BUFFER_SIZE` | **16384** | `UploadExchange.java:26`（取 32767 的半，留余量给包头 UUID+size 字段） |
| `PACKET_MAX_STRING_SIZE` | 32767 | `FriendlyByteBuf.MAX_STRING_LENGTH`（metadata 各 UTF 字段上限） |

**适配处置（已实现）**：

- 文件传输走自写的 stop-and-wait 分片（16KB/片），天然远低于 32767 上限，无需额外分包。
- metadata 单包（CONFIRM_USER 含全量 placement）：每个 placement 的 metadata 较小（数百字节），即便几十个 placement 也不会超限。原版未对 CONFIRM_USER 分片（`VersionHandshakeServer.onFeatureSetReceive` 一次性 `writeInt(count)` + 全部 metadata），本实现照此；若实测极多 placement 超限再考虑分批。
- S2C 默认走 NMS `DiscardedPayload` 直发（§1.4），仍受同一客户端 32767 上限，分片不变。
- C2S 接收：物理通道 `syncmatica:main` 由 framework `ProtocolChannel` 注册路由，Paper 不会因「未注册 custom payload」踢玩家。

---

## 9. 协议命门清单（维护必读）

1. **物理包体复合结构**：`byte[]` = `[Identifier][body]`，`SyncmaticaHandler.receivePlayPayload` 先 `readIdentifier()` 再切 body（§1.2/§1.3）。
2. **通道 path 拼写陷阱**：`request_download` / `mesage` 逐字照抄（§2）。
3. **S2C 默认 NMS 直发**：plugin messaging 的 wire 纯 Fabric 客户端收不到，必须 `DiscardedPayload` 直发；`/syncmatica debug s2c msg` 可切回对比（§1.4）。
4. **Feature 条件字段顺序**：`putMetaData`/`putPositionData` 的可选字段依赖握手后确定的 FeatureSet，顺序与客户端 `receiveXxx` 镜像严格对应（§4）。
5. **Rotation/Mirror 用 ordinal**：`values()[ordinal]` 还原，不用 name（§4.2）。
6. **checkPacket peek / handle 消费的两段式**：UUID 在 checkPacket 不消费（peek+回退），handle 第一行才 `readUUID` 消费（§5/AbstractExchange.checkUUID）。
7. **文件分片严格 stop-and-wait**：发一片等一个 RECEIVED，首片不等，不可批量（§6）。
8. **hash = MD5 → type-3 UUID**：不可改算法（§7）。
9. **`close(false)` vs `close(true)`**：版本不兼容 / 对端已 FINISHED 等场景用 `close(false)` 避免给不兼容对端发它认不出的包（§5.1、§5.3）。
10. **MODIFY 退化兼容**：对不支持 MODIFY feature 的客户端，修改结果用「REMOVE_SYNCMATIC + REGISTER_METADATA」模拟（§5.4）。
11. **不可复用 PacketSplitter**：文件传输分片自写（§6）。

---

> **相关**：Mixin→Bukkit 映射、降级矩阵、持久化路径 → [22-syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md)；实施记录 → [23-syncmatica-implementation-plan.md](23-syncmatica-implementation-plan.md)；客户端兼容测试 → [24-syncmatica-testing-guide.md](24-syncmatica-testing-guide.md)。
