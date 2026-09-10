package com.zhiyin.plugins.referenceContributor;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import com.zhiyin.plugins.oneClickNavigation.xml.domElements.Moc;
import com.zhiyin.plugins.utils.MyPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Moc XML 侧引用：&lt;Moc name="xxx"&gt; 的 name 属性值上 Ctrl+B，
 * 弹出所有引用该 Moc 的 Java 调用点（bizCommonService.xxxMocData(..., "xxx", ...) 字符串）。
 *
 * 目标来源：word index 按字符串字面量正向搜索调用点所在文件（限定本 XML 所在模块），
 * 只认挂了 MyMocReference 的调用字符串，与 Mapper 侧 MyXMLReference 同模式。
 */
public class MyMocXmlReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttributeValue.class), new PsiReferenceProvider() {
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
                if (!(element instanceof XmlAttributeValue xmlAttributeValue)) {
                    return PsiReference.EMPTY_ARRAY;
                }
                PsiElement parent = xmlAttributeValue.getParent();
                if (!(parent instanceof XmlAttribute xmlAttribute) || !"name".equalsIgnoreCase(xmlAttribute.getName())) {
                    return PsiReference.EMPTY_ARRAY;
                }
                PsiElement tag = xmlAttribute.getParent();
                if (!(tag instanceof XmlTag xmlTag) || !"Moc".equalsIgnoreCase(xmlTag.getName())) {
                    return PsiReference.EMPTY_ARRAY;
                }

                // 只认本插件 DOM 模型注册的 Moc 文件（与 MocXmlFileDescription 根标签口径一致）
                Project project = element.getProject();
                if (!(xmlAttributeValue.getContainingFile() instanceof com.intellij.psi.xml.XmlFile xmlFile)) {
                    return PsiReference.EMPTY_ARRAY;
                }
                DomManager domManager = DomManager.getDomManager(project);
                DomFileElement<Moc> domFileElement = domManager.getFileElement(xmlFile, Moc.class);
                if (domFileElement == null || domFileElement.getRootElement().getXmlElement() != xmlTag) {
                    return PsiReference.EMPTY_ARRAY;
                }

                return new PsiReference[]{new MyMocXmlReference(xmlAttributeValue, xmlAttributeValue.getValue())};
            }
        });
    }

    static class MyMocXmlReference extends PsiReferenceBase<XmlAttributeValue> implements PsiPolyVariantReference {

        private final String mocName;

        /**
         * 懒缓存解析结果（内含全项目用法搜索，必须缓存；元素失效自动重算）。
         */
        private volatile ResolveResult[] cachedResults;

        MyMocXmlReference(@NotNull XmlAttributeValue element, String mocName) {
            super(element);
            this.mocName = mocName;
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
            if (DumbService.isDumb(getElement().getProject()) || mocName == null || mocName.isEmpty()) {
                return results.toArray(new ResolveResult[0]);
            }
            // 用 word index 按字符串字面量正向搜索调用点所在文件（只碰含该词的文件，毫秒级）。
            // 不用 ReferencesSearch：它对 XML 属性值做全项目用法反查（几万文件），
            // 且搜索过程会回调各引用提供器形成解析-搜索互相触发的进度条循环。
            PsiSearchHelper searchHelper = PsiSearchHelper.getInstance(getElement().getProject());
            // 按模块过滤：搜索范围收窄到本 XML 所在模块（与 MyMocReference 正向的同模块口径对称），
            // 其他模块同名 Moc 的调用字符串不混入。模块判定不出时退回全项目，保持原行为。
            Module xmlModule = MyPsiUtil.getModuleByPsiElement(getElement());
            GlobalSearchScope javaScope = GlobalSearchScope.getScopeRestrictedByFileTypes(
                    xmlModule != null ? GlobalSearchScope.moduleScope(xmlModule)
                                      : GlobalSearchScope.projectScope(getElement().getProject()),
                    JavaFileType.INSTANCE);
            Set<PsiFile> candidateFiles = Collections.newSetFromMap(new IdentityHashMap<>());
            searchHelper.processAllFilesWithWordInLiterals(mocName, javaScope, file -> {
                candidateFiles.add(file);
                return true;
            });
            for (PsiFile file : candidateFiles) {
                // 文件内再按字面量精确定位：只认本插件注册了 MyMocReference 的调用字符串
                // （bizCommonService/queryDaoDataT 上下文），排除恰好含同名文本的普通字符串
                for (PsiLiteralExpression literal :
                        com.intellij.psi.SyntaxTraverser.psiTraverser(file).filter(PsiLiteralExpression.class)) {
                    if (!mocName.equals(literal.getValue())) continue;
                    for (PsiReference reference : literal.getReferences()) {
                        if (reference instanceof MyMocReference) {
                            // 展示具体调用方法（如 bizCommonService.insertMocData("xxx")），定位不到时回退
                            results.add(new PsiElementResolveResult(new CallSiteNavigationTarget(
                                    literal, CallSiteNavigationTarget.buildDisplayText(literal, "Moc 调用", mocName))));
                            break;
                        }
                    }
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
         * 单一调用点直接跳；多调用点返回 null 弹选择框。
         */
        @Nullable
        @Override
        public PsiElement resolve() {
            ResolveResult[] resolveResults = multiResolve(false);
            return resolveResults.length == 1 ? resolveResults[0].getElement() : null;
        }

        /**
         * 反向匹配必须覆写且不走 super：基类默认 resolve() → multiResolve → ReferencesSearch →
         * 搜索器回调候选引用 isReferenceTo 会自递归。此引用的正向目标就是调用字符串本身，
         * 无需再把本 XML 值当作别处引用的目标比对，直接返回 false（用法判定已由 MyMocReference 承担）。
         */
        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            return false;
        }
    }
}
