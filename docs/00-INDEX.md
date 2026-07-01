# VeryMcProto · 文档总索引

> 本目录是 **VeryMcProto**（Fabric 协议 Mod → Paper 1.21.11 插件移植）的全部技术文档。
>
> **已完成移植**：
> - **Servux**（masa 服务端协议）—— 5 通道 + schematic（投影投递/粘贴）+ EasyPlace，已实测通过。
> - **JEI Recipe Bridge**（配方同步）—— 已实现。
>
> **进行中**：**Syncmatica**（投影共享中央仓库）—— 文档已就绪（20–24），实施待启动。
>
> 顶层项目说明见根目录 [`../CLAUDE.md`](../CLAUDE.md)。
> 原版 Fabric 源码对照：[`../OriginImpl/`](../OriginImpl/)（`servux` / `syncmatica` / `litematica` / `malilib` / `syncmatica` / `JEIRecipeBridge` 各子目录）。

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
| 08 | [implementation-plan.md](08-implementation-plan.md) | 实施步骤：5 个阶段（环境→网络→数据采集→各 Provider→打磨）、任务拆解、依赖关系、里程碑、验收标准 | 任务拆分 |
| 10 | [testing-guide.md](10-testing-guide.md) | **客户端兼容测试**：5 通道↔3 mod 映射、C2S 拉取模型、Litematica / Tweakeroo 测试步骤、排错流程、降级清单、结果记录表 | 实测验证 |
| 11 | [schematic-migration-plan.md](11-schematic-migration-plan.md) | schematic 子系统（投影投递+粘贴）移植的**逐阶段作战手册**：P0-P9 文件清单、降级点、编译门、状态表 | 实施蓝图 |
| — | [references.md](references.md) | 外部参考链接汇总 | 资源 |

## 推荐阅读路线（Servux）

**第一次读（建立全貌，约 30 分钟）**：
1. 根 [`../CLAUDE.md`](../CLAUDE.md) —— 项目定位与核心约束
2. 本文 `00-INDEX.md`
3. [01-servux-architecture.md](01-servux-architecture.md) —— 原版怎么组织的
4. [02-network-protocol.md](02-network-protocol.md) —— **网络层是最关键、必须先吃透的**
5. [07-migration-architecture.md](07-migration-architecture.md) §1–§3 —— 迁移总体方案与可行性结论

**动手移植时（按模块查）**：
- 要移植某条协议 → [02](02-network-protocol.md) + [03](03-dataproviders-detail.md)
- 遇到 Mixin 不知道怎么办 → [04](04-mixin-analysis.md) + [07](07-migration-architecture.md) §降级矩阵
- 搞 Litematica 投影 → [05](05-schematic-system.md)
- 不确定 Fabric 用法在 Paper 怎么写 → [06](06-fabric-vs-paper.md)
- 不知道下一步做啥 → [08](08-implementation-plan.md)

**升级 / 排错时**：
- NMS 签名漂移 → [04](04-mixin-analysis.md) 的反射点 + [02](02-network-protocol.md) 的 NMS 约束
- 网络不通 → [02](02-network-protocol.md) §字节限制 + §失败重试 + [07](07-migration-architecture.md) §网络层
- 客户端不工作 / `not_enabled` → [10](10-testing-guide.md) §2 判官逻辑 + §7 排错流程

---

# 二、Syncmatica 移植（文档 20–24）

## 一句话定位

**Syncmatica 是一个「投影共享」协议 Mod**：让多个玩家在同一服务端共享 Litematica 投影——服务端作为**中央仓库**存储 `.litematic` 文件，玩家可上传、下载、并协同修改投影的放置位置。我们要在 Paper 复刻其**单物理通道 `syncmatica:main` + 18 逻辑 PacketType + Exchange 会话层 + 文件存储 + 持久化**。

