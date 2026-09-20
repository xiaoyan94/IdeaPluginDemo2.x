<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IdeaPluginDemo2.x Changelog

## [Unreleased]

## [2.1.2] - 2026-09-20

feat: 生成器支持从剪贴板粘贴 TSV 导入字段（codegen 改造 P3-2）——需求文档/Excel 表格直接复制进字段表

- **新增「从剪贴板粘贴字段」按钮**（读取按钮组末位）：解析剪贴板 TSV，按列序映射 name/type/comment（第 4 列及以后忽略——需求表常带「必填」等附加列）；需求文档表格直接复制即可建字段表，不再依赖 DDL/DB
- **表头自动跳过**（只看第一个非空行）：col1 非合法标识符（如「字段名」「序号」）判表头；col1 是标识符但命中表头关键词集时须 col2 不是合法类型词才判表头——`name/type/comment` 跳过而真字段首行 `name/varchar/名称`、`type/int/类型` 不误杀；前导空行计数后再判表头
- **type 词表宽容映射**：空缺省 string；剥括号参数后按 `TableParser.getType` 词表映射（varchar→string、timestamp/time→datetime、tinyint/bigint→int 等）；**非法 type（如「文本」）不靠 getType 兜底**——TSV 校验自带显式 22 词合法词表（getType 未知一律落 string，无法区分合法与非法）；括号参数提取为 length（`varchar(64)`→64、`decimal(19,4)`→"19,4" 沿用 P2-5 scale 语义）
- **粘贴前确认（覆盖现有/追加/取消）**：确认框展示有效字段数、表头跳过、脏数据计数（空行/非法分列，无脏数据不显示 0 条噪音）；覆盖=清空后导入（对齐 DDL 路径）、追加=保留现有末尾追加、取消不动
- **脏数据容错**：空行/非法行（name 非法标识符或 type 不在词表）跳过并计数、不中断整体解析；末尾换行的空尾行不算脏数据；全脏数据时错误提示含各计数
- 解析器独立成 `TsvFieldParser` 纯函数类（无 UI 依赖）；name 小写化对齐 parseCreateTable 出口；不预填 is*/easyuiClass——留给 updateTableModel 既有启发式，与 DDL/DB 路径行为一致
- 单测 100→115（TsvFieldParserTest 15 例：表头三形态/防误杀边界、type 缺省与词表边界、空行/CRLF/尾行、单列、第 4 列忽略、全量脏数据）；runIde 沙箱验证通过（HaichengMes order 模块：标准 3 列覆盖导入一次成型、脏数据确认框计数逐字符合、追加/取消路径、布尔与 easyuiClass 启发式生效；插件 ERROR=0、SlowOperations 断言 0）

## [2.1.1] - 2026-09-20

feat: 生成器字段表编辑体验升级（codegen 改造 P3-1，阶段 3 首项）——布尔列复选框化、列头右键批操作、easyuiClass 下拉

- **布尔列（isColumnField/isQueryField/isDialogField/isRequired/isEditHidden）复选框化**：表格模型按列名精确派发 Boolean 列类（`BOOLEAN_COLUMNS` 集合取代旧 `startsWith("is")`），平台默认复选框渲染 + `DefaultCellEditor(JCheckBox)`（单击进编辑态、复选框点击/空格切换，翻转全部走编辑器生命周期）；**删除旧「单击任意次直接翻转」MouseAdapter**——旧实现对选格等任意单击都直接 setValueAt 翻转，是误触翻转来源
- **布尔值归一**：DDL/DB 解析路径的 is* 值为 String "true"/"false"、启发式与编辑回写为 Boolean 两形态混合，布尔列声明 Boolean 列类后混合形态渲染异常——UI 表格层（updateTableModel/setValueAt）统一归一 `normalizeBoolean`（纯函数），解析层 TableParser 输出不动（P0-2 快照锁定）；下游消费点逐一核对语义不变（`"true".equals(toString())` 对 Boolean 兼容，layout.ftl 布尔上下文反而从潜在 String 异常改善为严格正确）
- **列头右键批量操作**：布尔列列头右键弹「本列全选 / 本列反选 / 本列清空」，对全部数据行生效（30+ 字段逐格点选成为历史）；先 cancelCellEditing 防编辑器回写覆盖；视图列经 convertColumnIndexToModel 判定（防列拖拽换序误判）；非布尔列不出菜单
- **easyuiClass 改下拉编辑器**：枚举按 GATE-F 真实词表（grep DengqiMes 既有 Layout.xml `easyuiClass="easyui-xxx"`，src 侧排除 target：combobox 1940/datebox 824/textbox 596/datetimebox 152/numberbox 115/timespinner 10/validatebox 7/filebox 2/checkbox 1，按频次降序）+ 首项「（空）」（启发式对 int+id 等返回空串，空值合法常用）；combo 非 editable 只能选不能乱填；计划原文枚举里的 combotree 零出现不收。按列挂编辑器（TableColumn.setCellEditor），不影响 name/type/length/comment 默认文本编辑
- 文本列随之回归 Swing 默认编辑时机（双击/F2，旧 MouseAdapter 的单击 editCellAt 分支同删）
- 单测 94→100（normalizeBoolean 边界 4 例、GATE-F 词表精确断言、BOOLEAN_COLUMNS 集合守卫）；runIde 沙箱验证通过（HaichengMes order 模块 biz_base_factory 30 字段：结构断言 16 项、真实鼠标事件单击进编辑+勾选切换+无误触翻转、批操作全选/反选/清空逐项生效、下拉选值即提交且「（空）」落空串；插件 ERROR=0、SlowOperations 断言均归因平台 fileIndex 层）

## [2.1.0] - 2026-09-20

feat: 勾选 Excel 导入时追加生成 Imp mapper 列定义骨架 + 导入定义 SQL 草稿（codegen 改造 P2-6，阶段 2 收尾升 minor）——导入链路从「生成即断」到「骨架开箱可用」

