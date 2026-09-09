# 02 · 核心网络协议技术细节

> 本文档是移植的**地基**。网络层吃透了，后面 5 条协议都是同一个模式的不同数据。
> 原版对照根目录：`OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/network/`
>
> 相关：架构骨架见 [01-servux-architecture.md](01-servux-architecture.md)；迁移方案见 [07-migration-architecture.md](07-migration-architecture.md) §网络层。

---

## 1. 一句话本质

Servux 用的是 **Mojang 在 1.20.2+ 引入的原版 `CustomPacketPayload`** 协议，**不是**旧的 Spigot plugin-messaging（`MC|Brand` 那套）。

- 每条功能 = 一条通道（`Identifier` / `ResourceLocation`，形如 `servux:hud_metadata`）
- 每条通道 = 一个 `CustomPacketPayload` 实现类型（`record Payload(...) implements CustomPacketPayload`）
- Fabric 的 `ServerPlayNetworking` / `PayloadTypeRegistry` **只是这套原版机制的注册封装**
- Payload 内部用 **VarInt `packetType`** 区分子消息，消息体是 **NBT（`CompoundTag`）** 或 **原始字节（`FriendlyByteBuf` slice）**

→ 对移植的决定性意义：**Paper 经 paperweight userdev 同样能直接读写 `FriendlyByteBuf`/`CompoundTag`/`CustomPacketPayload`，且 plugin messaging channel 直接映射到这些原版通道**。详见 [07](07-migration-architecture.md) §网络层。

---

## 2. 通道总表（5 条数据通道 + 1 配置 provider）

| 通道 ID（channel） | 协议版本 | Provider（逻辑名） | Packet 类 | 客户端配套 Mod | 用途 |
|---|---|---|---|---|---|
| `servux:hud_metadata` | **2** | `HudDataProvider`（hud_data） | `ServuxHudPacket` | **MiniHUD** | 世界元数据 / 出生点 / 天气 / 配方 / TPS·MobCap logger |
| `servux:entity_data` | 1 | `EntitiesDataProvider`（entity_data） | `ServuxEntitiesPacket` | MiniHUD / Tweakeroo | 方块实体 & 实体 NBT 查询（含玩家背包权限过滤） |
| `servux:tweaks` | 1 | `TweaksDataProvider`（tweaks_data） | `ServuxTweaksPacket` | Tweakeroo | NBT 查询（潜影盒堆叠未实现，见 [04](04-mixin-analysis.md)） |
| `servux:structures` | **2** | `StructureDataProvider`（structure_bounding_boxes） | `ServuxStructuresPacket` | MiniHUD | 原版结构边界框（村庄/神殿/要塞…） |
| `servux:litematics` | 1 | `LitematicsDataProvider`（litematic_data） | `ServuxLitematicaPacket` | **Litematica** | Litematica 投影投递 / 粘贴 / 批量实体数据 |

> **配置 provider**：`ConfigProvider`（逻辑名 `servux_main`，`DataProviderBase` 元信息 channel 标记为 `servux:main`，但 `registerHandler` 是 NO-OP——**不注册网络通道、不下发网络包**），承载全局 settings（permission_level / easy_place / debug 等），走 `/servux` 命令与 `servux.json` 持久化。

通道常量定义位置（Fabric）：
- `ServuxHudHandler.CHANNEL_ID = Identifier.fromNamespaceAndPath("servux", "hud_metadata")` ← **注意：HUD 通道网络名是 `servux:hud_metadata`，不是 `servux:main`**（"main" 只是 provider 的逻辑名）
- `ServuxEntitiesHandler.CHANNEL_ID`、`ServuxTweaksHandler.CHANNEL_ID`、`ServuxStructuresHandler.CHANNEL_ID`、`ServuxLitematicaHandler.CHANNEL_ID` 同理在各 Handler 类里定义

