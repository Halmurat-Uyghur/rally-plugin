package com.github.halmurat.rally.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings page persists the API-key field on Apply once the stored key has "loaded".
 * A PasswordSafe read that is merely slow (keychain access prompt, KeePass master password)
 * must therefore read as "still loading", never as "loaded and empty" — otherwise Apply
 * writes a blank key over the stored one.
 */
class RallySettingsApiKeyLoadTest {

    @Test
    fun `awaitApiKey reports a load still in flight as null, not as an empty key`() {
        val settings = RallySettings()
        assertNull(settings.awaitApiKey(50))
    }

    @Test
    fun `awaitApiKey returns the stored key once the load completes`() {
        val settings = RallySettings()
        settings.completeApiKeyLoad("abc123")
        assertEquals("abc123", settings.awaitApiKey(50))
    }

    @Test
    fun `awaitApiKey distinguishes a loaded empty key from a pending load`() {
        val settings = RallySettings()
        settings.completeApiKeyLoad("")
        assertEquals("", settings.awaitApiKey(50))
    }

    @Test
    fun `awaitApiKey wakes as soon as a pending load completes`() {
        val settings = RallySettings()
        Thread {
            Thread.sleep(100)
            settings.completeApiKeyLoad("late-key")
        }.start()
        val started = System.nanoTime()
        assertEquals("late-key", settings.awaitApiKey(10_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `a keychain read that lands after a key was saved does not overwrite it`() {
        // The startup read can block (keychain prompt) while the user saves a new key in
        // Settings; its older answer must not replace the newer in-memory key.
        val settings = RallySettings()
        settings.completeApiKeyLoad("new-key")          // what the apiKey setter publishes
        settings.completeApiKeyLoadIfPending("old-key") // the late PasswordSafe read
        assertEquals("new-key", settings.awaitApiKey(50))
    }

    @Test
    fun `a keychain read publishes when nothing was saved meanwhile`() {
        val settings = RallySettings()
        settings.completeApiKeyLoadIfPending("stored-key")
        assertEquals("stored-key", settings.awaitApiKey(50))
    }
}
