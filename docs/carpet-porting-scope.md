# Carpet → Paper 插件移植范围全量清单（预研结论）

> **状态**：预研评估（2026-10）。**落地进展：Phase 1「模块地基」已于 2026-10-17 实现**（规则框架 + /carpet 壳 + 8 条地基规则，as-built 与偏差清单见 [carpet-architecture.md](carpet-architecture.md)，推进状态见 [carpet-phases.md](carpet-phases.md) §11）——本文判定结论不变（预研快照）。评估对象 = gnembon/fabric-carpet（Fabric 端行为注入型 mod），目的是判定其各子系统以纯 Paper 插件形态（无 Mixin / 无服务端 fork，本仓库铁律）复刻的可行性，并给出建议落地范围。
>
> **对照源**（`OriginImpl/`，gitignore，不入库）：
>
> | 本仓库线 | 上游对照 | 目录 |
> |---|---|---|
> | `ver/1.21.11` | 分支 `1.21.11`（mod_version 1.4.194） | `OriginImpl/fabric-carpet-1.21.11/` |
> | `ver/26.1.2` | 分支 `26.1`（mod_version 26.1） | `OriginImpl/fabric-carpet-26.1/` |
> | `dev`（26.2） | tag `v26.2`（无长命分支，tag 打在 master 历史上） | `OriginImpl/fabric-carpet-26.2/`（master 仓库 worktree） |
> | 观望 | master（26.4-snapshot 线） | `OriginImpl/fabric-carpet-master/` |
>
> 本文逐条结论均给出对照源相对路径与关键行号；实现落地时以真实源码为准复核。

---

## §0 背景与上游画像

### §0.1 与已移植三 mod 的本质差异

Servux / JEI / Syncmatica 是**协议投递型** mod——客户端期待一套网络协议，服务端复刻协议 + 数据采集即可等价。Carpet 是**行为注入型** mod——核心价值 = 171 个服务端 Mixin 对 vanilla 逻辑的改写，网络协议只占极小比重。**对 Carpet 的移植只能是"挑选与注入无关或注入极轻的子系统"**，不存在"完整复制"。

### §0.2 上游版本规划（实证）

- 每条受支持的 release 一条长命分支（1.14.4 → 26.1 共 15 条），版本跳跃式支持（1.21.10 → 1.21.11 → 26.1，中间小版本跳过）；旧分支永久保留。
- master 滚动跟随快照（评估时 `minecraft_version=26.4-snapshot-4`，`mod_version=26.4-beta-3`，`depends: minecraft>26.3, java>=25`）。
- **26.2 / 26.3 只有 tag、没有长命分支**（`v26.2` = master 提交 `dbedd4c`，发布后就地打 tag 继续前进）。
- 版本号 = MC 版本即 mod 版本（26.1 线叫 `26.1`；1.21.x 线沿用语义版本 `1.4.194`）。

### §0.3 规模盘点（26.1 分支实测；26.2 = 422 文件 / 187 mixin 同量级，计数按 carpet.mixins.json 注册面）

| 子系统 | 规模 | 性质 |
|---|---|---|
| `mixins/` | **186 个 Mixin**（171 服务端 + 15 客户端，按 carpet.mixins.json 注册面计；mixins 数组为双端 common，"服务端"系移植语义表述；每树另有 3 个未注册死文件不计，26.2 = ChunkAccess_warningsMixin / Ingredient_scarpetMixin / ShulkerBoxAccessMixin） | 行为注入，Paper 不可移植层 |
| `script/`（Scarpet） | 123 文件 | 纯 Java 引擎为主，事件面靠 ~34 个 `*scarpet*` mixin |
| `fakes/` | 42 个 Accessor/Invoker 接口 | Paper 侧全部可用反射替代 |
| 规则 `CarpetSettings.java` | 83 条活跃 `@Rule` + 3 个注释态死块（8 处提及），1022 行 | 规则的行为 = 对应 Mixin |
| `commands/` | 11 个命令文件（14 命令族） | 混合 |
| `patches/`（假人三件套） | 3 文件共 381 行 | **纯子类，无 Mixin 依赖** |
| `network/`（carpet:hello） | 3 文件约 515 行 | 单 CompoundTag 自定义 payload 协议 |
| `carpet.accesswidener` | 12 条（8 类 + 4 成员） | 反射可替代 |
| `logging/` | 9 个 logger + 5 个 logHelper | 数据采集混入 vanilla 内部 |

