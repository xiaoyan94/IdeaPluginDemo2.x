package com.zhiyin.plugins.i18n;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal;
import com.intellij.codeInsight.folding.CodeFoldingManager;
import com.intellij.codeInsight.hints.InlayHintsPass;
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import com.zhiyin.plugins.component.HtmlFoldingProjectService;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.resources.Constants;
import com.zhiyin.plugins.utils.ProjectTypeChecker;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

public class I18nScanner {

    /**
     * 扫描整个项目的 i18n 文件
     *
     * @param project          当前项目
     * @param cacheManager     I18nCacheManager 实例
     * @param indicator        进度指示器，可为 null
     * @param showNotification 是否扫描完成后弹出通知
     */
    public static void scanProject(Project project, I18nCacheManager cacheManager,
                                   ProgressIndicator indicator, boolean showNotification) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        int totalModules = modules.length;

        if (indicator != null) {
            indicator.setIndeterminate(false); // ⚠️ 关键：设置为确定进度
        }

        for (int i = 0; i < totalModules; i++) {
            Module module = modules[i];
            if (indicator != null) {
                indicator.setFraction((double) i / totalModules);
                indicator.setText("Scanning module: " + module.getName());
            }

            // 先扫描库中的资源
            scanLibraryResources(module, cacheManager, indicator);

            // 再扫描模块中的资源
            scanModuleResources(cacheManager, module);

        }

