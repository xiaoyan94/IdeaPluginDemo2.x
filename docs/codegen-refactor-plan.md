# DataModelGenerator（Model code generator）改造计划

> 制定日期：2026-09-15 · 基于 2.0.24 现状 · 目标项目：`E:\view\DengqiMes\webproj\com.zhiyin.mes.dengqi.project`
>
> **用法**：每个小点独立走一个「开发 → 编译 → 测试 → 发版」循环。做下一项时，找本文档中第一个未勾选项。发版完成后勾选并更新下方进度行。

## 进度看板

- **当前进行到**：阶段 0 与 P1-1、P1-2、P1-3、P1-7 已完成；P1-3（含 ShowTableStructureAction 连接发现顺手修）随 2.0.28 发版提交（2026-09-16）。下一项 P1-4（预留号顺延：P1-4→2.0.29、P1-5→2.0.30、P1-6→2.0.31）；P1-8 证据已齐、可插队（DengqiMes dev 三个 properties 的临时 sslMode=DISABLED 待还原后再测）；P1-9 独立可并行
- **已完成**：P0-1、P0-2、P0-3、P1-1、P1-2、P1-3、P1-7
- **未提交变更清单**（发版提交后清空）：本轮 P1-3 源码 + CHANGELOG + gradle.properties + 本计划勾选看板 + 上轮遗留（看板定夺落盘、CLAUDE.md「每小点实现循环委托子代理」条目）随 2.0.28 发版一并提交，提交后本清单清空。.kotlin/、buildSrc/out/、out/、build_compile.log、hs_err_pid36440.log 仍为本机杂项，不随提交。
- **版本号规则**：计划中的版本号是预留号，若中途被计划外修复占用则整体顺延 +1，以 CHANGELOG 实际为准。阶段 2 收尾升 minor（2.1.0），阶段 4 升 2.2.0。

## 每个小点的标准循环（发版 SOP）

1. 读本计划，取第一个未勾选项；读其「改动点」「验收标准」。
2. **复现/取证**（fix 类必做）：改前先在 runIde 沙箱或真实项目复现问题，记录现象（截图/路径）作为前后对比基线。
3. 改代码（只改本项范围，不顺手重构）。
4. 编译：`JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2025.2.2/jbr" ./gradlew compileJava --console=plain --offline -q`（改了 build.gradle.kts/buildSrc 才去掉 --offline）。
5. 单测：`./gradlew test --console=plain --offline -q`（涉及 P0-2、P2-2、P3-6 时必跑）。
6. runIde 验证：`./gradlew runIde`，沙箱中打开 DengqiMes 项目，按验收标准逐条核对。
7. 真实项目验证（标注「需真实验证」的项）：在 DengqiMes 工作副本生成产物并 diff 检查，验证后还原生成物（SVN revert，不污染工作副本）。
8. CHANGELOG.md 在 `## [Unreleased]` 下按既有格式写变更（一行总结 + bullet 明细）。
9. gradle.properties 升 `pluginVersion`。
10. 走 `/plugin-release` skill 全流程（copyPluginToLocalDir → uploadPluginToR2ByAmazonS3 → 公网验证）。
11. git 提交（消息格式沿用 `fix:`/`feat:`/`chore:` + 中文摘要）。
12. 勾选本项 checkbox，更新进度看板。
13. **检查点（防上下文膨胀）**：看板同步记录「未提交变更清单」（发版提交后清空）；随后向用户提示「现在是干净检查点，可 /compact 或 /clear」——本计划文件 + CLAUDE.md 即唯一断点载体，新会话说「继续 codegen 计划」即可按第一个未勾选项续作。

## 环境与依赖

| 依赖 | 说明 |
|------|------|
| runIde 沙箱项目 | `E:\view\DengqiMes\webproj\com.zhiyin.mes.dengqi.project`（SpringBoot 多模块，src/main/webapp 结构） |
| 测试 DB | DengqiMes dev 库（连接信息在项目 properties 的 `database.url/username/password`，生成器只跑 `SHOW CREATE TABLE`，只读安全） |
| 传统 Java Web 验证项目 | `E:\view\SplashMes\webproj\com.zhiyin.mes.splash.project`（GATE-G 已确认；实测 `com.zhiyin.mes.splash.app.*` 模块为 `src/main/resources/META-INF/resources/WEB-INF` 结构） |
| 基线产物 | `docs/codegen-baseline/`（P0-1 生成），后续每版 diff 对比 |

## 全局纪律（每项都适用）

- 生成器是**用户主动触发**的动作，索引查询允许（不受 daemon 热路径铁律约束）；但后台线程跑 PSI/索引仍须 read-action + smart mode（参照 DatabaseConnectionFinder.java:37-41 的既有模式）。
- 模板（.ftl）改动属于产物格式变更：每项涉及模板的，必须 diff 基线产物确认无计划外变化。
- 写用户文件（i18n properties、菜单 SQL、codegen.json）一律**只追加/新建，绝不修改已有行**（保守原则）。
- 每项验证遵循自主验证闭环：先复现再修，修完跑通，前后对比一起交付。

---

## 阶段 0：基线与护栏（一次做完，可并入 2.0.25 发版，不单独发版）

### P0-1 生成回归基线 ✅（2026-09-15，2.0.24）
- [x] runIde 沙箱打开 DengqiMes，在 `com.zhiyin.mes.app.dengqi.order` 模块用当前 2.0.24 完整生成一套查询页（建议用已有表如 `biz_base_factory` 类似结构的小表），产物 6 件套（Layout.xml / Html / Controller / Service / Dao / Mapper.xml）+ Moc.xml 复制快照到 `docs/codegen-baseline/`
- [x] 记录各产物目标路径清单（后续每版的路径回归就对着这张清单查）
- **执行记录**：表 `biz_base_factory`（DDL 存档 `docs/codegen-baseline/input/`），GridName=`BaseFactory`，7 件产物零降级；生成后 DengqiMes 工作副本已 revert 还原。**采集时撞出 EDT 慢操作断言缺陷 → 已立 P1-7**；`工厂编号` 等字段 i18n 未命中退化为裸中文（P2-1 前提实证）。

