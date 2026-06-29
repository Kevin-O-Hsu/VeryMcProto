# 08 · 实施步骤与任务拆解

> 把整个移植拆成**可独立执行的小任务**，标注依赖与验收标准。整体方案见 [07](07-migration-architecture.md)；各任务的技术细节回查 [01](01-servux-architecture.md)–[06](06-fabric-vs-paper.md)。
>
> 建议从阶段 0 → 1 → 2 → 3 顺序推进；阶段 3（HUD 跑通）是**第一个里程碑**——证明协议层可行。之后 4/5/6 可并行或按价值排序。

---

## 阶段总览与依赖

```
阶段0 环境/构建 ─┬─▶ 阶段1 网络层骨架 ─┬─▶ 阶段2 架构层（Manager/事件/配置/命令/反射）─┐
                │                      └─▶ 阶段3 HUD Provider ★里程碑1（协议跑通）◀──────┘
                                                                                        │
                ┌──────── 阶段4 Entities/Tweaks ◀────────────────────────────────────────┤
                │                                                                        │
                ├──────── 阶段5 Structures ◀─────────────────────────────────────────────┤  (可并行)
                │                                                                        │
                └──────── 阶段6 Litematics（含投影系统）★里程碑2 ◀────────────────────────┘
                                                                                         │
                                       阶段7 打磨/降级项（EasyPlace 等）◀───────────────────┘
```

| 阶段 | 名称 | 关键产出 | 里程碑 |
|---|---|---|---|
| 0 | 环境与构建 | paperweight userdev 可编译可 `runServer` | M0：空插件跑起来 |
| 1 | 网络层骨架 | 通道注册 + Payload + PacketSplitter + PluginMessageListener | — |
| 2 | 架构层 | DataProviderManager + 生命周期事件 + 配置 + 命令 + reflect | — |
| 3 | **HUD Provider** | HUD 协议全功能 | **M1：MiniHUD 显示 HUD/出生点/天气/TPS** |
| 4 | Entities/Tweaks | 实体/方块实体 NBT 查询 | — |
| 5 | Structures | 结构边界框 | — |
| 6 | **Litematics** | 投影投递/粘贴/批量实体 | **M2：Litematica 投影互通** |
| 7 | 打磨/降级 | EasyPlace、性能、边界 | M3：发布候选 |

---

## 阶段 0 · 环境与构建（M0）

| # | 任务 | 细节 | 验收 | 参考 |
|---|---|---|---|---|
| 0.1 | 改 `build.gradle.kts` | 引入 `paperweight.userdev` + `run-paper`；`paperDevBundle("1.21.11"...)` | `./gradlew build` 通过 | [07](07-migration-architecture.md) §5；VeryMcBot `build.gradle.kts` |
| 0.2 | 修 `plugin.yml` | `api-version: '1.21'`；补 `name`/`main`/`version` | 加载无警告 | [07](07-migration-architecture.md) §5 |
| 0.3 | 建 `reflect/Reflect` 工具类 | 字段读写 + `MethodHandles`（抄 VeryMcBot） | 单测可读写 NMS 私有字段 | [03](03-dataproviders-detail.md) §3.2 |
| 0.4 | 建 `Reference.java` | `MOD_STRING="servux-paper-1.21.11-x.y.z"`；`getDataFolder()` | 编译通过 | [01](01-servux-architecture.md) §9 |
| 0.5 | 主类 `onEnable` 打印日志 | 验证 `runServer` 启动 | 控制台见日志 | — |

**M0 验收**：`./gradlew runServer` 起服，`/plugins` 见 VeryMcProto 已加载。

---

## 阶段 1 · 网络层骨架