对照组：本项目已移植的 Servux 26 个 Mixin、Syncmatica 5 个。

---

## §1 判定标准与图例

| 图例 | 含义 |
|---|---|
| ✅ | 可完整移植：NMS 直连 / Bukkit-Paper 事件等价 / Paper 原生自带 |
| ⚠️ | 可近似移植：有保真度降级或实现成本，需逐条取舍 |
| ⛔ | 不可移植：注入即产品，插件形态物理不存在等价物 |
| ➖ | 纯客户端功能：装 carpet 客户端的玩家自带，服务端无需移植 |
| 🔗 | 与 Scarpet 绑定，随其整体判定 |

可用手段（与 architecture.md §5 处置矩阵同源）：Bukkit/Paper 事件、Paper API、NMS 直连 + Mojang 名反射（paperweight 产物）、PacketEvents（softdepend，隔离加载）。

---

## §2 协议层（`carpet:hello`）完整消息面

载体 = `CarpetPayload`：**单个 CompoundTag** 经原版 `CustomPacketPayload` 机制收发（`carpet/network/CarpetClient.java:18-34`）——与本项目 servux/jei 通道同构，`ChannelManager` 注册 + `DiscardedPayload` 直发即可复刻。通道名 `carpet:hello`（`CarpetClient.java:48`）。

| # | 方向 | 消息 key | 载荷 | 用途 | 判定 |
|---|---|---|---|---|---|
| 1 | S2C | `HI` | string（服务端 carpet 版本） | 进服握手声明，`onPlayerJoin` 自动发（`carpet/network/ServerNetworkHandler.java:38-50`） | ✅ |
| 2 | C2S | `hello` | string（客户端 carpet 版本） | 客户端应答，触发 #3 全量下发（`ServerNetworkHandler.java:52-67`） | ✅ |
| 3 | S2C | `Rules` | `{规则名: {Value, Manager, Rule}}` | 全量规则同步（carpet 客户端规则浏览器数据源，`ServerNetworkHandler.java:219-239`） | ⚠️ 机制可复刻；内容 = 我方可移植规则/自定义规则集 |
| 4 | C2S | `clientCommand` | `{command, id}` | 客户端 app 请求服务端执行命令并采集输出（`ServerNetworkHandler.java:80-115`） | ✅ |
| 5 | S2C | `clientCommand` 回执 | `{id, error?, return, output?}` | #4 应答 | ✅ |
| 6 | S2C | 自定义 NBT（`broadcastCustomCommand`/`sendCustomCommand`） | 任意 Tag | 扩展数据通道；上游唯一在用者 = `scShapes`（scarpet 图形渲染，`carpet/script/external/Vanilla.java:139`） | ✅ 机制 / 🔗 内容 |
| 7 | 旁路 | —（不走本通道） | — | **HUD logger 走原版 `ClientboundTabListPacket`**（tab 头/脚，每 20 tick 一帧，`carpet/logging/HUDController.java:109-113`）；carpet 客户端 `Gui_tablistMixin`/`PlayerTabOverlayMixin` 负责画成 HUD | ✅ Bukkit `setPlayerListHeaderFooter` 原生等价 |
| 8 | 旁路 | — | — | chat 类 logger 走原版系统聊天；pathfinding 渲染走原版粒子包 | ✅ 投递 / ⚠️ 采集见 §4 |

要点：**服务端发送 HUD logger 数据不检查握手**（`HUDController` 无 gate）——不实现 carpet:hello 也能让 carpet 客户端显示 HUD；实现握手的价值 = 消除客户端版本警告 + 解锁规则同步壳。客户端 codec 注册（`CustomPacketPayload_networkStuffMixin`）是 carpet 客户端自己的事，无需移植。

---

## §3 命令层逐条（14 族）

