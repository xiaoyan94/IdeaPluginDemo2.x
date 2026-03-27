package com.zhiyin.plugins.manager;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.BoundedTaskExecutor;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.renderer.EditableHtmlFoldingRenderer;
import com.zhiyin.plugins.resources.Constants;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.awt.Point;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.zhiyin.plugins.utils.MyPsiUtil.*;

/**
 * 旧版本：偶有卡顿
 *
 * @see com.zhiyin.plugins.manager.HtmlFoldingManager
 */
@Deprecated
public class HtmlFoldingManagerOld implements Disposable {
    private static final Key<HtmlFoldingManagerOld> MANAGER_KEY = Key.create("html.folding.manager");
    private static final Pattern PATTERN = Pattern.compile("<@message\\s+key=['\"](.*?)['\"]\\s*/>");

    // 支持的匹配模式列表
    private static final List<Pattern> PATTERNS = List.of(
            // HTML
            Pattern.compile("<@message\\s+key=['\"](.*?)['\"]\\s*/>"),
            // JS zhiyin.i18n.translate("key")
            Pattern.compile("zhiyin\\.i18n\\.translate\\(['\"](.*?)['\"]\\)")
            // 以后如果有 i18n.t("xxx") 可以加：
            // Pattern.compile("i18n\\.t\\(['\"](.*?)['\"]\\)")
            // Pattern.compile("\\$t\\(['\"](.*?)['\"]\\)")
    );


    private final Editor editor;
    private final Project project;
    private final Map<Integer, Inlay<EditableHtmlFoldingRenderer>> inlayMap = new HashMap<>();
    private final Map<Inlay<EditableHtmlFoldingRenderer>, EditableHtmlFoldingRenderer> rendererMap = new HashMap<>();

