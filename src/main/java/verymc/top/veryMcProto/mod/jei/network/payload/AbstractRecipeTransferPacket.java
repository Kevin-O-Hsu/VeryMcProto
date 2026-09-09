package verymc.top.veryMcProto.mod.jei.network.payload;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.jei.transfer.BasicRecipeTransferHandlerServer;
import verymc.top.veryMcProto.mod.jei.transfer.TransferOperation;

/**
 * 配方转移包共用骨架（mod 层）。四个 wire 变体（with_result / counted_with_result / legacy × 2）的
 * 字段与流程同构，差异仅两点：① 操作列表是否带 count；② 是否回执结果——上游以四个独立类表达
 * （{@code PacketRecipeTransferWithResult} / {@code PacketRecipeTransferCountedWithResult} /
 * {@code legacy.PacketRecipeTransfer} / {@code legacy.PacketRecipeTransferCounted}，commit ccc16e8），
 * 本处以抽象基类收敛公共解码与执行流程，线序逐字段与上游一致。
 *
 * <p>公共线序：{@code List&lt;TransferOperation&gt; + List&lt;VAR_INT&gt; craftingSlots +
 * List&lt;VAR_INT&gt; inventorySlots + BOOL maxTransfer + BOOL requireCompleteSets}
 * （with_result 变体追加 {@code VAR_INT transferId}）。
 */
public abstract class AbstractRecipeTransferPacket
{
    final List<TransferOperation> transferOperations;
    final List<Integer> craftingSlots;
    final List<Integer> inventorySlots;
    final boolean maxTransfer;
    final boolean requireCompleteSets;

    public AbstractRecipeTransferPacket(List<TransferOperation> transferOperations,
                                        List<Integer> craftingSlots,
                                        List<Integer> inventorySlots,
                                        boolean maxTransfer,
                                        boolean requireCompleteSets)
    {
        this.transferOperations = transferOperations;
        this.craftingSlots = craftingSlots;
        this.inventorySlots = inventorySlots;
        this.maxTransfer = maxTransfer;
        this.requireCompleteSets = requireCompleteSets;
    }

    /** 解码操作列表（counted 变体传 true——见 {@link TransferOperation#readCounted}）。 */
    public static List<TransferOperation> readOperations(RegistryFriendlyByteBuf buf, boolean counted)
    {
        int size = buf.readVarInt();
        List<TransferOperation> list = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++)
        {
            list.add(counted ? TransferOperation.readCounted(buf) : TransferOperation.readUncounted(buf));
        }
        return list;
    }

    /** 解码 VAR_INT 列表（{@code ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list())} 的裸 buffer 等价物）。 */
    public static List<Integer> readVarIntList(FriendlyByteBuf buf)
    {
        int size = buf.readVarInt();
        List<Integer> list = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++)
        {
            list.add(buf.readVarInt());
        }
        return list;
    }

    /**
     * 槽 id 纯校验（上游 {@code PacketRecipeTransferWithResult.getSlots} 的可测拆分）：
     * 列表长度不得超过容器槽数，且每个 id 落在 [0, containerSize) 内。
     */
    static boolean validateSlotIds(List<Integer> slotIds, int containerSize)
    {
        if (slotIds.size() > containerSize)
        {
            return false;
        }
        for (int slotId : slotIds)
        {
            if (slotId < 0 || slotId >= containerSize)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 槽 id 列表 → 槽对象（上游 {@code getSlots}：越界/超量记日志并返回 null → 调用方按失败处理）。
     */
    static List<Slot> getSlots(AbstractContainerMenu container, List<Integer> slotIds)
    {
        if (!validateSlotIds(slotIds, container.slots.size()))
        {
            Reference.logger().warning("[JEI] 转移包槽 id 非法: " + slotIds.size() + " 个（容器仅 "
                    + container.slots.size() + " 槽）");
            return null;
        }

        List<Slot> slots = new ArrayList<>(slotIds.size());
        for (int slotId : slotIds)
        {
            slots.add(container.getSlot(slotId));
        }
        return slots;
    }

    /** 公共执行流程：取槽 → 校验失败/执行失败均 false；成功 true。 */
    protected boolean executeTransfer(AbstractContainerMenu container, net.minecraft.world.entity.player.Player player)
    {
        List<Slot> craftingSlots = getSlots(container, this.craftingSlots);
        List<Slot> inventorySlots = getSlots(container, this.inventorySlots);
        if (craftingSlots == null || inventorySlots == null)
        {
            return false;
        }

        return BasicRecipeTransferHandlerServer.setItemsWithResult(
                player,
                transferOperations,
                craftingSlots,
                inventorySlots,
                maxTransfer,
                requireCompleteSets);
    }
}
