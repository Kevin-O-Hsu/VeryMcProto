# 06 · Fabric ↔ Paper 框架差异对照

> 逐维度对照"Servux 在 Fabric 怎么做"vs"在 Paper 插件怎么等价实现"。每行可直接当作迁移转换规则用。
> 网络层的详细机制见 [02](02-network-protocol.md)；Mixin 差异见 [04](04-mixin-analysis.md)；整体方案见 [07](07-migration-architecture.md)。

---

## 1. 总览：两个框架的根本差异

| 维度 | Fabric + Servux | Paper 插件 |
|---|---|---|
| **运行模型** | Mod 与服务端**同进程同类加载器**，可直接 `import net.minecraft.*`、用 Mixin 改字节码 | 插件在**隔离类加载器**，NMS 经 paperweight userdev 间接访问，**无 Mixin** |
| **NMS 可达性** | Loom 提供官方 Mojang 映射，全量 `net.minecraft.*` 可见可改 | paperweight userdev 同样提供 Mojang 映射 `net.minecraft.*`（dev bundle），但**运行时经 reobf**；反射字符串用 Mojang 名 |
| **改字节码** | Mixin（运行时织入）+ AccessWidener（编译期暴露） | **无**。只能反射读私有、事件替代钩子、PacketEvents 拦截包 |
| **网络** | `fabric-networking-api-v1`（原版 `CustomPacketPayload` 封装） | plugin messaging channel（映射原版 custom payload）+ NMS `ClientboundCustomPayloadPacket` |
| **生命周期** | Mixin 钩 NMS 生命周期点 | Bukkit 事件 + scheduler |
| **权限** | `fabric-permissions-api`（Lucko） | Bukkit `Permission` / LuckPerms / Vault |
| **构建** | `fabric-loom` + `modImplementation` | Gradle + `paperweight userdev` + `run-paper` |

---

## 2. 生命周期 / 入口

| Fabric | 触发方式 | Paper 等价 |
|---|---|---|
| `ModInitializer.onInitialize()` | JVM 加载 mod | `JavaPlugin.onEnable()` |
| `MixinMinecraftDedicatedServer.<init>@TAIL`（注册 provider） | Mixin | `onEnable` 直接注册 |
| `MixinMinecraftServer.runServer` → onServerStarting/Started | Mixin | `ServerLoadEvent(ServerLoadEvent.LoadType.STARTED)` |
| `MixinMinecraftServer.tickServer@RETURN` → tickProviders | Mixin | `Bukkit.getScheduler().runTaskTimer(plugin, task, 0L, 1L)`（每 tick）+ 内部按 interval 分发 |
| `MixinMinecraftServer.reloadResources` Pre/Post | Mixin | `PluginReloadEvent`（Paper）/ 自行监听 `/reload`；或提供 `/servux reload` 命令 |
| `MixinMinecraftServer.stopServer` Pre/Post | Mixin | `PluginDisableEvent` / `onDisable` |
| `MixinMain.main`（捕获 RegistryAccess） | Mixin | `Bukkit.getServer()` NMS `registryAccess()`（任意时刻可得） |
| `MixinPlayerManager.placeNewPlayer` → onPlayerJoin | Mixin | `PlayerJoinEvent` |
| `MixinPlayerManager.remove` → onPlayerLeave | Mixin | `PlayerQuitEvent` |
| `MixinPlayerManager.respawn` | Mixin | `PlayerRespawnEvent` |
| `MixinPlayerManager.canPlayerLogin` | Mixin | `AsyncPlayerPreLoginEvent` / `PlayerLoginEvent` |
| `MixinPlayerManager.op/deop` | Mixin | `PlayerCommandPreprocessEvent`（监听 `/op`）/ 或轮询 `Player#isOp()` |
| `MixinServerWorld.advanceWeatherCycle`（天气采集） | Mixin | `WeatherChangeEvent` + `World#getWeatherDuration` |
| `MixinServerWorld.setRespawnData`（出生点） | Mixin | 监听 `World#getSpawnLocation` 变化 / 周期同步 |
| `MixinServerChunkLoadingManager.markChunkPendingToSend`（结构触发） | Mixin | 周期扫描玩家周围区块（按 view distance）查结构引用 |
| `MixinCommandManager.<init>`（注册命令） | Mixin | `plugin.yml` `commands:` + `CommandExecutor` / Paper Brigadier |

---

## 3. 网络层（要点对照；机制详见 [02](02-network-protocol.md)）

