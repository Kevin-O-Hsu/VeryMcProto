package verymc.top.veryMcProto.mod.carpet;

import net.minecraft.resources.Identifier;

/**
 * Carpet mod 专用常量（mod 层，第四模块）。上游对照 = {@code OriginImpl/fabric-carpet-26.2/}（tag v26.2）。
 *
 * <p>与既有三 mod 的 Reference 差异：carpet 的版本串域是<b>上游 carpet 自身的 mod_version</b>（逐字常量），
 * 不是本插件版本——P3 协议握手 HI 帧将携带该值，客户端 {@code ClientNetworkHandler} 对其做
 * {@code equals} 严格比较，发插件版本号（{@code 26.2-b3} 之类）必触发版本不匹配 warn
 * （docs/carpet/architecture.md §10 裁决；1.21.11 线该值为 "1.4.194"，无法从本仓库 gradle.properties 派生）。
 */
public final class CarpetReference
{
    private CarpetReference() { }

    public static final String MOD_ID = "carpet";
    public static final String MOD_NAME = "Carpet";
    /**
     * carpet 上游 mod_version 逐字值（OriginImpl/fabric-carpet-26.2/gradle.properties:15）。
     * 26.2 线恰与 mcVersion 同值纯系巧合，勿改用 Reference.PLUGIN_VERSION（见类 javadoc）。
     */
    public static final String CARPET_VERSION = "26.2";
    /** 协议握手标识（P3 HI 帧用；P1 仅常量，无网络行为）。 */
    public static final String MOD_STRING = MOD_ID + "-" + CARPET_VERSION;
    /** 通道网络名（上游 carpet/network/CarpetClient.java:48 逐字）。P3 接线，P1 仅声明。 */
    public static final Identifier CARPET_CHANNEL = Identifier.fromNamespaceAndPath("carpet", "hello");
    /** 上游 HI/HELLO 消息 key（CarpetClient.java:40-41 逐字，CompoundTag 内字符串键）。P3 使用。 */
    public static final String KEY_HI = "69";
    public static final String KEY_HELLO = "420";
    /** 规则持久化文件名（= SettingsManager 的 identifier + ".conf"，上游 getFile() 同式）。 */
    public static final String CONFIG_FILE_NAME = "carpet.conf";
    /** /carpet 命令权限节点（plugin.yml 声明；命名随 jei.command / syncmatica.command 扁平先例）。 */
    public static final String COMMAND_PERMISSION = "carpet.command";
}
