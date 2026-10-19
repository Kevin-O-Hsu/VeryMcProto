# Carpet → Paper 插件移植（1.21.11 线 as-built + 运维）

> **状态**：已实现（2026-10-19，自 dev 线 26.2 as-built 铺开）。本文是本线 Carpet 模块的**自含 as-built 与运维文档**
> （本线无独立运维文档，命令/权限/配置全键即本文 §8）：模块装配与规则框架（§1）、假人子系统（§2）、
> carpet:hello 协议（§3）、/log 日志（§4）、命令族（§5）、移植范围与非目标（§6）、**本线适配记录与验收（§7）**、
> 运维参考（§8）。
>
> 上游对照 = `OriginImpl/fabric-carpet-1.21.11/`（分支 `1.21.11`，mod_version = **`1.4.194`**——1.21.x 线沿用
> 语义版本号，与本线 mcVersion 不同源）。范围判定与偏差详情的原始论证见 dev 线文档（`carpet-porting-scope.md` /
> `carpet-architecture.md` / `carpet-protocol.md`，dev 分支树）；本线源码 javadoc 的 `docs/carpet.md §X` 锚点即指向本文。

---

## §0 定位

**Carpet 是「行为注入型」Fabric mod**（上游 171 服务端 Mixin）——与既有三 mod（协议投递型）根本不同。
本移植只携带**判定为可完整等价的子集**：规则框架 + `/carpet` 壳 + 18 条规则 + 假人 `/player` 全族 +
carpet:hello 协议 + `/log` 日志框架（tps/mobcaps HUD）+ 五条纯计算命令。Scarpet、TNT/红石/活塞优化类
Mixin 面不在移植范围（判定见 dev 线 scope 文档——上游三树 network/settings/logging 子目录逐字节相同已实证，
判定不随线漂移）。

- 模块：`mod/carpet/`（41 主源文件 + 7 测试类），主类第四 try-catch 块装配，`CarpetModule.disable()` 于 onDisable 回收。
- 上游版本串（协议握手用）：`CarpetReference.CARPET_VERSION = "1.4.194"` = 上游 `gradle.properties:16` 逐字
  （26.2 线该值为 "26.2"、26.1 线为 "26.1"——**禁止发插件版本号**，客户端 `equals` 严格比较）。

## §1 模块装配与规则框架

- `app/CarpetModule implements ModModule`：`onRegister` 装配链 = SettingsManager 单例 → 解析 `CarpetSettings`
  → `carpet.conf` 载入 → 行为监听器（`CarpetRuleListener`）→ 假人子系统（`FakePlayerListener` + `ActionPackManager`）
  → `/player`（Paper Brigadier lifecycle 注册）→ cleanLogs log4j 过滤器 → carpet:hello 通道 + 规则推送回调 +
  在线补扫 HI → `/log` 子系统（store 载入 + LoggerRegistry + HUD 任务）→ 五命令逐条注册（单命令失败只降级）。
- **规则框架**（`settings/`，上游 `api/settings/SettingsManager.java` 集群同构）：`@Rule` 注解 + 反射解析 +
  `carpet.conf` 纯文本持久化（逐行 `<rule> <value>`、`#` 注释、首行 `locked` = 锁定；文件从不主动创建；
  写盘原子 tmp/move）。上游注释怪癖逐字保留（值首字符 `#` 重读被吞——`SettingsManagerConfRoundtripTest` 锁定）。
- **`/carpet` 四子命令**（Bukkit `CarpetCommand`）：`list` / `setDefault <rule> <value…>` / `query <rule>` /
  `removeDefault <rule>`。**双层门禁**：plugin.yml 静态 `carpet.command`（op）+ 运行时动态
  `carpetCommandPermissionLevel` 规则（`ops`/`2`/`4` 经 NMS 判级）。
