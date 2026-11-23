package verymc.top.veryMcProto.framework.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonUtils#writeJsonToFileAsPath} 原子落盘单测：JSON 落盘链路为
 * 临时文件 + REPLACE_EXISTING/ATOMIC_MOVE 覆盖（不支持时回退普通移动），
 * 不再先删旧文件。两条判据：①目标已存在时新值生效且目录无 .tmp 残留；
 * ②落盘文件可被重新解析且与写入内容等价。
 */
class JsonUtilsAtomicWriteTest
{
    @TempDir
    Path tempDir;

    private static JsonObject sampleJson()
    {
        JsonObject root = new JsonObject();
        root.addProperty("key", "value");
        root.addProperty("number", 42);
        return root;
    }

    @Test
    void overwriteExistingFileAppliesNewContentWithoutTmpResidue() throws IOException
    {
        Path target = tempDir.resolve("config.json");
        Files.writeString(target, "{\"key\":\"old\",\"stale\":true}");

        JsonObject root = new JsonObject();
        root.addProperty("key", "value");

        assertTrue(JsonUtils.writeJsonToFileAsPath(root, target));

        JsonObject readBack = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
        assertEquals("value", readBack.get("key").getAsString());
        assertFalse(readBack.has("stale"));

        try (Stream<Path> entries = Files.list(tempDir))
        {
            long tmpResidue = entries.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0L, tmpResidue);
        }
    }

    @Test
    void writtenFileReparsableAndEquivalentToWrittenJson()
    {
        Path target = tempDir.resolve("data.json");
        JsonObject root = sampleJson();

        assertTrue(JsonUtils.writeJsonToFileAsPath(root, target));

        JsonObject readBack = JsonUtils.parseJsonFileAsPath(target).getAsJsonObject();
        assertEquals(root, readBack);
    }
}
