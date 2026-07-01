package verymc.top.veryMcProto.mod.jeirecipebridge.config;

import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import verymc.top.veryMcProto.framework.util.JsonUtils;
import verymc.top.veryMcProto.mod.jeirecipebridge.JeiRecipeBridgeReference;

/**
 * JEI Recipe Bridge 模块配置（mod 层）。
 *
 * <p>持久化唯一一项 {@code enabled} 开关——控制玩家进服时是否同步配方（{@code /jei enable|disable} 切换）。
 *
 * <p><b>设计</b>：单实例（{@link verymc.top.veryMcProto.mod.jeirecipebridge.app.JeiRecipeBridgeModule} 启动时构造，
 * 构造即注册 {@link #getInstance()} 单例）。读写复用框架 {@link JsonUtils}（Gson pretty + 原子 tmp/move 落盘），
 * 与 {@code servux.json} 同一套机制。默认 {@code enabled=true}；文件缺失/损坏按默认值重建并落盘。
 *
 * <p>JEI 是<b>纯 S2C、一次性</b>同步，无 servux 的多 provider 结构、无 syncmatica 的 exchange 会话——故本类是
 * 整个模块唯一的运行时状态，{@code enabled=false} 时 {@code RecipeSyncHandler} 直接跳过进服推送
 * （通道仍声明，不踢人，与 syncmatica 软禁用的"通道保留"语义一致）。
 */
public final class JeiConfiguration
{
    private static volatile JeiConfiguration instance;

    /** enabled 默认值：模块启用即对进服玩家同步配方。 */
    private static final boolean DEFAULT_ENABLED = true;

    private final Path configFile;
    private volatile boolean enabled = DEFAULT_ENABLED;

    /** 由 {@code JeiRecipeBridgeModule} 构造；构造即设 {@link #getInstance()} 单例。 */
    public JeiConfiguration(Path dataFolder)
    {
        instance = this;
        this.configFile = dataFolder.resolve(JeiRecipeBridgeReference.CONFIG_FILE_NAME);
        load();
    }

    public static JeiConfiguration getInstance() { return instance; }

    public boolean isEnabled() { return enabled; }

    public Path getConfigFile() { return configFile; }

    /**
     * 设置 enabled 并立即落盘（{@code /jei enable|disable} 调用）。落盘失败仅记日志、不回滚内存——
     * 运行时行为以内存为准（与 servux provider toggle 一致）。
     */
    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
        save();
    }

    /** 从配置文件读 enabled；文件缺失/损坏按 {@link #DEFAULT_ENABLED} 重建。 */
    private void load()
    {
        var root = JsonUtils.parseJsonFileAsPath(configFile);
        if (root instanceof JsonObject obj)
        {
            enabled = JsonUtils.getBooleanOrDefault(obj, "enabled", DEFAULT_ENABLED);
        }
        else
        {
            // 文件缺失/损坏：按默认值落盘，避免每次启动重复重建。
            enabled = DEFAULT_ENABLED;
            save();
        }
    }

    private void save()
    {
        JsonObject root = new JsonObject();
        root.add("enabled", new JsonPrimitive(enabled));
        JsonUtils.writeJsonToFileAsPath(root, configFile);
    }
}
