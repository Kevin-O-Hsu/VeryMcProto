package verymc.top.veryMcProto.mod.servux.dataproviders;

import java.util.List;

import org.bukkit.command.CommandSender;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.framework.dataproviders.DataProviderBase;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.permission.Perms;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;
import verymc.top.veryMcProto.framework.settings.IServuxSettingCallback;
import verymc.top.veryMcProto.framework.settings.ServuxBoolSetting;
import verymc.top.veryMcProto.framework.settings.ServuxIntSetting;
import verymc.top.veryMcProto.framework.settings.ServuxStringSetting;
import verymc.top.veryMcProto.mod.servux.ServuxReference;

/**
 * servux_main 全局配置 Provider（mod 层）。移植自原版 {@code ServuxConfigProvider}。
 *
 * <p>{@code getName()="servux_main"}，{@link DataProviderManager#readFromConfig} 永远强制启用它。
 * 它<b>不对应独立网络通道</b>（{@link #registerHandler} 是 NO-OP，不下发网络包），只承载全局配置：
 * permission_level / admin / easy_place / default_language / debug_log。
 *
 * <p><b>适配</b>：去掉 {@code i18nManager.LANG} 依赖（StringUtils 用 Component.translatable）；
 * {@code doReloadConfig/doSaveConfig} 形参从 NMS {@code CommandSourceStack} 改为 Bukkit {@link CommandSender}；
 * 权限从 {@code Permissions.check} 改为 {@link Perms#check}。
 */
public class ConfigProvider extends DataProviderBase
{
    public static final ConfigProvider INSTANCE = new ConfigProvider();

    private final ServuxIntSetting basePermissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0);
    private final ServuxIntSetting adminPermissionLevel = new ServuxIntSetting(this, "permission_level_admin", 3, 4, 0);
    private final ServuxIntSetting easyPlacePermissionLevel = new ServuxIntSetting(this, "permission_level_easy_place", 0, 4, 0);
    private final ServuxBoolSetting easyPlaceValidatorEnabled = new ServuxBoolSetting(this, "easy_place_validator_enabled", true);
    private final ServuxStringSetting defaultLanguage = new ServuxStringSetting(this, "default_language", "en_us", List.of("en_us"), false);
    private final ServuxBoolSetting debugLog = new ServuxBoolSetting(this, "debug_log", false, new DebugLogCallback());
    private final List<IServuxSetting<?>> settings = List.of(
            this.basePermissionLevel, this.adminPermissionLevel,
            this.easyPlacePermissionLevel, this.easyPlaceValidatorEnabled,
            this.defaultLanguage, this.debugLog
    );

    protected ConfigProvider()
    {
        super("servux_main",
                Identifier.fromNamespaceAndPath("servux", "main"),
                1, 0, ServuxReference.MOD_ID + ".main",
                "The Servux Main configuration data provider");
    }

    @Override
    public List<IServuxSetting<?>> getSettings() { return this.settings; }

    @Override
    public void registerHandler() { /* NO-OP: servux_main 不下发网络包，不注册 plugin messaging 通道 */ }

    @Override
    public void unregisterHandler() { /* NO-OP */ }

    @Override
    public boolean isPlayerRegistered(ServerPlayer player) { return true; }

    public void doReloadConfig(CommandSender source)
    {
        DataProviderManager.INSTANCE.readFromConfig();
        source.sendMessage("§aServux 配置已重载。");
    }

    public void doSaveConfig(CommandSender source)
    {
        DataProviderManager.INSTANCE.writeToConfig();
        source.sendMessage("§aServux 配置已保存。");
    }

    public boolean hasDebugMode()
    {
        return this.debugLog.getValue() || ServuxReference.DEV_DEBUG;
    }

    /**
     * 把 {@code servux_main:debug_log} 同步到 {@link ServuxDebug} 宏开关。
     *
     * <p>开启时自动 {@link ServuxDebug#enableAll()}（全分类），方便排障；关闭则静默。
     * 由 {@link DebugLogCallback}（命令 {@code /servux set} 触发）与 {@link #onConfigLoaded}（配置文件读取）双入口调用，
     * 保证「命令即时切换」与「改 servux.json 重启/reload」两条路径都生效。
     */
    public void syncDebugToFramework(boolean debugLogValue)
    {
        boolean on = debugLogValue || ServuxReference.DEV_DEBUG;
        ServuxDebug.setMaster(on);
        if (on)
        {
            ServuxDebug.enableAll();
            ServuxDebug.log(ServuxDebug.Cat.CONFIG, "调试宏开关已启用（servux_main:debug_log=" + debugLogValue
                    + ", DEV_DEBUG=" + ServuxReference.DEV_DEBUG + "），已开启全分类。");
        }
    }

    @Override
    public void onConfigLoaded()
    {
        this.syncDebugToFramework(this.debugLog.getValue());
    }

    @Override
    public boolean hasPermission(ServerPlayer player)
    {
        if (player == null) { return false; }
        return Perms.check(player, ServuxReference.MOD_ID + ".main.admin", this.adminPermissionLevel.getValue());
    }

    public boolean hasPermission_EasyPlace(ServerPlayer player)
    {
        if (player == null) { return false; }
        return Perms.check(player, ServuxReference.MOD_ID + ".main.easy_place", this.easyPlacePermissionLevel.getValue());
    }

    public boolean isEasyPlaceValidatorEnabled() { return this.easyPlaceValidatorEnabled.getValue(); }

    public String getDefaultLanguage() { return this.defaultLanguage.getValue(); }

    @Override public void onTickEndPre() { /* NO-OP */ }

    @Override public void onTickEndPost() { /* NO-OP */ }

    /** debug_log setting 变更回调：命令 {@code /servux set servux_main:debug_log <bool>} 时即时同步框架 Debug。 */
    public static class DebugLogCallback implements IServuxSettingCallback<Boolean>
    {
        @Override
        public void onValueChanged(IServuxSetting<Boolean> setting, Boolean oldValue, Boolean value)
        {
            ConfigProvider.INSTANCE.syncDebugToFramework(value);
        }
    }
}
