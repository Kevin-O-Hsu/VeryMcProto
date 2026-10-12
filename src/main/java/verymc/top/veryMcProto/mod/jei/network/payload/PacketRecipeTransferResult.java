package verymc.top.veryMcProto.mod.jei.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

import verymc.top.veryMcProto.mod.jei.JeiReference;
import verymc.top.veryMcProto.mod.jei.network.JeiS2CPacket;
import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;

/**
 * 配方转移结果回执包（S2C，通道 {@code jei:recipe_transfer_result}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.PacketRecipeTransferResult}：
 * {@code VAR_INT transferId + BOOL successful}。
 *
 * <p>{@code transferId} 是<b>客户端侧</b>关联键（客户端在发起转移时登记
 * {@code PENDING_RECIPE_TRANSFERS}，回执到达后据此完成回调）——服务端只透传，无会话状态。
 */
public final class PacketRecipeTransferResult implements JeiS2CPacket
{
    private final int transferId;
    private final boolean successful;

    public PacketRecipeTransferResult(int transferId, boolean successful)
    {
        this.transferId = transferId;
        this.successful = successful;
    }

    @Override
    public Identifier channelId()
    {
        return JeiReference.CHANNEL_RECIPE_TRANSFER_RESULT;
    }

    @Override
    public void encode(RegistryFriendlyByteBuf buf)
    {
        buf.writeVarInt(transferId);
        buf.writeBoolean(successful);
    }

    /** 转移失败/成功时的统一回执出口（上游 {@code PacketRecipeTransferWithResult.sendResult}）。 */
    public static void sendResult(JeiServerPacketContext context, int transferId, boolean successful)
    {
        context.sendPacketToClient(new PacketRecipeTransferResult(transferId, successful));
    }
}
