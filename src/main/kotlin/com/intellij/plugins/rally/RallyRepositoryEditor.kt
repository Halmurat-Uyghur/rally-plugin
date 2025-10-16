package com.intellij.plugins.rally

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.tasks.config.BaseRepositoryEditor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.Consumer
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

/**
 * Configuration editor for Rally repository
 * This is shown in Settings → Tools → Tasks → Servers
 */
class RallyRepositoryEditor(
    project: Project,
    repository: RallyRepository,
    consumer: Consumer<in RallyRepository>
) : BaseRepositoryEditor<RallyRepository>(project, repository, consumer) {

    private val serverUrlField = JBTextField()
    private val apiKeyField = JPasswordField()
    private val workspaceField = JBTextField()
    private val projectFilterField = JBTextField()

    init {
        // Set initial values from repository
        serverUrlField.text = myRepository.serverUrl
        apiKeyField.text = myRepository.apiKey
        workspaceField.text = myRepository.workspace
        projectFilterField.text = myRepository.projectFilter

        // Set tooltips
        serverUrlField.toolTipText = "Rally server URL (e.g., https://rally1.rallydev.com)"
        apiKeyField.toolTipText = "Rally API Key (get it from Rally Profile → API Keys)"
        workspaceField.toolTipText = "Workspace name (optional - leave blank for default workspace)"
        projectFilterField.toolTipText = "Filter tasks by project name (optional)"
    }

    override fun createCustomPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("Server URL:"),
                serverUrlField
            )
            .addLabeledComponent(
                JBLabel("API Key:"),
                apiKeyField
            )
            .addLabeledComponent(
                JBLabel("Workspace (optional):"),
                workspaceField
            )
            .addLabeledComponent(
                JBLabel("Project Filter (optional):"),
                projectFilterField
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel
    }

    override fun apply() {
        // Save values to repository
        myRepository.serverUrl = serverUrlField.text.trim()
        myRepository.apiKey = String(apiKeyField.password).trim()
        myRepository.workspace = workspaceField.text.trim()
        myRepository.projectFilter = projectFilterField.text.trim()

        // Also set the URL property (used by base class)
        myRepository.url = myRepository.serverUrl

        super.apply()
    }

    override fun setAnchor(anchor: JComponent?) {
        super.setAnchor(anchor)
    }

    /**
     * Additional components to show in the editor
     */
    override fun createComponent(): JComponent {
        val component = super.createComponent()

        // Add help text or instructions if needed
        return component
    }
}
