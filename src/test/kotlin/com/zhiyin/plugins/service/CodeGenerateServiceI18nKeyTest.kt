package com.zhiyin.plugins.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-1 纯逻辑单测：拟生成 i18n key 拼装与缺失判定（包级静态，须与本包同包访问）。
 * key 规则（GATE-B 确认）：<模块 i18n 前缀>.<gridName 小写>grid.<字段名小写>，
 * grid 段 = 模板 ${grid.dataGridName}Grid 全小写（DengqiMes OrderGrid 实证）。
 */
class CodeGenerateServiceI18nKeyTest {

    @Test
    fun buildProposedI18nKey_gridSegmentLowercasedWithGridSuffix() {
        // GridName=BaseFactory → grid 段 basefactorygrid
        assertEquals(
            "com.zhiyin.mes.app.order.basefactorygrid.code",
            CodeGenerateService.buildProposedI18nKey("com.zhiyin.mes.app.dengqi.order", "BaseFactory", "code")
        )
    }

    @Test
    fun buildProposedI18nKey_upperCaseFieldNameLowercased() {
        assertEquals(
            "com.zhiyin.mes.app.order.basefactorygrid.factorycode",
            CodeGenerateService.buildProposedI18nKey("com.zhiyin.mes.app.dengqi.order", "BaseFactory", "FactoryCode")
        )
    }

    @Test
    fun buildProposedI18nKey_orderGridRealShape() {
        // DengqiMes Order.xml 真实形态：modelName=Order → ordergrid.factoryid
        assertEquals(
            "com.zhiyin.mes.app.order.ordergrid.factoryid",
            CodeGenerateService.buildProposedI18nKey("com.zhiyin.mes.app.dengqi.order", "Order", "FactoryId")
        )
    }

    @Test
    fun buildProposedI18nKey_moduleWithoutProjectSegmentUsedAsIs() {
        assertEquals(
            "com.zhiyin.mes.app.order.basefactorygrid.code",
            CodeGenerateService.buildProposedI18nKey("com.zhiyin.mes.app.order", "BaseFactory", "code")
        )
    }

    @Test
    fun isI18nMissingReportable_rules() {
        // 已命中：无论 comment 是否有值都不算缺失
        assertFalse(CodeGenerateService.isI18nMissingReportable("工厂代码", true))
        assertFalse(CodeGenerateService.isI18nMissingReportable(null, true))
        // 未命中且 comment 非空白 → 计入缺失报告（trim 只用于判定，报告保留原文）
        assertTrue(CodeGenerateService.isI18nMissingReportable("工厂代码", false))
        assertTrue(CodeGenerateService.isI18nMissingReportable("  工厂代码  ", false))
        // 未命中但 comment null/空/空白 → 不计入
        assertFalse(CodeGenerateService.isI18nMissingReportable(null, false))
        assertFalse(CodeGenerateService.isI18nMissingReportable("", false))
        assertFalse(CodeGenerateService.isI18nMissingReportable("   ", false))
    }
}
