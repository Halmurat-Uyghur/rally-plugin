package com.github.halmurat.rally.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [apiKeyStatus] — the pure, headless model behind the Settings API-key
 * status indicator. Pins the three states and their exact copy so the user-visible
 * wording can't drift, and (critically) pins the precedence rule: while the async
 * PasswordSafe read is in flight ([loading] = true) the result is always LOADING,
 * so a key that exists in PasswordSafe never briefly renders as "not set".
 */
class ApiKeyStatusTextTest {

    @Test
    fun `loading dominates regardless of key presence`() {
        assertEquals(
            ApiKeyStatus("Checking…", ApiKeyStatusKind.LOADING),
            apiKeyStatus(keyPresent = false, loading = true)
        )
        // Even if the field already holds a key, an unfinished load must not yet assert "set".
        assertEquals(
            ApiKeyStatus("Checking…", ApiKeyStatusKind.LOADING),
            apiKeyStatus(keyPresent = true, loading = true)
        )
    }

    @Test
    fun `present key after load reads as saved`() {
        assertEquals(
            ApiKeyStatus("Saved to the IDE password store", ApiKeyStatusKind.SET),
            apiKeyStatus(keyPresent = true, loading = false)
        )
    }

    @Test
    fun `absent key after load reads as required`() {
        assertEquals(
            ApiKeyStatus("Required — no API key set", ApiKeyStatusKind.NOT_SET),
            apiKeyStatus(keyPresent = false, loading = false)
        )
    }
}
