# VeryMcProto · 文档总索引

> 本目录是 **VeryMcProto**（Fabric 协议 Mod → Paper 插件移植，当前 26.2 线）的全部技术文档；本文件是 **docs/ 的唯一全量登记索引**（README 文档导航节与根 AGENTS.md 文档地图只做指向路由，不重复维护清单）。
>
> **三个协议 mod 全部已完整实现并实测通过**：
> - **Servux**（masa 服务端协议）—— 5 数据通道 + schematic（投影粘贴）+ EasyPlace + task 组（Fill/Delete/Paste）。
> - **JEI 服务端协议**（最上游 mezz/JustEnoughItems 分支 `26.2`）—— 完整三层：配方同步（fabric/neoforge 双腿）+ jei:* 自有 10 通道 + 服务端行为层。
> - **Syncmatica**（投影共享中央仓库）—— 单通道 + Exchange 会话层 + 文件存储 + JSON 持久化。
>
> 顶层项目说明见根 [`../AGENTS.md`](../AGENTS.md)（唯一权威；`CLAUDE.md` 已收敛为指向它的薄指针）。项目门面见根 [`../README.md`](../README.md)（英文）。
> 原版 Fabric 源码对照：[`../OriginImpl/`](../OriginImpl/) 各子目录（详见 [references.md](references.md) §6）。

---

## 文档约定

- **文件命名不带序号，新增文件永不引发重排**：文件名 = 主题名（mod 前缀分组，如 `servux-*` / `syncmatica-*` / `jei`）；插入/删除文件不影响任何既有链接（2026-10 文档重构废除旧 `NN-` 数字前缀体系）。
- **节级锚稳定**：`src` 注释以 `docs/<文件名> §X.Y` 形式锚定文档章节（约 25 处）。1:1 改名文件保持原 § 编号延续；既有文件追加新节一律**追加尾部新号、不插号**；被删除的章节登记到本文件「历史锚点映射表」。
- **单一权威**：每个主题只在一个文件陈述——字节上限裸值唯一权威 = [architecture.md](architecture.md) §3.4；Mixin 处置唯一权威 = architecture.md §5；syncmatica 包结构唯一权威 = [syncmatica-architecture.md](syncmatica-architecture.md) §2；运维全键唯一权威 = [operations.md](operations.md)；外链唯一登记处 = [references.md](references.md)。其他位置只留一行指针，不复制裸值/处置行。
- **已知局限 = 工单唯一载体**：不设中央工单登记——各域文档的「已知局限」节就地登记行为级工单；升级窗口消费的观察项统一在 [operations.md](operations.md) §6.9。
- **历史 = git**：迁移期过程记录（蓝图、逐阶段计划、实录流水、历史 wire 记载）不占文档位；查历史用 `git log` / `git revert`。蒸馏后的方法论以 SOP/参考手册形态存续（operations.md §6 升级 SOP、architecture.md §5/§6 迁移参考）。
- **语态**：正文 = 现行线 as-built 现在时；跨版本兼容知识（协议常量、MOD_STRING 门禁、DataTag 分界）入协议文档「版本约束」节并保留上游来源树行号标注。
- **三载体边界**（防重复漂移）：运维全键 docs 唯一权威 = operations.md；根 AGENTS.md §5 权限表 = AI 上下文自包含安全网（豁免声明——AGENTS.md 是 AI 助手唯一必读文件）；[servux-providers.md](servux-providers.md) = 设置语义视角。
- **per-branch 命名差异**：旧维护线（`ver/26.1.2`、`ver/1.21.11`）的 docs/ 仍是旧 `NN-` 编号体系——**跨线 cherry-pick / backport 时，注释中的 docs 引用须按下文映射表按内容定位重映射**（旧线文件名与新线不同，错配会"响亮失败"而非静默错向，属预期防护）。
- **语言**：docs 全中文（键名/常量/命令保留英文原文）；唯一例外 README.md（英文门面）。

---

# 文档地图（12 文件）

## 全局层

| 文档 | 内容速览 |
|---|---|
| [architecture.md](architecture.md) ⭐ | **全局架构与迁移参考**：分层架构与框架契约（DataProviderManager/IDataProvider/settings）、网络框架与三种 S2C 投递路径、PacketSplitter 分片与字节限制裸值（唯一权威）、生命周期映射、**Mixin→Paper 统一处置矩阵（唯一权威）**、Fabric↔Paper 逐域对照、构建工具链、NMS 实测坑清单 |
| [operations.md](operations.md) ⭐ | **运维全键唯一权威 + 升级 SOP**：三 mod 命令/权限/配置全键/数据布局/排错速查（§1–§5）+ MC 版本升级标准作业程序、已知漂移模式表、验证判据索引、观察项（§6） |
| [references.md](references.md) | 外部参考链接汇总（全仓库唯一登记处） |

