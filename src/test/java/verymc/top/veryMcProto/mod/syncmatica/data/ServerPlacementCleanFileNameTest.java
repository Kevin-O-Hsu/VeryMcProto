package verymc.top.veryMcProto.mod.syncmatica.data;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import verymc.top.veryMcProto.mod.syncmatica.extended_core.PlayerIdentifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ServerPlacement#getCleanFileName()} 纯函数单测（逐字移植自上游
 * {@code ch.endte.syncmatica.data.ServerPlacement#getCleanFileName}，正则 {@code [^/\\]+$} 取 basename）。
 *
 * <p>背景：/syncmatica load 构造的 placement 内存 fileName 为绝对路径，wire（putMetaData）与
 * placements.json（saveServer）两个出口必须发/存 basename。本测固化清洗语义边界，
 * <b>防反向污染</b>：尾分隔符/空串形态 find() 失败时上游行为是<b>返回原串</b>（非 basename 意图值），
 * 断言必须与上游一致，不得为"更像 basename"而改断言或改实现。
 *
 * <p>注：saveServer 的清洗块（同正则 + setFileName 突变 + else 保底）依赖 Context/FileWriter，
 * 不在本单测覆盖内——其正确性以逐字对照上游 Syncmatica.java:124-137 为依据。
 */
class ServerPlacementCleanFileNameTest
{
    private static ServerPlacement placementOf(String fileName)
    {
        // 五参构造器纯赋值（无文件 IO），fileName 字符串原样入字段
        return new ServerPlacement(UUID.randomUUID(), fileName, "name", UUID.randomUUID(), PlayerIdentifier.MISSING_PLAYER);
    }

    @Test
    void windowsAbsolutePath_yieldsBasename()
    {
        // Bug 实测形态：/syncmatica load 写入的服务端路径
        ServerPlacement p = placementOf("plugins\\VeryMcProto\\syncmatics\\7407e937.litematic");
        assertEquals("7407e937.litematic", p.getCleanFileName());
    }

    @Test
    void windowsAbsolutePath_withDrive_yieldsBasename()
    {
        ServerPlacement p = placementOf("C:\\srv\\plugins\\VeryMcProto\\syncmatics\\castle.litematic");
        assertEquals("castle.litematic", p.getCleanFileName());
    }

    @Test
    void posixAbsolutePath_yieldsBasename()
    {
        ServerPlacement p = placementOf("/home/srv/plugins/VeryMcProto/syncmatics/castle.litematic");
        assertEquals("castle.litematic", p.getCleanFileName());
    }

    @Test
    void mixedSeparators_yieldsLastSegment()
    {
        ServerPlacement p = placementOf("a/b\\c.litematic");
        assertEquals("c.litematic", p.getCleanFileName());
    }

    @Test
    void plainFileName_noSeparator_returnsAsIs()
    {
        ServerPlacement p = placementOf("castle.litematic");
        assertEquals("castle.litematic", p.getCleanFileName());
    }

    @Test
    void emptyString_returnsEmpty()
    {
        // find() 对空串失败 → 返回原值（上游行为）
        ServerPlacement p = placementOf("");
        assertEquals("", p.getCleanFileName());
    }

    @Test
    void trailingSeparator_findFails_returnsOriginal()
    {
        // 末字符为分隔符被字符类排除 → find() 失败 → 原样返回（上游行为，严禁按 basename 意图断言）
        ServerPlacement p = placementOf("C:\\dir\\");
        assertEquals("C:\\dir\\", p.getCleanFileName());
    }

    @Test
    void driveRoot_findFails_returnsOriginal()
    {
        ServerPlacement p = placementOf("C:\\");
        assertEquals("C:\\", p.getCleanFileName());
    }

    @Test
    void setFileName_thenGetCleanFileName_idempotent()
    {
        // saveServer 突变收敛后（内存已为 basename），再次清洗应幂等
        ServerPlacement p = placementOf("a\\b\\castle.litematic");
        String cleaned = p.getCleanFileName();
        p.setFileName(cleaned);
        assertEquals("castle.litematic", p.getCleanFileName());
        assertEquals("castle.litematic", p.getFileName());
    }
}
