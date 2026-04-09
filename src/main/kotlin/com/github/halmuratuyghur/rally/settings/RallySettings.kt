package com.github.halmuratuyghur.rally.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "com.github.halmuratuyghur.rally.settings.RallySettings",
    storages = [Storage("RallyPlugin.xml")]
)
class RallySettings : PersistentStateComponent<RallySettings.State> {

    data class State(
        var serverUrl: String = "https://rally1.rallydev.com",
        @Deprecated("Use PasswordSafe via apiKey property instead")
        var apiKey: String = "",
        var workspaceRef: String = "",
        var username: String = "",
        var pageSize: Int = 200,
        var selectedProject: String = "",
        var selectedIteration: String = "",
        var exportDirectory: String = ""
    )

    @Volatile private var myState = State()
    @Volatile
    private var cachedApiKey: String? = null
    private val apiKeyReady = Object()
    @Volatile
    private var apiKeyLoaded = false

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        // Migrate cleartext API key to PasswordSafe off-EDT
        @Suppress("DEPRECATION")
        if (state.apiKey.isNotBlank()) {
            val keyToMigrate = state.apiKey
            state.apiKey = ""
            synchronized(apiKeyReady) {
                cachedApiKey = keyToMigrate
                apiKeyLoaded = true
                apiKeyReady.notifyAll()
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                PasswordSafe.instance.set(credentialAttributes, Credentials(CREDENTIAL_USER, keyToMigrate))
            }
        } else {
            synchronized(apiKeyReady) { cachedApiKey = null; apiKeyLoaded = false }
            // Eagerly load the API key from PasswordSafe off-EDT
            ApplicationManager.getApplication().executeOnPooledThread {
                val key = PasswordSafe.instance.getPassword(credentialAttributes) ?: ""
                synchronized(apiKeyReady) {
                    cachedApiKey = key
                    apiKeyLoaded = true
                    apiKeyReady.notifyAll()
                }
            }
        }
    }

    val serverUrl: String get() = myState.serverUrl
    val workspaceRef: String get() = myState.workspaceRef
    val username: String get() = myState.username
    val pageSize: Int get() = myState.pageSize

    var apiKey: String
        get() {
            if (!ApplicationManager.getApplication().isDispatchThread) {
                synchronized(apiKeyReady) {
                    val deadline = System.currentTimeMillis() + 2_000L
                    while (!apiKeyLoaded) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) break
                        apiKeyReady.wait(remaining)
                    }
                }
            }
            return cachedApiKey ?: ""
        }
        set(value) {
            synchronized(apiKeyReady) {
                cachedApiKey = value
                apiKeyLoaded = true
                apiKeyReady.notifyAll()
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                PasswordSafe.instance.set(credentialAttributes, Credentials(CREDENTIAL_USER, value))
            }
        }

    var selectedProject: String
        get() = myState.selectedProject
        set(value) { myState.selectedProject = value }
    var selectedIteration: String
        get() = myState.selectedIteration
        set(value) { myState.selectedIteration = value }
    var exportDirectory: String
        get() = myState.exportDirectory
        set(value) { myState.exportDirectory = value }

    fun isConfigured(): Boolean = myState.serverUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        private const val CREDENTIAL_USER = "RallyPlugin"
        private val credentialAttributes = CredentialAttributes(
            generateServiceName("RallyPlugin", "apiKey")
        )

        fun getInstance(): RallySettings {
            return ApplicationManager.getApplication().getService(RallySettings::class.java)
        }
    }
}
