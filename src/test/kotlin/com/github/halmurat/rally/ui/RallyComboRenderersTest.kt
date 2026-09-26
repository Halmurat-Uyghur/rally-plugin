package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyIteration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.plaf.basic.BasicHTML

/**
 * The Project and Sprint dropdowns show Rally-controlled names. A name starting with "<html>"
 * must render as the literal text: as live HTML, Swing would lay out its markup and fetch any
 * <img> it names. Tooltips are HTML-rendered by the IDE, so they carry the name escaped.
 */
class RallyComboRenderersTest {

    private val list = JList<String>()
    private val htmlName = "<html><b>Team</b> & \"Co\""
    private val escapedName = "&lt;html&gt;&lt;b&gt;Team&lt;/b&gt; &amp; &quot;Co&quot;"

    private fun JLabel.htmlView(): Any? = getClientProperty(BasicHTML.propertyKey)

    @Test
    fun `an HTML-looking project name renders as plain text with an escaped tooltip`() {
        val label = RallyNameComboRenderer().getListCellRendererComponent(list, htmlName, 1, false, false) as JLabel

        assertEquals(htmlName, label.text)
        assertNull("the name must not be rendered as HTML", label.htmlView())
        assertEquals("<html>$escapedName</html>", label.toolTipText)
    }

    @Test
    fun `an ordinary project name keeps its text`() {
        val label = RallyNameComboRenderer().getListCellRendererComponent(list, "Team A", 1, false, false) as JLabel
        assertEquals("Team A", label.text)
        assertEquals("<html>Team A</html>", label.toolTipText)
    }

    @Test
    fun `an HTML-looking sprint name with dates renders as plain text with an escaped tooltip`() {
        val sprint = RallyIteration(name = htmlName, startDate = "2026-02-10T00:00:00.000Z", endDate = "2026-02-24T00:00:00.000Z")
        val renderer = SprintComboRenderer { listOf(sprint) }

        // Index 0 is "All Sprints", so the sprint's row is index 1.
        val label = renderer.getListCellRendererComponent(list, htmlName, 1, false, false) as JLabel

        assertEquals("$htmlName  (2026-02-10 → 2026-02-24)", label.text)
        assertNull("the name must not be rendered as HTML", label.htmlView())
        assertEquals("<html>$escapedName: 2026-02-10 to 2026-02-24</html>", label.toolTipText)
    }

    @Test
    fun `a sprint row without dates, and All Sprints, keep their text`() {
        val renderer = SprintComboRenderer { listOf(RallyIteration(name = htmlName)) }

        val undated = renderer.getListCellRendererComponent(list, htmlName, 1, false, false) as JLabel
        assertEquals(htmlName, undated.text)
        assertNull(undated.htmlView())
        assertEquals("<html>$escapedName</html>", undated.toolTipText)

        val all = renderer.getListCellRendererComponent(list, "All Sprints", 0, false, false) as JLabel
        assertEquals("All Sprints", all.text)
        assertEquals("<html>All Sprints</html>", all.toolTipText)
    }

    @Test
    fun `screen readers get the plain name, not the escaped tooltip`() {
        // A label's accessible description falls back to its tooltip — here escaped HTML, which
        // a screen reader would read out as markup and entities.
        val name = """<b>Team</b> & "Co""""
        val project = RallyNameComboRenderer().getListCellRendererComponent(list, name, 1, false, false) as JLabel
        assertEquals(name, project.accessibleContext.accessibleName)
        assertEquals(name, project.accessibleContext.accessibleDescription)

        val sprint = RallyIteration(name = name, startDate = "2026-02-10T00:00:00.000Z", endDate = "2026-02-24T00:00:00.000Z")
        val renderer = SprintComboRenderer { listOf(sprint) }
        val dated = renderer.getListCellRendererComponent(list, name, 1, false, false) as JLabel
        assertEquals("$name: 2026-02-10 to 2026-02-24", dated.accessibleContext.accessibleName)
        assertEquals("$name: 2026-02-10 to 2026-02-24", dated.accessibleContext.accessibleDescription)
        // Shared renderer: the next row reads its own text.
        val all = renderer.getListCellRendererComponent(list, "All Sprints", 0, false, false) as JLabel
        assertEquals("All Sprints", all.accessibleContext.accessibleName)
        assertEquals("All Sprints", all.accessibleContext.accessibleDescription)
    }

    @Test
    fun `the Create dialogs' dropdowns render HTML-looking names as plain text`() {
        val combo = rallyNameCombo()
        combo.addItem(htmlName)

        val label = combo.renderer.getListCellRendererComponent(list, htmlName, 1, false, false) as JLabel

        assertEquals(htmlName, label.text)
        assertNull("the name must not be rendered as HTML", label.htmlView())
        assertEquals("<html>$escapedName</html>", label.toolTipText)
    }
}