- **18 条规则**（上游实名逐字）：P1 地基 8（`language` / `carpetCommandPermissionLevel` / `superSecretSetting` /
  `explosionNoBlockDamage` / `commandTick` / `perfPermissionLevel` / `customMOTD` / `cleanLogs`）+ 假人 3
  （`commandPlayer` / `allowSpawningOfflinePlayers` / `allowListingFakePlayers`）+ 日志 2（`commandLog` /
  `defaultLoggers`）+ 命令族 5（`commandSpawn` / `commandDistance` / `commandInfo` / `commandPerimeterInfo` /
  `commandDraw`）。**规则名必须逐字存在于上游 `CarpetSettings` 注册表**（客户端规则浏览器可达性硬门禁）。
- 行为面要点：`explosionNoBlockDamage` = EntityExplode/BlockExplode 双事件 `blockList().clear()`；
  `customMOTD` = `ServerListPingEvent#setMotd`；`cleanLogs` = log4j 过滤器（默认 false=**抑制**命名死亡日志行，
  极性勿反转）；`perfPermissionLevel` 只读登记（值 4 天然同构、值 2 结构性不可达）；`commandTick` 零行为死规则
  （上游同构——`/tick` 即原版命令）。

## §2 假人子系统（/player 全族）

上游 `patches/` 三件套（纯子类零 Mixin）+ `EntityPlayerActionPack`（629 行纯逻辑）+ `/player` brigadier 树。

| B 侧类 | 说明 |
|---|---|
| `patches/EntityPlayerMPFake` | spawn 走原版 `placeNewPlayer` 链（占 tab 栏/计入睡跳夜/playerdata 持久/可被 /tp /ban 识别全部原生承载）；kill = 完整退出链；respawn 实例复用零重组 |
| `patches/FakeClientConnection` | EmbeddedChannel 直赋 + 五 no-op 覆写（假人"能收不能回"） |
| `patches/NetHandlerPlayServerFake` | kick 拦截按 Paper 漏斗终端重推导（idling/duplicate_login 两 key 转 kill，其余吞掉）；`teleport` 覆写 = 上游逐字（resetPosition + chunkSource.move） |
| `helpers/EntityPlayerActionPack` | 动作状态机（once/continuous/interval 三模式，写 xxa/zza 运动输入） |
| `helpers/ActionPackManager` | 侧表 + BukkitRunnable 每 tick 驱动（替代上游 Mixin 字段 + tick HEAD 注入） |

- 命令面：`spawn [at <pos> [facing <rot> [in <dim> [in <gamemode>]]]] [in <gamemode>]`（**默认创造模式**——
  控制台源无玩家上下文时 `GameType.CREATIVE`；创造假人免掉落/环境伤害属 vanilla 语义）、`kill`、`shadow`、
  `stop`、`mount/dismount`、`sneak/sprint`、`look/turn`、`move`、`attack/use/jump/drop/hotbar/swapHands × 三模式`。
- 权限：无 Bukkit 节点——门禁 = `commandPlayer` 规则（`ops`/`true`/`false`/`0-4` 动态）。
- 插件卸载/reload 自动 kill 全假人（防 NMS 对象引用已卸载类 → NoClassDefFoundError）。
- **已知缺口**：`/tick freeze` 不冻结假人（Paper `isEntityFrozen` 恒豁免 Player，无注入点——唯一功能缺口）；
  击退保真经 `PlayerVelocityEvent` 取消近似（≤1 tick 残余窗口）；syncmatica 对每个假人发起一次必败握手
  （debug 日志噪声，无害）。

## §3 carpet:hello 协议

载体 = 单 CompoundTag（vanilla `writeNbt/readNbt`），消息区分靠字符串键（`"69"`=HI / `"420"`=hello /
`"Rules"` / `"clientCommand"`）。**S2C 唯一路径 = NMS `DiscardedPayload` 直发**（carpet 客户端不经
Fabric networking 注册本通道 → 从不发 `minecraft:register` 声明 → plugin messaging 结构性不可达）。

- 进服（PlayerJoinEvent 含假人）无条件发 `HI {"69": CARPET_VERSION}`；carpet 客户端应答 `hello` → 名册写入 +
  服务端镜像日志（匹配 info / 不匹配 warn——真实客户端版本带 `+v` 日期后缀时 warn 属上游 equals 语义同源产物）+
  `Rules` 全量帧（`{规则名: {Value, Manager:"carpet", Rule}}`，撞名追加 "2"）。
