//Gradle 强制要求 settings.gradle.kts 里的块顺序是：pluginManagement → plugins → 其他内容。
pluginManagement {
    repositories {
        // 替换或补充 JetBrains 官方源为镜像
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "IdeaPluginDemo2.x"
