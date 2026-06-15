package com.zhiyin.plugins.toolWindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * SVN 提交日志分析 ToolWindow Factory
 * 功能：按日期范围和人员筛选SVN提交记录，统计分析并生成结构化Prompt
 */
public class SvnCommitLogAnalysisToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        SvnCommitLogAnalysisToolWindowUI ui = new SvnCommitLogAnalysisToolWindowUI(project, toolWindow);
        toolWindow.getComponent().add(ui.getContent());
    }
}
