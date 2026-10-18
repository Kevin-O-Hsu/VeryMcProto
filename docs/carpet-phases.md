# Carpet → Paper 移植分阶段推进规划（phases）

> **状态**：推进规划（2026-10-17 制定；**P1-P4 已实现**，状态登记见 §11）。基于 [carpet-porting-scope.md](carpet-porting-scope.md)（2026-10 预研结论）制定；本文回答「按什么顺序做、每阶段做到什么程度算完成」——**范围可行性判定唯一权威 = [carpet-porting-scope.md](carpet-porting-scope.md)（下称 scope），Mixin 处置与降级矩阵唯一权威 = [architecture.md](architecture.md) §5**，本文只引用不复制。
>
> 行号口径：本文对 `OriginImpl/fabric-carpet-*` 源码的行号锚为 **26.2 树**值（与 scope 相同，其「实现落地时以真实源码为准复核」兜底适用）；对 scope 的引用以**节号**为主（避免 scope 后续修订引起行号漂移）。

---

## §0 定位与使用说明

- **与 scope 的分工**：scope = 逐条判定「能不能、值不值」（预研快照，判定不随本文变化）；本文 = 「先做什么后做什么、完成判据是什么、当前进展到哪」（**活体路线图**——§11 状态登记表随实现推进持续更新）。
- **阶段顺序的来源**：Phase 1-3（基础 → 假人 → 协议）为**用户决策**，显式优先于 scope §8 末行的工程量直觉（「MobCap/tps 部分最快见效」）。日志/命令层排后并非否定其价值，是顺序约束的结果；后续维护者勿按「快赢优先」回退此顺序。
- **与文档约定「历史 = git」的关系**（[index.md](index.md) 文档约定）：该条排除的是迁移期过程流水；本文是持续维护的推进规划，不属其列。各 Phase 实现期的过程记录仍走 git commit，不占文档位；每 Phase 完成时只更新本文 §11 状态登记表，as-built 细节届时沉淀到新建的专题文档（如 `carpet-architecture.md` / `carpet-protocol.md`，按需创建并登记 index.md）。
- **状态标记**：⬜ 未开始 / 🔧 进行中 / ✅ 已完成（登记完成线与 commit 锚）。

---

## §1 全局原则

1. **分支策略**：一切实现从 `dev`（26.2 线，对照 `OriginImpl/fabric-carpet-26.2/` = 上游 tag `v26.2`）起步；验证后按 §9 铺 `ver/26.1.2` / `ver/1.21.11`（scope §8 末行建议）。
2. **版本锚**（记录性）：2026-10-17 两次读取 Mojang 版本清单（https://launchermeta.mojang.com/mc/game/version_manifest_v2.json）一致得 latest.release = 26.3、latest.snapshot = 26.4-snapshot-4（仓内旁证：`OriginImpl/fabric-carpet-master/gradle.properties:10` 同值；根 AGENTS.md 在档 snapshot-2 为 snapshot 轨滚动旧值）。按「对齐 latest-1」策略当前目标线 = 26.2 = `dev` 现状（`gradle.properties` mcVersion=26.2）。**规范来源永远是开发前的 manifest 实读**（根 AGENTS.md 工作约定），本文不承载版本时效。
3. **降级与红线**：Mixin 处置唯一权威 = architecture.md §5；红线清单唯一权威 = scope §8 红线行。本文各 Phase 只引用，不复制处置详情。
4. **规则计数基准**：scope §5 判定基线 = **89 个唯一规则名**（83 活跃全覆盖 + 6 上游注释态死名†，基准注见 scope §5 表头）；本文 P6 池 = scope §5.2 补判后 **23 条**。
5. **装配与语言**：`mod/carpet/` + `ModModule` 注册（根 AGENTS.md「新增一个协议 mod」节）；对外可见面（plugin.yml / 日志 / 命令回执）一律英文，文档中文。
6. **并行性**：P1 完成后 P2/P3/P4/P5 相互无硬依赖，可并行推进；本文序号 = 建议优先序，非强制串行。**mobcaps 采集点归属 P4**（`SpawnState` 公开 API + MAGIC_NUMBER 硬编码 289，建 `mod/carpet/` 共享采集工具）；P5 的 `/spawn mobcaps` 只消费该采集点——若 P5 先行，内联最小实现、P4 落地时收敛（或该子命令挂起至 P4）。

