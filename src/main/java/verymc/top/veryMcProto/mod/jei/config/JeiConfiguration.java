package verymc.top.veryMcProto.mod.jei.config;

import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import verymc.top.veryMcProto.framework.util.JsonUtils;
import verymc.top.veryMcProto.mod.jei.JeiReference;

/**
 * JEI 模块配置（mod 层）。四键：
 * <ul>
 *   <li>{@code enabled} —— 模块总开关（配方同步 + jei:* 通道交互；{@code /jei enable|disable} 切换）；</li>
 *   <li>{@code cheatModeEnabledForOp / ForCreative / ForGive} —— cheat 权限三切面（语义与上游
 *       {@code mezz.jei.fabric.config.ServerConfig} 的 {@code jei-server.properties} 一致，
 *       且参与 {@code jei:cheat_permission} 包的 {@code allowedCheatingMethods} wire 内容）。</li>
 * </ul>
 * 默认值对齐上游：op=true、creative=true、give=false。
 *
 * <p><b>存量迁移</b>：新文件 {@code jei.json} 缺失时，若旧模块的 {@code jei-recipe-bridge.json}
 * 存在则迁移其 {@code enabled} 键值——杜绝"服主明确 disable 过、升级后被静默重置为 true"的行为翻转。
 *
 * <p>读写复用框架 {@link JsonUtils}（Gson pretty + 原子 tmp/move 落盘）；文件缺失/损坏按默认值重建。
 */
public final class JeiConfiguration
{
    private static volatile JeiConfiguration instance;

    /** 默认值（上游 ServerConfig 实证）。 */
    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_CHEAT_OP = true;
    private static final boolean DEFAULT_CHEAT_CREATIVE = true;
    private static final boolean DEFAULT_CHEAT_GIVE = false;

    private final Path configFile;
    private final Path legacyConfigFile;
    private volatile boolean enabled = DEFAULT_ENABLED;
    private volatile boolean cheatModeEnabledForOp = DEFAULT_CHEAT_OP;
    private volatile boolean cheatModeEnabledForCreative = DEFAULT_CHEAT_CREATIVE;
    private volatile boolean cheatModeEnabledForGive = DEFAULT_CHEAT_GIVE;

    /** 由 {@code JeiModule.enable} 构造；构造即注册 {@link #getInstance()} 单例并加载。 */
    public JeiConfiguration(Path dataFolder)
    {
        instance = this;
        this.configFile = dataFolder.resolve(JeiReference.CONFIG_FILE_NAME);
        this.legacyConfigFile = dataFolder.resolve(JeiReference.LEGACY_CONFIG_FILE_NAME);
        load();
    }

    public static JeiConfiguration getInstance() { return instance; }

    public boolean isEnabled() { return enabled; }

    public boolean isCheatModeEnabledForOp() { return cheatModeEnabledForOp; }

    public boolean isCheatModeEnabledForCreative() { return cheatModeEnabledForCreative; }

    public boolean isCheatModeEnabledForGive() { return cheatModeEnabledForGive; }

    public Path getConfigFile() { return configFile; }

    /**
     * 设置 enabled 并立即落盘（{@code /jei enable|disable} 调用）。落盘失败仅记日志、不回滚内存——
     * 运行时行为以内存为准。
     */
    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
        save();
    }

    /** 从配置文件读全部键；新文件缺失时尝试旧文件迁移，否则按默认值重建。 */
    private void load()
    {
        var root = JsonUtils.parseJsonFileAsPath(configFile);
        if (root instanceof JsonObject obj)
        {
            enabled = JsonUtils.getBooleanOrDefault(obj, "enabled", DEFAULT_ENABLED);
            cheatModeEnabledForOp = JsonUtils.getBooleanOrDefault(obj, "cheatModeEnabledForOp", DEFAULT_CHEAT_OP);
            cheatModeEnabledForCreative = JsonUtils.getBooleanOrDefault(obj, "cheatModeEnabledForCreative", DEFAULT_CHEAT_CREATIVE);
            cheatModeEnabledForGive = JsonUtils.getBooleanOrDefault(obj, "cheatModeEnabledForGive", DEFAULT_CHEAT_GIVE);
            return;
        }

        // 新文件缺失/损坏：迁移旧模块的 enabled（若有），其余按上游默认
        enabled = migrateLegacyEnabled();
        cheatModeEnabledForOp = DEFAULT_CHEAT_OP;
        cheatModeEnabledForCreative = DEFAULT_CHEAT_CREATIVE;
        cheatModeEnabledForGive = DEFAULT_CHEAT_GIVE;
        save();
    }

    private boolean migrateLegacyEnabled()
    {
        var legacyRoot = JsonUtils.parseJsonFileAsPath(legacyConfigFile);
        if (legacyRoot instanceof JsonObject obj)
        {
            return JsonUtils.getBooleanOrDefault(obj, "enabled", DEFAULT_ENABLED);
        }
        return DEFAULT_ENABLED;
    }

    private void save()
    {
        JsonObject root = new JsonObject();
        root.add("enabled", new JsonPrimitive(enabled));
        root.add("cheatModeEnabledForOp", new JsonPrimitive(cheatModeEnabledForOp));
        root.add("cheatModeEnabledForCreative", new JsonPrimitive(cheatModeEnabledForCreative));
        root.add("cheatModeEnabledForGive", new JsonPrimitive(cheatModeEnabledForGive));
        JsonUtils.writeJsonToFileAsPath(root, configFile);
    }
}
