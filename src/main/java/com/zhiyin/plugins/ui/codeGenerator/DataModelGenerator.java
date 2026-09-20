package com.zhiyin.plugins.ui.codeGenerator;

//import com.intellij.ui.table.JBTable;

import com.intellij.lang.properties.psi.Property;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ExceptionUtil;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.resources.MyIcons;
import com.zhiyin.plugins.service.CodeGenerateService;
import com.zhiyin.plugins.service.I18nGenerateService;
import com.zhiyin.plugins.translator.baidu.BaiduTranslator;
import com.zhiyin.plugins.utils.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intellij.openapi.util.text.StringUtil.isEmptyOrSpaces;

//@Service(Service.Level.PROJECT)
public class DataModelGenerator {
    private static final Logger LOG = Logger.getInstance(DataModelGenerator.class);

    /** 表名安全字符集：SHOW CREATE TABLE 为字符串拼接，表名只允许字母/数字/下划线/点号（P1-2） */
    static final Pattern DB_TABLE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.]+$");

    /** 底部两行子面板的行内水平间隙（FlowLayout hgap），最小窗宽余量同源取值，不写死魔法数 */
    private static final int BOTTOM_ROW_HGAP = 10;

    /**
     * P3-1：字段表布尔列集合——表格模型 getColumnClass 归派 Boolean、updateTableModel 值归一、
     * 列头右键批操作判定三处同源（比 startsWith("is") 精确，防未来新增非布尔 is 前缀列误入）
     */
    static final Set<String> BOOLEAN_COLUMNS = Set.of("isColumnField", "isQueryField", "isDialogField", "isRequired", "isEditHidden");

    /** P3-1：easyuiClass 下拉「空」选项的显示文案（选项实际值为空串，渲染层换显） */
    static final String EASYUI_EMPTY_OPTION_TEXT = "（空）";

    /**
     * P3-1：easyuiClass 下拉词表（GATE-F）——枚举为 DengqiMes 既有 Layout.xml 属性
     * easyuiClass="easyui-xxx" 出现过的集合（按出现频次降序，注释为 grep 计数；combotree
     * 零出现不收）；首项空串在下拉显示「（空）」——启发式对 int+id 等返回 ""，空值合法且常用
     */
    static final String[] EASYUI_CLASS_OPTIONS = {
            "",
            "easyui-combobox",    // 1940
            "easyui-datebox",     // 824
            "easyui-textbox",     // 596
            "easyui-datetimebox", // 152
            "easyui-numberbox",   // 115
            "easyui-timespinner", // 10
            "easyui-validatebox", // 7
            "easyui-filebox",     // 2
            "easyui-checkbox",    // 1
    };

    private final JFrame frame;
    private final JTable table;
    private final DefaultTableModel tableModel;

    private final List<Map<String, Object>> fields = new ArrayList<>();
    private final Project project;
    private final Module module;
    private final String[] COLUMNS_NAME;
    private final ButtonGroup pageTypeButtonGroup;

    private String tableName;
    private String sql;
    private JCheckBox mocCheckBox;
    private JCheckBox layoutCheckBox;
    private JCheckBox htmlCheckBox;
    private JCheckBox controllerCheckBox;
    private JCheckBox serviceCheckBox;
    private JCheckBox daoCheckBox;
    private JCheckBox myBatisMapperCheckBox;
    private JCheckBox excelImportCheckBox;
    private JCheckBox excelExportCheckBox;
    // P1-6：导出框架下拉（自动 / EasyExcel 旧 / EasyExcel2 新），默认自动
    private JComboBox<String> exportFrameworkComboBox;
    // P2-3：菜单注册 SQL 草稿可选产物（纯文本草稿，插件不执行任何 SQL、不连库）
    private JCheckBox menuSqlCheckBox;
    private JRadioButton dataMaintenanceRadioButton;
    private JRadioButton dataQueryRadioButton;

    public DataModelGenerator(Project project, Module module) {
        this.project = project;
        this.module = module;
        frame = new JFrame("Module: " + module.getName() + " - " + project.getName() + " - DataModelGenerator");
        frame.setIconImage(MyIcons.appIcon.getImage());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1200, 600);
        frame.setLayout(new BorderLayout());

        JButton queryButton = new JButton("从数据库读取表字段");
        queryButton.addActionListener(e -> fetchFieldsFromDatabase());
//        queryButton.setEnabled(false);

        JButton parseCreateSQLButton = new JButton("从建表DDL解析字段");
        parseCreateSQLButton.addActionListener(e -> fetchFieldsFromTableSQL());