---

## §2 Phase 依赖图

| Phase | 硬依赖 | 说明 |
|---|---|---|
| P1 基础 | — | 模块地基（骨架 / 规则框架 / 命令壳 / 权限） |
| P2 假人 | P1 | 复用骨架、规则框架与权限 |
| P3 协议 | P1 | Rules 帧数据源 = P1 规则框架；与 P2 无依赖 |
| P4 日志 | P1 | HUD 投递不走 carpet:hello（scope §2 要点行），与 P3 无依赖 |
| P5 命令族 | P1 | `/spawn mobcaps` 消费 P4 采集点（软依赖，§1.6 回退条款） |
| P6 近似规则池 | P1 | 逐条按需，随时可捞 |
| P7 三线铺开 | P1/P2/P4/P5 完成态 | **P3 可选缺席**——协议为可选件（scope §2 要点行 / §8 协议行），缺席不阻塞铺开 |

---

## §3 Phase 1 基础——模块地基

- **范围锚**：scope §8 命令行（/carpet 壳）+ 规则行子集；scope §5.1 地基 8 条
- **交付物**：
  1. `mod/carpet/` 装配：`CarpetModule`（ModModule 实现）+ 主类注册 + try-catch 降级（仓库装配范式——模块失败只记日志，不阻断其余三 mod）
  2. `CarpetReference` 常量类（通道名 `carpet:hello`（`carpet/network/CarpetClient.java:48`）、版本串常量等）
  3. 规则框架：注解规则表 + SettingsManager 同构（`carpet.conf` 持久化，对照上游 `api/settings/SettingsManager.java`）+ `/carpet list|setDefault|query|removeDefault` 四子命令（scope §3 /carpet 行）
  4. `plugin.yml`：命令注册 + 权限节点（命名跟随既有先例）
  5. **地基规则 8 条实名接入**：
     - `carpetCommandPermissionLevel` / `language` / `superSecretSetting` —— /carpet 壳配套（scope §5.1）
     - `explosionNoBlockDamage` —— `EntityExplodeEvent.blockList().clear()` 事件等价（scope §5.1）
     - `customMOTD` —— `ServerListPingEvent` 原生等价（scope §5.1）
     - `cleanLogs` —— 日志框架 filter 等价（scope §5.1）
     - `commandTick` —— **上游三线零使用死规则，注册仅为规则浏览器面完整、零行为（与上游同构）**（scope §3 /tick 行勘误后口径）
     - `perfPermissionLevel` —— Paper 原生命令权限节点等价（`PerfCommand_permissionMixin` 在册，scope §5.1）。**as-built 勘误（2026-10-17）**：实机与映射源实证——值 4（默认）= Paper 天然同构（vanilla /perf requires LEVEL_OWNERS 原样，零动作等价）；值 2 = 结构性不可达（上游靠 Mixin redirect 换 requires，Paper 无替换面；玩家聊天命令链绕过 bukkit commandMap，`minecraft.command.perf` 节点授权对玩家无效）——B 侧为规则面注册 + 只读登记，详见 [carpet-architecture.md](carpet-architecture.md)
- **明确不进本 Phase**（scope §5.1 其余 12 条去向）：假人 3 条（`commandPlayer` / `allowSpawningOfflinePlayers` / `allowListingFakePlayers`）随 P2；log 2 条（`commandLog` / `defaultLoggers`）随 P4；commandXxx 5 条（`commandDistance` / `commandInfo` / `commandPerimeterInfo` / `commandSpawn` / `commandDraw`）随 P5；`viewDistance` / `simulationDistance` 2 条**永不移植**（Paper 原生 per-world 配置自带，scope §5.1）
- **完成判据**：① `/carpet` 四子命令实机可用 ② `setDefault` → `carpet.conf` 落盘 → 重启 → 读回往返一致 ③ 规则表 `list` 输出与注册表一致（单测覆盖）④ 三既有 mod 回归无影响（起服无错，servux / jei / syncmatica 通道抽测正常）
- **验证方式**：单测（规则解析 / 持久化往返）+ `runServer` 实机
- **降级/缺口**：无（全部 ✅ 判定面）

