package com.github.halmurat.rally.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class RallyColorsTest {

    @Test
    fun `schedule states map to their existing foreground constants`() {
        assertSame(RallyColors.IN_PROGRESS, RallyColors.forState("In-Progress").foreground)
        assertSame(RallyColors.COMPLETED, RallyColors.forState("Completed").foreground)
        assertSame(RallyColors.DEFINED, RallyColors.forState("Defined").foreground)
        assertSame(RallyColors.ACCEPTED, RallyColors.forState("Accepted").foreground)
        assertSame(RallyColors.IDEA, RallyColors.forState("Idea").foreground)
    }

    @Test
    fun `verdicts map to pass and fail`() {
        assertSame(RallyColors.PASS, RallyColors.forState("Pass").foreground)
        assertSame(RallyColors.FAIL, RallyColors.forState("Fail").foreground)
    }

    @Test
    fun `defect lifecycle states share the matching schedule-stage colors`() {
        assertSame(RallyColors.forState("Defined"), RallyColors.forState("Submitted"))
        assertSame(RallyColors.forState("Fail"), RallyColors.forState("Open"))
        assertSame(RallyColors.forState("Completed"), RallyColors.forState("Fixed"))
        assertSame(RallyColors.forState("Accepted"), RallyColors.forState("Closed"))
    }

    @Test
    fun `null and unknown states fall back to neutral`() {
        assertSame(RallyColors.NEUTRAL, RallyColors.forState(null))
        assertSame(RallyColors.NEUTRAL, RallyColors.forState("Unknown"))
        assertSame(RallyColors.NEUTRAL, RallyColors.forState("No Verdict"))
        assertSame(RallyColors.NEUTRAL, RallyColors.forState("SomethingElse"))
    }

    @Test
    fun `method colors keep their existing semantics`() {
        assertSame(RallyColors.IN_PROGRESS, RallyColors.forMethod("Automated").foreground)
        assertSame(RallyColors.DEFINED, RallyColors.forMethod("Manual").foreground)
        assertSame(RallyColors.DEFINED, RallyColors.forMethod(null).foreground)
    }

    @Test
    fun `chip backgrounds are opaque pastels distinct from the foreground`() {
        val chip = RallyColors.forState("In-Progress")
        assertFalse(chip.foreground === chip.background)
        // Opaque: contrast must not depend on what's behind the chip (selection blue).
        assertEquals(255, chip.background.alpha)
        // Pastel, not the full-strength hue.
        assertFalse(chip.foreground.rgb == chip.background.rgb)
    }
}
