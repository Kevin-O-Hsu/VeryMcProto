# loggers 迁移笔记

> 原版源码根: `I:/Programming/VeryMcProto/OriginImpl/servux-LTS-1.21.11/src/main/java/fi/dy/masa/servux/loggers`
> Paper 目标包根: `verymc.top.veryMcProto.loggers`
> 本模块职责: 为 HUD Provider 采集 **TPS / MobCap** 数据。它**不是网络层**，只产出 `CompoundTag`（NBT），由 HUD Provider（`HudData`）周期性 `getResult(server)` 取走并随 `servux:main` 通道下发。本模块本身不发包、不定义 Payload/CHANNEL/PROTOCOL_VERSION。

---

## 文件清单表

| 原版文件(相对 servux 根) | 行数 | 建议目标包 | NMS依赖 | Mixin/AW依赖 | 迁移方式 |
|---|---|---|---|---|---|
| `loggers/DataLogger.java` | 58 | `verymc.top.veryMcProto.loggers` | `StringRepresentable`(可去) | 无 | 适配(去NMS枚举接口) |
| `loggers/DataLoggerBase.java` | 20 | `verymc.top.veryMcProto.loggers` | `MinecraftServer` | 无 | 照抄(NMS名不变) |
| `loggers/DataLoggerMobCaps.java` | 81 | `verymc.top.veryMcProto.loggers` | `NaturalSpawner.SpawnState`/`MAGIC_NUMBER`/`MobCategory`/`NbtOps`/`Codec`/`ServerLevel` | AW(`NaturalSpawner.MAGIC_NUMBER`=accessible) | 反射(MAGIC_NUMBER) + 适配 |
| `loggers/DataLoggerTPS.java` | 56 | `verymc.top.veryMcProto.loggers` | `ServerTickRateManager`/`MinecraftServer.getAverageTickTimeNanos`/`NbtOps` | Mixin `IMixinServerTickManager`(remainingSprintTicks) | 反射(remainingSprintTicks) + 适配 |
| `loggers/DataLoggerType.java` | 46 | `verymc.top.veryMcProto.loggers` | 无(纯泛型工厂) | 无 | 照抄 |
| `loggers/data/MobCapData.java` | 334 | `verymc.top.veryMcProto.loggers.data` | `MobCategory`/`StringRepresentable`/`Codec`/`PrimitiveCodec`/`RecordCodecBuilder` | 无 | 照抄(去 StringRepresentable 接口可选) |
| `loggers/data/TPSData.java` | 18 | `verymc.top.veryMcProto.loggers.data` | `Codec`/`PrimitiveCodec`/`RecordCodecBuilder` | 无 | 照抄 |

**合计 7 文件，613 行。**

> 注：本模块**无 packet/Payload 类**（无 `toPacket`/`fromPacket`/`CHANNEL_ID`/`PROTOCOL_VERSION`）。数据以 NBT 形式交给 HUD Provider，字节布局见 HUD/network 模块笔记。

---

## 迁移方式分布

- **照抄**: `DataLoggerBase`、`DataLoggerType`、`MobCapData`、`TPSData`（4 个；纯 Java/NMS API，去 Fabric 相关注解即可）
- **适配**: `DataLogger`（去 `StringRepresentable` 枚举接口，改普通枚举或保留 NMS 枚举）、`DataLoggerMobCaps`、`DataLoggerTPS`（去 Mixin 接口强转，逻辑保留）（3 个）
- **反射点**: 2 处
  1. `DataLoggerTPS` → `ServerTickRateManager.remainingSprintTicks`（原版 `@Accessor`，Paper 反射 `long` 字段）
  2. `DataLoggerMobCaps` → `NaturalSpawner.MAGIC_NUMBER`（原版 AccessWidener accessible，常量值 **289**，Paper 反射或直接硬编码 `289`）
- **事件/降级**: 0（本模块无生命周期钩子、无服务端行为改造）

---

## 逐文件详解

### DataLogger.java (58 行)

