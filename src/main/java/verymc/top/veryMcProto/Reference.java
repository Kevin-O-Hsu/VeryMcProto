package verymc.top.veryMcProto;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * 框架级全局常量与句柄（与具体协议 mod 解耦）。
 *
 * <p>平台 / 插件级常量放这里；某个协议 mod 专用的常量（如 Servux 的 MOD_ID、MOD_STRING、
 * 各通道 CHANNEL_ID）放各自 mod 包下的 {@code *Reference}。
 */
public final class Reference
{
    private Reference() { }

    public static final String PLUGIN_NAME = "VeryMcProto";
    /** Minecraft 目标版本。 */
    public static final String MC_VERSION = "1.21.11";
    /** 运行平台标识（用于协议握手字段）。 */
    public static final String PLATFORM = "paper";
    /** 开发调试开关（生产关闭）。 */
    public static final boolean DEV_DEBUG = false;

    private static volatile JavaPlugin plugin;

    /** 由主类 onEnable 注入插件句柄，供框架各处访问 Logger / 数据目录 / Bukkit 服务。 */
    public static void init(JavaPlugin plugin)
    {
        Reference.plugin = plugin;
    }

    /** 当前插件句柄（onEnable 之前为 null，返回 null 调用方需自检）。 */
    public static JavaPlugin plugin()
    {
        return plugin;
    }

    /** 插件 Logger；plugin 尚未初始化时回退到全局 Bukkit Logger。 */
    public static Logger logger()
    {
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
