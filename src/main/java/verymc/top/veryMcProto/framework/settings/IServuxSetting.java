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
     * 从字符串设值（命令 {@code /servux set} 用）。validateString 不通过时<b>静默不设值</b>——与上游
     * AbstractServuxSetting:101-107 逐字同构的静默闸；受检 throws 子句为上游同名签名照抄遗留（上游
     * 同为静默实现、无抛出点），保留以维持上游契约形态。非法值的显式报错职责在命令层前置校验
     * （对齐上游 ServuxCommand:309-312），勿据此签名期望本方法抛错。
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
