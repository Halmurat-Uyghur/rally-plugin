package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyAttachment
import com.github.halmurat.rally.api.RallyTaskItem
import com.github.halmurat.rally.api.RallyTestCase
import com.github.halmurat.rally.api.RallyTestCaseStep
import com.github.halmurat.rally.api.RallyUser
import com.github.halmurat.rally.api.RallyUserStory
import com.github.halmurat.rally.testutil.FetchRecorder
import com.github.halmurat.rally.testutil.SwingRender
import com.github.halmurat.rally.testutil.SwingRender.onEdt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.plaf.basic.BasicHTML

/**
 * A Rally string that is a label's whole text turns the label into live HTML when it starts
 * with `<html>` (BasicHTML), and Swing then loads its `<img>` URLs — synchronously, on the EDT.
 * The list renderers must show such strings as text, and build tooltips that do too.
 *
 * The HTML view is the only way a label fetches anything, and it fetches synchronously (BasicHTML
 * makes its image views load before layout returns — see the positive control), so a label with
 * no HTML view ([BasicHTML.propertyKey] unset) after rendering has fetched nothing, and nothing
 * can arrive later: no waiting for AWT's fetcher threads is needed here.
 */
class RendererHtmlSafetyTest {

    private val recorder = FetchRecorder()

    @After
    fun tearDown() = recorder.close()

    private fun attack(scope: String) = """<html><img src="${scope}label.png">"""

    @Test
    fun `a label without html disabled loads the image, synchronously (harness positive control)`() {
        val scope = recorder.newScope()
        val label = onEdt { JLabel().apply { text = attack(scope) } }
        SwingRender.paint(label, 200, 40)
        // Checked at once, no waiting: the request has been made by the time painting returns.
        assertTrue("an HTML label should have fetched its image while rendering", recorder.requestsUnder(scope).isNotEmpty())
        assertNotNull("the HTML view that fetched it", onEdt { label.getClientProperty(BasicHTML.propertyKey) })
    }

    @Test
    fun `ticket rows show Rally strings as text`() {
        val scope = recorder.newScope()
        val html = attack(scope)
        val story = RallyUserStory(formattedID = html, name = html, owner = RallyUser(displayName = html))
        val row = renderRow(ArtifactCellRenderer(), story)
        assertNoFetch(scope)
        assertShownLiterally(row, html, expectedLabels = 2) // "ID: name", owner
    }

    @Test
    fun `ticket row tooltips show Rally strings as text`() {
        val story = RallyUserStory(formattedID = "US1", name = """<img src="x.png"> & "q"""")
        val row = renderRow(ArtifactCellRenderer(), story)
        assertEquals("<html>US1: &lt;img src=&quot;x.png&quot;&gt; &amp; &quot;q&quot;</html>", row.toolTipText)
    }

    @Test
    fun `ticket rows give screen readers the plain text, not the escaped tooltip`() {
        // A component's accessible description falls back to its tooltip, and macOS reads the
        // description as the name when there is none: the escaped-HTML tooltip would be read out
        // as markup and entities.
        val renderer = ArtifactCellRenderer()
        val first = renderRow(renderer, RallyUserStory(formattedID = "US1", name = """<b>x</b> & "q""""))
        assertEquals("""US1: <b>x</b> & "q"""", first.accessibleContext.accessibleName)
        assertEquals("""US1: <b>x</b> & "q"""", first.accessibleContext.accessibleDescription)
        // The renderer is a rubber stamp: every row gets its own name.
        val second = renderRow(renderer, RallyUserStory(formattedID = "US2", name = "Other"))
        assertEquals("US2: Other", second.accessibleContext.accessibleName)
        assertEquals("US2: Other", second.accessibleContext.accessibleDescription)
    }

    @Test
    fun `test case rows show Rally strings as text`() {
        val scope = recorder.newScope()
        val html = attack(scope)
        val row = renderRow(TestCaseCellRenderer(), RallyTestCase(formattedID = html, name = html, method = "Manual"))
        assertNoFetch(scope)
        assertShownLiterally(row, html, expectedLabels = 1)
    }

    @Test
    fun `task rows show Rally strings as text`() {
        val scope = recorder.newScope()
        val html = attack(scope)
        val task = RallyTaskItem(formattedID = html, name = html, state = "Defined", owner = RallyUser(refObjectName = html))
        val row = renderRow(TaskCellRenderer(), task)
        assertNoFetch(scope)
        assertShownLiterally(row, html, expectedLabels = 2) // "ID: name", owner
    }

    @Test
    fun `attachment rows show Rally strings as text`() {
        val scope = recorder.newScope()
        val html = attack(scope)
        val row = renderRow(AttachmentCellRenderer(), RallyAttachment(name = html, contentType = "image/png", size = 10))
        assertNoFetch(scope)
        assertShownLiterally(row, html, expectedLabels = 1)
    }

    @Test
    fun `test step labels never render as html`() {
        // The renderer strips tags, so today's step text can't begin with <html>; the labels are
        // pinned to text anyway so a change to that stripping can't make them live HTML.
        val scope = recorder.newScope()
        val html = attack(scope)
        val row = renderRow(StepCellRenderer(), RallyTestCaseStep(stepIndex = 1, input = html, expectedResult = "$html ok"))
        val textLabels = onEdt {
            SwingRender.descendants(row).filterIsInstance<JLabel>().filter { !it.text.isNullOrEmpty() && !it.text.startsWith("#") }
        }
        assertEquals(2, textLabels.size) // input, expected
        textLabels.forEach {
            assertEquals("html.disable on '${it.text}'", true, it.getClientProperty("html.disable"))
            assertNull("HTML view built for '${it.text}'", onEdt { it.getClientProperty(BasicHTML.propertyKey) })
        }
        assertNoFetch(scope)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private fun <T> renderRow(renderer: ListCellRenderer<T>, value: T): JComponent = onEdt {
        val row = renderer.getListCellRendererComponent(JList<T>(), value, 0, false, false) as JComponent
        SwingRender.paint(row, 500, row.preferredSize.height.coerceAtLeast(20))
        row
    }

    /** Every label showing [rallyText] has HTML rendering disabled, and none built an HTML view. */
    private fun assertShownLiterally(row: JComponent, rallyText: String, expectedLabels: Int) {
        val labels = onEdt { SwingRender.descendants(row).filterIsInstance<JLabel>().filter { it.text?.contains(rallyText) == true } }
        assertEquals("labels showing the Rally string", expectedLabels, labels.size)
        for (label in labels) {
            assertEquals("html.disable on '${label.text}'", true, label.getClientProperty("html.disable"))
            assertNull("HTML view built for '${label.text}'", label.getClientProperty(BasicHTML.propertyKey))
        }
    }

    /** No request so far — which, for a label, is final: its only fetch path is synchronous (see the class KDoc). */
    private fun assertNoFetch(scope: String) {
        assertEquals(emptyList<String>(), recorder.requestsUnder(scope))
    }
}
