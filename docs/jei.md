# JEI 完整协议（Paper 服务端实现 · 1.21.11 线）

> **上游权威**：mezz/JustEnoughItems 分支 `1.21.11`（JEI 27.44.0 / MC 1.21.11 / Java 21，本地对照 `OriginImpl/JustEnoughItems-1.21.11/`，commit `2af0130`）。本线 2026-10 从旧 JEI Recipe Bridge 形态（`mod/jeirecipebridge/`，已删）完整迁移为 mezz 最上游范式——与 dev/26.2 线同构（jei 模块跨线 cherry-pick 禁止，本线为手工重写迁移，实录见 §8）。
>
> 配方同步层的 wire 真权威是 **Fabric API** `fabric-recipe-api-v1`（github FabricMC/fabric 分支 `1.21.11`，本地对照 `OriginImpl/fabric-1.21.11/`）；NeoForge 层是 NeoForge 加载器（wire 参考 `OriginImpl/JEIRecipeBridge-1.21.11/`——本线旧桥的直接上游）。
>
> 相关阅读：[02-network-protocol.md](02-network-protocol.md)（字节上限 §5.2 / Mixin 处置见 04-mixin-analysis.md）、命令/权限/配置见根 [../README.md](../README.md) 与 [../AGENTS.md](../AGENTS.md)、总索引 [00-INDEX.md](00-INDEX.md)。

## 0. 协议全景（三层）

JEI 的"服务端协议"由三层构成，缺一层则对应功能面在 Paper 上缺失：

| 层 | 通道 | 方向 | 内容 | 客户端消费方 |
|---|---|---|---|---|
| **配方同步层** | `fabric:recipe_sync` | S2C | 服务端全配方表（按 serializer 分组） | Fabric API `ClientRecipeSynchronizedEvent` → JEI `Internal.setClientSyncedRecipes` |
| | `neoforge:recipe_content` + `minecraft:tags`（UpdateTags） | S2C | 全配方 + tag 表 | NeoForge 加载器 → JEI |
| **jei:* 自有层** | 8 条 C2S + 2 条 S2C | 双向 | cheat 权限/给/删/热键栏 + 配方转移 | JEI 客户端 `mezz.jei.common.network.*` |
| **服务端行为层** | — | — | 权限判定 + 物品操作 + 转移算法 | （无 wire，由 C2S 处理器承载） |

**为什么配方不在 jei:* 通道里**：1.21.2 起原版把配方表收敛到服务端，配方回传是**加载器层**职责（Fabric API `RecipeSynchronization` / NeoForge `recipe_content`）。JEI 服务端（Fabric mod 装在服务端时）只是在初始化时调 `RecipeSynchronization.synchronizeRecipeSerializer` 把 vanilla 序列化器标记进同步集合，真正发送仍由 Fabric API 完成。Paper 上没有这两层，故我们直接实现两条 loader 通道。

## 1. 通道声明契约（客户端功能门禁，最重要）

JEI 客户端判定"服务端有 JEI"的方式（`Fabric/.../network/ConnectionToServer.java`）：

```java
public boolean isJeiOnServer() {
    return ClientPlayNetworking.canSend(PacketDeletePlayerItem.TYPE);   // jei:delete_player_item
}
```

`canSend` 检查的是**服务端是否在 vanilla register 机制中声明过该通道**。Paper 侧的等价物 = `ChannelManager.register`（incoming + outgoing 成对注册，servux 五通道同款范式）。

- **声明全 8 条 C2S 通道**（`JeiReference.C2S_CHANNELS`）：少一条 = 客户端对该包 `canSend=false` 永不发送（cheat/transfer 对应功能静默退网，无任何报错——排错成本极高）。**与上游的有意差异**：上游 `ServerNetworkHandler` serverbound 注册 9 条，第 9 条 `PacketCheatPermission`（`jei:cheat_permission`）为**误注册的纯 S2C 类型**——它 `extends PlayToClientPacket`、已在 clientbound 正确注册、且 serverbound 无任何 receiver；上游 clientbound 侧对称地误注册 2 条 legacy 纯 C2S 类型。我方 8 条为正确收敛，不复刻误注册（惰性无行为影响：真实 JEI 客户端永不发送该 C2S）。
- **brand 无关**：`isSameModLoader()`（server brand == "fabric"）只影响客户端"配方同步缺失"警告文案分支（`JeiStarter.verifyClientRecipes`），**不门控任何功能**。Paper brand = "Paper" 时客户端最多多一条黄/红字提示，前提是没有收到配方同步。
- **vanilla 客户端无害**：收到含 jei:* 的 REGISTER 声明会被原样忽略（`fabric:recipe_sync` 出站声明先例已在生产环境长跑——本线旧桥 1.21.11-b1~b4 发版实证）。
- **R3 硬约束**：`/jei disable` 只在 handler 内丢包，**通道永不注销**——注销 incoming 后客户端后续 C2S 会命中 Paper 未注册通道踢人（"Invalid payload"）。

