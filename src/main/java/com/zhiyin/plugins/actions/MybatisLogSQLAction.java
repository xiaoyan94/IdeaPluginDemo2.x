package com.zhiyin.plugins.actions;

import com.intellij.ide.CopyProvider;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.ide.CopyPasteManager;
import com.zhiyin.plugins.notification.MyPluginMessages;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MybatisLogSQLAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        DataContext dataContext = e.getDataContext();
        CopyProvider provider = PlatformDataKeys.COPY_PROVIDER.getData(dataContext);
        if (provider == null) {
            return;
        }
        provider.performCopy(dataContext);
        String string = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);

        String sql = extractSQLFromMyBatisLog(string);

        MyPluginMessages.showInfo("MybatisLog2SQL", sql, e.getProject());

        // Copy SQL to the system clipboard
        CopyPasteManager.getInstance().setContents(new StringSelection(sql));
    }

    private String extractSQLFromMyBatisLog(String log) {
        if (log == null || log.isEmpty()) {
            return "找不到日志内容";
        }

        // Regex patterns to extract different parts of MyBatis log
        Pattern preparedPattern = Pattern.compile("Preparing: (.+)");
        Pattern parametersPattern = Pattern.compile("Parameters: (.+)");

        // Collect all Parameters matches with their offsets, so each Preparing pairs with the nearest following Parameters
        List<Integer> parametersStarts = new ArrayList<>();
        List<String[]> parametersList = new ArrayList<>();
        Matcher parametersMatcher = parametersPattern.matcher(log);
        while (parametersMatcher.find()) {
            parametersStarts.add(parametersMatcher.start());
            parametersList.add(parametersMatcher.group(1).trim().split(",\\s*"));
        }

        // Each Preparing produces one statement; multiple statements are separated by blank lines
        List<String> sqlList = new ArrayList<>();
        Matcher preparedMatcher = preparedPattern.matcher(log);
        int paramIndex = 0;
        while (preparedMatcher.find()) {
            String sqlTemplate = preparedMatcher.group(1).trim();
            String[] parameters = null;
            if (paramIndex < parametersList.size() && parametersStarts.get(paramIndex) > preparedMatcher.start()) {
                parameters = parametersList.get(paramIndex);
                paramIndex++;
            }

            // Replace placeholders with actual parameter values, then format SQL
            String finalSql = formatSql(replaceSqlPlaceholders(sqlTemplate, parameters));
            sqlList.add(finalSql + ";");
        }

        // If no SQL template found, return error message
        if (sqlList.isEmpty()) {
            return "无法从日志中提取SQL";
        }

        return String.join("\n\n", sqlList);
    }

    private String formatSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }

        // 1. 压缩多余空格并去除首尾空白
        String compressed = sql.replaceAll("\\s+", " ").trim();

        // 2. 在关键字前添加换行（不区分大小写）
        String formatted = compressed.replaceAll("(?i)\\b(FROM|WHERE|HAVING|GROUP BY|ORDER BY|LEFT JOIN|INNER JOIN|RIGHT JOIN)\\b", "\n$1");

        // 3. 移除开头的多余换行（若关键字是首单词）
        return formatted.replaceFirst("^\n", "");
    }


    private String replaceSqlPlaceholders(String sqlTemplate, String[] parameters) {
        if (parameters == null || parameters.length == 0) {
            return sqlTemplate;
        }

        StringBuilder replacedSql = new StringBuilder(sqlTemplate);
        int paramIndex = 0;
        int placeholderIndex = replacedSql.indexOf("?");

        while (placeholderIndex != -1 && paramIndex < parameters.length) {
            String param = parameters[paramIndex].trim();

            // Handle different parameter types
            String processedParam = processParameterValue(param);

            replacedSql.replace(placeholderIndex, placeholderIndex + 1, processedParam);

            // Find next placeholder
            placeholderIndex = replacedSql.indexOf("?");
            paramIndex++;
        }

        return replacedSql.toString();
    }

    private String processParameterValue(String param) {
        // Remove type information (like (String), (Integer), etc.)
        param = param.replaceAll("\\(.*?\\)", "").trim();

        // Handle null values
        if (param.equalsIgnoreCase("null")) {
            return "NULL";
        }

        // Handle string values
        if (!param.matches("^-?\\d+(\\.\\d+)?$")) {
            // If not a number, assume it's a string and add quotes
            return "'" + param.replace("'", "''") + "'";
        }

        // Return numeric values as-is
        return param;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        DataContext dataContext = event.getDataContext();
        CopyProvider provider = PlatformDataKeys.COPY_PROVIDER.getData(dataContext);
        boolean available = provider != null && provider.isCopyEnabled(dataContext) && provider.isCopyVisible(dataContext);
        presentation.setEnabledAndVisible(available);
    }
}