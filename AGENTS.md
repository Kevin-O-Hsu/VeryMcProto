# AGENTS.md — VeryMcProto · Fabric 协议 Mod → Paper 插件移植

> 面向 AI 编码助手与人类维护者的**仓库权威说明**。CLAUDE.md 已收敛为指向本文件的薄指针，勿在彼处另写内容。

## 给 AI 助手的工作约定（动代码前先读）

- **开发前必查上游版本**：每次开始开发前，先请求 Mojang 官方版本清单
  `https://launchermeta.mojang.com/mc/game/version_manifest_v2.json`，
  读 `latest.release` / `latest.snapshot` 确认最新版本，再对齐动作：
  - 上游最新 release == 本开发线 `gradle.properties` 的 `mcVersion` → 正常开发；
  - 上游已越过本线版本 → 该版本属旧线：当前版本的活走 `ver/<X>-dev`，同时提醒用户启动 `dev` 的升级适配（见分支模型「生命周期」）。
  （Paper 侧版本可用 https://api.papermc.io/v2/projects/paper 交叉核对。）
- **分支**：当前 MC 版本的开发一律在 `dev` 分支提交，完成后合入 `main`（= 最新 MC 稳定发布线）。旧 MC 版本冻结为 `ver/<X>` + `ver/<X>-dev` 维护对：修 Bug 在 `ver/<X>-dev`，验证后合入 `ver/<X>`。详见下文「分支模型与版本系统」。
- **版本**：插件版本 = `<mcVersion>-b<buildNumber>`（当前 `26.1.2-b1`——26.1 线的精确上游补丁号，26.1 客户端 MOD_STRING 硬门禁要求）。**唯一来源是 `gradle.properties`**——发版只需在 dev 上 `buildNumber` +1，**任何源码、plugin.yml、文档中都不得手写版本号**（注入链路见下文）。
- **语言与风格**：注释、日志、文档用中文；与现有代码一致（中文 javadoc、常量类 + 源码实证注释）。
- **协议字段语义**改动前必须对照 `OriginImpl/` 下的客户端源码（litematica / malilib / syncmatica 是协议接收端），**不要凭服务端代码猜客户端行为**。26.1 起客户端还带协议版本 + MOD_STRING 前缀**硬门禁**（不匹配即整通道静默退网），协议常量必须与 `OriginImpl/*-LTS-26.1` 逐字对齐。
- **禁止引入 Mixin / AccessWidener / 服务端 patch 依赖**——Paper 无 Mixin 运行时，替代方案见核心约束 §2。
- **可选依赖**（PacketEvents 等 `compileOnly`）的类引用必须隔离到独立引导类 + 反射加载 + `catch(Throwable)`（见核心约束 §6 教训 5）。
- **构建**：`./gradlew build`（Mojang 映射 jar，26.1 起**无 reobf**）· `./gradlew test`（纯函数单测）· `./gradlew runServer`（本地测试服）。**JDK 25 工具链自动解析**：`settings.gradle.kts` 的 foojay-resolver-convention 插件（探测不到时自动下载）+ 本机 `~/.gradle/gradle.properties` 的 `org.gradle.java.installations.paths` 指向 F:\jdk——IDE / 无 JAVA_HOME 场景直接可用。**配置缓存已开启**：task 配置 lambda 内不得捕获脚本顶层 `val`，用 task 自身的 `providers` 取值。

## 项目简介

**VeryMcProto** 把 **Fabric 端独特的 Mod Protocol（协议 Mod）** 以纯 Paper 插件形式重新实现。所有实现基于 **Minecraft 26.1 线（26.1.2）**，运行在标准 **Paper 26.1.2** 服务端，不依赖任何服务端 patch / Mixin / 私有 fork。

每个被移植的 Mod 独占一个目录单元；原版 Fabric 实现统一存放在 `OriginImpl/` 下用于逐行对照（本地参考，已 gitignore，不入库；**1.21.11 与 26.1 双版本并存**，masa 系全部为 sakura-ryoko 维护的 `LTS/<版本>` 分支，JEI 为 Mrbysco 真上游）。**三个移植目标在 26.1 线全部实现并通过服务端实机验证**：

- **Servux**（`mod/servux/`，对照 `OriginImpl/servux-LTS-26.1/`）—— masa 开发的服务端协议 Mod，为 masa 的客户端 Mod（**MiniHUD / Litematica / Tweakeroo**）提供**服务端→客户端的数据投递与协议**，通过自定义网络通道（`servux:*`）下发：世界元数据、出生点、天气、TPS/MobCap、结构边界框、Litematica 投影投递/粘贴、实体与方块实体 NBT 查询、EasyPlace 服务端放置协议等。5 通道 + schematic（粘贴/投递）+ EasyPlace + **task 组 Fill/Delete**（`scheduler/` 三类：受理→分 tick 执行→InfoHud 状态同步，26.1 wire：协议版本 3/2/2/3/2、DataTag 载体、UNREGISTER_REPLY；type 15 客户端 TODO 故不发送、type 17 上游同源忽略）。
- **JEI Recipe Bridge**（`mod/jeirecipebridge/`，对照 `OriginImpl/JEIRecipeBridge-26.1/`）—— 玩家进服时把服务端配方表同步给 JEI 客户端，按 client brand 走 `fabric:recipe_sync` / `neoforge:recipe_content` 两条原版 custom payload 通道（NMS `ClientboundCustomPayloadPacket` 直发，绕过 plugin messaging size 上限）。纯 S2C / 一次性 / 仅 1 个 `enabled` 配置项。26.1 wire 零变化。
- **Syncmatica**（`mod/syncmatica/`，对照 `OriginImpl/syncmatica-LTS-26.1/`）—— **投影共享**协议 Mod：服务端作中央仓库存储 `.litematic`，多玩家上传/下载/协同修改放置位置。单物理通道 `syncmatica:main` + 18 逻辑 PacketType + Exchange 会话层（请求-应答状态机）+ 文件存储 + JSON 持久化 + 配额/调试服务。与 Servux（单向广播）根本不同——**双向、有状态、多玩家共享**。26.1 wire 零变化。

