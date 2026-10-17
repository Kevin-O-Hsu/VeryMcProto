# Carpet 模块 as-built（Phase 1 模块地基 + Phase 2 假人子系统）

> **状态**：Phase 1 已实现（2026-10-17，dev 线）；**Phase 2「假人——/player 全族」已实现（2026-10-18，dev 线，§11-§14）**。范围判定唯一权威 = [carpet-porting-scope.md](carpet-porting-scope.md)（scope）；推进状态 = [carpet-phases.md](carpet-phases.md) §11；命令/权限/配置运维键 = [operations.md](operations.md) §1.6-§1.7/§2.1/§3.4。本文承载 as-built 细节与**全量偏差清单**（上游对照树 = `OriginImpl/fabric-carpet-26.2/`，tag `v26.2`）。
>
> 上游本质是**行为注入型** mod（171 服务端 Mixin）——与既有三 mod（协议投递型）根本不同；本模块只移植 scope 判定为可完整等价的子集，P1 = 模块地基（规则框架 + `/carpet` 壳 + 8 条地基规则），P2 = 假人子系统（patches 三件套 + ActionPack + `/player` 全族 + 3 条假人规则）。

---

## §1 模块装配

- `app/CarpetModule implements ModModule`（仓库第二个 ModModule 实现，黄金模板 `ServuxModule`）：`getModId()/getModString()` 返 `CarpetReference` 常量；`onRegister(DataProviderManager)` **忽略 manager 形参**（carpet 上游是 SettingsManager 体系，无 Provider 概念——诚实降级）。
- 装配链：`SettingsManager` 单例（构造注入 `Reference.plugin().getDataFolder()`）→ `parseSettingsClass(CarpetSettings.class)` → `loadConfigurationFromConf()` → 行为监听器注册（`CarpetRuleListener`，`Bukkit.getPluginManager().registerEvents`）→ `CleanLogsFilter.install()`（幂等）。
- 主类接线：`VeryMcProto.onEnable` 第四 try-catch 模块块 + `/carpet` 注册块（既有同构形态）；`onDisable` 调 `CarpetModule.disable()`（摘除 log4j 过滤器——防插件 reload 类加载器泄漏，见 §7）。
- **即时生效构造**：行为规则监听器常驻注册、事件发生时读当前规则值——`setDefault` 的 live set 由构造保证即时生效，无 observer 热切换接线。

## §2 规则框架（`settings/`，上游 `api/settings/SettingsManager.java` 集群同构）

| B 侧类 | 上游对照 | 说明 |
|---|---|---|
| `Rule`（注解） | `carpet/settings/Rule.java`（legacy——上游 `CarpetSettings` 实际使用者） | 七元素 name/desc/extra/category/options/strict/validate 逐字；删 `appSource`/`condition`（§9） |
| `Validator` | `carpet/api/settings/Validator.java` + legacy 嵌套类 | source 类型换 `CommandSender`；内置 `_COMMAND`（值变更→全服 `Player#updateCommands()` 重发命令树）/`StrictValidator`（索引 0 前置）/`CommandLevel`（OPTIONS 常量） |
| `ParsedRule` | `carpet/settings/ParsedRule.java` | 反射解析：类型换壳（primitive→wrapper）、boolean→options `[true,false]`、String+COMMAND 类目→`CommandLevel.OPTIONS` 推断、COMMAND 类目自动挂 `_COMMAND`（+ String 型加 `CommandLevel`）、strict+options→`StrictValidator`@0；`set()` 验证器链 + 上游写场条件 `!value.equals(value()) || source == null`（命令路径 no-op 免写免通知；conf 载入恒写） |
| `SettingsManager` | `carpet/api/settings/SettingsManager.java` | 见 §3/§4；`notifyRuleChanged` 为空体扩展点（P3 Rules 全量同步挂载位，phases §5 交付物 3） |
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

- `SettingsManager.notifyRuleChanged(source, rule, userInput)`：空扩展点——P3 `carpet:hello` Rules 全量同步的挂载位（phases §5 交付物 3，数据源 = 本框架）。
- `CarpetReference.CARPET_CHANNEL/KEY_HI/KEY_HELLO`：P1 仅常量占位（上游 `CarpetClient.java:40-48` 逐字），P3 接线；**P3 HI 帧版本串 = `CARPET_VERSION`（上游 mod_version 逐字，禁发插件版本号**——`26.2-b3` 必触发客户端 `equals` 严格比较 warn，phases §5 裁决；ver 线 per-line 取值如 1.21.11 = `1.4.194`）。
- P2（假人）已按此接入（`CarpetSettings` 加 3 注解字段即自动注册）；P4（log）/P5（命令族）复用同路径。

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
- `CarpetRuleRegistryTest` 扩 11 规则锁（3 假人规则默认值/类型/选项域 + commandPlayer CommandLevel 推断）。
- 实机矩阵（phases §4 判据）：见 §11 实证清单。
