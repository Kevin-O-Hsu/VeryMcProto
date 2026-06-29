# other-packets 迁移笔记（Entities / Tweaks / Structures / Litematics）

> 对照样板：`ServuxHudPacket.java` / `ServuxHudHandler.java`（HUD 协议版本 2）。本模块 4 条通道协议帧与 HUD 高度同构，主要差异在 **Type 枚举成员数 / 字节布局分支 / 是否有 C2S bulk 分片接收**。
>
> Paper 移植核心思路（所有 packet 类通用）：
> - `CustomPacketPayload` record + `StreamCodec` + `FriendlyByteBuf` 编解码 → **照抄**（去 `@Environment` 注解）。
> - 原版 `payload.data()` 直接是 `ServuxXxxPacket` 实例；Paper 上 plugin messaging 收到的是 `byte[]`（裸 `FriendlyByteBuf`），需在 `onPluginMessageReceived` 里 `new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))` 后调 `ServuxXxxPacket.fromPacket(buf)` 还原，**不再走 Payload record 构造路径**（record 的 `Payload(FriendlyByteBuf)` 构造仅供原版 StreamCodec 用，Paper 可保留但实际不触发）。
> - Handler 的 Fabric `ServerPlayNetworking.send / registerGlobalReceiver` → Paper `Messenger` + `player.sendPluginMessage` / `onPluginMessageReceived`。
> - `IPluginServerPlayHandler` / `IServerPayloadData` 接口骨架可照抄为普通 Java 接口（去掉 Fabric 泛型依赖）。
> - `PacketSplitter.send(...)` 的 S2C 分片常量 1MiB → 改 ≤ 32000（见 CLAUDE.md §1）。

---

## 文件清单表

