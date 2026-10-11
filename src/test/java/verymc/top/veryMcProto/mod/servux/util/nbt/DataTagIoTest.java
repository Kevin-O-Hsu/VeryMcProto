package verymc.top.veryMcProto.mod.servux.util.nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataTagIo} 纯函数单测（无需起服务端；NMS 类经 paperDevBundle 在纯 JVM 可用）。
 *
 * <p>覆盖七类必备：黄金向量（对 GZIP 前的流手工断言字节序）、round-trip、ZipException 裸读回落、
 * 空 compound（根恒 tagType=10，非 EmptyData 0x00 形态）、空 list / 嵌套空容器、深层嵌套、
 * 长度前缀闸（读端非法长度拒绝 + 写端 64MB 降级）。
 */
class DataTagIoTest
{
    /** 黄金向量：compound {Foo:int 1} 的 malilib DataTag 流内字节（GZIP 前）。 */
    private static final byte[] GOLDEN_FOO1 = {
            0x0A,                                              // TAG_COMPOUND 根
            0x00, 0x00,                                        // writeUTF("") 根名（读端丢弃）
            0x03,                                              // 条目类型 TAG_Int
            0x00, 0x03, 'F', 'o', 'o',                         // writeUTF("Foo")
            0x00, 0x00, 0x00, 0x01,                            // int32 大端 1
            0x00                                                // TAG_END 收尾
    };

    private static CompoundTag foo1()
    {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Foo", 1);
        return tag;
    }

    /** 写端黄金向量：writeTag 输出 gunzip 后必须与 malilib 流内布局逐字节一致，且长度前缀 = 压缩后字节数。 */
    @Test
    void writeTagGoldenVector() throws Exception
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        DataTagIo.writeTag(buf, foo1());

        int length = buf.readInt();
        byte[] payload = new byte[length];
        buf.readBytes(payload);
        assertEquals(0, buf.readableBytes(), "the writer must consume exactly [int32][payload] with no extra bytes");