| 命令 | 子命令面 | 判定 | 说明 |
|---|---|---|---|
| `/player` | spawn（in/at/facing/gamemode）、kill、shadow、stop、mount [anything]、dismount、sneak/unsneak、sprint/unsprint、look north/south/east/west/up/down/at、turn left/right/back、move forward/backward/left/right（组合）、attack/use/jump × once/continuous/interval、drop all/mainhand/offhand/\<slot>（+ dropStack 变体）、hotbar \<slot> | ✅ 全族 | §6 深潜；上游 ~1400 行 + Paper 适配层 |
| `/log` | subscribe \<logger> [option]、clear | ✅ 框架 | 订阅状态纯服务端；logger 逐条见 §4 |
| `/spawn` | `mobcaps`、`entities <type> [all]` ✅；`list` ⚠️（依赖 tracking 统计）；`tracking start/stop`、`test`、`mocking`、`rates reset` ⛔ | ⚠️ 部分 | tracking 五连 = `NaturalSpawnerMixin` 5+ 个 @Redirect 深钩刷怪循环（`carpet/mixins/NaturalSpawnerMixin.java:49-271`），无事件面 |
| `/distance` | from/to | ✅ | 两点三距离公式（Spherical/Cylindrical/Manhattan，`carpet/utils/DistanceCalculator` 纯算法——**非寻路、零 Tracer 消费**，原文"A* 寻路/Tracer"系笔误，2026-10 按上游源码勘误） |
| `/info` | block、grep | ✅ | block-only 属性清单（`carpet/utils/BlockInfo`：registry key/属性/map colour/sound/光照/hardness/红石/wander_chances）——**无实体分支、非 NBT dump**，原文按上游源码勘误 |
| `/perimeterinfo` | — | ✅ | 周界刷怪诊断，纯计算大活（`carpet/utils/PerimeterDiagnostics`） |
| `/draw` | sphere/ball/cuboid/cylinder/cone/diamond/pyramid/replace ✅（setBlock 循环）；plop ⛔ | ⚠️ 部分 | plop = `WorldGenRegion_scarpetPlopMixin` 等 4 个世界生成钩 |
| `/profile` | health、entities | ⛔ | `CarpetProfiler` 采样本体即 mixin 注入；替代 = Paper 生态 spark，非移植 |
| `/counter` | realtime、reset | ⛔ | 随 hopperCounters（§5 ⛔ 表） |
| `/mobai`（/track） | track、clear | ⚠️ | AI 目标在 Brain/Goal 内部，反射逐 tick 采样理论可行、保真低 |
| `/script` | 28 个子命令 | 🔗 | 随 Scarpet（§7） |
| `/test` | testcarpet、dump | N/A | 上游自测框架 |
| `/carpet` | list/setDefault/query/removeDefault | ✅ 壳 | SettingsManager（.conf 持久化 + 注解规则表）可整体移植，管理我方可移植规则 |
| `/tick` | （原版命令） | ✅ 零成本 | 三线同构：四棵对照树均无 TickSpeed 类（CarpetServer.java:95 同位死注释）、`commandTick` 四树零使用（死规则）→ /tick 即原版命令，Paper 天然同构；commandTick 注册仅保规则面完整、零行为 |

---

## §4 /log 九个 logger 逐条

注册面：`carpet/logging/LoggerRegistry.java:40-48`。

| logger | 选项 | 数据采集 | 投递 | 判定 |
|---|---|---|---|---|
| `tps` | — | `server.getAverageTickTimeNanos()` 公开 API（`HUDController.java:118`） | HUD | ✅ 完整 |
| `mobcaps` | dynamic/overworld/nether/end | `world.getChunkSource().getLastSpawnState().getMobCategoryCounts()` 公开 API + `MAGIC_NUMBER`（本仓库 Servux 已有同款反射点；`carpet/utils/SpawnReporter.java:98-118`） | HUD | ✅ 完整（chunk 计数分母用 SpawnState 自带值/反射，小活） |
| `tnt` | brief/full | TNT 轨迹模拟，上游混入 PrimedTnt/Explosion（`TNTLogHelper`/`OptimizedExplosion`） | 聊天 | ⚠️ 调度器逐 tick 扫 PrimedTnt 实体 + 自写轨迹模拟可近似 |
| `explosions` | brief/full | Explosion 深钩 | 聊天 | ⚠️ `EntityExplodeEvent` 可近似（粒度降级） |
| `projectiles` | brief/full | `ThrowableProjectileMixin` | 聊天 | ⚠️ 实体扫描近似 |
| `fallingBlocks` | brief/full | FallingBlockEntity 钩 | 聊天 | ⚠️ 实体扫描近似 |
| `pathfinding` | 2/5/10 | 寻路节点流 | 聊天 + 粒子渲染 | ⚠️ NMS 反射读 `Mob.navigation.path` 可做，保真尚可 |
| `counter` | 16 色 | 漏斗计数器 | HUD | ⛔ 随 hopperCounters |
| `packets` | — | `Connection_packetCounterMixin` 连接层计数 | HUD | 🔧 PacketEvents 理论可另做（独立工程，非移植） |

---

## §5 规则层全表（按判定分组）

