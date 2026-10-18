# 运维参考（命令 / 权限 / 配置全键 / 数据布局 / 排错）+ 版本升级 SOP

> **本文是 docs/ 内运维信息的唯一权威**（英文 README 不再承载这些表格）：四 mod 全命令参考、权限节点（命令 + Provider 运行时）、四个配置文件全键表（含 `carpet.conf` 纯文本例外）、磁盘数据布局、调试系统与排错速查（§1–§5），以及 **MC 版本升级 SOP 与观察项**（§6）。
>
> - **键权威 = src 配置类**：`servux.json` 各 Provider settings 声明（`ConfigProvider` / `HudDataProvider` / … / `StructureDataProvider.java:83` 等）、`jei.json` = `mod/jei/config/JeiConfiguration.java`、`syncmatica-config.json` = `service/QuotaService.java` + `service/DebugService.java` + `util/SyncmaticaDebug.java`、`carpet.conf` = `mod/carpet/settings/SettingsManager.java` + `mod/carpet/CarpetSettings.java`（13 规则声明 = P1 地基 8 + P2 假人 3 + P4 日志 2）。**改键必须同步本文**（仓库"文档同步强制"约定）。
> - **三载体边界**：本文 = docs 内运维全键唯一权威；根 [`AGENTS.md`](../AGENTS.md) §5 权限表 = AI 上下文自包含安全网（豁免声明）；[`servux-providers.md`](servux-providers.md) = 设置语义视角（讲每个设置的含义与采集联动，不维护节点全表）。
> - 命令/权限的**语义与上游对照**见各 mod 协议文档（[`servux-protocol.md`](servux-protocol.md) / [`servux-providers.md`](servux-providers.md) / [`syncmatica-protocol.md`](syncmatica-protocol.md) / [`jei.md`](jei.md)）。

---

## 1. 命令参考

### 1.1 `/servux`

**权限**：根节点 `servux.commands`（default: op）+ 每子命令 `servux.commands.<sub>`；扩展子命令（enable/disable/debug/litematic）有独立节点；旧单节点 `servux.command` 经 plugin.yml children 映射自动继承整棵新树（旧授权不受影响）。

```
/servux                                          显示用法
/servux list [provider]                          列出全部 settings 现值（上游 configList 形态；值短于 10 字符行内显示）；可选 provider 过滤
/servux info <provider:setting|setting>          查看某 setting 现值 + 默认值
/servux set <provider:setting|setting> <value>   修改 setting（纯内存——持久化需 /servux save，上游语义）；非法值显式拒绝（§cInvalid value，校验与报错对齐上游 :309-312）；value 可含空格（整段重组为单值，对齐上游 greedyString 取值形态）
/servux enable <provider>                        启用 provider（如 hud_data）——扩展子命令，即时落盘
/servux disable <provider>                       停用 provider——扩展子命令，即时落盘
/servux search <keyword>                         搜索 setting（空格分词 AND；命中名/注释/provider 名任一；大小写敏感；零命中回「无匹配的设置: <关键词>」（上游 search.none 形态），非空先报计数与关键词再列条目（search.results 形态））
/servux reload                                   从 servux.json 重读配置
/servux save                                     当前配置写入 servux.json
/servux debug ...                                调试开关（见 §1.2）
/servux litematic ...                            投影文件管理（见 §1.3）
```

- setting 的**全名** = `<provider 逻辑名>:<setting 名>`，如 `hud_data:share_seed`、`servux_main:permission_level`；无歧义时可省略 provider 前缀。
- Provider 逻辑名见 [`servux-protocol.md`](servux-protocol.md) §2 通道总表：`servux_main` / `hud_data` / `entity_data` / `tweaks_data` / `structure_bounding_boxes` / `litematic_data`。
- `servux_main`（配置主通道）**永不可停用**；其余 5 个可 `enable`/`disable`。

### 1.2 `/servux debug` —— 调试日志热切换（即时生效 + 即时持久化到 servux.json）

```
/servux debug                  查看当前调试状态
/servux debug on|off           master 总开关（只管输出死活，不碰分类）
/servux debug status           查看状态
/servux debug cat all|none     全开 / 清空全部分类
/servux debug cat <name>       切换单个分类
```

> **master 与分类是两个正交维度，两者皆开才输出。** 分类共 **10 值**（源 `mod/servux/ServuxDebug.java` `Cat` 枚举）：`lifecycle` `handshake` `network` `packet` `tick` `permission` `provider` `config` `easyplace` `schematic`。

### 1.3 `/servux litematic` —— 服务端投影文件管理（需 `litematic_data` provider 启用）

```
/servux litematic list                          列出 schematics/ 下的 .litematic 文件
```

> S2C 文件投递（transmit）已于 2026-09 **物理删除**（stock 客户端无接收端；详见 [`servux-protocol.md`](servux-protocol.md) §8.5）。文件位于 `plugins/VeryMcProto/schematics/`。

### 1.4 `/syncmatica`

**权限**：`syncmatica.command`（default: **true**，全员可用基础命令）。

```
/syncmatica                                     显示用法
/syncmatica status                              模块状态（协议开关 + debug + 配置文件）           [admin]
/syncmatica save                                配置写入 syncmatica-config.json                  [admin]
/syncmatica reload                              从 syncmatica-config.json 重读                   [admin]
/syncmatica enable                              启用协议（在线玩家重新握手）                       [admin]
/syncmatica disable                             软禁用协议（通道保留、不踢人）                     [admin]
/syncmatica load                                把 syncmatics/ 下未注册的 .litematic 全部注册     [load]
/syncmatica load <file>                         注册单个 .litematic 为 placement                 [load + load_each]
/syncmatica debug ...                           调试开关（见下）                                  [debug]
```

> 投影的**上传 / 下载 / 修改 / 删除全部走协议 Exchange**（客户端侧操作）；命令只负责把本地文件注册为 placement 并广播。`[admin]` 需 `syncmatica.command.admin`，`[load]` 需 `syncmatica.command.load`，`[load_each]` 需 `syncmatica.command.load_each`，`[debug]` 需 `syncmatica.command.debug`。
>
> **load 广播语义**（对齐上游 `sendSuccess(..., true)`）：控制台执行 `load` 同样对在线已握手客户端即时广播 REGISTER_METADATA；load 成功/计数消息广播给持 `syncmatica.command.admin` 权限的全体玩家与控制台（≈ vanilla OP 广播位），执行者若不持该权限则补直发保证回执；"No file"/"Failed to peek" 类提示仍仅回执行者。

