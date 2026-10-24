# 运维参考（版本字串策略 / 命令 / 权限 / 配置全键 / 数据布局 / 排错）+ 版本升级 SOP

> **本文是 docs/ 内运维信息的唯一权威**（英文 README 不再承载这些表格——本线 README 曾长期内嵌 §6-§12 运维手册，2026-10 文档重构后迁入本文）：**版本字串策略（§0）**、四 mod 全命令参考、权限节点（命令 + Provider 运行时 + LuckPerms 示例）、配置文件全键表、磁盘数据布局、调试系统与排错速查（§1–§5），以及 **MC 版本升级 SOP（§6）**。
>
> - **键权威 = src 配置类**：`servux.json` 各 Provider settings 声明、`jei.json` = `mod/jei/config/JeiConfiguration.java`、`syncmatica-config.json` = `service/QuotaService.java` + `service/DebugService.java` + `util/SyncmaticaDebug.java`、`carpet.conf` = `mod/carpet/settings/SettingsManager.java` + `CarpetSettings.java`。**改键必须同步本文**（仓库"文档同步强制"约定）。
> - **三载体边界**：本文 = docs 内运维全键唯一权威；根 [`AGENTS.md`](../AGENTS.md) §5 权限表 = AI 上下文自包含安全网（豁免声明）；[`servux/providers.md`](servux/providers.md) = 设置语义视角。
> - 命令/权限的**语义与上游对照**见各 mod 协议文档（[`servux/protocol.md`](servux/protocol.md) / [`syncmatica/protocol.md`](syncmatica/protocol.md) / [`jei/protocol.md`](jei/protocol.md) / [`carpet/architecture.md`](carpet/architecture.md)）。

---

## 0. 版本字串策略（三线统一）

- **唯一来源**：插件版本 = `<mcVersion>-b<buildNumber>`，唯一来源 `gradle.properties`；源码、plugin.yml、docs 一律不得手写版本号。
- **README 例外（唯一活值位）**：README 封面 Version 行为本仓库发版流程认可的例外，**语义 = 最近已发版版本（tag 所指版本）**，随发版提交同步更新。本线（1.21.11）形态：Version 行具体值；**安装节 jar 名保持占位形态 `VeryMcProto-<version>-reobf.jar`**（本线 reobf 未废除，无逐版同步的活值位）。dev/26.1.2 线两处均为具体值形态。
- **占位化范围**：AGENTS.md 与 docs/ 内一切 buildNumber 活值一律占位化（`-b<N>` / `<buildNumber>` / 「现值见 gradle.properties」表述）——发版提交不再追改文档版本引用。
- **豁免清单（保留原文的具体值）**：tag 格式示例（如 `v1.21.11-b1`）；历史事故记录值；协议语义 mcVersion 值（MOD_STRING `servux-paper-1.21.11`、carpet HI 版本串 `"1.4.194"` 等——绑死上游 id 与协议语义，与构建号无关）。

---

## 1. 命令参考

### 1.1 `/servux`

**权限**：`servux.command`（default: op）。

```
/servux                                          显示用法
/servux list                                     列出全部 provider 及启停态
/servux info <provider:setting|setting>          查看 setting 现值 + 默认值
/servux set <provider:setting|setting> <value>   修改 setting 并即时落盘
/servux enable <provider>                        启用 provider（如 hud_data）
/servux disable <provider>                       停用 provider
/servux search <keyword>                         模糊搜索 setting 名
/servux reload                                   从 servux.json 重载
/servux save                                     当前配置写 servux.json
/servux debug ...                                调试开关（见 §1.2）
/servux litematic ...                            投影文件管理（见 §1.3）
```

- setting 全名 = `<provider 逻辑名>:<setting 名>`（如 `hud_data:share_seed`）；无歧义时可省 provider 前缀。
- Provider 逻辑名：`servux_main` / `hud_data` / `entity_data` / `tweaks_data` / `structure_bounding_boxes` / `litematic_data`；`servux_main`（配置主通道）**永不可禁用**。

### 1.2 `/servux debug`（调试日志热切换，运行时即时生效 + 即时持久化）

```
/servux debug                  显示当前状态
/servux debug on|off           master 总开关（仅 master，不碰分类）
/servux debug status           显示状态
/servux debug cat all|none     全开 / 清空分类
/servux debug cat <name>       切换单个分类
```

