package com.zhiyin.plugins.service;

import com.intellij.lang.properties.psi.Property;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
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
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.GlobalSearchScope;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.utils.MyPropertiesUtil;
import com.zhiyin.plugins.utils.ProjectTypeChecker;
import com.zhiyin.plugins.utils.StringUtil;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.intellij.openapi.util.text.StringUtil.isEmptyOrSpaces;

@Service(Service.Level.PROJECT)
public final class CodeGenerateService {
    private static final Logger LOG = Logger.getInstance(CodeGenerateService.class);

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
            notifyGenerateSummary(results, null);
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
            notifyGenerateSummary(results, null);
        }
    }

    /**
     * P1-7：i18nByComment 为后台预查好的「字段 comment → DataGrid i18n 命中」结果（见
     * MyPropertiesUtil#findModuleDataGridI18nPropertiesByValueBatch 与 DataModelGenerator#generateDataModel），
     * 本方法在 EDT 上被调用，不再直接做索引反查。
     * <p>P2-2：confirmedI18nByField 为确认对话框「追加并生成」读回的「字段 name → 拟生成 key + 三语言确认值」，
     * 这些字段按新 key 闭环布局 Title 的 i18nKey（模板 ${column.i18nKey!column.chs!column.name}）；
     * i18nAppendContext 为后台翻译阶段定位好的三语言 datagrid 文件与现有 key 集合，产物生成后据此追加写
     * properties，追加统计并入汇总通知。两参为 null（无缺失 / 直接生成）时行为与 P2-1 前完全一致。</p>
     */
    public void generateBaseQueryTypeFile(Module module, String folder, String modelName, String fileName, String sql, List<Map<String, Object>> fields, Map<String, Object> paramsMap, Map<String, List<Property>> i18nByComment,
                                          @Nullable Map<String, I18nGenerateService.I18nConfirmedAppend> confirmedI18nByField,
                                          @Nullable I18nGenerateService.I18nDatagridAppendContext i18nAppendContext) {
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
        // P2-2：确认追加的字段按拟生成 key 闭环（chs/cht/eng 用对话框编辑后的值，空值不写保持模板回退）；
        // 命中/缺失计数与缺失清单收集上移到 collectI18nMissingSummary（确认弹窗已前置到生成之前）
        for (Map<String, Object> field : columns) {
            field.put("chs", field.get("comment"));
            String fieldName = String.valueOf(field.get("name"));
            I18nGenerateService.I18nConfirmedAppend confirmed = confirmedI18nByField == null
                    ? null : confirmedI18nByField.get(fieldName);
            if (confirmed != null) {
                field.put("i18nKey", confirmed.key());
                // P2-2 修复：dsp 约定字段顺带闭环 dspKey（模板 dsp 显示列 Title 的
                // ${column.dspKey!column.i18nKey!column.name} 兜底链首选项），与 properties
                // 追加的 <field>dsp key 对应；命中既有 key 的字段 dsp 列复用 i18nKey（现状）
                if (I18nGenerateService.isDspField(fieldName)) {
                    field.put("dspKey", confirmed.key() + "dsp");
                }
                if (!isEmptyOrSpaces(confirmed.zhCn())) {
                    field.put("chs", confirmed.zhCn());
                }
                if (!isEmptyOrSpaces(confirmed.zhTw())) {
                    field.put("cht", confirmed.zhTw());
                }
                if (!isEmptyOrSpaces(confirmed.enUs())) {
                    field.put("eng", confirmed.enUs());
                }
                continue;
            }
            Object commentObj = field.get("comment");
            String comment = commentObj == null ? null : commentObj.toString();
            List<Property> properties = comment == null
                    ? Collections.emptyList()
                    : precomputedI18n.getOrDefault(comment, Collections.emptyList());
            if (!properties.isEmpty()) {
                field.put("i18nKey", properties.get(0).getKey());
                field.put("chs", properties.get(0).getValue());
                if (properties.size() > 2) {
                    field.put("cht", properties.get(1).getValue());
                    field.put("eng", properties.get(2).getValue());
                }
            }
        }
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
        // P1-6：导出框架解析——easyexcel2 项目用 EasyExcel2Utils 9 参新写法，其余保持旧 EasyExcelUtils 写法
        dmLayout.put("exportFramework", resolveExportFramework(module, paramsMap));
        // P2-3：菜单注册 SQL 草稿可选产物——dmLayout 附加菜单字段（插件只生成草稿，绝不执行任何 SQL、不连库）；
        // 菜单名翻译由 DataModelGenerator 后台任务完成后经 paramsMap 传入，缺失（null/空）时模板渲染 TODO 形态兜底
        boolean menuSqlEnabled = Boolean.TRUE.equals(paramsMap.get("menuSqlCheckBox"));
        if (menuSqlEnabled) {
            Object menuNameZh = paramsMap.get("menuNameZh");
            Object menuNameTw = paramsMap.get("menuNameTw");
            Object menuNameEn = paramsMap.get("menuNameEn");
            dmLayout.put("menuNameZh", menuNameZh == null ? "" : menuNameZh.toString());
            dmLayout.put("menuNameTw", menuNameTw == null ? "" : menuNameTw.toString());
            dmLayout.put("menuNameEn", menuNameEn == null ? "" : menuNameEn.toString());
            dmLayout.put("menuFolder", folder);
            dmLayout.put("menuSnakeKey", toSnakeCase(modelName));
            dmLayout.put("menuButtons", buildMenuButtons(resolveGenerateImport(paramsMap)));
        }

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
            // P2-3：菜单注册 SQL 草稿——输出 src/main/resources/sql/<ObjectName>_menu_draft.sql；
            // 该目录通常不存在，先 VFS 建目录再走通用生成（不改 generateXmlFile 通用逻辑，其他产物路径语义不变），
            // 结果照常进 results 参与汇总通知
            if (menuSqlEnabled) {
                VirtualFile contentRoot = findModuleContentRoot(module);
                if (contentRoot != null) {
                    // P2-3 修复：createDirectoryIfMissing 的 VFS 建目录属写操作，EDT 上必须包
                    // write-action（2024.3 实测裸调抛 Write access is allowed inside write-action only，
                    // 被本方法 catch 吞成「无法生成件」弹窗）；照 MyProjectService#createDictionariesDir 先例
                    WriteCommandAction.writeCommandAction(project).run(() ->
                            VfsUtil.createDirectoryIfMissing(contentRoot, "src/main/resources/sql"));
                }
                results.put(outputFileName + "_menu_draft.sql",
                        generateXmlFile(project, module, dmLayout, "menu.sql.ftl", "src/main/resources/sql", outputFileName + "_menu_draft.sql"));
            }
        } catch (Exception ex) {
            Messages.showErrorDialog("无法生成件，异常： " + ex.getMessage(), "操作失败");
        } finally {
            // P2-2：确认追加路径——产物生成后写模块三语言 datagrid properties（幂等只追加），
            // 追加统计拼接进汇总通知；直接生成路径 summary 为 null，通知形态与之前完全一致
            String i18nAppendSummary = null;
            if (confirmedI18nByField != null && !confirmedI18nByField.isEmpty()) {
                I18nGenerateService.I18nAppendResult appendResult = project.getService(I18nGenerateService.class)
                        .appendConfirmedEntries(module, i18nAppendContext, confirmedI18nByField.values());
                i18nAppendSummary = appendResult.toSummarySuffix();
            }
            notifyGenerateSummary(results, i18nAppendSummary);
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

    // P1-1：生成结束后一次汇总通知（成功/已存在跳过/目录不存在跳过），替代原先的逐文件弹窗与成功通知；
    // P2-2：i18nAppendSummary 非 null 时拼接在消息末尾（如「；i18n 追加：zh_CN +N、zh_TW +N、en_US +N、跳过已存在 M」）
    private void notifyGenerateSummary(Map<String, GenerateFileResult> results, @Nullable String i18nAppendSummary) {
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
        if (i18nAppendSummary != null) {
            message.append(i18nAppendSummary);
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

    // P2-1：i18n 缺失报告数据行（字段名 → 拟生成 key → 拟中文值），P2-2 写入闭环复用
    public record I18nMissingEntry(String fieldName, String proposedKey, String chs) {
    }

    // P2-2：i18n 命中/缺失汇总（计数 + 缺失清单），供 DataModelGenerator 在 EDT 上基于预查结果
    // 判定是否走「翻译 → 确认对话框」分流，以及确认对话框的计数展示
    public record I18nMissingSummary(int hitCount, int missCount, List<I18nMissingEntry> missingEntries) {
    }

    // P2-2：缺失清单与命中/缺失计数收集（纯函数，EDT 上基于后台预查结果调用，无索引/PSI 查询；
    // 判定口径与 P2-1 generateBaseQueryTypeFile 内联收集完全一致——弹窗前置后由本方法统一供给）
    public static I18nMissingSummary collectI18nMissingSummary(String moduleName, String gridName,
                                                               List<Map<String, Object>> fields,
                                                               Map<String, List<Property>> precomputedI18n) {
        Map<String, List<Property>> i18nByComment = precomputedI18n == null ? Collections.emptyMap() : precomputedI18n;
        int hitCount = 0;
        int missCount = 0;
        List<I18nMissingEntry> missingEntries = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            Object commentObj = field.get("comment");
            String comment = commentObj == null ? null : commentObj.toString();
            List<Property> properties = comment == null
                    ? Collections.emptyList()
                    : i18nByComment.getOrDefault(comment, Collections.emptyList());
            if (!properties.isEmpty()) {
                hitCount++;
            } else {
                String fieldName = String.valueOf(field.get("name"));
                // P2-2 修复：无 comment 的 state/status/type 是模板 dsp 列约定字段（endsWith 匹配必生成
                // dsp 显示列），以默认标题「状态」/「类型」进清单，否则 dsp 列 Title 裸字段名且漏追加
                // dsp key（HaichengMes 验收实证：status 无 comment → Title value="status"）
                String effectiveComment = isI18nMissingReportable(comment, false)
                        ? comment : I18nGenerateService.defaultDspFieldTitle(fieldName);
                if (effectiveComment != null) {
                    missCount++;
                    missingEntries.add(new I18nMissingEntry(fieldName,
                            buildProposedI18nKey(moduleName, gridName, fieldName), effectiveComment));
                }
            }
        }
        return new I18nMissingSummary(hitCount, missCount, missingEntries);
    }

    // P2-1：拟生成 key 拼装（包级静态，供单测锁定，规则 GATE-B 已确认）：
    // <模块 i18n 前缀>.<gridName 小写>grid.<字段名小写>——key 的 grid 段 = 模板
    // BaseQueryTypeLayout.ftl 生成的 ${grid.dataGridName}Grid 全小写（DengqiMes Order.xml 实证）
    static String buildProposedI18nKey(String moduleName, String gridName, String fieldName) {
        return MyPropertiesUtil.deriveI18nKeyPrefix(moduleName) + "." + gridName.toLowerCase() + "grid." + fieldName.toLowerCase();
    }

    // P2-1：缺失判定（包级静态，供单测锁定）：comment 非空白且预查 i18n 未命中 → 计入缺失报告
    static boolean isI18nMissingReportable(String comment, boolean i18nMatched) {
        return !i18nMatched && comment != null && !comment.trim().isEmpty();
    }

    // P1-6：导出框架解析——paramsMap 值 ∈ {auto, easyexcel, easyexcel2}；auto/缺失时探测模块 classpath
    // 是否有 com.zhiyin.service.excel.EasyExcel2Utils，探测到用新写法、否则回退旧写法（绝不中断生成）
    static String resolveExportFramework(Module module, Map<String, Object> paramsMap) {
        Object configured = paramsMap == null ? null : paramsMap.get("exportFramework");
        if ("easyexcel".equals(configured) || "easyexcel2".equals(configured)) {
            return (String) configured;
        }
        return combineExportFramework(configured, detectEasyExcel2(module));
    }

    // 配置值与探测结果的组合口径（包级静态，供单测锁定 auto/缺失→探测分支语义；未知值按 auto 对待）
    static String combineExportFramework(Object configured, boolean easyExcel2Detected) {
        if ("easyexcel".equals(configured) || "easyexcel2".equals(configured)) {
            return (String) configured;
        }
        return easyExcel2Detected ? "easyexcel2" : "easyexcel";
    }

    // P2-3：ObjectName 驼峰转蛇形小写（BaseFactory→base_factory、PartWarehouse→part_warehouse、
    // OrderSchedule→order_schedule），用于 sys_res_i18n 的 KEY 段 com.zhiyin.mes.menu.<snake>；
    // 连续大写按「后续紧跟小写才断词」收敛（ABCTest→abc_test），包级静态供单测锁定
    static String toSnakeCase(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (Character.isUpperCase(c)) {
                // 大写字母前补下划线：前一个字符是小写（aB 断词）或下一个字符是小写（ABc 断词），首字母除外
                boolean prevLowerOrDigit = i > 0 && !Character.isUpperCase(chars[i - 1]);
                boolean nextLower = i + 1 < chars.length && !Character.isUpperCase(chars[i + 1]);
                if (i > 0 && (prevLowerOrDigit || nextLower)) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // P2-3：菜单按钮集合与勾选项联动（DengqiMes 实证口径）——默认仅刷新+导出；
    // generateImport=true 时插入导出(模板)+导入，SEQ 顺序：刷新0、导出(模板)1、导入2、导出3
    // （不勾导入则刷新0、导出1）；公共按钮 i18n id 复用全局既有值（75/80/84/454），不生成按钮 i18n 行
    static List<Map<String, Object>> buildMenuButtons(boolean generateImport) {
        List<Map<String, Object>> buttons = new ArrayList<>();
        buttons.add(menuButton("刷新", "icon-reload", "Refresh", 0, 75));
        if (generateImport) {
            buttons.add(menuButton("导出(模板)", "icon-export", "downloadTemplate", 1, 84));
            buttons.add(menuButton("导入", "icon-import", "Import", 2, 454));
            buttons.add(menuButton("导出", "icon-export", "Export", 3, 80));
        } else {
            buttons.add(menuButton("导出", "icon-export", "Export", 1, 80));
        }
        return buttons;
    }

    private static Map<String, Object> menuButton(String fname, String icon, String code, int seq, int i18nid) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("fname", fname);
        button.put("icon", icon);
        button.put("code", code);
        button.put("seq", seq);
        button.put("i18nid", i18nid);
        return button;
    }

    // EasyExcel2Utils 探测属用户触发生成动作内的一次性索引查询（非 daemon 热路径）；
    // 线程纪律照 DatabaseConnectionFinder：EDT 用 runReadAction（不等待 smart mode），后台线程用
    // runReadActionInSmartMode；EDT 且 dumb mode 时直接回退旧写法，不在 EDT 阻塞等待索引
    private static boolean detectEasyExcel2(Module module) {
        if (module == null) {
            return false;
        }
        Project project = module.getProject();
        if (ApplicationManager.getApplication().isDispatchThread() && DumbService.isDumb(project)) {
            LOG.warn("EasyExcel2Utils 探测处于 EDT + dumb mode，回退旧导出写法");
            return false;
        }
        GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module);
        AtomicBoolean found = new AtomicBoolean(false);
        Runnable lookup = () -> found.set(JavaPsiFacade.getInstance(project)
                .findClass("com.zhiyin.service.excel.EasyExcel2Utils", scope) != null);
        try {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                ApplicationManager.getApplication().runReadAction(lookup);
            } else {
                DumbService.getInstance(project).runReadActionInSmartMode(lookup);
            }
        } catch (Exception e) {
            LOG.warn("EasyExcel2Utils 探测失败，回退旧导出写法", e);
            return false;
        }
        return found.get();
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
