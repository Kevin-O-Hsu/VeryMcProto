# 客户端兼容测试指南

> 本文档把「插件移植」转化为「实测验证」。基于 `OriginImpl/` 下 masa 全家桶
> （minihud / tweakeroo / litematica）**客户端源码的逐行分析**，给出每个 servux 通道对应的
> 客户端 mod、触发方式、预期表现和成功判据。
>
> 配套阅读：[`02-network-protocol.md`](02-network-protocol.md)（协议层）、[`03-dataproviders-detail.md`](03-dataproviders-detail.md)（数据内容）。

---

## 1. 通道 ↔ 客户端 Mod 总览（核心映射）

servux 共 5 条通道，对应 **3 个 masa 客户端 mod**。映射关系由客户端源码的
`Identifier.fromNamespaceAndPath("servux", ...)` 声明铁定：

| 通道 | 协议版本 | 客户端 Mod（声明源） | 功能 | 当前状态 |
|---|---|---|---|---|
| `servux:hud_metadata` | 2 | **MiniHUD** (`ServuxHudHandler`) | spawn / seed / 天气 / TPS / MobCap HUD | ✅ 已验证 |
| `servux:structure_bounding_boxes` | 2 | **MiniHUD** (`ServuxStructuresHandler`) | 结构边界框渲染 | ✅ 已验证 |
| `servux:entity_data` | 1 | **MiniHUD** (`ServuxEntitiesHandler`) | 实体 / 方块实体 NBT 查询 | ✅ 已验证 |
| `servux:tweaks` | 1 | **Tweakeroo** (`ServuxTweaksHandler`) | NBT 查询 + **潜影盒堆叠配置同步** | ❓ **待测** |
| `servux:litematics` | 1 | **Litematica** (`ServuxLitematicaHandler`) + Tweakeroo | NBT 查询 + **批量区块 NBT 拉取** + 投影传输/粘贴 | ❓ **待测** |

> **itemscroller 不碰任何 servux 通道**（源码无 `servux` namespace 引用），无需测试。
>
> **关键事实**：MiniHUD 只监听 hud / structure / entity 三条通道（`OriginImpl/minihud-*/network/`），
> 不碰 tweaks / litematics。所以「minihud 全兼容」≠「所有通道都通」。**接下来要测的是 Litematica 和 Tweakeroo**。

### 测试顺序建议

| 顺序 | 目标 | 为什么 |
|---|---|---|
| **1** | **Litematica**（`servux:litematics`） | 验证点最丰富、最直观：握手 + **保存投影触发批量 NBT（聊天框可见反馈）** |
| **2** | **Tweakeroo**（`servux:tweaks`） | 握手 + 潜影盒堆叠配置同步（需改服务端配置） |

---

## 2. 理解测试原理：客户端 C2S 拉取模型

**这是所有测试的基础，理解错了会误判。**

masa 客户端是 **C2S 主动拉取（pull）模式**，不是服务端推送（push）：

```
客户端进服 → ENTITY_DATA_SYNC 开 → 客户端每 tick 检查：
  if (没连过 servux && 不是单人局域网世界) {
      registerPlayReceiver(servux:*);
      sendPacket(PACKET_C2S_METADATA_REQUEST);   // ← 客户端主动问
  }
                       ↓
服务端收到 C2S MetadataRequest → sendMetadata(player)  // ← 服务端被动答
                       ↓
客户端收到 PACKET_S2C_METADATA → receiveServuxMetadata()
  → 校验协议版本 → setHasServuxServer(true)            // ← 标记「连上了」
```

### 判官逻辑：`not_enabled` vs `not_connected`（务必分清）

> 这是上一轮实测中从 `minihud/.../InfoLineServux.java` 提炼的铁证，对 tweakeroo / litematica 同样适用。

| 客户端显示 | 含义 | 是否服务端 bug |
|---|---|---|
| `not_enabled` | 客户端自己的同步开关（`entityDataSync`）**没开**——纯客户端本地配置检查，**根本不与服务端通信** | ❌ **不是**。去客户端开配置 |
| `not_connected` | 开关开了但握手没成功（C2S 请求没得到 S2C 响应） | ⚠️ **可能是**。查服务端 debug 日志 |
| 正常显示数据 | 握手成功，数据流通 | ✅ |

