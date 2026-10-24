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
- **版本**：插件版本 = `<mcVersion>-b<buildNumber>`（当前值以 `gradle.properties` 为准）。**唯一来源是 `gradle.properties`**——发版只需在所在开发线 `buildNumber` +1，**任何源码、plugin.yml、文档中都不得手写版本号**；README 的版本字串位为本仓库发版流程认可的例外，随发版提交同步（各线具体形态见 docs/operations.md §0 版本字串策略）（注入链路见下文）。
- **语言与风格**：**对外可见面一律英文**——plugin.yml 元数据（全部 description 等字符串）、日志输出、玩家消息/命令回执必须是英文；译文一律 ASCII 化（§ 颜色码除外），禁止 `\uXXXX` 转义等绕过写法。注释与 `docs/` 文档用中文，与现有代码一致（中文 javadoc、常量类 + 源码实证注释）。
- **协议字段语义**改动前必须对照 `OriginImpl/` 下的客户端源码（litematica / malilib / syncmatica 是协议接收端），**不要凭服务端代码猜客户端行为**。
- **禁止引入 Mixin / AccessWidener / 服务端 patch 依赖**——Paper 无 Mixin 运行时，替代方案见核心约束 §2。
- **可选依赖**（PacketEvents 等 `compileOnly`）的类引用必须隔离到独立引导类 + 反射加载 + `catch(Throwable)`（见核心约束 §6 教训 5）。
- **构建**：`./gradlew build`（reobf jar）· `./gradlew test`（纯函数单测）· `./gradlew runServer`（本地测试服）。**配置缓存已开启**：task 配置 lambda 内不得捕获脚本顶层 `val`，用 task 自身的 `providers` 取值。

## 项目简介

**VeryMcProto** 把 **Fabric 端独特的 Mod Protocol（协议 Mod）** 以纯 Paper 插件形式重新实现。所有实现基于 **Minecraft 1.21.11**，运行在标准 **Paper 1.21.11** 服务端，不依赖任何服务端 patch / Mixin / 私有 fork。

每个被移植的 Mod 独占一个目录单元；原版 Fabric 实现统一存放在 `OriginImpl/` 下用于逐行对照（本地参考，已 gitignore，不入库）。**三个协议移植目标全部已完整实现并实测通过；第四模块 Carpet（行为注入型 mod 的可移植子集）已落地**：

- **Servux**（`mod/servux/`，对照 `OriginImpl/servux-LTS-1.21.11/`）—— masa 开发的服务端协议 Mod，为 masa 的客户端 Mod（**MiniHUD / Litematica / Tweakeroo**）提供**服务端→客户端的数据投递与协议**，通过自定义网络通道（`servux:*`）下发：世界元数据、出生点、天气、TPS/MobCap、结构边界框、Litematica 投影投递/粘贴、实体与方块实体 NBT 查询、EasyPlace 服务端放置协议等。5 通道 + schematic（粘贴/投递）+ EasyPlace 全功能已实现。
- **JEI 服务端协议**（`mod/jei/`，对照 `OriginImpl/JustEnoughItems-1.21.11/`（mezz 分支 `1.21.11` = JEI 27.44.0 / MC 1.21.11 / Java 21，commit `2af0130`；与 26.2 分支 network/transfer 互 diff 零字节差））—— **完整 JEI 协议**三层：① 配方同步层：`fabric:recipe_sync`（Fabric API `fabric-recipe-api-v1` wire，对照 `OriginImpl/fabric-1.21.11/`，RegisterChannel 触发 + 进服时序整形 `RecipeSyncJoinOrderer`——netty 出站扣住 UpdateRecipesPacket、等 play register 证据后放行）+ `neoforge:recipe_content`（join + brand 触发，wire 参考 `OriginImpl/JEIRecipeBridge-1.21.11/`）+ NeoForge tag 表补发；② `jei:*` 自有通道 10 条（8 C2S：`request_cheat_permission` / `give_item_stack` / `delete_player_item` / `set_hotbar_item_stack` / `recipe_transfer_with_result` / `recipe_transfer_counted_with_result` / legacy `recipe_transfer` / legacy `recipe_transfer_counted`；2 S2C：`cheat_permission` / `recipe_transfer_result`）——**客户端功能门禁 `isJeiOnServer()` = 服务端声明过 `jei:delete_player_item` 通道（`ChannelManager` 成对注册），与 brand 无关**；③ 服务端行为：cheat 权限三切面（Op=权限级 2 / Give=`minecraft.command.give` / Creative）+ `BasicRecipeTransferHandlerServer` 配方转移算法逐行移植。协议详情见 [`docs/jei/protocol.md`](docs/jei/protocol.md)。2026-10 从旧 JEI Recipe Bridge 桥形态（`mod/jeirecipebridge/`，已删）完整迁移（jei 跨线 cherry-pick 禁止、手工重写，实录见 docs/jei/protocol.md §8）。
- **Syncmatica**（`mod/syncmatica/`，对照 `OriginImpl/syncmatica-LTS-1.21.11/`）—— **投影共享**协议 Mod：服务端作中央仓库存储 `.litematic`，多玩家上传/下载/协同修改放置位置。单物理通道 `syncmatica:main` + 18 逻辑 PacketType + Exchange 会话层（请求-应答状态机）+ 文件存储 + JSON 持久化 + 配额/调试服务。与 Servux（单向广播）根本不同——**双向、有状态、多玩家共享**。全功能已实现（`modifyState` 锁表有两处本地修复：CHM null 语义翻译 + null placement 守卫——迁移引入 NPE 链 + 上游原生缺陷，勿随模板回退，见 docs/21 §5.4）。
- **Carpet**（`mod/carpet/`，对照 `OriginImpl/fabric-carpet-1.21.11/`（gnembon/fabric-carpet 分支 `1.21.11`，mod_version `1.4.194`——协议握手 HI 帧版本串逐字，禁发插件版本号））—— Fabric 端**行为注入型** mod 的可移植子集：注解规则框架（`carpet.conf` 纯文本 + locked 语义 + validator 链）+ `/carpet` 四子命令（双层门禁）+ 18 条上游实名规则 + 假人子系统（patches 三件套走原版 placeNewPlayer 链 + ActionPack 侧表驱动 + `/player` 全族）+ carpet:hello 协议（单 CompoundTag 载体、S2C NMS DiscardedPayload 直发、Rules 全量/增量同步、clientCommand 回执）+ `/log` 订阅框架（tps/mobcaps HUD 走 tab 脚、跨会话持久 carpet-logs.json 双信号模型）+ 五条纯计算命令（/distance /info /perimeterinfo /draw /spawn）。**本线与 26.2 线的 Paper 玩家物理路径根本不同（掉落伤害经 `isClientAuthoritative()` 覆写恢复 vanilla 链，勿随 dev 线互抄）**——本线 as-built 与适配记录见 [`docs/carpet/architecture.md`](docs/carpet/architecture.md)（§6 per-line 差异）与 [`docs/carpet/protocol.md`](docs/carpet/protocol.md)。

