# CLAUDE.md — VeryMcProto · Fabric 协议 Mod → Paper 插件移植

## 项目简介

**VeryMcProto** 是一个把 **Fabric 端独特的 Mod Protocol（协议 Mod）** 以纯 Paper 插件形式重新实现的项目。所有实现基于 **Minecraft 1.21.11**，运行在标准 **Paper 1.21.11** 服务端，不依赖任何服务端 patch / Mixin / 私有 fork。

每个被移植的 Mod 独占一个目录单元；原版 Fabric 实现统一存放在 `OriginImpl/` 下用于逐行对照。当前进行中的第一个移植目标：

- **Servux**（`OriginImpl/servux-LTS-1.21.11/`）—— masa 开发的服务端协议 Mod，为 masa 的客户端 Mod（**MiniHUD / Litematica / Tweakeroo** 等）提供**服务端→客户端的数据投递与协议**，并通过自定义网络通道（`servux:*`）下发：世界元数据、出生点、天气、TPS/MobCap、结构边界框、Litematica 投影投递/粘贴、实体与方块实体 NBT 查询、EasyPlace 服务端放置协议等。

> **本项目的本质是"协议层移植"**：客户端仍是 masa 的 Fabric Mod；我们要在 Paper 服务端复刻它们期待的**网络协议 + 数据采集**，使"Fabric 客户端 + Paper 服务端"的组合能像"Fabric 客户端 + Servux 服务端"一样工作。

> 📚 **所有技术文档**都在 [`docs/`](docs/) 下，按阅读顺序编号，互相索引。**强烈建议先读 [`docs/00-INDEX.md`](docs/00-INDEX.md)** 获取文档地图与推荐阅读路线。

---

## 技术栈与构建

| 项 | 说明 |
|---|---|
| **目标平台** | Paper **1.21.11**（`api-version: 1.21`），Java **21** |
| **构建** | Gradle（Kotlin DSL） + **paperweight `userdev`** + `run-paper` |
| **NMS 映射** | 开发期用 `paperDevBundle("1.21.11-R0.1-SNAPSHOT")` 提供 Mojang 全反混淆的 `net.minecraft.*`；产物经 `reobfJar` 转 Spigot 运行时映射，标准 Paper 直接加载 |
| **反射用 Mojang 名** | `reobf` 不转换反射字符串，Paper 运行时即 Mojang 映射 → 反射私有成员直接用 Mojang 名 |
| **当前状态** | `build.gradle.kts` 仍是纯 `compileOnly("...paper-api")` 空壳；**迁移第一步即升级为 paperweight userdev**（见 [`docs/08-implementation-plan.md`](docs/08-implementation-plan.md) 阶段 0） |

构建命令（迁移完成后）：
```bash
./gradlew build        # 产出 reobf jar（标准 Paper 可直接加载）
./gradlew runServer    # 本地起 1.21.11 测试服（2G 堆）
```

> 为什么必须引入 paperweight/NMS：Servux 的数据采集大量依赖 NMS 内部（`NaturalSpawner.SpawnState`、`ServerTickRateManager`、`ChunkAccess.getAllReferences()`、`StructureStart.createTag()`、`Recipe.CODEC` + `NbtOps`、`BlockEntity.saveWithFullMetadata()` 等），网络层最干净的实现也复用原版 `FriendlyByteBuf` / `CompoundTag`。纯 Paper API 无法触达这些。详见 [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md)。

---

## 代码架构（迁移目标架构）

包根 `verymc.top.veryMcProto`，按 Servux 的 `fi.dy.masa.servux` 职责一一对应，但**适配 Paper 框架**：

| 包（目标） | 职责 | 对应 Servux 原版 |
|---|---|---|
| `VeryMcProto`（主类 `JavaPlugin`） | `onEnable`：注册通道 → 加载配置 → 注册 provider → 注册命令 → 启 tick 调度 | `Servux`(`ModInitializer`) |
| `network/` | 通道管理、`PayloadCodec`、字节流编解码（`FriendlyByteBuf`↔`byte[]`）、`PacketSplitter` 分片 | `network/`、`network/packet/` |
| `dataproviders/` | 5 个 Provider：`HudData` / `EntitiesData` / `TweaksData` / `StructuresData` / `LitematicsData` + `ConfigProvider` + `DataProviderManager` | `dataproviders/` |
| `schematic/` | Litematica 投影系统（BitArray/Palette/Container/Selection/Placement/Transmit）—— **大量纯算法，近乎照抄** | `schematic/` |
| `loggers/` | HUD 的 TPS / MobCap 数据采集（`DataLoggerTPS` / `DataLoggerMobCaps`） | `loggers/` |
| `command/` | `/servux` 命令树（Paper Brigadier / `CommandManager`） | `commands/` |
| `config/` | `servux.json` 配置读写（替代原版 `Reference.DEFAULT_CONFIG_DIR`） | `Reference` + `settings/` + `DataProviderManager` 配置部分 |
| `event/` | 生命周期与玩家事件分发（**Paper `PluginMessageListener` + Bukkit 事件**，替代 Mixin 钩子） | `event/` + `servux/*Listener` + 各 Mixin 钩子 |
| `nbt/` | NBT 工具与抽象（`NbtUtils` / `NbtView`，NMS 直连） | `util/nbt/` |
| `reflect/` | NMS 反射工具（字段读写、`MethodHandles`，仿 VeryMcBot） | 无（Servux 用 Mixin/AccessWidener） |
| `util/` | `MathUtils` / `IntBoundingBox` / `PlayerDimensionPosition` / `i18n` 等 | `util/` |

