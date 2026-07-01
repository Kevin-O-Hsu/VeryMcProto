package verymc.top.veryMcProto.framework.network;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.Identifier;

import verymc.top.veryMcProto.mod.servux.ServuxDebug;

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

    /** 注册 handler + 同步注册 plugin messaging 通道（incoming + outgoing + receiver）+ 标记已注册。 */
    public void registerServerPlayHandler(IPluginServerPlayHandler handler)
    {
        Identifier channel = handler.getPayloadChannel();
        boolean existed = handlers.containsKey(channel);
        handlers.putIfAbsent(channel, handler);
        ChannelManager.instance().register(channel, handler);
        // ★ 必须标记已注册：sendPlayPayload 的 isPlayRegistered 门控依赖此标志。
        // 漏调会导致 payloadRegistered 恒为 false → 所有 S2C 发送被「通道未注册」拦截
        // （实测 BUG：5 条 servux:* 通道全部 sendPlayPayload 失败）。对应原版 registerPlayPayload
        // 成功后由 fabric 回调触发的 setPlayRegistered。
        handler.setPlayRegistered(channel);
        ServuxDebug.log(ServuxDebug.Cat.NETWORK, "registerServerPlayHandler: " + channel
                + (existed ? " (handler 已存在，putIfAbsent 未覆盖)" : "")
                + " → ChannelManager.register + setPlayRegistered(true)");
    }

    /** 反注册 handler + 注销 plugin messaging 通道 + 清除已注册标记。 */
    public void unregisterServerPlayHandler(IPluginServerPlayHandler handler)
    {
        Identifier channel = handler.getPayloadChannel();
        IPluginServerPlayHandler existing = handlers.get(channel);
        if (existing == handler)
        {
            handlers.remove(channel);
            handler.reset(channel);
            handler.clearPlayRegistered(channel);
            ChannelManager.instance().unregister(channel);
            ServuxDebug.log(ServuxDebug.Cat.NETWORK, "unregisterServerPlayHandler: " + channel
                    + " → ChannelManager.unregister + clearPlayRegistered(false)");
        }
        else
        {
            ServuxDebug.log(ServuxDebug.Cat.NETWORK, "unregisterServerPlayHandler: " + channel + " 跳过（existing!=handler 或未注册）");
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