> **本项目的本质是"协议层移植"**：客户端仍是 masa / syncmatica 的 Fabric Mod；我们要在 Paper 服务端复刻它们期待的**网络协议 + 数据采集**，使"Fabric 客户端 + Paper 服务端"的组合能像"Fabric 客户端 + 原版服务端 Mod"一样工作。

> **所有技术文档**都在 [`docs/`](docs/) 下（全局四件留根 + 模块子目录，三线同构命名），互相索引。**强烈建议先读 [`docs/index.md`](docs/index.md)** 获取文档地图与推荐阅读路线。

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
- **1.21.11 已是旧版本**：`ver/1.21.11` + `ver/1.21.11-dev` 维护对已从 `main`（tag `v1.21.11-b1`）冻结切出；本仓库全部代码基于 1.21.11 NMS。
- `dev → main` 是最新版本开发线，**尚未适配 26.x**（`mcVersion` 仍为 1.21.11）；适配 26.2 属于后续工作（改 `mcVersion` + dev bundle + NMS 漂移核对，见「升级 Minecraft 版本」）。

**发版**（版本内更新）：所在开发线（`dev` 或 `ver/<X>-dev`）`buildNumber` +1 并提交 → 合入对应发布线（`main` 或 `ver/<X>`）→ `./gradlew build` → tag `v<版本>`（如 `v1.21.11-b1`）。

### 版本系统（单一来源注入链路）

版本格式 **`<MC版本>-b<构建号>`**（如 `1.21.11-b1`）；MC 未来改日期式命名（如 `26.1`）时自动成为 `26.1-b1`，无需改格式。

```
gradle.properties（mcVersion=1.21.11 · buildNumber=1）          ← 唯一改动点
   │  build.gradle.kts: version = "$mcVersion-b$buildNumber"
   ▼
├─ plugin.yml（version: '${version}' + api-version: '${mcVersion}' 展开） → /version、Paper 插件列表/加载门禁
├─ jar 文件名 VeryMcProto-<mcVersion>-b<buildNumber>(-reobf).jar（本线保留 reobf 装配；现值见 gradle.properties）
└─ version.properties（mcVersion/version 双键，processResources 展开）
       │  Reference 类加载时 Properties.load 读回
       ▼
   Reference.MC_VERSION / Reference.PLUGIN_VERSION（框架级常量）
       ├─ ServuxReference.MOD_STRING   = "servux-paper-<mcVersion>-b<buildNumber>"（三段式；本线客户端仅前缀识别）
       ├─ SyncmaticaReference.MOD_VERSION = "<mcVersion>-b<buildNumber>"（-b 后缀永不命中
       │    FeatureSet.fromVersionString 的 ^\d+(\.\d+){2,4}$ 正则 → 恒触发 FEATURE 交换）
       └─ （JEI 无版本握手支腿——jei 协议无 MOD_STRING/版本协商字段，服务端检测走通道声明 jei:delete_player_item）
```

要点：
- `processResources` 的 expand() 占位符值**不参与 Gradle up-to-date 跟踪**——已显式 `inputs.property("version"/"mcVersion")`（否则 buildNumber+1 后任务误判 UP-TO-DATE、陈旧版本进新 jar，26.1.2-b2 事故实证）；`verifyVersionInjection` 解包产物 jar 三断言（version.properties 双键/plugin.yml version/api-version==mcVersion）作构建期终检——检查对象为 plain jar，交付物为 `-reobf.jar`（资源两 jar 逐字节同源，断言等价）。
- `Reference.loadVersionProperty` 资源缺失时回退 `dev-unknown`（开发环境不崩），生产 jar 恒有值。
- 版本号出现在任何别的位置都是 bug——grep `1\.21\.11` 应只命中 `gradle.properties`、`version.properties` 模板（`${...}` 占位）与文档示例。
- 历史 tag `v1.0.0`（旧命名体系）保留作历史记录；新 tag 一律 `v<mcVersion>-b<buildNumber>`。