- **框架导入机制取证后按真实链路配套**（反编译 ExcelImportService + dengqimesv3 库实证）：导入链路 = Imp mapper 列定义 XML（`WEB-INF/etc/business/<Folder>/`）+ DB 配置行 `utils_base_data_import_define`（无行 getImportMapper 直接抛「尚未定义」）+ 物理临时表（insertTempDataByExcel 直接 insert）三件缺一不可——只生成 XML 链路仍断，故本版配套生成 SQL 草稿（插件仍不执行任何 SQL，与 P2-3 口径一致）
- **Imp mapper 骨架**（`ImpMapper.ftl`）：业务字段全进（排除 id/factoryid/useflag/maintainer/maintaintime/creator/createtime/delflag/version 9 个框架审计列）、col 连续 1..N、name 小写（消费端 toLowerCase）、description=comment（空则省略，真实样例同款）、`required="true"` 映射字段表必填列（解析期自动剔空行报错，EasyExcelUtils 反编译实证）、`i18n` 属性=comment 在模块**非 datagrid** i18n 资源按值反查命中 key（复用 `findModuleI18nPropertiesByValue` 范围），**未命中兜底 i18n=中文名**（框架 getMessage 查不到 key 返回原串，等效中文列头；2026-09-20 口径更新），无 comment 字段仍省略（真实样例同款）；传统 Java Web 项目走 Moc 同款 META-INF 分支（P1-3 双写不回归）
- **导入定义 SQL 草稿**（`import.sql.ftl` → `sql/<ObjectName>_import_draft.sql`）：`CREATE TABLE IF NOT EXISTS temp_imp_*`（列序/框架列/引擎字符集逐列镜像真实 temp_imp_exception；列长默认 varchar(255)、DDL 长度>255 取 DDL 长度）+ 幂等 INSERT 配置行（id 动态取 MAX+1、UNIQUE DataModelCode NOT EXISTS 判重，P2-3 同款）；表名与 XML 同一变量渲染；DataModelName 复用同次生成的菜单中文名、无则 `<ObjectName>导入` 兜底
- Service 模板 TODO 改指向草稿 SQL（temp→biz upsert 仍人工补，dao import 注释不动）；导入复选框文案改「Excel 导入（含 Imp mapper 骨架）」
- **i18n 反查线程纪律**：新增 `findModuleI18nPropertiesByValueBatch`（EDT runReadAction / 后台 runReadActionInSmartMode 双分支），预查在既有后台 Task 内完成、EDT 只消费 paramsMap 结果——不在 EDT 新增 FilenameIndex/FileTypeIndex 调用（P1-7 断言教训）
- 快照护栏抓出并修复 1 个真实模板 bug：FreeMarker 默认数字格式千分位会把 `varchar(1024)` 渲染成 `varchar(1,024)`，列长改 `?c` 计算机格式
- 单测 86→94（新增 ImportSkeletonTemplateGoldenTest 8 例：排除列/required/i18n 三形态、temp 表名推导幂等、SQL 草稿列长映射与 XML 同表名、幂等 INSERT 双分支）；默认组合（不勾导入）既有 golden 零漂移红线锁定
- 验证：编译 + 全量单测 94 例 0 失败；runIde 沙箱真实验证通过（HaichengMes order 模块 biz_base_factory 生成 9+1 件落位与内容逐项核对、i18n 命中/兜底/省略三形态、生成模块 `mvn compile -P central,dev` 通过、生成全程插件 ERROR=0）；Excel 导入端到端冒烟（执行草稿 SQL→xlsx 导入→临时表落行）待补（用户决定先发版，发现问题随下版修）

## [2.0.38] - 2026-09-18

feat: 代码生成器类型映射精度增强（codegen 改造 P2-5）——Moc 类型/长度按真实项目词表映射，decimal 保留 scale、enum 生成字典属性

- **DDL→Moc 类型映射按 GATE-E 真实词表重写**（`TableParser.getType`，DengqiMes 1245 个既有 Moc XML 统计定案，不臆造）：bigint/tinyint 等 int 族→int（bigint 主键既有惯例 1109/1113 为 int）、decimal→decimal（不再折算 number）、date 独立成类（不再混入 datetime）、timestamp/time→datetime、float/double→float、char/text 族→string、未知类型兜底 string；输出一律小写。DB 直读与 DDL 粘贴两路径 P1-2 后本就同源 TableParser，本次天然归一；正则兜底路径同款修复，双路径产物一致不变量维持
- **decimal 保留 scale**：`decimal(19,4)` 生成 `length="19,4"`（既有 Moc 16 例同形态实证），修正旧版丢 scale 只存精度
- **enum 字段生成 `enum="字段名"` 属性**（459 例 enum 字段 100% 带 enum= 实证，字典 code 默认字段名、可手改）——旧版完全不生成，与既有 Moc 惯例不一致
- **随版修两处编辑链路回归**：① 编辑 decimal(P,S) 行 `Integer.parseInt("19,4")` 崩溃（旧版对 datetime 等空串 length 同样会崩）——对话框打开取精度部分、确认未改长度时原样带回 "P,S"，scale 不因编辑往返丢失；② 编辑回写白名单式重建丢 enumRef——原 map 含该键则透传
- easyuiClass 启发式联动：decimal/float 字段配 easyui-numberbox（decimal 不再折算 number 后的直连分支补齐）
- 删死代码 `DatabaseMetadataUtil.getType`（P1-2 后零调用，删前 grep 复核）
- 基线零漂移程序化锁定：新增 MocTemplateGoldenTest——真实基线 DDL→新解析→新模板渲染与基线 Moc 件逐字节相等，int/datetime 不输出 length 红线断言；单测 73→86（TableParserTest 词表全覆盖 + 双路径一致、DataModelGeneratorTest 纯函数 6 例）
- 验证：编译 + 全量单测 86 例 0 失败；真实项目 DDL 粘贴全类型核对通过（bigint→int、decimal 保留 scale、enum 带 enum=、date/datetime/timestamp/float/tinyint 归位、编辑往返不崩不丢）

