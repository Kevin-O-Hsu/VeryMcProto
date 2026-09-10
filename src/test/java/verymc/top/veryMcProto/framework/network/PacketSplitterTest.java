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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PacketSplitter 分片→重组 round-trip 单测。
 *
 * <p>验证：send 把大包按 {@link PacketSplitter#MAX_PAYLOAD_PER_PACKET_S2C}（≈31995）切片
 * （首片含 VarInt 总长），receive 按相同 key 累积重组、收齐还原原字节。覆盖单片、跨片边界、
 * 超大 expectedSize 拒绝（DoS 防护）、S2C 客户端重组上限预检边界（16MB 严格 {@code >}）。
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

    @Test
    void receive_zeroExpectedSize_completesEmptyAndClearsSession()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf pkt = new FriendlyByteBuf(Unpooled.buffer());
        pkt.writeVarInt(0); // 零总长且无多余字节 → 走正常完成路径
        FriendlyByteBuf full = PacketSplitter.receive(h, 201L, pkt);
        assertNotNull(full, "==0 且无多余字节：应走正常完成路径返回空 buffer");
        assertEquals(0, full.readableBytes(), "空流应返回零长 buffer");
        assertEquals(0, PacketSplitter.readingSessionCount(), "完成后会话应已移除");
    }

    @Test
    void receive_zeroExpectedSizeWithExtraBytes_discardsSilently()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf pkt = new FriendlyByteBuf(Unpooled.buffer());
        pkt.writeVarInt(0);
        pkt.writeBytes(new byte[] { 1, 2, 3 }); // 声明 0 却带多余字节 → 坏流
        assertNull(PacketSplitter.receive(h, 202L, pkt), "==0 且带多余字节：应静默丢弃返回 null（上游 malilib :158-162）");
        assertEquals(0, PacketSplitter.readingSessionCount(), "丢弃后会话应已移除");
    }

    @Test
    void receive_positiveExpectedSizeWithoutPayload_throwsAndClearsSession()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf pkt = new FriendlyByteBuf(Unpooled.buffer());
        pkt.writeVarInt(50); // 正长度但首片无载荷字节 → 坏流
        assertThrows(IllegalArgumentException.class, () -> PacketSplitter.receive(h, 203L, pkt),
                ">0 且无载荷应抛 IAE（上游 servux :145-149）");
        assertEquals(0, PacketSplitter.readingSessionCount(), "异常终态后应清理会话");
    }

    @Test
    void evictExpired_evictsStaleSession()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf pkt = new FriendlyByteBuf(Unpooled.buffer());
        pkt.writeVarInt(100);
        pkt.writeBytes(new byte[10]); // 只收到 10/100，未收齐 → 会话存留（模拟中断上传）
        assertNull(PacketSplitter.receive(h, 204L, pkt));
        assertEquals(1, PacketSplitter.readingSessionCount(), "未收齐的会话应存留");
        // 未来时刻法：now + 20s 使会话视为已过期 20s（与回拨时间戳算术等价；勿用 Long.MAX_VALUE 防减法溢出假绿）
        PacketSplitter.evictExpired(System.currentTimeMillis() + 20_000L);
        assertEquals(0, PacketSplitter.readingSessionCount(), "超过 10s TTL 的中断会话应被驱逐");
    }

    @Test
    void evictExpired_keepsActiveSession_andReceiveRefreshesTimestamp()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf first = new FriendlyByteBuf(Unpooled.buffer());
        first.writeVarInt(100);
        first.writeBytes(new byte[10]);
        assertNull(PacketSplitter.receive(h, 205L, first));
        long t1 = PacketSplitter.sessionLastReceived(205L);

        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // 50ms > Windows currentTimeMillis 粒度（~15.6ms），确保时间戳可区分

        FriendlyByteBuf second = new FriendlyByteBuf(Unpooled.buffer());
        second.writeBytes(new byte[10]); // 续片（无长度头）
        assertNull(PacketSplitter.receive(h, 205L, second));
        assertTrue(PacketSplitter.sessionLastReceived(205L) > t1, "每收一片应刷新 lastReceivedTime（servux 每片刷新语义）");

        PacketSplitter.evictExpired(System.currentTimeMillis()); // 刚收过片 → 未过期
        assertEquals(1, PacketSplitter.readingSessionCount(), "活跃会话不应被误杀");
        PacketSplitter.evictExpired(System.currentTimeMillis() + 20_000L); // 清场，勿遗留静态会话给其他用例
        assertEquals(0, PacketSplitter.readingSessionCount());
    }

    @Test
    void discardAndReleaseAll_clearEverything()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf a = new FriendlyByteBuf(Unpooled.buffer());
        a.writeVarInt(100);
        a.writeBytes(new byte[5]);
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        b.writeVarInt(100);
        b.writeBytes(new byte[5]);
        assertNull(PacketSplitter.receive(h, 206L, a));
        assertNull(PacketSplitter.receive(h, 207L, b));

        int before = PacketSplitter.readingSessionCount();
        PacketSplitter.discardSession(206L);
        assertEquals(before - 1, PacketSplitter.readingSessionCount(), "discardSession 应精确移除指定会话（增量断言，不依赖用例顺序）");
        PacketSplitter.releaseAllSessions();
        assertEquals(0, PacketSplitter.readingSessionCount(), "releaseAllSessions 应清空全部会话");
    }

    @Test
    void send_rejectsFrameOver16MBClientLimit()
    {
        // 16,777,217 = 客户端上限 +1：malilib 26.1 严格 > 语义即销毁 session，服务端入口应整帧拒发
        FriendlyByteBuf buf = wrap(payload(PacketSplitter.MAX_REASSEMBLY_SIZE_S2C + 1, 3));
        CapturingHandler h = new CapturingHandler();
        boolean sent = PacketSplitter.send(h, buf, null);
        assertFalse(sent, "超客户端 16MB 重组上限（+1 字节）应整帧拒发返回 false");
        assertEquals(0, h.slices.size(), "拒发路径应零分片发出（客户端残留会话污染窗口归零）");
        assertEquals(0, buf.refCnt(), "入参 buffer 应已被 finally 释放（无泄漏）");
    }

    @Test
    void send_acceptsExact16MBClientLimit()
    {
        // 恰好 16,777,216：客户端严格 > 语义放行（1.21.11 线客户端为 128MB——回流时改常量须同步此处）
        int size = PacketSplitter.MAX_REASSEMBLY_SIZE_S2C;
        FriendlyByteBuf buf = wrap(payload(size, 4));
        CapturingHandler h = new CapturingHandler();
        assertTrue(PacketSplitter.send(h, buf, null), "恰好等于客户端上限应放行（严格 >，无 off-by-one）");
        int expectedSlices = (size + PacketSplitter.MAX_PAYLOAD_PER_PACKET_S2C - 1) / PacketSplitter.MAX_PAYLOAD_PER_PACKET_S2C;
        assertEquals(expectedSlices, h.slices.size(), "片数按分片常量表达式断言（解除对 31995 的除数耦合）；只数片不重组（免二次 16MB 分配）");
        assertEquals(0, buf.refCnt(), "发送完成后入参 buffer 应已释放");
    }
}
