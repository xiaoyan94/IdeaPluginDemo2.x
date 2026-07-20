package com.zhiyin.plugins.referenceContributor

import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.zhiyin.plugins.resources.MyIcons
import javax.swing.Icon

/**
 * 轻量导航目标包装：跳转委托给真实的 XmlAttributeValue（精确跳到 id 属性值），
 * 但展示文本使用不带双引号的方法名（NavigationItem 的 presentableText），
 * 使选择列表显示 "getXxx" 而非 "\"getXxx\""。
 *
 * 通过 Kotlin 接口委托（by delegate）自动生成 PsiElement 的全部方法，
 * 仅重写与展示/导航相关的少数方法，避免手写几十个委托方法。
 */
internal class StatementNavigationTarget(
    private val delegate: PsiElement,
    private val name: String
) : PsiElement by delegate, NavigationItem {

    private val navigatable: Navigatable? = delegate as? Navigatable

    override fun getText(): String = name

    override fun getName(): String = name

    override fun getIcon(flags: Int): Icon = MyIcons.pandaIconSVG16_2

    override fun getNavigationElement(): PsiElement = delegate

    override fun canNavigate(): Boolean =
        navigatable?.canNavigate() ?: (delegate.containingFile?.virtualFile != null)

    override fun canNavigateToSource(): Boolean =
        navigatable?.canNavigateToSource() ?: canNavigate()

    override fun navigate(requestFocus: Boolean) {
        // 优先走委托元素自身的 navigate；兜底直接用 OpenFileDescriptor 打开文件并定位到属性值偏移量，
        // 避免 XmlAttributeValue 在某些导航链路中不被识别为 Navigatable 而点击无反应。
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
            override fun getPresentableText(): String = name

            override fun getLocationString(): String {
                val file = delegate.containingFile
                return file?.name ?: ""
            }

            override fun getIcon(unused: Boolean): Icon = MyIcons.pandaIconSVG16_2
        }
    }
}