## [2.0.37] - 2026-09-18

fix: Ctrl+Alt+Shift+\ URL 搜索首次必空、须手动 Reset 才能搜到——启动扫描空集锁死修复

- **根因——启动扫描可在 dumb 窗口"成功"返回空 map 并锁死缓存**：`ControllerScanner.scheduleScan` 的 NBRA 链缺 `.inSmartMode(project)`，`runWhenSmart` 触发到线程池真正执行之间若再次进入 dumb（启动后首轮 smart 恰是 Maven import/索引补扫高发窗口），`performScan` 的 `isDumb` 早退返回空 map，被当作扫描成功结果 `replaceCache` 锁进缓存，无 TTL 无重试；与 2.0.20 ComboboxUrlService 空集锁死同类
- **自愈缺失**：2026.2 起 URL 搜索切工具窗口（`NewControllerToolWindowUI.doSearch`）只读快照，空 → 弹「未找到」即结束；旧 Search Everywhere 路径空结果时自动重扫+重搜的自愈（`UrlSearchEverywhereContributor`）随切换丢失，导致只能手动 Reset 解锁（Reset 时索引早已完成，重扫拿到真实结果）
- **修复**：① `ControllerScanner` NBRA 补 `.inSmartMode(project)`——dumb 期不执行、smart 后自动重跑，从源头消灭 isDumb 早退空结果；② `doSearch` 空缓存自愈——搜索命中空结果且缓存为空时提示并自动 `startScan(true)`，完成后重搜一次（防递归 flag），兜底依赖未导入完等其余空锁场景；缓存非空只是无匹配时仍走正常「未找到」分支
- 已知边界：自愈触发时若恰有扫描在途（`scanning` 占用），回调被静默丢弃需再按一次 Enter，正常场景不受影响
- 验证：编译通过；完整链路待真实 MES 项目验证（重启 IDEA 直接搜索应可搜到、全程无需 Reset）

## [2.0.36] - 2026-09-18

fix: Mapper 方法 Ctrl+B 弹窗同一目标偶现重复 N 次 + 表结构右键菜单首弹卡顿，两笔随版修复

- **Ctrl+B 重复目标根因——缓存盲加不去重**：`MyProjectService` 的 `xmlFileMap`/`mocFileMap` 启动全量扫描加载 1 次，之后每次保存 mapper XML（`VFileContentChangeEvent`）`MyXmlFileListener` 再向同 namespace 的 List 盲 append 1 条；`MyJavaMethodReference.multiResolve` 按缓存条数展开导航目标——保存 3 次即弹窗 4 条相同 Mapper 标签（偶现取决于自上次 reload 后保存次数）。`cacheToMap`/`cacheMocToMap` 改为按 VirtualFile 先驱逐后加的幂等合并，且 compute 内构建新 list 整体替换（copy-on-write）——顺带消除后台扫描线程改普通 ArrayList 与 EDT 导航读并发的修改风险
- **伴生修复（同根因）**：`reInitXmlFileMap` 补 `mocFileMap.clear()`（原先漏清，Moc 引用同样累积）；`MyXmlFileListener` 补 `VFileDeleteEvent`（驱逐）/`VFileMoveEvent`（驱逐+重加）两个 TODO，删除/移动 mapper 文件不再残留 invalid 陈旧目标；VFS 订阅裸 `connect()` 改 `connect(project)` 对齐生命周期规范
- **ShowTableStructureAction 右键菜单首弹卡 338ms**：`getActionUpdateThread` EDT → BGT——update 首次触发 `MyBundle` 冷加载（jar 解压 + ResourceBundle），放 EDT 会卡右键菜单弹出；BGT 下 update 由平台包在 read action 里执行，读选区文本线程安全
- 验证：编译通过；Ctrl+B 修复待真实环境验证（同一 mapper XML 保存多次后字符串方法名 Ctrl+B 弹窗恒 1 条）

## [2.0.35] - 2026-09-18

feat: 菜单注册 SQL 草稿（codegen 改造 P2-3）——勾选后随生成产出四表幂等 insert 草稿，插件不执行任何 SQL、不连库

