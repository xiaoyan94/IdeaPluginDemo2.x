package com.zhiyin.plugins.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/**
 * P1-4 golden 快照护栏：BaseQueryType 五模板（Controller/Service/Dao/Mapper/Html）的
 * generateImport/generateExport 条件包裹不得改变「双开」时的渲染输出。
 *
 * golden 基线时点 = Service TODO 注释与 Mapper 空 update 删除已落、条件包裹未做——
 * 用例 A 因此锁定的正是「<#if> 标签摆放零字节漂移」（FreeMarker 独行标签整行剥离）。
 * 快照采集 dataModel 与本测试 minimalDataModel 完全一致，勿单边修改。
 */
class BaseQueryTypeTemplateGoldenTest {

    private val templates = listOf(
        "BaseQueryTypeController.ftl" to "BaseFactoryController.java",
        "BaseQueryTypeService.ftl" to "BaseFactoryService.java",
        "BaseQueryTypeDao.ftl" to "BaseFactoryDao.java",
        "BaseQueryTypeMapper.ftl" to "BaseFactoryMapper.xml",
        "BaseQueryTypeHtml.ftl" to "BaseFactoryHtml.html",
    )

    /** 用例 A：双开（导入+导出）渲染输出与 golden 快照逐字节一致（同 chars 即同 UTF-8 字节） */
    @Test
    fun caseA_goldenByteEquality_whenBothEnabled() {
        for ((templateName, goldenName) in templates) {
            val golden = goldenText(goldenName)
            assertEquals("template=$templateName golden=$goldenName", golden, render(templateName, generateImport = true, generateExport = true))
        }
    }

    /** 用例 B：不勾导入——五产物零 import 痕迹，查询主链保留，Mapper 无 import 节点 */
    @Test
    fun caseB_noImportChain_whenImportDisabled() {
        val outputs = templates.associate { (templateName, _) ->
            templateName to render(templateName, generateImport = false, generateExport = true)
        }
        outputs.forEach { (templateName, content) ->
            assertFalse("template=$templateName", content.contains("importBaseFactory"))
        }
        val html = outputs.getValue("BaseQueryTypeHtml.ftl")
        assertFalse(html.contains("DivImport"))
        assertFalse(html.contains("btnImportSave"))
        assertFalse(html.contains("DownloadTemplate"))
        val mapper = outputs.getValue("BaseQueryTypeMapper.ftl")
        assertFalse(mapper.contains("id=\"import"))
        // 连带专属 import 语句/注入一并干净
        val controller = outputs.getValue("BaseQueryTypeController.ftl")
        assertFalse(controller.contains("SysLogger"))
        assertFalse(controller.contains("FileInputStream"))
        val service = outputs.getValue("BaseQueryTypeService.ftl")
        assertFalse(service.contains("ExcelImportService"))
        assertFalse(service.contains("BizCommonService"))
        // 查询主链不受影响
        assertTrue(service.contains("queryBaseFactoryList"))
        assertTrue(controller.contains("queryBaseFactoryList"))
    }

    /** 用例 C：勾导入——Service import 方法首行 TODO（P2-6 起指向 import_draft.sql 草稿与 temp→biz upsert）、Controller 端点在 */
    @Test
    fun caseC_importTodo_whenImportEnabled() {
        // 模板工作区为 CRLF，断言字面量用 \n——归一后比对，行尾不参与本用例语义
        val service = render("BaseQueryTypeService.ftl", generateImport = true, generateExport = true).replace("\r\n", "\n")
        assertTrue(service.contains("    public Map importBaseFactory(FileInputStream fis, String clientIp, Map<String, Object> params) throws Exception {\n        // TODO: 需执行 sql/BaseFactory_import_draft.sql（建临时表+注册导入定义）并补 temp→biz upsert（dao import 调用当前被注释）后方可启用"))
        val controller = render("BaseQueryTypeController.ftl", generateImport = true, generateExport = true)
        assertTrue(controller.contains("importBaseFactory"))
    }

