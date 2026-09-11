# 参考资源汇总

> 全仓库外部链接的唯一登记处（README Credits / AGENTS 参考资源只做导航指向，不重复维护）。
> 按用途分类；`OriginImpl/` 为本地对照源（已 gitignore，不入库）。

---

## 1. 关键可行性证据（已验证，迁移决策依据）

| 资源 | 用途 | 结论 |
|---|---|---|
| [FabricMC Discussion #4430 — Sending data from Spigot server to Fabric 1.21 client](https://github.com/orgs/FabricMC/discussions/4430) | Spigot/Paper ↔ Fabric 自定义通道互通 | **决定性证据**：plugin messaging channel（`namespace:path`）直接映射原版 `CustomPacketPayload`；`byte[]` = `FriendlyByteBuf` 裸字节；`sendPluginMessage`/`onPluginMessageReceived` 可直收发。见 [02](02-network-protocol.md) §1、[07](07-migration-architecture.md) §2 |
| [Bukkit `Messenger` Javadoc](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/plugin/messaging/Messenger.html) | plugin messaging 单包上限 | 1.21.x 起 `MAX_MESSAGE_SIZE = 1048576`（~1MiB）——**旧版 32768（32KiB）的说法已过时**；**真正 S2C 瓶颈是原版客户端对 `ClientboundCustomPayload`（未知通道）的 32767 字节解码上限**。字节限制教义见 [09](09-DELIVERY.md) §4 与 [../AGENTS.md](../AGENTS.md) §1 |
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
| Fabric 网络文档 | https://docs.fabricmc.net/develop/networking | 理解 `ServerPlayNetworking`/`PayloadTypeRegistry`/`CustomPacketPayload`（[02](02-network-protocol.md)） |
| Fabric Loader | https://docs.fabricmc.net/ | Mod 生命周期、`FabricLoader` |
| Mixin 文档 | https://github.com/SpongePowered/Mixin/wiki | 理解 `@Inject`/`@WrapOperation`/`@Accessor`（[04](04-mixin-analysis.md)） |
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
| PacketEvents | https://docs.packetevents.com/ / https://modrinth.com/plugin/packetevents | **EasyPlace 已实现**（`EasyPlaceListener` 拦截 `PLAYER_BLOCK_PLACEMENT` 改写 cursor 放行 + `EasyPlaceFixListener` 修正）；见 [07](07-migration-architecture.md) §4 |
| LuckPerms | https://luckperms.net/ | 权限增强（可选，替代 fabric-permissions-api；示例见 [40](40-configuration.md) §2.3） |
| Vault | https://github.com/MilkBowl/Vault | 权限/经济抽象（可选） |

---

## 6. 本仓库内对照源

| 资源 | 路径 | 用途 |
|---|---|---|
| **Servux 原版（26.1 线对照权威）** | [`../OriginImpl/servux-LTS-26.1/`](../OriginImpl/servux-LTS-26.1/) | 逐行对照（协议常量 / Handler 分发权威）；ver/1.21.11 维护线对照 `servux-LTS-1.21.11/` |
| — 网络层 | `.../network/`、`.../network/packet/` | [02](02-network-protocol.md) |
| — 数据采集 | `.../dataproviders/`、`.../loggers/` | [03](03-dataproviders-detail.md) |
| — Mixin | `.../mixin/`、`mixins.servux.json`、`servux.accesswidener` | [04](04-mixin-analysis.md) |
| — 投影系统 | `.../schematic/` | [05](05-schematic-system.md) |
| **Syncmatica 原版（26.1 线对照权威）** | [`../OriginImpl/syncmatica-LTS-26.1/`](../OriginImpl/syncmatica-LTS-26.1/) | [20](20-syncmatica-architecture.md)–[24](24-syncmatica-testing-guide.md) |
| **JEI 原版（26.1 线对照权威）** | `../OriginImpl/JustEnoughItems-26.1/`（mezz，分支 `26.1`） | [30](30-jei-protocol.md)；clone 命令与协议权威文件清单见 [../AGENTS.md](../AGENTS.md) §参考源码 |
| masa 客户端（litematica/malilib/minihud/tweakeroo） | `../OriginImpl/*-LTS-26.1/` | **协议接收端与硬门禁所在**，字段语义必查 |
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

## 8. 文档间索引速查

| 想了解 | 看这里 |
|---|---|
| 整体方案与可行性 | [07-migration-architecture.md](07-migration-architecture.md) §0 |
| 网络协议怎么收发 | [02-network-protocol.md](02-network-protocol.md) |
| 某个 Provider 采集什么 | [03-dataproviders-detail.md](03-dataproviders-detail.md) |
| 某 Mixin 怎么办 | [04-mixin-analysis.md](04-mixin-analysis.md) |
| 投影系统 | [05-schematic-system.md](05-schematic-system.md) |
| Fabric 用法在 Paper 怎么写 | [07-migration-architecture.md](07-migration-architecture.md) §7 逐域对照 |
| 命令 / 权限 / 配置 / 排错 | [40-configuration.md](40-configuration.md) |
| 26.1 迁移实录 | [09-DELIVERY.md](09-DELIVERY.md) §26.1 |
