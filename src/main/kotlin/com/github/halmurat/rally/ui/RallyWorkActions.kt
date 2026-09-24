package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyArtifact
import com.github.halmurat.rally.api.RallyDefect
import com.github.halmurat.rally.api.RallyTaskItem
import com.github.halmurat.rally.api.RallyTestCase
import com.github.halmurat.rally.api.RallyUser
import com.github.halmurat.rally.api.RallyUserNotFoundException
import com.github.halmurat.rally.api.RallyUserStory
import com.github.halmurat.rally.util.escapeHtml

// Decision logic behind the state-change, Start Working and Create toolbar actions, kept out of
// the Swing panel so it is unit-testable (same pattern as stateChangeAction in RallyLoadStatus.kt).

internal const val START_WORKING_TOOLTIP = "<html><b>Start working on the selected ticket</b><br>" +
    "1. Creates (or checks out) a git branch, e.g. <code>feature/US123</code> — you pick the prefix<br>" +
    "2. Moves the ticket to In-Progress<br>" +
    "3. Assigns it to you (the Username in Settings)<br>" +
    "The Rally ticket is left unchanged if the branch can't be checked out.</html>"
// Screen readers announce the tooltip as the accessible description when none is set,
// which would read the HTML markup above aloud — so the button carries this plain twin.
internal const val START_WORKING_DESCRIPTION = "Start working on the selected ticket: creates " +
    "or checks out a git branch, moves the ticket to In-Progress, and assigns it to you."
internal const val START_WORKING_NO_GIT_TOOLTIP = "Git integration is not available in this IDE"
internal const val START_WORKING_TEST_CASE_TOOLTIP =
    "Start Working doesn't apply to test cases — select a User Story or Defect"
internal const val START_WORKING_MULTI_TOOLTIP =
    "Start Working applies to one ticket at a time — select a single User Story or Defect"
internal const val START_WORKING_BUSY_TOOLTIP = "Start Working is already running"

