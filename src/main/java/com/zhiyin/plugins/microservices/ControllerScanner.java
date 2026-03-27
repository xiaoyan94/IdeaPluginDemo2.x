package com.zhiyin.plugins.microservices;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.PsiFormatUtil;
import com.intellij.psi.util.PsiFormatUtilBase;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.zhiyin.plugins.service.PluginDisposable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Scans project for @Controller / @RestController classes and extracts mappings on background.
 * <p>
 * Implementation notes:
 * - Uses AnnotatedElementsSearch to find classes annotated with Controller/RestController
 * - For each method inspects @RequestMapping / @GetMapping / @PostMapping / ... annotations
 * - Builds full paths by concatenating class-level and method-level paths
 * - Uses SmartPointerManager to create SmartPsiElementPointer<PsiMethod> for each method
 * <p>
 * The public entrypoint scheduleScan submits the read action non-blocking and calls the callback with the result map.
 */
public final class ControllerScanner {

    private static final String ANN_CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String ANN_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final String ANN_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String ANN_GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
    private static final String ANN_POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
    private static final String ANN_PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping";
    private static final String ANN_DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping";
    private static final String ANN_PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping";

    private static final List<String> METHOD_LEVEL_ANNOTATIONS = List.of(ANN_REQUEST_MAPPING, ANN_GET_MAPPING, ANN_POST_MAPPING, ANN_PUT_MAPPING, ANN_DELETE_MAPPING, ANN_PATCH_MAPPING);

    private static final String ANN_FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient";
    private static final @NotNull Logger logger = Logger.getInstance(ControllerScanner.class);

    private ControllerScanner() {
    }

    /**
     * Schedule the scan: will wait until project is "smart" (indexing done), then perform a non-blocking read action.
     * When completed, invokes the callback on the application executor (background thread).
     */
    public static void scheduleScan(@NotNull Project project, @NotNull Consumer<Map<String, List<MethodInfo>>> resultConsumer) {
        // Wait until indexing finished and project is smart
        com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart(() -> {
            // run non-blocking read action
            ReadAction.nonBlocking(() -> performScan(project))
                      .expireWith(PluginDisposable.getInstance(project))
                      .coalesceBy(project, "microservices-controller-scan")
                      .finishOnUiThread(ModalityState.nonModal(), result -> {
                          // invoke consumer on UI thread (safe)
                          resultConsumer.accept(result);

                          logger.info("Current thread is " + Thread.currentThread().getName() + " result size:" + result.size());

                          logger.info("ControllerScanner.performScan completed");
                      })
                      .submit(AppExecutorUtil.getAppExecutorService());
        });
    }

    /**
     * Performs scanning under a read action (called from ReadAction.nonBlocking)
     */
    private static Map<String, List<MethodInfo>> performScan(@NotNull Project project) {
        if(DumbService.isDumb(project)) {
            logger.warn("Skipped scan: Project in dumb mode");
            return Collections.emptyMap();
        }

        Map<String, List<MethodInfo>> map = new ConcurrentHashMap<>();
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        PsiClass[] controllerClasses = findControllerClasses(project, scope);
        SmartPointerManager pointerManager = SmartPointerManager.getInstance(project);

        for (PsiClass psiClass : controllerClasses) {

            if (psiClass == null) continue;
            String[] classPaths = extractPathsFromAnnotation(psiClass.getAnnotation(ANN_REQUEST_MAPPING));

            // TODO 移除多余代码
            if (classPaths == null || classPaths.length == 0) {
                classPaths = extractPathsFromAnnotation(psiClass.getAnnotation(ANN_POST_MAPPING));
                if (classPaths == null || classPaths.length == 0) {
                    classPaths = extractPathsFromAnnotation(psiClass.getAnnotation(ANN_GET_MAPPING));
                    if (classPaths == null || classPaths.length == 0) {
                        classPaths = extractPathsFromAnnotation(psiClass.getAnnotation(ANN_REST_CONTROLLER));
                    }
                }
            }

            // if controller has no class-level mapping, treat as ""
            if (classPaths == null || classPaths.length == 0) {
                classPaths = new String[]{""};
            }

            Module module = ModuleUtil.findModuleForPsiElement(psiClass);

            for (PsiMethod method : psiClass.getMethods()) {
                // skip synthetic/abstract methods
                if (method.isConstructor()) continue;

                List<String> methodPaths = new ArrayList<>();
                for (String ann : METHOD_LEVEL_ANNOTATIONS) {
                    PsiAnnotation a = method.getAnnotation(ann);
                    if (a != null) {
                        String[] paths = extractPathsFromAnnotation(a);
                        if (paths == null || paths.length == 0) {
                            // empty path means ""
                            methodPaths.add("");
                        } else {
                            Collections.addAll(methodPaths, paths);
                        }
                        // we do not break: method could have multiple mapping annotations (rare)
                    }
                }

                if (methodPaths.isEmpty()) {
                    // no mapping annotation on method -> skip
                    continue;
                }

                // combine classPaths x methodPaths
                for (String cp : classPaths) {
                    for (String mp : methodPaths) {
                        String full = PathUtils.combine(cp, mp);
                        // normalize (remove duplicate slashes)
                        full = PathUtils.normalize(full);
                        SmartPsiElementPointer<PsiMethod> ptr = pointerManager.createSmartPsiElementPointer(method);
                        String finalFull = full;
                        map.compute(full, (k, list) -> {
                            if (list == null) {
                                list = new ArrayList<>();
                            }
                            MethodInfo.Source source = MethodInfo.Source.CONTROLLER;
                            if (psiClass.getAnnotation(ANN_FEIGN_CLIENT) != null) {
                                source = MethodInfo.Source.FEIGN;
                            }

                            String methodSignature = PsiFormatUtil.formatMethod(
                                    method,
                                    PsiSubstitutor.EMPTY, // 传入空替换器
                                    PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_PARAMETERS,
                                    PsiFormatUtilBase.SHOW_TYPE
                            );
                            MethodInfo methodInfo = new MethodInfo(source, ptr, psiClass.getName(), finalFull, module == null ? "" : module.getName(), method.isDeprecated(), methodSignature);

                            list.add(methodInfo);
                            return list;
                        });
                    }
                }
            }

        }
        return map;
    }