    private HtmlFoldingManagerOld(@NotNull Editor editor) {
        this.editor = editor;
        this.project = editor.getProject();

        // 添加鼠标监听器处理点击事件
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override
            public void mouseClicked(@NotNull EditorMouseEvent event) {
                if (event.getMouseEvent().getClickCount() == 1) { // 单击事件
                    handleMouseClick(event);
                }
            }
        });

        // 当鼠标移动到 inlay 区域时，setHovered(true) 被调用，绘制时就会显示悬停效果；移出区域则 setHovered(false)。
        editor.addEditorMouseMotionListener(new EditorMouseMotionListener() {
            @Override
            public void mouseMoved(@NotNull EditorMouseEvent e) {
                Point point = e.getMouseEvent().getPoint();

                for (Map.Entry<Inlay<EditableHtmlFoldingRenderer>, EditableHtmlFoldingRenderer> entry : rendererMap.entrySet()) {
                    Inlay<EditableHtmlFoldingRenderer> inlay = entry.getKey();
                    EditableHtmlFoldingRenderer renderer = entry.getValue();

                    if (!inlay.isValid()) continue;

                    // 获取 inlay 的位置和大小
                    int offset = inlay.getOffset();
                    Point inlayPoint = editor.logicalPositionToXY(editor.offsetToLogicalPosition(offset));
                    int width = renderer.calcWidthInPixels(inlay);
                    int height = editor.getLineHeight();

                    boolean hovered = point.x >= inlayPoint.x && point.x <= inlayPoint.x + width &&
                            point.y >= inlayPoint.y && point.y <= inlayPoint.y + height;

                    renderer.setHovered(hovered);
                }
            }
        });

        // 监听文档变化
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                SwingUtilities.invokeLater(() -> updateInlays());
            }
        }, this);

        // 监听 VFS 变化
        if (project != null) {
            project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                @Override
                public void after(@NotNull List<? extends VFileEvent> events) {
                    for (VFileEvent event : events) {
                        if (event instanceof VFileContentChangeEvent) {
                            VirtualFile file = event.getFile();
                            if (file.isValid() && "properties".equals(file.getExtension()) &&
                                    file.getNameWithoutExtension().endsWith("zh_CN")) {
                                /*DumbService.getInstance(project).runWhenSmart(() -> {
                                    ApplicationManager.getApplication().runReadAction(() -> {
                                        // 更新 inlays
                                        updateInlays();
                                    });
                                });*/
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    updateInlays();
                                });
                            }
                        }
                    }
                }
            })
            ;
        }

        // 初始化
        updateInlays();
    }

    public static HtmlFoldingManagerOld getInstance(@NotNull Editor editor) {
        HtmlFoldingManagerOld manager = editor.getUserData(MANAGER_KEY);
        if (manager == null) {
            manager = new HtmlFoldingManagerOld(editor);
            editor.putUserData(MANAGER_KEY, manager);
        }
        return manager;
    }

    private void handleMouseClick(EditorMouseEvent event) {
        Point clickPoint = event.getMouseEvent().getPoint();

        // 检查点击位置是否在某个 inlay 上
        for (Map.Entry<Inlay<EditableHtmlFoldingRenderer>, EditableHtmlFoldingRenderer> entry : rendererMap.entrySet()) {
            Inlay<EditableHtmlFoldingRenderer> inlay = entry.getKey();
            EditableHtmlFoldingRenderer renderer = entry.getValue();

            if (inlay.isValid()) {
                renderer.bindInlay(inlay);   // 绑定 inlay 引用

                // 获取 inlay 的边界
                int offset = inlay.getOffset();
                LogicalPosition logicalPos = editor.offsetToLogicalPosition(offset);
                Point inlayPoint = editor.logicalPositionToXY(logicalPos);

                // 计算 inlay 的大小
                int width = renderer.calcWidthInPixels(inlay);
                int height = editor.getLineHeight();

                // 检查点击是否在 inlay 范围内
                if (clickPoint.x >= inlayPoint.x && clickPoint.x <= inlayPoint.x + width &&
                        clickPoint.y >= inlayPoint.y && clickPoint.y <= inlayPoint.y + height) {

                    // 触发编辑对话框
                    // renderer.showEditDialog();
                    // 直接触发翻译弹窗
                    renderer.showTranslateDialog();
                    break;
                }
            }
        }
    }

    /*private static final ExecutorService EXECUTOR =
            AppExecutorUtil.createBoundedApplicationPoolExecutor("HtmlFoldingExecutor", 2);

    private void updateInlays() {
        if (project == null) return;

        // 清除所有现有的 inlay
        clearAllInlays();

        // 扫描文档，创建新的 inlay
        String text = editor.getDocument().getText();
        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) return;
        String extension = virtualFile.getExtension();
        Pattern[] patterns = Constants.getPatternsByExtension(extension);

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                int startOffset = matcher.start();
                int endOffset = matcher.end();
                String matchedText = matcher.group();
                String key = matcher.group(1);

                ReadAction.nonBlocking(() -> {
                            // 创建渲染器
                            EditableHtmlFoldingRenderer renderer = new EditableHtmlFoldingRenderer(matchedText, editor, startOffset, endOffset);
                            return renderer;
                        }).finishOnUiThread(ModalityState.defaultModalityState(), renderer -> {
                            // 在 UI 线程创建 inlay
                            if (editor.isDisposed()) return;

                            Inlay<EditableHtmlFoldingRenderer> inlay = editor.getInlayModel().addInlineElement(startOffset, renderer);

                            if (inlay != null) {
                                String mapKey = key + "_" + startOffset;
                                inlayMap.put(mapKey, inlay);
                                rendererMap.put(inlay, renderer);
                            }
                        })//.submit(AppExecutorUtil.getAppExecutorService()); // 提交给 IntelliJ 的后台线程池
                        .coalesceBy(Arrays.asList(this.getClass(), virtualFile))
                        .submit(EXECUTOR);

                // 回到 UI 线程创建 inlay（必须在 EDT）
                *//*ApplicationManager.getApplication().invokeLater(() -> {
                    // 创建 inlay
                    Inlay<EditableHtmlFoldingRenderer> inlay = editor.getInlayModel()
                            .addInlineElement(startOffset, renderer);

                    if (inlay != null) {
                        String mapKey = key + "_" + startOffset;
                        inlayMap.put(mapKey, inlay);
                        rendererMap.put(inlay, renderer);
                    }
                });*//*
            }
        }
    }*/

    private static final ExecutorService EXECUTOR =
            AppExecutorUtil.createBoundedApplicationPoolExecutor("HtmlFoldingExecutor", 2);

    private final Alarm updateAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);

    private void scheduleUpdateInlays() {
        updateAlarm.cancelAllRequests();
        updateAlarm.addRequest(this::updateInlays, 300); // 延迟300ms合并更新
    }

    private void updateInlays() {
        if (project == null || editor.isDisposed()) return;

        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) return;
        String extension = virtualFile.getExtension();
        Pattern[] patterns = Constants.getPatternsByExtension(extension);
        String text = editor.getDocument().getText();

        ReadAction.nonBlocking(() -> {
                    Map<Integer, EditableHtmlFoldingRenderer> newRenderers = new HashMap<>();
                    for (Pattern pattern : patterns) {
                        Matcher matcher = pattern.matcher(text);
                        while (matcher.find()) {
                            int startOffset = matcher.start();
                            int endOffset = matcher.end();
                            String matchedText = matcher.group();
                            EditableHtmlFoldingRenderer renderer = new EditableHtmlFoldingRenderer(matchedText, matchedText, editor, startOffset, endOffset);
                            newRenderers.put(startOffset, renderer);
                        }
                    }
                    return newRenderers;
                })
                .finishOnUiThread(ModalityState.defaultModalityState(), renderers -> {
                    if (editor.isDisposed()) return;

                    // 清理失效 inlay，只清理旧的，不全部清除
                    inlayMap.entrySet().removeIf(e -> {
                        Inlay<EditableHtmlFoldingRenderer> inlay = e.getValue();
                        if (!inlay.isValid()) {
                            rendererMap.remove(inlay);
                            return true;
                        }
                        return false;
                    });

                    // 创建新的 inlay
                    renderers.forEach((offset, renderer) -> {
                        if (!inlayMap.containsKey(offset)) {
                            Inlay<EditableHtmlFoldingRenderer> inlay = editor.getInlayModel().addInlineElement(offset, renderer);
                            if (inlay != null) {
                                inlayMap.put(offset, inlay);
                                rendererMap.put(inlay, renderer);
                            }
                        }
                    });
                })
                .coalesceBy(editor, virtualFile) // 唯一标识符避免冲突
                .submit(EXECUTOR);
    }



    private void clearAllInlays() {
        for (Inlay<EditableHtmlFoldingRenderer> inlay : inlayMap.values()) {
            if (inlay.isValid()) {
                inlay.dispose();
            }
        }
        inlayMap.clear();
        rendererMap.clear();
    }

    @Override
    public void dispose() {
        clearAllInlays();
    }
}
