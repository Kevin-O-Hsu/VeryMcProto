package verymc.top.veryMcProto.mod.jei.cheat;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.jei.config.JeiConfiguration;
import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;
import verymc.top.veryMcProto.mod.jei.network.payload.PacketCheatPermission;

/**
 * cheat 服务端语义（mod 层）。逐字移植上游 {@code mezz.jei.common.util.ServerCommandUtil}（commit ccc16e8；
 * log4j → JUL shim）。权限三切面对应服务端配置三布尔：
 * creative（创造模式玩家）→ op（{@code Permissions.COMMANDS_GAMEMASTER} = 权限级 2）→ give（{/give} 权限），
 * 短路求值顺序与上游一致。
 *
 * <p><b>Paper 权限映射</b>：上游 op 检查直接复用 NMS {@code ServerPlayer.permissions().hasPermission(...)}（可用）；
 * give 检查上游走命令树 {@code getGiveCommand().canUse(...)}，Paper 上以等价的 Bukkit 权限节点
 * {@code minecraft.command.give}（default: op）判定。
 */
public final class Cheats
{
    private Cheats() { }

    // ───── 权限判定 ─────

    /**
     * 权限矩阵纯函数（可测拆分）：creative → op → give 短路。
     * 对应上游 {@code ServerCommandUtil.hasPermissionForCheatMode}。
     */
    public static boolean hasCheatPermission(boolean cfgCreative, boolean isCreative,
                                             boolean cfgOp, boolean isOpLevel,
                                             boolean cfgGive, boolean canGive)
    {
        if (cfgCreative && isCreative)
        {
            return true;
        }
        if (cfgOp)
        {
            return isOpLevel;
        }
        if (cfgGive)
        {
            return canGive;
        }
        return false;
    }

