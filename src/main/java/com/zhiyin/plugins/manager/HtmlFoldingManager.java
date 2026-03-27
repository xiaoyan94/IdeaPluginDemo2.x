package com.zhiyin.plugins.manager;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.zhiyin.plugins.i18n.I18nCacheManager;
import com.zhiyin.plugins.renderer.EditableHtmlFoldingRenderer;
import com.zhiyin.plugins.resources.Constants;
import com.zhiyin.plugins.utils.MyPropertiesUtil;
import com.zhiyin.plugins.utils.MyPsiUtil;
import e.Y.S;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intellij.openapi.util.text.StringUtil.isNotEmpty;
import static com.zhiyin.plugins.utils.MyPsiUtil.*;

public class HtmlFoldingManager implements Disposable {
    private static final Key<HtmlFoldingManager> MANAGER_KEY = Key.create("html.folding.manager");

    private final Editor editor;
    private final Project project;
    private final Map<String, Inlay<EditableHtmlFoldingRenderer>> inlayMap = new HashMap<>();
    private final Map<Inlay<EditableHtmlFoldingRenderer>, EditableHtmlFoldingRenderer> rendererMap = new HashMap<>();

    private static final ExecutorService EXECUTOR =
            com.intellij.util.concurrency.AppExecutorUtil.createBoundedApplicationPoolExecutor("HtmlFoldingExecutor", 2);
    private final I18nCacheManager i18nCacheManager;

