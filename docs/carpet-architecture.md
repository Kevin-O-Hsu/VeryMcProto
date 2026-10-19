# Carpet 模块 as-built（Phase 1 模块地基 + Phase 2 假人子系统 + Phase 3 carpet:hello 协议 + Phase 4 /log 日志 + Phase 5 命令族 + Phase 7 三线铺开）

> **状态**：Phase 1 已实现（2026-10-17，dev 线）；**Phase 2「假人——/player 全族」已实现（2026-10-18，dev 线，§11-§14）**；**Phase 3「carpet:hello 协议」已实现（2026-10-18，dev 线——wire as-built 专文 [carpet-protocol.md](carpet-protocol.md)，本文只保留装配/接口视角）**；**Phase 4「/log 日志框架 + tps/mobcaps」已实现（2026-10-19，dev 线，§16-§18）**；**Phase 5「命令族——纯计算类」已实现（2026-10-19，dev 线，§19-§21）**；**Phase 7「三线铺开 ver/26.1.2 + ver/1.21.11」已实现（2026-10-19，§22-§24）**。范围判定唯一权威 = [carpet-porting-scope.md](carpet-porting-scope.md)（scope）；推进状态 = [carpet-phases.md](carpet-phases.md) §11；命令/权限/配置运维键 = [operations.md](operations.md) §1.6-§1.13/§2.1/§3.4-§3.5。本文承载 as-built 细节与**全量偏差清单**（上游对照树 = `OriginImpl/fabric-carpet-26.2/`，tag `v26.2`——本线；两 ver 线对照各自上游树，见 §22）。
>
> 上游本质是**行为注入型** mod（171 服务端 Mixin）——与既有三 mod（协议投递型）根本不同；本模块只移植 scope 判定为可完整等价的子集，P1 = 模块地基（规则框架 + `/carpet` 壳 + 8 条地基规则），P2 = 假人子系统（patches 三件套 + ActionPack + `/player` 全族 + 3 条假人规则）。

---

## §1 模块装配

- `app/CarpetModule implements ModModule`（仓库第二个 ModModule 实现，黄金模板 `ServuxModule`）：`getModId()/getModString()` 返 `CarpetReference` 常量；`onRegister(DataProviderManager)` **忽略 manager 形参**（carpet 上游是 SettingsManager 体系，无 Provider 概念——诚实降级）。
- 装配链：`SettingsManager` 单例（构造注入 `Reference.plugin().getDataFolder()`）→ `parseSettingsClass(CarpetSettings.class)` → `loadConfigurationFromConf()` → 行为监听器注册（`CarpetRuleListener`，`Bukkit.getPluginManager().registerEvents`）→ `CleanLogsFilter.install()`（幂等）→ **P3：`CarpetHelloHandler`（ChannelManager 注册 `carpet:hello` + join/quit 监听 + 规则推送回调挂载 + 在线玩家补扫 HI，见 carpet-protocol.md §6）**。
- 主类接线：`VeryMcProto.onEnable` 第四 try-catch 模块块 + `/carpet` 注册块（既有同构形态）；`onDisable` 调 `CarpetModule.disable()`（摘除 log4j 过滤器——防插件 reload 类加载器泄漏，见 §7）。
- **即时生效构造**：行为规则监听器常驻注册、事件发生时读当前规则值——`setDefault` 的 live set 由构造保证即时生效，无 observer 热切换接线。

## §2 规则框架（`settings/`，上游 `api/settings/SettingsManager.java` 集群同构）

| B 侧类 | 上游对照 | 说明 |
|---|---|---|
| `Rule`（注解） | `carpet/settings/Rule.java`（legacy——上游 `CarpetSettings` 实际使用者） | 七元素 name/desc/extra/category/options/strict/validate 逐字；删 `appSource`/`condition`（§9） |
| `Validator` | `carpet/api/settings/Validator.java` + legacy 嵌套类 | source 类型换 `CommandSender`；内置 `_COMMAND`（值变更→全服 `Player#updateCommands()` 重发命令树）/`StrictValidator`（索引 0 前置）/`CommandLevel`（OPTIONS 常量） |
| `ParsedRule` | `carpet/settings/ParsedRule.java` | 反射解析：类型换壳（primitive→wrapper）、boolean→options `[true,false]`、String+COMMAND 类目→`CommandLevel.OPTIONS` 推断、COMMAND 类目自动挂 `_COMMAND`（+ String 型加 `CommandLevel`）、strict+options→`StrictValidator`@0；`set()` 验证器链 + 上游写场条件 `!value.equals(value()) || source == null`（命令路径 no-op 免写免通知；conf 载入恒写） |
| `SettingsManager` | `carpet/api/settings/SettingsManager.java` | 见 §3/§4；`notifyRuleChanged` 经注入回调转发规则推送（P3 已接线，见 §10 与 carpet-protocol.md §4） |
| `InvalidRuleValueException` | 同名 | notifySource → §c 英文提示 |

规则值由 `CarpetSettings` 静态字段承载（**volatile**——监听器/日志过滤器跨线程读，上游同病的加固）。

## §3 carpet.conf 格式与怪癖（逐字同构面）

- 逐行 `<rule> <value>`；读 `split("\\s+",2)`（值含空格整段保留）；`#` 注释行跳过；首行 `locked`（忽略大小写）= 锁定；未知规则名 error 日志后跳过；**文件从不主动创建**（首次写盘仅由 `setDefault`/`removeDefault` 触发，上游服务端语义）；写盘 `if (locked) return` 早退。
- **注释怪癖逐字保留**（上游 `SettingsManager.java:449` 的 `A && B || C` 优先级）：附带效果——**值首字符为 `#` 的条目重读时被吞**（如 `customMOTD #tag`）→ 上游继承缺陷，勿"修复"（单测 `commentLinesAndLeadingHashValuesFollowUpstreamQuirk` 锁定该行为）。
- `locked` 语义：全部 `/carpet` 子命令拒绝（含 list/query，等价上游 root `requires(canUseCommand && !locked())` 整树效果）+ `disableBooleanCommands`（COMMAND 类目且建议含 `false` 的规则置 false——当前集内即 `commandTick`）。
- `setDefault` conf 存**空格规范化输入串**（Bukkit args 单空格重组；上游 brigadier greedyString 原样保真——读写同串故往返自洽）。

## §4 /carpet 命令与双层门禁

- 四子命令（`JeiCommand` 形态）：`list`（全量规则按名排序 + default/modified 标记 = 注册表视图）/ `setDefault <rule> <value…>`（live set → conf read-modify-write）/ `query <rule>`（规则菜单：desc/extra/类目/现值/默认/选项/验证器说明）/ `removeDefault <rule>`（conf 移除 → 复位默认）。裸 `/carpet` = 版本行 + 规则计数 + 用法。
- **双层门禁**：plugin.yml 静态 `carpet.command`（op，Bukkit 面）+ 运行时动态 `canUseCommand(carpetCommandPermissionLevel)`。
- `util/CommandHelper.canUseCommand`（上游 `CommandHelper.java:44-60` 同构）：`true`/`false` 值**先于 sender 分派全局短路**；级别判定统一走 `VanillaCommandWrapper.getListener(sender).permissions()` + `Commands.LEVEL_*.check(...)`（26.2 PermissionSet API；全 sender 类型精确——玩家/命令方块/矿车命令方块/RCON/Console/Proxied/FeedbackForwarding 七分支为 Paper 自带映射，`getListener` 返回 null/异常 → deny+warn 兜底）。`parseLevel` 纯函数：`ops`→2、`0-4`→对应档、其余→-1（单测全矩阵）。
- `carpetCommandPermissionLevel` 的 `CarpetPermissionLevel` 验证器：**conf 载入（source==null）自由；命令路径修改需 owner 级（op 4）**（上游 `CarpetSettings.java:134-146` validator 实机制——其注解 desc "Can only be set via .conf file" 与机制脱节，系上游文案陈旧，以 validator 为准）。

## §5 8 地基规则表（默认值/选项域/上游行号/B 侧行为面）

