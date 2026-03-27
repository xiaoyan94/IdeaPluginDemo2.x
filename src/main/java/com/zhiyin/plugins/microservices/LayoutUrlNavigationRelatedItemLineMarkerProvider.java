package com.zhiyin.plugins.microservices;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.impl.source.xml.XmlTokenImpl;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.zhiyin.plugins.resources.MyIcons;
import com.zhiyin.plugins.utils.MyPsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@SuppressWarnings("DuplicatedCode")
public class LayoutUrlNavigationRelatedItemLineMarkerProvider extends RelatedItemLineMarkerProvider {

    @Override
    public void collectNavigationMarkers(@NotNull PsiElement element, @NotNull Collection<?
            super RelatedItemLineMarkerInfo<?>> result) {
        boolean isLayoutFile = MyPsiUtil.isLayoutFile(element);
        if (!isLayoutFile) {
            return;
        }

        PsiElement targetElement;

        if (element instanceof XmlTokenImpl) {
            targetElement = element;
            String elementType = ((LeafPsiElement) element).getElementType().toString();
            if (elementType.equals("XML_ATTRIBUTE_VALUE_TOKEN")) {
                element = element.getParent();
            }
        } else {
            return;
        }

        if (element instanceof XmlAttributeValue && element.getParent() instanceof XmlAttribute) {
            element = element.getParent();
        } else {
            return;
        }

        if (!List.of("value", "url").contains(((XmlAttribute) element).getName())) {
            return;
        }


        String stringValue = ((XmlAttribute) element).getValue();
        if (stringValue == null || stringValue.isBlank() || !stringValue.startsWith("../")) {
            return;
        }

        String url = stringValue;
        url = UrlResolver.normalize(url);

        // 查缓存
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
            return;
        }

        // 构建目标 PsiElement 列表
        List<PsiElement> targets = getTargetPsiElements(methodInfos);
        if (targets.isEmpty()) return;

        // ⚡ 使用和原来一致的方式创建导航图标
        NavigationGutterIconBuilder<PsiElement> builder = NavigationGutterIconBuilder
                .create(MyIcons.pandaIconSVG16_2)
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .setTargets(targets)
                .setTooltipText("Client URLs --> Controller method")
                .setPopupTitle("Client URLs --> Controller Method");

        PsiElement leaf = targetElement; // 叶子结点

        RelatedItemLineMarkerInfo<PsiElement> lineMarkerInfo = builder.createLineMarkerInfo(leaf);
        result.add(lineMarkerInfo);
    }

    private List<PsiElement> getTargetPsiElements(List<MethodInfo> methodInfos) {
        List<PsiElement> targets = new ArrayList<>();
        for (MethodInfo methodInfo : methodInfos) {
            targets.add(methodInfo.pointer().getElement());
        }
        return targets;
    }
}
