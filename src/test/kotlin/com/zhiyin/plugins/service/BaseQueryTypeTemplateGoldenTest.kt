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

    /** 用例 C：勾导入——Service import 方法首行 TODO、Controller 端点在 */
    @Test
    fun caseC_importTodo_whenImportEnabled() {
        // 模板工作区为 CRLF，断言字面量用 \n——归一后比对，行尾不参与本用例语义
        val service = render("BaseQueryTypeService.ftl", generateImport = true, generateExport = true).replace("\r\n", "\n")
        assertTrue(service.contains("    public Map importBaseFactory(FileInputStream fis, String clientIp, Map<String, Object> params) throws Exception {\n        // TODO: 需配置 Imp mapper 列定义后方可启用"))
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