---

## 技术栈与构建

| 项 | 说明 |
|---|---|
| **目标平台** | Paper **1.21.11**（`api-version: 1.21.11`，模板化注入），Java **21** |
| **构建** | Gradle（Kotlin DSL） + **paperweight `userdev`** + `run-paper`；配置缓存 / build cache / parallel 已开启 |
| **NMS 映射** | 开发期用 `paperDevBundle("1.21.11-R0.1-SNAPSHOT")` 提供 Mojang 全反混淆的 `net.minecraft.*`；产物经 `reobfJar` 转 Spigot 运行时映射，标准 Paper 直接加载 |
| **反射用 Mojang 名** | `reobf` 不转换反射字符串，Paper 运行时即 Mojang 映射 → 反射私有成员直接用 Mojang 名 |
| **可选依赖** | PacketEvents `compileOnly("...packetevents-spigot:2.13.0")` + `plugin.yml: softdepend: [packetevents]`（仅供 Servux EasyPlace 用；未装则优雅跳过） |
| **版本注入** | 见上节；`gradle.properties` 是唯一版本来源 |
| **当前状态** | 三个协议 mod（Servux / JEI / Syncmatica）+ 第四模块 Carpet 可移植子集全部已实现并实测通过 |

构建命令：
```bash
./gradlew build        # 产出 reobf jar（VeryMcProto-<版本>-reobf.jar，标准 Paper 可直接加载）
./gradlew test         # 纯函数单测（PacketSplitter/FeatureSet/LitematicaBitArray 等，无需起服务端）
./gradlew runServer    # 本地起 1.21.11 测试服（2G 堆；MC 版本跟随 gradle.properties 的 mcVersion）
```

> 为什么必须引入 paperweight/NMS：Servux 的数据采集大量依赖 NMS 内部（`NaturalSpawner.SpawnState`、`ServerTickRateManager`、`ChunkAccess.getAllReferences()`、`StructureStart.createTag()`、`Recipe.CODEC` + `NbtOps`、`BlockEntity.saveWithFullMetadata()` 等），网络层最干净的实现也复用原版 `FriendlyByteBuf` / `CompoundTag`，JEI/Syncmatica 的 S2C 大包直发依赖 NMS `ClientboundCustomPayloadPacket`。纯 Paper API 无法触达这些。详见 [`docs/architecture.md`](docs/architecture.md)。

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
| **jei** | `app/JeiModule`、`JeiReference`、`network/`（JeiServerPlayHandler + JeiPacketSender + RecipeSyncJoinOrderer（fabric 腿进服时序整形——netty 出站扣住 UpdateRecipesPacket、等 play register 证据后放行，见 docs/jei/protocol.md §5.2）+ `payload/` 11 文件 = 8 wire 包类 + 1 抽象基类 + `legacy/` 2）、`transfer/`（TransferOperation + BasicRecipeTransferHandlerServer）、`cheat/`（Cheats + GiveMode）、`recipesync/`（Fabric/Neoforge 双 payload + RecipeSyncService）、`config/JeiConfiguration`、`command/JeiCommand` | `JeiModule.enable(plugin)`（**自管**——仿 syncmatica：ChannelManager 注册 8 条 jei:* C2S + Messenger 出站声明配方通道 + RegisterChannel/Join/AsyncConfigure 监听；onDisable 调 `JeiModule.disable()`） |
| **syncmatica** | `app/SyncmaticaModule`、`SyncmaticaContext`、`communication/`（+`exchange/`）、`data/`（+`litematica/`）、`extended_core/`、`network/`、`service/`、`util/` | `SyncmaticaModule.enable(plugin)`（**不走 DataProviderManager**——Exchange 会话模型，自管通道注册 + 玩家监听） |
| **carpet** | `CarpetReference`、`CarpetSettings`（18 规则）、`app/CarpetModule`、`command/`（CarpetCommand/PlayerCommand/LogCommand + 五类纯计算命令——Paper Brigadier API）、`patches/`（EntityPlayerMPFake/FakeClientConnection/NetHandlerPlayServerFake）、`helpers/`（EntityPlayerActionPack + ActionPackManager）、`settings/`（Rule 注解 + Validator + ParsedRule + SettingsManager）、`network/`（CarpetHelloHandler/CarpetPacketSender/CarpetSnoopyCommandSource）、`logging/`（CleanLogsFilter + Logger/HUDLogger/LoggerRegistry/HUDController/SpawnReport/LogSubscriptionStore/LoggerListener）、`util/`（Messenger/CommandHelper/DistanceCalculator/BlockInfo/Colors/PerimeterDiagnostics/Tracer）、`listener/`（CarpetRuleListener/FakePlayerListener） | `CarpetModule.onRegister(DataProviderManager)`（ModModule 实现——manager 形参空转；SettingsManager 单例 + conf 载入 + 全子系统挂点；onDisable 摘通道/过滤器 + kill 全假人 + HUD 善后）——as-built 见 [`docs/carpet/architecture.md`](docs/carpet/architecture.md) |

