package verymc.top.veryMcProto.framework.settings;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonSyntaxException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServuxStringListSetting.validateString 行为锁（ServuxListSetting 静默闸的输入判定面，纯函数）。
 *
 * <p>三态：合法字符串数组通过 / 元素类型不符返回 false / 畸形输入抛非受检异常。畸形输入实测两形态
 * （gson ≥2.10.1 同行为）：Gson 的 {@link JsonSyntaxException}（畸形 JSON、顶层非数组字面量）与
 * NPE（Gson 对空文档返回 null 后 for-each）。
 *
 * <p>本测试同时锚定 {@code /servux set} 命令层前置校验的 <b>catch 宽度</b>：命令层
 * {@code catch (RuntimeException)} 全宽收口承重——收窄为 {@code JsonSyntaxException} 会使空串
 * 输入重新外溢为 Command execution error（半修态），勿改（见 ServuxCommand.isValidValue）。
 */
class ServuxStringListSettingValidateTest
{
    /** dataProvider 可为 null：validateString 全链不触碰它（AbstractServuxSetting 构造仅 requireNonNull(name)）。 */
    private static ServuxStringListSetting newSetting()
    {
        return new ServuxStringListSetting(null, "test_list", List.of());
    }

    @Test
    void validStringArrayPasses()
    {
        assertTrue(newSetting().validateString("[\"a\",\"b\"]"));
    }

    @Test
    void emptyArrayPasses()
    {
        assertTrue(newSetting().validateString("[]"));
    }

    @Test
    void nonStringElementsFail()
    {
        assertFalse(newSetting().validateString("[1,2]"));
    }

    @Test
    void malformedJsonThrowsJsonSyntaxException()
    {
        assertThrows(JsonSyntaxException.class, () -> newSetting().validateString("["));
    }

    @Test
    void nullLiteralThrowsJsonSyntaxException()
    {
        // Gson 的 JsonArray 目标对顶层 null 字面量自抛 JsonSyntaxException（非 ClassCastException）
        assertThrows(JsonSyntaxException.class, () -> newSetting().validateString("null"));
    }

    @Test
    void emptyInputThrowsNullPointerException()
    {
        // Gson 对空文档返回 null，validateString 的 for-each 随即 NPE——命令层 catch 宽度需覆盖此形态
        assertThrows(NullPointerException.class, () -> newSetting().validateString(""));
    }

    @Test
    void settingUsableWithoutProvider()
    {
        // 构造可行性锚：dataProvider=null 下 valueToString/valueFromString 亦不触碰 provider（命令层回显路径同依赖）
        ServuxStringListSetting setting = newSetting();
        setting.setValueNoCallback(List.of("a"));
        assertNotNull(setting.valueToString(setting.getValue()));
        assertTrue(setting.valueFromString("[\"x\"]").contains("x"));
    }
}