    private HtmlFoldingManager(@NotNull Editor editor) {
        this.editor = editor;
        this.project = editor.getProject();
        this.i18nCacheManager = project.getService(I18nCacheManager.class);

        // 鼠标点击监听
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override
            public void mouseClicked(@NotNull EditorMouseEvent event) {
                if (event.getMouseEvent().getClickCount() == 1) {
                    handleMouseClick(event);
                }
            }
        });

        // 鼠标移动悬停监听
        editor.addEditorMouseMotionListener(new EditorMouseMotionListener() {
            @Override
            public void mouseMoved(@NotNull EditorMouseEvent e) {
                handleMouseHover(e.getMouseEvent().getPoint());
            }
        });

        // 文档变化监听
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                SwingUtilities.invokeLater(() -> updateInlays());
            }
        }, this);

        // VFS变化监听
        if (project != null) {
            project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                @Override
                public void after(@NotNull List<? extends VFileEvent> events) {
                    for (VFileEvent event : events) {
                        if (event instanceof VFileContentChangeEvent) {
                            VirtualFile file = event.getFile();
                            if (file.isValid() && "properties".equals(file.getExtension()) && file.getNameWithoutExtension().endsWith("zh_CN")) {
                                ApplicationManager.getApplication().invokeLater(HtmlFoldingManager.this::updateInlays);
                            }
                        }
                    }
                }
            });
        }

        // 初始化 inlay
        updateInlays();
    }

    public static HtmlFoldingManager getInstance(@NotNull Editor editor) {
        HtmlFoldingManager manager = editor.getUserData(MANAGER_KEY);
        if (manager == null) {
            manager = new HtmlFoldingManager(editor);
            editor.putUserData(MANAGER_KEY, manager);
        }
        return manager;
    }

    private void handleMouseClick(EditorMouseEvent event) {
        Point clickPoint = event.getMouseEvent().getPoint();

        for (Map.Entry<Inlay<EditableHtmlFoldingRenderer>, EditableHtmlFoldingRenderer> entry : rendererMap.entrySet()) {
            Inlay<EditableHtmlFoldingRenderer> inlay = entry.getKey();
            EditableHtmlFoldingRenderer renderer = entry.getValue();

            if (!inlay.isValid()) continue;
            renderer.bindInlay(inlay);

            int offset = inlay.getOffset();
            LogicalPosition logicalPos = editor.offsetToLogicalPosition(offset);
            Point inlayPoint = editor.logicalPositionToXY(logicalPos);

            int width = renderer.calcWidthInPixels(inlay);
            int height = editor.getLineHeight();

            if (clickPoint.x >= inlayPoint.x && clickPoint.x <= inlayPoint.x + width &&
                    clickPoint.y >= inlayPoint.y && clickPoint.y <= inlayPoint.y + height) {
                renderer.showTranslateDialog();
                break;
            }
        }
    }

    private void handleMouseHover(Point point) {
        for (Map.Entry<Inlay<EditableHtmlFoldingRenderer>, EditableHtmlFoldingRenderer> entry : rendererMap.entrySet()) {
            Inlay<EditableHtmlFoldingRenderer> inlay = entry.getKey();
            EditableHtmlFoldingRenderer renderer = entry.getValue();
            if (!inlay.isValid()) continue;

            int offset = inlay.getOffset();
            Point inlayPoint = editor.logicalPositionToXY(editor.offsetToLogicalPosition(offset));
            int width = renderer.calcWidthInPixels(inlay);
            int height = editor.getLineHeight();

            boolean hovered = point.x >= inlayPoint.x && point.x <= inlayPoint.x + width &&
                    point.y >= inlayPoint.y && point.y <= inlayPoint.y + height;
            renderer.setHovered(hovered);
        }
    }

    public void updateInlays() {
        if (project == null || editor.isDisposed()) return;

        String text = editor.getDocument().getText();
        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) return;

        Pattern[] patterns = Constants.getPatternsByExtension(virtualFile.getExtension());

        ReadAction.nonBlocking(() -> {
            // 保存所有匹配，key 用 startOffset，方便在 UI 线程对比和更新
            Map<String, MatchInfo> newMatches = new HashMap<>();
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    int startOffset = matcher.start();
                    int endOffset = matcher.end();
                    String matchedText = matcher.group();
                    String key = matcher.group(1);
                    newMatches.put(key + "_" + startOffset, new MatchInfo(startOffset, endOffset, key, matchedText));
                }
            }
            return newMatches;
        }).finishOnUiThread(ModalityState.defaultModalityState(), newMatches -> {
            if (editor.isDisposed()) return;

            Set<String> toRemove = new HashSet<>();

            // 1. 删除不再匹配的 inlay
            for (Map.Entry<String, Inlay<EditableHtmlFoldingRenderer>> entry : inlayMap.entrySet()) {
                String mapKey = entry.getKey();
                Inlay<EditableHtmlFoldingRenderer> inlay = entry.getValue();
                // int offset = Integer.parseInt(mapKey.split("_")[1]); // key_offset
                MatchInfo match = newMatches.get(mapKey);
                String newText = match != null ? match.matchedText : null;

                if (newText == null || !newText.equals(inlay.getRenderer().getOriginalText())) {
                    if (inlay.isValid()) inlay.dispose();
                    toRemove.add(mapKey);
                }
            }
            toRemove.forEach(inlayMap::remove);

            // 2. 创建新的 inlay
            for (MatchInfo match : newMatches.values()) {
                String mapKey = match.key + "_" + match.startOffset; // 保持原逻辑 key
                if (!inlayMap.containsKey(mapKey)) {
                    EditableHtmlFoldingRenderer renderer = new EditableHtmlFoldingRenderer(match.matchedText, match.value, editor, match.startOffset, match.endOffset);
                    Inlay<EditableHtmlFoldingRenderer> inlay = editor.getInlayModel().addInlineElement(match.startOffset, renderer);
                    if (inlay != null) {
                        inlayMap.put(mapKey, inlay);
                        rendererMap.put(inlay, renderer);
                    }
                }
            }

        }).coalesceBy(Arrays.asList(this.getClass(), editor, virtualFile)).submit(EXECUTOR);
    }


    /**
     * 旧版本：偶有卡顿
     */
    @Deprecated
    public void updateInlaysOld() {
        if (project == null || editor.isDisposed()) return;

        String text = editor.getDocument().getText();
        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) return;

        Pattern[] patterns = Constants.getPatternsByExtension(virtualFile.getExtension());

        ReadAction.nonBlocking(() -> {
                    Map<Integer, EditableHtmlFoldingRenderer> rendererData = new HashMap<>();
                    for (Pattern pattern : patterns) {
                        Matcher matcher = pattern.matcher(text);
                        while (matcher.find()) {
                            int startOffset = matcher.start();
                            int endOffset = matcher.end();
                            String matchedText = matcher.group();
                            EditableHtmlFoldingRenderer renderer =
                                    new EditableHtmlFoldingRenderer(matchedText, matchedText, editor, startOffset, endOffset);
                            rendererData.put(startOffset, renderer);
                        }
                    }
                    return rendererData;
                }).finishOnUiThread(ModalityState.defaultModalityState(), rendererData -> {
                    if (editor.isDisposed()) return;

                    // 清理旧 inlay
                    clearAllInlays();

                    // 获取可见区域行范围
                    Rectangle visibleArea = editor.getScrollingModel().getVisibleArea();
                    int startLine = editor.xyToLogicalPosition(new Point(0, visibleArea.y)).line;
                    int endLine = editor.xyToLogicalPosition(new Point(0, visibleArea.y + visibleArea.height)).line;

                    for (Map.Entry<Integer, EditableHtmlFoldingRenderer> entry : rendererData.entrySet()) {
                        int offset = entry.getKey();
                        int line = editor.getDocument().getLineNumber(offset);
                        if (line < startLine || line > endLine) continue;

                        EditableHtmlFoldingRenderer renderer = entry.getValue();
                        Inlay<EditableHtmlFoldingRenderer> inlay = editor.getInlayModel().addInlineElement(offset, renderer);
                        if (inlay != null) {
                            String mapKey = offset + "_" + line;
                            inlayMap.put(mapKey, inlay);
                            rendererMap.put(inlay, renderer);
                        }
                    }
                }).coalesceBy(Arrays.asList(this.getClass(), editor, virtualFile))
                .submit(EXECUTOR);
    }

    private void clearAllInlays() {
        for (Inlay<EditableHtmlFoldingRenderer> inlay : inlayMap.values()) {
            if (inlay.isValid()) inlay.dispose();
        }
        inlayMap.clear();
        rendererMap.clear();
    }

    @Override
    public void dispose() {
        clearAllInlays();
    }

    private String getI18nValue(String key, int startOffset) {
        String originalText = key;
        key = key.replaceAll("\"", "");
        if (key.isEmpty()) return originalText;

        // 1. 获取项目实例
        Project project = editor.getProject();
        if (project == null) {
            return originalText;
        }

        // 2. 从编辑器文档获取虚拟文件
        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (virtualFile == null) {
            return originalText;
        }

        // 3. 使用 PsiManager 获取 PsiFile
        PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        if (psiFile == null) {
            return originalText;
        }

        // 4. 现在可以使用 PsiFile 的 findElementAt 方法了
        PsiElement element = psiFile.findElementAt(startOffset);
        if (element == null) return "${" + key + "}";

        Module module = MyPsiUtil.getModuleByPsiElement(element);
        boolean isInLibrary = ProjectFileIndex.getInstance(project).isInLibrary(virtualFile);
        if (isInLibrary) {
            module = null;
        }
        String moduleName = module == null ? null : module.getName();
        /*if (module == null) {
            if (!isInLibrary) {
                return "${" + key + "}"; // TODO 从Library中获取
            } else {
                // 如果从jar包打开，则module默认取模块名包含basic的模块
                Module[] modules = ModuleManager.getInstance(project).getModules();
                for (Module m : modules) {
                    if (m.getName().toLowerCase().contains("basic")) {
                        module = m;
                        break;
                    }
                }
                if (module == null) return "${" + key + "}";;
            }
        }*/

        // 默认显示 key
        String defaultVal = "${" + key + "}";

        String extension = virtualFile.getExtension();
        extension = extension == null ? "" : extension.toLowerCase();
        switch (extension) {
            case "html":
            case "ftl":
            case "htm":
            case "js":
            case "java":
            case "jsp":
                // 查module和web的i18n
                // String val = MyPropertiesUtil.findModuleWebI18nPropertyValue(project, module, key);
                // 从缓存中获取
                Map<String, String> valuesWeb = i18nCacheManager.getValuesByKey(moduleName, I18nCacheManager.ResourceType.WEB, key);
                Map<String, String> valuesOther = i18nCacheManager.getValuesByKey(moduleName, I18nCacheManager.ResourceType.OTHER, key);
                valuesWeb.putAll(valuesOther);
                String val = valuesWeb.get(I18nCacheManager.ZH_CN);
                if (isNotEmpty(val)) return val;

                return defaultVal;

            case "xml":
                if (!(psiFile instanceof XmlFile xmlFile)) return defaultVal;
                XmlTag rootTag = xmlFile.getRootTag();
                if (rootTag == null) return defaultVal;

                String rootTagName = rootTag.getName();
                if ("mapper".equalsIgnoreCase(rootTagName)) {
                    // Imp***Mapper
                    // String moduleWebI18nPropertyValue = MyPropertiesUtil.findModuleWebI18nPropertyValue(project, module, key);
                    Map<String, String> valuesMapperWeb = i18nCacheManager.getValuesByKey(moduleName, I18nCacheManager.ResourceType.WEB, key);
                    Map<String, String> valuesMapperOther = i18nCacheManager.getValuesByKey(moduleName, I18nCacheManager.ResourceType.OTHER, key);
                    valuesMapperWeb.putAll(valuesMapperOther);
                    String valMapper = valuesMapperWeb.get(I18nCacheManager.ZH_CN);
                    if (isNotEmpty(valMapper)) return valMapper;
                } else if ("DataGrid".equalsIgnoreCase(rootTagName) || "ViewDefine".equalsIgnoreCase(rootTagName)) {
                    // Layout
                    Map<String, String> valuesDataGrid = i18nCacheManager.getValuesByKey(moduleName, I18nCacheManager.ResourceType.DATAGRID, key);
                    String valDataGrid = valuesDataGrid.get(I18nCacheManager.ZH_CN);
                    if (isNotEmpty(valDataGrid)) return valDataGrid;
                }
                return defaultVal;

            default:
                return defaultVal;
        }

    }

    // 定义实体类保存匹配信息
    class MatchInfo {
        final int startOffset;
        final int endOffset;
        final String matchedText;
        final String key;
        final String value;

        MatchInfo(int startOffset, int endOffset, String key, String matchedText) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.key = key;
            this.matchedText = matchedText;
            this.value = getI18nValue(key, startOffset);
        }
    }
}
