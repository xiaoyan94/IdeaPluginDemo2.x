<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IdeaPluginDemo2.x Changelog

## [Unreleased]

## [2.0.13] - 2026-08-24

- 修复：Moc/Mapper XML 侧 Ctrl+B 反查 Java 调用点时的进度条循环与全项目扫描
  - 根因：两侧引用的 `multiResolve` 用 `ReferencesSearch`（PsiMethod / XML 属性值的全项目用法反查，几万文件）反查调用点，搜索过程又回调各引用提供器（含自身解析），解析-搜索互相触发，表现为「Resolving reference」进度条反复跑。
  - 修复：统一改为 `PsiSearchHelper.processAllFilesWithWordInLiterals` 的 word index 正向搜索——先按字符串字面量定位候选文件（只碰含该词的文件，毫秒级），再在文件内过滤本插件引用（`MyJavaMethodReference` / `MyMocReference`），排除恰好同名文本的普通字符串。
- 调整：Java "mocName" 字符串 Ctrl+B 只列同模块的 Moc XML（`MyMocReference.multiResolve` 严格同模块过滤，与原 `resolve()` 口径一致，跨模块同名 Moc 不再进入目标集合）。
- 新增：Moc XML ↔ Java 调用双向导航（与 Mapper 侧同模式）
  - `MyMocReference` 升级 poly 化 + `isReferenceTo` 覆写 + 解析懒缓存：bizCommonService.xxxMocData(..., "mocName", ...) 字符串的正向 Ctrl+B 保持跳 Moc XML name 属性（同模块优先）；**Find Usages / Moc name 声明处 Ctrl+B / Rename** 可反查到该字符串（此前 `PsiReferenceBase` 单目标默认实现下反向链路可用但未与多目标对齐）。
  - 新增 `MyMocXmlReferenceContributor`：`<Moc name="xxx">` 的 name 值上注册正向引用，Ctrl+B 弹出 Java 调用点选择框（单调用点直接跳），条目展示**具体调用方法**（如 `bizCommonService.insertMocData("xxx")` + 文件名:行号，`CallSiteNavigationTarget.buildDisplayText` 取字面量所在方法调用表达式）。`isReferenceTo` 恒 false 防自递归；dumb mode 跳过；懒缓存。
  - 调用点展示包装泛化为 `CallSiteNavigationTarget`（原 `QueryDaoCallNavigationTarget` 改名，展示文案参数化），Mapper 与 Moc 两侧共用。
- 增强：queryDaoDataT 字符串方法名与 Dao 方法 / Mapper XML 标签的双向导航
  - `MyJavaMethodReference` 保持原双目标解析（Mapper XML 语句标签 + Dao 接口方法，Ctrl+B 仍弹选择框）不变，新增 `isReferenceTo` 覆写：遍历 `multiResolve` 结果与目标比对（对 `StatementNavigationTarget` 包装目标同时认其导航委托元素）。
  - 效果：Find Usages / 在 Dao 方法声明处 Ctrl+B 可反查到 `queryDaoDataT(..., "getXxx", ...)` 字符串用法；对 Dao 方法 Rename 时字符串同步改名。此前双目标下 `resolve()` 恒为 null，基类 `PsiReferenceBase.isReferenceTo` 默认实现导致反向链路整体失效。
  - Mapper XML 侧：`MyXMLReference`（id 值 → Dao 方法引用）的 `multiResolve` 增加反向目标——通过 `ReferencesSearch` 查 Dao 方法用法，取 `MyJavaMethodReference` 引用的源元素（queryDaoDataT 字符串）入目标集合，使 **XML id 上 Ctrl+B 弹出「Dao 方法 + queryDaoDataT 调用字符串」选择框**。字符串目标用 `QueryDaoCallNavigationTarget` 包装（新增，同 `StatementNavigationTarget` 模式）：展示 `queryDaoDataT("getXxx")` + 文件名:行号 + 插件图标，裸字面量在选择框中只有引号字符串无上下文。配套：`resolve()` 改为单目标直跳/多目标返回 null 的标准语义（原实现恒取首个目标，Ctrl+B 永远直接跳 Dao 不弹框）；解析结果懒缓存（用法搜索开销大）；`isReferenceTo` 覆写为只认 Dao 方法的轻量比对（不走 super 的 resolve 链，避免 multiResolve→ReferencesSearch→isReferenceTo 自递归）；dumb mode 下跳过用法搜索。
