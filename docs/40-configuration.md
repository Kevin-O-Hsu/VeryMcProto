# 40 · 配置与运维参考（命令 / 权限 / 配置全键 / 数据布局 / 排错）

> **本文是 docs/ 内运维信息的唯一权威**（英文 README 不再承载这些表格）：
> 三 mod 全命令参考、权限节点（命令 + Provider 运行时）、三个配置文件全键表、磁盘数据布局、调试系统与排错速查。
>
> - **键权威 = src 配置类**：`servux.json` 各 Provider settings 声明（`ConfigProvider` / `HudDataProvider` / … / `StructureDataProvider.java:83` 等）、`jei.json` = `mod/jei/config/JeiConfiguration.java`、`syncmatica-config.json` = `service/QuotaService.java` + `service/DebugService.java` + `util/SyncmaticaDebug.java`。**改键必须同步本文**（仓库"文档同步强制"约定）。
> - **三载体边界**：本文 = docs 内运维全键唯一权威；根 [`AGENTS.md`](../AGENTS.md) §5 权限表 = AI 上下文自包含安全网（豁免声明）；[`03`](03-dataproviders-detail.md) = 设置语义视角（讲每个设置的含义与采集联动，不维护节点全表）。
> - 命令/权限的**语义与上游对照**见各 mod 协议文档（[`02`](02-network-protocol.md)/[`03`](03-dataproviders-detail.md)/[`21`](21-syncmatica-protocol.md)/[`30`](30-jei-protocol.md)）。

---

## 1. 命令参考

### 1.1 `/servux`

**权限**：根节点 `servux.commands`（default: op）+ 每子命令 `servux.commands.<sub>`；扩展子命令（enable/disable/debug/litematic）有独立节点；旧单节点 `servux.command` 经 plugin.yml children 映射自动继承整棵新树（旧授权不受影响）。

```
/servux                                          显示用法
/servux list [provider]                          列出全部 settings 现值（上游 configList 形态；值短于 10 字符行内显示）；可选 provider 过滤
/servux info <provider:setting|setting>          查看某 setting 现值 + 默认值
/servux set <provider:setting|setting> <value>   修改 setting（纯内存——持久化需 /servux save，上游语义）
/servux enable <provider>                        启用 provider（如 hud_data）——扩展子命令，即时落盘
/servux disable <provider>                       停用 provider——扩展子命令，即时落盘
/servux search <keyword>                         搜索 setting（空格分词 AND；命中名/注释/provider 名任一；大小写敏感；零命中回「无匹配的设置: <关键词>」（上游 search.none 形态），非空先报计数与关键词再列条目（search.results 形态））
/servux reload                                   从 servux.json 重读配置
/servux save                                     当前配置写入 servux.json
/servux debug ...                                调试开关（见 §1.2）
/servux litematic ...                            投影文件管理（见 §1.3）
```

- setting 的**全名** = `<provider 逻辑名>:<setting 名>`，如 `hud_data:share_seed`、`servux_main:permission_level`；无歧义时可省略 provider 前缀。
- Provider 逻辑名见 [`02`](02-network-protocol.md) 通道总表：`servux_main` / `hud_data` / `entity_data` / `tweaks_data` / `structure_bounding_boxes` / `litematic_data`。
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

> S2C 文件投递（transmit）已于 2026-09 **物理删除**：26.1 stock 客户端 `handleBulkData` 的 Transmit 分流整块注释（无接收端，帧被静默丢弃），上游 `sendTransmitFile` 亦 `@Deprecated(forRemoval)` 零调用点（详见 [`09`](09-DELIVERY.md) §5.5）。文件位于 `plugins/VeryMcProto/schematics/`。

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
> **load 广播语义**（对齐上游 `sendSuccess(..., true)`，2026-09 修复）：控制台执行 `load` 同样对在线已握手客户端即时广播 REGISTER_METADATA（此前仅注册不广播、客户端需重进服）；load 成功/计数消息广播给持 `syncmatica.command.admin` 权限的全体玩家与控制台（≈ vanilla OP 广播位），执行者若不持该权限则补直发保证回执；"No file"/"Failed to peek" 类提示仍仅回执行者（上游对应 `sendSuccess(..., false)`）。

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

> 分类共 **6 值**（源 `mod/syncmatica/util/SyncmaticaDebug.java` `Cat` 枚举）：`lifecycle` `handshake` `network` `packet` `exchange` `data`。
> S2C 默认 **NMS `DiscardedPayload` 直发**（实测 plugin messaging wire 对纯 Fabric syncmatica 客户端不可达，详见 [`22`](22-syncmatica-mixin-migration.md) §4.3）；`/syncmatica debug s2c msg` 临时切回 plugin messaging 作对照排错。

### 1.5 `/jei`

**权限**：`jei.command`（default: op）。

