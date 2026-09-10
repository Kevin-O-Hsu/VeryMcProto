package verymc.top.veryMcProto.mod.syncmatica.util;

import java.util.Set;

import verymc.top.veryMcProto.framework.debug.DebugSystem;

/**
 * Syncmatica 调试日志门面（mod 层，与 {@code mod/servux/ServuxDebug}【完全独立】）。
 *
 * <p>syncmatica 与 servux 是两个独立协议 mod，调试状态必须分开——{@code /syncmatica debug} 只控制
 * syncmatica 自己的日志，绝不影响 servux（反之亦然）。本类持有【独立】的 master 总开关 + 分类集合，
 * 基于框架通用引擎 {@link DebugSystem}（与 ServuxDebug 共用引擎代码，但实例隔离）。
 *
 * <h2>用法</h2>
 * <pre>
 *   SyncmaticaDebug.log(Cat.HANDSHAKE, "tryStartHandshake → " + name);
 *   if (SyncmaticaDebug.isOn(Cat.NETWORK)) { SyncmaticaDebug.log(Cat.NETWORK, describeExpensive()); }
 * </pre>
 *
 * <p>输出前缀 {@code [DBG/syncmatica/<cat>]}，便于与 servux 的 {@code [DBG/servux/<cat>]} 日志区分 grep。
 *
 * <p><b>不再有无分类 debug 入口</b>：旧 {@code SyncmaticaLog.debug} 已迁移到本类分类体系（DATA）——
 * 所有 debug 都受 {@code /syncmatica debug cat} 管控，杜绝 {@code cat none} 后仍刷屏。业务日志
 * （warn/error/info）走 {@code SyncmaticaLog}。
 *
 * <p><b>开关来源</b>：命令 {@code /syncmatica debug}（运行时即时生效）。<b>持久化</b>：master + 分类经
 * {@code SyncmaticaContext.saveConfiguration} 以顶层 {@code debugLog} 子对象写入 {@code syncmatica-config.json}，
 * 启动时 restore 恢复（docs/24 §2.3）。
 *
 * <p><b>线程安全</b>：由 {@link DebugSystem} 保证（copy-on-write active + volatile）。
 */
public final class SyncmaticaDebug
{
    private SyncmaticaDebug() { }

    /** Syncmatica 调试分类（按 syncmatica 子系统划分，独立于 servux 的分类）。 */
    public enum Cat
    {
        /** 生命周期：模块 enable/disable、玩家进出服。 */
        LIFECYCLE,
        /** 握手：onRegisterChannel、tryStartHandshake、VersionHandshakeServer、FeatureExchange、CONFIRM_USER。 */
        HANDSHAKE,
        /** 网络层：sendPacket 发送、C2S 接收、listening 状态。 */
        NETWORK,
        /** 包派发：onPacket exchange 命中/未命中、PacketType 解析。 */
        PACKET,
        /** Exchange 生命周期：startExchange / notifyClose / close / succeed。 */
        EXCHANGE,
        /** 数据/文件：ServerPlacement 读写、SyncmaticManager saveServer/loadServer、normalizeFileName（迁移自旧 SyncmaticaLog.debug）。 */
        DATA
    }

    /** syncmatica 独立调试引擎实例。初值 false（生产静默），由 {@code /syncmatica debug on} 即时开启。 */
    public static final DebugSystem<Cat> SYS = new DebugSystem<>("syncmatica", Cat.class, false);

    // ───── 静态委托（外部 API 不变，~20 处调用点零改动） ─────
    public static boolean master() { return SYS.master(); }
    public static void setMaster(boolean enabled) { SYS.setMaster(enabled); }
    public static boolean toggleMaster() { return SYS.toggleMaster(); }
    public static boolean isOn(Cat cat) { return SYS.isOn(cat); }
    public static Set<Cat> active() { return SYS.active(); }
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
