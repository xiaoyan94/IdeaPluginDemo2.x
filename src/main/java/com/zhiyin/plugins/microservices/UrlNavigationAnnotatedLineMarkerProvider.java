package com.zhiyin.plugins.microservices;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.zhiyin.plugins.resources.MyIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leaf-only LineMarkerProvider for URL navigation.
 * - Registers only on PsiLiteralExpression (leaf node)
 * - Only analyzes strings inside specific annotations
 * - Supports multi-target navigation (Controller <-> Feign)
 * - Uses the same icon creation style as your existing code
 */
public class UrlNavigationAnnotatedLineMarkerProvider implements LineMarkerProvider {

    // Controller 方法级注解
    private static final Set<String> METHOD_LEVEL_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
    );

    // FeignClient 注解
    private static final String FEIGN_CLIENT_ANNOTATION = "org.springframework.cloud.openfeign.FeignClient";

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // 只处理叶子节点
        if (!(element instanceof PsiLiteralExpression literal)) {
            return null;
        }

        // 获取父级注解成员
//        PsiAnnotationMemberValue parentValue = getParentAnnotationMemberValue(literal);
//        if (parentValue == null) {
//            return null;
//        }

        PsiAnnotation annotation = getContainingAnnotation(literal);
        if (annotation == null) {
            return null;
        }

        String qualifiedName = annotation.getQualifiedName();
        if (qualifiedName == null) return null;

        // 只处理特定注解
        if (!METHOD_LEVEL_ANNOTATIONS.contains(qualifiedName) && !FEIGN_CLIENT_ANNOTATION.equals(qualifiedName)) {
            return null;
        }

        Object valueObj = literal.getValue();
        if (!(valueObj instanceof String url) || url.isBlank()) {
            return null;
        }

        // 查缓存
        Project project = element.getProject();
        ControllerMappingService service = ControllerMappingService.getInstance(project);
        Map<String, List<MethodInfo>> mapping = service.getMappingSnapshot();

        if (mapping.isEmpty()) {
            System.out.println("[Microservices] 扫描中...");
            service.startScan(false);
            return null;
        }

        boolean isFeignClient = false;

        PsiMethod method = PsiTreeUtil.getParentOfType(literal, PsiMethod.class);
        if (method != null) {
            PsiClass psiClass = method.getContainingClass();
            if (psiClass == null) {
                return null;
            }

            isFeignClient = isFeignClient(psiClass);

            String[] classPaths = ControllerScanner.extractPathsFromAnnotation(
                    psiClass.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
            );// 可能有多个 path，取第一个
            if (classPaths != null && classPaths.length > 0) {
                url = PathUtils.combine(classPaths[0], url);
                url = PathUtils.normalize(url);
            }
        }

        List<MethodInfo> methodInfos = mapping.get(url);
        if (methodInfos == null || methodInfos.isEmpty()) {
            return null;
        }

        // 构建目标 PsiElement 列表
        List<PsiElement> targets = getTargetPsiElements(methodInfos, isFeignClient);
        if (targets.isEmpty()) return null;

        // ⚡ 使用和原来一致的方式创建导航图标
        NavigationGutterIconBuilder<PsiElement> builder = NavigationGutterIconBuilder
                .create(MyIcons.pandaIconSVG16_2)
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .setTargets(targets)
                .setTooltipText("Feign Client URLs <-> RestController method")
                .setPopupTitle("Feign Client URLs <-> RestController Method");

        PsiElement leaf = literal.getFirstChild(); // PsiJavaToken
        if (leaf == null) return null;

        return builder.createLineMarkerInfo(leaf);
    }

    private static @NotNull List<PsiElement> getTargetPsiElements(List<MethodInfo> methodInfos, boolean isFeignClient) {
        List<PsiElement> targets = new ArrayList<>();
        for (MethodInfo info : methodInfos) {
            if (isFeignClient && MethodInfo.Source.FEIGN.equals(info.source())) {
                continue;
            } else if (!isFeignClient && MethodInfo.Source.CONTROLLER.equals(info.source())) {
                continue;
            }
            PsiMethod targetMethod = info.pointer().getElement();
            if (targetMethod != null) {

                targets.add(targetMethod);
            }
        }
        return targets;
    }

    /**
     * 获取字符串字面量的父级 PsiAnnotationMemberValue
     */
    private PsiAnnotationMemberValue getParentAnnotationMemberValue(PsiLiteralExpression literal) {
        PsiElement parent = literal.getParent();
        if (parent instanceof PsiAnnotationMemberValue memberValue) {
            return memberValue;
        }
        return null;
    }

    /**
     * 获取包含该成员值的注解
     */
    private PsiAnnotation getContainingAnnotation(PsiLiteralExpression literal) {
        return PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
    }

    private boolean isFeignClient(PsiClass psiClass) {
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (FEIGN_CLIENT_ANNOTATION.equals(qualifiedName)) {
                return true;
            }
        }
        return false;
    }
}