- 规则变更（命令路径）→ 单规则帧多播名册全体；`clientCommand` 请求-回执（`CarpetSnoopyCommandSource` 以玩家
  自身权限执行 + INFO 审计行）。
- vanilla/未装 carpet 客户端：HI 静默丢弃（未知通道 clientbound 上限 1MiB，帧 ~30 字节），零影响。
- 帧恒 KB 级，零分片需求。plugin.yml 零条目（通道 = 运行时注册）。

## §4 /log 日志框架 + tps/mobcaps HUD

上游 `logging/` 集群同构 + B 侧新增跨会话持久层：

- 注册面 = `tps`（无 option）/ `mobcaps`（`dynamic`/`overworld`/`nether`/`end`）两条 HUD logger。
- **HUD 投递**：tab 脚每 20 tick 一帧（Bukkit `setPlayerListFooter(String)`——**只写脚、零 header 触碰**，
  不走 carpet:hello——未握手客户端同样收到）。tps 行 = MSPT/TPS 公式逐字；mobcaps 行 = `SpawnState` 公开
  API + `MAGIC_NUMBER=289` 硬编码 + 先除后乘公式 + 热力色。
- **订阅命令**：`/log`（状态列表）/ `log subscribe <logger> [option]` / `log clear [logger]`——全部子命令仅玩家
  （console 统一 "For players only"）；门禁 = `commandLog` 规则（默认 true）。

### §4.1 双信号模型（carpet-logs.json 跨会话持久）

`{ "<uuid>": { "name": 名字快照, "subs": { "<logger>": "<option|\"\">" }, "off": ["<logger>"] } }`：
`subs` = 显式订阅；`off` = 退订墓碑（压制默认施用复活）；**默认订阅永不落盘**——每次进服按当前
`defaultLoggers` 规则值对「未订阅且无墓碑」者重评估。改名玩家经 UUID 复原；假人全链豁免（防反复
spawn/kill 灌死记录）。文件从不主动创建；坏 JSON 按空册起步。

## §5 命令族（纯计算类）

| 命令 | 上游类 | 说明 |
|---|---|---|
| `/distance` | `DistanceCalculator` | 两点三距离公式（Spherical/Cylindrical/Manhattan）；`from/to` 六分支嵌套树 |
| `/info block` | `BlockInfo` | 方块属性清单 + grep（纯文本层匹配）；非 op 双守卫 |
| `/perimeterinfo` | `PerimeterDiagnostics` | ±128 周界刷怪诊断 + `mob` 参数（可召唤实体 suggests 桥接） |
| `/draw` | `DrawCommand` | 七形状 setBlock 循环（sphere/ball/diamond/pyramid/cone/cylinder/cuboid）；fillUpdates 固定 true 语义 |
| `/spawn` | `SpawnReport` 切片 | `mobcaps [<dim>]` + `entities <type> [all]`；与 /log mobcaps 同数据源交叉一致 |

输出层 = `Messenger` 组件面（上游一致，含可点击 tp 坐标行）。`/perimeterinfo` `/draw` 参数类经
`CommandBuildContext.simple(registryAccess, VANILLA_SET)` 公开工厂懒加载单点。五条规则实名接入（§1）。
plugin.yml 零条目（Brigadier 注册自足）。

## §6 移植范围与非目标（速览）

- **不做**：Scarpet 整体（引擎/事件/图形/AppStore）、TNT/爆炸优化、红石重写、活塞可移动方块、刷怪表注入、
  漏斗计数器、性能剖析类规则、`/profile`（spark 生态替代）、`/counter`、`/draw plop`、`/spawn` tracking 五连、
  `viewDistance`/`simulationDistance`（Paper 原生自带）。
- **后置可选**：五条近似 chat logger（tnt/explosions/projectiles/fallingBlocks/pathfinding）、近似规则池
  （dev 线 scope §5.2 的 23 条——按需逐条评估）。

## §7 本线（1.21.11）适配记录与验收

### §7.1 NMS 适配清单（相对 dev/26.2 as-built 的回退面）

