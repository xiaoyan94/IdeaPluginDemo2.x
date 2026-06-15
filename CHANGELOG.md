<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IdeaPluginDemo2.x Changelog

## [Unreleased]

## [2.0.8] - 2026-06-15

- 新增 SVN 提交日志分析工具窗口，支持按时间范围和项目路径分析SVN提交记录
- 支持作者筛选，筛选后统计图表、提交列表和结构化 Prompt 同步更新
- 柱状图可视化：柱子宽度钳制并在 slot 内居中，slot 均分填满横坐标，避免作者少/多时布局失衡
- 支持一键生成结构化 Prompt，可粘贴给大模型生成报告
- 支持手动选择报告类型：日报、周报、月度总结、年中总结、年度总结，每种类型有针对性指令
- 支持 Prompt 输出开关：包含详细变更路径 / 包含统计摘要
- 分析结果表格支持列排序和多选复制

## [2.0.7] - 2026-06-11

- 添加批量编辑 VM 参数功能

## [2.0.6] - 2026-05-28

- 新增 SVN 变更分析工具窗口，可按基准日期和路径分析各模块的文件变更数量及提交时间范围
- 日期输入支持手写输入与日历选择框，选中日期高亮回显
- 路径输入支持手写输入与目录选择框
- 分析结果表格支持单元格选中复制（Ctrl+C），列宽自适应内容
- 状态栏支持文本选中复制，模块名换行展示
- 输入区采用响应式网格布局，窄窗口下控件不被压缩

## [2.0.5] - 2026-03-27

OneClickNavication2.X测试版：

- 添加 <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>\\</kbd> 快速搜索URL
- 添加类似插件 `AutoTranslate` 的功能
- 优化部分功能使用体验
- 更新部分API版本，适配最新版本IDEA
- 适配 MES/APS 模块

## [2.0.4-alpha.2] - 2025-11-06

OneClickNavication2.X测试版：

- 添加 <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>\\</kbd> 快速搜索URL
- 添加类似插件 `AutoTranslate` 的功能
- 优化部分功能使用体验
- 更新部分API版本，适配最新版本IDEA

## [2.0.4-alpha.1] - 2025-10-21

OneClickNavication2.X测试版：

- 适配IDEA 2024.1及以后版本

## [0.0.4] - 2025-10-05

chore: 支持环境变量优先的配置读取

- 新增 envOrProperty 辅助函数，优先读取环境变量
- 修改 R2 存储配置读取逻辑，支持环境变量覆
- 更新 pluginVersion 从0.0.3 到0.0.4

## [0.0.3] - 2025-10-03

feat(build): 添加插件上传至CF R2存储的功能

- 在buildSrc中引入AWS S3 SDK依赖
- 新增UploadPluginToR2Task用于上传插件ZIP及updatePlugins.xml
- 配置R2的S3兼容API参数，包括访问密钥、端点等
- 修改generateLocalUpdateXml任务分组并添加依赖
- 注册uploadPluginToR2ByAmazonS3任务实现自动上传功能

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

[Unreleased]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.8...HEAD
[2.0.8]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.7...v2.0.8
[2.0.7]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.6...v2.0.7
[2.0.6]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.5...v2.0.6
[2.0.5]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.4-alpha.2...v2.0.5
[2.0.4-alpha.2]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.4-alpha.1...v2.0.4-alpha.2
[2.0.4-alpha.1]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v0.0.4...v2.0.4-alpha.1
[0.0.4]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v0.0.3...v0.0.4
[0.0.3]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/commits/v0.0.1