- 修复 inlay 全部消失：启动扫描线程断言异常阻断 HtmlFoldingProjectService 初始化
  - 根因：`I18nScanner.scanProject` 在 `Task.Backgroundable` 后台线程调用 `ProjectTypeChecker.isTraditionalJavaWebProject` → `MyApplicationService.isSpringCloudMesProject` 内 `PsiManager.findFile` 读 pom.xml **未持 read-action**，在 IntelliJ 线程断言下抛 `RuntimeExceptionWithAttachments`（idea.log 实证，帅威/海程等有 springboot+springcloud 目录的项目必现）；异常中断 `scanProject` 末尾的 `HtmlFoldingProjectService.getInstance()`——fileOpened 监听注册在其构造函数，链路一断所有新开文件不再创建 HtmlFoldingManager，inlay 全灭。
  - 修复（根上）：`checkIsOldMesProject` 内部自持 `ReadAction.compute`，不再依赖调用方线程上下文。
  - 修复（同类隐患）：`I18nCacheManager.scanI18nFiles` 的 `findModuleForFile`、`I18nFileListener.resolveModule`、`I18nCacheManager.findModuleForFile` 的 `getSourceRoots`——VFS 回调/后台线程上的模块模型查询统一包 read-action（嵌套无害）。
  - 防御：传统项目索引扫描段 try-catch 兜底（只记 warn），保证 `HtmlFoldingProjectService` 初始化（inlay 创建链守门人）永不被扫描逻辑阻断。
- 修复纯缓存化后 i18n 缓存的失效链路（事件增量刷新）
  - 缺口 A：`I18nFileListener` 原按「文件在模块 resources/i18n 目录下」定位模块，传统 Java Web 项目的 i18n 文件不在该目录 → 编辑 properties 后缓存永不更新（启动索引扫描灌入的是死数据）。修复：模块解析优先 `ModuleUtilCore.findModuleForFile`（与启动扫描归属口径一致），i18n 目录匹配仅作回退。
  - 缺口 B：`isI18nFile` 原匹配任意 `.properties`，config/database 等无关文件的每次保存都会触发模块解析 + 整文件加载。收紧为 i18n 形态文件名（`web_` 前缀或四语言后缀）。
  - 缺口 C（预存）：`updateInlays` 的 diff 只比较源文本，properties 变更后已存在的 Inlay 永远显示旧译文。修复：diff 增加译文维度（value 变化即重建 Inlay）；同步清理 `rendererMap` 中已释放 inlay 的死条目，避免长会话滚动累积。
  - 不引入定时刷新：失效源（文件变化）均有 VFS 事件覆盖——IDE 内保存即时触发，外部修改/SVN update 在窗口聚焦 refresh 后同样走 `VFS_CHANGES`；每次项目打开另有全量重扫兜底。定时轮询只会把已从热路径移除的索引查询换个地方重新引入。
- 修复打开文件即卡顿 + 老年代堆积：编辑器生命周期泄漏与热路径索引兜底
  - **泄漏（P0）**：`HtmlFoldingManager` 每实例通过 `project.getMessageBus().connect()`（无父级）订阅 VFS 变更，且从未注册 Disposer——每开一个文件永久滞留一条 MessageBus 连接 + 整个 Editor 对象图（document、inlayMap、markup 等），对应 jstat 老年代 80%+ 不降。修复：连接改为 `connect(this)`，并在 `getInstance` 中 `Disposer.register(editor, manager)`，editor 释放时级联清理订阅与 Inlay。
  - **卡顿根因（P0）**：folding 占位符热路径（`MyXMLFoldingBuilder` 每标签、`CustomHtmlFoldingBuilder.getPlaceholderText`、`MyJspI18nFoldingBuilder`、`MyJavaScriptFoldingBuilder`、`MyFreemarkerHTMLAnnotator` 等）在缓存未命中时落到 `FilenameIndex`×4 语言 + `FileTypeIndex` 全项目 properties 扫描；大 Layout XML 一次折叠计算触发数百次索引查询，正是「打开文件就卡 + Eden 每几秒打满」的分配源。修复：`findModuleWebI18nPropertyValue` / `findModuleDataGridI18nPropertyValue` 改为纯缓存查询（未命中返回 null），索引查询仅保留在用户主动触发的翻译对话框路径。
  - **缓存覆盖（配套）**：传统 Java Web 项目（老 MES）i18n 文件不在 `resources/i18n` 目录下，原靠上述索引兜底覆盖——现由 `I18nScanner.scanProject` 启动时按索引一次性找齐（`scanProjectResourcesByIndex`，smart mode 下执行）灌入缓存；扫描完成后刷新已打开编辑器的折叠与 Inlay，避免显示旧缓存 `${key}`。
  - **降噪（P1）**：移除热路径 `System.out.println`（`MyFreemarkerHTMLAnnotator` 每元素 4~5 条、`I18nCacheManager.loadSingleFile`、`I18nFileListener` 每次 VFS 事件、`MyPsiUtil` key 提取），daemon 扫描不再同步写控制台。
  - **caret 监听（P1）**：`FoldingCaretListener` 改用带 Disposable 的重载注册（绑定 editor 生命周期），修复原 `fileClosed` 从「关闭后新选中的 editor」移除监听器导致移错对象、旧 editor 上的监听器永不清理的问题；同时光标移动时无状态变化（无需折叠/展开）则跳过 `runBatchFoldingOperation`。
