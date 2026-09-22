package com.github.halmurat.rally.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel

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
    fun `update re-lays out the badge when its row container is validated`() {
        // Shared cell renderers are stamped via CellRendererPane, whose validate() only
        // re-lays out containers already marked invalid. update() must therefore
        // invalidate, or a row inherits the previous row's chip width (clipped text).
        val badge = StatusBadge()
        // Fixed row size, as in a list whose cells all share one width: a size change would
        // invalidate the row on its own and hide the bug.
        val row = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(badge)
            size = Dimension(500, 50)
            // Container.validate() is a no-op without a peer; a real renderer panel gets
            // one from the displayable CellRendererPane it is added to.
            addNotify()
        }
        badge.update("Idea", RallyColors.forState("Idea"))
        row.validate()

        badge.update("In-Progress", RallyColors.forState("In-Progress"))
        row.validate()

        assertEquals(badge.preferredSize.width, badge.width)
    }

    @Test
    fun `state text is exposed to assistive technology`() {
        val badge = StatusBadge()
        badge.update("Completed", RallyColors.forState("Completed"))
        assertEquals("Completed", badge.accessibleContext.accessibleName)
    }
}
