package com.zhiyin.plugins.service;

import com.intellij.lang.properties.psi.Property;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
//import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;


import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.utils.MyPropertiesUtil;
import com.zhiyin.plugins.utils.ProjectTypeChecker;
import com.zhiyin.plugins.utils.StringUtil;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.intellij.openapi.util.text.StringUtil.isEmptyOrSpaces;

@Service(Service.Level.PROJECT)
public final class CodeGenerateService {
    final Project project;

    public CodeGenerateService(Project project) {
        this.project = project;
    }

    public void generateModelByFields(Module module, String folder, String modelName, String tableName, List<Map<String, Object>> fields) {
        if (project == null) {
            Messages.showErrorDialog("Project is not available", "Error");
            return;
        }

        if (module == null) {
            Messages.showErrorDialog("Module is not available", "Error");
            return;
        }

        if (folder == null || folder.isEmpty()) {
            folder = "Basic";
        }

        // Define the data model for the template
        Map<String, Object> dmMoc = new HashMap<>();
        dmMoc.put("mocName", modelName);
        dmMoc.put("tableName", tableName);
        dmMoc.put("fields", fields);

        Map<String, Object> dmLayout = new HashMap<>();
        dmLayout.put("layoutName", modelName);
        Map<String, Object> dataGrid1 = new HashMap<>();
        dataGrid1.put("dataGridName", modelName);
        dataGrid1.put("ckDummyColumn", "true");
        List<Map<String, Object>> columns = new ArrayList<>(fields);
        columns.forEach(field -> {
            field.put("chs", field.get("comment"));
            List<Property> properties = MyPropertiesUtil.findModuleDataGridI18nPropertiesByValue(project, module, field.get("comment").toString());
            if (!properties.isEmpty()) {
                field.put("i18nKey", properties.get(0).getKey());
                field.put("chs", StringUtil.unicodeToString(properties.get(0).getValue()));
                if (properties.size() > 2) {
                    field.put("cht", StringUtil.unicodeToString(properties.get(1).getValue()));
                    field.put("eng", properties.get(2).getValue());
                }
            }
        });
        dataGrid1.put("columns", columns);
        List<Map<String, Object>> queryFields = new ArrayList<>(fields);
        queryFields.forEach(field -> field.put("ref", field.get("name")));
        queryFields.forEach(field -> {
            if ((field.get("isQueryField") instanceof Boolean)) {
                field.put("isQueryField", String.valueOf(field.get("isQueryField")));
            }
        });
        dataGrid1.put("queryFields", queryFields);
        List<Map<String, Object>> dialogFields = new ArrayList<>(fields);

        int colEachRow = 2;
        int currentRow = 2;
        int i = 0;
        List<String> ignoreFields = List.of("id", "factoryid", "factoryname", "maintainer", "maintaintime");
        List<String> removeFields = List.of("factoryname", "maintainer", "maintaintime");
        removeFields.forEach(field -> {
            dialogFields.removeIf(f -> String.valueOf(f.get("name")).equals(field));
            queryFields.removeIf(f -> String.valueOf(f.get("name")).equals(field));
        });
        dialogFields.forEach(field -> {
            if ((field.get("isRequired") instanceof Boolean)) {
                field.put("required", String.valueOf(field.get("isRequired")));
            }
            field.put("editable", "true");
        });
        for (Map<String, Object> field : dialogFields) {
            if (!ignoreFields.contains(String.valueOf(field.get("name")))){
                i++;
                int curCol = i % colEachRow;
                if (curCol != 0) {
                    field.put("layout", currentRow + ":" + curCol);
                } else {
                    field.put("layout", currentRow + ":" + colEachRow);
                    currentRow++;
                }
            }
            field.put("easyuiClass", "easyui-textbox");
            field.put("ref", field.get("name"));
        }
        dataGrid1.put("dialogFields", dialogFields);
        String moduleName = MyPropertiesUtil.getSimpleModuleName(module);
        dmLayout.put("moduleName", StringUtil.capitalize(moduleName));
        dmLayout.put("dataGrids", List.of(dataGrid1));

        // Generate the XML file
        try {
            // TODO 生成 java 文件
            generateXmlFile(project, module, dmMoc, "moc.ftl", "src/main/webapp/WEB-INF/etc/business/model/" + folder, dmMoc.get("mocName") + ".xml");
            generateXmlFile(project, module, dmLayout, "layout.ftl", "src/main/webapp/WEB-INF/etc/business/layout/" + folder, dmLayout.get("layoutName") + ".xml");
        } catch (Exception ex) {
            Messages.showErrorDialog("无法生成件，异常： " + ex.getMessage(), "操作失败");
        }
    }

