# VeryMcProto · 文档总索引

> 本目录是 **VeryMcProto**（Fabric 协议 Mod → Paper 插件移植，当前 26.1.2 线）的全部技术文档。
>
> **三个协议 mod 全部已完整实现并实测通过**：
> - **Servux**（masa 服务端协议）—— 5 数据通道 + schematic（投影投递/粘贴）+ EasyPlace。
> - **JEI 服务端协议**（最上游 mezz/JustEnoughItems 26.1，2026-09 起更换）—— 完整三层：配方同步（fabric/neoforge 双腿）+ jei:* 自有 10 通道（cheat 给/删/热键栏 + 配方转移）+ 服务端行为层。
> - **Syncmatica**（投影共享中央仓库）—— 单通道 + Exchange 会话层 + 文件存储 + JSON 持久化（实现说明见 20–24）。
>
> 顶层项目说明见根目录 [`../AGENTS.md`](../AGENTS.md)（唯一权威；`CLAUDE.md` 已收敛为指向它的薄指针）。
> 原版 Fabric 源码对照：[`../OriginImpl/`](../OriginImpl/)（`servux` / `syncmatica` / `litematica` / `malilib` / `JustEnoughItems-26.1`（mezz 最上游）/ `JEIRecipeBridge-{1.21.11,26.1}`（Mrbysco，1.21.11 线沿用 + neoforge 层参考）各子目录）。
> ⚠️ **时代标注**：文档 01–07 / 10 对原版的分析成文于 1.21.11 LTS 源码（文中协议版本等数字以该线为准）；26.1 线的协议面差异（协议版本 3/2/2/3/2、DataTag 载体、MOD_STRING 硬门禁等）以 [`09-DELIVERY.md`](09-DELIVERY.md) §26.1 与根 [`AGENTS.md`](../AGENTS.md) 为准。

---

# 一、Servux 移植（文档 01–11）

## 一句话定位

**Servux 是一个「协议 Mod」**：服务端把 masa 客户端 Mod（MiniHUD / Litematica / Tweakeroo）需要的元数据 / 投影 / 结构 / 实体数据，通过 **5 条原版自定义网络通道**（`servux:*`）投递给客户端。我们要在 Paper 服务端复刻这套**协议 + 数据采集**，让"Fabric 客户端 + Paper 服务端"等价于"Fabric 客户端 + Servux 服务端"。

## 文档地图

```
需求 (a) 原版技术细节                          需求 (b) 差异转换      需求 (c) 迁移方案    需求 (d) 实施规划
─────────────────────────                    ──────────────       ──────────────      ──────────────
01-servux-architecture.md  ─┐
02-network-protocol.md  ⭐  ─┤                06-fabric-vs-paper.md   07-migration-     08-implementation-
03-dataproviders-detail.md ─┤  (原版怎么写的)  (两边怎么不同、       architecture.md ⭐  plan.md
04-mixin-analysis.md       ─┤                 怎么转换)             (整体怎么迁、      (先做什么、后做什么、
05-schematic-system.md  ⭐ ─┘                                       可行性验证)         拆成哪些小任务)
```

| # | 文档 | 内容速览 | 关键词 |
|---|---|---|---|
| 01 | [servux-architecture.md](01-servux-architecture.md) | 启动流程、`DataProviderManager` 注册/调度/配置、`IDataProvider` 接口、生命周期事件分发、`settings` 配置项系统、`/servux` 命令 | 架构骨架 |
| 02 | [network-protocol.md](02-network-protocol.md) ⭐ | **网络层核心**：原版 `CustomPacketPayload` 模型、`Payload` record、`StreamCodec`、`PacketSplitter` 分片、5 条通道总表、字节布局、收发完整流程、失败重试 | 协议层 |
| 03 | [dataproviders-detail.md](03-dataproviders-detail.md) | 5 个 Provider 的协议数据内容（NBT 字段）+ 数据采集实现 + `loggers`（TPS/MobCap）+ 权限节点 | 数据采集 |
| 04 | [mixin-analysis.md](04-mixin-analysis.md) | 26 Mixin + 2 AccessWidener **逐项**清单：目标类、注入手法、归属功能、迁移分类、Paper 去向 | Mixin |
| 05 | [schematic-system.md](05-schematic-system.md) ⭐ | Litematica 投影系统：BitArray/Palette/Container 压缩、`SchematicBuffer` 分片传输、四阶段传输协议、几何 `Box`/`AreaSelection`、NBT 序列化、纯算法可移植性 | 大模块 |
| 06 | [fabric-vs-paper.md](06-fabric-vs-paper.md) | Fabric ↔ Paper 框架差异**对照表**：生命周期、权限、网络、配置、命令、构建、NMS 可达性 | 差异映射 |
| 07 | [migration-architecture.md](07-migration-architecture.md) ⭐ | **完整迁移方案**：目标架构、网络层迁移（plugin messaging + NMS）、数据采集迁移、Mixin 降级矩阵、字节限制方案、可行性验证（含网络文档引用） | 方案设计 |
| 08 | [implementation-plan.md](08-implementation-plan.md) | servux 历史实施蓝图（阶段 0–7 任务拆解、依赖、里程碑）；**已全部完成** | 历史蓝图 |
| 10 | [testing-guide.md](10-testing-guide.md) | **客户端兼容测试**：5 通道↔3 mod 映射、C2S 拉取模型、Litematica / Tweakeroo 测试步骤、排错流程、降级清单、结果记录表 | 实测验证 |
| 11 | [schematic-migration-plan.md](11-schematic-migration-plan.md) | schematic 子系统历史移植蓝图（P0-P9 文件清单、降级点、编译门）；**已完整移植** | 历史蓝图 |
| — | [references.md](references.md) | 外部参考链接汇总 | 资源 |

