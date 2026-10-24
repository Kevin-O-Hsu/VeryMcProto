package verymc.top.veryMcProto.mod.servux.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

import verymc.top.veryMcProto.Reference;

/**
 * servux 协议 v3 世代的 NBT 传输帧编解码器（mod 层工具）。
 *
 * <p>wire 布局：{@code [int32 BE 长度][GZIP 压缩的标准命名根 NBT 流]}；读侧对非 GZIP 载荷
 * （上游以 ZipException 判定）回退为非压缩解析。与上游 {@code fi.dy.masa.servux.util.data.tag.util.DataByteBufUtils}
 * （servux 0.9.9 / malilib 0.27.20+，LTS/1.21.11 线 v3 客户端在读侧逐字使用）字节等价：
 * 上游 {@code writeToNbtStream} 写出 {@code writeByte(tagType) + writeUTF(rootName="") + 载荷}，
 * 即标准 vanilla 命名根 NBT 流——本实现经 {@link NbtIo#write}/{@link NbtIo#read} 以纯原版 API
 * 产出/消费同一形状；空数据时上游写 END-only 流（单字节 0x00），此处同构。
 *
 * <p><b>为什么必须帧化</b>：v3 客户端读侧把非 metadata NBT 体的头 4 字节读作 int32 帧长——
 * 裸 NBT 直发会被解析出天文数字长度并在 {@code readSlice} 越界，客户端
 * {@code fromPacket} 吞异常后残留尾随字节，触发原版 {@code PacketDecoder} 断连。
 *
 * <p>读侧防御：长度超可读量即拒包（不进入非压缩回退——截断帧/垃圾长度在野外真实出现，
 * 上游由 readSlice 的越界异常落外层 catch 实现同一语义，此处显式化）。
 */
public final class ServuxNbtFrames
{
    private ServuxNbtFrames() { }

    /**
     * 把 NBT 以 v3 帧格式写入输出缓冲：{@code [int32 BE 压缩长度][GZIP(命名根流)]}。
     * 空/null NBT 写 END-only 流（单字节 0x00 的 GZIP 包），与上游 EmptyData 分支同构。
     * 失败只记日志，不向 output 写半截帧（长度前缀最后写，失败时 output 不受污染）。
     */
    public static void write(FriendlyByteBuf output, @Nullable CompoundTag nbt)
    {
        try
        {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();

            try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(compressed)))
            {
                if (nbt == null || nbt.isEmpty())
                {
                    dos.writeByte(0);
                }
                else
                {
                    NbtIo.write(nbt, dos);
                }
            }

            byte[] bytes = compressed.toByteArray();
            output.writeInt(bytes.length);
            output.writeBytes(bytes);
        }
        catch (Exception e)
        {
            Reference.logger().warning("ServuxNbtFrames#write: failed to frame NBT: " + e.getMessage());
        }
    }

    /**
     * 从输入缓冲解析 v3 帧，返回 NBT；空数据（END-only 流）、格式非法或长度越界返回 {@code null}。
     * 读取完成后 input 恰好耗尽帧区（长度前缀 + 指定字节数），调用方据此判尾随字节。
     */
    @Nullable
    public static CompoundTag read(FriendlyByteBuf input)
    {
        int length = input.readInt();

        if (length < 0 || length > input.readableBytes())
        {
            Reference.logger().warning("ServuxNbtFrames#read: framed length " + length
                    + " exceeds readable bytes " + input.readableBytes() + ", rejecting packet");
            return null;
        }

        byte[] payload = new byte[length];
        input.readBytes(payload);

        byte[] plain;
        try
        {
            plain = gunzip(payload);
        }
        catch (ZipException e)
        {
            // 上游同构：非 GZIP 载荷回退为非压缩解析
            plain = payload;
        }
        catch (Exception e)
        {
            Reference.logger().warning("ServuxNbtFrames#read: failed to decompress frame: " + e.getMessage());
            return null;
        }

        return parseNamedRoot(plain);
    }

    private static byte[] gunzip(byte[] payload) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(payload)))
        {
            gz.transferTo(out);
        }

        return out.toByteArray();
    }

    @Nullable
    private static CompoundTag parseNamedRoot(byte[] plain)
    {
        try
        {
            // END-only 流（空数据）：上游 readByte==TAG_END 时返回 null
            if (plain.length == 0 || plain[0] == 0)
            {
                return null;
            }

            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(plain)), NbtAccounter.unlimitedHeap());
        }
        catch (Exception e)
        {
            Reference.logger().warning("ServuxNbtFrames#read: failed to parse framed NBT: " + e.getMessage());
            return null;
        }
    }
}
