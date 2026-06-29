package verymc.top.veryMcProto.framework.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.minecraft.resources.Identifier;

/**
 * 全局通道注册表（框架层）。维护「通道 ID → {@link ProtocolChannel}」映射，所有协议 mod 共用。
 *
 * <p>替代原版 Fabric 的 {@code PayloadTypeRegistry.playC2S()/playS2C()} + {@code ServerPlayNetworking.registerGlobalReceiver}：
 * 一处注册 incoming + outgoing + 绑定 handler 接收回调。
 */
public final class ChannelManager
{
    private static final ChannelManager INSTANCE = new ChannelManager();

    public static ChannelManager instance()
    {
        return INSTANCE;
    }

    private final Map<Identifier, ProtocolChannel> channels = new ConcurrentHashMap<>();
    private volatile Plugin plugin;

    private ChannelManager() { }

    /** 由主类 onEnable 注入插件句柄（注册通道需要）。 */
    public void init(Plugin plugin)
    {
        this.plugin = plugin;
    }

    public Plugin plugin()
    {
        return plugin;
    }

    /**
     * 注册一条通道（incoming + outgoing）并绑定 handler 的接收回调。幂等（同 channel 返回已有实例）。
     */
    public ProtocolChannel register(Identifier channelId, IPluginServerPlayHandler handler)
    {
        return channels.computeIfAbsent(channelId, id ->
        {
            ProtocolChannel ch = new ProtocolChannel(plugin, id, (player, buf) -> handler.receivePlayPayload(buf, player));
            ch.registerIncoming();
            ch.registerOutgoing();
            return ch;
        });
    }

    /** 反注册通道（onDisable / provider 禁用）。 */
    public void unregister(Identifier channelId)
    {
        ProtocolChannel ch = channels.remove(channelId);
        if (ch != null)
        {
            ch.unregister();
        }
    }

    public ProtocolChannel get(Identifier channelId)
    {
        return channels.get(channelId);
    }

    /** 发送（plugin messaging）。 */
    public boolean send(Identifier channelId, Player player, byte[] bytes)
    {
        ProtocolChannel ch = channels.get(channelId);
        return ch != null && ch.send(player, bytes);
    }

    /** 卸载全部通道（onDisable）。 */
    public void unregisterAll()
    {
        for (ProtocolChannel ch : channels.values())
        {
            try
            {
                ch.unregister();
            }
            catch (Exception ignored) { }
        }
        channels.clear();
    }
}
