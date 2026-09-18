package com.zhiyin.plugins.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-2 纯逻辑单测护栏：TableParser.parseCreateTable / parseDQL 当前行为快照。
 * 断言以 2.0.24 实测行为为准锁定（decimal(19,4) 丢 scale 等瑕疵已随 P2-5 显式更新——
 * GATE-E 词表：DengqiMes 1245 个既有 Moc XML 统计定案，decimal→decimal 且 length 保留 scale）。
 * 样例取自 TableParser.java:218 main() 的 biz_product DDL 与 DQL 原文。
 */
class TableParserTest {

    companion object {
        /** TableParser.java:219-259 main() 中的 biz_product 建表 DDL 原文 */
        val BIZ_PRODUCT_DDL: String = "CREATE TABLE `biz_product` (\n" +
                "  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT 'ID',\n" +
                "  `code` varchar(128) NOT NULL COMMENT '产品编码',\n" +
                "  `name` varchar(512) DEFAULT NULL COMMENT '产品名称',\n" +
                "  `model` varchar(512) DEFAULT NULL COMMENT '产品机型',\n" +
                "  `colour` varchar(32) DEFAULT NULL COMMENT '颜色',\n" +
                "  `note` varchar(255) DEFAULT NULL COMMENT '备注',\n" +
                "  `maintainer` varchar(64) DEFAULT NULL COMMENT '维护人',\n" +
                "  `maintaintime` datetime DEFAULT NULL,\n" +
                "  `safetystock` varchar(64) DEFAULT NULL COMMENT '安全库存量',\n" +
                "  `factoryid` int(11) DEFAULT NULL COMMENT '所属工厂ID',\n" +
                "  `producttype` varchar(32) DEFAULT NULL,\n" +
                "  `groupingid` int(11) DEFAULT '-1' COMMENT '分组管理：分组ID',\n" +
                "  `defaultwarehouse` varchar(64) DEFAULT NULL,\n" +
                "  `defaultstorage` varchar(64) DEFAULT NULL,\n" +
                "  `issync` int(11) DEFAULT '0',\n" +
                "  `price` decimal(19,4) DEFAULT NULL,\n" +
                "  `saleprice` decimal(10,2) DEFAULT NULL COMMENT '销售单价',\n" +
                "  `processingprice` decimal(19,4) DEFAULT NULL,\n" +
                "  `packrulequantity` varchar(32) DEFAULT NULL COMMENT '包规数量',\n" +
                "  `minstock` varchar(16) DEFAULT NULL COMMENT '最低库存',\n" +
                "  `maxstock` varchar(16) DEFAULT NULL COMMENT '最高库存',\n" +
                "  `property` varchar(32) DEFAULT NULL COMMENT '物料属性',\n" +
                "  `weight` decimal(10,4) DEFAULT NULL COMMENT '产品重量',\n" +
                "  `weightunit` varchar(32) DEFAULT NULL COMMENT '单重单位',\n" +
                "  `unit` varchar(32) DEFAULT NULL COMMENT '基础单位',\n" +
                "  `pickingmethod` varchar(32) DEFAULT NULL COMMENT '领料方式',\n" +
                "  `isvirtual` int(11) DEFAULT '0' COMMENT '是否虚拟物料：是：1 否：0',\n" +
                "  `shelflife` int(11) DEFAULT NULL COMMENT '保质期',\n" +
                "  `purchaseadvtime` int(11) DEFAULT NULL COMMENT '采购提前期',\n" +
                "  `deliveryadvtime` int(11) DEFAULT NULL COMMENT '隔离期',\n" +
                "  `timeunit` varchar(32) DEFAULT NULL COMMENT '时间单位',\n" +
                "  `colourid` int(11) DEFAULT NULL COMMENT '关联颜色ID',\n" +
                "  `laborused` decimal(11,3) DEFAULT NULL COMMENT '人力资源',\n" +
                "  `delflag` int(2) DEFAULT '0' COMMENT '删除标识',\n" +
                "  PRIMARY KEY (`id`) USING BTREE,\n" +
                "  KEY `index_biz_product` (`factoryid`,`code`) USING BTREE,\n" +
                "  KEY `index_product_type_1` (`producttype`) USING BTREE,\n" +
                "  KEY `index_product_type` (`factoryid`,`producttype`,`property`,`weightunit`) USING BTREE,\n" +
                "  KEY `idx_product_groupingid` (`factoryid`,`groupingid`) COMMENT '分组管理：分组ID'\n" +
                ") ENGINE=InnoDB AUTO_INCREMENT=781740 DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='产品表'"

        /** TableParser.java:268-275 main() 中的 DQL 样例原文 */
        val SAMPLE_DQL: String = "select 'sotMaterialReciept' as operatetype, " +
                "date(a.maintaintime) as date, " +
                "substring_index(substring_index(maintainer, '(', -1), ')', 1) as warehousemanager, " +
                "count(1) as times " +
                "from biz_wms_material_receipt_barcode a " +
                "where a.maintaintime > '2024-11-01 00:00:00' " +
                "and a.maintaintime < '2024-11-01 23:59:59' " +
                "group by 'sotMaterialReciept', date(a.maintaintime), a.maintainer"

        /** docs/codegen-baseline/input/biz_base_factory.sql 的建表 DDL 原文（含大小写混杂列名） */
        val BIZ_BASE_FACTORY_DDL: String = "CREATE TABLE `biz_base_factory` (\n" +
                "  `id` int(11) NOT NULL COMMENT 'ID',\n" +
                "  `code` varchar(32) NOT NULL COMMENT '工厂编号',\n" +
                "  `name` varchar(64) DEFAULT NULL COMMENT '工厂名称',\n" +
                "  `maintainer` varchar(128) DEFAULT NULL,\n" +
                "  `maintaintime` datetime DEFAULT NULL,\n" +
                "  `address` varchar(255) DEFAULT NULL COMMENT '地址',\n" +
                "  `country` varchar(64) DEFAULT NULL COMMENT '国家',\n" +
                "  `province` varchar(32) DEFAULT NULL COMMENT '省',\n" +
                "  `industry` varchar(32) DEFAULT NULL COMMENT '行业类别',\n" +
                "  `contact` varchar(255) DEFAULT NULL COMMENT '联系人',\n" +
                "  `contactphone` varchar(32) DEFAULT NULL COMMENT '联系手机',\n" +
                "  `longitude` varchar(32) DEFAULT NULL COMMENT '经度',\n" +
                "  `latitude` varchar(32) DEFAULT NULL COMMENT '纬度',\n" +
                "  `note` varchar(255) DEFAULT NULL COMMENT '备注',\n" +
                "  `delflag` int(11) DEFAULT '0',\n" +
                "  `DefaultLang` varchar(32) DEFAULT NULL,\n" +
                "  `creator` varchar(128) DEFAULT NULL,\n" +
                "  `createtime` datetime DEFAULT NULL,\n" +
                "  `scode` varchar(64) DEFAULT NULL,\n" +
                "  `3rdFlag` varchar(32) DEFAULT NULL COMMENT '第三方推送工厂',\n" +
                "  `version` varchar(32) DEFAULT NULL,\n" +
                "  `datagettype` varchar(12) DEFAULT NULL COMMENT '数据采集方式 DataGetType',\n" +
                "  `status` varchar(32) DEFAULT 'fsValid',\n" +
                "  `city` varchar(64) DEFAULT NULL COMMENT '城市',\n" +
                "  `ExpireDate` date DEFAULT NULL COMMENT '失效日期',\n" +
                "  `Industrysize` varchar(32) DEFAULT NULL COMMENT '行业规模',\n" +
                "  `PurchasePeriod` int(11) DEFAULT NULL COMMENT '购买年限',\n" +
                "  `filename` varchar(64) DEFAULT NULL COMMENT 'logo',\n" +
                "  `aliasName` varchar(128) DEFAULT NULL COMMENT '生产中心别名',\n" +
                "  `EffectDate` date DEFAULT NULL COMMENT '生效日期',\n" +
                "  PRIMARY KEY (`id`) USING BTREE\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工厂'"
    }

