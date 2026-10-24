# VeryMcProto · 文档总索引（1.21.11 线）

> 本目录是 **VeryMcProto**（Fabric 协议 Mod → Paper 插件移植，本线 1.21.11）的全部技术文档；本文件是 **docs/ 的唯一全量登记索引**（归档件以指针登记，清单与冻结约定见 [archive/README.md](archive/README.md)；README 文档导航节与根 AGENTS.md 文档地图只做指向路由，不重复维护清单）。
>
> **文档树三线同构**：`dev`（26.2）、`ver/26.1.2-dev`、本线（`ver/1.21.11-dev`）三条线使用同一目录结构与文件命名（内容 per-line 自含）；本线额外携带 `docs/archive/`（冻结归档件 14 件——本线历史文档存量最多，含 docs/research/ 迁移笔记全六件）。跨线 cherry-pick 时 docs 路径 1:1 对应，无需重映射。
>
> **四个协议 mod 全部已完整实现并实测通过**：
> - **Servux**（masa 服务端协议）—— 5 数据通道 + schematic（S2C 投递保留 + 粘贴同步直放）+ EasyPlace + 隐私裁剪。
> - **JEI 服务端协议**（mezz/JustEnoughItems 1.21.11 分支）—— 完整三层：配方同步（fabric/neoforge 双腿）+ jei:* 自有 10 通道 + 服务端行为层。
> - **Syncmatica**（投影共享中央仓库）—— 单通道 + Exchange 会话层 + 文件存储 + JSON 持久化。
> - **Carpet**（行为注入型 mod 的可移植子集）—— 规则框架 + `/carpet` 壳（18 规则）+ 假人 `/player` 全族 + carpet:hello 协议（HI=`"1.4.194"`）+ `/log` 日志框架（tps/mobcaps HUD）+ 五条纯计算命令（as-built 见 [carpet/architecture.md](carpet/architecture.md) 与 [carpet/protocol.md](carpet/protocol.md)）。
>
> 顶层项目说明见根 [`../AGENTS.md`](../AGENTS.md)（唯一权威；`CLAUDE.md` 已收敛为指向它的薄指针）。项目门面见根 [`../README.md`](../README.md)（英文）。原版 Fabric 源码对照：[`../OriginImpl/`](../OriginImpl/) 各子目录（详见 [references.md](references.md)）。

---

## 文档约定

- **目录结构 = 全局四件 + 模块子目录**：`docs/` 根放跨域文件（index / architecture / operations / references）；每个 mod 一个子目录（`servux/` `syncmatica/` `jei/` `carpet/`）。文件命名不带序号，新增文件永不引发重排。
- **节级锚稳定**：`src` 注释以 `docs/<路径> §X.Y` 形式锚定文档章节（全仓 grep 为准，不维护手写计数）。1:1 改名/移动文件保持原 § 编号延续；追加新节一律尾部新号、不插号；被删除的章节登记到本文件「历史锚点映射表」。
- **单一权威**：每个主题只在一个文件陈述——字节上限裸值与 NMS 坑 = [architecture.md](architecture.md) §3；Mixin 处置矩阵 = architecture.md §5；syncmatica 包结构 = [syncmatica/architecture.md](syncmatica/architecture.md) §2；运维全键 = [operations.md](operations.md)（本线曾内嵌于 README 的运维手册已全部迁入）；wire 真值 = [servux/protocol.md](servux/protocol.md) §9；外链 = [references.md](references.md)。其他位置只留一行指针，不复制。
- **已知局限 = 工单唯一载体**：各域文档的「已知局限」节就地登记行为级工单；升级窗口消费的观察项统一在 [operations.md](operations.md) §6。
- **历史 = git（另设冻结归档）**：迁移期过程记录不占文档位（查历史用 `git log` / `git revert`）；本线在 [archive/](archive/README.md) 保留 14 件冻结归档原件（活内容已蒸馏入新树）。
- **语态**：正文 = 现行线 as-built 现在时；对 26.x 形态的差异（DataTag / MOD_STRING fabric 门禁 / task 化等）以本线真值为准并显式标注「26.x 线形态」——跨线勿互抄。
- **三载体边界**（防重复漂移）：运维全键 docs 唯一权威 = operations.md；根 AGENTS.md §5 权限表 = AI 上下文自包含安全网（豁免声明）；[servux/providers.md](servux/providers.md) = 设置语义视角。
- **语言**：docs 全中文（键名/常量/命令保留英文原文）；唯一例外 README.md（英文门面）。

