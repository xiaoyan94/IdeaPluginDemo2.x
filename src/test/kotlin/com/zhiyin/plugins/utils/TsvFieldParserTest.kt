package com.zhiyin.plugins.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-2 剪贴板 TSV 解析单测：表头自动跳过、type 词表宽容映射（varchar→string 等，
 * 词表与 TableParser.getType GATE-E 口径一致）、括号参数提取 length（P,S 保留 scale）、
 * 脏数据（空行/非法行）跳过计数不中断。
 */
class TsvFieldParserTest {

    // ---- 标准 3 列 TSV（含表头）一次成型 ----

    @Test
    fun parse_standardTsvWithHeader() {
        val tsv = "字段名\t类型\t说明\n" +
                "Code\tvarchar(64)\t产品编码\n" +
                "quantity\tint\t数量\n" +
                "price\tdecimal(19,4)\t单价\n" +
                "createdate\tdatetime\t创建时间\n" +
                "isvirtual\ttinyint\t是否虚拟"
        val result = TsvFieldParser.parse(tsv)

        assertTrue(result.headerSkipped)
        assertEquals(0, result.getSkippedTotal())
        assertEquals(5, result.validFields.size)

        val code = result.validFields[0]
        assertEquals("code", code["name"])            // name 小写化（与 parseCreateTable 出口同口径）
        assertEquals("string", code["type"])          // varchar→string
        assertEquals("64", code["length"])            // varchar(64)→"64"
        assertEquals("产品编码", code["comment"])

        val quantity = result.validFields[1]
        assertEquals("int", quantity["type"])
        assertEquals("", quantity["length"])          // 无括号参数 length 为空串

        assertEquals("decimal", result.validFields[2]["type"])
        assertEquals("19,4", result.validFields[2]["length"]) // decimal(19,4) 保留 scale（P2-5 语义）
        assertEquals("datetime", result.validFields[3]["type"])
        assertEquals("int", result.validFields[4]["type"])    // tinyint→int
    }

    @Test
    fun parse_noHeader_directData() {
        val tsv = "code\tvarchar(64)\t编码\nname\tstring\t名称"
        val result = TsvFieldParser.parse(tsv)

        assertFalse(result.headerSkipped)
        assertEquals(0, result.getSkippedTotal())
        assertEquals(2, result.validFields.size)
        assertEquals("code", result.validFields[0]["name"])
        assertEquals("string", result.validFields[1]["type"])
    }

    // ---- 表头判定边界：真字段 name/type 不误杀 ----

    @Test
    fun parse_headerDetection_englishHeaderRow() {
        // 首行 name/type/comment 是英文表头：col1 命中关键词且 type 不是合法类型词 → 跳过
        val tsv = "name\ttype\tcomment\ncode\tvarchar\t编码"
        val result = TsvFieldParser.parse(tsv)

        assertTrue(result.headerSkipped)
        assertEquals(1, result.validFields.size)
        assertEquals("code", result.validFields[0]["name"])
        assertEquals(0, result.getSkippedTotal()) // 表头跳过不算脏数据
    }

    @Test
    fun parse_headerDetection_realFieldNamedNameNotKilled() {
        // 真字段 name：col1 命中关键词但 col2 是合法类型词 → 数据行保留，不判表头
        val tsv = "name\tvarchar\t名称\ncode\tvarchar\t编码"
        val result = TsvFieldParser.parse(tsv)

        assertFalse(result.headerSkipped)
        assertEquals(2, result.validFields.size)
        assertEquals("name", result.validFields[0]["name"])
    }

    @Test
    fun parse_headerDetection_realFieldNamedTypeNotKilled() {
        // 真字段 type：col2=int 合法类型词 → 数据行保留
        val tsv = "type\tint\t类型\ncode\tvarchar\t编码"
        val result = TsvFieldParser.parse(tsv)

        assertFalse(result.headerSkipped)
        assertEquals(2, result.validFields.size)
        assertEquals("type", result.validFields[0]["name"])
        assertEquals("int", result.validFields[0]["type"])
    }

    // ---- type 缺省与列数容错 ----

