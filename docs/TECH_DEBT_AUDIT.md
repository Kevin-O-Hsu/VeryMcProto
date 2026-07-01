# Tech Debt Audit — VeryMcProto

生成时间：2026-07-02
审计范围：`src/main/**`（147 个 Java 文件 / 25216 行）；`OriginImpl/` 为对照参考源码，不审计
审计基线：commit `0df199b`（main，clean）

---

## 执行摘要

- **Critical：0** · **High：2** · **Medium：7** · **Low：9**（共 18 条实质发现；宁缺毋滥，未凑数到 30）
- **最大债务集中地**：分层承诺与现实不符——`framework/` 声称与 `mod/` 解耦，实际 6 处直接硬耦合 `mod.servux.ServuxDebug`（F001）。这是当前最该修的架构债。
- **测试债务是第二大债**：25216 行协议代码、复杂字节布局 + Feature 条件编解码 + 分片重组，**零自动化测试**（F002），仅靠手动真机验证（docs/10、docs/24）。
- **两份 ~95% 重复的 `JsonUtils`**（F004），且都标榜"移植自原版"——一份在 framework、一份在 servux，servux 的 4 个 selection 类不用 framework 版。
- **可观测性黑洞**：`HudDataProvider` 三处反射式数据采集吞异常且**无任何日志**（F005），数据静默丢失，出问题无法定位。
- **文档/实现漂移**：CLAUDE.md 的"framework 与 mod 解耦"与"通用 JsonUtils"两项声称被现实推翻（F001、F004）。
- **死常量 + 上限错配**：`PacketSplitter` 的 C2S 常量全是死代码，C2S 上传实际走 64MB（S2C）上限（F006）。
- **整体代码质量高于同规模平均水平**：注释密度高、防御性强、降级点全部有注释佐证、无 `System.out`/`printStackTrace` 残留、无硬编码密钥、依赖极简（2 个）。多数 catch 是有意防御，非草率。债务集中在"架构承诺未兑现"和"无测试"，而非代码脏乱。

---

## 架构心智模型

VeryMcProto 是一个**协议层移植**项目：把三个 Fabric 端"协议 Mod"（Servux / JEI Recipe Bridge / Syncmatica）期待的**网络协议 + 服务端数据采集**，以纯 Paper 插件形式复刻，使"Fabric 客户端 + Paper 服务端"等价于"Fabric 客户端 + 原版 Fabric 服务端 Mod"。

代码分两层：`framework/`（基础设施：网络通道封装、Provider 注册表、生命周期桥、反射、调试引擎）与 `mod/<modid>/`（三个被移植的协议）。三个 mod 性质不同：**Servux** 是单向 S2C 广播（6 Provider + schematic 子系统，最大）；**JEI Recipe Bridge** 是一次性 S2C 配方同步（最简单）；**Syncmatica** 是双向有状态 Exchange 会话（最复杂，自管通道，不走 DataProviderManager）。

**模型与 README/CLAUDE.md 的冲突**：文档反复强调"`framework/` 与具体协议 mod 解耦，新增 mod 只需在 `mod/` 下实现并注册，无需改动框架"。但审计发现 framework 的网络层、事件层、权限层、数据层共 6 个文件直接 `import mod.servux.ServuxDebug`，且 `DataProviderManager` 硬编码 `"servux_main"` / `"servux.json"`。事实上 framework 只与 servux 真正解耦于 JEI/Syncmatica——后者压根不用 DataProviderManager/LifecycleBridge 的 Provider 调度。这条裂缝是当前最值得修的债。

---

## Findings

