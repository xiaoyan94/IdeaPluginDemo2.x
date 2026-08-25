package com.zhiyin.plugins.referenceContributor;

import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.highlighting.HighlightedReference;
import com.intellij.codeInsight.lookup.AutoCompletionPolicy;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbService;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import com.intellij.util.xml.GenericAttributeValue;
import com.zhiyin.plugins.oneClickNavigation.xml.domElements.Mapper;
import com.zhiyin.plugins.resources.MyIcons;
import com.zhiyin.plugins.utils.MyPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class MyXMLReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttributeValue.class), new PsiReferenceProvider() {
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
                if (MyPsiUtil.isXmlFile(element)) {
                    XmlFile xmlFile = (XmlFile) element.getContainingFile();
                    Project project = element.getProject();
                    DomManager domManager = DomManager.getDomManager(project);
                    DomFileElement<Mapper> domFileElement = domManager.getFileElement(xmlFile, Mapper.class);
                    if (domFileElement == null) {
                        return PsiReference.EMPTY_ARRAY;
                    }

                    XmlAttributeValue xmlAttributeValue = (XmlAttributeValue) element;
                    PsiElement parentXmlAttribute = MyPsiUtil.findPsiElementParentMatching(element, psiElement -> psiElement instanceof XmlAttribute);
                    if (parentXmlAttribute == null || !((XmlAttribute) parentXmlAttribute).getName().equalsIgnoreCase("id")){
                        return PsiReference.EMPTY_ARRAY;
                    }
                    PsiElement parentXmlTag = MyPsiUtil.findPsiElementParentMatching(element, psiElement -> psiElement instanceof XmlTag);
                    if (parentXmlTag == null || !Arrays.asList("select", "insert", "update", "delete").contains(((XmlTag) parentXmlTag).getName().toLowerCase())){
//                        System.out.println(parentXmlTag);
                        return PsiReference.EMPTY_ARRAY;
                    }
                    Mapper mapper = domFileElement.getRootElement();
                    GenericAttributeValue<String> namespace = mapper.getNamespace();
//                    System.out.println(namespace); // com.zhiyin.dao.assemble.basic.IRoutingBomDao
                    if (namespace == null || namespace.getValue() == null) {
                        return PsiReference.EMPTY_ARRAY;
                    }
                    String namespaceValue = namespace.getValue();
                    JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
                    PsiClass psiClass = javaPsiFacade.findClass(namespaceValue, element.getResolveScope());
                    if (psiClass == null) {
                        return PsiReference.EMPTY_ARRAY;
                    } else {
                        return new PsiReference[]{new MyXMLReference(xmlAttributeValue, psiClass, xmlAttributeValue.getValue())};
                    }
                }
                return PsiReference.EMPTY_ARRAY;
            }
        });
    }

    public static class MyXMLReference extends PsiReferenceBase<XmlAttributeValue> implements PsiPolyVariantReference, HighlightedReference {
        private final PsiClass psiClass;
        private final String methodName;

        /**
         * 懒缓存解析结果。multiResolve 在 hover / 高亮 / 查找用法时会被高频调用，
         * 其中含全项目用法搜索（ReferencesSearch），必须缓存；元素失效时自动重算。
         */
        private volatile ResolveResult[] cachedResults;

        /**
         * Reference range is obtained from {@link ElementManipulator#getRangeInElement(PsiElement)}.
         *
         * @param element Underlying element.
         */
        public MyXMLReference(@NotNull XmlAttributeValue element, PsiClass psiClass, String methodName) {
            super(element);
            this.psiClass = psiClass;
            this.methodName = methodName;
        }

        /**
         * Returns the results of resolving the reference.
         *
         * @param incompleteCode if true, the code in the context of which the reference is
         *                       being resolved is considered incomplete, and the method may return additional
         *                       invalid results.
         * @return the array of results for resolving the reference.
         */
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
            // 1) Dao 接口方法（原目标不变）
            PsiMethod[] psiMethods = psiClass.findMethodsByName(methodName, false);
            for (PsiMethod psiMethod : psiMethods) {
                results.add(new PsiElementResolveResult(psiMethod));
            }
            // 2) 反向补充目标：queryDaoDataT(..., "methodName", ...) 调用字符串。
            //    用 word index 按字符串字面量正向搜索（只碰含该词的文件，毫秒级）。
            //    不用 ReferencesSearch：对 PsiMethod 做全项目用法反查（几万文件，进度条漫长），
            //    且搜索过程会回调各引用提供器，与 multiResolve 互相触发形成进度条循环。
            //    过滤 MyJavaMethodReference：只认 queryDaoDataT 上下文的字符串，
            //    真实 Java 调用 / 其他 XML id / Moc 引用不混入。
            if (!DumbService.isDumb(getElement().getProject()) && methodName != null && !methodName.isEmpty()) {
                PsiSearchHelper searchHelper = PsiSearchHelper.getInstance(getElement().getProject());
                // 按模块过滤：搜索范围收窄到本 XML 所在模块，其他模块同名方法字面量不混入
                //（多模块工作区里方法名字符串常见重名，且那些字面量的引用指向别的 namespace）。
                // 模块判定不出时退回全项目，保持原行为。
                Module xmlModule = MyPsiUtil.getModuleByPsiElement(getElement());
                GlobalSearchScope javaScope = GlobalSearchScope.getScopeRestrictedByFileTypes(
                        xmlModule != null ? GlobalSearchScope.moduleScope(xmlModule)
                                          : GlobalSearchScope.projectScope(getElement().getProject()),
                        JavaFileType.INSTANCE);
                Set<PsiFile> candidateFiles = Collections.newSetFromMap(new IdentityHashMap<>());
                searchHelper.processAllFilesWithWordInLiterals(methodName, javaScope, candidateFiles::add);
                for (PsiFile file : candidateFiles) {
                    for (PsiLiteralExpression literal :
                            com.intellij.psi.SyntaxTraverser.psiTraverser(file).filter(PsiLiteralExpression.class)) {
                        if (!methodName.equals(literal.getValue())) continue;
                        for (PsiReference reference : literal.getReferences()) {
                            if (reference instanceof MyJavaMethodReference) {
                                // 展示具体调用方法（如 bizCommonService.queryDaoDataT("getXxx")），定位不到时回退
                                results.add(new PsiElementResolveResult(new CallSiteNavigationTarget(
                                        literal, CallSiteNavigationTarget.buildDisplayText(literal, "queryDaoDataT", methodName))));
                                break;
                            }
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
         * 反向匹配只认 Dao 方法。不得走 super：基类默认实现调 resolve() → multiResolve →
         * ReferencesSearch → 搜索器回调候选引用的 isReferenceTo，会形成自递归；
         * 此处只做轻量比对，与 multiResolve 的重搜索解耦。
         */
        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            for (PsiMethod psiMethod : psiClass.findMethodsByName(methodName, false)) {
                if (getElement().getManager().areElementsEquivalent(psiMethod, element)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 单一目标时直接跳转；多目标（Dao 方法 + 多处 queryDaoDataT 字符串）返回 null，
         * 让 GotoDeclarationAction 走 multiResolve 弹出选择列表（与 MyJavaMethodReference 同语义）。
         * 原实现 length >= 1 恒取首个目标，导致 Ctrl+B 直接跳 Dao、不弹选择框。
         */
        @Override
        public @Nullable PsiElement resolve() {
            ResolveResult[] resolveResults = multiResolve(false);
            return resolveResults.length == 1 ? resolveResults[0].getElement() : null;
        }

        /**
         * Returns the array of String, {@link PsiElement} and/or {@link LookupElement}
         * instances representing all identifiers that are visible at the location of the reference. The contents
         * of the returned array are used to build the lookup list for basic code completion. (The list
         * of visible identifiers may not be filtered by the completion prefix string - the
         * filtering is performed later by the IDE.)
         * <p>
         * This method is default since 2018.3.
         *
         * @return the array of available identifiers.
         */
        @Override
        public Object @NotNull [] getVariants() {
            PsiMethod[] methods = psiClass.getMethods();
            List<LookupElement> variants = new ArrayList<>();
            for (final PsiMethod psiMethod : methods) {
                if (psiMethod != null) {
                    LookupElement element = LookupElementBuilder.create(psiMethod)
                                                                .withIcon(MyIcons.pandaIconSVG16_2)
                                                                .withItemTextItalic(true)
                                                                .withTypeText(psiClass.getName(), AllIcons.FileTypes.Java, true)
                                                                .withBoldness(true)
                                                                .withCaseSensitivity(false)
                                                                .withTailText(psiClass.getQualifiedName(), true)
                                                                .withAutoCompletionPolicy(
                                                                        AutoCompletionPolicy.ALWAYS_AUTOCOMPLETE)
                            ;
                    // 包装成最高优先级
                    LookupElement prioritized = PrioritizedLookupElement.withPriority(element, 1000.0);
                    variants.add(prioritized);
                }
            }
            return variants.toArray();
        }
    }
}
