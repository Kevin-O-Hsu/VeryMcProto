package verymc.top.veryMcProto.mod.servux.network;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import io.netty.buffer.Unpooled;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;

/**
 * Litematics 通道协议帧（mod 层）。照抄原版 {@code ServuxLitematicaPacket}（去注解 + jul logger）。
 *
 * <p>协议版本 {@value #PROTOCOL_VERSION}，通道 servux:litematics。相对 Entities 多两类请求：
 * <ul>
 *   <li>{@link Type#PACKET_C2S_BULK_ENTITY_NBT_REQUEST}：区块内批量方块实体 + 实体 NBT（minY/maxY 切片）；</li>
 *   <li>四阶段文件投递（C2S）{@link Type#PACKET_C2S_NBT_RESPONSE_START}/{@code _DATA}：
 *       客户端上传 Litematica 投影（本次降级：仅重组，不加载）。</li>
 * </ul>
 *
 * <p><b>字节布局严格照抄原版</b>：toPacket/fromPacket 与原版逐字节一致，含 ChunkPos / Bulk 切片。
 */
public class ServuxLitematicaPacket implements IServerPayloadData
{
    private Type packetType;
    private int transactionId;
    private int entityId;
    private BlockPos pos;
    private CompoundTag nbt;
    private ChunkPos chunkPos;
    private FriendlyByteBuf buffer;
    public static final int PROTOCOL_VERSION = 1;

    private ServuxLitematicaPacket(Type type)
    {
        this.packetType = type;
        this.transactionId = -1;
        this.entityId = -1;
        this.pos = BlockPos.ZERO;
        this.chunkPos = ChunkPos.ZERO;
        this.nbt = new CompoundTag();
        this.clearPacket();
    }

