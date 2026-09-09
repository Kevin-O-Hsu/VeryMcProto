package verymc.top.veryMcProto.mod.jei.transfer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import verymc.top.veryMcProto.Reference;

/**
 * 配方转移服务端算法（mod 层）。逐字移植上游 {@code mezz.jei.common.transfer.BasicRecipeTransferHandlerServer}
 * 与其 {@code RecipeTransferUtil.validateSlots}（commit ccc16e8；log4j → JUL shim，泛型风格不变）。
 *
 * <p><b>行为级移植（EasyPlace 同级敏感度）</b>：槽位归属校验（validateSlots）→ 可清空校验 →
 * 需求集计算 → 取物（complete-set 语义含回滚）→ 清格 → 放物 → 余料归包 → {@code broadcastChanges}。
 * 任何一步失败返回 false（调用方回 {@code recipe_transfer_result} 拒绝），不做半程状态。
 */
public final class BasicRecipeTransferHandlerServer
{
    private BasicRecipeTransferHandlerServer() { }

    /**
     * 校验转移请求的槽位合法性（上游 {@code RecipeTransferUtil.validateSlots}）：
     * 槽 id 在容器范围内；目标槽 ∈ craftingSlots；源槽 ∈ inventorySlots ∪ craftingSlots；
     * 两集合不相交；全部为真实槽（非输出/fake 槽）。
     */
    public static boolean validateSlots(
            Player player,
            Collection<TransferOperation> transferOperations,
            Collection<Slot> craftingSlots,
            Collection<Slot> inventorySlots)
    {
        AbstractContainerMenu container = player.containerMenu;
        List<Integer> invalidOperationSlotIndexes = transferOperations.stream()
                .flatMap(op -> Stream.of(op.inventorySlotId(), op.craftingSlotId()))
                .distinct()
                .filter(slotId -> !isValidSlotId(container, slotId))
                .toList();
        if (!invalidOperationSlotIndexes.isEmpty())
        {
            Reference.logger().warning("[JEI] 转移请求含越界槽 id: " + invalidOperationSlotIndexes);
            return false;
        }

        Set<Integer> inventorySlotIndexes = inventorySlots.stream()
                .map(s -> s.index)
                .collect(Collectors.toSet());
        Set<Integer> craftingSlotIndexes = craftingSlots.stream()
                .map(s -> s.index)
                .collect(Collectors.toSet());

        // 目标槽必须都在 craftingSlots 内
        {
            List<Integer> invalidRecipeIndexes = transferOperations.stream()
                    .map(op -> op.craftingSlot(player.containerMenu))
                    .map(s -> s.index)
                    .filter(s -> !craftingSlotIndexes.contains(s))
                    .toList();
            if (!invalidRecipeIndexes.isEmpty())
            {
                Reference.logger().warning("[JEI] 转移请求的目标槽不在 crafting 槽列表内: " + invalidRecipeIndexes);
                return false;
            }
        }

        // 源槽必须在 inventorySlots 或 craftingSlots 内
        {
            List<Integer> invalidInventorySlotIndexes = transferOperations.stream()
                    .map(op -> op.inventorySlot(player.containerMenu))
                    .map(s -> s.index)
                    .filter(s -> !inventorySlotIndexes.contains(s) && !craftingSlotIndexes.contains(s))
                    .toList();
            if (!invalidInventorySlotIndexes.isEmpty())
            {
                Reference.logger().warning("[JEI] 转移请求的源槽不在 inventory/crafting 槽列表内: " + invalidInventorySlotIndexes);
                return false;
            }
        }

        // 两集合不得相交
        {
            Set<Integer> overlappingSlots = inventorySlotIndexes.stream()
                    .filter(craftingSlotIndexes::contains)
                    .collect(Collectors.toSet());
            if (!overlappingSlots.isEmpty())
            {
                Reference.logger().warning("[JEI] 转移请求的 inventory 槽与 crafting 槽重叠: " + overlappingSlots);
                return false;
            }
        }

        // 全部为真实槽（输出/fake 槽禁止）
        {
            List<Integer> invalidFakeSlots = Stream.concat(craftingSlots.stream(), inventorySlots.stream())
                    .filter(Slot::isFake)
                    .map(slot -> slot.index)
                    .toList();
            if (!invalidFakeSlots.isEmpty())
            {
                Reference.logger().warning("[JEI] 转移请求包含 fake/输出槽: " + invalidFakeSlots);
                return false;
            }
        }

        return true;
    }

    private static boolean isValidSlotId(AbstractContainerMenu container, int slotId)
    {
        return slotId >= 0 && slotId < container.slots.size();
    }

