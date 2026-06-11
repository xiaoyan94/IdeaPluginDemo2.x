import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

var pluginName = providers.gradleProperty("pluginName").get()
var pluginVersion = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        java.srcDirs("src/main/kotlin", "src/main/java")
    }
}

// Configure project's dependencies
repositories {
    maven("https://maven.aliyun.com/repository/public")

    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    implementation(libs.okhttp)
    implementation(libs.fastjson)
    implementation(libs.freemarker)
    implementation(libs.mysqlConnector)
    implementation(libs.poiOoxml)
//    implementation("com.squareup.okhttp3:okhttp:4.12.0") // 添加OkHttp依赖项
//    implementation("dddd.alibaba:fastjson:2.0.28")
//    implementation("org.freemarker:freemarker:2.3.33")
//    // https://mvnrepository.com/artifact/mysql/mysql-connector-java
//    implementation("mysql:mysql-connector-java:8.0.33")
//    implementation("org.apache.poi:poi-ooxml:5.2.5")


    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaUltimate, providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {

    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
//        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
//            with(changelog) {
//                renderItem(
//                    (getOrNull(pluginVersion) ?: getUnreleased())
//                        .withHeader(false)
//                        .withEmptySections(false),
//                    Changelog.OutputType.HTML,
//                )
//            }
//        }

        // 将 changelog 的所有版本渲染为 HTML 并拼接（最新项放前面）
        changeNotes = providers.provider {
            with(changelog) {
                // getAll() 返回 Map<String, Changelog.Item>
                getAll().entries
                    .toList()
//                    .asReversed()
                    .joinToString(separator = "<hr/>") { (_, item) ->
                        // renderItem 渲染单个版本为 HTML；你可以调整 withHeader(true/false)
                        renderItem(item.withHeader(true).withEmptySections(false), Changelog.OutputType.HTML)
                    }
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    patchPluginXml {
        pluginName = providers.gradleProperty("pluginName")
    }

    buildPlugin {
        archiveFileName.set("${pluginName}-${pluginVersion}.zip")
    }

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    runIde {
        // 1.x 写法
//        jvmArgumentProviders += CommandLineArgumentProvider {
//            listOf(
//                "-Dfile.encoding=UTF-8",
//                "-Xmx4096m",
//                "-XX:ReservedCodeCacheSize=512m",
//                "-Xms256m",
//                "-Dsun.io.useCanonCaches=false",
//                "-Djdk.http.auth.tunneling.disabledSchemes=\"\"",
//                "--add-opens=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
//                "--add-opens=java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED",
//                "-javaagent:F:\\Tools\\jetbra-8f6785eac5e6e7e8b20e6174dd28bb19d8da7550\\jetbra\\ja-netfilter.jar=jetbrains"
//            )
//        }

        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "-Xmx4096m",
            "-XX:ReservedCodeCacheSize=512m",
            "-Xms256m",
            "-Dsun.io.useCanonCaches=false",
            "-Djdk.http.auth.tunneling.disabledSchemes=\"\"",
            "--add-opens=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED",
            "-javaagent:F:\\Tools\\jetbra-8f6785eac5e6e7e8b20e6174dd28bb19d8da7550\\jetbra\\ja-netfilter.jar=jetbrains",
        )
    }

    compileJava {
        options.encoding = "UTF-8"
    }

    compileTestJava {
        options.encoding = "UTF-8"
    }

}

/*1.x写法*/
/*intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Dfile.encoding=UTF-8",
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}*/

/*2.x写法*/
val runIdeForUiTests by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Dfile.encoding=UTF-8",
                "-Drobot-server.port=8082",
                "-Dide.mac.message.dialogs.as.sheets=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
            )
        }
    }

    plugins {
        robotServerPlugin()
    }
}

// 获取 changelog
val changelog = project.changelog

/**
 * 辅助函数：优先取环境变量，否则取 gradle.properties
 */
fun envOrProperty(envKey: String, propKey: String): Provider<String?> {
    val envProvider = providers.environmentVariable(envKey)
    val propProvider = providers.gradleProperty(propKey)

    // 环境变量优先，如果为空则 fallback 到 gradle.properties
    return envProvider.orElse(propProvider)
}