> **本项目的本质是"协议层移植"**：客户端仍是 masa / syncmatica 的 Fabric Mod；我们要在 Paper 服务端复刻它们期待的**网络协议 + 数据采集**，使"Fabric 客户端 + Paper 服务端"的组合能像"Fabric 客户端 + 原版服务端 Mod"一样工作。

> 📚 **所有技术文档**都在 [`docs/`](docs/) 下，按阅读顺序编号，互相索引。**强烈建议先读 [`docs/00-INDEX.md`](docs/00-INDEX.md)** 获取文档地图与推荐阅读路线。

---

## 分支模型与版本系统

### 分支模型（最新版本开发 vs 旧版本维护）

| 分支 | 职责 |
|---|---|
| `dev` | **最新 MC 版本的日常开发线**——功能与修 Bug 都提交在这里 |
| `main` | **最新 MC 版本的稳定发布线**（只接受来自 `dev` 的合并，不直接提交） |
| `ver/<X>-dev`（如 `ver/1.21.11-dev`） | **旧 MC 版本 X 的维护开发线**——版本内修 Bug 在这里提交 |
| `ver/<X>` | **旧 MC 版本 X 的稳定发布线**（只接受来自 `ver/<X>-dev` 的合并） |

**生命周期**（例：main 处于 MC 26.2，上游出现 26.3）：

1. **冻结**：从 `main`（= 26.2 最后一个发布，干净冻结点）切出 `ver/26.2` + `ver/26.2-dev` 一对分支。不要从 `dev` 切——dev 即将携带 26.3 的改动。
2. **跟进上游**：回到 `dev`，改 `gradle.properties` 的 `mcVersion=26.3` + dev bundle，适配 NMS 漂移；此后 `dev → main` 承载 26.3 的全部开发。
3. **旧版本维护**：26.2 的 Bug 修在 `ver/26.2-dev`，验证后合入 `ver/26.2`，在该分支 `buildNumber` +1 出包（26.2 线的构建号独立递增）。
4. **修复回流**：旧版本修的 Bug 若新版本同样存在，cherry-pick / 移植回 `dev`（NMS 漂移大则手工移植）。

**当前版本**（尚未冻结）的 Bug 直接走 `dev → main`，不为它开 `ver/*` 分支——避免同一件事存在多个改动入口。`ver/*` 对只在上游出现新版本的那一刻创建。

**当前状态**（2026-09，以上游版本清单为准）：
- 上游最新 release **26.2**，snapshot **26.3-pre-2**（26.3 将近）。
- **`dev → main` 承载 26.1 线（26.1.2）**：26.1 全量迁移已完成（构建面 Java 25 / 新 dev-bundle 格式 / reobf 废除、协议面 DataTag 载体 + 版本常量 + 硬门禁对齐、NMS 漂移 39 处修复），`./gradlew build` 23/23 单测全绿，Paper 26.1.2 实机起服验证通过。
- **1.21.11 是旧版本**：`ver/1.21.11` + `ver/1.21.11-dev` 维护对已从 `main`（tag `v1.21.11-b1`）冻结切出。
- 适配 26.2 属后续工作（冻结 ver/26.1.2 对 → dev 升 mcVersion + bundle → NMS/协议漂移核对，见「升级 Minecraft 版本」）。

**发版**（版本内更新）：所在开发线（`dev` 或 `ver/<X>-dev`）`buildNumber` +1 并提交 → 合入对应发布线（`main` 或 `ver/<X>`）→ `./gradlew build` → tag `v<版本>`（如 `v1.21.11-b1`）。

### 版本系统（单一来源注入链路）

版本格式 **`<MC版本>-b<构建号>`**（如 `1.21.11-b1`）；MC 未来改日期式命名（如 `26.1`）时自动成为 `26.1-b1`，无需改格式。

```
gradle.properties（mcVersion=26.1.2 · buildNumber=1）          ← 唯一改动点
   │  build.gradle.kts: version = "$mcVersion-b$buildNumber"
   ▼
├─ plugin.yml（version: '${version}' 展开）                      → /version、Paper 插件列表
├─ jar 文件名 VeryMcProto-26.1.2-b1.jar（26.1 起无 -reobf 产物）
└─ version.properties（mcVersion/version 双键，processResources 展开）
       │  Reference 类加载时 Properties.load 读回
       ▼
   Reference.MC_VERSION / Reference.PLUGIN_VERSION（框架级常量）
       ├─ ServuxReference.MOD_STRING   = "servux-fabric-26.1.2-b1"（MOD_TYPE 伪装 fabric：
       │    26.1 客户端 startsWith("servux-fabric-<精确上游id>") 硬门禁，"paper" 会被四通道拒绝）
       ├─ SyncmaticaReference.MOD_VERSION = "26.1.2-b1"（-b 后缀永不命中
       │    FeatureSet.fromVersionString 的 ^\d+(\.\d+){2,4}$ 正则 → 恒触发 FEATURE 交换）
       └─ JeiRecipeBridgeModule.getModString() = "jei-recipe-bridge-paper-26.1.2-b1"
```

