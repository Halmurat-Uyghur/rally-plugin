package com.github.halmurat.rally.ui

import com.github.halmurat.rally.testutil.FetchRecorder
import com.github.halmurat.rally.testutil.SwingRender
import com.github.halmurat.rally.testutil.SwingRender.onEdt
import com.github.halmurat.rally.util.DescriptionHtml
import com.github.halmurat.rally.util.DescriptionHtmlTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.Color
import java.util.EmptyStackException
import javax.swing.JEditorPane
import javax.swing.JTextPane
import javax.swing.text.Element
import javax.swing.text.Position
import javax.swing.text.html.CSS
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument

/**
 * The detail panel's real description pipeline — [DescriptionHtml.wrap] (which runs the
 * current sanitizer), the pane [RallyDetailPanel.newDescriptionPane] builds, and the
 * [RallyDetailPanel.setDescriptionHtml] guard every description goes through.
 */
class DescriptionRenderingTest {

    private val recorder = FetchRecorder()
    private val theme = DescriptionHtml.Theme(fontSizePx = 13, foregroundHex = "bbbbbb", linkHex = "589df6", errorHex = "ff5261")

    @After
    fun tearDown() = recorder.close()

    private fun renderDescription(pane: JEditorPane, description: String) = onEdt {
        RallyDetailPanel.setDescriptionHtml(pane, DescriptionHtml.wrap(description, theme), theme)
        SwingRender.paint(pane)
    }

    private fun text(pane: JEditorPane): String = onEdt { pane.document.let { it.getText(0, it.length) } }

    /** The color the pane paints [needle] in: the style sheet's foreground for the leaf view holding it. */
    private fun foregroundOf(pane: JEditorPane, needle: String): Color = onEdt {
        val document = pane.document as HTMLDocument
        val offset = document.getText(0, document.length).indexOf(needle)
        assertTrue("'$needle' is not shown", offset >= 0)
        var view = pane.ui.getRootView(pane)
        while (view.viewCount > 0) {
            val index = view.getViewIndex(offset, Position.Bias.Forward)
            if (index < 0) break
            view = view.getView(index)
        }
        document.styleSheet.getForeground(view.attributes)
    }

    /** The names of every element in the pane's document. */
    private fun elementNames(pane: JEditorPane): Set<String> = onEdt {
        val names = mutableSetOf<String>()
        fun walk(element: Element) {
            names.add(element.name)
            for (i in 0 until element.elementCount) walk(element.getElement(i))
        }
        walk(pane.document.defaultRootElement)
        names
    }

    @Test
    fun `the detail pane renders through the safe kit`() {
        val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
        assertTrue(pane.editorKit is SafeHtmlEditorKit)
        assertFalse(pane.isEditable)
    }

    @Test
    fun `no request is made for any resource-loading markup`() {
        val scoped = SafeHtmlEditorKitTest.ATTACKS.map { Triple(it.name, recorder.newScope(), it.html) }
        val errors = mutableListOf<String>()
        for ((name, scope, html) in scoped) {
            try {
                renderDescription(onEdt { RallyDetailPanel.newDescriptionPane() }, html(scope))
            } catch (e: Throwable) {
                errors.add("$name threw $e")
            }
        }
        Thread.sleep(FetchRecorder.QUIET_PERIOD_MS)
        onEdt { }
        val fetched = scoped.mapNotNull { (name, scope, _) ->
            recorder.requestsUnder(scope).takeIf { it.isNotEmpty() }?.let { "$name -> $it" }
        }
        if (errors.isNotEmpty() || fetched.isNotEmpty()) fail((errors + fetched).joinToString("\n", prefix = "Unsafe rendering:\n"))
    }

    @Test
    fun `resolved data images are shown`() {
        val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
        renderDescription(pane, """<p>Screenshot:</p><img src="${SafeHtmlEditorKitTest.DATA_PNG}">""")
        assertTrue("the data: image should decode", SwingRender.awaitLoadedImage(pane))
    }

    // ── document structure in the description ───────────────────────────────────────────

    @Test
    fun `a closing body or html tag in any spelling doesn't cut the description off`() {
        for (end in listOf("</body>", "</Body>", "</body >", "</BODY\n>", "</body x>", "</body\t>", "</html>", "</html >", "</body></html>")) {
            val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
            renderDescription(pane, "<p>first</p>$end<p>second</p>")
            val shown = text(pane)
            assertTrue("$end: $shown", shown.contains("first") && shown.contains("second"))
            assertFalse("$end: $shown", shown.contains("</"))
        }
    }

    @Test
    fun `a description pasted as a whole HTML document shows its body text only`() {
        val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
        renderDescription(pane, "<html><head><title>Doc</title></head><body><p>first</p><p>second</p></body></html>")
        assertEquals("first\nsecond", text(pane).trim())
    }

