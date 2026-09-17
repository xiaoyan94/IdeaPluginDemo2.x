package com.zhiyin.plugins.component;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
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

        // editor 释放时显式处置其 HtmlFoldingManager（direct Disposer.dispose 模式，平台告警原文认可）。
        // 必须用带 parentDisposable 的重载：disposable 在本服务 dispose() 里被 Disposer.dispose 摘除时，
        // 该监听随之自动移除，不随应用级 EditorFactory 泄漏到项目生命周期之后。
        // EditorFactory 事件跨项目触发；release 内部按 userData 判空，他项目/无 manager 的编辑器天然过滤。
        EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
            @Override
            public void editorReleased(@NotNull EditorFactoryEvent event) {
                HtmlFoldingManager.release(event.getEditor());
            }
        }, disposable);

        // 初始化已经打开的文件：延迟到非 EDT 的 smart mode 执行。
        // 本服务常被 EDT 上的 getInstance 惰性触发（I18nScanner.scanProject 的 invokeLater /
        // StartupActivity），构造器里同步遍历已开文件做 PsiManager.findFile 只包普通 ReadAction，
        // workspace 文件索引数据未就绪时 ensureIsUpToDate 会在 EDT 命中 SlowOperations 断言。
        // 与上方 fileOpened 监听同款机制；监听已在构造器更早处注册生效，延迟窗口内新开的文件
        // 由该监听覆盖，本任务晚到只会对同一 editor 重复执行幂等的 HtmlFoldingManager.getInstance。
        ReadAction.nonBlocking(() -> {
                    if (project.isDisposed()) return null;
                    initializeExistingFiles();
                    return null;
                })
                .inSmartMode(project) // 等待索引完成
                .expireWith(disposable) // 项目关闭时自动取消
                .submit(AppExecutorUtil.getAppExecutorService());
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
        // 必须走 Disposer.dispose 静态方法：从 ObjectTree 摘节点并级联处置子件
        // （EditorFactoryListener、expireWith 挂着的非阻塞任务）；直接调 .dispose() 只执行
        // 对象自身方法，树节点滞留 ROOT → 沙箱退出告警 HtmlFoldingManagerDisposable 泄漏实证。
        // 重复处置安全：connection 若已随本服务树级联处置，其 dispose() 幂等。
        Disposer.dispose(disposable);
        Disposer.dispose(connection);

        // 兜底：项目关闭期若 FileEditorManager 晚于本服务销毁，随后 editorReleased 到达时
        // listener 已随 disposable 摘除——主动扫本项目仍存活的 editor 补一次显式处置，
        // 保证任何销毁顺序下 manager 都不滞留 ROOT。release 按 userData 判空幂等，
        // 已处置/他项目的编辑器安全跳过。
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() == project) {
                HtmlFoldingManager.release(editor);
            }
        }
    }

    public static HtmlFoldingProjectService getInstance(@NotNull Project project) {
        return project.getService(HtmlFoldingProjectService.class);
    }
}