| 规则 | 默认 | 选项域 | 上游行号 | B 侧行为面 |
|---|---|---|---|---|
| `language` | `en_us` | 6 语言 strict | `CarpetSettings.java:77-84` | 纯壳注册（B 无 i18n，对外恒英文——对账语言维不继承） |
| `carpetCommandPermissionLevel` | `ops` | `ops`\|`2`\|`4` | `:147-153` | §4 动态门禁 |
| `superSecretSetting` | `false` | — | `:156-157` | 纯壳（上游 = Mixin 强制命令堆栈 debug 日志，不随 P1 移植） |
| `explosionNoBlockDamage` | `false` | — | `:222-223` | `EntityExplodeEvent` + `BlockExplodeEvent` 双挂点 `blockList().clear()`（上游 Explosion 层单点覆盖一切爆炸源 → Bukkit 拆二后双事件等价；实体伤害/击退不受影响，上游 `Explosion_optimizedTntMixin:61-66` 只清受影响方块） |
| `commandTick` | `ops` | CommandLevel OPTIONS | `:414-415` | **零行为死规则**（上游三线零使用——`/tick` 即原版命令，Paper 天然同构；注册仅为规则面完整，locked 时照上游置 false） |
| `perfPermissionLevel` | `4` | `2`\|`4` | `:424-450` | 只读登记，真因果见 §8 |
| `customMOTD` | `_` | 非 strict | `:705-712` | `_` = server.properties 原生；否则 `ServerListPingEvent#setMotd` |
| `cleanLogs` | `false` | — | `:897-905` | log4j 过滤器，代数见 §7 |

测试锚：`SettingsManagerConfRoundtripTest`（8 用例，判据②）/ `CarpetRuleRegistryTest`（4 用例，判据③——锁 8 规则名/默认/类型/选项域）/ `CommandHelperTest`（3 用例）。

## §6 权限发送端矩阵

`canUseCommand` 经 `getListener` 的隐式矩阵：Console=owner 级（全通 0-4/ops 域）；RCON=owner；命令方块=其配置级（paper config `commandBlocks.permissionsLevel`，默认 2——随配置精确，无硬编码漂移）；玩家=ops.json level。与上游 `CommandSourceStack.permissions()` 同一事实源（等级制，**不查 Bukkit 权限**——这就是 §8 玩家链证据链的一环）。

## §7 cleanLogs 过滤器代数（`logging/CleanLogsFilter`）

- **上游真机制**（语义权威）：`LivingEntity_cleanLogsMixin.java:23-27` @Redirect `die()` 内 `hasCustomName()` → `named && ServerLevel && cleanLogs && SHOW_DEATH_MESSAGES(实体世界)` 才放行命名死亡日志行。**⚠️ 极性警示：cleanLogs 默认 `false` = 抑制（"干净日志"）；`true` = 放行 vanilla 命名死亡日志——勿"纠正"反转。**（上游自身 desc 声称滤 "Maximum sound pool size" 行，与唯一机制 mixin 脱节——文案陈旧勘误，B 按 mixin 代数实现。）
- **B 侧等价**：log4j2 Filter（挂 root LoggerConfig，`addFilter + updateLoggers` 安装 / `removeFilter + updateLoggers` 摘除——不摘除会钉住旧类加载器）。匹配面：logger 名 `net.minecraft.world.entity.LivingEntity` + 消息前缀 `"Named entity "`（26.2 映射源实证全 jar 唯一来源）；DENY 条件 = 命中匹配面 && `!(cleanLogs && 实体世界 SHOW_DEATH_MESSAGES)`——**死亡实体取 `LogEvent` 参数[0]**（vanilla `LOGGER.info("Named entity {} died: {}", this, ...)` 传入实体本体）→ 其 `level()` 的 gamerule，per-entity per-world 精确、无聚合近似；参数缺失/异常 → 保守放行（宁可漏滤不误滤）。
- 共存开关：spigot.yml `settings.log-named-deaths`（默认 true）为原生总闸——false 时该行根本不产生，与过滤器无冲突。

## §8 perfPermissionLevel 真因果（只读登记）

上游机制 = `PerfCommand_permissionMixin` @Redirect 把 `/perf` 的 requires 换成 `perfPermissionCheck` 静态字段。Paper 侧实证（26.2 映射源）：

- **值 4（默认）= 天然同构**：vanilla `PerfCommand.register` 内联 `Commands.hasPermission(LEVEL_OWNERS)` 原样保留，零动作即等价；
- **值 2 = 结构性不可达**：放宽到级 2 需替换 brigadier requires（Mixin 面），Paper 无替换位（`CraftServer.syncCommands` 只重发树不改 requires；`LiteralCommandNode` 无公开 requirement 改写面）；
- **`minecraft.command.perf` 节点仅治理 commandMap dispatch 路径（console/API）**：玩家聊天命令链 = `performUnsignedChatCommand` → `PlayerCommandPreprocessEvent` → `Commands.performCommand` 直达 vanilla brigadier 树，**绕过 bukkit commandMap/testPermission**；且 `ServerPlayer.permissions()` = ops.json 纯等级制、不查 Bukkit 权限——**对玩家授权该节点不生效**（勿在文档承诺）；
- 控制 `/perf` 访问的真实面 = **ops.json 的 op level**。

`PermissionValidator` 副作用 = `CarpetModule.applyPerfPermission(value)`：只读定位节点 + info 日志输出上述真因果（conf 载入期经 `resetToDefault` 触发验证器链时同样生效——上游 validator 即副作用载体，同构）。

## §9 偏差清单（B ↔ 上游全量差异）

| # | 偏差 | 理由 |
|---|---|---|
| 1 | conf 位置 = 插件数据目录（上游 = 世界根 `LevelResource.ROOT`） | 宿主差异（对账登记）；构造注入目录兼使纯 JVM 单测可用 |
| 2 | 写盘原子 tmp/move（上游直写 `Files.newBufferedWriter`） | 仓库配置落盘纪律（architecture.md §3.7） |
| 3 | 命令面解耦到 Bukkit `CarpetCommand`（上游内嵌 brigadier 树） | ~~Paper 无 brigadier 注册面~~ **勘误（2026-10-18，P2 实证）**：paper-api 26.2 实存 `io.papermc.paper.command.brigadier.Commands`（54 个 brigadier 条目）——P1 当时断言错误；P2 的 `/player` 已改用 Paper Brigadier API（§12），`/carpet` 维持 CommandExecutor 形态（四子命令面无参数树需求，Bukkit 形态与 `/player` brigadier 形态并存） |
| 4 | 弃上游裸 `/carpet <rule> [value]` 会话级 set | 锚点四子命令面（会话级 set 与 setDefault 语义重叠，P1 不设）；上游对应 `setRule`/`displayRuleMenu` |
| 5 | 弃 `list defaults`（`findStartupOverrides`）与 `list <tag>` 子面 | 同上；conf 本身即覆盖视图（只含覆盖项） |
| 6 | 裸 `/carpet` = 版本行+计数+用法（上游 = 非默认规则 + 版本 + 类目跳转按钮） | B 无 Messenger 交互按钮面 |
| 7 | 无 Translations/i18n（desc/extra 注解内联英文；`LanguageValidator` 删 Translations 副作用） | 对账语言维不继承；仓库对外英文铁律；strict+options 已足约束 |
| 8 | 删 `appSource`/`condition` 注解面与 Scarpet 分支 | Scarpet 整体不可移植（scope §7）；8 规则零 conditions |
| 9 | 删 client 半边（`_CLIENT` validator / `canBeToggledClientSide`） | 纯客户端功能不移植（scope §5.4）；类目标签保留仅作展示 |
| 10 | tab 补全 = 前缀过滤（上游 `suggestMatchingContains` 含 camelCase 智能拆词） | `JeiCommand` 先例；补全面非语义面 |
| 11 | 规则字段 volatile 化（上游普通 static） | ping/netty 线程读 + 主线程写的跨线程可见性（上游同病的加固） |
| 12 | `Rule` 注解用 legacy 形态（desc/extra 内联）而非 api 新注解（翻译键） | B 无翻译资源体系（同 #7）；上游 `CarpetSettings` 实际用的就是 legacy 注解 |
| 13 | `_COMMAND` 副作用 = `Player#updateCommands()`（上游 `notifyPlayersCommandsChanged` 经 server schedule） | Bukkit 等价；命令路径已在主线程 |
| 14 | `superSecretSetting` 纯壳（上游 = `Commands_customCommandsMixin` 强制命令堆栈 debug 日志） | 锚点「壳配套」裁决；上游机制为 Mixin 面不移植 |
| 15 | `commandTick` 零行为（上游同为零使用死规则） | 与上游同构（scope §3 /tick 行勘误口径） |
| 16 | `perfPermissionLevel` 只读登记（上游 = Mixin redirect 真实放宽） | §8 真因果 |
| 17 | cleanLogs = log4j 过滤器（上游 = hasCustomName Redirect） | 锚点指定「日志框架 filter 等价」；代数逐字保持（§7） |
| 18 | conf 存空格规范化输入串（上游 greedyString 原样） | Bukkit args 已按空白切分；读写同串往返自洽 |

## §10 P1→后续 Phase 接口

