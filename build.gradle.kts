// 顶层显式 import：tasks{} 块内 Project 接收器的 `java` 扩展（JavaPluginExtension）会遮蔽
// java.* 包名限定，故 ZipFile/Properties 必须走 import 引入而非全限定名。
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
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
        // MC 版本跟随 gradle.properties 的 mcVersion（版本唯一来源）。
        // 注意：task 内用自身的 providers 取值，不捕获脚本顶层 val（配置缓存要求）。
        minecraftVersion(providers.gradleProperty("mcVersion").get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val projectVersion = project.version
        val mcVersionProp = providers.gradleProperty("mcVersion").get()
        // expand() 的占位符值不参与 Gradle up-to-date 跟踪——必须显式 inputs.property，否则
        // 发版 buildNumber+1 后本任务误判 UP-TO-DATE、陈旧展开产物被打进新文件名 jar
        //（1.21.11 线与 26.1.2-b2 事故同构暴露面：jar 与 -reobf.jar 双产物同步陈旧）。
        inputs.property("version", projectVersion)
        inputs.property("mcVersion", mcVersionProp)
        filesMatching(listOf("plugin.yml", "paper-plugin.yml", "version.properties")) {
            expand(mapOf("version" to projectVersion, "mcVersion" to mcVersionProp))
        }
    }

    // 版本注入终检：解包产物 jar，断言内部 version.properties / plugin.yml 与 project.version
    // 一致——把「展开陈旧 / 占位符缺位」这类静默错版转为构建失败。无 outputs 声明故每次构建必跑
    //（成本为解包读两 Entry）；捕获 Provider 而非 Task，配置缓存安全。
    // ★ 1.21.11 线（含 reobf）说明：本任务检查 plain jar（Mojang 映射），交付物是
    // build/libs/VeryMcProto-<版本>-reobf.jar——reobfJar 只重映射 .class、资源条目逐字节透传，
    // 两 jar 内 plugin.yml/version.properties 同源，断言等价；勿取 plain jar 部署（经典 plugin.yml
    // 会被旧版 Paper 按 Spigot 映射假设 deobf，见上方 :43-45 注释）。
    val verifyVersionInjection = register("verifyVersionInjection") {
        // 依赖必须经 tasks.named 显式取得——registering lambda 内裸引用 `jar` 会静默解析到
        // 非任务对象，dependsOn 不进任务图（dry-run 实证孤节点），verify 抢在 jar 重打包前读旧 jar。
        val jarTask = named<Jar>("jar")
        val jarArchive = jarTask.flatMap { it.archiveFile }
        val expectedVersion = project.version.toString()
        // api-version 已模板化（plugin.yml '${'$'}{mcVersion}'，随唯一来源展开），应逐字等于 mcVersion
        //（如 1.21.11；两段/三段皆官方支持——Paper ApiVersion.java 接受 2-3 段，语义 = 低于该值的
        // 服务器拒载）。本插件按 1.21.11 dev bundle 编译、NMS 绑死精确版本，放行旧补丁版本只会
        // 运行期炸于 NMS 漂移——加载期拒载优于运行期炸（Q4 裁决收口，与 README 宣称一致）。
        val expectedApiVersion = providers.gradleProperty("mcVersion").get()
        group = "verification"
        dependsOn(jarTask)
        doLast {
            val jarFile = jarArchive.get().asFile
            val expected = expectedVersion
            ZipFile(jarFile).use { zip ->
                val propEntry = zip.getEntry("version.properties")
                    ?: throw GradleException("产物 jar 缺 version.properties：${jarFile.name}")
                val props = Properties()
                zip.getInputStream(propEntry).use { props.load(it) }
                val propVersion = props.getProperty("version")
                    ?: throw GradleException("version.properties 缺 version 键：${jarFile.name}")
                val ymlEntry = zip.getEntry("plugin.yml")
                    ?: throw GradleException("产物 jar 缺 plugin.yml：${jarFile.name}")
                val ymlText = zip.getInputStream(ymlEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                val ymlVersion = Regex("(?m)^version:\\s*'?([^'\\r\\n]+)'?\\s*$").find(ymlText)?.groupValues?.get(1)
                    ?: throw GradleException("plugin.yml 缺 version 行：${jarFile.name}")
                val ymlApiVersion = Regex("(?m)^api-version:\\s*'?([^'\\r\\n]+)'?\\s*$").find(ymlText)?.groupValues?.get(1)
                    ?: throw GradleException("plugin.yml 缺 api-version 行：${jarFile.name}")
                if (ymlApiVersion != expectedApiVersion) {
                    throw GradleException(
                        "api-version 注入不一致：jar=${jarFile.name} 内 plugin.yml=$ymlApiVersion，期望 $expectedApiVersion" +
                            "（= mcVersion，plugin.yml 已模板化 '${'$'}{mcVersion}'）——疑似展开陈旧，执行 ./gradlew clean 后重新构建")
                }
                if (propVersion != expected || ymlVersion != expected) {
                    throw GradleException(
                        "版本注入不一致：jar=${jarFile.name} 内 version.properties=$propVersion / " +
                            "plugin.yml=$ymlVersion，期望 $expected——疑似展开陈旧，执行 ./gradlew clean 后重新构建")
                }
                logger.lifecycle("版本注入校验通过：${jarFile.name} 内部版本 = $expected")
            }
        }
    }
    build {
        dependsOn(verifyVersionInjection)
    }
}