### P0-2 纯逻辑单测护栏 ✅（2026-09-15，随 P0-1 一起，未提交）
- [x] 为 `TableParser.parseCreateTable` / `parseDQL`（TableParser.java:27/99）、`CodeGenerateService.formatSql`（CodeGenerateService.java:492）补 JUnit 测试到 src/test/kotlin（junit 已配，build.gradle.kts:50）。formatSql 为 private，提为包级静态方法
- [x] 断言以「当前行为快照」为准（哪怕行为有瑕疵，先锁定；后续 P3-6 改 formatSql 时显式更新断言并说明）
- [x] 测试样例直接用 TableParser.java:218 main() 里的 biz_product DDL 与 DQL 样例
- **执行记录**：`src/test/kotlin/com/zhiyin/plugins/utils/TableParserTest.kt`（8 断言组/4 用例）+ `src/test/kotlin/com/zhiyin/plugins/service/CodeGenerateServiceFormatSqlTest.kt`（4 用例，须与被测同包访问包级静态）。快照用临时捕获器实测 dump 后定稿（含已知瑕疵：decimal 丢 scale、datetime length 空串、函数内逗号误切、and/or 保留原大小写）。全量 `gradlew test` 11 用例零失败（含既有 MyPluginTest 3 例无回归）。

### P0-3 计划文件接入协作文档 ✅（2026-09-15，随 P0-1 一起，未提交）
- [x] CLAUDE.md 增加指向本计划的链接（含「按第一个未勾选项推进」的说明）

---

## 阶段 1：P0 缺陷修复（patch 版本，每项一发）

### P1-1 [预留 2.0.25] 重复生成不再把文件写到模块根目录 ✅（2026-09-15，2.0.25）
- [x] 完成
- **现状证据**：CodeGenerateService.generateXmlFile:424-440 —— 目录不存在（:427）或文件已存在（:437）时 `outputDirVariable = contentRoot` 降级继续写 → 半途重跑在模块根目录产生垃圾文件
- **改动点**：
  1. generateXmlFile 改为返回结果枚举（SUCCESS / SKIP_FILE_EXISTS / FAIL_DIR_NOT_FOUND），去掉内部逐文件弹窗
  2. generateBaseQueryTypeFile（CodeGenerateService.java:276）收集所有产物结果，结束后一次汇总通知：成功 N 个（列出文件名）、跳过 M 个（原因：已存在/目录不存在），不再写任何降级位置
  3. 复现先行：改前连续生成两次同名，确认第二次在 content root 出现垃圾文件（记录到本项备注）
- **验收标准**：
  - 同名连续生成两次：第二次 0 个新文件，汇总提示「已存在跳过」
  - 手动删掉目标目录再生成：报「目录不存在」且不在任何位置写文件
- **测试**：runIde 验证（不需真实项目）
- **风险**：低；只改错误分支，成功路径产物 diff 基线应零变化
- **执行记录**：复现 ✓（DengqiMes order 模块连续两次生成，模块根出现 6 个垃圾文件——第 7 件 Moc 因根目录已有同名 BaseFactory.xml 抛「无法生成件」中断，与链路预判一致）；验收 1 ✓（二次生成 0 新文件、7 个已存在跳过、零弹窗；查询页 6 件与 Moc 各一条汇总通知，两方法独立调用所致，P3-3 表单化时统一）；验收 2 ✓（layout/Order 临时改名后生成：目录不存在跳过 1 个 + 已存在跳过 5 个，任何位置零写入）；基线 diff ✓ 7/7 字节级一致；工作副本已还原干净。**教训**：Order 目录含 44 个真实 layout XML，验收「目录不存在」必须临时改名整目录而非 rm（本次误删已 svn update 即时恢复）。**附带发现（供 P1-2）**：DB 读取路径即使选对 dev 连接（192.168.116.9 可达、SHOW CREATE TABLE 命令行手测正常）仍静默失败，错误被 println 吞——P1-2 修复时先在沙箱复现取真实异常。generateModelByFields（死代码）内两处 generateXmlFile 调用未适配汇总（忽略返回值，编译无影响），P4-2 删除。

### P1-2 [预留 2.0.26] JDBC 读取后台化（修 EDT 冻结）+ 超时 + 错误可见 ✅（2026-09-16，2.0.27——2.0.26 被计划外修复占用顺延）
- [x] 完成
- **执行记录**：验收 1 ✓（不可达连接 192.168.33.52 test 库点读取 → 有界时间弹「数据库读取失败」含 host:port 与脱敏原因，UI 不冻结；WARN 日志实证，修复前被 println 吞）；验收 2 ✓（表名弹窗取消 → sql/tableName/字段表原值不动，无 from null a）；验收 3 ✓（`biz;drop table x` 拒绝）；`grep System.out DatabaseMetadataUtil` 零命中 ✓；单测 6 用例组（表名校验/脱敏/host 提取）全过。**教训**：lambda → 匿名 Task.Backgroundable 迁移时 `this` 语义变化（`this.tableName` 须改 `DataModelGenerator.this.tableName`）。SSL 根因取证与回退决策见 P1-8（2026-09-16）。
- **现状证据**：DataModelGenerator.fetchFieldsFromDatabase:228-274 在 invokeLater（EDT）内同步 `DriverManager.getConnection` + `SHOW CREATE TABLE`；DatabaseMetadataUtil.java:18 无连接超时；:25/28/35/118/181 错误全走 `System.out.println` 被吞
- **改动点**：
  1. DatabaseMetadataUtil.getTableMetadata 用 `DriverManager.getConnection(url, props)` 传 `connectTimeout=3000, socketTimeout=10000`
  2. 删全部 println，改 `Logger.getInstance(DatabaseMetadataUtil.class)` 记录并向上抛出（方法签名改为抛受检异常或返回带错误信息的 Result）
  3. UI 侧：选连接 + 输表名留在 EDT；JDBC 查询放 `Task.Backgroundable`（纯网络调用，不碰 PSI/VFS，无需 read-action）；onSuccess 回 EDT 更新 fields + updateTableModel；onError 弹 MyPluginMessages.showError（带库地址脱敏后的错误原因）
  4. 表名输入校验（DataModelGenerator.java:245-246）：null/空直接提示中止；且必须匹配 `^[A-Za-z0-9_.]+$`（SHOW CREATE TABLE 字符串拼接，杜绝奇怪输入）
