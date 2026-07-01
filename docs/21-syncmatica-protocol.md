# 21 · Syncmatica 网络协议详解

> 原版根目录：`OriginImpl/syncmatica-LTS-1.21.11/src/main/java/ch/endte/syncmatica/`
> 相关：架构总览见 [20](20-syncmatica-architecture.md)；Mixin 与迁移见 [22](22-syncmatica-mixin-migration.md)。
> **字段语义对照**：syncmatica 是双端 mod，同仓库的 `communication/ClientCommunicationManager.java` + 各 `*Client` Exchange 即协议接收端。本文所有字段顺序均已对照客户端 `receiveMetaData` / `receivePositionData` 确认一致。

---

## 1. 通道模型：单物理通道 + 逻辑 PacketType 复用

### 1.1 与 Servux 的根本差异

| | Servux | Syncmatica |
|---|---|---|
| 物理通道数 | 5（`servux:main` / `entity_data` / ...） | **1**（`syncmatica:main`，C2S + S2C 共用同一 ID） |
| 包体结构 | `[packetType VarInt][body]`（每通道独立） | **`[逻辑通道 Identifier][body bytes]`**（单通道内复用） |
| 消息区分 | 通道本身就是消息类别 | 第一字段 `Identifier` 决定消息类别（18 种） |

### 1.2 SyncmaticaPacket.Payload 字节布局

`network/SyncmaticaPacket.java`。物理通道 `syncmatica:main`（`Syncmatica.java:29`），其 `CustomPacketPayload` 的 data 字段 = 一个 `SyncmaticaPacket` 序列化：

