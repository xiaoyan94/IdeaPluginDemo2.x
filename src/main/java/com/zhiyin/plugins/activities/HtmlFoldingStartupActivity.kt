package com.zhiyin.plugins.activities

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zhiyin.plugins.component.HtmlFoldingProjectService

class HtmlFoldingStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // 主动获取 Service，从而触发构造函数里的 Listener 注册
        HtmlFoldingProjectService.getInstance(project)
    }
}
