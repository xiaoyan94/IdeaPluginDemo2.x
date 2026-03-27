package com.zhiyin.plugins.microservices;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;

/**
 * @param source  来源
 * @param pointer  方法指针
 * @param className  新增字段 所在类名
 * @param fullPath  完整 URL
 * @param moduleName  所在模块
 * @param deprecated  是否被 @Deprecated 标记
 * @param methodSignature  方法签名，用于 UI 显示 */
public record MethodInfo(Source source, SmartPsiElementPointer<PsiMethod> pointer, String className, String fullPath, String moduleName, boolean deprecated, String methodSignature) {
    public enum Source {CONTROLLER, FEIGN}

    @Override
    public @NotNull String toString() {
        StringBuilder sb = new StringBuilder();

        // URL 路径
        sb.append(fullPath);

        // 模块名
        if (moduleName != null && !moduleName.isEmpty()) {
            sb.append(" [").append(moduleName).append("]");
        }

        // 来源类型
        sb.append(" - ");
        sb.append(source == Source.CONTROLLER ? "Controller" : "Feign");

        // 类名和方法签名
        sb.append(" → ");
        sb.append(className);
        sb.append("#");
        sb.append(methodSignature);

        // 如果已废弃，添加标记
        if (deprecated) {
            sb.append(" [Deprecated]");
        }

        return sb.toString();
    }
}
