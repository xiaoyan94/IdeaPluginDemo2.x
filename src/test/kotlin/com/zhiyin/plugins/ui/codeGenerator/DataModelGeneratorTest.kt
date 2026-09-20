package com.zhiyin.plugins.ui.codeGenerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 表名输入校验单测：SHOW CREATE TABLE 为字符串拼接，表名必须匹配 ^[A-Za-z0-9_.]+$；
 * null/空/含空格、引号、分号、中文等非法输入一律拒绝（返回可直接展示的错误原因，合法返回 null）。
 * 附带覆盖错误信息脱敏（sanitizeDbErrorMessage）与 JDBC URL host 提取（describeDbHost）。
 */
class DataModelGeneratorTest {

    // ---- validateDbTableName：合法输入 ----

    @Test
    fun validateDbTableName_legal() {
        assertNull(DataModelGenerator.validateDbTableName("biz_base_factory"))
        assertNull(DataModelGenerator.validateDbTableName("a"))
        assertNull(DataModelGenerator.validateDbTableName("A9_.x"))
        assertNull(DataModelGenerator.validateDbTableName("mesv3.biz_base_factory"))
        assertNull(DataModelGenerator.validateDbTableName("  biz_base_factory  ")) // 首尾空格先 trim 再校验
    }

    // ---- validateDbTableName：null / 空 ----

    @Test
    fun validateDbTableName_nullOrEmpty() {
        assertEquals("表名不能为空", DataModelGenerator.validateDbTableName(null))
        assertEquals("表名不能为空", DataModelGenerator.validateDbTableName(""))
        assertEquals("表名不能为空", DataModelGenerator.validateDbTableName("   "))
    }

    // ---- validateDbTableName：非法字符（空格/引号/分号/反引号/注释/中文/换行/运算符） ----

    @Test
    fun validateDbTableName_illegal() {
        val illegal = listOf(
            "biz table",            // 空格
            "biz'table",            // 单引号
            "\"biz\"",              // 双引号
            "biz;drop table x",     // 分号
            "`biz_base_factory`",   // 反引号
            "biz-- comment",        // SQL 行注释
            "biz/*1*/",             // SQL 块注释
            "表名",                  // 中文
            "biz\ntable",           // 换行
            "a=b",                  // 运算符
            "a(b)",                 // 括号
            "biz-base",             // 连字符
        )
        illegal.forEach { name ->
            assertEquals("输入[$name] 应被拒绝", "表名只能包含字母、数字、下划线和点号（输入: ${name.trim()}）",
                DataModelGenerator.validateDbTableName(name))
        }
    }

    // ---- sanitizeDbErrorMessage：屏蔽 MySQL 报错中的 user 'xxx'@ ----

    @Test
    fun sanitizeDbErrorMessage_masksUser() {
        assertEquals("Access denied for user '***'@'1.2.3.4' (using password: YES)",
            DataModelGenerator.sanitizeDbErrorMessage("Access denied for user 'root'@'1.2.3.4' (using password: YES)"))
    }

    @Test
    fun sanitizeDbErrorMessage_nullOrEmpty() {
        assertEquals("未知错误（详见 idea.log）", DataModelGenerator.sanitizeDbErrorMessage(null))
        assertEquals("未知错误（详见 idea.log）", DataModelGenerator.sanitizeDbErrorMessage(""))
    }

    // ---- describeDbHost：从 JDBC URL 提取 host:port，剔除内嵌凭据 ----

    @Test
    fun describeDbHost_extractsAuthority() {
        assertEquals("192.168.116.9:3306", DataModelGenerator.describeDbHost("jdbc:mysql://192.168.116.9:3306/dengqimesv3"))
        assertEquals("localhost:3306", DataModelGenerator.describeDbHost("jdbc:mysql://localhost:3306/db?useSSL=false"))
        assertEquals("***@10.0.0.1:3307", DataModelGenerator.describeDbHost("jdbc:mysql://user:secret@10.0.0.1:3307/db"))
        assertEquals("未知数据源", DataModelGenerator.describeDbHost(null))
        assertEquals("未知数据源", DataModelGenerator.describeDbHost("not-a-jdbc-url"))
    }

    // ---- parseDialogLength（P2-5）：编辑对话框打开时的长度解析 ----
    // "P,S" 只取精度部分；空串/非数字兜底 0——旧 Integer.parseInt 对 "19,4" 与 ""（datetime/enum 列）均抛 NumberFormatException

    @Test
    fun parseDialogLength_decimalScale_takesPrecisionPart() {
        assertEquals(19, DataModelGenerator.parseDialogLength("19,4"))
        assertEquals(10, DataModelGenerator.parseDialogLength("10,2"))
    }

    @Test
    fun parseDialogLength_plainAndIntegerForms() {
        assertEquals(19, DataModelGenerator.parseDialogLength("19"))
        assertEquals(128, DataModelGenerator.parseDialogLength(128)) // 编辑回写过的 Integer 形态
        assertEquals(18, DataModelGenerator.parseDialogLength(" 18 "))
    }

    @Test
    fun parseDialogLength_nullEmptyAndGarbage_fallbackZero() {
        assertEquals(0, DataModelGenerator.parseDialogLength(null))
        assertEquals("空串（datetime/enum 列）不抛异常，兜底 0", 0, DataModelGenerator.parseDialogLength(""))
        assertEquals(0, DataModelGenerator.parseDialogLength("ab,c"))
    }