主类 `VeryMcProto.onEnable()`：初始化框架（ChannelManager / DataProviderManager / LifecycleBridge）→ 依次注册 servux / jei / syncmatica / carpet 四个模块 → 注册 `/servux` `/jei` `/syncmatica` `/carpet` 命令。所有装配均包 try-catch，任何模块失败只记录日志、降级跳过，绝不影响服务端启动。

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

**Servux 5 条数据通道 + 1 条配置主通道**（通道网络名 ≠ provider 逻辑名，源码 `ServuxReference.java` 实证）：

| 通道网络名 | Provider 逻辑名 | 协议版本 | 用途 |
|---|---|---|---|
| `servux:main` | `servux_main` | — | 配置主通道（ConfigProvider，永不可禁用，**不下发网络包**，仅承载全局 settings） |
| `servux:hud_metadata` | `hud_data` | 2 | HUD：世界元数据/出生点/天气/TPS/MobCap/配方 |
| `servux:entity_data` | `entity_data` | 1 | Entities：方块实体/实体 NBT 查询 |
| `servux:tweaks` | `tweaks_data` | 1 | Tweaks：实体/方块实体 NBT（与 Entities 同模式） |
| `servux:structures` | `structure_bounding_boxes` | 2 | Structures：结构边界框（周期扫描区块） |
| `servux:litematics` | `litematic_data` | 1 | Litematics：投影投递/粘贴/批量实体 |

**三种 S2C 路径**（按 mod 选择）：
- **Servux**：**plugin messaging 优先**（`ProtocolChannel.send` → `player.sendPluginMessage`），大包走 `PacketSplitter` 分片。**同通道 C2S 证明兜底**：Paper `CraftPlayer.sendPluginMessage` 有 `channels().contains(channel)` 门控（26.1.2 反编译实锤），玩家声明包被处理前 S2C **静默丢弃**（声明处理晚于客户端首个 C2S 到达）；若该玩家已在本通道发过 C2S（= 装有对应 mod、注册了 codec，能发即能收），`ProtocolChannel.send` 在 `listening=false` 时改走 NMS `new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))`——与 Paper 自身放行路径逐字同构。未发过 C2S 的玩家（vanilla / 未装 mod）永不走兜底（防护语义构造性保留）；证明集合随 `PlayerQuitEvent` 清除。
- **JEI**：**NMS `ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes))` 直发**（`JeiPacketSender.send`），配方包常超 1MiB（`ProtocolChannel.send` 对超 Bukkit 上限的包硬拒，故不可走框架 send）；jei:* C2S 8 条经 `ChannelManager` 成对注册（声明是客户端 `isJeiOnServer()` 门禁的解锁条件）。
- **Syncmatica**：默认 **NMS `DiscardedPayload` 直发**（`ExchangeTarget.sendPacket`，`S2C_VIA_NMS=true`），构造 `[Identifier][body]` 复合包体；可用 `/syncmatica debug s2c msg` 切回 plugin messaging 对比（实测 plugin messaging wire 对纯 Fabric syncmatica 客户端不可达，故默认走 NMS）。

**字节限制命门（务必注意）**：
- Bukkit `Messenger.MAX_MESSAGE_SIZE` = 1048576（~1MiB），plugin messaging API 层不再以 32KiB 拒绝（旧文档称 32768 已过时）。
- **真正的 S2C 瓶颈是原版客户端对 `ClientboundCustomPayload` 的 32767 字节解码上限**——超过会让客户端断连。
- `PacketSplitter`（`framework/network/PacketSplitter.java`）分片常量：
  - S2C：`MAX_TOTAL_PER_PACKET_S2C = 32_000`，`MAX_PAYLOAD_PER_PACKET_S2C = 31_995`（留余量给 VarInt 头，防御客户端 32767 上限）
  - 接收上限：`DEFAULT_MAX_RECEIVE_SIZE_S2C = 64MB`（`receive` 默认用它；C2S 上传如 litematic 粘贴同走此路径，单物理通道不分方向。原版 C2S 专用常量 `MAX_TOTAL_PER_PACKET_C2S` / `MAX_PAYLOAD_PER_PACKET_C2S` / `DEFAULT_MAX_RECEIVE_SIZE_C2S` 已删——零引用死代码，见 docs/TECH_DEBT_AUDIT F006）
- 大包（Recipe / Litematic 投影 / Structures / 批量实体）必须走 `PacketSplitter` 分片。Syncmatica 文件分片**不复用 `PacketSplitter`**，自写 stop-and-wait（`BUFFER_SIZE=16384`，每片确认）。详见 [`docs/servux/protocol.md`](docs/servux/protocol.md) §5 与 [`docs/syncmatica/protocol.md`](docs/syncmatica/protocol.md) §6。