//        parseCreateSQLButton.setEnabled(false);

        // TODO
        JButton parseSelectSQLButton = new JButton("从查询SQL解析字段");
        parseSelectSQLButton.addActionListener(e -> fetchFieldsFromQuerySQL());

        // TODO
        COLUMNS_NAME = new String[]{"name", "type", "length", "comment", "isColumnField", "isQueryField", "isDialogField", "isRequired", "isEditHidden", "easyuiClass"};
        tableModel = new DefaultTableModel(COLUMNS_NAME, 0){
            // P3-1：is* 布尔列声明 Boolean 列类——渲染器/编辑器按列类派发（平台默认 Boolean 复选框
            // 渲染 + DefaultCellEditor(JCheckBox)）；其余列保持 Object，继续走 CustomTableCellRenderer
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return BOOLEAN_COLUMNS.contains(getColumnName(columnIndex)) ? Boolean.class : Object.class;
            }

            @Override
            public void setValueAt(Object aValue, int row, int column) {
                super.setValueAt(aValue, row, column);
                // Sync the data to the original List<Map>
                String columnName = getColumnName(column);
                fields.get(row).put(columnName, aValue);
            }

            @Override
            public String getColumnName(int column) {
                return super.getColumnName(column);
            }
        };
        table = new JBTable(tableModel);
        // 设置自定义的渲染器（Boolean 列由 JTable 平台默认复选框渲染器按列类接管，不吃斑马纹——计划已认可）
        table.setDefaultRenderer(Object.class, new CustomTableCellRenderer());
        table.setRowSelectionAllowed(false);
        table.setCellSelectionEnabled(true);

        // P3-1：布尔列进编辑态（单击进编辑、复选框点击/空格切换），取代原单击直接翻转的 MouseAdapter
        // ——旧监听对任意单击（含选格/误触）直接 setValueAt 翻转、不经过编辑器生命周期，是误触翻转的来源
        DefaultCellEditor booleanCellEditor = new DefaultCellEditor(new JCheckBox());
        ((JCheckBox) booleanCellEditor.getComponent()).setHorizontalAlignment(JCheckBox.CENTER);
        booleanCellEditor.setClickCountToStart(1); // 单击进编辑态（JCheckBox 版 DefaultCellEditor 本就默认 1，显式声明意图）
        table.setDefaultEditor(Boolean.class, booleanCellEditor);

        // P3-1：easyuiClass 列单独挂下拉编辑器（GATE-F 词表 + 空选项，只能选不能乱填）；不用
        // setDefaultEditor(String.class, ...)——会波及 name/type/length/comment 的默认文本编辑。
        // 文本列随之回归 Swing 默认编辑时机：双击 / F2（原 MouseAdapter 的单击 editCellAt 分支已删）
        table.getColumn("easyuiClass").setCellEditor(new DefaultCellEditor(createEasyuiClassComboBox()));

        // P3-1：布尔列列头右键批量操作（本列全选/反选/清空）
        installBooleanColumnHeaderMenu();

        JButton addButton = new JButton("添加字段");
        addButton.addActionListener(e -> showEditFieldDialog(null, -1));

        JButton editButton = new JButton("编辑字段");
        editButton.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                Map<String, Object> field = fields.get(selectedRow);
                showEditFieldDialog(field, selectedRow);
            }
        });

        JButton deleteButton = new JButton("删除字段");
        deleteButton.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                tableModel.removeRow(selectedRow);
                fields.remove(selectedRow);
            }
            updateTableModel();
        });


        JPanel buttonPanel = new JPanel();
        buttonPanel.add(queryButton);
        buttonPanel.add(parseCreateSQLButton);
        buttonPanel.add(parseSelectSQLButton);
        buttonPanel.add(addButton);
        buttonPanel.add(editButton);
        buttonPanel.add(deleteButton);
