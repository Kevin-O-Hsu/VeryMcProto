package verymc.top.veryMcProto.mod.carpet.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 规则注解（克隆上游 {@code carpet/settings/Rule.java}，26.1 树——上游 CarpetSettings 的 8 条地基规则
 * 实际使用的即这套 legacy 注解）。标注于静态字段即成为 carpet 规则。
 *
 * <p>字段必须为 static，类型限 boolean/int/double/long/float/String/枚举；规则默认值 = 字段初值。
 *
 * <p>与上游的偏差（对账登记，docs/carpet/architecture.md）：删 {@code appSource}（Scarpet 整体不可移植，
 * scope §7）与 {@code condition}（8 条地基规则零使用）。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Rule
{
    /** 规则名，默认同字段名。 */
    String name() default "";

    /** 规则描述（英文——命令 query 展示与规则浏览器数据源）。 */
    String desc();

    /** 附加说明行。 */
    String[] extra() default {};

    /** 类目标签（小写字符串，如 "command"/"creative"/"tnt"——上游 RuleCategory 常量值域）。 */
    String[] category();

    /** 命令补全/菜单选项。boolean 与枚举自动推断，其余类型须显式给出（或为空 = 无建议）。 */
    String[] options() default {};

    /** true = 值必须命中 options；false = 任意值。 */
    boolean strict() default true;

    /** 值变更前置验证器（返回 null 拒绝）。 */
    Class<? extends Validator<?>>[] validate() default {};
}