- 修复 i18n Inlay 导致的内存泄漏（GC Thrashing / RangeHighlighter 堆积）
  - 根因：`HtmlFoldingManager.updateInlays()` 对每个打开的编辑器**全文档**扫描 i18n 占位符并为每一个创建 Inlay（底层即 `RangeHighlighterImpl`/`RHNode`）；在大型 MES 项目里单个大文件可达数千 Inlay，多文件并行累积至百万级，成为 IntelliJ 进程内存耗尽、Full GC 暴挫的直接诱因。
  - 措施 P0：
    - **大文件保护**：`getTextLength() > 1MB` 时不再创建 Inlay，仅保留原生折叠（`Constants.I18N_INLAY_MAX_TEXT_LENGTH`）。
    - **可见区域限制**：仅渲染视口内（上下各缓冲 50 行，`Constants.I18N_INLAY_VISIBLE_BUFFER_LINES`）的占位符，Inlay 数量由「全文档」降至「视口级」（约 1/10 以下）。
    - **滚动动态加载**：`HtmlFoldingManager` 实现 `VisibleAreaListener`，滚动时 `visibleAreaChanged` 重新计算可见区域 Inlay，滚出视口的旧 Inlay 在 diff 阶段被 dispose 释放。

  - 措施 P1（节流）：`HtmlFoldingManager` 引入 `Alarm` 做 200ms debounce（`scheduleUpdateInlays()`），合并高频文档变更与连续滚动事件，避免每次按键/滚动都重建 Inlay，进一步降低 CPU 与 GC 压力。

  - 措施 P2（清理）：删除已 `@Deprecated` 且无任何引用的死代码 `HtmlFoldingProjectComponent.java`，消除重复订阅风险与维护负担。

## [2.0.11] - 2026-07-20

- 增强 Java 方法引用解析能力

## [2.0.10] - 2026-07-20

- 支持在SVN提交日志分析中开关代码量统计
  - 新增「包含代码量统计」复选框（工具窗口筛选区）与设置页开关，取消勾选时跳过 `svn diff` 调用，显著提升分析速度。
  - 跳过代码量统计时，提交记录/统计表格的代码量列显示 `-`，图表自动禁用「按代码量」指标并回到「按提交次数」。
  - 开关状态持久化保存，可作为默认偏好在设置页配置。
- 优化SVN代码量统计的性能与稳定性
  - 并行化：多条提交的 `svn diff` 改为有界线程池（最多 6 线程）并行执行，墙钟时间约降为 1/池大小。
  - 流式解析：边读取 diff 输出边统计新增行，避免大提交将整段 diff 缓冲进内存导致 OOM / 卡死。
  - 重试机制：单条提交超时或命令失败时自动重试（最多 3 次），缓解网络抖动导致的「全部失败」。
  - 定向 diff（D）：仅对匹配 `.java` / `*Mapper.xml` 的变更文件执行 `svn diff`（去掉仓库路径前导 `/` 作为 WC 相对目标），大幅减小 diff 体积与服务器负担；若定向 diff 未命中匹配文件（目标路径与 WC 不匹配）或失败，则自动降级回全量 diff。
  - 超时收紧 + 一键重试（E）：单条 diff 超时阈值收紧为 3s 并配合重试；对仍失败的提交提供「重试失败项」按钮，可一键批量重跑定向 diff，完成后刷新表格/图表/Prompt 并保留仍失败项供再次重试。
  - 超时可配置：设置页新增「主分析超时(秒)」与「重试失败项超时(秒)」（均默认 3），分别作用于主分析首次统计与「重试失败项」时的单条 `svn diff` 超时；当网络/服务器较慢导致 3s 频繁超时时可调大。状态栏会显示本次重试使用的超时值。
  - 取消响应优化：点「取消」时，`svn diff` 由阻塞式 `waitFor` 改为每 100ms 轮询检查取消标记，命中即 `destroyForcibly` 终止进程，取消延迟从「等到超时（最多 9s/条）」降至约 100ms。
  - 查看失败项：新增「查看失败项」按钮（存在代码量统计失败的提交时可用），弹窗以表格列出每条失败提交的版本、作者、日期、失败原因，并为每条记录提供可直接复制执行的 `svn diff` 命令（定向 diff，路径为工作目录相对路径）；支持单击命令单元格或「复制」按钮复制单条，以及「复制全部命令」（按工作目录分组并附 `cd` 前缀，便于批量手动执行）。