    /** 用例 D：不勾导出——Controller/Html 零 export 痕迹与专属 import/注入，Service 不受 export 影响 */
    @Test
    fun caseD_noExportChain_whenExportDisabled() {
        val controller = render("BaseQueryTypeController.ftl", generateImport = true, generateExport = false)
        assertFalse(controller.contains("exportBaseFactory"))
        assertFalse(controller.contains("EasyExcelUtils"))
        assertFalse(controller.contains("ExcelExportService"))
        assertFalse(controller.contains("DateUtils"))
        // 导入链仍在
        assertTrue(controller.contains("importBaseFactory"))

        val html = render("BaseQueryTypeHtml.ftl", generateImport = true, generateExport = false)
        assertFalse(html.contains("exportBaseFactory"))
        assertFalse(html.contains("function Export()"))

        // Service 模板不含 export 分支：双开与只开导入输出应完全一致
        val serviceBoth = render("BaseQueryTypeService.ftl", generateImport = true, generateExport = true)
        val serviceImportOnly = render("BaseQueryTypeService.ftl", generateImport = true, generateExport = false)
        assertEquals(serviceBoth, serviceImportOnly)
        assertTrue(serviceImportOnly.contains("queryBaseFactoryList"))
    }

    /** 用例 E：paramsMap 缺省口径——导出缺省 true、导入缺省 false（与 UI 复选框初始态一致） */
    @Test
    fun caseE_paramsMapDefaults() {
        assertFalse(CodeGenerateService.resolveGenerateImport(emptyMap()))
        assertTrue(CodeGenerateService.resolveGenerateExport(emptyMap()))
        assertTrue(CodeGenerateService.resolveGenerateImport(mapOf("generateImport" to true)))
        assertFalse(CodeGenerateService.resolveGenerateImport(mapOf("generateImport" to false)))
        assertFalse(CodeGenerateService.resolveGenerateExport(mapOf("generateExport" to false)))
        assertTrue(CodeGenerateService.resolveGenerateExport(mapOf("generateExport" to true)))
    }

    /**
     * 用例 F（P1-6）：exportFramework=easyexcel2 渲染 Controller 与新 golden 快照逐字节一致，
     * 并逐项断言新写法要点：EasyExcel2Utils 注入、9 参调用（sheetName=fileName、classObj=service、
     * methodName="query...List"）、无 rows 预查、旧链专属 import（DateUtils/Date/List）不生成
     */
    @Test
    fun caseF_easyExcel2Variant_byteEquality() {
        val controller = render("BaseQueryTypeController.ftl", generateImport = true, generateExport = true, exportFramework = "easyexcel2")
        assertEquals(
            goldenText("BaseFactoryControllerEasyExcel2.java"),
            controller,
        )
        // 归一 CRLF 后做语义断言（模板工作区为 CRLF）
        val c = controller.replace("\r\n", "\n")
        assertTrue(c.contains("import com.zhiyin.service.excel.EasyExcel2Utils;"))
        assertFalse(c.contains("EasyExcelUtils"))
        // 新旧共用：ExcelExportService（列定义来源）保留
        assertTrue(c.contains("import com.zhiyin.service.excel.ExcelExportService;"))
        assertTrue(c.contains("@Resource\n    private EasyExcel2Utils EasyExcel2Utils;"))
        // 无 rows 预查、无旧调用痕迹
        assertFalse(c.contains("List<Map> rows"))
        assertFalse(c.contains("recordMap"))
        assertFalse(c.contains("DateUtils"))
        assertFalse(c.contains("new Date()"))
        assertFalse(c.contains("import java.util.Date;"))
        assertFalse(c.contains("import java.util.List;"))
        // 9 参调用：sheetName=fileName、classObj=baseFactoryService、methodName="queryBaseFactoryList"
        assertTrue(
            c.contains(
                "EasyExcel2Utils.writeExportExcel(response, fileName, (Object[]) columnMap.get(\"header\"), " +
                    "(String[]) columnMap.get(\"field\"), (String[]) columnMap.get(\"fieldtype\"), fileName, " +
                    "baseFactoryService, \"queryBaseFactoryList\", params);",
            ),
        )
        // 查询主链不受框架变体影响
        assertTrue(c.contains("queryBaseFactoryList"))
    }