- **验收标准**：
  - 故意填错库地址点「从数据库读取」：有界时间内弹明确错误、UI 全程不冻结（connectTimeout=3000；2026-09-16 修订：本机透明 TCP 拦截实测 ~5 秒、不可达网络下界 ≈2×connectTimeout≈6 秒，用户确认按此口径，不追求 ≤3 秒）
  - 取消表名输入弹窗：无 `from null a` 状态污染（sql/tableName 保持原值）
  - `grep System.out src/main/java/com/zhiyin/plugins/utils/DatabaseMetadataUtil.java` 零命中
- **测试**：runIde 验证 + 错误路径手测；单测覆盖表名校验正则
- **风险**：中低；注意 Backgroundable 回调线程，更新 Swing 前确认在 EDT

### P1-3 [预留 2.0.27] 传统 Java Web 项目 layout 双写修复 ✅（2026-09-16，2.0.28——预留号被 P1-2/P1-7 顺延占用）
- [x] 完成
- **执行记录**：验收 1 ✓（SplashMes order 模块 DDL 粘贴 biz_base_factory 生成，layout 仅 1 个文件落 `src/main/resources/META-INF/resources/WEB-INF/etc/business/layout/Order/`，webapp「目录不存在」跳过噪音消失；唯一跳过为 html——传统项目页面 jsp 化，范围外、用户认可暂不支持）；验收 2 ✓（DengqiMes 基线回归 7 件路径一致、6 件字节级一致；Layout 差异与 P1-7 第二轮验收同形态——基线 DDL 粘贴采集 comment 错位，本次产物逐字段正确：地址→address、城市→city、第三方推送工厂→3rdflag，归 P1-9）；验收 3 ✓（表结构查看 DengqiMes 完整链路用户验证通过，零 Slow operations 断言弹窗；SplashMes 侧弹「未找到 database.* 配置」属已知边界非回归）。单测 17 例全过（含 DataModelGeneratorTest 6 例）。两项目生成物验证后已删除还原。**附带发现（已记 CHANGELOG 已知边界，暂不立专项）**：传统 Spring XML 项目数据库配置在 applicationContext.xml（splash.app.web 的 applicationContext.xml:49）而非 properties database.* 三键，连接发现与「从数据库读取」不支持此类项目，生成器走 DDL 粘贴路径不受影响——若后续要支持需扩展 DatabaseConnectionFinder 扫 XML。
- **现状证据**：CodeGenerateService.java:382-392 —— isTraditionalJavaWebProject 分支先写 `src/main/webapp/...`（traditional 项目通常无此目录，触发 P1-1 所修的降级垃圾），再把路径变量改为 META-INF 路径，:390 又无条件写第二次 → 两个文件
- **改动点**：
  1. traditional 分支只保留 `src/main/resources/META-INF/resources/WEB-INF/...` 一处输出，删除第一次 webapp 写入；DengqiMes（SpringBoot+webapp）路径逻辑不动
  2. 顺手修（2026-09-16 定夺并入，来源 P1-7 执行记录附带发现①）：ShowTableStructureAction.java:109 的 EDT 连接发现（表结构查看功能，非生成链路）照 P1-7 ② 模式移入 `Task.Backgroundable` 后台预取、onSuccess 消费
- **验收标准**：
  - traditional 项目（SplashMes）勾 Layout 生成：仅 1 个文件且落在 META-INF 路径
  - DengqiMes 回归：产物路径与基线完全一致
  - 表结构查看（ShowTableStructureAction）全程零 Slow operations 断言弹窗
- **测试**：需真实验证（SplashMes + DengqiMes 各一次）+ runIde 表结构查看一次
- **风险**：低（主改动与顺手修不同文件，互不影响）

### P1-4 [预留 2.0.28] import 链路改为可选且默认关闭，删空 SQL 语句
- [ ] 完成
- **现状证据**：BaseQueryTypeController.ftl:89-113 生成 import 接口 → BaseQueryTypeService.ftl 的 import 方法依赖 `excelImportService.getImportMapper(...)`（需要未生成的 Imp mapper 列定义配置才能工作）且 dao 调用被注释 → BaseQueryTypeMapper.ftl 生成**空的** `<update id="import...">`（运行时静默 no-op）→ BaseQueryTypeDao.ftl 声明 import 方法。整条链生成即坏，无任何提示
- **改动点**：
  1. UI 复选框区（DataModelGenerator.createCheckBoxPanel:203）加「Excel 导入（需另行配置 Imp mapper）」默认**不勾**；「Excel 导出」默认勾（现有导出方法保留）
  2. paramsMap 传 generateImport / generateExport
  3. 模板改造：Controller/Service/Dao/Mapper 四个模板用 `<#if generateImport>` 包住 import 相关方法与声明；**Mapper 空 update 语句无条件删除**
  4. 勾选导入时，Service import 方法顶部生成 `// TODO: 需配置 Imp mapper 列定义后方可启用`（配置位置口径见 mes-import-regression skill）
- **口径 gate（GATE-A）**：已确认（2026-09-15）——导出方法**默认生成**（保留复选框、默认勾选）；导出框架调用方式随项目版本不同，适配工作独立成项 P1-6
- **验收标准**：
  - 不勾导入：4 个产物无 import 痕迹（grep import 方法名零命中）
  - 勾导入：生成且带 TODO 注释；Mapper.xml 无空语句节点
  - 不勾导出：Controller/Service 无 export 方法
- **测试**：runIde 验证，产物 diff 基线（默认选项组合下，除 import 块外应零变化）
- **风险**：低（模板条件渲染）

