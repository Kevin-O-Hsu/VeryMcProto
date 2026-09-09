package verymc.top.veryMcProto.mod.jei.network.payload;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;

import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;
import verymc.top.veryMcProto.mod.jei.transfer.TransferOperation;

/**
 * 配方转移包（C2S，通道 {@code jei:recipe_transfer_counted_with_result}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.PacketRecipeTransferCountedWithResult}——counted 操作 + 结果回执。
 * 线序同 {@link PacketRecipeTransferWithResult}，仅操作列表每项多一个 VAR_INT count。
 */
public final class PacketRecipeTransferCountedWithResult extends AbstractRecipeTransferPacket
{
    private final int transferId;

    private PacketRecipeTransferCountedWithResult(List<TransferOperation> transferOperations,
                                                  List<Integer> craftingSlots,
                                                  List<Integer> inventorySlots,
                                                  boolean maxTransfer,
                                                  boolean requireCompleteSets,
                                                  int transferId)
    {
        super(transferOperations, craftingSlots, inventorySlots, maxTransfer, requireCompleteSets);
        this.transferId = transferId;
    }

    public static PacketRecipeTransferCountedWithResult decode(RegistryFriendlyByteBuf buf)
    {
        return new PacketRecipeTransferCountedWithResult(
                readOperations(buf, true),
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
