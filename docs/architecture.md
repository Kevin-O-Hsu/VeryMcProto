# 全局架构 · 网络框架 · Mixin→Paper 迁移参考（1.21.11 线）

> 本文是本线全局层的唯一权威：分层架构与框架契约（§1–§2）、网络框架与 S2C 投递路径（§3，含 **NMS 实测坑清单 §3.6**）、生命周期（§4）、**Mixin→Paper 统一处置矩阵（§5，唯一权威）**、Fabric↔Paper 逐域对照（§6，原 06-fabric-vs-paper.md 并入此节）、构建与工具链（§7）、防御性编程要点（§8）、验证与保真度（§9）。
> **字节上限裸值唯一权威在本文 §3**（域文档只写行为语义+指针）；Servux 通道 wire 细节与 **1.21.11 wire 真值**见 [servux/protocol.md](servux/protocol.md)（§9）；各 Provider 数据内容见 [servux/providers.md](servux/providers.md)；syncmatica/JEI 见各自文档；命令/权限/配置/升级 SOP 见 [operations.md](operations.md)；仓库权威说明见 [../AGENTS.md](../AGENTS.md)。
> 历史文档（NN- 编号时代原件与 docs/research/ 迁移笔记）已冻结归档于 [archive/](archive/README.md)——本文由 01/06/07 + 04/09 活内容蒸馏归并而成（映射见 [index.md](index.md) 历史锚点映射表）。

---

## 1. 分层架构

### 1.1 原版 Servux 顶层结构（对照源）

```
fi.dy.masa.servux/
├── Servux.java                 ← ModInitializer 入口（onInitialize）
├── Reference.java              ← 常量（MOD_ID/MC_VERSION/配置目录/DEV_DEBUG）
├── servux/                     ← 真正的业务监听实现（ServerListener / PlayerListener / ServuxInitHandler）
├── event/                      ← 事件分发器（ServerHandler / PlayerHandler / ServerInitHandler）+ 接口
├── interfaces/                 ← 抽象接口（IServerListener / IPlayerListener / IServerInitHandler / 线程任务等）
├── dataproviders/              ← 核心：6 个 Provider + DataProviderManager + 接口/基类
├── network/                    ← 网络层（见 servux/protocol.md）
├── loggers/                    ← HUD 子系统：TPS / MobCap 数据采集
├── schematic/                  ← Litematica 投影系统（见 servux/schematic.md）
├── settings/                   ← 配置项系统（AbstractServuxSetting + 各类型）
├── commands/                   ← /servux 命令（CommandProvider + ServuxCommand）
├── util/                       ← 工具（NbtUtils/NbtView/JsonUtils/PositionUtils/i18n/log/...）
└── mixin/                      ← 26 个 Mixin（处置矩阵见本文 §5）
```

**核心三层**：`dataproviders/`（业务层）· `network/`（协议层）· `event/ + servux/ + mixin/`（生命周期层）。

### 1.2 目标架构与技术栈决策（as-built）

| 决策 | 选择 | 理由 |
|---|---|---|
| 构建 | **paperweight `userdev` 2.0.0-beta.21** + run-paper | 提供完整 Mojang NMS 映射；本线 **reobf 仍存在**——`reobfJar` 产出 Spigot 运行时映射 jar，标准 Paper 直接加载 |
| 网络收发 | **plugin messaging channel**（主）+ NMS `ClientboundCustomPayloadPacket`（大包/直发） | plugin messaging 内置路由不踢玩家、API 简单；NMS 直发承载超限大包（JEI 配方、syncmatica S2C、servux 声明滞后兜底） |
| 数据采集 | **NMS 直连**（主）+ 反射（辅，仅 4-5 点） | 绝大多数 Servux 采集 API 是公开方法；反射点见 §3.5 |
| NBT | **NMS `CompoundTag`/`NbtIo`** | 与原版字节级一致（本线业务包 = vanilla `writeNbt` 载体；DataTag 为 26.1 线形态） |
| 权限 | Bukkit `Permission`（+ LuckPerms 兼容） | 无新依赖；servux 权限节点原样保留 |
| Mixin | **不使用** | Paper 无 Mixin 运行时 |

> 反射用 **Mojang 名**：reobf 不转换反射字符串，而 Paper 运行时即 Mojang 映射——反射 Mojang 名天然正确。

### 1.3 实际包结构（as-built）