    /**
     * 用例 G（P1-6 缺省铁律显式化）：exportFramework 缺省（不传）时五模板渲染与既有 golden
     * 逐字节一致（caseA 即隐含此约束，此用例单独点名），且缺省 == 显式 easyexcel
     */
    @Test
    fun caseG_defaultFrameworkRendersOldStyle() {
        for ((templateName, goldenName) in templates) {
            assertEquals(
                "template=$templateName golden=$goldenName",
                goldenText(goldenName),
                render(templateName, generateImport = true, generateExport = true),
            )
        }
        val defaultCtl = render("BaseQueryTypeController.ftl", generateImport = true, generateExport = true)
        val explicitOldCtl = render("BaseQueryTypeController.ftl", generateImport = true, generateExport = true, exportFramework = "easyexcel")
        assertEquals(defaultCtl, explicitOldCtl)
    }

    /** 用例 H（P1-6 解析口径）：显式直通优先；auto/缺失/未知值跟随探测结果；module 不可用回退旧写法 */
    @Test
    fun caseH_exportFrameworkResolution() {
        // 显式覆盖优先，探测结果不参与
        assertEquals("easyexcel", CodeGenerateService.combineExportFramework("easyexcel", true))
        assertEquals("easyexcel2", CodeGenerateService.combineExportFramework("easyexcel2", false))
        // auto / 缺失 / 未知值 → 跟随探测
        assertEquals("easyexcel2", CodeGenerateService.combineExportFramework("auto", true))
        assertEquals("easyexcel", CodeGenerateService.combineExportFramework("auto", false))
        assertEquals("easyexcel2", CodeGenerateService.combineExportFramework(null, true))
        assertEquals("easyexcel", CodeGenerateService.combineExportFramework(null, false))
        assertEquals("easyexcel2", CodeGenerateService.combineExportFramework("bogus", true))
        // module=null（探测不可用）不抛异常：显式直通、缺省回退旧写法
        assertEquals("easyexcel2", CodeGenerateService.resolveExportFramework(null, mapOf("exportFramework" to "easyexcel2")))
        assertEquals("easyexcel", CodeGenerateService.resolveExportFramework(null, mapOf("exportFramework" to "auto")))
        assertEquals("easyexcel", CodeGenerateService.resolveExportFramework(null, emptyMap()))
        assertEquals("easyexcel", CodeGenerateService.resolveExportFramework(null, mapOf()))
    }

