package com.github.halmuratuyghur.rally.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.github.halmuratuyghur.rally.api.RallyApiClient
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
    private var usernameField: JBTextField? = null
    private var exportDirField: TextFieldWithBrowseButton? = null

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
        usernameField = JBTextField().apply {
            toolTipText = "Rally UserName (shown in Test Connection result). Used for 'My Tickets' filter. Can be any user's username."
        }
        exportDirField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                "Select Export Directory",
                "Directory where Rally exports (JSON/Markdown) will be saved",
                null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
            )
            textField.toolTipText = "Export directory (leave empty for project_root/rally_testcases)"
        }

        val testButton = JButton("Test Connection").apply {
            addActionListener { testConnection() }
        }

        val settings = RallySettings.getInstance()
        serverUrlField!!.text = settings.serverUrl
        apiKeyField!!.text = settings.apiKey
        workspaceRefField!!.text = settings.workspaceRef
        usernameField!!.text = settings.username
        exportDirField!!.text = settings.exportDirectory

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server URL:"), serverUrlField!!)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField!!)
            .addLabeledComponent(JBLabel("Workspace Ref:"), workspaceRefField!!)
            .addLabeledComponent(JBLabel("Username:"), usernameField!!)
            .addLabeledComponent(JBLabel("Export Directory:"), exportDirField!!)
            .addComponent(testButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = RallySettings.getInstance()
        return serverUrlField?.text != settings.serverUrl ||
                String(apiKeyField?.password ?: charArrayOf()) != settings.apiKey ||
                workspaceRefField?.text != settings.workspaceRef ||
                usernameField?.text != settings.username ||
                exportDirField?.text != settings.exportDirectory
    }

    override fun apply() {
        val state = RallySettings.getInstance().state
        state.serverUrl = serverUrlField?.text?.trim() ?: ""
        state.apiKey = String(apiKeyField?.password ?: charArrayOf()).trim()
        state.workspaceRef = workspaceRefField?.text?.trim() ?: ""
        state.username = usernameField?.text?.trim() ?: ""
        state.exportDirectory = exportDirField?.text?.trim() ?: ""
    }

    override fun reset() {
        val settings = RallySettings.getInstance()
        serverUrlField?.text = settings.serverUrl
        apiKeyField?.text = settings.apiKey
        workspaceRefField?.text = settings.workspaceRef
        usernameField?.text = settings.username
        exportDirField?.text = settings.exportDirectory
    }

    override fun disposeUIResources() {
        serverUrlField = null
        apiKeyField = null
        workspaceRefField = null
        usernameField = null
        exportDirField = null
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
                val displayName = user.displayName ?: "Unknown"
                val userName = user.userName ?: "Unknown"
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        "Connected successfully!\n\nDisplay Name: $displayName\nUserName (for queries): $userName\n\nUse the UserName value above in the Username field for 'My Tickets' filter.",
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
