package verymc.top.veryMcProto.mod.syncmatica.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaReference;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.communication.ServerCommunicationManager;
import verymc.top.veryMcProto.mod.syncmatica.util.SyncmaticaLog;

/**
 * syncmatica 单通道 handler（Paper 新增，实现 {@link IPluginServerPlayHandler}）。
 *
 * <p>对应原版 {@code network/handler/ServerPlayHandler.receiveSyncPayload} + {@code IServerPlay} mixin 桥接。
 * 接收端解析 {@code [Identifier][body]} 复合包体（docs/21 §1.2 命门），派发给 {@link ServerCommunicationManager#onPacket}。
 *
 * <p>不复用 {@code IServerPayloadData}（那是 Servux per-通道模型）；{@code encodeWithSplitter} 空实现
 *（syncmatica 文件分片走 exchange 自写 stop-and-wait，不用 {@code PacketSplitter}）。
 */
public class SyncmaticaHandler implements IPluginServerPlayHandler
{
    private final SyncmaticaContext context;
    private boolean registered = false;

    public SyncmaticaHandler(final SyncmaticaContext context)
    {
        this.context = context;
    }

    @Override
    public Identifier getPayloadChannel()
    {
        return SyncmaticaReference.NETWORK_ID;
    }

    @Override
    public boolean isPlayRegistered(final Identifier channel)
    {
        return registered;
    }

    @Override
    public void setPlayRegistered(final Identifier channel)
    {
        registered = true;
    }

    @Override
    public void clearPlayRegistered(final Identifier channel)
    {
        registered = false;
    }

    @Override
    public void reset(final Identifier channel)
    {
        // 无失败计数 / 缓冲需重置
    }

    @Override
    public void receivePlayPayload(final FriendlyByteBuf data, final ServerPlayer player)
    {
        // 防御：空包丢弃
        if (data == null || data.readableBytes() <= 0)
        {
            return;
        }
        SyncmaticaDebug.log(SyncmaticaDebug.Cat.NETWORK, "[syncm] C2S 收到 syncmatica:main ← " + player.getName().getString()
                + " bytes=" + data.readableBytes());
        // 解析 [逻辑通道 Identifier][body]（物理包体复合结构）
        final Identifier logicChannel;
        try
        {
            logicChannel = data.readIdentifier();
        }
        catch (final Exception e)
        {
            SyncmaticaLog.warn("SyncmaticaHandler: failed to read logic channel Identifier from {}; {}",
                    player.getName().getString(), e.getLocalizedMessage());
            return;
        }
        final PacketType type = PacketType.getType(logicChannel);
        if (type == null)
        {
            SyncmaticaDebug.log(SyncmaticaDebug.Cat.PACKET, "[syncm] 未知 PacketType " + logicChannel + " ← " + player.getName().getString() + "（丢弃）");
            SyncmaticaLog.warn("SyncmaticaHandler: unknown PacketType {} from {}", logicChannel, player.getName().getString());
            return;
        }
        SyncmaticaDebug.log(SyncmaticaDebug.Cat.PACKET, "[syncm] C2S PacketType=" + type + " ← " + player.getName().getString());
        // 剩余字节即 body
        final FriendlyByteBuf body = new FriendlyByteBuf(data.readBytes(data.readableBytes()));

        final Player bukkitPlayer = player.getBukkitEntity();
        final ServerCommunicationManager comMan = (ServerCommunicationManager) context.getCommunicationManager();
        final ExchangeTarget target = comMan.getOrCreateTarget(bukkitPlayer);
        comMan.onPacket(target, type, body);
    }

    @Override
    public void encodeWithSplitter(final ServerPlayer player, final FriendlyByteBuf buf)
    {
        // syncmatica 不用 PacketSplitter（exchange 自写 stop-and-wait）；此方法不会被调用。
    }
}
