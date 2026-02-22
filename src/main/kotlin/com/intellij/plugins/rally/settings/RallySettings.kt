package com.intellij.plugins.rally.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "com.intellij.plugins.rally.settings.RallySettings",
    storages = [Storage("RallyPlugin.xml")]
)
class RallySettings : PersistentStateComponent<RallySettings.State> {

    data class State(
        var serverUrl: String = "https://rally1.rallydev.com",
        var apiKey: String = "",
        var workspaceRef: String = "",
        var username: String = "",
        var pageSize: Int = 200,
        var selectedProject: String = "",
        var selectedIteration: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val serverUrl: String get() = myState.serverUrl
    val apiKey: String get() = myState.apiKey
    val workspaceRef: String get() = myState.workspaceRef
    val username: String get() = myState.username
    val pageSize: Int get() = myState.pageSize
    var selectedProject: String
        get() = myState.selectedProject
        set(value) { myState.selectedProject = value }
    var selectedIteration: String
        get() = myState.selectedIteration
        set(value) { myState.selectedIteration = value }

    fun isConfigured(): Boolean = myState.serverUrl.isNotBlank() && myState.apiKey.isNotBlank()

    companion object {
        fun getInstance(): RallySettings {
            return ApplicationManager.getApplication().getService(RallySettings::class.java)
        }
    }
}
