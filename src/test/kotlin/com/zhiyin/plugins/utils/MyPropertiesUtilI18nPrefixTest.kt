package com.zhiyin.plugins.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-1 纯逻辑单测：MyPropertiesUtil.deriveI18nKeyPrefix 前缀推导规则快照。
 * 规则（GATE-B 确认，order/basic/quality/wms 四模块真实 properties 实证）：
 * com.zhiyin.mes. 开头且段数 ≥ 6 → 去掉倒数第二段（项目段）；否则原样返回。
 */
class MyPropertiesUtilI18nPrefixTest {

    @Test
    fun deriveI18nKeyPrefix_dengqiAppSixSegments() {
        assertEquals(
            "com.zhiyin.mes.app.order",
            MyPropertiesUtil.deriveI18nKeyPrefix("com.zhiyin.mes.app.dengqi.order")
        )
    }

    @Test
    fun deriveI18nKeyPrefix_sysDengqi() {
        assertEquals(
            "com.zhiyin.mes.sys.auth",
            MyPropertiesUtil.deriveI18nKeyPrefix("com.zhiyin.mes.sys.dengqi.auth")
        )
    }

    @Test
    fun deriveI18nKeyPrefix_fiveSegmentsNoProjectSegment() {
        assertEquals(
            "com.zhiyin.mes.app.order",
            MyPropertiesUtil.deriveI18nKeyPrefix("com.zhiyin.mes.app.order")
        )
    }

    @Test
    fun deriveI18nKeyPrefix_nonMesPrefixReturnedAsIs() {
        assertEquals("foo.bar", MyPropertiesUtil.deriveI18nKeyPrefix("foo.bar"))
        // 同为 6 段但非 com.zhiyin.mes. 开头：不删段
        assertEquals(
            "com.zhiyin.cloud.app.dengqi.order",
            MyPropertiesUtil.deriveI18nKeyPrefix("com.zhiyin.cloud.app.dengqi.order")
        )
    }

    @Test
    fun deriveI18nKeyPrefix_nullAndEmpty() {
        assertEquals("", MyPropertiesUtil.deriveI18nKeyPrefix(null))
        assertEquals("", MyPropertiesUtil.deriveI18nKeyPrefix(""))
    }
}
