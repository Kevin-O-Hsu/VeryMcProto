package verymc.top.veryMcProto.mod.jei.network.payload;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;

import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;
import verymc.top.veryMcProto.mod.jei.transfer.TransferOperation;

/**
 * 配方转移包（C2S，通道 {@code jei:recipe_transfer_with_result}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.PacketRecipeTransferWithResult}——uncounted 操作 + 结果回执。
 * 线序：{@code ops + craftingSlots + inventorySlots + BOOL maxTransfer + BOOL requireCompleteSets + VAR_INT transferId}。
 */
public final class PacketRecipeTransferWithResult extends AbstractRecipeTransferPacket
{
    private final int transferId;

    private PacketRecipeTransferWithResult(List<TransferOperation> transferOperations,
                                           List<Integer> craftingSlots,
                                           List<Integer> inventorySlots,
                                           boolean maxTransfer,
                                           boolean requireCompleteSets,
                                           int transferId)
    {
        super(transferOperations, craftingSlots, inventorySlots, maxTransfer, requireCompleteSets);
        this.transferId = transferId;
    }

    public static PacketRecipeTransferWithResult decode(RegistryFriendlyByteBuf buf)
    {
        return new PacketRecipeTransferWithResult(
                readOperations(buf, false),
                readVarIntList(buf),
                readVarIntList(buf),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt());
    }

    public void process(JeiServerPacketContext context)
    {
        boolean successful = executeTransfer(context.player().containerMenu, context.player());
        PacketRecipeTransferResult.sendResult(context, transferId, successful);
    }
}
