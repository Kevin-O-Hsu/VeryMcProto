package verymc.top.veryMcProto.mod.servux.util.nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

import verymc.top.veryMcProto.Reference;

/**
 * malilib/servux 26.1「DataTag」线格式编解码器（vanilla {@link CompoundTag} 视角，纯函数）。
 *
 * <p>MC 26.1 起 servux 协议业务包的 NBT 载体从 vanilla {@code writeNbt} 切换为 malilib DataTag 线格式
 * （对照 {@code OriginImpl/servux-LTS-26.1 util/data/tag/util/DataByteBufUtils.java} 逐字节实证）：
 *
 * <pre>线格式 = [int32 大端 压缩后字节数][GZIP 压缩流]</pre>
 *
 * <p>流内为经典 NBT 具名根布局：{@code [byte tagType][writeUTF 根名（空串，读端丢弃）][CompoundTag 条目 + TAG_END]}，
 * 与原版 {@link NbtIo} 的具名根输出（空根名）<b>逐字节兼容</b>——故本类不移植 malilib 的 18 个 DataTag 类，
 * 直接以 NMS CompoundTag 为内部表示、借 {@link NbtIo} 完成序列化。
 *
 * <p>边界语义（上游实证，勿改）：
 * <ul>
 *   <li><b>写端恒发 tagType=10 根</b>（哪怕零键），绝不发上游 EmptyData 的 0x00 单字节形态——
 *       上游读端 {@code DataFileUtils.readFromNbtStream} 对 TAG_END 根返回 null → 包被整包丢弃；</li>
 *   <li>读端对 0x00 根（上游 EmptyData）容错为空 CompoundTag（上游调用方 {@code opt.ifPresent} 为静默丢弃，
 *       服务端收端选择保留空语义以便上层继续 Task 路由）；</li>
 *   <li>读端 GZIP 解压失败（{@link ZipException}）时回落按非压缩裸读（上游 {@code fromByteBuf} 同构）；</li>
 *   <li>长度前缀为<b>压缩后</b>字节数、大端 int32（{@code ByteBuf.writeInt}），<b>不是 VarInt</b>；</li>
 *   <li>单包 NBT 流上限 64MB（上游 {@code SizeTracker.NETWORK_MAX_BYTES}）。</li>
 * </ul>
 */
public final class DataTagIo
{
    /** 上游 {@code SizeTracker.NETWORK_MAX_BYTES} = 64MB：单包 NBT 流（压缩前）大小上限。 */
    public static final long NETWORK_MAX_BYTES = 64L * 1024L * 1024L;

    private DataTagIo() { }

    /**
     * 把 CompoundTag 以 DataTag 线格式写入 FriendlyByteBuf（S2C 发送用）。
     * 序列化失败时防御性写「空 compound 的线格式」，绝不抛穿调用方（上游 toPacket 均为 try/catch + log 形态）。
     */
    public static void writeTag(FriendlyByteBuf output, @Nullable CompoundTag tag)
    {
        CompoundTag root = (tag != null) ? tag : new CompoundTag();

        if (root.sizeInBytes() > NETWORK_MAX_BYTES)
        {
            // 上游超限为 SizeTrackerException → 抛 IOException → 包被丢弃；这里同构降级为空包 + 日志
            Reference.logger().warning("DataTagIo#writeTag: NBT 超过 64MB 网络上限（" + root.sizeInBytes() + " 字节），降级为空 compound");
            root = new CompoundTag();
        }

        byte[] payload = gzipNamedRoot(root);
        output.writeInt(payload.length);
        output.writeBytes(payload);
    }

    /**
     * 从 FriendlyByteBuf 读出 DataTag 线格式的 CompoundTag（C2S 接收用）。
     * 长度非法（负数 / 超出可读字节 / 超 64MB）返回 null（调用方按坏包丢弃）。
     */
    @Nullable
    public static CompoundTag readTag(FriendlyByteBuf input)
    {
        int length = input.readInt();

        if (length < 0 || length > input.readableBytes() || length > NETWORK_MAX_BYTES)
        {
            Reference.logger().warning("DataTagIo#readTag: 非法长度前缀 " + length + "（可读 " + input.readableBytes() + "）");
            return null;
        }

        byte[] payload = new byte[length];
        input.readBytes(payload);

        byte[] raw = gunzipOrRaw(payload);
        if (raw == null || raw.length == 0)
        {
            return new CompoundTag();
        }

        if (raw[0] == 0x00)
        {
            // 上游 EmptyData（TAG_END 根，单字节）→ 空语义
            return new CompoundTag();
        }

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw)))
        {
            CompoundTag tag = NbtIo.read(dis);
            return (tag != null) ? tag : new CompoundTag();
        }
        catch (Exception e)
        {
            Reference.logger().warning("DataTagIo#readTag: NBT 解析失败: " + e.getMessage());
            return null;
        }
    }

    /** 序列化为 [GZIP(具名根 NBT 流)]：[10][utf ""][条目 + TAG_END]，与上游 writeToNbtStream 逐字节兼容。 */
    private static byte[] gzipNamedRoot(CompoundTag root)
    {
        try
        {
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (DataOutputStream os = new DataOutputStream(new GZIPOutputStream(gz)))
            {
                NbtIo.write(root, os);
            }
            return gz.toByteArray();
        }
        catch (Exception e)
        {
            Reference.logger().warning("DataTagIo#gzipNamedRoot: GZIP 序列化失败: " + e.getMessage());
            // 兜底：空 compound（一个 0 键的 TAG_COMPOUND 根，非 EmptyData 形态）
            try
            {
                ByteArrayOutputStream gz = new ByteArrayOutputStream();
                try (DataOutputStream os = new DataOutputStream(new GZIPOutputStream(gz)))
                {
                    NbtIo.write(new CompoundTag(), os);
                }
                return gz.toByteArray();
            }
            catch (Exception e2)
            {
                // GZIP 一个空 compound 不可能失败；此处仅形式完备
                return new byte[0];
            }
        }
    }

    /** GZIP 解压；ZipException 时回落按非压缩裸读（上游 fromByteBuf 同构）。 */
    @Nullable
    private static byte[] gunzipOrRaw(byte[] payload)
    {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(payload);
             GZIPInputStream gis = new GZIPInputStream(bis);
             ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            gis.transferTo(out);
            return out.toByteArray();
        }
        catch (ZipException e)
        {
            return payload;
        }
        catch (Exception e)
        {
            Reference.logger().warning("DataTagIo#gunzipOrRaw: 解压失败: " + e.getMessage());
            return null;
        }
    }
}