> **`entityDataSync` 在 litematica / tweakeroo 里默认都是 `false`**（已查源码确认）。
> 所以测试第一步**永远是先开这个开关**，否则必然 `not_enabled`，与插件无关。

---

## 3. 测试前置准备

### 3.1 服务端：开启 debug 日志（强烈建议）

握手过程默认无日志，必须开 debug 才能看到 C2S / S2C 流向。两种方式：

- **运行时即时生效**（推荐）：服务端执行
  ```
  /servux debug                       # 切换总开关
  /servux debug handshake             # 切换单个分类（握手）
  /servux debug packet                # 切换数据包分类（收发 / 分片）
  ```
  完整分类见 `framework/debug/Debug.java` 的 `Cat` 枚举：`lifecycle / handshake / network / packet / tick / permission / provider / config`。
- **持久化**：编辑 `run/plugins/VeryMcProto/servux.json`，设 `servux_main.debug_log: true`，重启。

> 开启后日志形如：`[DBG/HANDSHAKE] litematic sendMetadata → Steve ok=true servux=servux-paper-1.21.11-1.0.0 ver=1`。

### 3.2 服务端：确认权限（当前默认全员可用）

当前 `servux.json` 所有 `permission_level` 均为 `0`（= 全员可用），`permission_level: 0` 对应
`player.hasPermission(node)` 不强制 OP。测试用普通玩家即可，无需改配置。

### 3.3 客户端：装 mod + 开配置

- **必装**：`malilib`（配置 GUI 框架，所有 masa mod 依赖）+ 对应功能 mod（Litematica / Tweakeroo）。
- **开配置**：打开对应 mod 的配置菜单 → **Generic** 分类 → 找到 `entityDataSync` → 开启（`true`）。
  - 也可直接编辑 `config/<mod>.json`，但 GUI 操作更稳妥。

---

## 4. 回归基线：MiniHUD（hud / structure / entity）✅

已验证通过，作为「机制正常」的对照基线。若 tweakeroo / litematica 出问题，先用 minihud 确认基础链路没退化。

| 通道 | 客户端开关 | 验证方式 |
|---|---|---|
| hud | `hudDataSync` | `InfoLineServux` 的 `hud_sync` 行显示 `overworld: x,y,z` |
| entity | `entityDataSync` | `InfoLineServux` 的 `entity_sync` 行显示缓存数 |
| structure | structure overlay 开关 | 游戏内看到结构边界框渲染 |

---

## 5. 测试一：Litematica（`servux:litematics`）⭐ 优先测

### 5.1 Litematica 用这条通道做什么（源码依据）

源自 `OriginImpl/litematica-*/.../data/EntityDataManager.java` + `network/ServuxLitematicaHandler.java`：

1. **握手 + 单个 NBT 查询**：`entityDataSync` 开 → C2S `requestMetadata` → S2C 响应后，逐个查询
   方块实体 / 实体 NBT（`requestServuxBlockEntityData` / `requestServuxEntityData`）。
   用途：渲染真实世界的容器内容（箱子、漏斗等的物品）。
2. **批量区块 NBT 拉取** ⭐（黄金验证点）：`requestServuxBulkEntityData(chunkPos, minY, maxY)`
   （`EntityDataManager.java:679`）—— **保存投影（Save Schematic）时**，对该区域每个区块请求
   全部方块实体 + 实体的完整 NBT。响应 `BulkEntityReply`（`TileEntities` + `Entities` + `chunkX/Z`）。
3. **投影文件传输**（服务器→客户端投递 .litematic）：`Litematic-TransmitStart/Data/End`。**⚠️ 已降级**。
4. **投影粘贴**（C2S 上传投影让服务端放置）：`handleClientPasteRequest`。**⚠️ 已降级**。

