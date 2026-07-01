package verymc.top.veryMcProto.mod.jeirecipebridge.app;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.ModModule;
import verymc.top.veryMcProto.framework.dataproviders.DataProviderManager;
import verymc.top.veryMcProto.mod.jeirecipebridge.JeiRecipeBridgeReference;
import verymc.top.veryMcProto.mod.jeirecipebridge.RecipeSyncHandler;

/**
 * JEI Recipe Bridge 协议 mod 模块（mod 层）。对应原版 {@code JEIRecipeBridgePlugin}（独立 JavaPlugin）——
 * 本移植并入 VeryMcProto 框架，复用主类生命周期。
 *
 * <p>{@link #onRegister} 注册两条 S2C outgoing 通道（{@code fabric:recipe_sync} / {@code neoforge:recipe_content}）
 * + {@link RecipeSyncHandler} 监听器。
 *
 * <p><b>与 Servux 的差异</b>：JEI 配方同步是<b>纯 S2C、一次性</b>（玩家进服触发），无 C2S、无配置、无 provider——
 * 故不向 {@link DataProviderManager} 注册任何东西（参数忽略），也不走 {@code ChannelManager}（它绑定 C2S receiver，JEI 用不上）。
 * 实际发送走 NMS {@code ClientboundCustomPayloadPacket} 直发（配方包大，绕过 plugin messaging size 上限）；
 * outgoing 注册仅作 Paper {@link Messenger} 通道声明（与原版一致）。
 */
public class JeiRecipeBridgeModule implements ModModule
{
    @Override
    public String getModId() { return JeiRecipeBridgeReference.MOD_ID; }

    @Override
    public String getModString() { return "jei-recipe-bridge-paper-1.21.11-1.0.0"; }

    @Override
    public void onRegister(DataProviderManager manager)
    {
        // JEI 配方同步无 provider / 无配置周期数据，manager 参数忽略。
        Plugin plugin = Reference.plugin();
        if (plugin == null)
        {
            Reference.logger().warning("[JEIRecipeBridge] 插件句柄未就绪，跳过模块注册。");
            return;
        }
        Messenger messenger = plugin.getServer().getMessenger();
        // 通道声明：实际配方包走 NMS ClientboundCustomPayloadPacket 直发，此处 registerOutgoingPluginChannel 仅作
        // Paper Messenger 合规声明（与原版一致）——否则部分 Paper 版本会对未声明通道的 sendPluginMessage 拒绝。
        messenger.registerOutgoingPluginChannel(plugin, JeiRecipeBridgeReference.CHANNEL_FABRIC.toString());
        messenger.registerOutgoingPluginChannel(plugin, JeiRecipeBridgeReference.CHANNEL_NEOFORGE.toString());

        plugin.getServer().getPluginManager().registerEvents(new RecipeSyncHandler(), plugin);
    }
}
