package verymc.top.veryMcProto.mod.jei.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.jei.cheat.Cheats;
import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;

/**
 * cheat 删除手持物品包（C2S，通道 {@code jei:delete_player_item}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.PacketDeletePlayerItem}——单个 {@code ItemStack.STREAM_CODEC}。
 *
 * <p>处理（上游 {@code process} 语义）：有权限 → 光标持有同 id 物品则清空，否则告警；无权限 →
 * 告警 + 回 {@code PacketCheatPermission(false)} 纠正。
 */
public final class PacketDeletePlayerItem
{
    private final ItemStack itemStack;

    private PacketDeletePlayerItem(ItemStack itemStack)
    {
        this.itemStack = itemStack;
    }

    public static PacketDeletePlayerItem decode(RegistryFriendlyByteBuf buf)
    {
        return new PacketDeletePlayerItem(ItemStack.STREAM_CODEC.decode(buf));
    }

    public void process(JeiServerPacketContext context)
    {
        Cheats.deletePlayerItem(context, itemStack);
    }
}