- **职责**: 顶层枚举，登记所有数据采集器类型（TPS / MOB_CAPS），每个枚举常量绑定一个 `DataLoggerType` + `Codec`，并提供 `init()` 工厂入口。
- **关键字段**:
  - `public static final EnumCodec<DataLogger> CODEC = StringRepresentable.fromEnum(DataLogger::values)`（L16）—— NMS `StringRepresentable.EnumCodec`
  - `public static final ImmutableList<DataLogger> VALUES`（L17）
  - `private final String name`（L19）
  - `private final DataLoggerType<?> type`（L20）
  - `private final Codec<?> codec`（L21，Mojang DFU `Codec`）
- **关键方法**:
  - `public @Nullable DataLoggerBase<?> init()`（L36）→ `return this.type.init(this)` —— 创建采集器实例
  - `public Codec<?> codec()`（L41）
  - `public @NotNull String getSerializedName()`（L31，`StringRepresentable` 接口实现）→ 返回 `name`
  - `public static @Nullable DataLogger fromStringStatic(String name)`（L46）—— 大小写不敏感字符串→枚举
  - 枚举常量（L12-13）: `TPS("tps", DataLoggerType.TPS, DataLoggerTPS.CODEC)`、`MOB_CAPS("mob_caps", DataLoggerType.MOB_CAPS, DataLoggerMobCaps.CODEC)`
- **NMS依赖**: `net.minecraft.util.StringRepresentable`（提供 `EnumCodec` 与 `fromEnum`）
- **Mixin/Accessor依赖**: 无
- **迁移方式**: **适配**。Paper NMS 同样有 `net.minecraft.util.StringRepresentable`，可保留；但更简洁是去掉该接口，`CODEC` 改为自建 `Map<String,DataLogger>` 或直接用 `DataLogger.valueOf`。`getSerializedName` 若不参与 NBT 序列化（本模块序列化走各自 `CODEC`），可删。**核心数据 `name`/`type`/`codec` 三元组照抄**。
- **歧义/风险点**: `DataLogger.CODEC` 本模块内**未实际使用**（序列化走具体子类的 `CODEC`），保留仅供配置序列化；迁移时可评估是否需要。

### DataLoggerBase.java (20 行)

- **职责**: 所有采集器的抽象基类，持有 `DataLogger type`，定义抽象 `getResult(MinecraftServer)` 由子类实现数据采集。
- **关键字段**:
  - `private final DataLogger type`（L7）
- **关键方法**:
  - `public DataLoggerBase(DataLogger type)`（L9）—— 构造
  - `public DataLogger getType()`（L14）
  - `public abstract T getResult(MinecraftServer server)`（L19）—— **唯一抽象方法**，子类采集入口
- **NMS依赖**: `net.minecraft.server.MinecraftServer`（参数类型；Paper NMS 同名，Bukkit 侧即 `Bukkit.getServer()` 的 NMS 句柄）
- **Mixin/Accessor依赖**: 无
- **迁移方式**: **照抄**。Paper paperweight userdev 下 `MinecraftServer` 即 `net.minecraft.server.MinecraftServer`，签名不变。泛型 `<T extends ...>` 由子类具化为 `CompoundTag`。
- **歧义/风险点**: `getResult` 在 Bukkit 调用侧需要拿 NMS `MinecraftServer` 句柄：`((CraftServer)Bukkit.getServer()).getServer()`，这是采集器与 Bukkit 之间的桥接点（属调用方 / Provider 职责，不在本模块内）。

### DataLoggerMobCaps.java (81 行)

- **职责**: 遍历服务器**所有维度**，对每个维度从 `NaturalSpawner.SpawnState` 取出各 `MobCategory` 的当前实体数与可生成 chunk 数，按 `MAGIC_NUMBER(=289)` 公式算出 mob cap，组装成 `MobCapData`，DFU 编码为 `CompoundTag`。维度 key 为 `world.dimension().identifier().toString()`（如 `minecraft:overworld`）。
- **关键字段**:
  - `public static final Codec<CompoundTag> CODEC = CompoundTag.CODEC`（L17）
