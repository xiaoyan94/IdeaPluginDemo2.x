package com.zhiyin.plugins.service

import com.intellij.lang.properties.psi.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.lang.reflect.Proxy

/**
 * P2-6 golden 快照护栏：Imp mapper 骨架（ImpMapper.ftl）与导入定义 SQL 草稿（import.sql.ftl）——
 * 排除列过滤 + col 连续 1..N + name 小写化 + required/description 属性渲染（空省略）+
 * i18n 属性（命中取 key 优先；未命中且 comment 非空兜底 i18n=comment 本身；无 comment 不输出——口径更新 2026-09-20）、
 * SQL 列序镜像 temp_imp_exception（clientid/factoryid/rowno 在前）与列长映射（>255 取 DDL 长度）、
 * XML 与 SQL 同表名（同一 tempTableName 变量）、幂等 INSERT（MAX+1 动态取号 + NOT EXISTS 判重）、
 * 菜单中文名复用与 `<ObjectName>导入` 兜底两分支；deriveTempTableName 推导与幂等性。
 *
 * golden 基线由实际渲染后 dump 定稿（P0-2 快照定稿法）——dataModel 与本测试 importGoldenDataModel
 * 完全一致，勿单边修改。快照修订记录：2026-09-20 i18n 兜底口径更新，BaseFactoryImpMapper.xml
 * 未命中列由「无 i18n 属性」修订为「i18n=中文名」（无 comment 列不变）。
 */
class ImportSkeletonTemplateGoldenTest {

    // ---------- 纯函数：deriveTempTableName ----------

    /** 用例 A：temp_imp 表名推导——biz_ 前缀剥离、无前缀直拼、temp_imp_ 形态幂等、null/空兜底、统一小写 */
    @Test
    fun caseA_deriveTempTableName() {
        assertEquals("temp_imp_base_factory", CodeGenerateService.deriveTempTableName("biz_base_factory"))
        // 无 biz_ 前缀：直接接 temp_imp_
        assertEquals("temp_imp_base_factory", CodeGenerateService.deriveTempTableName("base_factory"))
        // 已是 temp_imp_ 形态：幂等返回，不二次叠前缀
        assertEquals("temp_imp_base_factory", CodeGenerateService.deriveTempTableName("temp_imp_base_factory"))
        // 统一小写
        assertEquals("temp_imp_base_factory", CodeGenerateService.deriveTempTableName("BIZ_Base_Factory"))
        assertEquals("temp_imp_order_schedule", CodeGenerateService.deriveTempTableName("biz_order_schedule"))
        // null/空白兜底空串
        assertEquals("", CodeGenerateService.deriveTempTableName(null))
        assertEquals("", CodeGenerateService.deriveTempTableName("   "))
    }

    // ---------- 纯函数：buildImportColumns ----------

    /**
     * 用例 B：排除列过滤（9 个框架/审计列，小写比较容混大小写）+ col 连续 1..N + name 小写化
     * + required 映射（String "true" 与 Boolean true 两形态；"false"/缺省不输出）+ comment 空省略
     * description + i18n 命中取首个 key、未命中不输出
     */
    @Test
    fun caseB_buildImportColumns_filterAndAttributes() {
        val fields = listOf(
            field("name" to "ID", "type" to "int", "length" to "11", "comment" to "ID", "isRequired" to "true"),
            field("name" to "FactoryID", "type" to "int", "length" to "11", "comment" to "生产中心"),
            field("name" to "UseFlag", "type" to "int", "length" to "11"),
            field("name" to "delflag", "type" to "int", "length" to "11"),
            field("name" to "creator", "type" to "string", "length" to "128"),
            field("name" to "createtime", "type" to "datetime", "length" to ""),
            field("name" to "version", "type" to "string", "length" to "32"),
            field("name" to "maintainer", "type" to "string", "length" to "128"),
            field("name" to "maintaintime", "type" to "datetime", "length" to ""),
            // 业务列：混大小写名小写化 + required 两形态 + comment 命中/缺失/为空
            field("name" to "TypeName", "type" to "string", "length" to "64", "comment" to "异常类型名称", "isRequired" to "true"),
            field("name" to "TypeCode", "type" to "string", "length" to "64", "comment" to "异常类型代码"),
            field("name" to "Price", "type" to "decimal", "length" to "19,4", "comment" to "单价", "isRequired" to true),
            field("name" to "DefaultLang", "type" to "string", "length" to "32", "isRequired" to "false"),
        )
        val columns = CodeGenerateService.buildImportColumns(
            fields,
            mapOf("异常类型名称" to listOf(fakeProperty("com.zhiyin.mes.app.web.exception_typename"))),
        )

        // 排除 9 列后剩 4 列，col 连续 1..4，name 全小写
        assertEquals(4, columns.size)
        assertEquals(listOf(1, 2, 3, 4), columns.map { it["col"] })
        assertEquals(listOf("typename", "typecode", "price", "defaultlang"), columns.map { it["name"] })

        // required：String "true" 与 Boolean true 均输出；"false"/缺省不携带 key
        assertEquals(true, columns[0]["required"])
        assertEquals(true, columns[2]["required"])
        assertFalse(columns[1].containsKey("required"))
        assertFalse(columns[3].containsKey("required"))

        // description：comment 命中输出、为空/缺省不携带 key
        assertEquals("异常类型名称", columns[0]["description"])
        assertFalse(columns[3].containsKey("description"))

        // i18n：命中取首个 key（命中优先）；未命中且 comment 非空兜底 i18n=comment 本身（口径更新 2026-09-20）
        assertEquals("com.zhiyin.mes.app.web.exception_typename", columns[0]["i18nKey"])
        assertEquals("异常类型代码", columns[1]["i18nKey"])
        assertEquals("单价", columns[2]["i18nKey"])
        // 无 comment 字段（无 description）仍不输出 i18n（真实样例同款）
        assertFalse(columns[3].containsKey("i18nKey"))

        // length 供 SQL 草稿：decimal "19,4" 取精度 19 → 兜底 255；普通 64 → 255
        assertEquals(255, columns[0]["length"])
        assertEquals(255, columns[2]["length"])
    }

