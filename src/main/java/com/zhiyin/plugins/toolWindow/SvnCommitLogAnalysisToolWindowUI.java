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
    private final String[] logColumnNames = {"版本号", "作者", "日期", "消息摘要", "变更文件数"};
    private final DefaultTableModel logTableModel = new DefaultTableModel(logColumnNames, 0) {
        @Override
        public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JBTable logTable = new JBTable(logTableModel);

    // ---- 统计表格 ----
    private final String[] statColumnNames = {"作者", "提交次数", "变更文件总数", "占比"};
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

    // ---- 数据 ----
    private List<SvnCommitRecord> commitRecords = new ArrayList<>();
    private Map<String, AuthorStats> authorStatsMap = new LinkedHashMap<>();
    // 当前筛选后显示的数据（供 refreshPrompt 使用）
    private List<SvnCommitRecord> displayedRecords = new ArrayList<>();
    private Map<String, AuthorStats> displayedStats = new LinkedHashMap<>();
    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);

    // ---- 缓存：项目的作者列表 ----
    private final Set<String> allAuthorsCache = new LinkedHashSet<>();
    // 记录上次分析的工作目录（供 refreshPrompt 使用）
    private String lastAnalyzedWorkDir = "";

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat SVN_DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

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

        // 项目下拉框
        projectCombo.setToolTipText("选择要分析的SVN项目（可在设置中配置多个项目路径）");
        projectCombo.addItem("-- 默认(项目根目录) --");
        // 从设置中加载已配置的SVN项目路径
        refreshProjectCombo();

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

        // 每次 toolWindow 变为可见时，自动刷新项目下拉框（用户可能在设置中修改了路径）
        root.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                refreshProjectCombo();
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

        // Row 1: 项目、作者、操作按钮
        row++;
        gbc.gridy = row; gbc.gridx = 0; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("项目:"), gbc);

        gbc.gridx = 1; gbc.weightx = 0.3; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(projectCombo, gbc);

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
        // 设置列宽：版本号/作者/日期 窄列，消息摘要 宽列，变更文件数 窄列
        logTable.getColumnModel().getColumn(0).setPreferredWidth(70);   // 版本号
        logTable.getColumnModel().getColumn(1).setPreferredWidth(80);   // 作者
        logTable.getColumnModel().getColumn(2).setPreferredWidth(130);  // 日期
        logTable.getColumnModel().getColumn(3).setPreferredWidth(400);  // 消息摘要 - 宽列
        logTable.getColumnModel().getColumn(4).setPreferredWidth(70);   // 变更文件数

        // 消息摘要列使用 JTextArea 渲染器，支持自动换行显示完整内容
        logTable.getColumnModel().getColumn(3).setCellRenderer(new TableCellRenderer() {
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
        statScroll.setPreferredSize(new Dimension(280, 0));
        splitPane.setLeftComponent(statScroll);

        // 右侧：可视化图表
        chartPanel.setPreferredSize(new Dimension(350, 0));
        chartPanel.setMinimumSize(new Dimension(200, 0));
        splitPane.setRightComponent(new JBScrollPane(chartPanel));

        splitPane.setResizeWeight(0.45);
        splitPane.setDividerLocation(0.45);
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
     * 从设置中重新加载项目下拉框
     * 公开方法，供 Factory 在 toolWindow 激活时调用
     */
    public void refreshProjectCombo() {
        String currentSelected = (String) projectCombo.getSelectedItem();
        // 清除除"默认"外的所有项目
        while (projectCombo.getItemCount() > 1) {
            projectCombo.removeItemAt(projectCombo.getItemCount() - 1);
        }
        // 从设置加载项目
        AppSettingsState settings = AppSettingsState.getInstance();
        String paths = settings.svnProjectPaths;
        if (paths != null && !paths.trim().isEmpty()) {
            for (String line : paths.split("\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String projectName = line.contains("=") ? line.substring(0, line.indexOf('=')).trim() : line;
                projectCombo.addItem(projectName);
            }
        }
        // 恢复之前选中的项
        if (currentSelected != null) {
            projectCombo.setSelectedItem(currentSelected);
        }
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
        doPerformAnalysis(svnWorkDir);
    }

    /**
     * 分析下拉框中选中的配置项目
     */
    private void performAnalysisForSelected() {
        int idx = projectCombo.getSelectedIndex();
        if (idx <= 0) {
            MyPluginMessages.showWarning("提示", "请先在设置中配置项目路径，然后从下拉框中选择项目", project);
            return;
        }
        String selected = (String) projectCombo.getSelectedItem();
        String svnWorkDir = resolveProjectPath(selected);
        if (svnWorkDir == null) {
            MyPluginMessages.showWarning("错误", "未找到项目路径配置: " + selected, project);
            return;
        }
        doPerformAnalysis(svnWorkDir);
    }

    /**
     * 从设置中查找项目的实际路径
     * 支持两种格式:
     * 1. 项目名=路径   (如 CloudMES=D:/workspace/cloudmes)
     * 2. 直接路径       (如 D:/workspace/cloudmes)
     * 同时支持选中值本身就是一个有效路径的情况
     */
    private String resolveProjectPath(String projectName) {
        // 1) 先看选中值本身是不是有效路径（直接路径配置的兜底）
        if (projectName != null && !projectName.isEmpty()) {
            java.io.File f = new java.io.File(projectName);
            if (f.isDirectory()) {
                return projectName;
            }
        }

        // 2) 从设置中查找
        AppSettingsState settings = AppSettingsState.getInstance();
        String paths = settings.svnProjectPaths;
        if (paths != null) {
            for (String line : paths.split("\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.contains("=")) {
                    // name=path 格式
                    String name = line.substring(0, line.indexOf('=')).trim();
                    String path = line.substring(line.indexOf('=') + 1).trim();
                    if (name.equals(projectName)) return path;
                } else {
                    // 直接路径格式
                    if (line.equals(projectName)) return line;
                }
            }
        }
        return null;
    }

    /**
     * 核心分析逻辑
     */
    private void doPerformAnalysis(final String svnWorkDir) {
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
        lastAnalyzedWorkDir = svnWorkDir;

        final String authorFilter = getSelectedAuthor();

        // 重置 UI
        logTableModel.setRowCount(0);
        statTableModel.setRowCount(0);
        chartPanel.setData(Collections.emptyMap());
        promptArea.setText("");
        commitRecords.clear();
        authorStatsMap.clear();

        analysisRunning.set(true);
        analyzeCurrentButton.setEnabled(false);
        analyzeSelectedButton.setEnabled(false);
        cancelButton.setEnabled(true);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        statusLabel.setText("正在分析 SVN 提交日志... [" + svnWorkDir + "]");

        // 使用 IntelliJ 标准的后台任务 API（支持取消、进度反馈）
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "SVN 提交日志分析", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("正在查询 SVN 日志...");
                    indicator.setFraction(0.1);

                    // Step 1: 执行 svn log 获取详细提交记录
                    List<SvnCommitRecord> records = fetchSvnLog(svnWorkDir, startDate, endDate, indicator);
                    if (indicator.isCanceled()) return;

                    if (records.isEmpty()) {
                        updateUIStatus("未找到提交记录", false);
                        return;
                    }

                    // Step 2: 按作者过滤（三元表达式确保 effectively final）
                    final List<SvnCommitRecord> filteredRecords = (authorFilter != null && !authorFilter.isEmpty())
                            ? records.stream()
                                    .filter(r -> r.author.equalsIgnoreCase(authorFilter))
                                    .collect(Collectors.toList())
                            : records;

                    if (indicator.isCanceled()) return;
                    indicator.setText("正在统计分析...");
                    indicator.setFraction(0.7);

                    // Step 3: 统计分析
                    commitRecords = filteredRecords;
                    authorStatsMap = computeAuthorStats(filteredRecords);

                    if (indicator.isCanceled()) return;
                    indicator.setText("正在更新界面...");
                    indicator.setFraction(0.9);

                    // Step 4: 更新 UI（必须在 EDT 线程）
                    final String statusMsg = String.format("完成 - 共 %d 条提交记录，%d 位作者",
                            filteredRecords.size(), authorStatsMap.size());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        displayedRecords = commitRecords;
                        displayedStats = authorStatsMap;
                        updateLogTable(filteredRecords);
                        updateStatsTable(authorStatsMap);
                        chartPanel.setData(toChartData(authorStatsMap));
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
    private List<SvnCommitRecord> fetchSvnLog(String workDir, String startDate, String endDate,
                                               ProgressIndicator indicator) throws Exception {
        List<SvnCommitRecord> records = new ArrayList<>();

        // svn log -r {start}:{end} --xml -v
        // 使用 --xml 格式便于可靠解析
        ProcessBuilder pb = new ProcessBuilder(
                "svn", "log",
                "-r", "{" + startDate + "}:{" + endDate + "}",
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

        // 解析 XML
        records = parseSvnLogXml(xmlOutput.toString());

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
    private List<SvnCommitRecord> parseSvnLogXml(String xml) {
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
                    "r" + revision, author, displayDate != null ? displayDate : dateStr,
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
    //  统计分析
    // ================================================================

    private Map<String, AuthorStats> computeAuthorStats(List<SvnCommitRecord> records) {
        Map<String, AuthorStats> map = new LinkedHashMap<>();
        int totalCommits = records.size();
        for (SvnCommitRecord r : records) {
            AuthorStats stats = map.computeIfAbsent(r.author, k -> new AuthorStats(k));
            stats.commitCount++;
            stats.totalChangedFiles += r.changedFileCount;
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

    private Map<String, Integer> toChartData(Map<String, AuthorStats> statsMap) {
        Map<String, Integer> data = new LinkedHashMap<>();
        for (Map.Entry<String, AuthorStats> e : statsMap.entrySet()) {
            data.put(e.getKey(), e.getValue().commitCount);
        }
        return data;
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
                    r.revision, r.author, r.displayDate, r.summary, r.changedFileCount
            });
        }
    }

    private void updateStatsTable(Map<String, AuthorStats> statsMap) {
        statTableModel.setRowCount(0);
        for (AuthorStats s : statsMap.values()) {
            statTableModel.addRow(new Object[]{
                    s.author, s.commitCount, s.totalChangedFiles, s.percentage
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
        sb.append("- 项目路径: ").append(lastAnalyzedWorkDir).append("\n");
        sb.append("- 提交总数: ").append(displayedRecords.size()).append("\n");
        sb.append("- 参与人员: ").append(displayedStats.size()).append(" 位\n\n");

        if (includeStatsCheck.isSelected()) {
            sb.append("## 人员统计\n");
            sb.append("| 作者 | 提交次数 | 变更文件总数 | 占比 |\n");
            sb.append("|------|---------|-------------|------|\n");
            for (AuthorStats s : displayedStats.values()) {
                sb.append(String.format("| %s | %d | %d | %s |\n",
                        s.author, s.commitCount, s.totalChangedFiles, s.percentage));
            }
            sb.append("\n");
        }

        sb.append("## 提交记录汇总\n");
        if (includeDetailCheck.isSelected()) {
            sb.append("| 版本号 | 作者 | 日期 | 消息摘要 | 变更文件 |\n");
            sb.append("|--------|------|------|---------|----------|\n");
            for (SvnCommitRecord r : displayedRecords) {
                sb.append(String.format("| %s | %s | %s | %s | %d |\n",
                        r.revision, r.author, r.displayDate, r.summary, r.changedFileCount));
            }
        } else {
            for (SvnCommitRecord r : displayedRecords) {
                sb.append(String.format("- %s | %s | %s | %s\n",
                        r.revision, r.author, r.displayDate, r.summary));
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
        chartPanel.setData(toChartData(filteredStats));
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
        public final String revision;
        public final String author;
        public final String displayDate;
        public final String summary;
        public final int changedFileCount;
        public final List<String> changedPaths;
        public final String fullMessage;

        public SvnCommitRecord(String revision, String author, String displayDate,
                               String summary, int changedFileCount,
                               List<String> changedPaths, String fullMessage) {
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
        public String percentage = "0%";

        public AuthorStats(String author) {
            this.author = author;
        }
    }

    // ================================================================
    //  可视化图表组件（自定义绘制柱状图）
    // ================================================================

    static class ChartPanel extends JPanel {
        private Map<String, Integer> data = Collections.emptyMap();
        private static final Color[] BAR_COLORS = {
                new Color(0x4E79A7), new Color(0xF28E2B), new Color(0xE15759),
                new Color(0x76B7B2), new Color(0x59A14F), new Color(0xEDC948),
                new Color(0xB07AA1), new Color(0xFF9DA7), new Color(0x9C755F),
                new Color(0xBAB0AC)
        };

        public ChartPanel() {
            setBackground(UIManager.getColor("Panel.background"));
            setMinimumSize(new Dimension(200, 150));
        }

        public void setData(Map<String, Integer> data) {
            this.data = data;
            repaint();
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

            // ---- 自适应布局参数 ----
            g2.setFont(getFont().deriveFont(10f));
            FontMetrics fm = g2.getFontMetrics();

            // 估算最长作者名宽度
            int maxAuthorLen = 0;
            for (String author : data.keySet()) {
                maxAuthorLen = Math.max(maxAuthorLen, fm.stringWidth(author));
            }

            // 计算每个 bar 的 slot 宽度（均分图表）及实际视觉宽度
            double slotWidth = (double) (width - 80) / barCount;
            int minBarWidth = 14;
            int maxBarWidth = 80;

            // 决定标签显示策略（用 slotWidth 判断拥挤度）
            boolean rotateLabels = false;
            int labelStep = 1;
            if (slotWidth < 40 && barCount > 8) {
                rotateLabels = true;
                if (barCount > 25) labelStep = 3;
                else if (barCount > 15) labelStep = 2;
            } else if (slotWidth < 25 && barCount > 5) {
                labelStep = barCount > 15 ? 3 : 2;
            }

            // 重新计算布局（旋转标签需要更多底部空间）
            int bottomPad = rotateLabels ? 65 : 50;
            int leftPad = 50;
            int chartLeft = leftPad + 10;
            int chartBottom = height - bottomPad - 10;
            int chartRight = width - 20;
            int chartTop = 30;
            int chartWidth = chartRight - chartLeft;
            int chartHeight = chartBottom - chartTop;

            // slot 均分图表宽度，bar 本体在 slot 内居中且宽度钳制
            slotWidth = Math.max(minBarWidth, (double) chartWidth / barCount);
            double gap = Math.max(1, slotWidth * 0.1);
            double usableBarWidth = Math.min(maxBarWidth, Math.max(minBarWidth, slotWidth - gap));

            // 如果总宽度超出画布，压窄 slot
            if (slotWidth * barCount > chartWidth) {
                slotWidth = (double) chartWidth / barCount;
                usableBarWidth = Math.max(4, slotWidth - 2);
            }

            int maxVal = data.values().stream().max(Integer::compare).orElse(1);

            // 绘制标题
            g2.setColor(UIManager.getColor("Label.foreground"));
            g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g2.drawString("提交次数分布", chartLeft + chartWidth / 2 - 40, chartTop - 8);

            // 绘制坐标轴
            g2.setColor(UIManager.getColor("Label.disabledForeground"));
            g2.setStroke(new BasicStroke(1));
            g2.drawLine(chartLeft, chartTop, chartLeft, chartBottom);
            g2.drawLine(chartLeft, chartBottom, chartRight, chartBottom);

            // Y 轴刻度（自适应刻度数量）
            g2.setFont(getFont().deriveFont(10f));
            int yTickCount = Math.min(maxVal, Math.max(3, chartHeight / 35));
            for (int i = 0; i <= yTickCount; i++) {
                int val = maxVal * i / Math.max(1, yTickCount);
                int y = chartBottom - (chartHeight * val / Math.max(1, maxVal));
                g2.drawLine(chartLeft - 3, y, chartLeft, y);
                g2.drawString(String.valueOf(val), chartLeft - 30, y + 4);
            }

            // 绘制柱状图
            int idx = 0;
            for (Map.Entry<String, Integer> entry : data.entrySet()) {
                String author = entry.getKey();
                int val = entry.getValue();

                int barHeight = (int) ((double) val / Math.max(1, maxVal) * chartHeight);
                // slot 均分占满图表，bar 本体在 slot 内居中
                double slotLeft = chartLeft + idx * slotWidth;
                int slotCenterX = (int) (slotLeft + slotWidth / 2);
                int barVisualOffset = (int) ((slotWidth - usableBarWidth) / 2.0);
                int x = (int) (slotLeft + barVisualOffset);
                int y = chartBottom - barHeight;

                Color barColor = BAR_COLORS[idx % BAR_COLORS.length];
                g2.setColor(barColor);
                g2.fillRect(x, y, Math.max(2, (int) usableBarWidth), barHeight);

                // 数值标签（柱子够宽时才显示）
                g2.setColor(UIManager.getColor("Label.foreground"));
                g2.setFont(getFont().deriveFont(9f));
                String valStr = String.valueOf(val);
                int valW = g2.getFontMetrics().stringWidth(valStr);
                if (usableBarWidth >= valW + 2) {
                    g2.drawString(valStr, x + (int) (usableBarWidth - valW) / 2, y - 3);
                } else if (y > chartTop + 14) {
                    // 柱子太窄，数值放柱内白色显示
                    g2.setColor(Color.WHITE);
                    g2.drawString(valStr, x + (int) (usableBarWidth - valW) / 2, y + 14);
                }

                // 作者标签（按 step 间隔显示，避免拥挤）
                if (idx % labelStep == 0) {
                    String label = truncateAuthor(author, (int) slotWidth, g2.getFontMetrics());
                    g2.setColor(UIManager.getColor("Label.foreground"));
                    g2.setFont(getFont().deriveFont(10f));
                    FontMetrics lfm = g2.getFontMetrics();
                    int labelW = lfm.stringWidth(label);

                    if (rotateLabels) {
                        // 旋转 45° 绘制标签，定位在 slot 中心
                        AffineTransform orig = g2.getTransform();
                        double angle = -Math.PI / 4;
                        int ly = chartBottom + 8;
                        g2.translate(slotCenterX, ly);
                        g2.rotate(angle);
                        g2.drawString(label, 0, 0);
                        g2.setTransform(orig);
                    } else {
                        int lx = slotCenterX - labelW / 2;
                        g2.drawString(label, Math.max(0, lx), chartBottom + 14);
                    }
                }
                idx++;
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
