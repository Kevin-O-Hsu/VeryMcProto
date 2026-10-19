package verymc.top.veryMcProto.mod.carpet.logging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import verymc.top.veryMcProto.Reference;
import verymc.top.veryMcProto.framework.util.JsonUtils;

/**
 * /log 订阅跨会话持久层（B 侧新增——上游订阅纯内存、重启即失，判据②要求跨会话持久；上游无对应物，
 * 偏差登记 docs/31-carpet.md）。
 *
 * <p><b>双信号模型</b>（防两类确定性错误——退订项重登复活 / 默认规则变更对存量玩家永久冻结，机理见 docs/31-carpet.md §4.1）：
 * <ul>
 *   <li>{@code subs} = <b>显式</b>订阅（/log 命令产生）——UUID 键 + 名字快照；</li>
 *   <li>{@code off} = <b>退订墓碑</b>（/log clear 产生）——压制 defaultLoggers 对已显式退订者的重施用
 *       （防"退订项重登复活"）；</li>
 *   <li><b>默认订阅永不落盘</b>——每 join 由 {@link LoggerListener} 按当前规则值对「未订阅且无墓碑」者
 *       重评估（防 defaultLoggers 规则变更对存量玩家永久冻结）；</li>
 *   <li>{@code ""} 哨兵 ↔ null option 双向规范化（tps 默认 option = null，而 Gson 默认静默丢弃 Map 的
 *       null 值——不规范化则 tps 缺省订阅跨会话必丢）；</li>
 *   <li>假人豁免在 {@link LoggerListener} 入口（永不触达本层——防 /player 反复 spawn/kill 灌永久死记录）。</li>
 * </ul>
 *
 * <p>文件 = 插件数据目录 {@code carpet-logs.json}（Gson pretty + 原子 tmp/move，JsonUtils 先例）；
 * 坏 JSON / 缺失按空册起步。变更即落盘（崩溃安全的最小持久窗口）。
 */
public final class LogSubscriptionStore
{
    private final Path file;
    private final Map<UUID, PlayerRecord> records = new LinkedHashMap<>();

    /** 单玩家显式状态（显式订阅表 + 退订墓碑表）。 */
    public static final class PlayerRecord
    {
        public String name = "";
        /** logger 名 → option（{@code ""} = null option 哨兵）。 */
        public final Map<String, String> subs = new LinkedHashMap<>();
        /** 显式退订的 logger 名（墓碑）。 */
        public final Set<String> off = new LinkedHashSet<>();
    }

    public LogSubscriptionStore(Path dataFolder)
    {
        this.file = dataFolder.resolve("carpet-logs.json");
    }

    /** 载入（启用时；坏 JSON / 缺失 → 空册起步）。 */
    public void load()
    {
        records.clear();
        JsonElement root = JsonUtils.parseJsonFileAsPath(file);
        if (root == null || !root.isJsonObject())
        {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet())
        {
            UUID id;
            try
            {
                id = UUID.fromString(entry.getKey());
            }
            catch (IllegalArgumentException e)
            {
                Reference.logger().warning("[Carpet] skipping malformed subscription record key: " + entry.getKey());
                continue;
            }
            if (!entry.getValue().isJsonObject())
            {
                continue;
            }
            JsonObject obj = entry.getValue().getAsJsonObject();
            PlayerRecord record = new PlayerRecord();
            if (obj.has("name") && obj.get("name").isJsonPrimitive())
            {
                record.name = obj.get("name").getAsString();
            }
            if (obj.has("subs") && obj.get("subs").isJsonObject())
            {
                for (Map.Entry<String, JsonElement> sub : obj.get("subs").getAsJsonObject().entrySet())
                {
                    if (sub.getValue().isJsonPrimitive())
                    {
                        record.subs.put(sub.getKey(), sub.getValue().getAsString());
                    }
                }
            }
            if (obj.has("off") && obj.get("off").isJsonArray())
            {
                for (JsonElement tombstone : obj.get("off").getAsJsonArray())
                {
                    if (tombstone.isJsonPrimitive())
                    {
                        record.off.add(tombstone.getAsString());
                    }
                }
            }
            records.put(id, record);
        }
    }

    /** 取记录（无则 null）。 */
    public PlayerRecord record(UUID id)
    {
        return records.get(id);
    }

    /** 改名后的名字快照更新（join 时调用；有变化才落盘）。 */
    public void updateName(UUID id, String name)
    {
        PlayerRecord record = records.get(id);
        if (record != null && !name.equals(record.name))
        {
            record.name = name;
            save();
        }
    }

    /** 显式订阅（幂等清同 logger 墓碑——订阅即显式选择，压制旧墓碑防 {subs,off} 矛盾态）。 */
    public void markExplicit(UUID id, String name, String logger, String option)
    {
        PlayerRecord record = records.computeIfAbsent(id, k -> new PlayerRecord());
        record.name = name;
        record.subs.put(logger, option == null ? "" : option);
        record.off.remove(logger);
        save();
    }

    /** 显式退订（写墓碑——压制 defaultLoggers 重施用）。 */
    public void removeExplicit(UUID id, String name, String logger)
    {
        PlayerRecord record = records.computeIfAbsent(id, k -> new PlayerRecord());
        record.name = name;
        record.subs.remove(logger);
        record.off.add(logger);
        save();
    }

    /** 显式全退订（全部注册 logger 写墓碑）。 */
    public void clearAll(UUID id, String name, Collection<String> loggerNames)
    {
        PlayerRecord record = records.computeIfAbsent(id, k -> new PlayerRecord());
        record.name = name;
        record.subs.clear();
        record.off.addAll(loggerNames);
        save();
    }

    /**
     * 落盘（原子 tmp/move；disable 兜底与变更即写共用）。空册且文件不存在时跳过——
     * 「文件从不主动创建」仓库纪律（carpet.conf 先例）：从未有玩家触过 /log 的服不产生本文件
     * （records 只增不删，空册恒 = 处女态；有文件则 load 已灌入非空 records，本分支不可达）。
     */
    public void save()
    {
        if (records.isEmpty() && !Files.exists(file))
        {
            return;
        }
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, PlayerRecord> entry : records.entrySet())
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", entry.getValue().name);
            JsonObject subs = new JsonObject();
            for (Map.Entry<String, String> sub : entry.getValue().subs.entrySet())
            {
                subs.addProperty(sub.getKey(), sub.getValue() == null ? "" : sub.getValue());
            }
            obj.add("subs", subs);
            JsonArray off = new JsonArray();
            for (String tombstone : entry.getValue().off)
            {
                off.add(tombstone);
            }
            obj.add("off", off);
            root.add(entry.getKey().toString(), obj);
        }
        JsonUtils.writeJsonToFileAsPath(root, file);
    }
}
