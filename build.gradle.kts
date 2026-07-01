plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "verymc.top"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // PacketEvents（EasyPlace 拦截原版 use_item_on 包）；运行时由独立插件提供，compileOnly 引用
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
}

dependencies {
    // paperDevBundle 提供 Mojang 官方映射（全 deobfuscated）的 NMS（net.minecraft.*），开发时直接用 Mojang 名访问。
    // 锁定：paperweight 2.0.0-beta.21 + Paper 1.21.11 dev bundle（旧格式 1.21.11-R0.1-SNAPSHOT）。
    // 与姊妹项目 VeryMcBot 一致，已在该环境验证通过。
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")

    // PacketEvents（EasyPlace 拦截原版 use_item_on）：compileOnly，运行时由服务器独立安装的 packetevents 插件提供。
    // 锁定 2.13.0（codemc 最新 release；对照源码 OriginImpl/packetevents-2.0 为 2.13.1 开发版，API 一致）。
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")

    // 单元测试（JUnit 5 / Jupiter）。test classpath 继承 main 的 paperDevBundle——NMS 类（FriendlyByteBuf 等）
    // 在纯 JVM 可用，无需启动 MC 服务端（仅访问类，不触达需 Bootstrap 的方块/物品注册表运行时逻辑）。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// 1.21.11 支持 reobf：reobfJar 产出 Spigot 运行时映射 jar，标准 Paper 服务器可直接加载。
// 经典 plugin.yml 按 Paper 默认假设为 Spigot-mapped，加载时自动 deobfuscate 回 Mojang 运行时映射——与此产出匹配。
// 反射（Reflect）用 Mojang 名访问私有成员：reobf 不转换反射字符串，而 Paper 运行时即 Mojang 映射，故反射 Mojang 名天然正确。
tasks.assemble {
    dependsOn(tasks.reobfJar)
}

tasks {
    test {
        useJUnitPlatform()
    }

    runServer {
        // 仅供本地测试（M1/M2 验证）；与 paperweight 互补，不影响 build/reobf。
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val projectVersion = project.version
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(mapOf("version" to projectVersion))
        }
    }
}
