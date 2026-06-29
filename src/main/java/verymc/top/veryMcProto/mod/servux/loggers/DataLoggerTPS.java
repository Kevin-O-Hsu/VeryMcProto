package verymc.top.veryMcProto.mod.servux.loggers;

import java.util.concurrent.TimeUnit;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;

import verymc.top.veryMcProto.framework.reflect.Reflect;
import verymc.top.veryMcProto.mod.servux.loggers.data.TPSData;

/**
 * TPS 采集器（mod 层）。移植自原版 {@code DataLoggerTPS}。
 *
 * <p><b>反射点</b>：原版用 Mixin {@code IMixinServerTickManager.servux_getStringTicks()}
 * （{@code @Accessor("remainingSprintTicks")}）读 {@link ServerTickRateManager} 的私有 {@code long remainingSprintTicks}。
 * Paper 无 Mixin，改用 {@link Reflect#getOr} 反射（字段名 Mojang，reobf 不转换，命中正确）。
 *
 * <p>其余 NMS（{@code tickRateManager / getAverageTickTimeNanos / isFrozen / isSprinting / millisecondsPerTick /
 * isSteppingForward}）均为公开方法，paperweight 直连。
 */
public class DataLoggerTPS extends DataLoggerBase<CompoundTag>
{
    public static final Codec<CompoundTag> CODEC = CompoundTag.CODEC;

    public DataLoggerTPS(DataLogger type)
    {
        super(type);
    }

    @Override
    public CompoundTag getResult(MinecraftServer server)
    {
        try
        {
            return (CompoundTag) TPSData.CODEC.encodeStart(
                    server.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                    this.build(server)).getOrThrow();
        }
        catch (Exception e)
        {
            return new CompoundTag();
        }
    }

    private TPSData build(MinecraftServer server)
    {
        ServerTickRateManager tickManager = server.tickRateManager();
        boolean frozen = tickManager.isFrozen();
        boolean sprinting = tickManager.isSprinting();
        final double mspt = (double) server.getAverageTickTimeNanos() / TimeUnit.MILLISECONDS.toNanos(1L);
        double tps = 1000.0D / Math.max(sprinting ? 0.0 : tickManager.millisecondsPerTick(), mspt);

        if (frozen)
        {
            tps = 0.0d;
        }

        // 反射读私有字段 remainingSprintTicks（防御：版本漂移返回 0）
        long sprintTicks = Reflect.getOr(tickManager, "remainingSprintTicks", 0L);

        return new TPSData(mspt, tps, sprintTicks, frozen, sprinting, tickManager.isSteppingForward());
    }
}