//        buttonPanel.add(generateButton);

        JPanel generateTypeSelectPanel = new JPanel();
        // SOUTH 区固定结构：上行「页面功能类型 + 一键生成」band，下行文件类型固定两行——
        // 面板首选高度恒等于两行行高之和、与窗宽无关，不再依赖动态换行的宽度敏感高度计算
        generateTypeSelectPanel.setLayout(new BorderLayout(10, 5));
        // 添加多选框，可选择 Moc、Layout、Service、Controller、Service、Dao、MyBatisMapper

        // 创建一组单选框，表示“页面功能类型”，两个的单选框可选择 数据维护、记录查询
        dataMaintenanceRadioButton = new JRadioButton("数据维护");
        dataQueryRadioButton = new JRadioButton("记录查询");
        pageTypeButtonGroup = new ButtonGroup();
        pageTypeButtonGroup.add(dataMaintenanceRadioButton);
        pageTypeButtonGroup.add(dataQueryRadioButton);
        dataMaintenanceRadioButton.setEnabled(false);
        dataMaintenanceRadioButton.setSelected(false);
        dataQueryRadioButton.setSelected(true);
        JPanel pageTypePanel = new JPanel();
        pageTypePanel.setLayout(new BoxLayout(pageTypePanel, BoxLayout.Y_AXIS));
        pageTypePanel.add(dataMaintenanceRadioButton);
        pageTypePanel.add(dataQueryRadioButton);

        JBLabel label = new JBLabel("页面功能类型：");
        JPanel pageTypeRowPanel = new JPanel();
        // 行内不换行，拖窄防折行由 frame 最小窗宽兜底（见构造器 setMinimumSize）
        pageTypeRowPanel.setLayout(new FlowLayout(FlowLayout.LEFT, BOTTOM_ROW_HGAP, 5));
        pageTypeRowPanel.add(label);
        pageTypeRowPanel.add(pageTypePanel);

        JPanel checkBoxPanel = createCheckBoxPanel();
        JButton generateButton = new JButton("一键生成");
        generateButton.addActionListener(e -> generateDataModel());
        JPanel pageTypeBandPanel = new JPanel();
        pageTypeBandPanel.setLayout(new BorderLayout(10, 0));
        pageTypeBandPanel.add(pageTypeRowPanel, BorderLayout.WEST);
        pageTypeBandPanel.add(generateButton, BorderLayout.EAST);
        generateTypeSelectPanel.add(pageTypeBandPanel, BorderLayout.NORTH);
        generateTypeSelectPanel.add(checkBoxPanel, BorderLayout.CENTER);

        frame.add(buttonPanel, BorderLayout.NORTH);
        frame.add(new JScrollPane(table), BorderLayout.CENTER);
        frame.add(generateTypeSelectPanel, BorderLayout.SOUTH);
        // 底部两行行内不折行（FlowLayout 行内折行会重现高度欠分配裁行），以最小窗宽兜底：
        // 先 pack 让窗口取得真实装饰 insets 与最终字体度量，量取全窗首选宽
        // （BorderLayout 取 NORTH/CENTER/SOUTH 最大行宽，天然覆盖底部两行、页面类型 band、顶部按钮栏），
        // 加两倍行间隙余量防贴边折行后作为最小窗宽；随后恢复默认窗宽 1200x600，高度下限维持既有 600
        frame.pack();
        int minimumWidth = frame.getPreferredSize().width + BOTTOM_ROW_HGAP * 2;
        frame.setSize(1200, 600);
        frame.setMinimumSize(new Dimension(minimumWidth, 600));
    }

    private @NotNull JPanel createCheckBoxPanel() {
        // 固定两行、行内不换行：行 1 文件类型复选框，行 2 Excel 导入/导出 + 导出框架下拉。
        // 面板首选高度恒等于两行行高之和（与窗宽无关），SOUTH 区不会再因动态换行高度失准而裁行；
        // 拖窄后行内折行由 frame 最小窗宽兜底（见构造器 setMinimumSize）
        JPanel fileTypeRow = new JPanel();
        fileTypeRow.setLayout(new FlowLayout(FlowLayout.LEFT, BOTTOM_ROW_HGAP, 5));
        JLabel checkBoxLabel = new JLabel("文件类型：");
        fileTypeRow.add(checkBoxLabel);
        mocCheckBox = new JCheckBox("Moc", true);
        layoutCheckBox = new JCheckBox("Layout", true);
        htmlCheckBox = new JCheckBox("Html", true);
        controllerCheckBox = new JCheckBox("Controller", true);
        serviceCheckBox = new JCheckBox("Service", true);
        daoCheckBox = new JCheckBox("Dao", true);
        myBatisMapperCheckBox = new JCheckBox("MyBatisMapper", true);
        // P1-4：Excel 导入/导出链路可选——导出默认勾（产物与既有行为一致），导入默认不勾；
        // P2-6：勾导入时追加生成 Imp mapper 骨架 + 导入定义 SQL 草稿（均为纯文本草稿，插件不执行任何 SQL）
        excelImportCheckBox = new JCheckBox("Excel 导入（含 Imp mapper 骨架）", false);
        excelExportCheckBox = new JCheckBox("Excel 导出", true);
        // P1-6：导出框架选择——自动=按模块 classpath 探测 EasyExcel2Utils 定新旧写法，可手工覆盖
        exportFrameworkComboBox = new JComboBox<>(new String[]{"自动", "EasyExcel 旧", "EasyExcel2 新"});

        fileTypeRow.add(mocCheckBox);
        fileTypeRow.add(layoutCheckBox);
        fileTypeRow.add(htmlCheckBox);
        fileTypeRow.add(controllerCheckBox);
        fileTypeRow.add(serviceCheckBox);
        fileTypeRow.add(daoCheckBox);
        fileTypeRow.add(myBatisMapperCheckBox);

        JPanel excelRow = new JPanel();
        excelRow.setLayout(new FlowLayout(FlowLayout.LEFT, BOTTOM_ROW_HGAP, 5));
        excelRow.add(excelImportCheckBox);
        excelRow.add(excelExportCheckBox);
        // 「导出框架：」label 与下拉打成零间隙原子块，视觉成组
        JPanel exportFrameworkPanel = new JPanel();
        exportFrameworkPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
        exportFrameworkPanel.add(new JLabel("导出框架："));
        exportFrameworkPanel.add(exportFrameworkComboBox);
        excelRow.add(exportFrameworkPanel);
        // P2-3：菜单注册 SQL 草稿可选产物，默认不勾（勾选后生成 src/main/resources/sql/<ObjectName>_menu_draft.sql）
        menuSqlCheckBox = new JCheckBox("菜单 SQL（草稿）", false);
        excelRow.add(menuSqlCheckBox);

        JPanel checkBoxPanel = new JPanel();
        checkBoxPanel.setLayout(new BoxLayout(checkBoxPanel, BoxLayout.Y_AXIS));
        checkBoxPanel.add(fileTypeRow);
        checkBoxPanel.add(excelRow);
        return checkBoxPanel;
    }

    private void fetchFieldsFromDatabase() {
        // P1-2：选连接 + 输表名留在 EDT；JDBC 查询（纯网络调用，不碰 PSI/VFS）放后台任务，
        // 失败经 onError 弹窗可见，不再被 println 吞掉、不再冻结 EDT
        // P1-7：连接发现（DatabaseConnectionFinder → FileTypeIndex/PSI）同为索引查询，一并移出 EDT——
        // 后台预取连接清单（collectPropertiesFiles 内部走 runReadActionInSmartMode 分支），EDT 只做弹窗交互
        new Task.Backgroundable(project, "查找数据库连接", false) {
            private List<Map<String, String>> connections = Collections.emptyList();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                DatabaseConnectionFinder connectionFinder = project.getService(DatabaseConnectionFinder.class);
                if (connectionFinder != null) {
                    connections = connectionFinder.findDatabaseConnections(project);
                }
            }

            @Override
            public void onSuccess() {
                // Task.Backgroundable#onSuccess 在 EDT 执行
                JFrame frame = new JFrame(); // Dummy frame to center dialog
                SelectDatabaseConnectionDialog dialog = new SelectDatabaseConnectionDialog(frame, connections);
                dialog.setVisible(true);

            // Get the selected connection info after the dialog is closed
            Map<String, String> selectedConnectionInfo = dialog.getSelectedConnectionInfo();

            if (selectedConnectionInfo == null) {
                return;
            }

            String inputTableName = Messages.showInputDialog("请输入表名", "操作提示", Messages.getQuestionIcon());
            String validationError = validateDbTableName(inputTableName);
            if (validationError != null) {
                // 输入无效（含取消）：不动 this.tableName / this.sql / fields，避免「from null a」式状态污染
                MyPluginMessages.showWarning("操作失败", validationError, project);
                return;
            }
            String tableName = inputTableName.trim();
            DataModelGenerator.this.tableName = tableName;

            String jdbcUrl = selectedConnectionInfo.get("url");
            String username = selectedConnectionInfo.get("username");
            String password = selectedConnectionInfo.get("password");

            new Task.Backgroundable(project, "从数据库读取表字段: " + tableName, false) {
                private List<Map<String, Object>> tableMetadata;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        tableMetadata = DatabaseMetadataUtil.getTableMetadata(jdbcUrl, username, password, tableName);
                    } catch (Exception ex) {
                        LOG.warn("从数据库读取表字段失败: " + tableName + ", url=" + jdbcUrl, ex);
                        throw new RuntimeException(ex);
                    }
                }

                @Override
                public void onSuccess() {
                    // Task.Backgroundable#onSuccess 在 EDT 执行
                    fields.clear();
                    fields.addAll(tableMetadata);

                    StringBuilder sqlBuilder = new StringBuilder("select ");
                    for (int i = 0; i < fields.size(); i++) {
                        sqlBuilder.append("a.")
                                  .append(fields.get(i).get("name"))
                                  .append(",");
                    }
                    sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
                    sqlBuilder.append(" \nfrom ")
                              .append(tableName)
                              .append(" a");
                    sql = sqlBuilder.toString();

                    updateTableModel();
                }

                @Override
                public void onError(@NotNull Exception error) {
                    // onError 在 EDT 执行；错误原因脱敏（不含用户名/密码，host 可保留）
                    Throwable root = ExceptionUtil.getRootCause(error);
                    MyPluginMessages.showError("数据库读取失败",
                            "连接 " + describeDbHost(jdbcUrl) + " 读取表 " + tableName + " 失败："
                                    + sanitizeDbErrorMessage(root.getMessage()),
                            project);
                }
            }.queue();
            }
        }.queue();