    /**
     * 服务端执行配方转移并回报是否成功（上游 {@code setItemsWithResult}）。
     */
    public static boolean setItemsWithResult(
            Player player,
            List<TransferOperation> transferOperations,
            List<Slot> craftingSlots,
            List<Slot> inventorySlots,
            boolean maxTransfer,
            boolean requireCompleteSets)
    {
        if (!validateSlots(player, transferOperations, craftingSlots, inventorySlots))
        {
            return false;
        }
        if (!canClearCraftingSlots(player, craftingSlots))
        {
            return false;
        }

        List<RequiredTransfer> requiredTransfers = calculateRequiredTransfers(transferOperations, player);
        if (requiredTransfers == null)
        {
            return false;
        }

        // 仅当实现显式请求且玩家请求 max-transfer 时才尽量多转；否则按完整组转移
        boolean transferAsCompleteSets = requireCompleteSets || !maxTransfer;

        Map<Slot, ItemStack> recipeSlotToTakenStacks = takeItemsFromInventory(
                player,
                requiredTransfers,
                craftingSlots,
                inventorySlots,
                transferAsCompleteSets,
                maxTransfer);

        if (recipeSlotToTakenStacks.isEmpty())
        {
            Reference.logger().warning("[JEI] 转移失败：无法从背包取出任何原料。");
            return false;
        }

        // 清空合成格
        List<ItemStack> clearedCraftingItems = clearCraftingGrid(craftingSlots, player);

        // 放入合成格
        List<ItemStack> remainderItems = putItemsIntoCraftingGrid(recipeSlotToTakenStacks, requireCompleteSets);

        // 余料归包
        stowItems(player, inventorySlots, clearedCraftingItems);
        stowItems(player, inventorySlots, remainderItems);

        AbstractContainerMenu container = player.containerMenu;
        container.broadcastChanges();
        return true;
    }

    private static boolean canClearCraftingSlots(Player player, List<Slot> craftingSlots)
    {
        for (Slot craftingSlot : craftingSlots)
        {
            ItemStack stack = craftingSlot.getItem();
            if (!stack.isEmpty() && (!craftingSlot.mayPickup(player) || !craftingSlot.mayPlace(stack)))
            {
                Reference.logger().warning("[JEI] 转移失败：合成槽 " + craftingSlot.index + " 含不可移动物品。");
                return false;
            }
        }
        return true;
    }