    @Test
    fun parse_typeColumnEmpty_defaultsToString() {
        // col2 空 → type 缺省 string（需求表常不填类型）
        val tsv = "code\t\t编码\nname\tstring\t名称"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(2, result.validFields.size)
        assertEquals("string", result.validFields[0]["type"])
        assertEquals("", result.validFields[0]["length"])
    }

    @Test
    fun parse_singleColumnOnlyName_stillValid() {
        // 仅 1 列（只有 name）也合法：type 缺省 string、comment 空
        val tsv = "code\nname"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(2, result.validFields.size)
        assertEquals("string", result.validFields[0]["type"])
        assertEquals("", result.validFields[0]["comment"])
    }

    @Test
    fun parse_fourthColumnIgnored() {
        // 第 4 列及以后忽略（需求表常带「必填」等附加列）
        val tsv = "code\tvarchar(64)\t编码\t必填\t多余"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(1, result.validFields.size)
        assertEquals("编码", result.validFields[0]["comment"])
    }

    // ---- 非法行计数跳过（不中断整体解析） ----

    @Test
    fun parse_illegalTypeCountedAndSkipped() {
        // 非法 type：人读词「文本」、拼错的「xyz」均不在词表，跳过计数
        val tsv = "code\t文本\t编码\nname\txyz\t名称\nprice\tdecimal(19,4)\t单价"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(1, result.validFields.size)
        assertEquals("price", result.validFields[0]["name"])
        assertEquals(2, result.invalidLines)
        assertEquals(0, result.blankLines)
    }

    @Test
    fun parse_illegalNameCountedAndSkipped() {
        // name 非法：中文表头列混入中间、序号列（数字开头）均跳过计数
        val tsv = "code\tvarchar\t编码\n操作\tvarchar\t操作列\n1\tint\t序号列\nname\tvarchar\t名称"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(2, result.validFields.size)
        assertEquals("code", result.validFields[0]["name"])
        assertEquals("name", result.validFields[1]["name"])
        assertEquals(2, result.invalidLines)
    }

    // ---- 空行与换行容错 ----

    @Test
    fun parse_middleBlankLineCounted() {
        // 中间空行计入 blankLines，前后字段均保留
        val tsv = "code\tvarchar\t编码\n\nname\tvarchar\t名称"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(2, result.validFields.size)
        assertEquals(1, result.blankLines)
        assertEquals(0, result.invalidLines)
    }

    @Test
    fun parse_trailingNewlineNotDirty() {
        // 末尾换行产生的空尾行剥掉，不算脏数据（Excel 复制恒带末尾换行）
        val tsv = "code\tvarchar\t编码\nname\tvarchar\t名称\n"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(2, result.validFields.size)
        assertEquals(0, result.blankLines)
        assertEquals(0, result.invalidLines)
    }

    @Test
    fun parse_crlfNormalized() {
        // CRLF 剪贴板（Windows 默认）规范化后正常解析
        val tsv = "code\tvarchar(64)\t编码\r\nname\tstring\t名称\r\n"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(2, result.validFields.size)
        assertEquals("comment 不残留 \\r", "编码", result.validFields[0]["comment"])
        assertEquals(0, result.getSkippedTotal())
    }

    // ---- 词表边界映射 ----

    @Test
    fun parse_typeVocabularyEdges() {
        val tsv = "creattime\ttimestamp\t创建时间\nworktime\ttime\t工时\nqty\ttinyint\t数量\n" +
                "status\tenum\t状态\nratio\tdouble\t倍率\nflag\tbit\t标识\npcode\tchar(8)\t父编码"
        val result = TsvFieldParser.parse(tsv)

        assertEquals(0, result.invalidLines)
        val types = result.validFields.map { it["type"] as String }
        // timestamp/time → datetime；tinyint/bit → int；enum 原样；double → float；char → string
        assertEquals(listOf("datetime", "datetime", "int", "enum", "float", "int", "string"), types)
        assertEquals("8", result.validFields[6]["length"])
    }

    @Test
    fun parse_nullAndEmptyInput() {
        // 空剪贴板（null/空串）不出有效字段，也不报脏数据
        assertEquals(0, TsvFieldParser.parse(null).validFields.size)
        assertEquals(0, TsvFieldParser.parse("").validFields.size)
        assertEquals(0, TsvFieldParser.parse("\n\n\n").validFields.size) // 纯换行=尾空行剥掉
    }
}