    public void generateMocFile(Module module, String folder, String modelName, String tableName, List<Map<String, Object>> fields) {
        if (project == null) {
            Messages.showErrorDialog("Project is not available", "Error");
            return;
        }

        if (module == null) {
            Messages.showErrorDialog("Module is not available", "Error");
            return;
        }

        if (folder == null || folder.isEmpty() || "web".equalsIgnoreCase(folder)) {
            String moduleName = MyPropertiesUtil.getSimpleModuleName(module);
            if (ProjectTypeChecker.isTraditionalJavaWebProject(project, module)) {
                if ("web".equals(moduleName)) {
                    modelName = "Basic";
                }
            }
            folder = StringUtil.capitalize(moduleName);
        }

        // Define the data model for the template
        Map<String, Object> dmMoc = new HashMap<>();
        dmMoc.put("mocName", modelName);
        dmMoc.put("tableName", tableName);
        dmMoc.put("fields", fields);

        // Generate the XML file
        Map<String, GenerateFileResult> results = new LinkedHashMap<>();
        try {
            String outputSourceContentRootPath = "src/main/webapp/WEB-INF/etc/business/model/" + folder;
            if (ProjectTypeChecker.isTraditionalJavaWebProject(project, module)){
                outputSourceContentRootPath = "src/main/resources/META-INF/resources/WEB-INF/etc/business/model/" + folder;
            }
            results.put(dmMoc.get("mocName") + ".xml", generateXmlFile(project, module, dmMoc, "moc.ftl", outputSourceContentRootPath, dmMoc.get("mocName") + ".xml"));
        } catch (Exception ex) {
            Messages.showErrorDialog("无法生成件，异常： " + ex.getMessage(), "操作失败");
        } finally {
            notifyGenerateSummary(results);
        }
    }

    public void generateLayoutFile(Module module, String folder, String modelName, String tableName, List<Map<String, Object>> fields) {
        if (project == null) {
            Messages.showErrorDialog("Project is not available", "Error");
            return;
        }

        if (module == null) {
            Messages.showErrorDialog("Module is not available", "Error");
            return;
        }

        if (folder == null || folder.isEmpty() || "web".equalsIgnoreCase(folder)) {
            String moduleName = MyPropertiesUtil.getSimpleModuleName(module);
            if (ProjectTypeChecker.isTraditionalJavaWebProject(project, module)) {
                if ("web".equals(moduleName)) {
                    modelName = "Basic";
                }
            }
            folder = StringUtil.capitalize(moduleName);
        }

        Map<String, Object> dmLayout = new HashMap<>();
        dmLayout.put("layoutName", modelName);
        Map<String, Object> dataGrid1 = new HashMap<>();
        dataGrid1.put("dataGridName", modelName);
        dataGrid1.put("ckDummyColumn", "true");
        List<Map<String, Object>> columns = new ArrayList<>(fields);
        columns.forEach(field -> {
            field.put("chs", field.get("comment"));
            List<Property> properties = MyPropertiesUtil.findModuleDataGridI18nPropertiesByValue(project, module, field.get("comment").toString());
            if (!properties.isEmpty()) {
                field.put("i18nKey", properties.get(0).getKey());
                field.put("chs", properties.get(0).getValue());
                if (properties.size() > 2) {
                    field.put("cht", properties.get(1).getValue());
                    field.put("eng", properties.get(2).getValue());
                }
            }
        });
        dataGrid1.put("columns", columns);
        List<Map<String, Object>> queryFields = new ArrayList<>(fields);
        queryFields.forEach(field -> field.put("ref", field.get("name")));
        queryFields.forEach(field -> {
            if ((field.get("isQueryField") instanceof Boolean)) {
                field.put("isQueryField", String.valueOf(field.get("isQueryField")));
            }
        });
        dataGrid1.put("queryFields", queryFields);
        List<Map<String, Object>> dialogFields = new ArrayList<>(fields);

        int colEachRow = 2;
        int currentRow = 2;
        int i = 0;
        List<String> ignoreFields = List.of("id", "factoryid", "factoryname", "maintainer", "maintaintime");
        List<String> removeFields = List.of("factoryname", "maintainer", "maintaintime");
        removeFields.forEach(field -> {
            dialogFields.removeIf(f -> String.valueOf(f.get("name")).equals(field));
            queryFields.removeIf(f -> String.valueOf(f.get("name")).equals(field));
        });
        dialogFields.forEach(field -> {
            if ((field.get("isRequired") instanceof Boolean)) {
                field.put("required", String.valueOf(field.get("isRequired")));
            }
            field.put("editable", "true");
        });
        for (Map<String, Object> field : dialogFields) {
            if (!ignoreFields.contains(String.valueOf(field.get("name")))){
                i++;
                int curCol = i % colEachRow;
                if (curCol != 0) {
                    field.put("layout", currentRow + ":" + curCol);
                } else {
                    field.put("layout", currentRow + ":" + colEachRow);
                    currentRow++;
                }
            }
            field.put("easyuiClass", "easyui-textbox");
            field.put("ref", field.get("name"));
        }
        dataGrid1.put("dialogFields", dialogFields);
        String moduleName = MyPropertiesUtil.getSimpleModuleName(module);
        dmLayout.put("moduleName", StringUtil.capitalize(moduleName));
        dmLayout.put("dataGrids", List.of(dataGrid1));

        // Generate the XML file
        Map<String, GenerateFileResult> results = new LinkedHashMap<>();
        try {
            String outputSourceContentRootPath = "src/main/webapp/WEB-INF/etc/business/layout/" + folder;
            if (ProjectTypeChecker.isTraditionalJavaWebProject(project, module)){
                outputSourceContentRootPath = "src/main/resources/META-INF/resources/WEB-INF/etc/business/layout/" + folder;
            }
            results.put(dmLayout.get("layoutName") + ".xml", generateXmlFile(project, module, dmLayout, "layout.ftl", outputSourceContentRootPath, dmLayout.get("layoutName") + ".xml"));
        } catch (Exception ex) {
            Messages.showErrorDialog("无法生成件，异常： " + ex.getMessage(), "操作失败");
        } finally {
            notifyGenerateSummary(results);
        }
    }

