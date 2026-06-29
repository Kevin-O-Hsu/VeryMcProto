package verymc.top.veryMcProto.mod.servux.loggers;

import net.minecraft.server.MinecraftServer;

/**
 * Logger 抽象基类（mod 层）。照抄原版 {@code fi.dy.masa.servux.loggers.DataLoggerBase}。
 */
public abstract class DataLoggerBase<T>
{
    private final DataLogger type;

    public DataLoggerBase(DataLogger type)
    {
        this.type = type;
    }

    public DataLogger getType()
    {
        return this.type;
    }

    public abstract T getResult(MinecraftServer server);
}