```
verymc.top.veryMcProto/
├── VeryMcProto.java              JavaPlugin 主类（框架入口 + 装配 + 依次注册四个 mod + 命令）
├── Reference.java                框架级常量（MC_VERSION / PLUGIN_VERSION——类加载时读 version.properties）
│
├── framework/                    协议 mod 移植框架（与具体 mod 无关，所有 mod 复用）
│   ├── network/                  通道封装（ChannelManager/ProtocolChannel + C2S 证明兜底）、分片（PacketSplitter）、
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
└── mod/                          协议 mod 层（每个被移植的 Fabric 协议 mod 一个目录单元）
    ├── servux/                   app/ServuxModule + command/ + dataproviders/（6 Provider）+ network/（5 Handler+Packet）
    │                             + easyplace/ + loggers/ + schematic/ + util/
    ├── jei/                      app/JeiModule + network/（payload/ wire 包族 + RecipeSyncJoinOrderer）
    │                             + transfer/ + cheat/ + recipesync/ + config/ + command/
    ├── syncmatica/               app/SyncmaticaModule + communication/（+exchange/）+ data/（+litematica/）
    │                             + extended_core/ + network/ + service/ + util/
    └── carpet/                   CarpetModule + settings/ + patches/ + helpers/ + network/ + logging/ + command/ + util/
```

**新增协议 mod 的步骤**：在 `mod/<newmod>/` 实现 `ModModule`（或自管装配如 syncmatica/jei），在主类 `onEnable` 注册，在 `plugin.yml` 加命令/权限——无需改动框架（详见 [../AGENTS.md](../AGENTS.md) §代码架构）。

### 1.4 启动流程（Paper 版，as-built）

```
VeryMcProto.onEnable():
  1. 初始化框架（ChannelManager / DataProviderManager / LifecycleBridge）
  2. 依次注册 servux / jei / syncmatica / carpet 四个模块（各自 try-catch，单点失败降级不阻断）
  3. 注册 /servux /jei /syncmatica /carpet 命令
  4. ServerLoadEvent(STARTED) 后捕获 RegistryAccess → DataProviderManager.onCaptureImmutable
     + readFromConfig（servux.json 加载）
  5. LifecycleBridge：PlayerJoin/Quit/Respawn/RegisterChannel 事件桥 + runTaskTimer 每 tick tickProviders

onPlayerJoin（PlayerJoinEvent）:
  对每个 enabled provider → provider.onPlayerJoin(player)（40t 延迟 sendMetadata + onPlayerRegisterChannel 兜底）

onDisable():
  各模块 disable（syncmatica placements.json 原子落盘、carpet kill 全假人等）
```

---

## 2. 框架契约（Provider 体系）

### 2.1 `DataProviderManager` —— 注册表/调度器/配置中枢

原版 `dataproviders/DataProviderManager.java`，单例 `INSTANCE`。核心数据结构：

```java
protected final HashMap<String, IDataProvider> providers = new HashMap<>();      // 名字 → provider
protected ImmutableList<IDataProvider> providersImmutable = ImmutableList.of();  // 快照（遍历用）
protected ArrayList<IDataProvider> providersTicking = new ArrayList<>();         // 需要 tick 的子集
protected RegistryAccess.Frozen immutable = RegistryAccess.EMPTY;                // 早期捕获的注册表
```

核心方法：`registerDataProvider`（按名登记去重）/ `setProviderEnabled`（启停 + 通道注册联动）/ `tickProviders`（按 `tick % getTickInterval() == 0` 分发）/ `readFromConfig`/`writeToConfig`（`servux.json`，先各 settings 再 `DataProviderToggles` 决定启停）/ `onCaptureImmutable`（缓存注册表）/ `getSettingByName`（支持 `provider:setting` 格式，给 /servux set 用）。配置路径 = `plugins/VeryMcProto/servux.json`（全键表见 [operations.md](operations.md) §3.1）。

### 2.2 `IDataProvider` / `DataProviderBase` —— Provider 契约