## 推荐阅读路线（Servux）

**第一次读（建立全貌，约 30 分钟）**：
1. 根 [`../AGENTS.md`](../AGENTS.md) —— 项目定位与核心约束
2. 本文 `00-INDEX.md`
3. [01-servux-architecture.md](01-servux-architecture.md) —— 原版怎么组织的
4. [02-network-protocol.md](02-network-protocol.md) —— **网络层是最关键、必须先吃透的**
5. [07-migration-architecture.md](07-migration-architecture.md) §1–§3 —— 迁移总体方案与可行性结论

**维护 / 查阅时（按模块查）**：
- 某条协议的字节布局 → [02](02-network-protocol.md) + [03](03-dataproviders-detail.md)
- Mixin 的 Paper 处置 → [04](04-mixin-analysis.md) + [07](07-migration-architecture.md) §降级矩阵
- Litematica 投影系统 → [05](05-schematic-system.md)
- Fabric ↔ Paper 用法对照 → [06](06-fabric-vs-paper.md)
- 历史实施拆解 → [08](08-implementation-plan.md) / [11](11-schematic-migration-plan.md)

**升级 / 排错时**：
- NMS 签名漂移 → [04](04-mixin-analysis.md) 的反射点 + [02](02-network-protocol.md) 的 NMS 约束
- 网络不通 → [02](02-network-protocol.md) §字节限制 + §失败重试 + [07](07-migration-architecture.md) §网络层
- 客户端不工作 / `not_enabled` → [10](10-testing-guide.md) §2 判官逻辑 + §7 排错流程

---

# 二、Syncmatica 实现（文档 20–24）

## 一句话定位

**Syncmatica 是一个「投影共享」协议 Mod**：让多个玩家在同一服务端共享 Litematica 投影——服务端作为**中央仓库**存储 `.litematic` 文件，玩家可上传、下载、并协同修改投影的放置位置。本项目在 Paper 复刻了其**单物理通道 `syncmatica:main` + 18 逻辑 PacketType + Exchange 会话层 + 文件存储 + 持久化**（已完整实现）。

> 与 Servux（服务端→客户端**单向广播**）根本不同：syncmatica 是**客户端⇄服务端⇄客户端的双向、有状态、多玩家共享**协议。客户端仍是 syncmatica 自己的 Fabric 客户端 Mod（注入 Litematica GUI）。

## 文档地图

| # | 文档 | 内容速览 | 关键词 |
|---|---|---|---|
| 20 | [syncmatica-architecture.md](20-syncmatica-architecture.md) | 实际架构：包结构、装配生命周期（双保险握手）、Context 容器、**Exchange 会话层模型**、数据模型、**与 Servux 的本质差异对比表**、framework 复用边界 | 架构骨架 |
| 21 | [syncmatica-protocol.md](21-syncmatica-protocol.md) ⭐ | **网络协议核心**：单通道 `[Identifier][body]` 包体、18 PacketType 全表（⚠️ `request_download`/`mesage` 拼写陷阱）、Feature 协商、metadata/position 字段表、Exchange 状态机、文件分片 stop-and-wait（16KB）、MD5→UUID hash | 协议层 |
| 22 | [syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md) ⭐ | **迁移实现记录**：5 服务端 Mixin→Bukkit 已落地映射、生命周期、网络层迁移（通道/handler/ExchangeTarget/S2C NMS 直发）、持久化路径、权限/命令/服务层、降级矩阵、实际包结构 | 迁移记录 |
| 23 | [syncmatica-implementation-plan.md](23-syncmatica-implementation-plan.md) | **实现总览**：实际包结构全树、关键实现决策表、完成状态、文档导航 | 实现总览 |
| 24 | [syncmatica-testing-guide.md](24-syncmatica-testing-guide.md) | **客户端兼容测试**：环境准备、握手/分享/下载/修改/删除/命令/持久化/配额/多玩家协同测试步骤、判官逻辑、排错流程 + 症状表 | 实测验证 |

## 推荐阅读路线（Syncmatica）