> 我们的插件 `LitematicsDataProvider.onBulkEntityRequest` 在响应批量请求时会向玩家**聊天框**发送
> `Litematics bulk reply: <世界> <区块> TE=<方块实体数> E=<实体数> (<耗时>ms)`——**这是最直观的验证信号**。

### 5.2 测试 A：握手（必做，前置）

**目的**：确认 `servux:litematics` 通道握手成功（客户端 `hasServuxServer=true`）。

| 步骤 | 操作 |
|---|---|
| 1 | 服务端开 debug：`/servux debug` + `/servux debug handshake` |
| 2 | 客户端开 `entityDataSync`（Litematica 配置 → Generic） |
| 3 | 客户端进服（或重连） |

**预期（成功判据）—— 三处任一可见即通过**：

- ✅ 服务端日志：`[DBG/PACKET] C2S litematics ← <玩家> type=PACKET_C2S_METADATA_REQUEST`
- ✅ 服务端日志：`[DBG/HANDSHAKE] litematic sendMetadata → <玩家> ok=true servux=servux-paper-... ver=1`
- ✅ 客户端日志（`.minecraft/logs/latest.log`）：`LitematicDataChannel: joining Servux version servux-paper-...`

**若失败**：服务端只有 C2S 没有 `ok=true` 的 S2C → 握手回程丢包，查 §7 排错。

### 5.3 测试 B：保存投影触发批量 NBT 拉取 ⭐ 最直观

**前提**：测试 A 握手已成功（`hasServuxServer=true`）。

| 步骤 | 操作 |
|---|---|
| 1 | 在世界中找 / 造一个**含方块实体**的区域（如放几个箱子、熔炉、漏斗），实体也可有（动物等） |
| 2 | Litematica 主菜单（默认 `M` 键）→ **Area Selection** → 新建选区，框住该区域 |
| 3 | **Save Schematic**（保存投影）→ 命名 → 确认保存 |
| 4 | 保存瞬间，Litematica 向服务端批量请求区域内各区块的 NBT |

**预期（成功判据）**：

- ✅ **服务端聊天框**（玩家可见）：`Litematics bulk reply: minecraft:overworld [chunkX, chunkZ] TE=<数> E=<数> (<ms>)`
  - `TE=` 是方块实体数，`E=` 是实体数。框了箱子 → `TE>0`；区域有动物 → `E>0`。
- ✅ 服务端 debug 日志（`packet` 分类）：可见 `C2S litematics ← ... type=PACKET_C2S_BULK_ENTITY_NBT_REQUEST`
  与分包发送日志。
- ✅ 客户端日志：`EntityDataManager#handleBulkEntityData(): chunkPos ... received TE: [n], and E: [n] entiries from Servux`

> 即使个别情况下走的是「逐个查询」而非「批量」路径，服务端 debug（`packet` 分类）也必然能看到
> litematic 通道的 `BLOCK_ENTITY_REQUEST` / `ENTITY_REQUEST` + 响应。**只要握手后做保存投影，
> 服务端日志必有 NBT 查询活动**——这是通道是否真正通的双向证据。

### 5.4 降级说明（测试时注意，非 bug）

| 功能 | 状态 | 表现 |
|---|---|---|
| 投影文件传输（服务器投递投影给客户端） | ❌ 未实现 | 服务端重组后仅 `ServuxLog.debug` 记录，不加载。Litematica 收不到服务器推送的投影 |
| 投影粘贴（客户端上传投影让服务端放置） | ❌ 未实现 | 玩家收到 `§c粘贴功能未实现（schematic 系统未移植）`。详见 [`05-schematic-system.md`](05-schematic-system.md) |
| 单个 / 批量 NBT 查询 | ✅ 已实现 | 上述测试 A / B 覆盖 |

---

## 6. 测试二：Tweakeroo（`servux:tweaks`）

### 6.1 Tweakeroo 用这条通道做什么（源码依据）

源自 `OriginImpl/tweakeroo-*/.../data/EntityDataManager.java` + `network/ServuxTweaksHandler.java`：