- **GATE-C 口径（dengqimesv3 真实数据归纳）**：一条查询页菜单 = `sys_menu` 1 行（code=页面名、useflag=1、icon-blank、i18ntype='zh_CN'、functionurl=./<folder>/<ObjectName>）+ `sys_menu_fun` 按钮行（ID=FID 全局号、PID=菜单id、GROUPNAME=ToolBar、pagei18nid=661）+ `sys_res_i18n` 1 行（KEY=`com.zhiyin.mes.menu.<蛇形名>` 保留字反引号、TYPE=menu、maintainer='zhiyin'）+ `sys_res_i18n_type` 三语言 3 行（zh_CN/zh_TW/en_US，ID 与 sys_res_i18n 共享全局号段）
- **按钮集合跟勾选项联动**：默认仅刷新(i18n 75)+导出(80)；勾「Excel 导入」加导出(模板)(84)+导入(454)——公共按钮 i18n id 全部复用全局既有值，草稿零新增按钮 i18n 行，与生成产物的 import/export 能力精确对齐
- **幂等取号 SQL**：各表 ID 位不用字面 TODO、不连库取号，而是 MySQL 用户变量查当前 MAX+1（i18n 起点 GREATEST(两表 MAX)+1 对应共享号段）；防孤儿设计——fun 行 PID 经 `FROM sys_menu` 按 code 反查真实菜单 id + NOT EXISTS 按 PID+CODE 判重，type 行 PID 经 sys_res_i18n 按 KEY 反查 + 按 PID+TYPE 判重，重跑自动跳过已插入行；父菜单 pid 与 seq 留 `/* TODO */` 注释人工填
- **交互**：UI 加「菜单 SQL（草稿）」复选框（默认不勾）；勾选后生成时新增「菜单中文名」输入（sys_menu.name 与 zh_CN 值），取消输入整体中止；菜单名 zh_TW/en_US 复用百度翻译后台自动回填（失败渲染 TODO 形态不中断）；输出到模块 `src/main/resources/sql/<ObjectName>_menu_draft.sql`（目录不存在自动创建），文件头注明草稿语义；结果并入 P1-1 汇总通知
- **随版修复（HaichengMes runIde 验收实证）**：`VfsUtil.createDirectoryIfMissing` 建目录属 VFS 写操作，EDT 上裸调抛 Write access 断言被 catch 吞成「无法生成件」弹窗——照 MyProjectService 先例包 `WriteCommandAction.writeCommandAction().run()`（2024.3 实测该 API 不自带 write action）
- 单测 68 → 73（MenuSqlTemplateGoldenTest 5 例：默认/勾导入按钮集 golden、翻译缺失 TODO、幂等判重形态、驼峰转蛇形），全量 0 失败
- 验证（HaichengMes order 模块，biz_base_factory，GridName=BaseFactory，菜单中文名=工厂台账）：草稿与 DengqiMes 既有菜单 insert 逐字段同构（人工比对 PartWarehouse 真实记录）；zh_TW/en_US 翻译回填（工廠台賬/Factory ledger）；P2-2 确认追加流程共存正常；验证产物已清理（用户设备OEE 业务改动保留未动）

## [2.0.34] - 2026-09-18

feat: i18n 三语言 properties 追加生成（codegen 改造 P2-2）——缺失 key 确认后写入模块三语言 datagrid properties，布局 Title 闭环，随版修复 i18n 缓存不含未保存追加内容

- **确认后追加（口径已确认）**：生成前缺失清单升级为可编辑确认对话框（`I18nAppendConfirmDialog`，替换 P2-1 只读报告）——字段名/拟生成 key 只读，zh_CN（预填 comment）/zh_TW/en_US（预填百度翻译）三列可编辑；「追加并生成」写 properties + 布局 i18nKey 用新 key 闭环，「直接生成」/Esc/关闭保持裸中文回退；翻译后台 Task 串行调 BaiduTranslator（auto→cht/en），开头连续 3 字段失败快速放弃（断网不等几十次超时），翻译失败格可手填、确认时仍空则该语言文件追加 `# TODO: translate: <key>` 注释行

- **只追加绝不修改**：按 key 判重（后台预读 key 集合 + 写时 findPropertyByKey 双查），已存在跳过，重跑 +0 行；TODO 注释行按文件文本判重幂等；写入走 PSI addProperty + WriteCommandAction，写后 fileDirty + VFS 刷新 + I18nCacheManager 重载 + inlay 刷新；追加统计并入 P1-1 汇总通知（zh_CN +N、zh_TW +N、en_US +N、跳过已存在 M）
- **dsp 约定字段对齐真实项目惯例**：字段名精确 state/status/type 时三语言顺带补 `<field>dsp` key（值同原字段；实证 state/statedsp、type/typedsp、status/statusdsp 三对，RoutingTypeDsp 复用原 key 属反例不扩大）；无 comment 的这三个字段以默认标题「状态」/「类型」进确认清单；模板 dsp 显示列 Title 改 `${column.dspKey!column.i18nKey!column.name}` 优先用专属 key
- **随版修复（HaichengMes 验收实证）**：`I18nCacheManager.loadSingleFile` 原用 `VfsUtilCore.loadText`（VFS 层）读文件，而 PSI addProperty 的修改只落 Document、WriteCommandAction 不触发 save、VFS_CHANGES 不发——追加后缓存重装载的是旧内容，折叠/inlay/悬浮对新增 key 全部不生效；改为优先读 cached Document（`FileDocumentManager.getCachedDocument`），无缓存才走 loadText，listener 路径行为不变
- 单测 48 → 68（I18nGenerateServiceTest 20 例：dsp 派生/默认标题/TODO 判重/幂等过滤/转义/快速失败/摘要；collectI18nMissingSummary 默认标题进清单 2 例），全量 0 失败
- 验证（HaichengMes order 模块，biz_base_factory，GridName=BaseFactory）：确认追加三语言各 +22 行只有新增行；status（无 comment）以默认标题「状态」进清单，statusdsp 列 Title 用专属 key、properties 双 key 追加；生成完立即打开 Layout 折叠/inlay/悬浮即时生效（缓存修复实证）；幂等重跑 +0 行；验证产物已清理（用户业务 changelist 未受影响）

## [2.0.33] - 2026-09-17

feat: i18n 缺失分析报告（只读，codegen 改造 P2-1）——生成完成后弹缺失清单：字段名 → 拟生成 key → 拟中文值，为 P2-2 三语言追加生成对口径