> ⚠️ **移植易错点**：provider 的 `getName()`（如 `"hud_data"`、逻辑名 `"main"`）与 `getNetworkChannel()`（网络通道 `servux:hud_metadata`）是**两回事**。Paper 端注册 plugin messaging 通道必须用**通道网络名**，不是 provider 名。逐条核对见下表（移植时务必从各 Handler 的 `CHANNEL_ID` 字段抄）。

---

## 3. `CustomPacketPayload` Payload 模型（以 HUD 为模板）

> 原版：`network/packet/ServuxHudPacket.java`（426 行）。其余 4 条通道的 Packet 类**结构完全同构**，只是 `Type` 枚举与字段不同。

### 3.1 Payload record（协议帧）

```java
// ServuxHudPacket.java:405-425
public record Payload(ServuxHudPacket data) implements CustomPacketPayload
{
    // 1) 类型 ID = 通道 ResourceLocation
    public static final CustomPacketPayload.Type<Payload> ID =
        new CustomPacketPayload.Type<>(ServuxHudHandler.CHANNEL_ID);   // servux:hud_metadata

    // 2) 编解码器：write = data.toPacket(buf)；读 = new Payload(ServuxHudPacket.fromPacket(buf))
    public static final StreamCodec<FriendlyByteBuf, Payload> CODEC =
        CustomPacketPayload.codec(Payload::write, Payload::new);

    public Payload(FriendlyByteBuf input) { this(fromPacket(input)); }   // 反序列化入口
    private void write(FriendlyByteBuf output) { data.toPacket(output); } // 序列化出口

    @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
}
```

**移植要点**：这段 `Payload` record 在 Paper 端**可近乎照抄**——只要能用 `FriendlyByteBuf`（NMS，paperweight userdev 提供）。Paper 不需要 Fabric 的 `@Environment(EnvType.SERVER)` 注解。

### 3.2 Payload 内部字节布局（`toPacket` / `fromPacket`）

```
┌─────────────────────────────────────────────────────────┐
│ VarInt  packetType            ← 子消息类型（见 Type 枚举）│
├─────────────────────────────────────────────────────────┤
│ NBT(CompoundTag)   或   raw bytes(buffer slice)          │
│   —— 多数类型是 NBT；分包数据类型(…_DATA)是 raw bytes     │
└─────────────────────────────────────────────────────────┘
```

- `toPacket`（`ServuxHudPacket.java:189-226`）：先 `output.writeVarInt(packetType.get())`，再按类型 `writeNbt(nbt)` 或 `writeBytes(buffer.copy())`。
- `fromPacket`（`:229-354`）：先 `input.readVarInt()` → `Type.getType(i)`，再按类型 `readNbt()` 或 `readBytes(...)` 重建 packet。

> **HUD 的 NBT 字段内容**（每种 packetType 对应哪些 NBT 字段）见 [03-dataproviders-detail.md](03-dataproviders-detail.md) §HUD。

### 3.3 子消息类型枚举（以 HUD 为例）

```java
// ServuxHudPacket.java:381-403
public enum Type {
    PACKET_S2C_METADATA(1),                 PACKET_C2S_METADATA_REQUEST(2),
    PACKET_S2C_SPAWN_DATA(3),               PACKET_C2S_SPAWN_DATA_REQUEST(4),
    PACKET_S2C_WEATHER_TICK(5),             PACKET_C2S_RECIPE_MANAGER_REQUEST(6),
    PACKET_S2C_DATA_LOGGER_TICK(7),         PACKET_C2S_DATA_LOGGER_REQUEST(8),
    // 分包专用（Oversize Packets, S2C）
    PACKET_S2C_NBT_RESPONSE_START(10),      PACKET_S2C_NBT_RESPONSE_DATA(11);
    private final int type; int get() { return this.type; }
}
```

**规律**（所有通道通用）：
- 偶数/奇数不代表方向，按枚举顺序：`S2C_*` = 服务端→客户端；`C2S_*` = 客户端→服务端。
- 末尾的 `*_RESPONSE_START(10)` / `*_RESPONSE_DATA(11)` 是**大包分包**专用（NBT 超过单包上限时用，见 §5）。

