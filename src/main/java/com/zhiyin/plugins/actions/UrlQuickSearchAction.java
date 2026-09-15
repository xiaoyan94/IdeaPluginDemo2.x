package com.zhiyin.plugins.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zhiyin.plugins.service.MyToolWindowService;
import com.zhiyin.plugins.toolWindow.NewControllerToolWindowUI;
import org.jetbrains.annotations.NotNull;

/**
 * Ctrl+Alt+Shift+\ 直达 URL 搜索：统一打开 OneClickNavigationToolWindow 并聚焦 URL 输入框。
 * <p>
 * 默认键不再用 Ctrl+Shift+\：IDEA Ultimate 2026.x 的 microservices-plugin 给内置 Go to URL
 * （GotoUrlAction）默认绑了 control shift BACK_SLASH，插件侧无法移除平台绑定，冲突必现。
 * <p>
 * 不再走 Search Everywhere：2026.2 起新 SE（SeFrontendService）不接入第三方 legacy
 * searchEverywhereContributor（SeProvidersHolder.initialize 的 withAdaptedLegacyContributors=false，
 * SeFrontendService.kt#show），"/url" 搜不出结果；且 getCurrentlyShownUI() 会抛
 * UnsupportedOperationException（2.0.22 在 2026.2.2 的回归）。旧平台也不再依赖 SE，行为统一。
 */
public class UrlQuickSearchAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        openToolWindowSearch(project);
    }

    /**
     * 打开工具窗口并聚焦 URL 输入框，由用户直接在自有搜索 UI 里输入
     */
    private static void openToolWindowSearch(Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OneClickNavigationToolWindow");
        if (toolWindow == null) return;
        toolWindow.show(() -> {
            NewControllerToolWindowUI ui = project.getService(MyToolWindowService.class).getUI();
            if (ui != null) {
                ui.focusUrlField();
            }
        });
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
