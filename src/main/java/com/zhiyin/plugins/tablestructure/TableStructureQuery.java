package com.zhiyin.plugins.tablestructure;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 表结构查询：结构化数据全部走 information_schema（天然只读，无需表级权限），
 * 建表 DDL 走 SHOW CREATE TABLE（best-effort，失败降级为不展示 DDL）。
 */
public final class TableStructureQuery {

    /** MySQL 反引号外的合法标识符字符，用于拼 SHOW CREATE TABLE 前的白名单校验 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[0-9A-Za-z_$]+$");

    private TableStructureQuery() {
    }

    /**
     * 按连接信息（database.url/username/password）建 JDBC 连接。
     * 连接信息来源 {@link com.zhiyin.plugins.utils.DatabaseConnectionFinder}
     */
    public static Connection open(Map<String, String> connectionInfo) throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ignored) {
            // SPI 自动注册兜底
        }
        return DriverManager.getConnection(normalizeUrl(connectionInfo.get("url")),
                connectionInfo.get("username"), connectionInfo.get("password"));
    }

    /**
     * URL 追加超时/SSL/编码参数（已带的跳过）：
     * 超时防连接挂死、allowPublicKeyRetrieval 防 MySQL8 caching_sha2_password 非 SSL 握手失败、
     * characterEncoding 防中文注释乱码
     */
    static String normalizeUrl(String jdbcUrl) {
        String sep = jdbcUrl.contains("?") ? "&" : "?";
        StringBuilder sb = new StringBuilder(jdbcUrl);
        String[][] params = {
                {"connectTimeout", "5000"},
                {"socketTimeout", "15000"},
                {"useSSL", "false"},
                {"allowPublicKeyRetrieval", "true"},
                {"characterEncoding", "utf8"},
        };
        for (String[] param : params) {
            if (!jdbcUrl.contains(param[0] + "=")) {
                sb.append(sep).append(param[0]).append('=').append(param[1]);
                sep = "&";
            }
        }
        return sb.toString();
    }

    /** JDBC URL 里配置的默认库名（MySQL 的 catalog 即 database） */
    @Nullable
    public static String defaultSchema(Connection connection) throws SQLException {
        String catalog = connection.getCatalog();
        return catalog == null || catalog.isEmpty() ? null : catalog;
    }

    /** 按表名在实例内全部库中搜索其所在 schema（跨库同名表会返回多个） */
    public static List<String> findSchemas(Connection connection, String tableName) throws SQLException {
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT DISTINCT table_schema FROM information_schema.tables " +
                "WHERE table_name = ? ORDER BY table_schema";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
        }
        return schemas;
    }

    /**
     * 加载指定 schema 下表的完整结构。表不存在时返回 columns 为空的结果（调用方据此做跨库解析）。
     */
    public static TableStructure load(@NotNull Connection connection, @NotNull String schema, @NotNull String table)
            throws SQLException {
        List<TableStructure.Column> columns = new ArrayList<>();
        String columnSql = "SELECT column_name, column_type, is_nullable, column_key, " +
                "column_default, extra, column_comment " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement ps = connection.prepareStatement(columnSql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(new TableStructure.Column(
                            rs.getString(1),
                            rs.getString(2),
                            "YES".equalsIgnoreCase(rs.getString(3)),
                            orEmpty(rs.getString(4)),
                            rs.getString(5),
                            orEmpty(rs.getString(6)),
                            orEmpty(rs.getString(7))));
                }
            }
        }

        List<TableStructure.IndexColumn> indexes = new ArrayList<>();
        String indexSql = "SELECT index_name, non_unique, seq_in_index, column_name, index_type " +
                "FROM information_schema.statistics " +
                "WHERE table_schema = ? AND table_name = ? ORDER BY index_name, seq_in_index";
        try (PreparedStatement ps = connection.prepareStatement(indexSql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexes.add(new TableStructure.IndexColumn(
                            rs.getString(1),
                            rs.getInt(2) == 0,
                            rs.getInt(3),
                            rs.getString(4),
                            rs.getString(5)));
                }
            }
        }

        TableStructure.TableInfo info = null;
        // DATE_FORMAT 直接在库里格式化，避免 Timestamp.toString 带毫秒
        String tableSql = "SELECT table_comment, engine, table_rows, table_collation, " +
                "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') " +
                "FROM information_schema.tables WHERE table_schema = ? AND table_name = ?";
        try (PreparedStatement ps = connection.prepareStatement(tableSql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    info = new TableStructure.TableInfo(schema, table,
                            orEmpty(rs.getString(1)),
                            orEmpty(rs.getString(2)),
                            orEmpty(rs.getString(3)),
                            orEmpty(rs.getString(4)),
                            orEmpty(rs.getString(5)));
                }
            }
        }

        return new TableStructure(info, columns, indexes, fetchDdl(connection, schema, table));
    }

    /** SHOW CREATE TABLE；标识符先过白名单防注入，无权限等失败静默返回 null */
    @Nullable
    private static String fetchDdl(Connection connection, String schema, String table) {
        if (!IDENTIFIER.matcher(schema).matches() || !IDENTIFIER.matcher(table).matches()) {
            return null;
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `" + schema + "`.`" + table + "`")) {
            if (rs.next()) {
                return rs.getString(2);
            }
        } catch (SQLException ignored) {
            // 无表级权限等情况降级为不展示 DDL
        }
        return null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