```
┌─────────────────────────────────────────────────────────┐
│ Clientbound/ServerboundCustomPayloadPacket              │
│   payload.type = SyncmaticaPacket.Payload.ID            │
│   payload.data（FriendlyByteBuf）:                       │
│     ┌──────────────────────────────────────────────┐    │
│     │ [逻辑通道 Identifier][body bytes...]          │    │  ← SyncmaticaPacket.toPacket (:44-48)
│     └──────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

- `toPacket(output)`（`SyncmaticaPacket.java:44-48`）：`output.writeIdentifier(channel); output.writeBytes(body.copy())`
- `fromPacket(input)`（`:39-42`）：`new SyncmaticaPacket(input.readIdentifier(), new FriendlyByteBuf(input.readBytes(readableBytes)))`

> 🔑 **Paper 移植命门**：plugin messaging 通道 `syncmatica:main` 的 `onPluginMessageReceived(channel, player, byte[])` 收到的 `byte[]` **就是** `[Identifier][body]` 这段裸字节。**必须照抄 `fromPacket`**：先 `readIdentifier()`（VarInt 长度前缀的 UTF 字符串）得 PacketType，剩余字节即 body。发送时反向：构造 buf = `writeIdentifier(PacketType.id) + writeBytes(body)`，整体作为 `byte[]` 走 `ChannelManager.send`。**切勿**当成 servux 那样「byte[] 直接是 body」处理。

### 1.3 收发调用链

**S2C 发送**（服务端→客户端）：`ExchangeTarget.sendPacket(type, buf, ctx)`（`ExchangeTarget.java:48-72`）
→ `new SyncmaticaPacket(type.getId(), buf)` → `ServerPlayHandler.encodeSyncData`（`network/handler/ServerPlayHandler.java:30-38`）
→ `sendSyncPacket(new SyncmaticaPacket.Payload(data), player)`（`:45-51`）
→ `ServerPlayNetworking.send` 或 `handler.send(new ClientboundCustomPayloadPacket(payload))`

**C2S 接收**（客户端→服务端）：`ServerPlayHandler.receiveSyncPayload(payload, ctx)`（`:40-43`）
→ `decodeSyncData(payload.data(), context.player().connection)`（`:18-23`）
→ `IServerPlay.syncmatica$operateComms(sm -> sm.onPacket(target, type, buf))` → `CommunicationManager.onPacket`

> Paper 对应：`ProtocolChannel` 的 `onPluginMessageReceived` → wrap byte[] → 调 syncmatica handler 的 `receivePlayPayload` → 解出 `(PacketType, body)` → `ServerCommunicationManager.onPacket`。无需 `IServerPlay` mixin。

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
| `MESSAGE` | `syncmatica:`**`mesage`** ⚠️ | 双向 | 显示消息（path 是原版拼写错误，注释「can't fix the typo here lol」`PacketType.java:77-79`） |

> ⚠️ 两个拼写陷阱：`REQUEST_LITEMATIC`→`request_download`、`MESSAGE`→`mesage`。Paper 注册的通道名 / 发送的 Identifier 必须逐字用 path 值，不能用枚举名。

---

## 3. Feature 协商

### 3.1 FeatureSet 序列化

`communication/FeatureSet.java`。**不是二进制位图**，而是 `\n` 分隔的 Feature 枚举名拼成的**单一 UTF 字符串**：

```
例：完整版 FeatureSet → "CORE\nFEATURE\nMODIFY\nMESSAGE\nQUOTA\nDEBUG\nCORE_EX\nVERSION\nDISPLAY_NAME"
```

- `toString()`（`:40-49`）：`\n` join
- `fromString(s)`（`:29-38`）：按 `\n` split，逐个 `Feature.fromString` 加入集
- 传输：作为一个 `writeUtf(readUtf)` 字段（`FeatureExchange.java:29,46`），受 `PACKET_MAX_STRING_SIZE = 32767` 限制

### 3.2 版本默认集

`FeatureSet.java:59-62`：仅一条 `"0.1" → {CORE}`。`fromVersionString(version)`（`:15-27`）用正则 `^\d+(\.\d+){2,4}$` 校验后**逐级去掉末段**查表（`0.1.0` → 查不到 → 去成 `0.1` → 命中 `{CORE}`）。

> 即：版本号 `0.1.x` 的对端会被服务端默认认为只支持 `{CORE}`，除非走显式 FEATURE 交换。完整版客户端会声明全集，故服务端**也应声明全集**（`Context.getFeatureSet` 默认 `Arrays.asList(Feature.values())`，`Context.java:146-148`）。

### 3.3 握手完整时序

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

- `checkPartnerVersion(version)`（`Context.java:213-215`）：**仅拒绝 `"0.0.1"`**。
- `onFeatureSetReceive`（`VersionHandshakeServer.java:57-69`）：发 `CONFIRM_USER` = `writeInt(placementCount)` + 逐个 `putMetaData(p, buf, partner)`。
- 握手成功后客户端才算「已确认用户」，进入 `broadcastTargets`，此后服务端任何 placement 变更都会广播给它。

---

## 4. Metadata 与 Position 字段布局

这是协议最核心的字段表。`putMetaData` / `putPositionData` 在 `CommunicationManager.java:79-145`，客户端镜像 `receiveMetaData` / `receivePositionData` 在 `:147-223`。

### 4.1 REGISTER_METADATA / CONFIRM_USER 中的 placement 编码

```
putMetaData(metaData, buf, target)                          // CommunicationManager.java:86-112
├─ writeUUID(id)                          placement UUID
├─ writeUtf(fileName)                     基础文件名
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
putPositionData(metaData, buf, target)                     // CommunicationManager.java:114-145
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

> ⚠️ **字段顺序依赖握手阶段已确定的 FeatureSet**。`onFeatureSetReceive` 在 `setFeatureSet` **之后**才调（`VersionHandshakeServer.java:46-47`），故 `putMetaData` 调用时 FeatureSet 必然已就绪。Paper 移植必须保证同样顺序。
>
> ⚠️ **Rotation / Mirror 用 ordinal 传输**（非 name）。原版注释（`CommunicationManager.java:120-123`）说明这是有意为之——传输「非修改性枚举」给同应用另一实例，不关心序号持久性。Paper 端用 `net.minecraft.world.level.block.Rotation` / `Mirror` 的 `values()[ordinal]` 直接还原即可（枚举顺序与原版一致，不会漂移）。