### P1-5 [预留 2.0.29] 连接选择对话框：密码打码 + 路径可辨识
- [ ] 完成
- **现状证据**：SelectDatabaseConnectionDialog.java:22-32 密码列明文；:49-57 按文件名+url+用户名+密码四元组反查选中项（脆弱）；:54 只显示文件名，dev/prod 同名文件难区分
- **改动点**：
  1. 密码列显示 `******`，选择逻辑改 `connectionInfoList.get(table.getSelectedRow())` 直取（转换视图行索引 convertRowIndexToModel），删四元组反查
  2. 第一列改显示相对 project 路径（`VfsUtilCore.getRelativePath(file, projectBaseDir)` 或存相对路径进 map）
  3. 表格支持双击行即选中（现在必须选中后点 Select）
- **验收标准**：弹窗全程无明文密码；DengqiMes 多环境同名 properties 可区分；双击生效
- **测试**：runIde 验证
- **风险**：低

### P1-6 [预留 2.0.30] 导出方法按项目框架版本适配（EasyExcel / EasyExcel2）
- [ ] 完成
- **背景（GATE-A 答复衍生）**：用户确认导出默认生成，但不同项目框架版本的导出调用方式不同
- **现状证据**：
  - 当前模板 BaseQueryTypeController.ftl:65-87 为旧写法：先 `service.query...List(params)` 取 rows，再 `EasyExcelUtils.writeExportExcel(response, date, header, field, fieldtype, rows, fileName, params)`——与 DengqiMes 一致（DengqiMes 全项目零 EasyExcel2Utils）
  - HaichengMes（新框架）为 `EasyExcel2Utils.writeExportExcel(response, fileName, header, field, fieldtype, fileName, orderService, "queryOrderPreSchedule", params)`（haicheng aps OrderController.java:762-764）：传 **service bean + 查询方法名**，由导出框架重查数据（流式导出形态），且需注入 `EasyExcel2Utils` 字段（OrderController.java:65）；Haicheng 全项目 179 文件用 EasyExcel2Utils，仅 5 个残留旧写法
  - 两种写法**结构不同**（是否先查 rows、传参表、需注入的 bean），不只是类名替换
- **改动点**：
  1. Controller 模板 export 方法拆成两个 FreeMarker 宏/变体（easyexcel 旧 / easyexcel2 新），由 dataModel 的 `exportFramework` 选择；easyexcel2 变体含 `@Resource private EasyExcel2Utils ...` 注入与方法名传参
  2. 默认**自动探测**：`JavaPsiFacade.findClass("com.zhiyin.service.excel.EasyExcel2Utils", moduleScope)`（用户触发动作允许索引查询；EDT/后台的 smart mode 判定照 DatabaseConnectionFinder.java:37-41 模式）——探测到即用新写法，探测不到用旧写法
  3. UI 加「导出框架」下拉（自动 / EasyExcel 旧 / EasyExcel2 新），默认「自动」，可手工覆盖
  4. 做前先反编译核对 EasyExcel2Utils.writeExportExcel 完整签名与重载（mes-jar-decompile skill，Haicheng 的框架 jar），不臆造参数
- **验收标准**：
  - DengqiMes 生成旧写法，产物 diff 基线零变化
  - HaichengMes（aps 模块）生成新写法：import/注入/方法名传参正确，编译通过
- **测试**：需真实验证（两个项目各一次，Haicheng 侧产物放工作副本编译验证后还原）
- **风险**：中低；签名细节靠反编译核对兜底
- **依赖**：P1-4（import/export 的模板条件结构先行）

### P1-7 [预留顺延] 生成链路 EDT 慢操作断言修复（i18n 反查索引查询）✅（2026-09-16，2.0.27 与 P1-2 同版）
- [x] 完成
- **执行记录**：两处索引查询移出 EDT——① i18n 反查批量化（新增 `MyPropertiesUtil.findModuleDataGridI18nPropertiesByValueBatch`，EDT 走 runReadAction / 后台走 runReadActionInSmartMode 双分支，逐值调用原方法命中语义不变）；② **连接发现**（DatabaseConnectionFinder）从 `fetchFieldsFromDatabase` 的 invokeLater 移入 Task.Backgroundable 预取、onSuccess 弹窗——②是第一轮验收实测漏网项（19 组 SlowOperations 断言全来自 DataModelGenerator.java:252 连接发现，i18n 反查本身零断言）。第二轮验收 ✓：全链路（从数据库读取 + 一键生成）Slow operations 断言 0。基线 diff 6/7 字节级一致；Layout 差异定性为**基线自身采集缺陷**（走「DDL 粘贴」路径 comment 归属错位：maintainer←地址等；DB 直读产物逐字段正确，`备注`→ordergrid.note 命中两轮复现，i18n 语义不变）。`grep allowSlowOperations src/` 仅剩 FeignClientRelatedItemLineMarkerProvider:42 活跃调用。**附带发现（已定夺 2026-09-16）**：① ShowTableStructureAction.java:109 同模式 EDT 连接发现（表结构查看，非生成链路）→ 并入 P1-3 顺手修；② 「DDL 粘贴」comment 归属错位疑似 TableParser/UI 层缺陷 → 立 P1-9 专项核查。
- **现状证据**（2026-09-15 P0-1 基线采集时实际撞出，用户报内部错误弹窗，堆栈已核）：`DataModelGenerator.generateDataModel`（:543，一键生成按钮 EDT 链路）→ `CodeGenerateService.generateBaseQueryTypeFile:312` → `MyPropertiesUtil.findModuleDataGridI18nPropertiesByValue:475-476` 在 EDT 上跑 `FilenameIndex.getVirtualFilesByName` + `FileTypeIndex.getFiles`（`runReadActionInSmartMode` 内），触发 `SlowOperations.assertSlowOperationsAreAllowed` 断言——IU-2024.3.5 沙箱实测弹「Slow operations are prohibited on EDT」内部错误；:474 的 `SlowOperations.allowSlowOperations` 包装被注释掉。断言只记录不中断，产物正常，但每次生成都弹内部错误，且索引查询阻塞 EDT。
- **改动点**：
  1. i18n 反查移出 EDT：生成前在后台预查所有字段的 i18n 命中结果（`Task.Backgroundable`，参照 P1-2 模式），EDT 只消费结果；或至少恢复 `SlowOperations.allowSlowOperations` 包装消掉断言弹窗（治标，二选一以前者为佳，可与 P1-2 的后台化一起做）
  2. 消除后 `grep -rn "allowSlowOperations" src/` 应有明确语义（不再是被注释的死代码）
