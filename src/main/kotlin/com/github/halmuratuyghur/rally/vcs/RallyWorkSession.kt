package com.github.halmuratuyghur.rally.vcs

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class RallyWorkSession {
    var activeTicketId: String? = null
    var activeTicketRef: String? = null
    var activeTicketType: String? = null
    var activeBranchName: String? = null

    val isActive: Boolean get() = activeTicketId != null

    fun start(ticketId: String, ticketRef: String, ticketType: String, branchName: String) {
        activeTicketId = ticketId
        activeTicketRef = ticketRef
        activeTicketType = ticketType
        activeBranchName = branchName
    }

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
