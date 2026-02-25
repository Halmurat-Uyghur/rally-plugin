package com.github.halmuratuyghur.rally.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class RallyToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val rallyPanel = RallyToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(
            rallyPanel.getContent(),
            "",
            false
        )
        toolWindow.contentManager.addContent(content)
    }
}
