package com.zhiyin.plugins.utils;

import org.junit.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P1-8：JDBC 连接 SSL 兼容回退的纯函数单测（URL 改写 + 异常链判定）。
 * 实测链形态（2026-09-17 沙箱 idea.log:30750）：
 * CommunicationsException → CJCommunicationsException → SSLHandshakeException → EOFException("SSL peer shut down incorrectly")
 */
public class DatabaseMetadataUtilSslFallbackTest {

    // ---------- URL 改写：withSslModeDisabled ----------

    @Test
    public void urlWithoutParams_appendsWithQuestionMark() {
        assertEquals("jdbc:mysql://192.168.116.9:3306/haichengmesprod?sslMode=DISABLED",
                DatabaseMetadataUtil.withSslModeDisabled("jdbc:mysql://192.168.116.9:3306/haichengmesprod"));
    }

    @Test
    public void urlWithParams_appendsWithAmpersand() {
        assertEquals(
                "jdbc:mysql://192.168.116.9:3306/haichengmesprod?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED",
                DatabaseMetadataUtil.withSslModeDisabled(
                        "jdbc:mysql://192.168.116.9:3306/haichengmesprod?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"));
    }

    @Test
    public void existingSslMode_overriddenInPlace_noDuplicate() {
        assertEquals("jdbc:mysql://h:3306/db?sslMode=DISABLED",
                DatabaseMetadataUtil.withSslModeDisabled("jdbc:mysql://h:3306/db?sslMode=REQUIRED"));
        // 位于参数串中间时原位覆盖、前后参数不动
        assertEquals("jdbc:mysql://h:3306/db?a=1&sslMode=DISABLED&b=2",
                DatabaseMetadataUtil.withSslModeDisabled("jdbc:mysql://h:3306/db?a=1&sslMode=PREFERRED&b=2"));
        // 参数名大小写不敏感（Connector/J 属性名不区分大小写）
        assertEquals("jdbc:mysql://h:3306/db?a=1&sslMode=DISABLED",
                DatabaseMetadataUtil.withSslModeDisabled("jdbc:mysql://h:3306/db?a=1&sslmode=VERIFY_CA"));
    }

    @Test
    public void otherParamsPreserved_similarParamNameNotMisfired() {
        // 相似参数名（xsslMode）不被误伤：不匹配则走追加路径
        assertEquals("jdbc:mysql://h:3306/db?xsslMode=1&sslMode=DISABLED",
                DatabaseMetadataUtil.withSslModeDisabled("jdbc:mysql://h:3306/db?xsslMode=1"));
    }

    // ---------- 异常链判定：isSslHandshakeFailure ----------

    @Test
    public void sslHandshakeException_wrappedTwoLevels_detected() {
        // 复现实测链：SSLHandshakeException 被包两层，顶层消息不含 SSL 字样
        SSLHandshakeException root = new SSLHandshakeException("Remote host terminated the handshake");
        root.initCause(new EOFException("SSL peer shut down incorrectly"));
        SQLException mid = new SQLException("Communications link failure", root);
        SQLException top = new SQLException("Communications link failure", mid);
        assertTrue(DatabaseMetadataUtil.isSslHandshakeFailure(top));
    }

    @Test
    public void eofExceptionWithSslMessage_detectedAsFallback() {
        // 兜底路径：链上无 SSLException 类，但消息含 "SSL"
        SQLException e = new SQLException("Communications link failure",
                new EOFException("SSL peer shut down incorrectly"));
        assertTrue(DatabaseMetadataUtil.isSslHandshakeFailure(e));
    }

    @Test
    public void plainSqlException_notDetected() {
        assertFalse(DatabaseMetadataUtil.isSslHandshakeFailure(
                new SQLException("Access denied for user 'root'@'192.168.116.5' (using password: YES)")));
        // 嵌套普通 SQLException 链也不命中
        assertFalse(DatabaseMetadataUtil.isSslHandshakeFailure(
                new SQLException("Communications link failure", new SQLException("Table 'db.t' doesn't exist"))));
    }

    @Test
    public void nullMessageEntries_doNotThrow() {
        // 消息为 null 的链节点不 NPE
        assertFalse(DatabaseMetadataUtil.isSslHandshakeFailure(new SQLException((Throwable) null)));
    }

    // ---------- 日志辅助：hostPortOf ----------

    @Test
    public void hostPortOf_extractsHostPortOnly() {
        assertEquals("192.168.116.9:3306",
                DatabaseMetadataUtil.hostPortOf(
                        "jdbc:mysql://192.168.116.9:3306/haichengmesprod?characterEncoding=UTF-8"));
        assertEquals("h:3306", DatabaseMetadataUtil.hostPortOf("jdbc:mysql://h:3306"));
    }
}
