package com.zhiyin.plugins.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.service.MyProjectService;
import org.jetbrains.annotations.NotNull;

public class GenerateDictAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (null == project) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                MyPluginMessages.showInfo("拼写检查词典", "开始生成词典，请稍后", project);
            });
            project.getService(MyProjectService.class).createDictionaryFile(project);
        });

    }

}