```java
String getName();                 // 逻辑名（config key，如 "hud_data"）—— ≠ 网络通道名
Identifier getNetworkChannel();   // 网络通道（如 servux:hud_metadata）
int getProtocolVersion();         // 协议版本（1.21.11 真值见 servux/protocol.md §9）
void registerHandler();           // 启用：注册通道 + payload + receiver
void unregisterHandler();         // 禁用：反注册
default boolean shouldTick(); int getTickInterval();   // 默认间隔 40t
boolean isPlayerRegistered(ServerPlayer); boolean hasPermission(ServerPlayer);
JsonObject toJson(); void fromJson(JsonObject);        // settings 序列化
```

`DataProviderBase` 构造固化元信息（name/channel/protocolVersion/perm 0-4/permNode/description）。**纯抽象几乎照抄**（NMS 类型经 paperweight 可直接引用；`hasPermission` 换 Bukkit 权限）。

### 2.3 配置项系统与全局主 Provider

每 Provider 持有一组 `IServuxSetting<?>`（Bool/Int(min-max)/String/StringList + 变更回调 `IServuxSettingCallback`），由 Manager 统一持久化；`/servux set` 经 `getSettingByName` 定位。**纯 Java，照抄**。`ServuxConfigProvider`（`servux_main`）= 永不被禁用的全局配置单例（权限基线 / EasyPlace 开关 / `default_language` / `debug_log`），`registerHandler` NO-OP 不发包。

### 2.4 Reference / i18n / 日志

- `MOD_STRING` = `servux-paper-<mcVersion>-b<buildNumber>`（**本线 `MOD_TYPE="paper"`**——1.21.11 客户端只认 `servux-` 前缀 + 比对版本号；26.1 起客户端才硬门禁 `fabric` 前缀，26.x 线改用伪装，本线保持 paper 原生形态）。
- 配置目录 = `plugin.getDataFolder()`；日志 = `JavaPlugin.getLogger()`（SLF4J 占位符用 `Log.java` shim 机械替换）；i18n 硬编码中英文消息（masa 客户端不关心服务端消息语言）。

---

## 3. 网络框架与 S2C 投递路径

### 3.1 通道注册（plugin messaging）

```java
// framework/network/ProtocolChannel（形态示意）
public void register() {
    Messenger m = plugin.getServer().getMessenger();
    m.registerIncomingPluginChannel(plugin, channelId, (ch, player, bytes) -> {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));  // bytes 即 FriendlyByteBuf 裸字节
        receiver.accept(player, buf);
    });
    m.registerOutgoingPluginChannel(plugin, channelId);
}
```

> **关键**：`onPluginMessageReceived` 的 `bytes` 直接喂原版 `ServuxXxxPacket.fromPacket(FriendlyByteBuf)`，**协议解码逻辑零改动**。Paper 原版服务端对未注册 custom payload 会**踢玩家**（"Invalid payload"）；plugin messaging 注册的通道由 Paper 内置路由、不踢人——这是用 `Messenger.registerIncomingPluginChannel` 收 C2S 的根本理由（见 [../AGENTS.md](../AGENTS.md) §1「C2S 接收命门」）。

### 3.2 字节限制真实模型与两种投递路径

- Bukkit `Messenger.MAX_MESSAGE_SIZE` = **1048576（~1MiB）**（1.21.x 起；旧文档称 32768 已过时）。
- **真正的 S2C 瓶颈是原版客户端对 `ClientboundCustomPayload` 的 32767 字节解码上限**（未知通道 discarded 解码；超过客户端断连）。客户端**已注册 codec 的已知通道**不受此限（Fabric API 上限 64MB——`fabric:recipe_sync` 即此类，JEI 配方大包单发因此安全）。

**路径 A（servux 主路径）**：plugin messaging + `PacketSplitter` 分片——S2C 分片常量 `MAX_TOTAL_PER_PACKET_S2C = 32_000` / `MAX_PAYLOAD_PER_PACKET_S2C = 31_995`（留余量给 VarInt 头，防御 32767）。**重组上限预检 = 128MB**（1.21.11 客户端 malilib `DEFAULT_MAX_RECEIVE_SIZE_S2C = 134217728`；`PacketSplitter.MAX_REASSEMBLY_SIZE_S2C` 同值，`send` 入口超限整帧拒发 + warn——我方增强，勿随模板回退；26.1 起客户端上限降为 16MB，26.x 线门禁随之收紧）。
**路径 B（大包直发）**：NMS `new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))` 直发——JEI 配方包（常超 1MiB）、syncmatica S2C（plugin messaging wire 对纯 Fabric 客户端不可达）已采用；servux 的**同通道 C2S 证明兜底**亦走此路径（Paper 声明簿记滞后时改走 NMS 直发；证明集合随 `PlayerQuitEvent` 清除；未发过 C2S 的玩家构造性不受兜底影响——见 [../AGENTS.md](../AGENTS.md) §1）。