    @Test
    fun parseCreateTable_bizProduct_snapshot() {
        val columns = TableParser.parseCreateTable(BIZ_PRODUCT_DDL)

        // PRIMARY KEY / KEY 行不产生列；34 个真实列全部被解析
        assertEquals(34, columns.size)

        val expect = arrayOf(
            // name, type, length, comment, isRequired, nullable
            arrayOf("id", "int", "11", "ID", "true", "false"),
            arrayOf("code", "string", "128", "产品编码", "true", "false"),
            arrayOf("name", "string", "512", "产品名称", "false", "true"),
            arrayOf("model", "string", "512", "产品机型", "false", "true"),
            arrayOf("colour", "string", "32", "颜色", "false", "true"),
            arrayOf("note", "string", "255", "备注", "false", "true"),
            arrayOf("maintainer", "string", "64", "维护人", "false", "true"),
            arrayOf("maintaintime", "datetime", "", "", "false", "true"),   // 无长度无 comment → 空串
            arrayOf("safetystock", "string", "64", "安全库存量", "false", "true"),
            arrayOf("factoryid", "int", "11", "所属工厂ID", "false", "true"),
            arrayOf("producttype", "string", "32", "", "false", "true"),
            arrayOf("groupingid", "int", "11", "分组管理：分组ID", "false", "true"), // DEFAULT '-1' 不影响 nullable
            arrayOf("defaultwarehouse", "string", "64", "", "false", "true"),
            arrayOf("defaultstorage", "string", "64", "", "false", "true"),
            arrayOf("issync", "int", "11", "", "false", "true"),
            arrayOf("price", "decimal", "19,4", "", "false", "true"),       // P2-5 类型映射精度增强，快照显式更新：decimal→decimal+length 保留 scale（旧：number/19）
            arrayOf("saleprice", "decimal", "10,2", "销售单价", "false", "true"),
            arrayOf("processingprice", "decimal", "19,4", "", "false", "true"),
            arrayOf("packrulequantity", "string", "32", "包规数量", "false", "true"),
            arrayOf("minstock", "string", "16", "最低库存", "false", "true"),
            arrayOf("maxstock", "string", "16", "最高库存", "false", "true"),
            arrayOf("property", "string", "32", "物料属性", "false", "true"),
            arrayOf("weight", "decimal", "10,4", "产品重量", "false", "true"),
            arrayOf("weightunit", "string", "32", "单重单位", "false", "true"),
            arrayOf("unit", "string", "32", "基础单位", "false", "true"),
            arrayOf("pickingmethod", "string", "32", "领料方式", "false", "true"),
            arrayOf("isvirtual", "int", "11", "是否虚拟物料：是：1 否：0", "false", "true"),
            arrayOf("shelflife", "int", "11", "保质期", "false", "true"),
            arrayOf("purchaseadvtime", "int", "11", "采购提前期", "false", "true"),
            arrayOf("deliveryadvtime", "int", "11", "隔离期", "false", "true"),
            arrayOf("timeunit", "string", "32", "时间单位", "false", "true"),
            arrayOf("colourid", "int", "11", "关联颜色ID", "false", "true"),
            arrayOf("laborused", "decimal", "11,3", "人力资源", "false", "true"), // P2-5：decimal(11,3)→decimal，length="11,3"
            arrayOf("delflag", "int", "2", "删除标识", "false", "true"),
        )
        columns.forEachIndexed { i, col ->
            assertEquals("列[$i] ${expect[i][0]} 键集合快照", 6, col.size)
            assertEquals(expect[i][0], col["name"])
            assertEquals(expect[i][1], col["type"])
            assertEquals(expect[i][2], col["length"])
            assertEquals(expect[i][3], col["comment"])
            assertEquals(expect[i][4], col["isRequired"])
            assertEquals(expect[i][5], col["nullable"])
        }
    }

