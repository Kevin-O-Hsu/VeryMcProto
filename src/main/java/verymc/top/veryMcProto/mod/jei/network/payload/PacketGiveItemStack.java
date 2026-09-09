package verymc.top.veryMcProto.mod.jei.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import verymc.top.veryMcProto.mod.jei.cheat.Cheats;
import verymc.top.veryMcProto.mod.jei.cheat.GiveMode;
import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;

/**
 * cheat 给物品包（C2S，通道 {@code jei:give_item_stack}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.PacketGiveItemStack}——
 * {@code ItemStack.STREAM_CODEC + EnumStreamCodec(GiveMode)}（VAR_INT 序号，越界严格拒绝）。
 */
public final class PacketGiveItemStack
{
    private final ItemStack itemStack;
    private final GiveMode giveMode;

    private PacketGiveItemStack(ItemStack itemStack, GiveMode giveMode)
    {
        this.itemStack = itemStack;
        this.giveMode = giveMode;
    }

    public static PacketGiveItemStack decode(RegistryFriendlyByteBuf buf)
    {
        ItemStack itemStack = ItemStack.STREAM_CODEC.decode(buf);
        GiveMode giveMode = buf.readEnum(GiveMode.class);
        return new PacketGiveItemStack(itemStack, giveMode);
    }

    public void process(JeiServerPacketContext context)
    {
        Cheats.executeGive(context, itemStack, giveMode);
    }
}