**C2S 接收命门**：Paper 原版服务端对未注册的 custom payload 会**踢玩家**（"Invalid payload"）。plugin messaging 注册的通道由 Paper 内置路由、不踢人——这正是用 `Messenger.registerIncomingPluginChannel` 接收 C2S 的理由。**握手机制命门**：configuration phase 期间 `sendPluginMessage` 会静默丢弃，客户端收不到。框架用 `PlayerRegisterChannelEvent`（客户端声明通道 = 装了对应 mod = configuration phase 已完成）作为可靠信号，在 `IDataProvider.onPlayerRegisterChannel` / syncmatica `onPlayerRegisterChannel` 重发 metadata / 发起握手。**但该补发范式对 minihud structures 无效**——其客户端 metadata 接受窗口是单次的（进服开门 → 首个 `%20` tick 关门，minihud-LTS-1.21.11 `DataStorage.java:791-796/:705-721`），只能靠首个 C2S REGISTER 的**即时回复**建立连接；这正是上文「同通道 C2S 证明兜底」存在的理由（进服首回复不再被 Paper 门控吞掉）。已知限制：REGISTER 回复 RTT 超过客户端剩余窗口时仍需手动 toggle，与上游 Fabric servux 同源。

### 2. Mixin / AccessWidener 无法迁移 → 三段式处置

Servux 共 **26 个 Mixin + 2 个 AccessWidener 字段**；Syncmatica 共 **5 个服务端 Mixin**。Paper 无 Mixin 运行时，**逐一**按下表处置（完整清单见 [`docs/archive/04-mixin-analysis.md`](docs/archive/04-mixin-analysis.md) / [`docs/archive/22-syncmatica-mixin-migration.md`](docs/archive/22-syncmatica-mixin-migration.md)——冻结归档；处置矩阵 = [`docs/architecture.md`](docs/architecture.md) §5）：

| Mixin 类别 | 处置 | 示例 |
|---|---|---|
| **协议数据采集必需**（读私有字段） | **反射 / NMS 直接访问** | `ServerTickRateManager.remainingSprintTicks`、`NaturalSpawner.MAGIC_NUMBER`、`ChunkAccess.getAllReferences()` |
| **采集触发 / 生命周期**（钩子） | **Bukkit 事件 / 调度器替代** | `ServerLoadEvent` / `PlayerJoinEvent` / `PlayerQuitEvent` / `PlayerRegisterChannelEvent` + tick 调度 |
| **服务端行为改造**（改逻辑） | **降级 / PacketEvents / 事件 / 省略** | EasyPlace（PacketEvents）、UpdateSuppression（省略）、潜影盒堆叠（不可能，见 §3） |
| **调试** | 省略 | `SharedConstants.IS_RUNNING_IN_IDE` |
| AccessWidener | 反射 | `SharedConstants.DEBUG_ENABLED`、`NaturalSpawner.MAGIC_NUMBER` |

**判断标准**：原版源码里凡是 Mixin 注入（`@Inject`/`@WrapOperation`/`@Redirect`/`@Accessor`/`implements`）的，Paper 上都不能照抄——先判它属于上表哪一类，再选处置方式。**切勿引入 Mixin 依赖。**

### 3. EasyPlace / UpdateSuppression / 镜像修复 / 潜影盒堆叠 —— "改变服务端行为"类降级最严重

- **EasyPlace**（Tweakeroo 服务端配合）：**已用 PacketEvents 全量实现**。`EasyPlaceListener` 拦截原版 `PLAYER_BLOCK_PLACEMENT`，取消包后调 `PlacementHandler.applyPlacementProtocolV3` 解码精确状态，手动复刻 `BlockItem.place` 副作用（setBlock / setPlacedBy / placeSound / shrink / ack）。PacketEvents 类引用**隔离**在 `EasyPlaceBootstrap`（反射加载，`catch(Throwable)` 降级）——见 [`docs/architecture.md`](docs/architecture.md) §5 降级矩阵与 memory「可选依赖类隔离」。运行时需服务器装 packetevents 插件（`softdepend`），未装则优雅跳过、其余通道不受影响。
- **UpdateSuppression**：**省略**。依赖 Mixin 给 `Level`/`LevelChunk` 加接口 + 改 `setBlockState` 副作用，Paper 无 Mixin 无等价。
- **镜像修复**（箱子/铁轨/楼梯 180° 镜像）：**已实现**（粘贴时用）。箱子镜像修复在 `SchematicPlacingUtils` 内联照抄（`fixChestMirror` setting）；铁轨/楼梯靠 `BlockState.mirror()/rotate()` 自身行为（`fixRailRotations`/`fixStairs_mirror` settings，原版靠 Mixin，Paper 降级可能不完美）。
- **潜影盒堆叠**（Tweakeroo `tweakShulkerBoxStacking` 服务端配合）：**不可能实现 + 已删全部代码**。改 NMS 全局方法行为，Paper 无 Mixin 无等价（反射改不了方法返回值；Bukkit 事件在 `maxStackSize=1` 前提下恒失败；设 `MAX_STACK_SIZE` 组件污染序列化）。`TweaksDataProvider` **不下发** `stackingShulkers` 元数据——否则客户端 tweakeroo 据其开客户端堆叠渲染而服务端不配合 → 不一致。

### 4. 1.21.11 关键 NMS 约束（与 VeryMcBot 一致，移植时注意）