---

## §4 Phase 2 假人——/player 全族

- **范围锚**：scope §6 全部；规则 = scope §5.1 假人家族 3 条
- **交付物**：
  1. patches/ 三件套移植（上游纯子类零 Mixin，scope §6.1）：`EntityPlayerMPFake`（286 行，26.2 实测；26.1 = 291，差 5 行即 `checkFallDamage` 删除）/ `FakeClientConnection`（47 行，EmbeddedChannel + send no-op）/ `NetHandlerPlayServerFake`（43 行，只重写 disconnect / teleport）
  2. `EntityPlayerActionPack`（629 行，纯逻辑可单测）+ 侧表 `Map<UUID, ActionPack>` + 框架 BukkitRunnable tick 调度（替代 `ServerPlayer_actionPackMixin`，scope §6.2 表行 1）
  3. `placeNewPlayer` 后反射替换 `player.connection` 字段（替代 `PlayerList_fakePlayersMixin` ①，`framework/reflect/Reflect` 现成，scope §6.2 表行 2）
  4. respawn 手工重组：`newFake.restoreFrom(old, keepEverything)` + 重走 `placeNewPlayer` + remove 旧实体（替代 ②——vanilla respawn 本体即这三步，重写而非钩入，scope §6.2 表行 3）
  5. `hurtMarked` 每 tick 清近似（scope §6.2 表行 4，可选）；`TickRateManager` tick-freeze 假人不冻结——**接受差异，唯一功能缺口**（scope §6.2 表行 5）
  6. `/player` 全子命令（scope §3 /player 行全表：spawn / kill / shadow / stop / mount / dismount / sneak / sprint / look / turn / move / attack / use / jump × once|continuous|interval / drop / hotbar）+ 假人 3 规则接入
- **依赖**：P1
- **完成判据**：① 全子命令逐条实测通过表 ② 假人占 tab 栏、计入睡比例跳夜、playerdata 持久（重启还原）、可被 `/tp` `/ban` 原版命令识别 ③ 已知缺口显式登记（tick-freeze 差异 + 击退可选近似）
- **验证方式**：实机（多假人并发 + 重启持久化 + vanilla 命令交互）+ ActionPack 单测
- **风险集中面**：Paper `placeNewPlayer` 补丁栈交互（PlayerJoinEvent 触发 / CraftPlayer 创建）与 respawn 手工重组状态一致性（scope §6.3）——调参级，非阻断
- **三线注记**：patches 三件套近零漂移（1.21.11↔26.1 逐字节相同、26.1→26.2 仅删 5 行，scope §6.3）——本子系统三线铺开成本 ≈ 单线

---

## §5 Phase 3 协议——carpet:hello

- **范围锚**：scope §2 全部
- **交付物**：
  1. 通道注册：`carpet:hello`（ChannelManager + CarpetPayload 单 CompoundTag 载体，与 servux / jei 通道同构，scope §2 首段）
  2. 握手：S2C `HI`（进服自动）+ C2S `hello` 应答（scope §2 表行 1/2）。**版本串常量决策：HI 帧版本串 = 该线上游 mod_version 逐字值**（dev/26.2 线 = `26.2`、26.1 树 = `26.1`、1.21.11 树 = `1.4.194`，P7 铺开 per-line 取值）；客户端 `ClientNetworkHandler.java:95` `equals` 严格比较，不匹配仅 warn 不断连——**禁止发插件版本号**（`26.2-b3` 之类必触发 warn），纪律同 servux MOD_STRING 门禁
  3. S2C `Rules` 全量同步（scope §2 表行 3——内容 = 我方可移植规则集，数据源 = P1 规则框架）
  4. C2S `clientCommand` + S2C 回执（scope §2 表行 4/5）
  5. 自定义 NBT 广播机制面（scope §2 表行 6——上游唯一在用者 scShapes 随 Scarpet，内容不做）