    /**
     * P1-9：DDL 粘贴输入框是单行 JTextField，多行 DDL 粘贴后换行被剥离成单行，旧整段正则
     * 依赖「. 不跨行」保证 comment 归属，单行化后无 COMMENT 的字段（maintainer 等）会
     * 跨字段抢走后面第一个 COMMENT，注释整体错位（基线 BaseFactory.xml 即该错位形态）。
     * 断言「多行原样」「\n→空格单行化」「\r\n→空格单行化」三形态解析结果完全一致。
     */
    @Test
    fun parseCreateTable_singleLineDdl_commentNotShifted() {
        val multiLine = BIZ_BASE_FACTORY_DDL
        val singleLineFromLf = multiLine.replace("\n", " ")
        val singleLineFromCrLf = multiLine.replace("\n", "\r\n").replace("\r\n", " ")

        val byForm = listOf(multiLine, singleLineFromLf, singleLineFromCrLf)
                .map { TableParser.parseCreateTable(it) }

        byForm.forEach { assertEquals("30 个真实列（PRIMARY KEY 行不进结果）", 30, it.size) }
        // 三形态字段列表（含 comment）逐列完全一致
        assertEquals(byForm[0], byForm[1])
        assertEquals(byForm[0], byForm[2])

        // 字段列表与 DDL 顺序一致（name 小写口径不变）
        val expectedNames = listOf(
                "id", "code", "name", "maintainer", "maintaintime", "address", "country", "province",
                "industry", "contact", "contactphone", "longitude", "latitude", "note", "delflag",
                "defaultlang", "creator", "createtime", "scode", "3rdflag", "version", "datagettype",
                "status", "city", "expiredate", "industrysize", "purchaseperiod", "filename",
                "aliasname", "effectdate")
        assertEquals(expectedNames, byForm[0].map { it["name"] })

        // 修复前单行形态实锤错位的字段：comment 与 DDL 注释逐一对应，不再串位
        val comments = byForm[0].associate { it["name"] as String to it["comment"] as String }
        assertEquals("", comments["maintainer"])
        assertEquals("", comments["maintaintime"])
        assertEquals("地址", comments["address"])
        assertEquals("", comments["delflag"])
        assertEquals("第三方推送工厂", comments["3rdflag"])
        assertEquals("", comments["version"])
        assertEquals("数据采集方式 DataGetType", comments["datagettype"])
        assertEquals("", comments["status"])
        assertEquals("城市", comments["city"])
    }