### 4.3 客户端镜像（对照确认）

`receiveMetaData`（`:147-198`）严格按 §4.1 顺序读取，缺失 `DISPLAY_NAME` 时 `displayName = fileName` 兜底；缺失 `CORE_EX` 时 `owner = lastModifiedBy = MISSING_PLAYER`。`receivePositionData`（`:200-223`）按 §4.2 顺序，CORE_EX 子区域逐个 `subRegionData.modify(name, pos, rot, mir)`。**服务端 `putXxx` 必须与此镜像逐字段对应**。

---

## 5. Exchange 状态机详解

服务端必须实现的 4 个 Exchange（其余客户端 Exchange 见 §5.5，服务端只需正确回应其包）。

### 5.1 VersionHandshakeServer（版本握手）

`communication/exchange/VersionHandshakeServer.java`，继承 `FeatureExchange`。

| 阶段 | 收/发 | PacketType | body 字段 |
|---|---|---|---|
| `init()` | 发 | `REGISTER_VERSION` | `writeUtf(MOD_VERSION)`（`:72-77`） |
| 收 | 收 | `REGISTER_VERSION` | `readUtf(版本)` → `checkPartnerVersion` → `fromVersionString` → setFeatureSet 或 requestFeatureSet（`:31-48`） |
| `onFeatureSetReceive` | 发 | `CONFIRM_USER` | `writeInt(placementCount)` + × count `putMetaData`（`:57-68`）→ `succeed()` |

- `checkPacket`：`REGISTER_VERSION` / `FEATURE_REQUEST` / `FEATURE`（`:20-24`）
- 版本不兼容 → `close(false)`（**不发 cancel**，避免给不兼容对端发它不认识的包，`:33-38`）

### 5.2 DownloadExchange ↔ UploadExchange（文件传输对）

**严格 stop-and-wait（请求-应答）分片**。`BUFFER_SIZE = 16384`（`UploadExchange.java:19`，注释：32767 是 custom payload 上限，32768 会超，故取半）。

#### DownloadExchange（接收方，`communication/exchange/DownloadExchange.java`）

| 阶段 | 收/发 | PacketType | body 字段 |
|---|---|---|---|
| 构造 | — | — | 打开 `FileOutputStream` + `DigestOutputStream(MD5)`（`:34-37`） |
| `init()` | 发 | `REQUEST_LITEMATIC` | `writeUUID(id)`（`:115-120`） |
| 收 | 收 | `SEND_LITEMATIC` | `readUUID` → `readInt(size)` → 配额检查 → `readBytes(out, size)` 写文件 → 回 `RECEIVED_LITEMATIC`（`:55-82`） |
| 收 | 收 | `FINISHED_LITEMATIC` | `readUUID` → 算 MD5→UUID 比对 hash → 相等 `succeed()` / 不等 `close(false)`（`:84-107`） |
| 收 | 收 | `CANCEL_LITEMATIC` | `close(false)`（`:108-111`） |
| `onClose` | — | — | `setDownloadState(false)`；服务端且成功则 `progressQuota(bytesSent)`；关流；**失败删下载文件**（`:122-150`） |
| `sendCancelPacket` | 发 | `CANCEL_LITEMATIC` | `writeUUID(id)`（`:152-158`） |

#### UploadExchange（发送方，`communication/exchange/UploadExchange.java`）

