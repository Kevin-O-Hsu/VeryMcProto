# 参考资源汇总

> 全仓库外部链接的唯一登记处（README Credits / AGENTS 参考资源只做导航指向，不重复维护）。
> 按用途分类；`OriginImpl/` 为本地对照源（已 gitignore，不入库）。

---

## 1. 关键可行性证据（已验证，迁移决策依据）

| 资源 | 用途 | 结论 |
|---|---|---|
| [FabricMC Discussion #4430 — Sending data from Spigot server to Fabric 1.21 client](https://github.com/orgs/FabricMC/discussions/4430) | Spigot/Paper ↔ Fabric 自定义通道互通 | **决定性证据**：plugin messaging channel（`namespace:path`）直接映射原版 `CustomPacketPayload`；`byte[]` = `FriendlyByteBuf` 裸字节；`sendPluginMessage`/`onPluginMessageReceived` 可直收发。见 [servux-protocol.md](servux-protocol.md) §1、[architecture.md](architecture.md) §3 |
| [Bukkit `Messenger` Javadoc](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/plugin/messaging/Messenger.html) | plugin messaging 单包上限 | 1.21.x 起 `MAX_MESSAGE_SIZE = 1048576`（~1MiB）——**旧版 32768（32KiB）的说法已过时**；**真正 S2C 瓶颈是原版客户端对 `ClientboundCustomPayload`（未知通道）的 32767 字节解码上限**。字节限制教义见 [architecture.md](architecture.md) §3.4 与 [../AGENTS.md](../AGENTS.md) §1 |
| [SpigotMC — How to get around 32767 byte limit for plugin messaging](https://www.spigotmc.org/threads/how-to-get-around-32767-byte-limit-for-plugin-messaging.256652/) | 大包社区解法（历史参考） | 分片重组（与 PacketSplitter 思路一致；该帖语境是客户端 32767 解码上限，非 Bukkit Messenger 限制） |

---

## 2. Paper / Bukkit 官方文档

| 资源 | 链接 | 用途 |
|---|---|---|
| Paper 开发文档总览 | https://docs.papermc.io/paper/dev/ | 开发起步 |
| **Paper 插件消息通道** | https://docs.papermc.io/paper/dev/plugin-messaging/ | **网络层迁移主参考**（registerIncoming/Outgoing、PluginMessageListener） |
| PaperWeight 指南 | https://github.com/PaperMC/paperweight | userdev 构建、dev bundle（26.1 起 reobf 废除） |
| Spigot Javadocs | https://hub.spigotmc.org/javadocs/spigot/ | Bukkit API（事件/权限/Messenger） |

---

## 3. Fabric / Mixin（理解原版用）

| 资源 | 链接 | 用途 |
|---|---|---|
| Fabric 网络文档 | https://docs.fabricmc.net/develop/networking | 理解 `ServerPlayNetworking`/`PayloadTypeRegistry`/`CustomPacketPayload`（[servux-protocol.md](servux-protocol.md)） |
| Fabric Loader | https://docs.fabricmc.net/ | Mod 生命周期、`FabricLoader` |
| Mixin 文档 | https://github.com/SpongePowered/Mixin/wiki | 理解 `@Inject`/`@WrapOperation`/`@Accessor`（[architecture.md](architecture.md) §5） |
| MixinExtras | https://github.com/LlamaLad7/MixinExtras | `@WrapOperation`/`@Local` 等（Servux 用到） |

---

## 4. Minecraft 协议

| 资源 | 链接 | 用途 |
|---|---|---|
| Minecraft Protocol Wiki（wiki.vg） | https://wiki.vg/Protocol | 原版协议总览 |
| wiki.vg — Custom Payload 包 | https://wiki.vg/Protocol#Custom_Payload | `ClientboundCustomPayloadPacket`/`ServerboundCustomPayloadPacket` 结构与上限 |
| Minecraft Wiki — Java Edition protocol/Packets | https://minecraft.wiki/w/Java_Edition_protocol/Packets | 协议号、包清单 |

---

## 5. 第三方库（选做项）

| 资源 | 链接 | 用途 |
|---|---|---|
| PacketEvents | https://docs.packetevents.com/ / https://modrinth.com/plugin/packetevents | **EasyPlace 已实现**（`EasyPlaceListener` 拦截 `PLAYER_BLOCK_PLACEMENT` 改写 cursor 放行 + `EasyPlaceFixListener` 修正）；见 [architecture.md](architecture.md) §5.3 |
| LuckPerms | https://luckperms.net/ | 权限增强（可选，替代 fabric-permissions-api；示例见 [operations.md](operations.md) §2.3） |
| Vault | https://github.com/MilkBowl/Vault | 权限/经济抽象（可选） |

---

## 6. 本仓库内对照源

| 资源 | 路径 | 用途 |
|---|---|---|
| **Servux 原版（26.2 线对照权威）** | [`../OriginImpl/servux-LTS-26.2/`](../OriginImpl/servux-LTS-26.2/) | 逐行对照（协议常量 / Handler 分发权威）；ver/1.21.11 维护线对照 `servux-LTS-1.21.11/`（26.1 树保留在盘，不再是 dev 线权威） |
| — 网络层 | `.../network/`、`.../network/packet/` | [servux-protocol.md](servux-protocol.md) |
| — 数据采集 | `.../dataproviders/`、`.../loggers/` | [servux-providers.md](servux-providers.md) |
| — Mixin | `.../mixin/`、`mixins.servux.json`、`servux.accesswidener` | [architecture.md](architecture.md) §5 |
| — 投影系统 | `.../schematic/` | [servux-schematic.md](servux-schematic.md) |
| **Syncmatica 原版（26.2 线对照权威）** | [`../OriginImpl/syncmatica-LTS-26.2/`](../OriginImpl/syncmatica-LTS-26.2/) | [syncmatica-architecture.md](syncmatica-architecture.md)–[syncmatica-testing.md](syncmatica-testing.md) |
| **JEI 原版（26.2 线对照权威）** | `../OriginImpl/JustEnoughItems-26.2/`（mezz，分支 `26.2`） | [jei.md](jei.md)；clone 命令与协议权威文件清单见 [../AGENTS.md](../AGENTS.md) §参考源码 |
| masa 客户端（litematica/malilib/minihud/tweakeroo） | `../OriginImpl/*-LTS-26.2/` | **协议接收端与硬门禁所在**，字段语义必查 |
| **姊妹项目 VeryMcBot（paperweight+NMS 范式参考）** | `I:\Programming\VeryMcBot` | `build.gradle.kts`（userdev）、`reflect/Reflect`（反射工具）、其自身 CLAUDE.md（文档风格） |
| 本项目权威说明 | [`../AGENTS.md`](../AGENTS.md) | 架构、分支/版本模型、核心设计约束、工作约定 |

---

## 7. Servux 上游

| 资源 | 链接 |
|---|---|
| Servux CurseForge | https://www.curseforge.com/minecraft/mc-mods/servux |
| Servux 源码（maruohon） | https://github.com/maruohon/servux |
| Servux 源码（sakura-ryoko，LTS 维护） | https://github.com/sakura-ryoko/servux |
| 作者 masa | https://twitter.com/maruohon |
| masa 客户端 Mod（MiniHUD/Litematica/Tweakeroo） | https://masa.dy.fi/mcmods/client_mods/ |

---

## 8. 文档索引速查（12 文件）

> 全量登记与阅读路线见 [index.md](index.md)；此处仅一句话速查。

| 文档 | 一句话 |
|---|---|
| [index.md](index.md) | **文档总索引**：全量登记、文档约定、推荐阅读路线、历史锚点映射表（跨线 backport 必读） |
| [architecture.md](architecture.md) | **全局架构与迁移参考**：分层框架、网络框架与三种 S2C 投递路径、字节限制裸值（§3.4 唯一权威）、生命周期、Mixin→Paper 统一处置矩阵（§5）、逐域对照、构建工具链 |
| [servux-protocol.md](servux-protocol.md) | **Servux 五通道 wire 规范**：通道总表、Payload 模型、版本约束与安全闸（MOD_STRING 门禁/协议常量/DataTag 分界）、收发流程、与上游逐通道差异 |
| [servux-providers.md](servux-providers.md) | 5 个 Provider 的协议数据内容（NBT 字段）+ 数据采集实现 + loggers（TPS/MobCap）+ 设置语义 |
| [servux-schematic.md](servux-schematic.md) | **Litematica 投影子系统**：BitArray/Palette 压缩、C2S 上传两级分包、体积一致性预检、实体位置修复族、Task 调度器与 paste 任务化 |
| [servux-testing.md](servux-testing.md) | **Servux 客户端兼容测试**：5 通道↔3 mod 映射、判官逻辑、Litematica/Tweakeroo 测试步骤、上游对齐回归清单、实测验收要点、排错 |
| [syncmatica-architecture.md](syncmatica-architecture.md) | **Syncmatica 架构 + 迁移记录**：与 Servux 本质差异、包结构全树（唯一权威）、双保险握手、Exchange 会话层、数据模型、Mixin→Bukkit 迁移、关键决策 |
| [syncmatica-protocol.md](syncmatica-protocol.md) | **Syncmatica 单通道协议**：`[Identifier][body]` 包体、18 PacketType 全表（拼写陷阱）、Feature 协商、字段表、Exchange 状态机、stop-and-wait 分片、hash |
| [syncmatica-testing.md](syncmatica-testing.md) | **Syncmatica 客户端兼容测试**：握手/分享/下载/修改/删除/命令/持久化/配额/多玩家步骤、判官逻辑、排错 + 症状表 |
| [jei.md](jei.md) | **JEI 完整协议**：三层全景、通道声明契约（isJeiOnServer 门禁）、12 通道 wire 逐字段、cheat 权限模型、配方转移算法、进服时序整形、尺寸模型、上游源码索引 |
| [operations.md](operations.md) | **运维全键唯一权威 + 升级 SOP**：三 mod 命令/权限/配置全键/数据布局/排错速查（§1–§5）+ MC 版本升级标准作业程序（§6） |
| [references.md](references.md) | 本文件：外部参考链接全仓库唯一登记处 |