---

# 文档地图（19 项 = 全局 4 + servux 4 + syncmatica 3 + jei 1 + carpet 2 + archive 4 组 + 术语）

## 全局层（`docs/` 根）

| 文档 | 内容速览 |
|---|---|
| [architecture.md](architecture.md) | **全局架构与迁移参考**（01/06/07 + 04/09 活内容蒸馏归并）：分层架构与框架契约、网络框架与 S2C 投递路径（字节限制唯一权威 §3）、生命周期、**Mixin→Paper 处置矩阵（唯一权威 §5）**、Fabric↔Paper 逐域对照（原 06 并入 §6）、构建与工具链（reobf 基线 §7）、**NMS 实测坑清单（§3.6）**、防御性要点（§8） |
| [operations.md](operations.md) | **运维全键唯一权威 + 升级 SOP**：**版本字串策略（§0）**、四 mod 命令/权限（含 Perms 语义与 LuckPerms 示例）/配置全键（含 carpet.conf/carpet-logs.json 例外）/数据布局/排错速查（§1–§5，原 README §6–§12 运维手册迁入）+ 升级工作法（§6） |
| [references.md](references.md) | 外部参考链接汇总（全仓库唯一登记处） |
| [archive/](archive/README.md) | 冻结归档件 14 件（清单与冻结约定唯一权威 = archive/README.md；归档仅作历史留存备查——万一后续需要从旧文档寻找线索用，内容冻结于归档日、不代表现状） |

## Servux（`servux/`）

| 文档 | 内容速览 |
|---|---|
| [servux/protocol.md](servux/protocol.md) | **五通道 wire 规范 + §9 1.21.11 wire 真值（权威）**：Payload 模型、PacketSplitter 分片（128MB 重组预检）、收发时序；§9 协议版本全表（2/1/1/2/1）/MOD_STRING paper 形态/S2C transmit 保留与 C2S 接收移除/逐通道差异 |
| [servux/providers.md](servux/providers.md) | 5 个 Provider 的协议数据内容（NBT 字段）+ 数据采集实现 + loggers（TPS/MobCap）+ 设置语义 |
| [servux/schematic.md](servux/schematic.md) | **Litematica 投影子系统**：BitArray/Palette/Container 压缩、两级分包、四阶段传输帧、NBT 序列化、几何、粘贴（同步直放 + 镜像修复内联） |
| [servux/testing.md](servux/testing.md) | **客户端兼容测试**：5 通道↔3 mod 映射、判官逻辑、测试步骤、排错流程 |

## Syncmatica（`syncmatica/`）

| 文档 | 内容速览 |
|---|---|
| [syncmatica/architecture.md](syncmatica/architecture.md) | **架构 + 迁移记录**：与 Servux 本质差异对比表、**包结构全树（唯一权威，含逐文件迁移标注）**、双保险握手命门（含 disable→enable 卡死修复）、Exchange 会话层、数据模型、**§10 Mixin→Bukkit 迁移记录（含命门清单）** |
| [syncmatica/protocol.md](syncmatica/protocol.md) | **单通道协议**：`[Identifier][body]` 包体、18 PacketType 全表（**注意** `request_download`/`mesage` 拼写陷阱——上游原样保留，勿"修正"）、Feature 协商、Exchange 状态机、stop-and-wait 分片、MD5→UUID hash |
| [syncmatica/testing.md](syncmatica/testing.md) | **客户端兼容测试**：握手/分享/下载/修改/删除/命令/持久化/配额/多玩家协同测试步骤、判官逻辑、排错 + 症状表 |