- **关键方法**:
  - `public CompoundTag getResult(MinecraftServer server)`（L24-80）—— 核心采集逻辑：
    1. `server.getAllLevels()` 遍历每个 `ServerLevel world`（L29）
    2. `dimKey = world.dimension().identifier().toString()`（L31）—— 维度 ResourceLocation 字符串
    3. `NaturalSpawner.SpawnState info = world.getChunkSource().getLastSpawnState()`（L34）—— **NMS 命门**：取上次自然生成的快照
    4. `spawnableChunks = info.getSpawnableChunkCount()`（L41）
    5. `divisor = NaturalSpawner.MAGIC_NUMBER`（L42）—— **AW 字段，值=289**
    6. `worldTime = world.getGameTime()`（L43）
    7. 若 `spawnableChunks <= 0` 则 `continue`（未加载）（L45-49）
    8. 遍历 `info.getMobCategoryCounts().object2IntEntrySet()`（L51）—— `Object2IntMap<MobCategory>`
       - `vanillaCap = entry.getKey().getMaxInstancesPerChunk()`（L55）
       - `current = entry.getIntValue()`（L56）
       - `capacity = MathUtils.clamp(vanillaCap * (spawnableChunks / divisor), 0, vanillaCap)`（L57）—— **整除**: 注意 `spawnableChunks / divisor` 是 int 除法
       - `data[category.ordinal()].setCurrentAndCap(current, capacity)`（L59）
       - 内层把每个 category 写入 `mobCapData.setCurrentAndCapValues(...)`（L61-65）
    9. DFU 编码: `MobCapData.CODEC.encodeStart(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), mobCapData).getPartialOrThrow()`（L70）→ `nbtEntry`
    10. `nbtEntry.putLong("WorldTick", worldTime)`（L71）—— **1.21.11 `putLong` 返回 void，非链式**
    11. `nbt.put(dimKey, nbtEntry)`（L72）
  - 构造 `public DataLoggerMobCaps(DataLogger type)`（L19）
- **NMS依赖**:
  - `net.minecraft.world.level.NaturalSpawner`（静态字段 `MAGIC_NUMBER`，类型 int=289）
  - `net.minecraft.world.level.NaturalSpawner.SpawnState`（方法 `getSpawnableChunkCount()`、`getMobCategoryCounts()`）
  - `net.minecraft.server.level.ServerLevel`（方法 `dimension()`、`getChunkSource()`、`getGameTime()`、`registryAccess()`）
  - `net.minecraft.world.entity.MobCategory`（方法 `getMaxInstancesPerChunk()`）
  - `net.minecraft.server.MinecraftServer`（方法 `getAllLevels()`）
  - `net.minecraft.nbt.CompoundTag` / `net.minecraft.nbt.NbtOps`
  - `com.mojang.serialization.Codec` + `world.registryAccess().createSerializationContext(NbtOps.INSTANCE)`（**1.21.11 DFU API**：`createSerializationContext` 取代旧 `DatapackRegistry`/`ops`）
  - `it.unimi.dsi.fastutil.objects.Object2IntMap`（fastutil，Paper runtime 捆绑可用）
- **Mixin/Accessor依赖**:
  - **AccessWidener**: `NaturalSpawner.MAGIC_NUMBER` 原版为 `private static final int MAGIC_NUMBER = (int) Math.pow(17.0, 2.0);`（=289），Servux 用 AW 设为 accessible。
- **迁移方式**: **适配 + 反射**。
  - **`MAGIC_NUMBER`**: Paper 无 AW。两个选项:
    1. **硬编码 `289`**（最务实，常量是确定值 `(int)Math.pow(17,2)`，1.21.11 不变）—— 推荐注释标明来源 `NaturalSpawner.MAGIC_NUMBER`。
    2. 反射读 `NaturalSpawner.class.getDeclaredField("MAGIC_NUMBER")`（`setAccessible(true)`，取 `getInt(null)`）。reobf 不转反射字符串、Paper runtime=Mojang 映射，字段名 `MAGIC_NUMBER` 直接可用。
  - 其余 NMS API 在 paperweight userdev 下均可直接引用，逻辑**照抄**。
  - `MathUtils.clamp(int,...)` 见 util 模块（本模块笔记附在下方）。
