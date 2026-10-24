package verymc.top.veryMcProto.mod.servux.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServuxNbtFrames v3 帧编解码单测。
 *
 * <p>验证对上游 {@code DataByteBufUtils}（malilib 0.27.20+ / servux 0.9.9，v3 客户端读侧逐字使用）
 * 的 wire 等价性与防御语义：
 * <ul>
 *   <li>golden 结构断言：帧 = {@code [int32 BE 长度][GZIP(命名根 NBT 流)]}，解压后首字节 0x0A（TAG_Compound）、
 *       根名为空串、载荷可被 vanilla {@link NbtIo} 命名根读取——即 malilib 读侧
 *       {@code readByte(type)+readUTF(name)+payload} 消费形状的逐段契约；</li>
 *   <li>roundtrip：代表性 tag 树（long / byte array / 嵌套 list / 高码位字符串 / 嵌套 compound）双向无损；</li>
 *   <li>空 NBT → END-only 流（单字节 0x00，上游 EmptyData 同构）；</li>
 *   <li>非压缩回退：裸 NBT 帧前缀同样可读（上游 ZipException 回退语义）；</li>
 *   <li>界检查：越界/负长度拒包（不进回退）；垃圾载荷返回 null 不抛出。</li>
 * </ul>
 */
class ServuxNbtFramesTest
{
    private static CompoundTag representativeTag()
    {
        CompoundTag tag = new CompoundTag();
        tag.putLong("longVal", 0x0A000269L);
        tag.putByte("byteVal", (byte) 7);
        tag.putBoolean("flag", true);
        tag.putInt("intVal", -12345);
        tag.putByteArray("byteArray", new byte[] { 0x0A, 0x00, 0x02, 0x69, (byte) 0xFF, 0x00 });
        tag.putString("highCodepoints", "投影数据 Δ ω 你好世界 𠮷");

        CompoundTag nested = new CompoundTag();
        nested.putInt("inner", 42);
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(1));
        list.add(IntTag.valueOf(2));
        list.add(LongTag.valueOf(Long.MAX_VALUE));
        nested.put("list", list);
        tag.put("nested", nested);
        return tag;
    }

    @Test
    void goldenShapeMatchesMalilibReaderContract() throws Exception
    {
        CompoundTag tag = representativeTag();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ServuxNbtFrames.write(buf, tag);

        int declaredLength = buf.readInt();
        byte[] payload = new byte[declaredLength];
        buf.readBytes(payload);
        assertEquals(0, buf.readableBytes(), "frame must be exactly [int32][payload]");

        byte[] plain = gunzip(payload);
        // malilib readFromNbtStream 契约：writeByte(TAG_COMPOUND) + writeUTF(rootName) + 载荷
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(plain));
        assertEquals(0x0A, dis.readByte(), "decompressed stream must start with TAG_COMPOUND");
        assertEquals("", dis.readUTF(), "root tag name must be empty string");

        // 全流可被 vanilla 命名根读取且与原 NBT 等值
        CompoundTag parsed = NbtIo.read(new DataInputStream(new ByteArrayInputStream(plain)));
        assertEquals(tag, parsed);
    }

    @Test
    void roundtripRepresentativeTree()
    {
        CompoundTag tag = representativeTag();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ServuxNbtFrames.write(buf, tag);

        assertEquals(0, buf.readerIndex());
        CompoundTag back = ServuxNbtFrames.read(buf);
        assertNotNull(back);
        assertEquals(tag, back);
        assertEquals(0, buf.readableBytes(), "read must consume the frame exactly");
    }

    @Test
    void emptyNbtWritesEndOnlyStream()
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ServuxNbtFrames.write(buf, new CompoundTag());

        int declaredLength = buf.readInt();
        byte[] plain = gunzip(readRemaining(buf));
        assertArrayEquals(new byte[] { 0x00 }, plain, "empty data must be an END-only stream (upstream EmptyData shape)");

        FriendlyByteBuf reread = new FriendlyByteBuf(Unpooled.buffer());
        reread.writeInt(declaredLength);
        reread.writeBytes(plain);
        assertNull(ServuxNbtFrames.read(reread), "END-only stream reads back as null (upstream returns null on TAG_END)");
    }

    @Test
    void uncompressedFallbackReadsBareNbtFrame() throws Exception
    {
        // 上游回退语义：非 GZIP 载荷按非压缩命名根流解析
        CompoundTag tag = new CompoundTag();
        tag.putInt("k", 99);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(raw));   // 未压缩命名根流

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(raw.size());
        buf.writeBytes(raw.toByteArray());

        CompoundTag back = ServuxNbtFrames.read(buf);
        assertNotNull(back);
        assertEquals(tag, back);
    }

    @Test
    void oversizedLengthIsRejected()
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(167837708);   // 野外实测的垃圾长度形态（v3 客户端误读 v2 裸 NBT 头 4 字节）
        buf.writeBytes(new byte[] { 0x0A, 0x00, 0x02, 0x69 });
        assertNull(ServuxNbtFrames.read(buf), "length beyond readable bytes must be rejected");
    }

    @Test
    void negativeLengthIsRejected()
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(-1);
        assertNull(ServuxNbtFrames.read(buf));
    }

    @Test
    void garbagePayloadReturnsNullNotThrow()
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(2);
        buf.writeBytes(new byte[] { 0x01, 0x02 });   // 非 GZIP 且非合法 NBT 头
        assertNull(ServuxNbtFrames.read(buf));
    }

    private static byte[] gunzip(byte[] payload)
    {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(payload)))
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            gz.transferTo(out);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static byte[] readRemaining(FriendlyByteBuf buf)
    {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }
}