> 与 Servux（服务端→客户端**单向广播**）根本不同：syncmatica 是**客户端⇄服务端⇄客户端的双向、有状态、多玩家共享**协议。客户端仍是 syncmatica 自己的 Fabric 客户端 Mod（注入 Litematica GUI）。

## 文档地图

| # | 文档 | 内容速览 | 关键词 |
|---|---|---|---|
| 20 | [syncmatica-architecture.md](20-syncmatica-architecture.md) | 原版架构：顶层包结构、启动生命周期、Context 容器、**Exchange 会话层模型**、数据模型、5 大服务端流程、**与 Servux 的本质差异对比表**、framework 复用边界 | 架构骨架 |
| 21 | [syncmatica-protocol.md](21-syncmatica-protocol.md) ⭐ | **网络协议核心**：单通道 `[Identifier][body]` 包体、18 PacketType 全表（含 `request_download`/`mesage` 拼写陷阱）、Feature 协商、metadata/position 字段表、8 Exchange 状态机、文件分片 stop-and-wait、MD5→UUID hash、JSON 序列化 | 协议层 |
| 22 | [syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md) ⭐ | **迁移方案**：5 服务端 Mixin→Bukkit 事件逐项映射、生命周期总表、网络层迁移（通道/handler/ExchangeTarget/分片）、持久化路径映射、权限/命令/服务层迁移、降级矩阵（material 死代码）、目标包结构、依赖变更 | 方案设计 |
| 23 | [syncmatica-implementation-plan.md](23-syncmatica-implementation-plan.md) | **逐阶段作战手册**：P0–P9 文件清单 + 编译门 + 状态表、依赖与编译顺序、风险点、里程碑 M1–M8 验收 | 实施蓝图 |
| 24 | [syncmatica-testing-guide.md](24-syncmatica-testing-guide.md) | **客户端兼容测试**：环境准备（syncmatica + litematica + malilib）、握手/分享/下载/修改/删除/命令/持久化/配额/多玩家协同测试步骤、判官逻辑、排错流程、降级清单、结果记录表 | 实测验证 |

## 推荐阅读路线（Syncmatica）

**第一次读（建立全貌）**：
1. [20-syncmatica-architecture.md](20-syncmatica-architecture.md) —— **重点看 §1「与 Servux 的本质差异」对比表**，避免把它当「又一个 Servux provider」
2. [21-syncmatica-protocol.md](21-syncmatica-protocol.md) —— 重点 §1 包体复合结构 + §4 metadata 字段表 + §6 分片协议
3. [22-syncmatica-mixin-migration.md](22-syncmatica-mixin-migration.md) §1–§4 —— Mixin 映射 + 网络层迁移 + 持久化映射

**动手移植**：按 [23](23-syncmatica-implementation-plan.md) 的 **P0→P9** 顺序，每阶段过 `compileJava` 编译门。

**排错**：[24](24-syncmatica-testing-guide.md) §12 排错流程 + 症状→病因表。

---

## 约定与术语

### Servux 术语

| 术语 | 含义 |
|---|---|
| **Provider / DataProvider** | Servux 中"一条协议功能"的封装单元，每条对应一条网络通道。共 5 条 + 1 条配置主通道。 |
| **通道（channel）** | 一条原版自定义 payload 通道，形如 `servux:main`。对应客户端的一个 `ResourceLocation`。 |
| **Payload** | 一次协议消息的载荷，`record Payload(...) implements CustomPacketPayload`。 |
| **packetType** | Payload 内部用 VarInt 区分的子消息类型（如 HUD 的 `PACKET_S2C_METADATA=1`）。 |
| **PacketSplitter** | Servux 自研的应用层分包器，把超大 NBT 拆成多个 ≤1MiB 的网络包发送，接收端按 session 重组。**（syncmatica 不复用此器，见下）** |

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

> **协作约定**：所有文档互相用相对链接索引；提到原版代码时优先给出**相对路径**（`OriginImpl/<mod>-LTS-1.21.11/src/main/java/...`）与**关键行/方法名**，方便直接跳转对照。
