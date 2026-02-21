package com.intellij.plugins.rally.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.plugins.rally.api.RallyApiClient
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

class RallySettingsConfigurable : Configurable {

    private var serverUrlField: JBTextField? = null
    private var apiKeyField: JPasswordField? = null
    private var workspaceRefField: JBTextField? = null
    private var projectRefField: JBTextField? = null
    private var usernameField: JBTextField? = null

    override fun getDisplayName(): String = "Rally"

    override fun createComponent(): JComponent {
        serverUrlField = JBTextField().apply {
            toolTipText = "Rally server URL (e.g., https://rally1.rallydev.com)"
        }
        apiKeyField = JPasswordField().apply {
            toolTipText = "Rally API Key (get it from Rally Profile → API Keys)"
        }
        workspaceRefField = JBTextField().apply {
            toolTipText = "Workspace reference (e.g., /workspace/12345)"
        }
        projectRefField = JBTextField().apply {
            toolTipText = "Project reference (e.g., /project/67890)"
        }
        usernameField = JBTextField().apply {
            toolTipText = "Your Rally username/email for 'My Tickets' filter"
        }

        val testButton = JButton("Test Connection").apply {
            addActionListener { testConnection() }
        }

        val settings = RallySettings.getInstance()
        serverUrlField!!.text = settings.serverUrl
        apiKeyField!!.text = settings.apiKey
        workspaceRefField!!.text = settings.workspaceRef
        projectRefField!!.text = settings.projectRef
        usernameField!!.text = settings.username

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server URL:"), serverUrlField!!)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField!!)
            .addLabeledComponent(JBLabel("Workspace Ref:"), workspaceRefField!!)
            .addLabeledComponent(JBLabel("Project Ref:"), projectRefField!!)
            .addLabeledComponent(JBLabel("Username:"), usernameField!!)
            .addComponent(testButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = RallySettings.getInstance()
        return serverUrlField?.text != settings.serverUrl ||
                String(apiKeyField?.password ?: charArrayOf()) != settings.apiKey ||
                workspaceRefField?.text != settings.workspaceRef ||
                projectRefField?.text != settings.projectRef ||
                usernameField?.text != settings.username
    }

    override fun apply() {
        val state = RallySettings.getInstance().state
        state.serverUrl = serverUrlField?.text?.trim() ?: ""
        state.apiKey = String(apiKeyField?.password ?: charArrayOf()).trim()
        state.workspaceRef = workspaceRefField?.text?.trim() ?: ""
        state.projectRef = projectRefField?.text?.trim() ?: ""
        state.username = usernameField?.text?.trim() ?: ""
    }

    override fun reset() {
        val settings = RallySettings.getInstance()
        serverUrlField?.text = settings.serverUrl
        apiKeyField?.text = settings.apiKey
        workspaceRefField?.text = settings.workspaceRef
        projectRefField?.text = settings.projectRef
        usernameField?.text = settings.username
    }

    override fun disposeUIResources() {
        serverUrlField = null
        apiKeyField = null
        workspaceRefField = null
        projectRefField = null
        usernameField = null
    }

    private fun testConnection() {
        val url = serverUrlField?.text?.trim() ?: ""
        val key = String(apiKeyField?.password ?: charArrayOf()).trim()

        if (url.isBlank() || key.isBlank()) {
            Messages.showErrorDialog("Please provide server URL and API key.", "Rally Connection")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = RallyApiClient(url, key)
                val user = client.getCurrentUser()
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        "Connected successfully as: ${user.displayName ?: user.userName ?: "Unknown"}",
                        "Rally Connection"
                    )
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        "Connection failed: ${e.message}",
                        "Rally Connection"
                    )
                }
            }
        }
    }
}
