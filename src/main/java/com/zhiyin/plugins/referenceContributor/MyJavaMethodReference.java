package com.zhiyin.plugins.referenceContributor;

import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.highlighting.HighlightedReference;
import com.intellij.codeInsight.lookup.AutoCompletionPolicy;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.util.xml.GenericAttributeValue;
import com.zhiyin.plugins.oneClickNavigation.xml.domElements.Statement;
import com.zhiyin.plugins.oneClickNavigation.xml.utils.MyMapperUtils;
import com.zhiyin.plugins.resources.MyIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 扩展额外引用
 *
 * 同时解析到：
 * 1) DAO 接口中的 Java 方法（Ctrl+Click 直接跳转 / 补全列表）
 * 2) MyBatis Mapper XML 中对应的 <select>/<insert>/<update>/<delete id="..."> 标签
 */
final class MyJavaMethodReference extends PsiReferenceBase<PsiLiteralExpression> implements PsiPolyVariantReference, HighlightedReference {

    private final PsiClass psiClass;

    private final String methodName;

    /**
     * 懒缓存解析结果。multiResolve 在 hover / 高亮 / 查找用法时会被高频调用，
     * 缓存可避免反复执行 findMethodsByName 与 getStatementsByNamespace。
     * 当缓存中的 PSI 元素失效（如文件被编辑）时自动重算。
     */
    private volatile ResolveResult[] cachedResults;

    MyJavaMethodReference(@NotNull PsiLiteralExpression element, PsiClass psiClass, String methodName) {
        super(element);
        this.psiClass = psiClass;
        this.methodName = methodName;
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
        // 1) MyBatis Mapper XML 中对应的语句标签（namespace + id 匹配）
        String namespace = psiClass.getQualifiedName();
        if (namespace != null) {
            Project project = getElement().getProject();
            List<Statement> statements = MyMapperUtils.getStatementsByNamespace(project, namespace);
            statements.stream()
                      .filter(statement -> {
                          GenericAttributeValue<String> id = statement.getId();
                          return id != null && methodName.equals(id.getValue());
                      })
                      .map(statement -> statement.getId().getXmlAttributeValue())
                      .filter(Objects::nonNull)
                      .forEach(xmlAttr -> {
                          // 用包装元素作为导航目标：跳转仍精确落到 XML 的 id 属性值节点，
                          // 但展示文本使用不带双引号的方法名（见 StatementNavigationTarget）。
                          results.add(new PsiElementResolveResult(new StatementNavigationTarget(xmlAttr, methodName)));
                      });
        }
        // 2) DAO 接口中的 Java 方法
        for (PsiMethod psiMethod : psiClass.findMethodsByName(methodName, true)) {
            results.add(new PsiElementResolveResult(psiMethod));
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
     * 只有一个目标时直接跳转；多个目标时返回 null，
     * 让 GotoDeclarationAction 走 multiResolve 弹出选择列表。
     */
    @Nullable
    @Override
    public PsiElement resolve() {
        ResolveResult[] resolveResults = multiResolve(false);
        return resolveResults.length == 1 ? resolveResults[0].getElement() : null;
    }

    /**
     * 反向匹配（Find Usages / 声明处 Ctrl+B / Rename 用法收集）的统一判定入口。
     * 基类 PsiReferenceBase 默认只认 resolve() 的单一结果——multiResolve 多目标时
     * resolve() 恒为 null，本引用对反向查找隐身。此处改为遍历 multiResolve 与目标比对：
     * 字符串方法名既算 Dao 方法的用法（反向跳转/Rename 同步），
     * 也算 Mapper XML 语句标签的用法，与正向跳转的目标集合保持一致。
     */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        for (ResolveResult result : multiResolve(false)) {
            PsiElement resolved = result.getElement();
            if (resolved == null) continue;
            // StatementNavigationTarget 包装的 XML 目标，与其委托的 XmlAttributeValue 都认
            if (resolved instanceof StatementNavigationTarget) {
                PsiElement delegate = resolved.getNavigationElement();
                if (delegate != null && getElement().getManager().areElementsEquivalent(delegate, element)) {
                    return true;
                }
            }
            if (getElement().getManager().areElementsEquivalent(resolved, element)) {
                return true;
            }
        }
        return super.isReferenceTo(element);
    }

    /**
     * 代码提示:根据引用提供自动补全(ctrl + 空格)，列表统一使用插件专属图标
     */
    @Override
    public Object @NotNull [] getVariants() {
        PsiMethod[] methods = psiClass.getMethods();
        List<LookupElement> variants = new ArrayList<>();
        for (final PsiMethod psiMethod : methods) {
            if (psiMethod != null) {
                LookupElement lookupElement = LookupElementBuilder.create(psiMethod)
                                                                  .withIcon(MyIcons.pandaIconSVG16_2)
                                                                  .withItemTextItalic(true)
                                                                  .withTypeText(psiClass.getName(), null, true)
                                                                  .withBoldness(true)
                                                                  .withCaseSensitivity(false)
                                                                  .withTailText(psiClass.getQualifiedName(), true)
                                                                  .withAutoCompletionPolicy(
                                                                          AutoCompletionPolicy.ALWAYS_AUTOCOMPLETE)
                        ;

                // 包装成最高优先级
                LookupElement prioritized = PrioritizedLookupElement.withPriority(lookupElement, 1000.0);
                variants.add(prioritized);
            }
        }
        return variants.toArray();
    }

}
