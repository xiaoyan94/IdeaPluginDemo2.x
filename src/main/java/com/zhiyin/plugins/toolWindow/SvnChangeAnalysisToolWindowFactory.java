package com.zhiyin.plugins.toolWindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * SVN 变更分析 ToolWindow Factory
 * 功能：输入基准日期和SVN路径，统计各模块的文件变更数量和提交时间范围
 */
public class SvnChangeAnalysisToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        SvnChangeAnalysisToolWindowUI ui = new SvnChangeAnalysisToolWindowUI(project, toolWindow);
        toolWindow.getComponent().add(ui.getContent());
    }
}
