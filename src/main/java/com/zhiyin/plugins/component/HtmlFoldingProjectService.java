package com.zhiyin.plugins.component;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.zhiyin.plugins.manager.HtmlFoldingManager;
import com.zhiyin.plugins.utils.MyPsiUtil;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
public final class HtmlFoldingProjectService implements Disposable {

    private final Project project;
    private final Disposable disposable;
    private final MessageBusConnection connection;

    public HtmlFoldingProjectService(Project project) {
        this.project = project;
        this.disposable = Disposer.newDisposable("HtmlFoldingManagerDisposable");
        this.connection = project.getMessageBus().connect(this);

        // 订阅文件打开事件
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                ReadAction.nonBlocking(() -> {
                            // 耗时任务
                            handleFile(source, file);
                            return null;
                        })
                        .inSmartMode(project) // 等待索引完成
                        .expireWith(disposable) // 项目关闭时自动取消
                        .submit(AppExecutorUtil.getAppExecutorService());
//                handleFile(source, file);
            }
        });

        // 初始化已经打开的文件
        initializeExistingFiles();
    }

    private void handleFile(FileEditorManager source, VirtualFile file) {
        if (isHtmlOrTemplateFile(file)) {
            for (FileEditor editor : source.getEditors(file)) {
                if (editor instanceof TextEditor) {
                    HtmlFoldingManager.getInstance(((TextEditor) editor).getEditor());
                }
            }
        }
    }

    /*private boolean isHtmlOrTemplateFile(VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        String extension = file.getExtension();
        return "html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension) ||
               "ftl".equalsIgnoreCase(extension) || "xml".equalsIgnoreCase(extension) &&
                                                    (MyPsiUtil.isLayoutFile(psiFile) ||
                                                     MyPsiUtil.isImpMapperXML(psiFile)) ||
               "js".equalsIgnoreCase(extension) || "java".equalsIgnoreCase(extension) ||
//                "properties".equalsIgnoreCase(extension) ||
               "jsp".equalsIgnoreCase(extension);
    }*/

    private boolean isHtmlOrTemplateFile(VirtualFile file) {
        if (file == null || project.isDisposed()) return false;

        String extension = file.getExtension();
        if (extension == null) return false;

        // 仅当需要 Psi 时才使用 ReadAction 包装
        return ReadAction.compute(() -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile == null) return false;

            return "html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension) ||
                    "ftl".equalsIgnoreCase(extension) ||
                    ("xml".equalsIgnoreCase(extension) &&
                            (MyPsiUtil.isLayoutFile(psiFile) || MyPsiUtil.isImpMapperXML(psiFile))) ||
                    "js".equalsIgnoreCase(extension) ||
                    "java".equalsIgnoreCase(extension) ||
                    "jsp".equalsIgnoreCase(extension);
        });
    }

    private void initializeExistingFiles() {
        FileEditorManager manager = FileEditorManager.getInstance(project);
        for (VirtualFile file : manager.getOpenFiles()) {
            handleFile(manager, file);
        }
    }

    @Override
    public void dispose() {
        // connection 会随 this 自动释放，不需要手动 disconnect
        disposable.dispose();
        connection.dispose();
    }

    public static HtmlFoldingProjectService getInstance(@NotNull Project project) {
        return project.getService(HtmlFoldingProjectService.class);
    }
}