| Fabric | 作用 | Paper 等价 |
|---|---|---|
| `PayloadTypeRegistry.playC2S().register(Type, codec)` | 注册 C2S payload | `Messenger.registerIncomingPluginChannel(plugin, "servux:xxx", listener)` |
| `PayloadTypeRegistry.playS2C().register(Type, codec)` | 注册 S2C payload | `Messenger.registerOutgoingPluginChannel(plugin, "servux:xxx")` |
| `ServerPlayNetworking.registerGlobalReceiver(Type, handler)` | 注册接收器 | 同上 incoming 注册 + `PluginMessageListener` |
| `ServerPlayNetworking.send(player, payload)` | 发 S2C | `player.sendPluginMessage(plugin, "servux:xxx", bytes)` 或 NMS `player.connection.send(new ClientboundCustomPayloadPacket(payload))` |
| `ServerPlayNetworking.canSend(player, type)` | 客户端是否支持 | plugin messaging 无直接等价；靠客户端回握手判断 |
| `ServerPlayNetworking.Context` | 携带 player | `PluginMessageListener` 直接给 `Player` |
| `record Payload implements CustomPacketPayload` + `StreamCodec` | 协议帧 | **照抄**（paperweight 可用 NMS `CustomPacketPayload`/`StreamCodec`）；或直接序列化到 `byte[]` |
| `FriendlyByteBuf` / `Unpooled` | 字节流 | NMS `FriendlyByteBuf`（paperweight 直连）；`byte[]`↔buf 用 `Unpooled.wrappedBuffer`/`buf.array()` |
| `ClientboundCustomPayloadPacket(payload)` + `connection.send` | NMS 发包 | **照抄**（NMS 直连，绕过 32KiB 限制） |
| 单包上限（S2C 1MiB / C2S 32KiB） | PacketSplitter | plugin messaging 32KiB → S2C 分片常量改 ≤32760；或 S2C 走 NMS 保 1MiB |
| 未知 payload 处理 | Fabric 客户端注册即收 | **Paper 注册 incoming channel 即不踢玩家**（内置路由） |

---

## 4. 权限

| Fabric | Paper |
|---|---|
| `Permissions.check(player, "servux.provider.hud_data", level)` | `player.hasPermission("servux.provider.hud_data")`（默认在 `plugin.yml` 设 `default: op`） |
| permission level 0-4（NMS op 等级） | Bukkit 权限 + `default`；4=OP 用 `default: op`，0=全员用 `default: true` |
| `me.lucko:fabric-permissions-api` 依赖 | 无依赖（Bukkit 原生）；可选 LuckPerms/Vault 增强 |

> **转换规则**：Servux 的 `permission_level` setting（0-4）→ 在插件启动时**注册对应 Bukkit 权限**，`hasPermission` 时按 setting 值决定"是否要求 OP"。最简单实现：把所有 servux 权限节点 `default: op`，然后用 Bukkit permission attachment 按 setting 动态授予。

---

## 5. 配置 / 文件路径

| Fabric | Paper |
|---|---|
| `FabricLoader.getConfigDir()` → `config/servux.json` | `plugin.getDataFolder()` → `plugins/VeryMcProto/servux.json` |
| `Reference.DEFAULT_RUN_DIR = getGameDir()` | `Bukkit.getWorldContainer()` |
| `JsonUtils.parseJsonFileAsPath`（Gson） | 保留 Gson（Bukkit 自带）或换 SnakeYAML；`servux.json` 是 JSON，**建议保持 JSON**（与原版一致，配置项系统已基于 JsonObject） |
| `filesMatching("fabric.mod.json")` expand version | `processResources` expand `plugin.yml` 的 `${version}` |

---

## 6. 命令

| Fabric | Paper |
|---|---|
| `MixinCommandManager.<init>` 注入 Brigadier dispatcher | `plugin.yml` `commands: servux:` + `CommandExecutor`/`TabCompleter` |
| Brigadier `LiteralArgumentBuilder`（NMS `Commands`） | Bukkit `CommandSender` args 解析；或 Paper 的 `io.papermc.paper.command.brigadier`（Paper Brigadier，更接近原版） |
| `CommandSourceStack`（NMS 命令源） | `CommandSender`（Bukkit） |
| `SimpleCommandExceptionType` + i18n 消息 | `sender.sendMessage(...)` |

> **建议**：用 Paper Brigadier API（`LifecycleEvent` 注册），API 与原版 Brigadier 几乎一致，移植成本最低；`/servux reload|save|set|info|list|search` 树照搬。

---

## 7. 构建

| Fabric（`fabric-loom`） | Paper（`paperweight userdev`） |
|---|---|
| `id 'fabric-loom'` | `id 'io.papermc.paperweight.userdev'` + `id 'xyz.jpenilla.run-paper'` |
| `minecraft "com.mojang:minecraft:1.21.11"` + `mappings loom.officialMojangMappings()` | `paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:1.21.11-R0.1-SNAPSHOT")` |
| `modImplementation fabricApi.module("fabric-networking-api-v1", ...)` | （网络改用 plugin messaging + NMS，无需 Fabric API） |
| `accessWidenerPath` | 删除（Paper 无 AW；用反射） |
| `mixins: ["mixins.servux.json"]` | 删除（Paper 无 Mixin） |
| 产物：`.jar`（Fabric 格式） | 产物：`reobfJar`（Spigot 运行时映射，标准 Paper 可加载） |

