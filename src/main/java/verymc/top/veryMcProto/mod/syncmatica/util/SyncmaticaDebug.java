package verymc.top.veryMcProto.mod.syncmatica.util;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import verymc.top.veryMcProto.Reference;

/**
 * Syncmatica 独立调试日志门面（mod 层，与框架层 {@link verymc.top.veryMcProto.framework.debug.Debug}【完全独立】）。
 *
 * <p><b>设计理由</b>：syncmatica 与 servux 是两个独立协议 mod，调试状态必须分开——
 * {@code /syncmatica debug} 只控制 syncmatica 自己的日志，绝不影响 servux（反之亦然）。
 * 故本类维护【自己】的 master 总开关 + 分类集合，<b>不复用</b>框架层 {@code Debug} 的全局状态。
 *
 * <h2>宏开关</h2>
 * <ul>
 *   <li><b>总开关</b> {@link #master()}（{@code volatile}，运行时热切换，无需重编译）；</li>
 *   <li><b>分类</b> {@link #active()}：总开关开启后，仅启用的分类才输出，避免全开刷屏。</li>
 * </ul>
 * <b>开关来源</b>：命令 {@code /syncmatica debug}（运行时即时生效）。<b>不持久化</b>（重启恢复 false）。
 *
 * <h2>用法</h2>
 * <pre>
 *   SyncmaticaDebug.log(Cat.HANDSHAKE, "tryStartHandshake → " + name);
 *   if (SyncmaticaDebug.isOn(Cat.NETWORK)) { SyncmaticaDebug.log(Cat.NETWORK, describeExpensive()); }
 * </pre>
 *
 * <p>输出前缀 {@code [DBG/syncm/<cat>]}，便于与 servux 的 {@code [DBG/<cat>]} 日志区分 grep。
 *
 * <p><b>线程安全</b>：plugin messaging 收发可能在 netty 线程触发，分类集合 copy-on-write
 * （每次改动复制一份新 {@link EnumSet} 后整体替换引用），读取走 {@code volatile}，无需加锁。
 */
public final class SyncmaticaDebug
{
    private SyncmaticaDebug() { }

    /**
     * Syncmatica 调试分类（按 syncmatica 子系统划分，独立于 servux 的分类）。
     */
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
        EXCHANGE
    }

    /** 总开关（运行时可热切换）。初值 false（生产静默），由 {@code /syncmatica debug on} 即时开启。 */
    private static volatile boolean master = false;

    /** 启用的分类集合。master=true 时这些分类才输出。copy-on-write（volatile 引用整体替换）。 */
    private static volatile EnumSet<Cat> active = EnumSet.noneOf(Cat.class);

    // ───── 总开关 ─────
    public static boolean master() { return master; }

    public static void setMaster(boolean enabled) { master = enabled; }

    /** 总开关切换并返回新值（命令用）。 */
    public static boolean toggleMaster() { master = !master; return master; }

    // ───── 分类 ─────
    public static boolean isOn(Cat cat) { return master && active.contains(cat); }

    /** 当前启用的分类（返回副本，调用方可安全遍历/展示）。{@code active} 字段恒为 {@link EnumSet}，copyOf 对空集亦安全。 */
    public static Set<Cat> active() { return EnumSet.copyOf(active); }

    public static void enable(Cat cat)
    {
        EnumSet<Cat> next = EnumSet.copyOf(active);
        next.add(cat);
        active = next;
    }

    public static void disable(Cat cat)
    {
        EnumSet<Cat> next = EnumSet.copyOf(active);
        next.remove(cat);
        active = next;
    }

    /** 切换单个分类并返回该分类新状态（命令用）。 */
    public static boolean toggle(Cat cat)
    {
        if (active.contains(cat)) { disable(cat); return false; }
        enable(cat); return true;
    }

    public static void enableAll() { active = EnumSet.allOf(Cat.class); }

    public static void clearCats() { active = EnumSet.noneOf(Cat.class); }

    // ───── 输出 ─────
    public static void log(Cat cat, String msg)
    {
        if (master && active.contains(cat))
        {
            Reference.logger().info("[DBG/syncm/" + cat + "] " + msg);
        }
    }

    public static void log(Cat cat, String msg, Throwable t)
    {
        if (master && active.contains(cat))
        {
            Reference.logger().log(java.util.logging.Level.WARNING, "[DBG/syncm/" + cat + "] " + msg, t);
        }
    }

    // ───── 命令辅助 ─────
    /** 解析分类名（不区分大小写），找不到返回 null。 */
    public static Cat parseCat(String name)
    {
        if (name == null) { return null; }
        try { return Cat.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }

    /** 一行状态摘要（命令展示用）。 */
    public static String statusLine()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("syncmatica debug master=").append(master ? "ON" : "OFF");
        sb.append(" | cats=[");
        if (active.isEmpty()) { sb.append("none"); }
        else
        {
            boolean first = true;
            for (Cat c : active)
            {
                if (!first) { sb.append(","); }
                sb.append(c.name().toLowerCase(Locale.ROOT));
                first = false;
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
