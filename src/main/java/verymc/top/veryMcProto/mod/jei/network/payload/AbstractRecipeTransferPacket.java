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
    /**
     * 列表预分配容量封顶（65536 = vanilla 26.1.2 {@code ByteBufCodecs.collection(...)} 匿名 decode 的
     * {@code Math.min(count, 65536)}，常量 {@code MAX_INITIAL_COLLECTION_SIZE}）。声明 count 攻击者
     * 可控，直达分配器即 GB 级预分配 OutOfMemoryError——Error 穿透全部 catch(Exception)（本类两层 +
     * Paper handleCustomPayload 层）。封顶后：超大声明在元素循环内因字节耗尽 fail-fast（每帧 C2S
     * ≤32767 字节，vanilla DiscardedPayload 解码上限），负数由 ArrayList 构造器抛 IAE，均被
     * JeiServerPlayHandler 逐包 catch 承接。仅影响初始容量提示，合法列表（真实转移 ≤54 元素）零变化。
     */
    private static final int MAX_INITIAL_LIST_CAPACITY = 65536;

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
        List<TransferOperation> list = new ArrayList<>(Math.min(size, MAX_INITIAL_LIST_CAPACITY));
        for (int i = 0; i < size; i++)
        {
            list.add(counted ? TransferOperation.readCounted(buf) : TransferOperation.readUncounted(buf));
        }
        return list;
    }

    /**
     * 解码 VAR_INT 列表（{@code ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list())} 的裸 buffer 等价物；
     * 预分配容量经 {@code MAX_INITIAL_LIST_CAPACITY} 封顶——上游经 {@code ByteBufCodecs.list()} 解码
     * 同样落在 vanilla collection decode 的 65536 初始容量封顶内）。
     */
    public static List<Integer> readVarIntList(FriendlyByteBuf buf)
    {
        int size = buf.readVarInt();
        List<Integer> list = new ArrayList<>(Math.min(size, MAX_INITIAL_LIST_CAPACITY));
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
