package com.zhiyin.plugins.listeners;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.zhiyin.plugins.resources.Constants;
import com.zhiyin.plugins.settings.AppSettingsState;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class MyFileEditorManagerListener implements FileEditorManagerListener {

    /**
     * 光标监听器: 当光标离开目标折叠组后自动折叠
     */
    private CaretListener caretListener;

    private static boolean isTargetFoldRegion(FoldRegion foldRegion) {
        return foldRegion.getGroup() != null && foldRegion.getGroup().toString().equals(Constants.FOLDING_GROUP);
    }

    /**
     * This method is called synchronously (in the same EDT event), as the creation of FileEditor(s).
     *
     * @param source
     * @param file
     * @param editorsWithProviders
     * @see #fileOpened(FileEditorManager, VirtualFile)
     */
    @Override
    public void fileOpenedSync(@NotNull FileEditorManager source, @NotNull VirtualFile file, @NotNull List<FileEditorWithProvider> editorsWithProviders) {
        FileEditorManagerListener.super.fileOpenedSync(source, file, editorsWithProviders);
    }

    /**
     * This method is called after the focus settles down (if requested) in a newly created FileEditor.
     * Be aware though, that this isn't always true in case of editors loaded asynchronously, which, in general,
     * may happen with any text editor. In that case, the focus request is postponed until after the editor is fully loaded,
     * which means that it may gain the focus way after this method is called.
     * When necessary, use {@link FileEditorManager#runWhenLoaded(Editor, Runnable)}) to ensure the desired ordering.
     * <p>
     * {@link #fileOpenedSync(FileEditorManager, VirtualFile, List<FileEditorWithProvider>)} is always invoked before this method,
     * either in the same or the previous EDT event.
     *
     * @param source
     * @param file
     * @see #fileOpenedSync(FileEditorManager, VirtualFile, List<FileEditorWithProvider>)}
     */
    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        FileEditorManagerListener.super.fileOpened(source, file);

        collapseFoldRegion(source);

//        MyPluginMessages.showInfo("fileOpened", file.getName(), source.getProject());

        // 光标监听器绑定 editor 生命周期（editor 释放时自动移除），不再靠 fileClosed 手动移除。
        // Editor 接口未直接实现 Disposable（EditorImpl 才实现），需 instanceof 判定
        Editor editor = source.getSelectedTextEditor();
        if (editor instanceof Disposable parentDisposable) {
            caretListener = new FoldingCaretListener(editor);
            editor.getCaretModel().addCaretListener(caretListener, parentDisposable);
        }

        // adjustHighlighting(source, file);

    }

    private static void adjustHighlighting(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        Project project = source.getProject();

        ReadAction.nonBlocking(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            return document != null ? document.getLineCount() : 0;
        }).finishOnUiThread(ModalityState.defaultModalityState(), lineCount -> {
            if (lineCount > 100) {
                System.out.println("fileOpened: adjustHighlighting");

                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile == null) {
                    return;
                }
                HighlightingLevelManager hlManager = HighlightingLevelManager.getInstance(project);
                hlManager.runEssentialHighlightingOnly(psiFile);
                DaemonCodeAnalyzer.getInstance(project).restart(psiFile);
            }
        }).submit(AppExecutorUtil.getAppExecutorService());

    }

    /**
     * @param source 获取Editor得到的文件是关闭后新编辑窗口的文件
     * @param file   被关闭的文件
     */
    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
//        collapseFoldRegion(source);
        FileEditorManagerListener.super.fileClosed(source, file);
