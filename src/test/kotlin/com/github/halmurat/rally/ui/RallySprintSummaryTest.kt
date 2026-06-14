package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyDefect
import com.github.halmurat.rally.api.RallyIteration
import com.github.halmurat.rally.api.RallyRef
import com.github.halmurat.rally.api.RallyTestCase
import com.github.halmurat.rally.api.RallyUserStory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for the footer sprint-summary computation. The summary now tracks the displayed
 * (filtered) list rather than the whole sprint, so these verify that the counts/points
 * reflect exactly what is shown — restricted to stories/defects belonging to the labeled
 * sprint (the guard that keeps the "Sprint: X" line honest under "All Sprints").
 */
class RallySprintSummaryTest {

    private fun story(state: String?, points: Double?, sprint: String?) = RallyUserStory(
        formattedID = "US",
        scheduleState = state,
        planEstimate = points,
        iteration = sprint?.let { RallyRef(name = it) },
    )

    private fun defect(state: String?, points: Double?, sprint: String?) = RallyDefect(
        formattedID = "DE",
        scheduleState = state,
        planEstimate = points,
        iteration = sprint?.let { RallyRef(name = it) },
    )

    @Test
    fun `counts stories and defects in the labeled sprint`() {
        val displayed = listOf(
            story("In-Progress", 3.0, "Sprint 1"),
            defect("Completed", 2.0, "Sprint 1"),
        )
        val stats = computeSprintStats("Sprint 1", displayed)
        assertEquals(mapOf("In-Progress" to 1, "Completed" to 1), stats.stateCounts)
        assertEquals(5.0, stats.totalPoints, 0.0001)
        assertEquals(2.0, stats.completedPoints, 0.0001)
    }

    @Test
    fun `tracks a state-filtered displayed list`() {
        // Simulates State=In-Progress: the list contains only In-Progress items, so the
        // summary must reflect just those. This is the core behavior the change adds.
        val displayed = listOf(
            story("In-Progress", 3.0, "Sprint 1"),
            story("In-Progress", 1.0, "Sprint 1"),
        )
        val stats = computeSprintStats("Sprint 1", displayed)
        assertEquals(mapOf("In-Progress" to 2), stats.stateCounts)
        assertEquals(4.0, stats.totalPoints, 0.0001)
        assertEquals(0.0, stats.completedPoints, 0.0001)
    }

    @Test
    fun `excludes items belonging to other sprints`() {
        // Under "All Sprints" the displayed list spans iterations; only the labeled
        // sprint's items count so the "Sprint: X" line stays honest.
        val displayed = listOf(
            story("In-Progress", 3.0, "Sprint 1"),
            story("Defined", 8.0, "Sprint 2"),
            defect("Completed", 2.0, "Sprint 1"),
        )
        val stats = computeSprintStats("Sprint 1", displayed)
        assertEquals(mapOf("In-Progress" to 1, "Completed" to 1), stats.stateCounts)
        assertEquals(5.0, stats.totalPoints, 0.0001)
    }

    @Test
    fun `excludes test cases and items without an iteration`() {
        val displayed = listOf(
            story("In-Progress", 3.0, "Sprint 1"),
            story("Defined", 5.0, null),
            RallyTestCase(formattedID = "TC1", scheduleState = "Defined"),
        )
        val stats = computeSprintStats("Sprint 1", displayed)
        assertEquals(mapOf("In-Progress" to 1), stats.stateCounts)
        assertEquals(3.0, stats.totalPoints, 0.0001)
    }

    @Test
    fun `completed points include Completed and Accepted`() {
        val displayed = listOf(
            defect("Completed", 2.0, "Sprint 1"),
            story("Accepted", 5.0, "Sprint 1"),
            story("In-Progress", 4.0, "Sprint 1"),
        )
        val stats = computeSprintStats("Sprint 1", displayed)
        assertEquals(11.0, stats.totalPoints, 0.0001)
        assertEquals(7.0, stats.completedPoints, 0.0001)
    }

    @Test
    fun `empty displayed list yields empty stats`() {
        val stats = computeSprintStats("Sprint 1", emptyList())
        assertEquals(emptyMap<String, Int>(), stats.stateCounts)
        assertEquals(0.0, stats.totalPoints, 0.0001)
        assertEquals(0.0, stats.completedPoints, 0.0001)
    }

    @Test
    fun `artifactIterationName falls back to refObjectName`() {
        val s = RallyUserStory(formattedID = "US", iteration = RallyRef(refObjectName = "Sprint 9"))
        assertEquals("Sprint 9", artifactIterationName(s))
    }

    @Test
    fun `formatStateCounts orders standard states then appends others`() {
        val counts = linkedMapOf("Accepted" to 1, "Defined" to 2, "Blocked" to 1, "In-Progress" to 3)
        assertEquals(
            "Defined: 2 | In-Progress: 3 | Accepted: 1 | Blocked: 1",
            formatStateCounts(counts),
        )
    }

    @Test
    fun `buildSprintSummaryLabel reflects the displayed list with injected date`() {
        val iteration = RallyIteration(
            name = "Sprint 1",
            startDate = "2026-06-01T00:00:00.000Z",
            endDate = "2026-06-15T00:00:00.000Z",
            plannedVelocity = 30.0,
        )
        val displayed = listOf(
            story("In-Progress", 3.0, "Sprint 1"),
            defect("Completed", 2.0, "Sprint 1"),
            story("Accepted", 5.0, "Sprint 1"),
        )
        val label = buildSprintSummaryLabel(iteration, displayed, LocalDate.of(2026, 6, 4))
        assertEquals(
            "Sprint: Sprint 1 (2026-06-01 to 2026-06-15) | 11d left | " +
                "In-Progress: 1 | Completed: 1 | Accepted: 1 | 7/10 pts / 30 planned",
            label,
        )
    }
}
