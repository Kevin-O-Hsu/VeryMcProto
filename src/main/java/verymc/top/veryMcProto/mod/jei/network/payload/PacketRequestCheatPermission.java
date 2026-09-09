package verymc.top.veryMcProto.mod.jei.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;

import verymc.top.veryMcProto.mod.jei.cheat.Cheats;
import verymc.top.veryMcProto.mod.jei.network.JeiServerPacketContext;

/**
 * 请求 cheat 权限包（C2S，通道 {@code jei:request_cheat_permission}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.PacketRequestCheatPermission}——unit 包（无字段，
 * 上游 {@code StreamCodec.unit(INSTANCE)}）。
 *
 * <p>处理：按服务端配置判定权限 → 回 {@code PacketCheatPermission}。
 */
public final class PacketRequestCheatPermission
{
    private PacketRequestCheatPermission() { }

    public static void decodeAndProcess(RegistryFriendlyByteBuf buf, JeiServerPacketContext context)
    {
        Cheats.replyCheatPermission(context);
    }
}