1. **握手 + NBT 查询**：与 Litematica 同构（`entityDataSync` 开 → C2S 拉取 → 缓存）。
2. **潜影盒堆叠配置同步** ⭐（tweaks 通道独有）：`receiveServuxMetadata` → `checkTweaksConfigs`
   （`EntityDataManager.java:420`）—— 收到服务端下发的 `stackingShulkers` / `stackingShulkersMax`
   → 自动同步到客户端 `TWEAK_SHULKERBOX_STACKING` 开关与 `SHULKER_MAX_STACK_SIZE`。

> 我们的插件 `TweaksDataProvider.sendMetadata` 仅当 `stackable_shulkers=true` 时才下发这两个字段。

### 6.2 测试 A：握手（必做，前置）

步骤同 §5.2，仅通道不同：

- 客户端开关仍是 **Tweakeroo** 配置 → Generic → `entityDataSync`。
- **预期服务端日志**：`[DBG/HANDSHAKE] tweaks sendMetadata → <玩家> ok=true ... keys=[...]`
- **预期客户端日志**：`tweaksDataChannel: joining Servux version servux-paper-...`

### 6.3 测试 B：潜影盒堆叠配置同步 ⭐ tweaks 独有

**目的**：验证服务端配置变更能下发到客户端并生效。

| 步骤 | 操作 |
|---|---|
| 1 | 服务端编辑 `servux.json`：`tweaks_data.stackable_shulkers` 改为 `true`（可选改 `stackable_shulkers_count`，如 `16`） |
| 2 | `/servux reload`（或重启）使配置生效 |
| 3 | 客户端确保 `entityDataSync` 开（握手前提） |
| 4 | 客户端进服 / 重连，触发握手 |

**预期（成功判据）**：

- ✅ 服务端日志：`tweaks sendMetadata → <玩家> ... keys=[stackingShulkers, stackingShulkersMax, ...]`
  （`keys` 里出现 `stackingShulkers` 说明已下发该字段）
- ✅ 客户端日志：`checkTweaksConfigs: stackingShulkers: [true]` 与 `stackingShulkersMax: [16]`
- ✅ 客户端行为：打开 Tweakeroo 配置，`tweakShulkerBoxStacking` 开关被自动设为 `true`（配置同步生效）

### 6.4 降级说明（非 bug）

| 功能 | 状态 | 表现 |
|---|---|---|
| 配置下发（`stackingShulkers` / `Max`） | ✅ 已实现 | 上述测试 B 覆盖 |
| 服务端潜影盒堆叠行为（真正改变堆叠上限） | ❌ 已降级 | 原版用 Mixin 改 `ItemStack` / `Hopper` 逻辑，Paper 无 Mixin，**省略**。客户端虽收到配置并在本地堆叠，但服务端不认 → 重新拾起 / 移动时会被服务端按原上限拆开。**这是已知的、文档化的降级**，非测试失败。详见 [`04-mixin-analysis.md`](04-mixin-analysis.md) |

---

## 7. 排错指南

### 7.1 标准排查流程

```
客户端显示异常
  │
  ├─ not_enabled → 客户端配置没开（entityDataSync / hudDataSync）→ 去 GUI 开，重连
  │                 （与服务端无关，先排除！）
  │
  └─ not_connected / 无数据 → 握手失败 → 开服务端 debug：
        /servux debug
        /servux debug handshake
        /servux debug packet
        重连，看日志：
        │
        ├─ 无 "C2S <通道> ← 玩家 type=METADATA_REQUEST"
        │     → 客户端根本没发请求 → 客户端 entityDataSync 没开 / mod 没装 / 版本不匹配
        │
        ├─ 有 C2S 但无 "sendMetadata ... ok=true"
        │     → 服务端没回程 → 查 onPlayerRegisterChannel 是否触发、权限是否够
        │
        └─ 有 ok=true 但客户端仍 not_connected
              → S2C 包被客户端丢弃 → 协议版本不匹配 / 字节布局错（极少见，对照源码）
```

### 7.2 关键 debug 日志对照表

