package com.zhiyin.plugins.i18n;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.zhiyin.plugins.notification.MyPluginMessages;
import org.jetbrains.annotations.NotNull;

public class I18nTestAction extends AnAction {

    private final Logger LOG = Logger.getInstance(I18nTestAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        I18nCacheManager cacheManager = project.getService(I18nCacheManager.class);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Scanning I18n Files", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                I18nScanner.scanProject(project, cacheManager, indicator, true);

                // 扫描完成后打印缓存信息
                cacheManager.getAllModuleCaches().forEach((moduleName, moduleCache) -> {
                    LOG.info("Module: " + moduleName);
                    LOG.info("  webResources: " + moduleCache.webResources.keySet());
                    LOG.info("  otherResources: " + moduleCache.otherResources.keySet());
                    LOG.info("  datagridResources: " + moduleCache.datagridResources.keySet());
                });

            }
        });

    }

}