要点：
- `Reference.loadVersionProperty` 资源缺失时回退 `dev-unknown`（开发环境不崩），生产 jar 恒有值。
- 版本号出现在任何别的位置都是 bug——grep `26\.1\.2` 应只命中 `gradle.properties`、`version.properties` 模板（`${...}` 占位）与文档示例。
- 历史 tag `v1.0.0`（旧命名体系）保留作历史记录；新 tag 一律 `v<mcVersion>-b<buildNumber>`。

---

## 技术栈与构建

| 项 | 说明 |
|---|---|
| **目标平台** | Paper **26.1.2**（`api-version: 1.21`——旧值前向兼容，26.1 实测接受），Java **25** |
| **构建** | Gradle 9.7.1（Kotlin DSL） + **paperweight `userdev` 2.0.0-beta.23** + `run-paper 3.1.0`（v2 下载 API 已下线，3.1.0 起走 Fill v3）；配置缓存 / build cache / parallel 已开启 |
| **NMS 映射** | 开发期用 `paperDevBundle("26.1.2.build.74-stable")`（26.1 起新格式 `<mc>.build.<N>-stable`；Mojang 已移除服务端混淆）提供 Mojang 名 `net.minecraft.*`；**26.1 起 reobf 废除**（paperweight 官方文档：reobf 插件无法在 Paper 26.1+ 加载），产物即 Mojang 映射 jar，标准 Paper 直接加载 |
| **反射用 Mojang 名** | 产物即 Mojang 映射、Paper 运行时亦然 → 反射私有成员直接用 Mojang 名 |
| **可选依赖** | PacketEvents `compileOnly("...packetevents-spigot:2.13.0")` + `plugin.yml: softdepend: [packetevents]`（仅供 Servux EasyPlace 用；未装则优雅跳过） |
| **版本注入** | 见上节；`gradle.properties` 是唯一版本来源 |
| **当前状态** | 三个 mod（Servux / JEI Recipe Bridge / Syncmatica）在 26.1 线全部实现，服务端实机验证通过 |

构建命令（工具链 25 自动解析——foojay 下载兜底 + 本机 `~/.gradle/gradle.properties` 探测路径，无需手动 JAVA_HOME）：
```bash
./gradlew build        # 产出 Mojang 映射 jar（VeryMcProto-<版本>.jar，标准 Paper 26.1+ 可直接加载）
./gradlew test         # 纯函数单测（PacketSplitter/FeatureSet/LitematicaBitArray/DataTagIo 等，无需起服务端）
./gradlew runServer    # 本地起 26.1.2 测试服（2G 堆；MC 版本跟随 gradle.properties 的 mcVersion）
```

> 为什么必须引入 paperweight/NMS：Servux 的数据采集大量依赖 NMS 内部（`NaturalSpawner.SpawnState`、`ServerTickRateManager`、`ChunkAccess.getAllReferences()`、`StructureStart.createTag()`、`Recipe.CODEC` + `NbtOps`、`BlockEntity.saveWithFullMetadata()` 等），网络层最干净的实现也复用原版 `FriendlyByteBuf` / `CompoundTag`，JEI/Syncmatica 的 S2C 大包直发依赖 NMS `ClientboundCustomPayloadPacket`。纯 Paper API 无法触达这些。详见 [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md)。

---

## 代码架构

包根 `verymc.top.veryMcProto`，分**框架层**（`framework/`，与具体协议 mod 解耦的基础设施）与**协议 mod 层**（`mod/<modid>/`，每个被移植的 Fabric 协议 mod）。新增协议 mod 只需在 `mod/` 下实现 `ModModule` 并在主类注册，无需改动框架。

### 框架层 `framework/`

| 包 | 职责 | 关键类 |
|---|---|---|
| `framework/` | 协议 mod 模块抽象 | `ModModule`（getModId/getModString/onRegister） |
| （包根） | 全局常量与句柄 | `Reference`（PLUGIN_NAME / **MC_VERSION / PLUGIN_VERSION——类加载时读 version.properties** / init(plugin) / logger()） |
| `framework/network/` | plugin messaging 通道封装、字节流编解码、分片、Handler 注册表 | `ChannelManager`、`ProtocolChannel`、`PacketSplitter`、`ServerPlayHandler`、`IPluginServerPlayHandler`、`IServerPayloadData`、`FriendlyByteBufs` |
| `framework/dataproviders/` | Provider 注册表/调度器/配置中枢（Servux 用） | `DataProviderManager`、`DataProviderBase`、`IDataProvider` |
| `framework/event/` | Bukkit 事件 → Provider 生命周期桥 | `LifecycleBridge`（ServerLoad/PlayerJoin/Quit/Respawn/RegisterChannel + tick 调度） |
| `framework/debug/` | 通用调试日志引擎（多 mod 独立实例，master + 分类正交，持久化） | `DebugSystem` |
| `framework/permission/` | 权限工具（替代 fabric-permissions-api） | `Perms` |
| `framework/reflect/` | NMS 反射工具（缓存 + 防御式，版本漂移时降级返回默认值） | `Reflect` |
| `framework/nms/` | Bukkit ↔ NMS 转换 | `Nms`（toNms(Player/World)/server()） |
| `framework/settings/` | Servux 配置项体系 | `IServuxSetting` / `AbstractServuxSetting` / `ServuxBoolSetting` / `ServuxIntSetting` / ... |
| `framework/util/` | JSON / 字符串工具 | `JsonUtils`（Gson pretty + 原子 tmp/move 落盘）、`StringUtils` |

### 协议 mod 层 `mod/`