**`/syncmatica debug`**（独立于 `/servux debug` 的 `SyncmaticaDebug` 引擎）：

```
/syncmatica debug                  查看状态
/syncmatica debug on|off           master 总开关
/syncmatica debug status           查看状态
/syncmatica debug cat all|none     全开 / 清空全部分类
/syncmatica debug cat <name>       切换单个分类（lifecycle handshake network packet exchange data）
/syncmatica debug s2c              查看当前 S2C 发送路径
/syncmatica debug s2c nms|msg      切换 S2C 路径（NMS 直发 / plugin messaging）——诊断开关，不持久化
```

> 分类共 **6 值**。S2C 默认 **NMS `DiscardedPayload` 直发**（实测 plugin messaging wire 对纯 Fabric syncmatica 客户端不可达，详见 [`syncmatica-architecture.md`](syncmatica-architecture.md) §4.3）；`/syncmatica debug s2c msg` 临时切回 plugin messaging 作对照排错。

### 1.5 `/jei`

**权限**：`jei.command`（default: op）。

```
/jei                （或 /jei status）  查看模块 + cheat 三开关状态
/jei enable         启用模块（此后进服的玩家获得配方同步 + jei:* 交互）
/jei disable        停用模块（通道保持注册——不踢人；在途 C2S 静默丢弃）
```

> **生效范围**：只影响**之后**的交互。disable 时**从不注销通道**——注销会使后续客户端包命中未注册通道而被踢。cheat 三开关在 `jei.json`（文件管理，同上游 `jei-server.properties`；无命令切换）。

### 1.6 `/carpet`

**权限**：`carpet.command`（default: op）+ 运行时**动态规则门禁**（`carpetCommandPermissionLevel` 规则——`ops`/`2`/`4` 经 NMS PermissionSet 精确判级，上游 root requires 同构；`locked` 时全部子命令拒绝）。

```
/carpet                                          版本行 + 规则计数 + 用法
/carpet list                                     全量规则（按名排序，含现值与 default/modified 标记）
/carpet query <rule>                             规则详情（desc/extra/类目/现值/默认/选项域/验证器说明）
/carpet setDefault <rule> <value>                设规则默认值：即时生效 + 持久化 carpet.conf（value 可含空格）
/carpet removeDefault <rule>                     移除 conf 覆盖并复位默认值
```

> 上游 `/carpet <rule> [value]` 会话级 set 与 `list defaults` / `list <tag>` 子面未移植（B 侧四子命令面，偏差清单见 [`carpet-architecture.md`](carpet-architecture.md)）；规则全表与各键行为面见 §3.4。

### 1.7 `/player`（Carpet 假人，Phase 2）

**权限**：无 Bukkit 权限节点（plugin.yml 无条目）——命令经 Paper Brigadier API 注册，门禁 = **`commandPlayer` 规则**（`ops`/`true`/`false`/`0`-`4`，requires 每次执行求值 → 规则动态生效，上游同构）。非 op 玩家对**他人真实玩家**的操控被拒（cantManipulate 上游同款）；kill/move 仅限假人。

```
/player <name> spawn [at <pos> [facing <rot> [in <dim> [in <gamemode>]]]] [in <gamemode>]
    生成假人（默认位置/朝向/维度 = 发送者上下文；gamemode 节点需 op 2；离线名受 allowSpawningOfflinePlayers 门禁）
/player <name> kill                                移除假人（完整退出链：PlayerQuitEvent + playerdata 存档）
/player <name> shadow                              影子化真实在线玩家（原玩家被顶号断开，假人接管其档案）
/player <name> stop                                停止全部动作并复位移动输入
/player <name> mount [anything]                    骑乘周界 3×1×3 载具（anything = 任意实体）
/player <name> dismount                            下载具
/player <name> sneak|unsneak|sprint|unsprint       潜行/疾跑开关（互斥语义同上游）
/player <name> look north|south|east|west|up|down|at <pos>|<rot>
/player <name> turn left|right|back|<rot>
/player <name> move [forward|backward|left|right]  裸 move = 停止移动；子向 = 持续移动（实测定速 5.6 m/s 疾跑）
/player <name> use|jump|attack [once|continuous|interval <ticks>]
/player <name> drop|dropStack [all|mainhand|offhand|<slot 0-40>] [once|continuous|interval <ticks>]
/player <name> swapHands [once|continuous|interval <ticks>]
/player <name> hotbar <1-9>                        切换主手栏位
```

运维要点（as-built 见 [`carpet-architecture.md`](carpet-architecture.md)）：
- **假人占 tab 栏 / 计入睡比例跳夜（实测）/ playerdata 持久（重启后再 spawn 读回位置背包）/ 可被 `/tp` `/ban` 原版命令识别**——全部由 vanilla 进服/退出链原生承载。
- `/ban` 只入封禁列表**不移除在线假人**（上游同语义——假人不响应 disconnect；移除走 `/player kill`）；被封名单再 spawn 会被 cantSpawn 拒绝。
- **插件卸载/reload 自动 kill 全部假人**（防 NMS 对象引用已卸载类 → NoClassDefFoundError）。
- `use` 对床 = 假人睡下（白天/床边有怪会被 vanilla 拒绝，拒绝消息经假人 no-op 连接不可见——同上游）。
- 已知缺口：`/tick freeze` 不冻结假人（Paper 无注入点，唯一功能缺口）；击退保真经 `PlayerVelocityEvent` 取消近似（伤害同 tick 内第三方 `setVelocity` 的残余误伤窗口 ≤1 tick）。掉落与环境接触伤害（甜浆果丛/仙人掌/凋零玫瑰）已按 vanilla 语义修复（2026-10-18：甜浆果静止不伤/走动伤——vanilla 位移条件；蛛网减速不在列）；生存假人受 `/damage` 与战斗伤害正常。

### 1.8 `/log`（Carpet 日志订阅，Phase 4）

