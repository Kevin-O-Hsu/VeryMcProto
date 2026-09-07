package verymc.top.veryMcProto.mod.syncmatica;

import java.util.UUID;
import net.minecraft.resources.Identifier;
import verymc.top.veryMcProto.Reference;

/**
 * Syncmatica 常量（合并自原版 {@code Reference} + {@code Syncmatica} 的常量部分）。
 *
 * <p><b>Paper 适配</b>：
 * <ul>
 *   <li>{@link #MOD_VERSION} = 插件版本（如 {@code "1.21.11-b1"}，源自框架 Reference 版本单一来源），
 *       使 {@code FeatureSet.fromVersionString} 返回 null → 触发 FEATURE 交换 → 双方用全集
 *       FeatureSet（MODIFY/DISPLAY_NAME/CORE_EX/VERSION 全开）。{@code -b} 构建号后缀使其永远
 *       不命中 {@code ^\d+(\.\d+){2,4}$} 版本正则（连 "0.1.x" 兼容分支都不可能误入），行为比裸
 *       数字版本号更稳固；</li>
 *   <li>去掉原版 {@code Reference.isClient()/isIntegratedServer()/isOpenToLan()}（Paper 恒 dedicated server）；</li>
 *   <li>去掉 {@code StringTools.getModVersion}（Fabric Loader 依赖），版本用常量。</li>
 * </ul>
 */
public final class SyncmaticaReference
{
    public static final String MOD_ID = "syncmatica";
    public static final String MOD_NAME = "Syncmatica";
    /** 插件版本（= MC 版本-b构建号，源自框架 Reference，勿手写）；带 -b 后缀恒触发 FEATURE 交换，使双方用全集 FeatureSet。 */
    public static final String MOD_VERSION = Reference.PLUGIN_VERSION;

    /** 单物理通道（C2S/S2C 共用），内部第一字段是逻辑 PacketType Identifier。 */
    public static final Identifier NETWORK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "main");

    /** syncmatica 固定 UUID（原版 {@code Syncmatica.syncmaticaId}）。 */
    public static final UUID SYNCMATICA_ID = UUID.fromString("4c1b738f-56fa-4011-8273-498c972424ea");

    /** 投影文件子目录名（相对插件数据目录）。 */
    public static final String LITEMATIC_SUBDIR = "syncmatics";
    /** 服务端配置文件名（quota + debug）。 */
    public static final String CONFIG_FILE_NAME = "syncmatica-config.json";
    /** placement 注册表持久化文件名。 */
    public static final String PLACEMENTS_FILE_NAME = "placements.json";

    private SyncmaticaReference() { }
}