    public static ServuxLitematicaPacket MetadataRequest(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_C2S_METADATA_REQUEST);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    public static ServuxLitematicaPacket MetadataResponse(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_S2C_METADATA);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    // Entity simple response
    public static ServuxLitematicaPacket SimpleEntityResponse(int entityId, @Nullable CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE);
        if (nbt != null) { packet.nbt.merge(nbt); }
        packet.entityId = entityId;
        return packet;
    }

    public static ServuxLitematicaPacket SimpleBlockResponse(BlockPos pos, @Nullable CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE);
        if (nbt != null) { packet.nbt.merge(nbt); }
        packet.pos = pos.immutable();
        return packet;
    }

    public static ServuxLitematicaPacket BlockEntityRequest(BlockPos pos)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_C2S_BLOCK_ENTITY_REQUEST);
        packet.pos = pos.immutable();
        return packet;
    }

    public static ServuxLitematicaPacket EntityRequest(int entityId)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_C2S_ENTITY_REQUEST);
        packet.entityId = entityId;
        return packet;
    }

    public static ServuxLitematicaPacket BulkNbtRequest(ChunkPos chunkPos, @Nullable CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_C2S_BULK_ENTITY_NBT_REQUEST);
        packet.chunkPos = chunkPos;
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    // Nbt Packet, using Packet Splitter
    public static ServuxLitematicaPacket ResponseS2CStart(@Nonnull CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_S2C_NBT_RESPONSE_START);
        packet.nbt.merge(nbt);
        return packet;
    }

    public static ServuxLitematicaPacket ResponseS2CData(@Nonnull FriendlyByteBuf buffer)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_S2C_NBT_RESPONSE_DATA);
        packet.buffer = new FriendlyByteBuf(buffer.copy());
        packet.nbt = new CompoundTag();
        return packet;
    }

    public static ServuxLitematicaPacket ResponseC2SStart(@Nonnull CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_C2S_NBT_RESPONSE_START);
        packet.nbt.merge(nbt);
        return packet;
    }

    public static ServuxLitematicaPacket ResponseC2SData(@Nonnull FriendlyByteBuf buffer)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_C2S_NBT_RESPONSE_DATA);
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

    public void setTransactionId(int id) { this.transactionId = id; }
    public int getTransactionId() { return this.transactionId; }
    public int getEntityId() { return this.entityId; }
    public BlockPos getPos() { return this.pos; }
    public CompoundTag getCompound() { return this.nbt; }
    public ChunkPos getChunkPos() { return this.chunkPos; }
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
                try { output.writeVarInt(this.transactionId); output.writeBlockPos(this.pos); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket BlockEntityRequest: " + e.getMessage()); }
            }
            case PACKET_C2S_ENTITY_REQUEST ->
            {
                try { output.writeVarInt(this.transactionId); output.writeVarInt(this.entityId); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket EntityRequest: " + e.getMessage()); }
            }
            case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE ->
            {
                try { output.writeBlockPos(this.pos); output.writeNbt(this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket BlockResponse: " + e.getMessage()); }
            }
            case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE ->
            {
                try { output.writeVarInt(this.entityId); output.writeNbt(this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket EntityResponse: " + e.getMessage()); }
            }
            case PACKET_C2S_BULK_ENTITY_NBT_REQUEST ->
            {
                try { output.writeChunkPos(this.chunkPos); output.writeNbt(this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket BulkRequest: " + e.getMessage()); }
            }
            case PACKET_S2C_NBT_RESPONSE_DATA, PACKET_C2S_NBT_RESPONSE_DATA ->
            {
                try { output.writeBytes(this.buffer.copy()); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket buffer: " + e.getMessage()); }
            }
            case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA ->
            {
                try { output.writeNbt(this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket NBT: " + e.getMessage()); }
            }
            default -> Reference.logger().warning("ServuxLitematicaPacket#toPacket: 未知 packet type!");
        }
    }

    @Nullable
    public static ServuxLitematicaPacket fromPacket(FriendlyByteBuf input)
    {
        int i = input.readVarInt();
        Type type = getType(i);

        if (type == null)
        {
            Reference.logger().warning("ServuxLitematicaPacket#fromPacket: 无效 packet type");
            return null;
        }

        try
        {
            switch (type)
            {
                case PACKET_C2S_BLOCK_ENTITY_REQUEST -> { input.readVarInt(); return ServuxLitematicaPacket.BlockEntityRequest(input.readBlockPos()); }
                case PACKET_C2S_ENTITY_REQUEST -> { input.readVarInt(); return ServuxLitematicaPacket.EntityRequest(input.readVarInt()); }
                case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> { return ServuxLitematicaPacket.SimpleBlockResponse(input.readBlockPos(), (CompoundTag) input.readNbt(NbtAccounter.unlimitedHeap())); }
                case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE -> { return ServuxLitematicaPacket.SimpleEntityResponse(input.readVarInt(), (CompoundTag) input.readNbt(NbtAccounter.unlimitedHeap())); }
                case PACKET_C2S_BULK_ENTITY_NBT_REQUEST -> { return ServuxLitematicaPacket.BulkNbtRequest(input.readChunkPos(), (CompoundTag) input.readNbt(NbtAccounter.unlimitedHeap())); }
                case PACKET_S2C_NBT_RESPONSE_DATA -> { return ServuxLitematicaPacket.ResponseS2CData(new FriendlyByteBuf(input.readBytes(input.readableBytes()))); }
                case PACKET_C2S_NBT_RESPONSE_DATA -> { return ServuxLitematicaPacket.ResponseC2SData(new FriendlyByteBuf(input.readBytes(input.readableBytes()))); }
                case PACKET_C2S_METADATA_REQUEST -> { return ServuxLitematicaPacket.MetadataRequest(input.readNbt()); }
                case PACKET_S2C_METADATA -> { return ServuxLitematicaPacket.MetadataResponse(input.readNbt()); }
                default -> Reference.logger().warning("ServuxLitematicaPacket#fromPacket: 未知 packet type!");
            }
        }
        catch (Exception e)
        {
            Reference.logger().warning("ServuxLitematicaPacket#fromPacket: 解析失败 type=" + type + ": " + e.getMessage());
        }

        return null;
    }

    @Override
    public void clear()
    {
        if (this.nbt != null && !this.nbt.isEmpty()) { this.nbt = new CompoundTag(); }
        this.clearPacket();
        this.transactionId = -1;
        this.entityId = -1;
        this.pos = BlockPos.ZERO;
        this.chunkPos = ChunkPos.ZERO;
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
        PACKET_C2S_BULK_ENTITY_NBT_REQUEST(7),
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

    public record Payload(ServuxLitematicaPacket data) implements CustomPacketPayload
    {
        public static final CustomPacketPayload.Type<Payload> ID = new CustomPacketPayload.Type<>(ServuxLitematicaHandler.CHANNEL_ID);
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = CustomPacketPayload.codec(Payload::write, Payload::new);

        public Payload(FriendlyByteBuf input) { this(fromPacket(input)); }

        private void write(FriendlyByteBuf output) { data.toPacket(output); }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return ID; }
    }
}
