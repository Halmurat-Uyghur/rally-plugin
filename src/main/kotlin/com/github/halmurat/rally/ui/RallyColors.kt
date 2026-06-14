package com.github.halmurat.rally.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Foreground + chip-fill pair for one state value. The background is an opaque pastel
 * of the same hue so the chip reads in both light and dark themes while the
 * full-strength foreground keeps text contrast.
 */
class StateColors(val foreground: JBColor, val background: JBColor)

/**
 * Shared color constants for Rally UI components.
 *
 * Hues are picked so each state is distinguishable on grayscale and for the most
 * common color-blindness types (red-green deuteranopia, protanopia). Color is
 * still supplementary — every renderer draws the state name as text — but the
 * earlier palette had IN_PROGRESS and PASS both at pure green, and PASS and
 * COMPLETED both at pure blue, which made the column unreadable at a glance.
 *
 * [forState] is the single state→color mapping; the per-renderer `when` switches
 * it replaced had already drifted apart (the detail header lacked Pass/Fail).
 */
object RallyColors {
    private val IN_PROGRESS_L = Color(0, 128, 0)
    private val IN_PROGRESS_D = Color(100, 200, 100)
    private val COMPLETED_L = Color(0, 0, 180)
    private val COMPLETED_D = Color(100, 150, 255)
    private val DEFINED_L = Color(200, 120, 0)
    private val DEFINED_D = Color(255, 180, 80)
    private val PASS_L = Color(0, 160, 200)
    private val PASS_D = Color(80, 200, 230)
    private val FAIL_L = Color(180, 0, 0)
    private val FAIL_D = Color(255, 100, 100)
    private val ACCEPTED_L = Color(110, 110, 110)
    private val ACCEPTED_D = Color(160, 160, 160)
    private val IDEA_L = Color(128, 60, 170)
    private val IDEA_D = Color(190, 140, 225)
    private val NEUTRAL_L = Color(90, 90, 90)
    private val NEUTRAL_D = Color(150, 150, 150)

    val IN_PROGRESS = JBColor(IN_PROGRESS_L, IN_PROGRESS_D)  // pure green
    val COMPLETED = JBColor(COMPLETED_L, COMPLETED_D)        // pure blue
    val DEFINED = JBColor(DEFINED_L, DEFINED_D)              // orange
    val PASS = JBColor(PASS_L, PASS_D)                       // cyan — distinct from both green and blue
    val FAIL = JBColor(FAIL_L, FAIL_D)                       // pure red
    val ACCEPTED = JBColor(ACCEPTED_L, ACCEPTED_D)           // gray (was inline JBColor.GRAY)
    val IDEA = JBColor(IDEA_L, IDEA_D)                       // purple (had no color before)
    val DIVIDER = JBColor(Color(80, 80, 80), Color(70, 70, 70))

    /**
     * Opaque pastel fill: the hue pre-composited over the theme's default panel
     * background (white / #3C3F41) at construction. Opaque — not a translucent
     * tint — so the chip's text contrast is independent of what's behind it,
     * including the selection highlight.
     */
    private fun chipFill(light: Color, dark: Color): JBColor {
        fun blend(base: Color, hue: Color, alpha: Int): Color {
            val a = alpha / 255f
            return Color(
                (base.red * (1 - a) + hue.red * a).toInt(),
                (base.green * (1 - a) + hue.green * a).toInt(),
                (base.blue * (1 - a) + hue.blue * a).toInt()
            )
        }
        return JBColor(
            blend(Color(255, 255, 255), light, 34),
            blend(Color(60, 63, 65), dark, 46)
        )
    }

    private val IN_PROGRESS_CHIP = StateColors(IN_PROGRESS, chipFill(IN_PROGRESS_L, IN_PROGRESS_D))
    private val COMPLETED_CHIP = StateColors(COMPLETED, chipFill(COMPLETED_L, COMPLETED_D))
    private val DEFINED_CHIP = StateColors(DEFINED, chipFill(DEFINED_L, DEFINED_D))
    private val PASS_CHIP = StateColors(PASS, chipFill(PASS_L, PASS_D))
    private val FAIL_CHIP = StateColors(FAIL, chipFill(FAIL_L, FAIL_D))
    private val ACCEPTED_CHIP = StateColors(ACCEPTED, chipFill(ACCEPTED_L, ACCEPTED_D))
    private val IDEA_CHIP = StateColors(IDEA, chipFill(IDEA_L, IDEA_D))

    /** Fallback chip for null/unknown states ("Unknown", "No Verdict", blank). */
    val NEUTRAL = StateColors(JBColor(NEUTRAL_L, NEUTRAL_D), chipFill(NEUTRAL_L, NEUTRAL_D))

    private val STATE_COLORS: Map<String, StateColors> = mapOf(
        // ScheduleState (user stories + defects)
        "Idea" to IDEA_CHIP,
        "Defined" to DEFINED_CHIP,
        "In-Progress" to IN_PROGRESS_CHIP,
        "Completed" to COMPLETED_CHIP,
        "Accepted" to ACCEPTED_CHIP,
        // Defect State — mapped to the matching lifecycle-stage hue
        "Submitted" to DEFINED_CHIP,
        "Open" to FAIL_CHIP,
        "Fixed" to COMPLETED_CHIP,
        "Closed" to ACCEPTED_CHIP,
        // Test case verdicts
        "Pass" to PASS_CHIP,
        "Fail" to FAIL_CHIP,
    )

    /** Badge colors for a state/verdict value; unknown or null falls back to [NEUTRAL]. */
    fun forState(state: String?): StateColors = state?.let { STATE_COLORS[it] } ?: NEUTRAL

    /** Test-case Method badge colors (existing semantics: Automated=green, Manual=orange). */
    fun forMethod(method: String?): StateColors =
        if (method == "Automated") IN_PROGRESS_CHIP else DEFINED_CHIP
}
