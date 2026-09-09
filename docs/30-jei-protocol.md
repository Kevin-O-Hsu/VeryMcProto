# 30 · JEI 完整协议（Paper 服务端实现）

> **上游权威**：mezz/JustEnoughItems 分支 `26.1`（JEI 29.37.0 / MC 26.1.2 / Java 25，本地对照 `OriginImpl/JustEnoughItems-26.1/`，commit `ccc16e8`）。2026-09 起正式更换上游（原 Mrbysco/JEIRecipeBridge 已停更且只做过 1.21.11 的配方同步切面）；**此后 JEI 侧更新一律以最上游为准**。1.21.11 旧线（ver/1.21.11*）仍用原 JEI Recipe Bridge 实现——jei 模块跨线 cherry-pick 禁止，一律手工重写。
>
> 配方同步层的 wire 真权威是 **Fabric API** `fabric-recipe-api-v1`（github FabricMC/fabric 分支 26.1）；NeoForge 层是 NeoForge 加载器（wire 参考 `OriginImpl/JEIRecipeBridge-26.1/`）。

## 0. 协议全景（三层）

JEI 的"服务端协议"由三层构成，缺一层则对应功能面在 Paper 上缺失：

| 层 | 通道 | 方向 | 内容 | 客户端消费方 |
|---|---|---|---|---|
| **配方同步层** | `fabric:recipe_sync` | S2C | 服务端全配方表（按 serializer 分组） | Fabric API `ClientRecipeSynchronizedEvent` → JEI `Internal.setClientSyncedRecipes` |
| | `neoforge:recipe_content` + `minecraft:tags`（UpdateTags） | S2C | 全配方 + tag 表 | NeoForge 加载器 → JEI |
| **jei:* 自有层** | 8 条 C2S + 2 条 S2C | 双向 | cheat 权限/给/删/热键栏 + 配方转移 | JEI 客户端 `mezz.jei.common.network.*` |
| **服务端行为层** | — | — | 权限判定 + 物品操作 + 转移算法 | （无 wire，由 C2S 处理器承载） |

**为什么配方不在 jei:* 通道里**：26.1 起（实为 1.21.2+）原版把配方表收敛到服务端，配方回传是**加载器层**职责（Fabric API `RecipeSynchronization` / NeoForge `recipe_content`）。JEI 服务端（Fabric mod 装在服务端时）只是在初始化时调 `RecipeSynchronization.synchronizeRecipeSerializer` 把 vanilla 序列化器标记进同步集合，真正发送仍由 Fabric API 完成。Paper 上没有这两层，故我们直接实现两条 loader 通道。

## 1. 通道声明契约（客户端功能门禁，最重要）

JEI 客户端判定"服务端有 JEI"的方式（`Fabric/.../network/ConnectionToServer.java`）：

```java
public boolean isJeiOnServer() {
    return ClientPlayNetworking.canSend(PacketDeletePlayerItem.TYPE);   // jei:delete_player_item
}
```

`canSend` 检查的是**服务端是否在 vanilla register 机制中声明过该通道**。Paper 侧的等价物 = `ChannelManager.register`（incoming + outgoing 成对注册，servux 五通道同款范式）。

- **声明全 8 条 C2S 通道**（`JeiReference.C2S_CHANNELS`）：少一条 = 客户端对该包 `canSend=false` 永不发送（cheat/transfer 对应功能静默退网，无任何报错——排错成本极高）。
- **brand 无关**：`isSameModLoader()`（server brand == "fabric"）只影响客户端"配方同步缺失"警告文案分支（`JeiStarter.verifyClientRecipes`），**不门控任何功能**。Paper brand = "Paper" 时客户端最多多一条黄/红字提示，前提是没有收到配方同步。
- **vanilla 客户端无害**：收到含 jei:* 的 REGISTER 声明会被原样忽略（`fabric:recipe_sync` 出站声明先例已在生产环境长跑）。
- **R3 硬约束**：`/jei disable` 只在 handler 内丢包，**通道永不注销**——注销 incoming 后客户端后续 C2S 会命中 Paper 未注册通道踢人（"Invalid payload"）。