//        MyPluginMessages.showInfo("fileClosed", file.getName(), source.getProject());

        // caretListener 已随 editor 释放（addCaretListener(listener, editor)），无需手动移除
        caretListener = null;
    }

    /**
     * @param event
     */
    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        FileEditorManagerListener.super.selectionChanged(event);
        collapseFoldRegion(event.getManager());

        /*ApplicationManager.getApplication().executeOnPooledThread(() ->{
            Project project = event.getManager().getProject();
            VirtualFile oldFile = event.getOldFile();
            if (event.getOldFile() != null && event.getOldFile().isValid() && "java".equalsIgnoreCase(event.getOldFile().getFileType().getName())) {
                ControllerUrlService controllerUrlService = project.getService(ControllerUrlService.class);
                controllerUrlService.recollectControllerUrls(oldFile);
            }
        });*/

    }

    private static void collapseFoldRegion(@NotNull FileEditorManager source) {
        if(AppSettingsState.getInstance().defaultCollapseI18nStatus){
            Editor editor = source.getSelectedTextEditor();
            collapseFoldRegion(editor);
        }
    }

    public static void collapseFoldRegion(Editor editor) {
        if (editor != null) {
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                FoldRegion[] allFoldRegions = editor.getFoldingModel().getAllFoldRegions();
                Arrays.stream(allFoldRegions)
                        .filter(MyFileEditorManagerListener::isTargetFoldRegion)
                        .forEach(foldRegion -> {
                            foldRegion.setExpanded(false);
                        });
            });
        }
    }

    /**
     * 光标监听器：当光标离开目标折叠组后自动折叠
     */
    private static class FoldingCaretListener implements CaretListener {
        private final Editor editor;
        private FoldRegion lastRegion = null;

        public FoldingCaretListener(Editor editor) {
            this.editor = editor;
        }

        @Override
        public void caretPositionChanged(@NotNull CaretEvent event) {
            Caret eventCaret = event.getCaret();
            if (eventCaret == null) return; // 没有光标就直接返回
            int caretOffset = eventCaret.getOffset();

            FoldRegion[] regions = editor.getFoldingModel().getAllFoldRegions();

            // 找到光标当前所在的目标折叠区域
            FoldRegion currentRegion = Arrays.stream(regions)
                    .filter(MyFileEditorManagerListener::isTargetFoldRegion)
                    .filter(r -> r.getStartOffset() <= caretOffset && caretOffset <= r.getEndOffset())
                    .findFirst()
                    .orElse(null);

            // 状态没变化（未进入新区域、离开的也不是目标区域）就不触发折叠批处理，避免每次光标移动都跑一遍
            boolean needCollapse = lastRegion != null && lastRegion != currentRegion && lastRegion.isValid();
            boolean needExpand = currentRegion != null && !currentRegion.isExpanded();
            if (!needCollapse && !needExpand) {
                lastRegion = currentRegion;
                return;
            }

            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                // 若光标从某个折叠区域离开，则折叠它
                if (needCollapse && isTargetFoldRegion(lastRegion)) {
                    lastRegion.setExpanded(false);
                }

                // 若光标进入某个区域，则展开它
                if (needExpand) {
                    currentRegion.setExpanded(true);
                }

                lastRegion = currentRegion;
            });
        }
    }

    public static class Before implements FileEditorManagerListener.Before {

        /**
         * 在文件被关闭之前触发的事件处理方法。
         * <p>
         * 对于给定的文件编辑器管理器和虚拟文件，先尝试折叠所有折叠区域，然后调用超类的beforeFileClosed方法。
         * </p>
         *
         * @param source 文件编辑器管理器的实例，触发此事件的来源。
         * @param file   要被关闭的虚拟文件。
         */
        @Override
        public void beforeFileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
            // 在文件关闭前尝试折叠所有折叠代码区域
            collapseFoldRegion(source);
            // 调用父类的
            FileEditorManagerListener.Before.super.beforeFileClosed(source, file);
        }
    }

    /*public static class MyCaretListener implements CaretListener {
     *//**
     * Called when the caret is moved.
     *
     * @param e the event containing information about the caret movement.
     *//*
        @Override
        public void caretPositionChanged(@NotNull CaretEvent e) {
            Editor editor = e.getEditor();
            if (editor instanceof FileEditor) {
                collapseFoldRegion(editor);
            }
        }

    }*/

    /*public static class MyEditorMouseMotionListener implements EditorMouseMotionListener {
     *//**
     * Called when the mouse is moved over the editor and no mouse buttons are pressed.
     *
     * @param e the event containing information about the mouse movement.
     *//*
        @Override
        public void mouseMoved(@NotNull EditorMouseEvent e) {
            EditorMouseMotionListener.super.mouseMoved(e);
            Editor editor = e.getEditor();
            if (editor instanceof FileEditor) {
                collapseFoldRegion(editor);
            }
        }

    }*/
}
