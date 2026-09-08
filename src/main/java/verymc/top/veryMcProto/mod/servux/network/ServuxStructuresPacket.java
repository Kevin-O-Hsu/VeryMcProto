package verymc.top.veryMcProto.mod.servux.network;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import io.netty.buffer.Unpooled;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.network.IServerPayloadData;

/**
 * Structures 通道协议帧（mod 层）。照抄原版 {@code ServuxStructuresPacket}（去 Fabric 注解 + jul logger）。
 *
 * <p>协议版本 {@value #PROTOCOL_VERSION}（26.1：3，并<b>删除 type 10/11/12</b>——spawn/weather 元数据在 26.1
 * 完全收敛到 HUD 通道，Structures 只剩结构边界框本身）。本通道是 26.1 载体切换的唯一幸存者：
 * <b>全程 vanilla NBT / 裸 bytes</b>，无 DataTag；唯一例外是 START 大包经 PacketSplitter 分片后的
 * <b>重组整体内容</b>为 DataTag 帧（由 Handler 的 encodeServerData 包装，本类不感知）。
 *
 * <p>{@link Payload} record 保留（与原版一致），用于协议帧定义与未来方案 B（NMS 发包）；
 * 方案 A（plugin messaging）收发走 byte[]，由 {@code ServuxStructuresHandler.sendPlayPayload} 完成。
 */
public class ServuxStructuresPacket implements IServerPayloadData
{
    private Type packetType;
    private CompoundTag nbt;
    private FriendlyByteBuf buffer;
    public static final int PROTOCOL_VERSION = 3;

    public ServuxStructuresPacket(Type type, @Nullable CompoundTag nbt)
    {
        this.packetType = type;

        if (nbt != null && nbt.isEmpty() == false)
        {
            this.nbt = new CompoundTag();
            this.nbt.merge(nbt);
        }
        if (this.buffer != null)
        {
            this.buffer.clear();
            this.buffer = new FriendlyByteBuf(Unpooled.buffer());
        }
    }

    public ServuxStructuresPacket(Type type, @Nonnull FriendlyByteBuf packet)
    {
        this.packetType = type;
        this.nbt = new CompoundTag();
        this.buffer = new FriendlyByteBuf(packet.copy());
    }

    @Override public int getVersion() { return PROTOCOL_VERSION; }
    @Override public int getPacketType() { return this.packetType.get(); }

    @Override
    public int getTotalSize()
    {
        int total = 2;

        if (this.nbt != null && this.nbt.isEmpty() == false)
        {
            total += this.nbt.sizeInBytes();
        }
        if (this.buffer != null)
        {
            total += this.buffer.readableBytes();
        }

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

        if (this.packetType.equals(Type.PACKET_S2C_STRUCTURE_DATA))
        {
            // Write Packet Buffer（分包单片 raw bytes）
            try
            {
                output.writeBytes(this.buffer.copy());
            }
            catch (Exception e)
            {
                Reference.logger().warning("ServuxStructuresPacket#toPacket: 写入 buffer 失败: " + e.getMessage());
            }
        }
        else
        {
            // Write NBT
            try
            {
                output.writeNbt(this.nbt);
            }
            catch (Exception e)
            {
                Reference.logger().warning("ServuxStructuresPacket#toPacket: 写入 NBT 失败: " + e.getMessage());
            }
        }
    }

    @Nullable
    public static ServuxStructuresPacket fromPacket(FriendlyByteBuf input)
    {
        int i = input.readVarInt();
        Type type = getType(i);

        if (type == null)
        {
            Reference.logger().warning("ServuxStructuresPacket#fromPacket: 收到无效 packet type");
        }
        else if (type.equals(Type.PACKET_S2C_STRUCTURE_DATA))
        {
            // Read Packet Buffer
            try
            {
                return new ServuxStructuresPacket(type, new FriendlyByteBuf(input.readBytes(input.readableBytes())));
            }
            catch (Exception e)
            {
                Reference.logger().warning("ServuxStructuresPacket#fromPacket: 读取 buffer 失败: " + e.getMessage());
            }
        }
        else
        {
            // Read Nbt
            try
            {
                return new ServuxStructuresPacket(type, input.readNbt());
            }
            catch (Exception e)
            {
                Reference.logger().warning("ServuxStructuresPacket#fromPacket: 读取 NBT 失败: " + e.getMessage());
            }
        }

        return null;
    }

    @Override
    public void clear()
    {
        if (this.nbt != null && this.nbt.isEmpty() == false)
        {
            this.nbt = new CompoundTag();
        }
        if (this.buffer != null && this.buffer.readableBytes() > 0)
        {
            this.buffer.clear();
            this.buffer = new FriendlyByteBuf(Unpooled.buffer());
        }

        this.packetType = null;
    }

    @Nullable
    public static Type getType(int input)
    {
        for (Type type : Type.values())
        {
            if (type.get() == input)
            {
                return type;
            }
        }

        return null;
    }

    public enum Type
    {
        PACKET_S2C_METADATA(1),
        PACKET_S2C_STRUCTURE_DATA(2),
        PACKET_C2S_STRUCTURES_REGISTER(3),
        PACKET_C2S_STRUCTURES_UNREGISTER(4),
        PACKET_S2C_STRUCTURE_DATA_START(5);

        private final int type;

        Type(int type)
        {
            this.type = type;
        }

        int get() { return this.type; }
    }

    /**
     * 协议帧 Payload（保留定义；方案 A 走 byte[] 收发，方案 B 可用此 record 经 NMS 发包）。
     */
    public record Payload(ServuxStructuresPacket data) implements CustomPacketPayload
    {
        public static final CustomPacketPayload.Type<Payload> ID = new CustomPacketPayload.Type<>(ServuxStructuresHandler.CHANNEL_ID);
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