- ~~`SettingsManager.notifyRuleChanged(source, rule, userInput)`：空扩展点~~ **已接线（P3，2026-10-18）**——经 `setRuleChangedCallback` 注入回调转发给 `CarpetHelloHandler.onRuleChanged`（单规则帧多播；回调经 setter 注入保持 settings/ 包零 network/ 依赖），wire 链见 [carpet-protocol.md](carpet-protocol.md) §4。
- ~~`CarpetReference.CARPET_CHANNEL/KEY_HI/KEY_HELLO`：P1 仅常量占位，P3 接线~~ **已接线（P3）**——`CarpetHelloHandler`/`CarpetPacketSender`/`CarpetSnoopyCommandSource` 三类落地（`network/` 子包）；**HI 帧版本串 = `CARPET_VERSION`（上游 mod_version 逐字，禁发插件版本号**——`26.2-b3` 必触发客户端 `equals` 严格比较 warn，phases §5 裁决；ver 线 per-line 取值如 1.21.11 = `1.4.194`）。
- P2（假人）已按此接入（`CarpetSettings` 加 3 注解字段即自动注册）；P4（log）/P5（命令族）复用同路径——**新规则名必须逐字存在于上游 `CarpetSettings` 注册表**（客户端规则浏览器可达性硬门禁，carpet-protocol.md §3）。

---

## §11 P2 假人子系统总览（2026-10-18 as-built）

上游对照 = `OriginImpl/fabric-carpet-26.2/` 的 `patches/`（三件套 286/47/43 行，纯子类零 Mixin）+ `helpers/EntityPlayerActionPack.java`（629 行纯逻辑）+ `commands/PlayerCommand.java`（362 行 brigadier 树）+ `script/utils/Tracer.java`（90 行纯算法）。**判定结论（scope §6）"可完整实现，约 95% 等价"经实机实证成立**——缺口 = tick-freeze（唯一功能缺口）+ 击退近似残余窗口（§14）。

B 侧结构（`mod/carpet/` 下）：

| B 侧类 | 上游对照 | 说明 |
|---|---|---|
| `patches/EntityPlayerMPFake` | `carpet/patches/EntityPlayerMPFake.java`（286 行同构） | spawn/kill/die/tick/teleport(override) 链逐字 + 三处 Paper 适配（cookie 7 参 / shadow 名册守卫 / kill 链注释） |
| `patches/FakeClientConnection` | 同名（47 行照抄） | EmbeddedChannel 直赋 public `channel` 字段（上游经 duck 接口）；五 no-op 覆写不可省（`setupInboundProtocol` 会碰裸 channel 的 netty pipeline） |
| `patches/NetHandlerPlayServerFake` | 同名（kick 拦截面重推导） | 拦 Paper kick 漏斗**终端** `disconnect(DisconnectionDetails)`（上游单参 `disconnect(Component)` 已退化为转发壳）：idling/duplicate_login 两 key → `kill(reason)`，其余 return 吞掉 |
| `helpers/EntityPlayerActionPack` | 同名（629 行照抄） | 4 处 `ServerPlayerInterface.getActionPack()` → `ActionPackManager.get(player)` |
| `helpers/ActionPackManager` | 上游 = Mixin 字段 + tick HEAD 注入 | B 侧等价：`Map<UUID,ActionPack>` 侧表 + BukkitRunnable 每 tick `tickAll` + 生命周期（quit 清/respawn 复位/卸载 kill 全假人） |
| `util/Tracer` | `carpet/script/utils/Tracer.java`（90 行照抄） | 零 scarpet 依赖纯算法，迁包 |
| `command/PlayerCommand` | 同名（362 行树移植） | Paper Brigadier API 载体（§12） |
| `listener/FakePlayerListener` | 上游 = Mixin 字段生命周期 + `Player_fakePlayersMixin` | quit（侧表 remove）/respawn（动作归零）/velocity（击退保真，hurtMarked 门控）三事件 |

装配（`CarpetModule.onRegister` 追加）：`FakePlayerListener` 注册 → `ActionPackManager.start(plugin)`（runTaskTimer(0,1)）→ `LifecycleEvents.COMMANDS` 注册 `PlayerCommand.build()`（失败降级日志）；`disable()` 追加 `ActionPackManager.shutdown()`（kill 全假人——NMS 对象引用插件类，reload 后存活假人将 NoClassDefFoundError，必须先行移除）。

**判据实证（runServer 控制台 18 轮，2026-10-18）**：①全子命令逐条实测通过（spawn 含 `at <pos>` 相对/绝对坐标、look/turn/move/sneak/sprint/attack/use/jump/hotbar/drop/swapHands/mount/dismount/stop/kill/shadow 门禁回执）②占 tab 栏（`list` 显示 1 online）/ **计入睡比例跳夜**（peaceful 夜间 `use` 对床 → 睡下 → `ServerLevel.tick` 跳夜分支执行 → `wakeUpAllPlayers` 叫醒，堆栈实证）/ playerdata 持久（`world/players/data/<uuid>.dat` 落盘 + 再 spawn 读回）/ `/tp` 识别（Teleported + data get 确认）/ `/ban` 识别（入封禁列表 + 再 spawn 被 cantSpawn 拒）③速度保真标定（超平坦世界 sprint+move 8 秒位移 44.84 格 = **5.6 m/s 精确 vanilla 疾跑速度**）④四模块起服零回归。**已知缺口显式登记**：tick-freeze 不冻结假人（Paper `isEntityFrozen` 恒豁免一切 Player，无注入点）；击退 `PlayerVelocityEvent` 门控存在伤害同 tick 内第三方 `setVelocity` 的残余误伤窗口（≤1 tick，事件无 cause 字段无更窄判别面）。

## §12 /player 命令载体（Paper Brigadier API）

- 注册面：`Reference.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(PlayerCommand.build()))`——paper-api 实存 `io.papermc.paper.command.brigadier.Commands`（P1 偏差 #3 勘误的实证基础）。
- **source 类型层**：树泛型 = Paper 接口 `io.papermc.paper.command.brigadier.CommandSourceStack`（NMS `net.minecraft.commands.CommandSourceStack` 实现之）——上游逻辑全操作 NMS source，经 `PlayerCommand.nms()` 单点 cast（实现即 NMS 对象，零开销）。
- NMS 参数类直用：`Vec3Argument/RotationArgument/GameModeArgument/DimensionArgument`（相对坐标/旋转解析语义免费获得）；**NMS Argument 静态 get 方法绑定 NMS context 泛型**——取值改用 brigadier 标准 `context.getArgument(name, Class)`（`Coordinates.getPosition(nmsSource)` / `.getRotation(nmsSource)`、`ServerLevel.dimension()`、`GameType`），语义等价（上游静态 get 内部即此模式）；26.2 的 `RotationArgument` 泛型返回 `Coordinates`（无 `Rotation` 类）。
- 权限：根 `.requires(canUseCommand(commandPlayer))`——`CommandHelper` 新增 NMS `CommandSourceStack` 重载（上游 `CommandHelper.java:44-60` 同构）；requires 每次执行求值 → 规则动态性天然成立。gamemode 子节点 requires = LEVEL_GAMEMASTERS 谓词（上游同构）。**plugin.yml 无 /player 条目**（brigadier 注册自足，权限模型 = 规则门非 Bukkit 节点）。
- 补全建议 = Steve/Alex + 在线玩家名（上游 `getPlayerSuggestions` 同构）。

## §13 spawn / kill / respawn 三链（Paper 补丁栈交互 as-built）

