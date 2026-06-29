package verymc.top.veryMcProto.framework.settings;

/**
 * Setting 值变更回调（框架层）。照抄原版 {@code fi.dy.masa.servux.settings.IServuxSettingCallback}。
 */
public interface IServuxSettingCallback<T>
{
    void onValueChanged(IServuxSetting<T> setting, T oldValue, T value);
}