    /**
     * 用例 I（P2-7）：sync_erp_iqc_fail_log 真实 DDL 场景（iqc_incoming_check_upgrade.sql:180-198 关键列，
     * TableParser.getType 归一形态）——sql 与 dictTransformFields 均由 CodeGenerateService 计算传入
     * （不手写 key），Mapper/Service 渲染与新 golden 快照逐字节一致。
     * 锁定有意变化：select 4 个 DATE_FORMAT + ORDER BY、Mapper `<if>` 仅查询面板勾选字段、
     * Service status 字典块（5 参 queryDaoDataT + getStringFromMap）、generateImport=false 时
     * StringUtils import 因字典块存在仍输出（条件扩展）
     */
    @Test
    fun caseI_dictAndDateFormat_whenIqcFailLogQueryPage() {
        // 快照对比前归一 CRLF（caseC/caseF 惯例）：formatSql 注入的 \n 使渲染输出为混合行尾，
        // 严格 byte 对比会被 git autocrlf 的提交/检出转换破坏（快照入库转 LF、检出转全 CRLF）
        val mapper = renderWith(iqcFailLogDataModel(), "BaseQueryTypeMapper.ftl").replace("\r\n", "\n")
        assertEquals(goldenText("SyncErpIqcFailLogMapper.xml").replace("\r\n", "\n"), mapper)
        val service = renderWith(iqcFailLogDataModel(), "BaseQueryTypeService.ftl").replace("\r\n", "\n")
        assertEquals(goldenText("SyncErpIqcFailLogService.java").replace("\r\n", "\n"), service)

        // 要点断言（归一 CRLF 后做语义核对）
        val m = mapper.replace("\r\n", "\n")
        // 4 个 datetime 列包 DATE_FORMAT 到秒（别名=原列名保持 Map key），普通列传裸列
        assertEquals(4, Regex("DATE_FORMAT\\(a\\.\\w+, '%Y-%m-%d %H:%i:%s'\\) as ").findAll(m).count())
        assertTrue(m.contains("DATE_FORMAT(a.createtime, '%Y-%m-%d %H:%i:%s') as createtime"))
        assertTrue(m.contains("a.api"))
        // 存在 id 字段 → 追加排序
        assertTrue(m.contains("ORDER BY a.id desc"))
        // <if> 仅查询面板勾选字段（api/maintaintime/status），未勾选列不出死条件
        assertTrue(m.contains("""<if test="api != null and api != ''">"""))
        assertTrue(m.contains("""<if test="maintaintimefrom != null and maintaintimefrom != ''">"""))
        assertTrue(m.contains("""<if test="maintaintimeto != null and maintaintimeto != ''">"""))
        assertTrue(m.contains("""<if test="status != null and status != ''">"""))
        assertFalse(m.contains("""<if test="url"""))
        assertFalse(m.contains("""<if test="delflag"""))
        assertFalse(m.contains("""<if test="createtime"""))

        val s = service.replace("\r\n", "\n")
        // 5 参字典块：TODO 注释、setLanguage 走 getStringFromMap、链尾分号、第 5 参 builder
        assertTrue(s.contains("// TODO: 字典 pcode 按实际字典确认（默认 PascalCase 字段名），字典缺条目时 dsp 列空白无害"))
        assertTrue(s.contains("DictTransformBuilder dictTransformBuilder = new DictTransformBuilder()"))
        assertTrue(s.contains(""".setLanguage(StringUtils.getStringFromMap(params, "language"))"""))
        assertTrue(s.contains(""".addDictTransform("Status", "status", "statusdsp");"""))
        assertTrue(s.contains("queryDaoDataT(ISyncErpIqcFailLogDao.class, syncErpIqcFailLogDao, \"querySyncErpIqcFailLogList\", params, dictTransformBuilder);"))
        // generateImport=false 但字典块依赖 StringUtils → import 仍输出（P2-7 条件扩展）
        assertTrue(s.contains("import com.zhiyin.utils.StringUtils;"))
    }

    /**
     * 用例 J（P2-7 零漂移护栏）：无 state/status 字段的表——CGS 会 put 空 dictTransformFields 列表，
     * 输出必须与「无 key」（= 旧版 caseA 基线）逐字节一致；Mapper queryFields 非空但未勾选时不出任何 `<if>`
     */
    @Test
    fun caseJ_noDictFields_zeroDrift() {
        val withEmptyList = renderWith(minimalDataModel(generateImport = true, generateExport = true).toMutableMap().apply {
            put("dictTransformFields", emptyList<Map<String, String>>())
        }, "BaseQueryTypeService.ftl")
        assertEquals(goldenText("BaseFactoryService.java"), withEmptyList)

        // queryFields 非空但 isQueryField 均未勾选 → <where> 内零 <if>（收窄生效）
        val queryFields = listOf(
            linkedMapOf("name" to "code", "type" to "string", "isQueryField" to "false"),
            linkedMapOf("name" to "status", "type" to "int", "isQueryField" to "false"),
        )
        val mapper = renderWith(
            minimalDataModel(generateImport = true, generateExport = true).toMutableMap().apply {
                @Suppress("UNCHECKED_CAST")
                (this["dataGrids"] as List<MutableMap<String, Any>>)[0]["queryFields"] = queryFields
            },
            "BaseQueryTypeMapper.ftl",
        )
        assertFalse(mapper.contains("<if "))
        assertTrue(mapper.contains("<where>"))
    }

    private fun renderWith(dataModel: Map<String, Any>, templateName: String): String {
        val sw = StringWriter()
        FreeMarkerConfiguration.getConfiguration().getTemplate(templateName).process(dataModel, sw)
        return sw.toString()
    }