| 阶段 | 收/发 | PacketType | body 字段 |
|---|---|---|---|
| 构造 | — | — | 打开 `FileInputStream`（`:24-29`），`buffer = new byte[16384]`（`:22`） |
| `init()` | — | — | 调 `send()`（**首次不等 RECEIVED，直接发第一片**，`:99`） |
| 收 | 收 | `RECEIVED_LITEMATIC` | `readUUID`（消费）→ `send()` 发下一片（`:46-50`） |
| 收 | 收 | `CANCEL_LITEMATIC` | `close(false)`（`:51-54`） |
| `send()` | — | — | `inputStream.read(buffer)`：`-1` → `sendFinish()`；否则 `sendData(n)`（`:57-79`） |
| `sendData(n)` | 发 | `SEND_LITEMATIC` | `writeUUID(id)` → `writeInt(n)` → `writeBytes(buffer, 0, n)`（`:81-88`） |
| `sendFinish()` | 发 | `FINISHED_LITEMATIC` | `writeUUID(id)` → `succeed()`（`:90-96`） |
| `onClose` | — | — | 关 inputStream（`:101-112`） |
| `sendCancelPacket` | 发 | `CANCEL_LITEMATIC` | `writeUUID(id)`（`:114-120`） |

#### SEND_LITEMATIC 单片 body 字段顺序

```
writeUUID(placementId)     ← checkPacket peek 的那个，handle 第一行消费
writeInt(bytesRead)        ← 本片实际字节数（≤ 16384）
writeBytes(buffer, 0, bytesRead)
```

> ⚠️ **`checkPacket` peek 不能消费，`handle` 第一行才 `readUUID` 真正消费**——这是多 exchange 共存时正确路由的关键（`AbstractExchange.checkUUID` 记录/回退 readerIndex，`AbstractExchange.java:67-73`）。Paper 移植须复刻此两段式。

### 5.3 ModifyExchangeServer（放置修改锁）

`communication/exchange/ModifyExchangeServer.java`。

| 阶段 | 收/发 | PacketType | body 字段 |
|---|---|---|---|
| 构造 | — | — | `(placeId, partner, ctx)`，从 synMan 解出 placement（`:17-22`） |
| `init()` | — | — | placement 为 null 或已有 modifier → `close(true)`（发 DENY）；否则 `accept()`（`:48-58`） |
| `accept()` | 发 | `MODIFY_REQUEST_ACCEPT` | `writeUUID(placement.id)` → `setModifier(placement, this)` 占锁（`:60-66`） |
| 收 | 收 | `MODIFY_FINISH` | `readUUID` → `receivePositionData(placement, buf, partner)` 应用位置 → `setLastModifiedBy(玩家)` → `succeed()`（`:31-45`） |
| `sendCancelPacket` | 发 | `MODIFY_REQUEST_DENY` | `writeUUID(placementId)`（`:68-74`） |
| `onClose` | — | — | 若当前 modifier 是自己则清锁（`:78-85`） |

成功后由 `ServerCommunicationManager.handleExchange`（`:223-253`）广播 `MODIFY`：
```
writeUUID(placement.id)
putPositionData(placement, buf, client)            // §4.2
[若 client 有 CORE_EX]
  writeUUID(lastModifiedBy.uuid)
  writeUtf(lastModifiedBy.name)
```
对**不支持 MODIFY** feature 的客户端，退化兼容：先发 `REMOVE_SYNCMATIC[uuid]`，再发 `REGISTER_METADATA`（重发完整 metadata）。

### 5.4 FeatureExchange（Feature 协商抽象基类）

`communication/exchange/FeatureExchange.java`，是 `VersionHandshakeServer/Client` 的父类。

| 收/发 | PacketType | body 字段 |
|---|---|---|
| 收 `FEATURE_REQUEST` | → 调 `sendFeatures()`（`:24-26`） | — |
| `sendFeatures()` 发 | `FEATURE` | `writeUtf(featureSet.toString(), MAX_STRING)`（`:42-48`） |
| 收 `FEATURE` | → `readUtf` → `FeatureSet.fromString` → `setFeatureSet` → `onFeatureSetReceive()`（`:27-32`） | — |
| `requestFeatureSet()` 发 | `FEATURE_REQUEST` | 空 body（`:37-40`） |

### 5.5 客户端 Exchange（服务端需回应的包）

不移植实现，但服务端 `ServerCommunicationManager.handle` 必须正确处理它们发出的包：

