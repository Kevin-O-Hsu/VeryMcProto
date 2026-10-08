# Servux 网络协议（五通道 wire 规范）

> 本文档是 Servux 五条通道协议的**现行 as-built 规范**（26.2 线，逐项对照 `OriginImpl/servux-LTS-26.2/` 客户端源码钉死）：通道总表（§2）、Payload 模型（§3–§4）、版本约束与安全闸（§5 ⭐）、收发流程（§6–§7）、与上游的逐通道差异（§8）。
> 网络框架/分片常量/字节限制裸值的唯一权威见 [architecture.md](architecture.md) §3；各 Provider 的 NBT 数据内容见 [servux-providers.md](servux-providers.md)。

---

## 1. 一句话本质

Servux 用的是 **Mojang 在 1.20.2+ 引入的原版 `CustomPacketPayload`** 协议，**不是**旧的 Spigot plugin-messaging（`MC|Brand` 那套）。

- 每条功能 = 一条通道（`Identifier`，形如 `servux:hud_metadata`）
- 每条通道 = 一个 `CustomPacketPayload` 实现类型（`record Payload(...) implements CustomPacketPayload`）
- Fabric 的 `ServerPlayNetworking` / `PayloadTypeRegistry` **只是这套原版机制的注册封装**
- Payload 内部用 **VarInt `packetType`** 区分子消息，消息体是 **NBT（`CompoundTag`）** 或 **原始字节（`FriendlyByteBuf` slice）**

→ 对移植的决定性意义：**Paper 经 paperweight userdev 同样能直接读写 `FriendlyByteBuf`/`CompoundTag`/`CustomPacketPayload`，且 plugin messaging channel 直接映射到这些原版通道**（机制见 [architecture.md](architecture.md) §3.1）。

---

## 2. 通道总表（5 条数据通道 + 1 配置 provider）

| 通道 ID（channel） | 协议版本 | Provider（逻辑名） | Packet 类 | 客户端配套 Mod | 用途 |
|---|---|---|---|---|---|
| `servux:hud_metadata` | **3** | `HudDataProvider`（hud_data） | `ServuxHudPacket` | **MiniHUD** | 世界元数据 / 出生点 / 天气 / 配方 / TPS·MobCap logger |
| `servux:entity_data` | 2 | `EntitiesDataProvider`（entity_data） | `ServuxEntitiesPacket` | MiniHUD / Tweakeroo | 方块实体 & 实体 NBT 查询（含玩家背包权限过滤——仅查他人时剥离） |
| `servux:tweaks` | 2 | `TweaksDataProvider`（tweaks_data） | `ServuxTweaksPacket` | Tweakeroo | NBT 查询（潜影盒堆叠未实现，见 [architecture.md](architecture.md) §5.3） |
| `servux:structures` | **3** | `StructureDataProvider`（structure_bounding_boxes） | `ServuxStructuresPacket` | MiniHUD | 原版结构边界框（村庄/神殿/要塞…；按客户端 `max_receive_s2c` 能力条目级分批，机制见 [architecture.md](architecture.md) §3.3） |
| `servux:litematics` | 2 | `LitematicsDataProvider`（litematic_data） | `ServuxLitematicaPacket` | **Litematica** | Litematica 投影粘贴 / 批量实体数据（S2C 投递与 C2S 文件接收已移除，见 §8.5 / [servux-schematic.md](servux-schematic.md)） |

> **配置 provider**：`ConfigProvider`（逻辑名 `servux_main`，channel 标记为 `servux:main`，但 `registerHandler` 是 NO-OP——**不注册网络通道、不下发网络包**），承载全局 settings，走 `/servux` 命令与 `servux.json` 持久化（见 [architecture.md](architecture.md) §2.6 / [operations.md](operations.md)）。

通道常量定义位置：各 Handler 类的 `CHANNEL_ID` 字段（如 `ServuxHudHandler.CHANNEL_ID = Identifier.fromNamespaceAndPath("servux", "hud_metadata")`）。