**决策（as-built）**：servux 走 A + 兜底 B；jei / syncmatica 以 B 为主。两路径按通道选择、共存。

### 3.3 字节级可行性验证

`byte[]` ↔ `FriendlyByteBuf`（`Unpooled.wrappedBuffer` / `buf.array()`）；`CompoundTag` 读写（`writeNbt/readNbt`）；VarInt；通道命名（`namespace:key` 规则）——全部 NMS 直连。

### 3.4 Payload record 照抄

`ServuxXxxPacket.Payload` 去掉 `@Environment(EnvType.SERVER)` 后近乎原样可用——`CustomPacketPayload.Type`、`StreamCodec`、`FriendlyByteBuf` 全是 NMS。plugin messaging 收发 `byte[]` 时，Payload record 主要用于 NMS 直发与协议定义文档化。

### 3.5 数据采集迁移范式

**NMS 直连（绝大多数）**：`MinecraftServer` / `ServerLevel` / `ServerPlayer`、`getSeed()` / `recipeAccess()` / `getChunkSource().getLastSpawnState()`、`Recipe.CODEC.encodeStart(NbtOps.INSTANCE, ...)`、`BlockEntity.saveWithFullMetadata(registryAccess)` / `Entity.saveWithoutId(...)`、`ChunkAccess.getAllReferences()` / `StructureStart.createTag(ctx, pos)`——原版要 Mixin/AW 才能访问的，反而因 paperweight 完整映射而简化。

**反射（仅 4-5 点）**：`framework/reflect/Reflect`（线程安全缓存 + 防御式降级）——`ServerTickRateManager.remainingSprintTicks`（private）、`NaturalSpawner.MAGIC_NUMBER`（=289，可直接硬编码+注释标来源）、`TagValueInput/TagValueOutput` 内部字段（`NbtView` 重写绕开）。

**触发点迁移（Mixin → 事件/周期）**：

| 采集触发 | Fabric（Mixin） | Paper（事件/调度，as-built） |
|---|---|---|
| 天气变化 | `MixinServerLevel.advanceWeatherCycle` | tick 内周期读（本线：`getLevelData().getClearWeatherTime/getRainTime/getThunderTime/isRaining/isThundering`） |
| 出生点变化 | `MixinServerLevel.setRespawnData` | `updateSpawnFromServer`（Bukkit `World.getSpawnLocation`）周期同步 |
| 结构观察 | `MixinChunkLoadingManager.markChunkPendingToSend` | 周期扫描玩家 view distance 内区块（`update_interval` 默认 40t；结构引用是 LongSet，查找 O(1)） |
| TPS/MobCap | `tick` 内采集 | `BukkitScheduler` tick 任务 |
| 客户端就绪 | （原版无此问题） | `PlayerRegisterChannelEvent`（configuration phase 完成的可靠信号） |

### 3.6 NMS 实测坑清单（移植/维护必读）

> 实际编译验证踩过的坑（本线成文；26.1 线漂移清单见 26.1.2 线 architecture.md §3.6）；升级 MC 版本时务必重新核对。

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **ResourceLocation 改名 Identifier** | `net.minecraft.resources.ResourceLocation` 找不到 | 用 `net.minecraft.resources.Identifier`（1.21.5+ Mojang 重命名） |
| **ResourceKey.location() 改名** | `.location()` 不存在 | 用 `.identifier()` |
| **CompoundTag.getAllKeys()** | 找不到 | 用 `keySet()` |
| **player.serverLevel()** | 不存在 | 用 `(ServerLevel) player.level()` |
| **ServerLevel.getSharedSpawnPos()** | 不存在 | 用 Bukkit `world.getWorld().getSpawnLocation()` → BlockPos |
| **CommandSourceStack.hasPermission(int)** | 签名变成 (Permission,String) | op 等级改 `PlayerList.ops()` 反射或 Bukkit `isOp()`（Perms 用 isOp 二分） |
| **MinecraftServer.getProfiler()** | 不存在 | `IDataProvider.tick` **去掉 ProfilerFiller 形参** |
| **ServerLoadEvent.LoadType.STARTED** | 枚举值名漂移 | 去掉类型判断，处理所有 ServerLoadEvent |
| **CompoundTag 1.21.5+ 语义** | `getXxx` 返回 Optional | 用 `getBooleanOr/getStringOr/getIntOr/...` 或 `.orElse()`；`putXxx` 返回 void（非链式） |
| **FriendlyByteBuf.readNbt()** | 返回 `CompoundTag`（非 Optional） | 直接用；大包用 `readNbt(NbtAccounter.unlimitedHeap())` |
| **List.copyOf(enum[])** | 不接受数组 | 用 `Arrays.asList(values())` |
| **commons-lang3 Fraction** | 不保证暴露 | MathUtils 去掉 Fraction 重载 |
| **@NotNull/@Environment 注解** | Fabric 专有 | 全部删除 |
| **Messenger.MAX_MESSAGE_SIZE 不再是 32768** | 旧文档曾称 32KiB | 1.21.x 已上调（~1MiB）；真 S2C 瓶颈是客户端 32767（§3.2） |

