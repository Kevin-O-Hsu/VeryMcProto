package verymc.top.veryMcProto.mod.servux.command;

import java.util.List;

import com.google.gson.JsonElement;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import verymc.top.veryMcProto.framework.dataproviders.IDataProvider;
import verymc.top.veryMcProto.framework.settings.IServuxSetting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ServuxCommand} 三臂匹配 {@code matchesSearch} 纯函数单测（上游 ServuxCommand:119-131 语义固化）：
 * name / comment / provider 名任一 contains 即该词命中，全部词命中才保留（AND），大小写敏感（上游无归一）。
 *
 * <p>最小 setting 桩（仅 name/comment/provider 三臂参与匹配，其余方法空实现）；comment 臂按 Paper 侧
 * 实际形态给翻译键字符串（settings 构造不传 comment 时的退化形态——见 matchesSearch javadoc 平台差异声明）。
 */
class ServuxSearchTest
{
    /** 三臂可控的最小 setting 桩（真实接口全量空实现，仅匹配三臂返回受控值）。 */
    private static IServuxSetting<?> settingOf(String name, String comment, String providerName)
    {
        return new IServuxSetting<Object>()
        {
            @Override public String name() { return name; }
            @Override public Component prettyName() { return Component.literal(name); }
            @Override public Component comment() { return Component.literal(comment); }
            @Override public List<String> examples() { return List.of(); }
            @Override public IDataProvider dataProvider()
            {
                // 纯 JVM 最小 Provider 桩（真实单例会触发 NMS Level/GlobalPos 静态初始化，需 MC bootstrap，
                // 见 SchematicPlacementGuardTest 的 bootstrap 前置范式——本测只测字符串匹配，用桩更轻）
                return new IDataProvider()
                {
                    @Override public String getName() { return providerName; }
                    @Override public String getDescription() { return ""; }
                    @Override public Identifier getNetworkChannel() { return null; }
                    @Override public int getProtocolVersion() { return 0; }
                    @Override public boolean isEnabled() { return true; }
                    @Override public void setEnabled(boolean enabled) { }
                    @Override public boolean isRegistered() { return false; }
                    @Override public void setRegistered(boolean toggle) { }
                    @Override public void registerHandler() { }
                    @Override public void unregisterHandler() { }
                    @Override public int getTickInterval() { return 0; }
                    @Override public boolean isPlayerRegistered(ServerPlayer player) { return false; }
                    @Override public boolean hasPermission(ServerPlayer player) { return false; }
                    @Override public void onTickEndPre() { }
                    @Override public void onTickEndPost() { }
                    @Override public JsonObject toJson() { return null; }
                    @Override public void fromJson(JsonObject obj) { }
                    @Override public List<IServuxSetting<?>> getSettings() { return List.of(); }
                };
            }
            @Override public Object getDefaultValue() { return null; }
            @Override public Object getValue() { return null; }
            @Override public void setValueNoCallback(Object value) { }
            @Override public void setValue(Object value) { }
            @Override public void updateExamples(List<String> examples) { }
            @Override public void setValueFromString(String value) { }
            @Override public boolean validateString(String value) { return false; }
            @Override public String valueToString(Object value) { return String.valueOf(value); }
            @Override public Object valueFromString(String value) { return value; }
            @Override public void readFromJson(JsonElement element) { }
            @Override public JsonElement writeToJson() { return null; }
        };
    }

    @Test
    void threeArms_anyHit_countsAsMatch()
    {
        // name 臂命中
        assertTrue(ServuxCommand.matchesSearch(settingOf("update_interval", "servux.config.hud_data.update_interval.comment", "hud_data"), new String[] { "interval" }));
        // comment 臂命中（翻译键形态）
        assertTrue(ServuxCommand.matchesSearch(settingOf("some_name", "servux.config.hud_data.update_interval.comment", "hud_data"), new String[] { "update_interval.comment" }));
        // provider 臂命中（name/comment 均不含）
        assertTrue(ServuxCommand.matchesSearch(settingOf("some_name", "servux.config.hud_data.some_name.comment", "litematic_data"), new String[] { "hud_data" }));
    }

    @Test
    void allPartsMustMatch_andSemantics()
    {
        // 词一走 name 臂 + 词二走 provider 臂 → 全词命中（AND）
        assertTrue(ServuxCommand.matchesSearch(settingOf("update_interval", "x", "hud_data"), new String[] { "update", "hud_data" }));
        // 词二无一臂命中 → 整体不中
        assertFalse(ServuxCommand.matchesSearch(settingOf("update_interval", "x", "hud_data"), new String[] { "update", "nomatch" }));
        // 单词未命中
        assertFalse(ServuxCommand.matchesSearch(settingOf("update_interval", "x", "hud_data"), new String[] { "nomatch" }));
    }

    @Test
    void caseSensitive_upstreamFaithful()
    {
        // 上游无 toLowerCase 归一：大小写不同即不中（行为变更固化——原实现为双侧 toLowerCase 不敏感）
        assertFalse(ServuxCommand.matchesSearch(settingOf("update_interval", "x", "hud_data"), new String[] { "UPDATE" }));
        assertTrue(ServuxCommand.matchesSearch(settingOf("Update_Interval", "x", "hud_data"), new String[] { "Update" }));
    }
}
