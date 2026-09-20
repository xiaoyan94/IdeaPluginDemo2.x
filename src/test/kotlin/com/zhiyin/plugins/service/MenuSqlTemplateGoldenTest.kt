package com.zhiyin.plugins.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/**
 * P2-3 golden 快照护栏：菜单注册 SQL 草稿模板（menu.sql.ftl）——默认/勾导入两档按钮集渲染、
 * 翻译缺失 TODO 形态、幂等形态（NOT EXISTS 判重 + PID 反查 + KEY 反引号）与 toSnakeCase 驼峰转蛇形。
 *
 * golden 基线由实际渲染后 dump 定稿（P0-2 快照定稿法）——dataModel 与本测试 menuDataModel
 * 完全一致，勿单边修改。
 */
class MenuSqlTemplateGoldenTest {

    /** 用例 A：默认按钮集（刷新+导出两行 fun）渲染与 golden 逐字节一致（同 chars 即同 UTF-8 字节） */
    @Test
    fun caseA_goldenDefaultButtons() {
        val out = render(generateImport = false, menuNameTw = "工廠", menuNameEn = "Factory")
        assertEquals(goldenText("BaseFactoryMenuDefault.sql"), out)
        val c = out.replace("\r\n", "\n")
        // 默认两行：刷新 SEQ 0、导出 SEQ 1；functionurl 按 folder 拼、KEY 为蛇形
        assertTrue(c.contains("'刷新', 'icon-reload', 'Refresh', 'ToolBar', 0, 1, 75"))
        assertTrue(c.contains("'导出', 'icon-export', 'Export', 'ToolBar', 1, 1, 80"))
        assertFalseContains(c, "downloadTemplate")
        assertFalseContains(c, "'导入'")
        assertTrue(c.contains("'./Order/BaseFactory'"))
        assertTrue(c.contains("'com.zhiyin.mes.menu.base_factory'"))
    }

    /** 用例 B：勾导入按钮集（四行 fun，SEQ 0/1/2/3，模板变量驱动）渲染与 golden 逐字节一致 */
    @Test
    fun caseB_goldenImportButtons() {
        val out = render(generateImport = true, menuNameTw = "工廠", menuNameEn = "Factory")
        assertEquals(goldenText("BaseFactoryMenuImport.sql"), out)
        val c = out.replace("\r\n", "\n")
        // SEQ 顺序：刷新0、导出(模板)1、导入2、导出3
        assertTrue(c.contains("@fun_id + 0, m.id, @fun_id + 0, '刷新', 'icon-reload', 'Refresh', 'ToolBar', 0, 1, 75, NULL, 661, NULL"))
        assertTrue(c.contains("'导出(模板)', 'icon-export', 'downloadTemplate', 'ToolBar', 1, 1, 84"))
        assertTrue(c.contains("'导入', 'icon-import', 'Import', 'ToolBar', 2, 1, 454"))
        assertTrue(c.contains("'导出', 'icon-export', 'Export', 'ToolBar', 3, 1, 80"))
    }

    /** 用例 C：翻译缺失（menuNameTw/En 空）输出 TODO 形态，不中断渲染；zh_CN 两处仍为菜单中文名 */
    @Test
    fun caseC_translationMissingTodo() {
        val c = render(generateImport = false, menuNameTw = "", menuNameEn = "").replace("\r\n", "\n")
        assertTrue(c.contains("'/* TODO: zh_TW */'"))
        assertTrue(c.contains("'/* TODO: en_US */'"))
        assertTrue(c.contains("'工厂', 'BaseFactory', 1, NULL, './Order/BaseFactory'"))
        assertTrue(c.contains("'zh_CN' AS TYPE, '工厂' AS value"))
    }