> ⚠️ **易错点**：provider 的 `getName()`（逻辑名 `"hud_data"`）与 `getNetworkChannel()`（网络通道 `servux:hud_metadata`）是**两回事**。Paper 端注册 plugin messaging 通道必须用**通道网络名**（从各 Handler 的 `CHANNEL_ID` 抄），不是 provider 名。

---

## 3. `CustomPacketPayload` Payload 模型（以 HUD 为模板）

> 原版：`network/packet/ServuxHudPacket.java`。其余 4 条通道的 Packet 类**结构完全同构**，只是 `Type` 枚举与字段不同。

### 3.1 Payload record（协议帧）

```java
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

- `toPacket`：先 `output.writeVarInt(packetType.get())`，再按类型 `writeNbt(nbt)` 或 `writeBytes(buffer.copy())`。
- `fromPacket`：先 `input.readVarInt()` → `Type.getType(i)`，再按类型 `readNbt()` 或 `readBytes(...)` 重建 packet。

> **HUD 的 NBT 字段内容**（每种 packetType 对应哪些 NBT 字段）见 [servux-providers.md](servux-providers.md) §HUD。

### 3.3 子消息类型枚举（以 HUD 为例）

```java
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

**规律**（所有通道通用）：偶数/奇数不代表方向，按枚举顺序：`S2C_*` = 服务端→客户端；`C2S_*` = 客户端→服务端。末尾的 `*_RESPONSE_START(10)` / `*_RESPONSE_DATA(11)` 是**大包分包**专用（NBT 超过单包上限时用，分片机制见 [architecture.md](architecture.md) §3.3）。当前线枚举全表见 §5.6。

---

## 4. `IServerPayloadData` —— 协议数据的统一抽象

每个 Packet 实现该接口，统一暴露「协议版本 / packetType / 总大小 / 是否空 / 序列化反序列化 / 清空」：

```java
public interface IServerPayloadData {
    int getVersion();      // PROTOCOL_VERSION（各通道真值见 §2 通道总表——勿在此复述裸值，防版本线演进漂移）
    int getPacketType();   // 子消息 type id
    int getTotalSize();    // 估算字节数（用于诊断日志）
    boolean isEmpty();
    void toPacket(FriendlyByteBuf output);   // 序列化
    void clear();
}
```

**移植要点**：纯接口，可直接照抄；`PROTOCOL_VERSION` 常量务必与原版一致（客户端按版本协商，见 §5.2）。

---

## 5. 版本约束与安全闸 ⭐（26.2 线真值）

> 本节是跨版本兼容知识与 C2S 安全体制的唯一权威。wire 层面的升级核对方法论见 [operations.md](operations.md) 升级 SOP。当前线（26.1→26.2）协议面**零变化**——下列真值经 8 个上游仓 26.1/26.2 两树逐文件字节级 diff 实证（非抽样）。

### 5.1 MOD_STRING 硬门禁

客户端四处（minihud `HudDataManager:595` / minihud `DataStorage:851` / litematica `EntityDataManager:544` / tweakeroo `EntityDataManager:435`）校验 `servux.startsWith("servux-" + MOD_TYPE + "-" + MC_VERSION)`，`MOD_TYPE="fabric"`、`MC_VERSION` = Fabric loader 精确上游 id——**不匹配即整通道静默退网**。

→ 我方 `ServuxReference.MOD_TYPE = "fabric"`（伪装），MOD_STRING = `servux-fabric-<mcVersion>-b<buildNumber>`（版本段由 `gradle.properties` 唯一来源注入）。1.21.11 客户端只比对版本号不比对前缀，"paper" 才能蒙混——26.1 起堵死，`"paper"` 前缀会被四通道全部静默拒绝。

### 5.2 协议版本常量表

客户端收 metadata 按 `!=` **严格相等**校验自行退网，错一个即整通道退网 + UnregisterReply：

| 通道 | 协议版本 |
|---|---|
| HUD | **3** |
| Entities | **2** |
| Tweaks | **2** |
| Structures | **3** |
| Litematics | **2** |

### 5.3 DataTag 线格式载体与逐 Type 分界