> **master 与分类正交，双开才出输出**。分类：`lifecycle` `handshake` `network` `packet` `tick` `permission` `provider` `config` `easyplace`。

### 1.3 `/servux litematic`（S2C 投影投递，本线保留）

```
/servux litematic list                          列 schematics/ 下 .litematic
/servux litematic transmit <file> [player]      加载并向玩家投递（走 servux:litematics 分片）
```

> 控制台执行 transmit 须指定目标玩家；文件位于 `plugins/VeryMcProto/schematics/`（命令入口有目录包含性守卫）。**仅旧版 litematica 客户端可接收**——修复版（≥0.26.11）对 Transmit 帧双向静默丢弃。

### 1.4 `/syncmatica`

**权限**：`syncmatica.command`（default: **true**）。

```
/syncmatica                                     显示用法
/syncmatica status                              模块状态（协议启停 + 调试 + 配置文件）  [admin]
/syncmatica save                                保存到 syncmatica-config.json           [admin]
/syncmatica reload                              从 syncmatica-config.json 重载          [admin]
/syncmatica enable                              恢复协议（在线玩家重新握手）             [admin]
/syncmatica disable                             软禁用协议（通道保留、不踢人）           [admin]
/syncmatica load                                将 syncmatics/ 下未加载 .litematic 全部注册  [load]
/syncmatica load <file>                         注册单个文件为 placement                [load + load_each]
/syncmatica debug ...                           调试开关（见下）                         [debug]
```

> 投影的上传/下载/修改/删除**全走协议 exchange**（客户端动作）；命令仅做本地文件注册与运维。`[admin]` = `syncmatica.command.admin`；`[load]` = `.load`；`[load_each]` = `.load_each`；`[debug]` = `.debug`。

**`/syncmatica debug`**（syncmatica 独立调试系统，与 `/servux debug` 无关）：

```
/syncmatica debug                  显示状态
/syncmatica debug on|off           master 总开关
/syncmatica debug cat all|none     分类（lifecycle handshake network packet exchange）
/syncmatica debug s2c              查看当前 S2C 路径
/syncmatica debug s2c nms|msg      切换 S2C 路径（NMS 直发 / plugin messaging）——诊断用，不持久化
```

> S2C 默认 **NMS `DiscardedPayload` 直发**（纯 Fabric syncmatica 客户端仅 NMS 直发可达——plugin messaging wire 实测不可达）。

### 1.5 `/jei`

**权限**：`jei.command`（default: op）。

```
/jei                显示模块状态 + 用法（status）
/jei enable         启用 JEI 模块（配方同步 + jei:* 通道；后续进服者得到同步）
/jei disable        停用（通道保持声明、不踢人；已在线玩家不受影响）
```

> **生效面**：仅影响之后进服的玩家。停用 = 配方同步停止 + C2S 包在 handler 内静默丢弃——**通道永不反注册**（反注册会使后续客户端 C2S 命中 Paper 未知通道踢人）。

### 1.6 Carpet 命令族（门禁全部为运行时规则，详见 [carpet/architecture.md](carpet/architecture.md)）

- **`/carpet`**：`list` / `query <rule>` / `setDefault <rule> <value>`（即时生效 + 持久化 carpet.conf）/ `removeDefault <rule>`。双层门禁 = 静态 `carpet.command`（op）+ 动态 `carpetCommandPermissionLevel` 规则；首行 `locked` 锁定时全部子命令拒绝。
- **`/player <name> …`**（门禁 = `commandPlayer` 规则）：spawn（含 `at <pos> facing <rot> in <dim> in <gamemode>` 链，gamemode 节点需 gamemasters 级，控制台默认创造）/ kill / shadow / stop / mount / dismount / sneak / sprint / look / turn / move / attack / use / jump / drop / hotbar / swapHands（动作 × once|continuous|interval <ticks>）。
- **`/log`**（门禁 = `commandLog` 规则，默认 true，全部子命令仅玩家）：裸列表 / `subscribe <logger> [option]`（logger = tps|mobcaps，option = dynamic|overworld|nether|end）/ `clear [logger]`。
- **五条纯计算命令**（门禁 = 各自 commandXxx 规则；commandDraw 默认 ops，其余默认 true）：`/distance`（from/to 两点三距离）/ `/info block <pos> [grep]` / `/perimeterinfo`（±128 周界刷怪诊断，mob 参数出可召唤实体补全）/ `/draw`（七形状）/ `/spawn mobcaps|entities`。

