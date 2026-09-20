package com.zhiyin.plugins.utils;

import com.zhiyin.plugins.translator.TranslateException;
import com.zhiyin.plugins.translator.baidu.BaiduTranslator;
import com.zhiyin.plugins.translator.youdao.YouDaoTranslate;

import java.util.*;
import java.util.regex.*;

public class TableParser {

    // Method to extract table name from CREATE TABLE statement
    public static String extractTableName(String createTableSQL) {
        // Regular expression to match table name after CREATE TABLE keyword
        // P2-7 附带修复：兼容 `CREATE TABLE IF NOT EXISTS` 形态（登骐 docs SQL 原生写法；
        // 旧正则对此形态返回 null，生成被「模型数据为空」拦截）
        @SuppressWarnings("RegExpRedundantClassElement")
        String regex = "\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([\\w\\d_]+)`?\\s*\\(";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(createTableSQL);

        if (matcher.find()) {
            return matcher.group(1); // Group 1 contains the table name
        } else {
            return null; // Return null if no table name is found
        }
    }

    /**
     * P1-9：按「字段定义片段化」解析 CREATE TABLE——先定位列定义体（第一个 '(' 到与之
     * 配对的 ')' 之间），再在顶层逗号处切片段，逐片段提取列名/类型/长度/注释/非空标记。
     * 旧实现靠整段正则 + 「. 不跨行」偶然保证 comment 归属，DDL 被单行化（DDL 粘贴输入框
     * 是单行 JTextField，换行被剥离）后无 COMMENT 的字段会跨字段抢走后面第一个 COMMENT，
     * 造成注释整体错位；且 nullable 正则的 [^,]+ 会被 decimal(19,4) 类型内逗号截断致
     * NOT NULL 检测失效。片段化后两个问题一并消除。
     */
    public static List<Map<String, Object>> parseCreateTable(String createTableSql) {
        String columnBody = extractColumnBody(createTableSql);
        if (columnBody != null) {
            return parseColumnFragments(columnBody);
        }
        // 列定义体定位失败（畸形 DDL）：回退旧的整段正则解析，保证不比修复前更差
        return parseCreateTableByRegex(createTableSql);
    }

