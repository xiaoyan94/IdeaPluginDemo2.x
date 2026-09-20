package com.zhiyin.plugins.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Properties

/**
 * P2-2 纯逻辑单测：i18n 追加生成的纯函数口径锁定（包级静态，须与本包同包访问）。
 * 覆盖：dsp 派生（state/status/type 精确匹配）、TODO 降级行格式与文本判重、
 * 幂等过滤（现有 key 集合）、值转义与 key=value 行组装（Properties.load 可解析）、
 * 翻译快速失败阈值（开头连续 3 失败）、追加统计摘要格式。
 */
class I18nGenerateServiceTest {

    // ---- dsp 派生：state / status / type 精确命中 ----

    @Test
    fun isDspField_exactMatches() {
        assertTrue(I18nGenerateService.isDspField("state"))
        assertTrue(I18nGenerateService.isDspField("status"))
        assertTrue(I18nGenerateService.isDspField("type"))
    }

    @Test
    fun isDspField_suffixOrCompoundNoMatch() {
        // 后缀/复数/组合字段不派生（RoutingTypeDsp 复用原 key 属反例，不扩大范围）
        assertFalse(I18nGenerateService.isDspField("routingtype"))
        assertFalse(I18nGenerateService.isDspField("statetype"))
        assertFalse(I18nGenerateService.isDspField("types"))
        assertFalse(I18nGenerateService.isDspField("statedsp"))
        assertFalse(I18nGenerateService.isDspField("factorycode"))
        assertFalse(I18nGenerateService.isDspField(""))
    }

    @Test
    fun isDspField_caseSensitiveOnLowercaseConvention() {
        // 生成器字段 name 为小写口径：按小写字面量精确匹配，大写形式不派生
        assertFalse(I18nGenerateService.isDspField("State"))
        assertFalse(I18nGenerateService.isDspField("TYPE"))
    }

    @Test
    fun deriveDspKey_appendsDspSuffix() {
        assertEquals(
            "com.zhiyin.mes.app.order.ordergrid.statedsp",
            I18nGenerateService.deriveDspKey("state", "com.zhiyin.mes.app.order.ordergrid.state")
        )
        assertEquals(
            "com.zhiyin.mes.app.order.ordergrid.statusdsp",
            I18nGenerateService.deriveDspKey("status", "com.zhiyin.mes.app.order.ordergrid.status")
        )
    }

    @Test
    fun deriveDspKey_nonDspFieldReturnsNull() {
        assertNull(I18nGenerateService.deriveDspKey("routingtype", "com.zhiyin.mes.app.order.ordergrid.routingtype"))
    }

    // ---- dsp 约定字段默认标题（无 comment 时进确认清单的兜底值，HaichengMes 验收实证）----

    @Test
    fun defaultDspFieldTitle_stateAndStatusMapToStatus() {
        assertEquals("状态", I18nGenerateService.defaultDspFieldTitle("state"))
        assertEquals("状态", I18nGenerateService.defaultDspFieldTitle("status"))
        assertEquals("类型", I18nGenerateService.defaultDspFieldTitle("type"))
    }

    @Test
    fun defaultDspFieldTitle_nonDspFieldReturnsNull() {
        // 后缀/组合字段与其他字段无默认标题：无 comment 仍不进清单
        assertNull(I18nGenerateService.defaultDspFieldTitle("routingtype"))
        assertNull(I18nGenerateService.defaultDspFieldTitle("orderstatus"))
        assertNull(I18nGenerateService.defaultDspFieldTitle("factorycode"))
        assertNull(I18nGenerateService.defaultDspFieldTitle(""))
    }

    // ---- collectI18nMissingSummary：无 comment 的 dsp 约定字段以默认标题进清单 ----