    /**
     * P1-7：i18nByComment 为后台预查好的「字段 comment → DataGrid i18n 命中」结果（见
     * MyPropertiesUtil#findModuleDataGridI18nPropertiesByValueBatch 与 DataModelGenerator#generateDataModel），
     * 本方法在 EDT 上被调用，不再直接做索引反查。
     */
    public void generateBaseQueryTypeFile(Module module, String folder, String modelName, String fileName, String sql, List<Map<String, Object>> fields, Map<String, Object> paramsMap, Map<String, List<Property>> i18nByComment) {
        if (project == null) {
            Messages.showErrorDialog("Project is not available", "Error");
            return;
        }

        if (module == null) {
            Messages.showErrorDialog("Module is not available", "Error");
            return;
        }

        if (folder == null || folder.isEmpty() || "web".equalsIgnoreCase(folder)) {
            String moduleName = MyPropertiesUtil.getSimpleModuleName(module);
            if (ProjectTypeChecker.isTraditionalJavaWebProject(project, module)) {
                if ("web".equals(moduleName)) {
                    moduleName = "Basic";
                }
            }
            folder = StringUtil.capitalize(moduleName);
        }

        Map<String, Object> dmLayout = new HashMap<>();
        dmLayout.put("layoutName", modelName);
        Map<String, Object> dataGrid1 = new HashMap<>();
        dataGrid1.put("dataGridName", modelName);
        dataGrid1.put("ObjectName", modelName);
        // dataGrid1.put("objectName", modelName.replaceFirst(modelName.substring(0, 1), modelName.substring(0, 1).toLowerCase()));
        dataGrid1.put("objectName", StringUtil.firstLetterToLowerCase(modelName));
        dataGrid1.put("fileName", fileName);
        dataGrid1.put("tableName", paramsMap.get("tableName"));
        // dataGrid1.put("sql", sql.replace(" from ", " \nfrom ").replace(" where", " \nwhere "));
        dataGrid1.put("sql", formatSql(sql));
        dataGrid1.put("ckDummyColumn", "true");
        List<Map<String, Object>> columns = new ArrayList<>(fields);
        Map<String, List<Property>> precomputedI18n = i18nByComment == null ? Collections.emptyMap() : i18nByComment;
        columns.forEach(field -> {
            field.put("chs", field.get("comment"));
            Object commentObj = field.get("comment");
            List<Property> properties = commentObj == null
                    ? Collections.emptyList()
                    : precomputedI18n.getOrDefault(commentObj.toString(), Collections.emptyList());
            if (!properties.isEmpty()) {
                field.put("i18nKey", properties.get(0).getKey());
                field.put("chs", properties.get(0).getValue());
                if (properties.size() > 2) {
                    field.put("cht", properties.get(1).getValue());
                    field.put("eng", properties.get(2).getValue());
                }
            }
        });
        dataGrid1.put("columns", columns);
        List<Map<String, Object>> queryFields = new ArrayList<>(fields);
        queryFields.forEach(field -> field.put("ref", field.get("name")));
        queryFields.forEach(field -> {
            if ((field.get("isQueryField") instanceof Boolean)) {
                field.put("isQueryField", String.valueOf(field.get("isQueryField")));
                // field.put("isQueryField", "true");
            }
        });
        dataGrid1.put("queryFields", queryFields);

        /*List<Map<String, Object>> dialogFields = new ArrayList<>(fields);
        int colEachRow = 2;
        int currentRow = 2;
        int i = 0;
        List<String> ignoreFields = List.of("id", "factoryid", "factoryname", "maintainer", "maintaintime");
        List<String> removeFields = List.of("factoryname", "maintainer", "maintaintime");
        removeFields.forEach(field -> {
            dialogFields.removeIf(f -> String.valueOf(f.get("name")).equals(field));
            queryFields.removeIf(f -> String.valueOf(f.get("name")).equals(field));
        });
        dialogFields.forEach(field -> {
            if ((field.get("isRequired") instanceof Boolean)) {
                field.put("required", String.valueOf(field.get("isRequired")));
            }
            field.put("editable", "true");
        });
        for (Map<String, Object> field : dialogFields) {
            if (!ignoreFields.contains(String.valueOf(field.get("name")))){
                i++;
                int curCol = i % colEachRow;
                if (curCol != 0) {
                    field.put("layout", currentRow + ":" + curCol);
                } else {
                    field.put("layout", currentRow + ":" + colEachRow);
                    currentRow++;
                }
            }
            field.put("easyuiClass", "easyui-textbox");
            field.put("ref", field.get("name"));
        }
        dataGrid1.put("dialogFields", dialogFields);*/
        String moduleName = MyPropertiesUtil.getSimpleModuleName(module);
        dmLayout.put("moduleName", StringUtil.capitalize(moduleName));
        dmLayout.put("dataGrids", List.of(dataGrid1));
        // P1-4：Excel 导入/导出链路可选，标志放 dmLayout 根（模板 <#if generateImport/generateExport> 直取根变量）；
        // paramsMap 缺 key 时按 UI 复选框默认兜底（导出勾、导入不勾），兼容旧调用方
        dmLayout.put("generateImport", resolveGenerateImport(paramsMap));
        dmLayout.put("generateExport", resolveGenerateExport(paramsMap));

        // Generate the XML file
        Map<String, GenerateFileResult> results = new LinkedHashMap<>();
        try {
            String outputSourceContentRootPath = "src/main/webapp/WEB-INF/etc/business/layout/" + folder;
            String outputSourceContentHtmlPath = "src/main/webapp/WEB-INF/view/MesRoot/" + folder;
            String folderLowerCase = folder.toLowerCase();
            String outputSourceContentControllerPath = "src/main/java/com/zhiyin/controller/mes/" + folderLowerCase + "/";
            if ("basic".equals(folderLowerCase)) {
                outputSourceContentControllerPath = "src/main/java/com/zhiyin/controller/" + folderLowerCase + "/";
            }
            String outputSourceContentServicePath = "src/main/java/com/zhiyin/service/" + folderLowerCase + "/";
            String outputSourceContentDaoPath = "src/main/java/com/zhiyin/dao/" + folderLowerCase + "/";
            String outputSourceContentMapperPath = "src/main/java/com/zhiyin/maps/" + folderLowerCase + "/";

            Object outputFileName = dmLayout.get("layoutName");
            // P1-3：传统 Java Web 项目 layout 只写 META-INF 路径一处（原先先写 webapp 触发目录不存在跳过噪音，再无条件写第二次）
            if (ProjectTypeChecker.isTraditionalJavaWebProject(project, module) && (Boolean) paramsMap.get("layoutCheckBox")) {
                outputSourceContentRootPath = "src/main/resources/META-INF/resources/WEB-INF/etc/business/layout/" + folder;
            }
            if ((Boolean) paramsMap.get("layoutCheckBox")) {
                results.put(outputFileName + ".xml", generateXmlFile(project, module, dmLayout, "BaseQueryTypeLayout.ftl", outputSourceContentRootPath, outputFileName + ".xml"));
            }
            if ((Boolean) paramsMap.get("htmlCheckBox")) {
                results.put(outputFileName + ".html", generateXmlFile(project, module, dmLayout, "BaseQueryTypeHtml.ftl", outputSourceContentHtmlPath, outputFileName + ".html"));
            }
            if ((Boolean) paramsMap.get("controllerCheckBox")) {
                results.put(outputFileName + "Controller.java", generateXmlFile(project, module, dmLayout, "BaseQueryTypeController.ftl", outputSourceContentControllerPath, outputFileName + "Controller.java"));
            }
            if ((Boolean) paramsMap.get("serviceCheckBox")) {
                results.put(outputFileName + "Service.java", generateXmlFile(project, module, dmLayout, "BaseQueryTypeService.ftl", outputSourceContentServicePath, outputFileName + "Service.java"));
            }
            if ((Boolean) paramsMap.get("daoCheckBox")) {
                results.put("I" + outputFileName + "Dao.java", generateXmlFile(project, module, dmLayout, "BaseQueryTypeDao.ftl", outputSourceContentDaoPath, "I" + outputFileName + "Dao.java"));
            }
            if ((Boolean) paramsMap.get("myBatisMapperCheckBox")) {
                results.put(outputFileName + "Mapper.xml", generateXmlFile(project, module, dmLayout, "BaseQueryTypeMapper.ftl", outputSourceContentMapperPath, outputFileName + "Mapper.xml"));
            }
        } catch (Exception ex) {
            Messages.showErrorDialog("无法生成件，异常： " + ex.getMessage(), "操作失败");
        } finally {
            notifyGenerateSummary(results);
        }
    }