**权限**：无 Bukkit 节点——Paper Brigadier 注册（/player 先例），门禁 = `commandLog` 规则（默认 `true` 全员；ops/true/false/0-4 经 NMS 判级，requires 每次执行求值动态生效）。全部子命令**仅玩家可用**（console 统一回执 `For players only`）。

```
/log                                   订阅状态列表（各 logger 订阅态 + option 徽标 + 用法行）
/log subscribe <logger> [option]       订阅（option 缺省 = 该 logger 默认值；重复订阅覆写 option）
/log clear                             退订全部
/log clear <logger>                    单项退订（未知 logger 回执拒绝）
```

可用 logger（Phase 4 注册面）：`tps`（无 option——tab 脚 HUD 显示 TPS/MSPT 热力色行）、`mobcaps`（option = `dynamic`/`overworld`/`nether`/`end`——HUD 显示对应维度生物容量行）。HUD 投递 = tab 脚（每 20 tick 一帧，**只写脚不碰头**）；**不走 carpet:hello 通道**——未握手的 vanilla 客户端同样收到（carpet 客户端把 tab 脚画成 HUD 面板）。

运维要点（as-built 见 [`carpet-architecture.md`](carpet-architecture.md) §16-§18）：
- **订阅跨会话持久**（`carpet-logs.json`，§3.5）：显式订阅 + 退订墓碑双表；`defaultLoggers` 规则的默认订阅**永不落盘**——每次进服按当前规则值对「未订阅且未显式退订」的 logger 重评估（管理员改默认对未显式选择的玩家下次进服即生效；显式退订不被默认复活）。
- 假人整体豁免 /log 子系统（不施默认、不入册、不持久化）。
- **已知限制**：外部写 tab 脚的插件（TAB 类）会与本插件每秒互覆写（上游 carpet 同源行为）；tab 头零触碰（他插件 header 不受影响）。

---

## 2. 权限节点

### 2.1 命令权限（plugin.yml 声明）

| 节点 | default | 用途 |
|---|---|---|
| `servux.commands` | op | `/servux` 根节点（上游根 requires level 4 的 Bukkit 近似） |
| `servux.commands.reload` / `.save` / `.set` / `.info` / `.list` | op | 上游六子命令各自节点（`search` 复用 `.list`） |
| `servux.commands.enable` / `.disable` / `.debug` / `.litematic` | op | 我方扩展子命令节点（无上游对应） |
| `servux.command` | op | 旧版单节点（兼容保留：plugin.yml children 映射自动继承新树） |
| `jei.command` | op | `/jei status\|enable\|disable` |
| `syncmatica.command` | true | `/syncmatica` 基础命令（含 `load`） |
| `syncmatica.command.admin` | op | `/syncmatica save\|reload\|enable\|disable\|status` |
| `syncmatica.command.load` | true | `/syncmatica load`（批量注册） |
| `syncmatica.command.load_each` | true | `/syncmatica load <file>`（单个注册） |
| `syncmatica.command.debug` | op | `/syncmatica debug` |
| `carpet.command` | op | `/carpet` 规则壳（运行时另受 `carpetCommandPermissionLevel` 规则动态门禁——ops/2/4 级 NMS 判级） |
| —（无节点） | — | `/player` 假人命令**无 Bukkit 节点**——Paper Brigadier 注册，门禁 = `commandPlayer` 规则（ops/true/false/0-4，§1.7） |
| —（无节点） | — | `/log` 日志命令**无 Bukkit 节点**——Paper Brigadier 注册，门禁 = `commandLog` 规则（默认 true 全员；ops/true/false/0-4，§1.8） |

### 2.2 Provider 运行时权限（Perms 语义）

Servux Provider 权限不走 Bukkit permission `default`，由 `framework.permission.Perms` 按 **`permission_level` setting + op 等级**运行时判定。**`Perms.check(player, node, level)` 语义**：

1. 玩家被**显式授予/拒绝**该 Bukkit 权限节点（`isPermissionSet`，如 LuckPerms / permission attachment 设置）→ **以该结果为准**；
2. 否则 `level <= 0` → **全员放行**；
3. 否则回退 **op 二值**（`isOp()` 为 true 即通过，满足一切 `level >= 1` 的管理类设置）。

> 即 `permission_level` 实际只区分"0 = 全员 / ≥1 = 仅 OP"。**要区分等级（如放行 2 不放行 3），用 LuckPerms 显式授予对应节点。**

基础节点形如 `servux.provider.<provider 逻辑名>`，细化节点在其上追加后缀：

| 节点 | 管辖 setting（等级来源） | 控制什么 |
|---|---|---|
| `servux.main.admin` | `servux_main:permission_level_admin`（默认 3） | ConfigProvider 管理操作 |
| `servux.main.easy_place` | `servux_main:permission_level_easy_place`（默认 0） | EasyPlace 放置 |
| `servux.provider.hud_data` | `hud_data:permission_level`（默认 0） | HUD 元数据投递 |
| `servux.provider.hud_data.weather` | `hud_data:weather_permission_level`（默认 0） | 天气数据 |
| `servux.provider.hud_data.seed` | `hud_data:seed_permission_level`（默认 2） | 种子数据 |
| `servux.provider.hud_data.logger` | `hud_data:logger_permission_level`（默认 0） | Loggers 总开关 |
| `servux.provider.hud_data.logger.tps` | `hud_data:logger_permission_level` | TPS logger |
| `servux.provider.hud_data.logger.mob_caps` | `hud_data:logger_permission_level` | MobCap logger |
| `servux.provider.entity_data` | `entity_data:permission_level`（默认 0） | Entities 基础 |
| `servux.provider.entity_data.nbt_query_override` | `entity_data:nbt_query_permission_level`（默认 2） | NBT 查询独立权限 |
| `servux.provider.entity_data.nbt_allow_player_inventory` | `entity_data:player_inventory_permission_level`（默认 2） | 查询玩家背包 |
| `servux.provider.entity_data.nbt_allow_player_ender_items` | `entity_data:player_ender_items_permission_level`（默认 2） | 查询玩家末影箱 |
| `servux.provider.tweaks_data` | `tweaks_data:permission_level`（默认 0） | Tweaks 基础 |
| `servux.provider.structure_bounding_boxes` | `structure_bounding_boxes:permission_level`（默认 0） | Structures 基础 |
| `servux.provider.litematic_data` | `litematic_data:permission_level`（默认 0） | Litematics 基础（上传/粘贴） |
| `servux.provider.litematic_data.paste` | `litematic_data:permission_level_paste`（默认 0） | **粘贴**投影到世界（另需创造模式） |
| `servux.provider.litematic_data.task.fill` | `litematic_data:permission_level_tasks`（默认 0） | Fill task 受理（须同时过基础节点 + 创造模式） |
| `servux.provider.litematic_data.task.delete` | `litematic_data:permission_level_tasks`（默认 0） | Delete task 受理（须同时过基础节点 + 创造模式） |