    /** P1-9：KEY 定义行的 COMMENT 不得污染列结果——idx_x 不是列、索引注释不挂到任何列 */
    @Test
    fun parseCreateTable_keyDefinitionComment_notAttachedToColumn() {
        val ddl = "CREATE TABLE `t_key_demo` (\n" +
                "  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT 'ID',\n" +
                "  `a` varchar(32) DEFAULT NULL COMMENT '列A',\n" +
                "  `b` varchar(32) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`) USING BTREE,\n" +
                "  KEY `idx_x` (`a`,`b`) COMMENT '索引注释'\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试表'"

        // 多行与单行化两种形态（旧实现在单行形态下 b 会被抢挂 '索引注释'）
        for (form in listOf(ddl, ddl.replace("\n", " "))) {
            val columns = TableParser.parseCreateTable(form)
            assertEquals(3, columns.size)
            assertTrue("idx_x 不在字段结果里", columns.none { it["name"] == "idx_x" })
            assertTrue("索引注释未挂到任何列", columns.none { it["comment"] == "索引注释" })
        }
        val byName = TableParser.parseCreateTable(ddl)
                .associate { it["name"] as String to it["comment"] as String }
        assertEquals("ID", byName["id"])
        assertEquals("列A", byName["a"])
        assertEquals("", byName["b"])
    }

    /** P1-9：decimal(19,4) 类型内逗号曾使旧 nullable 正则 [^,]+ 提前截断、NOT NULL 检测失效 */
    @Test
    fun parseCreateTable_decimalNotNull_nullableDetected() {
        val ddl = "CREATE TABLE `t_decimal_demo` (\n" +
                "  `price` decimal(19,4) NOT NULL,\n" +
                "  `saleprice` decimal(10,2) DEFAULT NULL\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"

        // 多行与单行化两种形态（旧实现在两种形态下都误判 price nullable=true）
        for (form in listOf(ddl, ddl.replace("\n", " "))) {
            val byName = TableParser.parseCreateTable(form)
                    .associate { it["name"] as String to it }
            byName["price"]!!.let {
                assertEquals("false", it["nullable"])
                assertEquals("true", it["isRequired"])
            }
            byName["saleprice"]!!.let {
                assertEquals("true", it["nullable"])
                assertEquals("false", it["isRequired"])
            }
        }
    }

