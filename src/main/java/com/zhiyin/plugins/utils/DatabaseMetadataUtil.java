package com.zhiyin.plugins.utils;

import com.intellij.openapi.diagnostic.Logger;

import java.sql.*;
import java.util.*;

public class DatabaseMetadataUtil {

    private static final Logger LOG = Logger.getInstance(DatabaseMetadataUtil.class);

    /** 连接超时（毫秒）：错误库地址 3 秒内失败，不再无限挂起（P1-2） */
    private static final String CONNECT_TIMEOUT_MS = "3000";
    /** Socket 读超时（毫秒）：防 SHOW CREATE TABLE 查询无限等待（P1-2） */
    private static final String SOCKET_TIMEOUT_MS = "10000";

    // Method to get table metadata information
    // P1-2：错误不再吞掉——受检异常上抛给调用方（后台任务 onError 弹窗），日志记录完整堆栈
    public static List<Map<String, Object>> getTableMetadata(
            String jdbcUrl, String username, String password, String tableName) throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOG.error("MySQL JDBC 驱动加载失败", e);
            throw new SQLException("MySQL JDBC 驱动加载失败: " + e.getMessage(), e);
        }
        Properties props = new Properties();
        if (username != null) {
            props.setProperty("user", username);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        props.setProperty("connectTimeout", CONNECT_TIMEOUT_MS);
        props.setProperty("socketTimeout", SOCKET_TIMEOUT_MS);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, props)) {
            String createTableSQL = getCreateTableSQL(connection, tableName);
            return TableParser.parseCreateTable(createTableSQL);
        } catch (SQLException e) {
            LOG.warn("读取表元数据失败: table=" + tableName + ", url=" + jdbcUrl, e);
            throw e;
        }
    }

    // Method to execute SHOW CREATE TABLE and return the SQL statement
    public static String getCreateTableSQL(Connection connection, String tableName) throws SQLException {
        String sql = "SHOW CREATE TABLE " + tableName;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getString(2); // The SQL statement is in the second column
            } else {
                throw new SQLException("No create table SQL found for table: " + tableName);
            }
        }
    }

    /**
     * MySQL 类型转换 字符串的一律返回 string 整数类型的一律返回int 时间类型的返回 datetime
     */
    private static String getType(String columnType) {
        switch (columnType) {
            case "INT":
            case "INTEGER":
            case "TINYINT":
            case "SMALLINT":
            case "MEDIUMINT":
            case "BIGINT":
            case "BIT":
            case "SERIAL":
                return "int";
            case "DATE":
            case "DATETIME":
            case "TIMESTAMP":
            case "TIME":
                return "datetime";
            case "DECIMAL":
                return "number";
            default:
                return "string";
        }
    }

    // getAllDatabaseConnectionsMetaData, params connection info by DatabaseConnectionFinder class
    public static List<Map<String, Object>> getAllDatabaseConnectionsMetaData(List<Map<String, String>> databaseConnections,
                                                                              String tableName) throws SQLException {
        List<Map<String, Object>> tableMetadata = new ArrayList<>();
        for (Map<String, String> connectionInfo : databaseConnections) {
            String jdbcUrl = connectionInfo.get("url");
            String username = connectionInfo.get("username");
            String password = connectionInfo.get("password");
            List<Map<String, Object>> metadata = getTableMetadata(jdbcUrl, username, password, tableName);
            tableMetadata.addAll(metadata);
        }
        return tableMetadata;
    }

    // 新增：获取非指定前缀的所有表字段元数据
    // 供 MyProjectService（后台任务）调用，保持原签名：失败记日志返回已收集结果
    public static Map<String, List<Map<String, Object>>> getTablesMetadataByNotStartPrefix(
            String jdbcUrl, String username, String password, String prefix) {

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOG.error("MySQL JDBC 驱动加载失败", e);
            return result;
        }
        Properties props = new Properties();
        if (username != null) {
            props.setProperty("user", username);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        props.setProperty("connectTimeout", CONNECT_TIMEOUT_MS);
        props.setProperty("socketTimeout", SOCKET_TIMEOUT_MS);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, props)) {

            // 1. 获取前缀匹配的表名列表
            List<String> tableNames = getTablesByPrefix(connection, prefix);

            // 2. 遍历表获取字段元数据
            for (String tableName : tableNames) {
                List<Map<String, Object>> metadata = getTableMetadata(connection, tableName);
                result.put(tableName, metadata);
            }
        } catch (SQLException e) {
            LOG.warn("按前缀读取表元数据失败: url=" + jdbcUrl, e);
        }
        return result;
    }

    // 新增：获取非指定前缀的表名列表
    private static List<String> getTablesByPrefix(Connection conn, String prefix) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        // 统一转换为小写比较，解决大小写敏感问题
        String lowerPrefix = prefix.toLowerCase();

        // 从jdbcUrl中提取数据库名，或者作为参数传递
        String databaseName = extractDatabaseNameFromUrl(meta.getURL());

        try (ResultSet rs = meta.getTables(databaseName, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                // 核心修改：检查表名是否不以prefix开头
                if (!tableName.toLowerCase().startsWith(lowerPrefix)) {
                    tableNames.add(tableName);
                }
            }
        }
        return tableNames;
    }

    /**
     * 从 JDBC URL 中提取数据库名称
     *
     * @param jdbcUrl JDBC 连接 URL，例如: jdbc:mysql://localhost:3306/database_name
     * @return 数据库名称
     */
    private static String extractDatabaseNameFromUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            return null;
        }

        // 查找最后一个 "/" 的位置
        int lastSlashIndex = jdbcUrl.lastIndexOf('/');
        if (lastSlashIndex == -1) {
            return null;
        }

        // 查找 "?" 的位置（参数开始的地方）
        int questionMarkIndex = jdbcUrl.indexOf('?', lastSlashIndex);

        // 提取数据库名称
        if (questionMarkIndex != -1) {
            // 如果有参数，只取 "/" 和 "?" 之间的部分
            return jdbcUrl.substring(lastSlashIndex + 1, questionMarkIndex);
        } else {
            // 如果没有参数，取 "/" 之后的所有内容
            return jdbcUrl.substring(lastSlashIndex + 1);
        }
    }

    // 修改：增加使用现有Connection的方法（避免重复创建连接）
    public static List<Map<String, Object>> getTableMetadata(Connection connection, String tableName) {
        try {
            String createTableSQL = getCreateTableSQL(connection, tableName);
            return TableParser.parseCreateTable(createTableSQL);
        } catch (SQLException e) {
            LOG.warn("读取表元数据失败: table=" + tableName, e);
            return Collections.emptyList();
        }
    }

    public static void main(String[] args) {
        // Example usage
        String jdbcUrl = "jdbc:mysql://devhost:3306/fobritemes";
        String username = "root";
        String password = "root";
        /*String tableName = "your_table_name";

        List<Map<String, Object>> metadata = getTableMetadata(jdbcUrl, username, password, tableName);

        for (Map<String, Object> column : metadata) {
            LOG.info("Column Info: " + column);
        }*/

        String prefix = "act_";  // 表名前缀

        Map<String, List<Map<String, Object>>> result = getTablesMetadataByNotStartPrefix(jdbcUrl, username, password, prefix);

        // 打印结果
        result.forEach((tableName, columns) -> {
            LOG.info("Table: " + tableName);
            columns.forEach(col -> LOG.info("  Column: " + col.get("name") + " | Type: " + col.get("type")));
        });

    }
}