| # | 任务 | 细节 | 验收 | 参考 |
|---|---|---|---|---|
| 1.1 | `PacketSplitter` 移植 | 照抄；S2C 分片常量改 `32000`（方案A）；保留 `ReadingSession` + session key map | 单测：分片→重组还原原 bytes | [02](02-network-protocol.md) §5 |
| 1.2 | `ProtocolChannel` 封装 | `register()`（incoming+outgoing）+ `send(Player, byte[])` + NMS `sendNms`（方案B预留） | 能注册通道、收发 byte[] | [07](07-migration-architecture.md) §2.1 |
| 1.3 | `ChannelManager` | 5 条通道注册表（网络名） | 通道去重注册 | [02](02-network-protocol.md) §2 |
| 1.4 | Payload record 移植 | 抄 `ServuxXxxPacket.Payload`（去 `@Environment`）×5；`toPacket/fromPacket` 照抄 | 编译通过；能用 FriendlyByteBuf 编解码 | [02](02-network-protocol.md) §3 |
| 1.5 | byte[]↔FriendlyByteBuf 桥接 | `Unpooled.wrappedBuffer` 包装收；`buf.array()`/`getBytes` 取发 | 单测往返一致 | [07](07-migration-architecture.md) §2.3 |

**验收**：能注册 `servux:hud_metadata` 通道，收到客户端 C2S 时能在日志打印 packetType（用测试客户端或 Paper 假连接验证）。

---

## 阶段 2 · 架构层

| # | 任务 | 细节 | 验收 | 参考 |
|---|---|---|---|---|
| 2.1 | `IDataProvider`/`DataProviderBase` 移植 | 照抄；NMS 类型（`MinecraftServer`/`ServerPlayer`）经 paperweight 直连 | 编译通过 | [01](01-servux-architecture.md) §4 |
| 2.2 | `DataProviderManager` 移植 | 注册/启用/调度/配置；`configDir` 改 `plugin.getDataFolder()`；`servux.json` 路径 | 能读写 `plugins/VeryMcProto/servux.json` | [01](01-servux-architecture.md) §3 |
| 2.3 | 生命周期事件监听 | `PlayerJoinEvent`/`PlayerQuitEvent` → provider join/leave；`ServerLoadEvent` → starting/started；tick 任务 → `tickProviders` | 玩家进退服触发回调 | [01](01-servux-architecture.md) §5；[06](06-fabric-vs-paper.md) §2 |
| 2.4 | settings 系统移植 | `AbstractServuxSetting` + 各类型（纯 Java 照抄） | 能从 config 序列化 | [01](01-servux-architecture.md) §6 |
| 2.5 | `/servux` 命令 | Paper Brigadier 或 plugin.yml；`reload/save/set/info/list/search` | `/servux reload` 重读配置 | [01](01-servux-architecture.md) §7；[06](06-fabric-vs-paper.md) §6 |
| 2.6 | 权限对接 | Bukkit `Permission`；servux 节点 `default: op`；按 setting 动态 | `hasPermission` 正确 | [06](06-fabric-vs-paper.md) §4 |
| 2.7 | `ConfigProvider`（servux_main） | 全局配置；`hasPermission_EasyPlace` 等 | 启用且永不被禁用 | [01](01-servux-architecture.md) §8 |
| 2.8 | 主类装配 | onEnable 串起 1–2.x：注册→配置→事件→tick→命令 | 启动流程完整 | [07](07-migration-architecture.md) §1.3 |

**验收**：插件启动注册 6 个 provider，读 `servux.json`，`/servux` 可用，玩家进退服触发日志。

---

## 阶段 3 · HUD Provider ★M1

| # | 任务 | 细节 | 验收 | 参考 |
|---|---|---|---|---|
| 3.1 | `ServuxHudPacket` 完整移植 | 10 种 Type + NBT/buffer 编解码 + 工厂方法 | 编解码往返一致 | [02](02-network-protocol.md) §3；[03](03-dataproviders-detail.md) §1 |
| 3.2 | `HudDataProvider` 元数据/出生点/天气 | `sendMetadata`/`refreshSpawnMetadata`/`refreshWeatherData`；天气监听 `WeatherChangeEvent`；出生点周期同步 | MiniHUD 显示 HUD + 出生点 | [03](03-dataproviders-detail.md) §1.2–1.4 |
| 3.3 | 配方下发 | `Recipe.CODEC`+`NbtOps` 编码；走 PacketSplitter | MiniHUD 显示配方（大包分包正确） | [03](03-dataproviders-detail.md) §1.5 |
| 3.4 | TPS logger | NMS `tickRateManager` + `getAverageTickTimeNanos` + 反射 `remainingSprintTicks` | MiniHUD 显示 mspt/tps | [03](03-dataproviders-detail.md) §1.6.1 |
| 3.5 | MobCap logger | NMS `getLastSpawnState` + 硬编码 `MAGIC_NUMBER=289` | MiniHUD 显示 mob caps | [03](03-dataproviders-detail.md) §1.6.2 |
| 3.6 | 失败重试 + invalid 玩家 | plugin messaging 无返回值 → 用"客户端是否回 C2S 握手"判断；JOIN 延迟试发 | 无 MiniHUD 玩家不被刷屏 | [03](03-dataproviders-detail.md) §1.8 |

