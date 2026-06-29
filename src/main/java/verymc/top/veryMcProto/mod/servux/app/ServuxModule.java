package verymc.top.veryMcProto.mod.servux.app;

import verymc.top.veryMcProto.framework.ModModule;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.dataproviders.ConfigProvider;
import verymc.top.veryMcProto.mod.servux.dataproviders.EntitiesDataProvider;
import verymc.top.veryMcProto.mod.servux.dataproviders.HudDataProvider;
import verymc.top.veryMcProto.mod.servux.dataproviders.LitematicsDataProvider;
import verymc.top.veryMcProto.mod.servux.dataproviders.StructureDataProvider;
import verymc.top.veryMcProto.mod.servux.dataproviders.TweaksDataProvider;

/**
 * Servux 协议 mod 模块（mod 层）。首个 {@link ModModule} 实现。
 *
 * <p>向框架注册 Servux 的 6 个 Provider（1 配置主 + 5 协议）。{@link ConfigProvider}（servux_main）
 * 永远启用；其余由 {@link DataProviderManager#readFromConfig} 按 {@code servux.json} 开关启停。
 */
public class ServuxModule implements ModModule
{
    @Override
    public String getModId() { return ServuxReference.MOD_ID; }

    @Override
    public String getModString() { return ServuxReference.MOD_STRING; }

    @Override
    public void onRegister(DataProviderManager manager)
    {
        // 顺序：ConfigProvider 先注册（servux_main，永不可禁用，承载全局配置）
        manager.registerDataProvider(ConfigProvider.INSTANCE);
        manager.registerDataProvider(HudDataProvider.INSTANCE);
        manager.registerDataProvider(EntitiesDataProvider.INSTANCE);
        manager.registerDataProvider(TweaksDataProvider.INSTANCE);
        manager.registerDataProvider(StructureDataProvider.INSTANCE);
        manager.registerDataProvider(LitematicsDataProvider.INSTANCE);
    }
}
