# Carpet carpet:hello 协议（Phase 3 as-built）

> **状态**：Phase 3 已实现（2026-10-18，dev 线 26.2）。本文是 carpet:hello 通道的 **wire as-built 规范**：
> 通道与载体（§1）、消息面全表（§2）、握手时序与名册（§3）、规则同步链（§4）、clientCommand（§5）、
> 装配与生命周期（§6）、测试锚与实机判据（§7）、偏差清单（§8）。
> 上游对照 = `OriginImpl/fabric-carpet-26.2/src/main/java/carpet/network/`（tag v26.2 三件套 +
> `CarpetServer.java` 挂点）；模块装配与规则框架 = [carpet/architecture.md](architecture.md)。
> 字节上限裸值唯一权威 = [architecture.md](../architecture.md) §3.4。

---

## §1 通道与载体

- **通道**：`carpet:hello`（上游 `CarpetClient.java:48` 逐字；B 侧 `CarpetReference.CARPET_CHANNEL`，P1 预声明）。
- **载体**：`record CarpetPayload(CompoundTag data)` —— **单 CompoundTag 经 vanilla `writeNbt/readNbt`**
  （上游 `CarpetClient.java:18-38`）。不是 servux 的 DataTag、无 VarInt packetType 前缀——消息区分靠
  CompoundTag 的**字符串键**（`"69"`/`"420"`/`"Rules"`/`"clientCommand"`）。
- **S2C 唯一路径 = NMS `DiscardedPayload` 直发**（`CarpetPacketSender.send` = `player.connection.send(
  new ClientboundCustomPayloadPacket(new DiscardedPayload(CARPET_CHANNEL, bytes)))`，黄金模板
  `JeiPacketSender` 同款）。**结构性理由（勿改走 plugin messaging）**：carpet 客户端不经 Fabric
  networking API 注册本通道——其 codec 由 `CustomPacketPayload_networkStuffMixin` 直注 vanilla codec
  清单（上游全树零 `PayloadTypeRegistry`）→ 客户端从不发 `minecraft:register` 声明 →
  `PlayerRegisterChannelEvent` 对本通道**永不触发**、Paper 声明门控（`CraftPlayer.sendPluginMessage`
  按 `channels().contains` 静默丢弃）使 plugin messaging S2C 恒不可达；进服首帧 HI 先于任何 C2S，
  「同通道 C2S 证明兜底」（architecture.md §3.2）在握手起点结构性不可用。
- **C2S 接收**：`ChannelManager.register(CARPET_CHANNEL, handler)`（incoming + outgoing 成对）——
  Paper 26.2 路由补丁把已注册通道的 C2S 交给 Messenger 分发（不踢人）；敌意 NBT 由 vanilla
  `readNbt()` 自带 `NbtAccounter.defaultQuota()`（2MiB + 512 深度，javap 实证）封顶。
- **帧尺寸**：恒 KB 级（18 规则全量帧 < 10KB），零分片需求，不涉 `PacketSplitter`。
- **vanilla / 未装 carpet 客户端**：HI 对所有进服玩家无条件发送（上游同构，无客户端类型 gate）——
  vanilla 把未知通道 payload 解码为 DiscardedPayload 后静默丢弃（clientbound 未知通道上限 1MiB，
  HI 约 30 字节），构造性零影响；假人 join 同样触发 HI，被 `FakeClientConnection.send` no-op 吸收
  （上游同构——实现**无连接类型分支**）。

## §2 消息面全表