| mod | 包结构 | 装配方式 |
|---|---|---|
| **servux** | `app/ServuxModule`、`command/`、`dataproviders/`（6 Provider）、`network/`（5 Handler+Packet）、`easyplace/`、`loggers/`、`schematic/`（container/selection/placement/transmit）、`util/` | `ServuxModule.onRegister(DataProviderManager)` 注册 6 Provider + 反射加载 EasyPlace |
| **jeirecipebridge** | `app/JeiRecipeBridgeModule`、`RecipeSyncHandler`、`payload/`、`config/JeiConfiguration`、`command/JeiCommand` | `JeiRecipeBridgeModule.onRegister(manager)` 注册 outgoing 通道 + PlayerJoinEvent 监听（不注册 Provider） |
| **syncmatica** | `app/SyncmaticaModule`、`SyncmaticaContext`、`communication/`（+`exchange/`）、`data/`（+`litematica/`）、`extended_core/`、`network/`、`service/`、`util/` | `SyncmaticaModule.enable(plugin)`（**不走 DataProviderManager**——Exchange 会话模型，自管通道注册 + 玩家监听） |

主类 `VeryMcProto.onEnable()`：初始化框架（ChannelManager / DataProviderManager / LifecycleBridge）→ 依次注册 servux / jeirecipebridge / syncmatica 三个模块 → 注册 `/servux` `/jei` `/syncmatica` 命令。所有装配均包 try-catch，任何模块失败只记录日志、降级跳过，绝不影响服务端启动。

### 关键替换点（Fabric → Paper）一句话版

- `ModInitializer.onInitialize()` → `JavaPlugin.onEnable()`
- Mixin 生命周期钩子 → **Bukkit 事件**（`ServerLoadEvent` / `PlayerJoinEvent` / `PlayerQuitEvent` / `PlayerRespawnEvent` / `PlayerRegisterChannelEvent`）+ BukkitRunnable tick 调度
- **Mixin/AccessWidener 无法迁移**（Paper 无 Mixin）→ 反射 / Bukkit 事件 / PacketEvents / 降级省略（见核心约束 §2）
- Fabric `ServerPlayNetworking`（注册/收发 `CustomPacketPayload`）→ **Paper `Messenger`（plugin messaging channel）+ NMS `ClientboundCustomPayloadPacket` 直发**（见核心约束 §1）
- `fabric-permissions-api` → `framework.permission.Perms`（`player.hasPermission(...)`，op 等级映射）

---

## 核心设计约束（维护必读）

### 1. 网络层命门：原版 CustomPacketPayload，三种 S2C 路径

**这是整个移植的基石，理解错了后面全错。** 所有被移植的 mod 都用 **Mojang 在 1.20.2+ 引入的原版 `net.minecraft.network.protocol.common.custom.CustomPacketPayload`** 机制。Fabric 的 `ServerPlayNetworking` 只是这套原版机制的注册封装层，不是独立协议。

