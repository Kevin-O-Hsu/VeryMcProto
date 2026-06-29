package verymc.top.veryMcProto.framework.network;

import java.util.function.BiConsumer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;

/**
 * 单条 plugin messaging 通道封装（框架层）。
 *
 * <p>替代原版 Fabric 的 {@code ServerPlayNetworking.registerGlobalReceiver / send}：
 * <ul>
 *   <li>{@link #registerIncoming()} + {@link #registerOutgoing()} = 原版 registerPlayPayload + registerPlayReceiver；</li>
 *   <li>{@link PluginMessageListener#onPluginMessageReceived} 的 {@code byte[]} = {@link FriendlyByteBuf} 裸字节，
 *       包装后回调 {@code receiver}（→ handler.receivePlayPayload）；</li>
 *   <li>{@link #send} = 原版 {@code ServerPlayNetworking.send}（plugin messaging 发送）。</li>
 * </ul>
 *
 * <p><b>客户端支持检测</b>（替代原版 {@code ServerPlayNetworking.canSend}）：
 * {@link #send} 检查 {@code player.getListeningPluginChannels().contains(name)}。
 * Fabric 客户端装了对应 masa mod 才会通过 MC|Register 声明监听 {@code servux:*}；
 * 未装的玩家永远不含 → send 返回 false → handler 失败计数 → 最终标记 invalid（不刷屏）。
 * 这比原版 canSend 更可靠地识别"客户端无对应 mod"。
 *
 * <p>plugin messaging 注册的通道由 Paper 内置路由，<b>不会因未知 payload 踢玩家</b>（这是用 plugin messaging
 * 而非裸 NMS 发包的根本理由）。
 */
public final class ProtocolChannel
{
    private final Plugin plugin;
    private final Identifier channelId;
    private final BiConsumer<ServerPlayer, FriendlyByteBuf> receiver;

    private volatile boolean incoming = false;
    private volatile boolean outgoing = false;

    private final PluginMessageListener listener = new PluginMessageListener()
    {
        @Override
        public void onPluginMessageReceived(String channel, Player player, byte[] message)
        {
            if (!name().equals(channel))
            {
                return;
            }
            try
            {
                FriendlyByteBuf buf = FriendlyByteBufs.wrap(message);
                ServerPlayer nms = NmsHolder.toNms(player);
                receiver.accept(nms, buf);
            }
            catch (Exception e)
            {
                Reference.logger().warning("ProtocolChannel[" + channelId + "] 处理 C2S 失败: " + e.getMessage());
            }
        }
    };

    public ProtocolChannel(Plugin plugin, Identifier channelId, BiConsumer<ServerPlayer, FriendlyByteBuf> receiver)
    {
        this.plugin = plugin;
        this.channelId = channelId;
        this.receiver = receiver;
    }

    public Identifier channelId()
    {
        return channelId;
    }

    /** plugin messaging 通道名（{@code servux:hud_metadata} 等）。 */
    public String name()
    {
        return channelId.toString();
    }

    public synchronized void registerIncoming()
    {
        if (incoming)
        {
            return;
        }
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, name(), listener);
        incoming = true;
    }

    public synchronized void registerOutgoing()
    {
        if (outgoing)
        {
            return;
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, name());
        outgoing = true;
    }

    public synchronized void unregister()
    {
        Messenger m = plugin.getServer().getMessenger();
        try
        {
            if (incoming)
            {
                m.unregisterIncomingPluginChannel(plugin, name(), listener);
                incoming = false;
            }
        }
        catch (Exception e)
        {
            Reference.logger().warning("ProtocolChannel[" + channelId + "] unregisterIncoming 失败: " + e.getMessage());
        }
        try
        {
            if (outgoing)
            {
                m.unregisterOutgoingPluginChannel(plugin, name());
                outgoing = false;
            }
        }
        catch (Exception e)
        {
            Reference.logger().warning("ProtocolChannel[" + channelId + "] unregisterOutgoing 失败: " + e.getMessage());
        }
    }

    /**
     * 发送 S2C（plugin messaging，方案 A）。超 32KiB 由 {@link PacketSplitter} 分包保证。
     *
     * @return 是否成功投递。返回 false 含义：通道未注册 outgoing / 玩家离线 / 客户端未声明监听该通道 / 发送异常。
     *         调用方据此做失败计数。
     */
    public boolean send(Player player, byte[] bytes)
    {
        if (!outgoing)
        {
            return false;
        }
        if (player == null || !player.isOnline())
        {
            return false;
        }
        // 客户端必须已声明监听本通道（plugin messaging 握手 MC|Register）；未声明 = 客户端无对应 mod。
        if (!player.getListeningPluginChannels().contains(name()))
        {
            return false;
        }
        if (bytes.length > Messenger.MAX_MESSAGE_SIZE)
        {
            Reference.logger().warning("ProtocolChannel[" + channelId + "] 拒绝发送超限包: " + bytes.length
                    + " > " + Messenger.MAX_MESSAGE_SIZE + "（应走 PacketSplitter 分包）");
            return false;
        }
        try
        {
            player.sendPluginMessage(plugin, name(), bytes);
            return true;
        }
        catch (Exception e)
        {
            Reference.logger().warning("ProtocolChannel[" + channelId + "] sendPluginMessage 失败: " + e.getMessage());
            return false;
        }
    }

    /** 延迟引用 Nms，避免框架网络层与 nms 层循环初始化的边界问题（同模块无碍，留作可读性锚点）。 */
    private static final class NmsHolder
    {
        static ServerPlayer toNms(Player player)
        {
            return verymc.top.veryMcProto.framework.nms.Nms.toNms(player);
        }
    }
}