- **spawn**：`createFake` 异步 profile 解析（`spawning` 名册防重入）→ `placeNewPlayer(FakeClientConnection, fake, CommonListenerCookie.createInitial(profile, false))` 走原版进服管线（读 playerdata/入名册/入世界/PlayerJoinEvent）→ 返回后 `new NetHandlerPlayServerFake(...)` 替换监听器（ctor 自动 `player.connection = this`；被弃的真实例无全局注册面；Paper `createTextFilterForPlayer` 恒 DUMMY，双 join 无害——placeNewPlayer 内对真监听器的 suspendFlushing/teleport 调用落在被弃实例，无害）→ loadPlayerData/teleportTo/setHealth/STEP_HEIGHT/gameMode/广播 RotateHead+PositionSync/model layers/flying。**Paper 26.2 的 `CommonListenerCookie` 为 7 组件 record**（vanilla 4 参已扩：clientBrand/channels/KeepAlive）——createFake 用 `createInitial`（cli=createDefault 逐字段等价），createShadow 用 7 参 canonical（cli 沿用原玩家自定义）。
- **kill**（假人唯一移除协议）：`shakeOff`（卸载玩家乘客）→ duplicate_login 立即 / 其余 schedule 下 tick → `this.connection.onDisconnect(details)`——`this.connection` 静态类型 = **SGPL**（`ServerPlayer.connection` 字段声明），`SGPL.onDisconnect` = 完整退出链（`removePlayerFromWorld` → PlayerQuitEvent + 名册/世界移除 + 存档），不经 kick 终端、与 `NetHandlerPlayServerFake.disconnect` 覆写无递归。`die()` = shakeOff + super.die + setHealth(20) + new FoodData + kill(deathMessage)——假人死亡即移除不留尸。
- **respawn**：**Paper `PlayerList.respawn` 为实例复用**（`ServerPlayer serverPlayer = player`，vanilla `new ServerPlayer` 已被 CraftBukkit 移除，playerweight 源码实证）——上游 Mixin ② 的 respawnFake 重定向在 B 侧**无对应现实**，假人子类身份与 fake 监听器 respawn 后天然保留，零重组代码（scope §6.2 表行 3 的"手工三步重组"方案作废，勘误记档）。唯一触发源 = 上游 `teleport(TeleportTransition)` override 的 wonGame 分支（PERFORM_RESPAWN 经 `connection.handleClientCommand` 走 vanilla respawn 链，末地门场景）——照抄。`fixStartingPosition` 字段删除（其唯一消费点 = 上游 Mixin ① 的 placeNewPlayer 内注入；B 侧定位由 spawn 后 `teleportTo` 承载，差异 = placeNewPlayer 过程中假人短暂处于读档位置，调参级）。
- **shadow**：原玩家 `disconnect(duplicate_login)` → **名册守卫**（`getPlayer(uuid) != null` → 中止——Paper 的顶号踢线可被第三方插件取消（PlayerKickEvent），上游 Fabric 无事件面不存在此竞态）→ 复用 GameProfile/ClientInformation placeNewPlayer → ActionPack copyFrom（侧表覆盖真实玩家语义的来源）。

## §14 P2 偏差清单（B ↔ 上游）

| # | 偏差 | 理由 |
|---|---|---|
| 19 | kick 拦截面 = `disconnect(DisconnectionDetails)` 终端（上游 = `disconnect(Component)` 单参壳） | Paper 26.2 kick 重载族全部收敛于终端（PlayerKickEvent fire 点亦在终端内）；单参壳已退化为转发壳，照抄即失效 → 幽灵假人 |
| 20 | kick 对假人不 fire PlayerKickEvent | fire 点位于被覆写吞掉的终端方法内部（上游 Fabric 无事件面，同语义；第三方插件不会观察到假人被踢） |
| 21 | respawn 不做 Mixin ② 替代（respawnFake 删除） | Paper 实例复用天然保留假人身份（§13） |
| 22 | 击退 = `PlayerVelocityEvent` cancel + `hurtMarked` 门控（上游 = `Player.causeExtraKnockback` @Redirect） | Paper 注入点不存在（该方法仍在但无 Mixin 运行时）；事件 cancelled 路径跳过"回滚服务端速度"三件套 = 上游 Mixin 语义逐位等价；门控字段 = 上游被 redirect 的同位字段（攻击路径事件恰在 hurtMarked 置位后 fire，插件主动 setVelocity 路径无此不变量）；残余窗口见 §11 |
| 23 | actionPack = 侧表（上游 = Mixin 字段，所有 ServerPlayer 含真人都有包） | 禁 Mixin；侧表 `computeIfAbsent` 按需创建，shadow copyFrom 与 op 操控真人两消费面语义保持 |
| 24 | respawn 动作归零经 `PlayerRespawnEvent` 显式 `stopAllFor`（上游 = 新实例新包天然归零） | Paper 实例复用，包不换新 |
| 25 | tick 驱动 = BukkitRunnable 每 tick 遍历侧表（上游 = `ServerPlayer.tick` HEAD 注入） | scheduler 阶段早于同 tick 玩家 tick——运动输入写的消费不错过；实机 5.6 m/s 标定实证等价 |
| 26 | 命令载体 = Paper Brigadier API lifecycle 注册（上游 = Fabric Dispatcher） | §12；P1 偏差 #3 勘误的前置 |
| 27 | NMS Argument 取值 = `context.getArgument`（上游 = NMS 静态 get） | Paper 树泛型与 NMS context 泛型不兼容；语义等价 |
| 28 | `Messenger` 输出 → `sendFailure`/bold 组件（消息文本逐字英文） | B 侧无 carpet Messenger |
| 29 | `CommonListenerCookie` = `createInitial` / 7 参 canonical（上游 = vanilla 4 参） | Paper 26.2 扩展 record（§13） |
| 30 | 插件卸载 kill 全假人（上游 = CarpetServer 关服链） | 防插件 reload 后 NMS 对象引用已卸载类 → NoClassDefFoundError |
| 31 | shadow 名册守卫（上游无） | Paper 特有：顶号踢线可被插件取消 → 同 UUID 双实体 |
| 32 | `/ban` 只入 userbanlist 不移除在线假人 | 上游同语义（假人不响应 disconnect；移除走 `/player kill`；再 spawn 被 cantSpawn 拒） |
| 33 | syncmatica 对每个假人发起一次必败握手 | 假人 send 全吞 → 悬挂 exchange 至 quit 清理；不炸（per-target try-catch）、仅 debug 日志噪声；servux/jei 链实证安全（假人不经 configuration 相位、无 C2S 证明永不走 NMS 兜底发送） |
| 34 | tick-freeze 假人不冻结 = 唯一功能缺口 | Paper `TickRateManager.isEntityFrozen` 恒 `!(entity instanceof Player)` 豁免，插件形态无窄化注入点；解除条件 = Paper 提供冻结查询事件面 |
| 35 | `Tracer` 迁至 `mod/carpet/util`（上游在 scarpet 工具包） | Scarpet 整体不移植（scope §7）；本类零 scarpet 依赖 |
| 36 | `tick()` NPE 吞点保留 + 首次触发打一次性 WARN 堆栈（上游静默吞） | 上游逐字保留（其注释自证 Paper 移植防御）；加一次性日志供排错（实机多轮零触发） |
| 37 | 掉落伤害 = tick 手动累积 `fallDistance` + 落地直调 `LivingEntity.causeFallDamage`（上游 26.2 逐字移植时双免疫——Fabric 端正常、Paper 端坏） | **Paper 补丁差异**（官方 26.2 未混淆源码对照实证）：① vanilla `ServerPlayer.checkFallDamage` 结尾调 super（伤害在 `Entity.checkFallDamage` → fallOn → causeFallDamage 链），Paper 删掉了该 super 调用（只余落地粒子）；② 物理路径调用入口与 fallDistance 累积被客户端权威门对服务端玩家关闭（实测恒 0/零调用）。真玩家无感（伤害由 move 包路径结算），假人无 move 包 → 双免疫。修复 = 手动累积 + 直调与真玩家 move 包路径同一结算方法（护甲/药水全套公式原样继承）。上游 26.1 的桥接 override（checkFallDamage → doCheckFallDamage）在 26.2 结构下自递归，不可搬运 |
| 38 | 环境接触伤害 = tick 专项检测（甜浆果丛/仙人掌/凋零玫瑰；上游 Fabric 同样只受一次伤） | vanilla 的 entityInside 检测由 move 包位移驱动，假人静止后无数据源。修复 = AABB 相交方块专项检测 + vanilla 同款 DamageSource；甜浆果带 vanilla 位移条件（横向任一分量 ≥ 0.003 才伤——静止不伤走动伤，`SweetBerryBushBlock.entityInside` 字节码还原）；仙人掌/凋零玫瑰 vanilla 无位移条件（接触即伤）；频率由 hurtServer 无敌帧天然节流（与真玩家节奏一致）。蛛网减速等非伤害 inside 效果不在列（低价值登记） |

## §15 P2 测试锚

- `EntityPlayerActionPackTest`（4 用例）：Action 四工厂计时字段初值反射断言（once=(1,1,0)/continuous=(-1,1,0,true)/interval(5)=(−1,5,0,next=5)/interval(5,2) next=7）——命令面 `once|continuous|interval <ticks>` 的核心参数语义锁。Action.tick/retry 执行路径依赖 NMS ServerPlayer，不入纯 JVM 单测面（实机判据承载）。
- `CarpetRuleRegistryTest` 扩 11 规则锁（3 假人规则默认值/类型/选项域 + commandPlayer CommandLevel 推断；P4 再扩至 13——§18）。
- 实机矩阵（phases §4 判据）：见 §11 实证清单。

