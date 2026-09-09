package verymc.top.veryMcProto.mod.jei.network.payload;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;

import verymc.top.veryMcProto.mod.jei.JeiReference;
import verymc.top.veryMcProto.mod.jei.config.JeiConfiguration;
import verymc.top.veryMcProto.mod.jei.network.JeiS2CPacket;

/**
 * cheat 权限同步包（S2C，通道 {@code jei:cheat_permission}）。逐字镜像上游
 * {@code mezz.jei.common.network.packets.PacketCheatPermission}（commit ccc16e8）：
 * {@code BOOL hasPermission + List&lt;STRING_UTF8&gt; allowedCheatingMethods}。
 *
 * <p>发送时机与上游一致：① 应答 {@code PacketRequestCheatPermission}；② 玩家尝试 cheat 但无权限时
 * 主动纠正（{@code ServerCommandUtil.executeGive / setHotbarSlot} 与 {@code PacketDeletePlayerItem.process}
 * 的拒绝分支）。{@code allowedCheatingMethods} 内容为「服务端<b>开启</b>的获取途径」翻译键列表，
 * 客户端据此在拒绝提示中告知玩家可通过何种途径获得权限。
 */
public final class PacketCheatPermission implements JeiS2CPacket
{
    private final boolean hasPermission;
    private final List<String> allowedCheatingMethods;

    public PacketCheatPermission(boolean hasPermission, List<String> allowedCheatingMethods)
    {
        this.hasPermission = hasPermission;
        this.allowedCheatingMethods = List.copyOf(allowedCheatingMethods);
    }

    public PacketCheatPermission(boolean hasPermission, JeiConfiguration serverConfig)
    {
        this(hasPermission, getAllowedCheatingMethods(serverConfig.isCheatModeEnabledForOp(),
                serverConfig.isCheatModeEnabledForCreative(), serverConfig.isCheatModeEnabledForGive()));
    }

    /** 上游 {@code getAllowedCheatingMethods}：按 op → creative → give 顺序收集开启项的翻译键（纯函数，配单测）。 */
    public static List<String> getAllowedCheatingMethods(boolean opEnabled, boolean creativeEnabled, boolean giveEnabled)
    {
        List<String> allowedCheatingMethods = new ArrayList<>();
        if (opEnabled)
        {
            allowedCheatingMethods.add("jei.chat.error.no.cheat.permission.op");
        }
        if (creativeEnabled)
        {
            allowedCheatingMethods.add("jei.chat.error.no.cheat.permission.creative");
        }
        if (giveEnabled)
        {
            allowedCheatingMethods.add("jei.chat.error.no.cheat.permission.give");
        }
        return allowedCheatingMethods;
    }

    @Override
    public net.minecraft.resources.Identifier channelId()
    {
        return JeiReference.CHANNEL_CHEAT_PERMISSION;
    }

    @Override
    public void encode(RegistryFriendlyByteBuf buf)
    {
        buf.writeBoolean(hasPermission);
        buf.writeVarInt(allowedCheatingMethods.size());
        for (String method : allowedCheatingMethods)
        {
            buf.writeUtf(method);
        }
    }
}