**关键替换点**（Fabric → Paper）一句话版：

- `ModInitializer.onInitialize()` → `JavaPlugin.onEnable()`
- Mixin 生命周期钩子（`ServerHandler` / `PlayerHandler`，由 `MixinMinecraftServer` / `MixinPlayerManager` 触发）→ **Bukkit 事件**（`ServerLoadEvent` / `PlayerJoinEvent` / `PlayerQuitEvent` / `PlayerRespawnEvent` …）
- **Mixin/AccessWidener 无法迁移**（Paper 无 Mixin）→ 反射 / Bukkit 事件 / PacketEvents / 降级省略（见核心约束 §2）
- Fabric `ServerPlayNetworking`（注册/收发 `CustomPacketPayload`）→ **Paper `Messenger`（plugin messaging channel）+ NMS `ClientboundCustomPayloadPacket`**（见核心约束 §1）
- `fabric-permissions-api`（Lucko）→ Bukkit `player.hasPermission(...)` / LuckPerms / Vault

---

## 核心设计约束（维护必读）

### 1. 网络层命门：Servux 走的是「原版 CustomPacketPayload」，不是旧的 plugin messaging channel

**这是整个移植的基石，理解错了后面全错。**

- Servux 用的是 **Mojang 在 1.20.2+ 引入的原版 `net.minecraft.network.protocol.common.custom.CustomPacketPayload`** + `StreamCodec` 机制。5 条通道：
  - `servux:main`（HUD，协议版本 **2**）
  - `servux:entity_data`（Entities，**1**）
  - `servux:tweaks_data`（Tweaks，**1**）
  - `servux:structure_bounding_boxes`（Structures，**2**）
  - `servux:litematic_data`（Litematics，**1**）