**第一次读（建立全貌）**：
1. [20-syncmatica-architecture.md](20-syncmatica-architecture.md) —— **重点看「与 Servux 的本质差异」对比表**，避免把它当「又一个 Servux provider」
2. [21-syncmatica-protocol.md](21-syncmatica-protocol.md) —— 包体复合结构 + metadata 字段表 + Exchange 状态机 + 分片协议
3. [22-syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md) —— Mixin 已落地映射 + 网络层 + 持久化映射

**了解实现全貌**：[23](23-syncmatica-implementation-plan.md) 实际包结构 + 关键决策表 + 完成状态。

**排错**：[24](24-syncmatica-testing-guide.md) 排错流程 + 症状→病因表。

---

# 三、JEI 服务端协议（文档 30）

## 一句话定位

**JEI（Just Enough Items）是客户端物品/配方查看 Mod**；26.1 线（实为 1.21.2+）配方表收敛到服务端后，JEI 的完整功能依赖「服务端协议」：配方同步（Fabric API / NeoForge 加载器层通道）+ jei:* 自有通道（cheat 权限/给删物品/热键栏/配方转移）。上游 = 最上游 mezz/JustEnoughItems 分支 `26.1`（2026-09 起正式更换，原 Mrbysco/JEIRecipeBridge 已停更）；1.21.11 旧线仍用旧实现。

## 文档地图

| # | 文档 | 内容速览 | 关键词 |
|---|---|---|---|
| 30 | [jei-protocol.md](30-jei-protocol.md) ⭐ | **完整协议**：三层全景、通道声明契约（isJeiOnServer 门禁）、12 通道 wire 逐字段、cheat 权限模型、配方转移算法、配方同步双触发、尺寸模型、上游源码索引、实机验证清单 | 协议层 |

**维护入口**：[30](30-jei-protocol.md) 一篇全覆盖；上游更新时按其 §10 源码索引对照 `OriginImpl/JustEnoughItems-26.1/` 逐文件核对。

---

## 约定与术语

### Servux 术语

| 术语 | 含义 |
|---|---|
| **Provider / DataProvider** | Servux 中"一条协议功能"的封装单元，每条对应一条网络通道。共 5 条 + 1 条配置主通道。 |
| **通道（channel）** | 一条原版自定义 payload 通道，形如 `servux:hud_metadata`。对应客户端的一个 `ResourceLocation`。 |
| **Payload** | 一次协议消息的载荷，`record Payload(...) implements CustomPacketPayload`。 |
| **packetType** | Payload 内部用 VarInt 区分的子消息类型（如 HUD 的 `PACKET_S2C_METADATA=1`）。 |
| **PacketSplitter** | Servux 自研的应用层分包器，把超大 NBT 拆成多个 ≤32000 字节的网络包发送（S2C `MAX_TOTAL_PER_PACKET_S2C=32000`，防御客户端 32767 解码上限），接收端按 session 重组。**（syncmatica 不复用此器，自写 stop-and-wait）** |

### Syncmatica 术语

| 术语 | 含义 |
|---|---|
| **Exchange** | syncmatica 的「一个跨多包、有明确目标的双端通信会话」抽象（请求-应答状态机）。与 Servux 的 Provider 推送模型**根本不同**。 |
| **ExchangeTarget** | 「一个连接」的抽象（服务端 = 一个玩家），持 `ongoingExchanges` 列表 + `FeatureSet` + `sendPacket`。 |
| **PacketType** | 18 个逻辑消息类型（`REGISTER_METADATA` / `SEND_LITEMATIC` / ...），**复用同一物理通道** `syncmatica:main`（对比 Servux 每功能一通道）。物理包体 = `[逻辑通道 Identifier][body]`。 |
| **placement / ServerPlacement** | 服务端存储的一个投影放置（含文件 hash + origin + 旋转镜像 + owner 等）。 |
| **hash** | 投影文件内容的 MD5 → type-3 UUID（`UUID.nameUUIDFromBytes(md5)`），用作内容寻址键与去重。 |
| **Feature / FeatureSet** | 协议特性（9 个枚举）与其集合；握手时协商，决定 metadata 编码哪些可选字段（`DISPLAY_NAME`/`CORE_EX`/`VERSION`/`MODIFY`）。 |
| **broadcastTargets** | 已完成握手的 ExchangeTarget 集合，placement 变更时广播给全部。 |

### 通用术语

| 术语 | 含义 |
|---|---|
| **C2S / S2C** | Client→Server / Server→Client 方向。 |
| **NMS** | `net.minecraft.*`（Mojang 原版服务端类），Paper 经 paperweight userdev 可访问。 |
| **Mojang 名** | Mojang 全反混淆映射下的类/字段/方法名（`reobf` 不转换反射字符串，故反射用 Mojang 名）。 |

> **协作约定**：所有文档互相用相对链接索引；提到原版代码时优先给出**相对路径**（`OriginImpl/<mod>-LTS-26.1/src/main/java/...`）与**关键行/方法名**，方便直接跳转对照。
