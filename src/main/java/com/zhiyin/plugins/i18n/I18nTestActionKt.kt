@file:Suppress("ComponentNotRegistered")

package com.zhiyin.plugins.i18n

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

/* https://plugins.jetbrains.com/docs/intellij/execution-contexts.html */
class I18nTestActionKt : AnAction() {

    // Companion object for static logger access, idiomatic in Kotlin
    companion object {
        private val LOG = thisLogger()
    }

    override fun actionPerformed(e: AnActionEvent) {
        // Kotlin's safe call operator (?.) and 'let' function simplify null checks
        e.project?.let { project ->
            // Use the Kotlin extension function 'service' for easier service retrieval
            val cacheManager = project.service<I18nCacheManager>()

            ProgressManager.getInstance().run(
                // Use Task.Backgroundable for background operation with progress UI
                object : Task.Backgroundable(project, "Scanning I18n Files", false) {
                    override fun run(indicator: ProgressIndicator) {
                        // The actual scanning logic
                        I18nScanner.scanProject(project, cacheManager, indicator, false)
                    }
                }
            )
        }
    }
}