package verymc.top.veryMcProto.mod.servux.loggers;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;

import verymc.top.veryMcProto.mod.servux.loggers.data.MobCapData;
import verymc.top.veryMcProto.mod.servux.util.MathUtils;

/**
 * MobCap 采集器（mod 层）。移植自原版 {@code DataLoggerMobCaps}。
 *
 * <p><b>硬编码魔数</b>：原版用 AccessWidener 暴露 {@code NaturalSpawner.MAGIC_NUMBER}（private static int = 289，即 17×17）
 * 作生物容量除数。Paper 无 AW，直接硬编码 {@value #MAGIC_NUMBER}（注释标明来源，防版本漂移）。
 *
 * <p>公式精确照抄：{@code capacity = clamp(maxInstancesPerChunk * (spawnableChunks / 289), 0, vanillaCap)}
 * （int 整除，spawnableChunks < 289 时归 0 是原版行为）。
 */
public class DataLoggerMobCaps extends DataLoggerBase<CompoundTag>
{
    public static final Codec<CompoundTag> CODEC = CompoundTag.CODEC;

    /** NaturalSpawner.MAGIC_NUMBER（17×17）；原 AccessWidener 暴露的私有常量。 */
    private static final int MAGIC_NUMBER = 289;

    public DataLoggerMobCaps(DataLogger type)
    {
        super(type);
    }

    @Override
    public CompoundTag getResult(MinecraftServer server)
    {
        CompoundTag nbt = new CompoundTag();

        for (ServerLevel world : server.getAllLevels())
        {
            String dimKey = world.dimension().identifier().toString();
            MobCapData mobCapData = new MobCapData();
            MobCapData.Cap[] data = MobCapData.createCapArray();
            NaturalSpawner.SpawnState info = world.getChunkSource().getLastSpawnState();

            if (info != null)
            {
                int spawnableChunks = info.getSpawnableChunkCount();
                int divisor = MAGIC_NUMBER;
                long worldTime = world.getGameTime();

                if (spawnableChunks <= 0)
                {
                    continue; // 该维度区块未加载
                }

                for (Object2IntMap.Entry<MobCategory> entry : info.getMobCategoryCounts().object2IntEntrySet())
                {
                    MobCapData.EntityCategory category = MobCapData.EntityCategory.fromVanillaCategory(entry.getKey());

                    final int vanillaCap = entry.getKey().getMaxInstancesPerChunk();
                    int current = entry.getIntValue();
                    int capacity = MathUtils.clamp(entry.getKey().getMaxInstancesPerChunk() * (spawnableChunks / divisor), 0, vanillaCap);

                    data[category.ordinal()].setCurrentAndCap(current, capacity);

                    for (MobCapData.EntityCategory type : MobCapData.EntityCategory.values())
                    {
                        MobCapData.Cap cap = data[type.ordinal()];
                        mobCapData.setCurrentAndCapValues(type, cap.getCurrent(), cap.getCap(), worldTime);
                    }
                }

                try
                {
                    CompoundTag nbtEntry = (CompoundTag) MobCapData.CODEC.encodeStart(
                            world.registryAccess().createSerializationContext(NbtOps.INSTANCE), mobCapData).getPartialOrThrow();
                    nbtEntry.putLong("WorldTick", worldTime);
                    nbt.put(dimKey, nbtEntry);
                }
                catch (Exception ignored) { }
            }
        }

        return nbt;
    }
}