- **依赖**：P1（Rules 数据源）；与 P2/P4 无依赖
- **完成判据**：① carpet Fabric 客户端进服无版本不匹配警告 ② 客户端规则浏览器显示我方规则集 ③ clientCommand 请求-回执往返 ④ 未握手（vanilla / 未装 carpet）玩家零影响
  - **as-built 勘误（2026-10-18，判据①口径）**：真实发布的 carpet 客户端版本串带 `+v<构建日期>` 后缀（上游 build.gradle:10 无条件拼接）→ 发逐字 mod_version `"26.2"` 时此类客户端必打一条**本地 LOG.warn**（非聊天、不断连，equals 严格比较的确定性产物——与两个不同日期构建的上游 carpet 服互连同源）。判据①落地口径 = **无 chat 可见/断连级警告**；服务端镜像日志照抄上游（info=匹配/warn=不匹配）。详见 [carpet-protocol.md](carpet-protocol.md) §2。
- **验证方式**：carpet 客户端实机 + 对照 `OriginImpl/fabric-carpet-26.2/` 客户端源码核对 wire
- **注**：协议本身为可选件（HUD logger 不依赖握手，scope §2 要点行 / §8 协议行）——P3 置前是用户顺序约束的结果；后续若决策跳过 P3，不阻塞 P4+ 与 P7

---

## §6 Phase 4 日志——/log 框架 + tps/mobcaps

- **范围锚**：scope §4 + §8 日志行；规则 = scope §5.1 的 `commandLog` / `defaultLoggers`
- **交付物**：
  1. `/log` 框架：LoggerRegistry 同构（对照上游 `carpet/logging/LoggerRegistry.java:40-48`）+ 订阅状态 + `/log subscribe <logger> [option] | clear` + `defaultLoggers` 默认订阅
  2. HUD 投递：tab 头脚（Bukkit `setPlayerListHeaderFooter`，每 20 tick——上游 `HUDController.java:109-113` 走原版 TabListPacket，scope §2 表行 7 ✅）——**不走 carpet:hello**
  3. `tps` logger：NMS `MinecraftServer.getAverageTickTimeNanos()`（仓库 `DataLoggerTPS.java:53` 同款 NMS 直连）
  4. `mobcaps` logger：`SpawnState` 公开 API + **MAGIC_NUMBER 硬编码 289**（对齐仓库 as-built `DataLoggerMobCaps.java:29-30` 先例——注释标来源防漂移，勿引入上游没有的反射）
  5. `commandLog` / `defaultLoggers` 规则接入（实名）
- **后置可选池**（不阻塞 Phase 完成）：`tnt` / `explosions` / `projectiles` / `fallingBlocks` / `pathfinding` 五条 ⚠️ 近似 logger（scope §4 各行——每条独立取舍 + 近似差距声明）
- **不做**：`counter` ⛔（随 hopperCounters）、`packets` 🔧（PacketEvents 独立工程非移植）（scope §4 末两行）
- **依赖**：P1；与 P3 无依赖
- **完成判据**：① carpet 客户端 HUD 显示 tps/mobcaps（零协议路径实测）② `/log` 订阅状态跨会话持久 ③ tab 头脚占用与既有功能无冲突（仓内 `setPlayerListHeaderFooter` 现零占用，实测确认）
- **验证方式**：carpet 客户端实机

---

## §7 Phase 5 命令族——纯计算类

