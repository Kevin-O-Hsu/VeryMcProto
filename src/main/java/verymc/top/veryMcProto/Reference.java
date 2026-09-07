package verymc.top.veryMcProto;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

/**
 * 框架级全局常量与句柄（与具体协议 mod 解耦）。
 *
 * <p>平台 / 插件级常量放这里；某个协议 mod 专用的常量（如 Servux 的 MOD_ID、MOD_STRING、
 * 各通道 CHANNEL_ID）放各自 mod 包下的 {@code *Reference}。
 *
 * <p><b>版本单一来源</b>：{@link #MC_VERSION} / {@link #PLUGIN_VERSION} 在类加载时从
 * {@code version.properties} 读取（构建时由 Gradle 依据 {@code gradle.properties} 的
 * {@code mcVersion} / {@code buildNumber} 展开）。插件版本 = {@code <MC版本>-b<构建号>}
 * （如 {@code 1.21.11-b1}），禁止在任何源码 / plugin.yml 手写版本号。
 */
public final class Reference
{
    private Reference() { }

    public static final String PLUGIN_NAME = "VeryMcProto";
    /** Minecraft 目标版本（= gradle.properties 的 mcVersion，如 "1.21.11"）。 */
    public static final String MC_VERSION = loadVersionProperty("mcVersion");
    /** 插件版本（= mcVersion-b构建号，如 "1.21.11-b1"；与 plugin.yml / jar 文件名一致）。 */
    public static final String PLUGIN_VERSION = loadVersionProperty("version");
    /** 运行平台标识（用于协议握手字段）。 */
    public static final String PLATFORM = "paper";
    /** 开发调试开关（生产关闭）。 */
    public static final boolean DEV_DEBUG = false;

    /** 类加载时读 version.properties（构建注入）；资源缺失时回退，保证开发环境不崩。 */
    private static String loadVersionProperty(String key)
    {
        try (InputStream in = Reference.class.getClassLoader().getResourceAsStream("version.properties"))
        {
            if (in != null)
            {
                java.util.Properties props = new java.util.Properties();
                props.load(in);
                String value = props.getProperty(key);
                if (value != null && !value.isBlank())
                {
                    return value;
                }
            }
        }
        catch (IOException ignored)
        {
        }
        return "dev-unknown";
    }

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
