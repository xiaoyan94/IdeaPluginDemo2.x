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
    private final JButton analyzeButton = new JButton("分析变更");
    private final JLabel statusLabel = new JLabel("就绪");

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
        // 默认日期：前一天
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1);
        dateField.setText(new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime()));
        pathField.setText("springboot/app/");

        analyzeButton.setIcon(AllIcons.Actions.Execute);

        // 表格排序支持
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        resultTable.setRowSorter(sorter);
        resultTable.setFillsViewportHeight(true);

        // 按钮事件
        analyzeButton.addActionListener(e -> performAnalysis());
        dateField.addActionListener(e -> performAnalysis());
        pathField.addActionListener(e -> performAnalysis());
    }

    public JComponent getContent() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        // ---- 输入区域 ----
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        inputPanel.add(new JLabel("基准日期:"));
        inputPanel.add(dateField);
        inputPanel.add(Box.createHorizontalStrut(10));
        inputPanel.add(new JLabel("SVN路径:"));
        inputPanel.add(pathField);
        inputPanel.add(Box.createHorizontalStrut(10));
        inputPanel.add(analyzeButton);

        // ---- 结果表格 ----
        JBScrollPane scrollPane = new JBScrollPane(resultTable);

        // ---- 状态栏 ----
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusPanel.add(statusLabel);

        root.add(inputPanel);
        root.add(Box.createVerticalStrut(5));
        root.add(scrollPane);
        root.add(Box.createVerticalStrut(3));
        root.add(statusPanel);

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
        statusLabel.setText("分析中...");
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
                    String[] timeRange = getModuleTimeRange(basePath, date, svnPath + module);
                    rows.add(new Object[]{module, count, timeRange[0], timeRange[1]});

                    completed++;
                    final int progress = completed;
                    SwingUtilities.invokeLater(() ->
                            statusLabel.setText("分析中... (" + progress + "/" + moduleFileCount.size() + ")"));
                }

                // Step 3: 按文件数降序排序后更新UI
                rows.sort((a, b) -> Integer.compare((int) b[1], (int) a[1]));

                SwingUtilities.invokeLater(() -> {
                    for (Object[] row : rows) {
                        tableModel.addRow(row);
                    }
                    statusLabel.setText("完成 - 共 " + rows.size() + " 个模块有变更");
                    analyzeButton.setEnabled(true);
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

    private void updateStatus(String msg, boolean isError) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(msg);
            analyzeButton.setEnabled(true);
            if (isError) {
                MyPluginMessages.showError("SVN 变更分析", msg, project);
            }
        });
    }
}