    // ---- resolveEditedLength（P2-5）：编辑确认回写决策 ----
    // 原 "P,S" 且未改长度 → 原样带回（scale 不丢）；改了长度 → Integer 优先；无逗号/null → 对话框值

    @Test
    fun resolveEditedLength_unchanged_decimalScalePreserved() {
        assertEquals("19,4", DataModelGenerator.resolveEditedLength("19,4", 19))
        assertEquals("10,2", DataModelGenerator.resolveEditedLength("10,2", 10))
    }

    @Test
    fun resolveEditedLength_changed_userValueWins() {
        assertEquals(25, DataModelGenerator.resolveEditedLength("19,4", 25))
        assertEquals(64, DataModelGenerator.resolveEditedLength(128, 64))
    }

    @Test
    fun resolveEditedLength_noCommaOrNull_dialogValue() {
        assertEquals(19, DataModelGenerator.resolveEditedLength("19", 19))
        assertEquals(0, DataModelGenerator.resolveEditedLength("", 0))
        assertEquals(32, DataModelGenerator.resolveEditedLength(null, 32)) // rowIndex==-1 新增字段
        assertEquals(128, DataModelGenerator.resolveEditedLength(128, 128))
    }

    // ---- normalizeBoolean（P3-1）：is* 布尔键归一（仅 UI 表格层，TableParser 解析层不动） ----
    // fields 里 is* 键有 String（TableParser DDL/DB 路径）与 Boolean（computeIfAbsent 启发式、编辑弹窗回写）
    // 两形态；布尔列列类声明 Boolean 后，updateTableModel 填行前必须先归一

    @Test
    fun normalizeBoolean_booleanAsIs() {
        assertEquals(true, DataModelGenerator.normalizeBoolean(true))
        assertEquals(false, DataModelGenerator.normalizeBoolean(false))
    }

    @Test
    fun normalizeBoolean_stringIgnoreCaseTrue() {
        listOf("true", "TRUE", "True").forEach { s ->
            assertEquals("字符串[$s] 应归一为 true", true, DataModelGenerator.normalizeBoolean(s))
        }
    }

    @Test
    fun normalizeBoolean_stringFalseGarbageEmptyFallback() {
        listOf("false", "FALSE", "garbage", "", " true ").forEach { s ->
            assertEquals("字符串[$s] 应归一为 false（不 trim，Boolean.parseBoolean 语义）",
                false, DataModelGenerator.normalizeBoolean(s))
        }
    }

    @Test
    fun normalizeBoolean_nullAndOtherTypesFallbackFalse() {
        assertEquals(false, DataModelGenerator.normalizeBoolean(null))
        assertEquals(false, DataModelGenerator.normalizeBoolean(1))
    }

    // ---- EASYUI_CLASS_OPTIONS（P3-1，GATE-F 词表）----
    // 枚举 = DengqiMes 既有 Layout.xml 出现过的 easyuiClass 集合（按频次降序）；combotree 零出现不收；
    // 首项空串在下拉显示「（空）」——启发式对 int+id 等返回 ""，空值合法且常用

    @Test
    fun easyuiClassOptions_gateFVocabulary() {
        val options = DataModelGenerator.EASYUI_CLASS_OPTIONS.toList()
        assertEquals("首项是空值选项（下拉显示「（空）」，实际值为空串）", "", options.first())
        assertEquals(listOf(
            "easyui-combobox", "easyui-datebox", "easyui-textbox", "easyui-datetimebox", "easyui-numberbox",
            "easyui-timespinner", "easyui-validatebox", "easyui-filebox", "easyui-checkbox"), options.drop(1))
        assertTrue("combotree 零出现不进词表", options.none { it.contains("combotree", ignoreCase = true) })
    }

    @Test
    fun booleanColumns_exactlyFiveIsColumns() {
        assertEquals(setOf("isColumnField", "isQueryField", "isDialogField", "isRequired", "isEditHidden"),
            DataModelGenerator.BOOLEAN_COLUMNS)
    }

    @Test
    fun defaultQueryField_heuristics() {
        // 既有口径：code/name 结尾命中（精确名单在 computeIfAbsent 内先判，此处只测 endsWith 族）
        assertTrue(DataModelGenerator.defaultQueryField("factoryname", "string"))
        assertTrue(DataModelGenerator.defaultQueryField("factorycode", "string"))
        assertEquals(false, DataModelGenerator.defaultQueryField("orderno", "string"))
        // P2-7 扩展：status/state 结尾、datetime 类型列
        assertTrue(DataModelGenerator.defaultQueryField("status", "int"))
        assertTrue(DataModelGenerator.defaultQueryField("syncstatus", "int"))
        assertTrue(DataModelGenerator.defaultQueryField("maintaintime", "datetime"))
        // 不命中：失败日志表其余列形态
        assertEquals(false, DataModelGenerator.defaultQueryField("api", "string"))
        assertEquals(false, DataModelGenerator.defaultQueryField("retrycount", "int"))
        assertEquals(false, DataModelGenerator.defaultQueryField("createtime", "string")) // 非 datetime 类型不因名字命中
    }
}
