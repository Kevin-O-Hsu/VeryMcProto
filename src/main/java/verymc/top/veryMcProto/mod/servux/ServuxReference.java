package verymc.top.veryMcProto.mod.servux;

import net.minecraft.resources.Identifier;
import verymc.top.veryMcProto.Reference;

/**
 * Servux mod 专用常量（mod 层）。
 *
 * <p>协议握手字段 {@link #MOD_STRING} = {@code servux-fabric-26.1.2-b1}（26.1 起 masa 客户端把该字段升级为
 * <b>硬门禁</b>：{@code servux.startsWith("servux-" + MOD_TYPE + "-" + MC_VERSION)}，MOD_TYPE 恒为 "fabric"、
 * MC_VERSION 为 Fabric loader 的精确上游 id——见 minihud 26.1 {@code HudDataManager} 四处同构校验）。
 * 故服务端必须以 "fabric" 自称且版本段与上游精确 id 前缀一致，否则客户端整通道退网（静默失效）。
 *
 * <p><b>通道网络名</b>（{@link Identifier}）：严格取自原版各 Handler 的 {@code CHANNEL_ID} 字段（源码实证，
 * 非文档表格）。注意 provider 逻辑名（hud_data / tweaks_data / ...）≠ 通道网络名：
 * <ul>
 *   <li>HUD → {@code servux:hud_metadata}（provider 逻辑名 hud_data）</li>
 *   <li>Entities → {@code servux:entity_data}</li>
 *   <li>Tweaks → {@code servux:tweaks}（逻辑名 tweaks_data）</li>
 *   <li>Structures → {@code servux:structures}（逻辑名 structure_bounding_boxes）</li>
 *   <li>Litematics → {@code servux:litematics}（逻辑名 litematic_data）</li>
 * </ul>
 */
public final class ServuxReference
{
    private ServuxReference() { }

    public static final String MOD_ID = "servux";
    public static final String MOD_NAME = "Servux";
    /** Minecraft 目标版本（源自框架 Reference 的版本单一来源，勿手写）。 */
    public static final String MC_VERSION = Reference.MC_VERSION;
    /** 插件版本（= MC 版本-b构建号，源自框架 Reference，勿手写）。 */
    public static final String MOD_VERSION = Reference.PLUGIN_VERSION;
    /**
     * 伪装类型：26.1 客户端按 {@code servux-fabric-<精确MC id>} 前缀硬校验，"paper" 会被四通道全部拒绝。
     * 见类 javadoc；PLUGIN_VERSION 自带上游精确 MC id（26.1.2-b1），前缀校验天然满足。
     */
    public static final String MOD_TYPE = "fabric";
    /** 协议握手字段（metadata 的 "servux" 字段值）。 */
    public static final String MOD_STRING = MOD_ID + "-" + MOD_TYPE + "-" + Reference.PLUGIN_VERSION;
    public static final boolean DEV_DEBUG = false;

    // ───── 5 条通道网络名（源码 CHANNEL_ID 实证）─────
    public static final Identifier CHANNEL_HUD = Identifier.fromNamespaceAndPath("servux", "hud_metadata");
    public static final Identifier CHANNEL_ENTITIES = Identifier.fromNamespaceAndPath("servux", "entity_data");
    public static final Identifier CHANNEL_TWEAKS = Identifier.fromNamespaceAndPath("servux", "tweaks");
    public static final Identifier CHANNEL_STRUCTURES = Identifier.fromNamespaceAndPath("servux", "structures");
    public static final Identifier CHANNEL_LITEMATICS = Identifier.fromNamespaceAndPath("servux", "litematics");

    // ───── 注册门禁提示文案（上游 lang en_us.json:128/:129 的单一定义等价物）─────
    // 我方无 lang 资源体系（StringUtils.translate 会给玩家显示裸键名），以常量承载 § 码字面文本；
    // § 码由客户端聊天渲染器解析（先例：LitematicsDataProvider 的 MSG_TASK_INSUFFICIENT）。
    /** 上游 servux.general.error.protocol_version_too_low；%s = Provider 逻辑名（如 hud_data）。 */
    public static final String MSG_PROTOCOL_VERSION_TOO_LOW =
            "§d%s§7: §6data provider failed to be connected; Your client protocol version is too low.\n§6Please upgrade the relevant mod.";
    /** 上游 servux.hud_data.error.insufficient_for_loggers；%s = Data Logger 名。 */
    public static final String MSG_INSUFFICIENT_FOR_LOGGERS =
            "§cServux: Insufficient Permissions for Data Logger '%s'.§r";
}
