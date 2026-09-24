package com.github.halmurat.rally.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.github.halmurat.rally.api.RallyApiClient
import com.github.halmurat.rally.api.RallyUserNotFoundException
import com.github.halmurat.rally.util.literalHtmlMessage
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import java.util.concurrent.atomic.AtomicInteger

class RallySettingsConfigurable : Configurable {

    private var serverUrlField: JBTextField? = null
    private var apiKeyField: JPasswordField? = null
    private var workspaceRefField: JBTextField? = null
    private var usernameField: JBTextField? = null
    private var exportDirField: TextFieldWithBrowseButton? = null
    private var pageSizeField: JBTextField? = null
    private var apiKeyStatusLabel: JBLabel? = null
    @Volatile private var loadedApiKey: String = ""
    @Volatile private var apiKeyLoaded = false
    /** Bumped per API-key load and on dispose; a pending load stops once superseded. */
    private val apiKeyLoadGeneration = AtomicInteger()

    override fun getDisplayName(): String = "Rally"

    // The 4-arg addBrowseFolderListener(title, description, project, descriptor) used below
    // is deprecated in newer platforms in favor of a 2-arg overload that does not exist in
    // 2024.1/2024.2. Suppressed at the method level to keep sinceBuild=241; migrate to
    // addBrowseFolderListener(project, descriptor.withTitle(...)) once the floor is raised to 243+.
    @Suppress("DEPRECATION")
    override fun createComponent(): JComponent {
        serverUrlField = JBTextField().apply {
            toolTipText = "Rally server URL (e.g., https://rally1.rallydev.com)"
        }
        apiKeyField = JPasswordField().apply {
            toolTipText = "Rally API Key (generate one on your Rally API Keys page)"
        }
        apiKeyStatusLabel = JBLabel()
        // Recompute the status line on every edit. Cheap (a string check) and the only
        // way the indicator can stay truthful as the user types or clears the field —
        // which is what apply() persists (it CLEARS the stored key when left empty).
        apiKeyField!!.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateApiKeyStatus()
            override fun removeUpdate(e: DocumentEvent) = updateApiKeyStatus()
            override fun changedUpdate(e: DocumentEvent) = updateApiKeyStatus()
        })
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

        loadApiKeyAsync()

        // Each label is linked to its field via labelFor so screen readers can announce
        // the field name when focus moves to the input.
        val serverUrlLabel = JBLabel("Server URL:").apply { labelFor = serverUrlField }
        val apiKeyLabel = JBLabel("API Key:").apply { labelFor = apiKeyField }
        val workspaceLabel = JBLabel("Workspace Ref (optional):").apply { labelFor = workspaceRefField }
        val usernameLabel = JBLabel("Username:").apply { labelFor = usernameField }
        val exportDirLabel = JBLabel("Export Directory:").apply { labelFor = exportDirField!!.textField }
        val pageSizeLabel = JBLabel("Page Size:").apply { labelFor = pageSizeField }

        // Help text uses createCommentComponent (small, gray, auto-wrapping). The class is
        // deprecated in newer platforms in favor of the Kotlin UI DSL, but that would mean
        // rewriting this whole FormBuilder page; the factory is present and functional through
        // 261, so we reference it fully-qualified inside this @Suppress("DEPRECATION") method
        // (an import would warn outside the method scope). No hard-coded URL: the Rally server
        // is per-user (incl. on-prem), so a static path/link could be wrong.
        val apiKeyHelp = com.intellij.openapi.ui.panel.ComponentPanelBuilder.createCommentComponent(
            "First time? Generate an API key on your Rally API Keys page, then paste it into the API Key field.",
            true
        )

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(serverUrlLabel, serverUrlField!!)
            .addLabeledComponent(apiKeyLabel, apiKeyField!!)
            // Right column = aligned under the password field (not the label).
            .addComponentToRightColumn(apiKeyStatusLabel!!)
            .addComponentToRightColumn(apiKeyHelp)
            .addLabeledComponent(workspaceLabel, workspaceRefField!!)
            .addLabeledComponent(usernameLabel, usernameField!!)
            .addLabeledComponent(exportDirLabel, exportDirField!!)
            .addLabeledComponent(pageSizeLabel, pageSizeField!!)
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

        // Nudge open tool windows to reload with the new settings (L9).
        ApplicationManager.getApplication().messageBus
            .syncPublisher(RallySettingsListener.TOPIC)
            .settingsApplied()
    }

    override fun reset() {
        val settings = RallySettings.getInstance()
        serverUrlField?.text = settings.serverUrl
        workspaceRefField?.text = settings.workspaceRef
        usernameField?.text = settings.username
        exportDirField?.text = settings.exportDirectory
        pageSizeField?.text = settings.pageSize.toString()
        // Revert the field now; the stored key fills it once read (unless the user types first).
        apiKeyField?.text = ""
        loadApiKeyAsync()
    }

    /**
     * Load the stored API key off-EDT, then fill the field (only if the user hasn't typed into
     * it meanwhile — the read can land long after the page opened) and mark it loaded.
     *
     * [apiKeyLoaded] gates apply(): once set, apply() persists the field even when it is
     * blank. So it is set only after a REAL PasswordSafe read ([RallySettings.awaitApiKey]),
     * never on a timeout — a slow keychain read (access prompt, KeePass master password)
     * used to read as "" after 2s and let Apply overwrite the stored key with a blank one.
     * Until the read lands the status stays "Checking…" and a blank field is not persisted.
     * The wait ends when the read lands or when this load is superseded (reset/dispose).
     *
     * The modality state is captured on the EDT: the Settings dialog is modal, so an
     * invokeLater from the pooled thread would otherwise default to NON_MODAL and be
     * deferred until the dialog closes.
     */
    private fun loadApiKeyAsync() {
        apiKeyLoaded = false
        updateApiKeyStatus()   // show "Checking…" until the async read resolves
        val generation = apiKeyLoadGeneration.incrementAndGet()
        val modality = ModalityState.current()
        val settings = RallySettings.getInstance()
        ApplicationManager.getApplication().executeOnPooledThread {
            var key: String? = null
            while (key == null && apiKeyLoadGeneration.get() == generation) {
                key = settings.awaitApiKey(1_000L)
            }
            if (key == null) return@executeOnPooledThread   // superseded by reset()/dispose
            ApplicationManager.getApplication().invokeLater({
                if (apiKeyLoadGeneration.get() != generation) return@invokeLater
                val field = apiKeyField ?: return@invokeLater
                loadedApiKey = key
                // Flip the flag BEFORE the programmatic setText: setText notifies the
                // DocumentListener synchronously, so the listener must already see the
                // loaded state or it would flicker through a stale "Checking…".
                apiKeyLoaded = true
                if (field.password.isEmpty()) field.text = key
                updateApiKeyStatus()
            }, modality)
        }
    }

    /**
     * Recompute the API-key status line under the field. EDT-only. Reads ONLY the live
     * field text and the @Volatile [apiKeyLoaded] flag — never the blocking
     * `settings.apiKey` getter — so it is safe on the EDT and from the async callbacks.
     * Drives `keyPresent` from the field (what [apply] will persist), not the stored value.
     */
    private fun updateApiKeyStatus() {
        val label = apiKeyStatusLabel ?: return
        val keyPresent = apiKeyField?.let { String(it.password).isNotBlank() } ?: false
        val status = apiKeyStatus(keyPresent = keyPresent, loading = !apiKeyLoaded)
        label.text = status.text
        label.icon = when (status.kind) {
            ApiKeyStatusKind.SET -> AllIcons.General.GreenCheckmark
            ApiKeyStatusKind.NOT_SET -> AllIcons.General.Warning
            ApiKeyStatusKind.LOADING -> null
        }
        label.foreground =
            if (status.kind == ApiKeyStatusKind.NOT_SET) NamedColorUtil.getErrorForeground()
            else UIUtil.getLabelForeground()
    }

    override fun disposeUIResources() {
        apiKeyLoadGeneration.incrementAndGet()   // stop any pending API-key wait
        serverUrlField = null
        apiKeyField = null
        workspaceRefField = null
        usernameField = null
        exportDirField = null
        pageSizeField = null
        apiKeyStatusLabel = null
    }

    private fun testConnection() {
        // Capture all Swing field state on the EDT before dispatching to a background thread.
        val url = serverUrlField?.text?.trim() ?: ""
        val key = String(apiKeyField?.password ?: charArrayOf()).trim()
        val fieldUsername = usernameField?.text?.trim() ?: ""
        val fieldWorkspace = workspaceRefField?.text?.trim() ?: ""

        if (url.isBlank() || key.isBlank()) {
            Messages.showErrorDialog("Please provide server URL and API key.", "Rally Connection")
            return
        }

        // The result/auto-fill callbacks below open or update UI on top of the modal Settings
        // dialog; capture its modality on the EDT so they aren't deferred (NON_MODAL) until the
        // dialog closes — which would make Test Connection appear to do nothing.
        val modality = ModalityState.current()
        ApplicationManager.getApplication().executeOnPooledThread {
            var client: RallyApiClient? = null
            try {
                client = RallyApiClient(url, key)
                // Scope the checks below the way the tool window will: a mistyped Workspace Ref
                // must fail here, not after "Connected successfully!" on every load.
                client.workspaceRef = fieldWorkspace.ifBlank { null }
                val apiKeyOwner = client.getCurrentUser()
                val apiKeyName = apiKeyOwner.displayName ?: "Unknown"
                val apiKeyUserName = apiKeyOwner.userName ?: "Unknown"

                val configuredUsername = fieldUsername.ifBlank { apiKeyUserName }

                // Auto-fill username if empty
                ApplicationManager.getApplication().invokeLater({
                    if (usernameField?.text.isNullOrBlank()) {
                        usernameField?.text = apiKeyUserName
                    }
                }, modality)

                val workspace = if (fieldWorkspace.isNotBlank()) {
                    try {
                        WorkspaceCheck.Found(client.getWorkspaceName(fieldWorkspace))
                    } catch (e: Exception) {
                        LOG.warn("Workspace check failed for '$fieldWorkspace'", e)
                        WorkspaceCheck.Unreadable(fieldWorkspace, e.message ?: e.javaClass.simpleName)
                    }
                } else null

                // Check if a username is configured and validate it
                val username = if (workspace is WorkspaceCheck.Unreadable) {
                    UsernameCheck.Skipped // the user lookup is workspace-scoped; it would only repeat the error
                } else if (configuredUsername.isNotBlank()) {
                    try {
                        val configuredUser = client.getUserByUsername(configuredUsername)
                        UsernameCheck.Resolved(configuredUsername, configuredUser.displayName ?: configuredUser.refObjectName ?: "Unknown")
                    } catch (_: RallyUserNotFoundException) {
                        UsernameCheck.NotFound(configuredUsername)
                    } catch (e: Exception) {
                        // Could be a genuine "no such user" OR a transient network/auth error.
                        // Don't assert the username is wrong — log the cause and word it neutrally.
                        LOG.warn("Username verification failed for '$configuredUsername'", e)
                        UsernameCheck.LookupFailed(configuredUsername, e.message)
                    }
                } else {
                    UsernameCheck.Blank
                }

                val report = connectionTestReport(apiKeyName, apiKeyUserName, workspace, username)
                ApplicationManager.getApplication().invokeLater({
                    if (report.warning) {
                        Messages.showWarningDialog(report.message, "Rally Connection")
                    } else {
                        Messages.showInfoMessage(report.message, "Rally Connection")
                    }
                }, modality)
            } catch (e: Exception) {
                LOG.warn("Rally test connection failed", e)
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog(connectionFailedMessage(e), "Rally Connection")
                }, modality)
            } finally {
                client?.apiExecutor?.shutdownNow()
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(RallySettingsConfigurable::class.java)
    }
}

