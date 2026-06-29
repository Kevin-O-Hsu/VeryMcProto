package verymc.top.veryMcProto.mod.servux.loggers;

import java.util.List;
import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Logger 类型枚举（mod 层）。照抄原版 {@code DataLogger}（去 Guava ImmutableList）。
 */
public enum DataLogger implements StringRepresentable
{
    TPS("tps", DataLoggerType.TPS, DataLoggerTPS.CODEC),
    MOB_CAPS("mob_caps", DataLoggerType.MOB_CAPS, DataLoggerMobCaps.CODEC);

    public static final EnumCodec<DataLogger> CODEC = StringRepresentable.fromEnum(DataLogger::values);
    public static final List<DataLogger> VALUES = java.util.Arrays.asList(values());

    private final String name;
    private final DataLoggerType<?> type;
    private final Codec<?> codec;

    DataLogger(String name, DataLoggerType<?> type, Codec<?> codec)
    {
        this.name = name;
        this.type = type;
        this.codec = codec;
    }

    @Override
    public String getSerializedName()
    {
        return this.name;
    }

    @Nullable
    public DataLoggerBase<?> init()
    {
        return this.type.init(this);
    }

    public Codec<?> codec()
    {
        return this.codec;
    }

    @Nullable
    public static DataLogger fromStringStatic(String name)
    {
        for (DataLogger type : VALUES)
        {
            if (type.name.equalsIgnoreCase(name))
            {
                return type;
            }
        }
        return null;
    }
}