- **拟生成 key 规则（GATE-B）**：`<模块 i18n 前缀>.<gridName 小写>grid.<字段名小写>`；模块前缀 = 模块名去项目段（`com.zhiyin.mes.app.dengqi.order` → `com.zhiyin.mes.app.order`，DengqiMes order/basic/quality/wms 四模块真实 properties 主流族实证；key 的 grid 段 = 模板生成 gridId `GridName+"Grid"` 全小写，真实 Order.xml `OrderGrid` ↔ `ordergrid` 逐键核对）——规则与 `getSimpleModuleName`（文件名口径，system→sysadm 等映射）互不套用，计划 P2-4 外置进配置
- **报告对话框**：`I18nMissingReportDialog`（DialogWrapper 只读报告），顶部展示推导前缀 + 命中/缺失计数 + JBTable 三列（字段名/拟生成 key/拟中文值），单元格可选中复制；仅统计 comment 非空且按值反查未命中的字段（无注释字段不进清单），缺失列表为空不弹窗；生成产物与模板零改动（i18nKey 缺省回退保持，写入闭环留给 P2-2）
- 真实验证（HaichengMes order 模块，biz_base_factory，GridName=BaseFactory）：命中 2（note←`...ordergrid.note`、maintainer←`...productionbatch.maintainer`）/ 缺失 22，与真实 properties 逐条核对零误报零漏报；模块 `com.zhiyin.mes.app.haicheng.order` 派生前缀 `com.zhiyin.mes.app.order` 与海程存量 key 族（登骐拷贝）完全一致，跨项目验证派生规则
- 单测 38 → 48（前缀推导 5 例 + key 拼装与缺失判定 5 例），全量 0 失败

## [2.0.32] - 2026-09-17

fix: 「DDL 粘贴」comment 归属错位根治（codegen 改造 P1-9）——TableParser 改字段定义片段化解析，单行/多行 DDL 统一正确

- **根因**：DDL 粘贴输入框是单行 JTextField（`Messages.showInputDialog`），粘贴多行 DDL 时换行被剥离 → DDL 单行化后，`parseCreateTable` 的 comment 正则 `` `(\w+)`.*?COMMENT\s+'(.*?)' `` 失去「`.` 不跨行」的偶然防线——无 COMMENT 的字段作为匹配起点，`.*?` 扩到**下一个任意字段**的 COMMENT 抢走注释，被跨过的字段丢注释（错位形态：maintainer←地址、delflag←第三方推送工厂、version←数据采集方式、status←城市，面包—火腿整体前移一位，与 2.0.24 基线 Layout 错位逐字段一致）；P1-4 观察到的「同输入仅偶尔错位」实为粘贴换行是否被剥的差异，非状态残留
- **修复**：`parseCreateTable` 重写为「字段定义片段化」——括号深度 + 单引号转义（`''`）感知地定位列定义体并在顶层逗号切分片段，逐片段以 `lookingAt()` 锚定片段头提取 name/type/length，片段内提 COMMENT（值内 `''` 还原为 `'`）与 NOT NULL/AUTO_INCREMENT；PRIMARY KEY / KEY / CONSTRAINT / INDEX 等非列片段自然跳过，KEY 定义行的索引 COMMENT 不再污染列注释；列定义体定位失败（畸形 DDL）回退旧整段正则兜底，不比修复前更差
- **随版修复（同源缺陷）**：nullable 正则 `[^,]+` 被 `decimal(19,4)` 类型内逗号截断致 NOT NULL 检测失效（误判 nullable=true）——片段化后逐片段判定，`price decimal(19,4) NOT NULL` 正确出 nullable=false
- 单测 34 → 38（TableParserTest 新增 4 例：单行化 DDL 三形态解析全等 + 9 个实锤错位字段逐一断言、KEY 行 COMMENT 不挂列、decimal NOT NULL 判定、comment 含 `''` 转义与逗号不切断），全量 0 失败
- 验证（HaichengMes order 模块，biz_base_factory）：多行粘贴 vs 单行粘贴（旧版必错位形态）产物**字节级一致**（归一轮次前缀后）且逐字段归属正确；DB 直读路径（不经 TableParser）逐字段正确回归

## [2.0.31] - 2026-09-17

feat: 导出方法按项目框架版本适配 EasyExcel2 自动探测（codegen 改造 P1-6 + P1-8），随版修复生成器布局、模板旧写法签名、折叠翻译内存泄漏与 EDT 断言