## 2. jei:* 自有层 wire 逐字段

全部通道 id = `jei:<name>`（上游 `ModIds.JEI_ID`）。列表线序 = VarInt(size) + 元素（`ByteBufCodecs.list()` 等价物）；枚举 = VarInt 序号（`readEnum` 越界严格拒绝）；ItemStack = `ItemStack.STREAM_CODEC`（需 `RegistryFriendlyByteBuf`）。

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
2. `cheatModeEnabledForOp` → 判 `Permissions.COMMANDS_GAMEMASTER`（= 权限级 2，NMS 直用）；
3. `cheatModeEnabledForGive` → 判 `/give` 权限（Paper 映射：Bukkit 权限节点 `minecraft.command.give`，default op）。

配置三键落 `jei.json`，默认值对齐上游 `fabric/config/ServerConfig.java`：**op=true、creative=true、give=false**。给/删/热键栏的完整副作用（音效、`broadcastChanges`、`commands.give.success.single` 通知、溢出掉落 `makeFakeItem` 等）逐行镜像 `ServerCommandUtil`。

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

### fabric:recipe_sync（Fabric API `ClientboundRecipeSyncPayload` 逐字一致）

```
VarInt entryCount
└ 每 entry: Identifier(serializer id) + VarInt(n) + n × [ResourceKey<Recipe> + serializer.streamCodec(recipe)]
```

- **触发**：`PlayerRegisterChannelEvent(fabric:recipe_sync)`——对齐上游 `RecipeSyncImpl.sendRecipes` 的 `canSend(player)` 门控（只发给声明过能收的客户端；Fabric API 客户端注册 receiver 即声明；vanilla 零打扰）。分组为单遍 O(R)（IdentityHashMap 按 serializer 身份聚合）。
- **接收端**：Fabric API 展平排序 → `ClientRecipeSynchronizedEvent` → JEI `setClientSyncedRecipes`。read 端对未知 serializer 抛 `SkipPacketDecoderException`——Paper 配方恒 vanilla 序列化器，JEI 客户端 main entrypoint 已全量标记，无过滤对象。
- **未实现（有意）**：C2S `fabric:recipe_sync/supported_serializers`（configuration phase 支持集协商）——① Paper 上无截获 config phase 任意载荷的先例与必要（协商对象为零）；② 不声明该通道 → 客户端 `canSend=false` → 根本不发送 → 行为安全。**若上游后续让协商结果影响行为需重估**。

### neoforge:recipe_content（NeoForge 加载器 wire，参考 Mrbysco）

```
recipeTypes: VarInt(count) + RECIPE_TYPE 注册表 id 串（HashSet collection）
recipes:    VarInt(count) + RecipeHolder.STREAM_CODEC 列表
+ 补发 ClientboundUpdateTagsPacket(serializeTagsToNetwork)
```

触发 = `PlayerJoinEvent` + brand=="neoforge"（**保留旧行为**——NeoForge 客户端连 Paper 服处于 vanilla 模式，其通道声明行为不可依赖；此路径已在 1.21.11/26.1 实机验证）。聊天提示仅此路径保留。

## 6. 尺寸模型（与 docs/09 的 32767 表述勘误协调）

- **32767** = 客户端对**未知通道** custom payload 的 discarded 解码上限（超过断连）——对发给 vanilla/未装 mod 客户端的任意通道成立，servux 的 PacketSplitter 32000 分片防的就是它。
- **已知通道不受此限**：Fabric API 把 `fabric:recipe_sync` 注册为 64MB large payload（客户端 codec + mixin 提限）。我方单包直发给 Fabric API 客户端安全（26.1.2 实机验证，配方表全量 >1MiB 场景）。
- jei:* S2C 恒小包（几十字节）；C2S 受 vanilla custom payload 上限约束（≤32KiB 级），恶意超大列表被解码期上限天然截断。

## 7. 模块结构（`mod/jei/`，自管形态——黄金模板 syncmatica）

