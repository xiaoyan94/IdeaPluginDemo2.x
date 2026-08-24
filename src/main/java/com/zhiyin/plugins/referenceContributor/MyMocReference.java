package com.zhiyin.plugins.referenceContributor;

import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.highlighting.HighlightedReference;
import com.intellij.codeInsight.lookup.AutoCompletionPolicy;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlAttributeValue;
import com.zhiyin.plugins.oneClickNavigation.xml.domElements.Moc;
import com.zhiyin.plugins.oneClickNavigation.xml.utils.MyMapperUtils;
import com.zhiyin.plugins.resources.MyIcons;
import com.zhiyin.plugins.service.MyProjectService;
import com.zhiyin.plugins.utils.MyPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * bizCommonService.xxxMocData(..., "mocName", ...) / queryDaoDataT 风格调用中
 * Moc 名称字符串的引用：解析到 Moc XML 的 name 属性值。
 *
 * poly 化 + isReferenceTo 覆写后，除正向 Ctrl+B 跳转外，
 * Find Usages / Moc name 声明处 Ctrl+B / Rename 同步均可反查到该字符串。
 */
public class MyMocReference extends PsiReferenceBase<PsiLiteralExpression> implements PsiPolyVariantReference, HighlightedReference {

    /**
     * 懒缓存解析结果（multiResolve 高频调用；Moc 文件 map 查询 + 模块比对有开销）。
     * 元素失效（文件被编辑）时自动重算。
     */
    private volatile ResolveResult[] cachedResults;

    public MyMocReference(@NotNull PsiLiteralExpression element, boolean soft) {
        super(element, soft);
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        ResolveResult[] results = cachedResults;
        if (results == null || !areResultsValid(results)) {
            results = computeResolveResults();
            cachedResults = results;
        }
        return results;
    }

    private ResolveResult @NotNull [] computeResolveResults() {
        List<ResolveResult> results = new ArrayList<>();
        PsiLiteralExpression literal = getElement();
        String value = (String) literal.getValue();
        if (value == null) return results.toArray(new ResolveResult[0]);

        List<XmlAttributeValue> mocNameElements = MyMapperUtils.getMocListByName(literal.getProject(), value);
        Module callerModule = MyPsiUtil.getModuleByPsiElement(literal);
        // 只保留同模块的 Moc（保持原 resolve() 口径）；调用方模块判定不出（jar 内等）时不降级跨模块，
        // 宁可无目标也不误导
        for (XmlAttributeValue xmlAttributeValue : mocNameElements) {
            if (callerModule != null && callerModule.equals(MyPsiUtil.getModuleByPsiElement(xmlAttributeValue))) {
                results.add(new PsiElementResolveResult(xmlAttributeValue));
            }
        }
        return results.toArray(new ResolveResult[0]);
    }

    private static boolean areResultsValid(ResolveResult[] results) {
        for (ResolveResult result : results) {
            PsiElement element = result.getElement();
            if (element == null || !element.isValid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单一目标直接跳；多目标（多个模块同名 Moc）返回 null 弹选择框。
     */
    @Nullable
    @Override
    public PsiElement resolve() {
        ResolveResult[] resolveResults = multiResolve(false);
        return resolveResults.length == 1 ? resolveResults[0].getElement() : null;
    }

    /**
     * 反向匹配（Find Usages / 声明处 Ctrl+B / Rename 用法收集）统一判定：
     * 遍历 multiResolve 比对，与正向目标集合一致。
     */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        for (ResolveResult result : multiResolve(false)) {
            PsiElement resolved = result.getElement();
            if (resolved != null && getElement().getManager().areElementsEquivalent(resolved, element)) {
                return true;
            }
        }
        return super.isReferenceTo(element);
    }

    @Override
    public Object @NotNull [] getVariants() {
        PsiLiteralExpression element = getElement();
        Project project = element.getProject();
        MyProjectService myProjectService = project.getService(MyProjectService.class);
        List<LookupElement> variants = new ArrayList<>();
        Map<String, List<Moc>> mocFileMap = myProjectService.getMocFileMap();
        for (String mocName : mocFileMap.keySet()) {
            List<Moc> mocList = mocFileMap.get(mocName);
            if (mocList == null || mocList.isEmpty()) continue;
            if (mocList.stream().noneMatch(moc -> MyPsiUtil.isInSameModule(element, moc.getXmlElement()))) {
                continue;
            }
            LookupElement lookupElement = LookupElementBuilder.create(mocName)
                                                              .withIcon(MyIcons.pandaIconSVG16_2)
                                                              .withItemTextItalic(true)
                                                              .withTypeText("Moc", AllIcons.FileTypes.Xml, true)
                                                              .withBoldness(true)
                                                              .withCaseSensitivity(true)
//                    .withTailText("Moc", true)
                                                              .withAutoCompletionPolicy(
                                                                      AutoCompletionPolicy.ALWAYS_AUTOCOMPLETE)
                    ;
            // 包装成最高优先级
            LookupElement prioritized = PrioritizedLookupElement.withPriority(lookupElement, 1000.0);

            variants.add(prioritized);
        }
        return variants.toArray();
    }
}