## 2. jei:* 自有层 wire 逐字段

全部通道 id = `jei:<name>`（上游 `ModIds.JEI_ID`）。列表线序 = VarInt(size) + 元素（`ByteBufCodecs.list()` 等价物，预分配容量 65536 封顶——vanilla collection decode 同源，见 §6）；枚举 = VarInt 序号（`readEnum` 越界严格拒绝）；ItemStack = `ItemStack.STREAM_CODEC`（需 `RegistryFriendlyByteBuf`）。

### S2C（服务端 → 客户端，2 条，均经 `JeiPacketSender` NMS 直发）

| 通道 | 字段 | 触发 |
|---|---|---|
| `jei:cheat_permission` | BOOL hasPermission + List\<UTF8\> allowedCheatingMethods | ① 应答 request_cheat_permission；② 无权限 cheat 尝试的纠正回包。列表内容 = 服务端**开启**的途径翻译键（`jei.chat.error.no.cheat.permission.op/.creative/.give`，收集序 op→creative→give——`PacketCheatPermission.getAllAllowedCheatingMethods`） |
| `jei:recipe_transfer_result` | VAR_INT transferId + BOOL successful | 转移完成回执。transferId 是**客户端侧**关联键（`PENDING_RECIPE_TRANSFERS`），服务端只透传 |

### C2S（客户端 → 服务端，8 条，经 `ChannelManager` → `JeiServerPlayHandler`）

| 通道 | 字段 | 服务端行为 |
|---|---|---|
| `jei:request_cheat_permission` | （unit 无字段） | 判定权限 → 回 cheat_permission |
| `jei:give_item_stack` | ItemStack + GiveMode(VAR_INT 枚举) | `Cheats.executeGive`：INVENTORY=入包+溢出掉落+广播；MOUSE_PICKUP=并入光标 |
| `jei:delete_player_item` | ItemStack | 有权限：光标持同 id 物品则清空；无权限：警告 + cheat_permission(false) |
| `jei:set_hotbar_item_stack` | ItemStack + VAR_INT hotbarSlot | `Cheats.setHotbarSlot`（`Inventory.isHotbarSlot` 校验 0-8 + 同物短路 + 音效 + 广播） |
| `jei:recipe_transfer_with_result` | List\<TransferOperation\>(uncounted) + List\<VAR_INT\> craftingSlots + List\<VAR_INT\> inventorySlots + BOOL maxTransfer + BOOL requireCompleteSets + VAR_INT transferId | 转移执行 → 回 recipe_transfer_result |
| `jei:recipe_transfer_counted_with_result` | 同上，但操作列表 counted（每项多 VAR_INT count） | 同上 |
| `jei:recipe_transfer`（legacy） | 同 with_result 但**无 transferId** | 转移执行，无回执（旧客户端兼容；上游 legacy/README：禁止加字段） |
| `jei:recipe_transfer_counted`（legacy） | 同上 counted | 同上 |

**TransferOperation**（`transfer/TransferOperation.java`）：`VAR_INT inventorySlotId + VAR_INT craftingSlotId [+ VAR_INT count]`；counted 变体的 compact 构造拒绝 `count < 1`。

## 3. cheat 权限模型（服务端行为层）

上游 `ServerCommandUtil.hasPermissionForCheatMode` —— **creative → op → give 短路序**（`cheat/Cheats.hasCheatPermission` 纯函数，配单测矩阵）：