    /** P2-7 验收 DDL 关键列子集（type 为 TableParser.getType 归一形态），isQueryField 为查询面板勾选态 */
    private fun iqcFailLogFields(): List<Map<String, Any>> = listOf(
        linkedMapOf("name" to "id", "type" to "int", "isQueryField" to "false"),
        linkedMapOf("name" to "api", "type" to "string", "isQueryField" to "true"),
        linkedMapOf("name" to "url", "type" to "string", "isQueryField" to "false"),
        linkedMapOf("name" to "param", "type" to "string", "isQueryField" to "false"),
        linkedMapOf("name" to "result", "type" to "string", "isQueryField" to "false"),
        linkedMapOf("name" to "error", "type" to "string", "isQueryField" to "false"),
        linkedMapOf("name" to "createtime", "type" to "datetime", "isQueryField" to "false"),
        linkedMapOf("name" to "maintaintime", "type" to "datetime", "isQueryField" to "true"),
        linkedMapOf("name" to "status", "type" to "int", "isQueryField" to "true"),
        linkedMapOf("name" to "delflag", "type" to "int", "isQueryField" to "false"),
        linkedMapOf("name" to "retrycount", "type" to "int", "isQueryField" to "false"),
        linkedMapOf("name" to "lastretrytime", "type" to "datetime", "isQueryField" to "false"),
        linkedMapOf("name" to "successtime", "type" to "datetime", "isQueryField" to "false"),
        linkedMapOf("name" to "finalfailflag", "type" to "int", "isQueryField" to "false"),
    )

    private fun iqcFailLogDataModel(): Map<String, Any> {
        val fields = iqcFailLogFields()
        val dataGrid = linkedMapOf<String, Any>(
            "dataGridName" to "SyncErpIqcFailLog",
            "objectName" to "syncErpIqcFailLog",
            "tableName" to "sync_erp_iqc_fail_log",
            "fileName" to "SyncErpIqcFailLog",
            "sql" to CodeGenerateService.formatSql(CodeGenerateService.buildAutoSelectSql("sync_erp_iqc_fail_log", fields)),
            "columns" to emptyList<Any>(),
            "queryFields" to fields,
            "ckDummyColumn" to "true",
        )
        return linkedMapOf(
            "moduleName" to "Basic",
            "dataGrids" to listOf(dataGrid),
            "generateImport" to false,
            "generateExport" to true,
            "dictTransformFields" to CodeGenerateService.collectDictTransformFields(fields),
        )
    }

    private fun render(templateName: String, generateImport: Boolean, generateExport: Boolean, exportFramework: String? = null): String {
        val sw = StringWriter()
        FreeMarkerConfiguration.getConfiguration().getTemplate(templateName).process(
            minimalDataModel(generateImport, generateExport, exportFramework), sw,
        )
        return sw.toString()
    }

    private fun goldenText(name: String): String {
        val bytes = javaClass.getResourceAsStream("/codegen-golden/$name")?.readBytes()
            ?: error("golden resource missing: /codegen-golden/$name")
        return String(bytes, Charsets.UTF_8)
    }

    private fun minimalDataModel(generateImport: Boolean, generateExport: Boolean, exportFramework: String? = null): Map<String, Any> {
        val dataGrid = linkedMapOf<String, Any>(
            "dataGridName" to "BaseFactory",
            "objectName" to "baseFactory",
            "tableName" to "biz_base_factory",
            "fileName" to "BaseFactory",
            "sql" to "select a.* from biz_base_factory a",
            "columns" to emptyList<Any>(),
            "queryFields" to emptyList<Any>(),
            "ckDummyColumn" to "true",
        )
        val dataModel = linkedMapOf(
            "moduleName" to "Order",
            "dataGrids" to listOf(dataGrid),
            "generateImport" to generateImport,
            "generateExport" to generateExport,
        )
        if (exportFramework != null) {
            dataModel["exportFramework"] = exportFramework
        }
        return dataModel
    }
}
