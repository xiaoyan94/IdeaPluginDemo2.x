package com.zhiyin.plugins.search;

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.zhiyin.plugins.microservices.MethodInfo;
import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.actionSystem.AnActionEvent;

public class UrlSearchEverywhereContributorFactory implements SearchEverywhereContributorFactory<MethodInfo> {

    @Override
    public @NotNull SearchEverywhereContributor<MethodInfo> createContributor(@NotNull AnActionEvent event) {
        // 从 event 获取 Project
        return new UrlSearchEverywhereContributor(event.getProject());
    }
}