1. `cheatModeEnabledForCreative && 玩家是创造模式` → 放行；
2. `cheatModeEnabledForOp` → 判 `Permissions.COMMANDS_GAMEMASTER`（= 权限级 2，NMS 直用——`net.minecraft.server.permissions.Permissions` 在 1.21.11 映射实证存在）；
3. `cheatModeEnabledForGive` → 判 `/give` 权限（Paper 映射：Bukkit 权限节点 `minecraft.command.give`，default op）。

配置三键落 `jei.json`，默认值对齐上游 `fabric/config/ServerConfig.java`（1.21.11 与 26.2 分支逐字节同——互 diff 实证）：**op=true、creative=true、give=false**。给/删/热键栏的完整副作用（音效、`broadcastChanges`、`commands.give.success.single` 通知、溢出掉落 `makeFakeItem` 等）逐行镜像 `ServerCommandUtil`。

## 4. 配方转移算法（`transfer/BasicRecipeTransferHandlerServer`）

逐行移植上游同名类 + `RecipeTransferUtil.validateSlots`（log4j→JUL shim）。流程：

```
validateSlots（槽 id 界内 / 目标槽∈crafting / 源槽∈inventory∪crafting / 两集合不相交 / 无 fake 槽）
→ canClearCraftingSlots（mayPickup + mayPlace）
→ calculateRequiredTransfers（allowModification / 非空源 / mayPlace / 同槽同物）
→ takeItemsFromInventory（complete-set 语义：整组凑不齐则回滚本轮槽快照；maxTransfer 循环 + 满槽剔除）
→ clearCraftingGrid（safeTake）
→ putItemsIntoCraftingGrid（safeInsert + 组上限）
→ stowItems（余料归包 → inventory.add → drop）
→ broadcastChanges → 回执
```

任何一步失败返回 false → `recipe_transfer_result(successful=false)`，**不做半程状态**。物品操作全部主线程（plugin messaging 接收即主线程）。安全面：槽界纯校验（`AbstractRecipeTransferPacket.validateSlotIds`，配单测）+ 严格枚举解码 + count≥1 + 上游槽位校验全套。

## 5. 配方同步层 wire 与触发

### 5.1 fabric:recipe_sync（Fabric API 1.21.11 `RecipeSyncPayloadS2C` 逐字一致）

```
VarInt entryCount
└ 每 entry: Identifier(serializer id) + VarInt(n) + n × [ResourceKey<Recipe> + serializer.streamCodec(recipe)]
```

- **触发**：`PlayerRegisterChannelEvent(fabric:recipe_sync)`——对齐上游 `RecipeSyncImpl.sendRecipes` 的 `canSend(player)` 门控（只发给声明过能收的客户端；Fabric API 客户端注册 receiver 即声明；vanilla 零打扰）。分组为单遍 O(R)（IdentityHashMap 按 serializer 身份聚合）+ **minecraft 命名空间过滤**（按 `RECIPE_SERIALIZER` registry key 的 namespace 判定，仅同步 `minecraft` 序列化器——JEI 自有 `jei:jei_shaped` 等被上游排除；`getKey()==null`（未注册）跳过不放行）。该声明同时是**进服时序整形器**的"证据到达"信号（§5.2）。
- **接收端**：Fabric API 展平排序 → `ClientRecipeSynchronizedEvent` → JEI `setClientSyncedRecipes`。read 端对未知 serializer 抛 `SkipPacketDecoderException`——Paper 现状配方恒 vanilla 序列化器（过滤恒全量放行），但自定义 serializer 一旦出现即被服务端过滤拦下，不会到达客户端触发跳包。
- **未实现（有意）**：C2S `fabric:recipe_sync/supported_serializers`（configuration phase 支持集协商）——① 该 payload 受客户端 `canSend` 门控（服务端须先在 config 相位 S2C 声明该通道才发；Paper 不发 config S2C register → 该 C2S 在 Paper 上物理不可达）；② 不声明该通道 → 客户端 `canSend=false` → 根本不发送 → 行为安全。**若上游后续让协商结果影响行为需重估**。
- **本线历史注**：本线 wire 首次落地来自旧桥（Mrbysco 同款 1.21.11 实现，随 1.21.11-b1~b4 实机验证）；本次完整迁移承其 wire 编码体（Entry read/write 与其零差异实证）并对 Fabric API 1.21.11 上游逐字段重核（1.21.11 分支 payload 类名 = `RecipeSyncPayloadS2C`，26.x 才更名 `ClientboundRecipeSyncPayload`）。