## Servux（`servux-*`）

| 文档 | 内容速览 |
|---|---|
| [servux-protocol.md](servux-protocol.md) ⭐ | **五通道 wire 规范**：通道总表、Payload 模型、**版本约束与安全闸**（MOD_STRING 门禁/协议常量/DataTag 分界/C2S 双闸）、收发流程与版本门禁、与上游逐通道差异 |
| [servux-providers.md](servux-providers.md) | 5 个 Provider 的协议数据内容（NBT 字段）+ 数据采集实现 + loggers（TPS/MobCap）+ 设置语义 |
| [servux-schematic.md](servux-schematic.md) ⭐ | **Litematica 投影子系统**：BitArray/Palette/Container 压缩、C2S 上传两级分包、体积一致性预检、实体位置修复族、**Task 调度器与 paste 任务化 as-built** |
| [servux-testing.md](servux-testing.md) | **客户端兼容测试**：5 通道↔3 mod 映射、判官逻辑、Litematica/Tweakeroo 测试步骤、上游对齐回归清单、实测验收要点、排错流程 |

## Syncmatica（`syncmatica-*`）

| 文档 | 内容速览 |
|---|---|
| [syncmatica-architecture.md](syncmatica-architecture.md) ⭐ | **架构 + 迁移记录**：与 Servux 本质差异对比表、**包结构全树（唯一权威，含逐文件迁移标注）**、双保险握手命门、Exchange 会话层、数据模型、Mixin→Bukkit 迁移记录、关键决策速查、命门清单、已知局限 |
| [syncmatica-protocol.md](syncmatica-protocol.md) ⭐ | **单通道协议**：`[Identifier][body]` 包体、18 PacketType 全表（⚠️ `request_download`/`mesage` 拼写陷阱）、Feature 协商、metadata/position 字段表、Exchange 状态机、stop-and-wait 分片、MD5→UUID hash |
| [syncmatica-testing.md](syncmatica-testing.md) | **客户端兼容测试**：握手/分享/下载/修改/删除/命令/持久化/配额/多玩家协同测试步骤、判官逻辑、排错 + 症状表 |

## JEI

| 文档 | 内容速览 |
|---|---|
| [jei.md](jei.md) ⭐ | **完整协议**：三层全景、通道声明契约（isJeiOnServer 门禁）、12 通道 wire 逐字段、cheat 权限模型、配方转移算法、配方同步双触发、尺寸模型、上游源码索引、实机验证清单 |

---

## 推荐阅读路线

**第一次读（建立全貌，约 30 分钟）**：
1. 根 [`../AGENTS.md`](../AGENTS.md) —— 项目定位与核心约束
2. 本文（index.md）
3. [architecture.md](architecture.md) §1–§3 —— 全局架构 + 网络框架（最关键，必须先吃透）
4. 对应 mod 的 protocol 文档（[servux-protocol.md](servux-protocol.md) / [syncmatica-protocol.md](syncmatica-protocol.md) / [jei.md](jei.md)）

**维护 / 查阅时（按主题查）**：
- 某条协议的字节布局 → servux-protocol + servux-providers
- 字节上限 / 分片常量 → architecture.md §3.3–3.4（唯一权威）
- Mixin 的 Paper 处置 / 降级 → architecture.md §5
- Litematica 投影 / task 组 → servux-schematic
- Fabric ↔ Paper 用法对照 → architecture.md §6
- 命令 / 权限 / 配置 / 排错 → operations.md

**升级 / 排错时**：
- MC 版本升级 → operations.md §6 升级 SOP（含分支模型回链 AGENTS.md）
- NMS 签名漂移 → architecture.md §3.6 坑清单
- 网络不通 / 客户端不工作 → 对应 mod 的 testing 文档 + operations.md §5 排错速查

---

## 历史锚点映射表（旧编号 → 新文件，跨线 backport 必读）

> 2026-10 文档重构：17 文件（`NN-` 前缀）→ 12 文件（无前缀）。旧维护线仍用旧名；cherry-pick 注释锚点时按本表**按内容**重映射。整体历史（含迁移实录原文）见 git 历史。

