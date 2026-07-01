package verymc.top.veryMcProto.framework.network;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PacketSplitter 分片→重组 round-trip 单测。
 *
 * <p>验证：send 把大包按 {@link PacketSplitter#MAX_PAYLOAD_PER_PACKET_S2C}（≈31995）切片
 * （首片含 VarInt 总长），receive 按相同 key 累积重组、收齐还原原字节。覆盖单片、跨片边界、
 * 超大 expectedSize 拒绝（DoS 防护）。
 *
 * <p>用真实 {@link FriendlyByteBuf}（netty buffer 包装，paperDevBundle 提供，纯 JVM 可用，无需 MC 注册表）
 * + 手写 {@link IPluginServerPlayHandler} stub（捕获 send 各分片字节）。
 */
class PacketSplitterTest
{
    /** 最小 stub：捕获 encodeWithSplitter 各分片字节，其余 no-op。getPayloadChannel 返回 null（receive 私有重载不使用 channel）。 */
    private static final class CapturingHandler implements IPluginServerPlayHandler
    {
        final List<byte[]> slices = new ArrayList<>();

        @Override public Identifier getPayloadChannel() { return null; }
        @Override public boolean isPlayRegistered(Identifier channel) { return false; }
        @Override public void setPlayRegistered(Identifier channel) { }
        @Override public void reset(Identifier channel) { }
        @Override public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player) { }
        @Override public void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buf)
        {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            slices.add(data);
            buf.release();
        }
    }

    private static byte[] payload(int size, int seed)
    {
        byte[] p = new byte[size];
        for (int i = 0; i < size; i++) { p[i] = (byte) ((i * 31 + seed) % 251); }
        return p;
    }

    private static FriendlyByteBuf wrap(byte[] bytes)
    {
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        b.writeBytes(bytes);
        return b;
    }

    private static byte[] readAll(FriendlyByteBuf b)
    {
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    private static FriendlyByteBuf reassemble(CapturingHandler h, long key)
    {
        FriendlyByteBuf result = null;
        for (byte[] slice : h.slices)
        {
            result = PacketSplitter.receive(h, key, new FriendlyByteBuf(Unpooled.wrappedBuffer(slice)));
        }
        assertNotNull(result, "收齐所有分片后应返回完整 buf");
        return result;
    }

    @Test
    void roundTrip_singleSlice()
    {
        byte[] payload = payload(100, 1);
        CapturingHandler h = new CapturingHandler();
        PacketSplitter.send(h, wrap(payload), null);
        assertEquals(1, h.slices.size(), "100 字节应单片");
        assertArrayEquals(payload, readAll(reassemble(h, 101L)));
    }

    @Test
    void roundTrip_crossesSliceBoundary()
    {
        byte[] payload = payload(70_000, 2);
        CapturingHandler h = new CapturingHandler();
        PacketSplitter.send(h, wrap(payload), null);
        assertEquals(3, h.slices.size(), "70000 字节应跨 3 片（每片 ≈31995）");
        assertArrayEquals(payload, readAll(reassemble(h, 102L)));
    }

    @Test
    void receive_rejectsOversizedExpectedSize()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf malicious = new FriendlyByteBuf(Unpooled.buffer());
        malicious.writeVarInt(70_000_000); // > DEFAULT_MAX_RECEIVE_SIZE_S2C(64MB)
        malicious.writeBytes(new byte[10]);
        assertThrows(IllegalArgumentException.class, () -> PacketSplitter.receive(h, 103L, malicious),
                "expectedSize 超 64MB 上限应被拒");
    }
}
