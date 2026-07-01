package verymc.top.veryMcProto.mod.syncmatica.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 字符串工具（移植自 {@code ch.endte.syncmatica.util.StringTools}）。
 *
 * <p><b>Paper 适配</b>：原版 {@code getModVersion} 用 FabricLoader 读 mod 元数据，Paper 端不需要动态读
 * （协议版本由 {@code SyncmaticaReference.MOD_VERSION} 常量提供，触发 FEATURE 交换用全集），故移除该方法。
 * 保留 {@link #getHexString}（调试用）。
 */
public class StringTools
{
    public static String getHexString(byte[] bytes) {
        List<String> list = new ArrayList<>();
        for (byte b : bytes) {
            list.add(String.format("%02x", b));
        }
        return String.join(" ", list);
    }
}
