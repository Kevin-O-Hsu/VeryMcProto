package verymc.top.veryMcProto.mod.jei.network.payload.legacy;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;

import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;
import verymc.top.veryMcProto.mod.jei.network.payload.AbstractRecipeTransferPacket;
import verymc.top.veryMcProto.mod.jei.transfer.TransferOperation;

/**
 * legacy 配方转移包（C2S，通道 {@code jei:recipe_transfer}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.legacy.PacketRecipeTransfer}——uncounted 操作、无回执
 * （旧 JEI 客户端兼容；上游 legacy/README：保留旧 payload id 与 codec，禁止加字段）。
 */
public final class PacketRecipeTransfer extends AbstractRecipeTransferPacket
{
    private PacketRecipeTransfer(List<TransferOperation> transferOperations,
                                 List<Integer> craftingSlots,
                                 List<Integer> inventorySlots,
                                 boolean maxTransfer,
                                 boolean requireCompleteSets)
    {
        super(transferOperations, craftingSlots, inventorySlots, maxTransfer, requireCompleteSets);
    }

    public static PacketRecipeTransfer decode(RegistryFriendlyByteBuf buf)
    {
        return new PacketRecipeTransfer(
                readOperations(buf, false),
                readVarIntList(buf),
                readVarIntList(buf),
                buf.readBoolean(),
                buf.readBoolean());
    }

    public void process(JeiServerPacketContext context)
    {
        // legacy 无回执：槽校验失败静默拒绝，行为与上游一致
        executeTransfer(context.player().containerMenu, context.player());
    }
}