> **判定基线（26.2 线实测）**：活跃 `@Rule` 字段 83 个（唯一注册路径 = `api/settings/SettingsManager.java:180-186` 反射扫描 CarpetSettings 声明字段；注释态代码不入字节码）；`@Rule(` 文本命中 91 = 83 活跃 + 3 个注释态死块共 8 处提及（:86-132 五处：commandCamera/commandOverlay/commandAITracker + commandDraw:123/commandDistance:131 旧副本，活跃声明分别在 commandDraw:490 / commandDistance:470；:162-176 两处：growingUpWallJump/reloadSuffocationFix；:695-703 一处：waterFlow）。本表判定基线 = **89 个唯一规则名**（83 活跃全覆盖 + 6 上游注释态死名，标†；creativeNoClip 服务端/渲染两半分记 §5.3/§5.4、按一名计）。

### §5.1 ✅ 可完整移植（20 条）

| 规则 | 等价手段 |
|---|---|
| commandPlayer / allowSpawningOfflinePlayers / allowListingFakePlayers | 假人家族（§6） |
| commandLog / defaultLoggers | /log 框架 + 默认订阅 |
| commandDistance / commandInfo / commandPerimeterInfo / commandSpawn（开关条） | 对应命令 ✅（§3） |
| commandDraw（基础形状） | setBlock 循环 |
| commandTick / perfPermissionLevel | Paper 原生命令权限节点 |
| carpetCommandPermissionLevel / language / superSecretSetting | /carpet 壳配套 |
| explosionNoBlockDamage | `EntityExplodeEvent.blockList().clear()` 事件等价 |
| customMOTD | `ServerListPingEvent` 原生等价 |
| viewDistance / simulationDistance | **Paper 原生 per-world 配置自带，无需移植** |
| cleanLogs | 日志框架 filter 等价 |

### §5.2 ⚠️ 可近似移植（23 条）

| 规则 | 近似路径 / 差距 |
|---|---|
| flippinCactus | 事件改写放置朝向（本项目 EasyPlace「改写放行」范式先例） |
| rotatorBlock | `BlockDispenseEvent` 近似 |
| carpets | 放置彩羊毛执行命令，`BlockPlaceEvent` 近似 |
| placementRotationFix / interactionUpdates / tntDoNotUpdate / fillUpdates | `BlockPlaceEvent` 重建放置 flags / 命令重写——语义部分等价（vanilla 更新级联无法完全关闭） |
| tntPrimerMomentumRemoved / hardcodeTNTangle | `EntitySpawnEvent` 速度改写近似 |
| mergeTNT | 调度器扫描合并——可行但"为省性能的优化用费性能的方式做"本末倒置 |
| xpFromExplosions | 爆块 XP 查表补掉落 |
| renewableSponges | 雷击实体替换近似 |
| renewableCoral | 骨粉事件 + 自写结构放置 |
| renewableBlackstone / renewableDeepslate | 液体混合结果改写近似 |
| desertShrubs | 生长事件替换近似 |
| silverFishDropGravel | `EntityChangeBlockEvent` 补掉落 |
| summonNaturalLightning | spawn 事件补部分自然副作用（不全） |
| liquidDamageDisabled | `BlockFromToEvent` 监控近似 |
| forceloadLimit | 命令重写近似 |
| creativeFlySpeed | Abilities 反射 + 同步包 |
| commandTrackAI | AI 目标反射采样，保真低 |
| pingPlayerListLimit | `MinecraftServer_pingPlayerSampleLimit` mixin；Paper `ServerListPingEvent` 玩家样本操控面近似（粒度实现时核对） |

### §5.3 ⛔ 不可移植（37 条——注入即产品）