    @Test
    fun `a text color attribute doesn't override the theme foreground`() {
        // Swing maps the HTML 3.2 text= attribute to a CSS color on any element, so Word and
        // Outlook markup would paint black text on a dark theme.
        for (description in listOf(
            "<p text=#000000>Hello theme</p>",
            "<span text=#000000>Hello theme</span>",
            "<font text=#000000>Hello theme</font>",
            "<div text=\"#000000\"><span>Hello theme</span></div>",
            "<table text=#000000><tr><td>Hello theme</td></tr></table>",
            "<body text=\"#000000\" bgcolor=\"#FFFFFF\">\n<p>Hello theme</p>\n</body>",
        )) {
            val unsanitized = onEdt { RallyDetailPanel.newDescriptionPane() }
            onEdt {
                RallyDetailPanel.setDescriptionHtml(unsanitized, DescriptionHtml.shell(description, theme), theme)
                SwingRender.paint(unsanitized)
            }
            assertEquals("control: $description", Color.BLACK, foregroundOf(unsanitized, "Hello theme"))

            val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
            renderDescription(pane, description)
            assertEquals(description, THEME_FOREGROUND, foregroundOf(pane, "Hello theme"))
        }
    }

    // ── Swing's parser recursing without bound ──────────────────────────────────────────

    @Test
    fun `a table section tag outside a table renders as HTML instead of overflowing the parser`() {
        for (description in DescriptionHtmlTest.PARSER_OVERFLOWS) {
            val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
            renderDescription(pane, description)
            val shown = text(pane)
            for (word in listOf("Steps", "Expected", "More")) assertTrue("$description: $shown", shown.contains(word))
            // Rendered as HTML, not by the plain-text fallback (which keeps no paragraphs).
            assertTrue("$description: fell back to plain text", HTML.Tag.P.toString() in elementNames(pane))
        }
    }

    @Test
    fun `markup that overflows Swing's parser falls back to its text`() {
        for (description in DescriptionHtmlTest.PARSER_OVERFLOWS) {
            val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
            // Straight to the guard: the overflowing markup must reach Swing, whatever the sanitizer does.
            onEdt { RallyDetailPanel.setDescriptionHtml(pane, DescriptionHtml.shell(description, theme), theme) }
            val shown = text(pane)
            for (word in listOf("Steps", "Expected", "More")) assertTrue("$description: $shown", shown.contains(word))
        }
    }

    @Test
    fun `dropping table section tags doesn't change how a table looks`() {
        for (table in listOf(
            "<table border=1><thead><tr><th>Step</th><th>Expected</th></tr></thead>" +
                "<tbody><tr><td>1</td><td>Saved</td></tr><tr><td>2</td><td>Shown</td></tr></tbody>" +
                "<tfoot><tr><td colspan=2>end</td></tr></tfoot></table>",
            "<div class=\"table-wrap\"><table class=\"confluenceTable\"><colgroup><col><col></colgroup><tbody>" +
                "<tr><th class=\"confluenceTh\">Step</th><th class=\"confluenceTh\">Result</th></tr>" +
                "<tr><td class=\"confluenceTd\">Log in</td><td class=\"confluenceTd\"><ul><li>Dashboard shown</li></ul></td></tr>" +
                "</tbody></table></div><p>after</p>",
            "<table border=\"1\" cellpadding=\"4\"><caption>Test matrix</caption>" +
                "<thead><tr><th>Browser</th><th>Result</th></tr></thead>" +
                "<tbody><tr><td>Chrome</td><td>Pass</td></tr></tbody><tbody><tr><td>Firefox</td><td>Fail</td></tr></tbody></table>",
            "<table class=MsoTableGrid border=1 cellspacing=0 cellpadding=0 style='border-collapse:collapse;border:none'>\n" +
                "<tbody><tr>\n<td width=312 valign=top style='width:233.75pt;padding:0in 5.4pt 0in 5.4pt'>\n" +
                "<p class=MsoNormal>Expected &amp; actual</p>\n</td>\n</tr>\n</tbody></table>",
        )) {
            val wrapped = DescriptionHtml.wrap(table, theme)
            assertFalse("the sanitizer drops the section tags: $wrapped", Regex("(?i)<(thead|tbody|tfoot)").containsMatchIn(wrapped))
            val withSections = onEdt { RallyDetailPanel.newDescriptionPane() }
            val sanitized = onEdt { RallyDetailPanel.newDescriptionPane() }
            val before = onEdt {
                RallyDetailPanel.setDescriptionHtml(withSections, DescriptionHtml.shell(table, theme), theme)
                SwingRender.paint(withSections)
            }
            val after = onEdt {
                RallyDetailPanel.setDescriptionHtml(sanitized, wrapped, theme)
                SwingRender.paint(sanitized)
            }
            assertTrue("nothing drawn: $table", SwingRender.inkPixels(after) > 0)
            assertEquals(table, 0, SwingRender.diffPixels(before, after))
        }
    }

    // ── the guard ───────────────────────────────────────────────────────────────────────

