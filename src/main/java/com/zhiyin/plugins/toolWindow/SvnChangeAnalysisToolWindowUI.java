package com.zhiyin.plugins.toolWindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.zhiyin.plugins.notification.MyPluginMessages;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * SVN 变更分析 ToolWindow UI
 * 输入基准日期和 SVN 路径，分析各模块的文件变更数量及提交时间范围
 */
public class SvnChangeAnalysisToolWindowUI {

    private final Project project;
    private final ToolWindow toolWindow;

    private final JTextField dateField = new JTextField(10);
    private final JTextField pathField = new JTextField(20);
    private final JButton datePickerButton = new JButton("📅");
    private final JPanel datePanel = new JPanel(new BorderLayout());
    private final JButton pathChooserButton = new JButton("📂");
    private final JPanel pathPanel = new JPanel(new BorderLayout());
    private final JButton analyzeButton = new JButton("分析变更");
    private final JTextArea statusArea = new JTextArea("就绪");

    private final String[] columnNames = {"模块", "变更文件数", "最早提交", "最晚提交"};
    private final DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 1) return Integer.class;
            return String.class;
        }
    };
    private final JBTable resultTable = new JBTable(tableModel);

    public SvnChangeAnalysisToolWindowUI(Project project, ToolWindow toolWindow) {
        this.project = project;
        this.toolWindow = toolWindow;
        initUI();
    }

    private void initUI() {
        // 默认日期：今天
        dateField.setText(new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        pathField.setText("springboot/app/");

        analyzeButton.setIcon(AllIcons.Actions.Execute);

        // 表格排序支持
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        resultTable.setRowSorter(sorter);
        resultTable.setFillsViewportHeight(true);
        resultTable.setCellSelectionEnabled(true);

        // Ctrl+C 复制表格选中内容
        resultTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copy");
        resultTable.getActionMap().put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                StringBuilder sb = new StringBuilder();
                int[] rows = resultTable.getSelectedRows();
                int[] cols = resultTable.getSelectedColumns();
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < cols.length; c++) {
                        if (c > 0) sb.append('\t');
                        Object val = resultTable.getValueAt(rows[r], cols[c]);
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

        // 状态栏配置
        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setBackground(UIManager.getColor("Label.background"));
        statusArea.setForeground(UIManager.getColor("Label.disabledForeground"));

        // 按钮事件
        analyzeButton.addActionListener(e -> performAnalysis());
        dateField.addActionListener(e -> performAnalysis());
        pathField.addActionListener(e -> performAnalysis());

        datePickerButton.setToolTipText("选择日期");
        datePickerButton.setMargin(new Insets(0, 4, 0, 4));
        datePanel.add(dateField, BorderLayout.CENTER);
        datePanel.add(datePickerButton, BorderLayout.EAST);
        datePickerButton.addActionListener(e -> showDatePicker());

        // 防止日期面板和输入框被压缩
        dateField.setMinimumSize(new Dimension(80, dateField.getPreferredSize().height));
        datePanel.setMinimumSize(new Dimension(150, dateField.getPreferredSize().height));

        // 路径选择面板
        pathChooserButton.setToolTipText("选择路径");
        pathChooserButton.setMargin(new Insets(0, 4, 0, 4));
        pathPanel.add(pathField, BorderLayout.CENTER);
        pathPanel.add(pathChooserButton, BorderLayout.EAST);
        pathChooserButton.addActionListener(e -> showPathChooser());
        pathField.setMinimumSize(new Dimension(80, pathField.getPreferredSize().height));
        pathPanel.setMinimumSize(new Dimension(150, pathField.getPreferredSize().height));
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd").parse(dateField.getText().trim());
            if (d != null) cal.setTime(d);
        } catch (Exception ignored) {}

        // 保存当前选中的日期用于高亮
        final int selectedYear = cal.get(Calendar.YEAR);
        final int selectedMonth = cal.get(Calendar.MONTH);
        final int selectedDay = cal.get(Calendar.DAY_OF_MONTH);

        JDialog dialog = new JDialog(
                (Frame) SwingUtilities.getWindowAncestor(dateField), "选择日期", true);
        dialog.setLayout(new BorderLayout());
        dialog.setResizable(false);

        // 月份导航
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

        // 日历网格
        JPanel grid = new JPanel(new GridLayout(0, 7, 1, 1));
        String[] dayHeaders = {"日", "一", "二", "三", "四", "五", "六"};

        Runnable refresh = () -> {
            grid.removeAll();
            monthLabel.setText(monthFmt.format(cal.getTime()));
            for (String h : dayHeaders) {
                JLabel lbl = new JLabel(h, SwingConstants.CENTER);
                lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 10f));
                grid.add(lbl);
            }
            Calendar tmp = (Calendar) cal.clone();
            tmp.set(Calendar.DAY_OF_MONTH, 1);
            int firstDay = tmp.get(Calendar.DAY_OF_WEEK) - 1; // 0=周日
            int maxDay = tmp.getActualMaximum(Calendar.DAY_OF_MONTH);

            boolean isSelectedMonth = cal.get(Calendar.YEAR) == selectedYear
                                   && cal.get(Calendar.MONTH) == selectedMonth;

            for (int i = 0; i < firstDay; i++) grid.add(new JLabel());

            for (int day = 1; day <= maxDay; day++) {
                JButton dayBtn = new JButton(String.valueOf(day));
                dayBtn.setMargin(new Insets(0, 2, 0, 2));
                if (isSelectedMonth && day == selectedDay) {
                    dayBtn.setBackground(new Color(0x2675BF));
                    dayBtn.setForeground(Color.WHITE);
                    dayBtn.setFont(dayBtn.getFont().deriveFont(Font.BOLD));
                    dayBtn.setOpaque(true);
                    dayBtn.setBorderPainted(false);
                }
                int d = day;
                dayBtn.addActionListener(ev -> {
                    cal.set(Calendar.DAY_OF_MONTH, d);
                    dateField.setText(new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime()));
                    dialog.dispose();
                    performAnalysis();
                });
                grid.add(dayBtn);
            }
            dialog.pack();
        };

        prevBtn.addActionListener(e -> { cal.add(Calendar.MONTH, -1); refresh.run(); });
        nextBtn.addActionListener(e -> { cal.add(Calendar.MONTH, 1); refresh.run(); });

        dialog.add(navPanel, BorderLayout.NORTH);
        dialog.add(grid, BorderLayout.CENTER);
        refresh.run();
        dialog.setLocationRelativeTo(dateField);
        dialog.setVisible(true);
    }

    private void showPathChooser() {
        String basePath = project.getBasePath();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("选择 SVN 路径");
        if (basePath != null) {
            java.io.File currentDir = new java.io.File(basePath, pathField.getText().trim());
            if (currentDir.exists()) {
                chooser.setCurrentDirectory(currentDir);
            } else {
                chooser.setCurrentDirectory(new java.io.File(basePath));
            }
        }
        if (chooser.showOpenDialog(pathField) == JFileChooser.APPROVE_OPTION) {
            java.io.File selected = chooser.getSelectedFile();
            if (basePath != null) {
                String relative = selected.getAbsolutePath()
                        .replace('\\', '/')
                        .replace(basePath.replace('\\', '/'), "")
                        .replaceFirst("^/+", "");
                pathField.setText(relative.isEmpty() ? "." : relative);
            } else {
                pathField.setText(selected.getAbsolutePath());
            }
            performAnalysis();
        }
    }

    public JComponent getContent() {
        JPanel root = new JPanel(new BorderLayout(0, 3));

        // ---- 输入区域 ----
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 3, 2, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;

        // Row 0: [基准日期:] [dateField+📅 ...fill...]
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        inputPanel.add(new JLabel("基准日期:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        inputPanel.add(datePanel, gbc);

        // Row 1: [SVN路径:] [pathField ...stretch...]
        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.insets = new Insets(2, 3, 2, 3);
        inputPanel.add(new JLabel("SVN路径:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        inputPanel.add(pathPanel, gbc);

        // Row 2: [分析变更]
        gbc.gridy = 2;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.insets = new Insets(2, 3, 2, 3);
        inputPanel.add(analyzeButton, gbc);

        // ---- 结果表格 ----
        JBScrollPane scrollPane = new JBScrollPane(resultTable);

        // ---- 状态栏 ----
        JBScrollPane statusScrollPane = new JBScrollPane(statusArea);
        statusScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        statusScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        statusScrollPane.setPreferredSize(new Dimension(0, 320));
        statusScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 700));
        statusScrollPane.setBorder(BorderFactory.createEmptyBorder());

        root.add(inputPanel, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        root.add(statusScrollPane, BorderLayout.SOUTH);

        SimpleToolWindowPanel panel = new SimpleToolWindowPanel(true, false);
        panel.setContent(root);
        return panel;
    }

    private void performAnalysis() {
        String date = dateField.getText().trim();
        String svnPath = pathField.getText().trim();

        if (date.isEmpty() || svnPath.isEmpty()) {
            MyPluginMessages.showWarning("参数错误", "请填写基准日期和SVN路径", project);
            return;
        }

        tableModel.setRowCount(0);
        statusArea.setText("分析中...");
        analyzeButton.setEnabled(false);

        new Thread(() -> {
            try {
                String basePath = project.getBasePath();
                if (basePath == null) {
                    updateStatus("错误: 无法获取项目根路径", true);
                    return;
                }

                // Step 1: svn diff --summarize 获取变更文件列表
                Map<String, Integer> moduleFileCount = new LinkedHashMap<>();
                ProcessBuilder pb = new ProcessBuilder(
                        "svn", "diff", "--summarize",
                        "-r", "{" + date + "}:HEAD",
                        svnPath
                );
                pb.directory(new java.io.File(basePath));
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.length() < 2) continue;
                        // 格式: "M       springboot/app/moduleName/..."
                        String filePath = line.substring(1).trim();
                        filePath = filePath.replace('\\', '/');
                        String[] parts = filePath.split("/");
                        if (parts.length >= 3) {
                            String module = parts[2];
                            moduleFileCount.merge(module, 1, Integer::sum);
                        }
                    }
                }
                process.waitFor(60, TimeUnit.SECONDS);

                if (moduleFileCount.isEmpty()) {
                    updateStatus("无变更 - 过去时间段内 " + svnPath + " 下没有文件变更", true);
                    return;
                }

                // Step 2: 对每个模块执行 svn log 获取时间范围
                List<Object[]> rows = new ArrayList<>();
                int completed = 0;
                for (Map.Entry<String, Integer> entry : moduleFileCount.entrySet()) {
                    String module = entry.getKey();
                    int count = entry.getValue();
                    String normalizedPath = svnPath.replaceAll("/+$", "") + "/" + module;
                    String[] timeRange = getModuleTimeRange(basePath, date, normalizedPath);
                    rows.add(new Object[]{module, count, timeRange[0], timeRange[1]});

                    completed++;
                    final int progress = completed;
                    SwingUtilities.invokeLater(() ->
                            statusArea.setText("分析中... (" + progress + "/" + moduleFileCount.size() + ")"));
                }

                // Step 3: 按文件数降序排序后更新UI
                rows.sort((a, b) -> Integer.compare((int) b[1], (int) a[1]));

                SwingUtilities.invokeLater(() -> {
                    for (Object[] row : rows) {
                        tableModel.addRow(row);
                    }
                    StringBuilder modules = new StringBuilder();
                    for (Object[] row : rows) {
                        modules.append('\n').append(row[0]);
                    }
                    statusArea.setText("完成 - 共 " + rows.size() + " 个模块有变更" + modules);
                    analyzeButton.setEnabled(true);
                    autoResizeColumns();
                });

            } catch (Exception e) {
                updateStatus("错误: " + e.getMessage(), true);
            }
        }).start();
    }

    /**
     * 获取指定模块在时间范围内的最早和最晚提交时间
     * @return [earliest, latest] 格式为 "MM-dd HH:mm"
     */
    private String[] getModuleTimeRange(String basePath, String date, String modulePath) throws Exception {
        ProcessBuilder logPb = new ProcessBuilder(
                "svn", "log", "-r", "{" + date + "}:HEAD",
                "-q", "--limit", "1000",
                modulePath
        );
        logPb.directory(new java.io.File(basePath));
        logPb.redirectErrorStream(true);
        Process logProcess = logPb.start();

        List<Date> dates = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(logProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // 格式: "r12345 | user | 2026-05-26 10:30:00 +0800 (Wed, 26 May 2026)"
                if (line.startsWith("r") && line.contains("|")) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 3) {
                        String dateStr = parts[2].trim();
                        int endIndex = Math.min(dateStr.length(), 19); // "yyyy-MM-dd HH:mm:ss"
                        dateStr = dateStr.substring(0, endIndex);
                        try {
                            Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateStr);
                            dates.add(d);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        logProcess.waitFor(30, TimeUnit.SECONDS);

        String earliest = "-";
        String latest = "-";
        if (!dates.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
            dates.sort(Comparator.naturalOrder());
            earliest = sdf.format(dates.get(0));
            latest = sdf.format(dates.get(dates.size() - 1));
        }
        return new String[]{earliest, latest};
    }

    private void autoResizeColumns() {
        FontMetrics fm = resultTable.getFontMetrics(resultTable.getFont());
        int tableWidth = resultTable.getParent().getWidth();
        // 固定列数
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        for (int col = 0; col < resultTable.getColumnCount(); col++) {
            int maxWidth = fm.stringWidth(resultTable.getColumnName(col)) + 20;

            for (int row = 0; row < resultTable.getRowCount(); row++) {
                Object val = resultTable.getValueAt(row, col);
                if (val != null) {
                    int w = fm.stringWidth(val.toString()) + 20;
                    if (w > maxWidth) maxWidth = w;
                }
            }

            // 限制最大宽度，留空间给其他列
            int limit = tableWidth - (resultTable.getColumnCount() - col) * 60;
            if (maxWidth > limit) maxWidth = Math.max(limit, 80);

            resultTable.getColumnModel().getColumn(col).setPreferredWidth(maxWidth);
        }
    }

    private void updateStatus(String msg, boolean isError) {
        SwingUtilities.invokeLater(() -> {
            statusArea.setText(msg);
            analyzeButton.setEnabled(true);
            if (isError) {
                MyPluginMessages.showError("SVN 变更分析", msg, project);
            }
        });
    }
}
