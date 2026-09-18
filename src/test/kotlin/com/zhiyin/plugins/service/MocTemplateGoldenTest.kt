package com.zhiyin.plugins.service

import com.zhiyin.plugins.utils.TableParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter

/**
 * P2-5 golden 快照护栏：Moc 模板（moc.ftl）类型映射精度增强后的 Field 行形态——
 * length 仅 string/decimal 输出（int(11)/datetime 的 length 在 map 有值但不输出，存量惯例红线）、
 * decimal 保留 scale（length="19,4"）、enum 输出 enum="列名" 且不带 length。
 *
 * 用例 A 为基线零漂移护栏：真实基线 DDL（docs/codegen-baseline/input/biz_base_factory.sql）
 * 喂 TableParser + moc.ftl 渲染，与基线 Moc 件（golden 为其字节级拷贝，CRLF）逐字节一致——
 * P2-5 新映射下 int→int、varchar→string、datetime→datetime、date→date，基线表无 decimal/enum，
 * 故 2.0.24 基线产物必须零变化。
 */
class MocTemplateGoldenTest {

    /** 用例 A：基线 DDL → 新解析 + 新模板渲染 == 基线 Moc 件字节级一致（P2-5 基线零漂移） */
    @Test
    fun caseA_baselineDdl_renderMatchesBaselineMocByteForByte() {
        val baselineDdl = File("docs/codegen-baseline/input/biz_base_factory.sql").readText(Charsets.UTF_8)
        val fields = TableParser.parseCreateTable(baselineDdl)
        assertEquals("基线表 30 列全部解析", 30, fields.size)

        val out = render("BaseFactory", "biz_base_factory", fields)
        assertEquals("基线零漂移：P2-5 后基线表 Moc 渲染须与 2.0.24 基线件逐字节一致",
                goldenText("BaseFactoryMoc.xml"), out)

        // 新映射抽查：基线表类型集（int(11)/varchar(N)/datetime/date）在新词表下映射不变
        val byName = fields.associate { it["name"] as String to it["type"] as String }
        assertEquals("int", byName["id"])
        assertEquals("string", byName["code"])
        assertEquals("datetime", byName["maintaintime"])
        assertEquals("date", byName["expiredate"])
    }

    /**
     * 用例 B：Field 行形态三件套 + 红线——string/decimal 输出 length，decimal 无 scale 或裸 decimal
     * 不输出 length，enum 输出 enum= 且不带 length；int/datetime 的 length 在 map 有值但不得输出
     * （存量惯例：基线 Moc 的 int/datetime 字段均无 length 属性）。
     */
    @Test
    fun caseB_fieldLineShapes_stringDecimalEnum() {
        val fields = listOf(
                field("name" to "code", "type" to "string", "length" to "64"),
                field("name" to "price", "type" to "decimal", "length" to "19,4"),
                field("name" to "rate", "type" to "decimal", "length" to "18"),
                field("name" to "amount", "type" to "decimal", "length" to ""),
                field("name" to "status", "type" to "enum", "length" to "", "enumRef" to "status"),
                field("name" to "id", "type" to "int", "length" to "11"),
                field("name" to "createtime", "type" to "datetime", "length" to ""),
                field("name" to "d", "type" to "date", "length" to ""),
                field("name" to "noEnumRef", "type" to "enum", "length" to "", "enumRef" to ""),
        )
        val lines = render("T", "t", fields).split("\r\n")

        assertTrue(lines.contains("        <Field name=\"code\" type=\"string\" length=\"64\"/>"))
        // decimal 保留 scale / 仅 P；裸 decimal（无括号）不输出 length
        assertTrue(lines.contains("        <Field name=\"price\" type=\"decimal\" length=\"19,4\"/>"))
        assertTrue(lines.contains("        <Field name=\"rate\" type=\"decimal\" length=\"18\"/>"))
        assertTrue(lines.contains("        <Field name=\"amount\" type=\"decimal\"/>"))
        // enum：输出 enum="列名"，不输出 length；enumRef 空串不输出 enum 属性
        assertTrue(lines.contains("        <Field name=\"status\" type=\"enum\" enum=\"status\"/>"))
        assertTrue(lines.contains("        <Field name=\"noEnumRef\" type=\"enum\"/>"))
        // 红线：int(11)/datetime 的 length 在 map 有值也不输出（改宽会破坏基线零漂移）
        assertTrue(lines.contains("        <Field name=\"id\" type=\"int\"/>"))
        assertTrue(lines.contains("        <Field name=\"createtime\" type=\"datetime\"/>"))
        assertTrue(lines.contains("        <Field name=\"d\" type=\"date\"/>"))
    }

    /**
     * 用例 C：编辑字段回写路径回归护栏——DataModelGenerator.showEditFieldDialog 的
     * updatedFieldMap 会把 length 以 Integer 写回 field map，模板判空用 ?string 适配
     * （FreeMarker 2.3.33 下 `Integer != \"\"` 抛 Can't compare values of these types，
     * EvalUtil.compare 对 number×string 不走 typeMismatchMeansNotEqual 分支）。
     */
    @Test
    fun caseC_editedField_integerLength_stillRenders() {
        val fields = listOf(
                field("name" to "code", "type" to "string", "length" to 128),
                field("name" to "price", "type" to "decimal", "length" to 0),
        )
        val lines = render("T", "t", fields).split("\r\n")
        assertTrue("编辑回写的 Integer length 仍输出",
                lines.contains("        <Field name=\"code\" type=\"string\" length=\"128\"/>"))
        assertTrue(lines.contains("        <Field name=\"price\" type=\"decimal\" length=\"0\"/>"))
    }

    /** 解析路径端到端：enum 列经 TableParser 产出 enumRef，模板落 enum= 属性（GATE-E 459 例形态） */
    @Test
    fun caseD_parsedEnumField_rendersEnumRef() {
        val ddl = "CREATE TABLE `t_enum_demo` (\n" +
                "  `id` int(11) NOT NULL AUTO_INCREMENT,\n" +
                "  `status` enum('0','1','2') DEFAULT '0',\n" +
                "  `price` decimal(19,4) DEFAULT NULL\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        val lines = render("T", "t_enum_demo", TableParser.parseCreateTable(ddl)).split("\r\n")
        assertTrue(lines.contains("        <Field name=\"status\" type=\"enum\" enum=\"status\"/>"))
        assertTrue(lines.contains("        <Field name=\"price\" type=\"decimal\" length=\"19,4\"/>"))
        assertTrue(lines.contains("        <Field name=\"id\" type=\"int\"/>"))
    }

    private fun render(mocName: String, tableName: String, fields: List<Map<String, Any>>): String {
        val sw = StringWriter()
        FreeMarkerConfiguration.getConfiguration().getTemplate("moc.ftl").process(
                linkedMapOf<String, Any>(
                        "mocName" to mocName,
                        "tableName" to tableName,
                        "fields" to fields,
                ), sw,
        )
        return sw.toString()
    }

    private fun goldenText(name: String): String {
        val bytes = javaClass.getResourceAsStream("/codegen-golden/$name")?.readBytes()
            ?: error("golden resource missing: /codegen-golden/$name")
        return String(bytes, Charsets.UTF_8)
    }

    private fun field(vararg pairs: Pair<String, Any?>): MutableMap<String, Any> {
        val map = linkedMapOf<String, Any>()
        pairs.forEach { (k, v) -> if (v != null) map[k] = v }
        return map
    }
}
