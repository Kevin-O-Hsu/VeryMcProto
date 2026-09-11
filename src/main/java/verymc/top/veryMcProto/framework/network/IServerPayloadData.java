package verymc.top.veryMcProto.framework.network;

import javax.annotation.Nullable;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 协议数据统一抽象（框架层）。移植自原版 {@code fi.dy.masa.servux.network.IServerPayloadData}。
 *
 * <p>每条通道的 Packet 实现该接口，统一暴露：协议版本 / packetType / 总大小 / 是否空 / 序列化反序列化 / 清空。
 * 纯接口，无 NMS / Fabric 依赖，原样照抄。
 */
public interface IServerPayloadData
{
    /** 返回该协议版本，须与原版一致（客户端按版本协商）。各通道真值见 mod/servux/network/ 对应
     *  XxxPacket#PROTOCOL_VERSION 常量与 docs/02-network-protocol.md §2 通道总表（勿在此复述裸值，防版本线演进漂移）。 */
    int getVersion();

    /** 返回子消息 packetType id。 */
    int getPacketType();

    /** 估算字节数（诊断日志用）。 */
    int getTotalSize();

    /** 当前是否无数据。 */
    boolean isEmpty();

    /**
     * PacketByteBuf 解码器 —— 如何从 FriendlyByteBuf 还原本实现。
     * <p>[注]：仅为指引；实际由实现类的静态 {@code fromPacket(FriendlyByteBuf)} 完成。
     */
    @Nullable
    static <T extends IServerPayloadData> T fromPacket(FriendlyByteBuf input)
    {
        return null;
    }

    /** PacketByteBuf 编码器 —— 如何把本实现写入 FriendlyByteBuf。 */
    void toPacket(FriendlyByteBuf output);

    /** 清空 / 重置。 */
    void clear();
}