| 客户端 Exchange | 它发出的包 | 服务端处理 |
|---|---|---|
| `VersionHandshakeClient` | `REGISTER_VERSION`（回版本）/ `FEATURE` / `FEATURE_REQUEST` | `VersionHandshakeServer.handle` |
| `ShareLitematicExchange` | `REGISTER_METADATA`（含 metadata）/ 上传时由其内部 UploadExchange 发 `SEND_LITEMATIC` | `handle(REGISTER_METADATA)`（`:114-156`）+ `DownloadExchange` |
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

**关键设计点**：
1. **严格请求-应答**：发送方每发一片后**必须等 `RECEIVED_LITEMATIC`** 才发下一片（注释 `UploadExchange.java:25-26`：避免压垮对端连接）。**Paper 移植不可改成批量发送**——客户端 `DownloadExchange` 不会主动请求下一片。
2. **首片不等**：`UploadExchange.init` 直接 `send()` 发第一片，不等 RECEIVED。
3. **UUID 匹配路由**：每片 body 第一字段是 `placementId`，`checkPacket` peek 它判断是否归当前 exchange 处理（同一玩家可能同时有多个文件传输 exchange）。
4. **配额检查**（仅服务端 DownloadExchange）：每收到一片 `bytesSent += size`，若 `QuotaService.isOverQuota(sender, bytesSent)` → `close(true)` + 发 `MESSAGE(ERROR)`（`DownloadExchange.java:56-68`）。`UploadExchange`（S2C）**不查配额**。
5. **hash 校验**：`UUID.nameUUIDFromBytes(md5.digest())` 与 `placement.getHash()` 比对（`DownloadExchange.java:96`）。不相等 `close(false)`（不发 cancel，因对端已 FINISHED）。

> ⚠️ **不可复用 Servux 的 `PacketSplitter`**：它是「首包写总长 VarInt + 连续流 + session key 重组」的透明流式模型，接收端被动收齐。syncmatica 是「业务级 stop-and-wait + 显式 RECEIVED 应答 + UUID 路由」——两者不兼容。文件传输分片**必须在 UploadExchange/DownloadExchange 内自写**。

---

## 7. 数据序列化（JSON）

### 7.1 ServerPlacement.toJson / fromJson

`data/ServerPlacement.java:288 / :325`。**纯 Gson，无 NBT**。

**toJson 写出顺序**（`:290-322`）：

| # | key | 值 | 条件 |
|---|---|---|---|
| 1 | `id` | UUID.toString | 必写 |
| 2 | `file_name` | fileName | 必写 |
| 3 | `display_name` | displayName | 必写 |
| 4 | `hash` | hashValue.toString | 必写 |
| 5 | `origin` | origin.toJson()（`{position:[x,y,z], dimension:"..."}`） | 必写 |
| 6 | `rotation` | Rotation.name() | 必写 |
| 7 | `mirror` | Mirror.name() | 必写 |
| 8 | `owner` | owner.toJson()（`{uuid,name}`） | 必写 |
| 9 | `lastModifiedBy` | ... | **仅当 `!= owner`**（`:303`） |
| 10 | `subregionData` | SubRegionData.toJson | **仅当 `isModified()`**（`:308`） |
| 11 | `litematicVersion` | int | **仅当 `> -1`**（`:313`） |
| 12 | `dataVersion` | int | **仅当 `> -1`**（`:317`） |

**fromJson**（`:325-441`）：`id/file_name/hash/origin/rotation/mirror` 六者齐备才解析（否则返回 null）；缺失字段用默认值 + `dirty=true`；服务端额外用文件 `litematicPeek` 修正 displayName/version（`:382-395`）。任一修正触发 `markDirty()`，加载后立即回写。

### 7.2 子结构序列化

