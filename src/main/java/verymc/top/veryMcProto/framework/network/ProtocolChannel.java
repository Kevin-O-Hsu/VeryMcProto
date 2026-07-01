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
import verymc.top.veryMcProto.framework.debug.FrameworkDebug;

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
 * <p><b>客户端支持检测（策略变更：不再门控）</b>：曾用 {@code player.getListeningPluginChannels().contains(name)}
 * 门控丢弃，但 masa 客户端通过 1.20.5+ {@code PayloadTypeRegistry.playS2C()} 在配置阶段声明通道（新式协商），
 * 该声明不必然反映到 Bukkit 旧式 {@code MC|Register} 机制，且到达晚于 {@code onPlayerJoin} 首包 → metadata 被
 * 永久丢弃 → entity/tweaks/litematics {@code not_enabled}（实测 BUG）。故现改为<b>不门控直接发送</b>，与原版
 * {@code player.connection.send(ClientboundCustomPayloadPacket)} 等价（{@code sendPluginMessage} 在 Paper 上同样
 * 发 {@code ClientboundCustomPayloadPacket}）；仅在日志记录 {@code listening} 状态供诊断。客户端未声明时 Fabric
 * 丢弃（对 masa 客户端安全）；原版/vanilla 客户端的断连风险见 docs/09 §10.6。
 *
 * <p>plugin messaging 注册的通道由 Paper 内置路由 C2S 接收，<b>不会因未知 C2S payload 踢玩家</b>。
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
            FrameworkDebug.log("network", "C2S 收到 " + channelId + " ← " + player.getName() + " bytes=" + message.length);
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
        FrameworkDebug.log("network", "registerIncoming OK " + channelId);
    }

    public synchronized void registerOutgoing()
    {
        if (outgoing)
        {
            return;
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, name());
        outgoing = true;
        FrameworkDebug.log("network", "registerOutgoing OK " + channelId);
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
     * 发送 S2C（plugin messaging，方案 A）。
     *
     * <p><b>包大小命门</b>：1.21.x Bukkit {@code Messenger.MAX_MESSAGE_SIZE} 已上调至 ~1MiB（Spigot API 1048576），
     * 故本方法对 Bukkit API 合约而言不会因 32KiB 拒绝；真正的 S2C 瓶颈是<b>原版客户端对 ClientboundCustomPayload
     * 的 32767 字节解码上限</b>——超过会让客户端断连。故 {@link PacketSplitter} S2C 分片用 32000（留余量给 VarInt 头），
     * 大包必须走分包，不能直接 send。
     *
     * @return 是否成功投递。返回 false 含义：通道未注册 outgoing / 玩家离线 / 客户端未声明监听该通道 / 发送异常。
     *         调用方据此做失败计数。
     */
    public boolean send(Player player, byte[] bytes)
    {
        if (!outgoing)
        {
            FrameworkDebug.log("network", "send FAIL " + channelId + " bytes=" + bytes.length
                    + " : outgoing 未注册（provider 未 registerHandler / 通道已注销）");
            return false;
        }
        if (player == null || !player.isOnline())
        {
            FrameworkDebug.log("network", "send FAIL " + channelId + " bytes=" + bytes.length + " : player 离线/null");
            return false;
        }
        // ★ 命门修复：不再用 getListeningPluginChannels 门控丢弃。
        // masa 客户端通过 1.20.5+ PayloadTypeRegistry.playS2C() 在配置阶段声明通道（新式协商），
        // 该声明不一定反映到 Bukkit 旧式 MC|Register 机制，且到达晚于 onPlayerJoin 首包 →
        // metadata 被永久丢弃 → entity/tweaks/litematics not_enabled。原版 Fabric 直接
        // player.connection.send(ClientboundCustomPayloadPacket) 不门控；Paper sendPluginMessage 同样
        // 发 ClientboundCustomPayloadPacket，去掉门控即等价。客户端声明了则收，未声明 Fabric 丢弃（安全）。
        boolean listening = player.getListeningPluginChannels().contains(name());
        if (bytes.length > Messenger.MAX_MESSAGE_SIZE)
        {
            Reference.logger().warning("ProtocolChannel[" + channelId + "] 拒绝发送超限包: " + bytes.length
                    + " > Bukkit MAX_MESSAGE_SIZE（应走 PacketSplitter 分包；注意真正 S2C 瓶颈是客户端 32767 上限）");
            return false;
        }
        try
        {
            player.sendPluginMessage(plugin, name(), bytes);
            FrameworkDebug.log("network", "send OK " + channelId + " → " + player.getName()
                    + " bytes=" + bytes.length + " listening=" + listening);
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