```
/jei                （或 /jei status）  查看模块 + cheat 三开关状态
/jei enable         启用模块（此后进服的玩家获得配方同步 + jei:* 交互）
/jei disable        停用模块（通道保持注册——不踢人；在途 C2S 静默丢弃）
```

> **生效范围**：只影响**之后**的交互（新进服玩家的配方同步；新的 C2S 包被丢弃）。disable 时**从不注销通道**——注销会使后续客户端包命中未注册通道而被踢。cheat 三开关在 `jei.json`（文件管理，同上游 `jei-server.properties`；无命令切换）。

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

## 3. 配置文件（全部 JSON：Gson pretty + 原子 tmp/move 写）

目录 `plugins/VeryMcProto/`。

### 3.1 `servux.json`

按 Provider 分段，每段承载该 Provider 的 settings。**每个键都可用 `/servux set <provider:key> <value>` 修改**（免手编）。int 范围记法 `[min..max] default`。

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
| `easy_place_validator_enabled` | bool | true | EasyPlace 放置校验器 |
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

> ⛔ 原 `stackable_shulkers` / `stackable_shulkers_count` / `stackable_shulkers_fix` **已删除**——潜影盒堆叠在无 Mixin 的 Paper 上不可能实现（见 [`07`](07-migration-architecture.md) §4 降级矩阵）；保留会令客户端 Tweakeroo 开堆叠渲染而服务端不配合 → 不一致。

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

---

## 4. 数据布局

```
plugins/VeryMcProto/
├── servux.json                 Servux 全局配置（DataProviderToggles + 6 个 Provider settings 段）
├── jei.json                    JEI 配置（enabled + cheat 三开关；迁移旧 jei-recipe-bridge.json）
├── syncmatica-config.json      Syncmatica 配置（quota / debug / debugLog 段）
├── placements.json             Syncmatica placement 元数据持久化（+ .bak / .new 原子写）
├── syncmatics/                 Syncmatica .litematic 中央仓库（上传 / 下载 / 共享）
│   └── <hash-uuid>.litematic   文件名 = hash UUID（/syncmatica load 按它识别文件）
└── schematics/                 Servux 投影上传（粘贴）目录
    └── *.litematic             由 receiveFileTransmit 写入（客户端上传）；/servux litematic list 列出
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

syncmatica 不工作的排查：`on` + `cat all`（或单开 `handshake`/`network`/`packet`），盯握手链：声明通道 → `tryStartHandshake` → init 推 `REGISTER_VERSION` → 客户端回版本 → `FeatureSet` → `CONFIRM_USER` → `broadcastTargets`（详见 [`24`](24-syncmatica-testing-guide.md) §2.3）。

S2C 路径排查：`/syncmatica debug s2c`（查看）→ `/syncmatica debug s2c nms|msg`（切换对照）。

**JEI**：无独立调试引擎；`/jei status` 查看状态，看服务端日志（fabric 腿在通道声明时触发日志、neoforge 腿在进服时触发）。

### 5.2 症状速查表

| 症状 | 看哪里 |
|---|---|
| 客户端进服但收不到数据 | 查 Provider 是否启用（看 `servux.json` 顶层 `DataProviderToggles` 段 / 开 `/servux debug cat provider` 看生命周期日志——`/servux info` 只回显 setting 现值/默认值，不含启用状态）；查 `permission_level`；开 `handshake` 分类看握手是否成功（客户端侧先确认 `entityDataSync` 开关已开——`not_enabled` 是客户端本地开关未开，与服务端无关，见 [`10`](10-testing-guide.md) §2） |
| 大投影粘贴 / 上传失败 | 查客户端是否因 32,767 字节断连；开 `network`/`packet` 看分片（字节限制教义见 [`09`](09-DELIVERY.md)） |
| EasyPlace 无反应 | 确认服务器装了 PacketEvents 插件（`softdepend`）；开 `easyplace` 分类 |
| Syncmatica 客户端连不上 | 默认 NMS 直发；`/syncmatica debug s2c` 确认路径；开 `handshake` 看握手链 |
| JEI 配方不同步 | `/jei` 确认 `enabled`；fabric 腿要求客户端声明 `fabric:recipe_sync`（任何 Fabric-API 客户端都会）；进服时序整形器（`RecipeSyncJoinOrderer`）必须出现在 pipeline——缺失/安装失败会降级为旧时序（警告 + 客户端本地配方）；注意只影响**之后**进服的玩家（详见 [`30`](30-jei-protocol.md) §5.2） |

> Servux / Syncmatica 各通道的逐步实测步骤见 [`10`](10-testing-guide.md) 与 [`24`](24-syncmatica-testing-guide.md)。

---

> **回到索引**：[00-INDEX.md](00-INDEX.md) · 项目权威说明：[../AGENTS.md](../AGENTS.md)
