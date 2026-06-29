package verymc.top.veryMcProto.mod.servux.network;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import io.netty.buffer.Unpooled;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;

/**
 * HUD 通道协议帧（mod 层）。照抄原版 {@code ServuxHudPacket}（去 Fabric 注解 + jul logger）。
 *
 * <p>协议版本 {@value #PROTOCOL_VERSION}。10 种 Type；Payload 内部字节布局：
 * {@code VarInt(packetType) + NBT（CompoundTag）/ raw bytes（buffer slice）}。
 * {@code PACKET_S2C_NBT_RESPONSE_DATA} 携带分包单片（raw bytes）；其余携带 NBT。
 *
 * <p>{@link Payload} record 保留（与原版一致），用于协议帧定义与未来方案 B（NMS 发包）；
 * 方案 A（plugin messaging）收发走 byte[]，由 {@code ServuxHudHandler.sendPlayPayload} 完成。
 */
public class ServuxHudPacket implements IServerPayloadData
{
    private Type packetType;
    private CompoundTag nbt;
    private FriendlyByteBuf buffer;
    public static final int PROTOCOL_VERSION = 2;

    private ServuxHudPacket(Type type)
    {
        this.packetType = type;
        this.nbt = new CompoundTag();
        this.clearPacket();
    }

    public static ServuxHudPacket MetadataRequest(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_C2S_METADATA_REQUEST);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxHudPacket MetadataResponse(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_S2C_METADATA);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxHudPacket SpawnRequest(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_C2S_SPAWN_DATA_REQUEST);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxHudPacket SpawnResponse(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_S2C_SPAWN_DATA);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxHudPacket DataLoggerRequest(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_C2S_DATA_LOGGER_REQUEST);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxHudPacket DataLoggerTick(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_S2C_DATA_LOGGER_TICK);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxHudPacket WeatherTick(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_S2C_WEATHER_TICK);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxHudPacket RecipeManagerRequest(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_C2S_RECIPE_MANAGER_REQUEST);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    /** 大包起始（NBT，走 PacketSplitter 分包）。 */
    public static ServuxHudPacket ResponseS2CStart(CompoundTag nbt)
    {
        var packet = new ServuxHudPacket(Type.PACKET_S2C_NBT_RESPONSE_START);
        packet.nbt.merge(nbt);
        return packet;
    }

    /** 分包单片（raw bytes）。 */
    public static ServuxHudPacket ResponseS2CData(FriendlyByteBuf buffer)
    {
        var packet = new ServuxHudPacket(Type.PACKET_S2C_NBT_RESPONSE_DATA);
        packet.buffer = new FriendlyByteBuf(buffer.copy());
        packet.nbt = new CompoundTag();
        return packet;
    }

    private void clearPacket()
    {
        if (this.buffer != null)
        {
            this.buffer.clear();
            this.buffer = new FriendlyByteBuf(Unpooled.buffer());
        }
    }

    @Override public int getVersion() { return PROTOCOL_VERSION; }
    @Override public int getPacketType() { return this.packetType.get(); }

    @Override
    public int getTotalSize()
    {
        int total = 2;
        if (this.nbt != null && !this.nbt.isEmpty()) { total += this.nbt.sizeInBytes(); }
        if (this.buffer != null) { total += this.buffer.readableBytes(); }
        return total;
    }

    public Type getType() { return this.packetType; }
    public CompoundTag getCompound() { return this.nbt; }
    public FriendlyByteBuf getBuffer() { return this.buffer; }
    public boolean hasBuffer() { return this.buffer != null && this.buffer.isReadable(); }
    public boolean hasNbt() { return this.nbt != null && !this.nbt.isEmpty(); }

    @Override public boolean isEmpty() { return !this.hasBuffer() && !this.hasNbt(); }