- **验收标准**：runIde 沙箱生成全程零内部错误弹窗；基线产物 diff 零变化（i18n 命中结果不变）
- **测试**：runIde 验证 + 基线 diff
- **风险**：低中；线程切换后 i18n 结果传递需保持生成顺序语义（columns 列表逐字段 put）
- **关联**：P1-2（同为 EDT 阻塞治理，建议同版实施）；`docs/codegen-baseline/README.md` 已记录现象

### P1-8 [预留顺延] JDBC 连接 SSL 兼容回退（老 MySQL yaSSL × JDK17）
- [ ] 完成
- **现状证据**（2026-09-16 P1-2 复现取证实锤）：MySQL 5.7.26（yaSSL，仅支持 TLSv1/1.1）× JDK17（TLSv1/1.1 默认禁用）× Connector/J 8.2.0 默认 sslMode=PREFERRED → 服务端直接掐断握手（`SSLHandshakeException: Remote host terminated the handshake`），即 P1-1 附带发现的「选对 dev 连接仍静默失败」根因；mysql CLI 不走 TLS 故命令行手测正常。jshell 同驱动实测 `sslMode=DISABLED` 可连（server 5.7.26）；`enabledTLSProtocols=TLSv1,TLSv1.1,TLSv1.2` 救不回来（JDK disabledAlgorithms 层过滤）
- **改动点**：
  1. DatabaseMetadataUtil 建连捕获 SSL 握手类异常（异常链含 SSLHandshakeException / "SSL" 关键字）后，url 追加（或覆盖）`sslMode=DISABLED` 自动重试一次；重试成功 LOG.warn 记录走了明文回退；仍失败才上抛原异常
  2. 重试仅限插件侧建连（内网 dev 库场景），不改用户项目配置
- **验收标准**：对 DengqiMes dev（192.168.116.9，MySQL 5.7.26）**无需改 properties** 即可读取成功；新 MySQL（TLSv1.2+）连接行为不变（不走回退）
- **测试**：runIde 验证（先还原 DengqiMes 临时配置再测）
- **风险**：低
- **决策记录**：2026-09-16 用户确认走代码侧回退（非仅配置侧）；过渡期已在 DengqiMes dev 三个 properties（iot application-dev / config app-dev / txmanage application-dev）本地追加 sslMode=DISABLED，本项上线后可还原

### P1-9 [预留顺延]「DDL 粘贴」comment 归属错位专项核查（TableParser/UI 层）
- [ ] 完成
- **现状证据**（2026-09-16 P1-7 第二轮验收发现，来源 P1-7 执行记录附带发现②）：P0-1 基线经「DDL 粘贴」路径采集，产物中 comment 归属错位（`maintainer` 拿到地址类注释等）；同表走「从数据库读取」路径逐字段正确（`备注`→ordergrid.note 两轮复现）→ 缺陷在 DDL 粘贴解析链路（TableParser.parseCreateTable 或 UI 字段表填充层），非模板生成层
- **改动点**：
  1. 先核查定位：用 `docs/codegen-baseline/input/` 存档 DDL 走粘贴路径，比对 TableParser 解析出的字段↔comment 映射与 DB 直读结果，锁定错位环节（解析层 or UI 填充层），结论带 file:line 证据落回本节
  2. 依核查结论补修复：解析层缺陷 → 修 `parseCreateTable` 并把错位 case 补进 P0-2 单测护栏；UI 填充缺陷 → 修填充顺序
  3. 修复后同表双路径（粘贴 vs DB 直读）diff 验证一致，并重采基线 Layout（现基线 Layout 含错位数据，P1-7 已定性为基线自身采集缺陷）
- **验收标准**：核查结论落本节（含证据）；若修复：同表两路径产物 comment 映射一致 + 单测覆盖错位 case + 基线 Layout 重采归位
- **测试**：TableParser 单测 + runIde 双路径同表 diff
- **风险**：低（核查先行；生成主链路不受影响——DB 直读路径已正确）
- **关联**：P0-1（基线）、P0-2（单测护栏）、P1-7（发现来源）

---

## 阶段 2：生成能力增强（收尾升 2.1.0）

### P2-1 [预留 2.1.0 前置] i18n 缺失分析报告（只读，先对口径）
- [ ] 完成
- **目的**：把「key 前缀规则」用 DengqiMes 真实数据确认后再动写文件逻辑（先确认口径再实现）
- **现状证据**：CodeGenerateService.java:312 仅用字段 comment 反查已有 key（findModuleDataGridI18nPropertiesByValue），查不到时布局 Title 退化为裸中文（BaseQueryTypeLayout.ftl:18 `${column.i18nKey!column.chs!column.name}`）；真实项目每模块有 `resources/i18n/datagrid/<mod>.datagrid_{zh_CN,zh_TW,en_US}.properties`，key 形如 `com.zhiyin.mes.app.order.ordergrid.orderno=计划单号`
- **改动点**：
  1. 生成流程中收集未命中 i18n 的字段，生成完成后弹「i18n 缺失清单」对话框：字段名 → 拟生成 key → 拟中文值。本版**只报告不写文件**
  2. 拟生成 key 按规则拼装（规则见 GATE-B）
- **口径 gate（GATE-B）**：已确认（2026-09-15）——key 规则 `<模块 i18n 前缀>.<gridName 小写>grid?.<field>`，DengqiMes order 模块即 `com.zhiyin.mes.app.order.ordergrid.<field>`（前缀不含 dengqi）。P2-1 只读报告仍要跑：用真实数据核对归纳出的前缀映射表，作为 P2-2 的实现输入
- **验收标准**：DengqiMes order 模块真实表生成，缺失清单与人工翻 properties 核对一致
- **测试**：需真实验证
- **风险**：低（只读）

