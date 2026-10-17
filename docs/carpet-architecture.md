# Carpet 模块 as-built（Phase 1 模块地基）

> **状态**：Phase 1 已实现（2026-10-17，dev 线）。范围判定唯一权威 = [carpet-porting-scope.md](carpet-porting-scope.md)（scope）；推进状态 = [carpet-phases.md](carpet-phases.md) §11；命令/权限/配置运维键 = [operations.md](operations.md) §1.6/§2.1/§3.4。本文承载 as-built 细节与**全量偏差清单**（上游对照树 = `OriginImpl/fabric-carpet-26.2/`，tag `v26.2`）。
>
> 上游本质是**行为注入型** mod（171 服务端 Mixin）——与既有三 mod（协议投递型）根本不同；本模块只移植 scope 判定为可完整等价的子集，P1 = 模块地基（规则框架 + `/carpet` 壳 + 8 条地基规则）。

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
| 3 | 命令面解耦到 Bukkit `CarpetCommand`（上游内嵌 brigadier 树） | Paper 无 brigadier 注册面；plugin.yml + CommandExecutor 为仓库既定范式 |
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
- P2（假人）/P4（log）/P5（命令族）复用本框架：规则表新增即 `CarpetSettings` 加注解字段；`/carpet` 面无需改动。
