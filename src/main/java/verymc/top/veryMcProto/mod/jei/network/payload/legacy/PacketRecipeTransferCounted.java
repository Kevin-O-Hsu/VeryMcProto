package verymc.top.veryMcProto.mod.jei.network.payload.legacy;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;

import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;
import verymc.top.veryMcProto.mod.jei.network.payload.AbstractRecipeTransferPacket;
import verymc.top.veryMcProto.mod.jei.transfer.TransferOperation;

/**
 * legacy 配方转移包（C2S，通道 {@code jei:recipe_transfer_counted}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.legacy.PacketRecipeTransferCounted}——counted 操作、无回执。
 */
public final class PacketRecipeTransferCounted extends AbstractRecipeTransferPacket
{
    private PacketRecipeTransferCounted(List<TransferOperation> transferOperations,
                                        List<Integer> craftingSlots,
                                        List<Integer> inventorySlots,
                                        boolean maxTransfer,
                                        boolean requireCompleteSets)
    {
        super(transferOperations, craftingSlots, inventorySlots, maxTransfer, requireCompleteSets);
    }

    public static PacketRecipeTransferCounted decode(RegistryFriendlyByteBuf buf)
    {
        return new PacketRecipeTransferCounted(
                readOperations(buf, true),
                readVarIntList(buf),
                readVarIntList(buf),
                buf.readBoolean(),
                buf.readBoolean());
    }

    public void process(JeiServerPacketContext context)
    {
        executeTransfer(context.player().containerMenu, context.player());
    }
}