- **`CompoundTag`**：`getBoolean/getInt/getString/...` 返回 `Optional`/`OptionalInt`，须用 `getBooleanOr/getIntOr/getStringOr` 或 `.orElse()`；`putXxx` 返回 `void`（非链式）。
- **`FriendlyByteBuf`**：协议体编码的核心类，`writeVarInt`/`writeNbt`/`readNbt` 等；paperweight userdev 可直接引用。
- **`CustomPacketPayload`**：`record Payload(...) implements CustomPacketPayload` + `static Type<Payload> ID` + `static StreamCodec<FriendlyByteBuf, Payload> CODEC`。协议层可近乎照抄（去 Fabric `@Environment` 注解）。
- **`Identifier` / `ResourceLocation`**：`Identifier.fromNamespaceAndPath("servux","hud_metadata")`（= Mojang `ResourceLocation`）。
- **`DiscardedPayload`**：NMS 直发 custom payload 的载体（`new DiscardedPayload(Identifier, byte[])`），JEI / Syncmatica S2C 用。

### 5. 权限系统

- 原版 `me.lucko:fabric-permissions-api` → `framework.permission.Perms.check(player, node, level)`：显式设置以设置值为准；`level<=0` 全员放行；否则按 op 判断。
- **真实权限节点**（`src/main/resources/plugin.yml`）：

| 节点 | default | 用途 |
|---|---|---|
| `servux.commands`（根）+ `.reload/.save/.set/.info/.list/.enable/.disable/.debug/.litematic` | op | `/servux`（search 复用 .list；旧 `servux.command` 经 plugin.yml children 映射兼容） |
| `jei.command` | op | `/jei status\|enable\|disable` |
| `syncmatica.command` | true | `/syncmatica` 基础命令 |
| `syncmatica.command.admin` | op | `/syncmatica save\|reload\|enable\|disable\|status` |
| `syncmatica.command.load` | true | `/syncmatica load` |
| `syncmatica.command.load_each` | true | `/syncmatica load <file>` |
| `syncmatica.command.debug` | op | `/syncmatica debug` |
| `carpet.command` | op | `/carpet` 规则壳（运行时另受 `carpetCommandPermissionLevel` 规则动态门禁——ops/2/4 级 NMS 判级） |
| —（无节点） | — | `/player` `/log` `/distance` `/info` `/perimeterinfo` `/draw` `/spawn` 无 Bukkit 节点——Paper Brigadier 注册，门禁 = 各自规则（commandPlayer/commandLog/commandXxx，动态生效） |

- 各 Provider 内部权限节点保持原版命名（如 `servux.provider.hud_data` / `.weather` / `.seed` / `.logger` / `.paste`）。

### 6. 投影粘贴 / 文件投递 —— 已实现（Servux schematic 子系统）

Litematica 投影子系统（`mod/servux/schematic/`，约 8000 行）已移植完成并实测通过：

- **粘贴**（C2S）：客户端上传 `.litematic` → `ServuxLitematicaHandler` 经 `PacketSplitter.receive` 重组 → 重组完成后无条件走 `LitematicsDataProvider.handleClientPasteRequest`（Task 门控在 Provider 内，非 `LitematicaPaste` 静默忽略）→ `SchematicPlacement.pasteTo` → `SchematicPlacingUtils.placeToWorldWithinChunk`（真实 `setBlock` + 方块实体 + 实体放置，含 ReplaceMode / PasteLayerBehavior / LayerRange）。需创造模式 + paste 权限。
- **文件投递**（S2C）：`/servux litematic transmit <file> [player]` 加载 `schematics/*.litematic` 通过 `servux:litematics` 通道投递给客户端（文件名来自 op 命令参数非网络输入，命令入口有目录包含性守卫 `resolveContained`；**修复版 litematica 客户端 ≥0.26.11 对 Transmit 帧双向静默丢弃——仅旧版客户端可接收**，该线 `currentSlice` 修复仍存在且禁止随模板回退）。
- **文件接收**（C2S，**已移除 2026-10·安全修复**）：客户端可控 `FileName` 直达 `Path.of`/`dir.resolve` 无包含性检查，构成**路径穿越任意写/删/读回**原语（上游公告漏洞同源；另有 `new Slice[客户端控长]` OOM 向量）——`Litematic-Transmit*` 分流 + `receiveFileTransmit` + `SchematicBufferManager` 整链物理删除（`SchematicBuffer` 保留仅作 `BUFFER_SIZE` S2C 分片常量载体），重组完成后无条件走 `handleClientPasteRequest`（上游 0.9.5 `:153` 同构），恢复走 git revert。
- 历史移植蓝图冻结于 [`docs/archive/11-schematic-migration-plan.md`](docs/archive/11-schematic-migration-plan.md)；as-built 见 [`docs/servux/schematic.md`](docs/servux/schematic.md)。

**移植方法**：照抄原版纯算法（BitArray/Palette/Container/几何/transmit）+ NMS 直连（`BlockState`/`CompoundTag`/`NbtIo`/`ServerLevel`）；仅 3 类强制降级——`SchematicConversionMaps`（DataFixer，`readFromNBT(enableFixers=false)` 守卫下零影响）、`IMixinWorldTickScheduler`（保存投影读 tick，粘贴不需要）、`WorldUtils`（Mixin → no-op，靠 `setBlock` 的 flags 控制邻居更新）。`LitematicaSchematic` 因 `selection↔placement↔schematic↔PositionUtils` 四元循环依赖，用**桩版**（移除引用未移植类的方法 + 准确注释）分阶段引入、逐步回填。

