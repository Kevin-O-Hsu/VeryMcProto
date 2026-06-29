package verymc.top.veryMcProto.framework.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.network.chat.Component;

import verymc.top.veryMcProto.framework.dataproviders.IDataProvider;
import verymc.top.veryMcProto.framework.util.StringUtils;

/**
 * Setting 抽象基类（框架层）。移植自原版 {@code fi.dy.masa.servux.settings.AbstractServuxSetting}。
 *
 * <p>固化元信息（name / prettyName / comment / defaultValue / examples / dataProvider / callback），
 * 提供 setValue / setValueNoCallback / onValueChanged / setValueFromString 通用实现。
 * 子类实现 validateString / valueToString / valueFromString / readFromJson / writeToJson。
 */
public abstract class AbstractServuxSetting<T> implements IServuxSetting<T>
{
    private final String name;
    private final Component prettyName;
    private final Component comment;
    private final T defaultValue;
    private final List<String> examples;
    private final IDataProvider dataProvider;
    private final @Nullable IServuxSettingCallback<T> callback;
    private T value;

    public AbstractServuxSetting(IDataProvider dataProvider, String name, Component prettyName, Component comment,
                                 T defaultValue, List<String> examples, @Nullable IServuxSettingCallback<T> callback)
    {
        Objects.requireNonNull(name);
        this.name = name;
        this.prettyName = prettyName;
        this.comment = comment;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.examples = examples != null ? new ArrayList<>(examples) : new ArrayList<>();
        this.dataProvider = dataProvider;
        this.callback = callback;
    }

    public AbstractServuxSetting(IDataProvider dataProvider, String name, Component prettyName, Component comment,
                                 T defaultValue, IServuxSettingCallback<T> callback)
    {
        this(dataProvider, name, prettyName, comment, defaultValue, null, callback);
    }

    public AbstractServuxSetting(IDataProvider dataProvider, String name, Component prettyName, Component comment, T defaultValue)
    {
        this(dataProvider, name, prettyName, comment, defaultValue, null, null);
    }

    @Override
    public T getDefaultValue()
    {
        return defaultValue;
    }

    @Override
    public T getValue()
    {
        return value;
    }

    /**
     * 直接改值，不触发回调（内部逻辑用，如读配置时）。
     */
    @Override
    public void setValueNoCallback(T value)
    {
        this.value = value;
    }

    @Override
    public void setValue(T value) throws CommandSyntaxException
    {
        T oldValue = this.getValue();
        setValueNoCallback(value);
        onValueChanged(oldValue, value);
    }

    @Override
    public void updateExamples(List<String> examples)
    {
        this.examples.clear();
        this.examples.addAll(examples);
    }

    @Override
    public IDataProvider dataProvider()
    {
        return dataProvider;
    }

    protected void onValueChanged(T oldValue, T value)
    {
        if (this.callback != null)
        {
            this.callback.onValueChanged(this, oldValue, value);
        }
    }

    @Override
    public void setValueFromString(String value) throws CommandSyntaxException
    {
        if (this.validateString(value))
        {
            setValue(this.valueFromString(value));
        }
    }

    @Override
    public String name()
    {
        return name;
    }

    @Override
    public Component prettyName()
    {
        if (prettyName == null)
        {
            return StringUtils.translate("servux.config." + dataProvider.getName() + "." + name + ".name");
        }
        return prettyName;
    }

    @Override
    public Component comment()
    {
        if (comment == null)
        {
            return StringUtils.translate("servux.config." + dataProvider.getName() + "." + name + ".comment");
        }
        return comment;
    }

    @Override
    public List<String> examples()
    {
        if (examples == null)
        {
            return List.of();
        }
        return examples;
    }
}