    /** 用例 B2：i18n 预查 map 为 null / 空 → 有 comment 字段兜底 i18n=comment、无 comment 字段仍无 i18n，不抛异常 */
    @Test
    fun caseB2_buildImportColumns_noI18nPrecompute() {
        val fields = listOf(
            field("name" to "code", "type" to "string", "length" to "64", "comment" to "工厂编号"),
            field("name" to "defaultlang", "type" to "string", "length" to "32"),
        )
        for (precomputed in listOf(null, emptyMap<String, List<Property>>())) {
            val columns = CodeGenerateService.buildImportColumns(fields, precomputed)
            assertEquals(2, columns.size)
            assertEquals("工厂编号", columns[0]["i18nKey"])
            assertFalse(columns[1].containsKey("i18nKey"))
        }
    }

    // ---------- 纯函数：resolveImportColumnLength ----------

    /** 用例 C：列长映射——默认 255，DDL 长度 > 255 取 DDL 长度；"P,S" 取精度部分；空/非数字/null 兜底 255 */
    @Test
    fun caseC_resolveImportColumnLength() {
        assertEquals(255, CodeGenerateService.resolveImportColumnLength("64"))
        assertEquals(255, CodeGenerateService.resolveImportColumnLength(null))
        assertEquals(255, CodeGenerateService.resolveImportColumnLength(""))
        assertEquals(255, CodeGenerateService.resolveImportColumnLength("19,4"))
        assertEquals(255, CodeGenerateService.resolveImportColumnLength("abc"))
        assertEquals(1024, CodeGenerateService.resolveImportColumnLength("1024"))
        assertEquals(1024, CodeGenerateService.resolveImportColumnLength(1024))
        assertEquals(512, CodeGenerateService.resolveImportColumnLength("512,2"))
        assertEquals(255, CodeGenerateService.resolveImportColumnLength(255))
    }

    // ---------- 纯函数：resolveImportDataModelName ----------

    /** 用例 D：DataModelName——有菜单中文名复用「<菜单中文名>导入」，null/空/paramsMap 无键走「<ObjectName>导入」兜底 */
    @Test
    fun caseD_resolveImportDataModelName() {
        assertEquals("工厂导入", CodeGenerateService.resolveImportDataModelName(mapOf("menuNameZh" to "工厂"), "BaseFactory"))
        assertEquals("BaseFactory导入", CodeGenerateService.resolveImportDataModelName(emptyMap(), "BaseFactory"))
        assertEquals("BaseFactory导入", CodeGenerateService.resolveImportDataModelName(mapOf("menuNameZh" to null), "BaseFactory"))
        assertEquals("BaseFactory导入", CodeGenerateService.resolveImportDataModelName(mapOf("menuNameZh" to "  "), "BaseFactory"))
        assertEquals("BaseFactory导入", CodeGenerateService.resolveImportDataModelName(null, "BaseFactory"))
    }

    // ---------- golden：Imp mapper XML ----------

