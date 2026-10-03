package verymc.top.veryMcProto.mod.servux.command;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code /servux litematic transmit} 命令的目录包含性守卫单测（2026-10 安全修复附带收口）。
 *
 * <p>守卫语义：{@code dir.resolve(fileName).normalize()} 后以 {@code Path.startsWith(Path)}
 * （逐名元素比较）确认未逃逸基目录。同源对照：C2S 接收链的客户端可控 FileName 逃逸
 * （SchematicBuffer.getFileName 的 {@code Path.of(name)} 裸拼装）已整链移除，本守卫为
 * 命令面（op 参数）同类防御。Windows/POSIX 路径分隔符差异由 {@code Path} API 自行归一。
 */
class LitematicaTransmitCommandGuardTest
{
    @TempDir
    Path base;

    @Test
    void parentTraversalRejected()
    {
        assertNull(ServuxCommand.resolveContained(base, "..\\escaped.litematic"));
        assertNull(ServuxCommand.resolveContained(base, "../escaped.litematic"));
        assertNull(ServuxCommand.resolveContained(base, "sub/../../escaped.litematic"));
    }

    @Test
    void absolutePathRejected()
    {
        assertNull(ServuxCommand.resolveContained(base, "C:\\Windows\\evil.litematic"));
        assertNull(ServuxCommand.resolveContained(base, "/etc/passwd.litematic"));
    }

    @Test
    void siblingDirectoryPrefixRejected()
    {
        // 关键语义：Path.startsWith 是逐名元素比较——真逃逸到兄弟目录（字符串前缀同名的
        // "schematics-evil"）必须拒绝；String.startsWith 会因前缀 "/…/schematics" 误放行
        String siblingName = base.getFileName().toString() + "-evil";
        assertNull(ServuxCommand.resolveContained(base, ".." + java.io.File.separator + siblingName + java.io.File.separator + "evil.litematic"));
        assertNull(ServuxCommand.resolveContained(base, "../" + siblingName + "/evil.litematic"));
    }

    @Test
    void plainNameContained()
    {
        Path resolved = ServuxCommand.resolveContained(base, "castle.litematic");
        assertNotNull(resolved);
        assertEquals(base.toAbsolutePath().normalize().resolve("castle.litematic"), resolved);
    }

    @Test
    void innerNavigationNormalizedStillContained()
    {
        // 合法内部导航（往返归一）不误伤
        Path resolved = ServuxCommand.resolveContained(base, "sub/../ok.litematic");
        assertNotNull(resolved);
        assertEquals(base.toAbsolutePath().normalize().resolve("ok.litematic"), resolved);
    }

    @Test
    void emptyNamePassesGuardResolvesToBaseItself()
    {
        // 空名：resolve("") 得 dir 本身——过守卫，由下游 createFromFile 读失败兜底报错
        Path resolved = ServuxCommand.resolveContained(base, "");
        assertNotNull(resolved);
        assertEquals(base.toAbsolutePath().normalize(), resolved);
    }
}