    @Override
    public void toPacket(FriendlyByteBuf output)
    {
        output.writeVarInt(this.packetType.get());

        switch (this.packetType)
        {
            case PACKET_S2C_NBT_RESPONSE_DATA ->
            {
                try { output.writeBytes(this.buffer.copy()); }
                catch (Exception e) { Reference.logger().warning("ServuxHudPacket#toPacket: 写入 buffer 失败: " + e.getMessage()); }
            }
            case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA, PACKET_C2S_SPAWN_DATA_REQUEST, PACKET_S2C_SPAWN_DATA,
                 PACKET_S2C_WEATHER_TICK, PACKET_C2S_RECIPE_MANAGER_REQUEST, PACKET_S2C_DATA_LOGGER_TICK,
                 PACKET_C2S_DATA_LOGGER_REQUEST ->
            {
                try { output.writeNbt(this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxHudPacket#toPacket: 写入 NBT 失败: " + e.getMessage()); }
            }
            default -> Reference.logger().warning("ServuxHudPacket#toPacket: 未知 packet type!");
        }
    }

    @Nullable
    public static ServuxHudPacket fromPacket(FriendlyByteBuf input)
    {
        int i = input.readVarInt();
        Type type = getType(i);

        if (type == null)
        {
            Reference.logger().warning("ServuxHudPacket#fromPacket: 收到无效 packet type");
            return null;
        }

        try
        {
            switch (type)
            {
                case PACKET_S2C_NBT_RESPONSE_DATA -> { return ServuxHudPacket.ResponseS2CData(new FriendlyByteBuf(input.readBytes(input.readableBytes()))); }
                case PACKET_C2S_METADATA_REQUEST -> { return ServuxHudPacket.MetadataRequest(input.readNbt()); }
                case PACKET_S2C_METADATA -> { return ServuxHudPacket.MetadataResponse(input.readNbt()); }
                case PACKET_C2S_SPAWN_DATA_REQUEST -> { return ServuxHudPacket.SpawnRequest(input.readNbt()); }
                case PACKET_S2C_SPAWN_DATA -> { return ServuxHudPacket.SpawnResponse(input.readNbt()); }
                case PACKET_C2S_DATA_LOGGER_REQUEST -> { return ServuxHudPacket.DataLoggerRequest(input.readNbt()); }
                case PACKET_S2C_DATA_LOGGER_TICK -> { return ServuxHudPacket.DataLoggerTick(input.readNbt()); }
                case PACKET_S2C_WEATHER_TICK -> { return ServuxHudPacket.WeatherTick(input.readNbt()); }
                case PACKET_C2S_RECIPE_MANAGER_REQUEST -> { return ServuxHudPacket.RecipeManagerRequest(input.readNbt()); }
                default -> Reference.logger().warning("ServuxHudPacket#fromPacket: 未知 packet type!");
            }
        }
        catch (Exception e)
        {
            Reference.logger().warning("ServuxHudPacket#fromPacket: 解析失败 type=" + type + ": " + e.getMessage());
        }

        return null;
    }

    @Override
    public void clear()
    {
        if (this.nbt != null && !this.nbt.isEmpty()) { this.nbt = new CompoundTag(); }
        this.clearPacket();
        this.packetType = null;
    }

    @Nullable
    public static Type getType(int input)
    {
        for (Type type : Type.values())
        {
            if (type.get() == input) { return type; }
        }
        return null;
    }

    public enum Type
    {
        PACKET_S2C_METADATA(1),
        PACKET_C2S_METADATA_REQUEST(2),
        PACKET_S2C_SPAWN_DATA(3),
        PACKET_C2S_SPAWN_DATA_REQUEST(4),
        PACKET_S2C_WEATHER_TICK(5),
        PACKET_C2S_RECIPE_MANAGER_REQUEST(6),
        PACKET_S2C_DATA_LOGGER_TICK(7),
        PACKET_C2S_DATA_LOGGER_REQUEST(8),
        // For Packet Splitter (Oversize Packets, S2C)
        PACKET_S2C_NBT_RESPONSE_START(10),
        PACKET_S2C_NBT_RESPONSE_DATA(11);

        private final int type;

        Type(int type) { this.type = type; }

        int get() { return this.type; }
    }

    /**
     * 协议帧 Payload（保留定义；方案 A 走 byte[] 收发，方案 B 可用此 record 经 NMS 发包）。
     */
    public record Payload(ServuxHudPacket data) implements CustomPacketPayload
    {
        public static final CustomPacketPayload.Type<Payload> ID = new CustomPacketPayload.Type<>(ServuxHudHandler.CHANNEL_ID);
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = CustomPacketPayload.codec(Payload::write, Payload::new);

        public Payload(FriendlyByteBuf input)
        {
            this(fromPacket(input));
        }

        private void write(FriendlyByteBuf output)
        {
            data.toPacket(output);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type()
        {
            return ID;
        }
    }
}