    /** 用例 E：Imp mapper XML 渲染与 golden 逐字节一致，属性顺序 name/col/description/required/i18n；i18n 兜底口径（2026-09-20） */
    @Test
    fun caseE_goldenImpMapperXml() {
        val out = render("ImpMapper.ftl", importGoldenDataModel("工厂导入"))
        assertEquals(goldenText("BaseFactoryImpMapper.xml"), out)
        val c = out.replace("\r\n", "\n")
        // 结构照真实样例：mapper v1 + table name=temp_imp_；命中字段五属性齐全（命中优先 key）
        assertTrue(c.contains("<mapper version=\"v1\">"))
        assertTrue(c.contains("<table name=\"temp_imp_base_factory\">"))
        assertTrue(c.contains("<column name=\"code\" col=\"1\" description=\"工厂编号\" required=\"true\" i18n=\"com.zhiyin.mes.app.web.factory_code\"/>"))
        // 反查未命中且 comment 非空 → 兜底 i18n=中文名本身（口径更新 2026-09-20，不再省略）
        assertTrue(c.contains("<column name=\"name\" col=\"2\" description=\"工厂名称\" i18n=\"工厂名称\"/>"))
        assertTrue(c.contains("<column name=\"3rdflag\" col=\"6\" description=\"第三方推送工厂\" i18n=\"第三方推送工厂\"/>"))
        assertTrue(c.contains("<column name=\"price\" col=\"7\" description=\"单价\" required=\"true\" i18n=\"单价\"/>"))
        // comment 为空 → 无 description 且无 i18n（真实样例同款）；混大小写名小写化
        assertTrue(c.contains("<column name=\"defaultlang\" col=\"5\"/>"))
        // 排除列不出现（id/factoryid 等框架/审计列）
        assertFalse(c.contains("\"id\""))
        assertFalse(c.contains("factoryid"))
        assertFalse(c.contains("delflag"))
        assertFalse(c.contains("maintainer"))
    }

    // ---------- golden：导入定义 SQL 草稿 ----------