---

## 2. 权限节点

### 2.1 命令权限（plugin.yml）

| 节点 | default | 用途 |
|---|---|---|
| `servux.command` | op | `/servux` 全子命令 |
| `jei.command` | op | `/jei status\|enable\|disable` |
| `syncmatica.command` | **true** | `/syncmatica` 基础命令（含 `load`） |
| `syncmatica.command.admin` | op | `save\|reload\|enable\|disable\|status` |
| `syncmatica.command.load` | **true** | `/syncmatica load`（批量） |
| `syncmatica.command.load_each` | **true** | `/syncmatica load <file>` |
| `syncmatica.command.debug` | op | `/syncmatica debug` |
| `carpet.command` | op | `/carpet` 规则壳（另受 `carpetCommandPermissionLevel` 动态门禁） |
| —（无节点） | — | `/player` `/log` `/distance` `/info` `/perimeterinfo` `/draw` `/spawn`——门禁 = 各自 commandXxx 规则（动态生效） |

### 2.2 Provider 运行时权限（Perms 语义）

Servux provider 权限不走 Bukkit `default`，由 `framework.permission.Perms` 按 **`permission_level` setting + op 等级**运行时判定。`Perms.check(player, node, level)`：① 显式设置/拒绝 Bukkit 节点（`isPermissionSet`，如 LuckPerms）**优先**；② 否则 `level <= 0` → 全员放行；③ 否则回落 **op 二值**（`isOp()`）。

> `permission_level` 实际只区分「0 = 全员 / ≥1 = op」。**要区分等级（如给 2 不给 3），用 LuckPerms 显式授予对应节点。**

| 节点 | 管辖 setting（等级来源） | 控制面 |
|---|---|---|
| `servux.main.admin` | `servux_main:permission_level_admin`（默认 3） | ConfigProvider 管理操作 |
| `servux.main.easy_place` | `servux_main:permission_level_easy_place`（默认 0） | EasyPlace 放置 |
| `servux.provider.hud_data` | `hud_data:permission_level`（默认 0） | HUD 元数据投递 |
| `servux.provider.hud_data.weather` | `hud_data:weather_permission_level`（默认 0） | 天气数据 |
| `servux.provider.hud_data.seed` | `hud_data:seed_permission_level`（默认 2） | 种子数据 |
| `servux.provider.hud_data.logger`（.tps/.mob_caps） | `hud_data:logger_permission_level`（默认 0） | TPS/MobCap logger |
| `servux.provider.entity_data` | `entity_data:permission_level`（默认 0） | Entities 基座 |
| `servux.provider.entity_data.nbt_query_override` | `entity_data:nbt_query_permission_level`（默认 2） | NBT 查询独立权限 |
| `servux.provider.entity_data.nbt_allow_player_inventory` | `entity_data:player_inventory_permission_level`（默认 2） | 查玩家背包 |
| `servux.provider.entity_data.nbt_allow_player_ender_items` | `entity_data:player_ender_items_permission_level`（默认 2） | 查末影箱 |
| `servux.provider.tweaks_data` | `tweaks_data:permission_level`（默认 0） | Tweaks 基座 |
| `servux.provider.structure_bounding_boxes` | `structure_bounding_boxes:permission_level`（默认 0） | Structures 基座 |
| `servux.provider.litematic_data` | `litematic_data:permission_level`（默认 0） | Litematics 基座（投递/接收） |
| `servux.provider.litematic_data.paste` | `litematic_data:permission_level_paste`（默认 0） | **粘贴**投影入世界（需创造模式） |

> `nbt_query_override` 关闭时，Entities NBT 查询**回落 vanilla `minecraft.command.data`**（op 2），对齐原版 `/data` 权限。

### 2.3 LuckPerms 示例

```yaml
commands:
  - "lp user <player> permission set servux.provider.hud_data.seed true"    # 授予种子（默认 op）
  - "lp user <player> permission set servux.provider.litematic_data.paste true"  # 授予非 op 粘贴
```

---

## 3. 配置文件（全部 JSON：Gson pretty + 原子 tmp/move 写）

### 3.1 `servux.json`（按 Provider 分段；每键均可 `/servux set <provider:key> <value>` 在线改）