    @Test
    fun `unguarded rendering throws on malformed author CSS, with either kit (control)`() {
        // The safe kit keeps Swing's CSS parser, so the guard below is what catches this.
        for (pane in listOf(SwingRender.htmlPane(), SwingRender.htmlPane(SafeHtmlEditorKit()))) {
            try {
                onEdt { pane.text = MALFORMED_CSS }
                fail("expected Swing's CSS parser to throw (${pane.editorKit.javaClass.simpleName})")
            } catch (e: EmptyStackException) {
                // expected: this is what would surface as an IDE error report from the EDT
            }
        }
    }

    @Test
    fun `a description Swing can't render shows its text instead of throwing`() {
        val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
        // Straight to the guard: the malformed style must reach Swing, whatever the sanitizer does.
        onEdt { RallyDetailPanel.setDescriptionHtml(pane, DescriptionHtml.shell(MALFORMED_CSS, theme), theme) }
        val shown = text(pane)
        assertTrue(shown, shown.contains("Before"))
        assertTrue(shown, shown.contains("After"))
    }

    @Test
    fun `when even the text fallback fails, a short error is shown`() {
        // A pane on which only the error message renders.
        val pane = onEdt {
            object : JTextPane() {
                override fun setText(t: String?) {
                    if (t != null && t.isNotEmpty() && !t.contains("class='rally-error'")) throw EmptyStackException()
                    super.setText(t)
                }
            }.apply { editorKit = SafeHtmlEditorKit() }
        }
        onEdt { RallyDetailPanel.setDescriptionHtml(pane, DescriptionHtml.shell(MALFORMED_CSS, theme), theme) }
        val shown = text(pane)
        assertTrue(shown, shown.contains("Couldn't display the description"))
        assertFalse(shown, shown.contains("Before"))
    }

    @Test
    fun `unguarded rendering of deep nesting overflows the stack (control)`() {
        // ~900 nested tables (13 KB) overflow the EDT's stack while Swing builds the views; a
        // 256 KB thread overflows the same way, so the tests below can run it off the EDT.
        val pane = SwingRender.htmlPane(SafeHtmlEditorKit())
        val thrown = onSmallStack { pane.text = DescriptionHtml.shell(DEEP_NESTING, theme) }
        assertTrue("expected a StackOverflowError, got $thrown", thrown is StackOverflowError)
    }

    @Test
    fun `a description nested deep enough to overflow the stack shows its text instead of throwing`() {
        val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
        val thrown = onSmallStack { RallyDetailPanel.setDescriptionHtml(pane, DescriptionHtml.shell(DEEP_NESTING, theme), theme) }
        assertNull("escaped the guard: $thrown", thrown)
        val shown = text(pane)
        assertTrue(shown, shown.contains("Deep text") || shown.contains("Couldn't display the description"))
        // The pane is left on a fresh, consistent document: later layout and paint work.
        SwingRender.paint(pane)
        assertTrue(text(pane).isNotBlank())
    }

    @Test
    fun `each description starts from a fresh document`() {
        // A style rule one description sneaks in must not restyle the next ticket's.
        val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
        onEdt { RallyDetailPanel.setDescriptionHtml(pane, "<html><head><style>p{margin-left:77px}</style></head><body><p>a</p></body></html>", theme) }
        val first = onEdt { pane.document }
        assertEquals(
            "control: the rule is in the first document",
            "77px", onEdt { (first as HTMLDocument).styleSheet.getRule("p").getAttribute(CSS.Attribute.MARGIN_LEFT)?.toString() }
        )
        onEdt { RallyDetailPanel.setDescriptionHtml(pane, DescriptionHtml.shell("<p>b</p>", theme), theme) }
        val second = onEdt { pane.document }
        assertNotSame(first, second)
        assertNull(onEdt { (second as HTMLDocument).styleSheet.getRule("p").getAttribute(CSS.Attribute.MARGIN_LEFT) })
        assertTrue(text(pane).contains("b"))
    }

    @Test
    fun `clearing the pane empties it`() {
        val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
        onEdt { RallyDetailPanel.setDescriptionHtml(pane, DescriptionHtml.shell("<p>text</p>", theme), theme) }
        onEdt { RallyDetailPanel.setDescriptionHtml(pane, "", theme) }
        assertEquals("", text(pane).trim())
    }

    /** Run [block] on a thread with a 256 KB stack; returns what it threw, or null. */
    private fun onSmallStack(block: () -> Unit): Throwable? {
        var thrown: Throwable? = null
        val thread = Thread(null, {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            }
        }, "small-stack", 256L * 1024)
        thread.start()
        thread.join()
        return thrown
    }

    private companion object {
        /** Swing's CSS parser throws EmptyStackException on the unbalanced quote in this style. */
        const val MALFORMED_CSS = """<p style="font-family:'Arial">Before</p><p>After</p>"""

        val DEEP_NESTING = "<table><tr><td>".repeat(900) + "Deep text" + "</td></tr></table>".repeat(900)

        /** The [theme]'s foregroundHex. */
        val THEME_FOREGROUND = Color(0xbbbbbb)
    }
}