业务包 NBT 载体为 malilib DataTag 格式：`[int32 大端 压缩后长度][GZIP(具名根 NBT 流)]`，流内 = `[tagType=10][writeUTF("")][条目+TAG_END]`，与 NMS `NbtIo.write(tag, os)` 输出**逐字节兼容**（单测黄金向量实证）→ 实现为 `mod/servux/util/nbt/DataTagIo.java`（不移植 malilib 18 个 DataTag 类）。

**分界规则（逐 Type，不是逐通道）**：
- 全通道 metadata 1/2 恒 vanilla NBT；
- 分片 10-13 恒裸字节；
- 其余业务 Type 走 DataTag；**START 大包经 PacketSplitter 的重组整体**也是 DataTag（客户端 `DataByteBufUtils.fromByteBuf` 解析，含 recipe/structures bulk）；
- 幸存者：**Structures 通道包帧全程 vanilla/裸字节**（metadata writeNbt、STRUCTURE_DATA raw）——仅 START 重组整体为 DataTag。

边界：写端恒发 tagType=10 根（上游 EmptyData 的 0x00 单字节根会被 malilib 读端 `readFromNbtStream` 返回 null → 整包丢弃）；读端对 0x00 根容错为空 compound；GZIP 失败（ZipException）回落裸读；长度前缀是**压缩后**字节数、非 VarInt。

### 5.4 字节上限（行为语义）

字节上限**裸值唯一权威**见 [architecture.md](architecture.md) §3.4（32000/31995 分片、16MB 重组预检、64MB 接收缓冲、32767 客户端解码上限、1MiB Bukkit 限制）。协议侧语义：客户端（malilib）重组上限为 16MB——我方 `PacketSplitter.send` 入口对超限帧整帧拒发 + warn（log-and-drop），覆盖 RecipeManager 全量帧 / BulkEntityReply / Structures 全量帧等全部 S2C 分片帧；**上游 servux 无此预检——我方增强，勿随上游模板回退**。

### 5.5 C2S 解析双闸（`DataTagIo` 配额闸）

`DataTagIo.readTag` GZIP 流式直读 + `NbtAccounter.create(64MB)`——解压域逐结构计数、**先记账后分配**（`LongArrayTag.readAccounted` 字节码实证 accountBytes 先于 newarray），单闸同时拦截 gzip bomb（解压膨胀 ~1032×）与长度字段炸弹（小流内声明巨数组驱动 16GB 分配）；超配额 `NbtAccounterException` → warn + null 坏包丢弃。

- 与上游对齐：malilib `SizeTracker`（NETWORK_MAX_BYTES=64MB）即在解压流上逐字节计数、超限抛异常断读；上游 servux `NbtUtils` 即 `NbtIo.read(gzip 流, tracker)` 流式写法——**勿回退为整体解压进内存再解析**（多两次数据搬运且漏掉全部配额）。
- 两层关系：长度前缀闸（压缩字节 ≤64MB，帧长）与 accounter 闸（解压后语义字节，内容）正交覆盖；与 16MB 重组闸（S2C 发送方向）互不重叠。
- 异常三分语义（空 tag = peek 0x00 / 解压期 EOF；null = 超配额 / 解析失败；ZipException → 裸读回落）由 `DataTagIoTest` 13 用例锁定。

### 5.6 C2S 变化与枚举全表（26.1 起形态）

