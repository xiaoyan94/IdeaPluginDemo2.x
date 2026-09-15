package com.zhiyin.plugins.service

import com.zhiyin.plugins.utils.TableParserTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-2 纯逻辑单测护栏：CodeGenerateService.formatSql 当前行为快照（须与本包同包，访问包级静态方法）。
 * 已知瑕疵如实锁定（P3-6 改造时的基线）：
 * - 函数参数内逗号在嵌套括号下被误切（负向断言 `(?![^()]*\))` 只挡单层括号）
 * - 子查询的 SELECT/FROM/WHERE 同样被顶层规则换行，`(` 独占一行
 * - AND/OR 保留原大小写（$1 反向引用），其余关键字统一替换为大写规范形
 * - 每行前置两个 tab（8 空格视觉缩进）
 */
class CodeGenerateServiceFormatSqlTest {

    @Test
    fun formatSql_emptyAndBlank() {
        assertEquals("", CodeGenerateService.formatSql(null))
        assertEquals("", CodeGenerateService.formatSql(""))
        assertEquals("", CodeGenerateService.formatSql("   "))
    }

    @Test
    fun formatSql_autoBuiltSelect_snapshot() {
        // 生成器自动拼装的 select（DataModelGenerator.fetchFieldsFromTableSQL 同构输入）
        val input = "select a.id,a.code,a.name,a.maintainer,a.maintaintime,a.address,a.price \nfrom biz_base_factory a"
        val expected = listOf(
            "\t\tSELECT a.id,",
            "\t\ta.code,",
            "\t\ta.name,",
            "\t\ta.maintainer,",
            "\t\ta.maintaintime,",
            "\t\ta.address,",
            "\t\ta.price",
            "\t\tFROM biz_base_factory a",
        ).joinToString("\n")
        assertEquals(expected, CodeGenerateService.formatSql(input))
    }

    @Test
    fun formatSql_mainSampleDQL_snapshot() {
        // TableParser.java:268-275 main() 的 DQL 样例
        val expected = listOf(
            "\t\tSELECT 'sotMaterialReciept' as operatetype,",
            "\t\tdate(a.maintaintime) as date,",
            "\t\tsubstring_index(substring_index(maintainer,",   // 函数内逗号被误切（嵌套括号）【P3-6 待改】
            "\t\t'(', -1), ')', 1) as warehousemanager,",
            "\t\tcount(1) as times",
            "\t\tFROM biz_wms_material_receipt_barcode a",
            "\t\tWHERE a.maintaintime > '2024-11-01 00:00:00'",
            "\t\tand a.maintaintime < '2024-11-01 23:59:59'",    // and 保留原小写
            "\t\tGROUP BY 'sotMaterialReciept',",
            "\t\tdate(a.maintaintime),",
            "\t\ta.maintainer",
        ).joinToString("\n")
        assertEquals(expected, CodeGenerateService.formatSql(TableParserTest.SAMPLE_DQL))
    }

    @Test
    fun formatSql_subqueryAndCaseWhen_snapshot() {
        // 子查询 + case when：当前行为会被切碎【P3-6 待改，届时显式更新此断言】
        val input = "select a.code, case when a.status = 'fsValid' then 1 else 0 end as valid, " +
                "(select count(1) from biz_base_factory b where b.id = a.id) as cnt " +
                "from biz_base_factory a where a.delflag = 0 and (a.code like '%x%' or a.name like '%y%') order by a.createtime desc"
        val expected = listOf(
            "\t\tSELECT a.code,",
            "\t\tcase when a.status = 'fsValid' then 1 else 0 end as valid,",
            "\t\t(",
            "\t\tSELECT count(1)",
            "\t\tFROM biz_base_factory b",
            "\t\tWHERE b.id = a.id) as cnt",
            "\t\tFROM biz_base_factory a",
            "\t\tWHERE a.delflag = 0",
            "\t\tand (a.code like '%x%'",
            "\t\tor a.name like '%y%')",
            "\t\tORDER BY a.createtime desc",
        ).joinToString("\n")
        assertEquals(expected, CodeGenerateService.formatSql(input))
    }
}
