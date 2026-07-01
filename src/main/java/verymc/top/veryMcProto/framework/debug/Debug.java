package verymc.top.veryMcProto.framework.debug;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import verymc.top.veryMcProto.Reference;

/**
 * 统一调试日志门面（框架层，所有协议 mod 共用）。
 *
 * <p>解决旧实现里调试开关碎片化、且多为编译期常量（改了要重编译）的问题：
 * <ul>
 *   <li>{@link Reference#DEV_DEBUG}（编译期兜底）；</li>
 *   <li>{@code ServuxLog.debug}（走 {@code ConfigProvider.hasDebugMode}）；</li>
 *   <li>{@code DataProviderManager.debugLog}（走编译期 {@code Reference.DEV_DEBUG}）。</li>
 * </ul>
 *
 * <h2>宏开关</h2>
 * <ul>
 *   <li><b>总开关</b> {@link #master()}（{@code volatile}，<b>运行时可热切换，无需重编译</b>）。关闭时所有调试日志静默。</li>
 *   <li><b>分类</b> {@link #active()}：总开关开启后，仅启用的分类才输出，避免全开刷屏。</li>
 * </ul>
 * <b>开关来源优先级</b>：命令 {@code /servux debug}（运行时即时生效）&gt; 配置 {@code servux_main.debug_log}
 * （{@code onServerLoad} 同步）&gt; 编译期 {@link Reference#DEV_DEBUG}（最终兜底）。
 *
 * <h2>用法</h2>
 * <pre>
 *   // 普通日志（构造字符串成本低）
 *   Debug.log(Cat.NETWORK, "send " + channel + " bytes=" + n + " ok=" + ok);
 *
 *   // 高成本构造（如遍历 NBT）先判，避免白干
 *   if (Debug.isOn(Cat.HANDSHAKE)) { Debug.log(Cat.HANDSHAKE, describeNbt(meta)); }
 *
 *   // 无分类便捷入口（兼容旧 ServuxLog 语义，仅看 master 总开关）
 *   Debug.debug("...");
 * </pre>
 *
 * <p><b>线程安全</b>：plugin messaging 收发可能在 netty 线程触发，分类集合用 copy-on-write（每次改动复制一份
 * 新 {@link EnumSet} 后整体替换引用），读取走 {@code volatile}，无需加锁。
 */
public final class Debug
{
    private Debug() { }

    /** 调试分类。粒度按子系统划分，可独立开关，避免全开刷屏。 */
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
        EASYPLACE
    }

    /** 总开关（运行时可热切换）。初值取编译期 DEV_DEBUG 兜底，由 onServerLoad 用配置覆盖、命令即时切换。 */
    private static volatile boolean master = Reference.DEV_DEBUG;

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
            Reference.logger().info("[DBG/" + cat + "] " + msg);
        }
    }

    public static void log(Cat cat, String msg, Throwable t)
    {
        if (master && active.contains(cat))
        {
            Reference.logger().log(java.util.logging.Level.WARNING, "[DBG/" + cat + "] " + msg, t);
        }
    }

    /**
     * 无分类便捷入口：仅看 {@link #master()} 总开关。兼容旧 {@code ServuxLog.debug} 调用语义
     * （旧调用点无分类信息，统一映射为「总开关控制、不限分类」）。
     */
    public static void debug(String msg)
    {
        if (master)
        {
            Reference.logger().info("[DBG] " + msg);
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
        sb.append("debug master=").append(master ? "ON" : "OFF");
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
