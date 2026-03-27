package com.zhiyin.plugins.microservices;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiMethod;
import com.intellij.util.PsiNavigateUtil;
import com.zhiyin.plugins.notification.MyPluginMessages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * An action to trigger a scan. Useful for testing the incremental implementation.
 */
public class CollectControllersAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        ControllerMappingService service = ControllerMappingService.getInstance(project);

        // trigger scan (non-forced by default)
        service.startScan(true, () -> {
            // TEST: show input dialog with several random URL to select, then navigate to it using PsiNavigateUtil
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
                Map<String, List<MethodInfo>> mappingSnapshot = service.getMappingSnapshot();
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    PsiMethod selectedMethod = selectRandomMethod(mappingSnapshot, project);
                    if (selectedMethod != null) {
                        PsiNavigateUtil.navigate(selectedMethod);
                    }
                }, com.intellij.openapi.application.ModalityState.defaultModalityState());
            });
        });

        // We can't immediately know result because scan runs async. But we can schedule a small delayed check:
        // Here we'll run a simple delayed task on EDT to check after a short time — OR you can rely on notifications inside subscribe.
        // For simplicity, we will show a notification that scan started; user can later check counts via another action or inspect service.

        MyPluginMessages.showInfo("Controller scan", "Controller scan started in background. It will update project's mapping when done.");
    }

    /**
     * 从缓存中随机选择一个方法供用户选择
     */
    @Nullable
    private PsiMethod selectRandomMethod(Map<String, List<MethodInfo>> urlToMethods, Project project) {
        if (urlToMethods.isEmpty()) {
            MyPluginMessages.showWarning("Navigation", "No controller methods found", project);
            return null;
        }

        // 收集所有方法
        List<PsiMethod> methods = new ArrayList<>();
        for (List<MethodInfo> methodList : urlToMethods.values()) {
            if (methodList == null || methodList.size() < 3) {
                continue;
            }
            for (MethodInfo pointer : methodList) {
                System.out.println(pointer);
                PsiMethod method = pointer.pointer().getElement();
                if (method != null && Objects.requireNonNull(method.getContainingClass()).getAnnotation("org.springframework.cloud.openfeign.FeignClient")!= null) {
                    methods.add(method);
                }
            }
        }

        if (methods.isEmpty()) {
            MyPluginMessages.showWarning("Navigation", "No valid controller methods found", project);
            return null;
        }

        // 随机选择最多5个方法
        Collections.shuffle(methods);
        List<PsiMethod> candidates = methods.size() > 5 ? methods.subList(0, 5) : methods;

        // 显示选择对话框
        MethodSelectionDialog dialog = new MethodSelectionDialog(project, candidates);
        if (dialog.showAndGet()) {
            return dialog.getSelectedMethod();
        }
        return null;
    }

    /**
     * 方法选择对话框
     */
    private static class MethodSelectionDialog extends DialogWrapper {
        private final List<PsiMethod> methods;
        private ComboBox<String> methodComboBox;
        private PsiMethod selectedMethod;

        protected MethodSelectionDialog(@Nullable Project project, List<PsiMethod> methods) {
            super(project);
            this.methods = methods;
            setTitle("Select Controller Method");
            init();
        }

        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JLabel("Select a method to navigate to:"), BorderLayout.NORTH);

            String[] methodNames = methods.stream()
                                          .map(m -> m.getContainingClass().getName() + "." + m.getName())
                                          .toArray(String[]::new);

            methodComboBox = new ComboBox<>(methodNames);
            panel.add(methodComboBox, BorderLayout.CENTER);

            return panel;
        }

        @Override
        protected void doOKAction() {
            int selectedIndex = methodComboBox.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < methods.size()) {
                selectedMethod = methods.get(selectedIndex);
            }
            super.doOKAction();
        }

        public PsiMethod getSelectedMethod() {
            return selectedMethod;
        }
    }
}
