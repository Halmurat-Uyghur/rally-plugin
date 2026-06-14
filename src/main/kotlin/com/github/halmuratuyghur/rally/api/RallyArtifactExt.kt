package com.github.halmuratuyghur.rally.api

/**
 * Centralized helpers over [RallyArtifact] that previously lived as duplicated, drifting logic
 * across the UI and export layers:
 *
 * - The "effective state" rule (MED-12) was reimplemented ~7 times with inconsistent `?: ""` vs
 *   `?: "Unknown"` fallbacks and differing TestCase handling. [effectiveState] / [effectiveStateOrEmpty]
 *   give a single authoritative definition.
 * - The PlanEstimate type-switch (MED-12) — only User Stories and Defects carry story points —
 *   is consolidated into [storyPoints].
 * - The Rally `_type` discriminator strings (MED-13) are pinned in [RallyType] so they are no longer
 *   spelled out as bare literals at each comparison site.
 */

/** Rally WSAPI `_type` discriminator values used across the plugin (MED-13). */
object RallyType {
    const val USER_STORY = "HierarchicalRequirement"
    const val DEFECT = "Defect"
    const val TASK = "Task"
    const val TEST_CASE = "TestCase"
}

/**
 * The state to display for an artifact, with a non-empty fallback.
 *
 * Test Cases have no ScheduleState/State worth showing; their meaningful status is the last run
 * verdict, falling back to "No Verdict". Everything else prefers ScheduleState, then State, then
 * "Unknown".
 */
val RallyArtifact.effectiveState: String
    get() = if (type == RallyType.TEST_CASE) {
        (this as? RallyTestCase)?.lastVerdict ?: "No Verdict"
    } else {
        scheduleState ?: state ?: "Unknown"
    }

/**
 * The state used for client-side state filtering: ScheduleState, then State, then empty string.
 *
 * No TestCase special-case here — this matches the existing client-filter behavior, which compares
 * against ScheduleState/State directly and treats a missing value as the empty string.
 */
val RallyArtifact.effectiveStateOrEmpty: String
    get() = scheduleState ?: state ?: ""

/**
 * Story points (PlanEstimate) for artifacts that carry them — User Stories and Defects — or `null`
 * for Tasks, Test Cases, and any other type.
 */
val RallyArtifact.storyPoints: Double?
    get() = when (this) {
        is RallyUserStory -> planEstimate
        is RallyDefect -> planEstimate
        else -> null
    }
