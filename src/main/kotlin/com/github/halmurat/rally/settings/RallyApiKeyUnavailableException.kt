package com.github.halmurat.rally.settings

/**
 * The API key can't be used yet: the PasswordSafe read is still in flight (keychain access
 * prompt, KeePass master password) or it failed. Thrown instead of building a client with an
 * empty key, whose every call would otherwise fail as a misleading "Auth error".
 */
class RallyApiKeyUnavailableException(val loadFailed: Boolean) : IllegalStateException(
    if (loadFailed) "Couldn't read the Rally API key from the IDE credential store. Re-enter it in Settings → Tools → Rally."
    else "Waiting for the Rally API key from the IDE credential store..."
)