---

## 4. `IServerPayloadData` —— 协议数据的统一抽象

> 原版：`network/IServerPayloadData.java`（57 行）

每个 Packet 实现该接口，统一暴露"协议版本 / packetType / 总大小 / 是否空 / 序列化反序列化 / 清空"：

```java
public interface IServerPayloadData {
    int getVersion();      // PROTOCOL_VERSION（HUD=2, Entities=1, ...）
    int getPacketType();   // 子消息 type id
    int getTotalSize();    // 估算字节数（用于诊断日志）
    boolean isEmpty();
    void toPacket(FriendlyByteBuf output);   // 序列化
    void clear();
    static <T extends IServerPayloadData> T fromPacket(FriendlyByteBuf input) { return null; } // 指引
}
```

**移植要点**：纯接口，可直接照抄；`PROTOCOL_VERSION` 常量务必与原版一致（客户端按版本协商）。

---

## 5. `PacketSplitter` —— 应用层分包（大包命门）

> 原版：`network/PacketSplitter.java`（157 行）。源自 QuickCarpet（skyrising），Sakura 适配新版 payload。

### 5.1 为什么需要

单个网络包有大小上限。Servux 投递的 Recipe 列表、Litematica 投影（可达数十 MB）远超单包上限，必须**应用层分包**：发送端切片 → 接收端按 session 重组。

### 5.2 关键常量

```java
// PacketSplitter.java:22-27
public static final int MAX_TOTAL_PER_PACKET_S2C = 32_000;          // S2C 单片总上限（防御客户端 32767 解码上限）
public static final int MAX_PAYLOAD_PER_PACKET_S2C = MAX_TOTAL_PER_PACKET_S2C - 5; // ≈31995（留 VarInt 头）
public static final int DEFAULT_MAX_RECEIVE_SIZE_S2C = 67_108_864;  // 64 MiB（接收端缓冲上限；receive 默认用它）
// 原版另有 MAX_TOTAL_PER_PACKET_C2S / MAX_PAYLOAD_PER_PACKET_C2S / DEFAULT_MAX_RECEIVE_SIZE_C2S
// 三个 C2S 专用常量，但本实现 C2S/S2C 共用单物理通道，C2S 常量全代码库零引用——已在 F006 删除。
// C2S 上传（servux litematic 粘贴）的 receive 也走 DEFAULT_MAX_RECEIVE_SIZE_S2C（64MB）。
```

> ⚠️ **移植核心风险点**：Bukkit plugin messaging 单包硬上限是 `Messenger.MAX_MESSAGE_SIZE = 32768`（32 KiB），**远小于** S2C 的 1 MiB。详见 [07](07-migration-architecture.md) §网络层 · 字节限制方案。若 Paper 端全程走 plugin messaging，S2C 分片常量须改为 ≤32760。

### 5.3 发送逻辑（切片）

```java
// PacketSplitter.java:36-61
private static <T> boolean send(handler, packet /*FriendlyByteBuf*/, payloadLimit, player, networkHandler) {
    int len = packet.writerIndex();
    packet.resetReaderIndex();
    for (int offset = 0; offset < len; offset += payloadLimit) {
        int thisLen = Math.min(len - offset, payloadLimit);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(thisLen));
        buf.resetWriterIndex();
        if (offset == 0) buf.writeVarInt(len);   // 仅首包写【总长度】
        buf.writeBytes(packet, thisLen);
        handler.encodeWithSplitter(player, buf, networkHandler);  // 每片独立成一个 Payload 包
    }
    packet.release();
    return true;
}
```

**字节流布局**（分包后）：
```
包#0: [VarInt 总长度 N][原始字节 0 .. payloadLimit-1]
包#1:                [原始字节 payloadLimit .. 2*payloadLimit-1]
...
直到 offset >= N
```

### 5.4 接收逻辑（重组，`ReadingSession`）

