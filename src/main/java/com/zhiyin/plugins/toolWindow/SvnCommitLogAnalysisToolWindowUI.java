package com.zhiyin.plugins.toolWindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.settings.AppSettingsState;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * SVN 提交日志深度分析 ToolWindow UI
 *
 * 核心功能：
 * 1. 按自定义日期范围筛选 SVN 提交记录
 * 2. 按提交人员过滤
 * 3. 支持多项目本地 SVN 目录路径配置
 * 4. 提供数据统计与可视化图表
 * 5. 自动组装结构化 Prompt 供大模型生成日报/周报
 */
public class SvnCommitLogAnalysisToolWindowUI {

    private final Project project;
    private final ToolWindow toolWindow;

    // ---- 筛选控件 ----
    private final JTextField startDateField = new JTextField(10);
    private final JTextField endDateField = new JTextField(10);
    private final JButton startDatePickerBtn = new JButton("📅");
    private final JButton endDatePickerBtn = new JButton("📅");
    private final JComboBox<String> projectCombo = new JComboBox<>();
    private final JComboBox<String> authorCombo = new JComboBox<>();
    private final JButton analyzeCurrentButton = new JButton("分析当前项目");
    private final JButton analyzeSelectedButton = new JButton("分析选中项目");
    private final JButton cancelButton = new JButton("取消");
    private final JLabel statusLabel = new JLabel("就绪");
    private final JProgressBar progressBar = new JProgressBar();

    // ---- 提交记录表格 ----
    private final String[] logColumnNames = {"版本号", "项目", "作者", "日期", "消息摘要", "变更文件数", "代码量"};
    private final DefaultTableModel logTableModel = new DefaultTableModel(logColumnNames, 0) {
        @Override
        public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JBTable logTable = new JBTable(logTableModel);

    // ---- 统计表格 ----
    private final String[] statColumnNames = {"作者", "项目", "提交次数", "变更文件总数", "代码量", "占比"};
    private final DefaultTableModel statTableModel = new DefaultTableModel(statColumnNames, 0) {
        @Override
        public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JBTable statTable = new JBTable(statTableModel);

    // ---- 可视化图表 ----
    private final ChartPanel chartPanel = new ChartPanel();

    // ---- Prompt 区域 ----
    private final JTextArea promptArea = new JTextArea(12, 60);
    private final JButton copyPromptButton = new JButton("一键复制 Prompt");
    private final JLabel reportTypeLabel = new JLabel("报告类型:");
    private final JComboBox<String> reportTypeCombo = new JComboBox<>(new String[]{"日报", "周报", "月度总结", "年中总结", "年度总结"});
    private final JCheckBox includeDetailCheck = new JCheckBox("包含详细变更路径", true);
    private final JCheckBox includeStatsCheck = new JCheckBox("包含统计摘要", true);

    // ---- 项目多选 ----
    private final JButton projectSelectBtn = new JButton("选择项目...");
    private final Map<String, JCheckBox> projectCheckMap = new LinkedHashMap<>();
    private List<ProjectConfig> projectConfigs = new ArrayList<>();

    // ---- 数据 ----
    private List<SvnCommitRecord> commitRecords = new ArrayList<>();
    private Map<String, AuthorStats> authorStatsMap = new LinkedHashMap<>();
    // 当前筛选后显示的数据（供 refreshPrompt 使用）
    private List<SvnCommitRecord> displayedRecords = new ArrayList<>();
    private Map<String, AuthorStats> displayedStats = new LinkedHashMap<>();
    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);

    // ---- 缓存：项目的作者列表 ----
    private final Set<String> allAuthorsCache = new LinkedHashSet<>();
    // 记录上次分析的项目路径列表（供 refreshPrompt 使用）
    private List<String> lastAnalyzedWorkDirs = new ArrayList<>();

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat SVN_DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /** 将 yyyy-MM-dd 格式的日期字符串加一天后返回 */
    private static String addOneDay(String dateStr) {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(DATE_FMT.parse(dateStr));
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            return DATE_FMT.format(cal.getTime());
        } catch (ParseException e) {
            return dateStr; // 解析失败则原样返回
        }
    }

    public SvnCommitLogAnalysisToolWindowUI(Project project, ToolWindow toolWindow) {
        this.project = project;
        this.toolWindow = toolWindow;
        initUI();
        loadSettings();
    }

    // ================================================================
    //  UI 初始化
    // ================================================================

    private void initUI() {
        // 默认日期：最近一周
        Calendar cal = Calendar.getInstance();
        endDateField.setText(DATE_FMT.format(cal.getTime()));
        cal.add(Calendar.DAY_OF_MONTH, -7);
        startDateField.setText(DATE_FMT.format(cal.getTime()));

        // 按钮样式
        analyzeCurrentButton.setIcon(AllIcons.Actions.Execute);
        analyzeSelectedButton.setIcon(AllIcons.Actions.Execute);
        cancelButton.setIcon(AllIcons.Actions.Suspend);
        cancelButton.setEnabled(false);
        copyPromptButton.setIcon(AllIcons.Actions.Copy);

        // 进度条
        progressBar.setIndeterminate(false);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);

        // 日期选择器
        startDatePickerBtn.setMargin(new Insets(0, 4, 0, 4));
        startDatePickerBtn.setToolTipText("选择起始日期");
        endDatePickerBtn.setMargin(new Insets(0, 4, 0, 4));
        endDatePickerBtn.setToolTipText("选择结束日期");
        startDatePickerBtn.addActionListener(e -> showDatePicker(startDateField));
        endDatePickerBtn.addActionListener(e -> showDatePicker(endDateField));

        // 项目多选按钮
        projectSelectBtn.setToolTipText("选择要分析的SVN项目（支持多选，可在设置中配置项目别名=路径）");
        projectSelectBtn.addActionListener(e -> showProjectSelector());
        refreshProjectCheckList();

        // 作者下拉框（含"全部"选项）
        authorCombo.addItem("-- 全部作者 --");
        authorCombo.setEditable(true);
        authorCombo.setToolTipText("按提交人筛选，可直接输入");

        // 表格排序
        TableRowSorter<DefaultTableModel> logSorter = new TableRowSorter<>(logTableModel);
        logTable.setRowSorter(logSorter);
        logTable.setFillsViewportHeight(true);

        TableRowSorter<DefaultTableModel> statSorter = new TableRowSorter<>(statTableModel);
        statTable.setRowSorter(statSorter);
        statTable.setFillsViewportHeight(true);

        // Ctrl+C 复制
        registerTableCopyAction(logTable);
        registerTableCopyAction(statTable);

        // 按钮事件
        analyzeCurrentButton.addActionListener(e -> performAnalysisForCurrent());
        analyzeSelectedButton.addActionListener(e -> performAnalysisForSelected());
        cancelButton.addActionListener(e -> cancelAnalysis());
        copyPromptButton.addActionListener(e -> copyPromptToClipboard());
        authorCombo.addActionListener(e -> onAuthorFilterChanged());