        // 传统 Java Web 项目（老 MES）：i18n 文件不在 resources/i18n 目录下，目录扫描覆盖不到，
        // 按索引一次性找齐灌入缓存（取代原热路径 findXxxValue 里的索引兜底）。
        // try-catch 防御：本方法末尾的 HtmlFoldingProjectService 初始化是 inlay 创建链的守门人
        // （fileOpened 监听在其构造函数注册），这里任何异常都会导致全项目 inlay 失效，必须兜住只记日志
        try {
            if (ProjectTypeChecker.isTraditionalJavaWebProject(project, null)) {
                scanProjectResourcesByIndex(project, cacheManager);
            }
        } catch (Throwable t) {
            Logger.getInstance(I18nScanner.class).warn("Traditional project index scan failed, i18n cache may be incomplete", t);
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (showNotification) {
                MyPluginMessages.showInfo("I18n Scan", "I18n cache scanned successfully.");
            }

            // 缓存灌满后刷新已打开编辑器的折叠/Inlay，否则已打开文件显示的还是旧缓存（${key}）
            refreshOpenedEditorsFolding(project);
            com.zhiyin.plugins.manager.HtmlFoldingManager.refreshAllEditorsInlays(project);

            // 主动获取 Service，从而触发构造函数里的 Listener 注册
            HtmlFoldingProjectService.getInstance(project); // 保证在缓存加载完成后再初始化 HtmlFoldingProjectService
        });

    }

    /**
     * 按索引收集全项目 i18n 资源文件（含 web_、模块名前后缀、datagrid 目录），灌入缓存。
     * 只在启动扫描的后台线程跑一次；runReadActionInSmartMode 保证索引可用（dumb mode 时等待）。
     */
    private static void scanProjectResourcesByIndex(Project project, I18nCacheManager cacheManager) {
        DumbService.getInstance(project).runReadActionInSmartMode(() -> {
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
            Collection<VirtualFile> properties = FileTypeIndex.getFiles(
                    FileTypeManager.getInstance().getFileTypeByExtension("properties"), scope);

            Set<VirtualFile> i18nFiles = properties.stream()
                    .filter(vf -> {
                        String name = vf.getName();
                        // web_*.properties 在模块内任意位置（原 FilenameIndex 查找不限定 i18n 目录）
                        if (name.startsWith("web_")) return true;
                        // 模块资源与 datagrid 资源都在 i18n 目录下，按语言后缀识别
                        String path = vf.getPath().replace('\\', '/');
                        return path.contains("/i18n/") &&
                                (path.endsWith(Constants.I18N_ZH_CN_SUFFIX) ||
                                        path.endsWith(Constants.I18N_ZH_TW_SUFFIX) ||
                                        path.endsWith(Constants.I18N_EN_US_SUFFIX) ||
                                        path.endsWith(Constants.I18N_VI_VN_SUFFIX));
                    })
                    .collect(java.util.stream.Collectors.toSet());

            cacheManager.scanI18nFiles(project, i18nFiles);
        });
    }

    /**
     * 缓存加载完成后触发已打开编辑器的折叠重算，让占位符读到新缓存值。
     */
    private static void refreshOpenedEditorsFolding(Project project) {
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
        for (VirtualFile openedFile : fileEditorManager.getOpenFiles()) {
            for (FileEditor fileEditor : fileEditorManager.getEditors(openedFile)) {
                if (fileEditor instanceof TextEditor textEditor) {
                    CodeFoldingManager.getInstance(project)
                            .scheduleAsyncFoldingUpdate(textEditor.getEditor());
                }
            }
        }
    }

    private static void testRefreshOpenedEditorInlay(Project project) {
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);

        // 遍历所有当前打开的文件
        for (VirtualFile openedFile : fileEditorManager.getOpenFiles()) {

            // 获取文件对应的 FileEditor 实例
            for (FileEditor fileEditor : fileEditorManager.getEditors(openedFile)) {

                // 1. 检查 FileEditor 是否是 TextEditor
                if (fileEditor instanceof TextEditor textEditor) {

                    // 2. 将 FileEditor 安全地转换为 TextEditor

                    // 3. 从 TextEditor 中获取底层的 com.intellij.openapi.editor.Editor 实例
                    Editor editor = textEditor.getEditor();

                    // 4. 确保在 EDT 上执行 (在 DumbModeListener.exitDumbMode() 中已使用 ApplicationManager.getApplication().invokeLater())
                    CodeFoldingManager foldingManager = CodeFoldingManager.getInstance(project);

                    // 关键步骤：触发折叠的重新计算和渲染
                    foldingManager.scheduleAsyncFoldingUpdate(editor);

                    InlayHintsPassFactoryInternal.Companion.forceHintsUpdateOnNextPass();


                    // 如果你需要立即同步更新 (通常推荐使用 scheduleAsyncFoldingUpdate):
                    // foldingManager.updateFoldRegions(editor);

                    // 使用声明式 API 刷新 Inlay
                    DeclarativeInlayHintsPassFactory.Companion.scheduleRecompute(editor, project);

                    // 重启该项目的所有分析器
                    // （这会影响所有打开的文件）
                    DaemonCodeAnalyzer.getInstance(project).restart();

                    // 或者，如果你有 PsiFile，可以只重启那个文件
                    DaemonCodeAnalyzer.getInstance(project).restart(PsiUtil.getPsiFile(project, openedFile));
                }
            }

            InlayHintsPassFactoryInternal.Companion.restartDaemonUpdatingHints(project);
        }
    }

    /**
     * 强制重新加载项目中已打开的编辑器文件
     * 通过关闭并重新打开所有已打开的文件来实现刷新效果
     *
     * @param project 当前项目实例，用于获取文件编辑器管理器
     */
    private static void forceReloadOpenedEditors(Project project) {
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
        VirtualFile[] openFiles = fileEditorManager.getOpenFiles();

        if (openFiles.length == 0) {
            return;
        }

        // 记录当前选中的文件，以便在重新打开后恢复选中状态
        VirtualFile currentFile = fileEditorManager.getSelectedFiles().length > 0 ? fileEditorManager.getSelectedFiles()[0] : null;

        // 关闭所有已打开的文件
        ApplicationManager.getApplication().invokeAndWait(() -> {
            if (openFiles.length > 5) {
                Logger.getInstance("I18nInlayRefresh").warn("Too many files open, skipping refresh.");
                return;
            }
            for (VirtualFile file : openFiles) {
                try {
                    if (file.isValid()) {
                        fileEditorManager.closeFile(file);
                    }
                } catch (Exception e) {
                    Logger.getInstance("I18nInlayRefresh").warn("Failed to close file: " + file.getName(), e);
                }
            }
        });

        // 重新打开所有文件，并恢复之前选中的文件状态
        ApplicationManager.getApplication().invokeLater(() -> {
            for (VirtualFile file : openFiles) {
                try {
                    if (file.isValid()) {
                        fileEditorManager.openFile(file, false);
                    }
                } catch (Exception e) {
                    Logger.getInstance("I18nInlayRefresh").warn("Failed to reopen file: " + file.getName(), e);
                }
            }

            if (currentFile != null && currentFile.isValid()) {
                fileEditorManager.openFile(currentFile, true);
            }
        });
    }

    private static void scanModuleResources(I18nCacheManager cacheManager, Module module) {
        VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots(false);
        for (VirtualFile root : sourceRoots) {
            if (root.getPath().contains("/resources")) {
                VirtualFile i18nDir = root.findChild("i18n");
                cacheManager.scanI18nDir(module.getName(), i18nDir);
            }
        }
    }

    private static void scanLibraryResources(Module module, I18nCacheManager cacheManager,
                                             @Nullable ProgressIndicator indicator) {
        VirtualFile[] libraryRoots = OrderEnumerator.orderEntries(module)
                                                    .withoutSdk()
                                                    .librariesOnly()
                                                    .classes()
                                                    .getRoots();

        for (VirtualFile libRoot : libraryRoots) {
            // libRoot might be a jar file root
            if (libRoot.getFileSystem() instanceof JarFileSystem) {
                VirtualFile i18nDir = libRoot.findFileByRelativePath("i18n");
                if (i18nDir != null && i18nDir.isDirectory()) {
//                    String moduleKey = module.getName() + ":library:" + libRoot.getName();
                    cacheManager.scanI18nDir(module.getName(), i18nDir);
                }
            } else {
                // maybe exploded directory
                VirtualFile i18nDir = libRoot.findChild("i18n");
                if (i18nDir != null && i18nDir.isDirectory()) {
//                    String moduleKey = module.getName() + ":library:" + libRoot.getName();
                    cacheManager.scanI18nDir(module.getName(), i18nDir);
                }
            }
        }
    }
}