    /**
     * 定位 CREATE TABLE 的列定义体：第一个 '(' 到与之配对的 ')' 之间。
     * 括号深度与单引号字符串感知（字符串内 '' 为转义单引号，不计入配对）；定位失败返回 null。
     */
    private static String extractColumnBody(String sql) {
        int open = sql.indexOf('(');
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++; // '' 为字符串内转义的单引号，不结束字符串
                    } else {
                        inString = false;
                    }
                }
            } else if (c == '\'') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(open + 1, i);
                }
            }
        }
        return null; // 找不到与之配对的右括号
    }

    /**
     * 在顶层逗号处把列定义体切分为片段：括号深度感知，单引号字符串内的逗号/括号不参与
     * 切分（'' 转义不结束字符串），KEY (`a`,`b`) 与 decimal(19,4) 内的逗号都不会误切。
     */
    private static List<String> splitColumnDefinitions(String body) {
        List<String> fragments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++; // '' 为转义单引号，整对保留在片段内（COMMENT 值还原时再展开）
                    } else {
                        inString = false;
                    }
                }
            } else if (c == '\'') {
                inString = true;
                current.append(c);
            } else if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    fragments.add(trimmed);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            fragments.add(tail);
        }
        return fragments;
    }

    /**
     * 逐片段提取列定义：片段头 `name` type(len[,scale]) 定名列/类型/长度——length 在 P2-5 后
     * 保留 scale（decimal(19,4)→"19,4"，词表 length="18,4" 形态 16 例实证；无 scale 时仅 len，
     * int(11)/varchar(64) 行为不变，无括号为空串）；列类型为 enum 时额外写 enumRef=列名小写
     * （GATE-E：459 例 enum 字段 100% 带 enum= 属性，字典 code 默认字段名，用户可手改）；
     * 片段内找 COMMENT '...'（值内 '' 还原为 '）；片段含 NOT NULL 或 AUTO_INCREMENT 则
     * nullable=false。不具列定义形态的片段（PRIMARY KEY / KEY / UNIQUE KEY / CONSTRAINT /
     * INDEX 开头，片段头不匹配）直接跳过。
     */
    private static List<Map<String, Object>> parseColumnFragments(String body) {
        List<Map<String, Object>> columns = new ArrayList<>();

        Pattern headerPattern = Pattern.compile("`(\\w+)`\\s+(\\w+)(\\((\\d+)(,(\\d+))?\\))?");
        // COMMENT 值内 '' 为转义单引号，整对消费后再还原，避免值内引号提前截断
        Pattern commentPattern = Pattern.compile("COMMENT\\s+'((?:[^']|'')*)'");

        for (String fragment : splitColumnDefinitions(body)) {
            Matcher header = headerPattern.matcher(fragment);
            if (!header.lookingAt()) {
                continue;
            }
            String columnName = header.group(1);
            boolean notNullable = fragment.contains("NOT NULL") || fragment.contains("AUTO_INCREMENT");

            String comment = "";
            Matcher commentMatcher = commentPattern.matcher(fragment);
            if (commentMatcher.find()) {
                comment = commentMatcher.group(1).replace("''", "'");
            }

            Map<String, Object> columnInfo = new HashMap<>();
            columnInfo.put("name", columnName == null ? "" : columnName.toLowerCase());
            columnInfo.put("type", getType(header.group(2)));
            // P2-5：length 保留 scale——group(4)=P、group(6)=S 均非空时 "P,S"，无 scale 仅 P
            if (header.group(4) != null && header.group(6) != null) {
                columnInfo.put("length", header.group(4) + "," + header.group(6));
            } else if (header.group(4) != null) {
                columnInfo.put("length", header.group(4));
            } else {
                columnInfo.put("length", "");
            }
            if ("enum".equalsIgnoreCase(header.group(2))) {
                columnInfo.put("enumRef", columnInfo.get("name"));
            }
            columnInfo.put("nullable", notNullable ? "false" : "true");
            columnInfo.put("isRequired", notNullable ? "true" : "false");
            columnInfo.put("comment", comment);
            columns.add(columnInfo);
        }
        return columns;
    }

    /** 旧版整段正则解析：仅在列定义体定位失败（畸形 DDL）时兜底。P2-5 起与主路径同步维护
     *  length 保留 scale / enum 列 enumRef / getType 映射，其余保持原始实现 */
    private static List<Map<String, Object>> parseCreateTableByRegex(String createTableSql) {
        List<Map<String, Object>> columns = new ArrayList<>();

        // Regular expression to match columns
        String columnRegex = "`(\\w+)`\\s+(\\w+)(\\((\\d+)(,(\\d+))?\\))?";
        Pattern columnPattern = Pattern.compile(columnRegex);
        Matcher columnMatcher = columnPattern.matcher(createTableSql);

        // Regular expression to match comments
        String commentRegex = "`(\\w+)`.*?COMMENT\\s+'(.*?)'";
        Pattern commentPattern = Pattern.compile(commentRegex);
        Matcher commentMatcher = commentPattern.matcher(createTableSql);

        // Regular expression to match nullable information
        String nullableRegex = "`(\\w+)`\\s+(\\w+)([^,]+)";
        Pattern nullablePattern = Pattern.compile(nullableRegex);
        Matcher nullableMatcher = nullablePattern.matcher(createTableSql);

        Map<String, String> comments = new HashMap<>();
        while (commentMatcher.find()) {
            comments.put(commentMatcher.group(1), commentMatcher.group(2));
        }

        Map<String, Boolean> nullables = new HashMap<>();
        while (nullableMatcher.find()) {
            String columnName = nullableMatcher.group(1);
            String nullable = nullableMatcher.group(3);
            boolean isNullable = !(nullable.contains("NOT NULL") || nullable.contains("AUTO_INCREMENT"));
            nullables.put(columnName, isNullable);
        }

        while (columnMatcher.find()) {
            Map<String, Object> columnInfo = new HashMap<>();
            String columnName = columnMatcher.group(1);
            columnInfo.put("name", columnName == null ? "" : columnName.toLowerCase());
            columnInfo.put("type", getType(columnMatcher.group(2)));

            // P2-5：与 parseColumnFragments 同款修复——length 保留 scale，enum 列写 enumRef=列名小写，
            // 维持「片段化主路径与正则兜底产物一致」不变量（getType 经共享函数自动生效）
            if (columnMatcher.group(4) != null && columnMatcher.group(6) != null) {
                columnInfo.put("length", columnMatcher.group(4) + "," + columnMatcher.group(6));
            } else if (columnMatcher.group(4) != null) {
                columnInfo.put("length", columnMatcher.group(4));
            } else {
                columnInfo.put("length", "");
            }
            if ("enum".equalsIgnoreCase(columnMatcher.group(2))) {
                columnInfo.put("enumRef", columnInfo.get("name"));
            }

            columnInfo.put("nullable", nullables.getOrDefault(columnName, true).toString());
            columnInfo.put("isRequired", "true".equals(columnInfo.get("nullable")) ? "false" : "true");
            columnInfo.put("comment", comments.getOrDefault(columnName, ""));

            columns.add(columnInfo);
        }

        return columns;
    }

    /**
     * DDL 类型 → Moc 类型映射（P2-5，GATE-E 词表：DengqiMes 1245 个既有 Moc XML 统计定案，不得臆造）：
     * varchar/char/text 族 → string；int 族（int/integer/tinyint/smallint/mediumint/bigint/bit）→ int
     * （bigint 主键全库 1113 例中 1109 为 int）；decimal → decimal（不再折算 number，词表 109 例实证）；
     * date → date（58 例独立类型）；datetime/timestamp/time → datetime；float/double → float；
     * enum → enum（enum= 属性由模板层经 enumRef 输出，字典 code 默认字段名，用户可手改）；
     * 未知类型（json/blob 等）兜底 string。输出一律小写。
     */
    public static String getType(String type){
        if(type == null){
            return "";
        }
        switch (type.toLowerCase()){
            case "varchar":
            case "char":
            case "text":
            case "tinytext":
            case "mediumtext":
            case "longtext":
                return "string";
            case "int":
            case "integer":
            case "tinyint":
            case "smallint":
            case "mediumint":
            case "bigint":
            case "bit":
                return "int";
            case "decimal":
                return "decimal";
            case "date":
                return "date";
            case "datetime":
            case "timestamp":
            case "time":
                return "datetime";
            case "float":
            case "double":
                return "float";
            case "enum":
                return "enum";
            default:
                return "string";
        }
    }

    /**
     * 解析DQL
     * @param createTableSql
     * @return
     */
    public static List<Map<String, Object>> parseDQL(String sql) {
        List<Map<String, Object>> columns = new ArrayList<>();

        // 提取SELECT和FROM之间的部分
        Pattern selectPattern = Pattern.compile("select\\s+(.+?)\\s+from",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher selectMatcher = selectPattern.matcher(sql);

        if (!selectMatcher.find()) {
            throw new IllegalArgumentException("Invalid SQL: Cannot find SELECT clause");
        }

        String selectClause = selectMatcher.group(1);

        // 分割字段（考虑函数中的逗号）
        List<String> fieldExpressions = splitFields(selectClause);

        // YouDaoTranslate translator = new YouDaoTranslate();
        BaiduTranslator translator = new BaiduTranslator();

        for (String expr : fieldExpressions) {
            expr = expr.trim();

            // 检查是否有AS关键字（大小写不敏感）
            Pattern asPattern = Pattern.compile("(.+?)\\s+as\\s+([\\w]+)$",
                    Pattern.CASE_INSENSITIVE);
            Matcher asMatcher = asPattern.matcher(expr);

            String field;
            String alias;

            if (asMatcher.find()) {
                // 有AS的情况
                field = asMatcher.group(1).trim();
                alias = asMatcher.group(2).trim();
            } else {
                // 没有AS的情况，检查是否有简单别名
                Pattern simpleAliasPattern = Pattern.compile("(.+?)\\s+([\\w]+)$");
                Matcher simpleAliasMatcher = simpleAliasPattern.matcher(expr);

                if (simpleAliasMatcher.find()) {
                    field = simpleAliasMatcher.group(1).trim();
                    alias = simpleAliasMatcher.group(2).trim();
                } else {
                    // 没有别名，a.field --> 只保留field
                    field = expr.replaceAll("`", "").replaceAll("^\\s*\\w+\\.", "").trim();
                    alias = null;
                }
            }

            Map<String, Object> columnInfo = new HashMap<>();
            columnInfo.put("name", alias == null ? field : alias);
            columnInfo.put("alias", alias);
            columnInfo.put("type", "string");
            if (field != null && field.contains("date")) {
                columnInfo.put("type", "date");
            }
            if (field != null && field.contains("time")) {
                columnInfo.put("type", "datetime");
            }
            columnInfo.put("isRequired", "true");
            columnInfo.put("isQueryField" , "true");
            columnInfo.put("isDialogField" , "false");
            try {
                // columnInfo.put("comment", translator.translate(columnInfo.get("name").toString()));
                // TODO 默认中文配置
                // columnInfo.put("comment", translator.translate(columnInfo.get("name").toString(), "en", "zh"));
                columnInfo.put("comment", columnInfo.get("name"));
            } catch (Exception e) {
                columnInfo.put("comment", columnInfo.get("name"));
                e.printStackTrace();
            }
            columnInfo.put("nullable", "true");
            columnInfo.put("length", "120");
            columns.add(columnInfo);
        }

        return columns;
    }

    public static String extractTableNameFromDQL(String dql) {
        Pattern pattern = Pattern.compile("from\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(dql);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return "null";
        }
    }

    private static List<String> splitFields(String selectClause) {
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        int parenthesesCount = 0;

        for (char c : selectClause.toCharArray()) {
            if (c == '(') {
                parenthesesCount++;
            } else if (c == ')') {
                parenthesesCount--;
            }

            if (c == ',' && parenthesesCount == 0) {
                fields.add(currentField.toString().trim());
                currentField = new StringBuilder();
            } else {
                currentField.append(c);
            }
        }

        // 添加最后一个字段
        if (currentField.length() > 0) {
            fields.add(currentField.toString().trim());
        }

        return fields;
    }

    @SuppressWarnings("SpellCheckingInspection")
    public static void main(String[] args) {
        String createTableSql = "CREATE TABLE `biz_product` (\n" +
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
                ") ENGINE=InnoDB AUTO_INCREMENT=781740 DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='产品表'";

        List<Map<String, Object>> columns = parseCreateTable(createTableSql);
        for (Map<String, Object> column : columns) {
            System.out.println(column);
        }

        System.out.println("----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");

        String sql = "select 'sotMaterialReciept' as operatetype, " +
                "date(a.maintaintime) as date, " +
                "substring_index(substring_index(maintainer, '(', -1), ')', 1) as warehousemanager, " +
                "count(1) as times " +
                "from biz_wms_material_receipt_barcode a " +
                "where a.maintaintime > '2024-11-01 00:00:00' " +
                "and a.maintaintime < '2024-11-01 23:59:59' " +
                "group by 'sotMaterialReciept', date(a.maintaintime), a.maintainer";

        List<Map<String, Object>> parsedDQL = parseDQL(sql);
        for (Map<String, Object> map : parsedDQL) {
            System.out.println(map);
        }
    }
}