- Fabric 的 `ServerPlayNetworking.send/registerGlobalReceiver/PayloadTypeRegistry` **只是这套原版机制的注册封装层**，不是独立协议。
- **决定性结论**（实证：[FabricMC Discussion #4430](https://github.com/orgs/FabricMC/discussions/4430)）：Paper 的 **plugin messaging channel（`namespace:path` 命名）直接映射到原版 custom payload 通道**，`PluginMessageListener.onPluginMessageReceived(channel, player, byte[])` 收到的 `byte[]` 就是 `FriendlyByteBuf` 的**裸字节**，`player.sendPluginMessage(...)` 发出的 `byte[]` 同理。因此 Servux 的 5 条通道可用**标准 Paper API 直接收发，无需 ProtocolLib / PacketEvents**。

**字节限制命门（务必注意）**：
- 1.21.x Bukkit `Messenger.MAX_MESSAGE_SIZE` 已上调至 ~1MiB（Spigot API `1048576`），plugin messaging API 层不再以 32KiB 拒绝（旧文档称 `32768` 已过时）。
- **真正的 S2C 瓶颈是原版客户端对 `ClientboundCustomPayload` 的 32767 字节解码上限**——超过会让客户端断连（实测风险，见 [`docs/09-DELIVERY.md`](docs/09-DELIVERY.md) §10）。
- Servux 原版 `PacketSplitter`：S2C 分片 `MAX_PAYLOAD_PER_PACKET_S2C = 1MiB`（原版走 NMS 直发），C2S `= 32767`。
- **迁移处置（已落地）**：本移植方案 A 全程走 plugin messaging，`PacketSplitter` S2C 分片已从 1MiB 改为 **32000**（留余量给 VarInt 头，防御客户端 32767 上限）。大包（Recipe / Litematic 投影）必须走分包。若后续 S2C 改走 NMS `player.connection.send(new ClientboundCustomPayloadPacket(payload))`，仍受同一客户端 32767 上限，分片不变。详见 [`docs/02-network-protocol.md`](docs/02-network-protocol.md) §分片与 [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md) §网络层。

**C2S 接收命门**：Paper 原版服务端对未注册的 custom payload 会**踢玩家**（"Invalid payload"）。plugin messaging 注册的通道由 Paper 内置路由、不踢人——这正是用 `Messenger.registerIncomingPluginChannel` 接收 C2S 的理由。

### 2. Mixin / AccessWidener 无法迁移 → 三段式处置

Servux 共 **26 个 Mixin + 2 个 AccessWidener 字段**。Paper 无 Mixin 运行时，**逐一**按下表处置（完整清单与去向见 [`docs/04-mixin-analysis.md`](docs/04-mixin-analysis.md)）：

| Mixin 类别 | 数量 | 处置 | 示例 |
|---|---|---|---|
| **协议数据采集必需**（读私有字段） | ~9 | **反射 / NMS 直接访问** | `IMixinServerTickManager`(remainingSprintTicks)、`NaturalSpawner.MAGIC_NUMBER`、`IMixinWorldTickScheduler`(allContainers)、`IMixinNbtRead/WriteView` |
| **采集触发 / 生命周期**（钩子） | ~7 | **Bukkit 事件 / 调度器替代** | `MixinMinecraftServer`(tick)、`MixinPlayerManager`(join/leave/respawn/op)、`MixinMain`(capture RegistryAccess)、`MixinServerChunkLoadingManager`(watching chunk) |
| **服务端行为改造**（改逻辑） | ~9 | **降级 / PacketEvents / 事件 / 省略** | EasyPlace(`MixinBlockItem`/`MixinServerPlayNetworkHandler`)、UpdateSuppression(`MixinWorld*`)、潜影盒堆叠(`MixinItemStack`/`MixinHopper`)、Allay 修复(`MixinMob*`)、镜像修复(`MixinChest/Rail/Stairs`) |
| **调试** | 1 | 省略 | `MixinSharedConstants`(IS_RUNNING_IN_IDE) |
| AccessWidener | 2 | 反射 | `SharedConstants.DEBUG_ENABLED`(mutable)、`NaturalSpawner.MAGIC_NUMBER`(accessible) |

**判断标准**：Servux 源码里凡是 Mixin 注入（`@Inject`/`@WrapOperation`/`@Redirect`/`@Accessor`/`implements`）的，**Paper 上都不能照抄**——先判它属于上表哪一类，再选处置方式。**切勿引入 Mixin 依赖。**

### 3. EasyPlace / UpdateSuppression / 镜像修复 —— 这类"改变服务端行为"的功能降级最严重

- **EasyPlace**（Tweakeroo 服务端配合）：Servux 通过 Mixin `BlockItem.getPlacementState` 注入 `PlacementHandler.applyPlacementProtocolV3` + Mixin `ServerGamePacketListenerImpl.handleUseItemOn` 去掉命中位置校验。Paper 无 Mixin，**需降级**：用 PacketEvents 拦截 `ServerboundUseItemOnPacket` 自行放置，或直接省略（最务实）。详见 [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md) §降级矩阵。
- **UpdateSuppression**：依赖 Mixin 给 `Level`/`LevelChunk` 加接口 + 改 `setBlockState` 副作用。**Paper 上省略**（可选后续用 Paper 的 `World#refreshChunk`/计划 tick 工具部分模拟）。
- **镜像修复**（箱子/铁轨/楼梯 180° 镜像）：仅 Litematica 投影粘贴时用。可在**投影粘贴代码**（`SchematicPlacingUtils`）里内联等价修正，不走 Mixin。

### 4. 1.21.11 关键 NMS 约束（与 VeryMcBot 一致，移植时注意）

- **`CompoundTag`**：`getBoolean/getInt/getString/...` 返回 `Optional`/`OptionalInt`，须用 `getBooleanOr/getIntOr/getStringOr` 或 `.orElse()`；`putXxx` 返回 `void`（非链式）。
- **`FriendlyByteBuf`**：Servux 协议体编码的核心类，`writeVarInt`/`writeNbt`/`readNbt` 等；Paper 用 paperweight userdev 可直接引用 NMS 的它。
- **`CustomPacketPayload`**：`record Payload(...) implements CustomPacketPayload` + `static Type<Payload> ID` + `static StreamCodec<FriendlyByteBuf, Payload> CODEC = CustomPacketPayload.codec(Payload::write, Payload::new)`。**协议层这部分可近乎照抄**（去掉 Fabric `@Environment` 注解，改用 Paper 的字节收发）。
- **`Identifier` / `ResourceLocation`**：Servux 用 `net.minecraft.resources.Identifier`（= Mojang `ResourceLocation`），`Identifier.fromNamespaceAndPath("servux","hud_metadata")`。Paper NMS 同名同构。

### 5. 权限系统迁移

- Servux 用 `me.lucko:fabric-permissions-api`，`Permissions.check(player, node, defaultLevel)`。
- **Paper 迁移**：`player.hasPermission(node)`，默认等级映射到 Bukkit 权限（`permission-level: 4` = OP，0 = 全员）；或对接 LuckPerms/Vault。权限节点保持 `servux.provider.hud_data` / `.weather` / `.seed` / `.logger` / `.paste` 等原样命名。

---

## 维护与升级要点

- **新增一个 Provider**：在 `dataproviders/` 加类（`extends` 自 Paper 版 `DataProviderBase`），在 `network/` 加对应通道编解码（`ServuxXxxCodec` + `Payload`/字节布局），在 `network/` 通道管理注册，最后在 `DataProviderManager` 登记。详见 [`docs/03-dataproviders-detail.md`](docs/03-dataproviders-detail.md)。
- **升级 Minecraft 版本**：先重跑 paperweight 对齐新 dev bundle；再按 [`docs/04-mixin-analysis.md`](docs/04-mixin-analysis.md) 的反射点逐一核对 NMS 字段/方法签名漂移（尤其 `FriendlyByteBuf`、`CustomPacketPayload`、`CompoundTag` Optional 化、`StructureStart.createTag` 签名）。
- **新增 masa 客户端 Mod 的协议支持**：参考 [`docs/02-network-protocol.md`](docs/02-network-protocol.md) 的"通道 = 原版 custom payload"模型，按 client mod 期望的字节布局实现即可（字节格式就是 `FriendlyByteBuf`）。
- **参考源码**：`OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/` 是逐行对照的权威实现；遇到分歧以真实源码为准。

---

## 文档地图

| 文档 | 内容 | 对应需求 |
|---|---|---|
| [`docs/00-INDEX.md`](docs/00-INDEX.md) | 文档总索引 + 推荐阅读路线 | 导航 |
| [`docs/01-servux-architecture.md`](docs/01-servux-architecture.md) | 原版架构总览：启动流程、`DataProviderManager`、生命周期、配置/设置系统 | (a) 原版细节 |
| [`docs/02-network-protocol.md`](docs/02-network-protocol.md) ⭐ | **核心网络协议**：`CustomPacketPayload` 模型、`PacketSplitter` 分片、5 条通道、`Payload` record、字节布局、收发流程 | (a) 原版细节 |
| [`docs/03-dataproviders-detail.md`](docs/03-dataproviders-detail.md) | 5 个 Provider 的协议数据内容 + 数据采集（含 `loggers` TPS/MobCap） | (a) 原版细节 |
| [`docs/04-mixin-analysis.md`](docs/04-mixin-analysis.md) | 26 Mixin + 2 AccessWidener 逐项清单、分类、迁移去向 | (a)/(b) |
| [`docs/05-schematic-system.md`](docs/05-schematic-system.md) ⭐ | Litematica 投影系统：BitArray/Palette/Container/Selection/Placement/Transmit + 传输协议 | (a) 原版细节 |
| [`docs/06-fabric-vs-paper.md`](docs/06-fabric-vs-paper.md) | Fabric ↔ Paper 框架差异对照表（生命周期/权限/网络/配置/构建） | (b) 差异转换 |
| [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md) ⭐ | **完整迁移技术方案**：架构设计、网络层/数据采集/降级矩阵、可行性验证（含网络文档） | (c) 迁移方案 |
| [`docs/08-implementation-plan.md`](docs/08-implementation-plan.md) | 实施步骤：阶段划分 + 大任务拆小任务 + 依赖与里程碑 | (d) 实施规划 |
| [`docs/references.md`](docs/references.md) | 参考资源链接（Paper/Fabric/Protocol Wiki/Servux 源码） | 参考 |

---

## 参考资源

- Paper 开发文档：https://docs.papermc.io/paper/dev/
- Paper 插件消息通道（plugin messaging）：https://docs.papermc.io/paper/dev/plugin-messaging/
- PaperWeight 指南：https://github.com/PaperMC/paperweight
- Minecraft Protocol Wiki：https://wiki.vg/Protocol （`Custom Payload` 包结构）
- Fabric 网络文档：https://docs.fabricmc.net/develop/networking
- FabricMC Discussion #4430（Spigot/Paper ↔ Fabric 自定义通道实证）：https://github.com/orgs/FabricMC/discussions/4430
- Servux 原版源码（本仓库对照）：`OriginImpl/servux-LTS-1.21.11/`
- 姊妹项目 VeryMcBot（paperweight userdev + NMS 反射范式参考）：`I:\Programming\VeryMcBot`

**开发环境**：IntelliJ IDEA + Minecraft Dev SDK + Gradle + PaperWeight。