```
app/JeiModule          enable(plugin)/disable()：配置→通道注册→监听器
JeiReference           全部常量单源（12 通道 id / C2S 清单 / 配置文件名新旧）
network/JeiServerPlayHandler   IPluginServerPlayHandler 实现（每通道一实例，字节级分发 + R3 门控）
network/JeiPacketSender        S2C NMS DiscardedPayload 直发
network/JeiServerPacketContext C2S 处理上下文（player + config + 回包）
network/payload/*      10 个包类（record 化镜像上游；legacy/ 2 个）
transfer/*             TransferOperation + BasicRecipeTransferHandlerServer
cheat/*                Cheats（权限纯函数 + 给/删/热键栏语义）+ GiveMode
recipesync/*           Fabric/Neoforge payload + RecipeSyncService（双触发）
config/JeiConfiguration enabled + cheat 三布尔 + 旧 jei-recipe-bridge.json 的 enabled 迁移
command/JeiCommand     /jei status|enable|disable
```

**无 per-player 状态**：cheat 是无状态请求-应答、transferId 是客户端侧关联键——无需 PlayerQuitEvent 清理。

## 8. 与旧实现（mod/jeirecipebridge，已删）的差异清单

| 维度 | 旧（Mrbysco 范式） | 新（mezz 最上游范式） |
|---|---|---|
| 协议面 | 2 条 S2C（配方同步） | 12 条（配方 2 + jei:* 10）+ 服务端行为层 |
| 触发 | join + brand 判定（对 vanilla 也发聊天） | fabric=RegisterChannel 声明门控 / neoforge=join+brand；vanilla 零打扰 |
| 分组 | O(serializers × recipes) 双重扫描 | 单遍 O(R) |
| 通道声明 | 仅 2 条 outgoing | 8 C2S 成对（ChannelManager）+ 2 recipe outgoing |
| 形态 | ModModule 接口壳（onRegister 忽略 manager） | syncmatica 式自管 enable/disable |
| 配置 | enabled 单键（jei-recipe-bridge.json） | enabled + cheat 三布尔（jei.json，迁移旧 enabled） |
| 测试 | 0 | 常量对齐 / 权限矩阵 / 槽位校验 / 配置迁移 4 类单测 |

## 9. 实机验证清单（人工）

1. Fabric + JEI 客户端进服 → JEI 显示服务端配方（无 "recipe sync unavailable" 红字）；
2. cheat：创造模式拿物品（MOUSE_PICKUP/INVENTORY 两模式）、删除手持、热键栏放置；生存模式无权限时客户端收到拒绝提示（cheat_permission 纠正链）；
3. 配方转移：合成台打开 → JEI 转移按钮 → 物品正确入格 + 客户端收到成功回执；原料不足 → 失败回执 + 不动背包；
4. vanilla 客户端进服 → 零聊天骚扰、零断连（jei:* REGISTER 声明无害）；
5. NeoForge 客户端（若有）→ 配方 + tag 同步；
6. `/jei disable` → 新进服玩家不同步、在线玩家 C2S 被静默丢弃、**无人被踢**。

## 10. 上游源码索引（OriginImpl/JustEnoughItems-26.1/）

| 我方实现 | 上游权威文件 |
|---|---|
| payload/* 10 类 | `Common/src/main/java/mezz/jei/common/network/packets/*`（含 legacy/） |
| 通道注册面 | `Fabric/src/main/java/mezz/jei/fabric/network/ServerNetworkHandler.java`（PayloadTypeRegistry + GlobalReceiver 全清单） |
| transfer/* | `Common/.../common/transfer/BasicRecipeTransferHandlerServer.java` + `RecipeTransferUtil.java`（validateSlots） |
| cheat/Cheats | `Common/.../common/util/ServerCommandUtil.java` |
| 权限三切面默认值 | `Fabric/.../fabric/config/ServerConfig.java` |
| 客户端门禁 | `Fabric/.../fabric/network/ConnectionToServer.java`（isJeiOnServer/isSameModLoader）+ `Library/.../library/startup/JeiStarter.java`（verifyClientRecipes） |
| fabric:recipe_sync wire | FabricMC/fabric 分支 26.1 `fabric-recipe-api-v1/.../impl/recipe/sync/*`（ClientboundRecipeSyncPayload / RecipeSyncImpl） |
