package com.github.halmurat.rally.settings

/**
 * Pure, headless-testable model for the Settings API-key status indicator
 * (shown under the API Key field in Settings → Tools → Rally).
 *
 * Kept free of Swing/PasswordSafe so it can be unit-tested without an IDE harness,
 * mirroring the [com.github.halmurat.rally.ui.Scope] / StateFilter precedent. The
 * Swing layer in [RallySettingsConfigurable] maps [ApiKeyStatusKind] to an icon and
 * color; this object only decides the text + kind.
 */
enum class ApiKeyStatusKind { LOADING, SET, NOT_SET }

data class ApiKeyStatus(val text: String, val kind: ApiKeyStatusKind)

/**
 * Decide the status line.
 *
 * Precedence is deliberate: while the async PasswordSafe read is still in flight
 * ([loading] = true) the result is always [ApiKeyStatusKind.LOADING], regardless of
 * [keyPresent] — showing "not set" before the stored key has been read would flash a
 * false warning for a user who actually has a key saved.
 *
 * Once loaded, [keyPresent] must reflect the CURRENT FIELD CONTENTS (what `apply()`
 * will persist), not the stored value: `apply()` clears the key when the field is left
 * empty, so a field-driven status is the only one that truthfully predicts the outcome.
 *
 * The wording is intentionally backend-agnostic. The key is handed to the IDE's
 * PasswordSafe, whose actual at-rest store is a user setting (OS keychain / KeePass /
 * memory-only), so we claim only that it is saved to the IDE password store — never a
 * specific store or a security guarantee the plugin cannot back up.
 */
fun apiKeyStatus(keyPresent: Boolean, loading: Boolean): ApiKeyStatus = when {
    loading -> ApiKeyStatus("Checking…", ApiKeyStatusKind.LOADING)
    keyPresent -> ApiKeyStatus("Saved to the IDE password store", ApiKeyStatusKind.SET)
    else -> ApiKeyStatus("Required — no API key set", ApiKeyStatusKind.NOT_SET)
}
