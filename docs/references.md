# 参考资源汇总

> 移植过程中查阅/可查阅的外部资源与本仓库内对照源。按用途分类。

---

## 1. 关键可行性证据（已验证，迁移决策依据）

| 资源 | 用途 | 结论 |
|---|---|---|
| [FabricMC Discussion #4430 — Sending data from Spigot server to Fabric 1.21 client](https://github.com/orgs/FabricMC/discussions/4430) | Spigot/Paper ↔ Fabric 自定义通道互通 | **决定性证据**：plugin messaging channel（`namespace:path`）直接映射原版 `CustomPacketPayload`；`byte[]` = `FriendlyByteBuf` 裸字节；`sendPluginMessage`/`onPluginMessageReceived` 可直收发。见 [02](02-network-protocol.md) §1、[07](07-migration-architecture.md) §2 |
| [Bukkit `Messenger` Javadoc](https://helpch.at/docs/1.6.2/org/bukkit/plugin/messaging/Messenger.html) | plugin messaging 单包上限 | `MAX_MESSAGE_SIZE = 32768`（32 KiB）、`MAX_CHANNEL_SIZE`。见 [02](02-network-protocol.md) §5.2、[07](07-migration-architecture.md) §2.2 |
| [SpigotMC — How to get around 32767 byte limit for plugin messaging](https://www.spigotmc.org/threads/how-to-get-around-32767-byte-limit-for-plugin-messaging.256652/) | 32KiB 限制的社区解法 | 分片重组（与 Servux PacketSplitter 思路一致） |

---

## 2. Paper / Bukkit 官方文档

| 资源 | 链接 | 用途 |
|---|---|---|
| Paper 开发文档总览 | https://docs.papermc.io/paper/dev/ | 开发起步 |
| **Paper 插件消息通道** | https://docs.papermc.io/paper/dev/plugin-messaging/ | **网络层迁移主参考**（registerIncoming/Outgoing、PluginMessageListener） |
| PaperWeight 指南 | https://github.com/PaperMC/paperweight | userdev 构建、reobf、dev bundle |
| Paper Brigadier（命令） | https://docs.papermc.io/paper/dev/commandapi | `/servux` 命令移植 |
| Spigot Javadocs | https://hub.spigotmc.org/javadocs/spigot/ | Bukkit API（事件/权限/Messenger） |
| Paper 1.21.11 News | https://papermc.io/news/1-21-11 | 版本相关变更 |

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
| Minecraft Wiki — Java Edition protocol/Packets | https://minecraft.wiki/w/Java_Edition_protocol/Packets | 1.21.10/11 协议号、包清单 |

---

## 5. 第三方库（选做项）

| 资源 | 链接 | 用途 |
|---|---|---|
| PacketEvents | https://docs.packetevents.com/ / https://modrinth.com/plugin/packetevents | **EasyPlace 已实现**（`EasyPlaceListener` 拦截 `PLAYER_BLOCK_PLACEMENT` 改写 cursor 放行 + `EasyPlaceFixListener` 修正）；见 [07](07-migration-architecture.md) §4、[08](08-implementation-plan.md) 7.1 |
| LuckPerms | https://luckperms.net/ | 权限增强（可选，替代 fabric-permissions-api） |
| Vault | https://github.com/MilkBowl/Vault | 权限/经济抽象（可选） |

---

## 6. 本仓库内对照源

| 资源 | 路径 | 用途 |
|---|---|---|
| **Servux 原版（移植对照权威）** | [`../OriginImpl/servux-LTS-1.21.11/`](../OriginImpl/servux-LTS-1.21.11/) | 逐行对照；包 `fi.dy.masa.servux` |
| — 网络层 | `.../network/`、`.../network/packet/` | [02](02-network-protocol.md) |
| — 数据采集 | `.../dataproviders/`、`.../loggers/` | [03](03-dataproviders-detail.md) |
| — Mixin | `.../mixin/`、`mixins.servux.json`、`servux.accesswidener` | [04](04-mixin-analysis.md) |
| — 投影系统 | `.../schematic/` | [05](05-schematic-system.md) |
| **姊妹项目 VeryMcBot（paperweight+NMS 范式参考）** | `I:\Programming\VeryMcBot` | `build.gradle.kts`（userdev）、`reflect/Reflect`（反射工具）、CLAUDE.md（文档风格） |
| — VeryMcBot 技术参考 | `I:\Programming\VeryMcBot\docs\Plugin-Implementation-Details.md` | NMS 反射/降级范式 |
| 本项目根说明 | [`../CLAUDE.md`](../CLAUDE.md) | 项目定位与核心约束 |

---

## 7. Servux 上游

| 资源 | 链接 |
|---|---|
| Servux CurseForge | https://www.curseforge.com/minecraft/mc-mods/servux |
| Servux 源码（maruohon） | https://github.com/maruohon/servux |
| Servux Discord | https://discordapp.com/channels/211786369951989762/453662800460644354/ |
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
| Fabric 用法在 Paper 怎么写 | [06-fabric-vs-paper.md](06-fabric-vs-paper.md) |
| 下一步做啥 | [08-implementation-plan.md](08-implementation-plan.md) |