    // P1-1：单件产物生成结果，供调用方收集后一次汇总通知（不再降级写入模块根目录）
    enum GenerateFileResult {
        SUCCESS, SKIP_FILE_EXISTS, FAIL_DIR_NOT_FOUND
    }

    private GenerateFileResult generateXmlFile(Project project, Module module, Map<String, Object> dataModel, String templateName, String outputSourceContentRootPath, String outputFileName) throws IOException {
        Configuration cfg = FreeMarkerConfiguration.getConfiguration();
        Template template = cfg.getTemplate(templateName);

        // Define the path relative to the module's content root
        VirtualFile contentRoot = findModuleContentRoot(module);
        if (contentRoot == null) {
            throw new IOException("Module content root not found");
        }

        // Build the output file path
        VirtualFile outputDir = contentRoot.findFileByRelativePath(outputSourceContentRootPath);
        if (outputDir == null) {
            return GenerateFileResult.FAIL_DIR_NOT_FOUND;
        }

        // 已存在的产物直接跳过（不再降级写到模块根目录）
        if (outputDir.findChild(outputFileName) != null) {
            return GenerateFileResult.SKIP_FILE_EXISTS;
        }

        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                VirtualFile virtualFile = outputDir.createChildData(this, outputFileName);

                // Write data to the file
                try (OutputStream outputStream = virtualFile.getOutputStream(this)) {
                    try {
                        template.process(dataModel, new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
                    } catch (TemplateException | IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        // 标记文件为脏，触发 VCS 状态刷新
                        VcsDirtyScopeManager.getInstance(project).fileDirty(virtualFile);

                        // 刷新 VFS（如果你修改了文件内容或刚生成新文件）
                        virtualFile.refresh(true, true);

                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                ApplicationManager.getApplication().invokeLater(() ->
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(VfsUtilCore.virtualToIoFile(virtualFile)));

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        return GenerateFileResult.SUCCESS;
    }

    // P1-1：生成结束后一次汇总通知（成功/已存在跳过/目录不存在跳过），替代原先的逐文件弹窗与成功通知
    private void notifyGenerateSummary(Map<String, GenerateFileResult> results) {
        if (results.isEmpty()) {
            return;
        }
        List<String> successFiles = new ArrayList<>();
        List<String> existsFiles = new ArrayList<>();
        List<String> noDirFiles = new ArrayList<>();
        results.forEach((fileName, result) -> {
            switch (result) {
                case SUCCESS:
                    successFiles.add(fileName);
                    break;
                case SKIP_FILE_EXISTS:
                    existsFiles.add(fileName);
                    break;
                case FAIL_DIR_NOT_FOUND:
                default:
                    noDirFiles.add(fileName);
                    break;
            }
        });
        StringBuilder message = new StringBuilder("<html>代码生成完成。");
        message.append("成功 ").append(successFiles.size()).append(" 个文件");
        if (!successFiles.isEmpty()) {
            message.append("：").append(String.join("、", successFiles));
        }
        if (!existsFiles.isEmpty()) {
            message.append("；已存在跳过 ").append(existsFiles.size()).append(" 个：").append(String.join("、", existsFiles));
        }
        if (!noDirFiles.isEmpty()) {
            message.append("；目录不存在跳过 ").append(noDirFiles.size()).append(" 个：").append(String.join("、", noDirFiles));
        }
        message.append("</html>");
        boolean hasSkip = !existsFiles.isEmpty() || !noDirFiles.isEmpty();
        NotificationGroupManager.getInstance()
                .getNotificationGroup("ZhiyinOneClickNavigation")
                .createNotification(message.toString(), hasSkip ? NotificationType.WARNING : NotificationType.INFORMATION)
                .setTitle("CodeGenerator")
                .notify(project);
    }

    private VirtualFile findModuleContentRoot(Module module) {
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
        return contentRoots.length > 0 ? contentRoots[0] : null;
    }

    // P1-4：paramsMap 缺省口径（包级静态，供单测锁定）——导出缺省 true、导入缺省 false，与 DataModelGenerator 复选框初始态一致
    static boolean resolveGenerateExport(Map<String, Object> paramsMap) {
        return !Boolean.FALSE.equals(paramsMap.get("generateExport"));
    }

    static boolean resolveGenerateImport(Map<String, Object> paramsMap) {
        return Boolean.TRUE.equals(paramsMap.get("generateImport"));
    }

    // P0-2：由 private 提为包级静态，供 TableParser/CodeGenerateService 快照单测调用（无实例状态，纯函数）
    static String formatSql(String sql) {
        // 1. 检查输入是否为空，如果为空则直接返回空字符串
        if (isEmptyOrSpaces(sql)) {
            return "";
        }

        String formattedSql = sql.trim();

        // 2. 将 SELECT, FROM, WHERE, JOIN 等关键字前面加上换行符
        // `(?i)`: 开启不区分大小写模式
        // `\\b` : 匹配单词边界，确保只匹配完整的关键字，例如不匹配 `SELECTIVE` 中的 `SELECT`
        // `\\s*` : 匹配关键字后可能存在的任意数量的空格
        String[] keywords = {"SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LEFT JOIN", "INNER JOIN", "RIGHT JOIN", "ON"};
        for (String keyword : keywords) {
            formattedSql = formattedSql.replaceAll("(?i)\\b" + keyword + "\\b\\s*", "\n" + keyword + " ");
        }

        // 3. 将 WHERE 条件中的 AND 和 OR 放在新的一行并进行缩进
        // `$1` 是一个反向引用，它会保留匹配到的 `AND` 或 `OR`
        formattedSql = formattedSql.replaceAll("(?i)\\b(AND|OR)\\b\\s*", "\n  $1 ");

        // 4. 将 SELECT 语句中的逗号和函数参数外的逗号进行换行和缩进
        // `,\\s*`: 匹配逗号及后面的所有空格
        // `(?![^()]*\\))`: 这是一个负向先行断言，它确保我们匹配的逗号后面不跟着非括号字符和右括号。
        //                  这样可以防止匹配到函数参数内部的逗号，例如 `COUNT(col1, col2)`
        formattedSql = formattedSql.replaceAll(",\\s*(?![^()]*\\))", ",\n  ");

        // 5. 清理多余的换行符和空格，确保格式整洁
        formattedSql = formattedSql.replaceAll("\\s*\\n\\s*", "\n").trim(); // 将多余的换行符和空格合并
        formattedSql = formattedSql.replaceAll("\\s{2,}", " "); // 将多个空格替换为单个空格
        formattedSql = formattedSql.replaceAll("\n ", "\n"); // 清除换行符后的多余空格

        // 6. 针对特定关键字添加缩进，以提高可读性
        formattedSql = formattedSql.replace("SELECT \n", "SELECT\n  ");
        formattedSql = formattedSql.replace("FROM \n", "FROM\n");
        formattedSql = formattedSql.replace("WHERE \n", "WHERE\n  ");

        // 7. 每行前添加两个tab即8个空格
        // `(?m)^`: 这是一个多行模式匹配，表示匹配每行开头的位置。
        formattedSql = formattedSql.replaceAll("(?m)^", "\t\t");
        return formattedSql;
    }
}