        byte[] raw = gunzip(payload);
        assertArrayEquals(GOLDEN_FOO1, raw, "the in-stream layout before GZIP must match malilib DataFileUtils.writeToNbtStream byte for byte");
    }

    /** 读端黄金向量：手工构造 [int32][gzip(GOLDEN)] 必须解析回 {Foo:1}。 */
    @Test
    void readTagGoldenVector() throws Exception
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        byte[] gz = gzip(GOLDEN_FOO1);
        buf.writeInt(gz.length);
        buf.writeBytes(gz);

        CompoundTag tag = DataTagIo.readTag(buf);
        assertNotNull(tag);
        assertEquals(1, tag.getIntOr("Foo", -1));
        assertEquals(0, buf.readableBytes());
    }

    /** ZipException 裸读回落：非压缩裸流 + int32 长度前缀同样可解析（上游 fromByteBuf 同构）。 */
    @Test
    void readTagUncompressedFallback() throws Exception
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(GOLDEN_FOO1.length);
        buf.writeBytes(GOLDEN_FOO1);

        CompoundTag tag = DataTagIo.readTag(buf);
        assertNotNull(tag, "a bare stream (non-GZIP) must parse successfully via the ZipException fallback");
        assertEquals(1, tag.getIntOr("Foo", -1));
    }

    /** 空 compound：写端恒发 tagType=10 根（绝不发 EmptyData 的 0x00 单字节形态）。 */
    @Test
    void emptyCompoundRootIsAlwaysType10() throws Exception
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        DataTagIo.writeTag(buf, new CompoundTag());

        int length = buf.readInt();
        byte[] payload = new byte[length];
        buf.readBytes(payload);
        byte[] raw = gunzip(payload);

        assertEquals(0x0A, raw[0] & 0xFF, "root tagType must be 10 (TAG_COMPOUND); sending 0x00 would make the malilib reader drop the whole packet");
        assertEquals(0x00, raw[raw.length - 1] & 0xFF);

        FriendlyByteBuf rb = new FriendlyByteBuf(Unpooled.buffer());
        rb.writeInt(length);
        rb.writeBytes(payload);
        CompoundTag back = DataTagIo.readTag(rb);
        assertNotNull(back);
        assertTrue(back.isEmpty());
    }

    /** 读端对上游 EmptyData（0x00 单字节根）容错为空 CompoundTag。 */
    @Test
    void readTagEmptyDataTolerated() throws Exception
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        byte[] gz = gzip(new byte[] { 0x00 });
        buf.writeInt(gz.length);
        buf.writeBytes(gz);

        CompoundTag tag = DataTagIo.readTag(buf);
        assertNotNull(tag, "a 0x00 root should be tolerated as an empty compound, not a bad-packet null");
        assertTrue(tag.isEmpty());
    }

    /** round-trip：嵌套 compound / 各 primitive / list / 数组 / 修改版 UTF-8 边界字符（NUL）。 */
    @Test
    void roundTripRichCompound()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("s", "héllo wörld \u0000 nul");   // NUL → 修改版 UTF-8 的 C0 80
        tag.putBoolean("b", true);
        tag.putByte("by", (byte) -5);
        tag.putShort("sh", (short) 300);
        tag.putInt("i", -123456);
        tag.putLong("l", 9876543210L);
        tag.putFloat("f", 1.5f);
        tag.putDouble("d", Math.PI);
        tag.putByteArray("ba", new byte[] { 1, 2, 3, (byte) 0xFF });
        tag.putIntArray("ia", new int[] { Integer.MIN_VALUE, 0, Integer.MAX_VALUE });
        tag.putLongArray("la", new long[] { Long.MIN_VALUE, Long.MAX_VALUE });

        ListTag list = new ListTag();
        list.add(StringTag.valueOf("a"));
        list.add(StringTag.valueOf("b"));
        tag.put("list", list);

        CompoundTag nested = new CompoundTag();
        nested.put("emptyInner", new CompoundTag());
        nested.put("emptyList", new ListTag());
        tag.put("nested", nested);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        DataTagIo.writeTag(buf, tag);
        CompoundTag back = DataTagIo.readTag(buf);

        assertNotNull(back);
        assertEquals(tag, back, "round-trip must be lossless (including nested empty containers and modified UTF-8)");
    }

    /** 深层嵌套（上游读端深度上限 512，取 200 层验证）。 */
    @Test
    void roundTripDeepNesting()
    {
        CompoundTag root = new CompoundTag();
        CompoundTag current = root;
        for (int i = 0; i < 200; i++)
        {
            CompoundTag next = new CompoundTag();
            current.putInt("d" + i, i);
            current.put("n", next);
            current = next;
        }
        current.putString("leaf", "end");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        DataTagIo.writeTag(buf, root);
        CompoundTag back = DataTagIo.readTag(buf);

        assertNotNull(back);
        assertEquals(root, back);
    }

    /** 读端长度闸：负长度 / 超可读字节 / 超 64MB 的前缀一律拒绝为坏包（null）。 */
    @Test
    void readTagRejectsBadLengthPrefix()
    {
        FriendlyByteBuf negative = new FriendlyByteBuf(Unpooled.buffer());
        negative.writeInt(-1);
        negative.writeByte(0x0A);
        assertNull(DataTagIo.readTag(negative), "negative length must be rejected");

        FriendlyByteBuf overflow = new FriendlyByteBuf(Unpooled.buffer());
        overflow.writeInt(100);
        overflow.writeByte(0x0A);
        assertNull(DataTagIo.readTag(overflow), "length beyond readable bytes must be rejected");

        FriendlyByteBuf overLimit = new FriendlyByteBuf(Unpooled.buffer());
        overLimit.writeInt((int) (DataTagIo.NETWORK_MAX_BYTES + 1));
        assertNull(DataTagIo.readTag(overLimit), "length over the 64MB limit must be rejected");
    }

    /** 写端 64MB 闸：超限 compound 降级为空 compound（恒 tagType=10），不抛穿、不静默截断。 */
    @Test
    void writeTagDegradesOverLimit()
    {
        CompoundTag huge = new CompoundTag();
        huge.put("big", new ByteArrayTag(new byte[(int) DataTagIo.NETWORK_MAX_BYTES + 1024]));
        assertTrue(huge.sizeInBytes() > DataTagIo.NETWORK_MAX_BYTES);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        DataTagIo.writeTag(buf, huge);

        CompoundTag back = DataTagIo.readTag(buf);
        assertNotNull(back);
        assertTrue(back.isEmpty(), "over-limit must degrade to an empty compound rather than being sent as-is");
    }

    /** 与原版 NbtIo 具名根输出的字节级互证：同一 compound 的 NbtIo.write 输出 == DataTag 流内布局。 */
    @Test
    void nbtIoNamedRootIsByteCompatible() throws Exception
    {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(raw))
        {
            NbtIo.write(foo1(), out);
        }
        assertArrayEquals(GOLDEN_FOO1, raw.toByteArray(),
                "NbtIo.write(empty root name) must match malilib writeToNbtStream byte for byte (the foundation of DataTagIo's equivalence)");
    }

    /**
     * 长度字段炸弹（NbtAccounter 配额闸锁定）：gzip 的流内只声明 {@code long[2^30]}（8GB 分配）而无数据——
     * 旧实现（NbtIo.read 单参 = unlimitedHeap）会先分配后读、直接 OOM；流式 accounter 必须<b>先记账后分配</b>，
     * 超过 64MB 解压域配额抛 NbtAccounterException → 按坏包契约返回 null。
     * 字节布局：0A=TAG_COMPOUND 根 / 0000=根名"" / 0C=TAG_Long_Array / 0000=条目名"" / 40000000=int32 长度 2^30。
     */
    @Test
    void gzipBombRejectedByAccounter() throws Exception
    {
        byte[] bomb = { 0x0A, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00 };
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        byte[] gz = gzip(bomb);
        buf.writeInt(gz.length);
        buf.writeBytes(gz);

        assertNull(DataTagIo.readTag(buf), "a length-field bomb (declaring an 8GB long[]) must be intercepted as null by the 64MB quota gate and must not trigger allocation");
    }

    /**
     * 截断 gzip（deflate 体为零字节，仅 10 字节 GZIP header）→ peek 首字节触发 inflate 即 EOF——
     * 解压期失败必须维持旧语义「空 tag」（对齐 gunzipOrRaw 时代 :87-90 路径），而非坏包 null。
     */
    @Test
    void truncatedGzipYieldsEmptyTag() throws Exception
    {
        byte[] gz = gzip(GOLDEN_FOO1);
        byte[] headerOnly = java.util.Arrays.copyOf(gz, 10);   // 恰好 10 字节 GZIP header，deflate 为空

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(headerOnly.length);
        buf.writeBytes(headerOnly);

        CompoundTag tag = DataTagIo.readTag(buf);
        assertNotNull(tag, "a truncated gzip (EOF during decompression) should be tolerated as an empty compound, not null");
        assertTrue(tag.isEmpty());
    }

    /** 裸流 1 字节 payload（GZIP 魔数都读不齐 → ctor 抛 EOFException）→ 空 tag（对齐旧 gunzipOrRaw 失败路径）。 */
    @Test
    void bareEmptyDataYieldsEmptyTag()
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(1);
        buf.writeByte(0x0A);

        CompoundTag tag = DataTagIo.readTag(buf);
        assertNotNull(tag, "an over-short payload (EOF during decompression) should be tolerated as an empty compound, not null");
        assertTrue(tag.isEmpty());
    }

    private static byte[] gzip(byte[] data) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out))
        {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws Exception
    {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             GZIPInputStream gis = new GZIPInputStream(bis);
             ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            gis.transferTo(out);
            return out.toByteArray();
        }
    }
}