    @Test
    fun collectI18nMissingSummary_dspFieldWithoutCommentEntersWithDefaultTitle() {
        val fields = listOf(
            mapOf<String, Any>("name" to "status", "comment" to ""),
            mapOf<String, Any>("name" to "code", "comment" to ""),
        )
        val summary = CodeGenerateService.collectI18nMissingSummary(
            "com.zhiyin.mes.app.haicheng.order", "BaseFactory", fields, emptyMap()
        )
        assertEquals(1, summary.missCount)
        val entry = summary.missingEntries.single()
        assertEquals("status", entry.fieldName)
        assertEquals("com.zhiyin.mes.app.order.basefactorygrid.status", entry.proposedKey)
        // 无 comment 的 dsp 约定字段以默认标题进清单（模板 endsWith 匹配必生成 dsp 显示列）
        assertEquals("状态", entry.chs)
    }

    @Test
    fun collectI18nMissingSummary_nonDspFieldWithoutCommentStillSkipped() {
        val fields = listOf(mapOf<String, Any>("name" to "delflag", "comment" to ""))
        val summary = CodeGenerateService.collectI18nMissingSummary(
            "com.zhiyin.mes.app.haicheng.order", "BaseFactory", fields, emptyMap()
        )
        assertEquals(0, summary.missCount)
        assertTrue(summary.missingEntries.isEmpty())
    }

    // ---- TODO 降级行格式与文本判重 ----

    @Test
    fun buildTodoTranslateLine_format() {
        assertEquals(
            "# TODO: translate: com.zhiyin.mes.app.order.ordergrid.state",
            I18nGenerateService.buildTodoTranslateLine("com.zhiyin.mes.app.order.ordergrid.state")
        )
    }

    @Test
    fun containsTodoLine_dedup() {
        val key = "com.zhiyin.mes.app.order.ordergrid.state"
        val fileText = "ordergrid.factoryid=\\u5de5\\u5382\n# TODO: translate: $key\n"
        // 文件已含该 TODO 行 → 不重复追加（幂等重跑 +0 行）
        assertTrue(I18nGenerateService.containsTodoLine(fileText, key))
        // 不含（其他 key 的 TODO 行不算）→ 需要追加
        assertFalse(I18nGenerateService.containsTodoLine(fileText, "com.zhiyin.mes.app.order.ordergrid.code"))
        // 空文件 / null 文本 → 需要追加
        assertFalse(I18nGenerateService.containsTodoLine("", key))
        assertFalse(I18nGenerateService.containsTodoLine(null, key))
    }

    // ---- 幂等过滤：现有 key 集合 ----

    @Test
    fun isKeySkipped_existingKeySkipped() {
        val existing = setOf("ordergrid.factoryid", "ordergrid.state")
        assertTrue(I18nGenerateService.isKeySkipped(existing, "ordergrid.state"))
        assertFalse(I18nGenerateService.isKeySkipped(existing, "ordergrid.code"))
        // 防御：null 集合视为无已有 key（不跳过）
        assertFalse(I18nGenerateService.isKeySkipped(null, "ordergrid.state"))
    }

    // ---- 行组装与值转义：key=value 形态可解析 ----

    @Test
    fun buildPropertyLine_keyEqualsValue() {
        assertEquals("k=v", I18nGenerateService.buildPropertyLine("k", "v"))
        assertEquals("k=", I18nGenerateService.buildPropertyLine("k", null))
        assertEquals("k=", I18nGenerateService.buildPropertyLine("k", ""))
    }

    @Test
    fun propertyLine_parsableWhenValueContainsSeparators() {
        // en_US 原样写入（照渲染器口径不转义）：值中的空格/等号/冒号在 properties 语义下属值的一部分
        val line = I18nGenerateService.buildPropertyLine("k", "a=b:c d")
        assertEquals("a=b:c d", loadPropertyValue(line))
    }

    @Test
    fun escapePropertyValueForWrite_native2AsciiDisabled_escapesAll() {
        // 关闭 native2ascii 时预转 \uXXXX（StringUtil.stringToUnicode 全字符转义，十六进制大写）
        assertEquals("\\u5DE5", I18nGenerateService.escapePropertyValueForWrite("工", false))
        assertEquals("\\u5DE5\\u5382\\u4EE3\\u7801", I18nGenerateService.escapePropertyValueForWrite("工厂代码", false))
    }

