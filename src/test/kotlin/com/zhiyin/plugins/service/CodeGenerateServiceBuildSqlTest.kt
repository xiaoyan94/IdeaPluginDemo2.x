package com.zhiyin.plugins.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-7 纯逻辑单测护栏（同包访问包级静态方法）：
 * - buildAutoSelectSql：DB/DDL 两路径 select 拼装收敛（datetime/timestamp → DATE_FORMAT 到秒、
 *   date → DATE_FORMAT 到日（精确 equals，防 contains 把 datetime 误匹配进日期分支）、
 *   普通列传裸列、存在 id 字段追加 order by a.id desc、无 id 不加、type 大小写/空白归一）
 * - collectDictTransformFields：name 小写 endsWith state/status 触发（与 Layout dsp 列同口径），
 *   pcode=PascalCase(字段名)；name=type 等普通字段不触发；无命中返回空表
 */
class CodeGenerateServiceBuildSqlTest {

    private fun fields(vararg pairs: Pair<String, String>): List<Map<String, Any>> =
        pairs.map { linkedMapOf("name" to it.first, "type" to it.second) }

    @Test
    fun buildAutoSelectSql_datetimeTimestampDatePlainMixed() {
        val sql = CodeGenerateService.buildAutoSelectSql(
            "biz_demo",
            fields(
                "id" to "int",
                "code" to "string",
                "startdate" to "date",
                "createtime" to "datetime",
                "synctime" to "timestamp",
                "price" to "decimal",
            ),
        )
        assertEquals(
            "select a.id,a.code,DATE_FORMAT(a.startdate, '%Y-%m-%d') as startdate," +
                "DATE_FORMAT(a.createtime, '%Y-%m-%d %H:%i:%s') as createtime," +
                "DATE_FORMAT(a.synctime, '%Y-%m-%d %H:%i:%s') as synctime,a.price" +
                " \nfrom biz_demo a\norder by a.id desc",
            sql,
        )
    }

    @Test
    fun buildAutoSelectSql_noIdField_noOrderBy() {
        val sql = CodeGenerateService.buildAutoSelectSql(
            "v_demo",
            fields(
                "code" to "string",
                "status" to "int",
            ),
        )
        assertEquals("select a.code,a.status \nfrom v_demo a", sql)
    }

    @Test
    fun buildAutoSelectSql_typeCaseAndWhitespaceNormalized() {
        // TableParser.getType 已归一形态外的防御：大写/带空白 type 同样按 datetime/date 处理
        val sql = CodeGenerateService.buildAutoSelectSql(
            "biz_demo",
            fields(
                "createtime" to "DATETIME",
                "maintaindate" to " Date ",
            ),
        )
        assertEquals(
            "select DATE_FORMAT(a.createtime, '%Y-%m-%d %H:%i:%s') as createtime," +
                "DATE_FORMAT(a.maintaindate, '%Y-%m-%d') as maintaindate \nfrom biz_demo a",
            sql,
        )
    }

    @Test
    fun buildAutoSelectSql_datetimeNotMisroutedToDateBranch() {
        // 精确 equals 边界：datetime 绝不走 date 分支（contains 会误匹配），别名保持原列名
        val sql = CodeGenerateService.buildAutoSelectSql("t", fields("createtime" to "datetime"))
        assertTrue(sql.contains("'%Y-%m-%d %H:%i:%s'"))
        assertFalse(sql.contains("'%Y-%m-%d')"))
        assertTrue(sql.contains(") as createtime"))
    }

    /**
     * 验收口径 DDL：iqc_incoming_check_upgrade.sql:180-198 sync_erp_iqc_fail_log（TableParser.getType
     * 归一后形态：varchar→string、datetime→datetime、int→int）——4 个 datetime 列包 DATE_FORMAT + id 排序
     */
    @Test
    fun buildAutoSelectSql_syncErpIqcFailLogDdlSubset() {
        val sql = CodeGenerateService.buildAutoSelectSql(
            "sync_erp_iqc_fail_log",
            fields(
                "id" to "int",
                "api" to "string",
                "url" to "string",
                "param" to "string",
                "result" to "string",
                "error" to "string",
                "createtime" to "datetime",
                "maintaintime" to "datetime",
                "status" to "int",
                "delflag" to "int",
                "retrycount" to "int",
                "lastretrytime" to "datetime",
                "successtime" to "datetime",
                "finalfailflag" to "int",
            ),
        )
        assertEquals(
            "select a.id,a.api,a.url,a.param,a.result,a.error," +
                "DATE_FORMAT(a.createtime, '%Y-%m-%d %H:%i:%s') as createtime," +
                "DATE_FORMAT(a.maintaintime, '%Y-%m-%d %H:%i:%s') as maintaintime," +
                "a.status,a.delflag,a.retrycount," +
                "DATE_FORMAT(a.lastretrytime, '%Y-%m-%d %H:%i:%s') as lastretrytime," +
                "DATE_FORMAT(a.successtime, '%Y-%m-%d %H:%i:%s') as successtime,a.finalfailflag" +
                " \nfrom sync_erp_iqc_fail_log a\norder by a.id desc",
            sql,
        )
    }

    @Test
    fun collectDictTransformFields_stateStatusTrigger() {
        val result = CodeGenerateService.collectDictTransformFields(
            fields(
                "code" to "string",
                "orderstate" to "int",
                "syncstatus" to "int",
                "status" to "int",
            ),
        )
        assertEquals(
            listOf(
                linkedMapOf("name" to "orderstate", "pcode" to "Orderstate"),
                linkedMapOf("name" to "syncstatus", "pcode" to "Syncstatus"),
                linkedMapOf("name" to "status", "pcode" to "Status"),
            ),
            result,
        )
    }

    @Test
    fun collectDictTransformFields_typeFieldNotTrigger() {
        // name=type/delflag/finalfailflag/statusdsp 均不 endsWith state/status（statusdsp 是 dsp 列不是状态列）
        val result = CodeGenerateService.collectDictTransformFields(
            fields(
                "type" to "string",
                "delflag" to "int",
                "statusdsp" to "string",
                "maintaintime" to "datetime",
            ),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun collectDictTransformFields_emptyWhenNoStateStatusField() {
        assertTrue(CodeGenerateService.collectDictTransformFields(emptyList()).isEmpty())
        assertTrue(
            CodeGenerateService.collectDictTransformFields(
                fields("code" to "string", "qty" to "decimal"),
            ).isEmpty(),
        )
    }
}
