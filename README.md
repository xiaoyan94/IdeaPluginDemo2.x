# IdeaPluginDemo2.x

![Build](https://github.com/xiaoyan94/IdeaPluginDemo2.x/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [ ] Get familiar with the [template documentation][template].
- [ ] Adjust the [pluginGroup](./gradle.properties) and [pluginName](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml) and [sources package](./src/main/kotlin).
- [ ] Adjust the plugin description in `README` (see [Tips][docs:plugin-description])
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [ ] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.

<!-- Plugin description -->

![i18n中文自动完成](https://idea-plugin.oss.vaetech.uk/i18n1.gif)

**[OneClickNavigation2.X](https://github.com/xiaoyan94/Idea-Plugin-OneClickNavigation)** 是针对智引 Mes 项目开发的一个插件(2.X版本适用于IDEA2024.1及以后的新版IDEA)，用于提升日常开发效率。

此插件主要实现了以下功能：

- Dao接口方法声明和Service中的Dao方法调用，**一键跳转**到Mapper SQL
- **queryDaoDataT**方法参数和对应dao方法、xml**互相跳转**，**自动补全提示**（`Ctrl+空格`），方法参数错误提示。
- Moc 相关通用方法，提供Java一键跳转到 Moc xml文件。
- 大部分场景下的**I18n中文资源串错误提示，中文折叠显示**。已支持：
  - Java 中的 I18nUtils.getMessage 相关资源串方法
  - HTML 中 FreeMarker 模板的 message 指令
  - JSP 中的 message 标签
  - JavaScript 中的 i18n 方法
  - Layout 文件中 DataGrid 的 Title->value 和 Field->label
  - Imp*Mapper 文件中的 i18n
- 大部分场景下的I18n资源串**自动翻译**（**简繁英越**）和**自动替换key**（`Alt+Enter` 手动触发或💡**自动修复提示**）。支持场景同上。
- Imp*Mapper 导入模板文件中的 col 列字段**一键自动排序**。
- 一键打开当前 java 源文件编译后的 class 文件目录。
- 一键生成MOC文件。
- 一键生成Layout文件。
- 一键生成HTML文件。
- 一键生成Controller、Service、Dao、Mapper文件。
- 一键生成多语言导入模板EXCEL文件
- 一键百度搜索。
- Contoller URL搜索跳转工具窗口
- 💡more useful features...


### 新版IDEA插件开发-脚手架

基于官方模板创建的IDEA插件开发脚手架。

提供开箱即用的基础框架、构建脚本，可快速开发插件、发布插件。

### 基础Gradle构建功能

- [x] Gradle网络代理socks5配置
- [x] UTF-8编码配置
- [x] IDEA调试运行参数：指定IU版本并进行激活配置
- [x] changelog历史更新日志包含所有版本
- [x] 自动生成私服updatePlugins.xml文件
- [x] 自动上传插件zip包和updatePlugins.xml文件至私服S3

<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "IdeaPluginDemo2.x"</kbd> >
  <kbd>Install</kbd>
  
- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/xiaoyan94/IdeaPluginDemo2.x/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