- **歧义/风险点**:
  - **公式整除陷阱**: `spawnableChunks / divisor` 是 int 整除；`spawnableChunks < 289` 时结果为 0 → capacity=0。这是原版行为，**照抄不要改成浮点**。
  - **`getLastSpawnState()` 返回可能为 null**（世界刚加载未跑过自然生成）—— 代码已 `if (info != null)`（L36）。
  - **`object2IntEntrySet()` 顺序**：内层循环（L61）对**每个** entry 都重写全部 8 个 category 的值，属冗余但无害；保持照抄以免行为偏移。
  - **`getPartialOrThrow`** vs `getOrThrow`：用 `Partial` 允许部分失败仍返回（数据不全时不抛），是刻意选择，保留。
  - 维度 key 用 `identifier().toString()`：1.21.11 Paper NMS `ResourceLocation` 同构，照抄。

### DataLoggerTPS.java (56 行)

- **职责**: 计算 TPS / MSPT / sprintTicks / frozen / sprinting / stepping，组装 `TPSData` record，DFU 编码为 `CompoundTag`。
- **关键字段**:
  - `public static final Codec<CompoundTag> CODEC = CompoundTag.CODEC`（L15）
- **关键方法**:
  - `public CompoundTag getResult(MinecraftServer server)`（L23-33）:
    - `TPSData.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), this.build(server)).getOrThrow()`（L27）
    - 失败 `catch (Exception e) { return new CompoundTag(); }`（L29-32）—— 返回空 NBT 兜底
  - `private TPSData build(MinecraftServer server)`（L35-55）—— **采集核心**:
    - `ServerTickRateManager tickManager = server.tickRateManager()`（L37）
    - `boolean frozen = tickManager.isFrozen()`（L38）
    - `boolean sprinting = tickManager.isSprinting()`（L39）
    - `final double mspt = (double) server.getAverageTickTimeNanos() / TimeUnit.MILLISECONDS.toNanos(1L)`（L40）—— **NMS 命门**：纳秒→毫秒
    - `double tps = 1000.0D / Math.max(sprinting ? 0.0 : tickManager.millisecondsPerTick(), mspt)`（L41）—— **TPS 公式**；sprinting 时除数取 0 → 走 mspt 分母
    - `if (frozen) tps = 0.0d;`（L43-46）
    - 返回 `new TPSData(mspt, tps, ((IMixinServerTickManager) tickManager).servux_getStringTicks(), frozen, sprinting, tickManager.isSteppingForward())`（L48-54）
  - 构造 `public DataLoggerTPS(DataLogger type)`（L17）
- **NMS依赖**:
  - `net.minecraft.server.ServerTickRateManager`（方法 `isFrozen()`/`isSprinting()`/`millisecondsPerTick()`/`isSteppingForward()`；私有字段 `remainingSprintTicks`）
  - `net.minecraft.server.MinecraftServer`（方法 `tickRateManager()`、`getAverageTickTimeNanos()`、`registryAccess()`）
  - `net.minecraft.nbt.CompoundTag` / `net.minecraft.nbt.NbtOps`
  - `com.mojang.serialization.Codec`
  - `java.util.concurrent.TimeUnit`（JDK）
- **Mixin/Accessor依赖**:
  - **Mixin `IMixinServerTickManager`**（`mixin/server/IMixinServerTickManager.java`）: `@Accessor("remainingSprintTicks") long servux_getStringTicks()` —— 读取 `ServerTickRateManager.remainingSprintTicks`（`long` 字段，记录剩余 sprint tick 数）。