### 5.2 进服时序整形（`network/RecipeSyncJoinOrderer`）——JEI 警告根因与修复

**根因（上游实证链）**：JEI 客户端在处理 play 相位 `ClientboundUpdateRecipesPacket` 的 **RETURN** 时启动并一次性判定配方同步状态（`ClientPacketListenerRecipeUpdateMixin` @Inject handleUpdateRecipes RETURN → `JeiStarter.verifyClientRecipes`，Paper brand 落入 `recipe.sync.unavailable` 分支）。上游 Fabric 服务端在 `PlayerList.placeNewPlayer` 内、构造 UpdateRecipesPacket **之前**发送配方（fabric-lifecycle-events-v1 `PlayerListMixin` 注入点 `@At("NEW", target=...UpdateRecipesPacket)`）→ payload 排在 UpdateRecipesPacket 之前上线；该时点 `canSend` 能通过靠 Fabric 服务端在 config 相位发 S2C register 触发客户端提前声明 play 通道（reactive 机制，Paper 不发 config S2C register 故结构性缺失）。Paper 侧 `PlayerJoinEvent` 在 UpdateRecipesPacket 之后、`PlayerRegisterChannelEvent` 更晚（客户端收到 LoginPacket 前不可能发包）——任何 Bukkit 事件触发器因果上必输。

**机制**：config 相位末（`AsyncPlayerConnectionConfigureEvent`——1.21.11 paper-api 已含该事件（jar 解包实证），**fire 时机/线程语义为本线实机观察项**，见 §9）向连接 netty 管线 `addAfter(HandlerNames.ENCODER)` 安装一次性出站拦截器——扣住首个出站 UpdateRecipesPacket；play register 证据到达（现有 RegisterChannel 触发路径）→ `sendFabric` 提交 payload 写 → `release(uuid)` 放行被扣包。主线程同步提交序 + eventLoop FIFO ⇒ **wire 序恒为 payload → UpdateRecipesPacket**（与上游不变量逐字节同源）。netty 出站沿 tail→head 传播，拦截器必须在 encoder 的 **tail 侧**（addAfter）才能在编码前看到 Packet 对象（addBefore 在编码后收到 ByteBuf、拦截永不命中）。

**1.21.11 专属适配（本线与 dev/26.2 线的非同文点，勿以 dev 线此段为准）**：`PaperCommonConnection` 的 listener 字段名两线相反——1.21.11 为 `handle`（唯一字段，1.21.11 dev-bundle patch `PaperCommonConnection.java:25` javap 实证），26.x 才改名 `packetListener`。本线 `resolveListener` 以 `handle` 为主查、`packetListener` 为回退（双名反射 + `Reflect.getOr` 父类链查找对冲未来漂移）；**删除回退分支会使 install 恒 miss → 静默失去时序整形（fail-open 无报错），维护时严禁"清理"该回退**。

**防护与降级**：无证据绝不发送（~100KB 载荷给 vanilla 客户端会命中未知通道 32767 断连）；vanilla/慢网客户端走 **3s 超时放行**（配方书晚到上界，`ClientboundUpdateRecipesPacket.Entry` 自包含 RecipeDisplay 无损坏面）；一切内部失败（反射漂移双名回退 / 管线异常 fail-open / reconfigure 经 `Channel.attr` 跳过）降级为现状时序（JEI 警告依旧，无新增损害）。扣留期又来 UpdateRecipes（进服 3s 内 /reload 竞态）按原 wire 序放行被扣包再透传新包。

**已知局限（后续工单）**：① reconfigure（服务端发起重配）后不重发——通道集合从 play 相位携带、无 register 事件可作证据，JEI 用旧 synced 配方重启（无警告但配方陈旧）；② `/reload` 后不重发（上游 `reloadResources` 对全体玩家重发）；③ neoforge 腿（join+brand）仍为旧时序，NeoForge 客户端同样晚于 UpdateRecipes（需 NeoForge 实机环境验证后再对齐）。