## JEI（`jei/`）

| 文档 | 内容速览 |
|---|---|
| [jei/protocol.md](jei/protocol.md) | **完整协议**（mezz 1.21.11 分支）：三层全景、通道声明契约（isJeiOnServer 门禁）、12 通道 wire 逐字段、cheat 权限模型、配方转移算法、配方同步双触发、尺寸模型、上游源码索引、实机验证清单 |

## Carpet（`carpet/`）

| 文档 | 内容速览 |
|---|---|
| [carpet/architecture.md](carpet/architecture.md) | **本线自含 as-built**：模块装配与规则框架（18 规则）、假人子系统（ActionPack 三调用点 1.21.11 形态）、/log 日志与跨会话持久、五命令族、移植范围、**本线适配记录与验收（含掉落伤害 per-line 决策 §6.2）** |
| [carpet/protocol.md](carpet/protocol.md) | **carpet:hello 协议 as-built**（HI=`"1.4.194"`）：通道与载体（S2C NMS 直发唯一性）、消息面全表、握手时序与名册、规则同步链、偏差清单 |

> Carpet 移植范围在三线均已**终局定案**：可移植子集全部实现完毕，其余面永不纳入（各线 carpet/architecture.md §5）。

---

## 推荐阅读路线

**第一次读（建立全貌，约 30 分钟）**：
1. 根 [`../AGENTS.md`](../AGENTS.md) —— 项目定位与核心约束
2. 本文（index.md）
3. [architecture.md](architecture.md) §1–§3 —— 全局架构 + 网络框架（必须先吃透）
4. 对应 mod 的 protocol 文档（[servux/protocol.md](servux/protocol.md) / [syncmatica/protocol.md](syncmatica/protocol.md) / [jei/protocol.md](jei/protocol.md)）

**维护 / 查阅时（按主题查）**：
- 某条协议的字节布局 / 1.21.11 wire 真值 → servux/protocol §5/§9 + servux/providers
- 字节上限 / 分片常量 / NMS 坑 → architecture.md §3
- Mixin 的 Paper 处置 / 降级 → architecture.md §5（syncmatica 专属 → syncmatica/architecture §10）
- Litematica 投影 / 粘贴 → servux/schematic
- Fabric ↔ Paper 用法对照 → architecture.md §6
- 命令 / 权限 / 配置 / 排错 → operations.md

**升级 / 排错时**：
- MC 版本升级 → operations.md §6（分支模型回链 AGENTS.md）
- NMS 签名漂移 → architecture.md §3.6 坑清单
- 网络不通 / 客户端不工作 → 对应 mod 的 testing 文档 + operations.md §5 排错速查

---

## 历史锚点映射表（旧文件名 → 现路径）

> 本线重构 = NN- 编号体系 + 独立研究层 → 三线同构主题树（与 dev/26.1.2 线同构铺开；本线原 README §6–§12 运维手册同时迁入 operations.md）。映射含文件级与 § 级去向；归档原件在 [archive/](archive/README.md)。

