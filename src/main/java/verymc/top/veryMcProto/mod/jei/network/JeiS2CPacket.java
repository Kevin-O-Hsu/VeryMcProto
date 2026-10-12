package verymc.top.veryMcProto.mod.jei.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

/**
 * JEI S2C 包抽象（mod 层）。对应上游 {@code mezz.jei.common.network.packets.PlayToClientPacket} 的
 * Paper 移植形态：上游靠 Fabric {@code PayloadTypeRegistry} 注册 codec + {@code ServerPlayNetworking.send}，
 * 这里改为「裸 buffer 编码 + {@link JeiPacketSender} NMS 直发」。
 */
public interface JeiS2CPacket
{
    /** 通道 id（见 {@code JeiReference}）。 */
    Identifier channelId();

    /** 线序编码（与上游 STREAM_CODEC 逐字段一致）。 */
    void encode(RegistryFriendlyByteBuf buf);
}