    @Test
    fun escapePropertyValueForWrite_native2AsciiEnabled_raw() {
        assertEquals("工厂代码", I18nGenerateService.escapePropertyValueForWrite("工厂代码", true))
        // null / 空串原样返回
        assertNull(I18nGenerateService.escapePropertyValueForWrite(null, false))
        assertEquals("", I18nGenerateService.escapePropertyValueForWrite("", false))
    }

    @Test
    fun propertyLine_parsableAfterUnicodeEscape() {
        // 中文值经 \uXXXX 转义后组装的 key=value 行，Properties.load 解码回原值
        val value = "工厂代码"
        val escaped = I18nGenerateService.escapePropertyValueForWrite(value, false)
        val line = I18nGenerateService.buildPropertyLine("k", escaped)
        assertEquals(value, loadPropertyValue(line))
    }

    // ---- 翻译快速失败：开头连续 3 失败判定 ----

    @Test
    fun shouldFastFailTranslations_thresholdIsThree() {
        assertFalse(I18nGenerateService.shouldFastFailTranslations(0))
        assertFalse(I18nGenerateService.shouldFastFailTranslations(1))
        assertFalse(I18nGenerateService.shouldFastFailTranslations(2))
        assertTrue(I18nGenerateService.shouldFastFailTranslations(3))
        assertTrue(I18nGenerateService.shouldFastFailTranslations(5))
    }

    // ---- 追加统计摘要格式 ----

    @Test
    fun buildAppendSummary_format() {
        assertEquals(
            "；i18n 追加：zh_CN +2、zh_TW +1、en_US +3、跳过已存在 4",
            I18nGenerateService.buildAppendSummary(2, 1, 3, 4)
        )
    }

    @Test
    fun appendResult_toSummarySuffix() {
        val result = I18nGenerateService.I18nAppendResult(0, 0, 0, 0)
        assertEquals("；i18n 追加：zh_CN +0、zh_TW +0、en_US +0、跳过已存在 0", result.toSummarySuffix())
    }

    // ---- P2-7 修复：时间范围 from/to 派生 key（确定性派生，不进确认弹窗） ----

    @Test
    fun timeRangeToKey_constantMatchesTemplateLiteral() {
        // 与 BaseQueryTypeLayout.ftl to 分支 label 字面量、DengqiMes 既有共享 key 保持一致
        assertEquals("com.zhiyin.mes.app.order.ordergrid.to", I18nGenerateService.TIME_RANGE_TO_I18N_KEY)
    }

    @Test
    fun deriveTimeRangeFromEntry_valueConcat() {
        val e = I18nGenerateService.deriveTimeRangeFromEntry(
            "createtime", "com.zhiyin.mes.app.basic.syncerpiqcfailloggrid.createtime",
            "创建时间", "創建時間", "Create Time"
        )
        assertEquals("createtimefrom", e.fieldName())
        assertEquals("com.zhiyin.mes.app.basic.syncerpiqcfailloggrid.createtime_from", e.key())
        assertEquals("创建时间从", e.zhCn())
        assertEquals("創建時間從", e.zhTw())
        assertEquals("Create Time From", e.enUs())
    }

    @Test
    fun deriveTimeRangeFromEntry_missingLangLeavesEmptyForTodoLine() {
        // 某语言基值缺失 → 留空，追加时走既有 # TODO: translate 注释行机制
        val e = I18nGenerateService.deriveTimeRangeFromEntry("successtime", "k.successtime", "成功时间", null, " ")
        assertEquals("成功时间从", e.zhCn())
        assertEquals("", e.zhTw())
        assertEquals("", e.enUs())
    }

    @Test
    fun deriveTimeRangeToEntry_fixedSharedKey() {
        val e = I18nGenerateService.deriveTimeRangeToEntry()
        assertEquals(I18nGenerateService.TIME_RANGE_TO_I18N_KEY, e.key())
        assertEquals("到", e.zhCn())
        assertEquals("到", e.zhTw())
        assertEquals("To", e.enUs())
    }