- **迁移方式**: **适配 + 反射**。
  - **`remainingSprintTicks`**: Paper 无 Mixin。用反射:
    ```java
    Field f = ServerTickRateManager.class.getDeclaredField("remainingSprintTicks");
    f.setAccessible(true);
    long sprintTicks = (long) f.get(tickManager);
    ```
    反射字符串 `remainingSprintTicks` 在 reobf 后保持 Mojang 名、Paper runtime 即 Mojang 映射，可直接命中。**字段类型 `long`，1.21.11 不变**。
  - `server.getAverageTickTimeNanos()`、`tickRateManager()`、`millisecondsPerTick()`、`isFrozen/isSprinting/isSteppingForward()` 均为公开 API，paperweight userdev 直接引用，**照抄**。
  - 删除 `implements` 强转 `((IMixinServerTickManager) tickManager)`，改为反射调用。
  - DFU 编码 `createSerializationContext(NbtOps.INSTANCE)` 照抄。
- **歧义/风险点**:
  - **`servux_getStringTicks` 命名误导**: 方法名含 "String" 但返回 `long`（`remainingSprintTicks` 是 long）。迁移时直接命名为 `getRemainingSprintTicks()`，语义清晰。
  - **TPS 公式分母**: `Math.max(millisPerTick, mspt)` —— 当服务器卡顿时 mspt 大 → tps 低；sprinting 时强制 `0.0` 分母 → `Math.max(0, mspt)=mspt`，行为正常。**务必照抄，勿自作主张改公式**。
  - **`TimeUnit.MILLISECONDS.toNanos(1L)`** = 1000000L，可保留或硬编码，但保留可读性更好。
  - **frozen 时 tps=0** 优先于 sprinting 计算，注意顺序（L41 先算 sprinting 分母，L43 再覆写 0）。

### DataLoggerType.java (46 行)

- **职责**: 泛型工厂容器。每个 `DataLoggerType<T>` 持有一个 `DataLoggerFactory<T>`（函数式接口）与对应 `DataLogger` 枚举，`init(fmt)` 创建具体采集器实例。
- **关键字段**:
  - `public static final DataLoggerType<DataLoggerTPS> TPS`（L7）
  - `public static final DataLoggerType<DataLoggerMobCaps> MOB_CAPS`（L8）
  - `private final DataLoggerFactory<? extends T> factory`（L10）
  - `private final DataLogger type`（L11）
- **关键方法**:
  - `private static <T extends DataLoggerBase<?>> DataLoggerType<T> create(DataLoggerFactory<? extends T> factory, DataLogger type)`（L13）—— 内部工厂
  - `public @Nullable T init(DataLogger fmt)`（L24）→ `this.factory.create(fmt)`
  - `public DataLogger getType()`（L30）
  - 嵌套 `@FunctionalInterface interface DataLoggerFactory<T extends DataLoggerBase<?>> { T create(DataLogger type); }`（L35-39）
  - static 块（L41-45）: `TPS = create(DataLoggerTPS::new, DataLogger.TPS)`、`MOB_CAPS = create(DataLoggerMobCaps::new, DataLogger.MOB_CAPS)`
- **NMS依赖**: 无（纯 JDK 泛型 + 函数式接口）
- **Mixin/Accessor依赖**: 无
- **迁移方式**: **照抄**。无任何 Fabric/NMS 特性，整文件照搬。
- **歧义/风险点**: 静态块依赖 `DataLogger` 枚举存在；如 `DataLogger` 迁移时改结构需同步更新。

### MobCapData.java (334 行，data 子包)

