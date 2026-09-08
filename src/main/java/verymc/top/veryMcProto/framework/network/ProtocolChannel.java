package verymc.top.veryMcProto.framework.network;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
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
 * <p><b>客户端支持检测（实锤前提 + C2S 证明兜底）</b>：Paper {@code CraftPlayer.sendPluginMessage} 内部有
 * {@code channels().contains(channel)} 门控——玩家声明包（play 期 {@code minecraft:register}）被 Paper 处理前，
 * S2C 一律<b>静默丢弃</b>（26.1.2 反编译实证；声明处理晚于客户端首个 C2S 到达，进服首握手回复因此曾被吞 →
 * minihud structures 恒 not_connected）。对策：<b>同通道 C2S 证明兜底</b>——玩家在本通道发过 C2S 即证明其
 * 装有对应 mod、注册了 payload codec（能发即能收），此时若 Paper 声明簿记未跟上（listening=false），改走 NMS
 * {@code new ClientboundCustomPayloadPacket(new DiscardedPayload(channelId, bytes))} 直发——与 Paper 自身放行
 * 路径（{@code CraftPlayer.sendCustomPayload}）逐字同构，生产先例 {@code ExchangeTarget.sendViaNms} /
 * {@code RecipeSyncHandler.sendPayload}。未发过 C2S 的玩家（vanilla / 未装 mod）永不走兜底，维持
 * sendPluginMessage 原路径（Paper 按声明丢弃）——vanilla 防护语义构造性保留，不依赖
 * 「vanilla 对未知通道 S2C 的行为」这一未决项（docs/09 §10.6.2）。
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

    /** 已在本通道发过 C2S 的玩家（证明装有对应 mod、注册了 codec，能解码本通道 payload）。玩家退出时清除。 */
    private final Set<UUID> provenPlayers = ConcurrentHashMap.newKeySet();

    private final PluginMessageListener listener = new PluginMessageListener()
    {
        @Override
        public void onPluginMessageReceived(String channel, Player player, byte[] message)
        {
            if (!name().equals(channel))
            {
                return;
            }
            provenPlayers.add(player.getUniqueId());
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
     * 发送 S2C（plugin messaging 优先；未声明且已 C2S 证明时 NMS 兜底）。
     *
     * <p><b>包大小命门</b>：1.21.x Bukkit {@code Messenger.MAX_MESSAGE_SIZE} 已上调至 ~1MiB（Spigot API 1048576），
     * 故本方法对 Bukkit API 合约而言不会因 32KiB 拒绝；真正的 S2C 瓶颈是<b>原版客户端对 ClientboundCustomPayload
     * 的 32767 字节解码上限</b>——超过会让客户端断连。故 {@link PacketSplitter} S2C 分片用 32000（留余量给 VarInt 头），
     * 大包必须走分包，不能直接 send。
     *
     * @return 是否成功投递。返回 false 含义：通道未注册 outgoing / 玩家离线 / 两条路径发送异常。
     *         注意：listening=false 且未证明时仍走 sendPluginMessage 并返回 true——Paper 按声明门控丢弃该包，
     *         但沿用历史语义不报失败（避免对未装 mod 玩家误触发调用方失败计数）。调用方据此做失败计数。
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
        if (bytes.length > Messenger.MAX_MESSAGE_SIZE)
        {
            Reference.logger().warning("ProtocolChannel[" + channelId + "] 拒绝发送超限包: " + bytes.length
                    + " > Bukkit MAX_MESSAGE_SIZE（应走 PacketSplitter 分包；注意真正 S2C 瓶颈是客户端 32767 上限）");
            return false;
        }

        // ★ Paper 命门（26.1.2 反编译实锤）：CraftPlayer.sendPluginMessage 有 channels().contains(channel) 门控，
        // 玩家声明包被 Paper 处理前 S2C 一律静默丢弃——而声明处理晚于客户端首个 C2S（进服首握手回复曾被吞，
        // minihud structures 的 metadata 接受窗口是单次的，错过即 not_connected）。
        boolean listening = player.getListeningPluginChannels().contains(name());

        if (!listening && provenPlayers.contains(player.getUniqueId()))
        {
            // NMS 兜底：玩家在本通道发过 C2S = 装有 mod、注册了 codec（能发即能收），Paper 声明簿记未跟上
            // 属服务端认知滞后。构造与 Paper 放行路径（CraftPlayer.sendCustomPayload）逐字同构。
            try
            {
                NmsHolder.toNms(player).connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(channelId, bytes)));
                FrameworkDebug.log("network", "send OK " + channelId + " → " + player.getName()
                        + " bytes=" + bytes.length + " listening=false via=NMS-fallback");
                return true;
            }
            catch (Exception e)
            {
                Reference.logger().warning("ProtocolChannel[" + channelId + "] NMS 兜底发送失败: " + e.getMessage());
                return false;
            }
        }

        try
        {
            player.sendPluginMessage(plugin, name(), bytes);
            FrameworkDebug.log("network", "send OK " + channelId + " → " + player.getName()
                    + " bytes=" + bytes.length + " listening=" + listening + " via=pluginMsg"
                    + (listening ? "" : "(Paper 将按声明门控丢弃)"));
            return true;
        }
        catch (Exception e)
        {
            Reference.logger().warning("ProtocolChannel[" + channelId + "] sendPluginMessage 失败: " + e.getMessage());
            return false;
        }
    }

    /** 玩家退出时清除其 C2S 证明（LifecycleBridge.onPlayerQuit → ChannelManager.clearProven 调用）。 */
    public void clearProven(UUID playerId)
    {
        provenPlayers.remove(playerId);
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