- **ServerPosition.toJson**（`data/ServerPosition.java:38`）：`{position:[x,y,z], dimension:"minecraft:overworld"}`
- **PlayerIdentifier.toJson**（`extended_core/PlayerIdentifier.java:28`）：`{uuid:"...", name:"..."}`
- **SubRegionPlacementModification.toJson**（`extended_core/SubRegionPlacementModification.java:23`）：`{position:[x,y,z], name:"...", rotation:"...", mirror:"..."}`（注意 position 在 name 之前）
- **SubRegionData.toJson**（`extended_core/SubRegionData.java:66`）：`modificationData.values()` 转 JsonArray；**仅在 `isModified()` 时调用**（否则 modificationData 为 null 会 NPE）

### 7.3 hash 算法

`util/SyncmaticaUtil.java:34-53`：
```java
MessageDigest md5 = MessageDigest.getInstance("MD5");
// 4096 字节缓冲读全文
UUID hash = UUID.nameUUIDFromBytes(md5.digest());   // type-3 UUID
```
**三处计算**：`ServerPlacement.generateHash`（上传时）、`FileStorage.hashCompare`（校验时）、`RedirectFileStorage`（重定向时）。客户端用同一算法校验，**不能改**。

---

## 8. 字节限制与 Paper 适配

| 限制 | 值 | 来源 |
|---|---|---|
| Bukkit `Messenger.MAX_MESSAGE_SIZE` | ~1MiB（1.21.x） | Spigot API 1048576 |
| **客户端 `ClientboundCustomPayload` 解码上限** | **32767** | 原版硬限制（超此客户端断连） |
| syncmatica 文件分片 `BUFFER_SIZE` | **16384** | `UploadExchange.java:19`（取 32767 的半，留余量给包头 UUID+size 字段） |
| `PACKET_MAX_STRING_SIZE` | 32767 | `FriendlyByteBuf.MAX_STRING_LENGTH`（metadata 各 UTF 字段上限） |

**Paper 适配**：
- 文件传输走自写的 stop-and-wait 分片（16KB/片），天然远低于 32767 上限，无需额外分包。
- metadata 单包（CONFIRM_USER 含全量 placement）——每个 placement 的 metadata 较小（数百字节），即便几十个 placement 也不会超限；但理论上若有极多 placement，CONFIRM_USER 单包可能超 32767。**原版未对 CONFIRM_USER 分片**（`VersionHandshakeServer.java:57-68` 一次性 writeInt(count) + 全部 metadata），Paper 移植照此即可，若实测超限再考虑分批。
- 发送统一走 `ChannelManager.send(syncmatica:main, player, bytes)`（plugin messaging，Paper 内部发 `ClientboundCustomPayloadPacket`）。

---

## 9. 协议命门清单（移植必读）

1. **物理包体复合结构**：`byte[]` = `[Identifier][body]`，照抄 `SyncmaticaPacket.fromPacket` 解析（§1.2）。
2. **通道 path 拼写陷阱**：`request_download` / `mesage` 逐字照抄（§2）。
3. **Feature 条件字段顺序**：`putMetaData`/`putPositionData` 的可选字段依赖握手后确定的 FeatureSet，顺序与客户端 `receiveXxx` 镜像严格对应（§4）。
4. **Rotation/Mirror 用 ordinal**：`values()[ordinal]` 还原，不用 name（§4.2）。
5. **checkPacket peek / handle 消费的两段式**：UUID 在 checkPacket 不消费（peek+回退），handle 第一行才 `readUUID` 消费（§5.2）。
6. **文件分片严格 stop-and-wait**：发一片等一个 RECEIVED，首片不等，不可批量（§6）。
7. **hash = MD5 → type-3 UUID**：不可改算法（§7.3）。
8. **`close(false)` vs `close(true)`**：版本不兼容 / 对端已 FINISHED 等场景用 `close(false)` 避免给不兼容对端发它认不出的包（§5.1、§5.2）。
9. **MODIFY 退化兼容**：对不支持 MODIFY feature 的客户端，修改结果用「REMOVE_SYNCMATIC + REGISTER_METADATA」模拟（§5.3）。
10. **不可复用 PacketSplitter**：文件传输分片自写（§6）。

---

> **下一步**：Mixin 逐项 Bukkit 映射、降级矩阵、持久化路径映射 → [22-syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md)