    private static int getSlotStackLimit(Map<Slot, ItemStack> recipeSlotToTakenStacks, boolean requireCompleteSets)
    {
        if (!requireCompleteSets)
        {
            return Integer.MAX_VALUE;
        }

        return recipeSlotToTakenStacks.entrySet().stream()
                .mapToInt(e ->
                {
                    Slot craftingSlot = e.getKey();
                    ItemStack transferItem = e.getValue();
                    if (craftingSlot.mayPlace(transferItem))
                    {
                        return craftingSlot.getMaxStackSize(transferItem);
                    }
                    return Integer.MAX_VALUE;
                })
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private static List<ItemStack> clearCraftingGrid(List<Slot> craftingSlots, Player player)
    {
        List<ItemStack> clearedCraftingItems = new ArrayList<>();
        for (Slot craftingSlot : craftingSlots)
        {
            if (!craftingSlot.mayPickup(player))
            {
                continue;
            }

            ItemStack item = craftingSlot.getItem();
            if (!item.isEmpty() && craftingSlot.mayPlace(item))
            {
                ItemStack craftingItem = craftingSlot.safeTake(Integer.MAX_VALUE, Integer.MAX_VALUE, player);
                clearedCraftingItems.add(craftingItem);
            }
        }
        return clearedCraftingItems;
    }

    private static List<ItemStack> putItemsIntoCraftingGrid(Map<Slot, ItemStack> recipeSlotToTakenStacks, boolean requireCompleteSets)
    {
        final int slotStackLimit = getSlotStackLimit(recipeSlotToTakenStacks, requireCompleteSets);
        List<ItemStack> remainderItems = new ArrayList<>();

        recipeSlotToTakenStacks.forEach((slot, stack) ->
        {
            ItemStack remainder = slot.safeInsert(stack, slotStackLimit);
            if (!remainder.isEmpty())
            {
                remainderItems.add(remainder);
            }
        });

        return remainderItems;
    }

    private static List<RequiredTransfer> calculateRequiredTransfers(List<TransferOperation> transferOperations, Player player)
    {
        List<RequiredTransfer> requiredTransfers = new ArrayList<>(transferOperations.size());
        Map<Slot, ItemStack> targetSlotStacks = new HashMap<>();
        for (TransferOperation transferOperation : transferOperations)
        {
            Slot recipeSlot = transferOperation.craftingSlot(player.containerMenu);
            Slot inventorySlot = transferOperation.inventorySlot(player.containerMenu);
            if (!inventorySlot.allowModification(player))
            {
                Reference.logger().warning("[JEI] 转移失败：源槽 " + inventorySlot.index + " 不允许取物。");
                return null;
            }
            final ItemStack slotStack = inventorySlot.getItem();
            if (slotStack.isEmpty())
            {
                Reference.logger().warning("[JEI] 转移失败：源槽 " + inventorySlot.index + " 为空。");
                return null;
            }
            ItemStack stack = slotStack.copy();
            stack.setCount(transferOperation.count());
            if (!recipeSlot.mayPlace(stack))
            {
                Reference.logger().warning("[JEI] 转移失败：目标槽 " + recipeSlot.index + " 不接受该原料。");
                return null;
            }
            ItemStack targetSlotStack = targetSlotStacks.putIfAbsent(recipeSlot, stack);
            if (targetSlotStack != null && !ItemStack.isSameItemSameComponents(targetSlotStack, stack))
            {
                Reference.logger().warning("[JEI] 转移失败：目标槽 " + recipeSlot.index + " 被要求放入不同原料。");
                return null;
            }
            requiredTransfers.add(new RequiredTransfer(recipeSlot, inventorySlot, stack));
        }
        return requiredTransfers;
    }

    private static Map<Slot, ItemStack> takeItemsFromInventory(
            Player player,
            List<RequiredTransfer> requiredTransfers,
            List<Slot> craftingSlots,
            List<Slot> inventorySlots,
            boolean transferAsCompleteSets,
            boolean maxTransfer)
    {
        if (!maxTransfer)
        {
            return removeOneSetOfItemsFromInventory(player, requiredTransfers, craftingSlots, inventorySlots, transferAsCompleteSets);
        }

        List<RequiredTransfer> remainingRequiredTransfers = new ArrayList<>(requiredTransfers);
        final Map<Slot, ItemStack> recipeSlotToResult = new HashMap<>(requiredTransfers.size());
        while (true)
        {
            removeFullRecipeSlots(remainingRequiredTransfers, recipeSlotToResult);
            if (remainingRequiredTransfers.isEmpty())
            {
                break;
            }

            final Map<Slot, ItemStack> foundItemsInSet = removeOneSetOfItemsFromInventory(
                    player, remainingRequiredTransfers, craftingSlots, inventorySlots, transferAsCompleteSets);

            if (foundItemsInSet.isEmpty())
            {
                break;
            }

            merge(recipeSlotToResult, foundItemsInSet);
        }

        return recipeSlotToResult;
    }

    private static void removeFullRecipeSlots(List<RequiredTransfer> requiredTransfers, Map<Slot, ItemStack> recipeSlotToResult)
    {
        Set<Slot> fullRecipeSlots = new HashSet<>();
        for (RequiredTransfer requiredTransfer : requiredTransfers)
        {
            Slot recipeSlot = requiredTransfer.recipeSlot;
            ItemStack resultStack = recipeSlotToResult.get(recipeSlot);
            if (resultStack == null)
            {
                continue;
            }
            int requiredCount = getRequiredCount(requiredTransfers, recipeSlot);
            int maxStackSize = Integer.MAX_VALUE;
            if (recipeSlot.mayPlace(resultStack))
            {
                maxStackSize = recipeSlot.getMaxStackSize(resultStack);
            }
            if (resultStack.getCount() + requiredCount > maxStackSize)
            {
                fullRecipeSlots.add(recipeSlot);
            }
        }
        requiredTransfers.removeIf(requiredTransfer -> fullRecipeSlots.contains(requiredTransfer.recipeSlot));
    }

    private static int getRequiredCount(List<RequiredTransfer> requiredTransfers, Slot recipeSlot)
    {
        return requiredTransfers.stream()
                .filter(requiredTransfer -> requiredTransfer.recipeSlot == recipeSlot)
                .mapToInt(requiredTransfer -> requiredTransfer.stack.getCount())
                .sum();
    }

    private static Map<Slot, ItemStack> removeOneSetOfItemsFromInventory(
            Player player,
            List<RequiredTransfer> requiredTransfers,
            List<Slot> craftingSlots,
            List<Slot> inventorySlots,
            boolean transferAsCompleteSets)
    {
        Map<Slot, ItemStack> originalSlotContents = null;
        if (transferAsCompleteSets)
        {
            // complete-set 模式才需要快照（供整组未凑齐时回滚）
            originalSlotContents = new HashMap<>();
        }

        final Map<Slot, ItemStack> foundItemsInSet = new HashMap<>(requiredTransfers.size());

        for (RequiredTransfer requiredTransfer : requiredTransfers)
        {
            final Slot recipeSlot = requiredTransfer.recipeSlot;
            final ItemStack requiredStack = requiredTransfer.stack;
            final Slot hint = requiredTransfer.hint;

            final Slot sourceSlot = getSlotWithStack(player, requiredStack, craftingSlots, inventorySlots, hint)
                    .orElse(null);
            if (sourceSlot != null)
            {
                // 记录槽原始内容以备回滚
                if (originalSlotContents != null && !originalSlotContents.containsKey(sourceSlot))
                {
                    originalSlotContents.put(sourceSlot, sourceSlot.getItem().copy());
                }

                ItemStack removedItemStack = sourceSlot.safeTake(requiredStack.getCount(), Integer.MAX_VALUE, player);
                merge(foundItemsInSet, recipeSlot, removedItemStack);
            }
            else
            {
                // 凑不齐整组：回滚本轮全部槽变更
                if (transferAsCompleteSets)
                {
                    for (Map.Entry<Slot, ItemStack> slotEntry : originalSlotContents.entrySet())
                    {
                        ItemStack stack = slotEntry.getValue();
                        Slot slot = slotEntry.getKey();
                        slot.set(stack);
                    }
                    return Map.of();
                }
            }
        }
        return foundItemsInSet;
    }

    private static void merge(Map<Slot, ItemStack> result, Map<Slot, ItemStack> addition)
    {
        addition.forEach((slot, itemStack) -> merge(result, slot, itemStack));
    }

    private static ItemStack merge(Map<Slot, ItemStack> result, Slot slot, ItemStack itemStack)
    {
        ItemStack resultItemStack = result.get(slot);
        if (resultItemStack == null)
        {
            resultItemStack = itemStack;
            result.put(slot, resultItemStack);
        }
        else
        {
            resultItemStack.grow(itemStack.getCount());
        }
        return resultItemStack;
    }

    private static Optional<Slot> getSlotWithStack(Player player, ItemStack stack, List<Slot> craftingSlots, List<Slot> inventorySlots, Slot hint)
    {
        return getValidatedHintSlot(player, stack, hint)
                .or(() -> getSlotWithStack(player, craftingSlots, stack))
                .or(() -> getSlotWithStack(player, inventorySlots, stack));
    }

    private static Optional<Slot> getValidatedHintSlot(Player player, ItemStack stack, Slot hint)
    {
        if (isValidAndMatches(player, hint, stack))
        {
            return Optional.of(hint);
        }

        return Optional.empty();
    }

    private static void stowItems(Player player, List<Slot> inventorySlots, List<ItemStack> itemStacks)
    {
        for (ItemStack itemStack : itemStacks)
        {
            ItemStack remainder = stowItem(player, inventorySlots, itemStack);
            if (!remainder.isEmpty())
            {
                if (!player.getInventory().add(remainder))
                {
                    player.drop(remainder, false);
                }
            }
        }
    }

    private static ItemStack stowItem(Player player, Collection<Slot> slots, ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return ItemStack.EMPTY;
        }

        ItemStack remainder = stack.copy();

        // 先并入已有堆
        for (Slot slot : slots)
        {
            if (!slot.mayPickup(player))
            {
                continue;
            }
            final ItemStack inventoryStack = slot.getItem();
            if (!inventoryStack.isEmpty() && inventoryStack.isStackable())
            {
                remainder = slot.safeInsert(remainder);
                if (remainder.isEmpty())
                {
                    return ItemStack.EMPTY;
                }
            }
        }

        // 再放空槽
        for (Slot slot : slots)
        {
            if (slot.getItem().isEmpty())
            {
                remainder = slot.safeInsert(remainder);
                if (remainder.isEmpty())
                {
                    return ItemStack.EMPTY;
                }
            }
        }

        return remainder;
    }

    private static Optional<Slot> getSlotWithStack(Player player, Collection<Slot> slots, ItemStack itemStack)
    {
        return slots.stream()
                .filter(slot -> isValidAndMatches(player, slot, itemStack))
                .findFirst();
    }

    private static boolean isValidAndMatches(Player player, Slot slot, ItemStack stack)
    {
        ItemStack containedStack = slot.getItem();
        return ItemStack.isSameItemSameComponents(stack, containedStack)
                && containedStack.getCount() >= stack.getCount()
                && slot.allowModification(player);
    }

    private record RequiredTransfer(Slot recipeSlot, Slot hint, ItemStack stack) { }
}