    /**
     * 用例 D：幂等形态——NOT EXISTS 判重（sys_menu 按 code、fun 按 PID+CODE、type 按 PID+TYPE）、
     * fun 行 PID 经 sys_menu 按 code 反查（非 @menu_id 直填）、KEY 是 MySQL 保留字须反引号
     */
    @Test
    fun caseD_idempotentShape() {
        val c = render(generateImport = false, menuNameTw = "工廠", menuNameEn = "Factory").replace("\r\n", "\n")
        assertTrue(c.contains("WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE code = 'BaseFactory')"))
        assertTrue(c.contains("NOT EXISTS (SELECT 1 FROM sys_res_i18n WHERE `KEY` = 'com.zhiyin.mes.menu.base_factory')"))
        assertTrue(c.contains("NOT EXISTS (SELECT 1 FROM sys_res_i18n_type x WHERE x.PID = i.ID AND x.TYPE = t.TYPE)"))
        assertTrue(c.contains("NOT EXISTS (SELECT 1 FROM sys_menu_fun f WHERE f.PID = m.id AND f.CODE = 'Refresh')"))
        assertTrue(c.contains("NOT EXISTS (SELECT 1 FROM sys_menu_fun f WHERE f.PID = m.id AND f.CODE = 'Export')"))
        // fun 行 PID 来自 sys_menu 按 code 反查（m.id），不直填 @menu_id——重跑不插孤儿行
        assertTrue(c.contains("SELECT @fun_id + 0, m.id, @fun_id + 0"))
        assertTrue(c.contains("FROM sys_menu m WHERE m.code = 'BaseFactory'"))
        // KEY 反引号（INSERT 列清单与 WHERE 判重两处）
        assertTrue(c.contains("INSERT INTO sys_res_i18n (ID, `KEY`, TYPE, maintainer, MAINTAINTIME)"))
        // P2-7 修复：pid/seq 按父菜单名原子替换（SET 用户变量反查，不再留 TODO 占位），INSERT 用变量
        assertTrue(c.contains("SET @menu_pid = (SELECT id FROM sys_menu WHERE name = '基础数据'); -- 挂在哪个父菜单下必填"))
        assertTrue(c.contains("SET @seq = (SELECT IFNULL(MAX(id)+1, 1) FROM sys_menu WHERE pid = @menu_pid);"))
        assertTrue(c.contains("SELECT @menu_id, @menu_pid, '工厂', 'BaseFactory', 1, NULL, './Order/BaseFactory', 'icon-blank', @seq, NULL, @i18n_id, 'zh_CN'"))
        assertFalseContains(c, "/* TODO: 父菜单 pid */")
        assertFalseContains(c, "/* TODO: seq */")
    }

    /** 用例 E：toSnakeCase 驼峰转蛇形（连续大写按「后续紧跟小写才断词」收敛，ABCTest→abc_test） */
    @Test
    fun caseE_snakeCase() {
        assertEquals("base_factory", CodeGenerateService.toSnakeCase("BaseFactory"))
        assertEquals("part_warehouse", CodeGenerateService.toSnakeCase("PartWarehouse"))
        assertEquals("order_schedule", CodeGenerateService.toSnakeCase("OrderSchedule"))
        assertEquals("abc_test", CodeGenerateService.toSnakeCase("ABCTest"))
        assertEquals("factory", CodeGenerateService.toSnakeCase("Factory"))
        assertEquals("", CodeGenerateService.toSnakeCase(""))
    }

    private fun assertFalseContains(content: String, needle: String) {
        assertTrue("不应出现：$needle", !content.contains(needle))
    }

    private fun render(generateImport: Boolean, menuNameTw: String, menuNameEn: String): String {
        val sw = StringWriter()
        FreeMarkerConfiguration.getConfiguration().getTemplate("menu.sql.ftl").process(
            menuDataModel(generateImport, menuNameTw, menuNameEn), sw,
        )
        return sw.toString()
    }

    private fun goldenText(name: String): String {
        val bytes = javaClass.getResourceAsStream("/codegen-golden/$name")?.readBytes()
            ?: error("golden resource missing: /codegen-golden/$name")
        return String(bytes, Charsets.UTF_8)
    }

    private fun menuDataModel(generateImport: Boolean, menuNameTw: String, menuNameEn: String): Map<String, Any> {
        val dataGrid = linkedMapOf<String, Any>(
            "dataGridName" to "BaseFactory",
            "ObjectName" to "BaseFactory",
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
            "generateExport" to true,
            "menuNameZh" to "工厂",
            "menuNameTw" to menuNameTw,
            "menuNameEn" to menuNameEn,
            "menuFolder" to "Order",
            // P2-7 修复：父菜单名称（pid/seq 按此名反查；golden 测试数据补齐，如 基础数据）
            "parentMenuZh" to "基础数据",
            "menuSnakeKey" to CodeGenerateService.toSnakeCase("BaseFactory"),
            "menuButtons" to CodeGenerateService.buildMenuButtons(generateImport),
        )
    }
}
