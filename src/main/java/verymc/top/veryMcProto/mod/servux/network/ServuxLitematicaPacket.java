package verymc.top.veryMcProto.mod.servux.network;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import io.netty.buffer.Unpooled;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;
import verymc.top.veryMcProto.mod.servux.util.nbt.DataTagIo;

/**
 * Litematics 通道协议帧（mod 层）。照抄原版 {@code ServuxLitematicaPacket}（去注解 + jul logger）。
 *
 * <p>协议版本 {@value #PROTOCOL_VERSION}（26.1：2，<b>删除 transactionId 前置 VarInt</b>）。载体分组：
 * Type 1/2 vanilla NBT；Type 5/6（simple 响应）、Type 7（BULK 请求）与 Type 8/14-17（UNREGISTER_REPLY /
 * task 组）DataTag 线格式；Type 10-13 分片恒裸 bytes——<b>C2S 投影上传的重组整体亦为 DataTag 帧，且不再有
 * type VarInt 前缀，改按 NBT 内 "Task" 字符串路由</b>（26.1 客户端 encodeClientData 直接 DataTag 编码）。
 *
 * <p>Type 14-17（task 组，已实现——见 docs/09 §26.1.5）：type 14（C2S TASK_REQUEST）受理 Fill/Delete 选区任务；
 * type 16（S2C TASK_STATUS_SYNC）下行任务进度/完成帧（Fill/Delete/Paste 三类任务共用，Paste 粘贴自任务化后
 * 同走此帧清除客户端 InfoHud renderer）；type 15（S2C TASK_RESPONSE）客户端接收端 TODO 故服务端永不发送；
 * type 17（C2S TASK_CANCEL）上游双向 TODO 死路，同源忽略。
 */
public class ServuxLitematicaPacket implements IServerPayloadData
{
    private Type packetType;
    private int entityId;
    private BlockPos pos;
    private CompoundTag nbt;
    private ChunkPos chunkPos;
    private FriendlyByteBuf buffer;
    public static final int PROTOCOL_VERSION = 2;

    private ServuxLitematicaPacket(Type type)
    {
        this.packetType = type;
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

    public static ServuxLitematicaPacket UnregisterReply(@Nullable CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(Type.PACKET_C2S_UNREGISTER_REPLY);
        if (nbt != null) { packet.nbt.merge(nbt); }
        return packet;
    }

    /** task 组（14-17）占位工厂：仅还帧给 Handler 做日志声明，服务端不执行任务。 */
    public static ServuxLitematicaPacket TaskPacket(Type type, @Nullable CompoundTag nbt)
    {
        var packet = new ServuxLitematicaPacket(type);
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
                try { output.writeBlockPos(this.pos); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket BlockEntityRequest: " + e.getMessage()); }
            }
            case PACKET_C2S_ENTITY_REQUEST ->
            {
                try { output.writeVarInt(this.entityId); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket EntityRequest: " + e.getMessage()); }
            }
            case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE ->
            {
                try { output.writeBlockPos(this.pos); DataTagIo.writeTag(output, this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket BlockResponse: " + e.getMessage()); }
            }
            case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE ->
            {
                try { output.writeVarInt(this.entityId); DataTagIo.writeTag(output, this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket EntityResponse: " + e.getMessage()); }
            }
            case PACKET_C2S_BULK_ENTITY_NBT_REQUEST ->
            {
                try { output.writeChunkPos(this.chunkPos); DataTagIo.writeTag(output, this.nbt); }
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
            case PACKET_C2S_UNREGISTER_REPLY, PACKET_C2S_TASK_REQUEST, PACKET_S2C_TASK_RESPONSE, PACKET_S2C_TASK_STATUS_SYNC, PACKET_C2S_TASK_CANCEL ->
            {
                try { DataTagIo.writeTag(output, this.nbt); }
                catch (Exception e) { Reference.logger().warning("ServuxLitematicaPacket#toPacket Data: " + e.getMessage()); }
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
                case PACKET_C2S_BLOCK_ENTITY_REQUEST -> { return ServuxLitematicaPacket.BlockEntityRequest(input.readBlockPos()); }
                case PACKET_C2S_ENTITY_REQUEST -> { return ServuxLitematicaPacket.EntityRequest(input.readVarInt()); }
                case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> { return ServuxLitematicaPacket.SimpleBlockResponse(input.readBlockPos(), DataTagIo.readTag(input)); }
                case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE -> { return ServuxLitematicaPacket.SimpleEntityResponse(input.readVarInt(), DataTagIo.readTag(input)); }
                case PACKET_C2S_BULK_ENTITY_NBT_REQUEST -> { return ServuxLitematicaPacket.BulkNbtRequest(input.readChunkPos(), DataTagIo.readTag(input)); }
                case PACKET_S2C_NBT_RESPONSE_DATA -> { return ServuxLitematicaPacket.ResponseS2CData(new FriendlyByteBuf(input.readBytes(input.readableBytes()))); }
                case PACKET_C2S_NBT_RESPONSE_DATA -> { return ServuxLitematicaPacket.ResponseC2SData(new FriendlyByteBuf(input.readBytes(input.readableBytes()))); }
                case PACKET_C2S_METADATA_REQUEST -> { return ServuxLitematicaPacket.MetadataRequest(input.readNbt()); }
                case PACKET_S2C_METADATA -> { return ServuxLitematicaPacket.MetadataResponse(input.readNbt()); }
                case PACKET_C2S_UNREGISTER_REPLY -> { return ServuxLitematicaPacket.UnregisterReply(DataTagIo.readTag(input)); }
                // task 组（14-17）：type 14 已实现受理（Fill/Delete/Paste），type 16 为任务帧唯一 S2C 出口；15/17 上游同源不发送/忽略
                case PACKET_C2S_TASK_REQUEST, PACKET_S2C_TASK_RESPONSE, PACKET_S2C_TASK_STATUS_SYNC, PACKET_C2S_TASK_CANCEL ->
                {
                    CompoundTag taskNbt = DataTagIo.readTag(input);
                    return taskNbt != null ? ServuxLitematicaPacket.TaskPacket(type, taskNbt) : null;
                }
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
        PACKET_C2S_UNREGISTER_REPLY(8),
        // For Packet Splitter (Oversize Packets, S2C)
        PACKET_S2C_NBT_RESPONSE_START(10),
        PACKET_S2C_NBT_RESPONSE_DATA(11),
        // For Packet Splitter (Oversize Packets, C2S)
        PACKET_C2S_NBT_RESPONSE_START(12),
        PACKET_C2S_NBT_RESPONSE_DATA(13),
        // Task Scheduler Items（26.1 新增；服务端执行未实现，仅协议占位）
        PACKET_C2S_TASK_REQUEST(14),
        PACKET_S2C_TASK_RESPONSE(15),
        PACKET_S2C_TASK_STATUS_SYNC(16),
        PACKET_C2S_TASK_CANCEL(17);

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
