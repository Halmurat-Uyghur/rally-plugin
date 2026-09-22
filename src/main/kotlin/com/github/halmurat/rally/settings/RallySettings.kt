package com.github.halmurat.rally.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    // Opaque persistence key written into RallyPlugin.xml as <component name="…">. The IDE uses
    // this string VERBATIM as the storage id — it is NOT resolved as a class, so it is fully
    // independent of this class's package. FROZEN at the original (pre-rename) package string ON
    // PURPOSE: the class moved to com.github.halmurat.rally during the package rename, but changing
    // this id would point the platform at a different <component> name and orphan every user's saved
    // settings (serverUrl, workspaceRef, username, selectedProject, selectedIteration, …), silently
    // breaking the "My Tickets" filter (buildQuery skips the owner condition when username is blank).
    // MUST NOT change on future renames — same stability rule as the PasswordSafe serviceName
    // below. Pinned by RallySettingsPersistenceKeysTest.
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
            completeApiKeyLoad(keyToMigrate)
            ApplicationManager.getApplication().executeOnPooledThread {
                PasswordSafe.instance.set(credentialAttributes, Credentials(CREDENTIAL_USER, keyToMigrate))
            }
        } else {
            loadApiKeyFromPasswordSafe()
        }
    }

    /**
     * Called by the platform when there is NO persisted state (fresh install, or the
     * settings XML was deleted). Without this, [loadState] never runs, so `apiKeyLoaded`
     * would stay false forever and every off-EDT [apiKey] read would block the full 2s
     * deadline. We still trigger the eager PasswordSafe load because the keychain entry
     * can outlive the settings XML — flipping the flag without loading would make a valid
     * stored key read as absent.
     */
    override fun noStateLoaded() {
        loadApiKeyFromPasswordSafe()
    }

    /** Eagerly load the API key from PasswordSafe off-EDT and signal readiness. */
    private fun loadApiKeyFromPasswordSafe() {
        synchronized(apiKeyReady) { cachedApiKey = null; apiKeyLoaded = false }
        ApplicationManager.getApplication().executeOnPooledThread {
            completeApiKeyLoadIfPending(PasswordSafe.instance.getPassword(credentialAttributes) ?: "")
        }
    }

    /**
     * Publish a PasswordSafe read unless a key was set while it was in flight: the read can
     * block for a long time (keychain access prompt), and its answer is then older than a key
     * the user saved from Settings meanwhile.
     */
    internal fun completeApiKeyLoadIfPending(key: String) {
        synchronized(apiKeyReady) {
            if (!apiKeyLoaded) completeApiKeyLoad(key)
        }
    }

    /** Publish the loaded key ("" = none stored) and wake every [awaitApiKey] waiter. */
    internal fun completeApiKeyLoad(key: String) {
        synchronized(apiKeyReady) {
            cachedApiKey = key
            apiKeyLoaded = true
            apiKeyReady.notifyAll()
        }
    }

    /**
     * Wait (off-EDT only) up to [timeoutMs] for the async PasswordSafe load. Returns the key
     * ("" when none is stored) once loaded, or null while the load is still in flight — so a
     * caller that writes the key back (the Settings page's apply()) can tell "still loading"
     * from "loaded and empty". A slow keychain read (access prompt, KeePass master password)
     * must never read as an empty key there, or Apply would overwrite the stored one.
     */
    fun awaitApiKey(timeoutMs: Long): String? {
        synchronized(apiKeyReady) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!apiKeyLoaded) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                try { apiKeyReady.wait(remaining) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return cachedApiKey ?: ""
        }
    }

    val serverUrl: String get() = myState.serverUrl
    val workspaceRef: String get() = myState.workspaceRef
    val username: String get() = myState.username
    val pageSize: Int get() = myState.pageSize

    var apiKey: String
        get() {
            // Read-only callers get "" if the load hasn't finished within 2s. Callers that
            // persist the value must use awaitApiKey() instead, which reports "still loading".
            if (!ApplicationManager.getApplication().isDispatchThread) awaitApiKey(2_000L)
            return cachedApiKey ?: ""
        }
        set(value) {
            completeApiKeyLoad(value)
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

    /**
     * EDT-safe variant of [isConfigured]. Returns `true` optimistically while the
     * async PasswordSafe load is still in progress, so UI actions immediately after
     * IDE startup are not blocked by a transient empty apiKey. Off-EDT callers
     * should keep using [isConfigured], which waits up to 2s on the latch.
     *
     * Safe because `cachedApiKey` is only assigned a non-null value once the load
     * (or migration) completes; while it is `null` the load is still in flight.
     */
    fun isConfiguredOrLoading(): Boolean {
        if (myState.serverUrl.isBlank()) return false
        val key = cachedApiKey ?: return true   // null = load still in flight
        return key.isNotBlank()
    }

    companion object {
        // PasswordSafe credential identifiers. Together with the @State name above, these are the
        // plugin's persistence keys: changing any of them orphans the user's stored API key and
        // forces re-entry, exactly like a changed @State name orphans the settings XML. They are
        // NOT derived from the package name on purpose and MUST stay frozen across renames.
        // Pinned by RallySettingsPersistenceKeysTest.
        internal const val CREDENTIAL_USER = "RallyPlugin"
        internal const val CREDENTIAL_SERVICE_SUBSYSTEM = "RallyPlugin"
        internal const val CREDENTIAL_SERVICE_KEY = "apiKey"

        // The single-arg CredentialAttributes(serviceName) constructor compiles (against the
        // 2024.1 SDK) to a synthetic default-args constructor that is marked deprecated in
        // newer platforms. We deliberately do NOT add a userName here: PasswordSafe keychain
        // backends key on serviceName+userName, so changing it would orphan already-stored
        // API keys and force users to re-enter them. serviceName-only is the correct, stable key.
        @Suppress("DEPRECATION")
        private val credentialAttributes = CredentialAttributes(
            generateServiceName(CREDENTIAL_SERVICE_SUBSYSTEM, CREDENTIAL_SERVICE_KEY)
        )

        fun getInstance(): RallySettings {
            return ApplicationManager.getApplication().getService(RallySettings::class.java)
        }
    }
}