### 5.3 neoforge:recipe_content（NeoForge 加载器 wire，参考 Mrbysco 1.21.11）

```
recipeTypes: VarInt(count) + RECIPE_TYPE 注册表 id 串（HashSet collection）
recipes:    VarInt(count) + RecipeHolder.STREAM_CODEC 列表
+ 补发 ClientboundUpdateTagsPacket(serializeTagsToNetwork)
```

触发 = `PlayerJoinEvent` + brand=="neoforge"（**保留旧行为**——NeoForge 客户端连 Paper 服处于 vanilla 模式，其通道声明行为不可依赖；此路径即本线旧桥已实机验证的原行为，1.21.11-b1~b4 发版实证）。聊天提示仅此路径保留。

## 6. 尺寸模型（字节上限真值见 [02-network-protocol.md](02-network-protocol.md) §5.2）

- **32767** = 客户端对**未知通道** custom payload 的 discarded 解码上限（超过断连）——对发给 vanilla/未装 mod 客户端的任意通道成立，servux 的 PacketSplitter 32000 分片防的就是它。
- **已知通道不受此限**：Fabric API 把 `fabric:recipe_sync` 注册为 64MB large payload（客户端 codec + mixin 提限）。我方单包直发给 Fabric API 客户端安全（本线实机验证——旧桥同路径直发全量配方 >1MiB 场景，1.21.11-b1~b4）。
- jei:* S2C 恒小包（几十字节）；C2S 每帧 ≤32767 字节（vanilla 未知通道 `DiscardedPayload` 解码上限，超限在 netty 解码期拒绝、到不了插件代码）。
- **包长只限字节流，不限列表声明容量**：转移包列表解码若把声明 count 直达 `ArrayList` 构造容量——5 字节 VarInt 可声明 2^31，GB 级预分配的 `OutOfMemoryError` 是 Error，穿透全部 catch(Exception)，可达主线程。现行防护：列表预分配容量按 vanilla `ByteBufCodecs.collection(...)` decode 同源的 `Math.min(count, 65536)` 封顶（`AbstractRecipeTransferPacket.MAX_INITIAL_LIST_CAPACITY`；vanilla 1.21.11 反编译实证同款防御在位，26.x 同构）——超大声明在元素循环内字节耗尽 fail-fast、负数由 ArrayList 构造器抛 IAE，均被 `JeiServerPlayHandler` 逐包 catch(Exception) 记日志丢弃、连接保持（`RecipeTransferListBoundTest` 回归覆盖）。**已声明的平台级对照差异**：上游畸形 C2S 在 Fabric API/vanilla 协议层解码（STREAM_CODEC 于 netty 层执行），异常即断连；我方 C2S 经 plugin messaging 裸字节进入、解码发生在 handler 内，逐包 catch 记日志丢弃、连接保持——属 plugin messaging 路径的结构性设计（排错靠 C2S 处理失败日志而非客户端断连），非疏漏。

## 7. 模块结构（`mod/jei/`，自管形态——黄金模板 syncmatica）

```
app/JeiModule          enable(plugin)/disable()：配置→通道注册→监听器
JeiReference           全部常量单源（12 通道 id / C2S 清单 / 配置文件名新旧）
network/JeiServerPlayHandler   IPluginServerPlayHandler 实现（每通道一实例，字节级分发 + R3 门控）
network/JeiPacketSender        S2C NMS DiscardedPayload 直发
network/RecipeSyncJoinOrderer  fabric 腿进服时序整形（netty 出站扣包-等证据-放行，§5.2）
network/JeiServerPacketContext C2S 处理上下文（player + config + 回包）
network/payload/*      9 文件 = 8 现代 wire 包类 + 1 抽象基类（AbstractRecipeTransferPacket；类形态逐字段镜像上游）
network/payload/legacy/*  2 个 legacy 包类——wire 包口径共 10，对应 JeiReference 的 10 条 jei:* 通道
transfer/*             TransferOperation + BasicRecipeTransferHandlerServer
cheat/*                Cheats（权限纯函数 + 给/删/热键栏语义）+ GiveMode
recipesync/*           Fabric/Neoforge payload + RecipeSyncService（双触发）
config/JeiConfiguration enabled + cheat 三布尔 + 旧 jei-recipe-bridge.json 的 enabled 迁移
command/JeiCommand     /jei status|enable|disable
```

