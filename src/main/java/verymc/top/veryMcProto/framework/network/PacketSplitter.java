package verymc.top.veryMcProto.framework.network;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;

/**
 * 应用层分包器（框架层）。移植自原版 {@code fi.dy.masa.servux.network.PacketSplitter}（源自 QuickCarpet by skyrising）。
 *
 * <p><b>方案 A 适配（plugin messaging）</b>：Bukkit plugin messaging 单包硬上限 {@code Messenger.MAX_MESSAGE_SIZE = 32768}，
 * 故 S2C 分片常量从原版 1 MiB 下调到 {@value #MAX_TOTAL_PER_PACKET_S2C}（留余量给 VarInt 头 + 通道开销）。
 *
 * <p><b>并发安全增强</b>（相对原版）：
 * <ul>
 *   <li>{@code READING_SESSIONS} 从 {@code HashMap} 改 {@link ConcurrentHashMap}；</li>
 *   <li>单个 {@link ReadingSession#receive} 加 {@code synchronized}，防止 Netty / 异步投递下同一 session 并发重组错乱；</li>
 *   <li>坏包（超限 / 空缓冲 / 零长带余字节）立即丢弃该 session，避免卡死后续接收；</li>
 *   <li>会话带 {@code lastReceivedTime}（每片刷新），由 LifecycleBridge 心跳每 {@link #CLEANER_INTERVAL_TICKS} tick
 *       过期驱逐并 {@code release()}——上游 "PacketSplitter-Cleaner" 守护线程（10s TTL + 5s 扫描）的主线程等价物，
 *       中断的分片上传不再留下永生 session 与未释放 buffer。</li>
 * </ul>
 *
 * <p><b>S2C 客户端重组上限预检</b>：26.1 客户端（malilib）对分片重组帧有 16MB 硬上限
 * （客户端侧 {@code DEFAULT_MAX_RECEIVE_SIZE_S2C = 16777216}，严格 {@code >}，恰好相等放行），超限即销毁
 * 重组会话并抛异常——后续分片还会以垃圾 expectedSize 重建残留会话污染下一帧。故 {@link #send} 入口对
 * 帧总长预检，超限整帧拒发（零分片发出）并记日志，见 {@link #MAX_REASSEMBLY_SIZE_S2C}。
 */
public class PacketSplitter
{
    // 方案 A：plugin messaging 32KiB 上限。S2C 分片留余量（防御客户端 ClientboundCustomPayload 32767 解码上限）。
    public static final int MAX_TOTAL_PER_PACKET_S2C = 32_000;
    public static final int MAX_PAYLOAD_PER_PACKET_S2C = MAX_TOTAL_PER_PACKET_S2C - 5;

    // 接收端缓冲上限。receive 默认用此（C2S 上传——如 servux litematic 粘贴——也走同一 receive 路径：
    // plugin messaging 单物理通道不分方向）。原版另有 C2S 专用常量，但本实现 C2S/S2C 共用一通道，
    // 故只保留一个接收上限（C2S 专用死常量已删——见 docs/TECH_DEBT_AUDIT F006）。DoS 防护最后防线。
    public static final int DEFAULT_MAX_RECEIVE_SIZE_S2C = 67_108_864;

    // 26.1 客户端重组上限预检：malilib【客户端侧】同名常量（DEFAULT_MAX_RECEIVE_SIZE_S2C = 16777216，注意与上方
    // 我方接收侧 64MB 常量同名不同源、方向相反），客户端重组时严格 > 比较即销毁 session 抛异常；恰好相等放行。
    // 1.21.11 线客户端为 128MB——回流 ver/1.21.11 时须同步改值。
    // 被检量 = DataTag 帧化后 buffer 的 writerIndex（4 + GZIP 压缩长），与首包 VarInt 下发 / 客户端 expectedSize
    // 读取三方同源。与 LitematicaSchematic.MAX_TRANSMIT_FILE_SIZE（文件投递入口门禁）数值相同但源头不同——
    // 那边量的是文件字节数，禁合并：贴 16MiB 下方的文件可过文件门禁、其 START 帧仍可能撞本门禁（见 docs/09）。
    public static final int MAX_REASSEMBLY_SIZE_S2C = 16_777_216;

    /** 会话过期阈值（ms）——溯上游 servux/malilib {@code STALE_TIMEOUT_MS = 10000}。 */
    static final long STALE_TIMEOUT_MS = 10_000L;
    /** 过期扫描节拍（tick，100t = 5s）——对齐上游 {@code scheduleAtFixedRate(5, 5, SECONDS)}，由 LifecycleBridge 心跳驱动。 */
    public static final int CLEANER_INTERVAL_TICKS = 100;

