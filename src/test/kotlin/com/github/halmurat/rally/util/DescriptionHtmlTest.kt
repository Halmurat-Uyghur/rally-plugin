package com.github.halmurat.rally.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.DTD
import javax.swing.text.html.parser.ParserDelegator

class DescriptionHtmlTest {

    private val theme = DescriptionHtml.Theme(fontSizePx = 13, foregroundHex = "bbbbbb", linkHex = "589df6", errorHex = "ff5261")

    // ── wrap: the pure part of the detail panel's wrapHtml ──────────────────────────────

    @Test
    fun `wrap puts the description in the themed document shell`() {
        assertEquals(
            "<html><head><style>a{color:#589df6;}.rally-error{color:#ff5261;}</style></head>" +
                "<body style='font-family:sans-serif;font-size:13px;margin:4px;color:#bbbbbb;'>" +
                "<p>Hello <b>world</b></p></body></html>",
            DescriptionHtml.wrap("<p>Hello <b>world</b></p>", theme)
        )
    }

    @Test
    fun `wrap drops the description's own html, head and body tags so the shell stays intact`() {
        // Any spelling Swing reads as an end tag, not just the exact "</body>": each of these
        // used to end the shell's body early and cut the rest of the description off.
        for (end in listOf("</BODY></html>", "</body >", "</BODY\n>", "</body x>", "</html >", "</body\t>")) {
            assertEquals(end, DescriptionHtml.shell("<p>a</p><p>b</p>", theme), DescriptionHtml.wrap("<p>a</p>$end<p>b</p>", theme))
        }
        assertEquals(
            DescriptionHtml.shell("<title>Doc</title><p>a</p>", theme),
            DescriptionHtml.wrap("<html><head><title>Doc</title></head><body text=#000000 bgcolor=#ffffff><p>a</p></body></html>", theme),
        )
    }

    @Test
    fun `a stray body tag's attributes don't land on the next element`() {
        // Swing ignores a second <body> but keeps its attributes for the next start tag, so this
        // <p> would be indented 120px and black on a dark theme.
        val tags = swingStartTags(DescriptionHtml.wrap("<p>first</p><body style=\"margin-left:120px\" text=#000000><p>second</p>", theme))
        assertEquals(listOf("p" to emptyMap<String, String>(), "p" to emptyMap()), tags.filter { it.first == "p" })
    }

    @Test
    fun `wrap strips author colors and blanks external image sources`() {
        val wrapped = DescriptionHtml.wrap(
            """<span style="color:#000000">text</span><img src="http://x.invalid/pixel.png">""", theme
        )
        assertFalse(wrapped.contains("#000000"))
        assertFalse(wrapped.contains("x.invalid"))
        assertTrue(wrapped.contains("text"))
    }

    // ── plainText: the guard's fallback body ────────────────────────────────────────────

    @Test
    fun `plainText keeps the visible text and its line breaks, escaped`() {
        assertEquals(
            "Title<br>Before<br>After &amp; &lt;b&gt; &quot;q&quot;<br>one<br>two",
            DescriptionHtml.plainText(
                "<h2>Title</h2><p style=\"font-family:'Arial\">Before</p><p>After &amp; &lt;b&gt; \"q\"</p>" +
                    "<ul><li>one</li><li>two</li></ul>"
            )
        )
    }

    @Test
    fun `plainText keeps br line breaks inside a paragraph`() {
        assertEquals("line 1<br>line 2", DescriptionHtml.plainText("<p>line 1<br>line 2</p>"))
    }

    @Test
    fun `plainText skips the head, style and script content`() {
        assertEquals(
            "shown",
            DescriptionHtml.plainText(
                "<html><head><title>t</title><style>p{color:red}</style></head>" +
                    "<body><script>var x = 1;</script><p>shown</p></body></html>"
            )
        )
    }