| # | 方向 | 键 | 载荷 | B 侧实现 | 上游锚 |
|---|---|---|---|---|---|
| 1 | S2C | `HI`（"69"） | string（服务端 carpet 版本） | `onJoin → sendHi`：`{"69": CARPET_VERSION}` | ServerNetworkHandler.java:38-50 |
| 2 | C2S | `hello`（"420"） | string（客户端 carpet 版本） | `receivePlayPayload → onHello` | :52-67 |
| 3 | S2C | `Rules` | `{规则名键: {Value, Manager, Rule}}` | `onHello → sendRules`（全量）+ `onRuleChanged`（单规则增量） | :64-66 / :219-239 |
| 4 | C2S | `clientCommand` | `{command, id}` | `receivePlayPayload → handleClientCommand` | :80-115 |
| 5 | S2C | `clientCommand` 回执 | `{id, error?, return, output?}`（output 空则无键） | `buildClientCommandReply` | :97-113 |
| 6 | S2C | 自定义 NBT（任意键） | 任意 Tag | `broadcastCustomCommand` / `sendCustomCommand`（机制面，B 侧无调用者——上游唯一在用者 scShapes 随 Scarpet 不移植） | :144-162 |
| 7 | C2S | 未知键 | — | warn 日志丢弃（上游 onClientData 同构） | :117-130 |

**版本串纪律（同 servux MOD_STRING 门禁级）**：HI 帧版本串 = `CarpetReference.CARPET_VERSION` =
上游 mod_version 逐字值（26.2 线 = `"26.2"`，`OriginImpl/fabric-carpet-26.2/gradle.properties:15`），
**禁止发插件版本号**（`26.2-b3` 之类必触发客户端 `equals` 严格比较 warn）——`CarpetHelloFrameTest.
carpetVersionLiteralGuard` 单测锁死。ver 线 per-line 取值（**as-built 2026-10-19，两线已铺开**：
26.1.2 线 = `"26.1"`（commit `0e278bc`）、1.21.11 线 = `"1.4.194"`（commit `71fe5b7`）——各线
上游 `gradle.properties` 逐字对齐，字面锁随线同步；各线 as-built 见其自含文档 `31-carpet.md` /
`carpet.md`）。

**版本 warn 语义（判据①口径）**：客户端对其自身版本与 HI 值做 `equals` 严格比较
（`ClientNetworkHandler.java:95-101`），不等仅本地 LOG.warn（非聊天、不断连）。真实发布的 carpet
客户端版本串带 `+v<构建日期>` 后缀（上游 `build.gradle:10` 无条件拼接）→ 发 `"26.2"` 时此类客户端
必打一条 warn——与两个不同日期构建的上游 carpet 服互连行为完全同源，属上游语义的一部分；
**判据① = 无 chat 可见/断连级警告**（服务端镜像 info=匹配 / warn=不匹配，照抄上游 :56-63）。

## §3 握手时序与名册

```
玩家进服（PlayerJoinEvent，含假人）
  │ S2C HI {"69":"26.2"}（NMS 直发，无条件）
  ▼
carpet 客户端（装 carpet mod）            vanilla / 未装 carpet / 假人
  │ 记版本 + LOG（equals 严格比较）          │ 未知通道 payload 静默丢弃 / 假连接 no-op 吸收
  │ C2S hello {"420":"<客户端版本>"}
  ▼
服务端 onHello：
  ├─ 名册写入（UUID → 客户端版本）
  ├─ 服务端镜像日志（匹配 info / 不匹配 warn）
  └─ S2C Rules 全量帧（13 条全量，含默认值规则）
       ▼ 客户端按 Manager=="carpet" + Rule 名查自身注册表 set 值 → 规则浏览器生效
此后：规则变更（命令路径）→ 单规则帧多播给名册全体；clientCommand 请求-应答随时可用
```

- **名册 = 单表 `Map<UUID, String>`**（hello 写入、quit 清除、shutdown 清空）。上游 valid/remote 双集
  的差异仅在单机集成服（memory connection）场景，Paper 专用服恒等价；UUID 键对 respawn 实例重建
  免疫（上游以 ServerPlayer 实例为键，靠 connection 存续送达——B 侧加固，偏差 #1）。
- **客户端规则可达集 = 我方规则集 ∩ 客户端本地同名规则集**（客户端 `ClientNetworkHandler.java:60-61`
  按 `Manager`+`Rule` 在自身注册表查名，查不到静默跳过、零报错）——**硬门禁：进 Rules 帧的规则名
  必须逐字存在于上游 `CarpetSettings` 注册表**（当前 18 条已逐一核验——P4 的 commandLog/defaultLoggers
  为上游 :453-463 实名；P5 的 commandSpawn/commandDistance/commandInfo/commandPerimeterInfo/commandDraw
  为上游 :411-412/:465-490 实名逐字；接入新规则时同守此门）。