| ID | Category | File:Line | Severity | Effort | Description | Recommendation |
|----|----------|-----------|----------|--------|-------------|----------------|
| F001 | 架构腐化（分层违规） | `framework/event/LifecycleBridge.java:20`、`framework/network/ProtocolChannel.java:15`、`framework/network/ServerPlayHandler.java:8`、`framework/network/IPluginServerPlayHandler.java:12`、`framework/permission/Perms.java:7`、`framework/dataproviders/DataProviderManager.java:20` | High | M | framework 层 6 处 `import verymc.top.veryMcProto.mod.servux.ServuxDebug`，违背"framework 与 mod 解耦"承诺。`framework/debug/DebugSystem` 本是通用引擎（servux/syncmatica 各持实例），framework 自己却不用它，反而硬绑 servux 实例 → framework 网络日志永远只能由 `/servux debug` 控制。 | 给 `DebugSystem` 加一个 `framework` 实例（或让 ProtocolChannel 等持有可注入的 `DebugSystem<?>`），framework 层日志走该实例；6 处 import 改为 framework 自有句柄。 |
| F002 | 测试债务 | 全仓库 | High | L | **0 个自动化测试**（`find` 无任何 `*Test*.java`）。协议字节布局、`CommunicationManager.putMetaData`/`receiveMetaData` 的 Feature 条件编解码、`PacketSplitter.ReadingSession` 重组、`SchematicPlacingUtils` 几何均为纯函数，极适合单测；目前全靠手动真机（docs/10、docs/24）。回归风险随版本升级（NMS 签名漂移）显著放大。 | 至少为 `PacketSplitter`（分片→重组 round-trip）、`CommunicationManager` metadata 编解码、`PlacementHandler.applyPlacementProtocolV3` 加 JUnit 单测；这些不依赖 NMS 运行时。 |
| F003 | 架构腐化（命名/职责） | `framework/dataproviders/DataProviderManager.java:281,293,365` | Medium | M | 该类号称"通用 Provider 注册表/调度器/配置中枢"，实则 servux 专属：`getConfigFile()` 硬编码返回 `servux.json`；`readFromConfig` 硬编码 `"servux_main"` 永不禁用、`"debug_data"` 默认关。JEI 与 Syncmatica 都不用它（JeiConfiguration 自管、SyncmaticaModule 自管）。"通用"是名不副实。 | 要么承认它是 servux 专属并改名/下沉到 `mod/servux/`；要么把配置文件名、必启 provider 名做成构造参数，真正泛化。当前是"半成品抽象"。 |
| F004 | 一致性腐化（重复代码） | `framework/util/JsonUtils.java`（505 行）vs `mod/servux/util/JsonUtils.java`（483 行） | Medium | S | 两份 ~95% 相同的 `JsonUtils`：相同的 `hasX/getXOrDefault/blockPosFromJson/deepCopy/parseJsonFileAsPath/writeJsonToFileAsPath`，framework 版仅多一个 `aabbToJson`。servux 的 4 个 selection 类（`AreaSelection`、`AreaSelectionSimple`、`Box`、`SelectionManager`）坚持用 servux 版，framework 版只被 `DataProviderManager` + `JeiConfiguration` 用。 | 删 `mod/servux/util/JsonUtils.java`，把 `aabbToJson` 合进 framework 版，4 个 selection 类改 import。两份并存只会让未来修复（如原子落盘 bug）要改两处。 |
| F005 | 可观测性（静默吞异常） | `mod/servux/dataproviders/HudDataProvider.java:236,546,638` | Medium | S | 三处反射式数据采集（天气 :236、spawn :546、onConfigLoaded spawn 同步 :638）`catch (Exception ignored) {}` 且**完全无日志**。对比 `LifecycleBridge` 同类 catch 都至少 `Reference.logger().warning(...)`。NMS 版本漂移导致字段缺失时，HUD 数据静默退化，出问题零线索。 | 三处补 `ServuxDebug.log(Cat.HUD, ...)` 或至少 `Reference.logger().warning(...)`；反射失败应可见。 |
| F006 | 文档漂移 + 死常量 + 上限错配 | `framework/network/PacketSplitter.java:32,34,78` | Medium | S | `DEFAULT_MAX_RECEIVE_SIZE_C2S`（16MB）与 `MAX_PAYLOAD_PER_PACKET_C2S` 全代码库零引用（死常量）。`receive(handler,key,buf)` 默认硬编码用 `DEFAULT_MAX_RECEIVE_SIZE_S2C`（64MB），**C2S 上传路径（`ServuxLitematicaHandler:99` 接收客户端投影）也走 64MB**——恶意客户端可触发服务端 64MB 缓冲分配。注释称"C2S 分片常量"与实现矛盾。 | 要么删 C2S 死常量、要么让 `receive` 按通道方向选上限；C2S 上传建议用更紧的上限（16MB 或更小），与 docs/09 字节限制命门对齐。 |
| F007 | 并发（共享可变集合） | `mod/syncmatica/communication/CommunicationManager.java:42-45,54-56`、`ServerCommunicationManager.java:38,41` | Medium | M | `broadcastTargets`/`downloadState`/`modifyState`/`targets`/`downloadingFile` 均为 `ArrayList`/`HashMap`，作为跨方法共享状态。`onPacket` 迭代 `source.getExchanges()`（ArrayList），同时 `startExchangeUnchecked` 会 `add`。若 C2S 包处理（netty 线程？）与握手/命令（主线程）不同线程，存在 `ConcurrentModificationException` / 竞态风险。 | 显式确认 C2S 包处理线程；若非单线程，换成 `CopyOnWriteArrayList`/`ConcurrentHashMap`，或在 `onPacket` 迭代时用快照。 |
| F008 | 错误处理（吞关键失败） | `VeryMcProto.java:160` | Medium | S | `onDisable` 里 `catch (Exception ignored) {}` 包裹 `SyncmaticaModule.getInstance().disable()`——该 disable 内部做 `placements.json` shutdown 保存。若保存失败被静默吞，玩家协同修改的投影数据可能丢失且无任何日志。 | 至少 `Reference.logger().warning("syncmatica disable 异常: " + e.getMessage())`；shutdown 持久化失败应可见。 |
| F009 | 一致性腐化（日志风格分裂） | `mod/syncmatica/util/SyncmaticaLog.java` vs servux 全量用 `Reference.logger()` | Low | M | 两套业务日志风格：servux 直接 `Reference.logger().info("..." + x)`（JUL 字符串拼接），syncmatica 用 SLF4J 风格 `SyncmaticaLog.warn("..{}..{}", x)` shim。读代码要在两套心智间切换。 | 选一种统一。若保留 shim，servux 也应能用；否则 syncmatica 收敛到直接调 `Reference.logger()`。（CLAUDE.md 教训 4 已为 servux 选了 JUL，syncmatica 没遵循。） |
| F010 | 一致性腐化（单例命名） | `ChannelManager.java:21`（`instance()`）vs `DataProviderManager.java:37` 等 7 处（`INSTANCE`） | Low | S | 单例访问风格不统一：`ChannelManager.instance()` 用方法，其余 7 处用 `public static final XXX INSTANCE` 字段。 | 统一为一种（项目主流是 `INSTANCE` 字段，改 `ChannelManager` 即可）。 |
| F011 | 一致性腐化（控制流风格） | `mod/syncmatica/communication/ServerCommunicationManager.java:161-256` | Low | S | `handle(...)` 用 4 个 `if (type.equals(PacketType.X))` 链（PacketType 是 enum，应用 `switch`）；且 `REMOVE_SYNCMATIC`/`MODIFY_REQUEST` 末尾缺 `return`（靠逻辑互斥保安全，风格不一致）。 | 改 `switch(type)`，每分支显式 `return`。 |
| F012 | 脆弱代码（依赖重载决议） | `mod/syncmatica/communication/ExchangeTarget.java:148` | Low | S | `SyncmaticaLog.error("...partner={} bytes={}", e, persistentName, bytes.length)`——靠 Java vararg+Throwable 重载决议**巧合**命中 `error(String, Throwable, Object...)` 签名（行为正确：`e` 作 throwable、其余填占位符）。但意图极度不透明，任何人调整参数顺序即破。 | 显式：`SyncmaticaLog.error("...partner=" + persistentName + " bytes=" + bytes.length, e);`（用带 Throwable 的二参重载）。 |
| F013 | 文档漂移（误导注释） | `mod/syncmatica/communication/CommunicationManager.java:249` | Low | S | `// forgot a negation here` 照抄原版作者笔记，但下方 `if (!isReadyForDownload()) throw "...is not ready..."` 逻辑实际正确。注释反向误导后来者。 | 删该注释或改为"逻辑正确：!ready → throw"。 |
| F014 | 配置（潜在不兼容） | `gradle.properties`（`org.gradle.configuration-cache=true`）+ `build.gradle.kts:3`（paperweight `2.0.0-beta.21`） | Low | S | configuration-cache 开启 + beta 版 paperweight，是已知的兼容性雷区（beta 插件常未声明与 CC 兼容）。构建偶发 NPE 时难以定位根因。 | 若未实测 CC 增益，关掉 `configuration-cache` 直到 paperweight 出正式版；或保留但在升级 paperweight 时优先验证。 |
| F015 | 类型契约（信任边界） | `CommunicationManager.java:224-225,143-144`、`receivePositionData:238-239` | Low | S | `rotOrdinals[buf.readInt()]` / `mirOrdinals[buf.readInt()]` 直接用客户端 int 索引 enum 数组，恶意客户端传越界值抛 `ArrayIndexOutOfBoundsException`（被外层 `ProtocolChannel`/`onPacket` 的 try 吞）。非致命，但缺前置范围校验。 | 索引前 `Math.floorMod(v, rotOrdinals.length)` 或显式范围检查 + 丢弃非法包。 |
| F016 | 性能（C2S 大缓冲面） | `framework/network/PacketSplitter.java:127` | Low | S | `ReadingSession` 收首片时按客户端声明的 `expectedSize` 直接 `Unpooled.buffer(expectedSize)` 分配（虽有 maxLength 上限 64MB，见 F006）。多个并发未完成 session 可累积分配。 | 除 maxLength 外，加"每玩家未完成 session 数上限"或"全局未完成 session 数上限"。 |
| F017 | 依赖（无 CVE/版本治理） | `build.gradle.kts:18-27` | Low | S | 仅 2 个依赖（paperDevBundle + packetevents-spigot 2.13.0），无 sprawl，但无 CVE 扫描机制（Java/Gradle 无原生 `npm audit`）。packetevents 锁 2.13.0（对照源码 2.13.1）。 | 可选：加 `org.owasp:dependency-check-gradle` 或定期跑 `gradle dependencyUpdates`。低优先。 |
| F018 | 一致性腐化（持久化三套） | servux `servux.json`（framework JsonUtils）/ jei `jei-recipe-bridge.json`（framework JsonUtils）/ syncmatica `placements.json`（`SyncmaticaUtil.backupAndReplace`） | Low | M | 三 mod 三套 JSON 落盘，两套原子策略（framework `JsonUtils.writeJsonToFileAsPath` 用 tmp+move；syncmatica 用 `backupAndReplace`）。功能都对，但维护两套原子写逻辑。 | 低优先。若收敛，syncmatica 可改用 framework `JsonUtils.writeJsonToFileAsPath`（已具备 tmp+move）。 |