    @Test
    fun `plainText reads text and entity references as Swing's parser does`() {
        // The fallback no longer runs Swing's parser (it can overflow, see below), so it is
        // checked against it here, on markup the parser handles: same lines, same characters.
        for (html in listOf(
            "<p>caf&eacute; &Eacute;T&Eacute; &copy; 2026 &mdash; &rsquo;quoted&rsquo; &hellip; &euro;5</p>",
            "<p>&#233;&#xE9;&#Xe9; &#146;cp1252&#146; &#150; &#8212; &#128512; &#1114112;x &#65&#66</p>",
            "<p>&amp;&lt;&gt;&quot; &nbsp;x&nbsp; &zz; &zz &AMP; &Amp; & ; &# &#; &#name; &#SPACE;y &rsquo &amp\nnext</p>",
            "<p>" + (128..160).joinToString(" ") { "&#$it;" } + "</p>",
            "<div>Line one<br>Line two<br/>Line three</div><hr><p>After the rule</p>",
            "<p>Hello <b>bold</b>, <i>italic</i> and <a href=\"https://x.invalid/\">a link</a>.</p>\n<p>Second\nparagraph   with\tspaces</p>",
            "<h1>Title</h1><ol><li>one</li><li>two<ul><li>nested</li></ul></li></ol><blockquote>quote</blockquote>",
            "<table border=1><tr><th>Step</th><th>Expected</th></tr><tr><td>1</td><td>Saved &amp; shown</td></tr></table><p>after</p>",
            "<dl><dt>Term</dt><dd>Definition</dd></dl><center>centered</center><address>addr</address>",
            "<p class=MsoNormal><span style='font-size:11.0pt'>Repro\nsteps:<o:p></o:p></span></p><p class=MsoNormal>1.&nbsp;&nbsp;Open<o:p></o:p></p>",
            "<!-- a comment --><p>visible</p><!DOCTYPE html><p>1 < 2 and <b>x</b> <= y</p>",
            "<html><head><title>t</title><style>p{color:red}</style></head><body><p>shown</p></body></html>",
            DescriptionHtml.shell("<p>In the shell</p><span class='rally-error'>error</span>", theme),
        )) {
            assertEquals(html, swingPlainText(html), DescriptionHtml.plainText(html))
        }
    }

    @Test
    fun `plainText ends a title or a head where Swing's parser ends them`() {
        // A title holds only text and a head only head elements, so Swing's parser ends either at
        // the first start tag it can't hold, and shows the text after it.
        for (html in listOf(
            DescriptionHtml.shell("<title>a<b>b</b>c</title>d", theme),
            DescriptionHtml.shell("<title>a<p>b</p></title>c", theme),
            DescriptionHtml.shell("<title>a</p>b</title>c", theme),
            DescriptionHtml.shell("<title>unclosed<p>a</p>", theme),
            DescriptionHtml.shell("<title>Doc</title><p>a</p>", theme),
            "<html><head><title>t</title><p>a</p></head><body><p>b</p></body></html>",
            "<html><head>a<title>t</title></head><body><p>b</p></body></html>",
            "<head><title>t<b>x</b></title></head><p>b</p>",
            "<html><head>\n<title>t</title>\n<meta name=x content=y><style>p{}</style></head><body><p>b</p></body></html>",
        )) {
            assertEquals(html, swingPlainText(html), DescriptionHtml.plainText(html))
        }
    }

    // ── Swing's parser recursing without bound ──────────────────────────────────────────
    //
    // A thead, tbody or tfoot start tag outside a table, followed by li, caption, thead, tbody,
    // tfoot, title, html or nextid, sends javax.swing.text.html.parser.Parser.legalElementContext
    // into unbounded recursion: a StackOverflowError on any stack. So does a noscript inside a dir
    // or menu, in longer chains of elements on JDK 17 and in shorter ones since JDK 21. On a 256 KB
    // thread it comes quickly and deterministically.