| 适配点 | 位置 | 形态 |
|---|---|---|
| `EntityTypes` 常量类回并 | `BlockInfo` / `PerimeterDiagnostics` | `EntityTypes.*` → `EntityType.*`（26.2 才拆常量类） |
| `EntitySpawnRequest` 回退 | `PerimeterInfoCommand` | 第三参直接 `EntitySpawnReason.COMMAND`（26.2 才引入该类） |
| ActionPack 三调用点 | `EntityPlayerActionPack` :347/:353/:482 | `entity.interact(player, hand, hitPos)` → **`entity.interactAt(player, relativeHitPos, hand)`**；`player.interactOn(entity, hand, hitPos)` → **`player.interactOn(entity, hand)`**（上游 1.21.11 树同位形态）；jump 分支删 `else if (!player.onClimbable()) player.tryToStartFallFlying()`（26.x 才加，本线上游无） |
| HI 常量 + 字面锁 | `CarpetReference` / `CarpetHelloFrameTest` | `"1.4.194"`（上游 `gradle.properties:16`）；javadoc 谱系改指 `fabric-carpet-1.21.11` |
| javadoc 锚点重指 | 全模块 32 处 | `docs/carpet-*.md` → 本文档对应节 |

实测适配总量 = **5 处编译修复**（其余 NMS/Paper API 面零漂移——`Identifier`、`CompoundTag` 使用面、Paper
Brigadier、`VanillaCommandWrapper` 判级链、`isClientAuthoritative`/`doTick`/`teleport(PositionMoveRotation)`
等 1.21.11 已具备；`getAverageTickTimeNanos`/`sendSystemMessage` 亦在本线 API 面内）。

### §7.2 掉落伤害 per-line 决策（关键差异，勿随 dev 线回抄）

**1.21.11 与 26.2 的 Paper 玩家物理路径根本不同**（与 26.1.2 同属"物理敞开"一族）：

- **26.2（dev 线）**：Paper 关闭了服务端玩家物理，其修复 = tick 内手动累积 fallDistance + 落地直调
  `causeFallDamage`（`tickFallDamageDrive` 重构件）。
- **1.21.11（本线，与 26.1.2 同判）**：服务端玩家物理**敞开**（假人重力/行走全物理真实运行），但
  `Entity.move` 的 `if (isLocalInstanceAuthoritative()) checkFallDamage(...)` 门控因 `Player.isClientAuthoritative()`
  恒 `true` 而关闭 → vanilla 掉落伤害链被吞。**本线修复 = `EntityPlayerMPFake` 覆写 `isClientAuthoritative()`
  返回 `false`**（假人无客户端，服务端实例即移动权威——语义为真），恢复全 vanilla 掉落语义（含干草块/蜘蛛网
  等特殊 fallOn 方块与护甲/药水公式）。上游 1.21.11 树的 5 行 `checkFallDamage → doCheckFallDamage` 桥接在
  Paper 的门控形态下不可达，故不采用。

实证（runServer 控制台）：survival 假人自 111.5 坠落 40 格 → **1 秒内死亡**（vanilla 公式 40-3=37 ≥ 20 ✓）；
creative 假人坠落零伤（vanilla 语义保真）。**环境注意**：`/player spawn` 控制台默认创造模式，测伤害需
`in survival`（gamemode 节点需 gamemasters 级，全链 `at <pos> facing <rot> in <dim> in <gamemode>`）；
世界难度 PEACEFUL 时一切 `scalesWithDifficulty` 伤害（含摔落）被 vanilla 直接拒绝——排错先查 `/difficulty`。

### §7.3 服务端侧验收记录（2026-10-19，本机 runServer）