//        updateTableModel();
    }

    /**
     * 表名输入校验（P1-2）：null/空提示中止；SHOW CREATE TABLE 是字符串拼接，表名必须匹配安全字符集。
     *
     * @return null 表示合法；否则返回可直接展示给用户的错误原因
     */
    static String validateDbTableName(String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            return "表名不能为空";
        }
        if (!DB_TABLE_NAME_PATTERN.matcher(tableName.trim()).matches()) {
            return "表名只能包含字母、数字、下划线和点号（输入: " + tableName.trim() + "）";
        }
        return null;
    }

    /** 错误信息脱敏：屏蔽 MySQL 报错里的 user 'xxx'@ 部分（不含用户名/密码） */
    static String sanitizeDbErrorMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "未知错误（详见 idea.log）";
        }
        return message.replaceAll("(?i)user '[^']*'@", "user '***'@");
    }

    /** 从 JDBC URL 提取 host:port 供错误提示展示；剔除可能内嵌的 user:pass@ 凭据 */
    static String describeDbHost(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            return "未知数据源";
        }
        Matcher matcher = Pattern.compile("//([^/?]+)").matcher(jdbcUrl);
        if (!matcher.find()) {
            return "未知数据源";
        }
        String authority = matcher.group(1);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = "***@" + authority.substring(at + 1);
        }
        return authority;
    }

    private void fetchFieldsFromTableSQL() {
        ApplicationManager.getApplication().invokeLater(() -> {
            // IDEA 插件开发 获取弹窗输入的SQL语句
            String input = Messages.showInputDialog(
                    project,
                    "Enter your create table DDL sql:",
                    "从DDL提取字段信息",
                    Messages.getQuestionIcon()
            );
            // 从SQL语句中提取表信息
            if (input != null && !input.isEmpty()) {
                this.tableName = TableParser.extractTableName(input);
                List<Map<String, Object>> fieldsFromSQL = TableParser.parseCreateTable(input);
                fields.clear();
                fields.addAll(fieldsFromSQL);

                StringBuilder sqlBuilder = new StringBuilder("select ");
                for (int i = 0; i < fields.size(); i++) {
                    sqlBuilder.append("a.")
                              .append(fields.get(i).get("name"))
                              .append(",");
                }
                sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
                sqlBuilder.append(" \nfrom ")
                          .append(tableName)
                          .append(" a");
                this.sql = sqlBuilder.toString();

                updateTableModel();
            } else {
                MyPluginMessages.showError("操作失败", "请输入有效的 SQL 语句（暂只支持解析MySQL DDL）。", project);
            }
        });
    }

    private void fetchFieldsFromQuerySQL() {
        ApplicationManager.getApplication().invokeLater(() -> {
            // IDEA 插件开发 获取弹窗输入的SQL语句
            String input = Messages.showInputDialog(
                    project,
                    "Enter your create table DQL sql:",
                    "从DQL提取字段信息",
                    Messages.getQuestionIcon(),
                    "select a.code, a.name from sys_user a",
                    new InputValidatorEx() {
                        @Override
                        public @NlsContexts.DetailedDescription @Nullable String getErrorText(@NonNls String inputString) {
                            if (isEmptyOrSpaces(inputString) || !inputString.toLowerCase().contains("select")) {
                                return "请输入有效的 SQL 语句";
                            }
                            return null;
                        }
                    }
            );
            // 从SQL语句中提取表信息
            if (input != null && !input.isEmpty()) {
                this.tableName = TableParser.extractTableNameFromDQL(input);
                this.sql = input;
                List<Map<String, Object>> fieldsFromSQL = TableParser.parseDQL(input);
                fields.clear();
                fields.addAll(fieldsFromSQL);
                updateTableModel();
            } else {
                MyPluginMessages.showError("操作失败", "请输入有效的 SQL 语句", project);
            }
        });
    }

    private void updateTableModel() {
        tableModel.setRowCount(0);
        for (Map<String, Object> field : fields) {
            if (field == null) {
                continue;
            }

            if (isEmptyOrSpaces((String) field.get("name"))) {
                continue;
            }

            List<String> addQueryFields = List.of("code", "name", "factoryid", "workshopid", "factoryname");
            field.computeIfAbsent("isQueryField", (k) -> {
                if (field.get("name") != null){
                    String name = field.get("name").toString();
                    if (addQueryFields.contains(name)) {
                        return true;
                    }
                    if (name.endsWith("code") || name.endsWith("name")) {
                        return true;
                    }
                }
                return false;
            });
            field.putIfAbsent("isColumnField", true);
            List<String> notDialogFields = List.of("maintainer", "maintaintime", "creator", "createtime", "updater", "updatetime");
            field.computeIfAbsent("isDialogField", (k) -> {
                if (field.get("name") != null){
                    String name = field.get("name").toString();
                    if (notDialogFields.contains(name)) {
                        return false;
                    }
                    if (name.endsWith("flag")) {
                        return false;
                    }
                }
                return true;
            });
            field.computeIfAbsent("isEditHidden", (k) -> {
                if (field.get("name") != null){
                    String name = field.get("name").toString();
                    if (name.endsWith("id") || name.endsWith("flag")) {
                        return true;
                    }
                }
                return false;
            });
            field.computeIfAbsent("easyuiClass", (k) -> {
                if (field.get("type") != null && field.get("name") != null) {
                    String type = field.get("type").toString();
                    String name = field.get("name").toString();
                    if (type.equals("int") && name.endsWith("id")) {
                        return "";
                    } else if (type.equals("string")) {
                        return "easyui-textbox";
                    } else if (type.equals("date")) {
                        return "easyui-datebox";
                    } else if (type.equals("datetime")) {
                        return "easyui-datetimebox";
                    } else if (type.equals("number") || type.equals("decimal") || type.equals("float") || type.equals("int")) {
                        // P2-5：decimal 不再折算 number、float 新入词表——直连分支需并列，否则 decimal 字段漏 numberbox
                        return "easyui-numberbox";
                    } else {
                        return "";
                    }
                }
                return "";
            });

            Object[] rowData = new Object[COLUMNS_NAME.length];
            // 使用 COLUMNS_NAME 数组来初始化 rowData
            for (int i = 0; i < COLUMNS_NAME.length; i++) {
                String columnName = COLUMNS_NAME[i];
                Object value = field.get(columnName);
                if (BOOLEAN_COLUMNS.contains(columnName)) {
                    // P3-1：is* 值归一 Boolean 并回写 field map——fields 里 is* 存在 String（TableParser
                    // DDL/DB 解析路径）与 Boolean（computeIfAbsent 启发式、编辑弹窗回写）两形态，布尔列
                    // 列类已声明 Boolean，混合形态下渲染/编辑异常；解析层（TableParser）不动，归一只在 UI 层
                    value = normalizeBoolean(value);
                    field.put(columnName, value);
                }
                rowData[i] = value;
            }
            tableModel.addRow(rowData);
        }
    }

    /**
     * P3-1：is* 布尔键归一（纯函数，供单测）——Boolean 原样；String 按忽略大小写 "true" 判定
     * （Boolean.parseBoolean 语义，"TRUE"/"True" 亦真、其余含空格/前后缀均 false）；null 与其它类型兜底 false
     */
    static Boolean normalizeBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return Boolean.FALSE;
    }

    /**
     * P3-1：easyuiClass 下拉编辑器的 JComboBox——非 editable（只能选不能乱填）；空串选项在
     * 下拉里显示「（空）」，编辑落值仍是 ""（与 updateTableModel 启发式对 int+id 等返回的空串一致）
     */
    private static JComboBox<String> createEasyuiClassComboBox() {
        JComboBox<String> comboBox = new JComboBox<>(EASYUI_CLASS_OPTIONS);
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(value == null || value.toString().isEmpty() ? EASYUI_EMPTY_OPTION_TEXT : value.toString());
                return component;
            }
        });
        return comboBox;
    }

    /**
     * P3-1：布尔列列头右键批量操作——「全选/反选/清空」对本列所有数据行生效（rowAtPoint 只命中
     * 右键一处，故按表格行遍历而非取点击行）。当前表格无 RowSorter（模型行=视图行），仍统一走
     * table.setValueAt(视图坐标)——由 JTable 内部 convertRowIndexToModel，将来引入排序也不踩坑。
     */
    private void installBooleanColumnHeaderMenu() {
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e) || e.getClickCount() != 1) {
                    return;
                }
                int viewColumn = table.getTableHeader().columnAtPoint(e.getPoint());
                if (viewColumn < 0) {
                    return;
                }
                // 列头可拖拽换序：columnAtPoint 是视图列，判布尔列须经模型列
                int modelColumn = table.convertColumnIndexToModel(viewColumn);
                if (!Boolean.class.equals(tableModel.getColumnClass(modelColumn))) {
                    return; // 非布尔列不出菜单
                }
                JPopupMenu menu = new JPopupMenu();
                JMenuItem selectAllItem = new JMenuItem("本列全选");
                JMenuItem invertItem = new JMenuItem("本列反选");
                JMenuItem clearItem = new JMenuItem("本列清空");
                selectAllItem.addActionListener(ev -> applyBooleanColumnBatch(viewColumn, Boolean.TRUE));
                invertItem.addActionListener(ev -> applyBooleanColumnBatch(viewColumn, null));
                clearItem.addActionListener(ev -> applyBooleanColumnBatch(viewColumn, Boolean.FALSE));
                menu.add(selectAllItem);
                menu.add(invertItem);
                menu.add(clearItem);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    /**
     * P3-1：布尔列批量写值——target 为 null 表示反选（对归一后的 Boolean 取反）；先取消进行中的
     * 单元格编辑，防编辑器后续 stopCellEditing 回写覆盖批操作值
     */
    private void applyBooleanColumnBatch(int viewColumn, Boolean target) {
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            Object value = target != null ? target : !normalizeBoolean(table.getValueAt(viewRow, viewColumn));
            table.setValueAt(value, viewRow, viewColumn);
        }
    }

    private void showEditFieldDialog(Map<String, Object> field, int rowIndex) {
        Field fieldModel = Field.Companion.createDefault();
        if (field != null) {
            fieldModel.setName((String) field.get("name"));
            fieldModel.setType((String) field.get("type"));
            // P2-5：length 可能为 "P,S"（decimal 保留 scale）或空串（datetime/enum 等）——只取精度部分
            // 供对话框整数绑定（旧 parseInt 对两者均抛 NumberFormatException），scale 由回写侧带回
            fieldModel.setLength(parseDialogLength(field.get("length")));
            fieldModel.setComment((String) field.get("comment"));
            fieldModel.setRequired(field.get("isRequired") != null && "true".equals(field.get("isRequired").toString()));
            fieldModel.setQueryField(field.get("isQueryField") != null && "true".equals(field.get("isQueryField").toString()));
            fieldModel.setDialogField(field.get("isDialogField") != null && "true".equals(field.get("isDialogField").toString()));
        }

        KotlinFieldEditorDialog.Companion.showDialog(fieldModel, updatedField -> {
            Map<String, Object> updatedFieldMap = Map.of(
                    "name", updatedField.getName(),
                    "type", updatedField.getType(),
                    "length", resolveEditedLength(field != null ? field.get("length") : null, updatedField.getLength()),
                    "comment", updatedField.getComment(),
                    "isRequired", updatedField.isRequired(),
                    "isQueryField", updatedField.isQueryField(),
                    "isDialogField", updatedField.isDialogField()
            );
            updatedFieldMap = new HashMap<>(updatedFieldMap);
            // P2-5：enum= 属性透传——原 map 含 enumRef 键则原样带入，编辑往返不丢
            if (field != null && field.containsKey("enumRef")) {
                updatedFieldMap.put("enumRef", field.get("enumRef"));
            }
            if (rowIndex == -1) {
                fields.add(updatedFieldMap);
            } else {
                fields.set(rowIndex, updatedFieldMap);
            }
            updateTableModel();
            return null;
        });
    }

    /**
     * P2-5：编辑字段对话框打开时的长度解析（纯函数，供单测）——length 为 "P,S"（decimal 保留
     * scale 后的形态）时只取逗号前精度部分；空串/非数字兜底 0（Field.length 为 Int、对话框
     * 长度输入为整数绑定，scale 无处安放，由 {@link #resolveEditedLength} 在未改动时原样带回）。
     */
    static int parseDialogLength(Object length) {
        if (length == null) {
            return 0;
        }
        String s = length.toString();
        int comma = s.indexOf(',');
        if (comma >= 0) {
            s = s.substring(0, comma);
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * P2-5：编辑确认回写时的 length 决策（纯函数，供单测）——原 map 的 length 为 "P,S" 且对话框
     * 长度值等于精度部分（用户未改动长度）时，原样写回 "P,S"，scale 不因编辑往返丢失；用户改了
     * 长度则按对话框新值（Integer）写回，用户显式改动优先。其余情形（原 length 无逗号/null）按对话框值。
     */
    static Object resolveEditedLength(Object originalLength, int dialogLength) {
        if (originalLength != null) {
            String s = originalLength.toString();
            if (s.indexOf(',') >= 0 && dialogLength == parseDialogLength(s)) {
                return s;
            }
        }
        return dialogLength;
    }

    private void generateDataModel() {
        // 生成dataModel的逻辑
        CodeGenerateService service = project.getService(CodeGenerateService.class);

        // 检查 fields name、type必须有值
        if (this.fields.stream().anyMatch(field -> isEmptyOrSpaces((String) field.get("name")) || isEmptyOrSpaces((String) field.get("type")))) {
            MyPluginMessages.showWarning("无法继续生成", "字段名或类型为空", project);
            return;
        }

        if (dataMaintenanceRadioButton.isSelected() && !this.fields.isEmpty() && isEmptyOrSpaces(this.tableName)){
            this.tableName = Messages.showInputDialog(
                    project,
                    "请输入表名：",
                    "代码生成",
                    Messages.getQuestionIcon()
            );
        }

        if (isEmptyOrSpaces(this.tableName) || this.module == null || this.fields.isEmpty()) {
            MyPluginMessages.showWarning("无法继续生成", "模型数据为空", project);
            return;
        }

        String moduleName = MyPropertiesUtil.getSimpleModuleName(this.module);
        String folder = StringUtil.capitalize(moduleName);
        String modelName = Messages.showInputDialog(
                project,
                "请输入GridName（不用以Grid结尾）：",
                "代码生成",
                Messages.getQuestionIcon(),
                "",
                new InputValidatorEx() {
                    @Override
                    public @NlsContexts.DetailedDescription @Nullable String getErrorText(@NonNls String inputString) {
                        if (inputString.contains(" ")) {
                            return "GridName不能包含空格";
                        }
                        if (inputString.length() > 64) {
                            return "GridName不能超过64个字符";
                        }
                        return isEmptyOrSpaces(inputString) ? "GridName不能为空" : null;
                    }
                }
        );
        String fileName = Messages.showInputDialog(
                project,
                "请输入页面名称：",
                "代码生成",
                Messages.getQuestionIcon(),
                modelName,
                new InputValidatorEx() {
                    @Override
                    public @NlsContexts.DetailedDescription @Nullable String getErrorText(@NonNls String inputString) {
                        if (inputString.contains(" ")) {
                            return "不能包含空格";
                        }
                        if (inputString.length() > 64) {
                            return "不能超过64个字符";
                        }
                        return isEmptyOrSpaces(inputString) ? "页面名称不能为空" : null;
                    }
                }
        );
        // P2-3：菜单 SQL 草稿勾选时先问菜单中文名（sys_menu.name 与 i18n 中文值）；取消输入则整个生成中止
        final String menuNameZh;
        if (menuSqlCheckBox.isSelected()) {
            String menuNameInput = Messages.showInputDialog(
                    project,
                    "菜单中文名（sys_menu.name 与 i18n 中文值）：",
                    "菜单 SQL 草稿",
                    Messages.getQuestionIcon(),
                    "",
                    new InputValidatorEx() {
                        @Override
                        public @NlsContexts.DetailedDescription @Nullable String getErrorText(@NonNls String inputString) {
                            if (inputString.contains(" ")) {
                                return "菜单中文名不能包含空格";
                            }
                            if (inputString.length() > 64) {
                                return "菜单中文名不能超过64个字符";
                            }
                            return isEmptyOrSpaces(inputString) ? "菜单中文名不能为空" : null;
                        }
                    }
            );
            if (menuNameInput == null) {
                // 取消输入 → 整个生成中止（照 tableName 为空的中止模式）
                MyPluginMessages.showWarning("无法继续生成", "已取消菜单中文名输入，本次生成中止", project);
                return;
            }
            menuNameZh = menuNameInput;
        } else {
            menuNameZh = null;
        }
//        service.generateModelByFields(this.module, folder, modelName, this.tableName, this.fields);
        /*if(mocCheckBox.isSelected()){
            service.generateMocFile(this.module, folder, modelName, this.tableName, this.fields);
        }
        if(layoutCheckBox.isSelected()){
            service.generateLayoutFile(this.module, folder, modelName, this.tableName, this.fields);
        }*/

        Map<String, Object> paramsMap = new HashMap<>();
        paramsMap.put("tableName", tableName);
        paramsMap.put("sql", sql);
        paramsMap.put("mocCheckBox", mocCheckBox.isSelected());
        paramsMap.put("layoutCheckBox", layoutCheckBox.isSelected());
        paramsMap.put("htmlCheckBox", htmlCheckBox.isSelected());
        paramsMap.put("controllerCheckBox", controllerCheckBox.isSelected());
        paramsMap.put("serviceCheckBox", serviceCheckBox.isSelected());
        paramsMap.put("daoCheckBox", daoCheckBox.isSelected());
        paramsMap.put("myBatisMapperCheckBox", myBatisMapperCheckBox.isSelected());
        paramsMap.put("generateImport", excelImportCheckBox.isSelected());
        paramsMap.put("generateExport", excelExportCheckBox.isSelected());
        // P1-6：导出框架（auto / easyexcel / easyexcel2），下拉默认「自动」由服务端探测解析
        int exportFrameworkIndex = exportFrameworkComboBox.getSelectedIndex();
        paramsMap.put("exportFramework", exportFrameworkIndex == 1 ? "easyexcel" : exportFrameworkIndex == 2 ? "easyexcel2" : "auto");
        // P2-3：菜单注册 SQL 草稿勾选标志 + 菜单中文名（zh_TW/en_US 翻译结果由后台任务完成后回填进 paramsMap）
        paramsMap.put("menuSqlCheckBox", menuSqlCheckBox.isSelected());
        paramsMap.put("menuNameZh", menuNameZh);
        paramsMap.put("dataMaintenanceRadioButton", dataMaintenanceRadioButton.isSelected());
        paramsMap.put("dataQueryRadioButton", dataQueryRadioButton.isSelected());

        // P1-7：i18n 反查（FilenameIndex/FileTypeIndex）不在 EDT 执行——后台一次性预查所有字段
        // comment 的命中结果，EDT 只消费；命中语义与逐字段现查完全一致
        new Task.Backgroundable(project, "分析字段 i18n", false) {
            private Map<String, List<Property>> i18nByComment = Collections.emptyMap();
            // P2-6：导入骨架字段的非 datagrid i18n 预查结果（后台线程索引反查，EDT 只消费；未勾导入保持空集）
            private Map<String, List<Property>> importI18nByComment = Collections.emptyMap();
            // P2-3：菜单名翻译结果（后台线程网络 IO；失败保持空串，模板渲染 TODO 形态兜底，不中断生成）
            private String menuNameTw = "";
            private String menuNameEn = "";

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // P2-3：菜单名 zh_TW/en_US 照 P2-2 调用方式（from=auto，cht/en）在后台线程翻译
                if (menuSqlCheckBox.isSelected() && menuNameZh != null) {
                    BaiduTranslator translator = ApplicationManager.getApplication().getService(BaiduTranslator.class);
                    try {
                        menuNameTw = translator.translate(menuNameZh, "auto", "cht");
                    } catch (Exception ex) {
                        LOG.info("菜单名翻译 zh_TW 失败：" + ExceptionUtil.getMessage(ex));
                    }
                    try {
                        menuNameEn = translator.translate(menuNameZh, "auto", "en");
                    } catch (Exception ex) {
                        LOG.info("菜单名翻译 en_US 失败：" + ExceptionUtil.getMessage(ex));
                    }
                }
                // P2-6：导入骨架 i18n 属性反查（findModuleI18nPropertiesByValue 内部走 FilenameIndex/FileTypeIndex）
                // 同样禁止在 EDT 执行——非 datagrid 范围批量预查（与下方 datagrid 预查同款双分支线程纪律），
                // 结果经 paramsMap 传回 EDT 由 generateBaseQueryTypeFile 消费（命中取首个 key 作 i18n 属性）
                if (excelImportCheckBox.isSelected()) {
                    Set<String> importComments = new LinkedHashSet<>();
                    for (Map<String, Object> field : fields) {
                        Object comment = field.get("comment");
                        if (comment != null) {
                            importComments.add(comment.toString());
                        }
                    }
                    importI18nByComment = MyPropertiesUtil.findModuleI18nPropertiesByValueBatch(project, module, importComments);
                }
                if (!dataQueryRadioButton.isSelected()) {
                    return;
                }
                Set<String> comments = new LinkedHashSet<>();
                for (Map<String, Object> field : fields) {
                    Object comment = field.get("comment");
                    if (comment != null) {
                        comments.add(comment.toString());
                    }
                }
                i18nByComment = MyPropertiesUtil.findModuleDataGridI18nPropertiesByValueBatch(project, module, comments);
            }

            @Override
            public void onSuccess() {
                // Task.Backgroundable#onSuccess 在 EDT 执行：只消费预查结果，不再触发索引查询
                // P2-3：后台翻译好的菜单名回填 paramsMap，供 generateBaseQueryTypeFile 的菜单 SQL 分支消费
                paramsMap.put("menuNameTw", menuNameTw);
                paramsMap.put("menuNameEn", menuNameEn);
                // P2-6：导入骨架 i18n 预查结果回填 paramsMap，供 generateBaseQueryTypeFile 的 Imp mapper 骨架分支消费
                paramsMap.put("importI18nByComment", importI18nByComment);
                if (dataQueryRadioButton.isSelected()) {
                    // P2-2：纯逻辑计算缺失清单（复用预查结果，无索引/PSI 查询）；无缺失时零行为变化（直接生成，不弹窗）
                    CodeGenerateService.I18nMissingSummary missingSummary = CodeGenerateService.collectI18nMissingSummary(
                            module.getName(), modelName, fields, i18nByComment);
                    if (!missingSummary.missingEntries().isEmpty()) {
                        // 有缺失 → 第二个后台 Task：定位三语言 datagrid 文件 + 串行百度翻译，
                        // 完成后 EDT 弹可编辑确认对话框按「追加并生成/直接生成」分流；
                        // moc 生成延后到分流后（保持查询件先于 moc 的原时序）
                        new Task.Backgroundable(project, "翻译缺失字段 i18n", true) {
                            private I18nGenerateService.I18nDatagridAppendContext appendContext;
                            private List<I18nAppendConfirmDialog.I18nConfirmRow> confirmRows;

                            @Override
                            public void run(@NotNull ProgressIndicator indicator) {
                                indicator.setIndeterminate(true);
                                appendContext = project.getService(I18nGenerateService.class).prepareAppendContext(module);
                                confirmRows = translateMissingEntriesForConfirm(missingSummary.missingEntries());
                            }

                            @Override
                            public void onSuccess() {
                                // EDT：弹确认对话框（字段名/key 只读，三语言值可编辑）
                                I18nAppendConfirmDialog dialog = new I18nAppendConfirmDialog(
                                        MyPropertiesUtil.deriveI18nKeyPrefix(module.getName()), module.getName(),
                                        missingSummary.hitCount(), missingSummary.missCount(), confirmRows);
                                dialog.show();
                                // 「追加并生成」(OK) → 确认 map + 追加上下文（生成后写 properties + 布局 i18nKey 闭环）；
                                // 「直接生成」/Esc/关闭 → null（不写 properties，保持现状裸中文回退）
                                Map<String, I18nGenerateService.I18nConfirmedAppend> confirmedI18nByField = dialog.isOK()
                                        ? dialog.readConfirmedByField() : null;
                                service.generateBaseQueryTypeFile(module, folder, modelName, fileName, sql, fields,
                                        paramsMap, i18nByComment, confirmedI18nByField,
                                        confirmedI18nByField == null ? null : appendContext);
                                if (mocCheckBox.isSelected()) {
                                    service.generateMocFile(module, folder, modelName, tableName, fields);
                                }
                            }

                            @Override
                            public void onError(@NotNull Exception error) {
                                // 翻译/文件定位失败不阻断生成：降级为直接生成（裸中文回退，与 P2-2 之前行为一致）
                                LOG.warn("i18n 翻译后台任务失败，按直接生成继续", error);
                                service.generateBaseQueryTypeFile(module, folder, modelName, fileName, sql, fields,
                                        paramsMap, i18nByComment, null, null);
                                if (mocCheckBox.isSelected()) {
                                    service.generateMocFile(module, folder, modelName, tableName, fields);
                                }
                            }

                            @Override
                            public void onCancel() {
                                // 用户取消进度条（翻译卡住不想等）也不丢生成：降级为直接生成（onSuccess/onError 均不会走）
                                service.generateBaseQueryTypeFile(module, folder, modelName, fileName, sql, fields,
                                        paramsMap, i18nByComment, null, null);
                                if (mocCheckBox.isSelected()) {
                                    service.generateMocFile(module, folder, modelName, tableName, fields);
                                }
                            }
                        }.queue();
                        return;
                    }
                    service.generateBaseQueryTypeFile(module, folder, modelName, fileName, sql, fields, paramsMap, i18nByComment, null, null);
                }

                if (mocCheckBox.isSelected()) {
                    service.generateMocFile(module, folder, modelName, tableName, fields);
                }
            }

            @Override
            public void onError(@NotNull Exception error) {
                // 预查失败则中止生成，避免以空 i18n 结果静默降级产物
                LOG.warn("i18n 预查失败，中止生成", error);
                MyPluginMessages.showError("无法生成", "字段 i18n 预查失败，已中止本次生成："
                        + ExceptionUtil.getMessage(error), project);
            }
        }.queue();

        // 关闭窗口
//        frame.dispose();
    }

    /**
     * P2-2：缺失字段三语言翻译（后台线程串行调用 BaiduTranslator，from=auto，zh_TW→cht、en_US→en；
     * zh_CN 预填 comment 不走翻译）。
     * <p>快速失败：开头连续 {@code I18nGenerateService.FAST_FAIL_TRANSLATION_THRESHOLD}（=3）个字段翻译
     * 都抛异常则放弃剩余翻译、全部置空（断网时不让用户等几十次超时）；单字段失败只置空该字段对应
     * 语言值，翻译失败的格在确认对话框里可手填。</p>
     */
    private List<I18nAppendConfirmDialog.I18nConfirmRow> translateMissingEntriesForConfirm(
            List<CodeGenerateService.I18nMissingEntry> missingEntries) {
        BaiduTranslator translator = ApplicationManager.getApplication().getService(BaiduTranslator.class);
        List<I18nAppendConfirmDialog.I18nConfirmRow> rows = new ArrayList<>();
        int leadingConsecutiveFailures = 0;
        boolean seenAnySuccess = false;
        boolean abandoned = false;
        for (CodeGenerateService.I18nMissingEntry entry : missingEntries) {
            String zhTw = "";
            String enUs = "";
            if (!abandoned) {
                boolean fieldFailed = false;
                try {
                    zhTw = translator.translate(entry.chs(), "auto", "cht");
                    seenAnySuccess = true;
                } catch (Exception ex) {
                    fieldFailed = true;
                    LOG.info("i18n 翻译 zh_TW 失败（" + entry.proposedKey() + "）：" + ExceptionUtil.getMessage(ex));
                }
                try {
                    enUs = translator.translate(entry.chs(), "auto", "en");
                    seenAnySuccess = true;
                } catch (Exception ex) {
                    fieldFailed = true;
                    LOG.info("i18n 翻译 en_US 失败（" + entry.proposedKey() + "）：" + ExceptionUtil.getMessage(ex));
                }
                // 只统计「开头」的连续失败（出现任一成功后不再累积）
                if (!seenAnySuccess && fieldFailed) {
                    leadingConsecutiveFailures++;
                    if (I18nGenerateService.shouldFastFailTranslations(leadingConsecutiveFailures)) {
                        abandoned = true;
                        zhTw = "";
                        enUs = "";
                        LOG.warn("开头连续 " + leadingConsecutiveFailures + " 个字段翻译均失败（疑似断网/密钥失效），"
                                + "放弃剩余翻译；空值格可在确认对话框手填，确认后走 TODO 注释行降级");
                    }
                }
            }
            rows.add(new I18nAppendConfirmDialog.I18nConfirmRow(
                    entry.fieldName(), entry.proposedKey(), entry.chs(), zhTw, enUs));
        }
        return rows;
    }

    public void show() {
//        frame.pack();
        // 窗口居中
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // 自定义 TableCellRenderer
    static class CustomTableCellRenderer extends DefaultTableCellRenderer {
        private static final JBColor EVEN_ROW_COLOR = new JBColor(Gray._245, Gray._40);
        private static final JBColor ODD_ROW_COLOR = new JBColor(Color.WHITE, Gray._50);
        private static final JBColor SELECTED_CELL_BACKGROUND = new JBColor(new Color(184, 207, 229), new Color(90, 150, 230));
        private static final JBColor SELECTED_CELL_FOREGROUND = new JBColor(Color.BLACK, Color.WHITE);
        private static final JBColor TRUE_TEXT_COLOR = new JBColor(new Color(0, 128, 0), new Color(0, 150, 0));
        private static final JBColor FALSE_TEXT_COLOR = new JBColor(Color.RED, new Color(255, 100, 100));
        private static final JBColor DEFAULT_TEXT_COLOR = new JBColor(Color.BLACK, Color.LIGHT_GRAY);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component cellComponent = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            // Set background and foreground colors
            if (isSelected) {
                cellComponent.setBackground(SELECTED_CELL_BACKGROUND);
                cellComponent.setForeground(SELECTED_CELL_FOREGROUND);
            } else {
                if (row % 2 == 0) {
                    cellComponent.setBackground(EVEN_ROW_COLOR);
                } else {
                    cellComponent.setBackground(ODD_ROW_COLOR);
                }
                // Determine foreground color based on cell value
                String cellValue = value != null ? value.toString() : "";
                if ("true".equals(cellValue)) {
                    cellComponent.setForeground(TRUE_TEXT_COLOR);
                } else if ("false".equals(cellValue)) {
                    cellComponent.setForeground(FALSE_TEXT_COLOR);
                } else {
                    cellComponent.setForeground(DEFAULT_TEXT_COLOR);
                }
            }

            // Set the font for better appearance
            cellComponent.setFont(cellComponent.getFont().deriveFont(Font.PLAIN));

            return cellComponent;
        }
    }

}