    @Test
    fun `plainText never overflows on markup that sends Swing's parser into unbounded recursion`() {
        for (description in PARSER_OVERFLOWS) {
            assertTrue("control: Swing's parser overflows on $description", overflowsSwingParser(description))
            for (html in listOf(description, DescriptionHtml.shell(description, theme))) {
                assertEquals(html, "Steps<br>Expected<br>More", onSmallStack { DescriptionHtml.plainText(html) })
            }
        }
    }

    @Test
    fun `wrap never hands Swing's parser markup it overflows on, and keeps its text`() {
        for (description in PARSER_OVERFLOWS) {
            val wrapped = DescriptionHtml.wrap(description, theme)
            assertFalse("Swing's parser overflows on the wrapped $description", overflowsSwingParser(wrapped))
            val shown = onSmallStack { swingPlainText(wrapped) }
            for (word in listOf("Steps", "Expected", "More")) assertTrue("$description: $shown", shown.contains(word))
        }
    }

    @Test
    fun `wrap drops noscript tags and keeps their content`() {
        // Since JDK 21 (the JBR of IntelliJ 2024.2 and later) this short chain already overflows
        // Swing's parser; the test runtime's JDK 17 parser reads it fine, so it is pinned here.
        assertEquals(
            DescriptionHtml.shell("<p>Steps</p><menu><u><h2>Expected</h2></u></menu><p>More</p>", theme),
            DescriptionHtml.wrap("<p>Steps</p><menu><u><noscript><h2>Expected</h2></noscript></u></menu><p>More</p>", theme),
        )
    }

    @Test
    fun `wrap never hands Swing's parser a pair of elements it overflows on`() {
        // Every pair of elements Swing's HTML 3.2 DTD knows, directly and with text between them,
        // inside the contexts a description's markup commonly opens.
        val names = swingElementNames()
        val contexts = listOf("", "<p>", "<table>", "<table><tr><td>", "<ul><li>", "<dl>", "<b>")
        val documents = contexts.flatMap { context ->
            names.flatMap { parent -> names.flatMap { child -> listOf("$context<$parent><$child>", "$context<$parent>x<$child>") } }
        }
        val (rawOverflows, wrappedOverflows) = onSmallStack {
            documents.count { overflows(it) } to documents.filter { overflows(DescriptionHtml.wrap(it, theme)) }
        }
        assertTrue("control: some raw pairs overflow Swing's parser", rawOverflows > 0)
        assertEquals(emptyList<String>(), wrappedOverflows)
    }

    // ── escaping for labels and tooltips ────────────────────────────────────────────────

    @Test
    fun `escapeHtml escapes markup characters and leaves the rest`() {
        assertEquals("&lt;html&gt;&lt;img src=&quot;u&quot;&gt; &amp; it's", escapeHtml("<html><img src=\"u\"> & it's"))
    }

    @Test
    fun `literalHtmlTooltip wraps the escaped text in html`() {
        assertEquals("<html>US1: &lt;b&gt;x&lt;/b&gt;</html>", literalHtmlTooltip("US1: <b>x</b>"))
    }

