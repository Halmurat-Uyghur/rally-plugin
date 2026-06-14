package com.github.halmuratuyghur.rally.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the [RallyArtifact] extension properties (MED-12): the single source of truth
 * for the "effective state" rule and the PlanEstimate type-switch that were previously
 * reimplemented ~7 times with inconsistent fallbacks.
 */
class RallyArtifactExtTest {

    // ── effectiveState (display, non-empty fallback) ─────────────

    @Test
    fun `effectiveState prefers ScheduleState`() {
        assertEquals("In-Progress", RallyUserStory(scheduleState = "In-Progress", state = "ignored").effectiveState)
    }

    @Test
    fun `effectiveState falls back to State then Unknown`() {
        assertEquals("Open", RallyDefect(scheduleState = null, state = "Open").effectiveState)
        assertEquals("Unknown", RallyUserStory(scheduleState = null, state = null).effectiveState)
    }

    @Test
    fun `effectiveState for a TestCase uses LastVerdict`() {
        assertEquals("Pass", RallyTestCase(lastVerdict = "Pass").effectiveState)
    }

    @Test
    fun `effectiveState for a TestCase with no verdict is No Verdict`() {
        // A TestCase carries no meaningful ScheduleState/State, so it must NOT fall through
        // to "Unknown" — it uses the verdict-specific "No Verdict" label.
        assertEquals("No Verdict", RallyTestCase(lastVerdict = null, scheduleState = "x").effectiveState)
    }

    // ── effectiveStateOrEmpty (filtering, empty fallback) ────────

    @Test
    fun `effectiveStateOrEmpty matches the legacy client-filter behavior`() {
        assertEquals("Defined", RallyUserStory(scheduleState = "Defined").effectiveStateOrEmpty)
        assertEquals("Open", RallyDefect(scheduleState = null, state = "Open").effectiveStateOrEmpty)
        // No TestCase special-case here: missing values collapse to "" so the filter never
        // accidentally matches a literal state name.
        assertEquals("", RallyUserStory(scheduleState = null, state = null).effectiveStateOrEmpty)
        assertEquals("", RallyTestCase(lastVerdict = "Pass", scheduleState = null, state = null).effectiveStateOrEmpty)
    }

    // ── storyPoints ──────────────────────────────────────────────

    @Test
    fun `storyPoints reads PlanEstimate for stories and defects`() {
        assertEquals(5.0, RallyUserStory(planEstimate = 5.0).storyPoints)
        assertEquals(3.0, RallyDefect(planEstimate = 3.0).storyPoints)
    }

    @Test
    fun `storyPoints is null for tasks and test cases`() {
        assertNull(RallyTaskItem(formattedID = "TA1").storyPoints)
        assertNull(RallyTestCase(formattedID = "TC1").storyPoints)
    }

    @Test
    fun `storyPoints is null when PlanEstimate is unset`() {
        assertNull(RallyUserStory(planEstimate = null).storyPoints)
    }
}
