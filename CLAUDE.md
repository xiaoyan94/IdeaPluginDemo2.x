# CLAUDE.md

本仓库是 IntelliJ IDEA 插件 OneClickNavigation2.X（com.zhiyin.plugins），面向 com.zhiyin MES/WMS 项目的 i18n 折叠翻译、Mapper/Moc/Controller 一键导航、SVN 日志分析等能力。

## 编译

- Gradle 构建需 JVM 17+；本机默认 java 是 8，用 IDEA 自带 JBR：
  `JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2025.2.2/jbr" ./gradlew compileJava --console=plain`
- 发版（CHANGELOG 写变更 → 升 `pluginVersion` → `copyPluginToLocalDir`（自动归档 changelog）→ `uploadPluginToR2ByAmazonS3` → 验证 → 提交）走 `/plugin-release` skill。

## 插件性能铁律（2026-08 「打开文件即卡 + Full GC」事故沉淀）

- **daemon 热路径（foldingBuilder / annotator / lineMarker / Inlay 渲染）禁止 `FilenameIndex`/`FileTypeIndex` 查询**——一次索引兜底在大文件上会放大成数百次查询，是卡顿与 GC 压力的直接来源。i18n 值只查 `I18nCacheManager` 项目级缓存，未命中返回 null / 显示 `${key}` 占位。
- 全量数据由 `I18nScanner.scanProject` 启动扫描灌入（传统 Java Web 项目走 `scanProjectResourcesByIndex`，须在 smart mode 下执行索引查询）；增量由 `I18nFileListener`（VFS_CHANGES）维护，模块归属口径统一 `ModuleUtilCore.findModuleForFile`。索引查询只允许出现在启动扫描与用户主动触发的动作（翻译对话框）里。
- **生命周期注册规范**：
  - `MessageBus` 订阅必须 `project.getMessageBus().connect(disposable)` 带父级，禁止裸 `connect()`——否则订阅与监听器捕获的对象（如整个 Editor 对象图）泄漏到 project 生命周期。
  - 按 editor 注册的监听/Disposable 用带 Disposable 的重载（`addCaretListener(l, parent)`）；注意 `Editor` 接口不实现 `Disposable`（`EditorImpl` 才实现），需 `instanceof Disposable` 判定。
  - Inlay/diff 逻辑里 dispose inlay 时同步清理关联 Map（如 `rendererMap`），防长会话累积。
- **热路径禁止 `System.out.println`**（daemon 每元素触发，同步 I/O 拖慢扫描），用 `Logger` 或删除。
- foldingBuilder 的 `buildFoldRegions(root, document, quick)` 需尊重 `quick` 语义，避免 editor 刚打开时做重计算。

## DataModelGenerator（代码生成器）改造

- 改造计划见 `docs/codegen-refactor-plan.md`：按文档中**第一个未勾选项**推进，每项独立走「开发 → 编译 → 测试 → 发版 → 提交 → 勾选」循环；发版后更新计划内进度看板。涉生成器的改动优先对齐该计划，别游离在计划外。
- **每小点实现循环委托子代理**（无需用户每次要求）：「开发 → 编译 → 单测」交 Agent 子代理执行，任务书含改动点原文、验收标准、编译命令（JBR 17 + --offline）、单测命令、只改本项范围的约束；主会话只做取项、验收、runIde 验证协调、发版、提交、勾选看板。
- **检查点协议**：每项「勾选 + 看板更新（含未提交变更清单）」完成后即干净检查点，主动提示用户可 /compact 或 /clear（Claude 无法自行执行 /compact）；计划文件 + CLAUDE.md 是唯一断点载体，新会话说「继续 codegen 计划」即可续作。

## 代码风格

- 大量历史代码用 `System.out.println` 调试输出与注释掉的死代码（`HtmlFoldingManagerOld`、`MyHTMLFoldingBuilder` 等无引用类）——修改时保持周边风格即可，但**不要在热路径新增** println；删死代码前先 grep 确认无引用（含 plugin.xml）。
- `src/main/resources/config.properties` 只存占位符（REPLACE_ME）；真实翻译密钥放项目根 `config.local.properties`（已 git-ignore，模板 `config.local.properties.example`），`processResources` 构建时注入同名 key——仓库永不落真实密钥，`runIde`/`buildPlugin` 自动携带。历史 zip（2.0.5–2.0.19）曾以真实密钥公开分发（oss.vaetech.uk 匿名可下），密钥作废重发前视为已泄漏；`gradle.properties` 注释行里的 r2.cf.tokenValue 同理。
