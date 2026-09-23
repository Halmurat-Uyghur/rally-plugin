package com.github.halmurat.rally.settings

import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the plugin's persistence keys.
 *
 * Two opaque identifiers decide where user data is stored and must therefore stay **frozen across
 * package renames**, even though the class itself lives in `com.github.halmurat.rally`:
 *
 *  1. `@State(name = …)` — the `<component name="…">` id under which settings live in
 *     `RallyPlugin.xml` (serverUrl, workspaceRef, username, selectedProject, selectedIteration, …).
 *  2. The PasswordSafe `serviceName` (`generateServiceName(subsystem, key)`) + credential user
 *     under which the API key is stored in the OS keychain.
 *
 * A package rename (commit fef51b2, com.github.halmuratuyghur.rally → com.github.halmurat.rally)
 * once changed (1) along with the package and orphaned every user's saved settings: with `username`
 * reading back blank, the "My Tickets" filter silently degraded to showing all tickets, because
 * `RallyToolWindowPanel.buildQuery()` only adds the `(Owner.UserName = …)` condition when
 * `username.isNotBlank()`. The API key survived only because (2) was deliberately kept stable.
 *
 * These assertions pin both identifiers so that any future refactor which rewrites either literal
 * fails here, loudly, instead of silently in users' IDEs. They are pure compile-time/reflection
 * checks — no IDE fixture is needed (annotation reads do not run the class initializer, and the
 * credential identifiers are inlined `const` values).
 */
class RallySettingsPersistenceKeysTest {

    private fun stateAnnotation(): State =
        RallySettings::class.java.getAnnotation(State::class.java)
            ?: error("RallySettings is missing its @State annotation")

    @Test
    fun `@State name is frozen at the original package string`() {
        assertEquals(
            "com.github.halmuratuyghur.rally.settings.RallySettings",
            stateAnnotation().name
        )
    }

    @Test
    fun `@State persists to RallyPlugin xml`() {
        val storage: Storage = stateAnnotation().storages.singleOrNull()
            ?: error("Expected exactly one @Storage on RallySettings")
        assertEquals("RallyPlugin.xml", storage.value)
    }

    @Test
    fun `PasswordSafe credential identifiers are frozen`() {
        assertEquals("RallyPlugin", RallySettings.CREDENTIAL_SERVICE_SUBSYSTEM)
        assertEquals("apiKey", RallySettings.CREDENTIAL_SERVICE_KEY)
        assertEquals("RallyPlugin", RallySettings.CREDENTIAL_USER)
    }
}