**`servux_main`**：`permission_level` int[0..4]=0 / `permission_level_admin`=3 / `permission_level_easy_place`=0 / `easy_place_validator_enabled` bool=true / `default_language` string=`en_us` / `debug_log` bool=false / `debug_categories` string[]=[]。

**`hud_data`**：`permission_level`=0 / `update_interval` int[20..300]=40 / `share_weather_status` bool=false / `weather_permission_level`=0 / `share_seed` bool=false / `seed_permission_level`=2 / `loggers_enabled` bool=false / `loggers_enable_list` string[]=`["tps","mob_caps"]` / `logger_permission_level`=0。

**`entity_data`**：`permission_level`=0 / `nbt_query_override` bool=false / `nbt_query_permission_level`=2 / `fix_allay_gathering` bool=true / `nbt_allow_player_inventory` bool=true / `nbt_allow_player_ender_items` bool=true / `player_inventory_permission_level`=2 / `player_ender_items_permission_level`=2。

**`tweaks_data`**：`permission_level`=0 / `update_interval` int[40..1200]=120。
> 原 `stackable_shulkers` / `stackable_shulkers_count` / `stackable_shulkers_fix` **已删除**——潜影盒堆叠在 Paper 无 Mixin 下不可能实现（见 [architecture.md](architecture.md) §5）；保留该键会使客户端 tweakeroo 开堆叠渲染而服务端不配合 → 不一致。

**`structure_bounding_boxes`**：`permission_level`=0 / `structures_blacklist_enabled` bool=false / `structures_whitelist_enabled` bool=false / `structures_blacklist` string[]=`["minecraft:buried_treasure"]` / `structures_whitelist` string[]=[] / `update_interval` int[1..1200]=40 / `timeout` int[40..1200]=600。

**`litematic_data`**：`permission_level`=0 / `permission_level_paste`=0 / `fix_rail_rotations` bool=true / `fix_stairs_mirror` bool=true / `fix_chest_mirror` bool=true。

### 3.2 `jei.json`

`enabled` bool=true（`/jei enable\|disable` 切换）/ `cheatModeEnabledForOp` bool=true / `cheatModeEnabledForCreative` bool=true / `cheatModeEnabledForGive` bool=false。默认值对齐上游 `fabric/config/ServerConfig.java`；文件缺失/损坏按默认重建并落盘；旧 `jei-recipe-bridge.json` 的 `enabled` 值首次启动只读迁移。

### 3.3 `syncmatica-config.json`（按服务分段，建议经命令管理）

```jsonc
{
  "quota":     { "enabled": false, "limit": 40000000 },   // 上传配额（默认关/40MB；progress 不持久化，重启清零）
  "debug":     { "doPacketLogging": false },              // 包级收发日志（默认关）
  "debugLog":  { "master": false, "categories": [] }      // SyncmaticaDebug 快照（/syncmatica debug 即时持久化）
}
```

> 配额仅约束 `DownloadExchange`（玩家上传方向）；`UploadExchange`（下载）不查。

### 3.4 `carpet.conf`（纯文本例外）与 `carpet-logs.json`

- `carpet.conf`：纯文本逐行 `<rule> <value>`、`#` 注释、首行 `locked` = 锁定；仅 `setDefault/removeDefault` 写入，**从不主动创建**；写盘原子 tmp/move；上游注释怪癖逐字保留（值首字符 `#` 重读被吞）。18 规则全表见 [carpet/architecture.md](carpet/architecture.md) §1。
- `carpet-logs.json`：`/log` 订阅跨会话持久（双信号模型——显式订阅 + 退订墓碑；默认订阅永不落盘；从不主动创建；坏 JSON 按空册起步）。详见 [carpet/architecture.md](carpet/architecture.md) §3.1。

---

## 4. 数据布局

```
plugins/VeryMcProto/
├── servux.json                 Servux 全局配置（6 provider 段）
├── jei.json                     JEI 配置（enabled + cheat 三切面；旧 jei-recipe-bridge.json 只读迁移）
├── syncmatica-config.json      Syncmatica 配置（quota / debug / debugLog 段）
├── placements.json             Syncmatica placement 注册表（+ .bak / .new 原子写）
├── syncmatics/                 Syncmatica .litematic 中央仓库（上传/下载/共享）
│   └── <hash-uuid>.litematic   文件名 = hash UUID（/syncmatica load 按此识别）
├── schematics/                 Servux S2C 投递目录（/servux litematic transmit，op + 包含性守卫）
│   └── *.litematic
├── carpet.conf                 Carpet 规则持久化（纯文本，从不主动创建）
└── carpet-logs.json            /log 订阅持久化（从不主动创建）
```