| 旧文件 | 去向 |
|---|---|
| `00-INDEX.md` | → 本文 index.md |
| `01-servux-architecture.md` | 框架契约（§3/§4/§6/§8）→ architecture.md §2；§9 MOD_STRING → servux-protocol.md §5.1；其余对照内容 → architecture.md §5/§6 |
| `02-network-protocol.md` | §1–§4/§6–§8（保号）→ servux-protocol.md；§5 PacketSplitter → architecture.md §3.3–3.4 |
| `03-dataproviders-detail.md` | → servux-providers.md（保号） |
| `04-mixin-analysis.md` | → architecture.md §5（统一 Mixin 处置矩阵） |
| `05-schematic-system.md` | → servux-schematic.md（保号 §0–§8 + 新 §9 task 组）；历史 wire §3.1/§3.2 → git 历史 |
| `07-migration-architecture.md` | §0/§1/§2/§5/§6/§7/§9 → architecture.md；§3 → architecture.md §6.4 + servux-providers；§4 → architecture.md §5.3 |
| `09-DELIVERY.md` | §1/§3/§4/§7 → architecture.md §2/§3；§5 → servux-protocol.md §8；§6 → architecture.md §5.3；§10 → servux-testing.md §10；§26.1/§26.2 实录 → operations.md §6 升级 SOP（方法论提炼）+ servux-schematic.md §9（task 组 as-built）+ servux-protocol.md §5（wire 真值） |
| `10-testing-guide.md` | → servux-testing.md（保号 + 新 §10 验收要点） |
| `20-syncmatica-architecture.md` | → syncmatica-architecture.md（保号 §0–§9 + 新 §10–§13） |
| `21-syncmatica-protocol.md` | → syncmatica-protocol.md（保号） |
| `22-syncmatica-mixin-migration.md` | Mixin 处置 → architecture.md §5.4 概览；非永续半部（命门/网络/持久化/服务层/降级/命门清单）→ syncmatica-architecture.md §3.3/§7/§8/§10/§12 |
| `23-syncmatica-implementation-plan.md` | 决策表 → syncmatica-architecture.md §11（文件消亡） |
| `24-syncmatica-testing-guide.md` | → syncmatica-testing.md（保号） |
| `30-jei-protocol.md` | → jei.md（保号） |
| `40-configuration.md` | → operations.md（保号 §1–§5 + 新 §6 升级 SOP） |
| `references.md` | → references.md（原名重写） |

常用 § 级速查：`09 §26.1.5/§26.1.6`→servux-schematic §9；`09 §5.5`→servux-protocol §8.5；`09 §4`→architecture §3.6；`09 §10.6`→servux-testing §10；`02 §5`→architecture §3.3；`07 §4`→architecture §5.3；`07 §7`→architecture §6；`22 §3.1`→syncmatica-architecture §3.3；`22 §12`→syncmatica-architecture §12；`03 §1.2`→servux-providers §1.2。

---

## 术语表（全局）

| 术语 | 含义 |
|---|---|
| **Provider / DataProvider** | Servux 中"一条协议功能"的封装单元，每条对应一条网络通道。共 5 条 + 1 条配置主通道。 |
| **通道（channel）** | 一条原版自定义 payload 通道，形如 `servux:hud_metadata`。对应客户端的一个 `Identifier`。 |
| **Payload** | 一次协议消息的载荷，`record Payload(...) implements CustomPacketPayload`。 |
| **packetType** | Payload 内部用 VarInt 区分的子消息类型（如 HUD 的 `PACKET_S2C_METADATA=1`）。 |
| **PacketSplitter** | 应用层分包器，把超大 NBT 拆成多个 ≤32000 字节的网络包发送，接收端按 session 重组（常量唯一权威 architecture.md §3.4；syncmatica 不复用，自写 stop-and-wait）。 |
| **C2S / S2C** | Client→Server / Server→Client 方向。 |
| **NMS** | `net.minecraft.*`（Mojang 原版服务端类），Paper 经 paperweight userdev 可访问。 |
| **Mojang 名** | Mojang 全反混淆映射下的类/字段/方法名（26.1 起产物与 Paper 运行时同为 Mojang 名，反射直接用）。 |

> Syncmatica 域术语（Exchange / ExchangeTarget / PacketType / placement / hash / Feature / broadcastTargets）见 [syncmatica-architecture.md](syncmatica-architecture.md) §9。
>
> **协作约定**：所有文档互相用相对链接索引；提到原版代码时优先给出**相对路径**（`OriginImpl/<mod>-LTS-26.2/src/main/java/...`）与**关键行/方法名**，方便直接跳转对照。
