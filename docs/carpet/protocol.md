# Carpet carpet:hello 协议（1.21.11 线 as-built）

> 本文是本线 carpet:hello 通道的 **wire as-built 规范**（自 dev 线铺开，1.21.11 线协议面与 26.2 逐字同源——上游三树 `network/` 逐字节相同已实证；唯一 per-line 值 = HI 版本串）。模块装配与规则框架见 [architecture.md](architecture.md)。
> 上游对照 = `OriginImpl/fabric-carpet-1.21.11/src/main/java/carpet/network/`（分支 `1.21.11` 三件套 + `CarpetServer.java` 挂点）。

---

## §1 通道与载体

- 通道：`carpet:hello`（运行时注册，plugin.yml 零条目）。
- 载体 = 单 CompoundTag（vanilla `writeNbt/readNbt`），消息区分靠字符串键（`"69"`=HI / `"420"`=hello / `"Rules"` / `"clientCommand`）。
- **S2C 唯一路径 = NMS `DiscardedPayload` 直发**：carpet 客户端不经 Fabric networking 注册本通道 → 从不发 `minecraft:register` 声明 → plugin messaging 结构性不可达。帧恒 KB 级，零分片需求。

## §2 消息面全表

| 消息 | 方向 | 键 | 载荷 |
|---|---|---|---|
| HI | S2C（进服无条件，含假人） | `"69"` | `CarpetReference.CARPET_VERSION` = **`"1.4.194"`**（上游 mod_version 逐字——**禁止发插件版本号**，客户端 `equals` 严格比较；26.2 线为 `"26.2"`、26.1.2 线为 `"26.1"`） |
| hello | C2S（应答） | `"420"` | 客户端版本串（真实客户端带 `+v` 日期后缀） |
| Rules | S2C（全量帧 / 增量帧） | `"Rules"` | `{规则名: {Value, Manager:"carpet", Rule}}`，撞名追加 "2" |
| clientCommand | C2S（请求）/ S2C（回执） | `"clientCommand"` | 命令串 + 回执文本 |
| 自定义 NBT | S2C | 任意其他键 | 机制面保留（上游同源） |

## §3 握手时序与名册

- 进服（`PlayerJoinEvent` 含假人）无条件发 `HI {"69": CARPET_VERSION}`；carpet 客户端应答 `hello` → 名册写入 + 服务端镜像日志（匹配 info / 不匹配 warn——真实客户端版本带 `+v` 日期后缀时 warn 属上游 equals 语义同源产物）+ `Rules` 全量帧下发。
- vanilla/未装 carpet 客户端：HI 静默丢弃（未知通道 clientbound 上限 1MiB，帧 ~30 字节），零影响。

## §4 规则同步链

- 全量帧（hello 应答后）+ 增量推送（命令路径改规则 → 单规则帧多播名册全体）；gate 封闭清单见 dev 线 `carpet/protocol.md`（原始论证）。
- 挂载点 = `SettingsManager.notifyRuleChanged`。

## §5 clientCommand（请求-回执）

`CarpetSnoopyCommandSource` 以玩家自身权限执行 + INFO 审计行（Snoopy 移植——捕获命令输出回执客户端）。

## §6 装配与生命周期

`CarpetHelloHandler`（ChannelManager 注册 + join/quit 监听 + 规则推送回调挂载 + 在线玩家补扫 HI——reload 后对在线玩家补发）。装配链详见 [architecture.md](architecture.md) §1。

## §7 测试锚与实机判据

- 单测：`CarpetHelloFrameTest`（HI 版本串字面锁 `"1.4.194"` + 帧形状）。
- 实机：进服握手镜像日志、规则浏览器 18 条、`/call` 往返、tab 脚 HUD（不走本通道——[architecture.md](architecture.md) §3）。用户侧终验清单见 [architecture.md](architecture.md) §6.4。

## §8 偏差与上游差异

- 与上游的网络层差异（plugin messaging 不可达 → NMS 直发）为 Paper 平台结构性替代，wire 帧本身逐字同源。
- 全量偏差清单的原始论证见 dev 线 `carpet/architecture.md` / `carpet/protocol.md`（dev 分支树）；本线适配面（HI 常量 + javadoc 谱系）见 [architecture.md](architecture.md) §6.1。

---

> **相关**：[architecture.md](architecture.md)（模块 as-built）· [operations.md](../operations.md)（运维）· [index.md](../index.md)（总索引）。