**无 per-player 状态**：cheat 是无状态请求-应答、transferId 是客户端侧关联键——无需 PlayerQuitEvent 清理。

## 8. 本线迁移实录（2026-10：mod/jeirecipebridge → mod/jei 手工重写）

本线原实现为 Mrbysco/JEIRecipeBridge 范式的"配方搬运桥"（7 文件：2 条 S2C 配方通道声明 + join/brand 触发直发，无 C2S、无 jei:* 通道、无 cheat/transfer）。本次迁移以 dev/26.2 线 `mod/jei/`（同上游范式）为黄金模板逐文件手工重写（仓库规则：jei 跨线 cherry-pick 禁止），行为差异与旧形态对照：

| 维度 | 旧（Mrbysco 范式，已删） | 新（mezz 最上游范式） |
|---|---|---|
| 协议面 | 2 条 S2C（配方同步） | 12 条（配方 2 + jei:* 10）+ 服务端行为层 |
| 触发 | join + brand 判定（对 vanilla 也发聊天） | fabric=RegisterChannel 声明门控 / neoforge=join+brand；vanilla 零打扰 |
| 分组 | O(serializers × recipes) 双重扫描 | 单遍 O(R) |
| 通道声明 | 仅 2 条 outgoing | 8 C2S 成对（ChannelManager）+ 2 recipe outgoing |
| 形态 | ModModule 接口壳（onRegister 忽略 manager） | syncmatica 式自管 enable/disable |
| 配置 | enabled 单键（jei-recipe-bridge.json） | enabled + cheat 三布尔（jei.json，迁移旧 enabled——只读不删旧文件） |
| 测试 | 0 | 常量对齐 / 权限矩阵 / 槽位校验 / 配置迁移 / 进服时序整形 / 转移列表边界 6 类单测 |

**配置兼容**：现役 `jei-recipe-bridge.json` 的 `enabled` 值在 `jei.json` 缺失/损坏时只读迁移（`JeiConfiguration.migrateLegacyEnabled`），旧文件保留不删（回滚路径）。

### 8.1 上游互 diff 分诊表（迁移前置核验）

迁移前对上游两分支（`OriginImpl/JustEnoughItems-26.2` vs `OriginImpl/JustEnoughItems-1.21.11`）做了协议承载面全目录机械 diff，作为"我方 dev 线实现（镜像 26.2）对 1.21.11 客户端零漂移"的传递性判据：

| 上游目录/文件 | 互 diff 结论 | 分档 |
|---|---|---|
| `common/network/packets/`（全树含 legacy/ + handlers/） | **零字节差** | tier-1 平移 |
| `common/transfer/BasicRecipeTransferHandlerServer.java` | 仅两条日志字符串折行格式差异（语义相同，非 wire） | tier-1 平移（日志文案取 dev 单行形态） |
| `common/util/ServerCommandUtil.java` | 逐字节相同 | tier-1 平移 |
| `fabric/config/ServerConfig.java` | 逐字节相同 | tier-1 平移 |
| Fabric API `fabric-recipe-api-v1`（1.21.11 分支 `RecipeSyncPayloadS2C`） | 与我方（承旧桥 + dev 双源）Entry read/write 逐字一致 | tier-1 平移 |

### 8.2 1.21.11 适配面清单（与 dev/26.2 模板的全部差异点）

| # | 适配点 | 性质 |
|---|---|---|
| 1 | `RecipeSyncJoinOrderer.resolveListener` 字段主备反转（`handle` 为主——1.21.11 PaperCommonConnection 唯一字段） | 代码级必改（§5.2） |
| 2 | 工具链 Java 21（dev 线 25）——27 文件语法面扫描无 22+ 特性，编译实证零降档 | 编译驱动验证 |
| 3 | NMS/Paper 触面 37 符号逐项实证（`Permissions`/`HandlerNames.ENCODER`/`AsyncPlayerConnectionConfigureEvent`/`DiscardedPayload` 两参构造（Paper patch）/`Recipe.STREAM_CODEC` 等） | jar/javap/官方映射实证，编译零断裂 |
| 4 | `DiscardedPayload(id, bytes)` 两参构造：vanilla 1.21.11 仅单参，**Paper dev-bundle patch 补齐两参**——旧桥 `RecipeSyncHandler.java:165` 同习语已实机验证 | 平台差异记录 |
| 5 | 版本引用注释/日志（上游分支/commit/JEI 版本号口径） | 注释级 |