---

## Top 5 ——「如果只修五件事」

### 1. F001 —— 解开 framework→servux 的 6 处分层违规
**为什么先修**：这是 CLAUDE.md 反复声称却未兑现的核心架构承诺。每多写一行 framework 代码依赖 `ServuxDebug`，债就加深一层；它阻塞了"真正新增一个不依赖 servux 的协议 mod"这条路。
**修法草案**：
- 在 `framework/debug/` 加 `FrameworkDebug`（一个 `DebugSystem<FrameworkCat>` 实例，或直接复用一个通用 tag）。
- 把 `ProtocolChannel:56,96,107,154,160,179` 等 `ServuxDebug.log(ServuxDebug.Cat.NETWORK, ...)` 换成 `FrameworkDebug.log(...)`（或注入的 `DebugSystem<?>`）。
- `LifecycleBridge`/`Perms`/`DataProviderManager`/`ServerPlayHandler`/`IPluginServerPlayHandler` 同理。
- 验证：`grep -r "import verymc.top.veryMcProto.mod" framework/` 应为空。

### 2. F002 —— 给纯函数补单测
**为什么先修**：协议移植的正确性全靠字节布局，一次 NMS 版本升级就可能默默打破；无测试等于每次升级都靠玩家进服手动验证。
**修法草案**：新建 `src/test/java/`，加 Gradle `test` 任务（`java-library` 已带）。优先覆盖：
- `PacketSplitterTest`：构造 N 字节 buf → `send` 模拟分片 → 喂回 `receive` → 断言 round-trip 字节相等（覆盖跨片边界、单片、超大 expectedSize 拒绝）。
- `MetadataCodecTest`：`putMetaData`/`receiveMetaData` 对 FeatureSet 不同组合的 round-trip（DISPLAY_NAME / CORE_EX / VERSION 开关）。
- `PlacementHandlerTest`：`applyPlacementProtocolV3` 解码协议值 → 方块状态。
这些类不触达 NMS 运行时，纯 JVM 可跑。

