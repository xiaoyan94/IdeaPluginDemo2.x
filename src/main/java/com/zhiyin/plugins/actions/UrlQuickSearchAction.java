package com.zhiyin.plugins.actions;

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManagerImpl;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.JTextField;

/**
 * Ctrl+Shift+\ 直达 URL 搜索：打开 Search Everywhere（All 页签）并预填 "/url "，
 * 后续输入直接作为关键字搜索 Controller/Feign 映射（UrlSearchEverywhereContributor）。
 */
public class UrlQuickSearchAction extends AnAction {

    private static final String INITIAL_SEARCH_TEXT = "/url ";

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        SearchEverywhereManager manager = SearchEverywhereManager.getInstance(project);
        // SearchEverywhereManagerImpl#show 在弹窗已打开时会抛 IllegalStateException，先挡掉
        if (manager.isShown()) return;

        manager.show(SearchEverywhereManagerImpl.ALL_CONTRIBUTORS_GROUP_ID, INITIAL_SEARCH_TEXT, e);

        // 平台对预填文本默认 selectAll（SearchEverywhereManagerImpl#show），一输入就整段覆盖；
        // "/url " 是前缀命令，收起选区并把光标移到末尾，后续输入直接追加
        JTextField searchField = manager.getCurrentlyShownUI().getSearchField();
        searchField.setCaretPosition(searchField.getText().length());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
