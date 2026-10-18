package verymc.top.veryMcProto.mod.carpet.settings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.command.CommandSender;

import java.util.function.BiConsumer;

import verymc.top.veryMcProto.Reference;

/**
 * carpet 规则管理器（克隆上游 {@code carpet/api/settings/SettingsManager.java}，26.2 树核心面）：
 * 反射解析 @Rule 字段 → {@code carpet.conf} 纯文本持久化（逐行 {@code key value}、locked 行、
 * 未知键 error 跳过、值含空格 split(2) 保留）→ setDefault/removeDefault 命令语义
 * （live set → conf read-modify-write 落盘 → 即时生效）。
 *
 * <p>与上游的偏差（对账登记，docs/carpet-architecture.md）：
 * <ul>
 *   <li>conf 位置 = 构造注入的插件数据目录（上游 = 世界根目录 LevelResource.ROOT）；构造注入目录
 *       兼使纯 JVM 单测可用（Reference.plugin() 在测试环境为 null）；</li>
 *   <li>写盘加原子 tmp/move（仓库配置落盘纪律，上游为直写）；</li>
 *   <li>命令面解耦到 Bukkit {@code CarpetCommand}（上游内嵌 brigadier 命令树，B 侧无该注册面）；</li>
 *   <li>删 Translations/scarpet/网络推送的<b>内嵌实现</b>——{@link #notifyRuleChanged} 经注入的
 *       回调（{@link #setRuleChangedCallback}）转发给 Phase 3 的 carpet:hello 规则推送
 *       （docs/carpet-protocol.md）；回调经 setter 注入以保持本包零 network/ 依赖（纯 JVM 单测
 *       可独立构造本类，P1 测试形态不被破坏）。</li>
 * </ul>
 */
public class SettingsManager
{
    private final Map<String, ParsedRule<?>> rules = new HashMap<>();
    private final String version;
    private final String identifier;
    private final String fancyName;
    private final Path configDir;
    private boolean locked;
    /** 规则变更回调（Phase 3 carpet:hello 推送挂载；null = 无网络推送——单测/P1 形态）。 */
    private volatile BiConsumer<CommandSender, ParsedRule<?>> ruleChangedCallback;

    record ConfigReadResult(Map<String, String> ruleMap, boolean locked) { }

    public SettingsManager(String version, String identifier, String fancyName, Path configDir)
    {
        this.version = version;
        this.identifier = identifier;
        this.fancyName = fancyName;
        this.configDir = configDir;
    }

    public boolean locked()
    {
        return locked;
    }

    public String identifier()
    {
        return identifier;
    }

    public String version()
    {
        return version;
    }

    public String fancyName()
    {
        return fancyName;
    }

    /** 反射扫描 @Rule 静态字段注册（上游 parseSettingsClass :175-215 同构；B 侧无 conditions 面）。 */
    public void parseSettingsClass(Class<?> settingsClass)
    {
        for (Field field : settingsClass.getDeclaredFields())
        {
            Rule annotation = field.getAnnotation(Rule.class);
            if (annotation == null)
            {
                continue;
            }
            ParsedRule<?> parsed = ParsedRule.of(field, this);
            rules.put(parsed.name, parsed);
        }
    }

    public ParsedRule<?> getRule(String name)
    {
        return rules.get(name);
    }

    /** 注册表视图（判据③：/carpet list 输出与本视图一致）。 */
    public Collection<ParsedRule<?>> getRules()
    {
        return Collections.unmodifiableCollection(rules.values());
    }

    /** 按名排序视图（上游 getRulesSorted :347-350 同构；list 与 tab 补全共用）。 */
    public Collection<ParsedRule<?>> getRulesSorted()
    {
        return rules.values().stream().sorted(Comparator.comparing(r -> r.name)).toList();
    }

    /** conf 文件（上游 getFile :342-345 = 世界根 identifier+".conf"；B 侧 = 插件数据目录，见类 javadoc）。 */
    private Path getFile()
    {
        return configDir.resolve(identifier + ".conf");
    }

    private ConfigReadResult readSettingsFromConf(Path path)
    {
        try (BufferedReader reader = Files.newBufferedReader(path))
        {
            String line;
            boolean confLocked = false;
            Map<String, String> result = new HashMap<>();
            while ((line = reader.readLine()) != null)
            {
                line = line.replaceAll("[\\r\\n]", "");
                if ("locked".equalsIgnoreCase(line))
                {
                    confLocked = true;
                }
                String[] fields = line.split("\\s+", 2);
                if (fields.length > 1)
                {
                    // 上游注释怪癖逐字保留（api/settings/SettingsManager.java:449 的 A&&B||C 优先级——
                    // 附带效果：值首字符为 '#' 的条目重读时被跳过，上游继承缺陷，勿"修复"）
                    if (result.isEmpty() && fields[0].startsWith("#") || fields[1].startsWith("#"))
                    {
                        continue;
                    }
                    if (!rules.containsKey(fields[0]))
                    {
                        Reference.logger().warning("[Carpet] " + fancyName + " setting '" + fields[0] + "' is not a valid rule - ignoring...");
                        continue;
                    }
                    result.put(fields[0], fields[1]);
                }
            }
            return new ConfigReadResult(result, confLocked);
        }
        catch (NoSuchFileException e)
        {
            // 上游服务端语义：conf 缺失 = 空结果——从不主动创建，首次写盘仅由 setDefault/removeDefault 触发
            return new ConfigReadResult(new HashMap<>(), false);
        }
        catch (IOException e)
        {
            Reference.logger().log(Level.WARNING, "[Carpet] Exception while loading rules from " + path, e);
            return new ConfigReadResult(new HashMap<>(), false);
        }
    }