### P2-2 [预留] i18n 三语言 properties 追加生成
- [ ] 完成
- **前置**：P2-1 的 GATE-B 已确认
- **改动点**：
  1. 新建 I18nGenerateService：定位模块三个 datagrid properties 文件（复用 MyPropertiesUtil 的文件定位逻辑），对缺失 key **只追加**（读现有 key 集合，存在即跳过；绝不改动/删除已有行）
  2. zh_CN 值 = 字段 comment；zh_TW / en_US 值 = BaiduTranslator 翻译（插件已有，TableParser.java:117 曾引用），翻译失败则该 key 追加为注释行 `# TODO: translate` 并让布局回退 chs
  3. properties 转义与目标文件现有风格一致（native2ascii 判定复用 MyPropertiesUtil.isNative2AsciiForPropertiesFiles）；写入走 VFS + WriteAction，写后 `VcsDirtyScopeManager.fileDirty` 触发 SVN 刷新（参照 CodeGenerateService.java:455 既有做法）
  4. 布局 Title 的 i18nKey 使用本次生成的 key，闭环（不再依赖「恰好已有同文案 key」）
  5. 追加结果进 P1-1 的汇总通知（三文件各 +N 行）
- **验收标准**：
  - 生成后 diff 三个 properties：只有新增行
  - 同一配置重跑幂等：不重复追加（第二次 +0 行）
  - 翻译失败场景（断网）有 TODO 注释标记，不中断生成
- **测试**：需真实验证 + 单测覆盖（escape 转换、幂等判重、翻译失败降级）
- **风险**：中；编码/转义是主要坑，靠单测锁

### P2-3 [预留] 菜单注册 SQL 草稿
- [ ] 完成
- **前置（GATE-C）**：从 DengqiMes 库或既有上库 SQL 抽「菜单 4 表 + i18n」insert 真实样例（口径见 mes-report-dev / mes-dict-create skill），确定表名、必填列、ID 分配策略，产出模板字段清单交用户确认
- **改动点**：
  1. 新增 menu.sql.ftl：页面菜单 + 按钮权限 + i18n 三语言 insert，ID 位留 TODO 占位
  2. 输出到模块 `src/main/resources/sql/<ObjectName>_menu_draft.sql`，文件头注释「草稿——人工确认 ID 与父菜单后执行」
  3. UI 加「菜单 SQL」复选框，默认不勾
- **验收标准**：草稿字段与 DengqiMes 既有菜单 insert 同构（人工比对 1 条既有记录）；插件不执行任何 SQL
- **测试**：需真实验证（比对既有样例）
- **风险**：低（纯文本产物）

### P2-4 [预留] 约定外置：.zhiyin/codegen.properties
- [ ] 完成
- **现状证据**：模块名特殊映射硬编码（MyPropertiesUtil.java:614-624：system→sysadm、configure/v2→basic，属其他项目口径）；默认隐藏列/超管列在两个模板头部重复硬编码（BaseQueryTypeLayout.ftl:1-3、BaseQueryTypeMoc.ftl:1-3）；查询字段启发式硬编码（DataModelGenerator.java:358-367）
- **改动点**：
  1. 定义 `.zhiyin/codegen.properties` 配置项：`module.folder.mapping.*`（模块名→folder）、`i18n.prefix.*`（模块→key 前缀，服务 P2-2）、`export.framework`（easyexcel/easyexcel2/auto，兜底 P1-6 的自动探测）、`layout.hiddenColumns`、`query.fieldHeuristics`
  2. CodeGenerateConfigService：project 级服务，启动惰性加载 + 文件变更失效（参照 I18nCacheManager 的缓存纪律），未配置时回退现有硬编码默认值
  3. DengqiMes 落一份真实配置（含 sys.dengqi.auth/home 模块的正确落位——GATE-D：先在 DengqiMes 确认 sys 模块 Controller/Moc 实际目录口径）
- **验收标准**：无配置文件时行为与上一版完全一致（回归基线零 diff）；有配置时映射生效
- **测试**：runIde 验证 + 基线 diff
- **风险**：中低；注意配置解析失败要回退默认并告警，不能中断生成

### P2-5 [预留] 类型映射精度增强
- [ ] 完成
- **前置（GATE-E）**：统计 DengqiMes 既有 Moc XML 的 type 取值词表（框架实际支持哪些类型名、长度格式），按真实词表定映射，不臆造；现状 moc.ftl:5 直接输出 `${field.type}`
- **现状证据**：DatabaseMetadataUtil.getType:61-82 把 bigint/decimal(19,4)/tinyint(1)/enum 全部退化为 int/number/string；TableParser.java:64-68 length 只取第一个数字（decimal(19,4) → 19 丢 scale）
- **改动点**：按 GATE-E 词表重写 getType 与 length 提取（保留 scale），DDL→Moc 类型映射表进 P2-4 的配置
- **验收标准**：含 bigint/decimal(19,4)/tinyint(1)/date/datetime/enum 字段的表，生成 Moc 与项目既有 Moc 惯例一致
- **测试**：单测（映射表全覆盖）+ 需真实验证（与既有 Moc 抽样比对）
- **风险**：低

### P2-6 [预留 2.1.0] Imp mapper 导入列定义骨架（可选增强）
- [ ] 完成
- **前置**：P1-4 已上线；Imp mapper 列定义口径见 mes-import-regression skill（Imp*Mapper 列定义读法）
- **改动点**：勾选导入时顺带生成 Imp mapper 列定义骨架（字段→Excel 列映射，中文列头取 comment），接通导入链路
- **验收标准**：勾导入后生成的 Service import 方法不再需要手工配 Imp mapper（骨架可用，具体校验规则仍人工补）
- **测试**：需真实验证（DengqiMes 走一次真实 Excel 导入冒烟）
- **风险**：中；依赖框架 import 机制细节，做前先反编译核对（mes-jar-decompile skill）

---

## 阶段 3：用户操作体验（patch 顺延）

### P3-1 [预留] 字段表编辑体验：checkbox 化 + 批量操作 + 下拉
- [ ] 完成
- **现状证据**：布尔列单击即翻转（DataModelGenerator.java:105-135，:109 注释还写着 Double click），无编辑器；30+ 字段逐格点选；easyuiClass 自由文本（:394-413 启发式默认）
- **改动点**：
  1. is* 布尔列用 JBTable 的 `setDefaultEditor(Boolean.class, ...)` + 复选框渲染器，删除单击翻转 MouseAdapter
  2. 列头右键菜单：本列全选 / 本列反选 / 本列清空
  3. easyuiClass 列改下拉编辑器，枚举：easyui-textbox / combobox / datebox / datetimebox / numberbox / combotree / checkbox（以 DengqiMes 既有 Layout.xml 里出现过的 easyuiClass 集合为准——GATE-F：生成前 grep 一次真实项目确认枚举清单）