```java
// PacketSplitter.java:112-156
private static class ReadingSession {
    private final long key;                 // 随机 session key（见下注）
    private int expectedSize = -1;          // 从首包的 VarInt 读到
    private FriendlyByteBuf received;       // 重组缓冲

    private FriendlyByteBuf receive(FriendlyByteBuf data, int maxLength) {
        data.readerIndex(0);
        if (this.expectedSize < 0) {        // 首包：读总长度
            this.expectedSize = data.readVarInt();
            if (this.expectedSize > maxLength) throw new IllegalArgumentException("Payload too large");
            this.received = new FriendlyByteBuf(Unpooled.buffer(this.expectedSize));
        }
        this.received.writeBytes(data.copy());
        if (this.received.writerIndex() >= this.expectedSize) {  // 收齐
            READING_SESSIONS.remove(this.key);
            return this.received;                                  // 返回完整数据
        }
        return null;                                               // 还没收齐
    }
}
```

- `READING_SESSIONS`：`Map<Long, ReadingSession>`，按 `long key` 索引。
- `key`：旧版 MC 用 `Pair`，新版被移除；Sakura 改成**预共享的随机 long session key**（`Random.create(Util.getMeasuringTimeMs()).nextLong()`），可随握手包下发或接收端自行生成。**移植时需为每个分片流维护一份 session key 映射**（Fabric 端 HUD 在 `ServuxHudHandler.readingSessionKeys: Map<UUID, Long>`）。

**移植要点**：`PacketSplitter` 是**纯算法 + NMS `FriendlyByteBuf`/`Unpooled`**，可近乎照抄；唯一改动是 §5.2 的分片常量（受 plugin messaging 32KiB 限制）和 §5.4 的 session key 存储。

---

## 6. `IPluginServerPlayHandler` —— 收发封装接口

> 原版：`network/IPluginServerPlayHandler.java`（238 行）。是 Fabric networking 的**薄封装**，定义"一条通道怎么注册、收、发、分包"。

这是移植时**改动最大**的一层，因为它直接依赖 Fabric API。逐方法看替换：

| Fabric 方法（`IPluginServerPlayHandler`） | 作用 | Paper 替换 |
|---|---|---|
| `getPayloadChannel()` | 返回通道 ID | 同（用通道网络名字符串） |
| `registerPlayPayload(Type, codec, direction)` | 注册 payload 到 `PayloadTypeRegistry.playC2S()/playS2C()` | `Messenger.registerIncomingPluginChannel` (C2S) + `registerOutgoingPluginChannel` (S2C)；或 NMS 注册 |
| `registerPlayReceiver(Type, handler)` | `ServerPlayNetworking.registerGlobalReceiver` | `Messenger.registerIncomingPluginChannel(plugin, channel, listener)` |
| `unregisterPlayReceiver()` | `ServerPlayNetworking.unregisterGlobalReceiver` | `Messenger.unregisterIncomingPluginChannel` |
| `receivePlayPayload(payload, ctx)` | 收到包的入口（`ctx.player()`） | `PluginMessageListener.onPluginMessageReceived(channel, player, bytes)` → 包一层成 Payload |
| `sendPlayPayload(player, payload)` | `ServerPlayNetworking.send` | `player.sendPluginMessage`（已声明）；未声明且已在本通道发过 C2S → NMS `connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes)))` 兜底（`ProtocolChannel.send`，与 Paper 放行路径逐字同构；**勿**直发自定义 Payload record，会 CCE） |
| `sendPlayPayload(networkHandler, payload)` | 走 `ServerGamePacketListenerImpl.send(new ClientboundCustomPayloadPacket(payload))` | NMS `player.connection.send(...)`（**这条在 Paper 上可原样用**） |
| `encodeWithSplitter(player, buf, networkHandler)` | 分包时每片发送回调 | 调 Paper 版 `sendPlayPayload` |

> 关键：`IPluginServerPlayHandler` 的**两个 `sendPlayPayload` 重载里，第二个（走 `ServerGamePacketListenerImpl` + `ClientboundCustomPayloadPacket`）在 Paper 上几乎不用改**——这正是 NMS 方案能保真发包的原因。第一个（走 Fabric `ServerPlayNetworking.send`）需换成 plugin messaging 或 NMS 发包。