Paper 的 **plugin messaging channel（`namespace:path` 命名）直接映射到原版 custom payload 通道**：`PluginMessageListener.onPluginMessageReceived(channel, player, byte[])` 收到的 `byte[]` 就是 `FriendlyByteBuf` 的裸字节，`player.sendPluginMessage(...)` 发出的 `byte[]` 同理（实证：[FabricMC Discussion #4430](https://github.com/orgs/FabricMC/discussions/4430)）。

**Servux 5 条数据通道 + 1 条配置主通道**（通道网络名 ≠ provider 逻辑名，源码 `ServuxReference.java` 实证；协议版本为 26.1 线真值，客户端按 `!=` 严格相等校验，错一个即整通道退网）：

| 通道网络名 | Provider 逻辑名 | 协议版本 | 用途 |
|---|---|---|---|
| `servux:main` | `servux_main` | — | 配置主通道（ConfigProvider，永不可禁用，**不下发网络包**，仅承载全局 settings） |
| `servux:hud_metadata` | `hud_data` | 3 | HUD：世界元数据/出生点/天气/TPS/MobCap/配方 |
| `servux:entity_data` | `entity_data` | 2 | Entities：方块实体/实体 NBT 查询 |
| `servux:tweaks` | `tweaks_data` | 2 | Tweaks：实体/方块实体 NBT（与 Entities 同模式） |
| `servux:structures` | `structure_bounding_boxes` | 3 | Structures：结构边界框（周期扫描区块） |
| `servux:litematics` | `litematic_data` | 2 | Litematics：投影投递/粘贴/批量实体 |

**26.1 wire 三大变化**（对照 `OriginImpl/*-LTS-26.1` 客户端源码逐字实证）：
1. **MOD_STRING 硬门禁**：客户端校验 `servux.startsWith("servux-fabric-<精确上游MC id>")`（`MOD_TYPE` 恒 "fabric"），故我方 `ServuxReference.MOD_TYPE = "fabric"` 伪装 + `mcVersion` 必须用精确补丁号 26.1.2；1.21.11 时代的 "paper" 三段式会被四通道全部静默拒绝。
2. **DataTag 线格式载体**：业务包 NBT 从 vanilla `writeNbt` 切换为 malilib DataTag 格式 `[int32 大端 压缩长][GZIP(具名根 NBT 流)]`（`mod/servux/util/nbt/DataTagIo.java`，与 NMS `NbtIo` 输出逐字节兼容，配单测）。分界规则**逐 Type**：全通道 metadata 1/2 恒 vanilla；分片 10-13 恒裸字节；其余业务 Type 走 DataTag（含 START 大包经 PacketSplitter 的**重组整体**）。Structures 通道包帧本身全程 vanilla/裸字节，是唯一幸存者。
3. **C2S 变化**：请求删除 `transactionId` 前置 VarInt（残留吞读会错位解析）；批量重组体改按 NBT `"Task"` 字符串路由；新增 `UNREGISTER_REPLY`（HUD=9 / Entities=7 / Tweaks=7 / Litematics=8，服务端 decode→unregister）；Structures 删 type 10/11/12（spawn/weather 完全收敛到 HUD 通道）；Litematica task 组 14-17 **已实现**（`scheduler/` 三类 v3 极简形态 + 四处接线，见 docs/09 §26.1.5——type 14 受理 Fill/Delete、type 16 状态/完成帧；type 15 客户端接收端 TODO 故服务端永不发送、type 17 上游同源忽略）。

**三种 S2C 路径**（按 mod 选择）：
- **Servux**：全程 **plugin messaging**（`ProtocolChannel.send` → `player.sendPluginMessage`），大包走 `PacketSplitter` 分片。
- **JEI Recipe Bridge**：**NMS `ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))` 直发**（`RecipeSyncHandler.sendPayload`），配方包常远超 32KiB，走 `sendPluginMessage` 会被拒。
- **Syncmatica**：默认 **NMS `DiscardedPayload` 直发**（`ExchangeTarget.sendPacket`，`S2C_VIA_NMS=true`），构造 `[Identifier][body]` 复合包体；可用 `/syncmatica debug s2c msg` 切回 plugin messaging 对比（实测 plugin messaging wire 对纯 Fabric syncmatica 客户端不可达，故默认走 NMS）。

**字节限制命门（务必注意）**：
- Bukkit `Messenger.MAX_MESSAGE_SIZE` = 1048576（~1MiB），plugin messaging API 层不再以 32KiB 拒绝（旧文档称 32768 已过时）。
- **真正的 S2C 瓶颈是原版客户端对 `ClientboundCustomPayload` 的 32767 字节解码上限**——超过会让客户端断连。
- `PacketSplitter`（`framework/network/PacketSplitter.java`）分片常量：
  - S2C：`MAX_TOTAL_PER_PACKET_S2C = 32_000`，`MAX_PAYLOAD_PER_PACKET_S2C = 31_995`（留余量给 VarInt 头，防御客户端 32767 上限）
  - 我方接收上限：`DEFAULT_MAX_RECEIVE_SIZE_S2C = 64MB`（`receive` 默认用它；C2S 上传如 litematic 粘贴同走此路径，单物理通道不分方向。原版 C2S 专用常量 `MAX_TOTAL_PER_PACKET_C2S` / `MAX_PAYLOAD_PER_PACKET_C2S` / `DEFAULT_MAX_RECEIVE_SIZE_C2S` 已删——零引用死代码，见 docs/TECH_DEBT_AUDIT F006）
  - **26.1 客户端（malilib）重组上限降为 16MB**（1.21.11 为 128MB）——S2C 文件投递入口有服务端 16MB 门禁（`LitematicaSchematic.MAX_TRANSMIT_FILE_SIZE`，超限 TransmitCancel + 明确提示，不截断不静默）
- 大包（Recipe / Litematic 投影 / Structures / 批量实体）必须走 `PacketSplitter` 分片。Syncmatica 文件分片**不复用 `PacketSplitter`**，自写 stop-and-wait（`BUFFER_SIZE=16384`，每片确认）。详见 [`docs/02-network-protocol.md`](docs/02-network-protocol.md) §分片与 [`docs/21-syncmatica-protocol.md`](docs/21-syncmatica-protocol.md) §6。

**C2S 接收命门**：Paper 原版服务端对未注册的 custom payload 会**踢玩家**（"Invalid payload"）。plugin messaging 注册的通道由 Paper 内置路由、不踢人——这正是用 `Messenger.registerIncomingPluginChannel` 接收 C2S 的理由。**握手机制命门**：configuration phase 期间 `sendPluginMessage` 会静默丢弃，客户端收不到。框架用 `PlayerRegisterChannelEvent`（客户端声明通道 = 装了对应 mod = configuration phase 已完成）作为可靠信号，在 `IDataProvider.onPlayerRegisterChannel` / syncmatica `onPlayerRegisterChannel` 重发 metadata / 发起握手。

### 2. Mixin / AccessWidener 无法迁移 → 三段式处置

Servux 共 **26 个 Mixin + 2 个 AccessWidener 字段**；Syncmatica 共 **5 个服务端 Mixin**。Paper 无 Mixin 运行时，**逐一**按下表处置（完整清单见 [`docs/04-mixin-analysis.md`](docs/04-mixin-analysis.md) / [`docs/22-syncmatica-mixin-migration.md`](docs/22-syncmatica-mixin-migration.md)）：

| Mixin 类别 | 处置 | 示例 |
|---|---|---|
| **协议数据采集必需**（读私有字段） | **反射 / NMS 直接访问** | `ServerTickRateManager.remainingSprintTicks`、`NaturalSpawner.MAGIC_NUMBER`、`ChunkAccess.getAllReferences()` |
| **采集触发 / 生命周期**（钩子） | **Bukkit 事件 / 调度器替代** | `ServerLoadEvent` / `PlayerJoinEvent` / `PlayerQuitEvent` / `PlayerRegisterChannelEvent` + tick 调度 |
| **服务端行为改造**（改逻辑） | **降级 / PacketEvents / 事件 / 省略** | EasyPlace（✅ PacketEvents）、UpdateSuppression（⛔ 省略）、潜影盒堆叠（⛔ 不可能，见 §3） |
| **调试** | 省略 | `SharedConstants.IS_RUNNING_IN_IDE` |
| AccessWidener | 反射 | `SharedConstants.DEBUG_ENABLED`、`NaturalSpawner.MAGIC_NUMBER` |

**判断标准**：原版源码里凡是 Mixin 注入（`@Inject`/`@WrapOperation`/`@Redirect`/`@Accessor`/`implements`）的，Paper 上都不能照抄——先判它属于上表哪一类，再选处置方式。**切勿引入 Mixin 依赖。**

### 3. EasyPlace / UpdateSuppression / 镜像修复 / 潜影盒堆叠 —— "改变服务端行为"类降级最严重

- **EasyPlace**（Tweakeroo 服务端配合）：✅ **已用 PacketEvents 全量实现**。`EasyPlaceListener` 拦截原版 `PLAYER_BLOCK_PLACEMENT`，取消包后调 `PlacementHandler.applyPlacementProtocolV3` 解码精确状态，手动复刻 `BlockItem.place` 副作用（setBlock / setPlacedBy / placeSound / shrink / ack）。PacketEvents 类引用**隔离**在 `EasyPlaceBootstrap`（反射加载，`catch(Throwable)` 降级）——见 [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md) §降级矩阵与 memory「可选依赖类隔离」。运行时需服务器装 packetevents 插件（`softdepend`），未装则优雅跳过、其余通道不受影响。
- **UpdateSuppression**：⛔ **省略**。依赖 Mixin 给 `Level`/`LevelChunk` 加接口 + 改 `setBlockState` 副作用，Paper 无 Mixin 无等价。
- **镜像修复**（箱子/铁轨/楼梯 180° 镜像）：✅ **已实现**（粘贴时用）。箱子镜像修复在 `SchematicPlacingUtils` 内联照抄（`fixChestMirror` setting）；铁轨/楼梯靠 `BlockState.mirror()/rotate()` 自身行为（`fixRailRotations`/`fixStairs_mirror` settings，原版靠 Mixin，Paper 降级可能不完美）。
- **潜影盒堆叠**（Tweakeroo `tweakShulkerBoxStacking` 服务端配合）：⛔ **不可能实现 + 已删全部代码**。改 NMS 全局方法行为，Paper 无 Mixin 无等价（反射改不了方法返回值；Bukkit 事件在 `maxStackSize=1` 前提下恒失败；设 `MAX_STACK_SIZE` 组件污染序列化）。`TweaksDataProvider` **不下发** `stackingShulkers` 元数据——否则客户端 tweakeroo 据其开客户端堆叠渲染而服务端不配合 → 不一致。

### 4. 26.1 线关键 NMS 约束（编译驱动实测清单）

- **`CompoundTag`**：`getBoolean/getInt/getString/...` 返回 `Optional`/`OptionalInt`，须用 `getBooleanOr/getIntOr/getStringOr` 或 `.orElse()`；`putXxx` 返回 `void`（非链式）。
- **`FriendlyByteBuf`**：协议体编码的核心类，`writeVarInt`/`writeNbt`/`readNbt` 等；paperweight userdev 可直接引用。
- **`CustomPacketPayload`**：`record Payload(...) implements CustomPacketPayload` + `static Type<Payload> ID` + `static StreamCodec<FriendlyByteBuf, Payload> CODEC`。协议层可近乎照抄（去 Fabric `@Environment` 注解）。
- **`Identifier` / `ResourceLocation`**：`Identifier.fromNamespaceAndPath("servux","hud_metadata")`（= Mojang `ResourceLocation`）。
- **`DiscardedPayload`**：NMS 直发 custom payload 的载体（`new DiscardedPayload(Identifier, byte[])`），JEI / Syncmatica S2C 用。
- **`ChunkPos`（26.1 变 record）**：字段私有，`pos.x`/`pos.z` → `pos.x()`/`pos.z()`；`new ChunkPos(long)` → `ChunkPos.unpack(long)`；`new ChunkPos(BlockPos)` → `ChunkPos.containing(BlockPos)`；`asLong(x,z)`/`toLong()` → `pack(x,z)`/`pack()`。
- **天气状态搬家**：`ServerLevelData.getClearWeatherTime/getRainTime/getThunderTime/isRaining/isThundering` → `ServerLevel.getWeatherData()`（`net.minecraft.world.level.saveddata.WeatherData`，同名方法保留）。
- **消息 API**：`player.displayClientMessage(comp, false)` → `player.sendSystemMessage(comp)`（单参，无 overlay 位）。
- **reobf 废除**：26.1 起 Mojang 移除服务端混淆，`reobfJar` 对 26.1+ dev bundle 不再工作，Paper 26.1+ 拒载 reobf 插件——产物直发 Mojang 映射 jar（`tasks.assemble` 不再依赖 reobfJar）。

### 5. 权限系统

- 原版 `me.lucko:fabric-permissions-api` → `framework.permission.Perms.check(player, node, level)`：显式设置以设置值为准；`level<=0` 全员放行；否则按 op 判断。
- **真实权限节点**（`src/main/resources/plugin.yml`）：

| 节点 | default | 用途 |
|---|---|---|
| `servux.command` | op | `/servux` |
| `jei.command` | op | `/jei enable\|disable` |
| `syncmatica.command` | true | `/syncmatica` 基础命令 |
| `syncmatica.command.admin` | op | `/syncmatica save\|reload\|enable\|disable\|status` |
| `syncmatica.command.load` | true | `/syncmatica load` |
| `syncmatica.command.load_each` | true | `/syncmatica load <file>` |
| `syncmatica.command.debug` | op | `/syncmatica debug` |

- 各 Provider 内部权限节点保持原版命名（如 `servux.provider.hud_data` / `.weather` / `.seed` / `.logger` / `.paste`）。

### 6. 投影粘贴 / 文件投递 —— 已实现（Servux schematic 子系统）

Litematica 投影子系统（`mod/servux/schematic/`，约 8000 行）已移植完成并实测通过：

- **粘贴**（C2S）：客户端上传 `.litematic` → `ServuxLitematicaHandler` 经 `PacketSplitter.receive` 重组 → `handleBulkData` 分流（`LitematicaPaste` 走 `LitematicsDataProvider.handleClientPasteRequest`；`Litematic-Transmit*` 走 `LitematicaSchematic.receiveFileTransmit` 落盘 + 粘贴）→ `SchematicPlacement.pasteTo` → `SchematicPlacingUtils.placeToWorldWithinChunk`（真实 `setBlock` + 方块实体 + 实体放置，含 ReplaceMode / PasteLayerBehavior / LayerRange）。需创造模式 + paste 权限。
- **文件投递**（S2C）：`/servux litematic transmit <file> [player]` 加载 `schematics/*.litematic` 通过 `servux:litematics` 通道投递给客户端。
- 逐阶段记录见 [`docs/11-schematic-migration-plan.md`](docs/11-schematic-migration-plan.md)（历史移植蓝图）与 [`docs/05-schematic-system.md`](docs/05-schematic-system.md)。

**移植方法**：照抄原版纯算法（BitArray/Palette/Container/几何/transmit）+ NMS 直连（`BlockState`/`CompoundTag`/`NbtIo`/`ServerLevel`）；仅 3 类强制降级——`SchematicConversionMaps`（DataFixer，`readFromNBT(enableFixers=false)` 守卫下零影响）、`IMixinWorldTickScheduler`（保存投影读 tick，粘贴不需要）、`WorldUtils`（Mixin → no-op，靠 `setBlock` 的 flags 控制邻居更新）。`LitematicaSchematic` 因 `selection↔placement↔schematic↔PositionUtils` 四元循环依赖，用**桩版**（移除引用未移植类的方法 + 准确注释）分阶段引入、逐步回填。

**实战教训（维护必读）**：

1. **协议字段语义必须对照客户端源码确认，不能只看服务端瞎猜客户端行为。** 例：`sendTransmitFile` 的 `Slice` 字段 servux 原版写 `totalSlices`（总片数），但 litematica `SchematicBuffer.receiveSlice` 要求 `number ∈ [0, totalSlices)`——写 `totalSlices` 必然越界被丢弃。读了 litematica 源码才定位。
2. **原版里未被调用的公开 API 可能是含 bug 的死代码。** `sendTransmitFile` 在原版无调用点，其 `Slice=totalSlices` bug 从未触发；照抄会原样继承。凡照抄「原版无调用点的方法」，务必对照客户端验证字段语义。
3. **PacketSplitter 连续流不会串台**（源码 + 实测确认）：malilib `PacketSplitter.receive` 收齐即 `READING_SESSIONS.remove(key)`，litematica `ServuxLitematicaHandler` 每流新生成 readingSessionKey、收齐重置——连续多个独立分包流各自独立重组，**不需要**分 tick / 延迟发送这类 workaround。
4. **SLF4J → JUL logger 适配**：原版 `Servux.LOGGER.warn/error/info("...{}...", args)`（SLF4J 占位符）→ Paper JUL 不支持 `{}` 多参重载，用 `mod/servux/util/Log.java` shim 机械替换。
5. **可选依赖类隔离**：PacketEvents 等 `compileOnly`/`softdepend` 依赖，其类引用必须隔离到独立引导类（`EasyPlaceBootstrap`），用反射加载 + `catch(Throwable)`——`try/catch` 抓不到方法解析阶段的类加载失败。

---

## 维护与升级要点

- **新增一个 Provider**（servux）：在 `dataproviders/` 加类（`extends DataProviderBase`），在 `network/` 加对应 Handler+Packet（通道编解码 + 字节布局），在 `ServuxReference` 加通道常量，在 `ServuxModule.onRegister` 登记。详见 [`docs/03-dataproviders-detail.md`](docs/03-dataproviders-detail.md)。
- **新增一个协议 mod**：在 `mod/<newmod>/` 实现 `ModModule`（或自管装配如 syncmatica），在 `VeryMcProto.onEnable` 注册，在 `plugin.yml` 加命令/权限。
- **发版**（版本内更新）：在所在开发线（`dev` 或 `ver/<X>-dev`）`gradle.properties` 的 `buildNumber` +1 → 提交 → 合入对应发布线（`main` 或 `ver/<X>`）→ `./gradlew build` → tag `v<版本>`。
- **升级 Minecraft 版本**（顺应上游）：先从 `main` 冻结旧版本（切 `ver/<旧版本>` + `ver/<旧版本>-dev` 对）→ 再在 `dev` 上改 `gradle.properties` 的 `mcVersion` + `build.gradle.kts`（dev bundle 26.1+ 新格式 `<mc>.build.<N>-stable`、Java 工具链、必要时 paperweight/run-paper/wrapper 版本）→ 重跑 paperweight → 编译驱动修 NMS 漂移（26.1 实测清单见核心约束 §4）→ 按 [`docs/04-mixin-analysis.md`](docs/04-mixin-analysis.md) 核对反射点 → **对照新版本 `OriginImpl/*-LTS/<新版本>` 客户端源码核对协议面**（协议版本常量、MOD_STRING 前缀门禁、载体格式——26.1 迁移实录见 docs/09 §26.1）。
- **旧版本修 Bug**：在 `ver/<X>-dev` 提交 → 合入 `ver/<X>` 出包；若 `dev`（新版本）同样存在该 Bug，cherry-pick 回 `dev`。
- **参考源码**（`OriginImpl/` 下，逐行对照的权威实现；**遇到分歧以真实源码为准**；本地目录已 gitignore，不入库；**1.21.11 与 26.1 双版本并存**——26.1 线对照 `*-LTS-26.1`，ver/1.21.11 维护对照 `*-LTS-1.21.11`）：
  - **servux**：`OriginImpl/servux-LTS-26.1/`——服务端协议实现（协议常量 / Handler 分发的权威）。
  - **litematica / malilib / minihud / tweakeroo**（masa 客户端，**协议的接收端与硬门禁所在**）：`OriginImpl/*-LTS-26.1/`。任何协议字段语义、分包重组、Task 分派、版本/前缀门禁都要回来对照客户端源码确认，**不要凭服务端代码猜客户端行为**（见 §6 教训 1）。
  - **syncmatica**：`OriginImpl/syncmatica-LTS-26.1/`。
  - **JEIRecipeBridge**：`OriginImpl/JEIRecipeBridge-26.1/`（Mrbysco 真上游）。
  - 另有 `itemscroller-LTS-*`（客户端参考）与 `packetevents-2.0`（EasyPlace 依赖对照）。
  - ⚠️ 原版里**未被调用的公开 API**（如 `LitematicaSchematic.sendTransmitFile`）可能是**未经验证的死代码**、含字段语义 bug——照抄后必须对照客户端源码验证（见 §6 教训 2；26.1 上游仍带 `Slice=totalSlices` bug，我方 `currentSlice` 修复**禁止随模板回退**）。

---

## 文档地图

| 文档 | 内容 |
|---|---|
| [`docs/00-INDEX.md`](docs/00-INDEX.md) | 文档总索引 + 推荐阅读路线 |
| [`docs/01-servux-architecture.md`](docs/01-servux-architecture.md) | 原版架构总览：启动流程、`DataProviderManager`、生命周期、配置/设置系统 |
| [`docs/02-network-protocol.md`](docs/02-network-protocol.md) ⭐ | **核心网络协议**：`CustomPacketPayload` 模型、`PacketSplitter` 分片、6 条通道、字节布局、收发流程 |
| [`docs/03-dataproviders-detail.md`](docs/03-dataproviders-detail.md) | 6 个 Provider 的协议数据内容 + 数据采集（含 `loggers` TPS/MobCap）+ 权限节点 |
| [`docs/04-mixin-analysis.md`](docs/04-mixin-analysis.md) | 26 Mixin + 2 AccessWidener 逐项清单、分类、迁移去向 |
| [`docs/05-schematic-system.md`](docs/05-schematic-system.md) ⭐ | Litematica 投影系统：BitArray/Palette/Container/Selection/Placement/Transmit + 传输协议 |
| [`docs/06-fabric-vs-paper.md`](docs/06-fabric-vs-paper.md) | Fabric ↔ Paper 框架差异对照表 |
| [`docs/07-migration-architecture.md`](docs/07-migration-architecture.md) ⭐ | **完整迁移技术方案**：架构设计、网络层/数据采集/降级矩阵、可行性验证 |
| [`docs/08-implementation-plan.md`](docs/08-implementation-plan.md) | 历史实施步骤（阶段划分 + 任务拆解） |
| [`docs/09-DELIVERY.md`](docs/09-DELIVERY.md) | 投递/字节限制专题（含客户端 32767 上限实证） |
| [`docs/10-testing-guide.md`](docs/10-testing-guide.md) | **Servux 客户端兼容测试**：5 通道↔3 mod 映射、测试步骤、排错流程 |
| [`docs/11-schematic-migration-plan.md`](docs/11-schematic-migration-plan.md) | schematic 子系统历史移植蓝图（P0-P9） |
| **Syncmatica 实现说明**（文档 20–24） | 投影共享中央仓库：单通道 + Exchange 会话层 + 文件存储（**已完整实现**） |
| [`docs/20-syncmatica-architecture.md`](docs/20-syncmatica-architecture.md) | 实际架构 + **与 Servux 本质差异对比表** + Exchange 会话模型 + framework 复用边界 |
| [`docs/21-syncmatica-protocol.md`](docs/21-syncmatica-protocol.md) ⭐ | 单通道 `[Identifier][body]` 包体、18 PacketType、Feature 协商、metadata 字段表、Exchange 状态机、stop-and-wait 分片 |
| [`docs/22-syncmatica-mixin-migration.md`](docs/22-syncmatica-mixin-migration.md) ⭐ | 5 Mixin→Bukkit 已落地映射、网络层/持久化/权限/命令迁移实现、降级矩阵、实际包结构 |
| [`docs/23-syncmatica-implementation-plan.md`](docs/23-syncmatica-implementation-plan.md) | 实现总览：文件清单 + 完成状态 |
| [`docs/24-syncmatica-testing-guide.md`](docs/24-syncmatica-testing-guide.md) | **Syncmatica 客户端兼容测试**：握手/分享/下载/修改/持久化/多玩家步骤 + 排错 |
| [`docs/references.md`](docs/references.md) | 参考资源链接 |

---

## 参考资源

- 仓库远端：https://github.com/Kevin-O-Hsu/VeryMcProto
- Paper 开发文档：https://docs.papermc.io/paper/dev/
- Paper 插件消息通道（plugin messaging）：https://docs.papermc.io/paper/dev/plugin-messaging/
- PaperWeight 指南：https://github.com/PaperMC/paperweight
- Minecraft Protocol Wiki：https://wiki.vg/Protocol （`Custom Payload` 包结构）
- Fabric 网络文档：https://docs.fabricmc.net/develop/networking
- FabricMC Discussion #4430（Spigot/Paper ↔ Fabric 自定义通道实证）：https://github.com/orgs/FabricMC/discussions/4430
- masa 全家桶源码（本仓库对照）：servux/litematica/malilib/syncmatica/JEIRecipeBridge 均在 `OriginImpl/` 下
- 姊妹项目 VeryMcBot（paperweight userdev + NMS 反射范式参考）：`I:\Programming\VeryMcBot`

**开发环境**：IntelliJ IDEA + Minecraft Dev SDK + Gradle + PaperWeight。
