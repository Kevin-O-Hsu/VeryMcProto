package verymc.top.veryMcProto.mod.carpet;

import org.bukkit.command.CommandSender;

import verymc.top.veryMcProto.mod.carpet.app.CarpetModule;
import verymc.top.veryMcProto.mod.carpet.settings.ParsedRule;
import verymc.top.veryMcProto.mod.carpet.settings.Rule;
import verymc.top.veryMcProto.mod.carpet.settings.Validator;

/**
 * carpet 规则声明（克隆上游 {@code carpet/CarpetSettings.java} 26.2 树的 8 条地基规则——注解与默认值
 * 逐字；对照行号见各规则注释）。规则值由静态字段承载（volatile：监听器/日志过滤器跨线程读取）。
 *
 * <p>与上游的偏差（对账登记，docs/carpet-architecture.md）：
 * <ul>
 *   <li>{@code language} 的 LanguageValidator 删 Translations 副作用（B 侧无 i18n，仓库铁律对外英文；
 *       strict + options 已足约束值域）；</li>
 *   <li>{@code superSecretSetting} / {@code language} 为纯壳规则（上游行为面 = 客户端 i18n 切换 /
 *       Mixin 强制命令堆栈 debug 日志，均不随 P1 移植）；{@code commandTick} 为上游三线零使用死规则
 *       （注册仅为规则面完整，上游 /tick 即原版命令，Paper 天然同构）；</li>
 *   <li>{@code cleanLogs} 的 desc/extra 为上游逐字——其声称的 "Maximum sound pool size" 行与上游唯一
 *       机制（LivingEntity_cleanLogsMixin 命名死亡日志门控）脱节，系上游 desc 陈旧；B 侧按 mixin 代数
 *       实现（docs/carpet-architecture.md 勘误记档）。</li>
 * </ul>
 */
@SuppressWarnings("CanBeFinal")
public class CarpetSettings
{
    private CarpetSettings() { }

    // ───── 上游 :77-84（language；LanguageValidator 偏差见类 javadoc）─────
    @Rule(
            desc = "Sets the language for Carpet",
            category = "feature",
            options = {"en_us", "fr_fr", "es_ar", "pt_br", "zh_cn", "zh_tw"},
            strict = true
    )
    public static volatile String language = "en_us";

    // ───── 上游 :134-153（carpetCommandPermissionLevel + CarpetPermissionLevel）─────
    private static class CarpetPermissionLevel extends Validator<String>
    {
        @Override
        public String validate(CommandSender source, ParsedRule<String> currentRule, String newValue, String userInput)
        {
            // 上游语义（:134-146）：conf 载入（source==null）自由；命令路径需 owner 级（op 4）
            if (source == null || verymc.top.veryMcProto.mod.carpet.util.CommandHelper.hasOwnerPermission(source))
            {
                return newValue;
            }
            return null;
        }

        @Override
        public String description()
        {
            return "This setting can only be set by admins with op level 4";
        }
    }

    @Rule(
            desc = "Carpet command permission level. Can only be set via .conf file",
            category = "creative",
            validate = CarpetPermissionLevel.class,
            options = {"ops", "2", "4"}
    )
    public static volatile String carpetCommandPermissionLevel = "ops";

    // ───── 上游 :156-157（superSecretSetting，纯壳）─────
    @Rule(desc = "Gbhs sgnf sadsgras fhskdpri!!!", category = "experimental")
    public static volatile boolean superSecretSetting = false;

    // ───── 上游 :222-223（explosionNoBlockDamage → CarpetRuleListener 双爆炸事件）─────
    @Rule(desc = "Explosions won't destroy blocks", category = {"creative", "tnt"})
    public static volatile boolean explosionNoBlockDamage = false;

    // ───── 上游 :414-415（commandTick，COMMAND 类目 → 自动挂 _COMMAND + CommandLevel；死规则零行为）─────
    @Rule(desc = "Enables /tick command to control game clocks", category = "command")
    public static volatile String commandTick = "ops";

    // ───── 上游 :424-450（perfPermissionLevel + PermissionValidator；行为面真因果见 CarpetModule.applyPerfPermission）─────
    public static class PermissionValidator extends Validator<Integer>
    {
        @Override
        public Integer validate(CommandSender source, ParsedRule<Integer> currentRule, Integer newValue, String userInput)
        {
            // 上游副作用位（写 perfPermissionCheck 静态字段供 PerfCommand mixin 消费）的 B 侧等价：
            // 只读登记 + 真因果日志（Paper 无 Mixin 无 /perf requires 替换面）
            CarpetModule.applyPerfPermission(newValue);
            return newValue;
        }

        @Override
        public String description()
        {
            return "You must have the permission level you are trying to set";
        }
    }

    @Rule(
            desc = "Required permission level for /perf command",
            options = {"2", "4"},
            category = "creative",
            validate = PermissionValidator.class
    )
    public static volatile int perfPermissionLevel = 4;

    // ───── 上游 :705-712（customMOTD → CarpetRuleListener ServerListPingEvent）─────
    @Rule(
            desc = "Sets a different motd message on client trying to connect to the server",
            extra = "use '_' to use the startup setting from server.properties",
            options = "_",
            strict = false,
            category = "creative"
    )
    public static volatile String customMOTD = "_";

    // ───── 上游 :897-905（cleanLogs → CleanLogsFilter；desc 脱节勘误见类 javadoc）─────
    @Rule(
            desc = "Removes obnoxious messages from the logs",
            extra = {
                    "Doesn't display 'Maximum sound pool size 247 reached'",
                    "Which is normal with decent farms and contraptions"
            },
            category = {"survival", "client"}
    )
    public static volatile boolean cleanLogs = false;
}
