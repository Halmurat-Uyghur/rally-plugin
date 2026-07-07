package com.github.halmurat.rally.settings

import com.intellij.util.messages.Topic

/**
 * Application-level notification that Rally settings were applied (L9). The tool
 * window subscribes and reloads, so a changed server/key/username takes effect
 * immediately instead of leaving stale data on screen until a manual Refresh
 * (the client itself was already rebuilt lazily off the settings snapshot —
 * this closes the UI loop, not a staleness bug).
 */
interface RallySettingsListener {
    fun settingsApplied()

    companion object {
        @JvmField
        val TOPIC: Topic<RallySettingsListener> =
            Topic.create("Rally settings applied", RallySettingsListener::class.java)
    }
}
