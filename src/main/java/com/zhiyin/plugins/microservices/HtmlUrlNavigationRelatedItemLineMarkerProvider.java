package com.zhiyin.plugins.microservices;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.zhiyin.plugins.resources.MyIcons;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 为 HTML 文件中的 URL（包括 JS 脚本字符串）提供导航到对应 Controller 方法的行标记。
 * <p>
 * 特点：
 * - 仅对 HTML 文件生效
 * - 仅注册到叶子节点（XML_ATTRIBUTE_VALUE_TOKEN 或 JS:STRING_LITERAL）
 * - 支持标签属性中的 URL（如 href="../xxx"）
 * - 支持 JS 代码块中的 URL（如 fetch("../xxx")）
 * - 使用 ControllerMappingService 缓存加速查找
 */
@SuppressWarnings("DuplicatedCode")
public class HtmlUrlNavigationRelatedItemLineMarkerProvider extends RelatedItemLineMarkerProvider {

    @Override
    public void collectNavigationMarkers(
            @NotNull PsiElement element,
            @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result
    ) {
        PsiFile psiFile = element.getContainingFile();
        if (psiFile == null) return;

        // 仅处理 HTML 文件
        String fileType = psiFile.getFileType().getName();
        if (!"HTML".equalsIgnoreCase(fileType)) return;

        // 仅处理叶子节点
        if (!(element instanceof LeafPsiElement)) return;

        // 获取节点类型（如 XML_ATTRIBUTE_VALUE_TOKEN、JS:STRING_LITERAL）
        String elementType = element.getNode().getElementType().toString();
        if (!("XML_ATTRIBUTE_VALUE_TOKEN".equals(elementType) || "JS:STRING_LITERAL".equals(elementType))) {
            return;
        }

        // 提取字符串内容
        String text = element.getText();
        if (text == null || text.isBlank()) return;

        // 去掉引号
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1);
        }

        // 只识别相对路径形式的 URL（根据你项目风格，可调整规则）
        if (!text.startsWith("../") && !text.startsWith("/") && text.length() < 5) {
            return;
        }

        String url = UrlResolver.normalize(text);

        // 查 ControllerMappingService 缓存
        Project project = element.getProject();
        ControllerMappingService service = ControllerMappingService.getInstance(project);
        Map<String, List<MethodInfo>> mapping = service.getMappingSnapshot();
        if (mapping.isEmpty()) {
            System.out.println("[Microservices] 扫描中...");
            service.startScan(false);
            return;
        }

        List<MethodInfo> methodInfos = mapping.get(url);
        if (methodInfos == null || methodInfos.isEmpty()) {
            // "/Produce/layout/getDataGridColumns" -> "/{module}/layout/getDataGridColumns"
            // "/Basic/layout/getDataGridColumns" -> "/{module}/layout/getDataGridColumns"
            String replacedFirst = url.replaceFirst("/[a-zA-Z]+/", "/{module}/");
            methodInfos = mapping.get(replacedFirst);
            if (methodInfos == null || methodInfos.isEmpty()) {
                return;
            }
            // return;
        }

        // 构建目标 PsiElement 列表
        List<PsiElement> targets = new ArrayList<>();
        for (MethodInfo info : methodInfos) {
            PsiMethod targetMethod = info.pointer().getElement();
            if (targetMethod != null) {
                targets.add(targetMethod);
            }
        }

        if (targets.isEmpty()) return;

        // 使用与现有插件一致的方式创建导航图标
        NavigationGutterIconBuilder<PsiElement> builder = NavigationGutterIconBuilder
                .create(MyIcons.pandaIconSVG16_2)
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .setTargets(targets)
                .setTooltipText("HTML / JS URLs → Controller Method")
                .setPopupTitle("HTML / JS URLs → Controller Method");

        RelatedItemLineMarkerInfo<PsiElement> markerInfo = builder.createLineMarkerInfo(element);
        result.add(markerInfo);
    }
}
