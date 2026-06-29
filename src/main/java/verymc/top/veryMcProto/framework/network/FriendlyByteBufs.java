package verymc.top.veryMcProto.framework.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/**
 * {@code byte[]} ↔ {@link FriendlyByteBuf} 桥接（框架层）。
 *
 * <p>Bukkit plugin messaging 收发的 {@code byte[]} 即 {@link FriendlyByteBuf} 的<b>裸字节</b>：
 * {@code onPluginMessageReceived} 的 bytes = VarInt(packetType) + NBT/buffer；{@code sendPluginMessage} 同理。
 * 故原版 {@code fromPacket / toPacket} 逻辑可直接复用，仅需在边界做 byte[] 包装 / 提取。
 */
public final class FriendlyByteBufs
{
    private FriendlyByteBufs() { }

    /** 包装接收的 byte[] 为可读缓冲（零拷贝 wrappedBuffer）。 */
    public static FriendlyByteBuf wrap(byte[] bytes)
    {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
    }

    /** 新建可写缓冲。 */
    public static FriendlyByteBuf buffer()
    {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /** 新建指定初始容量的可写缓冲。 */
    public static FriendlyByteBuf buffer(int initialCapacity)
    {
        return new FriendlyByteBuf(Unpooled.buffer(initialCapacity));
    }

    /** 取出 buf 当前可读字节为 byte[]（不改变 readerIndex）。 */
    public static byte[] readableBytes(FriendlyByteBuf buf)
    {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    /** 取出可读字节并释放 buf。 */
    public static byte[] extractAndRelease(FriendlyByteBuf buf)
    {
        try
        {
            return readableBytes(buf);
        }
        finally
        {
            buf.release();
        }
    }

    /** 把 {@link IServerPayloadData} 序列化为 byte[]（toPacket），释放内部缓冲。 */
    public static byte[] encodePayload(IServerPayloadData data)
    {
        FriendlyByteBuf buf = buffer();
        try
        {
            data.toPacket(buf);
            return readableBytes(buf);
        }
        finally
        {
            buf.release();
        }
    }
}
