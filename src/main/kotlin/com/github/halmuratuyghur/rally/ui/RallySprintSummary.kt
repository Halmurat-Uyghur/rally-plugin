package com.github.halmuratuyghur.rally.ui

import com.github.halmuratuyghur.rally.api.RallyArtifact
import com.github.halmuratuyghur.rally.api.RallyDefect
import com.github.halmuratuyghur.rally.api.RallyIteration
import com.github.halmuratuyghur.rally.api.RallyUserStory
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure helpers for the footer sprint summary, extracted from [RallyToolWindowPanel] so the
 * counting/formatting logic can be unit-tested without a Swing/Project fixture.
 *
 * The summary tracks the currently displayed (filtered) list: its state counts and points are
 * computed from whatever is in the ticket list, restricted to the stories/defects that belong
 * to the labeled sprint. That restriction is a no-op when a specific sprint is selected (the
 * query already scoped the list) and is what keeps the line honest under "All Sprints", where
 * the displayed list spans multiple iterations.
 */

/** Aggregated sprint numbers derived from the currently displayed list. */
internal data class SprintStats(
    val stateCounts: Map<String, Int>,
    val totalPoints: Double,
    val completedPoints: Double,
)

/** Order the standard states are displayed in; non-standard states are appended after. */
private val SPRINT_STATE_ORDER = listOf("Idea", "Defined", "In-Progress", "Completed", "Accepted")

/** Iteration name an artifact belongs to — only stories and defects carry one. */
internal fun artifactIterationName(artifact: RallyArtifact): String? = when (artifact) {
    is RallyUserStory -> artifact.iteration?.name ?: artifact.iteration?.refObjectName
    is RallyDefect -> artifact.iteration?.name ?: artifact.iteration?.refObjectName
    else -> null
}

private fun planEstimateOf(artifact: RallyArtifact): Double? = when (artifact) {
    is RallyUserStory -> artifact.planEstimate
    is RallyDefect -> artifact.planEstimate
    else -> null
}

/**
 * Count states and sum points over the [displayed] list, keeping only stories/defects that
 * belong to [iterationName]. Completed points cover the Completed and Accepted states.
 */
internal fun computeSprintStats(iterationName: String?, displayed: List<RallyArtifact>): SprintStats {
    val counted = displayed.filter { artifact ->
        (artifact is RallyUserStory || artifact is RallyDefect) &&
            artifactIterationName(artifact) == iterationName
    }
    // LinkedHashMap so non-standard states render in a stable (encounter) order.
    val stateCounts = LinkedHashMap<String, Int>()
    var totalPoints = 0.0
    var completedPoints = 0.0
    for (artifact in counted) {
        val state = artifact.scheduleState ?: artifact.state ?: "Unknown"
        stateCounts[state] = (stateCounts[state] ?: 0) + 1
        val points = planEstimateOf(artifact)
        if (points != null) {
            totalPoints += points
            if (state == "Completed" || state == "Accepted") completedPoints += points
        }
    }
    return SprintStats(stateCounts, totalPoints, completedPoints)
}

/** Render the "Defined: 3 | In-Progress: 5 | …" segment, standard states first. */
internal fun formatStateCounts(stateCounts: Map<String, Int>): String {
    val ordered = SPRINT_STATE_ORDER
        .filter { stateCounts.containsKey(it) }
        .joinToString(" | ") { "$it: ${stateCounts[it]}" }
    val extra = stateCounts.filter { it.key !in SPRINT_STATE_ORDER }
    if (extra.isEmpty()) return ordered
    val extraText = extra.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
    return if (ordered.isNotEmpty()) "$ordered | $extraText" else extraText
}

/** "11d left" / "3d ago", or null when the end date is missing/unparseable. */
internal fun daysRemainingText(endDate: String, today: LocalDate): String? = try {
    if (endDate.length >= 10) {
        val end = LocalDate.parse(endDate.take(10))
        val days = ChronoUnit.DAYS.between(today, end)
        if (days >= 0) "${days}d left" else "${-days}d ago"
    } else null
} catch (_: Exception) {
    null
}

/**
 * Build the full sprint-summary label from the displayed list. [today] is injected so the
 * days-remaining segment is deterministic in tests.
 */
internal fun buildSprintSummaryLabel(
    iteration: RallyIteration,
    displayed: List<RallyArtifact>,
    today: LocalDate,
): String {
    val stats = computeSprintStats(iteration.name, displayed)
    val countsText = formatStateCounts(stats.stateCounts)
    val velocityText = iteration.plannedVelocity?.let { " / ${Math.round(it)} planned" } ?: ""
    val startDate = iteration.startDate?.take(10) ?: ""
    val endDate = iteration.endDate?.take(10) ?: ""
    val daysText = daysRemainingText(endDate, today)?.let { " | $it" } ?: ""
    return "Sprint: ${iteration.name} ($startDate to $endDate)$daysText | $countsText | " +
        "${Math.round(stats.completedPoints)}/${Math.round(stats.totalPoints)} pts$velocityText"
}