### 3. F004 —— 删掉重复的 `JsonUtils`
**为什么先修**：最干净的 Quick win，零风险、立即消除"改一处忘改另一处"的隐患。
**修法草案**：把 `aabbToJson` 从 framework 版保留（它是 framework 版独有），删 `mod/servux/util/JsonUtils.java`，4 个 selection 类的 `import verymc.top.veryMcProto.mod.servux.util.JsonUtils` 改为 `framework.util.JsonUtils`。

### 4. F005 + F008 —— 把两个静默黑洞补上日志
**为什么先修**：可观测性债是隐性最痛的——出问题时连"哪里坏了"都看不到。
**修法草案**：
- `HudDataProvider.java:236,546,638`：`catch (Exception ignored) {}` → `catch (Exception e) { ServuxDebug.log(ServuxDebug.Cat.HUD, "采集失败: " + e.getMessage()); }`。
- `VeryMcProto.java:160`：`catch (Exception ignored) {}` → `catch (Exception e) { Reference.logger().warning("syncmatica disable 异常: " + e.getMessage()); }`。

### 5. F006 —— 修 PacketSplitter 的死常量与 C2S 上限
**为什么先修**：文档（注释）与现实背离，C2S 上传路径用 64MB 上限是真实的 DoS 面（虽小）。
**修法草案**：`receive` 增加 `direction` 参数或新增 `receiveC2S` 重载用 16MB（或更小）；删 `MAX_PAYLOAD_PER_PACKET_C2S` 死常量或在 send 路径真正区分方向；更新 PacketSplitter 类注释。