### 8.3 删桥实录

`mod/jeirecipebridge/` 7 文件整体删除（`JeiRecipeBridgeReference` / `app/JeiRecipeBridgeModule` / `RecipeSyncHandler` / `command/JeiCommand` / `config/JeiConfiguration` / `payload/FabricRecipeSyncPayload` / `payload/NeoforgeRecipeSyncPayload`）。理由：与新模块并存 = `fabric:recipe_sync` outgoing 双注册 + join 双监听 → 配方包协议级重复下发；删除即最简状态。fabric/neoforge 两 payload 的 wire 编码体分别承袭进 `mod/jei/recipesync/`（§5.1 历史注）。主类 `VeryMcProto` 三处增量改（装配段 / 命令注册段类路径 / onDisable 挂 `JeiModule.disable()`），不整段照抄 dev 线（其 onDisable 含本线不存在的 servux scheduler 行）。

## 9. 实机验证清单（人工）

1. Fabric + JEI 客户端进服 → JEI **进服即**显示服务端配方（无 "recipe sync unavailable" 红字、无 "Loaded N vanilla recipes from client resources" 回退行）——时序整形器生效判定特征：客户端警告消失即通过；
2. cheat：创造模式拿物品（MOUSE_PICKUP/INVENTORY 两模式）、删除手持、热键栏放置；生存模式无权限时客户端收到拒绝提示（cheat_permission 纠正链）；
3. 配方转移：合成台打开 → JEI 转移按钮 → 物品正确入格 + 客户端收到成功回执；原料不足 → 失败回执 + 不动背包；
4. vanilla 客户端进服 → 零聊天骚扰、零断连（jei:* REGISTER 声明无害）；
5. NeoForge 客户端（若有）→ 配方 + tag 同步；
6. `/jei disable` → 新进服玩家不同步、在线玩家 C2S 被静默丢弃、**无人被踢**；
7. **本线观察项（后续工单）**：`AsyncPlayerConnectionConfigureEvent` 在 1.21.11 的 fire 时机/线程语义与 26.2 等价性（类存在已 jar 实证，行为待实机；若不等价，fabric 腿时序整形 fail-open 退化为 join 后 RegisterChannel 触发 = 旧桥行为，不劣化）。

## 10. 上游源码索引（OriginImpl/JustEnoughItems-1.21.11/）

| 我方实现 | 上游权威文件 |
|---|---|
| payload/* 10 类 | `Common/src/main/java/mezz/jei/common/network/packets/*`（含 legacy/） |
| 通道注册面 | `Fabric/src/main/java/mezz/jei/fabric/network/ServerNetworkHandler.java`（PayloadTypeRegistry + GlobalReceiver 全清单） |
| transfer/* | `Common/.../common/transfer/BasicRecipeTransferHandlerServer.java` + `RecipeTransferUtil.java`（validateSlots） |
| cheat/Cheats | `Common/.../common/util/ServerCommandUtil.java` |
| 权限三切面默认值 | `Fabric/.../fabric/config/ServerConfig.java` |
| 客户端门禁 | `Fabric/.../fabric/network/ConnectionToServer.java`（isJeiOnServer/isSameModLoader）+ `Library/.../library/startup/JeiStarter.java`（verifyClientRecipes） |
| fabric:recipe_sync wire | FabricMC/fabric 分支 1.21.11（`OriginImpl/fabric-1.21.11/`）`fabric-recipe-api-v1/.../impl/recipe/sync/*`（RecipeSyncPayloadS2C / RecipeSyncImpl） |
| 进服时序不变量 | FabricMC/fabric 分支 1.21.11 `fabric-lifecycle-events-v1/.../mixin/event/lifecycle/PlayerListMixin.java` + `fabric-networking-api-v1/.../client/ClientPlayNetworkAddon.java` |

---

> **回到索引**：[00-INDEX.md](00-INDEX.md) · 项目权威说明：[../AGENTS.md](../AGENTS.md)