- **范围锚**：scope §3 ✅ 项 + §8 命令行；规则 = scope §5.1 commandXxx 5 条
- **交付物**：`/distance`（Tracer 纯算法寻路成本）、`/info`（方块/实体 NBT dump）、`/perimeterinfo`（PerimeterDiagnostics 周界诊断）、`/draw` 基础形状（setBlock 循环）、`/spawn mobcaps,entities` + 五条规则实名接入（`commandDistance` / `commandInfo` / `commandPerimeterInfo` / `commandSpawn` / `commandDraw`）
- **后置可选**：`/spawn list` ⚠️（依赖 tracking 统计）、`/mobai` ⚠️（反射采样保真低）（scope §3 各行）
- **不做**：`/spawn` tracking 五连 ⛔、`/profile` ⛔（Paper 生态 spark 替代）、`/counter` ⛔、`/draw` plop ⛔、`/script` 🔗、`/test` N/A、`/tick`（已随 P1 权限面，scope §3 /tick 行）
- **依赖**：P1；`/spawn mobcaps` 消费 P4 采集点（软依赖 + §1.6 回退条款）
- **完成判据**：逐命令实测——`/info` 输出语义对照原版、`/distance` 两点寻路成本、`/perimeterinfo` 周界报告、`/draw` 形状落块正确、`/spawn mobcaps` 数值与 `/log mobcaps` **交叉一致**
- **验证方式**：实机 + 纯算法单测（Tracer / PerimeterDiagnostics）

---

## §8 Phase 6 近似规则自选池——scope §5.2 ⚠️ 23 条按需

- **范围锚**：scope §5.2 补判后 23 条实名（22 条近似移植 + 本次补判的 `pingPlayerListLimit`）
- **定位**：可选扩展池，**不设全量交付目标**；捞取门槛 = ① 真实需求驱动 ② 独立验收标准 ③ 近似差距显式声明（对照 scope §5.2 近似路径列）。`mergeTNT`（scope §5.2「为省性能的优化用费性能的方式做」本末倒置）默认不捞。
- **依赖**：P1 规则框架
- **完成判据**：条目级——每实现一条在 §11 状态表登记（规则名 + 近似差距 + 验证 commit）

---

## §9 Phase 7 三线铺开——ver/26.1.2 + ver/1.21.11

- **范围锚**：scope §0.2（上游版本规划）+ §6.3（假人三线稳定性）+ §8 末行（dev 起步建议）
- **前提**：目标子系统在 `dev` 线验收完成
- **分子系统等价性**：
  - **假人**：近零漂移（scope §6.3 实证：patches 三件套 1.21.11↔26.1 逐字节相同、26.1→26.2 仅删 5 行）——直接铺
  - **/tick**：三线同构（四棵对照树均无 TickSpeed 类——`CarpetServer.java:95` 同位死注释；`commandTick` 四树零使用死规则，scope §3 /tick 行勘误后口径）——无 /tick 跨线差异
  - **协议 / 日志 / 命令 / 规则层**：跨线漂移**未逐线评估**——铺开前须对照 `OriginImpl/fabric-carpet-<线>/` 逐线复核（尤其 HI 版本串 per-line：26.1 树 = `26.1`、1.21.11 树 = `1.4.194`，见 §5）
- **完成判据**：铺开线实机验收 = dev 线同判据；ver 线 `buildNumber` 独立递增（分支模型，根 AGENTS.md）
- **依赖**：P1/P2/P4/P5 完成态（P6 可选随行；P3 可缺席）

---

## §10 非目标（终判清单）

- **⛔ 不可移植 37 条**：清单与理由唯一权威 = scope §5.3（含本次补判的 `quasiConnectivity` / `thickFungusGrowth` / `lightningKillsDropsFix`，及 6 个上游注释态死名†——上游已注释即天然不可移植）
- **Scarpet 整体**：引擎 / 事件 / chunk 控制 / 图形渲染 / AppStore（scope §7）+ scope §5.5 全部 6 条（含本次补判的 `commandScriptACE`）+ `/script`
- **➖ 纯客户端 4 条**：scope §5.4
- **Paper 原生自带 / 生态替代**：`viewDistance` / `simulationDistance`（scope §5.1）、26.x 线 /tick 权限门（scope §3 /tick 行）、`/profile` → spark 生态（scope §3 /profile 行）
- **防重开声明**：以上为终判（判定基线 = 83 活跃规则全覆盖 + 6 死名†，基准注见 scope §5 表头）。除非 Paper 提供新事件面或上游协议变化，不重开评估；重开时以 scope 为判定入口、本文 §11 登记状态。