| 旧文件 | 去向 |
|---|---|
| `00-INDEX.md` | → 本文 index.md |
| `01-servux-architecture.md` | → architecture.md §1/§2/§4 |
| `02-network-protocol.md` | → servux/protocol.md §1–§8（原号保留）+ 新 §9 真值；PacketSplitter 细节 → architecture.md §3 |
| `03-dataproviders-detail.md` | → servux/providers.md（原号保留） |
| `04-mixin-analysis.md` | → architecture.md §5（处置矩阵；**原件冻结归档**） |
| `05-schematic-system.md` | → servux/schematic.md（原号保留；本线粘贴 = 同步直放，无 task 组） |
| `06-fabric-vs-paper.md` | → **原件冻结归档**（archive/06）；逐域对照活内容 → architecture.md §6 |
| `07-migration-architecture.md` | → architecture.md 整件并入（目标架构/网络/采集/降级矩阵/构建/对照） |
| `08-implementation-plan.md` | → **原件冻结归档**（archive/08；已执行的一次性实施计划） |
| `09-DELIVERY.md` | → **原件冻结归档**（archive/09）；活内容蒸馏：NMS 坑 → architecture §3.6；投递/字节限制 → architecture §3；防御要点 → architecture §8 |
| `10-testing-guide.md` | → servux/testing.md（原号保留） |
| `11-schematic-migration-plan.md` | → **原件冻结归档**（archive/11；已执行蓝图） |
| `20-syncmatica-architecture.md` | → syncmatica/architecture.md（原号保留 + 新 §10 迁移记录） |
| `21-syncmatica-protocol.md` | → syncmatica/protocol.md（原号保留） |
| `22-syncmatica-mixin-migration.md` | → **原件冻结归档**（archive/22）；活内容蒸馏 → syncmatica/architecture §10 |
| `23-syncmatica-implementation-plan.md` | → **原件冻结归档**（archive/23）；关键决策 → syncmatica/architecture 各节 |
| `24-syncmatica-testing-guide.md` | → syncmatica/testing.md（原号保留） |
| `carpet.md` | → carpet/architecture.md（§0-§5/§7 → §0-§6）+ carpet/protocol.md（原 §3 扩展成文）+ operations.md carpet 键节（原 §8） |
| `jei.md` | → jei/protocol.md（原号保留） |
| `TECH_DEBT_AUDIT.md` | → **原件冻结归档**（archive/；审计快照） |
| `research/01-06`（六件迁移笔记） | → **原件冻结归档**（archive/research/；1.21.11 交付期研究笔记） |
| `references.md` | → references.md（原名） |
| README §6–§12（运维手册节） | → operations.md §1–§5（命令/权限/配置/数据布局/排错）；§10 Mixin 矩阵 → architecture.md §5 对位；§11 网络协议 → architecture.md §3；§14 发版与分支 → AGENTS.md + operations.md §6 |

常用 § 级速查：`09 §4`→architecture §3.6；`02 §5`→servux/protocol §5 + architecture §3.2；`07 §2`→architecture §3；`07 §4`→architecture §5.2；`07 §7`/`06`→architecture §6；`04 §1`（block 镜像）→architecture §5.1；`04 §4`（EasyPlace/潜影盒）→architecture §5.1/§5.2；`22 §3.1`→syncmatica/architecture §3.3+§10.2；`22 §12`→syncmatica/architecture §10.7；`carpet.md §3`→carpet/protocol；`carpet.md §7.2`→carpet/architecture §6.2；`carpet.md §8`→operations §1.6/§3.4；README §7.2（Perms 语义）→operations §2.2。

---

## 术语表（全局）

| 术语 | 含义 |
|---|---|
| **Provider / DataProvider** | Servux 中"一条协议功能"的封装单元，每条对应一条网络通道。共 5 条 + 1 条配置主通道。 |
| **通道（channel）** | 一条原版自定义 payload 通道，形如 `servux:hud_metadata`。 |
| **Payload** | 一次协议消息的载荷，`record Payload(...) implements CustomPacketPayload`。 |
| **packetType** | Payload 内部用 VarInt 区分的子消息类型。 |
| **PacketSplitter** | 应用层分包器（S2C 分片 32000 防客户端 32767；重组预检 128MB；syncmatica 不复用，自写 stop-and-wait）。 |
| **C2S / S2C** | Client→Server / Server→Client 方向。 |
| **NMS** | `net.minecraft.*`（Mojang 原版服务端类），Paper 经 paperweight userdev 可访问。 |
| **Mojang 名** | Mojang 全反混淆映射下的类/字段/方法名（本线产物经 reobf，但反射字符串不受 reobf 转换、运行时亦 Mojang 名）。 |
| **Exchange / ExchangeTarget / PacketType（syncmatica）** | 见 [syncmatica/architecture.md](syncmatica/architecture.md) §9 术语表。 |

> **协作约定**：所有文档互相用相对链接索引；提到原版代码时优先给出**相对路径**（`OriginImpl/<mod>-LTS-1.21.11/src/main/java/...`）与**关键行/方法名**。
