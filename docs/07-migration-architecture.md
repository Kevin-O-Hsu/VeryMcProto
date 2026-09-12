# 07 · Fabric → Paper 架构对照与降级矩阵（含可行性论证）

> 本文是 Fabric 协议 Mod → Paper 插件移植的**现行规范**：目标架构、各层迁移决策、可行性结论、Mixin 降级矩阵、构建配置、风险缓解，以及 **Fabric ↔ Paper 逐域对照表**（原 `06-fabric-vs-paper.md` 已并入本文 §7，26.1 口径）。
> 阅读前提：已读 [01](01-servux-architecture.md)–[05](05-schematic-system.md)；协议字节细节见 [02](02-network-protocol.md)；26.1 线迁移实录见 [09](09-DELIVERY.md) §26.1；仓库权威说明见 [../AGENTS.md](../AGENTS.md)。
> 历史迁移计划文档（08/11 与 docs/research/）已于 2026-09 删除，恢复走 git revert。

---

## 0. 可行性总结论（先看这个）

| 问题 | 结论 | 依据 |
|---|---|---|
| Servux 的网络协议能在 Paper 复刻吗？ | ✅ **能，且无需第三方库** | plugin messaging channel 直接映射原版 `CustomPacketPayload`；`byte[]` = `FriendlyByteBuf` 裸字节。实证 [FabricMC #4430](https://github.com/orgs/FabricMC/discussions/4430)、[Paper plugin-messaging 文档](https://docs.papermc.io/paper/dev/plugin-messaging/) |
| 能访问 Servux 采集所需的 NMS 吗？ | ✅ **能** | paperweight userdev 提供 Mojang 全映射 `net.minecraft.*`（26.1 起 Mojang 移除服务端混淆，映射即运行时真名）；绝大多数采集 API 是公开/包级方法，直接调用 |
| 26 个 Mixin 怎么办？ | ✅ **可控** | 读私有(4-5个)→反射；生命周期(9个)→Bukkit 事件；改行为(11个)→降级省略/内联/PacketEvents；调试(1)→省略。详见 [04](04-mixin-analysis.md) |
| 大包（配方/投影）能发吗？ | ✅ **能** | PacketSplitter 照抄（S2C 分片 32000，防御客户端 32767 解码上限）；已知通道大包另可 NMS `ClientboundCustomPayloadPacket` 直发（JEI 配方包实证） |
| 投影系统（最大模块）能搬吗？ | ✅ **能** | ~60% 纯算法照抄；接口层 NMS 直连 `BlockState`/`CompoundTag`/`NbtIo`。详见 [05](05-schematic-system.md) |
| 有阻塞性难点吗？ | ⚠️ **UpdateSuppression** 等改服务端行为的功能需降级（EasyPlace 已用 PacketEvents 实现） | 见 §4 降级矩阵 |

**总体判定：协议层 100% 可移植；数据采集层绝大部分可直接 NMS 实现；仅少量"改行为"特性降级。迁移可行。**（三个 mod 已全部落地并实测，见 [../AGENTS.md](../AGENTS.md) 与 [09](09-DELIVERY.md)。）

---

## 1. 目标架构

### 1.1 技术栈决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 构建 | **paperweight `userdev` 2.0.0-beta.23** + `run-paper 3.1.0` | 提供完整 Mojang NMS 映射；26.1 起 **reobf 废除**，产物即 Mojang 映射 jar，标准 Paper 直接加载 |
| 网络收发 | **plugin messaging channel**（主）+ NMS `ClientboundCustomPayloadPacket`（大包/直发） | plugin messaging 内置路由不踢玩家、API 简单；NMS 直发承载超限大包（JEI 配方、syncmatica S2C、servux 声明滞后兜底） |
| 数据采集 | **NMS 直连**（主）+ 反射（辅，仅 4-5 点） | 绝大多数 Servux 采集 API 是公开方法；反射点已识别（[03](03-dataproviders-detail.md) §6、§7.8） |
| NBT | **NMS `CompoundTag`/`NbtIo`** | 与原版字节级一致，Litematica 客户端可直接读；保真度最高（26.1 业务包另按 DataTag 线格式封装，见 [09](09-DELIVERY.md) §26.1.2） |
| 权限 | Bukkit `Permission`（+ LuckPerms 兼容） | 无新依赖；servux 权限节点原样保留 |
| Mixin | **不使用** | Paper 无 Mixin 运行时 |

### 1.2 实际包结构（as-built）

```
verymc.top.veryMcProto/
├── VeryMcProto.java              JavaPlugin 主类（框架入口 + 装配 + 依次注册三个 mod + 命令）
├── Reference.java                框架级常量（MC_VERSION / PLUGIN_VERSION——类加载时读 version.properties）
│
├── framework/                    ★ 协议 mod 移植框架（与具体 mod 无关，所有 mod 复用）
│   ├── network/                  通道封装（ChannelManager/ProtocolChannel）、分片（PacketSplitter）、
│   │                             字节桥接（FriendlyByteBufs）、Handler 注册表（ServerPlayHandler）
│   ├── dataproviders/            Provider 契约（IDataProvider/DataProviderBase/DataProviderManager）
│   ├── event/LifecycleBridge     Bukkit 事件 → Provider 生命周期（Join/Quit/Respawn/ServerLoad/RegisterChannel/tick）
│   ├── debug/DebugSystem         通用调试日志引擎（多 mod 独立实例；FrameworkDebug 门面绑定）
│   ├── permission/Perms          权限（替代 fabric-permissions-api）
│   ├── reflect/Reflect           NMS 反射（线程安全缓存 + 防御式降级）
│   ├── nms/Nms                   Bukkit ↔ NMS 转换
│   ├── settings/                 Servux 配置项体系（Bool/Int/String/StringList/List + 回调）
│   └── util/                     JsonUtils（Gson pretty + 原子写）/ StringUtils
│
└── mod/                          ★ 协议 mod 层（每个被移植的 Fabric 协议 mod 一个目录单元）
    ├── servux/                   app/ServuxModule + command/ + dataproviders/（6 Provider）+ network/（5 Handler+Packet）
    │                             + easyplace/ + loggers/ + scheduler/（task 组五类）+ schematic/ + util/
    ├── jei/                      app/JeiModule + network/（含 RecipeSyncJoinOrderer；payload/ 为其子包、含 legacy/ 子层）+ transfer/ + cheat/
    │                             + recipesync/ + config/ + command/
    └── syncmatica/               app/SyncmaticaModule + communication/（+exchange/）+ data/（+litematica/）
                                  + extended_core/ + network/ + service/ + util/
```

**新增协议 mod 的步骤**：在 `mod/<newmod>/` 实现 `ModModule`（或自管装配如 syncmatica/jei），在主类 `onEnable` 注册，在 `plugin.yml` 加命令/权限——无需改动框架（详见 [../AGENTS.md](../AGENTS.md) §维护与升级要点）。

### 1.3 启动流程（Paper 版，as-built）

```
VeryMcProto.onEnable():
  1. 初始化框架（ChannelManager / DataProviderManager / LifecycleBridge）
  2. 依次注册 servux / jei / syncmatica 三个模块（各自 try-catch，单点失败降级不阻断）
  3. 注册 /servux /jei /syncmatica 命令
  4. ServerLoadEvent(STARTED) 后捕获 RegistryAccess → DataProviderManager.onCaptureImmutable
     + readFromConfig（servux.json 加载）
  5. LifecycleBridge：PlayerJoin/Quit/Respawn/RegisterChannel 事件桥 + runTaskTimer 每 tick tickProviders

onPlayerJoin（PlayerJoinEvent）:
  对每个 enabled provider → provider.onPlayerJoin(player)（40t 延迟 sendMetadata + onPlayerRegisterChannel 兜底）

onDisable():
  各模块 disable（syncmatica placements.json 原子落盘、scheduler clearTasks 等）
```

---

## 2. 网络层迁移方案 ⭐

### 2.1 通道注册（plugin messaging）

```java
// framework/network/ProtocolChannel（形态示意）
public void register() {
    Messenger m = plugin.getServer().getMessenger();
    m.registerIncomingPluginChannel(plugin, channelId, (ch, player, bytes) -> {
        // bytes 就是 FriendlyByteBuf 裸字节（含 VarInt packetType + NBT/buffer）
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        receiver.accept(player, buf);
    });
    m.registerOutgoingPluginChannel(plugin, channelId);
}
```

> **关键**：`onPluginMessageReceived` 的 `bytes` 直接喂给原版的 `ServuxXxxPacket.fromPacket(FriendlyByteBuf)` 即可，**协议解码逻辑零改动**。Paper 原版服务端对未注册 custom payload 会**踢玩家**（"Invalid payload"）；plugin messaging 注册的通道由 Paper 内置路由、不踢人——这是用 `Messenger.registerIncomingPluginChannel` 收 C2S 的根本理由（见 [../AGENTS.md](../AGENTS.md) §1「C2S 接收命门」）。

### 2.2 字节限制：真实模型与两种投递路径

- Bukkit `Messenger.MAX_MESSAGE_SIZE` = **1048576（~1MiB）**（1.21.x 起；旧文档称 32768 已过时）。
- **真正的 S2C 瓶颈是原版客户端对 `ClientboundCustomPayload` 的 32767 字节解码上限**（未知通道 discarded 解码；超过客户端断连）。客户端**已注册 codec 的已知通道**不受此限（Fabric API 上限 64MB）。

**路径 A（servux 主路径）**：plugin messaging + `PacketSplitter` 分片——S2C 分片常量 `MAX_TOTAL_PER_PACKET_S2C = 32_000` / `MAX_PAYLOAD_PER_PACKET_S2C = 31_995`（留余量给 VarInt 头，防御 32767）。另设 16MB 重组上限预检（26.1 客户端 malilib 重组上限，详见 [02](02-network-protocol.md) §5 / [09](09-DELIVERY.md)）。
**路径 B（大包直发）**：NMS `new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))` 直发——JEI 配方包（常超 1MiB）、syncmatica S2C（plugin messaging wire 对纯 Fabric 客户端不可达）已采用；servux 的"同通道 C2S 证明兜底"亦走此路径（Paper 声明簿记滞后时，[../AGENTS.md](../AGENTS.md) §1）。

> **决策（as-built）**：servux 走 A + 兜底 B；jei / syncmatica 以 B 为主。两路径按通道选择、共存。

### 2.3 字节级可行性验证

- ✅ `byte[]` ↔ `FriendlyByteBuf`：`Unpooled.wrappedBuffer(bytes)` 包装、`buf.getBytes(...)`/`buf.array()` 取出。
- ✅ `CompoundTag` 读写：`FriendlyByteBuf.writeNbt(tag)` / `readNbt()`，NMS 直连（同一套 Mojang NBT 协议）。
- ✅ VarInt：`FriendlyByteBuf.writeVarInt/readVarInt`，NMS 直连。
- ✅ 通道命名：`servux:hud_metadata` 满足 plugin channel 的 `namespace:key` 规则。
- ✅ 26.1 DataTag 线格式（业务包 NBT 载体）：`mod/servux/util/nbt/DataTagIo.java` 与 NMS `NbtIo` 输出逐字节兼容（配单测，见 [09](09-DELIVERY.md) §26.1.2）。

### 2.4 Payload record 照抄

`ServuxHudPacket.Payload`（及另 4 条通道的 Payload）在 Paper 端**去掉 `@Environment(EnvType.SERVER)`** 后近乎原样可用——`CustomPacketPayload.Type`、`StreamCodec`、`FriendlyByteBuf` 全是 NMS（paperweight 直连）。plugin messaging 收发 `byte[]` 时，Payload record 主要用于 NMS 直发与协议定义文档化。

---

## 3. 数据采集迁移方案

> 各 Provider 的具体采集点见 [03](03-dataproviders-detail.md)；本节给迁移范式；NMS 可达性总表见 §7.8。

### 3.1 NMS 直连范式（绝大多数）

paperweight dev bundle 可直接 import：`MinecraftServer` / `ServerLevel` / `ServerPlayer`、`ServerLevel.getSeed()` / `recipeAccess()` / `getChunkSource().getLastSpawnState()`、`Recipe.CODEC.encodeStart(NbtOps.INSTANCE, ...)`、`BlockEntity.saveWithFullMetadata(registryAccess)` / `Entity.saveWithoutId(...)`、`ChunkAccess.getAllReferences()` / `getStartForStructure()` / `StructureStart.createTag(ctx, pos)`、`MinecraftServer.registryAccess()`。

→ **这些在 Paper 上 = 直接调用，零额外成本**（原版要 Mixin/AccessWidener 才能访问的，反而因 paperweight 给了完整映射而简化）。

### 3.2 反射范式（仅 4-5 点）

`framework/reflect/Reflect`（线程安全缓存 + 防御式，漂移时降级返回默认值）：

```java
// TPS：读 ServerTickRateManager.remainingSprintTicks（private long）
long sprint = Reflect.get(tickManager, "remainingSprintTicks");  // Mojang 名（产物即 Mojang 映射）

// MobCap：NaturalSpawner.MAGIC_NUMBER（private static int = 289）
// 推荐：直接硬编码 int MAGIC = 289; （注释标明来源）
```

### 3.3 触发点迁移（Mixin → 事件）

| 采集触发 | Fabric（Mixin） | Paper（事件/调度，as-built） |
|---|---|---|
| 天气变化 | `MixinServerLevel.advanceWeatherCycle` | tick 内周期读（26.1：`ServerLevel.getWeatherData()`，见 [09](09-DELIVERY.md) §26.1.3） |
| 出生点变化 | `MixinServerLevel.setRespawnData` | `updateSpawnFromServer`（Bukkit `World.getSpawnLocation`）周期同步 |
| 结构观察 | `MixinChunkLoadingManager.markChunkPendingToSend` | 周期扫描玩家 view distance 内区块（`update_interval`，默认 40t） |
| TPS/MobCap | `tick` 内采集 | `BukkitScheduler` tick 任务 |
| 客户端就绪 | （原版无此问题） | `PlayerRegisterChannelEvent`（configuration phase 完成的可靠信号，见 [../AGENTS.md](../AGENTS.md) §1） |

> **Structures 触发的替代设计**：原版靠 chunk-watch mixin 精确触发；Paper 改**周期扫描**——每 `update_interval` tick 对每个注册玩家遍历其 view distance 内区块，`chunk.getAllReferences()` 收集结构、去重后发送。性能可接受（结构引用是 LongSet，查找 O(1)）。

---

## 4. Mixin 降级矩阵

> 完整 Mixin 清单见 [04](04-mixin-analysis.md)（servux）与 [22](22-syncmatica-mixin-migration.md)（syncmatica）。本节是**决策矩阵**：每个功能"做不做、怎么做"。

| 功能 | 原 Mixin | 迁移决策 | 优先级 | 备注 |
|---|---|---|---|---|
| **HUD 元数据/出生点/天气** | MixinMinecraftServer/ServerLevel | ✅ **做**（事件 + API） | P0 | 协议核心 |
| **TPS logger** | IMixinServerTickManager | ✅ **做**（NMS + 反射） | P1 | 反射 remainingSprintTicks |
| **MobCap logger** | (AW MAGIC_NUMBER) | ✅ **做**（NMS + 硬编码 289） | P1 | |
| **配方下发** | — | ✅ **做**（NMS Recipe.CODEC） | P1 | 大包，分包 |
| **实体/方块实体 NBT 查询** | MixinServerPlayNetworkHandler_QueryNbt | ✅ **做**（NMS saveWith*） | P1 | 权限走 Bukkit |
| **结构边界框** | MixinChunkLoadingManager | ✅ **做**（NMS getAllReferences，周期扫描触发） | P2 | 工作量最大 |
| **Litematica 投影粘贴** | MixinChestBlock/Rail/Stairs（镜像） | ✅ **做**（投影照抄 + 镜像修复**内联**到粘贴；任务化 PasteTask——见 [09](09-DELIVERY.md) §26.1.5/§26.1.6） | P2 | 见 [05](05-schematic-system.md) |
| **潜影盒可堆叠** | MixinItemStack/Hopper | ⛔ **不可能实现** | P3 | 改 NMS 方法全局返回行为，Paper 无 Mixin；已删全部相关代码（不下发 stackingShulkers 元数据，避免客户端误判）。详见 [04](04-mixin-analysis.md) §4 |
| **Allay 收集修复** | MixinMob/ItemEntity/Allay | ⚠️ **省略** | P4 | 改行为，影响小 |
| **EasyPlace**（Tweakeroo 精确放置） | MixinBlockItem_EasyPlace + MixinServerPlayNetworkHandler_EasyPlace | ✅ **已实现** | P3 | 「改写放行」范式：`EasyPlaceListener` netty 线程把编码包 `cursor.x` 改写回 `relX`（等效上游短路校验的 Mixin）+ 登记 pv；vanilla 全流程放置（**消除 netty 读手持的换手 desync 竞态**，2026-09 修复）；`EasyPlaceFixListener` 在 `BlockPlaceEvent`（HIGHEST）用 `applyPlacementProtocolV3` 修正属性。与上游差异：床/门双半格不修正（`BlockMultiPlaceEvent` 降级）、`itemPlacementContext` 恒 null、恢复 vanilla 距离/保护检查、保护插件重新可见放置事件 |
| **UpdateSuppression** | MixinLevel/LevelChunk/Block | ❌ **省略** | P4 | 改行为，Paper 无等价 |
| **调试 (IDE 模式)** | MixinSharedConstants | ❌ **省略** | — | 生产无用 |

> **决策原则**：P0/P1 是"协议能跑起来 + 核心功能"，必须做；P2 是完整功能；P3/P4 是"改服务端行为"类，降级/省略不影响协议本身。

---

## 5. 构建配置（26.1 as-built）

> 26.1 起 Mojang 移除服务端混淆：**reobf 废除**（paperweight 官方：reobf 插件无法在 Paper 26.1+ 加载），产物即 Mojang 映射 jar。版本唯一来源 `gradle.properties`（`mcVersion` / `buildNumber`）。

```kotlin
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

dependencies {
    paperweight.paperDevBundle("26.1.2.build.74-stable")
    // 26.1 起新格式 <mc>.build.<N>-stable；提供 Mojang 全映射 net.minecraft.* + io.papermc.paper.*
    // 可选依赖（EasyPlace）：compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

tasks {
    runServer { minecraftVersion("26.1.2"); jvmArgs("-Xms2G", "-Xmx2G") }
    processResources {
        // expand 占位符必须显式 inputs.property(...) 参与 up-to-date 跟踪（26.1.2-b2 曾实证陈旧展开事故）
        filesMatching("plugin.yml") { expand(props) }
    }
    // 无 reobfJar——tasks.assemble 不再依赖它；build 挂 verifyVersionInjection 终检版本注入一致性
}
```

**plugin.yml**（`api-version` 三段精确式，1.20.5+ 支持补丁段；本插件 MOD_STRING 硬门禁绑死精确补丁）：

```yaml
name: VeryMcProto
version: '${version}'
main: verymc.top.veryMcProto.VeryMcProto
api-version: '26.1.2'
load: POSTWORLD
```

> Gradle wrapper 9.7.1（run-paper 3.1.0 要求）；JDK 25 工具链经 foojay-resolver-convention 自动解析（见 [../AGENTS.md](../AGENTS.md) §技术栈与构建）。

---

## 6. 关键风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 客户端 32767 字节解码上限（未知通道） | S2C 大包断连 | `PacketSplitter` S2C 分片 32000/31995；已知通道大包走 NMS 直发（§2.2） |
| 客户端未装对应 Mod（如无 MiniHUD） | 发包失败/无响应 | `MAX_FAILURES` 计数 + invalid 玩家标记；`PlayerRegisterChannelEvent` + C2S 主动请求自愈 |
| `MOD_STRING` 协议握手字段 | 客户端版本协商 | **26.1 真值 `servux-fabric-<mcVersion>-b<buildNumber>`（当前 `servux-fabric-26.1.2-b4`）**——`MOD_TYPE` 恒 `"fabric"` 伪装，26.1 客户端 `startsWith("servux-fabric-<精确上游id>")` 硬门禁，1.21.11 时代的 `"paper"` 前缀会被四通道静默拒绝；协议版本用 26.1 真值 3/2/2/3/2（见 [09](09-DELIVERY.md) §26.1.2） |
| NMS 签名随版本漂移 | 升级 MC 时编译失败 | 反射点集中在 `framework/reflect/`；升级按 [04](04-mixin-analysis.md) 反射点清单 + [../AGENTS.md](../AGENTS.md) §4 NMS 约束核对 |
| Structures 周期扫描性能 | 玩家多时 CPU 占用 | 限扫描频率（`update_interval` 默认 40t=2s）；只扫 view distance 内；去重缓存 |
| EasyPlace 依赖 PacketEvents | 未装时功能缺席 | `EasyPlaceBootstrap` 反射加载 + `catch(Throwable)` 优雅跳过，其余通道零影响 |
| 通道名与 provider 名混淆 | 注册错通道 | 用各 Handler 的 `CHANNEL_ID` 常量（网络名），非 provider 名；见 [02](02-network-protocol.md) §2 |

---

## 7. Fabric ↔ Paper 逐域对照（原 06 并入，26.1 口径）

> 每行可直接当迁移转换规则用。网络层机制详见 [02](02-network-protocol.md)；Mixin 差异见 [04](04-mixin-analysis.md)。

### 7.1 总览：两个框架的根本差异

| 维度 | Fabric + Servux | Paper 插件 |
|---|---|---|
| **运行模型** | Mod 与服务端**同进程同类加载器**，可直接 `import net.minecraft.*`、用 Mixin 改字节码 | 插件在**隔离类加载器**，NMS 经 paperweight userdev 访问，**无 Mixin** |
| **NMS 可达性** | Loom 提供官方 Mojang 映射，全量 `net.minecraft.*` 可见可改 | paperweight userdev 同样提供 Mojang 映射（dev bundle）；**26.1 起产物与运行时同为 Mojang 名**，反射字符串直接用 Mojang 名 |
| **改字节码** | Mixin（运行时织入）+ AccessWidener（编译期暴露） | **无**。只能反射读私有、事件替代钩子、PacketEvents 拦截包 |
| **网络** | `fabric-networking-api-v1`（原版 `CustomPacketPayload` 封装） | plugin messaging channel（映射原版 custom payload）+ NMS `ClientboundCustomPayloadPacket` |
| **生命周期** | Mixin 钩 NMS 生命周期点 | Bukkit 事件 + scheduler |
| **权限** | `fabric-permissions-api`（Lucko） | Bukkit `Permission` / LuckPerms / Vault |
| **构建** | `fabric-loom` + `modImplementation` | Gradle + `paperweight userdev` + `run-paper`（§5） |

### 7.2 生命周期 / 入口

| Fabric | 触发方式 | Paper 等价（as-built） |
|---|---|---|
| `ModInitializer.onInitialize()` | JVM 加载 mod | `JavaPlugin.onEnable()` |
| `MixinMinecraftDedicatedServer.<init>@TAIL`（注册 provider） | Mixin | `onEnable` 直接注册 |
| `MixinMinecraftServer.runServer` → onServerStarting/Started | Mixin | `ServerLoadEvent`（不区分 LoadType，处理所有实例） |
| `MixinMinecraftServer.tickServer@RETURN` → tickProviders | Mixin | `runTaskTimer(plugin, task, 0L, 1L)` 每 tick + 内部按 interval 分发 |
| `MixinMinecraftServer.reloadResources` Pre/Post | Mixin | `/servux reload` 命令（自管重读） |
| `MixinMinecraftServer.stopServer` Pre/Post | Mixin | `PluginDisableEvent` / `onDisable` |
| `MixinMain.main`（捕获 RegistryAccess） | Mixin | `ServerLoadEvent(STARTED)` 后 NMS `registryAccess()` 捕获（时机命门：早了拿空注册表） |
| `MixinPlayerManager.placeNewPlayer` → onPlayerJoin | Mixin | `PlayerJoinEvent` |
| `MixinPlayerManager.remove` → onPlayerLeave | Mixin | `PlayerQuitEvent` |
| `MixinPlayerManager.respawn` | Mixin | `PlayerRespawnEvent` |
| `MixinPlayerManager.canPlayerLogin` | Mixin | `AsyncPlayerPreLoginEvent` / `PlayerLoginEvent` |
| `MixinServerLevel.advanceWeatherCycle`（天气采集） | Mixin | tick 内周期读天气状态（26.1：`ServerLevel.getWeatherData()`） |
| `MixinServerLevel.setRespawnData`（出生点） | Mixin | `updateSpawnFromServer`（Bukkit `World.getSpawnLocation`）周期同步 |
| `MixinChunkLoadingManager.markChunkPendingToSend`（结构触发） | Mixin | 周期扫描玩家 view distance 内区块查结构引用（§3.3） |
| `MixinCommandManager.<init>`（注册命令） | Mixin | `plugin.yml` `commands:` + `CommandExecutor`/`TabCompleter` |
| （1.20.2+ configuration phase 新增） | — | `PlayerRegisterChannelEvent`（客户端声明通道 = 握手可靠信号） |

### 7.3 网络层（要点对照；机制详见 [02](02-network-protocol.md)）

| Fabric | 作用 | Paper 等价 |
|---|---|---|
| `PayloadTypeRegistry.playC2S().register(Type, codec)` | 注册 C2S payload | `Messenger.registerIncomingPluginChannel(plugin, "servux:xxx", listener)` |
| `PayloadTypeRegistry.playS2C().register(Type, codec)` | 注册 S2C payload | `Messenger.registerOutgoingPluginChannel(plugin, "servux:xxx")` |
| `ServerPlayNetworking.registerGlobalReceiver(Type, handler)` | 注册接收器 | 同上 incoming 注册 + `PluginMessageListener` |
| `ServerPlayNetworking.send(player, payload)` | 发 S2C | `player.sendPluginMessage(...)` 或 NMS `connection.send(new ClientboundCustomPayloadPacket(payload))` |
| `ServerPlayNetworking.canSend(player, type)` | 客户端是否支持 | `player.getListeningPluginChannels().contains(channel)` + 同通道 C2S 证明兜底（[../AGENTS.md](../AGENTS.md) §1） |
| `ServerPlayNetworking.Context` | 携带 player | `PluginMessageListener` 直接给 `Player` |
| `record Payload implements CustomPacketPayload` + `StreamCodec` | 协议帧 | **照抄**（NMS `CustomPacketPayload`/`StreamCodec` 直连）；或直接序列化到 `byte[]` |
| `FriendlyByteBuf` / `Unpooled` | 字节流 | NMS `FriendlyByteBuf`；`byte[]`↔buf 用 `Unpooled.wrappedBuffer`/`buf.array()` |
| `ClientboundCustomPayloadPacket(payload)` + `connection.send` | NMS 发包 | **照抄**（大包直发路径，§2.2） |
| 单包上限 | PacketSplitter | Bukkit Messenger ~1MiB；**真正瓶颈是客户端 32767 解码上限** → S2C 分片常量 32000/31995（§2.2） |
| 未知 payload 处理 | Fabric 客户端注册即收 | **Paper 注册 incoming channel 即不踢玩家**（内置路由） |

### 7.4 权限

| Fabric | Paper |
|---|---|
| `Permissions.check(player, "servux.provider.hud_data", level)` | `framework.permission.Perms.check(...)`（显式设置优先 → `level<=0` 全员 → op 二值；详见 [40](40-configuration.md) §2.2） |
| permission level 0-4（NMS op 等级） | Bukkit 权限 + `default`；命令节点 `default: op` / `default: true` |
| `me.lucko:fabric-permissions-api` 依赖 | 无依赖（Bukkit 原生）；可选 LuckPerms/Vault 增强 |

### 7.5 配置 / 文件路径

| Fabric | Paper |
|---|---|
| `FabricLoader.getConfigDir()` → `config/servux.json` | `plugin.getDataFolder()` → `plugins/VeryMcProto/servux.json` |
| `Reference.DEFAULT_RUN_DIR = getGameDir()` | `Bukkit.getWorldContainer()` |
| `JsonUtils.parseJsonFileAsPath`（Gson） | 保留 Gson（Bukkit 自带）；**保持 JSON**（配置项系统基于 JsonObject，与原版一致） |
| `filesMatching("fabric.mod.json")` expand version | `processResources` expand `plugin.yml` 的 `${version}`（须显式 `inputs.property` 参与 up-to-date，§5） |

### 7.6 命令

| Fabric | Paper |
|---|---|
| `MixinCommandManager.<init>` 注入 Brigadier dispatcher | `plugin.yml` `commands:` + `CommandExecutor`/`TabCompleter` |
| Brigadier `LiteralArgumentBuilder`（NMS `Commands`） | Bukkit `CommandSender` args 解析（`/servux` 树照搬，含上游"值 <10 字符才显示"等怪癖） |
| `CommandSourceStack`（NMS 命令源） | `CommandSender`（Bukkit） |
| `SimpleCommandExceptionType` + i18n 消息 | `sender.sendMessage(...)` |

### 7.7 NBT API

| Fabric（直接 NMS） | Paper |
|---|---|
| `net.minecraft.nbt.CompoundTag` / `ListTag` / `Tag` | NMS 同名（paperweight 直连，**保真度最高**）；Bukkit `PersistentDataContainer` 受限不用 |
| `NbtOps.INSTANCE`（DataResult 编解码） | NMS 同名（配方/结构序列化用） |
| `NbtIo.writeCompressed` / `readCompressed` | NMS 同名（投影文件 GZIP） |
| `NbtView`（Servux 自研，基于 `TagValueInput/Output`） | 重写为直接 NMS `Entity.saveWithoutId(CompoundTag)` / `BlockEntity.saveWithFullMetadata`，绕开 `IMixinNbtRead/WriteView`（[04](04-mixin-analysis.md) §5） |

> **26.1 CompoundTag 约束**：`getXxx` 返回 Optional，用 `getXxxOr`/`.orElse()`；`putXxx` 返回 void（[../AGENTS.md](../AGENTS.md) §4）。

### 7.8 数据采集 API（NMS 可达性）——详见 [03](03-dataproviders-detail.md) §6

绝大多数 Servux 采集用的 NMS API，paperweight dev bundle **可直接 import 调用**，无需反射。仅以下需反射：

| NMS 项 | 原版访问方式 | Paper 处置 |
|---|---|---|
| `ServerTickRateManager.remainingSprintTicks`（private） | `IMixinServerTickManager @Accessor` | 反射 `Reflect.get` |
| `NaturalSpawner.MAGIC_NUMBER`(=289，private static) | AccessWidener | 反射 **或硬编码 289** |
| `TagValueInput.context`/`input`、`TagValueOutput.ops`/`output`（private） | `IMixinNbtRead/WriteView @Accessor` | 重写 `NbtView` 绕开（不反射） |
| `LevelTicks.allContainers`（private） | `IMixinWorldTickScheduler @Accessor` | 降级省略（粘贴不需要） |

### 7.9 i18n / 日志 / 杂项

| Fabric | Paper |
|---|---|
| `LogManager.getLogger(MOD_ID)`（log4j/SLF4J） | `plugin.getLogger()`（JUL）；SLF4J 占位符用 `Log.java` shim 机械替换 |
| `util/i18n/` + `assets/servux/lang/*.json` | 硬编码中英文消息（客户端不关心服务端消息语言） |
| `SharedConstants.getCurrentVersion()` | `Reference.MC_VERSION`（version.properties 注入） |
| `FabricLoader.getInstance()` | `Bukkit.getServer()` / `plugin.getDataFolder()` |

### 7.10 一页纸转换清单（移植时逐条核对）

```
入口/生命周期    onInitialize → onEnable + Bukkit 事件
provider 注册    MixinDedicatedServer → onEnable 直接 register
tick 调度        MixinMinecraftServer.tickServer → BukkitScheduler runTaskTimer
玩家进退服        MixinPlayerManager → PlayerJoinEvent/PlayerQuitEvent
客户端就绪        （原版无）→ PlayerRegisterChannelEvent
配置路径         config/servux.json → plugins/VeryMcProto/servux.json
权限             Permissions.check → framework.permission.Perms.check
命令             MixinCommandManager → plugin.yml + CommandExecutor
网络收(C2S)      ServerPlayNetworking → Messenger.registerIncomingPluginChannel
网络发(S2C)      ServerPlayNetworking.send → sendPluginMessage 或 NMS ClientboundCustomPayloadPacket
字节流            FriendlyByteBuf → NMS FriendlyByteBuf（paperweight 直连）
分包             PacketSplitter → 照抄（S2C 常量 32000/31995，防客户端 32767）
NBT              CompoundTag/NbtIo → NMS 直连
Mixin 读私有     → 反射 / NMS 直接调用（多数已是公开方法）
Mixin 改行为     → 降级省略 / PacketEvents / 投影代码内联
构建             fabric-loom → paperweight userdev + run-paper（26.1 无 reobf）
```

---

## 8. 验证策略（移植完成如何确认对）

1. **单元级**：`PacketSplitter` / `FeatureSet` / `LitematicaBitArray` / `DataTagIo` / task 组纯函数单测（`./gradlew test`）。
2. **协议级**：`runServer` + 真 Fabric 客户端（MiniHUD/Litematica/Tweakeroo/JEI/syncmatica）按 [10](10-testing-guide.md) / [24](24-syncmatica-testing-guide.md) / [30](30-jei-protocol.md) 流程验证。
3. **抓包对照**：比对 Fabric+原版服务端 mod 与 Paper+插件 的字节流是否一致（保真度关键验证）。
4. **回归**：跨多个 Minecraft 小版本验证 NMS 反射点（升级流程见 [../AGENTS.md](../AGENTS.md) §维护与升级要点）。

---

## 9. 与原版的保真度目标

| 层 | 保真度 | 说明 |
|---|---|---|
| 网络协议字节 | **100%** | 同一 `FriendlyByteBuf`/`CompoundTag`/DataTag 线格式，字节级一致 |
| 通道/版本号 | **100%** | 通道名、协议版本号与原版逐字对齐（26.1 真值） |
| 数据采集 | **≈95%** | 绝大多数 NMS 直连；TPS/MobCap 个别字段反射可能版本敏感 |
| 服务端行为改造 | **部分降级** | EasyPlace ✅ 已实现（PacketEvents）；UpdateSuppression/Allay 省略；潜影盒堆叠不可能实现（已删代码） |

> **结论**：对"Fabric 客户端 + Paper 服务端"的核心使用场景（HUD/结构/投影/实体查询），达到与原版 Servux **功能等价**；仅少数"服务端行为增强"特性降级，且均不影响协议主功能。