### 6.1 `ServerPlayHandler` 单例（handler 注册表）

> 原版：`network/ServerPlayHandler.java`（58 行）。`ArrayListMultimap<Identifier, IPluginServerPlayHandler>` 维护"通道→handler 列表"。Paper 端可简化为 `Map<String, Handler>`（Servux 每通道只有一个 handler）。

### 6.2 HUD handler 的收发流程（典型样板）

> 原版：`network/packet/ServuxHudHandler.java`（159 行）

**接收（C2S）**：
```
Fabric:  ServerPlayNetworking 收到 Payload
  → ServuxHudHandler.receivePlayPayload(payload, ctx)              // :104
  → decodeServerData(CHANNEL_ID, ctx.player(), payload.data())     // :67
  → 入口闸：!isEnabled() || !checkFailures(player) → 丢弃           // 上游 :77（五 Handler decode/encode 双侧）
  → switch(packet.getType()):
       C2S_METADATA_REQUEST   → 已注册先 unregister → register(player, nbt)   // 版本门禁（见下）
       C2S_SPAWN_DATA_REQUEST → HudDataProvider.refreshSpawnMetadata(player, nbt)
       C2S_RECIPE_MANAGER_REQUEST → HudDataProvider.refreshRecipeManager(player, nbt)
       C2S_DATA_LOGGER_REQUEST → HudDataProvider.refreshLoggers(player, nbt)
```

**C2S 注册版本门禁**（上游 `register()` 字面，五 Provider 同构）：`register(player, tags)` 首查
`tags == null || tags.getIntOr("version", -1) < PROTOCOL_VERSION`（严格 `<`——相等放行、高版本放行由客户端
自行退网）→ 拒绝四件套：warn 日志 + `protocol_version_too_low` 聊天提示（`ServuxReference.MSG_PROTOCOL_VERSION_TOO_LOW`
预格式化常量）+ `HANDLER.tickFailures(player)` 检疫 + return **不入注册名册**；通过 → 权限检查（不足不入册）→
`registeredPlayers` 入册 → sendMetadata。名册（`isPlayerRegistered = registered && !invalid`）是拒绝的
状态载体：一切后续 C2S 请求入口（refresh*/blockEntity/entity/bulk/task/分片回执）与 S2C 主动推送
（join/通道声明重发、tick 周期）均按名册拦截——被拒客户端只收得到 3 次拒绝消息（count 1→2→3，
第 4 起 `checkFailures` 静默），永收不到 metadata。

**发送（S2C）**：`HudDataProvider` 各 `refresh*` 方法构造 `ServuxHudPacket` → `HANDLER.encodeServerData(player, packet)`：
```
ServuxHudHandler.encodeServerData(player, data)                    // :121
  if packet.type == PACKET_S2C_NBT_RESPONSE_START:                 // 大包 → 分包
      buffer.writeNbt(packet.getCompound());
      PacketSplitter.send(this, buffer, player, player.connection) // :132
      → 每片 encodeWithSplitter → sendPlayPayload(ResponseS2CData(slice))  // :117
  else:                                                            // 普通包
      sendPlayPayload(player, new Payload(packet))                 // :134
```

**失败计数**（上游 `tickFailures/checkFailures` 语义，deny 检疫与发送失败共用同一份计数）：`sendPlayPayload`
返回 false（客户端没装 MiniHUD / 通道未就绪）→ `tickFailures` 计数；超限（`> maxFailures() = 2`，对齐上游
`MAX_FAILURES=2`）回调 `onPacketFailure(player)` 标记 invalid 且**不清零**——重置仅在 `resetFailures`
（unregister / removePlayer[quit] 触发）；decode/encode 入口的 `checkFailures` 闸静默丢弃越限玩家的后续包。

---

## 7. 收发完整时序（以 HUD 元数据握手为例）

