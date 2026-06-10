package com.github.halmuratuyghur.rally.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBadgeTest {

    @Test
    fun `blank text hides the badge and empty text zeroes its size`() {
        val badge = StatusBadge()
        badge.update(null, RallyColors.NEUTRAL)
        assertFalse(badge.isVisible)
        assertEquals(0, badge.preferredSize.width)
        assertEquals(0, badge.preferredSize.height)
    }

    @Test
    fun `non-blank text shows the badge and width grows with text`() {
        val badge = StatusBadge()
        badge.update("In-Progress", RallyColors.forState("In-Progress"))
        assertTrue(badge.isVisible)
        val wide = badge.preferredSize.width
        badge.update("Idea", RallyColors.forState("Idea"))
        assertTrue(badge.preferredSize.width < wide)
    }

    @Test
    fun `state text is exposed to assistive technology`() {
        val badge = StatusBadge()
        badge.update("Completed", RallyColors.forState("Completed"))
        assertEquals("Completed", badge.accessibleContext.accessibleName)
    }
}
