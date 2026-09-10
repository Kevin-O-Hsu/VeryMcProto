# 07 · 完整迁移技术方案（含可行性验证）

> 本文是移植的**总设计**：目标架构、各层迁移决策、可行性结论（附网络文档验证）、降级矩阵、构建配置、风险缓解。
> 阅读前提：已读 [01](01-servux-architecture.md)–[06](06-fabric-vs-paper.md)。实施步骤见 [08](08-implementation-plan.md)。

---

## 0. 可行性总结论（先看这个）

| 问题 | 结论 | 依据 |
|---|---|---|
| Servux 的网络协议能在 Paper 复刻吗？ | ✅ **能，且无需第三方库** | plugin messaging channel 直接映射原版 `CustomPacketPayload`；`byte[]` = `FriendlyByteBuf` 裸字节。实证 [FabricMC #4430](https://github.com/orgs/FabricMC/discussions/4430)、[Paper plugin-messaging 文档](https://docs.papermc.io/paper/dev/plugin-messaging/) |
| 能访问 Servux 采集所需的 NMS 吗？ | ✅ **能** | paperweight userdev 提供 Mojang 全映射 `net.minecraft.*`（姊妹项目 VeryMcBot 已验证范式）；绝大多数采集 API 是公开/包级方法，直接调用 |
| 26 个 Mixin 怎么办？ | ✅ **可控** | 读私有(4-5个)→反射；生命周期(9个)→Bukkit 事件；改行为(11个)→降级省略/内联/PacketEvents；调试(1)→省略。详见 [04](04-mixin-analysis.md) |
| 大包（配方/投影）能发吗？ | ✅ **能** | PacketSplitter 照抄；plugin messaging 32KiB 限制通过「调小 S2C 分片」或「S2C 走 NMS `ClientboundCustomPayloadPacket` 保 1MiB」解决 |
| 投影系统（最大模块）能搬吗？ | ✅ **能** | ~60% 纯算法照抄；接口层 NMS 直连 `BlockState`/`CompoundTag`/`NbtIo`。详见 [05](05-schematic-system.md) |
| 有阻塞性难点吗？ | ⚠️ **UpdateSuppression** 等改服务端行为的功能需降级（EasyPlace 已用 PacketEvents 实现） | 见 §降级矩阵 |

**总体判定：协议层 100% 可移植；数据采集层绝大部分可直接 NMS 实现；仅少量"改行为"特性降级。迁移可行。**

---

## 1. 目标架构

### 1.1 技术栈决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 构建 | **paperweight `userdev`** + `run-paper` | 与 VeryMcBot 一致；提供完整 Mojang NMS 映射；`reobfJar` 产出标准 Paper 可加载 |
| 网络收发 | **plugin messaging channel**（主）+ NMS `ClientboundCustomPayloadPacket`（大包保真） | plugin messaging 内置路由不踢玩家、API 简单；NMS 发包绕过 32KiB 限制 |
| 数据采集 | **NMS 直连**（主）+ 反射（辅，仅 4-5 点） | 绝大多数 Servux 采集 API 是公开方法；反射点已识别（[03](03-dataproviders-detail.md) §6） |
| NBT | **NMS `CompoundTag`/`NbtIo`** | 与原版字节级一致，Litematica 客户端可直接读；保真度最高 |
| 权限 | Bukkit `Permission`（+ LuckPerms 兼容） | 无新依赖；servux 权限节点原样保留 |
| Mixin | **不使用** | Paper 无 Mixin 运行时 |

### 1.2 目标包结构

```
verymc.top.veryMcProto/
├── VeryMcProto.java                  JavaPlugin 主类（onEnable 注册一切）
├── Reference.java                    常量（MOD_STRING 改 paper 标识）
├── network/
│   ├── ProtocolChannel.java          单通道封装（注册 incoming/outgoing + Listener）
│   ├── ChannelManager.java           5 条通道注册表（替代 ServerPlayHandler）
│   ├── PayloadCodec.java             Payload/StreamCodec 照抄（去 @Environment）
│   ├── PacketSplitter.java           分包器照抄（S2C 常量调整）
│   └── codec/                        5 条通道的 toPacket/fromPacket 逻辑（照抄 Servux*Packet）
├── dataproviders/
│   ├── IDataProvider.java            接口照抄
│   ├── DataProviderBase.java         基类照抄
│   ├── DataProviderManager.java      注册表/调度/配置照抄
│   ├── HudDataProvider.java          见 §3
│   ├── EntitiesDataProvider.java
│   ├── TweaksDataProvider.java
│   ├── StructureDataProvider.java
│   ├── LitematicsDataProvider.java
│   └── ConfigProvider.java           servux_main 全局配置
├── schematic/                        投影系统照抄（见 05）
├── loggers/                          TPS/MobCap 采集（见 §3）
├── command/                          /servux（Paper Brigadier）
├── config/                           servux.json 读写
├── event/                            Bukkit 事件监听（替代 Mixin 生命周期）
├── nbt/                              NbtUtils/NbtView 重写
├── reflect/                          NMS 反射工具（仿 VeryMcBot）
└── util/                             工具照抄
```

### 1.3 启动流程（Paper 版）

```
VeryMcProto.onEnable():
  1. 读 servux.json → DataProviderManager.readFromConfig()
  2. 注册 6 个 provider（ConfigProvider 永远启用）
  3. 对每个 enabled provider：
       - ChannelManager.register(channel)：Messenger.registerIncoming + registerOutgoing
       - provider.registerHandler()（内部完成 receiver 绑定）
  4. 捕获 RegistryAccess：server.registryAccess() → DataProviderManager.onCaptureImmutable
  5. 注册事件监听器（PlayerListener/ServerListener 的 Bukkit 版）
  6. 启 tick 调度：runTaskTimer 每 tick → DataProviderManager.tickProviders
  7. 注册 /servux 命令（Paper Brigadier 或 plugin.yml）

onPlayerJoin（PlayerJoinEvent）:
  对每个 enabled provider → provider.sendMetadata(player) / register(player)

onDisable():
  DataProviderManager.writeToConfig() + onTickEndPre()
```

---

## 2. 网络层迁移方案 ⭐

### 2.1 通道注册（plugin messaging）

```java
// network/ProtocolChannel.java（Paper 版伪代码）
public final class ProtocolChannel {
    private final JavaPlugin plugin;
    private final String channelId;          // "servux:hud_metadata" 等
    private final BiConsumer<Player, FriendlyByteBuf> receiver;  // 收到 C2S 时的处理

    public void register() {
        Messenger m = plugin.getServer().getMessenger();
        m.registerIncomingPluginChannel(plugin, channelId, (ch, player, bytes) -> {
            // bytes 就是 FriendlyByteBuf 裸字节（含 VarInt packetType + NBT/buffer）
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
            receiver.accept(player, buf);
        });
        m.registerOutgoingPluginChannel(plugin, channelId);
    }

    // 发 S2C（普通包）
    public void send(Player player, FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        player.sendPluginMessage(plugin, channelId, bytes);
    }

    // 发 S2C（大包，走 NMS 绕过 32KiB）—— 可选，见 §2.3
    public void sendNms(ServerPlayer nmsPlayer, CustomPacketPayload payload) {
        nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(payload));
    }
}
```

> **关键**：`onPluginMessageReceived` 的 `bytes` 直接喂给原版的 `ServuxXxxPacket.fromPacket(FriendlyByteBuf)` 即可，**协议解码逻辑零改动**。

### 2.2 单包字节限制：两种方案

**方案 A（推荐起步）：全程 plugin messaging，调小 S2C 分片**
```java
// PacketSplitter.java（Paper 版）
// 原版：MAX_PAYLOAD_PER_PACKET_S2C = 1MiB - 5
// Paper plugin messaging 上限 32768，留余量：
public static final int MAX_PAYLOAD_PER_PACKET_S2C = 32_000;
```
- 优点：全程统一 API，简单。
- 缺点：大包（Recipe/投影）分片数变多（5MB 投影从 ~5 片变 ~170 片），但功能正常。

**方案 B（大包优化）：S2C 大包走 NMS `ClientboundCustomPayloadPacket`**
```java
// 保留 1MiB 分片，但每片用 NMS 发包（不走 plugin messaging）
ClientboundCustomPayloadPacket pkt = new ClientboundCustomPayloadPacket(payload);
((CraftPlayer) player).getHandle().connection.send(pkt);
```
- 优点：分片少、保真度高（与原版一致）。
- 缺点：需 paperweight NMS；C2S 仍走 plugin messaging（C2S 请求本就小，32KiB 够）。

> **决策**：起步用方案 A（先把协议跑通），性能敏感的大包通道（Recipe/Litematic）后续按需升级方案 B。两者可共存（按通道选择）。

### 2.3 字节级可行性验证

- ✅ `byte[]` ↔ `FriendlyByteBuf`：`Unpooled.wrappedBuffer(bytes)` 包装、`buf.getBytes(...)`/`buf.array()` 取出。零拷贝语义与原版一致。
- ✅ `CompoundTag` 读写：`FriendlyByteBuf.writeNbt(tag)` / `readNbt()`，NMS 直连，与 Fabric 端字节一致（同一套 Mojang NBT 协议）。
- ✅ VarInt：`FriendlyByteBuf.writeVarInt/readVarInt`，NMS 直连。
- ✅ 通道命名：`servux:hud_metadata` 满足 plugin channel 的 `namespace:key` 规则（`MAX_CHANNEL_SIZE` 校验通过）。

### 2.4 Payload record 照抄

`ServuxHudPacket.Payload`（及另 4 条通道的 Payload）在 Paper 端**去掉 `@Environment(EnvType.SERVER)`** 后几乎原样可用——`CustomPacketPayload.Type`、`StreamCodec`、`FriendlyByteBuf` 全是 NMS（paperweight 直连）。若用方案 A（plugin messaging 收发 byte[]），则 Payload record 主要用于"方案 B 的 NMS 发包"与"协议定义文档化"；收发入口走 ProtocolChannel 的 byte[]。

---

## 3. 数据采集迁移方案

> 各 Provider 的具体采集点见 [03](03-dataproviders-detail.md)；本节给迁移范式。

### 3.1 NMS 直连范式（绝大多数）

Servux 采集用的这些 NMS API，paperweight dev bundle 可直接 import：
- `MinecraftServer` / `ServerLevel` / `ServerPlayer`（`((CraftWorld)world).getHandle()` / `((CraftPlayer)player).getHandle()` 取 NMS）
- `ServerLevel.getSeed()` / `recipeAccess().getRecipes()` / `getChunkSource().getLastSpawnState()`
- `Recipe.CODEC.encodeStart(NbtOps.INSTANCE, ...)`（配方）
- `BlockEntity.saveWithFullMetadata(registryAccess)` / `Entity.saveWithoutId(...)`
- `ChunkAccess.getAllReferences()` / `getStartForStructure()` / `StructureStart.createTag(ctx, pos)`（结构）
- `MinecraftServer.registryAccess()`（RegistryAccess）

→ **这些在 Paper 上 = 直接调用，零额外成本**（Servux 原本要 Mixin/AccessWidener 才能访问的，反而因为 paperweight 给了完整映射而简化）。

### 3.2 反射范式（仅 4-5 点）

仿 VeryMcBot 的 `reflect/Reflect` 工具类（`MethodHandles` / 字段读写）：

```java
// TPS：读 ServerTickRateManager.remainingSprintTicks（private long）
long sprint = Reflect.get(tickManager, "remainingSprintTicks");  // Mojang 名

// MobCap：NaturalSpawner.MAGIC_NUMBER（private static int = 289）
// 推荐：直接硬编码 int MAGIC = 289; （注释标明来源 NaturalSpawner.MAGIC_NUMBER）

// NbtView：重写绕开 TagValueInput/Output 私有字段（不反射）
```

### 3.3 触发点迁移（Mixin → 事件）

| 采集触发 | Fabric（Mixin） | Paper（事件/调度） |
|---|---|---|
| 天气变化 | `MixinServerWorld.advanceWeatherCycle` | `WeatherChangeEvent` + 周期读 `World#getWeatherDuration` |
| 出生点变化 | `MixinServerWorld.setRespawnData` | 周期同步 `World#getSpawnLocation`，比对变化 |
| 结构观察 | `MixinServerChunkLoadingManager.markChunkPendingToSend` | 周期扫描玩家周围（view distance 内）区块查结构引用 |
| TPS/MobCap | `tick` 内采集 | `BukkitScheduler` tick 任务 |

> **Structures 触发的替代设计**：原版靠 chunk-watch mixin 精确触发。Paper 无等价细粒度事件，**改为"周期扫描"**：每 `update_interval` tick，对每个注册玩家，遍历其 view distance 内区块，调 `chunk.getAllReferences()` 收集结构，去重后发送。性能可接受（结构引用是 LongSet，查找 O(1)）。

---

## 4. Mixin 降级矩阵

> 完整 Mixin 清单见 [04](04-mixin-analysis.md)。本节是**决策矩阵**：每个功能"做不做、怎么做"。

| 功能 | 原 Mixin | 迁移决策 | 优先级 | 备注 |
|---|---|---|---|---|
| **HUD 元数据/出生点/天气** | MixinMinecraftServer/ServerWorld | ✅ **做**（事件 + API） | P0 | 协议核心 |
| **TPS logger** | IMixinServerTickManager | ✅ **做**（NMS + 反射） | P1 | 反射 remainingSprintTicks |
| **MobCap logger** | (AW MAGIC_NUMBER) | ✅ **做**（NMS + 硬编码 289） | P1 | |
| **配方下发** | — | ✅ **做**（NMS Recipe.CODEC） | P1 | 大包，分包 |
| **实体/方块实体 NBT 查询** | MixinServerPlayNetworkHandler_QueryNbt | ✅ **做**（NMS saveWith*） | P1 | 权限走 Bukkit |
| **结构边界框** | MixinServerChunkLoadingManager | ✅ **做**（NMS getAllReferences，周期扫描触发） | P2 | 工作量最大 |
| **Litematica 投影粘贴** | MixinChestBlock/Rail/Stairs（镜像） | ✅ **做**（投影照抄 + 镜像修复**内联**到粘贴；S2C 投递死信链已删——26.1 客户端无接收端） | P2 | 见 [05](05-schematic-system.md) |
| **潜影盒可堆叠** | MixinItemStack/Hopper | ⛔ **不可能实现** | P3 | 改 NMS 方法全局返回行为，Paper 无 Mixin；已删 Tweaks provider 相关遗留代码（不下发 stackingShulkers 元数据，避免客户端误判）。详见 [04](04-mixin-analysis.md) §4 |
| **Allay 收集修复** | MixinMob/ItemEntity/Allay | ⚠️ **省略** | P4 | 改行为，影响小 |
| **EasyPlace**（Tweakeroo 精确放置） | MixinBlockItem_EasyPlace + MixinServerPlayNetworkHandler_EasyPlace | ✅ **已实现** | P3 | 「改写放行」范式：`EasyPlaceListener` netty 线程把编码包 `cursor.x` 改写回 `relX`（等效上游短路校验的 Mixin）+ 登记 pv；vanilla 全流程放置（手持/检查/BE/消耗/ack 原生——**消除 netty 读手持的换手 desync 竞态**，2026-09 修复）；`EasyPlaceFixListener` 在 `BlockPlaceEvent`（HIGHEST）用 `applyPlacementProtocolV3` 修正属性（基座=vanilla 落块状态）。与上游差异：床/门双半格不修正（`BlockMultiPlaceEvent` 降级）、`itemPlacementContext` 恒 null、恢复 vanilla 距离/保护检查、保护插件重新可见放置事件 |
| **UpdateSuppression** | MixinWorld/WorldChunk/Block | ❌ **省略** | P4 | 改行为，Paper 无等价，省略 |
| **调试 (IDE 模式)** | MixinSharedConstants | ❌ **省略** | — | 生产无用 |

> **决策原则**：P0/P1 是"协议能跑起来 + 核心功能"，必须做；P2 是完整功能；P3/P4 是"改服务端行为"类，降级/省略不影响协议本身，可后续迭代。

---

## 5. 构建配置（paperweight userdev 模板）

> 当前 `build.gradle.kts` 是纯 `compileOnly(paper-api)`。**阶段 0 改为**：

```kotlin
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"   // 与 VeryMcBot 对齐
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

dependencies {
    paperweight.paperDevBundle("1.21.11", "R0.1-SNAPSHOT")
    // paperweight.devBundle 提供 Mojang 全映射 net.minecraft.* + io.papermc.paper.*
    // 第三方：compileOnly(`com.github.retrooper:packetevents-spigot:2.13.0`)  // EasyPlace（softdepend 运行时）
}

java { toolchain.languageVersion = JavaLanguageVersion.of(21) }

tasks {
    runServer { minecraftVersion("1.21.11"); jvmArgs("-Xms2G","-Xmx2G") }
    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") { expand(props) }
    }
    // reobfJar 由 paperweight 自动配置，产出标准 Paper 可加载 jar
}
```

> `paperDevBundle` 与 VeryMcBot 的 `paperDevBundle("1.21.11-R0.1-SNAPSHOT")` 等价（写法因 paperweight 版本略异，以实际可用为准；VeryMcBot 已在该环境验证通过）。

**plugin.yml**（修正 `api-version`，Paper 1.20.5+ 用主次版本）：
```yaml
name: VeryMcProto
version: '${version}'
main: verymc.top.veryMcProto.VeryMcProto
api-version: '1.21'        # ← 当前是 '1.21.11'，Paper 只接受主次版本，需改
load: POSTWORLD
```

---

## 6. 关键风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| plugin messaging 32KiB 限制 | 大包发送失败 | 方案 A 调小分片 / 方案 B NMS 发包；PacketSplitter 已有分包机制兜底 |
| 客户端未装对应 Mod（如无 MiniHUD） | 发包失败/无响应 | 保留原版 `MAX_FAILURES` 重试 + invalid 玩家标记；JOIN 后延迟试探 |
| `MOD_STRING` 协议握手字段 | 客户端版本协商 | 改为 `servux-paper-1.21.11-x.y.z`；`version`(协议版本号) **保持不变**（HUD=2 等） |
| NMS 签名随版本漂移 | 升级 MC 时编译失败 | 反射点集中在 `reflect/`；升级时按 [04](04-mixin-analysis.md) 反射点清单核对 |
| Structures 周期扫描性能 | 玩家多时 CPU 占用 | 限扫描频率（`update_interval` 默认 100t=5s）；只扫 view distance 内；去重缓存 |
| EasyPlace 已实现 | Tweakeroo 精确放置可用（需服务器装 PacketEvents 插件） | 「改写放行」：`EasyPlaceListener` 改写编码包 cursor 放行 + `EasyPlaceFixListener` 在 `BlockPlaceEvent` 协议 v3 修正 |
| 通道名与 provider 名混淆 | 注册错通道 | 用各 Handler 的 `CHANNEL_ID` 常量（网络名），非 provider 名；见 [02](02-network-protocol.md) §2 |

---

## 7. 验证策略（移植完成如何确认对）

1. **单元级**：`PacketSplitter` 分包/重组单测（纯算法）；`LitematicaBitArray` 读写单测。
2. **协议级**：起测试服（`runServer`）+ 真 Fabric 客户端（装 MiniHUD/Litematica/Tweakeroo），验证：
   - MiniHUD HUD 显示世界信息/出生点/TPS（HUD 通道）
   - MiniHUD 显示结构边界框（Structures 通道）
   - Litematica 能从服务端拉取/上传投影、粘贴（Litematics 通道）
   - 实体/方块实体 NBT 查询（Entities/Tweaks 通道）
3. **抓包对照**：用 Wireshark / Paper 日志比对 Fabric+Servux 与 Paper+插件 的字节流是否一致（关键验证保真度）。
4. **回归**：跨多个 Minecraft 小版本验证 NMS 反射点。

---

## 8. 与原版的保真度目标

| 层 | 保真度 | 说明 |
|---|---|---|
| 网络协议字节 | **100%** | 同一 `FriendlyByteBuf`/`CompoundTag`，字节级一致 |
| 通道/版本号 | **100%** | 通道名、协议版本号保持原版 |
| 数据采集 | **≈95%** | 绝大多数 NMS 直连；TPS/MobCap 个别字段（sprintTicks）反射可能版本敏感 |
| 服务端行为改造 | **部分降级** | EasyPlace ✅ 已实现（PacketEvents）；UpdateSuppression/Allay 省略；潜影盒堆叠不可能实现（已删代码） |

> **结论**：对"Fabric 客户端 + Paper 服务端"的核心使用场景（HUD/结构/投影/实体查询），可达到与原版 Servux **功能等价**；仅少数"服务端行为增强"特性降级，且均不影响协议主功能。