- **`transactionId` 前置 VarInt 整体删除**（请求直读 BlockPos / VarInt entityId / ChunkPos；收端残留吞读会把首字节吃掉 → 全部错位）。
- **批量重组体**（投影上传）无 type VarInt 前缀，改按 NBT `"Task"` 字符串路由（现存路由仅 `LitematicaPaste`）。
- **`UNREGISTER_REPLY`**：HUD=9、Entities=7、Tweaks=7、Litematics=8——服务端 decode→unregister（我方映射为 provider.removePlayer）。
- **METADATA_REQUEST 语义** = 「先 unregister 再 register」（再注册）。
- **枚举增删**：HUD +`UNREGISTER_REPLY(9)`；Entities/Tweaks 各 +(7)；Litematica +(8) + task 组 `TASK_REQUEST(14)/TASK_RESPONSE(15)/TASK_STATUS_SYNC(16)/TASK_CANCEL(17)`；**Structures 删 10/11/12**（spawn/weather 完全收敛到 HUD 通道）。
- **task 组（14-17）**：客户端在检测到 servux 服务端后 Fill/Delete 选区**强制**走 `PACKET_C2S_TASK_REQUEST`（无超时回退、无能力探测）；type 14 受理 Fill/Delete；Paste 经 `LitematicaPaste` 批量路由；type 16 为三类任务共用进度/完成帧唯一 S2C 出口；type 15 客户端接收端 TODO 故服务端永不发送；type 17 上游同源忽略。服务端 as-built 见 [servux-schematic.md](servux-schematic.md) §9。
- **隐私裁剪**：查询**他人**玩家实体时按 `nbt_allow_player_inventory` / `player_inventory_permission_level`（ender 同理）清空 `Inventory` / `EnderItems`。
- **零变化**：PacketSplitter 分片帧（`[VarInt 总长][分片…]`、常量逐字一致）、HUD v3 数据字段（20 字段名比对零增删改）。

---

## 6. `IPluginServerPlayHandler` —— 收发封装接口

> 原版是 Fabric networking 的薄封装，定义「一条通道怎么注册、收、发、分包」——移植时**改动最大**的一层。逐方法替换：

| Fabric 方法 | 作用 | Paper 替换 |
|---|---|---|
| `getPayloadChannel()` | 返回通道 ID | 同（用通道网络名字符串） |
| `registerPlayPayload(Type, codec, direction)` | 注册 payload | `Messenger.registerIncomingPluginChannel` (C2S) + `registerOutgoingPluginChannel` (S2C) |
| `registerPlayReceiver(Type, handler)` | 注册接收器 | `Messenger.registerIncomingPluginChannel(plugin, channel, listener)` |
| `unregisterPlayReceiver()` | 反注册 | `Messenger.unregisterIncomingPluginChannel` |
| `receivePlayPayload(payload, ctx)` | 收包入口 | `PluginMessageListener.onPluginMessageReceived(channel, player, bytes)` → 包一层成 Payload |
| `sendPlayPayload(player, payload)` | 发 S2C | `player.sendPluginMessage`（已声明）；未声明且已在本通道发过 C2S → NMS `connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes)))` 兜底（[architecture.md](architecture.md) §3.2；**勿**直发自定义 Payload record，会 CCE） |
| `sendPlayPayload(networkHandler, payload)` | 走 `ServerGamePacketListenerImpl.send` | NMS `player.connection.send(...)`（**这条在 Paper 上可原样用**） |
| `encodeWithSplitter(player, buf, networkHandler)` | 分包时每片发送回调 | 调 Paper 版 `sendPlayPayload` |

### 6.1 `ServerPlayHandler` 单例（handler 注册表）

`Map`/Multimap 维护「通道→handler」（Servux 每通道只有一个 handler）。Paper 端经 `framework/network/ChannelManager` 统一注册。

### 6.2 HUD handler 的收发流程（典型样板）

**接收（C2S）**：
```
PluginMessageListener 收到 bytes → ServuxHudHandler.receivePlayPayload
  → decodeServerData(CHANNEL_ID, player, data)
  → 入口闸：!isEnabled() || !checkFailures(player) → 丢弃（五 Handler decode/encode 双侧）
  → switch(packet.getType()):
       C2S_METADATA_REQUEST      → 已注册先 unregister → register(player, nbt)   // 版本门禁（见下）
       C2S_SPAWN_DATA_REQUEST    → refreshSpawnMetadata(player, nbt)
       C2S_RECIPE_MANAGER_REQUEST → refreshRecipeManager(player, nbt)
       C2S_DATA_LOGGER_REQUEST   → refreshLoggers(player, nbt)
```

