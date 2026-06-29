package verymc.top.veryMcProto.framework.settings;

import com.google.gson.JsonElement;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import verymc.top.veryMcProto.framework.dataproviders.IDataProvider;

import java.util.List;

/**
 * Setting 契约（框架层）。移植自原版 {@code fi.dy.masa.servux.settings.IServuxSetting}。
 * 纯接口 + NMS {@link Component}（paperweight 直连），照抄。
 */
public interface IServuxSetting<T>
{
    String name();

    Component prettyName();

    Component comment();

    List<String> examples();

    IDataProvider dataProvider();

    T getDefaultValue();

    T getValue();

    void setValueNoCallback(T value);

    void setValue(T value) throws CommandSyntaxException;

    void updateExamples(List<String> examples);

    /**
     * 从字符串设值（命令 {@code /servux set} 用）。
     *
     * @throws CommandSyntaxException 非法值
     */
    void setValueFromString(String value) throws CommandSyntaxException;

    boolean validateString(String value);

    String valueToString(Object value);

    T valueFromString(String value);

    void readFromJson(JsonElement element);

    JsonElement writeToJson();

    default Component shortDisplayName()
    {
        return prettyName().copy().withStyle(style ->
                style.withHoverEvent(new HoverEvent.ShowText(comment().copy()
                                .append(Component.literal("\n(%s)".formatted(qualifiedName()))
                                        .withStyle(ChatFormatting.DARK_GRAY))))
                        .withColor(ChatFormatting.YELLOW)
        );
    }

    default String qualifiedName()
    {
        return dataProvider().getName() + ":" + name();
    }
}