**M1 验收**：真 Fabric 客户端（装 MiniHUD）连 Paper 测试服，HUD 显示世界信息/出生点/天气/TPS/MobCap，与原版 Servux 行为一致。

---

## 阶段 4 · Entities / Tweaks Provider

| # | 任务 | 细节 | 验收 | 参考 |
|---|---|---|---|---|
| 4.1 | `ServuxEntitiesPacket` + Handler | 移植；`onBlockEntityRequest`/`onEntityRequest`（NMS `saveWithFullMetadata`/`saveWithoutId`） | 实体/方块实体 NBT 查询可用 | [03](03-dataproviders-detail.md) §2 |
| 4.2 | 玩家背包/末影箱权限过滤 | 复用 Entities 的权限节点 | 无权限时 NBT 脱敏 | [03](03-dataproviders-detail.md) §2.2 |
| 4.3 | `NbtView` 重写 | 直接 NMS `Entity.saveWithoutId`，绕开 `IMixinNbtRead/WriteView` | 不依赖 Mixin | [04](04-mixin-analysis.md) §5 |
| 4.4 | `ServuxTweaksPacket` + Handler | 元数据（stackingShulkers）；NBT 查询复用 Entities | Tweakeroo 元数据握手 OK | [03](03-dataproviders-detail.md) §3 |

> 注：潜影盒"可堆叠"服务端行为省略（[04](04-mixin-analysis.md) 降级），仅下发元数据。

---

## 阶段 5 · Structures Provider

| # | 任务 | 细节 | 验收 | 参考 |
|---|---|---|---|---|
| 5.1 | `ServuxStructuresPacket` + Handler | 移植；`Structures` ListTag + `ExpandBox` | 编解码正确 | [03](03-dataproviders-detail.md) §4 |
| 5.2 | 结构采集 | NMS `ChunkAccess.getAllReferences`/`getStartForStructure`/`StructureStart.createTag`/`StructurePieceSerializationContext.fromLevel` | 能取到结构 NBT | [03](03-dataproviders-detail.md) §4.2 |
| 5.3 | 触发：周期扫描 | 每 `update_interval` 扫玩家 view distance 区块，去重，发送 | 玩家移动后结构框更新 | [07](07-migration-architecture.md) §3.3 |
| 5.4 | 黑白名单 + 过期刷新 | `structure_whitelist/blacklist` + Timeout 机制 | 过滤生效 | [03](03-dataproviders-detail.md) §4.3 |

**验收**：MiniHUD 显示村庄/神殿/要塞等结构边界框。

---

## 阶段 6 · Litematics Provider ★M2

| # | 任务 | 细节 | 验收 | 参考 |
|---|---|---|---|---|
| 6.1 | 投影系统骨架移植 | `container/`（BitArray/Palette/Container）、`selection/`（Box/Area）、`transmit/`（Buffer/Manager）照抄 | 单测：读写投影一致 | [05](05-schematic-system.md) §1–2 |
| 6.2 | `LitematicaSchematic` 移植 | NBT 读写、`createFromFile`、文件 GZIP（NMS `NbtIo`）；palette 解析传入 `RegistryAccess` | 能加载 .litematic 文件 | [05](05-schematic-system.md) §4 |
| 6.3 | 四阶段传输协议 | TransmitStart/Data/End/Cancel；SchematicBuffer 两级分包 | 能投递/接收大投影 | [05](05-schematic-system.md) §3 |
| 6.4 | 批量实体查询 | `onBulkEntityRequest`（区块内 TileEntities + Entities） | Litematica 批量取数 OK | [03](03-dataproviders-detail.md) §5.2 |
| 6.5 | 粘贴 | `SchematicPlacement.createFromNbt` + `pasteTo`（NMS `setBlock`）；镜像修复内联（箱子/铁轨/楼梯） | 能粘贴投影到世界 | [05](05-schematic-system.md) §6 |
| 6.6 | `ServuxLitematicaPacket` + Handler | 移植协议帧 | 编解码正确 | [03](03-dataproviders-detail.md) §5 |