    @Test
    fun concatDerivedSuffix_blankBaseStaysEmpty() {
        assertEquals("", I18nGenerateService.concatDerivedSuffix(null, "从"))
        assertEquals("", I18nGenerateService.concatDerivedSuffix("  ", "从"))
        assertEquals("同步日期从", I18nGenerateService.concatDerivedSuffix("同步日期", "从"))
        assertEquals("Sync Time From", I18nGenerateService.concatDerivedSuffix("Sync Time", " From"))
    }

    @Test
    fun isKeySkipped_timeRangeToKeyAlreadyExists() {
        // to-key 已存在时跳过（判重幂等，模块 properties 已含共享 key 则不重复追加）
        assertTrue(I18nGenerateService.isKeySkipped(setOf(I18nGenerateService.TIME_RANGE_TO_I18N_KEY), I18nGenerateService.TIME_RANGE_TO_I18N_KEY))
        assertFalse(I18nGenerateService.isKeySkipped(emptySet(), I18nGenerateService.TIME_RANGE_TO_I18N_KEY))
    }

    // ---- collectTimeRangeDerivedAppends（CodeGenerateService，同包静态）：派生清单收集 ----

    @Test
    fun collectTimeRangeDerivedAppends_fromAndToEntries() {
        val fields = listOf(
            // 非时间控件不派生（即便有 key）
            linkedMapOf("name" to "code", "isQueryField" to "true", "easyuiClass" to "easyui-textbox", "i18nKey" to "k.code"),
            // 查询未勾选不派生
            linkedMapOf("name" to "url", "isQueryField" to "false", "easyuiClass" to "easyui-datetimebox", "i18nKey" to "k.url"),
            // datebox 变体同样命中（contains date）
            linkedMapOf("name" to "startdate", "isQueryField" to true, "easyuiClass" to "easyui-datebox",
                "i18nKey" to "k.startdate", "chs" to "开始日期", "cht" to "開始日期", "eng" to "Start Date"),
            // datetimebox + unicode 转义 chs（PSI getValue 原样形态先解码再拼接）+ cht 缺失 → TODO 行
            linkedMapOf<String, Any>("name" to "createtime", "isQueryField" to "true", "easyuiClass" to "easyui-datetimebox",
                "i18nKey" to "k.createtime", "chs" to "\\u521b\\u5efa\\u65f6\\u95f4", "eng" to "Create Time"),
        )
        val appends = CodeGenerateService.collectTimeRangeDerivedAppends(fields)
        assertEquals(3, appends.size) // 2 个 from + 1 个 to
        val start = appends[0]
        assertEquals("startdatefrom", start.fieldName())
        assertEquals("k.startdate_from", start.key())
        assertEquals("开始日期从", start.zhCn())
        assertEquals("開始日期從", start.zhTw())
        assertEquals("Start Date From", start.enUs())
        val create = appends[1]
        assertEquals("k.createtime_from", create.key())
        assertEquals("创建时间从", create.zhCn())
        assertEquals("", create.zhTw())
        assertEquals("Create Time From", create.enUs())
        val to = appends[2]
        assertEquals(I18nGenerateService.TIME_RANGE_TO_I18N_KEY, to.key())
    }

    @Test
    fun collectTimeRangeDerivedAppends_noFromNoToEntry() {
        // 无有效列 key 的 datetime 查询字段不派生（模板走裸中文兜底）；无 from 条目时也不补 to 条目
        val fields = listOf(
            linkedMapOf("name" to "expiretime", "isQueryField" to "true", "easyuiClass" to "easyui-datetimebox"),
            linkedMapOf("name" to "code", "isQueryField" to "true", "easyuiClass" to "easyui-textbox", "i18nKey" to "k.code"),
        )
        assertTrue(CodeGenerateService.collectTimeRangeDerivedAppends(fields).isEmpty())
        assertTrue(CodeGenerateService.collectTimeRangeDerivedAppends(emptyList()).isEmpty())
    }

    /** java.util.Properties 解析单行 key=value，返回该 key 的值（转义口径的解析侧锚点） */
    private fun loadPropertyValue(line: String): String? {
        val props = Properties()
        StringReader(line).use { reader -> props.load(reader) }
        return props.getProperty("k")
    }
}
