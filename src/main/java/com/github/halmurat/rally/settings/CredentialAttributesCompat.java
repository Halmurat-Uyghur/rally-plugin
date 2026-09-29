package com.github.halmurat.rally.settings;

import com.intellij.credentialStore.CredentialAttributes;

/**
 * Builds {@link CredentialAttributes} through its plain {@code (String serviceName)} constructor.
 *
 * <p>Kotlin can't pick a {@code @JvmOverloads} overload: against the 2024.1 SDK it compiles
 * {@code CredentialAttributes(name)} to the synthetic default-args constructor with a
 * {@code requestor} Class, which newer platforms deprecate. javac binds the exact
 * {@code (String)} constructor, which exists and is not deprecated from 241 through 262.
 */
final class CredentialAttributesCompat {
    private CredentialAttributesCompat() {
    }

    static CredentialAttributes serviceOnly(String serviceName) {
        return new CredentialAttributes(serviceName);
    }
}
