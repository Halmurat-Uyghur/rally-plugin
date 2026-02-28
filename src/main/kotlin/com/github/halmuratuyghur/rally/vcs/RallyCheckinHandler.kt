package com.github.halmuratuyghur.rally.vcs

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory

class RallyCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return RallyCheckinHandler(panel)
    }
}

private class RallyCheckinHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    override fun getBeforeCheckinConfigurationPanel(): com.intellij.openapi.vcs.ui.RefreshableOnComponent? {
        val project = panel.project
        val session = RallyWorkSession.getInstance(project)
        if (!session.isActive) return null

        val ticketId = session.activeTicketId ?: return null
        val prefix = "[$ticketId]"
        val currentMessage = panel.commitMessage

        if (!currentMessage.startsWith(prefix)) {
            panel.commitMessage = "$prefix $currentMessage"
        }

        return null
    }
}