    private static final Map<Long, ReadingSession> READING_SESSIONS = new ConcurrentHashMap<>();

    /**
     * 按 S2C 默认分片上限发送（大 NBT 包）。
     *
     * <p>返回 false 含义：帧总长超 26.1 客户端重组上限 {@link #MAX_REASSEMBLY_SIZE_S2C} 被拒——
     * 零分片发出、仅记日志（当前五处调用点均不消费返回值，语义供未来调用方观测用；不计 tickFailures，
     * 超限是数据体量属性而非玩家过错）。
     */
    public static boolean send(IPluginServerPlayHandler handler, FriendlyByteBuf packet, ServerPlayer player)
    {
        return send(handler, packet, MAX_PAYLOAD_PER_PACKET_S2C, player);
    }

    private static boolean send(IPluginServerPlayHandler handler, FriendlyByteBuf packet, int payloadLimit, ServerPlayer player)
    {
        int len = packet.writerIndex();
        packet.resetReaderIndex();

        try
        {
            // 26.1 客户端重组上限预检：超限帧发出去会被客户端销毁 session + 异常裸抛（且后续分片以垃圾
            // expectedSize 重建残留会话污染下一帧），故入口整帧拒发——零分片发出、finally 恒释放本 buffer。
            // 日志有意不限频：每条对应一次真实拦截事件；唯一重复触发源是 Structures 周期全量重发（默认 100t），
            // 上界 ≈ 每名已注册玩家 12 条/分钟，随数据缩量自停。
            if (len > MAX_REASSEMBLY_SIZE_S2C)
            {
                Reference.logger().warning("PacketSplitter: 拒发超限帧 channel=" + handler.getPayloadChannel()
                        + " player=" + (player != null ? player.getName().getString() : "null")
                        + " size=" + len + " > " + MAX_REASSEMBLY_SIZE_S2C
                        + "（超 26.1 客户端分片重组上限，整帧丢弃，零分片发出）");
                return false;
            }

            for (int offset = 0; offset < len; offset += payloadLimit)
            {
                int thisLen = Math.min(len - offset, payloadLimit);
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(thisLen));
                buf.resetWriterIndex();

                if (offset == 0)
                {
                    buf.writeVarInt(len); // 仅首包写【总长度】
                }

                buf.writeBytes(packet, thisLen);
                handler.encodeWithSplitter(player, buf); // 每片独立成一个 Payload 包发送
            }

            return true;
        }
        finally
        {
            packet.release();
        }
    }

    /** 接收侧重组（C2S / 或 S2C 分片回传）。收齐返回完整缓冲，否则返回 null。 */
    public static FriendlyByteBuf receive(IPluginServerPlayHandler handler, long key, FriendlyByteBuf buf)
    {
        return receive(handler.getPayloadChannel(), key, buf, DEFAULT_MAX_RECEIVE_SIZE_S2C);
    }

    @Nullable
    private static FriendlyByteBuf receive(Identifier channel, long key, FriendlyByteBuf buf, int maxLength)
    {
        ReadingSession session = READING_SESSIONS.computeIfAbsent(key, ReadingSession::new);
        return session.receive(buf, maxLength);
    }

    /** 主动丢弃一个未完成的接收会话（玩家断连等清理，防内存泄漏）；一并释放其 netty buffer。 */
    public static void discardSession(long key)
    {
        ReadingSession session = READING_SESSIONS.remove(key);

        if (session != null)
        {
            session.release();
        }
    }

    /**
     * 周期过期清理入口（LifecycleBridge 每 {@link #CLEANER_INTERVAL_TICKS} tick 调一次）。
     *
     * <p>对应上游 "PacketSplitter-Cleaner" 守护线程的 evict 循环（servux/malilib
     * {@code scheduleAtFixedRate(5, 5, SECONDS)}）；此处改挂主线程心跳——receive 同为主线程
     * （Bukkit Messenger 同步分发），无跨线程释放竞态，且随 bridge start/stop 启停，
     * 插件 reload 不泄漏旧 classloader / 线程。
     */
    public static void evictStaleSessions()
    {
        evictExpired(System.currentTimeMillis());
    }

    /** 过期判定纯函数（nowMs 可注入——测试入口）：距最后收片超过 {@link #STALE_TIMEOUT_MS} 即驱逐并释放。 */
    static void evictExpired(long nowMs)
    {
        Iterator<Map.Entry<Long, ReadingSession>> it = READING_SESSIONS.entrySet().iterator();

        while (it.hasNext())
        {
            Map.Entry<Long, ReadingSession> entry = it.next();

            if (nowMs - entry.getValue().lastReceivedTime > STALE_TIMEOUT_MS)
            {
                Reference.logger().warning("PacketSplitter: 过期驱逐读取会话 [" + entry.getKey() + "]（超 " + STALE_TIMEOUT_MS + "ms 未收片）");
                it.remove();
                entry.getValue().release();
            }
        }
    }

    /** 全量释放（LifecycleBridge.stop() 调用）：插件 disable/reload 时确定性回收全部会话 buffer，不依赖 GC cleaner。 */
    public static void releaseAllSessions()
    {
        Iterator<Map.Entry<Long, ReadingSession>> it = READING_SESSIONS.entrySet().iterator();

        while (it.hasNext())
        {
            Map.Entry<Long, ReadingSession> entry = it.next();
            it.remove();
            entry.getValue().release();
        }
    }

    /** 测试桥：当前活动会话数（同包 {@code PacketSplitterTest} 断言用）。 */
    static int readingSessionCount() { return READING_SESSIONS.size(); }

    /** 测试桥：指定会话的最后收片时间（断言「每片刷新」语义用）。 */
    static long sessionLastReceived(long key)
    {
        ReadingSession session = READING_SESSIONS.get(key);
        return session != null ? session.lastReceivedTime : Long.MIN_VALUE;
    }

    /**
     * ReadingSession：按 session key 重组分片。
     *
     * <p>session key：原版旧 MC 用 Pair，新版被移除；Sakura 改成预共享的随机 long key
     * （{@code RandomSource.create(Util.getMillis()).nextLong()}），随握手包下发或接收端自行生成。
     * 每个分片流维护一份 key 映射（HUD 在 handler 的 {@code readingSessionKeys: Map<UUID, Long>}）。
     */
    private static final class ReadingSession
    {
        private final long key;
        private int expectedSize = -1;
        private FriendlyByteBuf received;
        /** 最后收片时间（ms）。volatile：心跳清理与（潜在异步的）接收线程的读写可见性。 */
        private volatile long lastReceivedTime;

        private ReadingSession(long key)
        {
            this.key = key;
            this.lastReceivedTime = System.currentTimeMillis();
        }

        @Nullable
        private synchronized FriendlyByteBuf receive(FriendlyByteBuf data, int maxLength)
        {
            data.readerIndex(0);
            this.lastReceivedTime = System.currentTimeMillis(); // 每片刷新（servux 语义）——活跃大文件流不受 TTL 误杀

            if (this.expectedSize < 0)
            {
                this.expectedSize = data.readVarInt();

                if (this.expectedSize > maxLength)
                {
                    PacketSplitter.READING_SESSIONS.remove(this.key);
                    throw new IllegalArgumentException("Payload too large: " + this.expectedSize + " > " + maxLength);
                }

                // 上游同源边界分支（servux:145-149 / malilib:151-156）：声明正长度却无载荷字节 → 坏流，废弃会话
                if (this.expectedSize > 0 && data.readableBytes() == 0)
                {
                    PacketSplitter.READING_SESSIONS.remove(this.key);
                    throw new IllegalArgumentException("Received size header but no data bytes.");
                }

                // 上游同源边界分支（malilib:158-162）：零长度却带多余字节 → 坏流，静默丢弃（调用方按 null 处理本流无产物）
                if (this.expectedSize == 0 && data.readableBytes() > 0)
                {
                    PacketSplitter.READING_SESSIONS.remove(this.key);
                    return null;
                }

                this.received = new FriendlyByteBuf(Unpooled.buffer(this.expectedSize));
            }

            if (this.received == null)
            {
                PacketSplitter.READING_SESSIONS.remove(this.key);
                throw new NullPointerException("Receive Buffer is empty"); // 上游 :160-163 同为 NPE——落入调用方 catch 集清键
            }

            this.received.writeBytes(data.copy());

            if (this.received.writerIndex() >= this.expectedSize)
            {
                PacketSplitter.READING_SESSIONS.remove(this.key);
                return this.received;
            }

            return null;
        }

        /** 释放会话缓冲（幂等）。synchronized 与 receive 互斥，防 evict/discard 与在途收片的释放竞态。 */
        private synchronized void release()
        {
            if (this.received != null)
            {
                this.received.release();
                this.received = null;
            }
        }
    }
}
