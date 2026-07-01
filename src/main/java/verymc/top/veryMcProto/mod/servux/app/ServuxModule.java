package verymc.top.veryMcProto.mod.servux.app;

import verymc.top.veryMcProto.framework.ModModule;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.framework.debug.FrameworkDebug;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
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
        // framework 层调试日志注入（F001 解耦）：framework/ 不再硬绑 ServuxDebug，改为由首个采用
        // 框架的 mod 在此注入自己的 DebugSystem 实例。运行时 framework 网络层等日志仍走
        // ServuxDebug.SYS，受 /servux debug 控制——行为与解耦前完全一致。
        FrameworkDebug.bind(ServuxDebug.SYS);

        // 顺序：ConfigProvider 先注册（servux_main，永不可禁用，承载全局配置）
        manager.registerDataProvider(ConfigProvider.INSTANCE);
        manager.registerDataProvider(HudDataProvider.INSTANCE);
        manager.registerDataProvider(EntitiesDataProvider.INSTANCE);
        manager.registerDataProvider(TweaksDataProvider.INSTANCE);
        manager.registerDataProvider(StructureDataProvider.INSTANCE);
        manager.registerDataProvider(LitematicsDataProvider.INSTANCE);

        // EasyPlace（Tweakeroo 服务端配合）：拦截原版 use_item_on，用 masa 协议 v3 解码精确放置状态。
        // 运行时依赖 PacketEvents 插件（plugin.yml: softdepend）。
        // ⚠ PE 类引用必须隔离在 EasyPlaceBootstrap 里、用反射加载：若直接在本方法引用 PE 类，
        //    JVM 解析 onRegister 时就触发 PE 类加载，服务器未装 PE 会抛 NoClassDefFoundError 且发生在
        //    方法体执行前（try 进不去），拖垮整个 ServuxModule / 6 个 provider 注册。
        try
        {
            Class.forName("verymc.top.veryMcProto.mod.servux.easyplace.EasyPlaceBootstrap")
                    .getMethod("register").invoke(null);
            verymc.top.veryMcProto.Reference.logger().info("[VeryMcProto] EasyPlace 已启用（依赖 PacketEvents）。");
        }
        catch (Throwable ex)
        {
            verymc.top.veryMcProto.Reference.logger().warning("[VeryMcProto] PacketEvents 未安装，EasyPlace（精确放置）不可用。其余功能不受影响。");
        }
    }
}