---

## Quick wins（低工作量 × 中/高 severity）

- [ ] **F004**：删 `mod/servux/util/JsonUtils.java`，4 处 import 改 framework 版（~10 分钟，零风险）
- [ ] **F005**：`HudDataProvider` 三处补 debug log（~5 分钟）
- [ ] **F008**：`VeryMcProto.onDisable:160` 吞异常补 warning（~2 分钟）
- [ ] **F013**：删 `CommunicationManager.java:249` 误导注释（~1 分钟）
- [ ] **F012**：`ExchangeTarget.java:148` 日志参数显式化（~3 分钟）
- [ ] **F010**：`ChannelManager.instance()` → `INSTANCE` 字段统一（~5 分钟 + 改 3 处调用点）
- [ ] **F015**：`CommunicationManager` enum ordinal 索引加 `floorMod` 防越界（~5 分钟）

---

## 看着糟实则 OK（曾考虑 flag、经核查放弃）

- **`LitematicaSchematic.java`（1370 行 / 93 方法）与 `PositionUtils.java`（1297 行 / 82 方法）god files**：纯算法/几何，逐行照抄原版 masa `litematica`/`malilib`。拆分会偏离原版、丧失"逐行对照"的核心维护价值（CLAUDE.md 强调以 OriginImpl 为权威）。**保留。**
- **两份 `JsonUtils` 里 19 处 `catch (Exception ignore) {}`**：是 type-narrowing 模式（试 `getAsInt()`/`getAsBoolean()` 失败即返回默认值），照抄原版 `fi.dy.masa.malilib` 风格，非草率吞异常。**保留。**
- **`EasyPlaceListener.java:150,178,189` 三处吞异常**（`setPlacedBy`/`ack`/`playPlaceSound`）：每处都有相邻注释说明为何防御——BE 初始化、ACK、音效失败都不应回滚已成功的方块放置。**保留。**
- **`WorldUtils.java` 全 no-op + `LitematicaSchematic.java:1025,1130-1148` DataFixer 空实现**：CLAUDE.md §6 明确记录 `enableFixers=false` 守卫下零影响、靠 `setBlock` flags 控制更新。降级有据。**保留。**
- **`EntitiesDataProvider` `fixAllayGathering` / `TweaksDataProvider` 潜影盒堆叠 setting 保留却不真生效**：CLAUDE.md §3 明确记录——潜影盒堆叠"不可能实现 + 已删全部代码"，setting 留壳是为协议兼容；`fixAllayGathering` 是 Mixin 改行为类，Paper 无等价。**保留。**
- **`ChannelManager.java:87` / `LifecycleBridge.java:70` 吞异常**：分别是 `unregisterAll` 卸载循环（一个通道失败继续卸其他）与 `tickTask.cancel()`（已取消/未调度时 `IllegalStateException`）。卸载/取消路径的防御性吞异常。**保留。**
- **`Reflect.get/set` 的 `@SuppressWarnings("unchecked")` 泛型 cast**：反射 API 天然返回 `Object`，调用方按类型直收，是反射范式不可避免。**保留。**
- **三 mod 各持独立 `SchematicMetadata`（servux/schematic vs syncmatica/data/litematica）**：来自两个独立原版 mod（masa litematica vs endte syncmatica），字段集合不同，分别对照各自原版是合理的——**除非确认字段高度重叠才值得抽公共**（见开放问题）。
- **`Reflect.findField`/`method` 的 `NoSuchFieldException ignored`**：找不到就沿父类链继续，找不到最终抛 `IllegalStateException`，是遍历循环的正常控制流。**保留。**