---

## 4. 生命周期（Fabric Mixin → Bukkit 事件）

| Fabric | 触发方式 | Paper 等价（as-built） |
|---|---|---|
| `ModInitializer.onInitialize()` | JVM 加载 mod | `JavaPlugin.onEnable()` |
| `MixinMinecraftDedicatedServer.<init>@TAIL`（注册 provider） | Mixin | `onEnable` 直接注册 |
| `MixinMinecraftServer.runServer` → onServerStarting/Started | Mixin | `ServerLoadEvent`（STARTED 后捕获 RegistryAccess——**时机命门：早了拿空注册表**） |
| `MixinMinecraftServer.tickServer@RETURN` → tickProviders | Mixin | `runTaskTimer(0L, 1L)` 每 tick + 内部按 interval 分发 |
| `MixinMinecraftServer.reloadResources` Pre/Post | Mixin | `/servux reload` 命令（自管重读，幂等） |
| `MixinMinecraftServer.stopServer` Pre/Post | Mixin | `PluginDisableEvent` / `onDisable` |
| `MixinMain.main`（捕获 RegistryAccess） | Mixin | `ServerLoadEvent(STARTED)` 后 NMS `registryAccess()` 捕获 |
| `MixinPlayerManager.placeNewPlayer` / `remove` / `respawn` / `canPlayerLogin` | Mixin | `PlayerJoinEvent`（40t 延迟握手）/ `PlayerQuitEvent` / `PlayerRespawnEvent` / `AsyncPlayerPreLoginEvent` |
| `MixinServerLevel`（天气/出生点） | Mixin | 周期读（§3.5） |
| `MixinChunkLoadingManager.markChunkPendingToSend` | Mixin | 周期扫描（§3.5） |
| `MixinCommandManager.<init>` | Mixin | `plugin.yml` + `CommandExecutor`（carpet 系用 Paper Brigadier） |
| （1.20.2+ configuration phase 新增） | — | `PlayerRegisterChannelEvent`（握手可靠信号） |

---

## 5. Mixin→Paper 统一处置矩阵（唯一权威）

> Servux 共 **25 个 Mixin**（注册表计）+ **2 个 AccessWidener 字段**；Syncmatica 5 个服务端 Mixin；Carpet 上游 171 个（本线携带子集零 Mixin——patches 三件套纯子类，移植范围终局定案）。逐项全表见归档件 [archive/04-mixin-analysis.md](archive/04-mixin-analysis.md)（Servux）与 [archive/22-syncmatica-mixin-migration.md](archive/22-syncmatica-mixin-migration.md)（Syncmatica）；本节是**决策矩阵**。

### 5.1 四大类处置速查（Servux 26 Mixin + 2 AW）

| 分类 | 数量 | Paper 处置 |
|---|---|---|
| **A. 反射替代**（读私有字段） | ~4 + 2 AW | `framework/reflect/Reflect`（缓存 + 防御）；`MAGIC_NUMBER=289` 直接硬编码 |
| **B. 事件/调度替代**（采集触发/生命周期） | ~9 | Bukkit 事件 + `BukkitRunnable`（§4 映射表） |
| **C. 降级/省略**（改服务端行为） | ~11 | 多数省略；EasyPlace PacketEvents；镜像修复内联进粘贴代码 |
| **D. 调试** | 1 | 省略（`IS_RUNNING_IN_IDE`，生产无用） |

