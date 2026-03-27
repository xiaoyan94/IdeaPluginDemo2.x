package com.zhiyin.plugins.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.CommitMessageI;
import com.zhiyin.plugins.settings.AppSettingsState;
import org.jetbrains.annotations.NotNull;

public class TestCommitAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        CommitMessageI commitMessageI = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);

        if (commitMessageI != null) {
            // 获取前缀模板
            String prefixTemplate = AppSettingsState.getInstance().commitMessageTemplate;

            // 因为 CommitMessageI 没有 get 方法，只能直接 set
            commitMessageI.setCommitMessage(prefixTemplate);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        presentation.setText("添加前缀模板");
        presentation.setDescription("Tips: 一键添加定义好的模板作为前缀");
        presentation.setEnabledAndVisible(e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
