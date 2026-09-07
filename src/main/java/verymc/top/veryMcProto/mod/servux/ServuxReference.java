package verymc.top.veryMcProto.mod.servux;

import net.minecraft.resources.Identifier;
import verymc.top.veryMcProto.Reference;

/**
 * Servux mod 专用常量（mod 层）。
 *
 * <p>协议握手字段 {@link #MOD_STRING} = {@code servux-paper-1.21.11-b1}（保持 {@code servux-} 前缀，
 * masa 客户端据此识别服务端装载了 Servux 协议——客户端仅做前缀识别与展示，不分段解析；
 * 具体版本协商走各通道的 protocol version）。
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
    public static final String MOD_TYPE = "paper";
    /** 协议握手字段（metadata 的 "servux" 字段值）；PLUGIN_VERSION 自带 MC 版本，故为三段式。 */
    public static final String MOD_STRING = MOD_ID + "-" + MOD_TYPE + "-" + Reference.PLUGIN_VERSION;
    public static final boolean DEV_DEBUG = false;

    // ───── 5 条通道网络名（源码 CHANNEL_ID 实证）─────
    public static final Identifier CHANNEL_HUD = Identifier.fromNamespaceAndPath("servux", "hud_metadata");
    public static final Identifier CHANNEL_ENTITIES = Identifier.fromNamespaceAndPath("servux", "entity_data");
    public static final Identifier CHANNEL_TWEAKS = Identifier.fromNamespaceAndPath("servux", "tweaks");
    public static final Identifier CHANNEL_STRUCTURES = Identifier.fromNamespaceAndPath("servux", "structures");
    public static final Identifier CHANNEL_LITEMATICS = Identifier.fromNamespaceAndPath("servux", "litematics");
}
