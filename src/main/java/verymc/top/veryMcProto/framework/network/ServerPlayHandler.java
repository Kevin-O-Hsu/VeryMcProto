package verymc.top.veryMcProto.framework.network;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.Identifier;

/**
 * Handler 注册表（框架层）。移植自原版 {@code fi.dy.masa.servux.network.ServerPlayHandler}，简化为
 * 「通道 → handler」单映射（Servux 每通道一个 handler）。
 *
 * <p>注册 / 反注册时同步联动 {@link ChannelManager}（plugin messaging 通道注册），使 Provider 只需调用：
 * <pre>
 *   ServerPlayHandler.getInstance().registerServerPlayHandler(HANDLER);   // 启用
 *   ServerPlayHandler.getInstance().unregisterServerPlayHandler(HANDLER); // 禁用
 * </pre>
 */
public final class ServerPlayHandler
{
    private static final ServerPlayHandler INSTANCE = new ServerPlayHandler();

    public static ServerPlayHandler getInstance()
    {
        return INSTANCE;
    }

    private final Map<Identifier, IPluginServerPlayHandler> handlers = new HashMap<>();

    private ServerPlayHandler() { }

    /** 注册 handler + 同步注册 plugin messaging 通道（incoming + outgoing + receiver）。 */
    public void registerServerPlayHandler(IPluginServerPlayHandler handler)
    {
        Identifier channel = handler.getPayloadChannel();
        handlers.putIfAbsent(channel, handler);
        ChannelManager.instance().register(channel, handler);
    }

    /** 反注册 handler + 注销 plugin messaging 通道。 */
    public void unregisterServerPlayHandler(IPluginServerPlayHandler handler)
    {
        Identifier channel = handler.getPayloadChannel();
        IPluginServerPlayHandler existing = handlers.get(channel);
        if (existing == handler)
        {
            handlers.remove(channel);
            handler.reset(channel);
            ChannelManager.instance().unregister(channel);
        }
    }

    public void reset(Identifier channel)
    {
        IPluginServerPlayHandler h = handlers.get(channel);
        if (h != null)
        {
            h.reset(channel);
        }
    }
}