| 日志（`[DBG/...]`） | 含义 |
|---|---|
| `onPlayerRegisterChannel: <玩家> 声明监听 → servux:litematics` | 客户端装了对应 mod 的可靠信号（configuration phase 完成后） |
| `C2S <通道> ← <玩家> type=PACKET_C2S_METADATA_REQUEST` | 客户端主动发起握手 |
| `<provider> sendMetadata → <玩家> ok=true servux=... ver=N` | 服务端握手成功回程 |
| `C2S <通道> ← <玩家> type=PACKET_C2S_BULK_ENTITY_NBT_REQUEST` | Litematica 保存投影触发的批量请求 |
| `<provider> sendMetadata ... keys=[...]` | 下发的 metadata 字段集合（tweaks 看是否含 stackingShulkers） |

### 7.3 协议版本不匹配告警

客户端若收到版本不符会 warn（如 `Mis-matched protocol version!`）。对照：

| 通道 | 客户端期望（`PROTOCOL_VERSION`） | 我们下发 |
|---|---|---|
| hud_metadata | 2 | 2（`ServuxHudPacket.PROTOCOL_VERSION`） |
| entity_data | 1 | 1 |
| structures | 2 | 2 |
| tweaks | 1 | 1 |
| litematics | 1 | 1 |

---

## 8. 已知降级清单（测试时预期这些「不工作」，非 bug）

源自 Mixin / schematic 无法迁移，详见 [`04-mixin-analysis.md`](04-mixin-analysis.md) 与
[`07-migration-architecture.md`](07-migration-architecture.md) §降级矩阵：

| 功能 | 所属通道 | 降级表现 |
|---|---|---|
| 投影文件传输（服务器→客户端投递投影） | litematics | 重组后仅日志，不加载 |
| 投影粘贴（C2S 上传放置） | litematics | 玩家收到「未实现」提示 |
| 服务端潜影盒堆叠行为 | tweaks | 配置可下发，但服务端不真改堆叠上限 |
| EasyPlace（Tweakeroo 服务端配合放置） | servux_main | 降级 / 省略 |
| UpdateSuppression | — | 省略 |
| 镜像修复（箱子/铁轨/楼梯 180°） | litematics | 仅在（未实现的）粘贴路径用，当前无影响 |

> **已实现且应正常工作的**：所有通道的握手 + 实体/方块实体 NBT 查询 + 批量 NBT 拉取 +
> HUD 数据 + 结构边界框 + 潜影盒配置下发。

---

## 9. 测试结果记录表

> 每次实测后填写，便于回归。

| 日期 | 通道 | 客户端 Mod | 测试项 | 结果 | 日志证据 / 备注 |
|---|---|---|---|---|---|
| | hud_metadata | MiniHUD | hud_sync 显示 | ✅ | |
| | structures | MiniHUD | 结构边界框 | ✅ | |
| | entity_data | MiniHUD | entity_sync 显示 | ✅ | |
| | litematics | Litematica | 握手 | ⬜ | |
| | litematics | Litematica | 保存投影批量拉取 | ⬜ | |
| | tweaks | Tweakeroo | 握手 | ⬜ | |
| | tweaks | Tweakeroo | 潜影盒配置同步 | ⬜ | |

---

## 附：源码对照索引

| 客户端源码 | 看什么 |
|---|---|
| `OriginImpl/litematica-*/.../data/EntityDataManager.java` | `requestServuxBulkEntityData`（保存投影触发点）、`receiveServuxMetadata`、`onClientTick` 握手条件 |
| `OriginImpl/litematica-*/.../network/ServuxLitematicaHandler.java` | 客户端通道 `servux:litematics` 收发、`handleBulkData` 任务分发 |
| `OriginImpl/tweakeroo-*/.../data/EntityDataManager.java` | `checkTweaksConfigs`（潜影盒配置同步）、握手条件 |
| `OriginImpl/tweakeroo-*/.../network/ServuxTweaksHandler.java` | 客户端通道 `servux:tweaks` 收发 |
| 我们的插件 | `mod/servux/dataproviders/LitematicsDataProvider.java`、`TweaksDataProvider.java`、`mod/servux/network/Servux*Litematica/Tweaks*Handler.java` |