- **P1-6 导出框架适配**：Controller 模板 export 方法拆双变体，由 dataModel 的 `exportFramework` 选择。easyexcel2 新写法：`@Resource private EasyExcel2Utils EasyExcel2Utils;` 注入 + 9 参调用 `writeExportExcel(response, fileName, header, field, fieldtype, fileName, xxxService, "queryXxxList", params)`（传 service bean + 方法名由导出框架反射重查数据，签名经 CFR 反编译 haicheng 框架 jar 核实，与 HaichengMes 311 处调用同构）；exportFramework 缺省渲染旧写法（golden 字节级锁定零漂移）
- **自动探测**：`JavaPsiFacade.findClass("com.zhiyin.service.excel.EasyExcel2Utils", moduleWithDependenciesAndLibrariesScope)`（用户触发生成动作内的一次性索引查询），EDT runReadAction / 后台 runReadActionInSmartMode 双分支，dumb mode/异常回退旧写法并 LOG.warn，绝不中断生成；生成器 UI 新增「导出框架」下拉（自动 / EasyExcel 旧 / EasyExcel2 新，默认自动可手工覆盖）
- runIde + 真实项目验证：DengqiMes（classpath 无 EasyExcel2Utils）自动出旧写法；HaichengMes order 模块自动出新写法且 `mvn compile -P central,dev` 编译通过（验证后 svn revert 还原）；全程零 Slow operations 断言
- **P1-8 JDBC SSL 兼容回退**：老 MySQL yaSSL（5.7.26，仅 TLSv1/1.1）× JDK17（TLSv1/1.1 默认禁用）× Connector/J 8.2.0 默认 sslMode=PREFERRED 握手被服务端掐断——DatabaseMetadataUtil 建连捕获异常链中 SSLException 系（SSLHandshakeException 等类判定 + 消息含 SSL 兜底）后以 `sslMode=DISABLED` 明文重试一次（URL 改写纯函数覆盖已有参数不重复追加），成功 LOG.warn 记录回退（host:port 脱敏），失败上抛原始异常；仅插件侧建连生效，不改用户项目配置。实测 HaichengMes 库（192.168.116.9）不改 properties 读取成功；过渡期手工加在 DengqiMes dev 三个 properties 的 sslMode=DISABLED 可还原
- **fix 生成器窗口布局**：底部面板改固定两行（行 1 文件类型七复选框 / 行 2 Excel 导入导出 + 导出框架下拉），frame.pack() 实测最小窗宽兜底防拖窄裁切——修复新增下拉后控件溢出 1200 窗宽不可见、换行行高被裁半的问题
- **fix 模板旧写法签名（存量缺陷）**：旧 `EasyExcelUtils.writeExportExcel` 调用去 fieldtype 参数改 7 参——dengqi 族框架 jar 实证其重载没有 fieldtype 参数，原 8 参形态 `String[]` 对不上 `List<Map>` 在 DengqiMes 编译不过（历版验收仅字节 diff 从未编译验证故未暴露）；golden 快照与 docs/codegen-baseline Controller 同步显式修订
- **fix HtmlFoldingManager 内存泄漏**：IDE 退出报 ROOT_DISPOSABLE 未 dispose（沙箱实测单会话 25 实例）。两段根治：① Alarm 等以 this 为 parent 的 Disposer 注册从构造期挪到挂父之后的 initDisposables()（构造期注册会让无父 manager 在 ObjectTree ROOT 产生孤儿节点）；② getInstance 的 `editor instanceof Disposable` 挂父对 TextEditor.getEditor() 返回的 wrapper 实测不成立，改由 HtmlFoldingProjectService 注册 EditorFactoryListener（带 parentDisposable 重载）在 editorReleased 时显式 `Disposer.dispose`（平台告警认可的 direct dispose 模式，volatile released 防 EDT/后台竞态）；服务 dispose() 改 Disposer.dispose 静态调用（HtmlFoldingManagerDisposable 告警同源）。沙箱终验：退出泄漏告警 25 → 0
- **fix EDT PSI 慢操作断言**：HtmlFoldingProjectService 构造器同步遍历已开文件 `PsiManager.findFile`（仅普通 runReadAction），workspace 索引未就绪时在 EDT 命中 Slow operations 断言（沙箱单会话 25 次，右键菜单等操作触发服务惰性构造时爆发）——initializeExistingFiles 延迟到 `ReadAction.nonBlocking + inSmartMode` 后台执行，与 fileOpened 监听同款机制，监听先注册无空窗。沙箱终验：断言 25 → 0
- 单测 25 → 34（新增 DatabaseMetadataUtilSslFallbackTest 9 例：URL 改写 / SSL 异常链判定 / host 提取；金测新增 caseF/G/H 三用例：新变体 golden、缺省零漂移显式化、框架解析口径），全量 0 失败

## [2.0.30] - 2026-09-16

fix: 连接选择对话框密码打码与路径可辨识（codegen 改造 P1-5），双击行即选中

- 密码列固定显示 `******`，明文不再进入表格模型；选中逻辑由「文件名+url+用户名+密码四元组反查」改为按选中行直取（convertRowIndexToModel 转换后从连接列表取，消除脆弱匹配）
- 第一列由裸文件名改显示项目相对路径（DatabaseConnectionFinder 新增 filePath 键，VfsUtilCore.getRelativePath 相对 project base dir，项目外文件回退全路径；fileName 键保持原值不动——MyProjectService 字典生成按 `app-dev.properties` 精确匹配消费）：显示层自动去除本批最长公共目录前缀并把 `src/main/resources`、`src/main/webapp` 折叠为 `…`，悬停 Path 列 tooltip 显示完整路径——多模块多环境同名 properties（如各模块 application-dev.properties）靠模块段+环境可区分
- 表格模型 isCellEditable 恒 false（展示型表格禁编辑，双击不再进入单元格编辑态）+ 双击行即等价 Select（与 Select 按钮共用 confirmSelection，点列头/表体外不触发）
- Path 列 320 preferred 宽、URL 列限宽（max 380）防挤压
- runIde 验证（DengqiMes）：弹窗全程无明文密码、同名 properties 路径可区分、双击选中、选中连接读取链路回归通过；全量单测 22 例零回归
- 附带发现（存量问题，不属本项范围）：IDE 退出时 HtmlFoldingManager 报 ROOT_DISPOSABLE 未 dispose 内存泄漏告警，与本次改动无关

## [2.0.29] - 2026-09-16

feat: 代码生成器 Excel 导入/导出链路可选化（codegen 改造 P1-4），默认不再产出坏链 import 方法

- 生成器复选框区新增「Excel 导出」（默认勾，产物与既有行为一致）与「Excel 导入（需另行配置 Imp mapper）」（默认不勾）：导入链依赖未生成的 Imp mapper 列定义才能工作且 dao 调用被注释、Mapper 侧生成空 update 语句（运行时静默 no-op），此前生成即坏且无任何提示，现默认不生成
- Controller/Service/Dao/Html 四模板用 `<#if generateImport>` 包住 import 方法及其专属 import 语句/@Resource 注入（SysLogger/FileInputStream/HashMap、ExcelImportService/BizCommonService 等，逐符号核实归属，共享符号保持无条件）；Html 的 Import()/DownloadTemplate()/导入对话框（DivImport/DivError）与 Export() JS 属同一链路，一并条件包裹（`<#if>` 置于 `<#noparse>` 外）——计划原文列 4 模板，Html 为同链路补全
- Mapper 模板空 `<update id="import...">` 节点无条件删除（勾不勾导入都不再产出空语句）
- 勾选导入时 Service import 方法首行生成 `// TODO: 需配置 Imp mapper 列定义后方可启用`
- paramsMap 传 generateImport/generateExport，缺省口径 export=true / import=false（与复选框初始态一致，兼容旧调用方）
- 新增 BaseQueryTypeTemplateGoldenTest 5 用例：双开渲染与 golden 快照（src/test/resources/codegen-golden/）字节级一致（锁条件包裹零漂移）、不勾导入/导出产物零痕迹、缺省口径纯逻辑断言；全量单测 22 例通过
- runIde 三轮验证（DengqiMes order 模块 biz_base_factory）：默认组合与基线 diff 仅 import 块移除；勾导入后 Controller/Dao/Html/Moc 与基线字节级一致、Service 仅多 TODO 行、Mapper 仅少空 update；不勾导出 Controller/Html 零 export 痕迹