| 原版文件(相对servux根) | 行数 | 建议目标包 | NMS依赖 | Mixin/AW依赖 | 迁移方式 |
|---|---|---|---|---|---|
| network/packet/ServuxEntitiesPacket.java | 483 | `verymc.top.veryMcProto.network.packet` | CompoundTag/NbtAccounter/FriendlyByteBuf/StreamCodec/CustomPacketPayload/BlockPos | 无 | 照抄(去注解) |
| network/packet/ServuxEntitiesHandler.java | 195 | `verymc.top.veryMcProto.network.packet` | FriendlyByteBuf/CustomPacketPayload/Identifier/ServerPlayer/ServerGamePacketListenerImpl | 无 | 适配(网络收发换 Messenger) |
| network/packet/ServuxTweaksPacket.java | 483 | `verymc.top.veryMcProto.network.packet` | 同 Entities + BlockPos/NbtAccounter | 无 | 照抄(去注解) |
| network/packet/ServuxTweaksHandler.java | 197 | `verymc.top.veryMcProto.network.packet` | 同 EntitiesHandler | 无 | 适配(网络收发换 Messenger) |
| network/packet/ServuxStructuresPacket.java | 247 | `verymc.top.veryMcProto.network.packet` | CompoundTag/FriendlyByteBuf/StreamCodec/CustomPacketPayload | 无 | 照抄(去注解) |
| network/packet/ServuxStructuresHandler.java | 164 | `verymc.top.veryMcProto.network.packet` | 同 EntitiesHandler | 无 | 适配(网络收发换 Messenger) |
| network/packet/ServuxLitematicaPacket.java | 523 | `verymc.top.veryMcProto.network.packet` | CompoundTag/NbtAccounter/FriendlyByteBuf/StreamCodec/CustomPacketPayload/BlockPos/**ChunkPos** | 无 | 照抄(去注解) |
| network/packet/ServuxLitematicaHandler.java | 227 | `verymc.top.veryMcProto.network.packet` | FriendlyByteBuf/CompoundTag/NbtAccounter/CustomPacketPayload/Identifier/ServerPlayer/ServerGamePacketListenerImpl/RandomSource/Util + schematic.LitematicaSchematic | 无 | 适配(Messenger) + 事件式调用 |

> 合计：8 文件 / 约 **2519** 行。另有 HUD 样板 2 文件 / 586 行（不计入本模块，仅作对照）。
>
> Mixin/AW 依赖：**全 0**。本模块是纯协议层，无任何注入点 —— 这是迁移最干净的一块。
> NMS 依赖均为 paperweight userdev 直接可用的公开类（`FriendlyByteBuf` / `CompoundTag` / `BlockPos` / `ChunkPos` / `NbtAccounter` / `CustomPacketPayload` / `StreamCodec` / `Identifier`(`ResourceLocation`) / `ServerPlayer` / `ServerGamePacketListenerImpl` / `RandomSource` / `Util`）。

---

## 逐文件详解

### ServuxEntitiesPacket.java (483 行)

- **职责**：`servux:entity_data` 通道的协议帧封装 —— 实体/方块实体 NBT 查询请求与响应、metadata 握手、PacketSplitter 大包分片。
- **关键字段**：
  - `Type packetType` — 当前包类型
  - `int transactionId = -1` — bulk 分片事务 id
  - `int entityId = -1`
  - `BlockPos pos = BlockPos.ZERO` (NMS)
  - `CompoundTag nbt` (NMS)
  - `FriendlyByteBuf buffer` (NMS) — 分片字节缓冲
  - `public static final int PROTOCOL_VERSION = 1`
- **关键方法（签名 + 行号）**：
  - `private ServuxEntitiesPacket(Type type)` (L27)
  - `static ServuxEntitiesPacket MetadataRequest(@Nullable CompoundTag nbt)` (L37) → Type=2
  - `static ServuxEntitiesPacket MetadataResponse(@Nullable CompoundTag nbt)` (L47) → Type=1
  - `static ServuxEntitiesPacket SimpleEntityResponse(int entityId, @Nullable CompoundTag nbt)` (L58) → Type=6
  - `static ServuxEntitiesPacket SimpleBlockResponse(BlockPos pos, @Nullable CompoundTag nbt)` (L69) → Type=5，`pos.immutable()`
  - `static ServuxEntitiesPacket BlockEntityRequest(BlockPos pos)` (L80) → Type=3
  - `static ServuxEntitiesPacket EntityRequest(int entityId)` (L87) → Type=4
  - `static ServuxEntitiesPacket ResponseS2CStart(@Nonnull CompoundTag nbt)` (L95) → Type=10
  - `static ServuxEntitiesPacket ResponseS2CData(@Nonnull FriendlyByteBuf buffer)` (L102) → Type=11，**直接持有 buffer 引用不 copy**
  - `static ServuxEntitiesPacket ResponseC2SStart(@Nonnull CompoundTag nbt)` (L110) → Type=12
  - `static ServuxEntitiesPacket ResponseC2SData(@Nonnull FriendlyByteBuf buffer)` (L117) → Type=13，**copy()**
  - `int getVersion()` → PROTOCOL_VERSION；`int getPacketType()` → packetType.get()；`int getTotalSize()` (L146) = 2 + nbt.sizeInBytes() + buffer.readableBytes()
  - `Type getType()` / `getTransactionId()` / `setTransactionId(int)` / `getEntityId()` / `getPos()` / `getCompound()` / `getBuffer()` / `hasBuffer()` / `hasNbt()` / `isEmpty()`
  - `void toPacket(FriendlyByteBuf output)` (L202) — 编码主入口，见字节布局
  - `static ServuxEntitiesPacket fromPacket(FriendlyByteBuf input)` (L293) — 解码主入口
  - `void clear()` (L408)
  - `static Type getType(int input)` (L422)
- **Type 枚举**（L436，10 个）：
  | 名 | id |
  |---|---|
  | PACKET_S2C_METADATA | 1 |
  | PACKET_C2S_METADATA_REQUEST | 2 |
  | PACKET_C2S_BLOCK_ENTITY_REQUEST | 3 |
  | PACKET_C2S_ENTITY_REQUEST | 4 |
  | PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE | 5 |
  | PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE | 6 |
  | PACKET_S2C_NBT_RESPONSE_START | 10 |
  | PACKET_S2C_NBT_RESPONSE_DATA | 11 |
  | PACKET_C2S_NBT_RESPONSE_START | 12 |
  | PACKET_C2S_NBT_RESPONSE_DATA | 13 |
- **Payload record** (L461)：`record Payload(ServuxEntitiesPacket data) implements CustomPacketPayload`
  - `ID = new CustomPacketPayload.Type<>(ServuxEntitiesHandler.CHANNEL_ID)`
  - `CODEC = CustomPacketPayload.codec(Payload::write, Payload::new)`
  - `Payload(FriendlyByteBuf input){ this(fromPacket(input)); }`
  - `private void write(FriendlyByteBuf output){ data.toPacket(output); }`
  - `type()` 返回 ID
- **协议字节布局（toPacket，L202）**：所有分支**首字节恒为 `writeVarInt(packetType.get())`**，随后按类型：
  - `PACKET_C2S_BLOCK_ENTITY_REQUEST`(3)：`VarInt transactionId` + `BlockPos pos`
  - `PACKET_C2S_ENTITY_REQUEST`(4)：`VarInt transactionId` + `VarInt entityId`
  - `PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE`(5)：`BlockPos pos` + `NBT nbt`
  - `PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE`(6)：`VarInt entityId` + `NBT nbt`
  - `PACKET_S2C_NBT_RESPONSE_DATA`(11) / `PACKET_C2S_NBT_RESPONSE_DATA`(13)：`rawBytes(buffer.copy())`
  - `PACKET_C2S_METADATA_REQUEST`(2) / `PACKET_S2C_METADATA`(1)：`NBT nbt`
- **fromPacket 反序列化要点（L293）**：
  - BE/Entity Request 请求分支先 `input.readVarInt()`（注释 `// todo: old code compat`）再读 pos/entityId —— **保留这个丢弃读取**，否则与客户端字节流错位。
  - Simple 响应用 `input.readNbt(NbtAccounter.unlimitedHeap())` 并强制转 `(CompoundTag)`。
  - 分片 DATA 分支读 `input.readBytes(input.readableBytes())` 包成新 `FriendlyByteBuf`。
  - `readNbt()` 无参版本返回可空，Simple 响应用带 `NbtAccounter.unlimitedHeap()` 版本。
- **NMS 依赖**：`CompoundTag.merge/isEmpty/sizeInBytes`、`NbtAccounter.unlimitedHeap()`、`FriendlyByteBuf`、`BlockPos.ZERO/immutable()`、`StreamCodec`、`CustomPacketPayload.Type/codec`、`io.netty.buffer.Unpooled`。
- **Mixin/Accessor 依赖**：无。
- **迁移方式**：**照抄**。去 `@Environment`/Fabric import；Payload record + CODEC 保留（paperweight 下原版 StreamCodec 仍可用，但 Paper 实际收发走 plugin messaging 字节流，fromPacket 路径必须自实现入口）。
- **歧义/风险点**：
  1. `fromPacket` BE/Entity Request 分支的 `readVarInt()` 丢弃读取是历史兼容遗留，**必须照抄**，否则与 masa 客户端字节流不对齐。
  2. 1.21.11 `CompoundTag`：`merge` 返回 `void`（非链式），原代码已是语句式用法，OK。`readNbt()` 无参返回 `@Nullable CompoundTag`，Simple 响应分支带 `NbtAccounter`。
  3. Type id 不连续（6→10），枚举顺序与 id 顺序不一致 —— `getType(int)` 用线性遍历匹配，不能假设 `ordinal()`。

---

### ServuxEntitiesHandler.java (195 行)

- **职责**：`servux:entity_data` 通道的服务端收发协调 —— 单例、注册状态、C2S 解码路由、S2C 编码（含 PacketSplitter 大包）、失败计数。
- **关键字段**：
  - `static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath("servux", "entity_data")` (L37) —— **网络通道名 `servux:entity_data`**
  - `boolean payloadRegistered`
  - `Map<UUID,Integer> failures`
  - `static final int MAX_FAILURES = 4`
  - `Map<UUID,Long> readingSessionKeys` —— C2S 分片会话 key（**当前 C2S bulk 接收代码被注释掉，字段保留但未用**）
- **关键方法（签名 + 行号）**：
  - `static ServuxEntitiesHandler<ServuxEntitiesPacket.Payload> getInstance()` (L35)
  - `Identifier getPayloadChannel()` → CHANNEL_ID
  - `boolean isPlayRegistered(Identifier)` / `void setPlayRegistered(Identifier)` (L48/L58)
  - `<P extends IServerPayloadData> void decodeServerData(Identifier, ServerPlayer, P data)` (L68) — 路由：
    - `PACKET_C2S_METADATA_REQUEST` → `EntitiesDataProvider.INSTANCE.sendMetadata(player)`
    - `PACKET_C2S_BLOCK_ENTITY_REQUEST` → `onBlockEntityRequest(player, packet.getPos())`
    - `PACKET_C2S_ENTITY_REQUEST` → `onEntityRequest(player, packet.getEntityId())`
    - **`PACKET_C2S_NBT_RESPONSE_DATA` 整块被注释**（L81–116）→ 当前不接收 C2S 分片
  - `void reset(Identifier)` / `resetFailures(Identifier, ServerPlayer)` (L122/L130)
  - `void receivePlayPayload(T payload, ServerPlayNetworking.Context ctx)` (L139) — 校验 `payload.type().id().equals(CHANNEL_ID)` 后调 decodeServerData
  - `void encodeWithSplitter(ServerPlayer, FriendlyByteBuf buffer, ServerGamePacketListenerImpl)` (L149) — 把每个分片 buffer 包成 `Payload(ResponseS2CData(buffer))` 发出
  - `<P extends IServerPayloadData> void encodeServerData(ServerPlayer, P data)` (L156) — S2C 出口：
    - Provider 未启用直接 return
    - 若 type == `PACKET_S2C_NBT_RESPONSE_START`：组 buffer = `writeVarInt(transactionId) + writeNbt(nbt)`，调 `PacketSplitter.send(this, buffer, player, player.connection)`
    - 否则 `sendPlayPayload(player, new Payload(packet))`；失败则计数，超 MAX_FAILURES 调 `EntitiesDataProvider.INSTANCE.onPacketFailure(player)`
- **NMS 依赖**：`Identifier`(`ResourceLocation`)、`FriendlyByteBuf`、`Unpooled`、`ServerPlayer`、`ServerGamePacketListenerImpl`、`CustomPacketPayload`、`UUID`。
- **Mixin/Accessor 依赖**：无。
- **迁移方式**：**适配**。改动：
  - `receivePlayPayload` → Paper `PluginMessageListener.onPluginMessageReceived(channel, player, byte[])`，内部 `new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))` → `ServuxEntitiesPacket.fromPacket(buf)` → 走 decodeServerData 同样 switch。
  - `sendPlayPayload` → `player.sendPluginMessage(plugin, "servux:entity_data", bytes)`，bytes 由 `out = new FriendlyByteBuf(Unpooled.buffer()); packet.toPacket(out); out.array()` 得到（注意 release / 拷贝）。
  - `ServerGamePacketListenerImpl networkHandler` 参数（`player.connection`）在 Paper 上仍可用作 NMS 发包入口（绕过 32KiB 限制的备选方案）。
  - `IPluginServerPlayHandler<T>` 接口：泛型 `T extends CustomPacketPayload` 可保留为标记接口，或简化为不带泛型（取决于网络层骨架设计）。
  - C2S bulk 接收注释块：**实现 Litematics 那条通道时再开**；Entities 通道当前可省略。
- **歧义/风险点**：
  1. `encodeWithSplitter` 与 `encodeServerData(START)` 双路发包：START 包触发 PacketSplitter.send，后者回调 `encodeWithSplitter` 发每个分片 —— Paper 上必须保证 PacketSplitter 回调仍指向同一 `Messenger.sendPluginMessage` 路径。
  2. `player.connection` 作为 `ServerGamePacketListenerImpl` 传入：paperweight userdev 下 `ServerPlayer.connection` 字段类型即 `ServerGamePacketListenerImpl`，可直传。
  3. 失败计数阈值 4 与 Provider `onPacketFailure` 联动，需与 EntitiesDataProvider 实现对齐。

---

### ServuxTweaksPacket.java (483 行)

- **职责**：`servux:tweaks_data` 通道（**注意：Handler 的 CHANNEL_ID 用的是 `"tweaks"`，不是 `"tweaks_data"`**）的协议帧，结构与 EntitiesPacket **完全同构**（10 个 Type、相同字节布局）。
- **关键字段**：与 EntitiesPacket 完全一致（packetType/transactionId/entityId/pos/nbt/buffer + `PROTOCOL_VERSION = 1`）。
- **关键方法**：与 EntitiesPacket 一一对应，类名替换为 `ServuxTweaksPacket`。工厂方法集合相同：MetadataRequest/Response、SimpleEntityResponse、SimpleBlockResponse、BlockEntityRequest、EntityRequest、ResponseS2CStart/Data、ResponseC2SStart/Data。
- **Type 枚举**：与 EntitiesPacket **完全相同**的 10 个成员 + 相同 id（1–6, 10–13）。
- **Payload record** (L461)：结构同 Entities，`ID` 指向 `ServuxTweaksHandler.CHANNEL_ID`。
- **协议字节布局**：与 EntitiesPacket **逐字节一致**。
- **Bug 提示（L117–123 `ResponseC2SData`）**：原代码有重复赋值 ——
  ```java
  packet.buffer = buffer;          // L120
  packet.buffer = new FriendlyByteBuf(buffer.copy());  // L121 覆盖了上一行
  ```
  Entities 版本只做一次 copy。**移植时按 Entities 的正确写法（单次 copy）实现**，并注意 Tweaks 这里是原版 bug。
- **NMS 依赖**：同 EntitiesPacket。
- **Mixin/Accessor 依赖**：无。
- **迁移方式**：**照抄**（去注解），修复 L120/L121 重复赋值 bug。
- **歧义/风险点**：
  1. **CHANNEL_ID 不一致**：类名暗示 `tweaks_data`，但 Handler 用 `servux:tweaks`。以 Handler 的 `CHANNEL_ID` 为准（网络名权威），参见 CLAUDE.md 通道表 `servux:tweaks_data` —— **需实测确认 masa 客户端订阅的是 `servux:tweaks` 还是 `servux:tweaks_data`**；若 CLAUDE.md 表与源码冲突，以源码为准但需交叉验证客户端。
  2. L120/L121 双赋值 bug。
  3. 其余同 Entities。

---

### ServuxTweaksHandler.java (197 行)

- **职责**：`servux:tweaks` 通道收发协调 —— 与 EntitiesHandler **结构同构**。
- **关键字段**：
  - `static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath("servux", "tweaks")` (L37) —— **网络名 `servux:tweaks`**
  - 其余（payloadRegistered/failures/MAX_FAILURES=4/readingSessionKeys）同 EntitiesHandler。
- **关键方法**：与 EntitiesHandler 一一对应。decodeServerData (L68) 路由：
  - `PACKET_C2S_METADATA_REQUEST` → `TweaksDataProvider.INSTANCE.sendMetadata(player)`
  - `PACKET_C2S_BLOCK_ENTITY_REQUEST` → `onBlockEntityRequest(player, pos)`
  - `PACKET_C2S_ENTITY_REQUEST` → `onEntityRequest(player, entityId)`
  - C2S bulk 同样**被注释**（L81–117）
- encodeServerData (L157) / encodeWithSplitter (L150) 逻辑与 EntitiesHandler 完全一致，只是 Provider 换 `TweaksDataProvider`、Packet 换 `ServuxTweaksPacket`。
- **NMS 依赖**：同 EntitiesHandler。
- **Mixin/Accessor 依赖**：无。
- **迁移方式**：**适配**（同 EntitiesHandler，网络收发换 Messenger）。
- **歧义/风险点**：CHANNEL_ID 名称需与 masa 客户端核对；其余同 EntitiesHandler。

---

### ServuxStructuresPacket.java (247 行)

- **职责**：`servux:structure_bounding_boxes` 通道（**Handler CHANNEL_ID 用 `"structures"`，非 `structure_bounding_boxes`**）协议帧 —— 结构边界框下发、metadata、注册/反注册、spawn/weather 元数据。**无 transactionId/entityId/pos，最简 packet。**
- **关键字段**：
  - `Type packetType`、`CompoundTag nbt`、`FriendlyByteBuf buffer`
  - `public static final int PROTOCOL_VERSION = 2` —— **注意此通道协议版本是 2（与 HUD 一致），不是 1**
- **构造方法（公开，非私有工厂模式，与其它 packet 不同）**：
  - `ServuxStructuresPacket(Type type, @Nullable CompoundTag nbt)` (L22)
  - `ServuxStructuresPacket(Type type, @Nonnull FriendlyByteBuf packet)` (L38) — buffer.copy()
- **关键方法**：`getVersion/getPacketType/getTotalSize`、`getType/getCompound/getBuffer/hasBuffer/hasNbt/isEmpty`、`void toPacket(FriendlyByteBuf)` (L100)、`static fromPacket(FriendlyByteBuf)` (L136)、`clear()` (L174)、`static Type getType(int)` (L190)。
- **Type 枚举**（L204，**8 个，编号不连续且含义独立**）：
  | 名 | id |
  |---|---|
  | PACKET_S2C_METADATA | 1 |
  | PACKET_S2C_STRUCTURE_DATA | 2 |
  | PACKET_C2S_STRUCTURES_REGISTER | 3 |
  | PACKET_C2S_STRUCTURES_UNREGISTER | 4 |
  | PACKET_S2C_STRUCTURE_DATA_START | 5 |
  | PACKET_S2C_SPAWN_METADATA | 10 |
  | PACKET_C2S_REQUEST_SPAWN_METADATA | 11 |
  | PACKET_S2C_WEATHER_DATA | 12 |
- **Payload record** (L225)：结构同上，`ID` 指向 `ServuxStructuresHandler.CHANNEL_ID`。
- **协议字节布局（toPacket, L100）**：
  - 首字节恒 `writeVarInt(packetType.get())`
  - `PACKET_S2C_STRUCTURE_DATA`(2)：`rawBytes(buffer.copy())` —— 结构数据走原始 buffer
  - **其余所有类型**：`writeNbt(nbt)` —— 统一 NBT
- **fromPacket (L136)**：
  - 读 VarInt → getType；null 则 warn
  - `PACKET_S2C_STRUCTURE_DATA`：`new ServuxStructuresPacket(type, new FriendlyByteBuf(input.readBytes(input.readableBytes())))`
  - 否则 `new ServuxStructuresPacket(type, input.readNbt())` —— **注意此处无 NbtAccounter**（与 Entities/Litematica 不同）
- **NMS 依赖**：`CompoundTag`、`FriendlyByteBuf`、`Unpooled`、`StreamCodec`、`CustomPacketPayload`。
- **Mixin/Accessor 依赖**：无。
- **迁移方式**：**照抄**（去注解）。构造方法是公开的，工厂模式不同，照原样保留。
- **歧义/风险点**：
  1. **协议版本 2**（不是 1）。
  2. `fromPacket` 读 NBT **无 NbtAccounter.unlimitedHeap()**，大结构 metadata 可能触发默认 NbtAccounter 上限 —— 但 metadata 通常很小，OK。移植时若担心可加 `unlimitedHeap()`，但会改变字节读行为需谨慎。
  3. CHANNEL_ID 名 `servux:structures` 与 CLAUDE.md 表 `servux:structure_bounding_boxes` **冲突**，以源码为准但需核对客户端。
  4. `PACKET_C2S_REQUEST_SPAWN_METADATA`(11) 收到后实际转发给 `HudDataProvider`（见 Handler），跨 Provider 协议耦合，移植时保留该跨调用。

---

### ServuxStructuresHandler.java (164 行)

- **职责**：`servux:structures` 通道收发协调 —— 单例、注册路由、结构数据 PacketSplitter 下发。**注意：方法名与其它 Handler 不同**（`decodeStructuresPacket` / `encodeStructuresPacket`，非 `decodeServerData` / `encodeServerData`）。
- **关键字段**：
  - `static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath("servux", "structures")` (L37)
  - `payloadRegistered`、`failures`、`MAX_FAILURES = 4`（**无 readingSessionKeys** —— 此通道不接收 C2S bulk 分片）
- **关键方法**：
  - `getInstance()` (L35)、`getPayloadChannel/isPlayRegistered/setPlayRegistered`
  - `void decodeStructuresPacket(Identifier, ServerPlayer, ServuxStructuresPacket)` (L66) — 路由（**注释明确"Only NBT type packets received from MiniHUD, not using PacketSplitter"**）：
    - `PACKET_C2S_STRUCTURES_REGISTER`(3)：先 `StructureDataProvider.INSTANCE.unregister(player)` 再 `register(player)`
    - `PACKET_C2S_REQUEST_SPAWN_METADATA`(11)：**转发给 `HudDataProvider.INSTANCE.refreshSpawnMetadata(player, packet.getCompound())`**（跨 Provider）
    - `PACKET_C2S_STRUCTURES_UNREGISTER`(4)：`StructureDataProvider.INSTANCE.unregister(player)`
  - `reset` / `resetFailures` (L93/L102)
  - `receivePlayPayload(T, Context)` (L110) — 校验通道后调 decodeStructuresPacket
  - `encodeWithSplitter(ServerPlayer, FriendlyByteBuf, ServerGamePacketListenerImpl)` (L120) — 包成 `new ServuxStructuresPacket(PACKET_S2C_STRUCTURE_DATA, buffer)` 再 `encodeStructuresPacket`
  - `void encodeStructuresPacket(ServerPlayer, ServuxStructuresPacket)` (L127) — S2C 出口：
    - Provider 未启用 return
    - `PACKET_S2C_STRUCTURE_DATA_START`(5)：buffer = `writeNbt(nbt)`，`PacketSplitter.send(this, buffer, player, player.connection)`
    - 否则 sendPlayPayload；失败计数超 MAX_FAILURES 调 `StructureDataProvider.INSTANCE.unregister(player)`（**注意：失败处置是 unregister，不是 onPacketFailure**）
- **NMS 依赖**：`Identifier`、`FriendlyByteBuf`、`Unpooled`、`ServerPlayer`、`ServerGamePacketListenerImpl`、`CustomPacketPayload`、`UUID`。
- **Mixin/Accessor 依赖**：无。
- **迁移方式**：**适配**（Messenger）。注意方法命名与其它 Handler 不统一（移植时可统一为 decodeServerData/encodeServerData，但需同步改 IServerPayloadData 接口契约，或保留原名）。
- **歧义/风险点**：
  1. 失败超限处置是 `unregister(player)`（断开该玩家结构订阅），而非调 `onPacketFailure` —— 与其它 Handler 行为不同。
  2. 跨 Provider：`PACKET_C2S_REQUEST_SPAWN_METADATA` 委托 HudDataProvider —— 需保证 HudDataProvider 在 Structures 之前就绪。
  3. encodeWithSplitter 用 `new ServuxStructuresPacket(type, buffer)` 公开构造（buffer 分支），与 START 分支的 `writeNbt` 协同。

---

### ServuxLitematicaPacket.java (523 行)

- **职责**：`servux:litematic_data` 通道（**Handler CHANNEL_ID 用 `"litematics"`，非 `litematic_data`**）协议帧 —— 投影投递/粘贴、实体/方块实体查询、**Bulk Entity NBT 请求（带 ChunkPos）**、PacketSplitter 分片。**唯一有 `chunkPos` 字段、唯一有 C2S bulk 接收实际启用**。
- **关键字段**：
  - `Type packetType`、`int transactionId=-1`、`int entityId=-1`、`BlockPos pos=BlockPos.ZERO` (NMS)、`CompoundTag nbt`、`ChunkPos chunkPos=ChunkPos.ZERO` (NMS)、`FriendlyByteBuf buffer`
  - `public static final int PROTOCOL_VERSION = 1`
- **关键方法（签名 + 行号）**：与 Entities 同构 + 多一个：
  - `static ServuxLitematicaPacket BulkNbtRequest(ChunkPos chunkPos, @Nullable CompoundTag nbt)` (L97) → Type=7，`packet.chunkPos = chunkPos`
  - `ChunkPos getChunkPos()` (L201)
  - 其余（MetadataRequest/Response L40/L50、SimpleEntityResponse L61、SimpleBlockResponse L72、BlockEntityRequest L83、EntityRequest L90、ResponseS2CStart/Data L109/L116、ResponseC2SStart/Data L124/L131、toPacket L219、fromPacket L321、clear L448、getType L462）与 Entities 同构。
- **Type 枚举**（L475，**11 个，含 Bulk**）：
  | 名 | id |
  |---|---|
  | PACKET_S2C_METADATA | 1 |
  | PACKET_C2S_METADATA_REQUEST | 2 |
  | PACKET_C2S_BLOCK_ENTITY_REQUEST | 3 |
  | PACKET_C2S_ENTITY_REQUEST | 4 |
  | PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE | 5 |
  | PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE | 6 |
  | PACKET_C2S_BULK_ENTITY_NBT_REQUEST | 7 |
  | PACKET_S2C_NBT_RESPONSE_START | 10 |
  | PACKET_S2C_NBT_RESPONSE_DATA | 11 |
  | PACKET_C2S_NBT_RESPONSE_START | 12 |
  | PACKET_C2S_NBT_RESPONSE_DATA | 13 |
- **Payload record** (L501)：同构，`ID` 指向 `ServuxLitematicaHandler.CHANNEL_ID`。
- **协议字节布局（toPacket, L219）**：首字节恒 `writeVarInt(type)`，随后：
  - `PACKET_C2S_BLOCK_ENTITY_REQUEST`(3)：`VarInt transactionId` + `BlockPos pos`
  - `PACKET_C2S_ENTITY_REQUEST`(4)：`VarInt transactionId` + `VarInt entityId`
  - `PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE`(5)：`BlockPos pos` + `NBT nbt`
  - `PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE`(6)：`VarInt entityId` + `NBT nbt`
  - **`PACKET_C2S_BULK_ENTITY_NBT_REQUEST`(7)：`ChunkPos chunkPos` + `NBT nbt`** （`writeChunkPos`）
  - `PACKET_S2C_NBT_RESPONSE_DATA`(11)/`PACKET_C2S_NBT_RESPONSE_DATA`(13)：`rawBytes(buffer.copy())`
  - `PACKET_C2S_METADATA_REQUEST`(2)/`PACKET_S2C_METADATA`(1)：`NBT nbt`
- **fromPacket (L321)**：同 Entities + Bulk 分支：`BulkNbtRequest(input.readChunkPos(), (CompoundTag) input.readNbt(NbtAccounter.unlimitedHeap()))`。BE/Entity Request 同样有 `readVarInt()` 丢弃读取（old code compat）。
- **NMS 依赖**：同 Entities + **`ChunkPos.ZERO`** + **`FriendlyByteBuf.writeChunkPos/readChunkPos`**。
- **Mixin/Accessor 依赖**：无。
- **迁移方式**：**照抄**（去注解）。
- **歧义/风险点**：
  1. **CHANNEL_ID `servux:litematics`** vs CLAUDE.md `servux:litematic_data` —— 源码为准，核对客户端。
  2. `writeChunkPos/readChunkPos` 在 1.21.11 NMS 签名稳定（paperweight 直接可用）。
  3. Type id 不连续（7→10）。

---

### ServuxLitematicaHandler.java (227 行)

- **职责**：`servux:litematics` 通道收发协调 —— **唯一真正启用 C2S bulk 分片接收**（PacketSplitter.receive），处理 Litematica 投影文件传输四阶段（TransmitStart/Cancel/Data/End）。
- **关键字段**：
  - `static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath("servux", "litematics")` (L43)
  - `payloadRegistered`、`failures`、`MAX_FAILURES=4`、**`Map<UUID,Long> readingSessionKeys`（实际使用）**
- **关键方法（签名 + 行号）**：
  - `getInstance()` (L41)、`getPayloadChannel/isPlayRegistered/setPlayRegistered`
  - `<P> void decodeServerData(Identifier, ServerPlayer, P data)` (L74) — 路由：
    - `PACKET_C2S_METADATA_REQUEST` → `LitematicsDataProvider.INSTANCE.sendMetadata(player)`
    - `PACKET_C2S_BLOCK_ENTITY_REQUEST` → `onBlockEntityRequest(player, pos)`
    - `PACKET_C2S_ENTITY_REQUEST` → `onEntityRequest(player, entityId)`
    - `PACKET_C2S_BULK_ENTITY_NBT_REQUEST` → `onBulkEntityRequest(player, chunkPos, compound)`
    - **`PACKET_C2S_NBT_RESPONSE_DATA`（L88–126，启用）**：C2S 分片接收主逻辑：
      1. 取/建 `readingSessionKey`：`RandomSource.create(Util.getMillis()).nextLong()`，按 player UUID 存入 readingSessionKeys
      2. `FriendlyByteBuf fullPacket = PacketSplitter.receive(this, readingSessionKey, packet.getBuffer())`
      3. fullPacket != null（收齐）→ `readingSessionKeys.remove(uuid)`；`handleBulkData(player, fullPacket.readVarInt(), (CompoundTag) fullPacket.readNbt(NbtAccounter.unlimitedHeap()))`
  - `private void handleBulkData(ServerPlayer player, final int type, CompoundTag nbt)` (L131) — **四阶段路由核心**：
    - `task = nbt.getStringOr("Task", "LitematicaPaste")`（1.21.11 `getStringOr` 用法）
    - `switch(task)`：
      - `"Litematic-TransmitStart"` / `"Litematic-TransmitCancel"` / `"Litematic-TransmitData"` / `"Litematic-TransmitEnd"` → `Pair<LitematicaSchematic,CompoundTag> schemPair = LitematicaSchematic.receiveFileTransmit(nbt, player)`；若 schemPair 非空且 `schemPair.getLeft().getFile() != null` → `LitematicsDataProvider.INSTANCE.handleClientPasteRequestPair(player, type, schemPair)`
      - `default` → `LitematicsDataProvider.INSTANCE.handleClientPasteRequest(player, type, nbt)`
  - `reset` / `resetFailures` (L153/L161)
  - `receivePlayPayload(T, Context)` (L170)
  - `encodeWithSplitter(ServerPlayer, FriendlyByteBuf, ServerGamePacketListenerImpl)` (L180)
  - `encodeServerData(ServerPlayer, P)` (L187) — START 包组 `writeVarInt(transactionId) + writeNbt(nbt)` 走 PacketSplitter.send；否则 sendPlayPayload + 失败计数 → `LitematicsDataProvider.INSTANCE.onPacketFailure(player)`
- **NMS 依赖**：同其它 Handler + **`RandomSource.create().nextLong()`**、**`Util.getMillis()`**、`NbtAccounter.unlimitedHeap()`、`CompoundTag.getStringOr`、**`org.apache.commons.lang3.tuple.Pair`**、**`schematic.LitematicaSchematic.receiveFileTransmit(nbt, player)`**（依赖投影系统模块）。
- **Mixin/Accessor 依赖**：无。
- **迁移方式**：**适配**（Messenger）+ 与 `schematic/` 模块联动。
  - `RandomSource`/`Util.getMillis` paperweight 直接可用。
  - `Pair` 来自 apache commons-lang3（Minecraft 自带依赖），Paper 环境可用。
  - `LitematicaSchematic.receiveFileTransmit` 属 `schematic/` 模块，需该模块先移植（见 05-schematic-system 文档）。
- **歧义/风险点（Litematica 四阶段，最关键）**：
  1. **SliceKey = `readingSessionKey`**：`RandomSource.create(Util.getMillis()).nextLong()` 生成，按 player UUID 缓存。客户端首次发 DATA 时建 key，后续复用直到 `PacketSplitter.receive` 返回完整包后 remove。移植时须保留此 UUID→sessionKey 映射生命周期。
  2. **四阶段 Task 字符串**：`"Litematic-TransmitStart"` / `"Litematic-TransmitCancel"` / `"Litematic-TransmitData"` / `"Litematic-TransmitEnd"` —— 这些是 NBT 里的 `Task` 字段值，由 `LitematicaSchematic.receiveFileTransmit` 内部状态机驱动，本 Handler 只负责按 task 分流。**真正的四阶段状态机在 `LitematicaSchematic.receiveFileTransmit`**，不在本 Handler。
  3. fullPacket 读出顺序：`readVarInt()`（type）+ `readNbt()`（nbt）—— 与 encodeServerData START 写入的 `writeVarInt(transactionId) + writeNbt(nbt)` 顺序对应（**注意：这里读出来的第一个 VarInt 在 handleBulkData 里被当作 `type` 参数透传**）。
  4. `getStringOr` 是 1.21.11 Optional 化后的正确 API（非 `getString`）。
  5. 大投影文件传输会触发大量 C2S 分片 —— **C2S 单包 ≤ 32767（plugin messaging 上限）**，与 PacketSplitter C2S 常量 32767 吻合，无需改；但 S2C 下发（START 走 PacketSplitter.send）需把分片常量从 1MiB 改 ≤ 32000。

---

## 跨文件共性 / 迁移总览

### 1. 通道名 vs CLAUDE.md 表（**冲突点，必须核对客户端**）

| Handler 类 | 源码 CHANNEL_ID | CLAUDE.md 表 |
|---|---|---|
| ServuxHudHandler | `servux:hud_metadata` | `servux:main`（HUD）|
| ServuxEntitiesHandler | `servux:entity_data` | `servux:entity_data` ✓ |
| ServuxTweaksHandler | `servux:tweaks` | `servux:tweaks_data` ✗ |
| ServuxStructuresHandler | `servux:structures` | `servux:structure_bounding_boxes` ✗ |
| ServuxLitematicaHandler | `servux:litematics` | `servux:litematic_data` ✗ |

> **结论**：以源码 `CHANNEL_ID` 常量值为准（原版权威）。但 CLAUDE.md 表（与 masa 客户端订阅名）存在出入，移植注册 plugin channel 时**必须用源码里的字符串**，并在集成测试时核对 masa 客户端实际订阅的通道名（`servux:tweaks` / `servux:structures` / `servux:litematics`）。HUD 通道 `servux:hud_metadata` 也与 CLAUDE.md 的 `servux:main` 不符 —— 全表以源码为准。

### 2. 协议版本

| 通道 | PROTOCOL_VERSION |
|---|---|
| HUD (`ServuxHudPacket`) | **2** |
| Entities | 1 |
| Tweaks | 1 |
| Structures | **2** |
| Litematics | 1 |

### 3. PacketSplitter 使用矩阵

| Handler | S2C 下发 START | C2S 接收 DATA |
|---|---|---|
| Hud | ✓（writeNbt(nbt)）| ✗ |
| Entities | ✓（writeVarInt(txId)+writeNbt(nbt)）| ✗（注释掉）|
| Tweaks | ✓（同 Entities）| ✗（注释掉）|
| Structures | ✓（writeNbt(nbt)，type=STRUCTURE_DATA_START）| ✗（无 readingSessionKeys）|
| Litematics | ✓（writeVarInt(txId)+writeNbt(nbt)）| **✓ 实际启用**（readingSessionKeys + PacketSplitter.receive）|

> S2C START 写入载荷差异：HUD/Structures 只写 NBT；Entities/Tweaks/Litematics 写 `VarInt transactionId + NBT`。**移植时务必逐通道对齐**。

### 4. 全模块迁移方式分布
- **照抄（去注解）**：4 个 Packet 类（Entities / Tweaks / Structures / Litematics）
- **适配（Fabric 网络 → Paper Messenger）**：4 个 Handler 类
- **反射**：0
- **事件**：0（Handler 内的 Provider 调用是普通方法调用，非 Bukkit 事件；Provider 内部才用事件）
- **降级**：0（本模块是纯协议帧，无服务端行为改造）

### 5. 全模块无 Mixin / 无 AccessWidener —— 是整个移植里最干净的一块，可作为网络层首批落地目标。