---

## §16 P4 /log 日志子系统总览（2026-10-19 as-built）

上游对照 = `carpet/logging/`（LoggerRegistry 173 行 + Logger 255 行 + HUDLogger 53 行 + HUDController 149 行）+ `carpet/commands/LogCommand.java`（222 行）+ `carpet/utils/SpawnReporter.java:93,:99-152`（mobcaps 切片）+ `carpet/utils/Messenger.java:30-123`（色表）。范围 = scope §4 的 tps/mobcaps 两条 HUD logger + 框架（scope §3 `/log` 行）；chat 型五条（tnt/explosions/projectiles/fallingBlocks/pathfinding）后置、counter/packets 不做。

B 侧结构（`mod/carpet/` 下）：

| B 侧类 | 上游对照 | 说明 |
|---|---|---|
| `logging/Logger` | `carpet/logging/Logger.java`（逐段同构） | 名键 online/offline 双桶 + 桶迁移 + 两 log 重载 + isOptionValid；删上游 6 死成员（偏差 #45） |
| `logging/HUDLogger` | 同名（同构） | removePlayer→clearPlayer / sendPlayerMessage→HUD 缓冲 |
| `logging/LoggerRegistry` | 同名（同构减 seenPlayers/toggle） | 双 Map + `__tps`/`__mobcaps` 反射快捷位（上游 Field 机制保留）+ 订阅三原语；注册面 = tps(null,null) + mobcaps("dynamic",四选项) 两条（上游 :44/:47 逐字） |
| `logging/HUDController` | `HUDController.java:70-129`（P4 面） | tps 行（MSPT/TPS 公式逐字）+ mobcaps 行（option→维度 switch）+ footer flush + runTaskTimer(20,20) 任务 |
| `logging/SpawnReport` | `SpawnReporter.java:99-152` 切片 + Messenger 色表 | MAGIC_NUMBER=289 硬编码 + 先除后乘公式逐字 + 热力色/类别色 § 码化 |
| `logging/LogSubscriptionStore` | —（B 侧新增层） | 跨会话持久（上游无）：显式订阅 + 退订墓碑双表、UUID 键 + 名字快照、`""`↔null 哨兵、变更即原子写、空册不主动建文件 |
| `logging/LoggerListener` | 上游 = PlayerList/SGPL 双 Mixin 挂点 | join/quit Bukkit 事件；join 谓词见 §16.1；假人全链豁免；handleJoin 供启用期补扫 |
| `command/LogCommand` | `carpet/commands/LogCommand.java` | Paper Brigadier 载体（PlayerCommand 范式）；subscribe/clear 形（phases §6 交付物①） |

装配（`CarpetModule.onRegister` 追加）：`store.load()` → `LoggerRegistry.initLoggers()` → `LoggerListener` 注册 → `LifecycleEvents.COMMANDS` 注册 `LogCommand.build(store)` → `HUDController.start(plugin)` → 在线玩家补扫 `handleJoin`（reload 空窗闭合，hello 补扫同款 B 侧增补）。`disable()` 追加三连清：`HUDController.shutdown()`（停任务 + 清在线订阅者 footer——须先于 stopLoggers，订阅名册仍存活）→ `store.save()` 兜底 → `LoggerRegistry.stopLoggers()`。plugin.yml 无 /log 条目（Brigadier 注册自足，/player 先例）。

### §16.1 订阅状态模型（设计要点：显式订阅 + 退订墓碑 + 默认不落盘三性质）

**内存册**（上游同构）：`playerSubscriptions`（名→logger→option，HashMap 容 null 值——tps 默认 option 即 null）+ 每 Logger 内 online/offline 名键双桶（quit/connect 桶迁移，会话内重登保活）。

**持久册**（`carpet-logs.json`，B 侧新增——判据②跨会话持久）双信号模型：

