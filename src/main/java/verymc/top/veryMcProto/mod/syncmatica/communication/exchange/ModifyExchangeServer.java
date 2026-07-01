package verymc.top.veryMcProto.mod.syncmatica.communication.exchange;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import verymc.top.veryMcProto.mod.syncmatica.SyncmaticaContext;
import verymc.top.veryMcProto.mod.syncmatica.communication.ExchangeTarget;
import verymc.top.veryMcProto.mod.syncmatica.data.ServerPlacement;
import verymc.top.veryMcProto.mod.syncmatica.network.PacketType;
import io.netty.buffer.Unpooled;

/**
 * 服务端放置修改 Exchange（移植自 {@code ModifyExchangeServer}）。
 *
 * <p>收 {@code MODIFY_REQUEST}[uuid] 时由 {@code ServerCommunicationManager.handle} 创建。
 * {@code init}：placement 为 null 或已被他人占用 → {@code close(true)}（发 DENY）；否则 {@code accept}（发 ACCEPT + 占锁）。
 * 收 {@code MODIFY_FINISH}[uuid + positionData] → {@code receivePositionData} 应用 + setLastModifiedBy → {@code succeed}。
 *
 * <p><b>Paper 适配</b>：原版 {@code createOrGet(getPartner())}（ExchangeTarget 重载）→ 改用
 * {@code createOrGet(uuid, name)}（P2 删了 ExchangeTarget 重载，从 ExchangeTarget 取 playerId + 名字）。
 */
public class ModifyExchangeServer extends AbstractExchange
{
    private final ServerPlacement placement;
    UUID placementId;

    public ModifyExchangeServer(final UUID placeId, final ExchangeTarget partner, final SyncmaticaContext con)
    {
        super(partner, con);
        placementId = placeId;
        placement = con.getSyncmaticManager().getPlacement(placementId);
    }

    @Override
    public boolean checkPacket(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        return type.equals(PacketType.MODIFY_FINISH) && checkUUID(packetBuf, placement.getId());
    }

    @Override
    public void handle(final PacketType type, final FriendlyByteBuf packetBuf)
    {
        packetBuf.readUUID(); // consume uuid
        if (type.equals(PacketType.MODIFY_FINISH))
        {
            getContext().getCommunicationManager().receivePositionData(placement, packetBuf, getPartner());

            // Paper 适配：从 ExchangeTarget 取 playerId + name（原版 createOrGet(ExchangeTarget)）
            final verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifier identifier =
                    getContext().getPlayerIdentifierProvider().createOrGet(
                            getPartner().getPlayerId(),
                            getPartner().getPlayer().getName()
                    );
            placement.setLastModifiedBy(identifier);
            getContext().getSyncmaticManager().updateServerPlacement(placement);
            succeed();
        }
    }

    @Override
    public void init()
    {
        if (getPlacement() == null || getContext().getCommunicationManager().getModifier(placement) != null)
        {
            close(true); // equivalent to deny
        }
        else
        {
            accept();
        }
    }

    private void accept()
    {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(placement.getId());
        getPartner().sendPacket(PacketType.MODIFY_REQUEST_ACCEPT, buf, getContext());
        getContext().getCommunicationManager().setModifier(placement, this);
    }

    @Override
    protected void sendCancelPacket()
    {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(placementId);
        getPartner().sendPacket(PacketType.MODIFY_REQUEST_DENY, buf, getContext());
    }

    public ServerPlacement getPlacement() { return placement; }

    @Override
    protected void onClose()
    {
        if (getContext().getCommunicationManager().getModifier(placement) == this)
        {
            getContext().getCommunicationManager().setModifier(placement, null);
        }
    }
}