---

## 给维护者的开放问题

1. **framework→servux 耦合是有意还是滑落？** F001 的 6 处 import 是一开始就这么写、还是从"servux 独占"演进到"多 mod"时漏掉的？若短期内不会引入"非 servux 的 framework 消费者"，F001 可降优先级；若计划扩 mod，应尽早解。
2. **`CommunicationManager` 的集合是否在单线程假设下安全？** F007 取决于 Paper 下 `syncmatica:main` 的 C2S 包回调线程（主线程 or netty）。若确认恒为主线程，F007 可关；若不确定，值得抓一次堆栈验证。
3. **两份 `SchematicMetadata` 字段重叠度多高？** 若 servux 版（365 行）与 syncmatica 版（499 行）大部分字段同名同义，可抽公共基类；若语义实际不同（投影元数据 vs 共享仓库元数据），保持独立。需要你按字段对照判定。
4. **`DataProviderManager` 是否计划泛化给未来 mod？** 若是，F003 值得做（参数化配置文件名/必启项）；若 servux 是唯一消费者，直接下沉到 `mod/servux/` 更诚实。
5. **`configuration-cache` + beta paperweight 实际构建是否稳定？** F014 是否给你带来过偶发构建失败？若没有，可忽略。

---

*本审计为静态审查，非安全审计；未运行渗透或威胁建模。业务逻辑正确性（协议字段语义）需结合 docs/10、docs/24 的真机测试覆盖，非本次范围。*