> `nbt_query_override` 关闭时，Entities NBT 查询**回退原版权限 `minecraft.command.data`**（等级 2），与原版 `/data` 命令权限对齐。

### 2.3 LuckPerms 示例

放开种子查看（默认仅 OP）：

```yaml
commands:
  - "lp user <player> permission set servux.provider.hud_data.seed true"
```

给非 OP 玩家粘贴权限：

```yaml
commands:
  - "lp user <player> permission set servux.provider.litematic_data.paste true"
```

---

## 3. 配置文件（JSON：Gson pretty + 原子 tmp/move 写；例外 `carpet.conf` 为上游同构纯文本）

目录 `plugins/VeryMcProto/`。

### 3.1 `servux.json`

按 Provider 分段，每段承载该 Provider 的 settings。**每个键都可用 `/servux set <provider:key> <value>` 修改**（免手编）。int 范围记法 `[min..max] default`。

`set` 的值语义：非法值（越界 int、非布尔字面、strict 串不在候选、List JSON 元素类型错或畸形）一律显式拒绝回 `Invalid value`，不再静默伪成功；值可含空格——有类型校验的 setting（bool/int/strict string/list）多余 token 会使整值校验失败被拒绝，自由字符串 setting（`default_language`，唯一非 strict 串）整段并入值。校验与报错语义对齐上游 `ServuxCommand:309-312`，但不与上游完全对齐（上游缺 value 参时回退默认值的行为我方不实现——缺参显示用法）。

顶层另有 **`DataProviderToggles`** 段——6 个布尔键 = Provider 逻辑名，即各 Provider 的启用开关；`/servux enable|disable <provider>` 即时写入此段（`servux_main` 恒启用、永不可停用——`/servux disable servux_main` 被命令层拒绝、配置加载期亦强制启用）：

```jsonc
"DataProviderToggles": {
  "servux_main": true,               // 恒 true（ALWAYS_ENABLED，永不可停用）
  "hud_data": true,
  "entity_data": true,
  "tweaks_data": true,
  "structure_bounding_boxes": true,
  "litematic_data": true
}
```

#### `servux_main`（`servux:main`）

配置主通道——永不可停用、不下发网络包（仅承载全局 settings）。

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `permission_level` | int [0..4] | 0 | 基础权限等级（0 = 全员） |
| `permission_level_admin` | int [0..4] | 3 | 管理操作权限等级 |
| `permission_level_easy_place` | int [0..4] | 0 | EasyPlace 权限等级 |
| `easy_place_validator_enabled` | bool | true | EasyPlace 放置校验器（仅控制 `PlacementHandler` 内部的 canSurvive 中间/终态校验回退；**修正态 canSurvive + 实体碰撞的 canBuild 拒绝判定恒生效**——关闭时我方比上游严，上游 OFF 会连 vanilla 基座检查一起旁路放行非法态，我方仍拒绝） |
| `default_language` | string | `en_us` | 默认语言 |
| `debug_log` | bool | false | 调试 master 总开关 |
| `debug_categories` | string[] | `[]` | 已启用的调试分类（与 `debug_log` 正交） |

#### `hud_data`（`servux:hud_metadata`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `permission_level` | int [0..4] | 0 | HUD 基础权限 |
| `update_interval` | int [20..300] | 40 | HUD 推送间隔（tick） |
| `share_weather_status` | bool | false | 是否投递天气 |
| `weather_permission_level` | int [0..4] | 0 | 天气数据权限 |
| `share_seed` | bool | false | 是否投递世界种子 |
| `seed_permission_level` | int [0..4] | 2 | 种子数据权限 |
| `loggers_enabled` | bool | false | 是否启用 loggers（TPS/MobCap 周期数据） |
| `loggers_enable_list` | string[] | `["tps","mob_caps"]` | 启用的 logger 类型 |
| `logger_permission_level` | int [0..4] | 0 | Loggers 数据权限 |

#### `entity_data`（`servux:entity_data`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `permission_level` | int [0..4] | 0 | 基础权限 |
| `nbt_query_override` | bool | false | 启用独立 NBT 查询权限（否则回退 `minecraft.command.data`） |
| `nbt_query_permission_level` | int [0..4] | 2 | NBT 查询权限 |
| `fix_allay_gathering` | bool | true | 修复 Allay 收集 NBT |
| `nbt_allow_player_inventory` | bool | true | 允许查询玩家背包 |
| `nbt_allow_player_ender_items` | bool | true | 允许查询玩家末影箱 |
| `player_inventory_permission_level` | int [0..4] | 2 | 背包查询权限 |
| `player_ender_items_permission_level` | int [0..4] | 2 | 末影箱查询权限 |

#### `tweaks_data`（`servux:tweaks`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `permission_level` | int [0..4] | 0 | 基础权限 |
| `update_interval` | int [40..1200] | 120 | 推送间隔（tick） |

> ⛔ 原 `stackable_shulkers` / `stackable_shulkers_count` / `stackable_shulkers_fix` **已删除**——潜影盒堆叠在无 Mixin 的 Paper 上不可能实现（见 [`architecture.md`](architecture.md) §5.3）；保留会令客户端 Tweakeroo 开堆叠渲染而服务端不配合 → 不一致。