- 起服：四模块注册（servux/jei/syncmatica/**carpet**）+ `Done (15.8s)`，无 ERROR/SEVERE。
- `/carpet list` = 18 规则全列（默认值标记）；`setDefault customMOTD p7check` → conf 落盘 → `removeDefault`
  复位往返干净。
- 假人：survival spawn（abilities 验证 = 生存正常位：mayfly/invulnerable/instabuild 全 0）→ `/list` 占位 →
  `/tp` 生效 → **40 格坠落致死**（§7.2）→ `kill` 完整退出链（"CP7 left the game"）。
- `/log` console "For players only"（命令注册 + 门禁放行 + 执行体拒绝链）。
- `/distance from 0 -60 0 to 100 -60 0` 三行输出（100.00/100.00/100.0）；`/spawn mobcaps` 八类别多行；
  `/draw sphere 0 -60 0 3 minecraft:glass` → "Filled 98 blocks"（r=3 空心球壳正确）。
- 构建：`./gradlew build` 全绿（**125 单测 0 失败**，含 carpet 7 类），`verifyVersionInjection` 通过
  （产物 `VeryMcProto-1.21.11-b6.jar` + `-reobf.jar`——本线保留 reobf 装配）。

### §7.4 用户侧终验清单（需 1.21.11 线 carpet 客户端 = carpet 1.4.194）

1. 进服无 chat 可见/断连级版本警告（客户端本地 `LOG.warn` 属上游 equals 语义同源产物，接受）。
2. 规则浏览器显示我方 18 条规则集（值随 `/carpet setDefault` 即时变更）。
3. `clientCommand` 请求-回执往返（客户端 `/call <cmd>`）。
4. HUD：tab 脚显示 tps/mobcaps 行（`/log subscribe` 后，未握手亦可显示）。
5. `/perimeterinfo <tab>` 出可召唤实体补全全集；可点击 tp 坐标组件点击跳转。

### §7.5 已知局限（本线）

§2 已知缺口全量适用（tick-freeze / 击退残余窗口 / TAB 类插件互覆写 / wander_chances 慢路径）。
另：本线 `/player spawn` 自控制台默认创造模式（各线同构）——伤害类判据须显式 `in survival`。

## §8 运维参考（本线命令/权限/配置全键）

- **命令**：
  - `/carpet`（权限 `carpet.command`=op + 动态 `carpetCommandPermissionLevel` 门禁）——`list` / `query <rule>` /
    `setDefault <rule> <value>`（即时生效 + 持久化 carpet.conf）/ `removeDefault <rule>`；`locked` 首行锁定时
    全部子命令拒绝。
  - `/player <name> …`（无 Bukkit 节点，门禁 = `commandPlayer` 规则）——spawn（含 at/facing/in 链，gamemode
    节点需 gamemasters 级，控制台默认创造）/ kill / shadow / stop / mount / dismount / sneak / sprint / look /
    turn / move / attack / use / jump / drop / hotbar / swapHands（动作 × once|continuous|interval <ticks>）。
  - `/log`（无节点，`commandLog` 规则默认 true，仅玩家）——裸列表 / `subscribe <logger> [option]`（logger =
    tps|mobcaps，option = dynamic|overworld|nether|end）/ `clear [logger]`。
  - `/distance` `/info block <pos> [grep]` `/perimeterinfo` `/draw 七形状` `/spawn mobcaps|entities`
    （无节点，各自 commandXxx 规则；commandDraw 默认 ops，其余默认 true）。
- **配置**：`plugins/VeryMcProto/carpet.conf`（纯文本逐行 key value；仅 setDefault/removeDefault 写入，从不
  主动创建；首行 `locked` = 锁定）+ `carpet-logs.json`（§4.1 双信号模型，从不主动创建）。
- **排错**：`[Carpet]` 前缀日志（握手 `joined with a matching carpet client` / `another carpet version: <v>`
  （`+v` 后缀 warn 属正常）/ `clientCommand from` 审计 / `Unknown carpet client data`）；假人卡死先
  `/player <name> kill`；掉落/环境伤害不生效先查游戏模式（默认创造免伤）与 `/difficulty`；HUD 无显示先
  `/log` 裸命令看订阅态。

---

> **相关**：[00-INDEX.md](00-INDEX.md)（总索引）· [jei.md](jei.md)（JEI 域）· dev 线 `carpet-architecture.md` /
> `carpet-protocol.md` / `carpet-porting-scope.md` / `carpet-phases.md`（as-built 原始论证与偏差清单全量——
> dev 分支树）