- **验收标准**：布尔列点击进编辑态（复选框/空格切换），无误触翻转；列批操作生效；下拉可选不可乱填
- **测试**：runIde 验证
- **风险**：低

### P3-2 [预留] 剪贴板 TSV 粘贴导入字段
- [ ] 完成
- **改动点**：新增「从剪贴板粘贴」按钮：解析剪贴板 TSV（需求文档表格直接复制），按列序映射 name/type/comment（首行是表头则自动跳过；type 缺省 string；type 词表宽容映射 varchar→string 等），粘贴前弹确认（覆盖现有 / 追加）
- **验收标准**：从 Excel 复制 3 列表格粘贴一次成型；脏数据（空行、非法 type）跳过并在确认框提示条数
- **测试**：runIde 验证 + 单测（TSV 解析容错）
- **风险**：低

### P3-3 [预留] 输入合并进主窗体 + 生成前重名校验
- [ ] 完成
- **现状证据**：表名/连接在读取时弹窗问（DataModelGenerator.java:245），GridName/页面名生成时连续两个 showInputDialog（:483-520）
- **改动点**：
  1. 主窗体顶部加表单：表名、GridName、页面名称（页面名默认 GridName）、目标模块（只读展示，来自 Action 上下文）
  2. 删除 :467-474 兜底问表名与 :483-520 两个弹窗；生成前校验表单完整性，错误内联标红而非弹窗
  3. 生成前重名检查：对勾选的每种产物，按目标路径直接查文件存在性（`contentRoot.findFileByRelativePath`，不用索引），已存在者在确认提示中列出，配合 P1-1 的跳过策略
- **验收标准**：一次生成全程 0 个输入弹窗；同名重生成有明确预警清单
- **测试**：runIde 验证
- **风险**：低

### P3-4 [预留] 配置持久化（关窗不丢）
- [ ] 完成
- **现状证据**：DataModelGeneratorAction.java:30 每次 `new DataModelGenerator`，fields/勾选项/SQL 关窗即丢
- **改动点**：
  1. 保存：表单 + 字段表 + 勾选项序列化到 `<project>/.zhiyin/codegen/<tableName>.json`（按钮「保存配置」+ 生成成功后自动保存）
  2. 载入：打开生成器时若 module 下存在当前表名配置，提示「载入上次配置？」
  3. JSON 读写用 platform 自带（Gson/jackson 由 IDE 发行版提供——遵守「未声明外部库靠平台自带 jar」纪律，先查 build.gradle.kts 已声明依赖，缺则显式声明，不裸用未声明库）
- **验收标准**：配置→关窗→重开→载入，字段表（含勾选/编辑值）完整还原；损坏 JSON 提示并忽略，不崩
- **测试**：runIde 验证 + 单测（序列化往返）
- **风险**：低

### P3-5 [预留] 生成确认页 + 单次 undo + 可点击通知
- [ ] 完成
- **前置**：P1-1（结果汇总机制）、P3-3（表单化输入）
- **改动点**：
  1. 点「一键生成」先弹确认对话框：文件清单（类型 → 目标路径 → 新建/已存在将跳过），确认后才写盘
  2. 全部产物收进**一个** WriteCommandAction（现在每文件独立，CodeGenerateService.java:443），一次 Ctrl+Z 整体撤销；SVN 侧全部新文件归入一个 changelist
  3. 成功通知加超链接（Notification Listener → FileEditorManager.openFile 直达首个产物，如 Controller）
- **验收标准**：确认页路径与实际落盘一致；生成后单次 undo 撤销全部新文件；通知点链接打开文件
- **测试**：runIde 验证
- **风险**：中；单 WriteCommandAction 里逐文件失败的部分回滚语义要在实现前确认（WriteCommandAction 异常时是否整体回滚——查平台文档，拿不准就退回逐文件+统一 changelist 方案）

### P3-6 [预留] formatSql 增强
- [ ] 完成
- **前置**：P0-2 的 formatSql 快照断言已就位
- **改动点**：CodeGenerateService.formatSql:492-533 现纯正则换行，子查询/case when 会被切碎（:511 的 AND/OR 规则命中嵌套条件、:517 逗号断言对多层括号误判）。改为括号深度感知的简易 formatter：深度 0 才换行 AND/OR/JOIN，逗号按深度处理；更新并新增快照断言（含子查询、case when、concat 样例）
- **验收标准**：嵌套子查询 SQL 生成到 Mapper 后换行/缩进正确（人工比对）；既有基线 SQL 输出不变或差异在断言中显式更新
- **测试**：单测为主 + 需真实验证（一条真实复杂报表 SQL）
- **风险**：低

---

## 阶段 4：结构性重构（升 2.2.0）

### P4-1 [预留 2.2.0] JFrame → DialogWrapper 迁移
- [ ] 完成
- **现状证据**：DataModelGenerator.java:39,64 裸 Swing JFrame——不吃 IDE 主题/字体、可同时开多份、project 关闭窗口不随之释放（持有 project/module 引用）；SelectDatabaseConnectionDialog.java:12 裸 JDialog
- **改动点**：
  1. DataModelGenerator 改继承 `DialogWrapper`（可缩放 + 记住尺寸），内容面板迁移；Action 侧 `new ... .show()`
  2. SelectDatabaseConnectionDialog 迁移 DialogWrapper；P1-5 的改造直接在新壳上做
  3. 窗口随 project 关闭释放（DialogWrapper + project dispose 自动处理）
- **验收标准**：单实例模态、跟随 IDE 主题（深色模式检查）、project 关闭后再开不残留旧窗口；全部功能回归（字段表操作、三种读取、生成）
- **测试**：runIde 全功能回归 + 基线产物 diff
- **风险**：中高；纯迁移不改逻辑，靠阶段 0 基线兜底

