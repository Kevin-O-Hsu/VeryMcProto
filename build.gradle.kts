plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "verymc.top"

// 版本唯一来源：gradle.properties 的 mcVersion / buildNumber → "<MC版本>-b<构建号>"（如 1.21.11-b1）。
// MC 上游改命名风格（如日期式 26.1）时直接改 mcVersion 即可，格式不变。分支命名对应 ver/<mcVersion>。
val mcVersion = providers.gradleProperty("mcVersion").get()
val buildNumber = providers.gradleProperty("buildNumber").get()
version = "$mcVersion-b$buildNumber"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // PacketEvents（EasyPlace 拦截原版 use_item_on 包）；运行时由独立插件提供，compileOnly 引用
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
}

dependencies {
    // paperDevBundle 提供 Mojang 官方映射（全 deobfuscated）的 NMS（net.minecraft.*），开发时直接用 Mojang 名访问。
    // MC 26.1 起 dev bundle 改为 <mcVersion>.build.<N>-stable 新命名（旧格式 X-R0.1-SNAPSHOT 止于 1.21.x），
    // 26.1.2.build.74-stable 为 26.1 线当前最高 stable（repo.papermc.io metadata 实测）。
    paperweight.paperDevBundle("26.1.2.build.74-stable")

    // PacketEvents（EasyPlace 拦截原版 use_item_on）：compileOnly，运行时由服务器独立安装的 packetevents 插件提供。
    // 锁定 2.13.0（codemc 最新 release；对照源码 OriginImpl/packetevents-2.0 为 2.13.1 开发版，API 一致）。
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")

    // 单元测试（JUnit 5 / Jupiter）。test classpath 继承 main 的 paperDevBundle——NMS 类（FriendlyByteBuf 等）
    // 在纯 JVM 可用，无需启动 MC 服务端（仅访问类，不触达需 Bootstrap 的方块/物品注册表运行时逻辑）。
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// MC 26.1 起 Paper 不再支持把插件重映射到 Spigot 映射（Mojang 已移除服务端混淆；paperweight 官方文档明示
// "reobfuscated plugins will not work from Paper 26.1 onwards"）——不再装配 reobfJar，build 产物即 Mojang 映射 jar，
// 标准 Paper 26.1+ 直接加载。反射（Reflect）用 Mojang 名访问私有成员的约定在此形态下依旧天然正确。

tasks {
    test {
        useJUnitPlatform()
    }

    runServer {
        // 仅供本地测试（M1/M2 验证）；与 paperweight 互补，不影响 build/reobf。
        // MC 版本跟随 gradle.properties 的 mcVersion（版本唯一来源）。
        // 注意：task 内用自身的 providers 取值，不捕获脚本顶层 val（配置缓存要求）。
        minecraftVersion(providers.gradleProperty("mcVersion").get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val projectVersion = project.version
        val mcVersionProp = providers.gradleProperty("mcVersion").get()
        filesMatching(listOf("plugin.yml", "paper-plugin.yml", "version.properties")) {
            expand(mapOf("version" to projectVersion, "mcVersion" to mcVersionProp))
        }
    }
}