/** What Test Connection found for the Workspace Ref (there is no check when the field is blank). */
internal sealed class WorkspaceCheck {
    data class Found(val name: String) : WorkspaceCheck()
    data class Unreadable(val ref: String, val reason: String) : WorkspaceCheck()
}

/** What Test Connection found for the Username. */
internal sealed class UsernameCheck {
    /** No Username to check. */
    object Blank : UsernameCheck()

    /** Not looked up: the lookup is workspace-scoped, and the workspace check failed. */
    object Skipped : UsernameCheck()
    data class Resolved(val username: String, val name: String) : UsernameCheck()
    data class NotFound(val username: String) : UsernameCheck()
    data class LookupFailed(val username: String, val reason: String?) : UsernameCheck()
}

/** The Test Connection result dialog: a warning (the workspace check failed) or not, and its text. */
internal data class ConnectionTestReport(val warning: Boolean, val message: String)

/**
 * The Test Connection result for the API key owner's [ownerName] and [ownerUserName] and what the
 * checks found. IntelliJ renders Messages text as HTML (AlertDialog, with a live-link listener),
 * and every value here comes from Rally or the server — DisplayNames, the workspace name, error
 * text — so the text is built plain and shown literally ([literalHtmlMessage]): markup in a Rally
 * name reads as written instead of rendering, or loading an `<img>` it names.
 */
