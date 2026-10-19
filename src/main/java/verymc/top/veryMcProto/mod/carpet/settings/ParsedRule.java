package verymc.top.veryMcProto.mod.carpet.settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.bukkit.command.CommandSender;

/**
 * 由静态 {@link Rule} 字段解析出的规则对象（克隆上游 {@code carpet/settings/ParsedRule.java}，1.21.11 树）。
 * 当前值存于源静态字段（volatile——监听器/日志过滤器会在非主线程读取，上游同病的加固，对账登记）；
 * 默认值 = 解析时的字段初值快照。
 *
 * <p>与上游的偏差（对账登记，docs/carpet.md）：删 scarpet（appSource/ScarpetValidator）、
 * client 半边（_CLIENT/canBeToggledClientSide）、Translations 体系（desc/extra 直接来自注解内联英文）、
 * Comparable 与 deprecated 访问器群。
 */
public final class ParsedRule<T>
{
    @FunctionalInterface
    interface FromStringConverter<T>
    {
        T convert(String value) throws InvalidRuleValueException;
    }

    /** 上游 CONVERTER_MAP 同构（String/Boolean/Integer/Double/Long/Float；枚举未用故未移植）。 */
    private static final Map<Class<?>, FromStringConverter<?>> CONVERTER_MAP = Map.ofEntries(
            Map.entry(String.class, str -> str),
            Map.entry(Boolean.class, str -> switch (str)
            {
                case "true" -> true;
                case "false" -> false;
                default -> throw new InvalidRuleValueException("Invalid boolean value");
            }),
            numericalConverter(Integer.class, Integer::parseInt),
            numericalConverter(Double.class, Double::parseDouble),
            numericalConverter(Long.class, Long::parseLong),
            numericalConverter(Float.class, Float::parseFloat)
    );

    public final String name;
    public final Field field;
    public final Class<T> type;
    public final List<String> categories;
    public final List<String> options;
    public final boolean isStrict;
    public final T defaultValue;
    public final String description;
    public final List<String> extraInfo;
    /** 验证器链（含自动挂载件；query 展示各验证器 description 用，上游 public 字段先例）。 */
    public final List<Validator<T>> validators;
    private final FromStringConverter<T> converter;
    private final SettingsManager settingsManager;

    public static <T> ParsedRule<T> of(Field field, SettingsManager settingsManager)
    {
        return new ParsedRule<>(field, field.getAnnotation(Rule.class), settingsManager);
    }

    @SuppressWarnings("unchecked")
    private ParsedRule(Field field, Rule rule, SettingsManager settingsManager)
    {
        this.name = rule.name().isEmpty() ? field.getName() : rule.name();
        this.field = field;
        field.setAccessible(true);
        this.type = (Class<T>) primitiveToWrapper(field.getType());
        this.isStrict = rule.strict();
        this.categories = List.of(rule.category());
        this.description = rule.desc();
        this.extraInfo = List.of(rule.extra());
        this.settingsManager = settingsManager;
        this.validators = new ArrayList<>();
        for (Class<? extends Validator<?>> cls : rule.validate())
        {
            validators.add(instantiateValidator(cls));
        }
        this.defaultValue = value();

        // COMMAND 类目自动挂载（上游 ParsedRule.java:187-194 同构：_COMMAND + String 型加 CommandLevel）
        if (categories.contains("command"))
        {
            validators.add(new Validator._COMMAND<>());
            if (this.type == String.class)
            {
                @SuppressWarnings("unchecked")
                Validator<T> commandLevel = (Validator<T>) new Validator.CommandLevel();
                validators.add(commandLevel);
            }
        }

        // options 推断（上游 :207-234 同构：显式 > boolean > String+COMMAND > 空）
        List<String> inferred;
        if (rule.options().length > 0)
        {
            inferred = List.of(rule.options());
        }
        else if (this.type == Boolean.class)
        {
            inferred = List.of("true", "false");
        }
        else if (this.type == String.class && categories.contains("command"))
        {
            inferred = Validator.CommandLevel.OPTIONS;
        }
        else
        {
            inferred = List.of();
        }
        this.options = inferred;
        if (isStrict && !this.options.isEmpty())
        {
            validators.add(0, new Validator.StrictValidator<>());
        }

        FromStringConverter<T> fromMap = (FromStringConverter<T>) CONVERTER_MAP.get(type);
        if (fromMap == null)
        {
            throw new UnsupportedOperationException("Unsupported type for ParsedRule " + type);
        }
        this.converter = fromMap;
    }