#### `structure_bounding_boxes`（`servux:structures`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `permission_level` | int [0..4] | 0 | 基础权限 |
| `structures_blacklist_enabled` | bool | false | 启用结构黑名单 |
| `structures_whitelist_enabled` | bool | false | 启用结构白名单 |
| `structures_blacklist` | string[] | `["minecraft:buried_treasure"]` | 黑名单结构 ID |
| `structures_whitelist` | string[] | `[]` | 白名单结构 ID |
| `update_interval` | int [1..1200] | 40 | 扫描间隔（tick） |
| `timeout` | int [40..1200] | 600 | 结构扫描超时（tick） |

#### `litematic_data`（`servux:litematics`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `permission_level` | int [0..4] | 0 | 基础权限（上传/粘贴） |
| `permission_level_paste` | int [0..4] | 0 | 粘贴到世界的权限 |
| `permission_level_tasks` | int [0..4] | 0 | task 组（Fill/Delete）受理权限等级（节点 `.task.fill` / `.task.delete`，见 §2.2） |
| `player_task_feedback` | bool | false | task 完成/中断聊天反馈 |
| `fix_rail_rotations` | bool | true | 粘贴时修复铁轨朝向 |
| `fix_stairs_mirror` | bool | true | 粘贴时修复楼梯镜像 |
| `fix_chest_mirror` | bool | true | 粘贴时修复箱子镜像 |
| `deduplicate_schematic_entities` | bool | false | 粘贴实体去重：false = 撞车重排开（id/UUID 与世界撞车时改派新值）；true = 跳过重排（依赖原版 UUID 唯一性拒绝重复实体） |

### 3.2 `jei.json`

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | bool | true | 模块总开关——配方同步 + jei:* 交互（`/jei enable\|disable` 切换） |
| `cheatModeEnabledForOp` | bool | true | 权限级 2（OP）玩家可用 cheat |
| `cheatModeEnabledForCreative` | bool | true | 创造模式玩家可用 cheat |
| `cheatModeEnabledForGive` | bool | false | 持有 `/give` 权限（`minecraft.command.give`）的玩家可用 cheat |

默认值对齐上游 `jei-server.properties`。首次启动若存在旧版 `jei-recipe-bridge.json`，其 `enabled` 值会被迁移（不会静默重开被故意关闭的服务端）；文件缺失/损坏时按默认值重建并落盘。

### 3.3 `syncmatica-config.json`

按 service 分段（每段一个子对象）；**优先用 `/syncmatica` 命令管理**，少手编：

```jsonc
{
  "quota": {                       // 上传配额服务
    "enabled": false,              // 是否启用上传字节配额（默认关）
    "limit": 40000000              // 每玩家上传字节上限（默认 ~40MB；进度不持久化，重启清零）
  },
  "debug": {
    "doPacketLogging": false       // 收发包逐包日志（默认关）
  },
  "debugLog": {                    // SyncmaticaDebug 运行时快照（master + 分类；/syncmatica debug 即时持久化）
    "master": false,
    "categories": []
  }
}
```

> 配额只约束 `DownloadExchange`（玩家上传方向）；`UploadExchange`（玩家下载）不检查。原版 `DebugService` 字段拼写曾为 `doPackageLogging`，Paper 版修正为 `doPacketLogging`（配置文件以真实拼写为准）。

### 3.4 `carpet.conf`（纯文本——上游 `SettingsManager` 同构，非 JSON）

逐行 `<rule> <value>`（值含空格整段保留，`split("\s+",2)` 读回）；`#` 注释行跳过；**首行 `locked` = 锁定**（全部子命令拒绝 + COMMAND 类可关规则置 false，上游语义）；未知规则名 error 日志后跳过；**文件从不主动创建**（首次写盘仅由 `/carpet setDefault|removeDefault` 触发）；写盘带原子 tmp/move。完整怪癖与偏差清单见 [`carpet-architecture.md`](carpet-architecture.md)。

| 规则键 | 类型 | 默认 | 选项域 | 行为面 |
|---|---|---|---|---|
| `language` | string | `en_us` | 6 语言 strict | 纯壳注册（B 侧无 i18n，对外恒英文） |
| `carpetCommandPermissionLevel` | string | `ops` | `ops`\|`2`\|`4` | /carpet 动态门禁（NMS 判级；conf 载入自由、命令路径改需 op 4） |
| `superSecretSetting` | bool | `false` | — | 纯壳注册（上游 debug 行为不随 P1 移植） |
| `explosionNoBlockDamage` | bool | `false` | — | `EntityExplodeEvent` + `BlockExplodeEvent` 双挂点 `blockList().clear()`（即时生效） |
| `customMOTD` | string | `_` | 非 strict | `_` = server.properties 原生；否则 `ServerListPingEvent` 改写 ping MOTD（即时生效） |
| `cleanLogs` | bool | `false` | — | log4j 过滤器（默认 false=**抑制**命名实体死亡日志行；true=放行——极性勿反转，代数见 carpet-architecture.md） |
| `commandTick` | string | `ops` | CommandLevel OPTIONS | 零行为死规则（上游 /tick 即原版命令，Paper 天然同构；注册仅为规则面完整） |
| `perfPermissionLevel` | int | `4` | `2`\|`4` | **只读登记**：值 4 = Paper 天然同构（vanilla /perf 恒要求级 4）；值 2 = 结构性不可达（上游靠 Mixin redirect 换 requires，Paper 无替换面）；控制面 = ops.json op level；`minecraft.command.perf` 节点仅影响 console/API dispatch，玩家聊天命令链绕过 commandMap |
| `commandPlayer` | string | `ops` | CommandLevel OPTIONS | /player 命令门禁（Brigadier requires 每次执行求值——规则动态生效，上游同构） |
| `allowSpawningOfflinePlayers` | bool | `true` | — | 允许在正版服 spawn 不存在的离线玩家（offline UUID 兜底，上游同构） |
| `allowListingFakePlayers` | bool | `false` | — | 假人在多人屏列表的可见性（`allowsListing` 覆写） |
| `commandLog` | string | `true` | CommandLevel OPTIONS | /log 命令门禁（Brigadier requires 每次执行求值——规则动态生效，上游同构；默认 true 全员可订阅自身 HUD） |
| `defaultLoggers` | string | `none` | `none`\|`tps`\|`mobcaps,tps`（非 strict，任意 csv） | 新玩家默认订阅：csv 条目 = "logger" 或 "logger option"（如 `mobcaps overworld`）；每进服对「未订阅且未显式退订」者重评估（显式退订墓碑拦截，§3.5；显式订阅优先） |

