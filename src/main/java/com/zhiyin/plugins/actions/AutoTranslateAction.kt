package com.zhiyin.plugins.actions

import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.indexing.FileBasedIndex
import com.zhiyin.plugins.ui.MyTranslateDialogWrapper

/**
 * 自动多语言翻译写入 Action
 *
 * 功能说明：
 * 1. 从选中的 *.properties 文件中找到同目录下其他语言版本文件；
 * 2. 弹出翻译输入框；
 * 3. 使用 PSI 层安全写入多语言内容；
 * 4. 自动支持 Undo/Redo、索引刷新。
 */
class AutoTranslateAction : AnAction() {
    private val log = Logger.getInstance(AutoTranslateAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val baseFile = e.getData(PlatformDataKeys.VIRTUAL_FILE) ?: run {
            Messages.showErrorDialog("未找到有效的资源文件", "操作失败")
            return
        }

        val module = e.getData(PlatformDataKeys.MODULE) ?: run {
            Messages.showErrorDialog("未找到关联模块", "操作失败")
            return
        }

        val bundleFiles = getResourceBundleFiles(baseFile)
        if (bundleFiles.isEmpty()) {
            Messages.showErrorDialog("未找到关联资源文件", "操作失败")
            return
        }

        val dialog = MyTranslateDialogWrapper(project, module)
        dialog.setCheckI18nKeyExistsFun { key ->
            bundleFiles/*.filter { it.nameWithoutExtension.endsWith("_zh_CN") }*/.any { file ->
                val props = parsePropertiesFile(project, file)
                props?.findPropertyByKey(key) != null
            }
        }

        if (!dialog.showAndGet()) return

        val i18nKey = dialog.inputModel.propertyKey
        val chinese = dialog.inputModel.chinese
        val chineseTW = dialog.inputModel.chineseTW
        val english = dialog.inputModel.english
        val vietnamese = dialog.inputModel.vietnamese

        // 不用传转义后的值，IntelliJ PSI 写入时的 PropertiesFile 根据编码规则自动转义

        bundleFiles.forEach { file ->
            val value = when {
                file.name.contains("_zh_CN") -> chinese
                file.name.contains("_en_US") -> english
                file.name.contains("_zh_TW") -> chineseTW
                file.name.contains("_vi_VN") -> vietnamese
                else -> null
            }
            value?.let {
                setProperty(project, file, i18nKey, it)
            }
        }

        Logger.getInstance(AutoTranslateAction::class.java).info("Key：$i18nKey 已写入多语言文件")
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(PlatformDataKeys.VIRTUAL_FILE)
        val module = e.getData(PlatformDataKeys.MODULE)
        e.presentation.isEnabledAndVisible = (module != null && isBaseResourceBundle(file))
    }

    /**
     * 判断文件是否是标准多语言文件（zh_CN/en_US/zh_TW/vi_VN）
     */
    private fun isBaseResourceBundle(file: VirtualFile?): Boolean {
        return file?.extension == "properties" && listOf("_zh_CN", "_en_US", "_zh_TW", "_vi_VN").any { file.nameWithoutExtension.endsWith(it) }
    }

    /**
     * 获取同目录下所有多语言资源文件
     */
    private fun getResourceBundleFiles(baseFile: VirtualFile): List<VirtualFile> {
        return baseFile.parent?.children?.filter { it.extension == "properties" && it.name.startsWith(baseFile.nameWithoutExtension.substringBefore("_")) }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * 使用 PSI 层解析 Properties 文件
     */
    private fun parsePropertiesFile(project: Project, file: VirtualFile): PropertiesFile? {
        return try {
            val psiFile = PsiManager.getInstance(project).findFile(file)
            psiFile as? PropertiesFile
        } catch (ex: Exception) {
            log.warn("解析属性文件失败: ${file.path}", ex)
            null
        }
    }

    /**
     * 使用 PSI 安全写入属性值（支持撤销、重做、文件刷新）
     */
    private fun setProperty(project: Project, file: VirtualFile, key: String, value: String) {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
        val propertiesFile = psiFile as? PropertiesFile ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val existingProperty = propertiesFile.findPropertyByKey(key)
            if (existingProperty != null) {
                // 更新已有属性值
                existingProperty.setValue(value)
            } else {
                // 使用新版 API 添加属性（推荐）
                // propertiesFile.addProperty(key, value)
                // 追加到末尾
                val last = propertiesFile.properties.lastOrNull()
                propertiesFile.addPropertyAfter(key, value, last)

            }

            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }


}
