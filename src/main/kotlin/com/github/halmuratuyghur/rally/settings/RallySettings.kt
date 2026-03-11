package com.github.halmuratuyghur.rally.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private var myState = State()
    @Volatile
    private var cachedApiKey: String? = null
    private val apiKeyLatch = CountDownLatch(1)

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        // Migrate cleartext API key to PasswordSafe off-EDT
        @Suppress("DEPRECATION")
        if (state.apiKey.isNotBlank()) {
            val keyToMigrate = state.apiKey
            state.apiKey = ""
            cachedApiKey = keyToMigrate
            apiKeyLatch.countDown()
            ApplicationManager.getApplication().executeOnPooledThread {
                PasswordSafe.instance.set(credentialAttributes, Credentials(CREDENTIAL_USER, keyToMigrate))
            }
        } else {
            // Eagerly load the API key from PasswordSafe off-EDT
            ApplicationManager.getApplication().executeOnPooledThread {
                cachedApiKey = PasswordSafe.instance.getPassword(credentialAttributes) ?: ""
                apiKeyLatch.countDown()
            }
        }
    }

    val serverUrl: String get() = myState.serverUrl
    val workspaceRef: String get() = myState.workspaceRef
    val username: String get() = myState.username
    val pageSize: Int get() = myState.pageSize

    var apiKey: String
        get() {
            if (cachedApiKey == null) {
                apiKeyLatch.await(2, TimeUnit.SECONDS)
            }
            return cachedApiKey ?: ""
        }
        set(value) {
            cachedApiKey = value
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
