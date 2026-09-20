package com.zhiyin.plugins.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P3-2：剪贴板 TSV 字段表解析器（纯函数、无 UI 依赖，供 DataModelGenerator 粘贴按钮与单测共用）。
 *
 * 输入口径：需求文档/Excel 里字段表直接复制出的 TSV——行按 \n 切、格按 \t 切，只取前 3 列
 * name/type/comment（第 4 列及以后忽略，需求表常带「必填」等附加列）。脏数据（空行、非法行）
 * 跳过并计数、不中断整体解析，条数由确认框展示；首行表头自动跳过单独记录（不算脏数据）。
 *
 * 为什么合法词表不靠 TableParser.getType 兜底判定：getType 未知类型一律落 string，
 * 本身无法区分「合法类型」与「非法类型」（需求表 type 列常混「文本」等人读词），TSV 校验
 * 必须自带显式词表；词表 = getType 的全部 case 输入词 ∪ 规范输出词，映射本身仍复用 getType
 * （输出词如 string/int 走 default/case 分支恰好各自正确归位）。
 */
public final class TsvFieldParser {

    /** 字段名合法形态：字母/下划线开头，后随字母/数字/下划线——数字开头（序号列）、中文等混入即非法行 */
    static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * type 列合法形态：裸类型词（不区分大小写）可带括号参数，参数仅认数字（P 或 P,S——P2-5 的
     * length 保留 scale 语义）；`varchar(64)`→(varchar, 64)、`decimal(19,4)`→(decimal, 19,4)。
     * 整体不匹配（如「文本」、`varchar(abc)`）视为非法 type 行。
     */
    static final Pattern TYPE_WITH_PARAMS = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\((\\d+(?:\\s*,\\s*\\d+)?)\\))?$");

    /**
     * type 合法词表（显式列出，勿靠 getType 兜底判定——见类注释）：
     * getType 的全部 case 输入词 ∪ 规范输出词 {string,int,decimal,date,datetime,float,enum}
     */
    static final Set<String> VALID_TYPE_WORDS = Set.of(
            // string 族（getType case 输入）
            "varchar", "char", "text", "tinytext", "mediumtext", "longtext", "string",
            // int 族
            "int", "integer", "tinyint", "smallint", "mediumint", "bigint", "bit",
            // 其余独立类型 + 规范输出词
            "decimal", "date", "datetime", "timestamp", "time", "float", "double", "enum");

    /**
     * 表头关键词集（col1 小写化后命中才进入「col1 合法但疑似表头」分支）：
     * 需求文档表头常见叫法。判定须叠加「col2 不是合法类型词」——否则 `name/varchar/名称`
     * （真字段 name）、`type/int/类型`（真字段 type）会被误杀
     */
    static final Set<String> HEADER_KEYWORDS = Set.of(
            "字段名", "字段名称", "列名", "字段英文名", "英文名", "name", "field", "column",
            "type", "字段类型", "类型", "数据类型",
            "说明", "备注", "注释", "描述", "中文名", "中文名称", "comment", "desc", "title");

    private TsvFieldParser() {
    }

    /**
     * 解析剪贴板 TSV 文本。行为要点：
     * - 表头只看第一个非空行：col1 不是合法标识符，或「col1 命中表头关键词且 col2 不是合法类型词」
     *   即判表头跳过（headerSkipped=true，不计脏数据）
     * - 数据行：col1 合法标识符（name 小写化，与 parseCreateTable 出口同口径）；col2 空缺省 string、
     *   非空剥括号后须在词表内；comment 缺省 ""；字段 Map 只含 name/type/length/comment——
     *   is* 标志与 easyuiClass 不预填，留给 updateTableModel 既有启发式兜底（与 DDL/DB 路径一致）
     * - 空行计数：前导/中间空行计入 blankLines；末尾换行产生的空尾行剥掉不计数
     * - 非法行（name 非法标识符或 type 不在词表）计入 invalidLines，跳过不中断
     */
    public static TsvParseResult parse(String clipboardText) {
        List<Map<String, Object>> validFields = new ArrayList<>();
        boolean headerSkipped = false;
        int blankLines = 0;
        int invalidLines = 0;

        if (clipboardText == null || clipboardText.isEmpty()) {
            return new TsvParseResult(validFields, headerSkipped, blankLines, invalidLines);
        }

        // 换行规范化：Windows 剪贴板 \r\n、罕见孤立 \r 统一为 \n，行切分才不残留 \r 尾巴
        String normalized = clipboardText.replace("\r\n", "\n").replace('\r', '\n');
        // limit=-1 保留末尾空串（末尾换行的产物）；从尾部剥掉连续空行——不算脏数据
        String[] lines = normalized.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].trim().isEmpty()) {
            end--;
        }

        int i = 0;
        // 前导空行是实际内容（非换行产物），按空行计数后再做表头判定
        while (i < end && lines[i].trim().isEmpty()) {
            blankLines++;
            i++;
        }

        // 表头自动跳过：仅看第一个非空行
        if (i < end && isHeaderRow(splitCells(lines[i]))) {
            headerSkipped = true;
            i++;
        }

        for (; i < end; i++) {
            if (lines[i].trim().isEmpty()) {
                blankLines++;
                continue;
            }
            String[] cells = splitCells(lines[i]);
            // col1：字段名（小写化出口与 parseCreateTable 同口径）
            String nameCell = cells[0].trim();
            if (!NAME_PATTERN.matcher(nameCell).matches()) {
                invalidLines++;
                continue;
            }
            // col2：type（空缺省 string；非空剥括号参数后须在合法词表内）
            String typeCell = cells.length > 1 ? cells[1].trim() : "";
            String type;
            String length = "";
            if (typeCell.isEmpty()) {
                type = "string";
            } else {
                Matcher typeMatcher = TYPE_WITH_PARAMS.matcher(typeCell);
                if (!typeMatcher.matches() || !VALID_TYPE_WORDS.contains(typeMatcher.group(1).toLowerCase())) {
                    invalidLines++;
                    continue;
                }
                type = TableParser.getType(typeMatcher.group(1).toLowerCase());
                // 括号参数即 length：varchar(64)→"64"、decimal(19,4)→"19,4"（去参数内空格），无括号 ""
                if (typeMatcher.group(2) != null) {
                    length = typeMatcher.group(2).replace(" ", "");
                }
            }
            // col3：comment 原样保留（缺省空串）；第 4 列及以后忽略
            String comment = cells.length > 2 ? cells[2] : "";

            Map<String, Object> field = new HashMap<>();
            field.put("name", nameCell.toLowerCase());
            field.put("type", type);
            field.put("length", length);
            field.put("comment", comment);
            validFields.add(field);
        }
        return new TsvParseResult(validFields, headerSkipped, blankLines, invalidLines);
    }

    /** 行按 \t 切格（split 默认丢尾空串不影响取前 3 列语义：缺列即缺省值） */
    private static String[] splitCells(String line) {
        return line.split("\t");
    }

    /**
     * 表头行判定（只用于第一非空行）：col1 不是合法标识符（如「字段名」「序号」）直接判表头；
     * col1 是合法标识符但命中表头关键词时，还须 col2 不是合法类型词才判表头——
     * 否则 `name/varchar/名称`（真字段 name）、`type/int/类型`（真字段 type）会被误杀
     */
    private static boolean isHeaderRow(String[] cells) {
        String col1 = cells[0].trim().toLowerCase();
        if (!NAME_PATTERN.matcher(col1).matches()) {
            return true;
        }
        if (!HEADER_KEYWORDS.contains(col1)) {
            return false;
        }
        String col2 = cells.length > 1 ? cells[1].trim() : "";
        return !isKnownTypeWord(col2);
    }

    /** type 列是否为合法类型词（剥括号参数后查词表；空串不算——单列「name」首行仍判表头） */
    private static boolean isKnownTypeWord(String typeCell) {
        if (typeCell == null || typeCell.isEmpty()) {
            return false;
        }
        Matcher matcher = TYPE_WITH_PARAMS.matcher(typeCell);
        return matcher.matches() && VALID_TYPE_WORDS.contains(matcher.group(1).toLowerCase());
    }

    /** 解析结果：有效字段行 + 各类跳过计数（供确认框展示，不显示 0 条噪音由 UI 层裁剪） */
    public static final class TsvParseResult {
        public final List<Map<String, Object>> validFields;
        public final boolean headerSkipped;
        public final int blankLines;
        public final int invalidLines;

        TsvParseResult(List<Map<String, Object>> validFields, boolean headerSkipped,
                       int blankLines, int invalidLines) {
            this.validFields = validFields;
            this.headerSkipped = headerSkipped;
            this.blankLines = blankLines;
            this.invalidLines = invalidLines;
        }

        /** 跳过总行数（空行 + 非法行），确认框「跳过脏数据 M 行」同源取值 */
        public int getSkippedTotal() {
            return blankLines + invalidLines;
        }
    }
}
