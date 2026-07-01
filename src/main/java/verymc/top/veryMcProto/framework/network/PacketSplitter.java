package verymc.top.veryMcProto.framework.network;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

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
 *   <li>坏包（超限 / 空缓冲）立即丢弃该 session，避免卡死后续接收。</li>
 * </ul>
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

    private static final Map<Long, ReadingSession> READING_SESSIONS = new ConcurrentHashMap<>();

    /** 按 S2C 默认分片上限发送（大 NBT 包）。 */
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

    /** 主动丢弃一个未完成的接收会话（玩家断连等清理，防内存泄漏）。 */
    public static void discardSession(long key)
    {
        READING_SESSIONS.remove(key);
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

        private ReadingSession(long key)
        {
            this.key = key;
        }

        @Nullable
        private synchronized FriendlyByteBuf receive(FriendlyByteBuf data, int maxLength)
        {
            data.readerIndex(0);

            if (this.expectedSize < 0)
            {
                this.expectedSize = data.readVarInt();

                if (this.expectedSize > maxLength)
                {
                    PacketSplitter.READING_SESSIONS.remove(this.key);
                    throw new IllegalArgumentException("Payload too large: " + this.expectedSize + " > " + maxLength);
                }

                this.received = new FriendlyByteBuf(Unpooled.buffer(this.expectedSize));
            }

            if (this.received == null)
            {
                PacketSplitter.READING_SESSIONS.remove(this.key);
                throw new RuntimeException("Receive Buffer is empty");
            }

            this.received.writeBytes(data.copy());

            if (this.received.writerIndex() >= this.expectedSize)
            {
                PacketSplitter.READING_SESSIONS.remove(this.key);
                return this.received;
            }

            return null;
        }
    }
}
