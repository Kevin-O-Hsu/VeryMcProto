package verymc.top.veryMcProto.mod.servux.loggers.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.PrimitiveCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * TPS 数据记录（mod 层）。照抄原版 {@code fi.dy.masa.servux.loggers.data.TPSData}。
 *
 * <p>NBT 字段：mspt / tps / sprintTicks / frozen / sprinting / stepping。
 */
public record TPSData(double mspt, double tps, long sprintTicks, boolean frozen, boolean sprinting, boolean stepping)
{
    public static Codec<TPSData> CODEC = RecordCodecBuilder.create(
            (inst) -> inst.group(
                    PrimitiveCodec.DOUBLE.fieldOf("mspt").forGetter(TPSData::mspt),
                    PrimitiveCodec.DOUBLE.fieldOf("tps").forGetter(TPSData::tps),
                    PrimitiveCodec.LONG.fieldOf("sprintTicks").forGetter(TPSData::sprintTicks),
                    PrimitiveCodec.BOOL.fieldOf("frozen").forGetter(TPSData::frozen),
                    PrimitiveCodec.BOOL.fieldOf("sprinting").forGetter(TPSData::sprinting),
                    PrimitiveCodec.BOOL.fieldOf("stepping").forGetter(TPSData::stepping)
            ).apply(inst, TPSData::new));
}