    /** 字符串值 → 类型化值并走验证链写场（上游 set(CommandSourceStack, String) 同构）。 */
    public void set(CommandSender source, String value) throws InvalidRuleValueException
    {
        set(source, converter.convert(value), value);
    }

    /** 类型化值直接写场（removeDefault 复位用；userInput 取值的字符串形态）。 */
    public void set(CommandSender source, T value) throws InvalidRuleValueException
    {
        set(source, value, String.valueOf(value));
    }

    private void set(CommandSender source, T value, String userInput) throws InvalidRuleValueException
    {
        for (Validator<T> validator : validators)
        {
            value = validator.validate(source, this, value, userInput);
            if (value == null)
            {
                if (source != null)
                {
                    validator.notifyFailure(source, this, userInput);
                }
                throw new InvalidRuleValueException();
            }
        }
        // 上游写场条件逐字（ParsedRule.java:299）：命令路径值未变则免写免通知；conf 载入（source==null）恒写
        if (!value.equals(value()) || source == null)
        {
            try
            {
                this.field.set(null, value);
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException("Couldn't access field for rule: " + name, e);
            }
            if (source != null)
            {
                settingsManager.notifyRuleChanged(source, this, userInput);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public T value()
    {
        try
        {
            return (T) field.get(null);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalArgumentException("Couldn't access field for rule: " + name, e);
        }
    }

    public boolean isInDefaultValue()
    {
        return defaultValue.equals(value());
    }

    /** 复位到默认值（上游 RuleHelper.resetToDefault 同构）。 */
    public void resetToDefault(CommandSender source)
    {
        try
        {
            set(source, defaultValue);
        }
        catch (InvalidRuleValueException ignored)
        {
            // 默认值已过验证链（解析期快照），不可达防御
        }
    }

    @Override
    public String toString()
    {
        return this.name + ": " + value();
    }

    @SuppressWarnings("unchecked")
    private static <T> Validator<T> instantiateValidator(Class<? extends Validator<?>> cls)
    {
        try
        {
            Constructor<? extends Validator<?>> constr = cls.getDeclaredConstructor();
            constr.setAccessible(true);
            return (Validator<T>) constr.newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    private static <T> Map.Entry<Class<T>, FromStringConverter<T>> numericalConverter(Class<T> outputClass, Function<String, T> converter)
    {
        return Map.entry(outputClass, str ->
        {
            try
            {
                return converter.apply(str);
            }
            catch (NumberFormatException e)
            {
                throw new InvalidRuleValueException("Invalid number for rule");
            }
        });
    }

    /** 上游 commons-lang ClassUtils.primitiveToWrapper 的本仓内联（支持的规则类型域内）。 */
    private static Class<?> primitiveToWrapper(Class<?> c)
    {
        if (!c.isPrimitive())
        {
            return c;
        }
        if (c == boolean.class)
        {
            return Boolean.class;
        }
        if (c == int.class)
        {
            return Integer.class;
        }
        if (c == long.class)
        {
            return Long.class;
        }
        if (c == double.class)
        {
            return Double.class;
        }
        if (c == float.class)
        {
            return Float.class;
        }
        throw new UnsupportedOperationException("Unsupported primitive rule type: " + c);
    }
}