**实战教训（维护必读）**：

1. **协议字段语义必须对照客户端源码确认，不能只看服务端瞎猜客户端行为。** 例：`sendTransmitFile` 的 `Slice` 字段 servux 原版写 `totalSlices`（总片数），但 litematica `SchematicBuffer.receiveSlice` 要求 `number ∈ [0, totalSlices)`——写 `totalSlices` 必然越界被丢弃。读了 litematica 源码才定位。
2. **原版里未被调用的公开 API 可能是含 bug 的死代码。** `sendTransmitFile` 在原版无调用点，其 `Slice=totalSlices` bug 从未触发；照抄会原样继承。凡照抄「原版无调用点的方法」，务必对照客户端验证字段语义。
3. **PacketSplitter 连续流不会串台**（源码 + 实测确认）：malilib `PacketSplitter.receive` 收齐即 `READING_SESSIONS.remove(key)`，litematica `ServuxLitematicaHandler` 每流新生成 readingSessionKey、收齐重置——连续多个独立分包流各自独立重组，**不需要**分 tick / 延迟发送这类 workaround。
4. **SLF4J → JUL logger 适配**：原版 `Servux.LOGGER.warn/error/info("...{}...", args)`（SLF4J 占位符）→ Paper JUL 不支持 `{}` 多参重载，用 `mod/servux/util/Log.java` shim 机械替换。
5. **可选依赖类隔离**：PacketEvents 等 `compileOnly`/`softdepend` 依赖，其类引用必须隔离到独立引导类（`EasyPlaceBootstrap`），用反射加载 + `catch(Throwable)`——`try/catch` 抓不到方法解析阶段的类加载失败。

---

## 维护与升级要点

- **新增一个 Provider**（servux）：在 `dataproviders/` 加类（`extends DataProviderBase`），在 `network/` 加对应 Handler+Packet（通道编解码 + 字节布局），在 `ServuxReference` 加通道常量，在 `ServuxModule.onRegister` 登记。详见 [`docs/servux/providers.md`](docs/servux/providers.md)。
- **新增一个协议 mod**：在 `mod/<newmod>/` 实现 `ModModule`（或自管装配如 syncmatica），在 `VeryMcProto.onEnable` 注册，在 `plugin.yml` 加命令/权限。
- **发版**（版本内更新）：在所在开发线（`dev` 或 `ver/<X>-dev`）`gradle.properties` 的 `buildNumber` +1 → 提交 → 合入对应发布线（`main` 或 `ver/<X>`）→ `./gradlew build` → tag `v<版本>`。
- **升级 Minecraft 版本**（顺应上游）：先从 `main` 冻结旧版本（切 `ver/<旧版本>` + `ver/<旧版本>-dev` 对）→ 再在 `dev` 上改 `gradle.properties` 的 `mcVersion` + `build.gradle.kts` 的 dev bundle → 重跑 paperweight → 按 [`docs/architecture.md`](docs/architecture.md) §3.5 反射点清单逐一核对 NMS 字段/方法签名漂移（尤其 `FriendlyByteBuf`、`CustomPacketPayload`、`CompoundTag` Optional 化、`StructureStart.createTag` 签名、`DiscardedPayload` 构造）。
- **旧版本修 Bug**：在 `ver/<X>-dev` 提交 → 合入 `ver/<X>` 出包；若 `dev`（新版本）同样存在该 Bug，cherry-pick 回 `dev`。
- **参考源码**（`OriginImpl/` 下，逐行对照的权威实现；**遇到分歧以真实源码为准**；本地目录已 gitignore，不入库）：
  - **servux**：`OriginImpl/servux-LTS-1.21.11/`——服务端协议实现。
  - **litematica / malilib**（masa 客户端，**协议的接收端**）：`OriginImpl/litematica-LTS-1.21.11/`、`OriginImpl/malilib-LTS-1.21.11/`。任何协议字段语义、分包重组、Task 分派都要回来对照客户端源码确认，**不要凭服务端代码猜客户端行为**（见 §6 教训 1）。
  - **syncmatica**：`OriginImpl/syncmatica-LTS-1.21.11/`。
  - **carpet**：`OriginImpl/fabric-carpet-1.21.11/`（gnembon/fabric-carpet 分支 `1.21.11`，mod_version `1.4.194`）——Carpet 模块唯一对照；协议/规则/命令/日志层的字段语义以该树为准（移植范围已终局定案，as-built 见本线 docs/carpet/architecture.md）。
  - **JEI**（本线权威）：`OriginImpl/JustEnoughItems-1.21.11/`（mezz 分支 `1.21.11`，clone 命令 `git clone --depth 1 --branch 1.21.11 https://github.com/mezz/JustEnoughItems.git`——协议权威文件：`Common/src/main/java/mezz/jei/common/network/packets/*` + `common/transfer/*` + `common/util/ServerCommandUtil.java` + `fabric/config/ServerConfig.java`）。`fabric:recipe_sync` wire 真权威 = Fabric API 分支 `1.21.11`（`OriginImpl/fabric-1.21.11/` 的 `fabric-recipe-api-v1`）。`OriginImpl/JEIRecipeBridge-1.21.11/`（Mrbysco）保留作 neoforge:recipe_content 层 wire 参考 + 本线旧桥历史。**jei 模块跨线 cherry-pick 禁止，一律手工重写**（本线 2026-10 迁移实录见 docs/jei/protocol.md §8）。
  - **注意**：原版里**未被调用的公开 API**（如 `LitematicaSchematic.sendTransmitFile`）可能是**未经验证的死代码**、含字段语义 bug——照抄后必须对照客户端源码验证（见 §6 教训 2）。

