package verymc.top.veryMcProto.framework.debug;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import verymc.top.veryMcProto.Reference;

/**
 * 通用调试日志引擎（框架层，所有协议 mod 共用一份，消灭 copy-paste）。
 *
 * <p>每个 mod 持有自己的 {@link DebugSystem} 实例（各自独立的 master 总开关 + 分类集合），
 * 实现「不同 mod 的 debug log 完全分开」——例如 {@code /servux debug} 只控制 servux 实例，
 * {@code /syncmatica debug} 只控制 syncmatica 实例，互不影响。
 *
 * <p>mod 层用法：定义自己的 {@code Cat} 枚举 + 一个 {@code public static final DebugSystem<Cat> SYS}
 * 实例 + 一组静态委托方法（见 {@code mod/servux/ServuxDebug.java} / {@code mod/syncmatica/util/SyncmaticaDebug.java}
 * 范式）。调用点用 {@code XxxDebug.log(Cat.X, "...")}，受 {@code /xxxmod debug cat} 控制。
 *
 * <h2>宏开关（两个正交维度，皆开才输出）</h2>
 * <ul>
 *   <li><b>总开关</b> {@link #master()}（{@code volatile}，运行时可热切换，无需重编译）。关闭时静默。</li>
 *   <li><b>分类</b> {@link #active()}：总开关开启后，仅启用的分类才输出，避免全开刷屏。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：plugin messaging 收发可能在 netty 线程触发，分类集合用 copy-on-write
 * （每次改动复制一份新 {@link EnumSet} 后整体替换引用），读取走 {@code volatile}，无需加锁。
 *
 * @param <C> 该 mod 的调试分类枚举类型
 */
public final class DebugSystem<C extends Enum<C>>
{
    /** 日志前缀标识 + statusLine 标签，如 "servux" / "syncmatica"。输出 {@code [DBG/<tag>/<cat>]}。 */
    private final String tag;
    /** Cat 枚举类（EnumSet/Enum.valueOf 用）。 */
    private final Class<C> catType;

    private volatile boolean master;
    private volatile EnumSet<C> active;

    public DebugSystem(String tag, Class<C> catType, boolean defaultMaster)
    {
        this.tag = tag;
        this.catType = catType;
        this.master = defaultMaster;
        this.active = EnumSet.noneOf(catType);
    }

    // ───── 总开关 ─────
    public boolean master() { return master; }

    public void setMaster(boolean enabled) { master = enabled; }

    /** 总开关切换并返回新值（命令用）。 */
    public boolean toggleMaster() { master = !master; return master; }

    // ───── 分类 ─────
    public boolean isOn(C cat) { return master && active.contains(cat); }

    /** 当前启用的分类（返回副本，调用方可安全遍历/展示）。{@code active} 字段恒为 {@link EnumSet}，copyOf 对空集亦安全。 */
    public Set<C> active() { return EnumSet.copyOf(active); }

    public void enable(C cat)
    {
        EnumSet<C> next = EnumSet.copyOf(active);
        next.add(cat);
        active = next;
    }

    public void disable(C cat)
    {
        EnumSet<C> next = EnumSet.copyOf(active);
        next.remove(cat);
        active = next;
    }

    /** 切换单个分类并返回该分类新状态（命令用）。 */
    public boolean toggle(C cat)
    {
        if (active.contains(cat)) { disable(cat); return false; }
        enable(cat); return true;
    }

    public void enableAll() { active = EnumSet.allOf(catType); }

    public void clearCats() { active = EnumSet.noneOf(catType); }

    // ───── 输出 ─────
    public void log(C cat, String msg)
    {
        if (master && active.contains(cat))
        {
            Reference.logger().info("[DBG/" + tag + "/" + cat.name() + "] " + msg);
        }
    }

    public void log(C cat, String msg, Throwable t)
    {
        if (master && active.contains(cat))
        {
            Reference.logger().log(java.util.logging.Level.WARNING, "[DBG/" + tag + "/" + cat.name() + "] " + msg, t);
        }
    }

    // ───── 命令辅助 ─────
    /** 解析分类名（不区分大小写），找不到返回 null。 */
    public C parseCat(String name)
    {
        if (name == null) { return null; }
        try { return Enum.valueOf(catType, name.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }

    /** 一行状态摘要（命令展示用）。格式：{@code <tag> debug master=ON|OFF | cats=[...]}. */
    public String statusLine()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(tag).append(" debug master=").append(master ? "ON" : "OFF");
        sb.append(" | cats=[");
        if (active.isEmpty()) { sb.append("none"); }
        else
        {
            boolean first = true;
            for (C c : active)
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