    /** P1-9：COMMENT 值内 '' 转义单引号须还原为 '，值内逗号不得切断片段 */
    @Test
    fun parseCreateTable_commentWithEscapedQuoteAndComma() {
        val ddl = "CREATE TABLE `t_quote_demo` (\n" +
                "  `note` varchar(255) DEFAULT NULL COMMENT '备注，含''引号''与,逗号',\n" +
                "  `name` varchar(32) DEFAULT NULL COMMENT '名称'\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"

        for (form in listOf(ddl, ddl.replace("\n", " "))) {
            val columns = TableParser.parseCreateTable(form)
            assertEquals("注释内逗号不切断片段，两列齐全", 2, columns.size)
            assertEquals("note", columns[0]["name"])
            assertEquals("备注，含'引号'与,逗号", columns[0]["comment"])
            assertEquals("name", columns[1]["name"])
            assertEquals("名称", columns[1]["comment"])
        }
    }

    /** P2-5：getType GATE-E 词表全覆盖——映射不得臆造，逐项对应 DengqiMes 1245 个 Moc XML 统计结论 */
    @Test
    fun getType_gateEVocabulary_fullCoverage() {
        // string 族（string 7427 例主流；词表无 text 原名）
        assertEquals("string", TableParser.getType("varchar"))
        assertEquals("string", TableParser.getType("char"))
        assertEquals("string", TableParser.getType("text"))
        assertEquals("string", TableParser.getType("tinytext"))
        assertEquals("string", TableParser.getType("mediumtext"))
        assertEquals("string", TableParser.getType("longtext"))
        // 未知类型兜底 string（json/blob 等，与旧 default 行为一致）
        assertEquals("string", TableParser.getType("json"))
        assertEquals("string", TableParser.getType("blob"))
        // int 族（bigint 主键 id 全库 1113 例中 1109 为 int，long 仅 4 例杂族）
        assertEquals("int", TableParser.getType("int"))
        assertEquals("int", TableParser.getType("integer"))
        assertEquals("int", TableParser.getType("tinyint"))
        assertEquals("int", TableParser.getType("smallint"))
        assertEquals("int", TableParser.getType("mediumint"))
        assertEquals("int", TableParser.getType("bigint"))
        assertEquals("int", TableParser.getType("bit"))
        // decimal 独立类型（不再折算 number，词表 109 例实证）
        assertEquals("decimal", TableParser.getType("decimal"))
        // date 独立类型（修正旧 DatabaseMetadataUtil 把 DATE 归 datetime 的退化，58 例）
        assertEquals("date", TableParser.getType("date"))
        // datetime 族（datetime 1724 主流 vs timestamp 22 杂族；time 词表 0 例沿用旧映射不臆造）
        assertEquals("datetime", TableParser.getType("datetime"))
        assertEquals("datetime", TableParser.getType("timestamp"))
        assertEquals("datetime", TableParser.getType("time"))
        // float 族（float 50 例、double 0 例）
        assertEquals("float", TableParser.getType("float"))
        assertEquals("float", TableParser.getType("double"))
        // enum（459 例 100% 带 enum= 属性，属性值由模板层经 enumRef 输出）
        assertEquals("enum", TableParser.getType("enum"))
        // 大小写不敏感，输出一律小写
        assertEquals("enum", TableParser.getType("ENUM"))
        assertEquals("decimal", TableParser.getType("DECIMAL"))
        assertEquals("int", TableParser.getType("BIGINT"))
        assertEquals("datetime", TableParser.getType("DATETIME"))
        assertEquals("string", TableParser.getType("VARCHAR"))
        // null 兜底
        assertEquals("", TableParser.getType(null))
    }

