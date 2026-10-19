# docs/archive/ —— 冻结归档（1.21.11 线）

> 本目录是本线文档体系重构（NN- 编号时代 + 独立研究层 → 三线同构主题树）时保留的**历史原件**。归档件**内容冻结于归档日**：出链零翻新（文中 docs/ 链接指向归档时的旧路径，解码见 [index.md](../index.md) 历史锚点映射表）；活内容已按「描述现役行为/维护必读」判据蒸馏入新树对应文件。查更早历史用 `git log` / `git revert`。

## 归档清单（唯一权威）

| 归档件 | 归档原因 | 现行对等文档 |
|---|---|---|
| [04-mixin-analysis.md](04-mixin-analysis.md) | Servux 26 Mixin + 2 AW 逐项原始全表 | [architecture.md](../architecture.md) §5（处置矩阵唯一权威） |
| [06-fabric-vs-paper.md](06-fabric-vs-paper.md) | Fabric↔Paper 框架差异逐域对照原始全表 | [architecture.md](../architecture.md) §6（逐域对照） |
| [08-implementation-plan.md](08-implementation-plan.md) | 已执行的一次性实施计划 | 无（已完成使命；现行架构 = [architecture.md](../architecture.md) as-built） |
| [09-DELIVERY.md](09-DELIVERY.md) | 投递/字节限制/逐通道差异原始全表 | [architecture.md](../architecture.md) §3/§3.6/§8 · [servux/protocol.md](../servux/protocol.md) §9 |
| [11-schematic-migration-plan.md](11-schematic-migration-plan.md) | 已执行的投影子系统移植蓝图 | [servux/schematic.md](../servux/schematic.md)（as-built） |
| [22-syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md) | Syncmatica 5 Mixin 迁移原始全表 + 命门清单 | [syncmatica/architecture.md](../syncmatica/architecture.md) §10 |
| [23-syncmatica-implementation-plan.md](23-syncmatica-implementation-plan.md) | 实现总览快照 | [syncmatica/architecture.md](../syncmatica/architecture.md) 全文 |
| [TECH_DEBT_AUDIT.md](TECH_DEBT_AUDIT.md) | 技术债审计快照 | 无（审计时点记录；现存观察项 = 各域「已知局限」节） |
| [research/01-schematic-algo.md](research/01-schematic-algo.md) | 1.21.11 交付期迁移研究笔记（投影算法） | [servux/schematic.md](../servux/schematic.md)（as-built 承载结论） |
| [research/02-schematic-world.md](research/02-schematic-world.md) | 迁移研究笔记（投影世界交互） | 同上 |
| [research/03-loggers.md](research/03-loggers.md) | 迁移研究笔记（loggers） | [servux/providers.md](../servux/providers.md) |
| [research/04-other-packets.md](research/04-other-packets.md) | 迁移研究笔记（Entities/Tweaks/Structures/Litematics） | [servux/protocol.md](../servux/protocol.md) / [servux/providers.md](../servux/providers.md) |
| [research/05-settings-commands-util.md](research/05-settings-commands-util.md) | 迁移研究笔记（settings/commands/util） | [servux/providers.md](../servux/providers.md) · [operations.md](../operations.md) |
| [research/06-lifecycle-dataproviders.md](research/06-lifecycle-dataproviders.md) | 迁移研究笔记（生命周期 + Provider 层） | [architecture.md](../architecture.md) §2/§4 |

## 冻结约定

- 归档件头部统一带 🧊 冻结声明（本目录 README 为声明与清单的唯一权威，index.md 只留指针）。
- 归档件**永不追改**（错字也不修）；如需引用其内容，复制到现行文档并更新，不回改原件。
- 本目录新增归档须在本表登记（新增归档 = 该件内容已被现行文档取代且不再描述现役行为）。
