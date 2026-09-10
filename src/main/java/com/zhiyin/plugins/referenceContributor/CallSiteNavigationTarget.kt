package com.zhiyin.plugins.referenceContributor

import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.zhiyin.plugins.resources.MyIcons
import javax.swing.Icon

/**
 * Java 调用字符串的导航目标包装（用于选择列表展示），供 queryDaoDataT / Moc 调用共用。
 *
 * 裸 PsiLiteralExpression 在 GotoDeclaration 选择框里只显示带引号的字符串、无任何上下文；
 * 包装后展示为 displayText（如 queryDaoDataT("getXxx") / Moc 调用("mocName")），
 * 位置显示 文件名:行号，图标用插件专属熊猫标，与方法行（含签名/参数类型）形成可辨识的对照。
 * 跳转委托给真实字面量。
 *
 * 通过 Kotlin 接口委托（by delegate）自动生成 PsiElement 的全部方法，
 * 仅重写与展示/导航相关的少数方法（同 StatementNavigationTarget 模式）。
 */
internal class CallSiteNavigationTarget(
    private val delegate: PsiElement,
    private val displayText: String
) : PsiElement by delegate, NavigationItem {

    private val navigatable: Navigatable? = delegate as? Navigatable

    companion object {
        /**
         * 生成调用点展示文案：取字面量所在的方法调用表达式（如 bizCommonService.insertMocData）
         * 拼 "方法(\"字面量值\")"；定位不到调用表达式时回退 "前缀(\"字面量值\")"。
         */
        @JvmStatic
        fun buildDisplayText(literal: PsiElement, fallbackPrefix: String, value: String): String {
            val call = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression::class.java, false)
            val methodExpr = call?.methodExpression?.text
            return if (methodExpr != null) "$methodExpr(\"$value\")" else "$fallbackPrefix(\"$value\")"
        }
    }

    override fun getName(): String = displayText

    override fun getIcon(flags: Int): Icon = MyIcons.pandaIconSVG16_2

    override fun getNavigationElement(): PsiElement = delegate

    override fun canNavigate(): Boolean =
        navigatable?.canNavigate() ?: (delegate.containingFile?.virtualFile != null)

    override fun canNavigateToSource(): Boolean =
        navigatable?.canNavigateToSource() ?: canNavigate()

    override fun navigate(requestFocus: Boolean) {
        // 优先走委托元素自身的 navigate；兜底 OpenFileDescriptor 定位到字符串偏移量，
        // 避免字面量在某些导航链路中不被识别为 Navigatable 而点击无反应。
        if (navigatable != null) {
            navigatable!!.navigate(requestFocus)
            return
        }
        val file = delegate.containingFile
        val vFile = file?.virtualFile
        if (vFile != null) {
            OpenFileDescriptor(file.project, vFile, delegate.textRange.startOffset)
                .navigate(requestFocus)
        }
    }

    override fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
            override fun getPresentableText(): String = displayText

            override fun getLocationString(): String {
                val file = delegate.containingFile ?: return ""
                val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
                    ?: return file.name
                val line = document.getLineNumber(delegate.textRange.startOffset) + 1
                return "${file.name}:$line"
            }

            override fun getIcon(unused: Boolean): Icon = MyIcons.pandaIconSVG16_2
        }
    }
}