### P4-2 [预留 2.2.1] 数据模型收敛 + 死代码清理
- [ ] 完成
- **现状证据**：fields 全程 `List<Map<String,Object>>` 字符串键（"isQueryField" 布尔靠 `"true".equals(...)` 比较，DataModelGenerator.java:431-433）；已有 Kotlin Field data class 但只在编辑弹窗用（:425）；dialog-field 布局逻辑三处复制（CodeGenerateService.java:97-126、230-258、333-363 注释掉一份）；死代码：GenerateXmlAction（plugin.xml:258 已注释注销，硬编码 Student demo）、generateModelByFields 无人调用（DataModelGenerator.java:521 已注释）、BaseQueryTypeMoc.ftl 无任何引用
- **改动点**：
  1. 字段模型统一为 data class（表格、编辑弹窗、模板入参共用），Map 仅在 FreeMarker dataModel 边界转换
  2. 三处 dialog-field 布局逻辑收敛为单一 builder 私有方法
  3. 删 GenerateXmlAction.java、generateModelByFields、BaseQueryTypeMoc.ftl（删前 grep 确认零引用，含 plugin.xml 与 ftl include）
- **验收标准**：编译零警告新增；基线产物 diff 零变化（纯重构）；死代码文件删除后全量编译通过
- **测试**：runIde 回归 + 基线 diff + `grep -r "GenerateXmlAction\|BaseQueryTypeMoc" src/` 零命中
- **风险**：中；靠基线兜底

### P4-3 [预留 2.2.2] 多 grid 主从页（1 主 + 1 从）
- [ ] 完成
- **前置**：P4-2 数据模型收敛
- **现状证据**：layout.ftl / BaseQueryTypeLayout.ftl 本身是 `dataGrids` 列表结构（BaseQueryTypeLayout.ftl:6 `<#list dataGrids as grid>`），真实 Controller 走 createMultiGridPatternView（DengqiMes OrderScheduleViewController.java:35），但 UI 只支持单 grid
- **改动点**：
  1. UI 字段表加 grid 页签（主表 + 从表），每 grid 独立字段编辑；从表 SQL 单独输入
  2. dataModel 传双 grid；Controller/Html/Layout 模板适配主从（从表 URL 挂主表 `/query...List` 之下，按 DengqiMes 一张真实主从页比对口径——GATE-H：选一张真实主从页作为模板对照）
- **验收标准**：对照 GATE-H 真实页面，生成的主从页结构同构（人工比对 + 真机打开页面渲染正常）
- **测试**：需真实验证（页面能打开渲染）
- **风险**：高（模板与 UI 双改）；做前先在计划外开设计小节确认

### P4-4 [预留 2.2.3] 数据维护（CRUD）模式补齐
- [ ] 完成
- **现状证据**：单选框被禁用（DataModelGenerator.java:179-181）；moc.ftl/layout.ftl 的 dialog 生成链路存在但不可达；Dao/Mapper 缺增删改模板
- **改动点**：
  1. 启用「数据维护」单选，接通 generateMocFile + dialog 字段布局（P4-2 收敛后的 builder）
  2. 新增 CRUD Dao/Mapper/Service 模板（insert/update/deleteById/deleteBatch，参照 DengqiMes 一张真实维护页的 Mapper 口径）
  3. 字段表已支持 isRequired/isDialogField/isEditHidden 列，直接生效
- **验收标准**：选数据维护生成一套，页面新增/编辑/删除走通（真机冒烟）
- **测试**：需真实验证（完整 CRUD 冒烟）
- **风险**：高；与 P4-3 一样先出设计小节

---

## 待用户确认的口径 gate 汇总

| Gate | 内容 | 阻塞项 | 状态 |
|------|------|--------|------|
| GATE-A | 导出方法默认生成还是做成开关 | P1-4 | ✅ 已确认：导出默认生成；框架版本适配独立成 P1-6 |
| GATE-B | i18n key 前缀/后缀规则 | P2-1 → P2-2 | ✅ 已确认：`<模块 i18n 前缀>.<gridName 小写>grid?.<field>`；P2-1 保留只读核对 |
| GATE-C | 菜单 4 表 insert 真实样例与 ID 策略 | P2-3 | 待办（P2-3 开工时归纳） |
| GATE-D | DengqiMes sys 模块（sys.dengqi.auth/home）Controller/Moc 实际目录口径 | P2-4 | 待办（P2-4 开工时核对） |
| GATE-E | Moc XML type 词表（统计真实项目既有值） | P2-5 | 待办（P2-5 开工时统计） |
| GATE-F | Layout 中实际出现的 easyuiClass 枚举清单 | P3-1 | 待办（P3-1 开工时 grep） |
| GATE-G | 传统 Java Web 验证项目路径 | P1-3 | ✅ 已确认：`E:\view\SplashMes\webproj\com.zhiyin.mes.splash.project` |
| GATE-H | 主从页模板对照页（选一张 DengqiMes 真实主从页） | P4-3 | 待办（P4-3 开工时选） |

## 里程碑与依赖

```
阶段0 ──→ P1-1 ──→ P1-3 / P3-5（依赖结果汇总机制）
              ├──→ P1-2 ──→ P1-5（连接对话框同域）；P1-7 与 P1-2 同域，建议同版
              ├──→ P1-4 ──→ P1-6（模板条件结构先行）──→ P2-6（导入链路）
阶段0 ──→ P2-1(GATE-B 已确认) ──→ P2-2 ──→ P2-4（i18n 前缀进配置）
P2-3 / P2-5 独立，可与 P1 并行
P1-8 / P1-9 独立（P1-9 核查型，结论反哺 P0-2 单测与基线重采）
阶段3 各项独立（P3-5 依赖 P1-1+P3-3）
阶段4 串行：P4-1 → P4-2 → P4-3 / P4-4
```

## 工作量预估

| 阶段 | 循环数 | 预估 |
|------|--------|------|
| 0 | 并入首个发版 | 0.5-1 天 |
| 1 | 8 | 6-8 天 |
| 2 | 6 | 5-8 天 |
| 3 | 6 | 4-6 天 |
| 4 | 4 | 6-10 天 |