### 3.5 `carpet-logs.json`（/log 订阅持久化——B 侧新增，上游无对应物）

JSON（Gson pretty + 原子 tmp/move）：`{ "<uuid>": { "name": 名字快照, "subs": { "<logger>": "<option|\"\">" }, "off": ["<logger>"...] } }`——`subs` = **显式**订阅（/log 命令产生，`""` = null option 哨兵）；`off` = **退订墓碑**（/log clear 产生，压制默认施用复活）。变更即落盘；**文件从不主动创建**（从未有玩家触过 /log 的服不产生）；坏 JSON 按空册起步（默认订阅重施）。改名玩家经 UUID 复原订阅（名字快照随进服更新）；假人永不写入。模型细节见 [`carpet-architecture.md`](carpet-architecture.md) §16.1。

---

## 4. 数据布局

```
plugins/VeryMcProto/
├── servux.json                 Servux 全局配置（DataProviderToggles + 6 个 Provider settings 段）
├── jei.json                    JEI 配置（enabled + cheat 三开关；迁移旧 jei-recipe-bridge.json）
├── syncmatica-config.json      Syncmatica 配置（quota / debug / debugLog 段）
├── carpet.conf                 Carpet 规则持久化（纯文本逐行 key value；仅 setDefault/removeDefault 写入——§3.4）
├── carpet-logs.json            /log 订阅持久化（显式订阅 + 退订墓碑；/log 变更即写，从不主动创建——§3.5）
├── placements.json             Syncmatica placement 元数据持久化（+ .bak / .new 原子写）
├── syncmatics/                 Syncmatica .litematic 中央仓库（上传 / 下载 / 共享）
│   └── <hash-uuid>.litematic   文件名 = hash UUID（/syncmatica load 按它识别文件）
└── schematics/                 Servux 投影目录
    └── *.litematic             当前线已无写入方（C2S 接收链已随安全修复移除；旧维护线仅 op 命令只读发送）；/servux litematic list 列出
```

> `schematics/` 与 `syncmatics/` 首次访问时自动创建。停服（`onDisable`）时 `placements.json` 由 `SyncmaticManager` 原子保存（backup → current ← incoming）；启动时读取，损坏条目逐条 try/catch 跳过并修正重写。

---

## 5. 调试与排错速查

每个 mod 一套**独立调试引擎**（master + 正交分类，两者皆开才输出）；切换即时持久化、重启完全恢复。

### 5.1 调试开关

**Servux**（分类 10 值，见 §1.2）：

```
/servux debug on                 master 开
/servux debug cat all            全部分类开
```

**Syncmatica**（分类 6 值：`lifecycle handshake network packet exchange data`，见 §1.4）：

```
/syncmatica debug on
/syncmatica debug cat all
```

syncmatica 不工作的排查：`on` + `cat all`（或单开 `handshake`/`network`/`packet`），盯握手链：40t 探针 → `tryStartHandshake` → init 推 `REGISTER_VERSION` → 客户端回版本 → `FeatureSet` → `CONFIRM_USER` → `broadcastTargets`（声明通道是握手的下游产物，详见 [`syncmatica-testing.md`](syncmatica-testing.md) §2.3）。

S2C 路径排查：`/syncmatica debug s2c`（查看）→ `/syncmatica debug s2c nms|msg`（切换对照）。

**JEI**：无独立调试引擎；`/jei status` 查看状态，看服务端日志（fabric 腿在通道声明时触发日志、neoforge 腿在进服时触发）。

**Carpet**：无独立调试引擎；`/carpet query <rule>` 查看规则详情，conf 读写问题看 `[Carpet]` 前缀日志。carpet:hello 握手排错（Phase 3）：carpet 客户端进服后服务端日志应出现 `Player <名> joined with a matching carpet client`（或 `another carpet version: <v>`——真实发布客户端版本带 `+v` 日期后缀时属上游 equals 语义的正常 warn，非故障）；规则浏览器空 = 查该日志确认 hello 是否到达 + 规则名是否存在于上游注册表（[carpet-protocol.md](carpet-protocol.md) §3/§7）；`[Carpet] clientCommand from <名>: <命令>` = 远端命令执行审计行。假人排错：spawn/kill 全链异常看 `[Carpet]` 前缀日志；`EntityPlayerMPFake.tick()` 有上游同源的 NPE 吞点（首次触发打一次性 WARN 堆栈）；假人卡死不消失时先 `/player <name> kill`（kill 走完整退出链含 PlayerQuitEvent 与存档）；syncmatica 对每个假人会发起一次必败握手（debug 日志噪声，quit 自动清理，无害）。/log 排错（Phase 4）：订阅后 tab 脚无 HUD = 先 `/log` 裸命令看订阅态（"Subscribed"/option 徽标）→ 有订阅仍无显示则确认玩家进服晚于插件启用（reload 后经补扫恢复）；mobcaps 行显示 `--UNAVAILABLE--` = 该维度 SpawnState 尚未建立（新区块首刻，正常）；订阅丢失跨会话 = 查 `carpet-logs.json` 是否被外部改动（坏 JSON 按空册起步 + 默认重施）；与外部 TAB 类插件的 tab 脚每秒互覆写属已知限制（上游 carpet 同源行为，carpet-architecture.md 偏差 #39）。

### 5.2 症状速查表