---

## 修复日志（2026-07-02 完成）

本审计 18 项发现处理结果：**13 项实修**（不改业务行为，逐项独立提交）+ **5 项评估保留**（附理由）。修复原则：表面行为完全不变（日志/注释/纯重构/删死代码/解耦）；每个 finding 一个独立 commit，可单独 revert。

### 一、实修清单（13 项，按提交时间序）

| Finding | Commit | Severity | 修复方式 | 验证 |
|---------|--------|----------|----------|------|
| F013 | `2eeeab5` | Low | 删 `CommunicationManager.download` 的误导注释 `forgot a negation here`（逻辑实际正确），改说明性注释 | 注释 |
| F012 | `6810b41` | Low | `ExchangeTarget.sendViaNms` 的 `error` 调用显式走 `(String, Throwable)` 二参重载，不再靠 vararg 重载决议巧合命中 | 编译，行为等价 |
| F005 | `6576041` | Medium | `HudDataProvider` 三处静默 `catch{}` 补 debug log（pollWeather/updateSpawnFromServer/onConfigLoaded，归 Cat.TICK/HANDSHAKE） | 编译 |
| F008 | `300a2e2` | Medium | `onDisable` 的 syncmatica disable catch 补 warning（placements.json shutdown 保存失败可见） | 编译 |
| F010 | `cf7fbc5` | Low | `ChannelManager` 单例 `instance()`→`INSTANCE` 字段，6 处调用点统一 | 编译 |
| F011 | `030d7fa` | Low | `ServerCommunicationManager.handle` 4 个 `if(type.equals)` 链改 `switch` + 每分支显式 `return` + `default` 兜底 | 编译，控制流等价 |
| F015 | `31157b9` | Low | `receivePositionData` 的 enum ordinal 加 `safeRotation`/`safeMirror` 范围校验（越界仍被 ProtocolChannel try 兜底丢弃，表面行为不变） | 编译 |
| F007 | `a5d77b0` | Medium | 6 个共享集合（broadcastTargets/downloadState/modifyState/targets/downloadingFile/ongoingExchanges）换 CopyOnWriteArrayList/ConcurrentHashMap | 编译，功能等价 |
| F004 | `dc539d8` | Medium | 删重复的 `mod/servux/util/JsonUtils`（487 行），4 selection + LayerRange 改用 framework 版（diff 实测业务逻辑完全一致） | 编译 |
| F003 | `44a001d` | Medium | `DataProviderManager` 硬编码 servux.json/servux_main/debug_data 提取为命名常量 + 类注释明确"当前 servux 专属 + 为何不下沉" | 编译 |
| F006 | `2c49222` | Medium | 删 PacketSplitter 的 3 个 C2S 死常量 + 修类注释（C2S 上限保持 64MB 不变）；同步修 CLAUDE.md/docs/02 过时描述 | 编译 |
| F001 | `4eabb18` | High | **注入式解耦**：DebugSystem 加字符串分类重载 + 新建 FrameworkDebug 门面 + ServuxModule 注入 ServuxDebug.SYS + 6 framework 文件改调用。运行时行为完全不变（仍走 /servux debug）；`grep 'import ...mod.' framework/` 为空 | 编译 + grep |
| F002 | `1f655b5` | High | 新增 JUnit 5 测试基础设施 + 3 测试类 13 用例（PacketSplitter 分片 round-trip / FeatureSet 编解码 / LitematicaBitArray bit-pack），全绿 | `./gradlew test` 13/13 |