| 规则 | 不可原因 |
|---|---|
| persistentParrots / growingUpWallJump† / reloadSuffocationFix† | 实体行为 bugfix 深钩（`Player_parrotMixin` 等；† = 上游注释态死规则） |
| xpNoCooldown | `Player.takeXpDelay` 内部字段钩 |
| stackableShulkerBoxes | 改 NMS 全局堆叠（与本项目 servux 同判：MAX_STACK_SIZE 污染序列化） |
| optimizedTNT / tntRandomRange | Explosion 内部算法/半径，无 API |
| fastRedstoneDust | 红石线更新算法重写（`RedstoneWireTurbo`） |
| huskSpawningInTemples / shulkerSpawningInEndCities / piglinsSpawningInBastions | 刷怪表/结构注入（`ChunkGenerator_customMobSpawnsMixin` 等） |
| antiCheatDisabled | 移动检查豁免无事件面 |
| hopperCounters | 拦截点在 `HopperBlockEntity_counterMixin`，Paper 无对应事件（暴力逐 tick 扫描不推荐） |
| movableAmethyst / movableBlockEntities / chainStone | 活塞系统重写（PistonBaseBlock 3+ mixin 家族） |
| pushLimit / railPowerLimit / structureBlockLimit / structureBlockIgnored / sculkSensorRange / creativeFlyDrag | vanilla 常量 / static final 深字段，新 JVM 反射不可靠 |
| maxEntityCollisions / waterFlow† / lagFreeSpawning | 性能优化深钩（waterFlow† = 上游注释态死规则） |
| missingTools | 挖掘速度无 API |
| moreBlueSkulls | wither 射击内部逻辑（`WitherBoss_moreBlueMixin`） |
| updateSuppressionBlock | 与本项目 servux UpdateSuppression 同判：⛔ 省略 |
| creativePlayersLoadChunks | 区块加载系统钩（`ChunkMap_creativePlayersLoadChunksMixin`） |
| creativeNoClip（服务端半边） | 碰撞豁免无 API（客户端渲染半边 ➖） |
| commandProfile / commandCamera† / commandOverlay† | profiler 深钩 / scarpet app（camera / overlay† = 上游注释态死规则） |
| commandAITracker† | 绑 scarpet app 生态（其采样子命令的近似已单列 §5.2；† = 上游注释态死规则） |
| quasiConnectivity | 红石 / 发射器 QC 行为注入（`DispenserBlock_qcMixin` + `PistonBaseBlock_qcMixin` + `helpers/QuasiConnectivity`；fastRedstoneDust 同族） |
| thickFungusGrowth | 巨型真菌生长世界生成钩（`HugeFungusFeatureMixin`；plop 世界生成钩同族） |
| lightningKillsDropsFix | 雷击击杀掉落修复深钩（`ItemEntityMixin` thunderHit；persistentParrots 同族 bugfix 深钩） |

### §5.4 ➖ 纯客户端（4 条）

fogOff、smoothClientAnimations、structureBlockOutlineDistance、creativeNoClip 渲染半边——装 carpet 客户端的玩家自带。

### §5.5 🔗 随 Scarpet 判定（6 条）

commandScript、commandScriptACE、scriptsAutoload、scriptsDebugging、scriptsOptimization、scriptsAppStore。

---

## §6 假人子系统（/player）——可行性与机制

### §6.1 上游机制（26.1 分支全文实证）

三件套全是**普通子类，不是 Mixin**（`carpet/patches/`）：

- **`EntityPlayerMPFake`**（291 行）extends ServerPlayer：`createFake` → 解析 offline UUID / 异步拉取 Mojang 皮肤 profile → `server.getPlayerList().placeNewPlayer(new FakeClientConnection(...), fake, cookie)` 走**原版进服管线** → 加载 playerdata NBT → 传送 → 广播 RotateHead/PositionSync。假人因此天然具备：占 tab 栏、计入睡比例跳夜、保存进 playerdata、被 /tp /ban 等原版命令识别。
- **`FakeClientConnection`**（47 行）extends Connection：挂 `EmbeddedChannel`（`isOpen=true`，上游注释明示这让末影珍珠都能传送假人）；`send`/`handleDisconnection` 全 no-op——S2C 包在连接层丢弃。
- **`NetHandlerPlayServerFake`**（43 行）extends ServerGamePacketListenerImpl：只重写 `disconnect`（idling/重复登录转 kill）与 `teleport`（后置 resetPosition）。
- **`EntityPlayerActionPack`**（629 行，`carpet/helpers/`）：每 tick 写 `player.zza`（前进）/ `player.xxa`（侧移）+ 跳跃/潜行/疾跑标志，动作分 once/continuous/interval 三模式。

### §6.2 Mixin 依赖面与 Paper 替代（仅 4 个集成点）