    /** NMS 参数版：从 {@link ServerPlayer} 与配置提取三布尔后代入纯函数。 */
    public static boolean hasPermissionForCheatMode(ServerPlayer sender, JeiConfiguration serverConfig)
    {
        return hasCheatPermission(
                serverConfig.isCheatModeEnabledForCreative(), sender.isCreative(),
                serverConfig.isCheatModeEnabledForOp(), sender.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),
                serverConfig.isCheatModeEnabledForGive(), sender.getBukkitEntity().hasPermission("minecraft.command.give"));
    }

    /** 应答 {@code jei:request_cheat_permission}（上游 {@code PacketRequestCheatPermission.process}）。 */
    public static void replyCheatPermission(JeiServerPacketContext context)
    {
        ServerPlayer player = context.player();
        JeiConfiguration serverConfig = context.serverConfig();
        boolean hasPermission = hasPermissionForCheatMode(player, serverConfig);
        context.sendPacketToClient(new PacketCheatPermission(hasPermission, serverConfig));
    }

    /** 无权限时的统一纠正回包（上游各拒绝分支共用）。 */
    private static void sendCheatPermissionDenied(JeiServerPacketContext context)
    {
        context.sendPacketToClient(new PacketCheatPermission(false, context.serverConfig()));
    }

    // ───── 给物品（上游 executeGive）─────

    public static void executeGive(JeiServerPacketContext context, ItemStack itemStack, GiveMode giveMode)
    {
        ServerPlayer sender = context.player();
        JeiConfiguration serverConfig = context.serverConfig();
        if (hasPermissionForCheatMode(sender, serverConfig))
        {
            if (itemStack.isEmpty())
            {
                return;
            }
            if (giveMode == GiveMode.INVENTORY)
            {
                giveToInventory(sender, itemStack);
            }
            else if (giveMode == GiveMode.MOUSE_PICKUP)
            {
                mousePickupItemStack(sender, itemStack);
            }
        }
        else
        {
            sendCheatPermissionDenied(context);
        }
    }

    private static void giveToInventory(ServerPlayer entityplayermp, ItemStack itemStack)
    {
        ItemStack itemStackCopy = itemStack.copy();
        boolean flag = entityplayermp.getInventory().add(itemStack);
        if (flag && itemStack.isEmpty())
        {
            itemStack.setCount(1);
            ItemEntity entityitem = entityplayermp.drop(itemStack, false);
            if (entityitem != null)
            {
                entityitem.makeFakeItem();
            }

            entityplayermp.level().playSound(null, entityplayermp.getX(), entityplayermp.getY(), entityplayermp.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
                    ((entityplayermp.getRandom().nextFloat() - entityplayermp.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F);
            entityplayermp.inventoryMenu.broadcastChanges();
        }
        else
        {
            ItemEntity entityitem = entityplayermp.drop(itemStack, false);
            if (entityitem != null)
            {
                entityitem.setNoPickUpDelay();
                entityitem.setTarget(entityplayermp.getUUID());
            }
        }

        notifyGive(entityplayermp, itemStackCopy);
    }

    public static void mousePickupItemStack(ServerPlayer sender, ItemStack itemStack)
    {
        AbstractContainerMenu containerMenu = sender.containerMenu;

        ItemStack itemStackCopy = itemStack.copy();
        ItemStack existingStack = containerMenu.getCarried();

        final int giveCount;
        if (canStack(existingStack, itemStack))
        {
            int newCount = Math.min(existingStack.getMaxStackSize(), existingStack.getCount() + itemStack.getCount());
            giveCount = newCount - existingStack.getCount();
            if (giveCount > 0)
            {
                existingStack.setCount(newCount);
            }
        }
        else
        {
            containerMenu.setCarried(itemStack);
            giveCount = itemStack.getCount();
        }

        if (giveCount > 0)
        {
            itemStackCopy.setCount(giveCount);
            notifyGive(sender, itemStackCopy);
            containerMenu.broadcastChanges();
        }
    }

    public static boolean canStack(ItemStack a, ItemStack b)
    {
        return !a.isEmpty()
                && !b.isEmpty()
                && ItemStack.isSameItemSameComponents(a, b);
    }

    // ───── 热键栏放置（上游 setHotbarSlot）─────

    public static void setHotbarSlot(JeiServerPacketContext context, ItemStack itemStack, int hotbarSlot)
    {
        ServerPlayer sender = context.player();
        JeiConfiguration serverConfig = context.serverConfig();
        if (hasPermissionForCheatMode(sender, serverConfig))
        {
            if (itemStack.isEmpty())
            {
                return;
            }
            if (!Inventory.isHotbarSlot(hotbarSlot))
            {
                return;
            }
            ItemStack stackInSlot = sender.getInventory().getItem(hotbarSlot);
            if (ItemStack.matches(stackInSlot, itemStack))
            {
                return;
            }
            ItemStack itemStackCopy = itemStack.copy();
            sender.getInventory().setItem(hotbarSlot, itemStack);
            sender.level().playSound(null, sender.getX(), sender.getY(), sender.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
                    ((sender.getRandom().nextFloat() - sender.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F);
            sender.inventoryMenu.broadcastChanges();
            notifyGive(sender, itemStackCopy);
        }
        else
        {
            sendCheatPermissionDenied(context);
        }
    }

    // ───── 删除手持（上游 PacketDeletePlayerItem.process 内联逻辑）─────

    public static void deletePlayerItem(JeiServerPacketContext context, ItemStack itemStack)
    {
        ServerPlayer player = context.player();
        JeiConfiguration serverConfig = context.serverConfig();
        if (hasPermissionForCheatMode(player, serverConfig))
        {
            ItemStack playerItem = player.containerMenu.getCarried();
            if (playerItem.getItem() == itemStack.getItem())
            {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
            else if (!playerItem.isEmpty())
            {
                Reference.logger().warning("[JEI] 玩家 '" + player.getName().getString() + "' (" + player.getUUID()
                        + ") 尝试删除 '" + itemStack.getDisplayName().getString()
                        + "' 但手持的是不同物品 '" + playerItem.getDisplayName().getString() + "'。");
            }
        }
        else
        {
            ItemStack playerItem = player.containerMenu.getCarried();
            Reference.logger().warning("[JEI] 玩家 '" + player.getName().getString() + "' (" + player.getUUID()
                    + ") 无 cheat 权限却尝试删除 '" + playerItem.getDisplayName().getString() + "'。");
            sendCheatPermissionDenied(context);
        }
    }

    // ───── 通知（上游 notifyGive）─────

    private static void notifyGive(ServerPlayer player, ItemStack stack)
    {
        CommandSourceStack commandSource = player.createCommandSourceStack();
        int count = stack.getCount();
        Component stackTextComponent = stack.getDisplayName();
        Component displayName = player.getDisplayName();
        Component message = Component.translatable("commands.give.success.single", count, stackTextComponent, displayName);
        commandSource.sendSuccess(() -> message, true);
    }
}