### 二、评估保留（5 项，附理由）

| Finding | 决定 | 理由 |
|---------|------|------|
| **F009**（日志风格分裂） | 保留 | **审计描述不准**：称"servux 直接 Reference.logger()"，实测 servux 也有 `Log.java` SLF4J→JUL shim（与 `SyncmaticaLog.java` 几乎同构，fmt 实现等价）。两 shim 各自对照 masa（servux）/ endte（syncmatica）原版 LOGGER，命名保留有对照价值。合并需改 19 文件 64 处调用点（servux 20 + syncmatica 44），Low severity 收益不抵风险。 |
| **F014**（configuration-cache + beta paperweight） | 保留 | 关闭 configuration-cache 是改构建行为（性能/缓存），与"不改行为"约束冲突；本环境实测构建稳定（compileJava 增量 1-2s，test 3s，CC 持续命中）。beta paperweight 已在姊妹项目 VeryMcBot 验证。升级正式版时优先复验。 |
| **F016**（C2S 大缓冲面） | 保留（依附 F006） | 加"未完成 session 数上限"=新增拒绝机制=改行为。F006 已保留 64MB 上限不变（避免误伤合法大投影）；DoS 面实际受 Paper 连接数 + 单玩家通道数天然约束。真正收紧需先确认合法投影上限。 |
| **F017**（无 CVE/版本治理） | 保留 | 项目仅 2 个依赖（paperDevBundle + packetevents），依赖极简是设计原则。加 dependency-check 引入外部插件 + 构建时间，与原则冲突。建议手动定期 `./gradlew dependencyUpdates`。低优先。 |
| **F018**（持久化三套） | 保留 | syncmatica placements.json 用 `backupAndReplace`（先备份再写），framework JsonUtils 用 tmp+move。原子写策略语义不同，统一是改落盘实现=改行为（备份逻辑变化），有数据丢失风险。功能都对，保留。低优先。 |

### 三、验证总结

- **编译**：`./gradlew compileJava` 全程 BUILD SUCCESSFUL（13 个修复每个独立验证）。
- **单测**：`./gradlew test` → **13/13 通过**（PacketSplitterTest 3 + LitematicaBitArrayTest 4 + FeatureSetTest 6）。验证 NMS（FriendlyByteBuf）在 test classpath 可用。
- **行为不变**：所有实修均不改变协议/业务行为——日志补全（debug log 范畴）、纯重构（命名/控制流/集合类型/常量提取）、注入式解耦（运行时仍走原实例）、删死代码/重复代码。
- **解耦验证**：`grep -rn "import verymc.top.veryMcProto.mod" src/main/java/verymc/top/veryMcProto/framework/` 为空（F001 达成）。

### 四、对审计本身的修正

1. **F001 定性**：审计称"架构腐化"，但 `ServuxDebug.java` 注释已明确"承认 framework = servux 底层"——是有意设计。本次仍按"真正解耦"修（注入式），让代码层面 framework 不 import mod，同时运行时行为不变。
2. **F009 描述不准**：审计称"servux 直接 Reference.logger()"，实测 servux 有 `Log.java` shim（见保留理由）。已纠正。
3. **F006 docs/02 过时**：docs/02 §5.2 常量块原写 S2C=1MiB（实际 32_000），顺带修正；C2S 死常量描述同步删除。
4. **F002 测试对象调整**：审计草案建议 CommunicationManager metadata（需 mock Bukkit Player，手写 stub 不现实）+ PlacementHandler（需 NMS 方块注册表 Bootstrap，非纯 JVM），改用 FeatureSet（Feature 协商编解码，metadata 的 Feature 条件基础）+ LitematicaBitArray（schematic 字节布局核心），仍覆盖"协议编解码 + 分片重组 + 位级打包"三类纯逻辑。