## [2.0.28] - 2026-09-16

fix: 传统 Java Web 项目 layout 生成双写修复（codegen 改造 P1-3），表结构查看连接发现移出 EDT

- CodeGenerateService traditional 分支不再先写一遍 src/main/webapp 路径再无条件写第二次：传统 Java Web 项目（如 SplashMes，src/main/resources/META-INF/resources/WEB-INF 结构）勾 Layout 生成仅写 META-INF 路径一处，消除每次生成必然出现的「目录不存在」跳过噪音（原双写第一次必然落空）；SpringBoot webapp 项目（DengqiMes）路径逻辑零变化，基线回归 7 件路径一致、6 件字节级一致（Layout 差异系基线采集走 DDL 粘贴路径的已知缺陷，P1-9 专项处理）
- ShowTableStructureAction 连接发现（DatabaseConnectionFinder → FileTypeIndex 索引查询）照 P1-7 ② 模式移入 Task.Backgroundable：EDT 只做弹窗交互，表结构查看不再触发 Slow operations 断言内部错误弹窗
- 已知边界（记录，不在本项范围）：传统 Spring XML 项目数据库配置在 applicationContext.xml（非 properties 的 database.url/username/password 三键），连接发现与「从数据库读取」不支持此类项目，生成器走 DDL 粘贴路径不受影响

## [2.0.27] - 2026-09-16

fix: 代码生成器数据库读取后台化（超时 + 错误可见 + 表名校验）并根治生成链路 EDT 慢操作断言（codegen 改造 P1-2 + P1-7）

- DatabaseMetadataUtil 建连改 `DriverManager.getConnection(url, props)` 显式传 connectTimeout=3000 / socketTimeout=10000；错误不再被 System.out.println 吞掉，改 Logger 记录并向上抛 SQLException（getTableMetadata / getAllDatabaseConnectionsMetaData），System.out 全量清零
- 「从数据库读取」JDBC 查询移入 Task.Backgroundable（纯网络调用不碰 PSI）：选连接/输表名留 EDT，onSuccess 回 EDT 更新字段表与 sql，onError 弹「数据库读取失败」（显示 host:port，报错脱敏屏蔽 `user 'xxx'@`，URL 内嵌凭据打码）——错误库地址有界时间内失败，UI 不再冻结
- 表名输入校验：null/空提示中止（取消表名弹窗不再产生 `from null a` 状态污染，sql/tableName 保持原值）；SHOW CREATE TABLE 为字符串拼接，表名必须匹配 `^[A-Za-z0-9_.]+$`（新增 DataModelGeneratorTest 单测覆盖校验/脱敏/host 提取）
- P1-7 生成链路 EDT 慢操作断言修复：字段 i18n 反查（FilenameIndex/FileTypeIndex）改为生成前后台批量预查（新增 MyPropertiesUtil.findModuleDataGridI18nPropertiesByValueBatch，逐值调用原方法、命中语义不变），EDT 只消费结果；数据库连接发现（DatabaseConnectionFinder，同为索引查询）一并移出 EDT——IU-2024.3.5 沙箱实测修复前单次生成链路 19 组 SlowOperations 断言内部错误，修复后 0，基线产物 6/7 字节级一致（Layout 差异系基线采集走 DDL 粘贴路径 comment 归属错位，DB 直读产物为正确口径，`备注`→ordergrid.note 命中复现证明 i18n 语义不变）
- 附带清理：MyPropertiesUtil 删除被注释的 SlowOperations.allowSlowOperations 死代码

## [2.0.26] - 2026-09-16

fix: Layout URL 导航 line marker 在 Controller PSI 失效时抛 IllegalArgumentException

- LayoutUrlNavigationRelatedItemLineMarkerProvider.getTargetPsiElements 过滤 SmartPsiElementPointer.getElement() 为 null 的目标（对齐 HtmlUrl/UrlNavigationAnnotated 两个 Provider 既有写法）：Controller 文件在扫描后被修改/删除时指针失效返回 null，targets 变成 [null]，NavigationGutterIconBuilder.setTargets 校验非空直接抛 IllegalArgumentException（daemon line marker pass 报错）
- 移除该 Provider 热路径上的 System.out.println("[Microservices] 扫描中...")：mapping 快照为空期间每个 XML 元素每次 pass 都触发，违反热路径禁 println 铁律；startScan 触发逻辑不变

## [2.0.25] - 2026-09-15

fix: 代码生成器重复生成不再把文件降级写到模块根目录，逐文件弹窗改为一次汇总通知（codegen 改造 P1-1）

- generateXmlFile 改为返回结果枚举（SUCCESS / SKIP_FILE_EXISTS / FAIL_DIR_NOT_FOUND）：目标目录不存在或文件已存在时直接跳过，不再弹错误框后降级写入模块 content root（半途重跑在模块根产生垃圾文件的根因）；仅保留 content root 缺失时的异常中断
- generateBaseQueryTypeFile / generateMocFile / generateLayoutFile 收集各产物结果，生成结束后一次汇总通知（成功 N 个列出文件名、已存在跳过 M 个、目录不存在跳过 M 个），替代原先逐文件「代码生成成功」通知；查询页 6 件与 Moc 各一条汇总（两者走独立服务方法，待 P3-3 表单化时统一）
- 移除写盘后 invokeLater 里的全局 LocalFileSystem.refresh(true) 同步刷新（VCS 感知由 VcsDirtyScopeManager.fileDirty 承担）
- 携带 codegen 改造阶段 0 产物：生成回归基线（docs/codegen-baseline，biz_base_factory 7 件套 + 输入 DDL）、TableParser / formatSql 快照单测（11 用例）、改造计划文档 docs/codegen-refactor-plan.md 及 CLAUDE.md 接入说明

