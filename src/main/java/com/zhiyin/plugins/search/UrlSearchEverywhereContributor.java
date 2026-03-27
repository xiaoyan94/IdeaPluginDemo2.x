package com.zhiyin.plugins.search;

import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.Processor;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.zhiyin.plugins.microservices.ControllerMappingService;
import com.zhiyin.plugins.microservices.MethodInfo;
import com.zhiyin.plugins.microservices.UrlResolver;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.resources.MyIcons;
import com.zhiyin.plugins.service.MyToolWindowService;
import com.zhiyin.plugins.service.PluginDisposable;
import com.zhiyin.plugins.toolWindow.NewControllerToolWindowUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * 在 Search Everywhere 中支持 /url 搜索 Controller 方法
 */
public class UrlSearchEverywhereContributor implements SearchEverywhereContributor<MethodInfo> {

    private final Project project;

    public UrlSearchEverywhereContributor(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getSearchProviderId() {
        return "UrlSearchEverywhereContributor";
    }

    @Override
    public @NotNull String getGroupName() {
        return "Controller URLs";
    }

    /**
     * <p>Defines weight for sorting contributors (<b>not elements</b>).
     * This weight is used for example for ordering groups in results list when splitting by groups is enabled.</p>
     *
     * <p>Please do not use this method to set found items weights. For this purposes look at {@link SearchEverywhereContributor#getElementPriority(Object, String)}
     * and {@link WeightedSearchEverywhereContributor#fetchWeightedElements(String, ProgressIndicator, Processor)} methods.</p>
     */
    @Override
    public int getSortWeight() {
        // 返回一个整数权重，越大越靠前
        return 100; // 默认权重
    }

    /**
     * Defines if results found by this contributor can be shown in <i>Find</i> toolwindow.
     */
    @Override
    public boolean showInFindResults() {
        return false;
    }

    @Override
    public boolean isShownInSeparateTab() {
        return false; // 不创建独立 tab
    }

    @Override
    public @Nullable String getAdvertisement() {
        return "输入 /url <关键字> 搜索 Controller / Feign 映射";
    }

    /**
     * <p>Performs searching process. All found items will be passed to consumer.</p>
     * <p>Searching is performed until any of following events happens:
     * <ul>
     *   <li>all items which match {@code pattern} are found</li>
     *   <li>{@code progressIndicator} is cancelled</li>
     *   <li>{@code consumer} returns {@code false} for any item</li>
     * </ul></p>
     *
     * @param pattern           searching pattern used for matching
     * @param progressIndicator {@link ProgressIndicator} which can be used for tracking or cancelling searching process
     * @param consumer          items {@link Processor} which will receive any found item. When false is returned by consumer this contributor stops
     *                          searching process
     */
    @Override
    public void fetchElements(@NotNull String pattern, @NotNull ProgressIndicator progressIndicator, @NotNull Processor<? super MethodInfo> consumer) {

        // 只处理 /url 开头的输入
        if (!pattern.startsWith("/url")) {
            return;
        }

        String keyword = pattern.substring(4).trim();
        if (keyword.isEmpty()) {
            return;
        }

        // 读取 ControllerMappingService 的快照
        List<MethodInfo> results = ReadAction.compute(() -> {
            ControllerMappingService service = ControllerMappingService.getInstance(project);
            Map<String, List<MethodInfo>> snapshot = service.getMappingSnapshot();

            return snapshot.values()
                           .stream()
                           .flatMap(List::stream)
                           .filter(info -> UrlResolver.fuzzyMatchWithContains(UrlResolver.normalize(keyword), info.fullPath()))
                           .filter(info -> !Objects.equals(info.fullPath(), "/"))
                           .sorted(Comparator.comparing(MethodInfo::source).thenComparing(MethodInfo::moduleName, Comparator.nullsLast(String::compareTo)).thenComparing(MethodInfo::className))
                           .limit(200)
                           .toList();
        });

        // 将找到的元素传递给 consumer，遵循 Processor 的返回值逻辑
        for (MethodInfo info : results) {
            if (progressIndicator.isCanceled()) return; // 用户取消
            boolean shouldContinue = consumer.process(info); // 返回 false 停止搜索
            if (!shouldContinue) return;
        }

        // 如果没有匹配项，可以触发 ToolWindow 搜索
        if (results.isEmpty()) {
            AppExecutorUtil.getAppExecutorService().submit(() -> ReadAction.run(() -> openToolWindowAndSearch(keyword)));
        }
    }

    /**
     * 打开 ToolWindow 并执行搜索（联动逻辑）
     */
    private void openToolWindowAndSearch(@NotNull String keyword) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OneClickNavigationToolWindow");
        if (toolWindow != null) {
            String finalUrl = keyword;
            ControllerMappingService.getInstance(project).startScan(true, () -> {
                // 这里是在 EDT 上安全执行
                MyPluginMessages.showInfo("缓存已刷新", "Controller 缓存已重新加载", project);

                NewControllerToolWindowUI ui = project.getService(MyToolWindowService.class).getUI();
                if (ui != null) {
                    ui.setUrlText(finalUrl);
                    ui.clickJumpButton();
                }
                toolWindow.show(null);

            });
        }
    }


    @Override
    public boolean processSelectedItem(@NotNull MethodInfo item, int modifiers, @NotNull String searchText) {
        ReadAction.nonBlocking(() -> item.pointer().getElement())
                  .inSmartMode(project)
                  .expireWith(PluginDisposable.getInstance(project))
                  .finishOnUiThread(ModalityState.defaultModalityState(), method -> {
                      if (method != null) {
                          NavigationUtil.activateFileWithPsiElement(method);
                      } else {
                          MyPluginMessages.showWarning("方法失效", "无法定位到方法：" + item.methodSignature(), project);
                      }
                  })
                  .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
        return true;
    }

    /**
     * Creates {@link ListCellRenderer} for found items.
     */
    @Override
    public @NotNull ListCellRenderer<Object> getElementsRenderer() {
        System.out.println("=== getElementsRenderer called ===");
        return new ColoredListCellRenderer<Object>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<?> list,
                                                 Object value,
                                                 int index,
                                                 boolean selected,
                                                 boolean hasFocus) {
                if (!(value instanceof MethodInfo methodInfo)) {
                    return;
                }

                setIcon(MyIcons.pandaIconSVG16_2);

                append(methodInfo.fullPath(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);

                if (methodInfo.moduleName() != null) {
                    append(" [" + methodInfo.moduleName() + "]", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }

                String source = methodInfo.source() == MethodInfo.Source.CONTROLLER ? " Controller" : " Feign";
                append(source, SimpleTextAttributes.GRAY_ATTRIBUTES);

                append(" → " + methodInfo.className() + "#" + methodInfo.methodSignature(),
                       SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        };
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull MethodInfo element, @NotNull String dataId) {
        return switch (dataId) {
            case "text" -> element.fullPath();       // 返回 URL 文本
            case "psi" -> element.pointer().getElement(); // 返回 PsiElement，用于导航
            default -> null;
        };
    }



}
