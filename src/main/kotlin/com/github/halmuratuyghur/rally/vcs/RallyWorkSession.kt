package com.github.halmuratuyghur.rally.vcs

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class RallyWorkSession {
    @Volatile var activeTicketId: String? = null
        private set
    @Volatile var activeTicketRef: String? = null
        private set
    @Volatile var activeTicketType: String? = null
        private set
    @Volatile var activeBranchName: String? = null
        private set

    val isActive: Boolean get() = activeTicketId != null

    @Synchronized
    fun start(ticketId: String, ticketRef: String, ticketType: String, branchName: String) {
        activeTicketId = ticketId
        activeTicketRef = ticketRef
        activeTicketType = ticketType
        activeBranchName = branchName
    }

    @Synchronized
    fun finish() {
        activeTicketId = null
        activeTicketRef = null
        activeTicketType = null
        activeBranchName = null
    }

    companion object {
        fun getInstance(project: Project): RallyWorkSession =
            project.getService(RallyWorkSession::class.java)
    }
}
