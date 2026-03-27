package com.zhiyin.plugins.toolWindow;

import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.zhiyin.plugins.microservices.MethodInfo;
import com.zhiyin.plugins.microservices.UrlResolver;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.microservices.ControllerMappingService;
import com.zhiyin.plugins.service.PluginDisposable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.util.List;
import java.util.*;

/**
 * 新版 ToolWindow：基于 ControllerMappingService 的 URL -> MethodInfo 缓存
 */
public class NewControllerToolWindowUI {

    private final Project project;
    private final ToolWindow toolWindow;

    private final JTextField urlField = new JTextField(20);
    private final JButton searchButton = new JButton("Find");
    private final JButton resetButton = new JButton("Reset");

    private final DefaultListModel<MethodInfo> listModel = new DefaultListModel<>();
    private final JList<MethodInfo> resultList = new JBList<>(listModel);

    public NewControllerToolWindowUI(Project project, ToolWindow toolWindow) {
        this.project = project;
        this.toolWindow = toolWindow;
        initUI();
    }

    private void initUI() {
        searchButton.setIcon(AllIcons.Actions.Find);
        searchButton.setToolTipText("根据 URL 查找对应的 Controller 或 Feign 方法");
        resetButton.setIcon(AllIcons.Actions.Refresh);
        resetButton.setToolTipText("重置缓存数据");

        resultList.setCellRenderer(new MethodInfoCellRenderer());

        // 搜索按钮事件
        searchButton.addActionListener(e -> doSearch());
        urlField.addActionListener(e -> doSearch());

        // 重置按钮
        resetButton.addActionListener(e -> {
            ControllerMappingService.getInstance(project).startScan(true, () -> {
                MyPluginMessages.showInfo("缓存已刷新", "Controller 缓存已重新加载", project);
            });
        });

        // 列表点击导航
        resultList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                MethodInfo info = resultList.getSelectedValue();
                if (info != null) {
                    navigateToMethod(info);
                }
            }
        });

        // 列表双击导航
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    MethodInfo info = resultList.getSelectedValue();
                    if (info != null) {
                        navigateToMethod(info);
                    }
                }
            }
        });
    }

    public JComponent getContent() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        // 顶部输入区
        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.X_AXIS));

        JLabel label = new JLabel("URL:");
        inputPanel.add(label);
        inputPanel.add(Box.createHorizontalStrut(10));
        inputPanel.add(urlField);
        inputPanel.add(Box.createHorizontalGlue());
        inputPanel.add(searchButton);
        inputPanel.add(resetButton);
        inputPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JScrollPane scrollPane = new JBScrollPane(resultList);

        root.add(inputPanel);
        root.add(Box.createVerticalStrut(5));
        root.add(scrollPane);

        SimpleToolWindowPanel panel = new SimpleToolWindowPanel(true, false);
        panel.setContent(root);
        return panel;
    }

    public void setUrlText(String text) {
        urlField.setText(text);
    }

    public void clickJumpButton() {
        doSearch();
    }

    private void doSearch() {
        String inputUrl = urlField.getText().trim();
        if (inputUrl.isEmpty()) {
            MyPluginMessages.showWarning("输入为空", "请输入 URL 片段进行搜索", project);
            return;
        }

        listModel.clear();

        ReadAction.nonBlocking(() -> {
            ControllerMappingService service = ControllerMappingService.getInstance(project);
            Map<String, List<MethodInfo>> mappingSnapshot = service.getMappingSnapshot();
            List<MethodInfo> methods = mappingSnapshot.values()
                                                      .stream()
                                                      .flatMap(List::stream)
                                                      .filter(info -> UrlResolver.fuzzyMatchWithContains(UrlResolver.normalize(inputUrl), info.fullPath()))
                                                      .filter(info -> !info.fullPath().equals("/"))
                                                      .filter(Objects::nonNull)
                                                      .toList();
            return methods;
        }).finishOnUiThread(ModalityState.defaultModalityState(), results -> {
            if (results == null || results.isEmpty()) {
                MyPluginMessages.showInfo("未找到匹配项", "未找到匹配的 URL 映射", project);
                return;
            }

            results.stream()
                   .sorted(Comparator.comparing(MethodInfo::source)
                                     .thenComparing(MethodInfo::moduleName, Comparator.nullsLast(String::compareTo))
                                     .thenComparing(MethodInfo::className))
                    .limit(200)
                   .forEach(listModel::addElement);

            if (listModel.size() == 1) {
                resultList.setSelectedIndex(0);
                toolWindow.hide(null);
            }
        }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private void navigateToMethod(MethodInfo info) {
        ReadAction.nonBlocking(() -> info.pointer().getElement())
                .inSmartMode(project)
                .expireWith(PluginDisposable.getInstance(project))
                .finishOnUiThread(ModalityState.defaultModalityState(), method -> {
                    if (method != null) {
                        NavigationUtil.activateFileWithPsiElement(method);
                    } else {
                        MyPluginMessages.showWarning("方法失效", "无法定位到方法：" + info.methodSignature(), project);
                    }
                }).submit(AppExecutorUtil.getAppExecutorService());
    }

    /** 自定义列表渲染器 */
    static class MethodInfoCellRenderer extends JLabel implements ListCellRenderer<MethodInfo> {
        @Override
        public Component getListCellRendererComponent(
                JList<? extends MethodInfo> list, MethodInfo value, int index, boolean isSelected, boolean cellHasFocus) {

            String iconType = value.source() == MethodInfo.Source.CONTROLLER ? "Controller" : "Feign";
            setIcon(iconType.equals("Controller") ? AllIcons.Nodes.Class : AllIcons.Nodes.Interface);

            String deprecatedMark = value.deprecated() ? "<span style='color:red;'>(Deprecated)</span> " : "";
            String modulePart = value.moduleName() != null ? "<span style='color:#00A5FF;'>" + value.moduleName() + "</span> - " : "";

            setText("<html><table cellpadding='0' cellspacing='0'>" +
                    "<tr><td><b style='color:#4CAF50;'>" + value.fullPath() + "</b></td></tr>" +
                    "<tr><td>" + deprecatedMark + modulePart +
                    "<i style='color:#A9A9A9;'>" + value.className() + "#" + value.methodSignature() + "</i></td></tr>" +
                    "</table></html>");

            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }

            return this;
        }
    }
}
