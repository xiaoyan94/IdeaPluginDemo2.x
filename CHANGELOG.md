<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IdeaPluginDemo2.x Changelog

## [Unreleased]

## [0.0.2] - 2025-10-03

### Improved

- 创建 buildSrc 模块以管理自定义 Gradle 任务
- 将 GenerateLocalUpdateXmlTask 类移至 buildSrc/src/main/kotlin
- 更新 build.gradle.kts 文件，移除旧的任务定义
- 在 buildSrc/build.gradle.kts 中配置插件和仓库
- 修改 XML 生成逻辑，增加 vendor 和 updateTime 字段
- 更新 .idea/gradle.xml 配置以识别 buildSrc 模块
- 添加 Gradle Wrapper 的代理设置支持本地开发环境访问外网

## [0.0.1] - 2025-10-03

### Added

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- 初始化编译环境，基础构建框架，插件开发环境搭建

[Unreleased]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v0.0.2...HEAD
[0.0.2]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/commits/v0.0.1