| 症状 | 看哪里 |
|---|---|
| 客户端进服但收不到数据 | 查 Provider 是否启用（看 `servux.json` 顶层 `DataProviderToggles` 段 / 开 `/servux debug cat provider` 看生命周期日志——`/servux info` 只回显 setting 现值/默认值，不含启用状态）；查 `permission_level`；开 `handshake` 分类看握手是否成功（客户端侧先确认 `entityDataSync` 开关已开——`not_enabled` 是客户端本地开关未开，与服务端无关，见 [`servux-testing.md`](servux-testing.md) §2） |
| 大投影粘贴 / 上传失败 | 查客户端是否因 32,767 字节断连；开 `network`/`packet` 看分片（字节限制教义见 [`architecture.md`](architecture.md) §3.4） |
| EasyPlace 无反应 | 确认服务器装了 PacketEvents 插件（`softdepend`）；开 `easyplace` 分类——若日志见「拒绝放置（写入前 FAIL）」为 canBuild 拒绝分支（validator/床头拒绝或修正态 canSurvive/实体碰撞失败，语义对齐上游整次 FAIL），非故障 |
| Syncmatica 客户端连不上 | 默认 NMS 直发；`/syncmatica debug s2c` 确认路径；开 `handshake` 看握手链 |
| JEI 配方不同步 | `/jei` 确认 `enabled`；fabric 腿要求客户端声明 `fabric:recipe_sync`（任何 Fabric-API 客户端都会）；进服时序整形器（`RecipeSyncJoinOrderer`）必须出现在 pipeline——缺失/安装失败会降级为旧时序（警告 + 客户端本地配方）；注意只影响**之后**进服的玩家（详见 [`jei.md`](jei.md) §5.2） |

> Servux / Syncmatica 各通道的逐步实测步骤见 [`servux-testing.md`](servux-testing.md) 与 [`syncmatica-testing.md`](syncmatica-testing.md)。

---

## 6. MC 版本升级 SOP（维护者方法论）

> 本节是版本升级的标准作业程序，由 26.1 / 26.2 两次迁移提炼。分支模型（`dev` / `main` / `ver/<X>`(+`-dev`)）与发版流程的唯一权威见 [`AGENTS.md`](../AGENTS.md) §分支模型，本文不复制。

### 6.1 前置检查

1. **查上游版本清单**：`https://launchermeta.mojang.com/mc/game/version_manifest_v2.json`，读 `latest.release` / `latest.snapshot`；Paper 侧用 `https://api.papermc.io/v2/projects/paper` 交叉核对。
2. **版本策略 = 对齐 latest-1**（latest 可能出小版本更新，latest.release 的下一版才是目标）。
3. 对齐动作：上游最新 release == 本线 `gradle.properties` 的 `mcVersion` → 正常开发；上游已越过本线 → 该版本属旧线，当前版本的活走 `ver/<X>-dev`，同时按本 SOP 启动升级。

### 6.2 冻结与切分支

上游新版本出现时：从 `main`（干净冻结点）切出 `ver/<旧版本>` + `ver/<旧版本>-dev` 对（**不要从 dev 切**——dev 即将携带新版本改动）→ 回到 `dev` 改 `gradle.properties` 开始升级。旧版本 Bug 修在 `ver/<X>-dev`，验证后合入 `ver/<X>` 出包；若新版本同样存在，cherry-pick / 手工移植回 `dev`。

### 6.3 构建面升级清单

逐项核对（历史漂移实例见 §6.7）：

- [ ] `gradle.properties`：`mcVersion` 改为**精确上游 id**（客户端 MOD_STRING 硬门禁绑死；Fill/dev-bundle/runServer 三处都无裸版本段）+ `buildNumber` 换线重置为 1（每线独立编号）。
- [ ] dev bundle：`<mc>.build.<N>-stable` 新格式（26.1 起），取该线当前最高 stable（repo.papermc.io metadata 实测）。
- [ ] paperweight userdev：策略性跟随最新 beta（若无功能必要性且编译失败可回退并记录）。
- [ ] run-paper / Gradle wrapper：核对插件 API 版本要求（run-task 3.1.0 要求 Gradle ≥9.7）。
- [ ] Java 工具链：对照上游 `build.gradle` 与 piston-meta `javaVersion`。
- [ ] **reobf 已废除**（26.1 起）：不得恢复 reobfJar 装配。
- [ ] `plugin.yml` `api-version` 已模板化 `${mcVersion}`——确认展开链路（`inputs.property` + `verifyVersionInjection` 终检；历史事故：手写 api-version 误留旧值致 Modrinth 错标 + 发版陈旧展开打进新 jar）。
- [ ] packetevents：核对新 MC 版本支持与 API 面破坏（`WrapperPlayClientPlayerBlockPlacement` 等），运行时实机复验。

### 6.4 协议面核对方法论

> **静默失败重灾区，编译器不可见**——必须逐字对照客户端源码（`OriginImpl/*-LTS/<版本>`），不要凭服务端代码猜客户端行为。

1. **六路全树逐文件字节级 diff**（`git diff --no-index` / `diff -rq`），非抽样：servux / malilib+litematica / minihud+tweakeroo / syncmatica / JEI（+ Fabric API `fabric:recipe_sync` 所在分支）/ fabric-carpet（对照 `OriginImpl/fabric-carpet-<线>/`）。
2. 核对面：协议版本常量表（客户端 `!=` 严格相等）、MOD_STRING 期待前缀（`servux-fabric-<精确上游id>`）、DataTag 载体与逐 Type 分界、C2S 枚举增删、PacketSplitter 常量与重组上限、JEI 通道/权限/转移算法、syncmatica 18 PacketType/FeatureSet/Exchange。
3. **wire diff 基线**：当前线（26.1→26.2）协议面**零变化**已实证；旧线维护时以各线上游树为准（如 Schema 两表各随各的上游树，勿互相对齐）。上游 HEAD 锚（commit hash）在升级 commit 中记录。
4. Schema/DataVersion 表：整表照抄上游（含上游主动携带的前瞻条目与特有序型，保序）；上游删除的条目跟随删除（非回归）。

### 6.5 NMS 漂移修复

**编译驱动**：改版本 → `./gradlew build` → 逐个编译错误修复。已知坑清单见 [`architecture.md`](architecture.md) §3.6（升级时逐行重核）。Paper 行为实证重核：dev bundle 自带全套 .java 源码可直读（如 `CraftPlayer.sendPluginMessage` 门控、`PaperCommonConnection.packetListener` 反射目标、`handleUseItemOn` 逐轴校验与 capture 时序）。

### 6.6 实机验证