关键逐项（block 包：`MixinChestBlock`/`MixinRailBlocks`/`MixinStairBlock` 镜像修复只在粘贴时需要 → **内联进 `SchematicPlacingUtils`**，对应 `fix_chest_mirror`/`fix_rail_rotations`/`fix_stairs_mirror` settings；item 包：`MixinBlockItem_EasyPlace` 已实现（§5.2）；`MixinItemStack`+`MixinHopperBlockEntity` 潜影盒堆叠 **不可能实现**——反射改不了返回值、事件在 `maxStackSize=1` 前提下恒失败、组件污染序列化，已删全部代码且**不下发 `stackingShulkers` 元数据**；nbt 包：`IMixinNbtRead/WriteView` → `NbtView` 重写为直接 `Entity.saveWithoutId` / `BlockEntity.saveWithFullMetadata` 绕开；network 包：QueryNbt 权限覆盖 → Bukkit op 权限等价）。

### 5.2 功能级决策矩阵

| 功能 | 原实现 | 迁移决策 | 备注 |
|---|---|---|---|
| **HUD 元数据/出生点/天气** | MixinMinecraftServer/ServerLevel | **做**（事件 + API） | 协议核心 |
| **TPS logger** | IMixinServerTickManager | **做**（NMS + 反射） | 反射 remainingSprintTicks |
| **MobCap logger** | (AW MAGIC_NUMBER) | **做**（硬编码 289） | |
| **配方下发** | — | **做**（NMS Recipe.CODEC） | 大包走 NMS 直发 |
| **实体/方块实体 NBT 查询** | MixinServerPlayNetworkHandler_QueryNbt | **做**（NMS saveWith*） | 权限走 Bukkit |
| **结构边界框** | MixinChunkLoadingManager | **做**（周期扫描触发） | 工作量最大 |
| **Litematica 投影粘贴** | MixinChestBlock/Rail/Stairs（镜像） | **做**（投影照抄 + 镜像修复内联；粘贴 = 同步直放——本线无 task 化，26.1 线才有） | 见 [servux/schematic.md](servux/schematic.md) §6 |
| **S2C 投影文件投递** | — | **保留**（`/servux litematic transmit`；仅旧版 litematica 客户端可接收——修复版 ≥0.26.11 对 Transmit 帧双向静默丢弃；命令入口有目录包含性守卫） | |
| **潜影盒可堆叠** | MixinItemStack/Hopper | **不可能实现**（已删代码） | §5.1 |
| **Allay 收集修复** | MixinMob/ItemEntity/Allay | **省略** | 改行为，影响小 |
| **EasyPlace** | MixinBlockItem + NetworkHandler | **已实现**（PacketEvents） | 详 [operations.md](operations.md) §5 |
| **UpdateSuppression** | MixinLevel/LevelChunk/Block | **省略** | 改行为，无等价 |
| **调试 (IDE 模式)** | MixinSharedConstants | **省略** | 生产无用 |

### 5.3 Syncmatica 5 Mixin（全部干净替换，无降级）

`MixinMinecraftServer` / `MixinPlayerManager` / `MixinServerPlayNetworkHandler` / `MixinServerCommonNetworkHandler`（冗余不实现）/ `MixinCommandManager`——**无「改服务端行为」类降级**（对比 Servux）。逐项与命门详见 [syncmatica/architecture.md](syncmatica/architecture.md) §10。

---

## 6. Fabric ↔ Paper 逐域对照（每行可直接当迁移转换规则用；原 06-fabric-vs-paper.md 并入此节）

### 6.1 总览：两个框架的根本差异

| 维度 | Fabric + Servux | Paper 插件 |
|---|---|---|
| **运行模型** | Mod 与服务端**同进程同类加载器**，可直接 `import net.minecraft.*`、用 Mixin 改字节码 | 插件在**隔离类加载器**，NMS 经 paperweight userdev 访问，**无 Mixin** |
| **NMS 可达性** | Loom 提供官方 Mojang 映射 | paperweight userdev 同样提供（dev bundle Mojang 名；产物经 reobf，但反射字符串不受 reobf 转换、Paper 运行时亦 Mojang 名） |
| **改字节码** | Mixin（运行时织入）+ AccessWidener | **无**。只能反射读私有、事件替代钩子、PacketEvents 拦截包 |
| **网络** | `fabric-networking-api-v1`（原版 `CustomPacketPayload` 封装） | plugin messaging channel（映射原版 custom payload）+ NMS `ClientboundCustomPayloadPacket` |
| **生命周期** | Mixin 钩 NMS 生命周期点 | Bukkit 事件 + scheduler |
| **权限** | `fabric-permissions-api`（Lucko） | Bukkit `Permission` / LuckPerms / Vault |
| **构建** | `fabric-loom` | Gradle + `paperweight userdev` + `run-paper`（本线含 reobf） |