- **职责**: MobCap 数据结构 + DFU Codec。含 `Cap`（current/cap 二元组）、`EntityCategory`（8 种 MobCategory 映射枚举）、staging 暂存机制（收集跨 tick 的数据，60 tick 窗口内全部收齐才算有效）。
- **关键字段**:
  - `protected static final int CAP_COUNT = EntityCategory.values().length`（L17）—— =8
  - `public static final Codec<MobCapData> CODEC`（L19-24）—— RecordCodecBuilder，字段 `cap_count`(int)、`cap_data`(list of Cap)
  - `protected final Cap[] data`（L26）—— 已确认数据
  - `protected final Cap[] stagingData`（L27）—— 暂存数据
  - `protected final boolean[] dataValid`（L28）
  - `protected final long[] worldTicks`（L29）
  - `protected boolean hasValidData`（L30）
  - `protected long completionWorldTick`（L31）
  - 内部类 `Cap`: `protected int current`、`protected int cap`、`public static Codec<Cap> CODEC`（字段 `current`/`cap`）
  - 枚举 `EntityCategory`: 8 个常量 MONSTER/CREATURE/AMBIENT/AXOLOTLS/UNDERGROUND_WATER_CREATURE/WATER_CREATURE/WATER_AMBIENT/MISC，各绑定一个 `MobCategory`，`public static final EnumCodec<EntityCategory> CODEC`、`ImmutableList<EntityCategory> VALUES`
- **关键方法**:
  - `public MobCapData()`（L33）—— 初始化 staging 数组
  - `private MobCapData(int capCount, List<Cap> capData)`（L41）—— DFU 反序列化构造
  - `public static Cap[] createCapArray()`（L54）
  - `public void clear()`（L95）
  - `public Cap getCap(EntityCategory type)`（L102）
  - `public void setCurrentAndCapValues(EntityCategory type, int currentValue, int capValue, long worldTick)`（L107）—— **staging 写入入口**，触发 `checkStagingComplete`
  - `protected void clearStaging()`（L118）
  - `protected void checkStagingComplete(long worldTick)`（L129）—— **60-tick 窗口校验**: `max - min <= 60` 才把 staging 提交到 `data`
  - `public static long getMinValue(long[] arr)`（L161）/ `getMaxValue(long[] arr)`（L185）
  - `Cap.setCurrentAndCap(int,int)`（L250）/ `Cap.setFrom(Cap)`（L256）
  - `EntityCategory.fromVanillaCategory(MobCategory type)`（L302）—— switch 映射
  - `EntityCategory.fromVanillaCategoryName(String name)`（L318）
- **NMS依赖**:
  - `net.minecraft.world.entity.MobCategory`（枚举，8 个常量）
  - `net.minecraft.util.StringRepresentable`（`EntityCategory implements StringRepresentable`，`EnumCodec`、`getSerializedName`）
  - `com.mojang.serialization.Codec`、`com.mojang.serialization.codecs.PrimitiveCodec`、`com.mojang.serialization.codecs.RecordCodecBuilder`
  - `com.google.common.collect.ImmutableList`
- **Mixin/Accessor依赖**: 无
- **迁移方式**: **照抄**（可选去 `StringRepresentable` 接口）。
  - `MobCategory`、`StringRepresentable`、DFU Codec 在 Paper NMS 全可用，整文件可近乎原样搬。
  - 若想解耦 NMS，`EntityCategory` 可改为普通 enum + 自建 name map，但 `MobCategory` 映射仍需保留（`fromVanillaCategory` 输入是 NMS `MobCategory`）。**建议保留 `StringRepresentable` 以最小化改动**。
- **歧义/风险点**:
  - **staging 机制在本采集路径是否真用上**：`DataLoggerMobCaps.getResult` 每次新建 `MobCapData()` 并即时填充后立即 DFU 编码，`checkStagingComplete` 的 60-tick 窗口逻辑在服务端单次采集时**几乎不会触发提交**（除非 8 类全填满）。这是设计冗余（类也用于客户端侧聚合），服务端照抄无害。
  - `EntityCategory` 枚举顺序必须与 `MobCategory` 一一对应（`ordinal()` 索引 `Cap[]`），**勿改顺序**。
  - `private MobCapData(int, List<Cap>)` 的 `createCapArrayFromList` 与后续 `for` 循环重复填充 `data[i]`（L43 与 L48-51），属冗余，照抄即可。

### TPSData.java (18 行，data 子包)