    @Test
    fun `literalHtmlMessage escapes server text and keeps its line breaks`() {
        assertEquals(
            "<html>Failed to change state: &quot;Validation failed &lt;img src=x&gt;&quot;<br>Retry later &amp; check</html>",
            literalHtmlMessage("Failed to change state: \"Validation failed <img src=x>\"\nRetry later & check"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    /** Every start tag Swing's parser reports for [html], with its attributes. */
    private fun swingStartTags(html: String): List<Pair<String, Map<String, String>>> {
        val tags = mutableListOf<Pair<String, Map<String, String>>>()
        ParserDelegator().parse(StringReader(html), object : HTMLEditorKit.ParserCallback() {
            override fun handleStartTag(t: HTML.Tag, a: MutableAttributeSet, pos: Int) {
                val attributes = mutableMapOf<String, String>()
                for (name in a.attributeNames) attributes[name.toString()] = a.getAttribute(name).toString()
                tags.add(t.toString() to attributes)
            }
        }, true)
        return tags
    }

    /**
     * The oracle for [DescriptionHtml.plainText]: the same lines built from Swing's own parser
     * (what plainText did until the parser's unbounded recursion made it unsafe there). Runs of
     * whitespace come back as one space: Swing's parser keeps a space next to one an entity
     * produces (" &#SPACE;"), and the pane renders both as one.
     */
    private fun swingPlainText(html: String): String = swingParserLines(html).replace(Regex("[ \t\r\n]+"), " ")

    private fun swingParserLines(html: String): String {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        fun breakLine() {
            val text = line.toString().trim()
            if (text.isNotEmpty()) lines.add(text)
            line.setLength(0)
        }
        val hidden = setOf(HTML.Tag.HEAD, HTML.Tag.STYLE, HTML.Tag.SCRIPT, HTML.Tag.TITLE)
        fun HTML.Tag.endsLine() = isBlock || breaksFlow()
        ParserDelegator().parse(StringReader(html), object : HTMLEditorKit.ParserCallback() {
            private var hiddenDepth = 0

            override fun handleStartTag(t: HTML.Tag, a: MutableAttributeSet, pos: Int) {
                if (t in hidden) hiddenDepth++ else if (t.endsLine()) breakLine()
            }

            override fun handleEndTag(t: HTML.Tag, pos: Int) {
                if (t in hidden) hiddenDepth = maxOf(0, hiddenDepth - 1) else if (t.endsLine()) breakLine()
            }

            override fun handleSimpleTag(t: HTML.Tag, a: MutableAttributeSet, pos: Int) {
                if (hiddenDepth == 0 && t.endsLine()) breakLine()
            }

            override fun handleText(data: CharArray, pos: Int) {
                if (hiddenDepth == 0) line.append(data)
            }
        }, true)
        breakLine()
        return lines.joinToString("<br>") { escapeHtml(it) }
    }

    /** Whether Swing's parser overflows the stack on [html] (call on a small stack, see [onSmallStack]). */
    private fun overflows(html: String): Boolean = try {
        ParserDelegator().parse(StringReader(html), HTMLEditorKit.ParserCallback(), true)
        false
    } catch (e: StackOverflowError) {
        true
    }

    private fun overflowsSwingParser(html: String): Boolean = onSmallStack { overflows(html) }

    /** Run [block] on a thread with a 256 KB stack; returns its result or rethrows what it threw. */
    private fun <T> onSmallStack(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(block) }, "small-stack", 256L * 1024)
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    /** The element names of Swing's HTML 3.2 DTD, the one its parser reads descriptions with. */
    private fun swingElementNames(): List<String> {
        ParserDelegator() // loads the DTD
        return DTD.getDTD("html32").elements.map { it.name }.filter { !it.startsWith("#") && it != "unknown" }
            .also { assertTrue("DTD not loaded: $it", it.size > 60) }
    }

    companion object {
        /** Descriptions whose markup sends Swing's parser into unbounded recursion; each shows "Steps", "Expected", "More". */
        val PARSER_OVERFLOWS = listOf(
            "<p>Steps</p><tbody><li>Expected</li></tbody><p>More</p>",
            "<p>Steps</p><thead><li>Expected</li></thead><p>More</p>",
            "<p>Steps</p><tfoot>\n<li>Expected</li></tfoot><p>More</p>",
            "<p>Steps</p><tbody><caption>Expected</caption></tbody><p>More</p>",
            "<p>Steps</p><thead>Expected<tbody><p>More</p>",
            "<p>Steps</p><tfoot><title>t</title>Expected<p>More</p>",
            "<p>Steps</p><tbody><html>Expected<p>More</p>",
            "<p>Steps</p><thead><nextid n=z1>Expected<p>More</p>",
            "<p>Steps</p><table><tr><td><dir><blink><strike>Expected<noscript><blockquote>More</blockquote></noscript></strike></blink></dir></td></tr></table>",
        )
    }
}
