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

    override fun beforeCheckin(): ReturnResult {
        val project = panel.project
        val session = RallyWorkSession.getInstance(project)
        if (!session.isActive) return ReturnResult.COMMIT

        val ticketId = session.activeTicketId ?: return ReturnResult.COMMIT
        val trailer = "Refs: $ticketId"
        val currentMessage = panel.commitMessage

        if (!currentMessage.contains(trailer)) {
            // Append as a git trailer after a blank line separator
            val trimmed = currentMessage.trimEnd()
            panel.commitMessage = if (trimmed.contains("\n\n")) {
                "$trimmed\n$trailer"
            } else {
                "$trimmed\n\n$trailer"
            }
        }

        return ReturnResult.COMMIT
    }
}