**M2 验收**：Litematica 客户端能从 Paper 服拉取投影、上传投影、粘贴投影，与原版 Servux 行为一致。

---

## 阶段 7 · 打磨与降级项

| # | 任务 | 细节 | 优先级 | 参考 |
|---|---|---|---|---|
| 7.1 | EasyPlace（选做） | PacketEvents 拦截 `ServerboundUseItemOnPacket`，实现 `PlacementHandler.applyPlacementProtocolV3` | P3 | [04](04-mixin-analysis.md) §4；[07](07-migration-architecture.md) §4 |
| 7.2 | 大包性能优化 | Recipe/Litematic 通道升级方案 B（NMS 发包保 1MiB 分片） | P2 | [07](07-migration-architecture.md) §2.2 |
| 7.3 | Structures 性能 | 扫描频率/缓存优化；view distance 限制 | P2 | [07](07-migration-architecture.md) §6 |
| 7.4 | i18n / 日志 | lang 文件或硬编码消息；`getLogger` | P3 | [01](01-servux-architecture.md) §9 |
| 7.5 | 边界与异常 | 客户端断连、超大投影拒绝、并发安全（SchematicBufferManager 已用 ConcurrentHashMap） | P2 | [05](05-schematic-system.md) §2 |
| 7.6 | 抓包保真对照 | 对比 Fabric+Servux vs Paper+插件 字节流 | P1 | [07](07-migration-architecture.md) §7 |

**M3 验收**：协议全通道功能等价；抓包对照字节一致；性能可接受。

---

## 任务依赖与并行建议

- **串行必经**：0 → 1 → 2 → 3（M1 前不可跳）。
- **M1 后可并行**：4 / 5 / 6 三条相对独立，可分配给不同人/不同 session 并行（共享阶段 1-2 的网络与架构层）。
- **阶段 6 最重**：建议单独排期（投影系统 ~9000 行，但多为照抄）。
- **阶段 7 可穿插**：7.1（EasyPlace）等降级项可在主功能稳定后再做。

---

## 每个任务的"完成定义"（DoD）模板

1. **代码**：照抄自原版的部分标明来源文件路径；Paper 特有部分有注释说明转换理由。
2. **编译**：`./gradlew build` 通过，无未处理 NMS 警告。
3. **自测**：相关单测（分包/BitArray/编解码）通过。
4. **联调**：真客户端验证该通道功能（MiniHUD/Litematica/Tweakeroo）。
5. **文档**：在对应 docs（01-07）中该任务的实现细节已记录（反射点、降级点等）。

---

## 风险任务 Top 5（最可能卡住的地方）

1. **5.2/5.3 Structures**：NMS 结构 API 调用 + 周期扫描触发设计（无原版 chunk-watch 事件）。建议先做最小可用版（只发玩家所在区块结构），再优化。
2. **6.5 粘贴 + 镜像修复**：方块状态旋转/镜像变换 + 内联修正，逻辑复杂。建议先支持无旋转粘贴，再加 Mirror/Rotation。
3. **3.4 TPS 反射**：`remainingSprintTicks` 字段名需核对 1.21.11 实际 Mojang 名（可能漂移）。先验证字段存在。
4. **1.1/2.2 配置与分包**：分片常量 + session key 在多玩家并发下的正确性。
5. **3.6 失败重试**：plugin messaging 无 send 返回值，invalid 玩家判断需重新设计（靠客户端握手响应）。
