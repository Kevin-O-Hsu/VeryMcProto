package verymc.top.veryMcProto.framework.debug;

/**
 * 框架层调试日志门面（{@code framework/} 自有，与具体协议 mod 解耦）。
 *
 * <p><b>解耦命门</b>：{@code framework/} 各类（ProtocolChannel / ServerPlayHandler /
 * {@link verymc.top.veryMcProto.framework.event.LifecycleBridge} / {@link verymc.top.veryMcProto.framework.permission.Perms}
 * / {@link verymc.top.veryMcProto.framework.dataproviders.DataProviderManager} / IPluginServerPlayHandler）
 * 不再直接 {@code import} 某个 mod 的 DebugSystem 实例（原硬绑 {@code mod.servux.ServuxDebug}，
 * 违背"framework 与 mod 解耦"——历史技术债已清偿）。本门面持一个
 * {@link DebugSystem} 引用，由采用框架的协议 mod 在 {@code onRegister} 时 {@link #bind} 注入
 * 自己的实例。
 *
 * <p><b>分类按字符串名</b>（如 {@code "network"} / {@code "packet"} / {@code "lifecycle"}），
 * 由底层 {@link DebugSystem#parseCat} 解析为该 mod 的 {@code Cat} 枚举——故 framework 不依赖
 * 任何 mod 的 {@code Cat} 类型。命名约定：小写、与 servux 的 {@code ServuxDebug.Cat} 枚举名
 * 一致（{@code NETWORK}→{@code "network"}），保证注入 servux 实例时 parseCat 命中。
 *
 * <p><b>行为完全不变</b>：{@code bind} 后 framework 日志仍走注入的 mod 实例（当前
 * {@code ServuxDebug.SYS}），受 {@code /servux debug} 命令控制、输出前缀不变
 * （{@code [DBG/servux/<cat>]}）。{@code bind} 前（或未注入）走 {@link #FALLBACK} 自建实例
 * （{@code master=false} 静默，与编译期 {@code DEV_DEBUG=false} 一致——framework 日志默认本就静默）。
 *
 * <p><b>线程安全</b>：{@code bound} 为 {@code volatile}，{@code bind} 仅在 {@code onEnable}
 * 注册阶段（主线程）发生一次，之后只读。
 *
 * @see DebugSystem
 * @see verymc.top.veryMcProto.mod.servux.ServuxDebug
 */
public final class FrameworkDebug
{
    private FrameworkDebug() { }

    /**
     * fallback 分类枚举（{@link #FALLBACK} 实例用）。命名与 {@code ServuxDebug.Cat} 对齐，
     * 仅用于 {@code bind} 前的静默兜底——实际运行时 framework 日志几乎总走 bind 后的 servux 实例。
     */
    public enum Cat
    {
        LIFECYCLE, HANDSHAKE, NETWORK, PACKET, TICK, PERMISSION, PROVIDER, CONFIG
    }

    /** fallback 实例（无 mod 注入时用；{@code master=false} 恒静默）。 */
    private static final DebugSystem<Cat> FALLBACK = new DebugSystem<>("framework", Cat.class, false);

    /** 当前绑定的实例（bind 后为 servux/syncmatica 等 mod 的 DebugSystem；bind 前为 FALLBACK）。 */
    private static volatile DebugSystem<?> bound = FALLBACK;

    /**
     * 由采用框架的协议 mod（当前 servux）注入自己的 {@link DebugSystem} 实例。
     * 一经绑定即覆盖 fallback；之后 framework 层日志走该实例。
     */
    public static void bind(DebugSystem<?> sys)
    {
        if (sys != null) { bound = sys; }
    }

    /** 当前是否注入了 mod 实例（true=走 mod 的 DebugSystem，受其命令控制）。 */
    public static boolean isBound() { return bound != FALLBACK; }

    public static boolean isOn(String cat) { return bound.isOn(cat); }

    public static void log(String cat, String msg) { bound.log(cat, msg); }

    public static void log(String cat, String msg, Throwable t) { bound.log(cat, msg, t); }
}
