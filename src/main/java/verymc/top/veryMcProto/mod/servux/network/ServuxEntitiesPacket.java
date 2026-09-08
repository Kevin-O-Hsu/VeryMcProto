package verymc.top.veryMcProto.mod.servux.network;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import io.netty.buffer.Unpooled;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.mod.servux.util.nbt.DataTagIo;

/**
 * Entities 通道协议帧（mod 层）。照抄原版 {@code ServuxEntitiesPacket}（去注解 + jul logger）。
 * 协议版本 {@value #PROTOCOL_VERSION}（26.1：2，并<b>删除 transactionId 前置 VarInt</b>——1.21.11 客户端请求带、
 * 26.1 客户端不再发，收端残留吞读会导致 BlockPos/entityId 错位）。载体分组：
 * Type 1/2 vanilla NBT；Type 5/6（simple 响应）与 Type 7（UNREGISTER_REPLY）DataTag 线格式；
 * Type 10-13 分片恒裸 bytes（重组后整体为 DataTag 帧）。
 */
public class ServuxEntitiesPacket implements IServerPayloadData
{
    private Type packetType;
    private int entityId;
    private BlockPos pos;
    private CompoundTag nbt;
    private FriendlyByteBuf buffer;
    public static final int PROTOCOL_VERSION = 2;

    private ServuxEntitiesPacket(Type type)
    {
        this.packetType = type;
        this.entityId = -1;
        this.pos = BlockPos.ZERO;
        this.nbt = new CompoundTag();
        this.clearPacket();
    }

