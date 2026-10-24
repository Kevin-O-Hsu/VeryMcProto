package verymc.top.veryMcProto.mod.servux;

import net.minecraft.resources.Identifier;
import verymc.top.veryMcProto.Reference;

/**
 * Servux mod 专用常量（mod 层）。
 *
 * <p>协议握手字段 {@link #MOD_STRING} = {@code servux-fabric-1.21.11-b<构建号>}。<b>{@link #MOD_TYPE} 必须为
 * {@code "fabric"}</b>：v3 世代 masa 客户端（minihud 0.38.16+ / malilib 0.27.20+ / litematica 0.26.16+ /
 * tweakeroo 0.27.15+）在 metadata 校验中对 "servux" 字段做前缀硬门禁——minihud 三通道校验
 * {@code startsWith("servux-fabric")}（HudDataManager/EntityDataManager/DataStorage），litematica 与
 * tweakeroo 校验 {@code startsWith("servux-fabric-1.21.11")}（带 MC 版本段）；任一不满足即静默拒收该通道
 * 全部数据。旧世代（v2，≤0.38.13）无字符串校验，故本值对两代安全。
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
     * 服务端类型段（v3 客户端前缀门禁要求 "servux-fabric[-mcVersion]" 形状；见类 javadoc）。
     * 本插件实际运行于 Paper，但协议身份必须伪装为上游 Fabric 形态——与上游 MOD_TYPE 语义对齐。
     */
    public static final String MOD_TYPE = "fabric";
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