internal fun pluralize(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"

/** Enabled state, tooltip, and plain-text accessible description of the Start Working button. */
internal data class StartWorkingButtonState(
    val enabled: Boolean,
    val tooltip: String,
    val accessibleDescription: String,
)

/**
 * A disabled button explains why it's disabled instead of describing what it would do.
 * An empty selection keeps the button enabled: the click explains what to select.
 */
internal fun startWorkingButtonState(
    gitAvailable: Boolean,
    inFlight: Boolean,
    selection: List<RallyArtifact>,
): StartWorkingButtonState {
    val disabledReason = when {
        // Start Working creates a git branch; without Git4Idea there's nothing it can do —
        // keep it visible but disabled so the affordance is obvious.
        !gitAvailable -> START_WORKING_NO_GIT_TOOLTIP
        inFlight -> START_WORKING_BUSY_TOOLTIP
        selection.size > 1 -> START_WORKING_MULTI_TOOLTIP
        selection.singleOrNull() is RallyTestCase -> START_WORKING_TEST_CASE_TOOLTIP
        else -> null
    }
    return if (disabledReason == null) {
        StartWorkingButtonState(true, START_WORKING_TOOLTIP, START_WORKING_DESCRIPTION)
    } else {
        StartWorkingButtonState(false, disabledReason, disabledReason)
    }
}

/**
 * The confirmation question for a state change, or null when none is needed. Most single-item
 * changes are a one-click reversible action, so they aren't confirmed. It asks when bulk-changing
 * more than one ticket (so a stray multi-select doesn't move 50 stories at once), when test cases
 * in the selection would be dropped, and always for Completed: it sits next to In-Progress, and
 * the prior state isn't recorded, so a misclick isn't easy to undo.
 *
 * [ids] are the FormattedIDs of the tickets that will change (null when a row has none).
 * The list never mixes test cases with other types today (only the Test Cases scope loads
 * them), so [skippedTestCases] is a defensive path.
 */
internal fun stateChangeConfirmation(ids: List<String?>, skippedTestCases: Int, newState: String): String? {
    if (ids.size <= 1 && skippedTestCases == 0 && newState != "Completed") return null
    val known = ids.filterNotNull()
    val what = if (ids.size == 1) known.singleOrNull() ?: "1 ticket" else pluralize(ids.size, "ticket")
    val details = if (ids.size > 1 && known.isNotEmpty()) "\n${known.joinToString(", ")}" else ""
    val skippedNote = if (skippedTestCases > 0) {
        "\n\n${pluralize(skippedTestCases, "selected test case")} will be skipped — " +
            "state changes don't apply to test cases."
    } else ""
    return "Move $what to '$newState'?$details$skippedNote"
}

/** Status-bar text after a state change. */
internal fun stateChangeStatus(updated: Int, skippedTestCases: Int): String =
    "Updated $updated" + if (skippedTestCases > 0) " (${pluralize(skippedTestCases, "test case")} skipped)" else ""

/**
 * The warning after a state change some tickets didn't make: which ones ([failedIds], their
 * FormattedIDs, the first ten), so the user knows exactly what to revisit (MED-9). Plain text.
 */
internal fun stateChangeFailureMessage(updated: Int, failedIds: List<String>): String {
    val shown = failedIds.take(10).joinToString(", ")
    val suffix = if (failedIds.size > 10) ", … and ${failedIds.size - 10} more" else ""
    return "Updated: $updated, Failed: ${failedIds.size}\nFailed to move: $shown$suffix"
}

/**
 * Outcome of Start Working's owner step. [error] is an unexpected skip worth a warning;
 * [note] is a skip the Start Working dialog already announced (blank Username), so it only
 * goes in the status bar.
 */
internal data class OwnerStepResult(val assigned: RallyUser?, val error: String?, val note: String?)

/**
 * Start Working's owner step. Runs only once the state change landed, so a failure can't leave
 * the ticket reassigned but not In-Progress. Every skip is reported: the tooltip and dialog
 * promise this step, so it must never be dropped silently. [lookup] resolves the Rally user and
 * [assign] writes the owner ref; both may throw.
 */
internal fun runOwnerStep(
    stateChangeSucceeded: Boolean,
    username: String,
    lookup: (String) -> RallyUser,
    assign: (String) -> Unit,
): OwnerStepResult {
    if (!stateChangeSucceeded) {
        return OwnerStepResult(null, "Owner not assigned because the state change failed", null)
    }
    if (username.isBlank()) {
        return OwnerStepResult(null, null, "owner unchanged — no Username in Settings")
    }
    return try {
        val user = lookup(username)
        val userRef = user.ref
            ?: return OwnerStepResult(null, "Owner not assigned: Rally user '$username' was not found", null)
        assign(userRef)
        OwnerStepResult(user, null, null)
    } catch (_: RallyUserNotFoundException) {
        OwnerStepResult(null, "Owner not assigned: Rally user '$username' was not found", null)
    } catch (e: Exception) {
        OwnerStepResult(null, "Owner assignment failed: ${e.message}", null)
    }
}

/** Warning dialog text (null when there's nothing to warn about) and status-bar text. */
internal data class StartWorkingOutcome(val warning: String?, val status: String)

/**
 * The result message of Start Working after its branch was checked out. When the state change
 * failed the ticket was never touched in Rally, so it must not read as "Started working".
 */
internal fun startWorkingOutcome(
    ticketId: String,
    branchName: String,
    stateChangeSucceeded: Boolean,
    errors: List<String>,
    note: String?,
): StartWorkingOutcome {
    if (!stateChangeSucceeded) {
        return StartWorkingOutcome(
            "Branch $branchName is checked out, but the Rally ticket $ticketId was not updated:\n\n" +
                errors.joinToString("\n"),
            "Branch $branchName checked out — Rally update failed"
        )
    }
    if (errors.isNotEmpty()) {
        return StartWorkingOutcome(
            "Started working on $ticketId with issues:\n\n${errors.joinToString("\n")}",
            "Working on $ticketId (with issues)"
        )
    }
    return StartWorkingOutcome(null, if (note != null) "Working on $ticketId ($note)" else "Working on $ticketId")
}

// The Create balloons are HTML (createHtmlTextBalloonBuilder renders its text as markup), so the
// Rally FormattedID and the server's error text are escaped: markup in them would otherwise render
// — and fetch any <img> it names — instead of reading as written.

/** The balloon announcing a create, with the assign-to-me [assignWarning] if there was one. */
internal fun createdBalloonHtml(createdId: String, assignWarning: String?): String =
    escapeHtml("Created $createdId" + (assignWarning?.let { " ($it)" } ?: ""))

/** The balloon after a create whose attachment upload then failed with [error]. */
internal fun uploadFailedBalloonHtml(createdId: String, error: String): String =
    "Created ${escapeHtml(createdId)}, but attachment upload failed: ${escapeHtml(error)}<br>" +
        "You can re-attach the file in the Rally web UI."

internal const val CREATE_CONNECTION_CHANGED_MESSAGE =
    "The Rally connection (server, API key or workspace) changed while the Create dialog was open, " +
        "so its project and sprint lists were out of date. Nothing was created — reopen Create and " +
        "pick the project again."

/**
 * Whether a Create must be abandoned because the Rally connection changed after its dialog
 * captured the project/sprint lists ([listsGenerationAtOpen] vs [listsGenerationNow], bumped
 * whenever the client is rebuilt for new settings). A ref picked from those lists belongs to
 * the previous connection, and the create POST carries no workspace of its own, so Rally would
 * file the artifact under the old project or reject it. A create naming neither a project nor
 * a sprint sends nothing stale.
 */
internal fun createBlockedByConnectionChange(
    listsGenerationAtOpen: Long,
    listsGenerationNow: Long,
    projectRef: String?,
    iterationRef: String?,
): Boolean = listsGenerationAtOpen != listsGenerationNow && (projectRef != null || iterationRef != null)

/**
 * Optimistic copy of an artifact with its state field updated — Stories and
 * Defects carry ScheduleState, Tasks carry State. Shared by changeState /
 * startWorking so the paths can't drift (the missing RallyTaskItem branch once
 * had to be fixed in each separately).
 */
internal fun withState(artifact: RallyArtifact, newState: String): RallyArtifact = when (artifact) {
    is RallyUserStory -> artifact.copy(scheduleState = newState)
    is RallyDefect -> artifact.copy(scheduleState = newState)
    is RallyTaskItem -> artifact.copy(state = newState)
    else -> artifact
}

/** Optimistic copy of an artifact with its Owner replaced (Start Working's assignment). */
internal fun withOwner(artifact: RallyArtifact, owner: RallyUser): RallyArtifact = when (artifact) {
    is RallyUserStory -> artifact.copy(owner = owner)
    is RallyDefect -> artifact.copy(owner = owner)
    is RallyTaskItem -> artifact.copy(owner = owner)
    else -> artifact
}