1. **单测**：`./gradlew test` 全绿（规模基线以实跑为准；新增漂移应补锁定用例——见 §6.8 判据索引）。
2. **构建终检**：`./gradlew build` + `verifyVersionInjection`（解包断言 plugin.yml / version.properties 与 project.version 一致）。
3. **runServer 环境隔离（运行目录按线派生）**：runServer 运行目录由 mcVersion 派生为 `run-<mcVersion>/`（build.gradle.kts），各线世界/配置/plugins/logs 物理隔离。根因：共享 `run/` 的 server.properties 残留跨线泄漏（level-name 指向别线格式世界）——1.21.11 线实撞 `No key dimensions in MapLike[{}]`、26.x 两线间为静默互开互写；旧「level-name 三件套」SOP 已废弃（其 .bak 备份正是此事故的制度化来源）。注意：新目录首启需预置 `eula.txt`（eula=true，不入库）；packetevents jar 不随目录迁移（各线 `run-<mcVersion>/plugins/` 自行放置，2026-10-11 起已预置 2.14.0）；两线并行需各自在己线 server.properties 改 port（一次性持久）；旧共享 `run/` 死遗留已于 2026-10-11 连同仓库根 `logs/`（手工 java -jar 残骸）清除。教训：改 .gitignore 须逐行自检——2c38ee2 曾把规则粘连进注释行留废规则。
4. **起服检查**：版本横幅正确 → 四模块注册（servux / jei / syncmatica / carpet）+ `framework ready` + `Done`，无 ERROR/SEVERE。
5. **客户端互通冒烟（用户侧最终验收）三要素**：
   - HUD/litematics 握手（按 [`servux-testing.md`](servux-testing.md) 流程）；
   - **EasyPlace 编码包改写放行回归**（packetevents 跨版本 read/write 对称性是活风险点）；
   - `RecipeNbtNormalizer` 的 wire 病灶形状确认（plain NbtOps 编码形状无头环境不可观测，需真客户端）。

### 6.7 已知漂移模式表（历史实例，升级时优先排查同类）

| 线 | 漂移 | 形态 |
|---|---|---|
| 26.1 | `ChunkPos` 变 record | 字段私有化：`x()/z()`、`unpack/pack/containing` |
| 26.1 | 天气状态搬家 | `ServerLevelData.getXxx` → `ServerLevel.getWeatherData()` |
| 26.1 | 消息 API | `displayClientMessage(comp, bool)` → `sendSystemMessage(comp)` |
| 26.1 | `ResourceLocation` → `Identifier` | Mojang 重命名；`.location()` → `.identifier()` |
| 26.1 | `CompoundTag` Optional 语义 | `getXxx` 返回 Optional；`getXxxOr`/`.orElse()` |
| 26.2 | `EntityType.PLAYER` 等常量迁至 `EntityTypes` | vanilla 拆常量类（`getKey/create` 仍在 `EntityType`） |
| 26.2 | `EntityType.create` 第三参 | `new EntitySpawnRequest(EntitySpawnReason.LOAD, true)` |
| 26.2 | `BlockTags.CONCRETE_POWDER` → `CONCRETE_POWDERS` | tag 改名，判定集合不变 |

### 6.8 验证判据索引（测试名 + 通过条件 → 详锚）

| 测试 | 通过条件 | 详锚 |
|---|---|---|
| `TaskGroupTest`（4 黄金样本） | Box IntArrayTag 形状 / REMAINING_CHUNKS 字面量 / 完成帧缺键 / 10 条上限无标题行（纯 JVM 需 `SharedConstants.tryDetectVersion()+Bootstrap.bootStrap()` 前置） | [`servux-schematic.md`](servux-schematic.md) §9.5 |
| `TaskSchedulerTest`（7 用例） | timer 首启 / interval 钳制 / 周期复位 / 完成移除 / 同 tick 双任务 / clearTasks | 同上 |
| `EntityPastePositionFixTest`（11 用例） | 实体修复族 ①-⑤ 全守卫（字面串防同源共错 + wire 形状 + 负数截断 + UUID 不变） | 同上 §6.1 |
| `DataTagIoTest`（13 用例） | 异常三分语义（空 tag / null / ZipException 裸读回落）+ 黄金向量 | [`servux-protocol.md`](servux-protocol.md) §5.5 |
| `SettingsManagerConfRoundtripTest`（8 用例） | carpet.conf 往返（落盘格式/重启读回/locked/未知键/注释怪 quirks/严格校验/removeDefault/从不主动创建） | [`carpet-architecture.md`](carpet-architecture.md) §5 |
| `CarpetRuleRegistryTest`（4 用例） | 规则注册面锁：13 规则名/默认值/类型/选项域（P1 地基 8 + P2 假人 3 + P4 日志 2） + 排序视图 | 同上 |
| `EntityPlayerActionPackTest`（4 用例） | P2：Action 工厂计时语义锁（once 达限/continuous 永续/interval 节拍/offset 相位——反射断言，NMS 面走实机） | 同上 |
| `CarpetLoggerTest`（18 用例） | P4：订阅语义/桶迁移快捷位 + carpet-logs.json round-trip（哨兵/墓碑/坏 JSON/空册不建文件）+ join 分支矩阵 + defaultLoggers CSV 解析 + 热力色阈值 + mobcaps 行格式 | [`carpet-architecture.md`](carpet-architecture.md) §18 |
| `CommandHelperTest`（3 用例） | parseLevel 纯函数矩阵（ops→2 / 0-4 / 其他→-1） | 同上 |
| `PacketSplitterTest` / `FeatureSet` / `LitematicaBitArray` | 分片重组 / 版本正则 / 位打包纯函数 | — |

### 6.9 观察项（升级窗口消费）

- 上游 servux 树已携带 26.3 Schema 前瞻条目；`DataTypeUtils` 已为 26.3 预留 `"id"/"properties"` BlockState 读宽容——**26.3 wire 可能翻转写路径**，届时 DataTag 块状态写出需对照。
- 上游 26.4 出现时：按 §6.2 冻结 `ver/26.2`（+`-dev`）对 → dev 升 `mcVersion=26.3`。
- 僵尸 VHS 周期重试器（enable 后正常对局中客户端停止应答的残留 exchange）——行为级已知局限，登记于 [`syncmatica-architecture.md`](syncmatica-architecture.md) §已知局限。

---

> **回到索引**：[index.md](index.md) · 项目权威说明：[../AGENTS.md](../AGENTS.md)
