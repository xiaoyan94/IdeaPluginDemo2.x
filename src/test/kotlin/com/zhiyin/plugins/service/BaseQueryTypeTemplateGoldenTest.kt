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

    private fun render(templateName: String, generateImport: Boolean, generateExport: Boolean): String {
        val sw = StringWriter()
        FreeMarkerConfiguration.getConfiguration().getTemplate(templateName).process(
            minimalDataModel(generateImport, generateExport), sw,
        )
        return sw.toString()
    }

    private fun goldenText(name: String): String {
        val bytes = javaClass.getResourceAsStream("/codegen-golden/$name")?.readBytes()
            ?: error("golden resource missing: /codegen-golden/$name")
        return String(bytes, Charsets.UTF_8)
    }

    private fun minimalDataModel(generateImport: Boolean, generateExport: Boolean): Map<String, Any> {
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
        return linkedMapOf(
            "moduleName" to "Order",
            "dataGrids" to listOf(dataGrid),
            "generateImport" to generateImport,
            "generateExport" to generateExport,
        )
    }
}
