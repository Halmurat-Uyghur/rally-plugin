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
    private var pageSizeField: JBTextField? = null
    @Volatile private var loadedApiKey: String = ""
    @Volatile private var apiKeyLoaded = false

    override fun getDisplayName(): String = "Rally"

    override fun createComponent(): JComponent {
        serverUrlField = JBTextField().apply {
            toolTipText = "Rally server URL (e.g., https://rally1.rallydev.com)"
        }
        apiKeyField = JPasswordField().apply {
            toolTipText = "Rally API Key (get it from Rally Profile → API Keys)"
        }
        workspaceRefField = JBTextField().apply {
            toolTipText = "Workspace reference (optional — e.g., /workspace/12345). Leave blank to use your default workspace."
        }
        usernameField = JBTextField().apply {
            toolTipText = "Your Rally UserName (email address, e.g. john.doe@company.com). Used for 'My Tickets' filter and 'Assign to me'. Use Test Connection to validate."
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

        pageSizeField = JBTextField().apply {
            toolTipText = "Number of items to fetch per API request (25-200, default: 200)"
        }

        val testButton = JButton("Test Connection").apply {
            addActionListener { testConnection() }
        }

        val settings = RallySettings.getInstance()
        serverUrlField!!.text = settings.serverUrl
        workspaceRefField!!.text = settings.workspaceRef
        usernameField!!.text = settings.username
        exportDirField!!.text = settings.exportDirectory
        pageSizeField!!.text = settings.pageSize.toString()

        // Load API key off-EDT to avoid blocking and to prevent writing blank on early apply
        apiKeyLoaded = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = settings.apiKey
            loadedApiKey = key
            ApplicationManager.getApplication().invokeLater {
                val field = apiKeyField ?: return@invokeLater
                // Only populate if user hasn't started typing
                if (field.password.isEmpty()) {
                    field.text = key
                }
                apiKeyLoaded = true
            }
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server URL:"), serverUrlField!!)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField!!)
            .addLabeledComponent(JBLabel("Workspace Ref (optional):"), workspaceRefField!!)
            .addLabeledComponent(JBLabel("Username:"), usernameField!!)
            .addLabeledComponent(JBLabel("Export Directory:"), exportDirField!!)
            .addLabeledComponent(JBLabel("Page Size:"), pageSizeField!!)
            .addComponent(testButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = RallySettings.getInstance()
        return serverUrlField?.text != settings.serverUrl ||
                String(apiKeyField?.password ?: charArrayOf()) != loadedApiKey ||
                workspaceRefField?.text != settings.workspaceRef ||
                usernameField?.text != settings.username ||
                exportDirField?.text != settings.exportDirectory ||
                pageSizeField?.text != settings.pageSize.toString()
    }

    override fun apply() {
        val settings = RallySettings.getInstance()
        val state = settings.state
        state.serverUrl = serverUrlField?.text?.trim() ?: ""
        state.workspaceRef = workspaceRefField?.text?.trim() ?: ""
        state.username = usernameField?.text?.trim() ?: ""
        state.exportDirectory = exportDirField?.text?.trim() ?: ""
        state.pageSize = pageSizeField?.text?.trim()?.toIntOrNull()?.coerceIn(25, 200) ?: 200
        // Only save API key if the async load completed (so we know the field has real data)
        // or if the user explicitly typed a new key
        val fieldKey = String(apiKeyField?.password ?: charArrayOf()).trim()
        if (apiKeyLoaded || fieldKey.isNotBlank()) {
            loadedApiKey = fieldKey
            settings.apiKey = fieldKey
        }
    }

    override fun reset() {
        val settings = RallySettings.getInstance()
        serverUrlField?.text = settings.serverUrl
        workspaceRefField?.text = settings.workspaceRef
        usernameField?.text = settings.username
        exportDirField?.text = settings.exportDirectory
        pageSizeField?.text = settings.pageSize.toString()
        // Load API key off-EDT
        apiKeyLoaded = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = settings.apiKey
            loadedApiKey = key
            ApplicationManager.getApplication().invokeLater {
                apiKeyField?.text = key
                apiKeyLoaded = true
            }
        }
    }

    override fun disposeUIResources() {
        serverUrlField = null
        apiKeyField = null
        workspaceRefField = null
        usernameField = null
        exportDirField = null
        pageSizeField = null
    }

    private fun testConnection() {
        val url = serverUrlField?.text?.trim() ?: ""
        val key = String(apiKeyField?.password ?: charArrayOf()).trim()

        if (url.isBlank() || key.isBlank()) {
            Messages.showErrorDialog("Please provide server URL and API key.", "Rally Connection")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            var client: RallyApiClient? = null
            try {
                client = RallyApiClient(url, key)
                val apiKeyOwner = client.getCurrentUser()
                val apiKeyName = apiKeyOwner.displayName ?: "Unknown"
                val apiKeyUserName = apiKeyOwner.userName ?: "Unknown"

                // Auto-fill username if empty
                ApplicationManager.getApplication().invokeLater {
                    if (usernameField?.text.isNullOrBlank()) {
                        usernameField?.text = apiKeyUserName
                    }
                }

                // Check if a username is configured and validate it
                val configuredUsername = usernameField?.text?.trim() ?: ""
                val usernameInfo = if (configuredUsername.isNotBlank()) {
                    try {
                        val configuredUser = client.getUserByUsername(configuredUsername)
                        val name = configuredUser.displayName ?: configuredUser.refObjectName ?: "Unknown"
                        "\n\nConfigured Username: $configuredUsername\nResolved to: $name (valid)"
                    } catch (_: Exception) {
                        "\n\nConfigured Username: $configuredUsername\nWarning: No Rally user found with this UserName! 'My Tickets' filter will not work."
                    }
                } else {
                    "\n\nUsername field is empty. Enter your Rally UserName (email) for 'My Tickets' filter."
                }

                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        "Connected successfully!\n\nAPI Key Owner: $apiKeyName ($apiKeyUserName)$usernameInfo",
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
            } finally {
                client?.apiExecutor?.shutdownNow()
            }
        }
    }
}