        // 状态栏样式
        statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));
    }

    /**
     * 组装完整 UI 布局
     */
    public JComponent getContent() {
        JPanel root = new JPanel(new BorderLayout(3, 3));

        // ---- 顶部：筛选区 ----
        root.add(buildFilterPanel(), BorderLayout.NORTH);

        // ---- 中部：Tab 切换（提交记录 / 统计图表 / 文件级详情） ----
        JBTabbedPane tabbedPane = new JBTabbedPane();
        tabbedPane.addTab("提交记录", buildLogTablePanel());
        tabbedPane.addTab("统计图表", buildStatsPanel());
        root.add(tabbedPane, BorderLayout.CENTER);

        // ---- 底部：Prompt 生成区 ----
        root.add(buildPromptPanel(), BorderLayout.SOUTH);

        // 外层容器
        SimpleToolWindowPanel panel = new SimpleToolWindowPanel(true, false);
        panel.setContent(root);

        // 每次 toolWindow 变为可见时，自动刷新项目列表（用户可能在设置中修改了路径）
        // 用 invokeLater 延迟执行，确保持久化状态已完全加载
        root.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                SwingUtilities.invokeLater(() -> {
                    refreshProjectCheckList();
                    updateProjectBtnLabel();
                });
            }
            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {}
            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent event) {}
        });

        return panel;
    }

    // ================================================================
    //  筛选区 UI
    // ================================================================

    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // Row 0: 日期范围
        gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("日期范围:"), gbc);

        gbc.gridx = 1; gbc.weightx = 0.3; gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel startPanel = new JPanel(new BorderLayout());
        startPanel.add(startDateField, BorderLayout.CENTER);
        startPanel.add(startDatePickerBtn, BorderLayout.EAST);
        panel.add(startPanel, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(" 至 "), gbc);

        gbc.gridx = 3; gbc.weightx = 0.3; gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel endPanel = new JPanel(new BorderLayout());
        endPanel.add(endDateField, BorderLayout.CENTER);
        endPanel.add(endDatePickerBtn, BorderLayout.EAST);
        panel.add(endPanel, gbc);

        // Row 1: 项目、作者
        row++;
        gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("项目:"), gbc);

        gbc.gridx = 1; gbc.weightx = 0.3; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(projectSelectBtn, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("作者:"), gbc);

        gbc.gridx = 3; gbc.weightx = 0.3; gbc.fill = GridBagConstraints.HORIZONTAL;
        authorCombo.setPreferredSize(new Dimension(120, authorCombo.getPreferredSize().height));
        panel.add(authorCombo, gbc);

        // Row 2: 按钮 + 进度条 + 状态
        row++;
        gbc.gridy = row; gbc.gridx = 0; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnPanel.add(analyzeCurrentButton);
        btnPanel.add(analyzeSelectedButton);
        btnPanel.add(cancelButton);
        btnPanel.add(progressBar);
        btnPanel.add(statusLabel);
        panel.add(btnPanel, gbc);

        // 设置面板最小高度
        panel.setBorder(BorderFactory.createTitledBorder("筛选条件"));
        return panel;
    }

    // ================================================================
    //  提交记录表格
    // ================================================================

    private JComponent buildLogTablePanel() {
        // 设置列宽：版本号/项目/作者 窄列，日期 中列，消息摘要 宽列，变更文件数/代码量 窄列
        logTable.getColumnModel().getColumn(0).setPreferredWidth(65);   // 版本号
        logTable.getColumnModel().getColumn(1).setPreferredWidth(80);   // 项目
        logTable.getColumnModel().getColumn(2).setPreferredWidth(70);   // 作者
        logTable.getColumnModel().getColumn(3).setPreferredWidth(125);  // 日期
        logTable.getColumnModel().getColumn(4).setPreferredWidth(370);  // 消息摘要 - 宽列（缩减20px给代码量列）
        logTable.getColumnModel().getColumn(5).setPreferredWidth(65);   // 变更文件数
        logTable.getColumnModel().getColumn(6).setPreferredWidth(65);   // 代码量

        // 消息摘要列使用 JTextArea 渲染器，支持自动换行显示完整内容
        logTable.getColumnModel().getColumn(4).setCellRenderer(new TableCellRenderer() {
            private final JTextArea textArea = new JTextArea();
            {
                textArea.setLineWrap(true);
                textArea.setWrapStyleWord(true);
                textArea.setOpaque(true);
                textArea.setFont(logTable.getFont());
                textArea.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
            }
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus,
                                                           int row, int column) {
                textArea.setText(value != null ? value.toString() : "");
                textArea.setSize(table.getColumnModel().getColumn(column).getWidth(), Integer.MAX_VALUE);
                int preferredHeight = textArea.getPreferredSize().height;
                // 动态调整行高以显示完整内容
                if (table.getRowHeight(row) < preferredHeight) {
                    table.setRowHeight(row, preferredHeight);
                }
                if (isSelected) {
                    textArea.setBackground(table.getSelectionBackground());
                    textArea.setForeground(table.getSelectionForeground());
                } else {
                    textArea.setBackground(table.getBackground());
                    textArea.setForeground(table.getForeground());
                }
                return textArea;
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(logTable);
        scrollPane.setPreferredSize(new Dimension(0, 200));
        return scrollPane;
    }

    // ================================================================
    //  统计图表区
    // ================================================================

    private JComponent buildStatsPanel() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // 左侧：统计表格
        JBScrollPane statScroll = new JBScrollPane(statTable);
        statScroll.setPreferredSize(new Dimension(340, 0));
        splitPane.setLeftComponent(statScroll);

        // 右侧：可视化图表
        chartPanel.setPreferredSize(new Dimension(350, 0));
        chartPanel.setMinimumSize(new Dimension(200, 0));
        splitPane.setRightComponent(chartPanel);

        splitPane.setResizeWeight(0.50);
        splitPane.setDividerLocation(0.50);
        return splitPane;
    }

    // ================================================================
    //  Prompt 生成区
    // ================================================================

    private JPanel buildPromptPanel() {
        JPanel panel = new JPanel(new BorderLayout(3, 3));
        panel.setBorder(BorderFactory.createTitledBorder("结构化 Prompt（可粘贴给大模型生成日报/周报/月报/年报）"));

        promptArea.setEditable(false);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        topRow.add(reportTypeLabel);
        topRow.add(reportTypeCombo);
        topRow.add(includeDetailCheck);
        topRow.add(includeStatsCheck);
        topRow.add(copyPromptButton);

        // 更新 Prompt 当选项变化时
        reportTypeCombo.addActionListener(e -> refreshPrompt());
        includeDetailCheck.addActionListener(e -> refreshPrompt());
        includeStatsCheck.addActionListener(e -> refreshPrompt());

        panel.add(topRow, BorderLayout.NORTH);
        panel.add(new JBScrollPane(promptArea), BorderLayout.CENTER);

        // 控制最小高度
        panel.setPreferredSize(new Dimension(0, 200));
        return panel;
    }

    // ================================================================
    //  设置加载与持久化
    // ================================================================

    private void loadSettings() {
        AppSettingsState settings = AppSettingsState.getInstance();
        if (settings.svnLastStartDate != null && !settings.svnLastStartDate.isEmpty()) {
            startDateField.setText(settings.svnLastStartDate);
        }
        if (settings.svnLastEndDate != null && !settings.svnLastEndDate.isEmpty()) {
            endDateField.setText(settings.svnLastEndDate);
        }
    }

    /**
     * 从设置中解析项目配置列表（别名=路径）
     */
    private List<ProjectConfig> parseProjectConfigs() {
        List<ProjectConfig> configs = new ArrayList<>();
        AppSettingsState settings = AppSettingsState.getInstance();
        String paths = settings.svnProjectPaths;
        if (paths != null && !paths.trim().isEmpty()) {
            for (String line : paths.split("\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.contains("=")) {
                    String name = line.substring(0, line.indexOf('=')).trim();
                    String path = line.substring(line.indexOf('=') + 1).trim();
                    if (!name.isEmpty() && !path.isEmpty()) {
                        configs.add(new ProjectConfig(name, path));
                    }
                }
            }
        }
        return configs;
    }

    /**
     * 从设置中重新加载项目多项选择列表
     */
    public void refreshProjectCheckList() {
        projectConfigs = parseProjectConfigs();
        projectCheckMap.clear();
        for (ProjectConfig cfg : projectConfigs) {
            JCheckBox cb = new JCheckBox(cfg.name);
            projectCheckMap.put(cfg.name, cb);
        }
        updateProjectBtnLabel();
    }

    private void updateProjectBtnLabel() {
        int selected = getSelectedProjectCount();
        if (selected == 0) {
            projectSelectBtn.setText("选择项目...");
        } else {
            projectSelectBtn.setText("已选 " + selected + " 个项目");
        }
    }

    private int getSelectedProjectCount() {
        int count = 0;
        for (JCheckBox cb : projectCheckMap.values()) {
            if (cb.isSelected()) count++;
        }
        return count;
    }

    /**
     * 弹出项目多选菜单
     * 每次点击时主动重新读取配置，并校验路径有效性
     */
    private void showProjectSelector() {
        // 主动重新读取项目路径配置
        refreshProjectCheckList();

        // 先校验配置格式（别名=路径）
        List<String> formatErrors = new ArrayList<>();
        AppSettingsState settings = AppSettingsState.getInstance();
        String paths = settings.svnProjectPaths;
        if (paths != null && !paths.trim().isEmpty()) {
            int lineNum = 0;
            for (String line : paths.split("\\n")) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (!trimmed.contains("=")) {
                    formatErrors.add("第" + lineNum + "行格式错误（缺少\"=\"分隔符）: " + trimmed);
                    continue;
                }
                String name = trimmed.substring(0, trimmed.indexOf('=')).trim();
                String path = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                if (name.isEmpty()) {
                    formatErrors.add("第" + lineNum + "行格式错误（别名为空）: " + trimmed);
                }
                if (path.isEmpty()) {
                    formatErrors.add("第" + lineNum + "行格式错误（路径为空）: " + trimmed);
                }
            }
        }
        if (!formatErrors.isEmpty()) {
            MyPluginMessages.showWarning(
                "项目路径配置格式校验",
                "配置格式应为每行\"别名=路径\"：\n" + String.join("\n", formatErrors),
                project
            );
        }

        // 校验项目路径有效性
        List<String> invalidPaths = new ArrayList<>();
        for (ProjectConfig cfg : projectConfigs) {
            java.io.File dir = new java.io.File(cfg.path);
            if (!dir.exists() || !dir.isDirectory()) {
                invalidPaths.add(cfg.name + " (" + cfg.path + ")");
            }
        }
        if (!invalidPaths.isEmpty()) {
            MyPluginMessages.showWarning(
                "项目路径校验",
                "以下项目路径无效或不存在：\n" + String.join("\n", invalidPaths),
                project
            );
        }

        JPopupMenu popup = new JPopupMenu();
        if (projectCheckMap.isEmpty()) {
            popup.add(new JLabel("（请在设置中配置项目别名=路径）")).setEnabled(false);
        } else {
            // 全选 / 取消全选
            JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JButton selectAllBtn = new JButton("全选");
            JButton deselectAllBtn = new JButton("取消");
            selectAllBtn.addActionListener(e -> {
                for (JCheckBox cb : projectCheckMap.values()) cb.setSelected(true);
                updateProjectBtnLabel();
            });
            deselectAllBtn.addActionListener(e -> {
                for (JCheckBox cb : projectCheckMap.values()) cb.setSelected(false);
                updateProjectBtnLabel();
            });
            actionPanel.add(selectAllBtn);
            actionPanel.add(deselectAllBtn);
            popup.add(actionPanel);
            popup.addSeparator();

            for (JCheckBox cb : projectCheckMap.values()) {
                cb.addActionListener(e -> updateProjectBtnLabel());
                popup.add(cb);
            }
        }
        popup.show(projectSelectBtn, 0, projectSelectBtn.getHeight());
    }

    public void refreshProjectCombo() {
        // 兼容旧接口，内部调用新方法
        refreshProjectCheckList();
    }

    /**
     * 将缓存的作者列表更新到作者下拉框
     */
    private void refreshAuthorCombo() {
        // 保存当前选中项
        Object currentSelected = authorCombo.getSelectedItem();
        // 清除除"全部作者"外的所有项
        while (authorCombo.getItemCount() > 1) {
            authorCombo.removeItemAt(authorCombo.getItemCount() - 1);
        }
        // 按字母排序添加作者
        List<String> sortedAuthors = new ArrayList<>(allAuthorsCache);
        Collections.sort(sortedAuthors, String.CASE_INSENSITIVE_ORDER);
        for (String author : sortedAuthors) {
            authorCombo.addItem(author);
        }
        // 尝试恢复之前选中的项
        if (currentSelected != null) {
            authorCombo.setSelectedItem(currentSelected);
        }
    }

    private void saveLastDateRange() {
        AppSettingsState settings = AppSettingsState.getInstance();
        settings.svnLastStartDate = startDateField.getText().trim();
        settings.svnLastEndDate = endDateField.getText().trim();
    }

    // ================================================================
    //  异步分析 - 两个入口：分析当前项目 / 分析选中项目
    // ================================================================

    /**
     * 分析当前打开的项目
     */
    private void performAnalysisForCurrent() {
        String base = project.getBasePath();
        String svnWorkDir = base != null ? base : System.getProperty("user.dir");
        List<ProjectConfig> configs = new ArrayList<>();
        configs.add(new ProjectConfig("当前项目", svnWorkDir));
        doPerformAnalysis(configs);
    }

    /**
     * 分析勾选中的配置项目
     */
    private void performAnalysisForSelected() {
        List<ProjectConfig> selected = new ArrayList<>();
        for (ProjectConfig cfg : projectConfigs) {
            JCheckBox cb = projectCheckMap.get(cfg.name);
            if (cb != null && cb.isSelected()) {
                selected.add(cfg);
            }
        }
        if (selected.isEmpty()) {
            MyPluginMessages.showWarning("提示", "请先在设置中配置项目路径，然后勾选要分析的项目", project);
            return;
        }
        doPerformAnalysis(selected);
    }

    /**
     * 核心分析逻辑（支持多项目）
     */
    private void doPerformAnalysis(final List<ProjectConfig> configs) {
        if (analysisRunning.get()) return;

        final String startDate = startDateField.getText().trim();
        final String endDate = endDateField.getText().trim();

        if (startDate.isEmpty() || endDate.isEmpty()) {
            MyPluginMessages.showWarning("参数错误", "请填写起始日期和结束日期", project);
            return;
        }

        // 校验日期格式
        try {
            DATE_FMT.parse(startDate);
            DATE_FMT.parse(endDate);
        } catch (ParseException ex) {
            MyPluginMessages.showWarning("日期格式错误", "请使用 yyyy-MM-dd 格式", project);
            return;
        }

        saveLastDateRange();
        lastAnalyzedWorkDirs.clear();
        for (ProjectConfig cfg : configs) lastAnalyzedWorkDirs.add(cfg.path);

        final String authorFilter = getSelectedAuthor();

        // 重置 UI
        logTableModel.setRowCount(0);
        statTableModel.setRowCount(0);
        chartPanel.setStackedData(Collections.emptyMap());
        chartPanel.clearProjectTabs();
        promptArea.setText("");
        commitRecords.clear();
        authorStatsMap.clear();

        analysisRunning.set(true);
        analyzeCurrentButton.setEnabled(false);
        analyzeSelectedButton.setEnabled(false);
        cancelButton.setEnabled(true);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        statusLabel.setText("正在分析 SVN 提交日志... [" + configs.size() + " 个项目]");

        // 使用 IntelliJ 标准的后台任务 API（支持取消、进度反馈）
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "SVN 提交日志分析", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    // Step 1: 遍历所有项目执行 svn log
                    List<SvnCommitRecord> allRecords = new ArrayList<>();
                    List<SvnCommitRecord> allErrors = new ArrayList<>();
                    float perProjectFraction = 0.5f / Math.max(1, configs.size());

                    for (int i = 0; i < configs.size(); i++) {
                        ProjectConfig cfg = configs.get(i);
                        indicator.setText("正在查询: " + cfg.name + "...");
                        indicator.setFraction(0.1f + i * perProjectFraction);
                        if (indicator.isCanceled()) return;

                        try {
                            List<SvnCommitRecord> records = fetchSvnLog(cfg.name, cfg.path, startDate, endDate, indicator);
                            allRecords.addAll(records);
                        } catch (Exception ex) {
                            // 单个项目失败不中断整体，记录错误
                            allErrors.add(new SvnCommitRecord(cfg.name, "", "ERROR", "",
                                    ex.getMessage(), 0, Collections.<String>emptyList(), ex.getMessage()));
                        }
                    }

                    if (indicator.isCanceled()) return;

                    if (allRecords.isEmpty()) {
                        updateUIStatus("未找到提交记录", false);
                        return;
                    }

                    // Step 2: 按作者过滤
                    indicator.setText("正在按作者筛选...");
                    indicator.setFraction(0.65f);
                    final List<SvnCommitRecord> filteredRecords = (authorFilter != null && !authorFilter.isEmpty())
                            ? allRecords.stream()
                                    .filter(r -> r.author.equalsIgnoreCase(authorFilter))
                                    .collect(Collectors.toList())
                            : allRecords;

                    if (indicator.isCanceled()) return;

                    // Step 3: 计算代码量（svn diff）
                    indicator.setText("正在统计代码量...");
                    indicator.setFraction(0.68f);

                    // 构建项目名→路径映射
                    Map<String, String> projectNameToPath = new LinkedHashMap<>();
                    for (ProjectConfig cfg : configs) {
                        projectNameToPath.put(cfg.name, cfg.path);
                    }
                    // 统计每个提交的代码量
                    int totalCodeVolume = 0;
                    int commitsWithCode = 0;
                    int diffFailures = 0;
                    int checkedCommits = 0; // 有匹配文件的提交数
                    for (int i = 0; i < filteredRecords.size(); i++) {
                        if (indicator.isCanceled()) return;
                        SvnCommitRecord r = filteredRecords.get(i);
                        if (i % Math.max(1, filteredRecords.size() / 20) == 0) {
                            indicator.setFraction(0.68f + 0.07f * i / Math.max(1, filteredRecords.size()));
                            indicator.setText("正在统计代码量... " + (i + 1) + "/" + filteredRecords.size());
                        }
                        // 先快速检查是否有匹配的文件变更
                        String workDir = projectNameToPath.get(r.projectName);
                        if (workDir == null) {
                            // 兼容旧数据：没有项目名对应路径的跳过
                            workDir = projectNameToPath.get("当前项目");
                        }
                        if (workDir == null) continue;

                        boolean hasMatchingFile = false;
                        for (String cp : r.changedPaths) {
                            // changedPaths 格式: "M /trunk/src/File.java"（action + space + path）
                            String filePath = cp.contains(" ") ? cp.substring(cp.indexOf(' ') + 1) : cp;
                            if (matchesCodeVolumePattern(filePath)) {
                                hasMatchingFile = true;
                                break;
                            }
                        }
                        if (hasMatchingFile) {
                            checkedCommits++;
                            try {
                                int vol = computeCommitCodeVolume(workDir,
                                        r.revision.startsWith("r") ? r.revision.substring(1) : r.revision,
                                        indicator);
                                r.codeVolume = Math.max(0, vol); // -1 视为 0（diff 失败）
                                if (vol > 0) {
                                    totalCodeVolume += vol;
                                    commitsWithCode++;
                                } else if (vol < 0) {
                                    diffFailures++;
                                }
                            } catch (Exception e) {
                                diffFailures++; // 异常也视为 diff 失败
                            }
                        }
                    }

                    if (indicator.isCanceled()) return;
                    indicator.setText("正在统计分析...");
                    indicator.setFraction(0.80f);

                    // Step 4: 统计分析
                    commitRecords = filteredRecords;
                    authorStatsMap = computeAuthorStats(filteredRecords);

                    // 构建两种指标的图表数据（提交次数 + 代码量）
                    final Map<String, Map<String, Integer>> stackedByCommits = computeStackedChartData(filteredRecords, false);
                    final Map<String, Map<String, Integer>> stackedByVolume = computeStackedChartData(filteredRecords, true);
                    final Map<String, Map<String, Integer>> projectByCommits = computePerProjectChartData(filteredRecords, false);
                    final Map<String, Map<String, Integer>> projectByVolume = computePerProjectChartData(filteredRecords, true);

                    if (indicator.isCanceled()) return;
                    indicator.setText("正在更新界面...");
                    indicator.setFraction(0.95);

                    // Step 5: 更新 UI（必须在 EDT 线程）
                    final String statusMsg;
                    if (diffFailures > 0 && diffFailures == checkedCommits) {
                        statusMsg = String.format(
                                "完成 - %d 个项目，共 %d 条记录 | 代码量统计全部失败(%d/%d)，请检查 SVN 工作目录是否有效",
                                configs.size(), filteredRecords.size(), diffFailures, checkedCommits);
                    } else if (diffFailures > 0) {
                        statusMsg = String.format(
                                "完成 - %d 个项目，共 %d 条记录 | 代码量: %d 行 (%d/%d 成功, %d 失败)",
                                configs.size(), filteredRecords.size(), totalCodeVolume,
                                commitsWithCode, checkedCommits, diffFailures);
                    } else {
                        statusMsg = String.format(
                                "完成 - %d 个项目，共 %d 条记录，%d 位作者 | 代码量: %d 行 (%d 次提交)",
                                configs.size(), filteredRecords.size(), authorStatsMap.size(),
                                totalCodeVolume, commitsWithCode);
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        displayedRecords = commitRecords;
                        displayedStats = authorStatsMap;
                        updateLogTable(filteredRecords);
                        updateStatsTable(authorStatsMap);
                        chartPanel.setAllData(stackedByCommits, stackedByVolume, projectByCommits, projectByVolume);
                        refreshAuthorCombo();
                        refreshPrompt();
                        updateUIStatus(statusMsg, false);
                    });

                } catch (Exception ex) {
                    updateUIStatus("分析失败: " + ex.getMessage(), true);
                } finally {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        analysisRunning.set(false);
                        analyzeCurrentButton.setEnabled(true);
                        analyzeSelectedButton.setEnabled(true);
                        cancelButton.setEnabled(false);
                        progressBar.setVisible(false);
                    });
                }
            }

            @Override
            public void onCancel() {
                analysisRunning.set(false);
                updateUIStatus("分析已取消", false);
            }
        });
    }

    private void cancelAnalysis() {
        if (analysisRunning.get()) {
            // ProgressManager 会自动处理取消
            cancelButton.setEnabled(false);
            statusLabel.setText("正在取消...");
        }
    }

    // ================================================================
    //  SVN 命令执行
    // ================================================================

    /**
     * 执行 svn log 命令获取提交记录
     * 使用 svn log --xml 格式解析，获取完整的提交元数据
     */
    private List<SvnCommitRecord> fetchSvnLog(String projectName, String workDir, String startDate, String endDate,
                                               ProgressIndicator indicator) throws Exception {
        List<SvnCommitRecord> records = new ArrayList<>();

        // svn log -r {start}:{end+1day} --xml -v
        // SVN 的 {date} 语法将日期视为当天 00:00:00，因此 {2026-06-15}:{2026-06-15} 范围为零。
        // 将 endDate 加一天，使得它能覆盖用户选择的结束日期全天。
        // 使用 --xml 格式便于可靠解析
        String svnEndDate = addOneDay(endDate);
        ProcessBuilder pb = new ProcessBuilder(
                "svn", "log",
                "-r", "{" + startDate + "}:{" + svnEndDate + "}",
                "--xml", "-v"
        );
        pb.directory(new java.io.File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder xmlOutput = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                xmlOutput.append(line).append('\n');
                lineCount++;
                // 每 100 行检查一次取消状态
                if (lineCount % 100 == 0 && indicator.isCanceled()) {
                    process.destroyForcibly();
                    return records;
                }
            }
        }

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("SVN log 命令执行超时（120秒）");
        }

        if (process.exitValue() != 0) {
            String errorMsg = xmlOutput.toString();
            if (errorMsg.contains("is not a working copy")) {
                throw new RuntimeException("当前目录不是 SVN 工作副本: " + workDir);
            }
            throw new RuntimeException("SVN 命令执行失败 (exit=" + process.exitValue() + "):\n" + errorMsg);
        }

        // 解析 XML 并注入项目名称
        records = parseSvnLogXml(projectName, xmlOutput.toString());

        // 缓存作者列表
        for (SvnCommitRecord r : records) {
            allAuthorsCache.add(r.author);
        }

        return records;
    }

    /**
     * 简单解析 svn log --xml 输出
     * 格式示例:
     * <logentry revision="12345">
     *   <author>username</author>
     *   <date>2026-06-10T10:30:00.000000Z</date>
     *   <msg>commit message</msg>
     *   <paths>
     *     <path action="M">/trunk/src/File.java</path>
     *   </paths>
     * </logentry>
     */
    private List<SvnCommitRecord> parseSvnLogXml(String projectName, String xml) {
        List<SvnCommitRecord> records = new ArrayList<>();

        // 简单字符串解析，不引入 XML 解析库
        String[] entries = xml.split("<logentry");
        for (int i = 1; i < entries.length; i++) {
            String entry = entries[i];

            String revision = extractXmlValue(entry, "revision=\"", "\"");
            String author = extractXmlValue(entry, "<author>", "</author>");
            String dateStr = extractXmlValue(entry, "<date>", "</date>");
            String msg = extractXmlValue(entry, "<msg>", "</msg>");

            // 解析变更路径
            List<String> changedPaths = new ArrayList<>();
            String[] pathEntries = entry.split("<path");
            for (int j = 1; j < pathEntries.length; j++) {
                String action = extractXmlValue(pathEntries[j], "action=\"", "\"");
                String path = extractXmlValue(pathEntries[j], ">", "</path>");
                if (path != null && !path.isEmpty()) {
                    changedPaths.add((action != null ? action : "?") + " " + path);
                }
            }

            if (revision == null || author == null) continue;

            // 解析日期
            String displayDate = dateStr;
            if (dateStr != null && dateStr.length() >= 19) {
                // "2026-06-10T10:30:00.000000Z" -> "2026-06-10 10:30:00"
                displayDate = dateStr.substring(0, 10) + " " + dateStr.substring(11, 19);
            }

            // 保留完整提交消息（将换行转为空格，避免破坏表格行）
            String summary = msg != null ? msg.replace('\n', ' ').replace('\r', ' ').trim() : "";

            records.add(new SvnCommitRecord(
                    projectName, "r" + revision, author, displayDate != null ? displayDate : dateStr,
                    summary, changedPaths.size(), changedPaths, msg
            ));
        }

        // 副本号从旧到新排列
        records.sort(Comparator.comparing(r -> {
            try {
                return Integer.parseInt(r.revision.substring(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }));

        return records;
    }

    private String extractXmlValue(String text, String startTag, String endTag) {
        int startIdx = text.indexOf(startTag);
        if (startIdx < 0) return null;
        startIdx += startTag.length();
        int endIdx = text.indexOf(endTag, startIdx);
        if (endIdx < 0) return null;
        return text.substring(startIdx, endIdx).trim();
    }

    // ================================================================
    //  代码量统计（svn diff 解析）
    // ================================================================

    /** 判断文件路径是否匹配代码量统计范围 */
    private static boolean matchesCodeVolumePattern(String path) {
        // 排除 components 目录
        if (path.contains("/components/") || path.contains("\\components\\")) return false;
        // 包含 **/*.java
        if (path.endsWith(".java")) return true;
        // 包含 **/*Mapper.xml
        if (path.endsWith("Mapper.xml")) return true;
        return false;
    }

    /**
     * 对单次提交执行 svn diff 并统计匹配文件的新增代码行数
     * <p>
     * 只统计新增行（+开头且非 +++），不统计删除行（-开头），符合业界惯例。
     * 跳过空新增行（仅有 + 号无实质内容）。
     * </p>
     * @return 新增代码行数，失败返回 -1（需与 0 区分，0=无匹配文件的提交）
     */
    private int computeCommitCodeVolume(String workDir, String revision, ProgressIndicator indicator) throws Exception {
        // svn diff -c {revision}
        // 注意：不传 --ignore-properties，因为某些旧版 SVN 不支持会导致全部失败
        ProcessBuilder pb = new ProcessBuilder("svn", "diff", "-c", revision);
        pb.directory(new java.io.File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // ★ 关键修复：必须在 waitFor 之前启动独立线程消费输出流，
        //    否则大 diff 的输出缓冲区会填满导致进程死锁 → 超时 → 返回 0
        StringBuilder output = new StringBuilder();
        AtomicBoolean readDone = new AtomicBoolean(false);

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // 进程被 destroyForcibly 时流中断属正常情况
            }
            readDone.set(true);
        }, "svn-diff-reader-" + revision);
        readerThread.setDaemon(true);
        readerThread.start();

        // 等待进程结束（不再死锁，因为输出已被消费）
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return -1; // 超时
        }

        // 等待读线程结束
        try { readerThread.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if (process.exitValue() != 0) return -1; // SVN 命令执行失败

        // 解析 diff 输出
        int addedLines = 0;
        boolean inMatchingFile = false;

        for (String line : output.toString().split("\n")) {
            if (indicator.isCanceled()) return -1;

            // Index: 行标记文件开始
            if (line.startsWith("Index: ")) {
                String filePath = line.substring(7).trim();
                inMatchingFile = matchesCodeVolumePattern(filePath);
            } else if (inMatchingFile) {
                // 跳过 diff 元信息行
                if (line.startsWith("===") || line.startsWith("---")
                        || line.startsWith("+++") || line.startsWith("@@")) {
                    continue;
                }
                // 跳过属性变更头部（Property changes on: / Modified: 等）
                if (line.startsWith("Property changes on:") || line.startsWith("Modified:")
                        || line.startsWith("Added:") || line.startsWith("Deleted:")
                        || line.startsWith("___")) {
                    continue;
                }
                // 只统计新增行（+开头），跳过纯 + 号空行
                if (line.startsWith("+") && line.length() > 1) {
                    addedLines++;
                }
            }
        }
        return addedLines;
    }

    // ================================================================
    //  统计分析
    // ================================================================

    private Map<String, AuthorStats> computeAuthorStats(List<SvnCommitRecord> records) {
        Map<String, AuthorStats> map = new LinkedHashMap<>();
        int totalCommits = records.size();
        for (SvnCommitRecord r : records) {
            AuthorStats stats = map.computeIfAbsent(r.author, k -> new AuthorStats(k));
            stats.commitCount++;
            stats.totalChangedFiles += r.changedFileCount;
            stats.totalCodeVolume += r.codeVolume;
            if (r.projectName != null && !r.projectName.isEmpty()) {
                stats.projectNames.add(r.projectName);
            }
        }
        // 计算占比
        for (AuthorStats s : map.values()) {
            s.percentage = totalCommits > 0
                    ? String.format("%.1f%%", 100.0 * s.commitCount / totalCommits)
                    : "0%";
        }
        // 按提交次数降序
        List<AuthorStats> sorted = new ArrayList<>(map.values());
        sorted.sort((a, b) -> Integer.compare(b.commitCount, a.commitCount));
        Map<String, AuthorStats> sortedMap = new LinkedHashMap<>();
        for (AuthorStats s : sorted) sortedMap.put(s.author, s);
        return sortedMap;
    }

    private Map<String, Integer> toChartData(Map<String, AuthorStats> statsMap, boolean useVolume) {
        Map<String, Integer> data = new LinkedHashMap<>();
        for (Map.Entry<String, AuthorStats> e : statsMap.entrySet()) {
            data.put(e.getKey(), useVolume ? e.getValue().totalCodeVolume : e.getValue().commitCount);
        }
        return data;
    }

    /**
     * 构建堆叠图表数据：每个作者 → 每个项目 → 值（提交次数或代码量）
     */
    private Map<String, Map<String, Integer>> computeStackedChartData(List<SvnCommitRecord> records, boolean useVolume) {
        // 按作者排序
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        // 先收集所有作者
        Set<String> authors = new LinkedHashSet<>();
        for (SvnCommitRecord r : records) authors.add(r.author);
        // 按字母排序作者
        List<String> sortedAuthors = new ArrayList<>(authors);
        Collections.sort(sortedAuthors, String.CASE_INSENSITIVE_ORDER);

        for (String author : sortedAuthors) {
            Map<String, Integer> projectCounts = new LinkedHashMap<>();
            for (SvnCommitRecord r : records) {
                if (r.author.equals(author)) {
                    String pn = r.projectName != null && !r.projectName.isEmpty() ? r.projectName : "未知";
                    int value = useVolume ? r.codeVolume : 1;
                    projectCounts.merge(pn, value, Integer::sum);
                }
            }
            result.put(author, projectCounts);
        }
        return result;
    }

    /**
     * 构建每个项目的独立图表数据：项目名 → 作者 → 值（提交次数或代码量）
     */
    private Map<String, Map<String, Integer>> computePerProjectChartData(List<SvnCommitRecord> records, boolean useVolume) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        // 按项目分组
        for (SvnCommitRecord r : records) {
            String pn = r.projectName != null && !r.projectName.isEmpty() ? r.projectName : "未知";
            int value = useVolume ? r.codeVolume : 1;
            result.computeIfAbsent(pn, k -> new LinkedHashMap<>())
                  .merge(r.author, value, Integer::sum);
        }
        // 每个项目内部按值降序
        for (Map<String, Integer> authorMap : result.values()) {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(authorMap.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            authorMap.clear();
            for (Map.Entry<String, Integer> e : sorted) authorMap.put(e.getKey(), e.getValue());
        }
        return result;
    }

    // ================================================================
    //  UI 更新（必须在 EDT 线程调用）
    // ================================================================

    private void updateLogTable(List<SvnCommitRecord> records) {
        logTableModel.setRowCount(0);
        // 重置行高为默认值，后续由 CellRenderer 按内容动态调整
        logTable.setRowHeight(logTable.getRowHeight());
        for (SvnCommitRecord r : records) {
            logTableModel.addRow(new Object[]{
                    r.revision, r.projectName, r.author, r.displayDate, r.summary, r.changedFileCount,
                    r.codeVolume > 0 ? String.valueOf(r.codeVolume) : "-"
            });
        }
    }

    private void updateStatsTable(Map<String, AuthorStats> statsMap) {
        statTableModel.setRowCount(0);
        // 按作者+项目维度展示
        for (AuthorStats s : statsMap.values()) {
            String projects = s.projectNames != null && !s.projectNames.isEmpty()
                    ? String.join(", ", s.projectNames) : "-";
            statTableModel.addRow(new Object[]{
                    s.author, projects, s.commitCount, s.totalChangedFiles,
                    s.totalCodeVolume > 0 ? String.valueOf(s.totalCodeVolume) : "-",
                    s.percentage
            });
        }
    }

    private void updateUIStatus(String msg, boolean isError) {
        ApplicationManager.getApplication().invokeLater(() -> {
            statusLabel.setText(msg);
            analyzeCurrentButton.setEnabled(true);
            analyzeSelectedButton.setEnabled(true);
            cancelButton.setEnabled(false);
            progressBar.setVisible(false);
            analysisRunning.set(false);
            if (isError) {
                statusLabel.setForeground(UIManager.getColor("Label.errorForeground"));
            } else {
                statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));
            }
        });
    }

    // ================================================================
    //  Prompt 生成与复制
    // ================================================================

    /**
     * 根据筛选结果组装结构化 Prompt
     */
    private void refreshPrompt() {
        if (displayedRecords.isEmpty()) {
            promptArea.setText("（暂无数据，请先执行分析）");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# SVN 提交记录分析报告\n\n");

        String startDate = startDateField.getText().trim();
        String endDate = endDateField.getText().trim();
        sb.append("## 基本信息\n");
        sb.append("- 分析时间范围: ").append(startDate).append(" ~ ").append(endDate).append("\n");
        sb.append("- 分析项目数: ").append(lastAnalyzedWorkDirs.size()).append("\n");
        if (!lastAnalyzedWorkDirs.isEmpty()) {
            for (String dir : lastAnalyzedWorkDirs) {
                sb.append("  - ").append(dir).append("\n");
            }
        }
        sb.append("- 提交总数: ").append(displayedRecords.size()).append("\n");
        sb.append("- 参与人员: ").append(displayedStats.size()).append(" 位\n\n");

        if (includeStatsCheck.isSelected()) {
            sb.append("## 人员统计\n");
            sb.append("| 作者 | 涉及项目 | 提交次数 | 变更文件总数 | 代码量 | 占比 |\n");
            sb.append("|------|---------|---------|-------------|--------|------|\n");
            for (AuthorStats s : displayedStats.values()) {
                String projects = s.projectNames != null && !s.projectNames.isEmpty()
                        ? String.join(", ", s.projectNames) : "-";
                String volStr = s.totalCodeVolume > 0 ? String.valueOf(s.totalCodeVolume) : "-";
                sb.append(String.format("| %s | %s | %d | %d | %s | %s |\n",
                        s.author, projects, s.commitCount, s.totalChangedFiles, volStr, s.percentage));
            }
            sb.append("\n");
        }

        sb.append("## 提交记录汇总\n");
        if (includeDetailCheck.isSelected()) {
            sb.append("| 版本号 | 项目 | 作者 | 日期 | 消息摘要 | 变更文件 | 代码量 |\n");
            sb.append("|--------|------|------|------|---------|----------|--------|\n");
            for (SvnCommitRecord r : displayedRecords) {
                String volStr = r.codeVolume > 0 ? String.valueOf(r.codeVolume) : "-";
                sb.append(String.format("| %s | %s | %s | %s | %s | %d | %s |\n",
                        r.revision, r.projectName, r.author, r.displayDate, r.summary, r.changedFileCount, volStr));
            }
        } else {
            for (SvnCommitRecord r : displayedRecords) {
                sb.append(String.format("- [%s] %s | %s | %s | %s\n",
                        r.projectName, r.revision, r.author, r.displayDate, r.summary));
            }
        }
        sb.append("\n");

        // 结构化 Prompt 指令
        String reportType = (String) reportTypeCombo.getSelectedItem();
        sb.append("## Prompt 指令\n");
        sb.append("请根据以上 SVN 提交记录，为我生成一份规范的 **");
        sb.append(reportType).append("**，要求：\n");

        switch (reportType) {
            case "日报":
                sb.append("1. 按今日工作内容逐条罗列（如：Bug修复、新功能开发、代码优化、文档更新等）\n");
                sb.append("2. 每项标注当前进度和完成状态\n");
                sb.append("3. 列出明日计划和待解决问题\n");
                sb.append("4. 语言简洁，控制在 500 字以内\n");
                break;
            case "周报":
                sb.append("1. 按功能模块归类本周提交内容（如：Bug修复、新功能开发、代码优化、文档更新等）\n");
                sb.append("2. 突出关键进展和重要变更，标注里程碑节点\n");
                sb.append("3. 列出下周计划和风险点\n");
                sb.append("4. 使用专业、简洁的技术语言，适合团队内部汇报\n");
                break;
            case "月度总结":
                sb.append("1. 按迭代/版本维度汇总本月主要工作成果\n");
                sb.append("2. 统计各模块工作量分布，突出核心产出\n");
                sb.append("3. 分析遇到的问题及解决方案\n");
                sb.append("4. 总结团队协作情况和下月规划\n");
                sb.append("5. 适当使用量化指标说明成果（如代码行数、修复Bug数等）\n");
                break;
            case "年中总结":
                sb.append("1. 按月度或迭代梳理上半年的主要工作成果\n");
                sb.append("2. 统计上半年各模块工作量分布，突出核心产出和关键里程碑\n");
                sb.append("3. 分析上半年遇到的问题、解决方案及经验沉淀\n");
                sb.append("4. 总结团队成长和技术债务现状\n");
                sb.append("5. 提出下半年的技术规划、改进方向和重点目标\n");
                break;
            case "年度总结":
                sb.append("1. 按季度或里程碑梳理全年重点工作\n");
                sb.append("2. 总结年度技术成果和项目交付情况\n");
                sb.append("3. 分析团队成长、技术债务和架构演进\n");
                sb.append("4. 提出下一年度的技术规划和改进方向\n");
                sb.append("5. 使用数据和事实支撑结论，体现年度价值贡献\n");
                break;
        }

        promptArea.setText(sb.toString());
        promptArea.setCaretPosition(0);
    }

    private void copyPromptToClipboard() {
        String text = promptArea.getText();
        if (text == null || text.trim().isEmpty() || text.startsWith("（暂无数据")) {
            MyPluginMessages.showWarning("提示", "没有可复制的内容", project);
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        MyPluginMessages.showInfo("已复制", "Prompt 已复制到剪贴板，可粘贴给大模型使用", project);
    }

    // ================================================================
    //  作者切换
    // ================================================================

    private void onAuthorFilterChanged() {
        if (commitRecords.isEmpty()) return;
        // 仅在已有数据时实时过滤更新
        String selected = getSelectedAuthor();
        List<SvnCommitRecord> filtered;
        if (selected == null || selected.isEmpty()) {
            filtered = commitRecords;
        } else {
            String finalSelected = selected;
            filtered = commitRecords.stream()
                    .filter(r -> r.author.equalsIgnoreCase(finalSelected))
                    .collect(Collectors.toList());
        }
        updateLogTable(filtered);
        Map<String, AuthorStats> filteredStats = computeAuthorStats(filtered);
        displayedRecords = filtered;
        displayedStats = filteredStats;
        updateStatsTable(filteredStats);
        Map<String, Map<String, Integer>> stackedByCommits = computeStackedChartData(filtered, false);
        Map<String, Map<String, Integer>> stackedByVolume = computeStackedChartData(filtered, true);
        Map<String, Map<String, Integer>> projectByCommits = computePerProjectChartData(filtered, false);
        Map<String, Map<String, Integer>> projectByVolume = computePerProjectChartData(filtered, true);
        chartPanel.setAllData(stackedByCommits, stackedByVolume, projectByCommits, projectByVolume);
        refreshPrompt();
    }

    private String getSelectedAuthor() {
        Object item = authorCombo.getSelectedItem();
        if (item == null) return null;
        String selected = item.toString().trim();
        if (selected.equals("-- 全部作者 --")) return null;
        return selected;
    }

    // ================================================================
    //  日期选择器
    // ================================================================

    private void showDatePicker(JTextField targetField) {
        Calendar cal = Calendar.getInstance();
        try {
            Date d = DATE_FMT.parse(targetField.getText().trim());
            if (d != null) cal.setTime(d);
        } catch (Exception ignored) {}

        final Calendar workingCal = (Calendar) cal.clone();
        final int origYear = cal.get(Calendar.YEAR);
        final int origMonth = cal.get(Calendar.MONTH);
        final int origDay = cal.get(Calendar.DAY_OF_MONTH);

        JDialog dialog = new JDialog(
                (Frame) SwingUtilities.getWindowAncestor(targetField), "选择日期", true);
        dialog.setLayout(new BorderLayout());
        dialog.setResizable(false);

        JPanel navPanel = new JPanel(new BorderLayout());
        SimpleDateFormat monthFmt = new SimpleDateFormat("yyyy年 M月");
        JLabel monthLabel = new JLabel("", SwingConstants.CENTER);

        JButton prevBtn = new JButton("<");
        prevBtn.setMargin(new Insets(0, 4, 0, 4));
        JButton nextBtn = new JButton(">");
        nextBtn.setMargin(new Insets(0, 4, 0, 4));
        navPanel.add(prevBtn, BorderLayout.WEST);
        navPanel.add(monthLabel, BorderLayout.CENTER);
        navPanel.add(nextBtn, BorderLayout.EAST);

        JPanel grid = new JPanel(new GridLayout(0, 7, 1, 1));
        String[] dayHeaders = {"日", "一", "二", "三", "四", "五", "六"};

        Runnable refresh = () -> {
            grid.removeAll();
            monthLabel.setText(monthFmt.format(workingCal.getTime()));
            for (String h : dayHeaders) {
                JLabel lbl = new JLabel(h, SwingConstants.CENTER);
                lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 10f));
                grid.add(lbl);
            }
            Calendar tmpCal = (Calendar) workingCal.clone();
            tmpCal.set(Calendar.DAY_OF_MONTH, 1);
            int firstDay = tmpCal.get(Calendar.DAY_OF_WEEK) - 1;
            int maxDay = tmpCal.getActualMaximum(Calendar.DAY_OF_MONTH);
            boolean isSelectedMonth = workingCal.get(Calendar.YEAR) == origYear
                    && workingCal.get(Calendar.MONTH) == origMonth;

            for (int i = 0; i < firstDay; i++) grid.add(new JLabel());
            for (int day = 1; day <= maxDay; day++) {
                JButton dayBtn = new JButton(String.valueOf(day));
                dayBtn.setMargin(new Insets(0, 2, 0, 2));
                if (isSelectedMonth && day == origDay) {
                    dayBtn.setBackground(new Color(0x2675BF));
                    dayBtn.setForeground(Color.WHITE);
                    dayBtn.setFont(dayBtn.getFont().deriveFont(Font.BOLD));
                    dayBtn.setOpaque(true);
                    dayBtn.setBorderPainted(false);
                }
                int d = day;
                dayBtn.addActionListener(ev -> {
                    workingCal.set(Calendar.DAY_OF_MONTH, d);
                    targetField.setText(DATE_FMT.format(workingCal.getTime()));
                    dialog.dispose();
                });
                grid.add(dayBtn);
            }
            dialog.pack();
        };

        prevBtn.addActionListener(e -> { workingCal.add(Calendar.MONTH, -1); refresh.run(); });
        nextBtn.addActionListener(e -> { workingCal.add(Calendar.MONTH, 1); refresh.run(); });

        dialog.add(navPanel, BorderLayout.NORTH);
        dialog.add(grid, BorderLayout.CENTER);
        refresh.run();
        dialog.setLocationRelativeTo(targetField);
        dialog.setVisible(true);
    }

    // ================================================================
    //  表格复制
    // ================================================================

    private void registerTableCopyAction(JTable table) {
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_C,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copy");
        table.getActionMap().put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                StringBuilder sb = new StringBuilder();
                int[] rows = table.getSelectedRows();
                int[] cols = table.getSelectedColumns();
                if (cols.length == 0) {
                    // 如果没有选中列，默认复制所有可见列
                    int colCount = table.getColumnCount();
                    cols = new int[colCount];
                    for (int i = 0; i < colCount; i++) cols[i] = i;
                }
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < cols.length; c++) {
                        if (c > 0) sb.append('\t');
                        Object val = table.getValueAt(rows[r], cols[c]);
                        if (val != null) sb.append(val.toString());
                    }
                    if (r < rows.length - 1) sb.append('\n');
                }
                if (sb.length() > 0) {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(sb.toString()), null);
                }
            }
        });
    }

    // ================================================================
    //  数据模型
    // ================================================================

    public static class SvnCommitRecord {
        public final String projectName;  // 所属项目别名
        public final String revision;
        public final String author;
        public final String displayDate;
        public final String summary;
        public final int changedFileCount;
        public final List<String> changedPaths;
        public final String fullMessage;
        public int codeVolume = 0;  // 代码变更行数（java + Mapper.xml，排除components）

        public SvnCommitRecord(String projectName, String revision, String author, String displayDate,
                               String summary, int changedFileCount,
                               List<String> changedPaths, String fullMessage) {
            this.projectName = projectName;
            this.revision = revision;
            this.author = author;
            this.displayDate = displayDate;
            this.summary = summary;
            this.changedFileCount = changedFileCount;
            this.changedPaths = changedPaths;
            this.fullMessage = fullMessage;
        }
    }

    public static class AuthorStats {
        public final String author;
        public int commitCount = 0;
        public int totalChangedFiles = 0;
        public int totalCodeVolume = 0;   // 代码变更行数合计
        public String percentage = "0%";
        public final Set<String> projectNames = new LinkedHashSet<>();  // 涉及的多个项目

        public AuthorStats(String author) {
            this.author = author;
        }
    }

    /** 项目配置：名称 + 路径 */
    static class ProjectConfig {
        final String name;
        final String path;
        ProjectConfig(String name, String path) { this.name = name; this.path = path; }
    }

    // ================================================================
    //  可视化图表组件（支持堆叠柱状图 + 分项目 Tab）
    // ================================================================

    static class ChartPanel extends JPanel {
        // 提交次数版本
        private Map<String, Map<String, Integer>> stackedDataByCommits = Collections.emptyMap();
        private Map<String, Map<String, Integer>> projectDataByCommits = Collections.emptyMap();
        // 代码量版本
        private Map<String, Map<String, Integer>> stackedDataByVolume = Collections.emptyMap();
        private Map<String, Map<String, Integer>> projectDataByVolume = Collections.emptyMap();
        // 当前指标
        private boolean useVolume = false;
        private final JComboBox<String> metricCombo = new JComboBox<>(new String[]{"按提交次数", "按代码量"});

        // 内部 Tab 面板
        private final JTabbedPane innerTabs = new JTabbedPane();
        // 堆叠图画板
        private final BarCanvas stackedCanvas = new BarCanvas();

        private static final Color[] BAR_COLORS = {
                new Color(0x4E79A7), new Color(0xF28E2B), new Color(0xE15759),
                new Color(0x76B7B2), new Color(0x59A14F), new Color(0xEDC948),
                new Color(0xB07AA1), new Color(0xFF9DA7), new Color(0x9C755F),
                new Color(0xBAB0AC)
        };

        public ChartPanel() {
            super(new BorderLayout());
            setMinimumSize(new Dimension(200, 150));

            // 顶部：指标选择
            JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            topPanel.add(new JLabel("统计指标:"));
            metricCombo.addActionListener(e -> {
                useVolume = metricCombo.getSelectedIndex() == 1;
                applyCurrentMetric();
            });
            topPanel.add(metricCombo);
            add(topPanel, BorderLayout.NORTH);

            innerTabs.addTab("汇总(堆叠)", stackedCanvas);
            add(innerTabs, BorderLayout.CENTER);
        }

        /** 一次性设置两种指标的数据 */
        public void setAllData(
                Map<String, Map<String, Integer>> stackedByCommits,
                Map<String, Map<String, Integer>> stackedByVolume,
                Map<String, Map<String, Integer>> projectByCommits,
                Map<String, Map<String, Integer>> projectByVolume) {
            this.stackedDataByCommits = stackedByCommits;
            this.stackedDataByVolume = stackedByVolume;
            this.projectDataByCommits = projectByCommits;
            this.projectDataByVolume = projectByVolume;
            applyCurrentMetric();
        }

        /** 兼容旧 API：设置堆叠柱状图数据 */
        public void setStackedData(Map<String, Map<String, Integer>> stackedData) {
            this.stackedDataByCommits = stackedData;
            // 同时作为代码量版本的默认值（未计算代码量时）
            if (stackedDataByVolume.isEmpty()) {
                applyCurrentMetric();
            }
        }

        /** 兼容旧 API：设置分项目 Tab */
        public void setProjectTabs(Map<String, Map<String, Integer>> projectData) {
            this.projectDataByCommits = projectData;
        }

        /** 根据当前指标刷新图表 */
        private void applyCurrentMetric() {
            Map<String, Map<String, Integer>> stacked = useVolume ? stackedDataByVolume : stackedDataByCommits;
            Map<String, Map<String, Integer>> project = useVolume ? projectDataByVolume : projectDataByCommits;
            String yLabel = useVolume ? "代码量分布（行）" : "提交次数分布";

            // 更新堆叠图标题
            stackedCanvas.setYAxisLabel(yLabel);

            // 更新堆叠图
            if (!stacked.isEmpty()) {
                Map<String, Integer> authorTotals = new LinkedHashMap<>();
                for (Map.Entry<String, Map<String, Integer>> e : stacked.entrySet()) {
                    authorTotals.put(e.getKey(), e.getValue().values().stream().mapToInt(Integer::intValue).sum());
                }
                Set<String> projectOrder = new LinkedHashSet<>();
                for (Map<String, Integer> pm : stacked.values()) {
                    projectOrder.addAll(pm.keySet());
                }
                List<String> projectList = new ArrayList<>(projectOrder);
                stackedCanvas.setMode(BarCanvas.Mode.STACKED, authorTotals, stacked, projectList);
            } else {
                stackedCanvas.setMode(BarCanvas.Mode.SINGLE, Collections.emptyMap(), Collections.emptyMap(), Collections.<String>emptyList());
            }

            // 更新分项目 Tab（保留"汇总(堆叠)"，清除其余）
            while (innerTabs.getTabCount() > 1) {
                innerTabs.removeTabAt(innerTabs.getTabCount() - 1);
            }
            if (!project.isEmpty()) {
                for (Map.Entry<String, Map<String, Integer>> entry : project.entrySet()) {
                    BarCanvas canvas = new BarCanvas();
                    canvas.setYAxisLabel(yLabel);
                    canvas.setMode(BarCanvas.Mode.SINGLE, entry.getValue(), Collections.emptyMap(), Collections.<String>emptyList());
                    innerTabs.addTab(entry.getKey(), canvas);
                }
            }
            stackedCanvas.repaint();
            innerTabs.repaint();
        }

        /** 清除所有项目 Tab */
        public void clearProjectTabs() {
            while (innerTabs.getTabCount() > 1) {
                innerTabs.removeTabAt(innerTabs.getTabCount() - 1);
            }
            stackedCanvas.setMode(BarCanvas.Mode.SINGLE, Collections.emptyMap(), Collections.emptyMap(), Collections.<String>emptyList());
            repaint();
        }
    }

    /** 柱状图画布：支持单维度画柱和堆叠画柱 */
    static class BarCanvas extends JPanel {
        enum Mode { SINGLE, STACKED }

        private static final Color[] BAR_COLORS = {
            new Color(0x4E, 0x9A, 0xF1), // 蓝
            new Color(0xF0, 0x8C, 0x2D), // 橙
            new Color(0x5E, 0xB8, 0x6A), // 绿
            new Color(0xE8, 0x49, 0x49), // 红
            new Color(0x9B, 0x59, 0xB6), // 紫
            new Color(0x34, 0x95, 0x8E), // 青
            new Color(0xF3, 0x9C, 0x12), // 金
            new Color(0x29, 0x80, 0xB9), // 深蓝
            new Color(0xC0, 0x39, 0x2B), // 深红
            new Color(0x27, 0xAE, 0x60), // 深绿
            new Color(0x8E, 0x44, 0xAD), // 深紫
            new Color(0xD3, 0x54, 0x00), // 暗橙
        };

        private Mode mode = Mode.SINGLE;
        private Map<String, Integer> data = Collections.emptyMap();
        private Map<String, Map<String, Integer>> stackedData = Collections.emptyMap();
        private List<String> projectOrder = Collections.emptyList();
        private String yAxisLabel = "提交次数分布";

        public BarCanvas() {
            setBackground(UIManager.getColor("Panel.background"));
            setMinimumSize(new Dimension(200, 150));
        }

        public void setYAxisLabel(String label) {
            this.yAxisLabel = label;
        }

        public void setMode(Mode mode, Map<String, Integer> data,
                            Map<String, Map<String, Integer>> stackedData,
                            List<String> projectOrder) {
            this.mode = mode;
            this.data = data;
            this.stackedData = stackedData;
            this.projectOrder = projectOrder;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (data.isEmpty()) {
                g.setColor(UIManager.getColor("Label.disabledForeground"));
                g.drawString("暂无数据", getWidth() / 2 - 20, getHeight() / 2);
                return;
            }

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int barCount = data.size();
            if (barCount == 0) return;

            g2.setFont(getFont().deriveFont(10f));

            // ---- 自适应布局 ----
            double slotWidth = (double) (width - 80) / barCount;
            int minBarWidth = 14;
            int maxBarWidth = 80;

            boolean rotateLabels = false;
            int labelStep = 1;
            if (slotWidth < 40 && barCount > 8) {
                rotateLabels = true;
                if (barCount > 25) labelStep = 3;
                else if (barCount > 15) labelStep = 2;
            } else if (slotWidth < 25 && barCount > 5) {
                labelStep = barCount > 15 ? 3 : 2;
            }

            int bottomPad = rotateLabels ? 65 : 50;
            int leftPad = 50;
            int chartLeft = leftPad + 10;
            int chartBottom = height - bottomPad - 10;
            int chartRight = width - 20;
            int chartTop = 40;
            int chartWidth = chartRight - chartLeft;
            int chartHeight = chartBottom - chartTop;

            slotWidth = Math.max(minBarWidth, (double) chartWidth / barCount);
            double gap = Math.max(1, slotWidth * 0.1);
            double usableBarWidth = Math.min(maxBarWidth, Math.max(minBarWidth, slotWidth - gap));

            if (slotWidth * barCount > chartWidth) {
                slotWidth = (double) chartWidth / barCount;
                usableBarWidth = Math.max(4, slotWidth - 2);
            }

            int maxVal = mode == Mode.STACKED
                    ? data.values().stream().max(Integer::compare).orElse(1)
                    : data.values().stream().max(Integer::compare).orElse(1);

            // 标题（动态）
            g2.setColor(UIManager.getColor("Label.foreground"));
            g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
            int titleW = g2.getFontMetrics().stringWidth(yAxisLabel);
            g2.drawString(yAxisLabel, chartLeft + chartWidth / 2 - titleW / 2, chartTop - 8);

            // 坐标轴
            g2.setColor(UIManager.getColor("Label.disabledForeground"));
            g2.setStroke(new BasicStroke(1));
            g2.drawLine(chartLeft, chartTop, chartLeft, chartBottom);
            g2.drawLine(chartLeft, chartBottom, chartRight, chartBottom);

            // Y 轴刻度
            g2.setFont(getFont().deriveFont(10f));
            int yTickCount = Math.min(maxVal, Math.max(3, chartHeight / 35));
            for (int i = 0; i <= yTickCount; i++) {
                int val = maxVal * i / Math.max(1, yTickCount);
                int y = chartBottom - (chartHeight * val / Math.max(1, maxVal));
                g2.drawLine(chartLeft - 3, y, chartLeft, y);
                g2.drawString(String.valueOf(val), chartLeft - 30, y + 4);
            }

            // ---- 绘制柱状图 ----
            if (mode == Mode.STACKED) {
                drawStackedBars(g2, chartLeft, chartBottom, chartTop, chartWidth, chartHeight,
                        maxVal, slotWidth, usableBarWidth, gap, rotateLabels, labelStep);
            } else {
                drawSingleBars(g2, chartLeft, chartBottom, chartTop, chartWidth, chartHeight,
                        maxVal, slotWidth, usableBarWidth, gap, rotateLabels, labelStep);
            }

            // 图例（仅堆叠模式）
            if (mode == Mode.STACKED && !projectOrder.isEmpty()) {
                drawLegend(g2, chartLeft, chartTop - 18);
            }
        }

        private void drawSingleBars(Graphics2D g2, int chartLeft, int chartBottom, int chartTop,
                                    int chartWidth, int chartHeight, int maxVal,
                                    double slotWidth, double usableBarWidth, double gap,
                                    boolean rotateLabels, int labelStep) {
            int idx = 0;
            for (Map.Entry<String, Integer> entry : data.entrySet()) {
                String author = entry.getKey();
                int val = entry.getValue();
                int barHeight = (int) ((double) val / Math.max(1, maxVal) * chartHeight);
                double slotLeft = chartLeft + idx * slotWidth;
                int slotCenterX = (int) (slotLeft + slotWidth / 2);
                int barVisualOffset = (int) ((slotWidth - usableBarWidth) / 2.0);
                int x = (int) (slotLeft + barVisualOffset);
                int y = chartBottom - barHeight;

                Color barColor = BAR_COLORS[idx % BAR_COLORS.length];
                g2.setColor(barColor);
                g2.fillRect(x, y, Math.max(2, (int) usableBarWidth), barHeight);

                // 数值标签：柱顶太靠近标题时画在柱子内部
                g2.setFont(getFont().deriveFont(9f));
                String valStr = String.valueOf(val);
                int valW = g2.getFontMetrics().stringWidth(valStr);
                if (usableBarWidth >= valW + 2 && y > chartTop + 16) {
                    g2.setColor(UIManager.getColor("Label.foreground"));
                    g2.drawString(valStr, x + (int) (usableBarWidth - valW) / 2, y - 3);
                } else if (usableBarWidth >= valW + 2) {
                    g2.setColor(Color.WHITE);
                    g2.drawString(valStr, x + (int) (usableBarWidth - valW) / 2, y + 14);
                } else if (y > chartTop + 14) {
                    g2.setColor(Color.WHITE);
                    g2.drawString(valStr, x + (int) (usableBarWidth - valW) / 2, y + 14);
                }

                drawAuthorLabel(g2, author, idx, slotLeft, slotWidth, slotCenterX, chartBottom,
                        (int) usableBarWidth, rotateLabels, labelStep);
                idx++;
            }
        }

        private void drawStackedBars(Graphics2D g2, int chartLeft, int chartBottom, int chartTop,
                                     int chartWidth, int chartHeight, int maxVal,
                                     double slotWidth, double usableBarWidth, double gap,
                                     boolean rotateLabels, int labelStep) {
            int idx = 0;
            for (Map.Entry<String, Integer> entry : data.entrySet()) {
                String author = entry.getKey();
                int totalVal = entry.getValue();
                Map<String, Integer> projCounts = stackedData.getOrDefault(author, Collections.emptyMap());

                double slotLeft = chartLeft + idx * slotWidth;
                int slotCenterX = (int) (slotLeft + slotWidth / 2);
                int barVisualOffset = (int) ((slotWidth - usableBarWidth) / 2.0);
                int barX = (int) (slotLeft + barVisualOffset);
                int barW = Math.max(2, (int) usableBarWidth);

                // 从底部往上堆叠各项目段
                int currentY = chartBottom;
                int pi = 0;
                for (String project : projectOrder) {
                    int pVal = projCounts.getOrDefault(project, 0);
                    if (pVal == 0) { pi++; continue; }
                    int segHeight = (int) ((double) pVal / Math.max(1, maxVal) * chartHeight);
                    g2.setColor(BAR_COLORS[pi % BAR_COLORS.length]);
                    g2.fillRect(barX, currentY - segHeight, barW, segHeight);
                    // 段内数值（够高时显示）
                    if (segHeight > 14) {
                        g2.setColor(Color.WHITE);
                        g2.setFont(getFont().deriveFont(9f));
                        String pvStr = String.valueOf(pVal);
                        int pvW = g2.getFontMetrics().stringWidth(pvStr);
                        if (barW >= pvW + 2) {
                            g2.drawString(pvStr, barX + (barW - pvW) / 2, currentY - segHeight / 2 + 4);
                        }
                    }
                    currentY -= segHeight;
                    pi++;
                }

                // 总数标签在柱子顶部（柱顶太靠近标题时画在柱子内部）
                g2.setFont(getFont().deriveFont(Font.BOLD, 9f));
                String valStr = String.valueOf(totalVal);
                int valW = g2.getFontMetrics().stringWidth(valStr);
                int barTop = chartBottom - (int) ((double) totalVal / Math.max(1, maxVal) * chartHeight);
                if (usableBarWidth >= valW + 2 && barTop > chartTop + 16) {
                    g2.setColor(UIManager.getColor("Label.foreground"));
                    g2.drawString(valStr, barX + (int) (usableBarWidth - valW) / 2, barTop - 3);
                } else if (usableBarWidth >= valW + 2) {
                    g2.setColor(Color.WHITE);
                    g2.drawString(valStr, barX + (int) (usableBarWidth - valW) / 2, barTop + 14);
                } else if (barTop > chartTop + 14) {
                    g2.setColor(Color.WHITE);
                    g2.drawString(valStr, barX + (int) (usableBarWidth - valW) / 2, barTop + 14);
                }

                drawAuthorLabel(g2, author, idx, slotLeft, slotWidth, slotCenterX, chartBottom,
                        (int) usableBarWidth, rotateLabels, labelStep);
                idx++;
            }
        }

        private void drawAuthorLabel(Graphics2D g2, String author, int idx,
                                     double slotLeft, double slotWidth, int slotCenterX,
                                     int chartBottom, int usableBarWidth,
                                     boolean rotateLabels, int labelStep) {
            if (idx % labelStep != 0) return;
            String label = truncateAuthor(author, (int) slotWidth, g2.getFontMetrics());
            g2.setColor(UIManager.getColor("Label.foreground"));
            g2.setFont(getFont().deriveFont(10f));
            FontMetrics lfm = g2.getFontMetrics();
            int labelW = lfm.stringWidth(label);

            if (rotateLabels) {
                AffineTransform orig = g2.getTransform();
                double angle = -Math.PI / 4;
                g2.translate(slotCenterX, chartBottom + 8);
                g2.rotate(angle);
                g2.drawString(label, 0, 0);
                g2.setTransform(orig);
            } else {
                int lx = slotCenterX - labelW / 2;
                g2.drawString(label, Math.max(0, lx), chartBottom + 14);
            }
        }

        private void drawLegend(Graphics2D g2, int x, int y) {
            g2.setFont(getFont().deriveFont(10f));
            int legendX = x;
            for (int i = 0; i < projectOrder.size(); i++) {
                g2.setColor(BAR_COLORS[i % BAR_COLORS.length]);
                g2.fillRect(legendX, y, 10, 10);
                g2.setColor(UIManager.getColor("Label.foreground"));
                g2.drawString(projectOrder.get(i), legendX + 13, y + 10);
                legendX += 13 + g2.getFontMetrics().stringWidth(projectOrder.get(i)) + 15;
            }
        }

        /** 根据柱宽智能截断作者名 */
        private static String truncateAuthor(String author, int barWidth, FontMetrics fm) {
            int maxChars = Math.max(2, barWidth / (fm.charWidth('W') + 1));
            if (author.length() <= maxChars) return author;
            if (maxChars <= 3) return author.substring(0, maxChars);
            return author.substring(0, maxChars - 2) + "..";
        }
    }
}
