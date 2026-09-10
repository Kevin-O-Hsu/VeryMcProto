// 顶层显式 import：tasks{} 块内 Project 接收器的 `java` 扩展（JavaPluginExtension）会遮蔽
// java.* 包名限定，故 ZipFile/Properties 必须走 import 引入而非全限定名。
import java.util.Properties
import java.util.zip.ZipFile

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
        // expand() 的占位符值不参与 Gradle up-to-date 跟踪——必须显式 inputs.property，否则
        // 发版 buildNumber+1 后本任务误判 UP-TO-DATE、陈旧展开产物被打进新文件名 jar
        // （26.1.2-b2 事故实证：jar 名 b2、内部 plugin.yml/version.properties 仍为 b1）。
        inputs.property("version", projectVersion)
        inputs.property("mcVersion", mcVersionProp)
        filesMatching(listOf("plugin.yml", "paper-plugin.yml", "version.properties")) {
            expand(mapOf("version" to projectVersion, "mcVersion" to mcVersionProp))
        }
    }

    // 版本注入终检：解包产物 jar，断言内部 version.properties / plugin.yml 与 project.version
    // 一致——把「展开陈旧 / 占位符缺位」这类静默错版转为构建失败（26.1.2-b2 事故后增设）。
    // 无 outputs 声明故每次构建必跑（成本为解包读两Entry）；捕获 Provider 而非 Task，配置缓存安全。
    val verifyVersionInjection = register("verifyVersionInjection") {
        // 依赖必须经 tasks.named 显式取得——registering lambda 内裸引用 `jar` 会静默解析到
        // 非任务对象，dependsOn 不进任务图（dry-run 实证 :verifyVersionInjection 孤节点），
        // verify 抢在 jar 重打包前读旧 jar。
        val jarTask = named<Jar>("jar")
        val jarArchive = jarTask.flatMap { it.archiveFile }
        val expectedVersion = project.version.toString()
        // api-version 应逐字等于 mcVersion（如 26.1.2；1.20.5 起官方支持三段式，语义 = 低于该值
        // 的服务器拒载）。本插件协议面绑死精确补丁（MOD_STRING 硬门禁 + dev bundle），放行旧补丁
        // 只会让握手静默失败；Modrinth 等平台亦按 api-version 标注适用版本——纳入终检构建期拦截。
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
                            "（= mcVersion 主次段）——升级 MC 版本时须同步 plugin.yml 的 api-version")
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