tasks.register<GenerateLocalUpdateXmlTask>("generateLocalUpdateXml") {
    // 指定任务组
    group = "Publish To Private Repository"
    description = "Generate local updatePlugins.xml"

    dependsOn("buildPlugin", "patchChangelog")

    // 设置参数
    pluginId.set(providers.gradleProperty("pluginId"))
    pluginName.set(providers.gradleProperty("pluginName"))
    pluginVersion.set(providers.gradleProperty("pluginVersion"))
    pluginGroup.set(providers.gradleProperty("pluginGroup"))
    sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
    untilBuild.set(providers.gradleProperty("pluginUntilBuild"))
    downloadUrl.set(
        providers.gradleProperty("pluginRepositoryOssUrl").map { "$it${pluginName.get()}-${pluginVersion.get()}.zip" }
    )

    // 插件描述（从 README.md 的注释块抽取）
    pluginDescription.set(
        providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            val lines = it.lines()
            if (!lines.containsAll(listOf(start, end))) {
                throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
            }
            lines.subList(lines.indexOf(start) + 1, lines.indexOf(end))
                .joinToString("\n") { line -> markdownToHTML(line) } // 你可以在这里加 markdownToHTML(line)
        }
    )

    // 变更日志（HTML 渲染）
    changeNotes.set(
        providers.provider {
            changelog.getAll()
                .entries
                .sortedByDescending { it.key } // 新版本在前
                .joinToString(separator = "<hr/>") { (ver, item) ->
                    println("rendering changelog: $ver")
                    changelog.renderItem(
                        item.withHeader(true).withEmptySections(false),
                        Changelog.OutputType.HTML
                    )
                }
        }
    )
    // 固定 plugin，从文件读取
    extraPluginXml.set(
        providers.fileContents(layout.projectDirectory.file("OneClickNavigation-v1-plugin.xml")).asText
    )

    outputFile.set(layout.buildDirectory.file("updatePlugins.xml"))
}

tasks.register<UploadPluginToR2Task>("uploadPluginToR2ByAmazonS3") {
    group = "Publish To Private Repository"
    description = "Upload built plugin and updatePlugins.xml to Amazon S3"

    dependsOn("buildPlugin", "generateLocalUpdateXml")

    // 优先环境变量，其次 gradle.properties
    region.set(envOrProperty("R2_S3_REGION", "r2.s3.region"))
    bucketName.set(envOrProperty("R2_BUCKET_NAME", "r2.bucketName"))
    endpoint.set(envOrProperty("R2_S3_ENDPOINT", "r2.s3.endpoint"))
    accessKey.set(envOrProperty("R2_S3_ACCESS_KEY_ID", "r2.s3.accessKeyId"))
    secretKey.set(envOrProperty("R2_S3_SECRET_ACCESS_KEY", "r2.s3.secretAccessKey"))

    this.pluginName.set(providers.gradleProperty("pluginName"))
    this.pluginVersion.set(version.toString())

    pluginFile.set(layout.buildDirectory.file("distributions/${pluginName.get()}-${pluginVersion.get()}.zip"))
    updateXmlFile.set(layout.buildDirectory.file(providers.gradleProperty("updatePluginXmlFileName")))

}

tasks.register<Copy>("copyPluginToLocalDir") {
    group = "Publish To Private Repository"
    description = "Copy built plugin and updatePlugins.xml to a local directory"

    dependsOn("buildPlugin", "generateLocalUpdateXml")

    // 使用 directoryProperty 读取或默认目录
    // 输出目录：优先 gradle.properties: localPublishDir，否则默认 projectDir/local-publish
    val outputDir = objects.directoryProperty()
    outputDir.set(providers.gradleProperty("localPublishDir").map { dirPath: String -> layout.projectDirectory.dir(dirPath) } // String -> Directory
        .orElse(layout.projectDirectory.dir("local-publish"))            // 默认 Directory
    )

    // 要复制的文件
    from(layout.buildDirectory.file("distributions/${pluginName}-${pluginVersion}.zip"))
    from(layout.buildDirectory.file(providers.gradleProperty("updatePluginXmlFileName")))

    // 输出目标目录
    into(outputDir)

    doFirst {
        println("📦 Copying plugin and update XML to: ${outputDir.get().asFile.absolutePath}")
    }

    doLast {
        println("✅ Files copied successfully to ${outputDir.get().asFile.absolutePath}")
    }
}

/*
tasks.jar {
    archiveBaseName.set(pluginName)     // 主体名（不含版本号）
    archiveVersion.set(pluginVersion)          // 可选，默认取 project.version
}
*/
