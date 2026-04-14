package com.github.halmuratuyghur.rally.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Shared color constants for Rally UI components.
 *
 * Hues are picked so each state is distinguishable on grayscale and for the most
 * common color-blindness types (red-green deuteranopia, protanopia). Color is
 * still supplementary — every renderer draws the state name as text — but the
 * earlier palette had IN_PROGRESS and PASS both at pure green, and PASS and
 * COMPLETED both at pure blue, which made the column unreadable at a glance.
 */
object RallyColors {
    val IN_PROGRESS = JBColor(Color(0, 128, 0), Color(100, 200, 100))      // pure green
    val COMPLETED = JBColor(Color(0, 0, 180), Color(100, 150, 255))        // pure blue
    val DEFINED = JBColor(Color(200, 120, 0), Color(255, 180, 80))         // orange
    val PASS = JBColor(Color(0, 160, 200), Color(80, 200, 230))            // cyan — distinct from both green and blue
    val FAIL = JBColor(Color(180, 0, 0), Color(255, 100, 100))             // pure red
    val DIVIDER = JBColor(Color(80, 80, 80), Color(70, 70, 70))
}