    public static ServuxEntitiesPacket MetadataRequest(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_C2S_METADATA_REQUEST);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxEntitiesPacket MetadataResponse(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_S2C_METADATA);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxEntitiesPacket UnregisterReply(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_C2S_UNREGISTER_REPLY);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxEntitiesPacket SimpleEntityResponse(int entityId, @Nullable CompoundTag nbt)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE);
        if (nbt != null) { packet.nbt.merge(nbt); }
        packet.entityId = entityId;
        return packet;
    }

    public static ServuxEntitiesPacket SimpleBlockResponse(BlockPos pos, @Nullable CompoundTag nbt)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE);
        if (nbt != null) { packet.nbt.merge(nbt); }
        packet.pos = pos.immutable();
        return packet;
    }

    public static ServuxEntitiesPacket BlockEntityRequest(BlockPos pos)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_C2S_BLOCK_ENTITY_REQUEST);
        packet.pos = pos.immutable();
        return packet;
    }

    public static ServuxEntitiesPacket EntityRequest(int entityId)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_C2S_ENTITY_REQUEST);
        packet.entityId = entityId;
        return packet;
    }

    public static ServuxEntitiesPacket ResponseS2CStart(CompoundTag nbt)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_S2C_NBT_RESPONSE_START);
        packet.nbt.merge(nbt);
        return packet;
    }

    public static ServuxEntitiesPacket ResponseS2CData(FriendlyByteBuf buffer)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_S2C_NBT_RESPONSE_DATA);
        packet.buffer = buffer;
        packet.nbt = new CompoundTag();
        return packet;
    }

    public static ServuxEntitiesPacket ResponseC2SStart(CompoundTag nbt)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_C2S_NBT_RESPONSE_START);
        packet.nbt.merge(nbt);
        return packet;
    }

    public static ServuxEntitiesPacket ResponseC2SData(FriendlyByteBuf buffer)
    {
        var packet = new ServuxEntitiesPacket(Type.PACKET_C2S_NBT_RESPONSE_DATA);
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
    public int getEntityId() { return this.entityId; }
    public BlockPos getPos() { return this.pos; }
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
            case PACKET_C2S_BLOCK_ENTITY_REQUEST ->
            {
                try { output.writeBlockPos(this.pos); }
                catch (Exception e) { Reference.logger().warning("ServuxEntitiesPacket#toPacket BlockEntityRequest: " + e.getMessage()); }
            }
            case PACKET_C2S_ENTITY_REQUEST ->
            {
                try { output.writeVarInt(this.entityId); }
                catch (Exception e) { Reference.logger().warning("ServuxEntitiesPacket#toPacket EntityRequest: " + e.getMessage()); }
            }
            case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE ->
            {
                try { output.writeBlockPos(this.pos); DataTagIo.writeTag(output, this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxEntitiesPacket#toPacket BlockResponse: " + e.getMessage()); }
            }
            case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE ->
            {
                try { output.writeVarInt(this.entityId); DataTagIo.writeTag(output, this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxEntitiesPacket#toPacket EntityResponse: " + e.getMessage()); }
            }
            case PACKET_S2C_NBT_RESPONSE_DATA, PACKET_C2S_NBT_RESPONSE_DATA ->
            {
                try { output.writeBytes(this.buffer.copy()); }
                catch (Exception e) { Reference.logger().warning("ServuxEntitiesPacket#toPacket buffer: " + e.getMessage()); }
            }
            case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA ->
            {
                try { output.writeNbt(this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxEntitiesPacket#toPacket NBT: " + e.getMessage()); }
            }
            case PACKET_C2S_UNREGISTER_REPLY ->
            {
                try { DataTagIo.writeTag(output, this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxEntitiesPacket#toPacket Unregister: " + e.getMessage()); }
            }
            default -> Reference.logger().warning("ServuxEntitiesPacket#toPacket: 未知 packet type!");
        }
    }

    @Nullable
    public static ServuxEntitiesPacket fromPacket(FriendlyByteBuf input)
    {
        int i = input.readVarInt();
        Type type = getType(i);

        if (type == null)
        {
            Reference.logger().warning("ServuxEntitiesPacket#fromPacket: 无效 packet type");
            return null;
        }

        try
        {
            switch (type)
            {
                case PACKET_C2S_BLOCK_ENTITY_REQUEST -> { return ServuxEntitiesPacket.BlockEntityRequest(input.readBlockPos()); }
                case PACKET_C2S_ENTITY_REQUEST -> { return ServuxEntitiesPacket.EntityRequest(input.readVarInt()); }
                case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> { return ServuxEntitiesPacket.SimpleBlockResponse(input.readBlockPos(), DataTagIo.readTag(input)); }
                case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE -> { return ServuxEntitiesPacket.SimpleEntityResponse(input.readVarInt(), DataTagIo.readTag(input)); }
                case PACKET_S2C_NBT_RESPONSE_DATA -> { return ServuxEntitiesPacket.ResponseS2CData(new FriendlyByteBuf(input.readBytes(input.readableBytes()))); }
                case PACKET_C2S_NBT_RESPONSE_DATA -> { return ServuxEntitiesPacket.ResponseC2SData(new FriendlyByteBuf(input.readBytes(input.readableBytes()))); }
                case PACKET_C2S_METADATA_REQUEST -> { return ServuxEntitiesPacket.MetadataRequest(input.readNbt()); }
                case PACKET_S2C_METADATA -> { return ServuxEntitiesPacket.MetadataResponse(input.readNbt()); }
                case PACKET_C2S_UNREGISTER_REPLY -> { return ServuxEntitiesPacket.UnregisterReply(DataTagIo.readTag(input)); }
                default -> Reference.logger().warning("ServuxEntitiesPacket#fromPacket: 未知 packet type!");
            }
        }
        catch (Exception e)
        {
            Reference.logger().warning("ServuxEntitiesPacket#fromPacket: 解析失败 type=" + type + ": " + e.getMessage());
        }

        return null;
    }

    @Override
    public void clear()
    {
        if (this.nbt != null && !this.nbt.isEmpty()) { this.nbt = new CompoundTag(); }
        this.clearPacket();
        this.entityId = -1;
        this.pos = BlockPos.ZERO;
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
        PACKET_C2S_BLOCK_ENTITY_REQUEST(3),
        PACKET_C2S_ENTITY_REQUEST(4),
        PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE(5),
        PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE(6),
        PACKET_C2S_UNREGISTER_REPLY(7),
        // For Packet Splitter (Oversize Packets, S2C)
        PACKET_S2C_NBT_RESPONSE_START(10),
        PACKET_S2C_NBT_RESPONSE_DATA(11),
        // For Packet Splitter (Oversize Packets, C2S)
        PACKET_C2S_NBT_RESPONSE_START(12),
        PACKET_C2S_NBT_RESPONSE_DATA(13);

        private final int type;
        Type(int type) { this.type = type; }
        int get() { return this.type; }
    }

    public record Payload(ServuxEntitiesPacket data) implements CustomPacketPayload
    {
        public static final CustomPacketPayload.Type<Payload> ID = new CustomPacketPayload.Type<>(ServuxEntitiesHandler.CHANNEL_ID);
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = CustomPacketPayload.codec(Payload::write, Payload::new);

        public Payload(FriendlyByteBuf input) { this(fromPacket(input)); }

        private void write(FriendlyByteBuf output) { data.toPacket(output); }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
    }
}