- `subs` = **显式**订阅（/log 命令产生），UUID 键 + 名字快照；
- `off` = **退订墓碑**（/log clear 产生）——压制默认施用对已显式退订者的重施（防「退订项重登复活」）；
- **默认订阅永不落盘**——每次 join 由 LoggerListener 按当前 `defaultLoggers` 规则值对「未订阅且无墓碑」的 logger 重评估（防规则变更对存量玩家永久冻结；管理员改默认对未显式选择者下次 join 生效）；
- join 谓词（钉死）：当前名**在内存册**（`getPlayerSubscriptions != null`——主表即名册，清空删键、离线条目亦镜像）→ 仅桶迁移；**不在册**（新玩家/重启首连/**改名重登**）→ store 经 UUID 载入 + 以当前名入册 + 名字快照更新。

**假人豁免**：join/quit/补扫全跳过（`EntityPlayerMPFake` 判定，shadow 共享真人 UUID 一并豁免）——假人 HUD 天然 void（上游发往假人同样不可见 = 可见行为等价），B 侧持久层新增故须显式豁免（防 /player 反复 spawn/kill 灌永久死记录）。

### §16.2 HUD 投递

每 20 tick（`runTaskTimer(20,20)`）`updateHud`：`__tps` 门 → tps 行（MSPT = `getAverageTickTimeNanos()`/1e6、TPS = `1000/max(sprint?0:目标mspt, MSPT)`、frozen→0、热力色，上游 :116-129 公式逐字）；`__mobcaps` 门 → 按 (option, player) 维度行（switch：overworld/nether/end 固定，dynamic/未知落玩家当前维度，上游 :86-92）；flush = 逐名 `setPlayerListFooter(String.join("\n", lines))`——**只写脚、零 header 触碰**（paper-api 实 jar 实证 setter 存在）。快捷位为 false 时整段跳过（零订阅零成本）。已知限制：外部写 footer 的 tab 类插件与本插件每秒互覆写（上游同源行为，上游 :109-113 亦裸覆写）——见 operations.md §5.1。

### §16.3 规则（上游 CarpetSettings.java:453-463 逐字）

`commandLog`（默认 `"true"`，COMMAND 类目→CommandLevel OPTIONS 推断）= /log 根 requires 规则门；`defaultLoggers`（默认 `"none"`，options `{none,tps,mobcaps,tps}` 含复合值、strict=false、extra csv 说明、category `{creative,survival}`）= join 重评估值源——CSV 语法 `split(",")→split(" ",2)`，条目 = "logger" 或 "logger option"（"mobcaps overworld" 合法），未知名经 getLogger==null 自然跳过。

## §17 P4 偏差清单（B ↔ 上游，续 §9/§14 编号）

| # | 偏差 | 理由 |
|---|---|---|
| 39 | HUD 投递 = Bukkit `setPlayerListFooter(String)` 单写（上游 = NMS `ClientboundTabListPacket` 头脚齐发，头=scarpet 保留） | phases §6 交付物②点名 Bukkit 面；零 header 触碰（scarpet 不移植、他插件 header 不覆写）；§ 码 String 为该 API 原生输入 |
| 40 | 节流 = `runTaskTimer(period 20)`（上游每 tick 被调 + `%20` 内部门） | 同拍率的任务参数化承载（ActionPackManager 先例）；初相 20 = 首帧不抢跑 |
| 41 | HUD 缓冲键 = 玩家名（上游 ServerPlayer 实例） | Bukkit Player 引用不持有（reload 防泄漏）；flush 经 getPlayerExact 复查在线 |
| 42 | 行分隔 = `String.join("\n")`（上游 `Component.literal("\n")` 组件） | String 载体等价物 |
| 43 | 消息载体 = § 码 String[]（上游 Messenger Component DSL + 色字母） | tab footer API 的 String 形态；色字母→§ 码映射 SpawnReport 静态化（g=§7/e=§2/y=§e/r=§c/m=§d/n=§4/f=§8/v=§1/q=§3/w=§f） |
| 44 | 跨会话持久层 `carpet-logs.json` 新增（上游纯内存、重启即失，stopLoggers 全清） | 判据②明确要求跨会话持久——上游无对应物；双信号模型（显式+墓碑+默认不落盘）见 §16.1 |
| 45 | 命令形状 = `/log`（裸列表）/`subscribe <logger> [option]`/`clear [logger]`（上游 flat 树 `/log <name> [option] [player]` + toggle + `<name> clear`） | phases §6 交付物①字面 subscribe/clear 形；自用面无 `<player>` 定向参、无 toggle |
| 46 | listLogs 纯文本 + 命令提示行（上游可点击/悬停组件 `!`/`^`） | P2 #28 Messenger 替换范式同判；option-less logger（tps）分支结构保持 |
| 47 | console/非玩家全子命令统一 "For players only" 拒绝（上游各执行体经 getPlayerByName(null) 恒失败走 "No player specified"） | 自用面无目标参，收敛为同一句（上游裸 /log 文案同源） |
| 48 | 假人全链豁免（上游对假人照常施用默认与订阅——会话即清无累积面） | B 侧持久层新增：防 /player 反复 spawn/kill 灌永久死记录 + shadow 污染真人册 |
| 49 | Logger 删上游 6 死成员：`stardardLogger` 工厂/`log(lMessageIgnorePlayer)`/`getAcceptedOption`/两 4 参 `@Deprecated` 构造器/`togglePlayerSubscription` | 上游全树零调用或消费点全在本期范围外（counter/packets/chat 五条）——AGENTS.md §6 教训 2「勿照抄死 API」；后置 chat logger 期回填 |
| 50 | `seenPlayers`/`firstTime` 整体删除（上游 :139-164 会话级首连判定） | 唯一消费点（默认施用 else-if :215-226）已随持久化模型迁 LoggerListener 每 join 重评估——无第二消费方 |
| 51 | 注册面 = tps/mobcaps 两条（上游 9 条） | scope §4：chat 型五条后置、counter/packets 不做——框架留有注册位 |
| 52 | mobcaps chunkcount 数据源 = `SpawnState.getSpawnableChunkCount()`（上游自有 `chunkCounts` 事件计数 map，ServerChunkCacheMixin 维护） | phases §6 交付物④钉死 SpawnState 公开 API（上游 Monitoring.java:61 自用同款、servux DataLoggerMobCaps 同路径）——两谱系数值可偏差（事件计数 vs 刷怪周期快照）；公式仍 carpet 逐字（先除后乘 :119） |
| 53 | 持久文件空册且不存在时跳过写盘 | 「文件从不主动创建」仓库纪律（carpet.conf 先例）；records 只增不删，空册恒处女态 |

## §18 P4 测试锚与实机判据

- `CarpetLoggerTest`（18 用例，纯 JVM）：注册表订阅语义（null 归一/清空删键/桶迁移翻快捷位——纯 JVM 环境 playerFromName 恒 null = 恒离线桶，恰好使 onPlayerConnect 迁移可测）；store round-trip（`""` 哨兵/墓碑三态/clearAll 全墓碑/坏 JSON 空册/改名快照/空册不建文件）；join 分支矩阵（新玩家默认不落盘/改名重登 UUID 复原/墓碑拦截/显式优先/同会话重登桶迁移/未知名跳过）；defaultLoggers CSV 解析（"logger option" 语法）；heatmap 阈值边界（严格 >，0.5r/0.8r/r 升色）；mobcaps 行格式（--UNAVAILABLE-- 逐字/cur<0 显"-"/末分隔符去除/公式锁值）。
- `CarpetRuleRegistryTest` 扩 13 规则锁（commandLog/defaultLoggers 名/默认/类型/选项域 + CommandLevel 推断 + 复合 option "mobcaps,tps"）。
- **实机判据（phases §6）**：① carpet 客户端 HUD 显示 tps/mobcaps（**用户侧终验**——服务端侧以 footer 写入路径 + 单测/冒烟承载）② 订阅跨会话持久（状态机单测全矩阵 + carpet-logs.json 往返；真玩家会话往返属客户端联测）③ tab 头脚无冲突（仓内零占用 grep + 只写 footer 设计）。
- runServer 冒烟（2026-10-19）：四模块零回归起服；console 三入口（裸/subscribe/clear）全部 "For players only"（命令注册 + commandLog=true 门放行 console + 执行体拒绝链）；`/carpet list` 13 规则（commandLog=true/defaultLoggers=none 默认标记）；`setDefault defaultLoggers tps` → conf 落盘 → `removeDefault` 复位（往返干净）；假人 spawn/kill 零 /log 写入（豁免实证）；停服 disable 三连清无异常。

---

## §19 P5 命令族总览（2026-10-19 as-built）

上游对照 = `carpet/commands/{DistanceCommand(44),InfoCommand(87),PerimeterInfoCommand(84),DrawCommand(451),SpawnCommand(250 行——仅 mobcaps/entities 两子树)}.java` + `carpet/utils/{DistanceCalculator,BlockInfo,PerimeterDiagnostics,Messenger}.java` + `carpet/script/utils/Colors.java` + `carpet/utils/SpawnReporter.java:99-152,:234-258`（多行 mobcaps 与实体清单切片）。**上游语义勘误（本 Phase 落地时实证，scope §3 / phases §7 已同步勘误）**：`/distance` = 两点三距离公式（Spherical/Cylindrical/Manhattan，`DistanceCalculator`——非寻路、零 Tracer 消费）；`/info` = block-only 属性清单（`BlockInfo`——无实体分支、非 NBT dump）。

B 侧结构（`mod/carpet/` 下）：

| B 侧类 | 上游对照 | 说明 |
|---|---|---|
| `util/Messenger` | `carpet/utils/Messenger.java`（~320 行全量逐字） | **P5 起新增的命令输出层**：色字母码→Style、可点击/悬停（`!`/`?`/`^`/`@`/`&`）、`tp` 坐标行、`heatmap_color`/`creatureTypeColor` 字母码版。imports 全 vanilla chat API + slf4j（NMS 自带）——零 Mixin/scarpet 依赖。**三代输出面并存**：/player /log = § 码（#28/#43/#46 已裁决面，不回改）；HUD footer = § 码 String（传输载体）；P5 命令 = Messenger 组件（上游一致，锚点扩展①裁决） |
| `util/DistanceCalculator` | 同名（逐字） | `START_POINT_STORAGE` 按源名键控 + 三距离公式（`Mth.abs((float)a-(float)b)` float 域逐字）+ setEnd 无起点回落；`findDistanceBetweenTwoPoints(Vec3,Vec3)` 返回组件列表**零 source 依赖 → 纯 JVM 单测直调**（测试缝隙抽取取消——比原计划更贴上游） |
| `util/BlockInfo` | 同名（逐字） | 属性清单 + `wander_chances`（1000 次采样 + 最坏 1000×36000 次 canUse 评估——上游同构照抄，继承风险登记 §20 #61） |
| `util/Colors` | `carpet/script/utils/Colors.java`（逐字迁包） | 仅包位迁移（scarpet 工具包 → util，Tracer P2 同判）；BlockInfo 同包直用 |
| `util/PerimeterDiagnostics` | 同名（逐字） | ±128 圆柱 + 24²-128² 球壳 + liquid/ground/specific + samples≤10 + check_entity_spawn 全 vanilla 公开 API；深 ServerLevel/Mob 依赖 → 实机覆盖 |
| `command/DistanceCommand` | 同名（六分支嵌套树逐字） | `from` 裸→setStart(当前位)/`from <v>` 裸→setStart/`from <v> to` 裸→**直算回显不触存储**/`from <v> to <v>`→distance/顶层 `to` 裸→setEnd(当前位)/`to <v>`→setEnd |
| `command/InfoCommand` | 同名（逐字） | grep 跑 `line.getString()` **纯文本层**（§ 码层会把 "grep 7" 变行行命中）+ 首行恒输出 + 非 gamemasters 双守卫（hasChunkAt/isInWorldBounds） |
| `command/PerimeterInfoCommand` | 同名（逐字 + suggests 桥接） | 根裸 = 当前位为心；mob = `ResourceArgument.resource(ctx, ENTITY_TYPE)`；`loadEntityRecursive` 26.2 新形态（`EntitySpawnRequest(COMMAND,true)`）@minY-10 + addWithUUID；非 Mob 拒绝行 |
| `command/DrawCommand` | 同名（七 literal 树 + 四算法内联逐字） | sphere/ball/diamond/pyramid/cone/cylinder/cuboid + `drawShape` 尾部（block + [replace filter]）；setBlock = Container clearContent → `BlockInput.place(world,pos,2)`；**fillUpdates 固定 true 语义**（偏差 #57）；plop 零耦合实证不涉 |
| `command/SpawnCommand` | `SpawnCommand.java:95-107` 两子树逐字 | mobcaps [\<dimension>] + entities [\<type> [all]]；`entities <type>` = `string()` + MobCategory 名建议 + `getCategory` 校验（**type 是类别词如 monster，非实体 id**）；`mobcaps set` 裁剪（偏差 #58） |
| `logging/SpawnReport`（追加） | `SpawnReporter.java:99-152,:234-258` 切片 | +`cachedMobCategories()`/多行 mobcaps 组件报告/`printEntitiesByType`——append-only，P4 短行 § 码面与 18 测试零触碰；rounds 段恒空（spawn_tries 追踪面未移植） |

