package verymc.top.veryMcProto.mod.jei.network;

import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.mod.jei.config.JeiConfiguration;

/**
 * C2S 包处理上下文（mod 层）。对应上游 {@code mezz.jei.common.network.ServerPacketContext}
 * {@code (player, serverConfig, connection)} 的 Paper 移植形态——{@code connection} 收敛为
 * {@link JeiPacketSender} 静态直发。
 */
public record JeiServerPacketContext(ServerPlayer player, JeiConfiguration serverConfig)
{
    /** 回包给客户端（上游 {@code IConnectionToClient.sendPacketToClient}）。 */
    public void sendPacketToClient(JeiS2CPacket packet)
    {
        JeiPacketSender.sendPacketToClient(player, packet);
    }
}
