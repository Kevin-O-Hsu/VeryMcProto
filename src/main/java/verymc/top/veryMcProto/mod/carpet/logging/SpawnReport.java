package verymc.top.veryMcProto.mod.carpet.logging;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;

/**
 * mobcaps 采集与短行格式化（上游 {@code carpet/utils/SpawnReporter.java:93,:99-152} 的 P4 切片 +
 * {@code carpet/utils/Messenger.java:30-123} 色表的 § 码化）。
 *
 * <p><b>硬编码魔数</b>：{@value #MAGIC_NUMBER} = 17×17（上游 SpawnReporter.java:93 亦为硬编码
 * {@code (int)Math.pow(17.0, 2.0)}；vanilla 来源 = NaturalSpawner 私有常量）——注释标来源防漂移，
 * 勿引入上游没有的反射（phases §6 交付物④ 裁决；仓库同款先例 {@code mod/servux/loggers/DataLoggerMobCaps.java:29-30}）。
 *
 * <p><b>数据源谱系偏差（登记）</b>：上游 chunkcount 来自其自有事件计数 map
 * {@code chunkCounts}（ServerChunkCacheMixin 区块加载/卸载事件维护，SpawnReporter.java:108/:111）；
 * B 侧按 phases §6 交付物④ 钉死为 {@code SpawnState.getSpawnableChunkCount()} 公开 API（上游 Monitoring.java:61 自用同款、
 * 仓库 DataLoggerMobCaps.java:51 同路径）——两谱系数值可偏差（事件计数 vs 刷怪周期快照）。
 *
 * <p><b>公式逐字</b>（:119）：{@code max = (int)(chunkcount * ((double) maxPerChunk / MAGIC_NUMBER))}
 * ——<b>先除后乘</b>（double 除法后乘 chunkcount），勿改写为先乘后除（末位 ulp 差在 {@code (int)}
 * 截断处可翻转 ±1）；亦勿误搬 servux 先例的 int 整除形态（那是 servux 协议格式，非 carpet HUD 格式）。
 */
public final class SpawnReport
{
    /** NaturalSpawner/carpet SpawnReporter MAGIC_NUMBER（17×17 生物容量除数）。 */
    static final int MAGIC_NUMBER = 289;

    /** 上游 "g   --UNAVAILABLE--"（:111/:147，§7 = g 灰、三空格逐字）。 */
    static final String UNAVAILABLE = "§7   --UNAVAILABLE--";

    private SpawnReport() { }

    /** 维度 mobcaps 单行短格式（上游 printMobcapsForDimension(world, false) 的 String 化）。 */
    public static String printMobcapsForDimension(MinecraftServer server, ResourceKey<Level> dim)
    {
        ServerLevel world = server.getLevel(dim);
        if (world == null)
        {
            return UNAVAILABLE;
        }
        NaturalSpawner.SpawnState lastSpawner = world.getChunkSource().getLastSpawnState();
        if (lastSpawner == null)
        {
            return UNAVAILABLE;
        }
        return formatMobcaps(lastSpawner.getMobCategoryCounts(), lastSpawner.getSpawnableChunkCount());
    }

    /**
     * 纯格式化（单测锁值锚）：逐 {@link MobCategory#values()}（上游 cachedMobCategories 即其缓存副本）
     * 拼短码 {@code 色值 §7/ 色上限 §7,}，末分隔符去除（上游 :115-148）。
     */
    static String formatMobcaps(Object2IntMap<MobCategory> dimCounts, int chunkcount)
    {
        if (dimCounts == null || chunkcount < 0)
        {
            return UNAVAILABLE;
        }
        List<String> tokens = new ArrayList<>();
        for (MobCategory category : MobCategory.values())
        {
            int cur = dimCounts.getOrDefault(category, -1);
            // 上游 :119 逐字：先除后乘
            int max = (int) (chunkcount * ((double) category.getMaxInstancesPerChunk() / MAGIC_NUMBER));
            tokens.add(heatmapColor(cur, max) + " " + (cur < 0 ? "-" : cur));
            tokens.add("§7 /");
            tokens.add(creatureTypeColor(category) + " " + max);
            tokens.add("§7 ,");
        }
        if (tokens.isEmpty())
        {
            return UNAVAILABLE;
        }
        tokens.remove(tokens.size() - 1); // 去末尾 "§7 ,"（上游 :142 同位）
        return String.join("", tokens);
    }

    /**
     * 热力色（上游 Messenger.heatmap_color :103-111 阈值逐字 + 色字母 § 码化：
     * g=§7 / e=§2 / y=§e / r=§c / m=§d）。
     */
    static String heatmapColor(double actual, double reference)
    {
        String color = "§7"; // g
        if (actual >= 0.0D)
        {
            color = "§2"; // e
        }
        if (actual > 0.5D * reference)
        {
            color = "§e"; // y
        }
        if (actual > 0.8D * reference)
        {
            color = "§c"; // r
        }
        if (actual > reference)
        {
            color = "§d"; // m
        }
        return color;
    }

    /**
     * 生物类别色（上游 Messenger.creatureTypeColor :112-123 + 色字母 § 码化：
     * n=§4 / e=§2 / f=§8 / v=§1 / q=§3；MISC 与 UNDERGROUND_WATER_CREATURE 落 w=§f——上游 default 分支）。
     */
    static String creatureTypeColor(MobCategory type)
    {
        return switch (type)
        {
            case MONSTER -> "§4"; // n
            case CREATURE -> "§2"; // e
            case AMBIENT -> "§8"; // f
            case WATER_CREATURE -> "§1"; // v
            case WATER_AMBIENT -> "§3"; // q
            default -> "§f"; // w（MISC / UNDERGROUND_WATER_CREATURE / AXOLOTLS——上游未列，default 白）
        };
    }
}