### 6.2 网络 / 权限 / 配置 / 命令 / NBT / 杂项

| Fabric | 作用 | Paper 等价 |
|---|---|---|
| `PayloadTypeRegistry.playC2S/playS2C().register(...)` | 注册 payload | `Messenger.registerIncoming/OutgoingPluginChannel` |
| `ServerPlayNetworking.send(player, payload)` | 发 S2C | `player.sendPluginMessage(...)` 或 NMS 直发（§3.2 路径 B） |
| `ServerPlayNetworking.canSend(player, type)` | 客户端是否支持 | `getListeningPluginChannels().contains` + 同通道 C2S 证明兜底 |
| `record Payload implements CustomPacketPayload` + `StreamCodec` | 协议帧 | **照抄**（NMS 直连）；或直接序列化到 `byte[]` |
| `FriendlyByteBuf` / `Unpooled` | 字节流 | NMS 同名；`byte[]`↔buf 用 `Unpooled.wrappedBuffer`/`buf.array()` |
| `Permissions.check(player, node, level)` | 权限 | `framework.permission.Perms.check(...)`（节点表见 [operations.md](operations.md) §2） |
| `FabricLoader.getConfigDir()` → `config/servux.json` | 配置 | `plugin.getDataFolder()` → `plugins/VeryMcProto/servux.json` |
| `MixinCommandManager.<init>` 注入 Brigadier | 命令 | `plugin.yml` + `CommandExecutor`（carpet 系 → Paper Brigadier） |
| `CompoundTag` / `NbtOps` / `NbtIo` | NBT | NMS 同名（保真度最高）；`NbtView` 重写绕开 Accessor |
| `LogManager.getLogger(MOD_ID)` | 日志 | `plugin.getLogger()`（JUL）；SLF4J 占位符用 `Log.java` shim |
| `SharedConstants.getCurrentVersion()` | 版本 | `Reference.MC_VERSION`（version.properties 注入） |

### 6.3 一页纸转换清单（移植时逐条核对）

```
入口/生命周期    onInitialize → onEnable + Bukkit 事件
provider 注册    MixinDedicatedServer → onEnable 直接 register
tick 调度        MixinMinecraftServer.tickServer → BukkitScheduler runTaskTimer
玩家进退服        MixinPlayerManager → PlayerJoinEvent/PlayerQuitEvent
客户端就绪        （原版无）→ PlayerRegisterChannelEvent
配置路径         config/servux.json → plugins/VeryMcProto/servux.json
权限             Permissions.check → framework.permission.Perms.check
命令             MixinCommandManager → plugin.yml + CommandExecutor（carpet 系 → Paper Brigadier）
网络收(C2S)      ServerPlayNetworking → Messenger.registerIncomingPluginChannel
网络发(S2C)      ServerPlayNetworking.send → sendPluginMessage 或 NMS ClientboundCustomPayloadPacket
字节流            FriendlyByteBuf → NMS FriendlyByteBuf（paperweight 直连）
分包             PacketSplitter → 照抄（S2C 常量 32000/31995，防客户端 32767）
NBT              CompoundTag/NbtIo → NMS 直连
Mixin 读私有     → 反射 / NMS 直接调用（多数已是公开方法）
Mixin 改行为     → 降级省略 / PacketEvents / 投影代码内联
构建             fabric-loom → paperweight userdev + run-paper（本线 reobfJar 产出运行时 jar）
```

---

## 7. 构建与工具链（1.21.11 as-built）

> 版本唯一来源 `gradle.properties`（`mcVersion` / `buildNumber`）；`api-version` = `'${mcVersion}'` 模板化展开。