- Manager 键恒 `"carpet"`（= `SettingsManager.identifier()`，`CarpetModule` 构造注入 `MOD_ID`）——
  与客户端主管理器查找分支精确匹配。

## §4 规则同步链

- **全量帧**（onHello 应答）：`SettingsManager.getRules()` 全部规则（含默认值——上游 :64-66 口径），
  每条 `{Value: String.valueOf(value()), Manager: "carpet", Rule: name}`；键撞名时追加 `"2"`
  （上游 :229-232 while 循环逐字保留——多管理器场景防御，B 侧单管理器下不可达，单测经直构双同名
  规则锁死该行为）。
- **增量推送**：`ParsedRule.set`（命令路径 source≠null）→ `SettingsManager.notifyRuleChanged`
  （经 `setRuleChangedCallback` 注入的回调——保持 settings/ 包零 network/ 依赖，P1 单测形态不破）
  → `CarpetHelloHandler.onRuleChanged` → 名册全体单规则帧。**conf 载入（source==null）不推送**
  （上游同构）——载入后的首帧由下一次 hello 应答的全量 Rules 承载（reload 补扫链恰好闭合该空窗）。
- **superSecretSetting gate 封闭清单（勿外溢）**：仅三处抑制——①规则变更推送
  （上游 :134-137）②`broadcastCustomCommand`（:146-149）③`isValidCarpetPlayer`（:181-184）；
  **onHello 的全量 Rules 下发豁免**（上游 :52-67 无 gate）——superSecretSetting=true 期间新进服
  客户端仍收全量、仅增量广播抑制。

## §5 clientCommand（请求-回执）

- 执行载体 = `CarpetSnoopyCommandSource`（上游 `SnoopyCommandSource` 移植）：以**玩家自身权限**执行
  （`getProfilePermissions(player.nameAndId())`，无提权；`withPermission/withMaximumPermission` 返回
  this 锁级），覆写 `sendSuccess/sendFailure` 采集输出/错误；构造走 26.2 public 9 参构造器，
  覆写集 = 上游封闭 11 方法清单（withEntity/withPosition/withRotation/withCallback×2/withPermission/
  withMaximumPermission/withAnchor/withSigningContext/withLevel/facing）。
- 执行入口 = `server.getCommands().performPrefixedCommand(source, command)`（NMS 直调）。
- **回执**：`{id, error?[错误文本], return:0, output?[ListTag<String>]}`（output 空则无键，
  上游 :109-112）。客户端只消费 id/error/output（`CarpetClient.onClientCommand:95-112` 逐行实证）。
- **安全面**：上游同构零门禁（不查名册/不限速）+ B 侧增补 **INFO 审计行**（`[Carpet] clientCommand
  from <玩家>: <命令>`）——`performPrefixedCommand` 直调 NMS 命令链绕过
  `PlayerCommandPreprocessEvent` 与 ops 广播，审计行补偿该缺口（偏差 #4）。畸形包缺键抛
  NoSuchElementException → 框架 `ProtocolChannel` catch 记 warn 丢弃（不踢人——plugin messaging
  路径结构性语义，与上游 Fabric 层解码失败踢人不同，偏差 #5）。

## §6 装配与生命周期

- **onRegister 追加**（`CarpetModule`）：`new CarpetHelloHandler` → `ChannelManager.register`
  → `registerEvents(handler)`（join/quit）→ `sm.setRuleChangedCallback(handler::onRuleChanged)` →
  **对 `Bukkit.getOnlinePlayers()` 补发 HI**（插件中途启用/reload 时在线玩家无 join 事件——
  上游 init 期无在线玩家、无此路径，B 侧增补闭合「reload 后规则推送永久空转」半失效态，偏差 #6）。
- **disable 追加**：`ChannelManager.unregister(CARPET_CHANNEL)` + `handler.shutdown()`（名册清空；
  须先于框架 `ChannelManager.unregisterAll`——`VeryMcProto.onDisable` 时序，jei 先例）。