    /**
     * P2-5：映射精度端到端——bigint/tinyint(1)→int、decimal 三形态（P,S / 仅 P / 裸）、
     * date/datetime/timestamp、enum（enumRef=列名小写）、float/char/text/json、大写输入小写输出。
     */
    @Test
    fun parseCreateTable_typePrecision_mappingAndLength() {
        val ddl = "CREATE TABLE `t_type_demo` (\n" +
                "  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',\n" +
                "  `flag` tinyint(1) DEFAULT '0',\n" +
                "  `price` decimal(19,4) DEFAULT NULL,\n" +
                "  `rate` decimal(18) DEFAULT NULL,\n" +
                "  `amount` decimal DEFAULT NULL,\n" +
                "  `d` date DEFAULT NULL,\n" +
                "  `dt` datetime DEFAULT NULL,\n" +
                "  `ts` timestamp NULL DEFAULT NULL,\n" +
                "  `status` enum('0','1','2') DEFAULT '0',\n" +
                "  `ratio` float DEFAULT NULL,\n" +
                "  `ratio2` double(10,2) DEFAULT NULL,\n" +
                "  `code` char(10) DEFAULT NULL,\n" +
                "  `body` text,\n" +
                "  `extra` json DEFAULT NULL,\n" +
                "  `UC_STATUS` ENUM('A','B') DEFAULT NULL,\n" +
                "  `UC_PRICE` DECIMAL(19,4) DEFAULT NULL,\n" +
                "  `UC_ID` BIGINT DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"

        val byName = TableParser.parseCreateTable(ddl)
                .associate { it["name"] as String to it }

        assertEquals("PRIMARY KEY 行不进结果，17 列齐全", 17, byName.size)
        // int 族：bigint 主键与 tinyint(1) 均为 int
        byName["id"]!!.let {
            assertEquals("int", it["type"]); assertEquals("20", it["length"])
        }
        byName["flag"]!!.let {
            assertEquals("int", it["type"]); assertEquals("1", it["length"])
        }
        // decimal 三形态：P,S → "P,S"；仅 P → "P"；裸 decimal → 空串
        byName["price"]!!.let {
            assertEquals("decimal", it["type"]); assertEquals("19,4", it["length"])
        }
        byName["rate"]!!.let {
            assertEquals("decimal", it["type"]); assertEquals("18", it["length"])
        }
        byName["amount"]!!.let {
            assertEquals("decimal", it["type"]); assertEquals("", it["length"])
        }
        // 时间族：date 独立，datetime/timestamp 归 datetime，均无 length
        byName["d"]!!.let {
            assertEquals("date", it["type"]); assertEquals("", it["length"])
        }
        byName["dt"]!!.let {
            assertEquals("datetime", it["type"]); assertEquals("", it["length"])
        }
        byName["ts"]!!.let {
            assertEquals("datetime", it["type"]); assertEquals("", it["length"])
        }
        // enum：type=enum + enumRef=列名小写（字典 code 默认字段名，用户可手改）
        byName["status"]!!.let {
            assertEquals("enum", it["type"]); assertEquals("", it["length"])
            assertEquals("status", it["enumRef"])
        }
        // float 族
        byName["ratio"]!!.let {
            assertEquals("float", it["type"]); assertEquals("", it["length"])
        }
        byName["ratio2"]!!.let {
            assertEquals("float", it["type"]); assertEquals("10,2", it["length"])
        }
        // char→string 带 length；text→string 无 length；json 未知→string
        byName["code"]!!.let {
            assertEquals("string", it["type"]); assertEquals("10", it["length"])
        }
        byName["body"]!!.let {
            assertEquals("string", it["type"]); assertEquals("", it["length"])
        }
        byName["extra"]!!.let {
            assertEquals("string", it["type"]); assertEquals("", it["length"])
        }
        // 大写输入 → 小写输出；enumRef 取列名小写
        byName["uc_status"]!!.let {
            assertEquals("enum", it["type"]); assertEquals("uc_status", it["enumRef"])
        }
        byName["uc_price"]!!.let {
            assertEquals("decimal", it["type"]); assertEquals("19,4", it["length"])
        }
        byName["uc_id"]!!.let {
            assertEquals("int", it["type"])
        }
        // enumRef 仅 enum 列携带，其余列无此键（键集合口径：普通列恒 6 键）
        assertTrue("enumRef 仅 enum 列携带",
                TableParser.parseCreateTable(ddl).count { it.containsKey("enumRef") } == 2)
    }