    /** 用例 F：SQL 草稿（菜单中文名复用分支）与 golden 逐字节一致——列序/类型映射/幂等 INSERT 形态 */
    @Test
    fun caseF_goldenImportDraftSql() {
        val out = render("import.sql.ftl", importGoldenDataModel("工厂导入"))
        assertEquals(goldenText("BaseFactoryImportDraft.sql"), out)
        val c = out.replace("\r\n", "\n")
        // 列序镜像 temp_imp_exception：clientid/factoryid/rowno 在前、业务列随后
        assertTrue(c.contains("CREATE TABLE IF NOT EXISTS `temp_imp_base_factory` ("))
        assertTrue(c.contains("  `clientid` varchar(255) DEFAULT NULL,\n  `factoryid` int(11) NOT NULL COMMENT '生产中心ID',\n  `rowno` varchar(255) DEFAULT NULL COMMENT '行号',"))
        // 类型映射：DDL 长度 > 255 取 DDL 长度；decimal 19,4 取精度 19 兜底 255；comment 为空无 COMMENT
        assertTrue(c.contains("  `note` varchar(1024) DEFAULT NULL COMMENT '备注',"))
        assertTrue(c.contains("  `price` varchar(255) DEFAULT NULL COMMENT '单价'"))
        assertTrue(c.contains("  `defaultlang` varchar(255) DEFAULT NULL,\n"))
        assertTrue(c.contains(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"))
        // 幂等 INSERT：MAX+1 动态取号、NOT EXISTS 按 DataModelCode 判重、TableName 同 temp_imp_
        assertTrue(c.contains("INSERT INTO utils_base_data_import_define (id, DataModelCode, DataModelName, TemplateMould, TemplateName, tablename)"))
        assertTrue(c.contains("SELECT (SELECT IFNULL(MAX(id), 0) + 1 FROM utils_base_data_import_define),"))
        assertTrue(c.contains("WHERE NOT EXISTS (SELECT 1 FROM utils_base_data_import_define WHERE DataModelCode = 'BaseFactory');"))
        // 菜单中文名复用：DataModelName=<菜单中文名>导入；TemplateMould=folder、TemplateName=Imp mapper 文件名
        assertTrue(c.contains("'BaseFactory', '工厂导入', 'Order', 'ImpBaseFactoryMapper.xml', 'temp_imp_base_factory'"))
    }

    /** 用例 G：兜底分支——无菜单中文名时 DataModelName=`<ObjectName>导入`；XML 与 SQL 表名同源一致 */
    @Test
    fun caseG_fallbackDataModelName_andSameTempTable() {
        val fallbackDataModel = importGoldenDataModel(CodeGenerateService.resolveImportDataModelName(emptyMap(), "BaseFactory"))
        val sql = render("import.sql.ftl", fallbackDataModel).replace("\r\n", "\n")
        assertTrue(sql.contains("'BaseFactory', 'BaseFactory导入', 'Order', 'ImpBaseFactoryMapper.xml', 'temp_imp_base_factory'"))
        // 同表名一致性：XML table name 与 SQL 建表处（反引号）/INSERT tablename 值（单引号）出自同一变量，
        // 渲染结果必须一致（单测锁定）
        val xml = render("ImpMapper.ftl", fallbackDataModel)
        val xmlTable = Regex("<table name=\"([^\"]+)\">").find(xml)!!.groupValues[1]
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `$xmlTable` ("))
        assertTrue(sql.contains("'ImpBaseFactoryMapper.xml', '$xmlTable'"))
        assertEquals(CodeGenerateService.deriveTempTableName("biz_base_factory"), xmlTable)
    }

    // ---------- 渲染与数据模型 ----------

    private fun render(templateName: String, dataModel: Map<String, Any>): String {
        val sw = StringWriter()
        FreeMarkerConfiguration.getConfiguration().getTemplate(templateName).process(dataModel, sw)
        return sw.toString()
    }

    private fun goldenText(name: String): String {
        val bytes = javaClass.getResourceAsStream("/codegen-golden/$name")?.readBytes()
            ?: error("golden resource missing: /codegen-golden/$name")
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * golden 数据模型——字段子集镜像 docs/codegen-baseline/input/biz_base_factory.sql（含混大小写名
     * DefaultLang/3rdFlag）并补 decimal/超长列两形态；i18n 仅「工厂编号」命中（非 datagrid 反查首个 key）。
     * 与 CodeGenerateService.generateBaseQueryTypeFile 勾导入时写入 dmLayout 的键一一对应。
     */
    private fun importGoldenDataModel(dataModelName: String): Map<String, Any> {
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
            "generateImport" to true,
            "generateExport" to true,
            "tempTableName" to CodeGenerateService.deriveTempTableName("biz_base_factory"),
            "importColumns" to CodeGenerateService.buildImportColumns(goldenFields, goldenImportI18n),
            "importDataModelName" to dataModelName,
            "importFolder" to "Order",
        )
    }

    private val goldenImportI18n: Map<String, List<Property>> =
        mapOf("工厂编号" to listOf(fakeProperty("com.zhiyin.mes.app.web.factory_code")))

    private val goldenFields = listOf(
        // 排除列（9 个框架/审计列，混大小写校验小写比较）
        field("name" to "id", "type" to "int", "length" to "11", "comment" to "ID"),
        field("name" to "factoryid", "type" to "int", "length" to "11", "comment" to "生产中心"),
        field("name" to "useflag", "type" to "int", "length" to "11"),
        field("name" to "delflag", "type" to "int", "length" to "11"),
        field("name" to "creator", "type" to "string", "length" to "128"),
        field("name" to "createtime", "type" to "datetime", "length" to ""),
        field("name" to "version", "type" to "string", "length" to "32"),
        field("name" to "maintainer", "type" to "string", "length" to "128"),
        field("name" to "maintaintime", "type" to "datetime", "length" to ""),
        // 业务列
        field("name" to "code", "type" to "string", "length" to "64", "comment" to "工厂编号", "isRequired" to "true"),
        field("name" to "name", "type" to "string", "length" to "64", "comment" to "工厂名称", "isRequired" to "false"),
        field("name" to "address", "type" to "string", "length" to "255", "comment" to "地址"),
        field("name" to "note", "type" to "string", "length" to "1024", "comment" to "备注"),
        field("name" to "DefaultLang", "type" to "string", "length" to "32"),
        field("name" to "3rdFlag", "type" to "string", "length" to "32", "comment" to "第三方推送工厂"),
        field("name" to "price", "type" to "decimal", "length" to "19,4", "comment" to "单价", "isRequired" to true),
    )

    private fun field(vararg pairs: Pair<String, Any>): Map<String, Any> = linkedMapOf(*pairs)

    /** 纯 JVM 单测无平台环境，Property（PSI 接口）以动态代理伪造——仅消费 getKey() */
    private fun fakeProperty(key: String): Property =
        Proxy.newProxyInstance(
            Property::class.java.classLoader,
            arrayOf(Property::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getKey" -> key
                "equals" -> proxy === args?.get(0)
                "hashCode" -> key.hashCode()
                "toString" -> "Property(key=$key)"
                else -> null
            }
        } as Property
}