- 修复代码量统计因路径不匹配而失败（E155010 node not found）
  - 根因：`svn log -v` 返回的是仓库绝对路径（如 `/branches/HaichengMes/webproj/xxx/Foo.java`），而工作副本根目录本身已对应仓库路径 `/branches/HaichengMes/webproj`，旧逻辑仅去掉前导 `/` 后作为 diff 目标，导致在工作副本下查找不存在的 `branches/...` 子目录而报错。
  - 修复：通过 `svn info --xml` 读取工作副本的 relative-url（或 url−root）得到其仓库路径前缀，将变更文件的仓库绝对路径正确转换为工作副本相对路径后再执行定向 `svn diff`；前缀按工作目录缓存，分析开始时清空。「查看失败项」弹窗中复制的 `svn diff` 命令同样使用转换后的相对路径，可直接在工作目录下执行。
- 统计分析新增「代码量占比」列
  - 统计表在原有「占比」（提交次数占比）基础上新增「代码量占比」列，按各作者代码变更行数合计占总代码量的百分比计算；Markdown 导出同步新增该列。
- 修复大提交代码量统计反复超时（剩 4 条一直超时）
  - 根因：超时阈值是固定值（设置 `svnDiffTimeoutSeconds`，默认 3s）。`svn diff -c REV 文件1 文件2 …` 会让 SVN 服务器对每个文件逐个做 diff（一次网络往返），单条提交改动文件越多越慢；固定 3s 阈值下，改动数十个文件的提交（如 29051/29602/29114/28970）在 3 次重试中均超时，被误判失败。
  - 修复：超时阈值按匹配文件数自适应放大（每个匹配文件 +1s，上限取 60s 与“配置阈值×10”的较大者）。小提交仍按原阈值快速完成，大提交获得足够时间，不再误判超时。
- 修复表格列排序按字符串排序的问题
  - 根因：统计表「提交次数/变更文件总数/代码量/提交次数占比/代码量占比」及提交记录表「版本号/变更文件数/代码量」等列的单元格值为字符串（含 `%`、`-`），双击列头排序时按字典序排列（如 `"10" < "9"`、`"10%" < "9%"`）。
  - 修复：为上述数值/百分比列设置自定义比较器，解析为数值后按大小排序；`-`/空值视为最小值，`%` 结尾自动去除后比较。
- 设置新增「排除作者」功能
  - 在插件设置中新增「排除作者(多人, 逗号/分号/空格分隔)」输入框，可填写多名需忽略的作者（如 `zhangsan,lisi`）。
  - 统计时这些作者的提交会在记录收集阶段被整体排除，不参与提交数、代码量、图表与明细统计，也不再出现在作者下拉框中；代码量统计（svn diff）也会跳过这些提交，节省时间。匹配忽略大小写。
- 代码量统计支持大提交分批累加
  - 改动：匹配文件数超过 `DIFF_BATCH_SIZE`（默认 10）时，将目标拆分为多批，每批单独 `svn diff` 后将各批新增行累加得到总代码量；单批超时按批内文件数自适应放大（每文件 +1s，上限 60s 或“配置阈值×10”），单批失败仅重试该批。
  - 效果：单条命令携带的文件数可控，避免大提交（如单次改动数十个文件）在固定阈值下反复超时；相比单纯放大整条超时，分批更稳且每条命令耗时更短。
- 修复进度条与取消响应问题
  - 修复工具窗口进度条一直显示 0% 的问题：进度条改为确定模式，各分析阶段（查询/筛选/代码量统计/统计分析/更新界面）实时同步百分比与文字，主分析与「重试失败项」均生效。
  - 修复点「取消」后 IDEA 后台任务仍显示「Stopping…」长时间不结束的问题：取消时真正调用 `indicator.cancel()`（新增 `currentIndicator` 引用），并将 `svn log` 输出读取改为守护线程 + 主线程每 100ms 轮询取消标记，命中即 `destroyForcibly` 终止进程，取消可即时生效。

## [2.0.9] - 2026-06-15

- 支持多项目分析与可视化，优化UI交互
- 支持SVN提交日志代码量分析与可视化
  - 新增SVN提交日志的代码量统计功能，可在日志和统计表格中展示代码变更行数。
  - 引入“按代码量”和“按提交次数”两种统计指标切换，优化图表可视化分析。
  - 改进代码量统计规则，只统计`.java`和`*Mapper.xml`文件变更，并排除`/components/`目录。   
  - 修复SVN diff命令处理大量数据时可能引发的死锁问题，通过异步读取进程输出流增强了稳定性。
  - 调整SVN日志查询的日期范围逻辑，确保用户选择的结束日期包含当天所有提交记录。
- 优化插件国际化配置，将UI文本统一管理，提升了维护性和可扩展性。

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

[Unreleased]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.13...HEAD
[2.0.13]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.11...v2.0.13
[2.0.11]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.10...v2.0.11
[2.0.10]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.9...v2.0.10
[2.0.9]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.8...v2.0.9
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