- plugin.yml **零改动**（通道 = 运行时 Messenger 注册，先例 jei/syncmatica；clientCommand 是协议
  请求非 Bukkit 命令，无新权限节点）。

## §7 测试锚与实机判据

- `CarpetHelloFrameTest`（5 用例，纯 JVM）：Rules 帧 `{Value,Manager,Rule}` 形状 + 全量口径 +
  布尔串化（"true"/"false"）+ Manager=="carpet" + 撞名 "2" 循环（直构双同名规则）+ clientCommand
  回执缺省键（error/output 空则无键）+ 版本串/键名/通道名字面守卫（`"26.2"`/`"69"`/`"420"`/
  `carpet:hello`——防误换插件版本号）。
- **实机判据（四条；判据①为改写口径）**：① carpet Fabric 客户端进服无 chat 可见/
  断连级警告（客户端本地 LOG.warn 属上游 equals 语义同源产物，接受）；② 客户端规则浏览器显示
  我方 13 条规则集（值随 `/carpet setDefault` 即时变更）；③ `clientCommand` 请求-回执往返（carpet
  客户端 `/call <cmd>`，输出回显于客户端日志）；④ 未握手（vanilla / 未装 carpet）玩家零影响
  （进服无感知、无断连；假人用例含内——HI 被假连接吸收）。
- 排错入口（operations.md §5.1）：服务端 `[Carpet]` 前缀日志——`joined with a matching carpet
  client` / `joined with another carpet version: <v>`（握手成立与版本比对）、`clientCommand from`
  （命令执行审计）、`Unknown carpet client data`（未知键包）。

## §8 偏差清单（P3，B ↔ 上游）

| # | 偏差 | 理由 |
|---|---|---|
| 1 | 名册单表 Map<UUID,String>（上游 valid/remote 双集，键 = ServerPlayer 实例） | Paper 专用服双集恒等价（memory connection 无对应物）；UUID 键 respawn 免疫（加固） |
| 2 | 回执 `return` 恒 0（上游经 Mixin setupPrivates 写 resultCallback 采真实值） | Paper 无 Mixin，基类私有域不可写；9 参构造器默认 `CommandResultCallback.EMPTY`（no-op）→ 恒 0 为确定性语义；客户端零消费 return（CarpetClient.java:95-112 逐行实证） |
| 3 | Snoopy 构造 = 9 参 public 构造器 + with* 保型覆写（上游 setupPrivates 写 5 私有域） | 同 #2 的移植路径；覆写集 = 上游封闭 11 方法（26.2 另有的 withSource/withLocation/withSuppressedOutput 上游不覆写，同判） |
| 4 | clientCommand 增补 INFO 审计行（上游无） | performPrefixedCommand 绕过 PlayerCommandPreprocessEvent 与 ops 广播——审计缺口补偿（安全增强，非 wire 变化） |
| 5 | 畸形包缺键 → warn 丢弃不踢（上游 orElseThrow 异常 = Fabric 层踢人） | plugin messaging 路径结构性语义（jei 同款先例）；我方更保守 |
| 6 | onRegister 对在线玩家补发 HI（上游 init 期无在线玩家、无此路径） | 闭合插件中途启用（reload）时在线客户端永失握手 + conf 载入推送空窗的半失效态 |
| 7 | join 挂点 = PlayerJoinEvent（上游 PlayerList Mixin placeNewPlayer RETURN） | Paper 无 Mixin；时点更晚但客户端 HI 接收持续有效（handleUnknownCustomPayload 无单次窗口），时序安全 |
| 8 | 删 getPlayerStatus（上游供 scarpet EntityValue.client_brand 消费） | Scarpet 整体不移植（终局判定），零调用点 |

---

> **相关**：[carpet/architecture.md](architecture.md)（模块装配/规则框架/假人）·
> [architecture.md](../architecture.md) §3（网络框架/S2C 路径/字节上限）· [operations.md](../operations.md) §5.1（排错）