internal fun connectionTestReport(
    ownerName: String,
    ownerUserName: String,
    workspace: WorkspaceCheck?,
    username: UsernameCheck,
): ConnectionTestReport {
    val workspaceInfo = when (workspace) {
        null -> ""
        is WorkspaceCheck.Found -> "\n\nWorkspace: ${workspace.name}"
        is WorkspaceCheck.Unreadable ->
            "\n\nWorkspace Ref '${workspace.ref}' could not be read: ${workspace.reason}\n" +
                "Check the Workspace Ref (the workspace's ObjectID), or leave it empty to use your default workspace."
    }
    val usernameInfo = when (username) {
        UsernameCheck.Skipped -> ""
        UsernameCheck.Blank -> "\n\nUsername field is empty. Enter your Rally UserName (email) for 'My Tickets' filter."
        is UsernameCheck.Resolved -> "\n\nConfigured Username: ${username.username}\nResolved to: ${username.name} (valid)"
        is UsernameCheck.NotFound ->
            "\n\nConfigured Username: ${username.username}\nWarning: no Rally user has this UserName — " +
                "'My Tickets' will be empty and tickets can't be assigned to you."
        is UsernameCheck.LookupFailed ->
            "\n\nConfigured Username: ${username.username}\nWarning: could not verify this UserName " +
                "(lookup failed: ${username.reason}). If it is correct, 'My Tickets' will still work."
    }
    val warning = workspace is WorkspaceCheck.Unreadable
    val headline = if (warning) "Connected, but the workspace check failed." else "Connected successfully!"
    return ConnectionTestReport(
        warning,
        literalHtmlMessage("$headline\n\nAPI Key Owner: $ownerName ($ownerUserName)$workspaceInfo$usernameInfo")
    )
}

/** The Test Connection error dialog's text for [e]: server text, shown literally like [connectionTestReport]'s. */
internal fun connectionFailedMessage(e: Exception): String = literalHtmlMessage("Connection failed: ${e.message}")
