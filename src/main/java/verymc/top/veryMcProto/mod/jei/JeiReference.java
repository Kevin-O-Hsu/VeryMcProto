package verymc.top.veryMcProto.mod.jei;

import net.minecraft.resources.Identifier;

/**
 * JEI 模块常量单源（mod 层）。上游 = mezz/JustEnoughItems 26.1 分支（JEI 29.37.0 / MC 26.1.2，
 * {@code OriginImpl/JustEnoughItems-26.1}，commit {@code ccc16e8}）。
 *
 * <p>通道 id 全部逐字镜像上游 {@code mezz.jei.common.network.packets.*} 的
 * {@code CustomPacketPayload.Type} 定义（{@code ModIds.JEI_ID = "jei"}），步骤 0 冻结清单实证。
 * 配方同步两条通道的 wire 归属：{@code fabric:recipe_sync} = Fabric API
 * {@code fabric-recipe-api-v1}（客户端经 {@code ClientRecipeSynchronizedEvent} 消费）；
 * {@code neoforge:recipe_content} = NeoForge 加载器层（参考 Mrbysco/JEIRecipeBridge 实现）。
 *
 * <p><b>客户端门禁（上游实证）</b>：JEI 客户端 {@code isJeiOnServer() = ClientPlayNetworking.canSend(jei:delete_player_item)}
 * ——即<b>服务端是否在 vanilla register 机制中声明过该通道</b>。服务端经 {@code ChannelManager.register}
 * （incoming + outgoing 成对注册）声明全部 C2S 通道后，客户端才解锁 cheat 给/删物品与网络版配方转移；
 * brand（{@code isSameModLoader}）仅影响客户端"配方同步缺失"警告文案，不门控功能。
 */
public final class JeiReference
{
    private JeiReference() { }

    /** 模块 id（日志 / 文档标识，非 wire 字段——JEI 协议无版本握手）。 */
    public static final String MOD_ID = "jei";

    // ───── jei:* 自有通道（S2C，服务端 → 客户端）─────

    /** cheat 权限同步（BOOL hasPermission + List&lt;UTF8&gt; allowedCheatingMethods）。 */
    public static final Identifier CHANNEL_CHEAT_PERMISSION = Identifier.fromNamespaceAndPath("jei", "cheat_permission");
    /** 配方转移结果回执（VAR_INT transferId + BOOL successful）。 */
    public static final Identifier CHANNEL_RECIPE_TRANSFER_RESULT = Identifier.fromNamespaceAndPath("jei", "recipe_transfer_result");

    // ───── jei:* 自有通道（C2S，客户端 → 服务端）─────

    /** 请求 cheat 权限（unit，无字段）。 */
    public static final Identifier CHANNEL_REQUEST_CHEAT_PERMISSION = Identifier.fromNamespaceAndPath("jei", "request_cheat_permission");
    /** cheat 给物品（ItemStack + GiveMode）。 */
    public static final Identifier CHANNEL_GIVE_ITEM_STACK = Identifier.fromNamespaceAndPath("jei", "give_item_stack");
    /** cheat 删除手持物品（ItemStack）。 */
    public static final Identifier CHANNEL_DELETE_PLAYER_ITEM = Identifier.fromNamespaceAndPath("jei", "delete_player_item");
    /** cheat 放置到热键栏（ItemStack + VAR_INT hotbarSlot）。 */
    public static final Identifier CHANNEL_SET_HOTBAR_ITEM_STACK = Identifier.fromNamespaceAndPath("jei", "set_hotbar_item_stack");
    /** 配方转移（counted 变体 + 结果回执）。 */
    public static final Identifier CHANNEL_RECIPE_TRANSFER_COUNTED_WITH_RESULT = Identifier.fromNamespaceAndPath("jei", "recipe_transfer_counted_with_result");
    /** 配方转移（结果回执变体）。 */
    public static final Identifier CHANNEL_RECIPE_TRANSFER_WITH_RESULT = Identifier.fromNamespaceAndPath("jei", "recipe_transfer_with_result");
    /** legacy 配方转移（counted，旧 JEI 客户端兼容，无回执）。 */
    public static final Identifier CHANNEL_RECIPE_TRANSFER_COUNTED_LEGACY = Identifier.fromNamespaceAndPath("jei", "recipe_transfer_counted");
    /** legacy 配方转移（旧 JEI 客户端兼容，无回执）。 */
    public static final Identifier CHANNEL_RECIPE_TRANSFER_LEGACY = Identifier.fromNamespaceAndPath("jei", "recipe_transfer");

    /** 全部 C2S 通道（{@code JeiModule} 经 {@code ChannelManager} 成对注册的清单——声明缺一即客户端功能静默退网）。 */
    public static final Identifier[] C2S_CHANNELS = {
            CHANNEL_REQUEST_CHEAT_PERMISSION,
            CHANNEL_GIVE_ITEM_STACK,
            CHANNEL_DELETE_PLAYER_ITEM,
            CHANNEL_SET_HOTBAR_ITEM_STACK,
            CHANNEL_RECIPE_TRANSFER_WITH_RESULT,
            CHANNEL_RECIPE_TRANSFER_COUNTED_WITH_RESULT,
            CHANNEL_RECIPE_TRANSFER_LEGACY,
            CHANNEL_RECIPE_TRANSFER_COUNTED_LEGACY,
    };

    // ───── 配方同步层通道（S2C，loader 层协议）─────

    /** Fabric API 配方同步（{@code fabric-recipe-api-v1} 的 {@code ClientboundRecipeSyncPayload.TYPE}）。 */
    public static final Identifier CHANNEL_FABRIC_RECIPE_SYNC = Identifier.fromNamespaceAndPath("fabric", "recipe_sync");
    /** NeoForge 配方内容（NeoForge 加载器层，wire 参考 Mrbysco 实现）。 */
    public static final Identifier CHANNEL_NEOFORGE_RECIPE_CONTENT = Identifier.fromNamespaceAndPath("neoforge", "recipe_content");

    // ───── 配置文件 ─────

    /** 新配置文件名（enabled + cheat 三布尔）。 */
    public static final String CONFIG_FILE_NAME = "jei.json";
    /** 旧模块配置文件名（仅 enabled 一项；首启迁移其键值，杜绝静默翻转）。 */
    public static final String LEGACY_CONFIG_FILE_NAME = "jei-recipe-bridge.json";
}