装配（`CarpetModule.onRegister` 追加）：五命令各独立 try-catch 经 `LifecycleEvents.COMMANDS` 注册（单命令失败只降级）；`/perimeterinfo` `/draw` 的参数类工厂需 `CommandBuildContext` = `CommandBuildContext.simple(Nms.server().registryAccess(), FeatureFlags.VANILLA_SET)`（公开静态工厂，javap 实证；懒加载单点缓存——registryAccess 只读快照）。规则五条 = 上游活跃声明逐字（`commandSpawn="ops"`/`commandDistance`/`commandInfo`/`commandPerimeterInfo`=`"true"`/`commandDraw="ops"`，COMMAND 类目自动 CommandLevel OPTIONS）；conf 持久化与 carpet:hello Rules 增量同步均既有链路自动承载。plugin.yml 零改动；`disable()` 零新清理面（命令树无资源句柄）。

**`/spawn mobcaps` 与 `/log mobcaps` 交叉一致判据由构造保证**：同一 `SpawnReport` 类、同一 `getLastSpawnState().getSpawnCategoryCounts()/getSpawnableChunkCount()` 数据源、同一先除后乘公式与热力色阈值（§ 码/字母码两形态同值映射，偏差 #43 映射表）。

## §20 P5 偏差清单（B ↔ 上游，续 §9/§14/§17 编号）

| # | 偏差 | 理由 |
|---|---|---|
| 54 | P5 命令输出 = `Messenger` 组件面（含可点击 tp 坐标——上游一致）；/player /log /HUD 保留 § 码面不回改 | 锚点扩展①「一切语义与输出形态以上游源码为准」——P5 新命令按上游组件形态；P2 #28/P4 #43/#46 为已裁决已交付面（回改超锚点，登记双轨并存） |
| 55 | `/perimeterinfo` suggests 桥 = 直接 unchecked cast `(SuggestionProvider<paper>) SuggestionProviders.SUMMONABLE_ENTITIES`（上游 = `SuggestionProviders.cast(...)`） | `cast` 的泛型界 `<S extends SharedSuggestionProvider>` 在 paper 树泛型上不可推断（paper CommandSourceStack 不继承该接口）；运行时安全字节码级实证——provider 对 `context.getSource()` 做 `checkcast SharedSuggestionProvider` + `enabledFeatures()`（触 source），而 Paper 派发链运行时对象即 NMS `CommandSourceStack`（implements 之，javap 类声明实证）→ checkcast 必过；失败模式双重可观测（服务端 CCE 日志 + 客户端补全空）。**本项目首个触 source 的 NMS suggestion provider**——tab 补全实测 = 硬判据（`/perimeterinfo <tab>` 出可召唤实体 id 全集） |
| 56 | `/perimeterinfo` mob 参数取值 = `getArgument(Holder.Reference.class)` 直取，canSummon 复检省略 | vanilla `ResourceArgument.getSummonableEntityType` 的 `ERROR_NOT_SUMMONABLE_ENTITY` 非 public 不可引用；suggests 已过滤 + 非 Mob 拒绝行双兜底（不可达面经 loadEntityRecursive 后仍落上游同款拒绝文案） |
| 57 | `/draw` fillUpdates 固定 true 语义：`impendingFillSkipUpdates` ThreadLocal 删除、收尾 `updateNeighborsAt` 无条件 | ThreadLocal 消费端 = 6 个 fillUpdates Mixin（BlockInput/Level/LevelChunk/StructureBlockEntity/Commands/SGPL），Paper 无 Mixin 物理不可移植——机械照抄得无消费者死代码 + 虚假保真；上游 `fillUpdates` 规则（scope §5.2 ⚠️）不随 P5 移植；false 路径（放置态邻居形状后处理剥离）随之不可达 |
| 58 | `/spawn mobcaps set` 子分支裁剪 | 写场 `SpawnReporter.mobcap_exponent` 的消费端全在 Mixin（`MobCategory_spawnMixin.java:21` + `NaturalSpawnerMixin.java:233`）——移植即得"打印成功实为 no-op"的说谎命令（P4 MAGIC_NUMBER=289 硬编码连显示都不变） |
| 59 | `/spawn` 数据面 = `SpawnReport`（P4 切片类 + P5 追加多行/实体清单）；rounds 段恒 `"w "` 空段 | 上游单类 `SpawnReporter` 的 B 侧承载——chunkcount 谱系随偏差 #52（SpawnState 公开 API）；`spawn_tries` 属 tracking 面（⛔ 未移植）→ rounds 恒 1，上游 :124-128 rounds==1 分支同位行为 |
| 60 | NMS 参数取值等价体族：`Vec3Argument.getVec3`/`BlockPosArgument.getSpawnablePos`/`BlockStateArgument.getBlock`/`BlockPredicateArgument.getBlockPredicate`/`DimensionArgument.getDimension`/`ResourceArgument.getSummonableEntityType` → `context.getArgument(name, Result.class)` 直取 | P2 #27 同判扩展：这些 vanilla 静态助手签名钉死 NMS 泛型上下文、paper 树泛型不可直调；javap 实证各助手体即 `getArgument` 直取（BlockStateArgument.getBlock = 单条 getArgument 指令；getBlockPredicate = getArgument(Result)——Result implements Predicate 直接可用） |
| 61 | `wander_chances` 照抄不加限（已知继承风险） | 上游同构同步重计算（主线程最坏 1000×36000 次 goal 评估——实体被困 1×1 场景分钟级停顿）；保真裁决 R5；上游 Fabric 同风险 |
| 62 | `CommandBuildContext` = `CommandBuildContext.simple(registryAccess, VANILLA_SET)` 懒加载单点（上游 = vanilla `Commands` 构建期注入同一实例） | paper lifecycle 注册面无 ctx 注入位；公开静态工厂（javap 实证签名）+ registryAccess 只读快照可安全缓存 |

## §21 P5 测试锚与实机判据

- `DistanceCalculatorTest`（4 用例，纯 JVM 直调 `findDistanceBetweenTwoPoints`）：3-4-5 三角锁三公式与四行结构（` - Spherical: 5.00`/` - Cylindrical: 5.00`/` - Manhattan: 7.0`）；纯垂直（cylindrical 归零）；float 域减法格式面；两点对称性。setStart/setEnd 状态机（NMS source 依赖）与命令树归实机。
- `CarpetRuleRegistryTest` 扩 18 规则锁（五条 P5 规则名/默认/类型 + CommandLevel OPTIONS 推断断言；方法名 `exactlyElevenRulesRegistered`→`exactlyEighteenRulesRegistered` 事实更名——原名自 13 规则期已陈旧）。
- **纯测不可行面如实声明**：PerimeterDiagnostics（ServerLevel/Mob 深依赖）、DrawCommand 几何（setBlock 依赖世界）、InfoCommand grep（依附 BlockInfo 行列表）→ 实机承载。
- **实机判据（phases §7）**：runServer 已验（2026-10-19）——`carpet list` 18 规则全列；`distance from 0 -60 0 to 100 -60 0` 三行输出逐字（含 Vec3Argument 整数居中 0.5——上游同构）；`spawn mobcaps` 八类别多行 + `spawn entities monster` 空表回执；`draw sphere 0 -60 0 3 minecraft:glass` → "Filled 98 blocks"（r=3 空心球壳正确）；`help perimeterinfo`/`help info` 树形（`[<center position>] [<mob>]`/`block <block position> [grep]`——CommandBuildContext + ResourceArgument 链路注册实证）；零 `[Carpet] failed to register` 日志。**用户侧终验**：`/info block` 属性清单对照上游、`/perimeterinfo` 周界报告（慢路径）、`/perimeterinfo <tab>` 补全全集（#55 硬判据）、可点击 tp 组件点击行为、`/spawn mobcaps` 与 `/log mobcaps` 数值交叉目检。

---

## §22 P7 三线铺开总览（2026-10-19 as-built）

把本线（dev/26.2）已验收的 Carpet 全模块（P1+P2+P3+P4+P5；P6 按用户裁决跳过）铺开到两条旧版本维护线，**每线一个原子 commit（代码 + 该线文档同车）**：

| 线 | commit | 交付物 | buildNumber | 服务端侧验收 |
|---|---|---|---|---|
| `ver/26.1.2-dev` | `0e278bc` | 61 文件（carpet 48 + 主类三触点 + plugin.yml + gradle.properties + 文档 11） | 7→8 | build 绿 + 168 单测 0 失败 + runServer 全判据（§24） |
| `ver/1.21.11-dev` | `71fe5b7` | 57 文件（同构；1.21.11 线自 `ver/26.1.2-dev` 版整体铺开——已含两项 26.x 回退面） | 5→6 | build 绿（Java 21）+ 125 单测 0 失败 + runServer 全判据（§24） |