| Mixin | 注入内容 | Paper 替代 |
|---|---|---|
| `ServerPlayer_actionPackMixin` | 给所有 ServerPlayer 挂 actionPack 字段 + tick 头部钩子 | 侧表 `Map<UUID, ActionPack>` + 框架现成 BukkitRunnable tick 调度 |
| `PlayerList_fakePlayersMixin` ① | `placeNewPlayer` 内 `new ServerGamePacketListenerImpl` 重定向为 Fake 版 | `placeNewPlayer` 返回后反射替换 `player.connection` 字段（`framework/reflect/Reflect` 现成） |
| `PlayerList_fakePlayersMixin` ② | `respawn` 内 `new ServerPlayer` 重定向为 fake 版 | 手工重组 respawn：`newFake.restoreFrom(old, keepEverything)` + 重走 placeNewPlayer + remove 旧实体（vanilla respawn 本体即这三步，重写而非钩入）。**勘误（2026-10-18 P2 as-built）**：本行机制描述对 Paper 不成立且"重走 placeNewPlayer"在任何现代实现中均会重复 PlayerJoinEvent/名册双计入——Paper respawn 为**实例复用**（无 `new ServerPlayer`），假人子类身份天然保留，**零重组代码**即等价（as-built 见 carpet-architecture.md §13）；表行 3 的替代方案作废，行 5 唯一缺口结论不变 |
| `Player_fakePlayersMixin` | 击退时假人 `hurtMarked` 判定 | 每 tick 清 `hurtMarked` 近似，或接受细微差异 |
| `TickRateManager_fakePlayersMixin` | /tick freeze 时假人也冻结的判定 | 无注入点，**接受差异**（唯一功能缺口） |

### §6.3 三线稳定性与先例

- **1.21.11 ↔ 26.1：patches/ 三件套逐字节相同**（diff 为空）；**26.1 → 26.2：仅删 `checkFallDamage` override 5 行**，ActionPack 相同；输入模型 `xxa/zza` 三线存活。一次设计、三线铺开，维护成本接近单线。
- 存在性先例：Modrinth `fakeplayer` 插件（Paper/Purpur 纯插件，功能面近全，1.20.x–1.21.4）；Leaves（Paper fork）内置 /bot 佐证该路径在 Paper 补丁栈下成立；上游 `EntityPlayerMPFake.tick()` 的 `catch(NullPointerException)` 注释自证 Paper 移植存在。
- 主要风险面：Paper 对 `placeNewPlayer` 的补丁栈（PlayerJoinEvent 触发、CraftPlayer 创建）与 respawn 手工重组的状态一致性——调参级问题，非阻断；上游的防御式 catch 即为此类交互的兜底先例。
- 结论：**可完整实现，约 95% 等价**（缺口 = §6.2 末行 tick-freeze 差异 + 击退细节可选近似）。

---

## §7 Scarpet——整体不可行

- 引擎（Expression/词法/值系统，123 文件纯 Java，仅 5 文件 import fakes）：理论可移植，但**不经济**（数万行 + vanilla 桥接层重写）。
- 事件系统：17 个 `*_scarpetEventsMixin`（entity died/jumped/removed、player 全周期、container click、explosion 等）——主体无事件面，少数可 Bukkit 事件近似。
- chunk/光照控制：6 个 `*scarpetChunkCreation*` mixin（set_chunk/load_chunk/light）⛔。
- 图形渲染（scShapes + 2 个客户端 render mixin）🔗 随引擎。
- AppStore 在线仓库：网络功能可做，但 app 生态全部依赖上述 ⛔ 面 → 无意义。

---

## §8 汇总与建议落地范围

| 层 | 可交付实质范围 |
|---|---|
| 假人 | `/player` 全族（§6，约 95% 等价） |
| 日志 | `/log mobcaps`、`/log tps`（HUD 走原版 tab 包，零协议成本） |
| 命令 | `/spawn mobcaps,entities`、`/distance`、`/info`、`/perimeterinfo`、`/draw` 基础形状、`/carpet` 配置壳 |
| 规则 | §5.1 全部 20 条 + §5.2 按需自选近似项 |
| 协议 | `carpet:hello` 握手（可选——不做握手 HUD 照常工作） |
| 红线 | TNT/爆炸优化、红石、活塞可移动方块、刷怪表、漏斗计数器、性能剖析/优化类规则、Scarpet——这些的实现就是对 vanilla 字节码的注入本身，插件形态物理不存在等价物 |

工程量直觉：MobCap/tps 部分（采集 + 协议 + 命令）约数百行、风险极低、最快见效；假人约 1400 行上游代码 + 1500~2500 行 Paper 适配层，风险集中在 `placeNewPlayer` 补丁栈交互与 respawn 手工重组。建议从 `dev`（26.2 线，对照 tag `v26.2`）起步，验证后按 §6.3 近零漂移特性铺到 `ver/26.1.2` 与 `ver/1.21.11`。