> `schematics/` 与 `syncmatics/` 首次访问自动创建。停服（`onDisable`）时 `placements.json` 由 `SyncmaticManager` 原子落盘（backup → current ← incoming）；启动读取，坏条目逐条 try/catch 跳过并修正回写。

---

## 5. 调试与排错速查

每个 mod 独立调试引擎（master + 正交分类，双开才输出；切换即时持久化，重启全恢复）。Servux 分类见 §1.2；Syncmatica 见 §1.4（另含 S2C 路径切换）；JEI 无独立引擎（`/jei` + 服务端日志）。

| 症状 | 排查 |
|---|---|
| 客户端连上但收不到数据 | `/servux list` 查 provider 启停；查 `permission_level`；开 `handshake` 分类看握手是否成功 |
| 大投影投递/粘贴失败 | 查客户端是否因 32,767 字节断连；开 `network`/`packet` 看分片（字节限制教义见 [architecture.md](architecture.md) §3.2） |
| EasyPlace 无反应 | 确认 packetevents 插件已装（`softdepend`）；开 `easyplace` 分类 |
| Syncmatica 客户端连不上 | 默认 NMS 直发；`/syncmatica debug s2c` 确认路径；开 `handshake` 看链：declare → tryStartHandshake → REGISTER_VERSION → 版本应答 → FeatureSet → CONFIRM_USER → broadcastTargets |
| JEI 配方不同步 / cheat 与转移不可用 | `/jei status` 查 enabled；客户端功能门禁要求服务端声明过 `jei:delete_player_item` 通道（模块加载即自动声明） |
| Carpet 规则浏览器空 / HUD 不显示 | `[Carpet]` 日志查 hello 是否到达 + 规则名是否在上级注册表（[carpet/protocol.md](carpet/protocol.md) §3）；HUD 先 `/log` 裸命令看订阅态 |
| 假人掉落/环境伤害不生效 | 先查游戏模式（`/player spawn` 控制台默认创造免伤——测伤害须 `in survival`）与 `/difficulty`（PEACEFUL 拒绝难度缩放伤害）；机制见 [carpet/architecture.md](carpet/architecture.md) §6.2 |

---

## 6. MC 版本升级 SOP（1.21.11 线）

> 分支模型与发版流程的唯一权威见根 [`AGENTS.md`](../AGENTS.md) §分支模型，本文不复制。本节是**升级工作法**；本线为冻结维护线（1.21.11），升级到 26.x 属跨线工作（在 `dev` 线做，参考 26.1.2 线 operations.md §6 的 1.21.11→26.1.2 实测表）。

### 6.1 本线构建面（冻结基线）

paperweight `userdev` 2.0.0-beta.21 + `paperDevBundle("1.21.11-R0.1-SNAPSHOT")`（旧格式；26.1 起新格式 `<mc>.build.<N>-stable`）+ Java 21 工具链 + **reobfJar 保留**（产物 `-reobf.jar` = Spigot 运行时映射；26.1 起 Mojang 移除服务端混淆、reobf 废除）。`api-version` = `'${mcVersion}'` 模板化。PacketEvents 2.13.0（EasyPlace 可选）。

### 6.2 本线内修 Bug 流程

在 `ver/1.21.11-dev` 提交 → 验证（`./gradlew build` 全绿 + runServer 判据）→ 合入 `ver/1.21.11` → `buildNumber` +1 出包（本线构建号独立递增）→ 若 `dev` 线同病，cherry-pick / 手工移植回 `dev`（NMS 漂移大则手工重写）。

### 6.3 反射点核对（本线内小版本不动，跨线移植时必查）

按 [architecture.md](architecture.md) §3.5 反射清单逐条验证：`ServerTickRateManager.remainingSprintTicks` / `NaturalSpawner.MAGIC_NUMBER`(289) / `TagValueInput/TagValueOutput` 内部字段。测试环境注意：纯 JVM 单测无 Bukkit 类时 `Reference.logger()` 回退须为 JUL。

---

> **回到索引**：[index.md](index.md) · 项目权威说明：[../AGENTS.md](../AGENTS.md)
