package verymc.top.veryMcProto.mod.jei.transfer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * 单次转移操作（mod 层）：从 {@code inventorySlotId} 取物品放入 {@code craftingSlotId}。
 * 逐字镜像上游 {@code mezz.jei.common.transfer.TransferOperation}（record {@code (inventorySlotId, craftingSlotId, count)}）。
 *
 * <p>线序两种（上游 {@code STREAM_CODEC} / {@code COUNTED_STREAM_CODEC}，均 2/3 × VAR_INT）：
 * uncounted 变体不传 count（解码恒为 1），counted 变体显式传 count（compact 构造校验 {@code count >= 1}）。
 */
public record TransferOperation(int inventorySlotId, int craftingSlotId, int count)
{
    public TransferOperation(int inventorySlotId, int craftingSlotId)
    {
        this(inventorySlotId, craftingSlotId, 1);
    }

    public TransferOperation
    {
        if (count < 1)
        {
            throw new IllegalArgumentException("Transfer operation count must be positive");
        }
    }

    /** uncounted 解码（{@code STREAM_CODEC}：2 × VAR_INT，count 恒 1）。 */
    public static TransferOperation readUncounted(FriendlyByteBuf buf)
    {
        return new TransferOperation(buf.readVarInt(), buf.readVarInt(), 1);
    }

    /** counted 解码（{@code COUNTED_STREAM_CODEC}：3 × VAR_INT；count &lt; 1 由 compact 构造拒绝）。 */
    public static TransferOperation readCounted(FriendlyByteBuf buf)
    {
        return new TransferOperation(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public Slot inventorySlot(AbstractContainerMenu container)
    {
        return container.getSlot(inventorySlotId);
    }

    public Slot craftingSlot(AbstractContainerMenu container)
    {
        return container.getSlot(craftingSlotId);
    }
}