- **铺开方法**：`git checkout <源线> -- mod/carpet 两目录`（只携带 carpet 文件，零源线演进污染）+ 编译驱动逐点适配（目标形态一律取上游该线树同位实现）+ 全判据实机验收——与仓库 ver 线手工落码惯例（jei 先例）同判。
- **分子系统等价性复核结论**（phases §9 预判的逐线复核，as-built 实证）：上游三树 `network/`+`settings/`+`logging/` **逐字节相同**（协议/规则/日志层零跨线漂移）；`commands/` 仅 PerimeterInfoCommand 差 `EntitySpawnRequest` 单点（26.2 独有）；`patches/` 仅 EntityPlayerMPFake 差 5 行（checkFallDamage override，26.x 独有）；`helpers/`（ActionPack）26.1↔1.21.11 差三处调用点（见偏差 #64）。**协议层唯一 per-line 值 = HI 版本串**：26.2=`"26.2"`、26.1 树=`"26.1"`、1.21.11 树=`"1.4.194"`（各线上游 `gradle.properties` 逐字；`CarpetHelloFrameTest` 字面锁随线同步）。
- **P3 协议随行判定实证**：carpet 客户端对各线均要求 HI 值等于其自身 mod_version——26.1 线客户端 / 1.21.11 线 carpet 1.4.194 客户端各验（用户侧终验清单，§24）。
- **文档承载**：26.1.2 线 = `docs/31-carpet.md`（编号沿该线 NN- 体系）+ `40-configuration.md` 增补 carpet 节；1.21.11 线 = `docs/carpet.md` **自含含运维节**（该线 2026-10 起新文档主题命名，沿 jei.md 先例；且无独立运维文档）。两线源码 javadoc 的 32 处 `docs/carpet-*.md §X` 锚点全部重指各线自含文档对应节。

## §23 P7 per-line 适配矩阵与偏差清单（续 §9/§14/§17/§20 编号）

**实测适配计数**（相对本线 as-built 的回退/改写面）：26.1.2 线 = **6 处**（EntityTypes 回并 ×2 + EntitySpawnRequest 回退 + HI 常量与字面锁 + 谱系/锚点族）；1.21.11 线 = 26.1.2 版基础上**再 3 处**（ActionPack :347/:353 签名 + jump 分支删行）+ HI=`"1.4.194"`。其余预测漂移面（CompoundTag Optional 族 25 处/10 文件、ResourceKey 访问器、`random→getRandom`、`sendSystemMessage`、`getAverageTickTimeNanos`、判级链）**实测零漂移**——1.21.11 的 `Identifier`/`CompoundTag` 使用面/Paper Brigadier/`isClientAuthoritative`/`doTick`/`teleport(PositionMoveRotation)` 等 API 均已具备（编译驱动实证）。

| # | 偏差 | 理由 |
|---|---|---|
| 63 | **掉落伤害 per-line 双机制**：26.2 线（dev）= tick 手动累积 fallDistance + 落地直调 `causeFallDamage`（§14 #37 的 `tickFallDamageDrive` 重构件）；26.1.2 与 1.21.11 线 = `EntityPlayerMPFake` 覆写 `isClientAuthoritative()` 返回 false，删手动驱动 | **Paper 玩家物理路径 per-line 根本不同**（勿跨线互抄）：26.2 上服务端玩家物理被 Paper 关闭（#37 实测）；26.1.2/1.21.11 上物理**敞开**（假人重力/行走全物理真实，实测 4.3 m/s 步行）但 `Entity.move` 的 `isLocalInstanceAuthoritative()` 门控（`Player.isClientAuthoritative()` 恒 true）吞掉 vanilla `checkFallDamage` 链。覆写该位为 false（假人无客户端，服务端实例即移动权威——语义为真）恢复全 vanilla 掉落语义，比手动直调更保真（含干草块/蜘蛛网等特殊 fallOn 方块）。**根因链**：26.x 的玩家 tick 入口 = `SGPL.tickPlayer → doTick()`（invokespecial 直跳 `Player.tick`）——子类 `tick()` 覆写在 26.1.2 上不可达，26.2 的手动驱动在旧线沦为旁观者（编译与常规判据均不可见的静默失效）。上游 26.1/1.21.11 树的 5 行 `checkFallDamage → doCheckFallDamage` 桥接在 Paper 门控形态下不可达（桥接目标同样被挡），不采用。**排错口径**：创造假人免伤与 PEACEFUL 拒绝难度缩放伤害均系 vanilla 语义（`/player spawn` 控制台默认创造——测伤害须 `in survival` 全链；先查 `/difficulty`） |
| 64 | 1.21.11 线 ActionPack 三调用点（`entity.interactAt(player, hitPos, hand)` / `player.interactOn(entity, hand)` 两参 / jump 分支无 `tryToStartFallFlying`） | 上游 1.21.11 树同位形态（26.x 才改签名/加行）；`EntityTypes` 回并 + `EntitySpawnRequest` 回退同判（26.2 独有面） |
| 65 | 两 ver 线 javadoc 锚点/谱系 per-line 重指（32 处 `docs/carpet-*.md §X` → 各线自含文档节；`fabric-carpet-26.2` → 各线上游树） | ver 线 docs 为各自体系且无 carpet 文档（悬空锚点/失真谱系）；版本 grep 门禁合规（1.21.11 线 javadoc 版本串先例 = 该线 10 文件既有形态） |
| 66 | ver 线文档承载 per-line（26.1.2 = `31-carpet.md` 编号 + `40-configuration.md` 增补；1.21.11 = `carpet.md` 主题命名自含**含运维节**） | 各线自身 AGENTS.md 约定（编号体系 vs 主题命名先例 jei.md；1.21.11 线无运维全键文档）；跨分支引用不可达 → per-line 文档必须自含 |

## §24 P7 测试锚与实机判据（两 ver 线，2026-10-19）

- **单测随行**：carpet 7 类原样迁入两线（零用例改动；仅 `CarpetHelloFrameTest` 版本字面锁与两个 SettingsManager 构造 fixture 串随线替换）。26.1.2 线 = 27 测试类 168 单测 0 失败（CarpetLoggerTest 一次 `@TempDir` Windows 清理 IOException flake，单类重跑 18/18 + 全量复跑 168/0/0——非逻辑失败）；1.21.11 线 = 22 测试类 125 单测 0 失败。
- **runServer 服务端侧判据（两线同集，均过）**：四模块注册 + `Done` 无 ERROR/SEVERE；`/carpet list` 18 规则全列；`setDefault customMOTD` → conf 落盘 → `removeDefault` 往返干净；假人 spawn 全链（`at <pos> facing <rot> in <dim> in <gamemode>`）→ `/list` 占位 → `/tp` 生效 → `kill` 完整退出链（PlayerQuitEvent + playerdata 存档）；`/log` 三入口 console 全部 "For players only"；`/distance from 0 -60 0 to 100 -60 0` 三行（100.00/100.00/100.0）；`/spawn mobcaps` 八类别多行；`/draw sphere r3` → "Filled 98 blocks"；**掉落差分判据（P7 新增，#63 的行为面）**：survival 假人自 111.5 坠 40 格 → 死亡（vanilla 公式 40-3=37 ≥ 20——两线均实证）；26.1.2 线另验 `move forward` 4.3 m/s 步行精确与 18 规则 `query` 详情。
- **构建终检**：两线 `./gradlew build` 全绿 + `verifyVersionInjection` 过（`VeryMcProto-26.1.2-b8.jar` / `VeryMcProto-1.21.11-b6(-reobf).jar`——1.21.11 线保留 reobf 装配）。工具链：1.21.11 线 Java 21（`GRADLE_USER_HOME` installations 需含 zulu21 路径——本机 `D:\tmp\gradle\gradle.properties` 已配）。
- **用户侧终验清单（未执行，留用户——两线各需对应版本 carpet 客户端）**：① 进服无 chat 可见/断连级版本警告（客户端本地 `LOG.warn` 属上游 equals 语义同源产物）② 规则浏览器显示 18 规则集（值随 `/carpet setDefault` 即时变更）③ `clientCommand` 请求-回执往返（`/call`）④ HUD tab 脚显示 tps/mobcaps ⑤ `/perimeterinfo <tab>` 补全全集 + 可点击 tp 组件。
- **发版语义**：本次 buildNumber 递增（8/6）为铺开交付构建号；合入 `ver/26.1.2` / `ver/1.21.11` 发布线 + tag 属后续发版动作（分支模型 SOP），未自动执行。
