package verymc.top.veryMcProto.mod.servux.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.mod.servux.ServuxDebug;
import verymc.top.veryMcProto.framework.network.IPluginServerPlayHandler;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.mod.servux.ServuxReference;
import verymc.top.veryMcProto.mod.servux.dataproviders.TweaksDataProvider;
import verymc.top.veryMcProto.mod.servux.util.ServuxNbtFrames;

/**
 * Tweaks 通道收发 Handler（mod 层）。移植自原版 {@code ServuxTweaksHandler}（去 Fabric + networkHandler 形参 +
 * 去 {@code <T extends CustomPacketPayload>} 泛型，框架 {@link IPluginServerPlayHandler} 无泛型）。
 *
 * <p>通道 servux:tweaks（{@link ServuxReference#CHANNEL_TWEAKS}），协议版本 1。
 * 收 C2S（metadata / block entity / entity 请求）→ 分发到 {@link TweaksDataProvider}；
 * 发 S2C 响应（plugin messaging，大包走 {@link verymc.top.veryMcProto.framework.network.PacketSplitter}）。
 */
public class ServuxTweaksHandler implements IPluginServerPlayHandler
{
    private static final ServuxTweaksHandler INSTANCE = new ServuxTweaksHandler();
    public static ServuxTweaksHandler getInstance() { return INSTANCE; }

    public static final Identifier CHANNEL_ID = ServuxReference.CHANNEL_TWEAKS;

    private boolean payloadRegistered = false;
    private final Map<UUID, Integer> failures = new HashMap<>();
    private static final int MAX_FAILURES = 4;
    private final Map<UUID, Long> readingSessionKeys = new HashMap<>();

    public Map<UUID, Long> getReadingSessionKeys() { return this.readingSessionKeys; }

    @Override public Identifier getPayloadChannel() { return CHANNEL_ID; }

    @Override
    public boolean isPlayRegistered(Identifier channel) { return channel.equals(CHANNEL_ID) && this.payloadRegistered; }

    @Override
    public void setPlayRegistered(Identifier channel) { if (channel.equals(CHANNEL_ID)) { this.payloadRegistered = true; } }

    @Override
    public void clearPlayRegistered(Identifier channel) { if (channel.equals(CHANNEL_ID)) { this.payloadRegistered = false; } }

    @Override
    public void reset(Identifier channel) { if (channel.equals(CHANNEL_ID)) { this.failures.clear(); } }

    public void resetFailures(Identifier channel, ServerPlayer player)
    {
        if (channel.equals(CHANNEL_ID)) { this.failures.remove(player.getUUID()); }
    }

    @Override
    public void receivePlayPayload(FriendlyByteBuf data, ServerPlayer player)
    {
        ServuxTweaksPacket packet = ServuxTweaksPacket.fromPacket(data);
        if (packet == null) { return; }
        ServuxDebug.log(ServuxDebug.Cat.PACKET, "C2S tweaks <- " + player.getName().getString() + " type=" + packet.getType());
        this.decodeServerData(CHANNEL_ID, player, packet);
    }

    @Override
    public <P extends IServerPayloadData> void decodeServerData(Identifier channel, ServerPlayer player, P data)
    {
        ServuxTweaksPacket packet = (ServuxTweaksPacket) data;
        if (!channel.equals(CHANNEL_ID)) { return; }

        // 观测行：v3 世代 version 为 int；v2 世代为 string（不受支持，静默降级留痕）
        if (packet.getType() == ServuxTweaksPacket.Type.PACKET_C2S_METADATA_REQUEST)
        {
            int declared = packet.getCompound().getIntOr("version", -1);
            String genInfo = declared != -1
                    ? String.valueOf(declared)
                    : packet.getCompound().getStringOr("version", "absent") + " (v2 generation, unsupported)";
            ServuxDebug.log(ServuxDebug.Cat.HANDSHAKE, "tweaks METADATA_REQUEST <- " + player.getName().getString() + " declaredVersion=" + genInfo);
        }

        switch (packet.getType())
        {
            case PACKET_C2S_METADATA_REQUEST -> TweaksDataProvider.INSTANCE.register(player); // 上游同构：请求即注册（入册+推送）
            case PACKET_C2S_BLOCK_ENTITY_REQUEST -> TweaksDataProvider.INSTANCE.onBlockEntityRequest(player, packet.getPos());
            case PACKET_C2S_ENTITY_REQUEST -> TweaksDataProvider.INSTANCE.onEntityRequest(player, packet.getEntityId());
            case PACKET_C2S_UNREGISTER_REPLY -> TweaksDataProvider.INSTANCE.removePlayer(player); // v2：客户端失败路径注销回执
            // PACKET_C2S_NBT_RESPONSE_DATA（C2S 分片接收）原版注释禁用，保持注释省略
            default -> Reference.logger().warning("ServuxTweaksHandler#decodeServerData: invalid packetType " + packet.getPacketType()
                    + " from " + player.getName().getString() + ", size=" + packet.getTotalSize());
        }
    }

    @Override
    public void encodeWithSplitter(ServerPlayer player, FriendlyByteBuf buffer)
    {
        // Send each PacketSplitter buffer slice
        this.sendPlayPayload(player, ServuxTweaksPacket.ResponseS2CData(buffer));
    }

    @Override
    public <P extends IServerPayloadData> void encodeServerData(ServerPlayer player, P data)
    {
        if (!TweaksDataProvider.INSTANCE.isEnabled()) { return; }

        ServuxTweaksPacket packet = (ServuxTweaksPacket) data;

        // Send Response Data via Packet Splitter
        if (packet.getType().equals(ServuxTweaksPacket.Type.PACKET_S2C_NBT_RESPONSE_START))
        {
            ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData tweaks -> " + player.getName().getString()
                    + " type=" + packet.getType() + " -> PacketSplitter fragmentation");
            // v3 帧：splitter 载荷 = [int32][GZIP NBT]（对齐上游 DataByteBufUtils.toByteBuf → PacketSplitter.send）
            FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            ServuxNbtFrames.write(buffer, packet.getCompound());
            verymc.top.veryMcProto.framework.network.PacketSplitter.send(this, buffer, player);
        }
        else if (!this.sendPlayPayload(player, packet))
        {
            UUID id = player.getUUID();
            int count = this.failures.getOrDefault(id, 0) + 1;

            // Packet failure tracking（第 MAX_FAILURES 次触发并清零）
            if (count >= MAX_FAILURES)
            {
                this.failures.remove(id);
                ServuxDebug.log(ServuxDebug.Cat.PACKET, "encodeServerData tweaks -> " + player.getName().getString()
                        + " exceeded " + MAX_FAILURES + " consecutive send failures, triggering onPacketFailure (Tweakeroo possibly not installed)");
                TweaksDataProvider.INSTANCE.onPacketFailure(player);
            }
            else { this.failures.put(id, count); }
        }
    }
}
