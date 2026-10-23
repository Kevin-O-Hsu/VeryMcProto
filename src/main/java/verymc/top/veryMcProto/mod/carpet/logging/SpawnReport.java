package verymc.top.veryMcProto.mod.carpet.logging;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.entity.EntityTypeTest;
import verymc.top.veryMcProto.mod.carpet.util.Messenger;

/**
 * mobcaps 采集与格式化（上游 {@code carpet/utils/SpawnReporter.java} 的 B 侧承载切片：
 * /log = :93,:99-152 短行 HUD 面 + {@code Messenger.java:30-123} 色表的 § 码化；命令族追加 =
 * :99-152 多行组件面与 :234-258 实体清单，供 /spawn mobcaps,entities——见文件尾部命令族节，
 * /log 短行 § 码面与其单测零触碰）。
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

    // ─────（/spawn mobcaps,entities）命令侧扩展——append-only，/log 短行面零触碰 ─────

    /** 上游 cachedMobCategories()（SpawnReporter.java:295-297 = MobCategory.values() 缓存副本）同构。 */
    public static MobCategory[] cachedMobCategories()
    {
        return MobCategory.values();
    }

    /**
     * 维度 mobcaps 组件报告（上游 printMobcapsForDimension(world, multiline) :99-152 的组件面——
     * 表头 + 逐类整行 / 短码单行，/spawn mobcaps 与 entities 裸分支消费）。
     *
     * <p>rounds 段：上游读 spawn_tries 追踪 map（tracking 面未移植）→ rounds 恒 1 → {@code "w "}
     * 空段（上游 :124-127 rounds==1 同位行为）。色走 {@link Messenger} 字母码（本类 § 码私有函数
     * 为 HUD footer String 传输面，双体系并存——传输载体差异，非冗余）。
     */
    public static List<Component> printMobcapsForDimension(ServerLevel world, boolean multiline)
    {
        ResourceKey<Level> dim = world.dimension();
        String name = dim.identifier().getPath();
        List<Component> lst = new ArrayList<>();
        if (multiline)
            lst.add(Messenger.s(String.format("Mobcaps for %s:",name)));
        NaturalSpawner.SpawnState lastSpawner = world.getChunkSource().getLastSpawnState();
        // B 侧谱系（偏差 #52）：上游 chunkcount 来自 chunkCounts 事件计数 map（Mixin 维护）；此处走
        // SpawnState 公开 API——两谱系数值可偏差，公式仍逐字
        Object2IntMap<MobCategory> dimCounts = lastSpawner == null ? null : lastSpawner.getMobCategoryCounts();
        int chunkcount = lastSpawner == null ? -1 : lastSpawner.getSpawnableChunkCount();
        if (dimCounts == null || chunkcount < 0)
        {
            lst.add(Messenger.c("g   --UNAVAILABLE--"));
            return lst;
        }

        List<String> shortCodes = new ArrayList<>();
        for (MobCategory category : cachedMobCategories())
        {
            int cur = dimCounts.getOrDefault(category, -1);
            // 上游 :119 逐字：先除后乘
            int max = (int)(chunkcount * ((double)category.getMaxInstancesPerChunk() / MAGIC_NUMBER)); // from ServerChunkManager.CHUNKS_ELIGIBLE_FOR_SPAWNING
            String color = Messenger.heatmap_color(cur, max);
            String mobColor = Messenger.creatureTypeColor(category);
            if (multiline)
            {
                // rounds==1 恒空段（spawn_tries 追踪面未移植，见 javadoc；上游 :124-128 同位三元）
                lst.add(Messenger.c(String.format("w   %s: ", category.getName()),
                        (cur < 0) ? "g -" : (color + " " + cur), "g  / ", mobColor + " " + max,
                        "w "
                ));
            }
            else
            {
                shortCodes.add(color+" "+((cur<0)?"-":cur));
                shortCodes.add("g /");
                shortCodes.add(mobColor+" "+max);
                shortCodes.add("g ,");
            }
        }
        if (!multiline)
        {
            if (shortCodes.size() > 0)
            {
                shortCodes.remove(shortCodes.size() - 1);
                lst.add(Messenger.c(shortCodes.toArray(new Object[0])));
            }
            else
            {
                lst.add(Messenger.c("g   --UNAVAILABLE--"));
            }

        }
        return lst;
    }

    /**
     * 类别实体清单（上游 printEntitiesByType :234-258 逐字——persistent 判定 =
     * {@code isPersistenceRequired() || requiresCustomPersistence()}，默认排除、all 含）。
     */
    public static List<Component> printEntitiesByType(MobCategory cat, ServerLevel worldIn, boolean all)
    {
        List<Component> lst = new ArrayList<>();
        lst.add(Messenger.s(String.format("Loaded entities for %s category:", cat)));
        for (Entity entity : worldIn.getEntities(EntityTypeTest.forClass(Entity.class), (e) -> e.getType().getCategory() == cat))
        {
            boolean persistent = entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence());
            if (!all && persistent)
                continue;

            EntityType<?> type = entity.getType();
            net.minecraft.core.BlockPos pos = entity.blockPosition();
            lst.add(Messenger.c(
                    "w  - ",
                    Messenger.tp(persistent ? "gb" : "wb", pos),
                    String.format(persistent ? "g : %s" : "w : %s", type.getDescription().getString())
            ));

        }
        if (lst.size() == 1)
        {
            lst.add(Messenger.s(" - Empty."));
        }
        return lst;
    }
}