---

## 文档地图

| 文档 | 内容 |
|---|---|
| [`docs/index.md`](docs/index.md) | 文档总索引 + 约定 + 历史锚点映射表 + 推荐阅读路线 |
| [`docs/architecture.md`](docs/architecture.md) | **全局架构与迁移参考**（01/06/07 + 04/09 活内容蒸馏归并）：分层/框架契约、网络框架与 S2C 投递路径（字节限制唯一权威 §3）、生命周期、**Mixin→Paper 处置矩阵（唯一权威 §5）**、Fabric↔Paper 逐域对照（§6）、构建工具链（reobf 基线 §7）、**NMS 坑清单（§3.6）**、防御要点（§8） |
| [`docs/servux/protocol.md`](docs/servux/protocol.md) | **五通道 wire 规范 + §9 1.21.11 wire 真值（权威）**：Payload 模型、PacketSplitter 分片（128MB 重组预检）、协议版本全表/MOD_STRING paper 形态/逐通道差异 |
| [`docs/servux/providers.md`](docs/servux/providers.md) | 5 个 Provider 的协议数据内容 + 数据采集（含 loggers TPS/MobCap）+ 设置语义 |
| [`docs/servux/schematic.md`](docs/servux/schematic.md) | Litematica 投影系统：压缩/传输/序列化/几何/粘贴（同步直放 + 镜像修复内联） |
| [`docs/servux/testing.md`](docs/servux/testing.md) | **Servux 客户端兼容测试**：5 通道↔3 mod 映射、测试步骤、排错流程 |
| [`docs/syncmatica/architecture.md`](docs/syncmatica/architecture.md) | 实际架构 + **与 Servux 本质差异对比表** + Exchange 会话模型 + **§10 Mixin→Bukkit 迁移记录（含命门清单）** |
| [`docs/syncmatica/protocol.md`](docs/syncmatica/protocol.md) | 单通道 `[Identifier][body]` 包体、18 PacketType、Feature 协商、Exchange 状态机、stop-and-wait 分片 |
| [`docs/syncmatica/testing.md`](docs/syncmatica/testing.md) | **Syncmatica 客户端兼容测试**：握手/分享/下载/修改/持久化/多玩家步骤 + 排错 |
| [`docs/jei/protocol.md`](docs/jei/protocol.md) | **JEI 完整协议 + 2026-10 迁移实录**：三层全景、通道声明契约、wire 逐字段、cheat 权限、配方转移、进服时序整形、实机验证清单 |
| [`docs/carpet/architecture.md`](docs/carpet/architecture.md) | **Carpet 本线自含 as-built**：模块装配与规则框架（18 规则 + carpet.conf）、假人 /player 全族（ActionPack 三调用点 1.21.11 形态）、/log 日志与跨会话持久、五命令族、**本线适配记录（掉落伤害 per-line 决策）与验收** |
| [`docs/carpet/protocol.md`](docs/carpet/protocol.md) | **carpet:hello 协议 as-built**（HI="1.4.194"）：通道与载体、消息面全表、握手时序与名册、规则同步链 |
| [`docs/operations.md`](docs/operations.md) | **运维参考（docs 内唯一权威，原 README §6–§12 运维手册迁入）**：版本字串策略（§0）、四 mod 命令、权限节点（含 Perms 语义 + LuckPerms）、配置全键（含 carpet.conf/carpet-logs.json）、数据布局、排错速查、升级工作法（§6） |
| [`docs/references.md`](docs/references.md) | 参考资源链接 |
| `docs/archive/` | 冻结归档件 14 件（04/06/08/09/11/22/23/TECH_DEBT + research/×6；清单与冻结约定唯一权威 = `docs/archive/README.md`） |

---

## 参考资源

- 仓库远端：https://github.com/Kevin-O-Hsu/VeryMcProto
- Paper 开发文档：https://docs.papermc.io/paper/dev/
- Paper 插件消息通道（plugin messaging）：https://docs.papermc.io/paper/dev/plugin-messaging/
- PaperWeight 指南：https://github.com/PaperMC/paperweight
- Minecraft Protocol Wiki：https://wiki.vg/Protocol （`Custom Payload` 包结构）
- Fabric 网络文档：https://docs.fabricmc.net/develop/networking
- FabricMC Discussion #4430（Spigot/Paper ↔ Fabric 自定义通道实证）：https://github.com/orgs/FabricMC/discussions/4430
- 上游源码（本仓库对照）：servux/litematica/malilib/syncmatica/JustEnoughItems-1.21.11/fabric-1.21.11 均在 `OriginImpl/` 下
- 姊妹项目 VeryMcBot（paperweight userdev + NMS 反射范式参考）：`I:\Programming\VeryMcBot`

**开发环境**：IntelliJ IDEA + Minecraft Dev SDK + Gradle + PaperWeight。