**C2S 注册版本门禁 + 名册拦截**（对齐上游 `register()` 语义，五通道同构）：`register(player, tags)` 首查 `tags == null || tags.getIntOr("version", -1) < PROTOCOL_VERSION`（严格 `<`——相等/更高均放行）→ 拒绝四件套（warn 日志 + `MSG_PROTOCOL_VERSION_TOO_LOW` 预格式化聊天提示 + `tickFailures` 检疫 + return **不入册**）；名册 `isPlayerRegistered = registeredPlayers && !invalid` 承载拒绝状态——后续全部 C2S 请求入口与 S2C 推送路径（join/声明重发、tick 周期）均按名册白名单拦截，被拒旧客户端只收 3 次拒绝消息（`maxFailures()=2`，count=3 起 decode/encode 双侧 `checkFailures` 闸静默），永收不到 metadata。

**发送（S2C）**：各 `refresh*` 构造 packet → `HANDLER.encodeServerData(player, packet)`：
```
if packet.type == PACKET_S2C_NBT_RESPONSE_START:   // 大包 → 分包
    buffer.writeNbt(packet.getCompound());
    PacketSplitter.send(...)  → 每片 encodeWithSplitter → sendPlayPayload(ResponseS2CData(slice))
else:
    sendPlayPayload(player, new Payload(packet))
```

**失败计数**（deny 检疫与发送失败共用同一份计数）：`sendPlayPayload` 返回 false（客户端没装 mod / 通道未就绪）→ `tickFailures` 计数；超限（`> maxFailures() = 2`）标记 invalid 且**不清零**——重置仅在 `resetFailures`（unregister / removePlayer[quit] 触发）；decode/encode 入口的 `checkFailures` 闸静默丢弃越限玩家的后续包。

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
     │  MiniHUD 重组，刷新配方提示
