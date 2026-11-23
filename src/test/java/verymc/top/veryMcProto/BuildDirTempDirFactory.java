package verymc.top.veryMcProto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * JUnit 5 全局 {@link TempDirFactory}：把所有裸 {@code @TempDir} 目录统一建到
 * {@code build/tmp/test-tempdir/} 下（根目录绝对路径由 build.gradle.kts 的 test 任务
 * 经系统属性传入），不再落系统临时目录。
 *
 * <p>动机：Windows 上 @TempDir 清理与"刚写完的文件"之间存在句柄竞态（首跑偶发失败、
 * 重跑即过）——全局默认工厂收敛目录进 build/ 由 {@code gradlew clean} 统一回收，清理模式
 * 置 NEVER（配置见 {@code src/test/resources/junit-platform.properties}）。
 *
 * <p>系统属性缺失时（如 IDE 不经 Gradle 裸跑单测）退回 JUnit 默认行为（系统临时目录）。
 */
public class BuildDirTempDirFactory implements TempDirFactory
{
    /** 根目录系统属性名——值由 build.gradle.kts 的 test 任务注入（build/tmp/test-tempdir 绝对路径）。 */
    public static final String ROOT_DIR_PROPERTY = "verymc.test.tempdir";

    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext) throws IOException
    {
        String root = System.getProperty(ROOT_DIR_PROPERTY);

        if (root == null || root.isBlank())
        {
            // 不经 Gradle 注入（IDE 裸跑）时维持 JUnit 默认：系统临时目录
            return Files.createTempDirectory("junit-");
        }

        Path rootDir = Path.of(root);
        Files.createDirectories(rootDir);
        return Files.createTempDirectory(rootDir, "junit-");
    }
}