## [2.0.24] - 2026-09-15

fix: 修复 IDEA 2026.2 新 Search Everywhere 下 URL 搜索报错且搜不到结果，默认快捷键改为 Ctrl+Alt+Shift+\

- 2026.2 本地默认启用新 Search Everywhere（SeFrontendService）：第三方 legacy searchEverywhereContributor 不被接入（withAdaptedLegacyContributors=false），"/url" 前缀搜不出结果；getCurrentlyShownUI() 直接抛 UnsupportedOperationException。UrlQuickSearchAction 不再走 SE，统一改为打开 OneClickNavigation 工具窗口并聚焦 URL 输入框（全平台行为一致）
- NewControllerToolWindowUI 新增 focusUrlField()：聚焦输入框并全选旧值，输入即替换
- 默认快捷键 Ctrl+Shift+\ 改为 Ctrl+Alt+Shift+\：IDEA Ultimate 2026.x 内置 Go to URL（GotoUrlAction，microservices-plugin）默认占用 control shift BACK_SLASH，插件侧无法移除平台绑定，保留必然冲突
- UrlSearchEverywhereContributor（legacy SE EP）保留注册：旧 SE 用户手动输入 /url 前缀仍可触发

## [2.0.22] - 2026-09-15

feat: 新增 Ctrl+Shift+\ 直达 URL 搜索

- 新增 UrlQuickSearchAction：打开 Search Everywhere（All 页签）并预填 "/url "，输入即搜 Controller/Feign 映射（补上 2.0.5 提交信息承诺但未实现的快捷键）
- 修复预填文本默认全选、一输入就整段覆盖的问题：收起选区并把光标定位到末尾，输入直接追加（平台 SearchEverywhereManagerImpl#show 对预填文本默认 selectAll）

## [2.0.21] - 2026-09-15

fix: 修复 ComboboxUrlService 负缓存导致补全缓存为空且无法刷新的回归

- 空结果缓存增加 10s TTL：项目打开期扫描时机不对扫到的空集不再被永久负缓存锁死，过期后下次补全自动重扫自愈
- dumb mode（索引更新中）下跳过扫描且不入缓存，杜绝不完整扫描结果被当成有效缓存
- 扫描统一包 ReadAction，手动 Action 触发的重扫路径同样线程安全

## [2.0.20] - 2026-09-14

build: 移除冗余依赖并支持本地密钥注入

- 移除 apache commons 及无效导入，改用 IntelliJ 平台工具类；
- 优化 ComboboxUrlService 缓存为 ConcurrentHashMap 并支持负缓存；
- processResources 支持从 config.local.properties 注入真实密钥， config.properties 改为占位符，新增本地配置文件忽略规则。

## [2.0.19] - 2026-09-14

优化依赖与缓存

- 移除 apache commons 及无效导入，改用 IntelliJ 平台工具类
- 优化 ComboboxUrlService 缓存结构，使用 ConcurrentHashMap 并支持负缓存

## [2.0.18] - 2026-09-10

- 启用表结构查看菜单项

## [2.0.17] - 2026-09-10

- 新增查看表结构及 Moc 调用导航功能
- 新增 ShowTableStructureAction：选中表名右键连库查看表结构，支持多数据源切换与记忆
- 新增 TableStructure/TableStructurePopup：表结构模型与结果弹窗展示
- 新增 MyMocXmlReferenceContributor：Moc XML name 属性 Ctrl+B 跳转到所有 Java 调用点
- 新增 CallSiteNavigationTarget：调用点导航目标包装，展示调用方法上下文

## [2.0.16] - 2026-09-03

- 优化：修复启动期索引竞态可能导致的异常

## [2.0.15] - 2026-08-31

- 修复 EDT 线程中读取 PSI 导致的线程访问异常
- MybatisLogSQLAction 增挂 EditorPopupMenu，编辑器右键菜单现在与 Console 一样可用（仍需先选中日志文本）。
- MybatisLogSQLAction 多语句支持
- 修正SVN提交时间显示时区偏移

## [2.0.14] - 2026-08-25

- 按模块收窄 XML 方法引用搜索范围

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

[Unreleased]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.1.2...HEAD
[2.1.2]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.1.1...v2.1.2
[2.1.1]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.1.0...v2.1.1
[2.1.0]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.38...v2.1.0
[2.0.38]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.37...v2.0.38
[2.0.37]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.36...v2.0.37
[2.0.36]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.35...v2.0.36
[2.0.35]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.34...v2.0.35
[2.0.34]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.33...v2.0.34
[2.0.33]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.32...v2.0.33
[2.0.32]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.31...v2.0.32
[2.0.31]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.30...v2.0.31
[2.0.30]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.29...v2.0.30
[2.0.29]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.28...v2.0.29
[2.0.28]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.27...v2.0.28
[2.0.27]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.26...v2.0.27
[2.0.26]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.25...v2.0.26
[2.0.25]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.24...v2.0.25
[2.0.24]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.22...v2.0.24
[2.0.22]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.21...v2.0.22
[2.0.21]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.20...v2.0.21
[2.0.20]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.19...v2.0.20
[2.0.19]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.18...v2.0.19
[2.0.18]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.17...v2.0.18
[2.0.17]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.16...v2.0.17
[2.0.16]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.15...v2.0.16
[2.0.15]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.14...v2.0.15
[2.0.14]: https://github.com/xiaoyan94/IdeaPluginDemo2.x/compare/v2.0.13...v2.0.14
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
