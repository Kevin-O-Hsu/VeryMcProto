package verymc.top.veryMcProto.framework.util;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * 字符串 / i18n 工具（框架层）。移植自原版 {@code fi.dy.masa.servux.util.StringUtils}。
 *
 * <p><b>适配</b>：去掉 Fabric {@code getModVersionString}（Paper 版本由 Gradle 注入）；
 * 去掉 {@code ServuxConfigProvider.LANG} 依赖——{@link #translate} 改用 NMS {@link Component#translatable}
 * 兜底（键不存在时显示键本身）。masa 客户端不关心服务端消息语言，故此简化不影响协议。
 */
public final class StringUtils
{
    private StringUtils() { }

    public static String removeDefaultMinecraftNamespace(Identifier id)
    {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    /** 翻译键 → 文本组件（走 NMS i18n，键缺失时显示键）。 */
    public static MutableComponent translate(String translationKey, Object... args)
    {
        return Component.translatable(translationKey, args);
    }

    /** 翻译键 → 纯字符串。 */
    public static String translateAsString(String translationKey, Object... args)
    {
        return Component.translatable(translationKey, args).getString();
    }

    public static CommandSyntaxException translateError(String translationKey, Object... args)
    {
        return new SimpleCommandExceptionType(translate(translationKey, args)).create();
    }
}
