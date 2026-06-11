package com.zhiyin.plugins.actions;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.CommonJavaRunConfigurationParameters;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class AddVmOptionsAction extends AnAction {

    // ─────────────────────────────────────────────────────────────────────────
    // 预设参数分组
    // ─────────────────────────────────────────────────────────────────────────

    /** 每组：{ 显示名, 参数值, 互斥前缀（同前缀的会被覆盖） } */
    private static final String[][] PRESETS = {
            // 堆内存 - 最小
            {"Xms128m",  "-Xms128m",  "-Xms"},
            {"Xms256m",  "-Xms256m",  "-Xms"},
            {"Xms512m",  "-Xms512m",  "-Xms"},
            {"Xms1g",    "-Xms1g",    "-Xms"},
            {"Xms2g",    "-Xms2g",    "-Xms"},
            // 堆内存 - 最大
            {"Xmx512m",  "-Xmx512m",  "-Xmx"},
            {"Xmx1g",    "-Xmx1g",    "-Xmx"},
            {"Xmx2g",    "-Xmx2g",    "-Xmx"},
            {"Xmx4g",    "-Xmx4g",    "-Xmx"},
            {"Xmx8g",    "-Xmx8g",    "-Xmx"},
            // 线程栈
            {"Xss256k",  "-Xss256k",  "-Xss"},
            {"Xss512k",  "-Xss512k",  "-Xss"},
            {"Xss1m",    "-Xss1m",    "-Xss"},
            // GC
            {"G1GC",     "-XX:+UseG1GC",          "-XX:+Use"},
            {"ZGC",      "-XX:+UseZGC",            "-XX:+Use"},
            {"ShenGC",   "-XX:+UseShenandoahGC",   "-XX:+Use"},
            {"SerialGC", "-XX:+UseSerialGC",       "-XX:+Use"},
            // 代码缓存
            {"CodeCache256m", "-XX:ReservedCodeCacheSize=256m", "-XX:ReservedCodeCacheSize="},
            {"CodeCache512m", "-XX:ReservedCodeCacheSize=512m", "-XX:ReservedCodeCacheSize="},
            // 断言
            {"-ea (启用断言)",  "-ea",  "-ea"},
            {"-da (禁用断言)",  "-da",  "-da"},
            // 远程调试
            {"Debug 5005", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-agentlib:jdwp"},
            {"Debug 5006", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006", "-agentlib:jdwp"},
            // GC 日志
            {"GC Log",   "-Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=20m", "-Xlog:gc"},
            // OOM dump
            {"HeapDump on OOM", "-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump.hprof", "-XX:+HeapDumpOnOutOfMemoryError"},
            // 编码
            {"UTF-8",    "-Dfile.encoding=UTF-8",  "-Dfile.encoding="},
            // 时区
            {"Asia/Shanghai", "-Duser.timezone=Asia/Shanghai", "-Duser.timezone="},
            // Spring
            {"Spring Dev",   "-Dspring.profiles.active=dev",   "-Dspring.profiles.active="},
            {"Spring Test",  "-Dspring.profiles.active=test",  "-Dspring.profiles.active="},
            {"Spring Prod",  "-Dspring.profiles.active=prod",  "-Dspring.profiles.active="},
            // Spring MVC 端口
            {"Port 8080",  "-Dserver.port=8080",  "-Dserver.port="},
            {"Port 8081",  "-Dserver.port=8081",  "-Dserver.port="},
            {"Port 8090",  "-Dserver.port=8090",  "-Dserver.port="},
            {"Port 9000",  "-Dserver.port=9000",  "-Dserver.port="},
            {"Port 9090",  "-Dserver.port=9090",  "-Dserver.port="},
    };

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings contextSettings = tryResolveFromContext(e, runManager);

        List<RunnerAndConfigurationSettings> allJavaSettings = runManager.getAllSettings()
                                                                         .stream()
                                                                         .filter(s -> s.getConfiguration() instanceof CommonJavaRunConfigurationParameters)
                                                                         .collect(Collectors.toList());

        if (allJavaSettings.isEmpty()) {
            Messages.showWarningDialog(project, "没有找到任何 Java/Kotlin 运行配置。", "未找到配置");
            return;
        }

        // 第一步：多选配置
        ConfigSelectDialog selectDialog = new ConfigSelectDialog(project, allJavaSettings, contextSettings);
        if (!selectDialog.showAndGet()) return;

        List<RunnerAndConfigurationSettings> selected = selectDialog.getSelectedSettings();
        if (selected.isEmpty()) {
            Messages.showWarningDialog(project, "未选择任何配置。", "提示");
            return;
        }

        // 第二步：编辑 VM 参数（取第一个选中配置的当前值作为初始值）
        String firstCurrent = ((CommonJavaRunConfigurationParameters)
                selected.get(0).getConfiguration()).getVMParameters();
        firstCurrent = firstCurrent == null ? "" : firstCurrent;

        String dialogTitle = selected.size() == 1
                ? selected.get(0).getConfiguration().getName()
                : selected.get(0).getConfiguration().getName() + " 等 " + selected.size() + " 个配置";

        VmOptionsDialog vmDialog = new VmOptionsDialog(project, dialogTitle, firstCurrent);
        if (!vmDialog.showAndGet()) return;

        String newVmOptions = vmDialog.getVmOptions().trim();

        // 第三步：批量写入
        ApplicationManager.getApplication().invokeLater(() -> {
            for (RunnerAndConfigurationSettings settings : selected) {
                CommonJavaRunConfigurationParameters javaConfig =
                        (CommonJavaRunConfigurationParameters) settings.getConfiguration();
                javaConfig.setVMParameters(newVmOptions);
                runManager.makeStable(settings);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VM 参数合并 / toggle 逻辑
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 检查 existing 中是否已包含 toAdd 的所有 token（精确匹配）。
     */
    static boolean containsOption(String existing, String toAdd) {
        List<String> existingTokens = tokenize(existing);
        List<String> addTokens = tokenize(toAdd);
        return existingTokens.containsAll(addTokens);
    }

    /**
     * 移除 existing 中与 toRemove 完全匹配的 token。
     */
    static String removeVmOption(String existing, String toRemove) {
        if (existing == null) return "";
        List<String> tokens = tokenize(existing);
        List<String> removeTokens = tokenize(toRemove);
        tokens.removeAll(removeTokens);
        return tokens.stream().filter(t -> !t.isBlank()).collect(Collectors.joining(" "));
    }

    /**
     * Toggle：已存在则移除，不存在则合并（相同互斥前缀的覆盖，否则追加）。
     */
    static String toggleVmOption(String existing, String toAdd, String mutexPrefix) {
        if (containsOption(existing, toAdd)) {
            // 已存在 → 移除
            return removeVmOption(existing, toAdd);
        }
        // 不存在 → 覆盖同前缀 or 追加
        if (existing == null) existing = "";
        existing = existing.trim();

        List<String> tokens = tokenize(existing);
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).startsWith(mutexPrefix)) {
                tokens.set(i, "__REPLACED__");
                break;
            }
        }
        tokens.removeIf(t -> t.equals("__REPLACED__"));
        tokens.addAll(tokenize(toAdd));
        return tokens.stream().filter(t -> !t.isBlank()).collect(Collectors.joining(" "));
    }

    /** 简易分词：按空格切，但 -agentlib:... 这种带 = 的不会被切断 */
    private static List<String> tokenize(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        return Arrays.stream(s.trim().split("\\s+"))
                     .filter(t -> !t.isBlank())
                     .collect(Collectors.toCollection(ArrayList::new));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DataContext 反射查找
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable
    private RunnerAndConfigurationSettings tryResolveFromContext(@NotNull AnActionEvent e,
                                                                 @NotNull RunManager runManager) {
        String[][] candidates = {
                {"com.intellij.execution.ExecutionDataKeys", "RUN_CONFIGURATION_SETTINGS"},
                {"com.intellij.execution.ExecutionDataKeys", "RUN_CONFIGURATION"},
                {"com.intellij.execution.dashboard.RunDashboardDataKeys", "RUN_CONFIGURATION_NODE"},
                {"com.intellij.execution.services.ServiceViewActionUtils", "SELECTED_ITEM"},
        };
        for (String[] candidate : candidates) {
            try {
                Class<?> clazz = Class.forName(candidate[0]);
                Field field = clazz.getField(candidate[1]);
                Object dataKey = field.get(null);
                if (!(dataKey instanceof DataKey)) continue;
                @SuppressWarnings("unchecked")
                DataKey<Object> key = (DataKey<Object>) dataKey;
                Object value = e.getData(key);
                if (value == null) continue;
                if (value instanceof RunnerAndConfigurationSettings)
                    return (RunnerAndConfigurationSettings) value;
                if (value instanceof RunConfiguration) {
                    RunnerAndConfigurationSettings s = findSettings(runManager, (RunConfiguration) value);
                    if (s != null) return s;
                }
                try {
                    Method getter = value.getClass().getMethod("getConfigurationSettings");
                    Object result = getter.invoke(value);
                    if (result instanceof RunnerAndConfigurationSettings)
                        return (RunnerAndConfigurationSettings) result;
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Nullable
    private RunnerAndConfigurationSettings findSettings(@NotNull RunManager runManager,
                                                        @NotNull RunConfiguration target) {
        try {
            Method m = runManager.getClass().getMethod("getSettings", RunConfiguration.class);
            Object result = m.invoke(runManager, target);
            if (result instanceof RunnerAndConfigurationSettings)
                return (RunnerAndConfigurationSettings) result;
        } catch (Exception ignored) {}
        for (RunnerAndConfigurationSettings s : runManager.getAllSettings()) {
            RunConfiguration c = s.getConfiguration();
            if (c == target) return s;
            if (c.getClass().equals(target.getClass()) && c.getName().equals(target.getName())) return s;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 多选配置对话框
    // ─────────────────────────────────────────────────────────────────────────

    private static class ConfigSelectDialog extends DialogWrapper {
        private final List<RunnerAndConfigurationSettings> allSettings;
        private final CheckBoxList<RunnerAndConfigurationSettings> checkBoxList;

        protected ConfigSelectDialog(@NotNull Project project,
                                     @NotNull List<RunnerAndConfigurationSettings> allSettings,
                                     @Nullable RunnerAndConfigurationSettings preSelected) {
            super(project);
            this.allSettings = allSettings;
            this.checkBoxList = new CheckBoxList<>();
            setTitle("选择要修改的运行配置");
            for (RunnerAndConfigurationSettings s : allSettings) {
                checkBoxList.addItem(s, s.getConfiguration().getName(), s == preSelected);
            }
            init();
        }

        @Override
        @Nullable
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.setPreferredSize(new Dimension(420, 300));
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JButton all = new JButton("全选");
            JButton none = new JButton("全不选");
            all.addActionListener(ev -> { allSettings.forEach(s -> checkBoxList.setItemSelected(s, true)); checkBoxList.repaint(); });
            none.addActionListener(ev -> { allSettings.forEach(s -> checkBoxList.setItemSelected(s, false)); checkBoxList.repaint(); });
            toolbar.add(all);
            toolbar.add(none);
            panel.add(new JBLabel("勾选需要统一修改 VM 参数的配置："), BorderLayout.NORTH);
            panel.add(new JBScrollPane(checkBoxList), BorderLayout.CENTER);
            panel.add(toolbar, BorderLayout.SOUTH);
            return panel;
        }

        @NotNull
        public List<RunnerAndConfigurationSettings> getSelectedSettings() {
            List<RunnerAndConfigurationSettings> result = new ArrayList<>();
            for (RunnerAndConfigurationSettings s : allSettings) {
                if (checkBoxList.isItemSelected(s)) result.add(s);
            }
            return result;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VM 参数编辑对话框（含丰富预设 + 智能合并）
    // ─────────────────────────────────────────────────────────────────────────

    private static class VmOptionsDialog extends DialogWrapper {

        private final JBTextField vmOptionsField;

        protected VmOptionsDialog(@NotNull Project project,
                                  @NotNull String configTitle,
                                  @NotNull String currentOptions) {
            super(project);
            this.vmOptionsField = new JBTextField(currentOptions, 70);
            setTitle("编辑 VM 参数 — " + configTitle);
            init();
        }

        @Override
        @Nullable
        protected JComponent createCenterPanel() {
            JPanel root = new JPanel(new BorderLayout(8, 10));
            root.setPreferredSize(new Dimension(700, 420));

            // 顶部：参数输入框
            JPanel inputPanel = new JPanel(new BorderLayout(4, 4));
            inputPanel.setBorder(BorderFactory.createTitledBorder("当前 VM 参数（可直接编辑）"));
            inputPanel.add(vmOptionsField, BorderLayout.CENTER);

            JButton clearBtn = new JButton("清空");
            clearBtn.addActionListener(ev -> vmOptionsField.setText(""));
            JPanel inputSouth = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            inputSouth.add(clearBtn);
            inputPanel.add(inputSouth, BorderLayout.SOUTH);

            // 中部：预设参数面板（分组）
            JPanel presetsPanel = new JPanel(new GridLayout(0, 4, 6, 6));
            presetsPanel.setBorder(BorderFactory.createTitledBorder("快速插入（相同类型参数自动覆盖）"));

            // 分组顺序
            String[][] groups = {
                    {"堆内存（最小）", "-Xms"},
                    {"堆内存（最大）", "-Xmx"},
                    {"线程栈", "-Xss"},
                    {"GC 策略", "-XX:+Use"},
                    {"代码缓存", "-XX:ReservedCodeCacheSize="},
                    {"断言", "-ea"},
                    {"远程调试", "-agentlib:jdwp"},
                    {"GC 日志", "-Xlog:gc"},
                    {"OOM Dump", "-XX:+HeapDumpOnOutOfMemoryError"},
                    {"编码 / 时区", "-Dfile.encoding="},
                    {"Spring Profile", "-Dspring.profiles.active="},
                    {"Spring 端口", "-Dserver.port="},
            };

            for (String[] group : groups) {
                String groupName = group[0];
                String groupPrefix = group[1];

                // 找出属于这个组的预设
                List<String[]> groupPresets = Arrays.stream(PRESETS)
                                                    .filter(p -> p[2].equals(groupPrefix) ||
                                                            (groupPrefix.equals("-Dfile.encoding=") && (p[2].equals("-Dfile.encoding=") || p[2].equals("-Duser.timezone="))) ||
                                                            (groupPrefix.equals("-ea") && (p[2].equals("-ea") || p[2].equals("-da"))))
                                                    .collect(Collectors.toList());

                if (groupPresets.isEmpty()) continue;

                JPanel groupPanel = new JPanel();
                groupPanel.setLayout(new BoxLayout(groupPanel, BoxLayout.Y_AXIS));
                groupPanel.setBorder(new TitledBorder(groupName));

                for (String[] preset : groupPresets) {
                    String label = preset[0];
                    String value = preset[1];
                    String mutex = preset[2];
                    JButton btn = new JButton(label);
                    btn.setFont(btn.getFont().deriveFont(11f));
                    btn.setMargin(new Insets(2, 6, 2, 6));

                    // 初始化按钮高亮状态
                    updateButtonAppearance(btn, vmOptionsField.getText(), value);

                    btn.addActionListener(ev -> {
                        String toggled = toggleVmOption(vmOptionsField.getText(), value, mutex);
                        vmOptionsField.setText(toggled);
                        // 更新按钮视觉状态
                        updateButtonAppearance(btn, toggled, value);
                    });

                    // 输入框变化时同步更新所有按钮状态
                    vmOptionsField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                        public void insertUpdate(javax.swing.event.DocumentEvent e) { updateButtonAppearance(btn, vmOptionsField.getText(), value); }
                        public void removeUpdate(javax.swing.event.DocumentEvent e) { updateButtonAppearance(btn, vmOptionsField.getText(), value); }
                        public void changedUpdate(javax.swing.event.DocumentEvent e) { updateButtonAppearance(btn, vmOptionsField.getText(), value); }
                    });

                    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1));
                    row.add(btn);
                    groupPanel.add(row);
                }
                presetsPanel.add(groupPanel);
            }

            JBScrollPane presetsScroll = new JBScrollPane(presetsPanel);
            presetsScroll.setPreferredSize(new Dimension(680, 280));

            root.add(inputPanel, BorderLayout.NORTH);
            root.add(presetsScroll, BorderLayout.CENTER);
            return root;
        }

        /** 已激活：加粗 + 前景色变蓝；未激活：恢复默认 */
        private static void updateButtonAppearance(JButton btn, String currentOptions, String value) {
            boolean active = containsOption(currentOptions, value);
            btn.setFont(btn.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, 11f));
            btn.setForeground(active ? new Color(0x2470B3) : null);
        }

        @NotNull
        public String getVmOptions() {
            return vmOptionsField.getText();
        }
    }
}