    /**
     * P2-5：片段化主路径与正则兜底产物一致（畸形 DDL 触发兜底 + 反射直调兜底实现双重验证）。
     * 兜底路径两处 P1-9 前遗留形态不属本项范围、断言口径相应收窄：
     * 1) nullable 正则 `[^,]+` 在 int(11) 内逗号处截断致 NOT NULL 检测失效——样例全列可空避开；
     * 2) 单行形态 comment 正则无行界感知，表名会抢走首列 COMMENT（P1-9 的修复动机本身）——
     *    单行形态只断言 P2-5 改动面（name/type/length/enumRef），多行形态做全量相等。
     */
    @Test
    fun parseCreateTable_dualPath_decimalEnum_consistent() {
        val ddl = "CREATE TABLE `t_dual_demo` (\n" +
                "  `id` int(11) COMMENT 'ID',\n" +
                "  `price` decimal(19,4) COMMENT '单价',\n" +
                "  `status` enum('0','1') COMMENT '状态',\n" +
                "  `name` varchar(64) COMMENT '名称'\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"

        val reflection = TableParser::class.java.getDeclaredMethod("parseCreateTableByRegex", String::class.java)
        reflection.isAccessible = true

        for ((form, singleLine) in listOf(ddl to false, ddl.replace("\n", " ") to true)) {
            // 畸形 DDL（列定义体收尾括号缺失）→ extractColumnBody 返回 null → 走正则兜底
            val malformed = form.replaceFirst(Regex("\\) ENGINE"), " ENGINE")
            val mainPath = TableParser.parseCreateTable(form)
            val fallbackViaMalformed = TableParser.parseCreateTable(malformed)
            @Suppress("UNCHECKED_CAST")
            val fallbackByReflection = reflection.invoke(null, form) as List<Map<String, Any>>

            if (singleLine) {
                // 单行形态：兜底 comment 归属错位为 P1-9 前遗留（表名抢首列 COMMENT），只钉 P2-5 改动面
                fun List<Map<String, Any>>.p2d5Face() = map { listOf(it["name"], it["type"], it["length"], it["enumRef"]) }
                assertEquals("单行形态 P2-5 改动面（name/type/length/enumRef）双路径一致",
                        mainPath.p2d5Face(), fallbackViaMalformed.p2d5Face())
                assertEquals("单行形态 P2-5 改动面：反射直调兜底一致",
                        mainPath.p2d5Face(), fallbackByReflection.p2d5Face())
            } else {
                assertEquals("兜底（畸形触发）与主路径产物一致（多行形态）", mainPath, fallbackViaMalformed)
                assertEquals("兜底（反射直调）与主路径产物一致（多行形态）", mainPath, fallbackByReflection)
            }

            // P2-5 改动面逐键钉死：type/length/enumRef（status 携带 enumRef，其余列无）
            val byName = mainPath.associate { it["name"] as String to it }
            assertEquals("int", byName["id"]!!["type"])
            assertEquals("11", byName["id"]!!["length"])
            assertEquals("decimal", byName["price"]!!["type"])
            assertEquals("19,4", byName["price"]!!["length"])
            assertEquals("enum", byName["status"]!!["type"])
            assertEquals("status", byName["status"]!!["enumRef"])
        }
    }

    @Test
    fun extractTableName_bizProduct() {
        assertEquals("biz_product", TableParser.extractTableName(BIZ_PRODUCT_DDL))
    }

    @Test
    fun parseDQL_sample_snapshot() {
        val columns = TableParser.parseDQL(SAMPLE_DQL)
        assertEquals(4, columns.size)

        // 第一列：字符串常量 as 别名
        columns[0].let {
            assertEquals("operatetype", it["name"])
            assertEquals("operatetype", it["alias"])
            assertEquals("string", it["type"])
        }
        // date(a.maintaintime) as date：字段含 "time" → datetime（含 "date" 先命中又被 "time" 覆盖）
        columns[1].let {
            assertEquals("date", it["name"])
            assertEquals("datetime", it["type"])
        }
        // 嵌套 substring_index 的括号内逗号不切列
        columns[2].let {
            assertEquals("warehousemanager", it["name"])
            assertEquals("string", it["type"])
        }
        // count(1) as times：默认 string
        columns[3].let {
            assertEquals("times", it["name"])
            assertEquals("string", it["type"])
        }
        // DQL 列固定口径：comment 回退为 name（不联网翻译），length 恒 120，三开关固定
        columns.forEach { col ->
            assertEquals("快照只锁定这 9 个键", 9, col.size)
            assertEquals(col["name"], col["comment"])
            assertEquals("120", col["length"])
            assertEquals("true", col["isRequired"])
            assertEquals("true", col["isQueryField"])
            assertEquals("false", col["isDialogField"])
            assertEquals("true", col["nullable"])
        }
    }

    @Test
    fun extractTableNameFromDQL_sample() {
        assertEquals("biz_wms_material_receipt_barcode", TableParser.extractTableNameFromDQL(SAMPLE_DQL))
        assertEquals("null", TableParser.extractTableNameFromDQL("select 1"))
    }
}
