# Servux → Paper 移植 · 文档总索引

> 本目录是 **Servux（Fabric 协议 Mod）→ Paper 1.21.11 插件**移植的全部技术文档。
> 顶层项目说明见根目录 [`../CLAUDE.md`](../CLAUDE.md)。
> 原版 Fabric 源码对照：[`../OriginImpl/servux-LTS-1.21.11/`](../OriginImpl/servux-LTS-1.21.11/)（包 `fi.dy.masa.servux`）。

---

## 一句话定位

**Servux 是一个「协议 Mod」**：服务端把 masa 客户端 Mod（MiniHUD / Litematica / Tweakeroo）需要的元数据 / 投影 / 结构 / 实体数据，通过 **5 条原版自定义网络通道**（`servux:*`）投递给客户端。我们要在 Paper 服务端复刻这套**协议 + 数据采集**，让"Fabric 客户端 + Paper 服务端"等价于"Fabric 客户端 + Servux 服务端"。

---

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
| — | [references.md](references.md) | 外部参考链接汇总 | 资源 |

---

## 推荐阅读路线

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

## 约定与术语

| 术语 | 含义 |
|---|---|
| **Provider / DataProvider** | Servux 中"一条协议功能"的封装单元，每条对应一条网络通道。共 5 条 + 1 条配置主通道。 |
| **通道（channel）** | 一条原版自定义 payload 通道，形如 `servux:main`。对应客户端的一个 `ResourceLocation`。 |
| **Payload** | 一次协议消息的载荷，`record Payload(...) implements CustomPacketPayload`。 |
| **packetType** | Payload 内部用 VarInt 区分的子消息类型（如 HUD 的 `PACKET_S2C_METADATA=1`）。 |
| **PacketSplitter** | Servux 自研的应用层分包器，把超大 NBT 拆成多个 ≤1MiB 的网络包发送，接收端按 session 重组。 |
| **C2S / S2C** | Client→Server / Server→Client 方向。 |
| **NMS** | `net.minecraft.*`（Mojang 原版服务端类），Paper 经 paperweight userdev 可访问。 |
| **Mojang 名** | Mojang 全反混淆映射下的类/字段/方法名（`reobf` 不转换反射字符串，故反射用 Mojang 名）。 |

> **协作约定**：所有文档互相用相对链接索引；提到原版代码时优先给出**相对路径**（`OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/...`）与**关键行/方法名**，方便直接跳转对照。