---

## §11 状态登记表

| Phase | 名称 | 状态 | 完成线 | commit |
|---|---|---|---|---|
| P1 | 基础（模块地基） | ✅ 已完成 | 2026-10-17：四判据全过——①四子命令实机可用（run1：list 8 规则/query/setDefault×3/removeDefault）②conf 往返（run2 重启 `Loaded 2 settings` + modified 标记）③list=注册表（单测锁）④四模块起服无错零回归；单测 144/0/0（新增 15）+ cleanLogs 双极性探针 | 见交付 commit（feat: Carpet Phase 1） |
| P2 | 假人（/player 全族） | ✅ 已完成 | 2026-10-18：判据全过——①全子命令逐条实测（runServer 控制台 18 轮：spawn 含 at 坐标/look/turn/move/sneak/sprint/attack/use/jump × 三模式/drop 族/hotbar/swapHands/mount/dismount/stop/kill/shadow 门禁回执）②占 tab 栏（list）+ **入睡跳夜**（peaceful 夜 use 对床 → 跳夜分支堆栈实证）+ playerdata 持久（players/data 落盘读回）+ /tp //ban 原版命令识别（ban 后 cantSpawn 拒）③缺口显式登记（tick-freeze 唯一功能缺口 + 击退 PlayerVelocityEvent 门控 ≤1tick 残余窗口）；速度保真标定 5.6 m/s（超平坦 sprint 8s 位移 44.84 格）；单测 148/0（新增 ActionPackTest 4 + 规则表扩 11 锁）；三裁决落地（kick 终端拦截 / respawn 实例复用改锚 / brigadier 载体） | 见交付 commit（feat: Carpet Phase 2） |
| P3 | 协议（carpet:hello） | ✅ 已完成 | 2026-10-18：判据②③④待 carpet 客户端实机终验（用户侧）；判据①已按 javap/上游 build.gradle 实证改写口径（真实客户端版本串带 `+v` 日期后缀 → 本地 LOG.warn 属上游 equals 语义同源产物，接受——无 chat 可见/断连级警告）；S2C 全 NMS DiscardedPayload 直发（carpet 客户端无 minecraft:register 声明 → plugin messaging 结构性不可达，静态全链实证）；单测 153/0（新增 CarpetHelloFrameTest 5：帧形状/撞名"2"/回执缺省键/版本串字面守卫）；runServer 冒烟（四模块+假人 join+/carpet 回归）通过；as-built = [carpet-protocol.md](carpet-protocol.md) | 见交付 commit（feat: Carpet Phase 3） |
| P4 | 日志（/log + tps/mobcaps） | ✅ 已完成 | 2026-10-19：判据①服务端侧全链就绪（HUD 行公式逐字 + footer 只写路径 + 单测/冒烟）——carpet 客户端 HUD 终验留用户侧（phases §6 验证方式即"客户端实机"）；判据②状态机全矩阵单测（墓碑双信号模型 + join 谓词 + carpet-logs.json 往返）+ conf/13 规则往返；判据③仓内零占用 grep + 只写 footer（零 header 触碰）；runServer 冒烟（console 三入口拒绝面/13 规则/conf 往返/假人豁免/disable 三连清）+ 单测 171/0/0（新增 CarpetLoggerTest 18）；as-built = [carpet-architecture.md](carpet-architecture.md) §16-§18 | 见交付 commit（feat: Carpet Phase 4） |
| P5 | 命令族（纯计算类） | ⬜ 未开始 | — | — |
| P6 | 近似规则自选池 | ⬜ 未开始（可选） | — | — |
| P7 | 三线铺开 | ⬜ 未开始 | — | — |
