package verymc.top.veryMcProto.mod.jei.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import verymc.top.veryMcProto.mod.jei.cheat.Cheats;
import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;

/**
 * cheat 放置热键栏包（C2S，通道 {@code jei:set_hotbar_item_stack}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.PacketSetHotbarItemStack}——
 * {@code ItemStack.STREAM_CODEC + VAR_INT hotbarSlot}（服务端再经 {@code Inventory.isHotbarSlot} 校验 0-8）。
 */
public final class PacketSetHotbarItemStack
{
    private final ItemStack itemStack;
    private final int hotbarSlot;

    private PacketSetHotbarItemStack(ItemStack itemStack, int hotbarSlot)
    {
        this.itemStack = itemStack;
        this.hotbarSlot = hotbarSlot;
    }

    public static PacketSetHotbarItemStack decode(RegistryFriendlyByteBuf buf)
    {
        ItemStack itemStack = ItemStack.STREAM_CODEC.decode(buf);
        int hotbarSlot = buf.readVarInt();
        return new PacketSetHotbarItemStack(itemStack, hotbarSlot);
    }

    public void process(JeiServerPacketContext context)
    {
        Cheats.setHotbarSlot(context, itemStack, hotbarSlot);
    }
}