> 当前 `build.gradle.kts` 是纯 `compileOnly("...paper-api")`，**迁移第一步即改为 paperweight userdev**（见 [08](08-implementation-plan.md) 阶段 0）。完整 build 模板见 [07](07-migration-architecture.md) §构建配置。

---

## 8. NBT API

| Fabric（直接 NMS） | Paper |
|---|---|
| `net.minecraft.nbt.CompoundTag` / `ListTag` / `Tag` | NMS 同名（paperweight 直连，**保真度最高**）；或 Bukkit `PersistentDataContainer`（受限）/ 第三方 NBT-API |
| `NbtOps.INSTANCE`（DataResult 编解码） | NMS 同名（配方/结构序列化用） |
| `NbtIo.writeCompressed` / `readCompressed` | NMS 同名（投影文件 GZIP） |
| `NbtView`（Servux 自研，基于 `TagValueInput/Output`） | 重写为直接 NMS `Entity.saveWithoutId(CompoundTag)` / `BlockEntity.saveWithFullMetadata`，绕开 `IMixinNbtRead/WriteView`（[04](04-mixin-analysis.md) §5） |

> **1.21.11 CompoundTag 约束**（与 VeryMcBot 一致）：`getXxx` 返回 Optional，用 `getXxxOr`/`.orElse()`；`putXxx` 返回 void。

---

## 9. 数据采集 API（NMS 可达性）—— 详见 [03](03-dataproviders-detail.md) §6

绝大多数 Servux 采集用的 NMS API，paperweight userdev 的 Mojang dev bundle **可直接 import 调用**（公开/包级方法），无需反射。仅以下需反射：

| NMS 项 | 原版访问方式 | Paper 处置 |
|---|---|---|
| `ServerTickRateManager.remainingSprintTicks`（private） | `IMixinServerTickManager @Accessor` | 反射 `Reflect.get` |
| `NaturalSpawner.MAGIC_NUMBER`(=289，private static) | AccessWidener | 反射 **或硬编码 289** |
| `TagValueInput.context`/`input`、`TagValueOutput.ops`/`output`（private） | `IMixinNbtRead/WriteView @Accessor` | 重写 `NbtView` 绕开（不反射） |
| `LevelTicks.allContainers`（private） | `IMixinWorldTickScheduler @Accessor` | 反射（若 scheduled tick 数据需要） |

---

## 10. i18n / 日志 / 杂项

| Fabric | Paper |
|---|---|
| `LogManager.getLogger(MOD_ID)`（log4j） | `plugin.getLogger()`（SLF4J） |
| `AnsiLogger`（ANSI 颜色） | 可保留为工具类，或用 `plugin.getLogger()` |
| `util/i18n/` + `assets/servux/lang/*.json` | 插件自带 lang 文件，或硬编码中英文消息（客户端不关心服务端消息语言） |
| `SharedConstants.getCurrentVersion()` | NMS 同名 / `Bukkit.getMinecraftVersion()` |
| `FabricLoader.getInstance()` | `Bukkit.getServer()` / `plugin.getDataFolder()` |

---

## 11. 一页纸转换清单（移植时逐条核对）

```
入口/生命周期    onInitialize → onEnable + Bukkit 事件
provider 注册    MixinDedicatedServer → onEnable 直接 register
tick 调度        MixinMinecraftServer.tickServer → BukkitScheduler runTaskTimer
玩家进退服        MixinPlayerManager → PlayerJoinEvent/PlayerQuitEvent
配置路径         config/servux.json → plugins/VeryMcProto/servux.json
权限             Permissions.check → player.hasPermission
命令             MixinCommandManager → plugin.yml + CommandExecutor / Paper Brigadier
网络收(C2S)      ServerPlayNetworking → Messenger.registerIncomingPluginChannel
网络发(S2C)      ServerPlayNetworking.send → sendPluginMessage 或 NMS ClientboundCustomPayloadPacket
字节流            FriendlyByteBuf → NMS FriendlyByteBuf（paperweight 直连）
分包             PacketSplitter → 照抄（S2C 常量改 ≤32760）
NBT              CompoundTag/NbtIo → NMS 直连
Mixin 读私有     → 反射 / NMS 直接调用（多数已是公开方法）
Mixin 改行为     → 降级省略 / PacketEvents / 投影代码内联
构建             fabric-loom → paperweight userdev + run-paper
```