    private static PsiClass[] findControllerClasses(@NotNull Project project, @NotNull GlobalSearchScope scope) {
        Collection<PsiClass> results = new ArrayList<>();
        JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);

        // 使用 allScope，而不是仅 projectScope，可以找到外部依赖中的注解类
        GlobalSearchScope allScope = GlobalSearchScope.allScope(project);

        PsiClass annControllerClass = psiFacade.findClass(ANN_CONTROLLER, allScope);
        if (annControllerClass != null) {
            results.addAll(AnnotatedElementsSearch.searchPsiClasses(annControllerClass, scope).findAll());
        } else {
            System.out.println("⚠️ 未找到 @Controller 注解类，可能依赖未被索引或未添加到 module library。");
        }

        PsiClass annRestControllerClass = psiFacade.findClass(ANN_REST_CONTROLLER, allScope);
        if (annRestControllerClass != null) {
            results.addAll(AnnotatedElementsSearch.searchPsiClasses(annRestControllerClass, scope).findAll());
        } else {
            System.out.println("⚠️ 未找到 @RestController 注解类。");
        }

        PsiClass annFeignClass = psiFacade.findClass(ANN_FEIGN_CLIENT, allScope);
        if (annFeignClass != null) {
            results.addAll(AnnotatedElementsSearch.searchPsiClasses(annFeignClass, scope).findAll());
        } else {
            System.out.println("⚠️ 未找到 @FeignClient 注解类。");
        }

        return results.toArray(new PsiClass[0]);
    }


    /**
     * Extracts paths from a mapping annotation. Handles value() or path() attributes and arrays.
     * Returns null if annotation is null.
     */
    static String[] extractPathsFromAnnotation(PsiAnnotation annotation) {
        if (annotation == null) return null;
        // first try "value", then "path"
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value == null) {
            value = annotation.findAttributeValue("path");
        }
        if (value == null) {
            // no explicit value -> default mapping (empty string)
            return new String[]{""};
        }

        // value can be a literal or an array initializer
        List<String> paths = new ArrayList<>();
        if (value instanceof PsiLiteralExpression) {
            Object val = ((PsiLiteralExpression) value).getValue();
            if (val != null) paths.add(val.toString());
        } else if (value instanceof PsiArrayInitializerMemberValue arr) {
            for (PsiAnnotationMemberValue v : arr.getInitializers()) {
                if (v instanceof PsiLiteralExpression) {
                    Object val = ((PsiLiteralExpression) v).getValue();
                    if (val != null) paths.add(val.toString());
                }
            }
        } else {
            // other forms - attempt to evaluate to string if possible (best effort)
            String text = value.getText();
            if (text != null && !text.isBlank()) {
                // strip quotes if present
                text = text.replaceAll("^\"|\"$", "");
                paths.add(text);
            }
        }
        return paths.toArray(new String[0]);
    }
}