- **职责**: TPS 数据 record + DFU Codec。纯数据载体，6 个字段。
- **关键字段（record components）**:
  - `double mspt`（fieldOf `"mspt"`）
  - `double tps`（fieldOf `"tps"`）
  - `long sprintTicks`（fieldOf `"sprintTicks"`）
  - `boolean frozen`（fieldOf `"frozen"`）
  - `boolean sprinting`（fieldOf `"sprinting"`）
  - `boolean stepping`（fieldOf `"stepping"`）
  - `public static Codec<TPSData> CODEC`（L9-17）—— RecordCodecBuilder
- **关键方法**: record 自动生成的 accessor（`mspt()`/`tps()`/`sprintTicks()`/`frozen()`/`sprinting()`/`stepping()`）
- **NMS依赖**: `com.mojang.serialization.Codec`、`PrimitiveCodec`、`RecordCodecBuilder`（DFU，Paper runtime 捆绑）
- **Mixin/Accessor依赖**: 无
- **迁移方式**: **照抄**。Java record + DFU Codec，无任何 Fabric 特性，原样搬移。
- **歧义/风险点**: 字段名 `"sprintTicks"` 是 NBT key（客户端 MiniHUD 据此解析），**勿改名**；其值来源是 `remainingSprintTicks`（见 DataLoggerTPS 反射点）。

---

## 附: MathUtils.clamp 依赖（util 模块，本模块调用）

`DataLoggerMobCaps` L57 调用 `MathUtils.clamp(int value, int min, int max)`（`util/MathUtils.java` L82-92），实现即标准 clamp（`<min→min`，否则 `Math.min(value,max)`）。迁移时该工具方法**照抄**到 `verymc.top.veryMcProto.util.MathUtils`（util 模块统一处理）。也可直接换 `Math.min(Math.max(v,min),max)`，但建议保留工具类以一致。

---

## 关键反射点速查表（Paper 实现）

| 反射目标 | 类 | 字段/方法 | 类型 | 原版机制 | Paper 处置 | 字面值 |
|---|---|---|---|---|---|---|
| remainingSprintTicks | `net.minecraft.server.ServerTickRateManager` | 字段 `remainingSprintTicks` | `long` | Mixin `@Accessor` (`IMixinServerTickManager.servux_getStringTicks`) | 反射 `getDeclaredField("remainingSprintTicks")` + `getLong` | — |
| MAGIC_NUMBER | `net.minecraft.world.level.NaturalSpawner` | 静态字段 `MAGIC_NUMBER` | `int` | AccessWidener (accessible) | 反射 `getField("MAGIC_NUMBER").getInt(null)` **或硬编码 `289`** | **289** |

> 反射字符串在 reobf 后不转换，Paper 运行时即 Mojang 映射，上述字段名直接命中。MAGIC_NUMBER 若选硬编码务必加注释注明来源与 `(int)Math.pow(17,2)`，方便版本升级时核对。

---

## 模块级调用链（Paper 迁移后）

```
HUD Provider 周期任务 (BukkitScheduler / tick)
  └─ DataLogger.TPS.init() / DataLogger.MOB_CAPS.init()   // 实例化采集器
       └─ dataLogger.getResult(MinecraftServer)           // ((CraftServer)Bukkit.getServer()).getServer()
            ├─ DataLoggerTPS.build(): tickRateManager() + getAverageTickTimeNanos() + 反射 remainingSprintTicks
            │     └─ TPSData.CODEC.encodeStart(NbtOps) → CompoundTag
            └─ DataLoggerMobCaps.getResult(): 遍历 getAllLevels() + getLastSpawnState() + MAGIC_NUMBER(289/反射)
                  └─ MobCapData.CODEC.encodeStart(NbtOps) → CompoundTag (维度→entry)
  └─ CompoundTag 交给 HUD Provider → 拼进 servux:main 的 HUD metadata/periodic 包
```

**注意**: 本模块的 `getResult` 需要 NMS `MinecraftServer` 句柄，调用方（HUD Provider）负责 `((CraftServer)Bukkit.getServer()).getServer()` 桥接。周期发送机制（订阅玩家、间隔 tick）**不在本模块**，属 HUD Provider / DataProviderManager 职责（见 dataproviders 模块笔记）。
