package com.zhiyin.plugins.activities;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.VirtualFile;
import com.zhiyin.plugins.i18n.I18nCacheManager;
import com.zhiyin.plugins.i18n.I18nFileListener;
import com.zhiyin.plugins.i18n.I18nScanner;
import com.zhiyin.plugins.microservices.ControllerMappingService;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.service.MyProjectService;
import com.zhiyin.plugins.service.ControllerUrlService;
import com.zhiyin.plugins.annotator.MyHTMLAnnotator;
import com.zhiyin.plugins.annotator.MyJavaScriptBlockAnnotator;
import com.intellij.lang.LanguageAnnotators;
import com.intellij.lang.Language;
import com.intellij.lang.html.HTMLLanguage;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.daemon.LineMarkerProviders;
import com.zhiyin.plugins.provider.lineMarkers.FeignClientRelatedItemLineMarkerProvider;
import com.zhiyin.plugins.provider.lineMarkers.JSUrlRelatedItemLineMarkerProvider;
import com.zhiyin.plugins.service.PluginDisposable;
import com.zhiyin.plugins.settings.TranslateSettingsComponent;
import com.zhiyin.plugins.settings.TranslateSettingsState;
import org.jetbrains.annotations.NotNull;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Java 实现 ProjectActivity (Kotlin suspend fun) 的示例
 */
public class MyProjectActivity implements ProjectActivity {

    private static final List<LineMarkerProvider> lineMarkerProviders = new ArrayList<>();

    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        DumbService.getInstance(project).runWhenSmart(() -> {
            if (project.isDisposed()) return;

            // 初始化服务
            MyProjectService myProjectService = project.getService(MyProjectService.class);
            myProjectService.reInitXmlFileMap();

            // project.getService(ControllerUrlService.class);
            ControllerMappingService.getInstance(project).startScan(true);

            registerLineMarkerProviders(project);

            registerI18nCacheManagerService(project);
        });

        // 返回 kotlin.Unit.INSTANCE 表示完成
        return Unit.INSTANCE;
    }

    private static void registerI18nCacheManagerService(@NotNull Project project) {
        // 获取项目级 Service
        I18nCacheManager cacheManager = project.getService(I18nCacheManager.class);
        if (cacheManager == null) return;

        // 1️⃣ 注册监听器（会自动绑定到 project 生命周期）
        new I18nFileListener(project, cacheManager).register();

        // 2️⃣ 异步扫描项目模块 i18n 文件
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Scanning I18n Files", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                I18nScanner.scanProject(project, cacheManager, indicator, true);
            }
        });
    }

    private static void registerLineMarkerProviders(@NotNull Project project) {
        TranslateSettingsState state = TranslateSettingsComponent.Companion.getInstance().getState();

        /*if (state.getEnableFeignToRestController() && false) {
            Language language = Language.findLanguageByID("JAVA");
            if (language != null && lineMarkerProviders.stream().noneMatch(p -> p instanceof FeignClientRelatedItemLineMarkerProvider)) {
                FeignClientRelatedItemLineMarkerProvider feignProvider = new FeignClientRelatedItemLineMarkerProvider();
                LineMarkerProviders.getInstance().addExplicitExtension(language, feignProvider, PluginDisposable.getInstance(project));
                lineMarkerProviders.add(feignProvider);
            }
        }

        if (state.getEnableHtmlUrlToController() && false) {
            Language js = Language.findLanguageByID("JavaScript");
            if (js != null && lineMarkerProviders.stream().noneMatch(p -> p instanceof JSUrlRelatedItemLineMarkerProvider)) {
                JSUrlRelatedItemLineMarkerProvider jsProvider = new JSUrlRelatedItemLineMarkerProvider();
                LineMarkerProviders.getInstance().addExplicitExtension(js, jsProvider, PluginDisposable.getInstance(project));
                lineMarkerProviders.add(jsProvider);
            }
        }*/

        if (state.getEnableHtmlAnnotator()) {
            LanguageAnnotators.INSTANCE.addExplicitExtension(HTMLLanguage.INSTANCE, new MyHTMLAnnotator());
            Language js = Language.findLanguageByID("JavaScript");
            if (js != null) {
                LanguageAnnotators.INSTANCE.addExplicitExtension(js, new MyJavaScriptBlockAnnotator());
            }
        }
    }

    public static void unregisterLineMarkerProviders() {
        Language language = Language.findLanguageByID("JAVA");
        if (language != null) {
            Iterator<LineMarkerProvider> iterator = lineMarkerProviders.iterator();
            while (iterator.hasNext()) {
                LineMarkerProvider provider = iterator.next();
                LineMarkerProviders.getInstance().removeExplicitExtension(language, provider);
                iterator.remove();
            }
        }
    }
}
