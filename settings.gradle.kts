plugins {
    // 工具链自动下载仓库（Gradle 官方推荐）：本机探测不到所需 JDK（如 25）时经 Foojay Disco API 自动 provisioning。
    // 兼容本仓库已开启的配置缓存 / build cache；与本机 ~/.gradle/gradle.properties 的
    // org.gradle.java.installations.paths（指向本机既有 JDK 25）互补——先探测命中即不下载。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "VeryMcProto"