```kotlin
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")   // 旧格式 R0.1-SNAPSHOT（26.1 起新格式 <mc>.build.<N>-stable）
    // 可选依赖（EasyPlace）：compileOnly packetevents-spigot 2.13.0
}

java { toolchain.languageVersion = JavaLanguageVersion.of(21) }
```

- **本线 reobf 保留**：`reobfJar` 产出 Spigot 运行时映射 jar（`-reobf.jar` 产物，标准 Paper 直接加载），Mojang 映射 jar 为中间产物。26.1 起 Mojang 移除服务端混淆、reobf 废除——升级时删除该装配。
- **反射用 Mojang 名**：reobf 不转换反射字符串，Paper 运行时即 Mojang 映射——天然正确。
- **PacketEvents 2.13.0**（EasyPlace 可选依赖，`compileOnly` + `softdepend`；类引用隔离在 `EasyPlaceBootstrap`）。
- runServer 运行目录按线隔离（`run-<mcVersion>/`——共享 `run/` 会让多线静默互踩世界与 packetevents 版本）。
- `processResources` expand 占位符须显式 `inputs.property(...)` 参与 up-to-date 跟踪（曾实证陈旧展开事故）；`build` 挂 `verifyVersionInjection` 终检。

---

## 8. 防御性编程要点（as-built）

- **所有装配 try-catch**：主类 onEnable/onDisable、LifecycleBridge 事件分发、tickProviders，单点异常不影响整体。
- **反射防御**：`Reflect.getOr(obj, field, default)` 失败返回默认值。
- **并发安全**：`PacketSplitter.READING_SESSIONS` 用 `ConcurrentHashMap` + `synchronized`；坏包立即丢弃 session。
- **大包保护**：`ProtocolChannel.send` 拒绝超 `Messenger.MAX_MESSAGE_SIZE` 的包；`PacketSplitter.send` 入口 128MB 重组上限预检（log-and-drop）。
- **投影体积一致性预检（安全修复）**：`readSubRegionsFromNBT` 每 region 校验声明体积 ≤ BlockStates 实际容量（floor 除法 + 回绕守卫），拒绝脱钩坏包——上游 `catch(OutOfMemoryError)` 是客户端本地文件语境死防御**勿搬运**（详见 [servux/schematic.md](servux/schematic.md)）。
- **S2C transmit 命令入口目录包含性守卫**（文件名来自 op 命令参数）；**C2S 文件接收链已整链移除**（客户端可控 `FileName` 路径穿越任意写删——上游安全公告同源，2026-10 安全修复，恢复走 git revert）。
- **客户端未装 mod**：`getListeningPluginChannels` 检测 + MAX_FAILURES 计数 → invalid，不刷屏。
- **配置原子写**：`JsonUtils` 用 `.tmp` + move。
- **RegistryAccess 时机**：必须 `ServerLoadEvent(STARTED)` 后捕获。

---

## 9. 验证策略与保真度目标

1. **单元级**：`PacketSplitter` / `FeatureSet` / `LitematicaBitArray` 等纯函数单测（`./gradlew test`）。
2. **协议级**：`runServer` + 真 Fabric 客户端按 [servux/testing.md](servux/testing.md) / [syncmatica/testing.md](syncmatica/testing.md) / [jei/protocol.md](jei/protocol.md) 流程验证。
3. **抓包对照**：比对 Fabric+原版服务端 mod 与 Paper+插件 的字节流。
4. **回归**：跨 MC 小版本验证 NMS 反射点（升级 SOP 见 [operations.md](operations.md) §6）。

| 层 | 保真度 | 说明 |
|---|---|---|
| 网络协议字节 | **100%** | 同一 `FriendlyByteBuf`/`CompoundTag` 线格式，字节级一致 |
| 通道/版本号 | **100%** | 通道名、协议版本号与原版逐字对齐（1.21.11 真值见 [servux/protocol.md](servux/protocol.md) §9） |
| 数据采集 | **≈95%** | 绝大多数 NMS 直连；个别反射字段版本敏感 |
| 服务端行为改造 | **部分降级** | EasyPlace 已实现；UpdateSuppression/Allay 省略；潜影盒堆叠不可能（§5） |

> **结论**：对"Fabric 客户端 + Paper 服务端"核心场景（HUD/结构/投影投递与粘贴/实体查询/投影共享/配方同步/Carpet 子集）达到与原版**功能等价**；仅少数"服务端行为增强"特性降级，均不影响协议主功能。
