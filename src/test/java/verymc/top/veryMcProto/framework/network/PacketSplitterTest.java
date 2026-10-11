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
        assertNotNull(result, "should return the full buffer once all fragments arrive");
        return result;
    }

    @Test
    void roundTrip_singleSlice()
    {
        byte[] payload = payload(100, 1);
        CapturingHandler h = new CapturingHandler();
        PacketSplitter.send(h, wrap(payload), null);
        assertEquals(1, h.slices.size(), "100 bytes should fit in a single fragment");
        assertArrayEquals(payload, readAll(reassemble(h, 101L)));
    }

    @Test
    void roundTrip_crossesSliceBoundary()
    {
        byte[] payload = payload(70_000, 2);
        CapturingHandler h = new CapturingHandler();
        PacketSplitter.send(h, wrap(payload), null);
        assertEquals(3, h.slices.size(), "70000 bytes should span 3 fragments (~31995 each)");
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
                "expectedSize over the 64MB limit should be rejected");
    }

    @Test
    void receive_zeroExpectedSize_completesEmptyAndClearsSession()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf pkt = new FriendlyByteBuf(Unpooled.buffer());
        pkt.writeVarInt(0); // 零总长且无多余字节 → 走正常完成路径
        FriendlyByteBuf full = PacketSplitter.receive(h, 201L, pkt);
        assertNotNull(full, "==0 with no extra bytes: should take the normal completion path and return an empty buffer");
        assertEquals(0, full.readableBytes(), "empty stream should return a zero-length buffer");
        assertEquals(0, PacketSplitter.readingSessionCount(), "session should be removed after completion");
    }

    @Test
    void receive_zeroExpectedSizeWithExtraBytes_discardsSilently()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf pkt = new FriendlyByteBuf(Unpooled.buffer());
        pkt.writeVarInt(0);
        pkt.writeBytes(new byte[] { 1, 2, 3 }); // 声明 0 却带多余字节 → 坏流
        assertNull(PacketSplitter.receive(h, 202L, pkt), "==0 with extra bytes: should be silently discarded returning null (upstream malilib :158-162)");
        assertEquals(0, PacketSplitter.readingSessionCount(), "session should be removed after discard");
    }

    @Test
    void receive_positiveExpectedSizeWithoutPayload_throwsAndClearsSession()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf pkt = new FriendlyByteBuf(Unpooled.buffer());
        pkt.writeVarInt(50); // 正长度但首片无载荷字节 → 坏流
        assertThrows(IllegalArgumentException.class, () -> PacketSplitter.receive(h, 203L, pkt),
                ">0 with no payload should throw IAE (upstream servux :145-149)");
        assertEquals(0, PacketSplitter.readingSessionCount(), "session should be cleaned up after the exceptional terminal state");
    }

    @Test
    void evictExpired_evictsStaleSession()
    {
        CapturingHandler h = new CapturingHandler();
        FriendlyByteBuf pkt = new FriendlyByteBuf(Unpooled.buffer());
        pkt.writeVarInt(100);
        pkt.writeBytes(new byte[10]); // 只收到 10/100，未收齐 → 会话存留（模拟中断上传）
        assertNull(PacketSplitter.receive(h, 204L, pkt));
        assertEquals(1, PacketSplitter.readingSessionCount(), "incomplete session should persist");
        // 未来时刻法：now + 20s 使会话视为已过期 20s（与回拨时间戳算术等价；勿用 Long.MAX_VALUE 防减法溢出假绿）
        PacketSplitter.evictExpired(System.currentTimeMillis() + 20_000L);
        assertEquals(0, PacketSplitter.readingSessionCount(), "interrupted session past the 10s TTL should be evicted");
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
        assertTrue(PacketSplitter.sessionLastReceived(205L) > t1, "each received fragment should refresh lastReceivedTime (servux per-fragment refresh semantics)");

        PacketSplitter.evictExpired(System.currentTimeMillis()); // 刚收过片 → 未过期
        assertEquals(1, PacketSplitter.readingSessionCount(), "active session should not be mistakenly evicted");
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
        assertEquals(before - 1, PacketSplitter.readingSessionCount(), "discardSession should remove exactly the specified session (incremental assertion, independent of test order)");
        PacketSplitter.releaseAllSessions();
        assertEquals(0, PacketSplitter.readingSessionCount(), "releaseAllSessions should clear all sessions");
    }

    @Test
    void send_rejectsFrameOver16MBClientLimit()
    {
        // 16,777,217 = 客户端上限 +1：malilib 26.1 严格 > 语义即销毁 session，服务端入口应整帧拒发
        FriendlyByteBuf buf = wrap(payload(PacketSplitter.MAX_REASSEMBLY_SIZE_S2C + 1, 3));
        CapturingHandler h = new CapturingHandler();
        boolean sent = PacketSplitter.send(h, buf, null);
        assertFalse(sent, "one byte over the client 16MB reassembly limit should reject the whole frame and return false");
        assertEquals(0, h.slices.size(), "reject path should emit zero fragments (no leftover client session pollution)");
        assertEquals(0, buf.refCnt(), "input buffer should have been released in finally (no leak)");
    }

    @Test
    void send_acceptsExact16MBClientLimit()
    {
        // 恰好 16,777,216：客户端严格 > 语义放行（1.21.11 线客户端为 128MB——回流时改常量须同步此处）
        int size = PacketSplitter.MAX_REASSEMBLY_SIZE_S2C;
        FriendlyByteBuf buf = wrap(payload(size, 4));
        CapturingHandler h = new CapturingHandler();
        assertTrue(PacketSplitter.send(h, buf, null), "exactly at the client limit should be accepted (strict >, no off-by-one)");
        int expectedSlices = (size + PacketSplitter.MAX_PAYLOAD_PER_PACKET_S2C - 1) / PacketSplitter.MAX_PAYLOAD_PER_PACKET_S2C;
        assertEquals(expectedSlices, h.slices.size(), "slice count asserted via fragment-constant expression (decoupled from the 31995 divisor); count slices only without reassembling (avoids a second 16MB allocation)");
        assertEquals(0, buf.refCnt(), "input buffer should be released after send");
    }
}
