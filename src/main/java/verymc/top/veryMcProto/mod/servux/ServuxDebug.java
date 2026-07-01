package verymc.top.veryMcProto.mod.servux;

import java.util.Set;

import verymc.top.veryMcProto.framework.debug.DebugSystem;

/**
 * Servux 调试日志门面（mod 层）。
 *
 * <p>servux 协议栈（含 {@code framework/} 底层：DataProviderManager / Perms / ProtocolChannel /
 * LifecycleBridge / ServerPlayHandler 等）唯一的调试日志入口。基于框架通用引擎 {@link DebugSystem}，
 * 持有【独立】的 master 总开关 + 分类集合，与 {@code mod/syncmatica/util/SyncmaticaDebug} 完全隔离——
 * {@code /servux debug} 只控制 servux，不影响 syncmatica（反之亦然）。
 *
 * <p><b>层次说明</b>：{@code framework/} 包虽在框架层，但实为 servux 移植时抽象的底层、当前<b>只服务
 * servux</b>（syncmatica 完全独立不用 framework，jei 不用 debug）。故 framework 层的 debug 日志也走本类，
 * 一套 {@code /servux debug} 一键控制 servux 全栈。承认 framework = servux 底层。
 *
 * <h2>用法</h2>
 * <pre>
 *   ServuxDebug.log(Cat.HANDSHAKE, "sendMetadata → " + name);
 *   if (ServuxDebug.isOn(Cat.PACKET)) { ServuxDebug.log(Cat.PACKET, describeExpensive()); }
 * </pre>
 *
 * <p>输出前缀 {@code [DBG/servux/<cat>]}，与 syncmatica 的 {@code [DBG/syncmatica/<cat>]} 便于 grep 区分。
 *
 * <p><b>不再有无分类 debug 入口</b>：旧 {@code ServuxLog.debug} / {@code framework Debug.debug} /
 * {@code Log.debugLog} 已全部删除或迁移到分类体系——所有 debug 都受 {@code /servux debug cat} 管控，
 * 杜绝 {@code cat none} 后仍刷屏的「无分类后门」。业务日志（warn/error/info）走 {@code mod/servux/util/Log.java}。
 *
 * <p><b>开关来源优先级</b>：命令 {@code /servux debug}（运行时即时生效）&gt; 配置 {@code servux_main.debug_log}
 * （{@code ConfigProvider#onConfigLoaded} 同步）&gt; 编译期 {@link ServuxReference#DEV_DEBUG}（最终兜底）。
 *
 * <p><b>线程安全</b>：由 {@link DebugSystem} 保证（copy-on-write active + volatile）。
 */
public final class ServuxDebug
{
    private ServuxDebug() { }

    /** Servux 调试分类（按子系统划分，可独立开关，避免全开刷屏）。 */
    public enum Cat
    {
        /** 框架生命周期：onEnable / ServerLoad / Provider 注册与启停 / tick 调度。 */
        LIFECYCLE,
        /** 握手：metadata 发送/接收、onPlayerJoin/RegisterChannel、客户端通道声明。 */
        HANDSHAKE,
        /** 网络层：plugin messaging 通道注册/反注册、send 各失败原因、字节大小、C2S 接收。 */
        NETWORK,
        /** 数据包：S2C/C2S packet type、encodeServerData/sendPlayPayload、PacketSplitter 分包、失败计数。 */
        PACKET,
        /** 周期：tickProviders、provider.tick、数据采集（TPS/MobCap/weather/structures）。 */
        TICK,
        /** 权限：Perms.check 节点 / level / 是否显式设置 / 结果。 */
        PERMISSION,
        /** Provider 状态机：enabled / registered / setProviderEnabled / updatePacketHandlerRegistration。 */
        PROVIDER,
        /** 配置：readFromConfig / writeToConfig / setting 变更。 */
        CONFIG,
        /** EasyPlace：use_item_on 包拦截 / protocolValue 编码判定 / 解码 / 放置 / ack。 */
        EASYPLACE,
        /** Schematic 系统：投影投递/粘贴/文件 IO（迁移自旧 Log.debugLog）。 */
        SCHEMATIC
    }

    /** servux 独立调试引擎实例。初值取编译期 DEV_DEBUG 兜底，由 onServerLoad 用配置覆盖、命令即时切换。 */
    public static final DebugSystem<Cat> SYS = new DebugSystem<>("servux", Cat.class, ServuxReference.DEV_DEBUG);

    // ───── 静态委托（签名与旧 framework Debug 对称，调用点机械替换 Debug → ServuxDebug） ─────
    public static boolean master() { return SYS.master(); }
    public static void setMaster(boolean enabled) { SYS.setMaster(enabled); }
    public static boolean toggleMaster() { return SYS.toggleMaster(); }
    public static boolean isOn(Cat cat) { return SYS.isOn(cat); }
    public static Set<Cat> active() { return SYS.active(); }
    /** 当前启用分类的小写名集合（持久化导出用）。 */
    public static Set<String> activeNames() { return SYS.activeNames(); }
    public static void enable(Cat cat) { SYS.enable(cat); }
    public static void disable(Cat cat) { SYS.disable(cat); }
    public static boolean toggle(Cat cat) { return SYS.toggle(cat); }
    public static void enableAll() { SYS.enableAll(); }
    public static void clearCats() { SYS.clearCats(); }
    public static void log(Cat cat, String msg) { SYS.log(cat, msg); }
    public static void log(Cat cat, String msg, Throwable t) { SYS.log(cat, msg, t); }
    public static Cat parseCat(String name) { return SYS.parseCat(name); }
    public static String statusLine() { return SYS.statusLine(); }
}