```
客户端(MiniHUD)                         服务端(Servux / Paper插件)
     │  玩家进服，MiniHUD 发起握手
     │ ──── C2S METADATA_REQUEST (nbt 含 version) ────────────► PluginMessageListener
     │                                                            → register(player, nbt)
     │                                                            → 版本门禁：version < PROTOCOL_VERSION?
     │                                ┌─ 是（旧客户端）───────────┘
     │                                │   → warn 日志 + protocol_version_too_low 消息
     │                                │   → tickFailures 检疫 + return（不入名册）
     │  ◄── §d…protocol version too low…（最多 3 次，此后静默）
     │                                │
     │                                └─ 否（合法客户端，含相等/更高）─
     │                                                            → 权限检查 → 入册 registeredPlayers
     │                                                            → 构造 metadata CompoundTag
     │                                                            → HANDLER.sendPlayPayload(player, MetadataResponse)
     │ ◄──────── S2C METADATA (nbt: name/id/version/servux/      (player.sendPluginMessage 或 NMS发包)
     │              spawnPos*/Loggers?) ──────────────────────────
     │  MiniHUD 解析，渲染 HUD / 出生点指示器
     │
     │  后续按 update_interval(默认40t) 周期性：
     │ ◄──────── S2C WEATHER_TICK (天气变化时) ──────────────────
     │ ◄──────── S2C SPAWN_DATA (出生点变化时) ──────────────────
     │ ◄──────── S2C DATA_LOGGER_TICK (TPS/MobCap, 每15t) ───────
     │
     │  玩家请求配方（大包，走分包）：
     │ ──── C2S RECIPE_MANAGER_REQUEST ─────────────────────────►
     │ ◄──────── S2C NBT_RESPONSE_START (首片, VarInt总长) ──────
     │ ◄──────── S2C NBT_RESPONSE_DATA  (切片…) ─────────────────
     │ ◄──────── ... 直到 PacketSplitter 收齐 ───────────────────
     │  MiniHUD 重组，刷新配方提示
```

---

## 8. 移植到 Paper 的要点速览（详见 07）

1. **通道 = plugin messaging channel**：用通道**网络名**（`servux:hud_metadata` 等）注册 `registerIncomingPluginChannel`（C2S）+ `registerOutgoingPluginChannel`（S2C）。
2. **收到的 byte[] = FriendlyByteBuf 裸字节**：`new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))` 即可复用原版 `fromPacket` 逻辑。
3. **发送**：把 `toPacket(buf)` 写出的字节 `buf.array()`/`ByteBuf.getBytes` 成 `byte[]` → `sendPluginMessage`。**Paper 命门**：`CraftPlayer.sendPluginMessage` 按 `channels().contains(channel)` 门控，玩家声明包被处理前 S2C 静默丢弃（26.1.2 反编译实锤；声明处理晚于客户端首个 C2S）→ `ProtocolChannel.send` 对**已在本通道发过 C2S 的玩家**在 `listening=false` 时走 NMS `connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes)))` 兜底（字面量 DiscardedPayload，与 Paper 放行路径同构；自定义 Payload record 直发会 CCE 踢人）。
4. **分包常量**：S2C 分片从 1MiB 改 ≤32760（若走 plugin messaging）；session key 逻辑照搬。
5. **Payload record / StreamCodec / toPacket / fromPacket**：几乎照抄（去掉 `@Environment`）。
6. **C2S 不踢人**：plugin messaging 注册的通道 Paper 内置路由，不会因"未知 payload"踢玩家。
7. **协议版本号保持一致**（26.1 线 HUD=3 / structures=3 / entities=2 / tweaks=2 / litematics=2）：客户端
   收 metadata 按 `!=` 严格校验自行退网；服务端 C2S REGISTER 按 `<` 拒绝旧客户端（版本门禁 + 名册拦截，
   见 §6.2/§7）。

完整迁移设计、字节限制方案、NMS vs plugin messaging 取舍见 [07-migration-architecture.md](07-migration-architecture.md) §网络层。