```

握手时序保障：HUD 有三道（onPlayerJoin 40t / onPlayerRegisterChannel 事件 / 客户端 C2S 主动请求）；entity/tweaks/litematics 亦有 `onPlayerRegisterChannel` 重发（幂等）。minihud structures 的单次接受窗口与 REGISTER 即时回复机制见 [architecture.md](architecture.md) §3.2。

---

## 8. 与上游的逐通道差异/适配点（26.2 复核真值）

> wire 与可观测行为与上游逐项等价；下列为我方实现侧的适配差异（非协议差异）。EasyPlace/潜影盒等 Mixin 行为类差异见 [architecture.md](architecture.md) §5.3。

### 8.1 HUD（servux:hud_metadata，协议版本 3）

- `Reference` → `ServuxReference`；`Permissions.check` → `Perms.check`。
- `registerHandler`：删除 `registerPlayPayload/registerPlayReceiver`（plugin messaging 注册即收发），仅 `ServerPlayHandler.registerServerPlayHandler(HANDLER)` + `setRegistered(true)`。
- `sendMetadata`：删除 NMS `networkHandler` 重载分支，统一走 plugin messaging（`HANDLER.sendPlayPayload`）。
- `tick`：去掉 `ProfilerFiller` 形参与 `profiler.push/pop`。
- **天气采集**：原版 Mixin `advanceWeatherCycle` → tick 内周期读 `ServerLevel.getWeatherData()`（`getClearWeatherTime/getRainTime/getThunderTime/isRaining/isThundering` 同名方法保留）。
- **出生点**：原版 Mixin 回填 `setSpawnPos`，我方用 `updateSpawnFromServer`（Bukkit `World.getSpawnLocation`）。
- **onPlayerJoin 握手**：plugin messaging 通道握手需时间，延迟 40t（2s）后 `sendMetadata`。

### 8.2 Entities（servux:entity_data，协议版本 2）

- 方块实体 NBT：`be.saveWithFullMetadata(registryAccess)`（NMS 公开，照抄）。
- 实体 NBT：`NbtView.getWriter` + `entity.saveWithoutId`（NbtView 重写绕开 IMixinNbtWriteView，反射 `TagValueOutput.output`）。
- `hasNbtQueryPermission`：原版 NMS `Permissions.COMMANDS_GAMEMASTER` → 按 op level 2（等价 `/data get` 默认）。

### 8.3 Tweaks（servux:tweaks，协议版本 2）

- NBT 查询复用 `EntitiesDataProvider` 权限方法。
- **潜影盒堆叠——不可能实现，已删除**：已从 provider 删除 `stackable_shulkers` 系列 setting 与 `stackingShulkers/stackingShulkersMax` 元数据下发（见 [architecture.md](architecture.md) §5.3）。
- 修正原版 `ResponseS2CData` L120/121 重复赋值 bug。

### 8.4 Structures（servux:structures，协议版本 3）

- **触发**：原版 Mixin `markChunkPendingToSend` → `onStartedWatchingChunk` 精确触发；我方改**周期扫描**（tick 内遍历玩家 view distance 区块，去重发送；`update_interval` 默认 40t，性能注记见 [architecture.md](architecture.md) §8）。
- NMS 结构采集（paperweight 直连，不需反射）：`ChunkAccess.getAllReferences/getStartForStructure/StructureStart.createTag/StructurePieceSerializationContext.fromLevel`。
- 黑白名单 setting 保留；timeout 简化为周期全量刷新。
- **`max_receive_s2c` 能力协商已移植**（register 读 TAG_INT 存名册 entry，条目级分批机制见 [architecture.md](architecture.md) §3.3；当前线客户端零发送点恒走默认 16MB，机制层对齐、真实环境不可观测）。
- `unregister` 单参（上游 tags 形参全实现未读，有意简化）。

### 8.5 Litematics（servux:litematics，协议版本 2）—— ✅ 全功能（含投影粘贴；C2S/S2C 文件传输已移除）

- **元数据握手 / 方块实体 NBT 查询 / 实体 NBT 查询 / 批量实体查询（onBulkEntityRequest）**：✅ 实现（复用 Entities 的 `NbtView` + `be.saveWithFullMetadata` / `entity.saveWithoutId` + 玩家背包/末影箱权限过滤；批量查询拼 ListTag 走 PacketSplitter 分包）。
- **协议帧（toPacket/fromPacket）**：✅ 照抄原版（SliceKey + CHANNEL_ID=servux:litematics + 协议版本 2）。
- **投影粘贴（C2S 上传 .litematic，任务化）**：✅ 已实现——客户端上传分片经 `ServuxLitematicaHandler` 重组 → 重组完成后无条件走 `LitematicsDataProvider.handleClientPasteRequest`（上游 0.10.7 `:201` 同构；Task 门控在 Provider——非 `LitematicaPaste` 静默忽略）加载 `SchematicPlacement` 后创建 `PasteTask` 登记 TaskScheduler 分 tick 粘贴。scheduler/task 组 as-built 见 [servux-schematic.md](servux-schematic.md) §9。
- **S2C 文件投递命令**：⛔ **已移除**——当前 stock 客户端 `handleBulkData` 的 Transmit 分流整块注释（无接收端，帧被静默丢弃），上游 `sendTransmitFile` 亦 `@Deprecated(forRemoval)` 零调用点。死信链（`/servux litematic transmit` 命令 + `sendTransmitFile` + 文件字节级门禁）已物理删除，恢复走 git revert。修复版 litematica（≥0.26.11）客户端对 Transmit 帧双向静默丢弃。
- **C2S 文件接收**（`Litematic-Transmit*` → `receiveFileTransmit` 落盘）：⛔ **已移除（安全修复）**——客户端可控 `FileName` 直达 `Path.of`/`dir.resolve` 无包含性检查，构成**路径穿越任意写/删/读回**原语（上游安全公告漏洞同源；另有 `new Slice[客户端控长]` 的 OOM 向量），上游同判禁用该实验性接收功能。整链物理删除，恢复走 git revert。
- **降级点**（schematic 边缘能力，不影响粘贴主链路）：从世界选区创建/采集投影（保存侧）、Sponge/Vanilla structure 格式导入、DataFixer 旧版转换——服务端只消费现成 .litematic，这些原版保存/转换 API 保留签名返回默认值（见 [servux-schematic.md](servux-schematic.md) §7）。

---

> **相关**：[architecture.md](architecture.md)（网络框架与字节限制裸值）· [servux-providers.md](servux-providers.md)（NBT 字段内容）· [servux-schematic.md](servux-schematic.md)（投影子系统与 task 组）· [servux-testing.md](servux-testing.md) · [operations.md](operations.md) · [index.md](index.md)
