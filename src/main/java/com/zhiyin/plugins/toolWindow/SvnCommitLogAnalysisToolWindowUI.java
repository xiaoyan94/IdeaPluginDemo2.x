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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final JButton retryFailedButton = new JButton("重试失败项");
    private final JButton viewFailedButton = new JButton("查看失败项");
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
    private final String[] statColumnNames = {"作者", "项目", "提交次数", "变更文件总数", "代码量", "提交次数占比", "代码量占比"};
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
    private final JCheckBox includeCodeVolumeCheck = new JCheckBox("包含代码量统计", true);

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
    // 当前后台任务的进度指示，供"取消"按钮主动取消（indicator.cancel()）
    private volatile ProgressIndicator currentIndicator = null;

    // ---- 失败重试：记录代码量统计失败的提交及其工作目录/匹配文件路径/失败原因 ----
    private final Map<SvnCommitRecord, String> failedWorkDirMap = new LinkedHashMap<>();
    private final Map<SvnCommitRecord, List<String>> failedMatchingPathsMap = new LinkedHashMap<>();
    private final Map<SvnCommitRecord, String> failedReasonMap = new LinkedHashMap<>();

    // ---- 缓存：工作目录 → 其在仓库中的相对路径前缀（如 /branches/HaichengMes/webproj）----
    // svn log -v 返回的是仓库绝对路径（如 /branches/HaichengMes/webproj/xxx/Foo.java），
    // 而 svn diff 需要工作副本相对路径（如 xxx/Foo.java），需按此前缀转换。
    private final Map<String, String> wcRepoPrefixCache = new ConcurrentHashMap<>();

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
        // 数值/百分比列按数值大小排序，避免字符串字典序（如 "10" < "9"、"10%" < "9%"、"-" 等问题）
        Comparator<Object> numericComparator = (a, b) ->
                Double.compare(parseNumericCell(a), parseNumericCell(b));

        TableRowSorter<DefaultTableModel> logSorter = new TableRowSorter<>(logTableModel);
        logTable.setRowSorter(logSorter);
        logTable.setFillsViewportHeight(true);
        // 版本号、变更文件数、代码量
        for (int col : new int[]{0, 5, 6}) {
            logSorter.setComparator(col, numericComparator);
        }

        TableRowSorter<DefaultTableModel> statSorter = new TableRowSorter<>(statTableModel);
        statTable.setRowSorter(statSorter);
        statTable.setFillsViewportHeight(true);
        // 提交次数、变更文件总数、代码量、提交次数占比、代码量占比
        for (int col : new int[]{2, 3, 4, 5, 6}) {
            statSorter.setComparator(col, numericComparator);
        }

        // Ctrl+C 复制
        registerTableCopyAction(logTable);
        registerTableCopyAction(statTable);

        // 按钮事件
        analyzeCurrentButton.addActionListener(e -> performAnalysisForCurrent());
        analyzeSelectedButton.addActionListener(e -> performAnalysisForSelected());
        cancelButton.addActionListener(e -> cancelAnalysis());
        retryFailedButton.addActionListener(e -> retryFailedCommits());
        retryFailedButton.setEnabled(false);
        retryFailedButton.setToolTipText("对代码量统计失败的提交进行一键批量重试");
        viewFailedButton.addActionListener(e -> viewFailedCommits());
        viewFailedButton.setEnabled(false);
        viewFailedButton.setToolTipText("查看代码量统计失败的提交及其原因，并提供可复制的 svn 命令");
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
        btnPanel.add(includeCodeVolumeCheck);
        btnPanel.add(retryFailedButton);
        btnPanel.add(viewFailedButton);
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
        includeCodeVolumeCheck.setSelected(settings.svnIncludeCodeVolumeStats);
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
        settings.svnIncludeCodeVolumeStats = includeCodeVolumeCheck.isSelected();
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
        failedWorkDirMap.clear();
        failedMatchingPathsMap.clear();
        failedReasonMap.clear();
        wcRepoPrefixCache.clear();
        retryFailedButton.setEnabled(false);
        viewFailedButton.setEnabled(false);

        analysisRunning.set(true);
        analyzeCurrentButton.setEnabled(false);
        analyzeSelectedButton.setEnabled(false);
        cancelButton.setEnabled(true);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setString("0%");
        statusLabel.setText("正在分析 SVN 提交日志... [" + configs.size() + " 个项目]");

        // 使用 IntelliJ 标准的后台任务 API（支持取消、进度反馈）
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "SVN 提交日志分析", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                currentIndicator = indicator;
                try {
                    // 读取用户配置的主分析单条 diff 超时（秒），非法/未配置回退 3s
                    final long diffTimeoutSeconds = AppSettingsState.getInstance().svnDiffTimeoutSeconds > 0
                            ? AppSettingsState.getInstance().svnDiffTimeoutSeconds : 3;
                    // Step 1: 遍历所有项目执行 svn log
                    List<SvnCommitRecord> allRecords = new ArrayList<>();
                    List<SvnCommitRecord> allErrors = new ArrayList<>();
                    float perProjectFraction = 0.5f / Math.max(1, configs.size());

                    for (int i = 0; i < configs.size(); i++) {
                        ProjectConfig cfg = configs.get(i);
                        updateAnalysisProgress(indicator, 0.1 + i * perProjectFraction, "正在查询: " + cfg.name + "...");
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

                    // 排除指定作者（设置项 svnExcludeAuthors，逗号/分号/空格/换行分隔，忽略大小写）
                    Set<String> excludeAuthors = parseExcludeAuthors();
                    if (!excludeAuthors.isEmpty()) {
                        allRecords.removeIf(r -> r.author != null && excludeAuthors.contains(r.author.toLowerCase()));
                        allAuthorsCache.removeIf(a -> a != null && excludeAuthors.contains(a.toLowerCase()));
                    }

                    if (indicator.isCanceled()) return;

                    if (allRecords.isEmpty()) {
                        updateUIStatus("未找到提交记录", false);
                        return;
                    }

                    // Step 2: 按作者过滤
                    updateAnalysisProgress(indicator, 0.65, "正在按作者筛选...");
                    final List<SvnCommitRecord> filteredRecords = (authorFilter != null && !authorFilter.isEmpty())
                            ? allRecords.stream()
                                    .filter(r -> r.author.equalsIgnoreCase(authorFilter))
                                    .collect(Collectors.toList())
                            : allRecords;

                    if (indicator.isCanceled()) return;

                    // Step 3: 计算代码量（svn diff）
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

                    if (includeCodeVolumeCheck.isSelected()) {
                        updateAnalysisProgress(indicator, 0.68, "正在统计代码量...");

                        // 预筛需要统计代码量的提交（含匹配文件的），并记录其工作目录与匹配文件路径
                        // D: 同时收集匹配文件的仓库相对路径，传给 computeCommitCodeVolume 做定向 diff
                        List<SvnCommitRecord> toDiff = new ArrayList<>();
                        Map<SvnCommitRecord, String> workDirMap = new LinkedHashMap<>();
                        Map<SvnCommitRecord, List<String>> matchingPathsMap = new LinkedHashMap<>();
                        Map<SvnCommitRecord, String> reasonMap = new LinkedHashMap<>();
                        for (SvnCommitRecord r : filteredRecords) {
                            String workDir = projectNameToPath.get(r.projectName);
                            if (workDir == null) {
                                // 兼容旧数据：没有项目名对应路径的跳过
                                workDir = projectNameToPath.get("当前项目");
                            }
                            if (workDir == null) continue;

                            List<String> matchingPaths = new ArrayList<>();
                            for (String cp : r.changedPaths) {
                                // changedPaths 格式: "M /trunk/src/File.java"（action + space + path）
                                String filePath = cp.contains(" ") ? cp.substring(cp.indexOf(' ') + 1) : cp;
                                if (matchesCodeVolumePattern(filePath)) {
                                    matchingPaths.add(filePath);
                                }
                            }
                            if (!matchingPaths.isEmpty()) {
                                toDiff.add(r);
                                workDirMap.put(r, workDir);
                                matchingPathsMap.put(r, matchingPaths);
                            }
                        }
                        checkedCommits = toDiff.size();

                        if (toDiff.isEmpty()) {
                            updateAnalysisProgress(indicator, 0.75, null);
                        } else {
                            // 优化 A：有界线程池并行执行 svn diff，墙钟时间约降为 1/池大小
                            int poolSize = Math.min(6, toDiff.size());
                            ExecutorService pool = Executors.newFixedThreadPool(poolSize);
                            try {
                                List<Future<CodeVolumeResult>> futures = new ArrayList<>(toDiff.size());
                                for (final SvnCommitRecord r : toDiff) {
                                    final String wd = workDirMap.get(r);
                                    final String rev = r.revision.startsWith("r")
                                            ? r.revision.substring(1) : r.revision;
                                    final List<String> mp = matchingPathsMap.get(r);
                                    futures.add(pool.submit(() -> computeCommitCodeVolume(wd, rev, mp, diffTimeoutSeconds, indicator)));
                                }
                                // 在主后台线程中按完成顺序汇总，保证 indicator 更新线程安全
                                int done = 0;
                                for (int k = 0; k < toDiff.size(); k++) {
                                    if (indicator.isCanceled()) break;
                                    SvnCommitRecord r = toDiff.get(k);
                                    CodeVolumeResult cvr;
                                    try {
                                        cvr = futures.get(k).get();
                                    } catch (Exception e) {
                                        cvr = new CodeVolumeResult();
                                        cvr.volume = -1;
                                        cvr.reason = "任务执行异常: " + e.getMessage();
                                    }
                                    r.codeVolume = cvr.volume; // 保留 -1 标记失败，供"重试失败项"识别；展示层对 <=0 统一显示 "-"
                                    reasonMap.put(r, cvr.reason); // 暂存失败原因，供"查看失败项"展示
                                    if (cvr.volume > 0) {
                                        totalCodeVolume += cvr.volume;
                                        commitsWithCode++;
                                    } else if (cvr.volume < 0) {
                                        diffFailures++;
                                    }
                                    done++;
                                    updateAnalysisProgress(indicator, 0.68 + 0.07 * done / toDiff.size(),
                                            "正在统计代码量... " + done + "/" + toDiff.size());
                                }
                            } finally {
                                pool.shutdownNow();
                            }

                            // 收集失败的提交，供"重试失败项"/"查看失败项"使用
                            failedWorkDirMap.clear();
                            failedMatchingPathsMap.clear();
                            failedReasonMap.clear();
                            for (SvnCommitRecord r : toDiff) {
                                if (r.codeVolume < 0) {
                                    failedWorkDirMap.put(r, workDirMap.get(r));
                                    failedMatchingPathsMap.put(r, matchingPathsMap.get(r));
                                    failedReasonMap.put(r, reasonMap.getOrDefault(r, "未知原因"));
                                }
                            }
                        }
                    } else {
                        // 跳过代码量统计：所有提交代码量保持 0（下游展示已对 0 做 "-" 兜底）
                        updateAnalysisProgress(indicator, 0.75, "已跳过代码量统计");
                    }

                    if (indicator.isCanceled()) return;
                    updateAnalysisProgress(indicator, 0.80, "正在统计分析...");

                    // Step 4: 统计分析
                    commitRecords = filteredRecords;
                    authorStatsMap = computeAuthorStats(filteredRecords);

                    // 构建两种指标的图表数据（提交次数 + 代码量）
                    final Map<String, Map<String, Integer>> stackedByCommits = computeStackedChartData(filteredRecords, false);
                    final Map<String, Map<String, Integer>> stackedByVolume = computeStackedChartData(filteredRecords, true);
                    final Map<String, Map<String, Integer>> projectByCommits = computePerProjectChartData(filteredRecords, false);
                    final Map<String, Map<String, Integer>> projectByVolume = computePerProjectChartData(filteredRecords, true);

                    if (indicator.isCanceled()) return;
                    updateAnalysisProgress(indicator, 0.95, "正在更新界面...");

                    // Step 5: 更新 UI（必须在 EDT 线程）
                    final boolean codeVolumeSkipped = !includeCodeVolumeCheck.isSelected();
                    final String statusMsg;
                    if (codeVolumeSkipped) {
                        statusMsg = String.format(
                                "完成 - %d 个项目，共 %d 条记录，%d 位作者 | 代码量统计已跳过",
                                configs.size(), filteredRecords.size(), authorStatsMap.size());
                    } else if (diffFailures > 0 && diffFailures == checkedCommits) {
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
                        chartPanel.setVolumeEnabled(!codeVolumeSkipped);
                        chartPanel.setAllData(stackedByCommits, stackedByVolume, projectByCommits, projectByVolume);
                        refreshAuthorCombo();
                        refreshPrompt();
                        retryFailedButton.setEnabled(!failedWorkDirMap.isEmpty());
                        viewFailedButton.setEnabled(!failedWorkDirMap.isEmpty());
                        updateUIStatus(statusMsg, false);
                    });

                } catch (Exception ex) {
                    updateUIStatus("分析失败: " + ex.getMessage(), true);
                } finally {
                    currentIndicator = null;
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

    /**
     * 同步更新进度：同时驱动 IDEA 后台任务进度条与工具窗口自有进度条。
     */
    private void updateAnalysisProgress(ProgressIndicator indicator, double fraction, String text) {
        if (indicator != null) {
            if (text != null) indicator.setText(text);
            indicator.setFraction(fraction);
        }
        final int pct = (int) Math.round(fraction * 100.0);
        final String label = pct + "%";
        ApplicationManager.getApplication().invokeLater(() -> {
            progressBar.setIndeterminate(false);
            progressBar.setValue(pct);
            progressBar.setString(label);
        });
    }

    private void cancelAnalysis() {
        if (analysisRunning.get()) {
            cancelButton.setEnabled(false);
            statusLabel.setText("正在取消...");
            // 真正触发后台任务的取消，使 indicator.isCanceled() 立即返回 true 并回调 onCancel
            ProgressIndicator ind = currentIndicator;
            if (ind != null) {
                ind.cancel();
            }
        }
    }

    /**
     * 一键批量重试代码量统计失败的提交
     * <p>
     * 仅对 {@link #failedWorkDirMap} 中记录的失败项重跑定向 diff（D）+ 3s 超时重试（E），
     * 完成后刷新表格/图表/Prompt，并保留仍失败的记录供再次重试。
     * </p>
     */
    private void retryFailedCommits() {
        if (analysisRunning.get() || failedWorkDirMap.isEmpty()) return;

        // 快照当前失败项（重跑过程中映射会被重建）
        final List<SvnCommitRecord> toRetry = new ArrayList<>(failedWorkDirMap.keySet());
        final int total = toRetry.size();
        // 读取用户配置的重试超时（秒），非法/未配置回退 3s
        final long retryTimeoutSeconds = AppSettingsState.getInstance().svnRetryTimeoutSeconds > 0
                ? AppSettingsState.getInstance().svnRetryTimeoutSeconds : 3;
        statusLabel.setText("正在重试失败的代码量统计... (" + total + " 项, 超时 " + retryTimeoutSeconds + "s/条)");

        analysisRunning.set(true);
        analyzeCurrentButton.setEnabled(false);
        analyzeSelectedButton.setEnabled(false);
        cancelButton.setEnabled(true);
        retryFailedButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setString("0%");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "重试代码量统计", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    int poolSize = Math.min(6, toRetry.size());
                    ExecutorService pool = Executors.newFixedThreadPool(poolSize);
                    Map<SvnCommitRecord, String> retryReasonMap = new LinkedHashMap<>();
                    try {
                        List<Future<CodeVolumeResult>> futures = new ArrayList<>(toRetry.size());
                        for (final SvnCommitRecord r : toRetry) {
                            final String wd = failedWorkDirMap.get(r);
                            final String rev = r.revision.startsWith("r")
                                    ? r.revision.substring(1) : r.revision;
                            final List<String> mp = failedMatchingPathsMap.getOrDefault(r, Collections.emptyList());
                            futures.add(pool.submit(() -> computeCommitCodeVolume(wd, rev, mp, retryTimeoutSeconds, indicator)));
                        }
                        // 主后台线程按完成顺序汇总，保证 indicator 更新线程安全
                        int done = 0;
                        for (int k = 0; k < toRetry.size(); k++) {
                            if (indicator.isCanceled()) break;
                            SvnCommitRecord r = toRetry.get(k);
                            CodeVolumeResult cvr;
                            try {
                                cvr = futures.get(k).get();
                            } catch (Exception e) {
                                cvr = new CodeVolumeResult();
                                cvr.volume = -1; // 任务异常视为失败
                                cvr.reason = "任务执行异常: " + e.getMessage();
                            }
                            r.codeVolume = cvr.volume; // 保留 -1 标记仍失败，供再次重试识别；展示层对 <=0 统一显示 "-"
                            retryReasonMap.put(r, cvr.reason);
                            done++;
                            updateAnalysisProgress(indicator, 0.1 + 0.9 * done / total, "正在重试代码量... " + done + "/" + total);
                        }
                    } finally {
                        pool.shutdownNow();
                    }

                    // 重建失败映射：仅保留仍失败的提交
                    Map<SvnCommitRecord, String> stillFailed = new LinkedHashMap<>();
                    Map<SvnCommitRecord, List<String>> stillFailedMp = new LinkedHashMap<>();
                    Map<SvnCommitRecord, String> stillFailedReason = new LinkedHashMap<>();
                    for (Map.Entry<SvnCommitRecord, String> e : failedWorkDirMap.entrySet()) {
                        if (e.getKey().codeVolume < 0) {
                            stillFailed.put(e.getKey(), e.getValue());
                            stillFailedMp.put(e.getKey(), failedMatchingPathsMap.get(e.getKey()));
                            stillFailedReason.put(e.getKey(), retryReasonMap.getOrDefault(e.getKey(), "未知原因"));
                        }
                    }
                    failedWorkDirMap.clear();
                    failedWorkDirMap.putAll(stillFailed);
                    failedMatchingPathsMap.clear();
                    failedMatchingPathsMap.putAll(stillFailedMp);
                    failedReasonMap.clear();
                    failedReasonMap.putAll(stillFailedReason);

                    final int succeeded = total - failedWorkDirMap.size();
                    final int remaining = failedWorkDirMap.size();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        // 复用现有展示刷新逻辑（按当前作者筛选刷新表格/图表/Prompt）
                        onAuthorFilterChanged();
                        retryFailedButton.setEnabled(!failedWorkDirMap.isEmpty());
                        viewFailedButton.setEnabled(!failedWorkDirMap.isEmpty());
                        String msg = String.format("重试完成 - %d/%d 成功%s",
                                succeeded, total,
                                remaining > 0 ? "，" + remaining + " 项仍失败（可再次重试）" : "");
                        statusLabel.setText(msg);
                        statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));
                    });
                } catch (Exception ex) {
                    updateUIStatus("重试失败: " + ex.getMessage(), true);
                } finally {
                    currentIndicator = null;
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
                updateUIStatus("重试已取消", false);
            }
        });
    }

    // ================================================================
    //  查看失败项（弹窗列出失败记录 / 原因 / 可复制 svn 命令）
    // ================================================================

    /**
     * 打开"查看失败项"对话框：列出代码量统计失败的提交、失败原因，
     * 并为每条记录提供可直接复制执行的 svn diff 命令（在对应工作目录下执行）。
     */
    private void viewFailedCommits() {
        if (failedWorkDirMap.isEmpty()) {
            MyPluginMessages.showWarning("提示", "当前没有代码量统计失败的提交", project);
            return;
        }
        ApplicationManager.getApplication().invokeLater(this::showFailedCommitsDialog);
    }

    private void showFailedCommitsDialog() {
        final List<SvnCommitRecord> failed = new ArrayList<>(failedWorkDirMap.keySet());

        // 预构建每行的 svn 命令与工作目录（保持与失败项相同顺序）
        final List<String> workDirs = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        for (SvnCommitRecord r : failed) {
            String wd = failedWorkDirMap.get(r);
            String rev = r.revision.startsWith("r") ? r.revision.substring(1) : r.revision;
            List<String> mp = failedMatchingPathsMap.getOrDefault(r, Collections.emptyList());
            workDirs.add(wd);
            commands.add(buildSvnDiffCommand(wd, rev, mp));
        }

        JDialog dialog = new JDialog(
                (Frame) SwingUtilities.getWindowAncestor(viewFailedButton),
                "代码量统计失败的提交 (" + failed.size() + ")", true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.setPreferredSize(new Dimension(840, 480));

        // 顶部说明
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        JLabel tip = new JLabel("<html>以下提交代码量统计失败。复制对应 <b>svn diff</b> 命令并在其<b>工作目录</b>下手动执行，即可查看该次提交的代码变更。</html>");
        header.add(tip, BorderLayout.CENTER);
        dialog.add(header, BorderLayout.NORTH);

        // 表格
        String[] cols = {"版本", "作者", "日期", "失败原因", "SVN 命令", ""};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // 复制由鼠标事件处理，避免进入编辑态
            }
        };
        for (int i = 0; i < failed.size(); i++) {
            SvnCommitRecord r = failed.get(i);
            model.addRow(new Object[]{
                    r.revision,
                    r.author,
                    r.displayDate,
                    failedReasonMap.getOrDefault(r, "未知原因"),
                    commands.get(i),
                    "复制"
            });
        }
        JBTable table = new JBTable(model);
        table.setRowHeight(60);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setPreferredWidth(330);
        table.getColumnModel().getColumn(5).setPreferredWidth(50);
        table.getColumnModel().getColumn(4).setCellRenderer(new CommandCellRenderer());
        table.getColumnModel().getColumn(5).setCellRenderer(new ButtonRenderer());

        // 单击命令单元格或"复制"按钮即复制对应命令
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && (col == 4 || col == 5)) {
                    copyText(commands.get(row));
                }
            }
        });

        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        dialog.add(scroll, BorderLayout.CENTER);

        // 底部工具栏
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        JLabel hint = new JLabel("提示：单击命令单元格或\"复制\"按钮可复制单条命令");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        footer.add(hint, BorderLayout.WEST);
        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton copyAll = new JButton("复制全部命令");
        copyAll.setIcon(AllIcons.Actions.Copy);
        JButton close = new JButton("关闭");
        copyAll.addActionListener(e -> copyText(buildAllCommands(workDirs, commands)));
        close.addActionListener(e -> dialog.dispose());
        rightBtns.add(copyAll);
        rightBtns.add(close);
        footer.add(rightBtns, BorderLayout.EAST);
        dialog.add(footer, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    /** 构建单条 svn diff 命令（定向 diff，路径转为工作副本相对路径） */
    private String buildSvnDiffCommand(String workDir, String revision, List<String> matchingPaths) {
        StringBuilder sb = new StringBuilder("svn diff -c ").append(revision);
        if (matchingPaths != null) {
            for (String mp : matchingPaths) {
                String t = toWcRelativePath(workDir, mp);
                if (!t.isEmpty()) sb.append(" \"").append(t).append("\"");
            }
        }
        return sb.toString();
    }

    /** 构建"复制全部"命令块：按工作目录分组，附 cd 前缀，便于批量执行 */
    private static String buildAllCommands(List<String> workDirs, List<String> commands) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append("# 工作目录: ").append(workDirs.get(i)).append("\n");
            sb.append("cd \"").append(workDirs.get(i)).append("\"\n");
            sb.append(commands.get(i));
        }
        return sb.toString();
    }

    /** 复制文本到系统剪贴板并提示 */
    private void copyText(String text) {
        if (text == null || text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        MyPluginMessages.showInfo("已复制", "svn 命令已复制到剪贴板", project);
    }

    /** 命令列渲染：等宽字体 + 自动换行 */
    private static class CommandCellRenderer extends JTextArea implements TableCellRenderer {
        CommandCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setEditable(false);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            setText(value == null ? "" : value.toString());
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            return this;
        }
    }

    /** "复制"按钮列渲染 */
    private static class ButtonRenderer extends JButton implements TableCellRenderer {
        ButtonRenderer() {
            setText("复制");
            setMargin(new Insets(0, 4, 0, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            return this;
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

        // 在独立线程中读取输出，主线程轮询取消状态，避免 readLine() 阻塞导致取消无法即时响应
        final Process fp = process;
        final StringBuilder sb = xmlOutput;
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    synchronized (sb) {
                        sb.append(buf, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // 进程被 destroyForcibly 时流关闭，忽略
            }
        }, "svn-log-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // 轮询：每 100ms 检查一次取消状态与进程存活；最长等待 120 秒
        final long deadline = System.currentTimeMillis() + 120_000L;
        boolean finished = false;
        while (true) {
            if (indicator != null && indicator.isCanceled()) {
                process.destroyForcibly();
                readerThread.interrupt();
                return records;
            }
            if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                finished = true;
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                break;
            }
        }
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("SVN log 命令执行超时（120秒）");
        }
        // 等待读取线程收尾（进程已结束，剩余缓冲很快读完）
        readerThread.join(2000);

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

    /**
     * 获取工作目录在仓库中的相对路径前缀（如 {@code /branches/HaichengMes/webproj}）。
     * <p>
     * svn log -v 返回的是仓库绝对路径（如 {@code /branches/HaichengMes/webproj/xxx/Foo.java}），
     * 而 svn diff 的目标需要工作副本相对路径（如 {@code xxx/Foo.java}）。本方法通过
     * {@code svn info --xml} 读取工作副本的 relative-url（或 url - root）得到该前缀，供路径转换使用。
     * 结果按工作目录缓存；无法确定时返回空串（调用方回退到"仅去掉前导 /"的旧行为）。
     * </p>
     */
    private String getWcRepoPrefix(String workDir) {
        if (workDir == null) return "";
        String cached = wcRepoPrefixCache.get(workDir);
        if (cached != null) return cached;

        String prefix = "";
        try {
            ProcessBuilder pb = new ProcessBuilder("svn", "info", "--xml");
            pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(15, TimeUnit.SECONDS);
            String xml = sb.toString();
            // 优先使用 relative-url（svn 1.8+），形如 ^/branches/HaichengMes/webproj
            String rel = extractXmlValue(xml, "<relative-url>", "</relative-url>");
            if (rel != null && rel.startsWith("^")) {
                prefix = rel.substring(1);
            } else {
                // 兜底：url - root
                String url = extractXmlValue(xml, "<url>", "</url>");
                String root = extractXmlValue(xml, "<root>", "</root>");
                if (url != null && root != null && url.startsWith(root)) {
                    prefix = url.substring(root.length());
                }
            }
            if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        } catch (Exception ignored) {
            // svn info 失败：返回空前缀，调用方回退旧行为
        }
        wcRepoPrefixCache.put(workDir, prefix);
        return prefix;
    }

    /**
     * 将 svn log 的仓库绝对路径转换为在指定工作副本下可用的相对路径。
     * <p>
     * 例：workDir 对应仓库 {@code /branches/HaichengMes/webproj}，
     * repoPath = {@code /branches/HaichengMes/webproj/xxx/Foo.java} → 返回 {@code xxx/Foo.java}。
     * 若前缀无法确定或不匹配，回退到"仅去掉前导 /"的旧行为。
     * </p>
     */
    private String toWcRelativePath(String workDir, String repoPath) {
        if (repoPath == null) return "";
        String prefix = getWcRepoPrefix(workDir);
        if (!prefix.isEmpty()) {
            if (repoPath.startsWith(prefix + "/")) {
                return repoPath.substring(prefix.length() + 1);
            }
            if (repoPath.equals(prefix)) {
                return "";
            }
        }
        return repoPath.startsWith("/") ? repoPath.substring(1) : repoPath;
    }

    /**
     * 解析设置中“排除作者”列表为小写作者名集合（逗号/分号/空格/换行分隔，忽略大小写）。
     * 用于统计时忽略指定作者的提交。
     */
    private Set<String> parseExcludeAuthors() {
        Set<String> set = new LinkedHashSet<>();
        String raw = AppSettingsState.getInstance().svnExcludeAuthors;
        if (raw == null || raw.trim().isEmpty()) return set;
        for (String part : raw.split("[,;\\s]+")) {
            String a = part.trim();
            if (!a.isEmpty()) set.add(a.toLowerCase());
        }
        return set;
    }

    /**
     * 将统计表格单元格的值解析为数值，用于按数值大小排序。
     * 支持整数、浮点、百分比（去除结尾 %），以及 "-"、空值（视为最小值）。
     */
    private static double parseNumericCell(Object value) {
        if (value == null) return Double.NEGATIVE_INFINITY;
        if (value instanceof Number) return ((Number) value).doubleValue();
        String s = value.toString().trim();
        if (s.isEmpty() || "-".equals(s)) return Double.NEGATIVE_INFINITY;
        if (s.endsWith("%")) s = s.substring(0, s.length() - 1).trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NEGATIVE_INFINITY;
        }
    }

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
     * 优化（A/B/C/D/E）：
     * - B 流式解析：reader 线程边读边统计，避免整段 diff 缓冲进内存导致大提交 OOM。
     * - C 重试：超时/命令失败自动重试，缓解网络抖动导致的"全部失败"。
     * - D 定向 diff：仅对 matchingPaths 中匹配 .java / *Mapper.xml 的文件做 svn diff，
     *     大幅减小 diff 体积与服务器负担；若定向 diff 未命中匹配文件（目标路径与 WC 不匹配）
     *     或失败，则降级回全量 diff。
     * - D2 分批累加：当匹配文件数超过 DIFF_BATCH_SIZE（默认 10）时，拆分为多批分别 svn diff，
     *     各批新增行累加得到总代码量；单批失败仅重试该批，避免单条命令文件过多而超时。
     * - E 超时配合重试（MAX_ATTEMPTS 次）；超时阈值由调用方传入（默认 3s），并按批内文件数自适应放大
     *     （每个文件 +1s，上限 60s 或“配置阈值×10”），避免大提交反复超时；失败项可由"重试失败项"按钮批量重跑。
     * - A 由调用方（doPerformAnalysis Step 3 / retryFailedCommits）通过线程池并行调度。
     *
     * @param matchingPaths 该提交中匹配代码量统计范围的仓库相对路径（如 /trunk/src/Foo.java），
     *                      可为空（调用方已预筛，通常为非空）
     * @return 新增代码行数，失败/取消返回 -1（需与 0 区分，0=无匹配文件的提交/无新增行）
     */
    private CodeVolumeResult computeCommitCodeVolume(String workDir, String revision,
                                                      List<String> matchingPaths, long timeoutSeconds,
                                                      ProgressIndicator indicator) {
        // D: 定向 diff —— 仅对匹配文件做 svn diff，减小体积与服务器负担
        // E: 超时配合重试，失败项可由"重试失败项"按钮批量重跑（超时阈值由调用方传入，默认 3s）
        final int MAX_ATTEMPTS = 3;            // 首次 + 重试两次
        final int DIFF_BATCH_SIZE = 10;        // 单次 svn diff 最多携带的文件数，超过则分批累加
        CodeVolumeResult result = new CodeVolumeResult();

        // 构建定向 diff 目标：将 svn log 的仓库绝对路径转为工作副本相对路径
        // （如 /branches/HaichengMes/webproj/xxx/Foo.java → xxx/Foo.java），
        // 否则会因 WC 下不存在 branches/... 目录而报 E155010 node not found。
        List<String> targets = new ArrayList<>();
        if (matchingPaths != null) {
            for (String mp : matchingPaths) {
                String t = toWcRelativePath(workDir, mp);
                if (!t.isEmpty()) targets.add(t);
            }
        }

        List<String> fullCmd = Arrays.asList("svn", "diff", "-c", revision);

        if (targets.isEmpty()) {
            // 无匹配文件（理论上不会发生，因调用方已预筛）→ 全量 diff
            DiffResult f = runDiff(fullCmd, workDir, timeoutSeconds, indicator);
            if (f.canceled) { result.volume = -1; result.reason = "任务已取消"; return result; }
            if (f.exitOk) { result.volume = f.addedLines; return result; }
            result.volume = -1; result.reason = buildDiffFailReason(f, timeoutSeconds, MAX_ATTEMPTS);
            return result;
        }

        // 文件较多时拆分为多批，每批单独 svn diff 后累加新增行。
        // 这样单条命令携带的文件数可控，配合每批自适应超时，避免大提交在固定阈值下反复超时；
        // 单批失败仅重试该批，不影响其他批的累加结果。
        int totalAdded = 0;
        boolean anyMatching = false;
        boolean anyBatchFailed = false;
        String batchFailReason = "";
        for (int start = 0; start < targets.size(); start += DIFF_BATCH_SIZE) {
            if (indicator != null && indicator.isCanceled()) {
                result.volume = -1; result.reason = "任务已取消"; return result;
            }
            List<String> batch = targets.subList(start, Math.min(start + DIFF_BATCH_SIZE, targets.size()));
            List<String> batchCmd = new ArrayList<>();
            batchCmd.add("svn");
            batchCmd.add("diff");
            batchCmd.add("-c");
            batchCmd.add(revision);
            batchCmd.addAll(batch);
            // 单批超时按批内文件数自适应放大（每文件 +1s，上限 60s 或“配置阈值×10”）
            long batchTimeout = Math.min(timeoutSeconds + batch.size(), Math.max(60L, timeoutSeconds * 10));

            DiffResult br = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                br = runDiff(batchCmd, workDir, batchTimeout, indicator);
                if (br.canceled) { result.volume = -1; result.reason = "任务已取消"; return result; }
                if (br.exitOk) break;
            }
            if (br == null || !br.exitOk) {
                // 该批失败：记录原因，整体判失败交由“重试失败项”重跑（避免部分累加导致代码量低估）
                anyBatchFailed = true;
                batchFailReason = buildDiffFailReason(br, batchTimeout, MAX_ATTEMPTS);
                break;
            }
            totalAdded += br.addedLines;
            if (br.sawMatchingFile) anyMatching = true;
        }

        if (anyBatchFailed) {
            result.volume = -1;
            result.reason = batchFailReason;
            return result;
        }
        if (anyMatching) {
            result.volume = totalAdded; // 各批新增行累加
            return result;
        }

        // 所有批均未命中匹配文件（目标路径可能与 WC 不匹配）→ 降级全量 diff 兜底（保持旧行为）
        long fallbackTimeout = Math.min(timeoutSeconds + targets.size(), Math.max(60L, timeoutSeconds * 10));
        DiffResult f = runDiff(fullCmd, workDir, fallbackTimeout, indicator);
        if (f.canceled) { result.volume = -1; result.reason = "任务已取消"; return result; }
        if (f.exitOk) { result.volume = f.addedLines; return result; }
        result.volume = -1; result.reason = buildDiffFailReason(f, fallbackTimeout, MAX_ATTEMPTS);
        return result;
    }

    /** 根据单次 diff 结果构建可读的失败原因 */
    private static String buildDiffFailReason(DiffResult r, long timeoutSeconds, int maxAttempts) {
        if (r.startFailed) return "无法启动 svn 进程（请确认 svn 已安装并在系统 PATH 中）";
        if (r.timedOut) return "svn diff 超时（单条 " + timeoutSeconds + "s，已重试 " + (maxAttempts - 1) + " 次）";
        return "svn diff 命令失败（exit code=" + r.exitValue + "）";
    }

    /** svn diff 单次执行的结果载体 */
    private static class DiffResult {
        int addedLines = 0;          // 匹配文件的新增行数
        boolean sawMatchingFile = false; // 是否解析到至少一个匹配文件的 Index 段
        boolean exitOk = false;      // 命令是否正常退出（exit code == 0）
        boolean canceled = false;    // 是否被取消
        boolean timedOut = false;    // 是否因超时未退出
        boolean startFailed = false; // 进程是否启动失败
        int exitValue = -1;          // 实际退出码（超时/启动失败时为 -1）
    }

    /** 单次提交代码量统计结果（含失败原因，供"查看失败项"展示） */
    private static class CodeVolumeResult {
        int volume = 0;      // 新增代码行数；失败/取消为 -1
        String reason = "";  // 失败原因（成功时为空）
    }

    /**
     * 执行一次 svn diff 命令并流式统计匹配文件的新增行数
     *
     * @return 命令结果（见 {@link DiffResult}）；超时/取消时 exitOk=false
     */
    private DiffResult runDiff(List<String> cmd, String workDir, long timeoutSeconds,
                               ProgressIndicator indicator) {
        DiffResult res = new DiffResult();

        final Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (Exception e) {
            res.canceled = (indicator != null && indicator.isCanceled());
            res.startFailed = true;
            return res; // 进程启动失败
        }

        // 流式解析：reader 线程边读边统计匹配文件的新增行，避免整段缓冲导致 OOM
        final AtomicInteger addedLines = new AtomicInteger(0);
        final AtomicBoolean sawMatchingFile = new AtomicBoolean(false);
        final AtomicBoolean canceled = new AtomicBoolean(false);

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean inMatchingFile = false;
                while ((line = reader.readLine()) != null) {
                    if (indicator != null && indicator.isCanceled()) { canceled.set(true); break; }
                    if (line.startsWith("Index: ")) {
                        String filePath = line.substring(7).trim();
                        inMatchingFile = matchesCodeVolumePattern(filePath);
                        if (inMatchingFile) sawMatchingFile.set(true);
                    } else if (inMatchingFile) {
                        // 跳过 diff 元信息行
                        if (line.startsWith("===") || line.startsWith("---")
                                || line.startsWith("+++") || line.startsWith("@@")) {
                            continue;
                        }
                        // 跳过属性变更头部
                        if (line.startsWith("Property changes on:") || line.startsWith("Modified:")
                                || line.startsWith("Added:") || line.startsWith("Deleted:")
                                || line.startsWith("___")) {
                            continue;
                        }
                        // 只统计新增行（+开头），跳过纯 + 号空行
                        if (line.startsWith("+") && line.length() > 1) {
                            addedLines.incrementAndGet();
                        }
                    }
                }
            } catch (Exception ignored) {
                // 进程被 destroyForcibly 时流中断属正常情况
            }
        }, "svn-diff-reader-" + String.join("_", cmd).hashCode());
        readerThread.setDaemon(true);
        readerThread.start();

        // 轮询等待：每 100ms 检查一次取消/超时，取消时立即 destroy 进程，避免 waitFor 阻塞整个超时
        boolean finished = false;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (true) {
                if (indicator != null && indicator.isCanceled()) {
                    process.destroyForcibly();
                    res.canceled = true;
                    try { readerThread.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    return res;
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs <= 0) break;
                finished = process.waitFor(Math.min(remainingMs, 100), TimeUnit.MILLISECONDS);
                if (finished) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            res.canceled = true;
            return res;
        }
        if (!finished) {
            process.destroyForcibly();
            res.timedOut = true;
            try { readerThread.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return res; // 超时：exitOk 保持 false
        }

        try { readerThread.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        if (canceled.get()) { res.canceled = true; return res; }
        res.exitValue = process.exitValue();
        res.exitOk = (res.exitValue == 0);
        res.addedLines = addedLines.get();
        res.sawMatchingFile = sawMatchingFile.get();
        return res;
    }

    // ================================================================
    //  统计分析
    // ================================================================

    private Map<String, AuthorStats> computeAuthorStats(List<SvnCommitRecord> records) {
        Map<String, AuthorStats> map = new LinkedHashMap<>();
        int totalCommits = records.size();
        int totalCodeVolume = 0;
        for (SvnCommitRecord r : records) {
            AuthorStats stats = map.computeIfAbsent(r.author, k -> new AuthorStats(k));
            stats.commitCount++;
            stats.totalChangedFiles += r.changedFileCount;
            stats.totalCodeVolume += Math.max(0, r.codeVolume); // 失败项（-1）不计入总量
            totalCodeVolume += Math.max(0, r.codeVolume); // 失败项（-1）不计入总量
            if (r.projectName != null && !r.projectName.isEmpty()) {
                stats.projectNames.add(r.projectName);
            }
        }
        // 计算占比（提交次数占比 + 代码量占比）
        for (AuthorStats s : map.values()) {
            s.percentage = totalCommits > 0
                    ? String.format("%.1f%%", 100.0 * s.commitCount / totalCommits)
                    : "0%";
            s.volumePercentage = totalCodeVolume > 0
                    ? String.format("%.1f%%", 100.0 * s.totalCodeVolume / totalCodeVolume)
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
                    int value = useVolume ? Math.max(0, r.codeVolume) : 1; // 失败项（-1）不计入图表
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
            int value = useVolume ? Math.max(0, r.codeVolume) : 1; // 失败项（-1）不计入图表
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
                    s.percentage, s.volumePercentage
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
            sb.append("| 作者 | 涉及项目 | 提交次数 | 变更文件总数 | 代码量 | 提交次数占比 | 代码量占比 |\n");
            sb.append("|------|---------|---------|-------------|--------|------------|----------|\n");
            for (AuthorStats s : displayedStats.values()) {
                String projects = s.projectNames != null && !s.projectNames.isEmpty()
                        ? String.join(", ", s.projectNames) : "-";
                String volStr = s.totalCodeVolume > 0 ? String.valueOf(s.totalCodeVolume) : "-";
                sb.append(String.format("| %s | %s | %d | %d | %s | %s | %s |\n",
                        s.author, projects, s.commitCount, s.totalChangedFiles, volStr, s.percentage, s.volumePercentage));
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
        public String percentage = "0%";          // 提交次数占比
        public String volumePercentage = "0%";    // 代码量占比
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

        /**
         * 是否允许切换"按代码量"指标。
         * 当未统计代码量时禁用该选项并强制回到"按提交次数"，避免图表全 0 的误导。
         */
        public void setVolumeEnabled(boolean enabled) {
            metricCombo.setEnabled(enabled);
            if (!enabled) {
                metricCombo.setSelectedIndex(0);
                useVolume = false;
                applyCurrentMetric();
            }
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