    /** conf 写盘（上游 writeSettingsToConf :373-389 同构；偏差 = 原子 tmp/move，见类 javadoc）。 */
    private void writeSettingsToConf(ConfigReadResult data)
    {
        if (locked)
        {
            return;
        }
        Path file = getFile();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try
        {
            try (BufferedWriter fw = Files.newBufferedWriter(tmp))
            {
                for (String key : data.ruleMap().keySet())
                {
                    fw.write(key + " " + data.ruleMap().get(key));
                    fw.newLine();
                }
            }
            try
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException atomicEx)
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e)
        {
            Reference.logger().log(Level.WARNING, "[Carpet] failed to write " + file.getFileName() + " config file", e);
        }
    }

    /**
     * conf 载入（上游 loadConfigurationFromConf :403-429 同构）：复位默认 → locked 处置
     * （禁用 COMMAND 类可关规则）→ 逐条应用（source=null：恒写场、不通知）。
     */
    public void loadConfigurationFromConf()
    {
        for (ParsedRule<?> rule : rules.values())
        {
            rule.resetToDefault(null);
        }
        ConfigReadResult conf = readSettingsFromConf(getFile());
        locked = false;
        if (conf.locked())
        {
            Reference.logger().info("[Carpet] " + fancyName + " features are locked by the administrator");
            disableBooleanCommands();
        }
        int loadedCount = 0;
        for (String key : conf.ruleMap().keySet())
        {
            try
            {
                rules.get(key).set(null, conf.ruleMap().get(key));
                loadedCount++;
            }
            catch (InvalidRuleValueException exc)
            {
                Reference.logger().warning("[Carpet] Failed to load setting: " + key + (exc.getMessage() != null ? " (" + exc.getMessage() + ")" : ""));
            }
        }
        if (loadedCount > 0)
        {
            Reference.logger().info("[Carpet] Loaded " + loadedCount + " settings from " + identifier + ".conf");
        }
        locked = conf.locked();
    }

    /**
     * locked 置位时禁用 COMMAND 类可关规则（上游 disableBooleanCommands :356-371 同构——
     * 建议值含 "false" 的命令规则一律置 false；B 侧 commandTick 即此面）。
     */
    private void disableBooleanCommands()
    {
        for (ParsedRule<?> rule : rules.values())
        {
            if (!rule.categories.contains("command"))
            {
                continue;
            }
            try
            {
                if (rule.options.contains("false"))
                {
                    rule.set(null, "false");
                }
                else
                {
                    Reference.logger().warning("[Carpet] Couldn't disable command rule " + rule.name + ": it doesn't suggest false as a valid option");
                }
            }
            catch (InvalidRuleValueException e)
            {
                throw new IllegalStateException(e); // options 契约下不可达（上游同判）
            }
        }
    }

    /**
     * 存不同默认值到 conf（上游 setDefault :694-709 同构）：live set（即时生效）→ conf read-modify-write。
     * conf 存<b>空格规范化输入串</b>（Bukkit args 单空格重组，上游 brigadier greedyString 的保真边界，
     * 读写同串故往返自洽）。
     *
     * @return 是否成功（locked / 未知规则 / 验证失败 = false）
     */
    public boolean setDefault(CommandSender source, ParsedRule<?> rule, String stringValue)
    {
        if (locked)
        {
            return false;
        }
        if (!rules.containsKey(rule.name))
        {
            return false;
        }
        try
        {
            rule.set(source, stringValue);
        }
        catch (InvalidRuleValueException e)
        {
            e.notifySource(rule.name, source);
            return false;
        }
        ConfigReadResult conf = readSettingsFromConf(getFile());
        conf.ruleMap().put(rule.name, stringValue);
        writeSettingsToConf(conf);
        return true;
    }

    /** 移除 conf 覆盖并复位默认（上游 removeDefault :711-721 同构：conf remove → 写盘 → reset）。 */
    public boolean removeDefault(CommandSender source, ParsedRule<?> rule)
    {
        if (locked)
        {
            return false;
        }
        if (!rules.containsKey(rule.name))
        {
            return false;
        }
        ConfigReadResult conf = readSettingsFromConf(getFile());
        conf.ruleMap().remove(rule.name);
        writeSettingsToConf(conf);
        rules.get(rule.name).resetToDefault(source);
        return true;
    }

    /**
     * 规则变更通知（上游 :261-267 的 Paper 形态——上游此处推 observers/网络客户端/scarpet 三支，
     * B 侧仅网络推送一支，经注入回调转发给 carpet:hello 的规则推送）。
     * 仅命令路径（source != null）经 ParsedRule.set 触发；conf 载入（source==null）不通知——
     * 上游同构，载入后的首帧由 hello 应答的全量 Rules 承载。
     */
    public void notifyRuleChanged(CommandSender source, ParsedRule<?> rule, String userInput)
    {
        BiConsumer<CommandSender, ParsedRule<?>> callback = ruleChangedCallback;
        if (callback != null)
        {
            callback.accept(source, rule);
        }
    }

    /** 注入规则变更回调（Phase 3 装配期由 CarpetModule 挂 CarpetHelloHandler::onRuleChanged）。 */
    public void setRuleChangedCallback(BiConsumer<CommandSender, ParsedRule<?>> callback)
    {
        this.ruleChangedCallback = callback;
    }
}
