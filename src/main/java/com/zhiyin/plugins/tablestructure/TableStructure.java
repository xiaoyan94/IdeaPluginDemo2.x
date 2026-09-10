package com.zhiyin.plugins.tablestructure;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 表结构查询结果模型（information_schema 查询产物）
 */
public class TableStructure {

    /** 单个字段信息 */
    public static class Column {
        public final String name;
        /** column_type，含长度，如 varchar(64) */
        public final String type;
        public final boolean nullable;
        /** PRI / UNI / MUL / "" */
        public final String key;
        /** 数据库里的默认值，null 表示 DEFAULT NULL */
        public final String defaultValue;
        /** auto_increment / on update CURRENT_TIMESTAMP 等 */
        public final String extra;
        public final String comment;

        public Column(String name, String type, boolean nullable, String key,
                      String defaultValue, String extra, String comment) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.key = key;
            this.defaultValue = defaultValue;
            this.extra = extra;
            this.comment = comment;
        }
    }

    /** 索引中的单列（一个索引多列拆成多行） */
    public static class IndexColumn {
        public final String indexName;
        public final boolean unique;
        public final int seq;
        public final String column;
        /** BTREE / FULLTEXT 等 */
        public final String indexType;

        public IndexColumn(String indexName, boolean unique, int seq, String column, String indexType) {
            this.indexName = indexName;
            this.unique = unique;
            this.seq = seq;
            this.column = column;
            this.indexType = indexType;
        }
    }

    /** 表级信息 */
    public static class TableInfo {
        public final String schema;
        public final String table;
        public final String comment;
        public final String engine;
        /** InnoDB 下为估算行数 */
        public final String rows;
        public final String collation;
        public final String createTime;

        public TableInfo(String schema, String table, String comment, String engine,
                         String rows, String collation, String createTime) {
            this.schema = schema;
            this.table = table;
            this.comment = comment;
            this.engine = engine;
            this.rows = rows;
            this.collation = collation;
            this.createTime = createTime;
        }
    }

    public final TableInfo tableInfo;
    public final List<Column> columns;
    public final List<IndexColumn> indexes;
    /** SHOW CREATE TABLE 结果；无权限或失败时为 null（弹窗里隐藏对应页签） */
    @Nullable
    public final String ddl;

    public TableStructure(TableInfo tableInfo, List<Column> columns, List<IndexColumn> indexes, @Nullable String ddl) {
        this.tableInfo = tableInfo;
        this.columns = columns;
        this.indexes = indexes;
        this.ddl = ddl;
    }
}
