package com.github.halmurat.rally.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Random
import javax.swing.JTextPane
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet
import javax.swing.text.html.parser.ParserDelegator

/**
 * Oracle tests for the description sanitizer ([RallyHtmlUtils.stripInlineColors] +
 * [RallyHtmlUtils.neutralizeActiveContent]). Every assertion is made against what Swing itself
 * sees — the parser JTextPane uses ([swingView]) or the text a stock JTextPane renders
 * ([renderedText]) — never against sanitized-string fragments, so a divergence between the
 * sanitizer's tokenizer and Swing's parser shows up as a failure here.
 */
class RallyHtmlSanitizerTest {

    // ── oracles ──────────────────────────────────────────────────

    private data class SwingTag(val name: String, val attributes: Map<String, String>)

    /** The detail panel pipeline: colors stripped, then active content neutralized. */
    private fun sanitize(html: String): String =
        RallyHtmlUtils.neutralizeActiveContent(RallyHtmlUtils.stripInlineColors(html))

    /** Mirrors RallyDetailPanel.wrapHtml so the oracles parse exactly what the pane parses. */
    private fun wrap(fragment: String): String =
        "<html><head><style>a{color:#589df6;}</style></head>" +
            "<body style='font-family:sans-serif;font-size:13px;margin:4px;color:#bbbbbb;'>" +
            fragment + "</body></html>"

    /** Every tag (start, empty, end) Swing's HTML parser reports for [fragment], with its attributes. */
    private fun swingView(fragment: String): List<SwingTag> {
        val tags = mutableListOf<SwingTag>()
        fun record(tag: HTML.Tag, attributes: MutableAttributeSet) {
            val attrs = mutableMapOf<String, String>()
            val names = attributes.attributeNames
            while (names.hasMoreElements()) {
                val key = names.nextElement()
                attrs[key.toString().lowercase()] = attributes.getAttribute(key)?.toString() ?: ""
            }
            tags.add(SwingTag(tag.toString().lowercase(), attrs))
        }
        ParserDelegator().parse(StringReader(wrap(fragment)), object : HTMLEditorKit.ParserCallback() {
            override fun handleStartTag(t: HTML.Tag, a: MutableAttributeSet, pos: Int) = record(t, a)
            override fun handleSimpleTag(t: HTML.Tag, a: MutableAttributeSet, pos: Int) = record(t, a)
        }, true)
        return tags
    }

    /** The visible text of a stock JTextPane after setText of the wrapped [fragment]. */
    private fun renderedText(fragment: String): String {
        val pane = JTextPane()
        pane.contentType = "text/html"
        pane.text = wrap(fragment)
        return pane.document.getText(0, pane.document.length)
    }

    private val forbiddenTags = setOf(
        "base", "meta", "form", "input", "button", "select", "option", "textarea", "object", "param",
        "applet", "embed", "iframe", "frame", "frameset", "script", "isindex", "link",
    )

    /** Every way [sanitized] would still make Swing load or act on something; empty = safe. */
    private fun violations(sanitized: String): List<String> {
        val found = mutableListOf<String>()
        // The wrapper's own <style> element is not part of the description.
        for (tag in swingView(sanitized)) {
            if (tag.name in forbiddenTags) found += "active tag <${tag.name}>"
            tag.attributes["src"]?.let { src ->
                val s = src.trim()
                if (s.isNotEmpty() && !s.startsWith("data:", ignoreCase = true)) found += "<${tag.name}> src=$src"
            }
            if ("background" in tag.attributes) found += "<${tag.name}> background=${tag.attributes["background"]}"
            tag.attributes["style"]?.let { style ->
                val lower = style.lowercase()
                if ("url" in lower || "image" in lower || "://" in lower) found += "<${tag.name}> style=$style"
                // The CSS parser HTMLReader runs on every style attribute; a throw there makes
                // JTextPane.setText fail (EmptyStackException) and cuts the description off.
                try {
                    StyleSheet().getDeclaration(style)
                } catch (e: RuntimeException) {
                    found += "<${tag.name}> style=$style crashes the CSS parser: $e"
                }
            }
        }
        return found
    }

    private fun assertSafe(input: String) {
        val sanitized = sanitize(input)
        assertEquals("input: $input\nsanitized: $sanitized", emptyList<String>(), violations(sanitized))
        val neutralizedOnly = RallyHtmlUtils.neutralizeActiveContent(input)
        assertEquals("input: $input\nneutralized: $neutralizedOnly", emptyList<String>(), violations(neutralizedOnly))
    }

    private fun assertTextSurvives(input: String, vararg texts: String) {
        val rendered = renderedText(sanitize(input))
        for (text in texts) {
            assertTrue("'$text' missing from rendered text '$rendered' (sanitized: ${sanitize(input)})", rendered.contains(text))
        }
    }

    // ── F1: tags Swing accepts although their quotes don't balance ──

    @Test
    fun `unbalanced quotes cannot hide an external src`() {
        for (html in listOf(
            """<img src="http://evil.invalid/p.png" alt=Bob's>""",
            """<img src='http://evil.invalid/p.png' title="a>""",
            """<img src="http://evil.invalid/p.png" alt=a<b>""",
            """<img src="http://evil.invalid/p.png"""",
            """<img src="http://evil.invalid/p.png" <p>x</p>""",
            """<img src="http://evil.invalid/p.png" alt='it's'>""",
            """<img x'y src="http://evil.invalid/p.png">""",
            """<img x'y src=http://evil.invalid/p.png>""",
        )) assertSafe(html)
    }

    // ── F2: attribute contents are not separate attributes ──

    @Test
    fun `src text inside a quoted attribute value keeps both paragraphs visible`() {
        assertTextSurvives("<p title='the src=foo'>Important requirement</p><p>Acceptance criteria</p>",
            "Important requirement", "Acceptance criteria")
        assertTextSurvives("""<p title="the src=foo">Important requirement</p><p>Acceptance criteria</p>""",
            "Important requirement", "Acceptance criteria")
    }

    @Test
    fun `src text inside quoted values cannot inject a live src`() {
        for (html in listOf(
            """<img alt='x src=b'> ' src=http://evil.invalid/chain1.png >""",
            """<img src="data:image/png;base64,AAAA" alt="a src=b">src=http://evil.invalid/x1.png""",
            """<img alt="x src=b"> " src=http://evil.invalid/chain2.png >""",
        )) assertSafe(html)
    }

    @Test
    fun `sibling attribute passes keep quoted values intact`() {
        assertTextSurvives("""<p title='x background="y' class="z">Important</p><p>AC</p>""", "Important", "AC")
        assertTextSurvives("""<p title="x background='y" class='z'>Important</p><p>AC</p>""", "Important", "AC")
        assertTextSurvives("<p title='x color=red'>Important</p><p>AC</p>", "Important", "AC")
        assertTextSurvives("""<p title="x color=red">Important</p><p>AC</p>""", "Important", "AC")
        assertTextSurvives("<p title='see <meta'>Important</p><p>AC</p>", "Important", "AC")
        assertTextSurvives("""<p title="see <meta">Important</p><p>AC</p>""", "Important", "AC")
        assertTextSurvives("""<p title="Use <select> here">Important</p><p>AC</p><select><option>o</option></select><p>Tail</p>""",
            "Important", "AC", "Tail")
        assertTextSurvives("""<p title='Use <style> here'>Important</p><p>AC</p><p>Tail</p>""", "Important", "AC", "Tail")
    }

    @Test
    fun `quoted markup in titles and links keeps visible text`() {
        assertTextSurvives("""<p title="use &lt;img src=&quot;logo.png&quot;&gt;">Logo note</p><p>Next</p>""", "Logo note", "Next")
        assertTextSurvives("""<p><a href="https://x.invalid/q?a=1 src=b">link text</a></p><p>After</p>""", "link text", "After")
        val link = sanitize("""<a href="https://x.invalid/q?a=1 src=b">link text</a>""")
        assertEquals("""<a href="https://x.invalid/q?a=1 src=b">link text</a>""", link)
    }

    // ── attribute boundaries Swing recognizes without whitespace ──

    @Test
    fun `an attribute directly after a quote or a skipped character is still sanitized`() {
        for (html in listOf(
            """<img alt="x"src="http://evil.invalid/a.png">""",
            """<img alt='x'src='http://evil.invalid/b.png'>""",
            """<img,src=http://evil.invalid/c.png>""",
            """<img x;src=http://evil.invalid/d.png>""",
            """<img "src=http://evil.invalid/e.png">""",
            """<img "src"="http://evil.invalid/e2.png">""",
            """<img xml:src=http://evil.invalid/f.png>""",
            """<img _src=http://evil.invalid/g.png>""",
            """<table x="1"background="http://evil.invalid/h.png"><tr><td>t</td></tr></table>""",
            """<p a=1 =x src=http://evil.invalid/i.png>tail</p>""",
            """<img SRC=http://evil.invalid/j.png>""",
            """<image src=http://evil.invalid/k.png>""",
            """<img src = "http://evil.invalid/l.png">""",
            """<img src="data:image/png;base64,AAAA" src="http://evil.invalid/m.png">""",
        )) assertSafe(html)
    }

    @Test
    fun `quoted attribute names keep their values and the text after the tag`() {
        // Swing reads "title" (quotes around the name) as the attribute title; a tokenizer that
        // skipped the quote instead would end the attribute list early and show the rest of the
        // tag as text.
        for (html in listOf(
            """<p "title"="a" class=x>Important</p><p>AC</p>""",
            """<p " title"=a>T</p>""",
            """<img "alt"="a" src="data:image/png;base64,iVBORw0KGgo=">tail""",
        )) {
            val sanitized = sanitize(html)
            assertEquals("input: $html\nsanitized: $sanitized", renderedText(html), renderedText(sanitized))
            assertEquals("input: $html\nsanitized: $sanitized", swingView(html), swingView(sanitized))
        }
    }

    // ── entity references at the end of an unquoted value ──
    //
    // Swing's parseEntityReference consumes one line break after a reference as its terminator,
    // so inside an unquoted value the reference carries the value on to the next line: Swing
    // reads <img alt=x&amp⏎title=" src=…"> as alt="x&title=" followed by a live src.

    @Test
    fun `a named reference cannot run an unquoted value past a line feed`() {
        assertSafe("<img alt=x&amp\ntitle=\" src=http://evil.invalid/r1.png \">")
    }

    @Test
    fun `an unknown reference cannot run an unquoted value past a line feed`() {
        assertSafe("<img alt=x&zz\ntitle=\" src=http://evil.invalid/r2.png \">")
    }

    @Test
    fun `a decimal reference cannot run an unquoted value past a line feed`() {
        assertSafe("<img alt=x&#65\ntitle=\" src=http://evil.invalid/r3.png \">")
    }

    @Test
    fun `an empty hex reference cannot run an unquoted value past a CRLF`() {
        assertSafe("<img alt=x&#x\r\ntitle=\" src=http://evil.invalid/r4.png \">")
    }

    @Test
    fun `a reference-extended value cannot turn a quoted value into markup`() {
        // Swing's alt value swallows title=" and ends at the '>', so the rest of the quoted
        // title is parsed as a live <input type=image>.
        val html = "<p alt=x&amp\ntitle=\"><input type=image src=http://evil.invalid/r5.png>\">text</p>"
        assertSafe(html)
        assertTextSurvives(html, "text")
    }

    @Test
    fun `an identifier with an inner underscore is one attribute name`() {
        // x_src is a single (unknown) attribute to Swing, so it is kept as-is.
        val html = """<img x_src="http://evil.invalid/n.png" src="data:image/png;base64,AAAA">"""
        assertEquals(html, RallyHtmlUtils.neutralizeActiveContent(html))
        assertSafe(html)
    }

    // ── style values Swing's CSS parser chokes on ──

    @Test
    fun `entity-quoted CSS urls neither crash the pane nor survive`() {
        val quoted = """<p style="background-image: url(&quot;https://evil.invalid/bg.png&quot;);">Styled</p><p>After</p>"""
        assertSafe(quoted)
        assertTextSurvives(quoted, "Styled", "After")

        val base64 = """<div style="background-image:url('data:image/png;base64,iVBORw0KGgo=')">Boxed</div><p>After</p>"""
        assertSafe(base64)
        assertTextSurvives(base64, "Boxed", "After")
    }

    @Test
    fun `entity-quoted font families survive unchanged`() {
        val html = """<span style="font-family: &quot;Segoe UI&quot;, sans-serif">Pasted</span>"""
        assertEquals(html, sanitize(html))
        assertTextSurvives(html, "Pasted")
    }

    @Test
    fun `a semicolon inside a quoted font name does not split the declaration`() {
        for (html in listOf(
            """<span style="font-family:'Foo;Bar', serif">x</span>""",
            """<span style="font-family:&quot;A;B&quot;; font-weight:bold">x</span>""",
        )) {
            assertEquals(html, RallyHtmlUtils.stripInlineColors(html))
            assertEquals(html, RallyHtmlUtils.neutralizeActiveContent(html))
        }
    }

    @Test
    fun `an unbalanced quote in a style declaration is dropped`() {
        val out = sanitize("""<span style="font-weight:bold;font-family:'Arial">x</span><p>After</p>""")
        assertEquals("""<span style="font-weight:bold;">x</span><p>After</p>""", out)
        assertTextSurvives("""<span style="font-family:'Arial">x</span><p>After</p>""", "x", "After")
    }

    @Test
    fun `an unbalanced or mismatched bracket in a style declaration is dropped`() {
        // Swing's CSS parser keeps one stack of '(' and '[' blocks and throws on a close that
        // doesn't match, or on a block left open.
        for (html in listOf(
            """<p style="font-family:a]">x</p><p>After</p>""",
            """<p style="margin:[1px">x</p><p>After</p>""",
            """<p style="margin:([)]">x</p><p>After</p>""",
        )) {
            assertSafe(html)
            assertTextSurvives(html, "x", "After")
        }
        // Balanced brackets, a ';' inside them and a bracket inside a quoted string stay.
        for (html in listOf(
            """<p style="margin:[1px]">x</p>""",
            """<p style="margin:[1px;font-weight:bold]">x</p>""",
            """<p style="font-family:'a]'">x</p>""",
        )) assertEquals(html, sanitize(html))
    }

    @Test
    fun `CSS image urls in well-formed markup are dropped`() {
        for (html in listOf(
            """<p style=background-image:url(http://evil.invalid/u1.png)>x</p>""",
            """<p style="background-image:http://evil.invalid/u2.png">x</p>""",
            """<ul style="list-style-image:http://evil.invalid/u3.png"><li>x</li></ul>""",
            """<p style="background:url&#40;http://evil.invalid/u4.png)">x</p>""",
            """<p style="background:url&#x28;http://evil.invalid/u5.png)">x</p>""",
            """<p style='background: URL(http://evil.invalid/u6.png)'>x</p>""",
        )) assertSafe(html)
    }

    // ── tag reassembly and remaining active tags ──

    @Test
    fun `removing a tag cannot join its neighbours into a new tag`() {
        for (html in listOf(
            """<ba<input>se href="http://evil.invalid/p.png"><img src="">""",
            """<obj<input>ect classid="javax.swing.JEditorPane">""",
            """<isin<input>dex>""",
            """<if<script>x</script>rame src="http://evil.invalid/f">""",
        )) assertSafe(html)
    }

    @Test
    fun `isindex and link are removed`() {
        assertSafe("""<isindex action="http://evil.invalid/q" prompt="Key?">""")
        assertSafe("""<link rel="stylesheet" href="http://evil.invalid/x.css"><p>x</p>""")
        assertEquals("<p>x</p>", RallyHtmlUtils.neutralizeActiveContent("""<link rel="stylesheet" href="http://evil.invalid/x.css"><p>x</p>"""))
    }

    @Test
    fun `an end tag inside another tag's quoted value does not end a dropped element`() {
        val out = RallyHtmlUtils.neutralizeActiveContent(
            """<select><option title="</select>">o</option></select><p>Tail</p>"""
        )
        assertEquals("<p>Tail</p>", out)
    }

    @Test
    fun `a dropped element without an end tag keeps its content`() {
        assertEquals("<p>kept</p>", RallyHtmlUtils.neutralizeActiveContent("<select><p>kept</p>"))
    }

    // ── comments ──

    @Test
    fun `comments are dropped together with any markup they hide`() {
        assertEquals("<p>a</p><p>b</p>",
            RallyHtmlUtils.neutralizeActiveContent("""<p>a</p><!-- <img src="http://evil.invalid/c1.png"> --><p>b</p>"""))
        assertSafe("""<!-- <img src="http://evil.invalid/c1.png"> -->""")
    }

    @Test
    fun `an unterminated comment re-parses from its first newline`() {
        val html = "<!-- note\n<img src=\"http://evil.invalid/c2.png\"><p>after</p>"
        assertSafe(html)
        assertTextSurvives(html, "after")
    }

    @Test
    fun `an in-tag comment cannot hide an external src`() {
        assertSafe("""<img alt="a" -- x --> src="http://evil.invalid/c3.png">""")
        assertSafe("<img alt=\"a\" -- x \n src=\"http://evil.invalid/c4.png\">")
    }

    @Test
    fun `a lone dash inside a tag discards the next character exactly as Swing does`() {
        // Swing's parser drops the character after a lone '-' in an attribute list too, whatever
        // it is (a quote, the '>' that ends the tag), so what follows is read from there on.
        for (html in listOf(
            """<p class="note" -">Important requirement</p><p>Acceptance criteria</p>""",
            """<table><tr><td width=50 -">Important requirement</td></tr></table>""",
            "<b ->hidden</b><p>after</p>",
            "<p ->Text</p>",
        )) {
            assertEquals(html, renderedText(html), renderedText(sanitize(html)))
        }
    }

    @Test
    fun `a stray less-than sign is dropped exactly as Swing drops it`() {
        // Text never carries a raw '<' out of the sanitizer; Swing discards a '<' that doesn't
        // open a tag anyway, so what the pane shows is unchanged.
        for (html in listOf("<p>1 < 2 and 3 <4</p>", "<p>x <= y</p>", "<p>tail <</p>", "<!DOCTYPE html><p>doc</p>")) {
            assertEquals(html, renderedText(html), renderedText(sanitize(html)))
        }
        // "<>" is an SGML empty start tag that reopens the most recent element (a paragraph
        // break here); it carries nothing worth keeping, so it goes and the text stays.
        assertEquals("<p>ab</p>", sanitize("<p>a<>b</p>"))
    }

    @Test
    fun `a stray less-than sign cannot join the text after a dropped tag into a tag`() {
        // Kept as text, the stray '<' would meet the text after the removed tag and open a new
        // one; dropped (as Swing drops it), that text stays text.
        for ((html, literal) in listOf(
            "<<input>img src=\"http://evil.invalid/j1.png\">" to "img src=",
            "<<script>x</script>img src=http://evil.invalid/j2.png>" to "img src=",
            "a <<script>x</script>base href=\"http://evil.invalid/\">" to "base href=",
            "<<select></select>img src=http://evil.invalid/j3.png>" to "img src=",
        )) {
            assertSafe(html)
            assertTextSurvives(html, literal)
        }
    }

    // ── verbatim copy of well-formed descriptions ──

    @Test
    fun `a realistic well-formed description is byte-identical after neutralizing`() {
        val html = """
            <p><b>Summary</b>: the checkout page should show <i>tax</i> before payment.</p>
            <table border="1" cellpadding="2" cellspacing='0' width=100%>
              <tr><th>Step</th><th>Expected</th></tr>
              <tr><td valign=top>1</td><td><span style="font-weight:bold;font-size:12px">Open cart</span></td></tr>
            </table>
            <p>Screenshot:<br /><img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==" alt="cart" width="120"></p>
            <ul><li>See <a href="https://rally1.rallydev.com/#/detail/userstory/1">US1</a></li><li>1 &lt; 2 &amp;&amp; 3 &gt; 2</li></ul>
            <p style="margin-left:20px;">Indented &nbsp;text</p>
        """.trimIndent()
        assertEquals(html, RallyHtmlUtils.neutralizeActiveContent(html))
    }

    // ── property test: the sanitizer and Swing's parser must agree ──

    @Test
    fun `fuzzed markup never reaches Swing with an external resource or active tag`() {
        val random = Random(20260924L)
        val alphabet = listOf(
            "<", "<", "<", ">", ">", "\"", "\"", "'", "'", "=", "=", " ", " ", "/", "-", "-", "!", ",", "&", "\n",
            "img", "src", "=", "http://evil.invalid/x.png", "background", "style", "url(", "base", "href",
            "isindex", "input", "data:image/png;base64,AAAA", "a", "p", ";", "#", "(", ")", "[", "]", ":",
            // Compound fragments, so short strings still reach the attribute and style paths.
            "<img ", "<p ", " src=", " src=\"", " src='", "src=http://evil.invalid/x.png", " background=",
            " style=\"", "style=", "background-image:url(http://evil.invalid/x.png)", "&quot;", "<!--", "-->", "--",
        )
        val failures = mutableListOf<String>()
        repeat(20_000) {
            val sb = StringBuilder()
            repeat(1 + random.nextInt(12)) { sb.append(alphabet[random.nextInt(alphabet.size)]) }
            val input = sb.toString()
            for (sanitized in listOf(RallyHtmlUtils.neutralizeActiveContent(input), sanitize(input))) {
                val v = violations(sanitized)
                if (v.isNotEmpty() && failures.size < 20) failures += "input=${escape(input)} sanitized=${escape(sanitized)} -> $v"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun pick(random: Random, options: List<String>): String = options[random.nextInt(options.size)]

    /** Pieces of attribute values: entity references in each form Swing parses, line breaks, stray quotes, hidden markup. */
    private val valueParts = listOf(
        "x", "1", "&amp", "&amp;", "&zz", "&#65", "&#x", "&lt", "&", "\n", "\r\n", "\r", "\"", "'", "=", ">",
        " src=http://evil.invalid/f.png ", "<input type=image src=http://evil.invalid/g.png>",
        "data:image/png;base64,AAAA", "http://evil.invalid/h.png", "url(http://evil.invalid/i.png)",
    )

    /**
     * A start tag whose attribute list mixes quoted and unquoted values built from [valueParts],
     * separated by every kind of whitespace Swing skips, followed by some content.
     */
    private fun randomAttributeTag(random: Random): String {
        val sb = StringBuilder("<").append(pick(random, listOf("img", "p", "a")))
        repeat(1 + random.nextInt(4)) {
            sb.append(pick(random, listOf(" ", " ", "\n", "\r\n", "\r", "\t", "")))
            sb.append(pick(random, listOf("alt", "title", "src", "style", "class", "background")))
            if (random.nextInt(8) == 0) return@repeat // valueless
            sb.append('=')
            val quote = pick(random, listOf("\"", "'", "", ""))
            sb.append(quote)
            repeat(1 + random.nextInt(3)) { sb.append(pick(random, valueParts)) }
            sb.append(quote)
        }
        sb.append(pick(random, listOf(">", ">", "/>", "\n>", "")))
        sb.append(pick(random, listOf("text</p>", "", "<p>after</p>")))
        return sb.toString()
    }

    @Test
    fun `fuzzed attribute lists never reach Swing with an external resource or active tag`() {
        val random = Random(20260925L)
        val failures = mutableListOf<String>()
        repeat(20_000) {
            val input = randomAttributeTag(random)
            for (sanitized in listOf(RallyHtmlUtils.neutralizeActiveContent(input), sanitize(input))) {
                val v = violations(sanitized)
                if (v.isNotEmpty() && failures.size < 20) failures += "input=${escape(input)} sanitized=${escape(sanitized)} -> $v"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** Unquoted value pieces: no whitespace, quotes, '<' or '>', so the value ends where it looks like it does. */
    private val unquotedParts = listOf("x", "1", "&amp", "&amp;", "&zz", "&#65", "&#x", "&#x41;", "&", "=", "/", ";", "#", ":", ".")

    /** Quoted value pieces, including whitespace, line breaks and markup that must stay inside the value. */
    private val quotedParts = unquotedParts + listOf(
        " ", "\t", "\n", "\r\n", "\r", "'", "\"", ">", "<b>",
        " src=http://evil.invalid/q.png ", "><input type=image src=http://evil.invalid/q2.png>",
    )

    /** One attribute as a well-formed-looking tag writes it, and the same declaration written unambiguously (quoted, on one line). */
    private class DeclaredAttribute(val written: String, val normalized: String)

    private fun declaredAttribute(random: Random): DeclaredAttribute {
        val name = pick(random, listOf("alt", "title", "class", "lang", "width"))
        fun value(parts: List<String>) = buildString { repeat(1 + random.nextInt(3)) { append(pick(random, parts)) } }
        return when (random.nextInt(5)) {
            0, 1 -> value(unquotedParts).let { DeclaredAttribute("$name=$it", "$name=\"$it\"") }
            2 -> value(quotedParts.filterNot { '"' in it }).let { DeclaredAttribute("$name=\"$it\"", "$name=\"$it\"") }
            3 -> value(quotedParts.filterNot { '\'' in it }).let {
                DeclaredAttribute("$name='$it'", if ('"' in it) "$name='$it'" else "$name=\"$it\"")
            }
            else -> DeclaredAttribute(name, name)
        }
    }

    @Test
    fun `a tag copied verbatim is read by Swing exactly as it is written`() {
        // The sanitizer copies a tag byte-for-byte only when it read it exactly as Swing does. So
        // whenever a tag comes out unchanged, Swing must see the attributes the tag textually
        // declares — the ones it sees in the same declaration written quoted on one line.
        val random = Random(20260926L)
        val failures = mutableListOf<String>()
        var verbatim = 0
        val cases = 20_000
        repeat(cases) {
            val attributes = List(1 + random.nextInt(4)) { declaredAttribute(random) }
            val separators = List(attributes.size) { pick(random, listOf(" ", "  ", "\n", "\r\n", "\r", "\t")) }
            val written = "<p" + attributes.indices.joinToString("") { separators[it] + attributes[it].written } +
                pick(random, listOf(">", " >", "\n>")) + "text</p>"
            if (RallyHtmlUtils.neutralizeActiveContent(written) != written) return@repeat
            verbatim++
            val normalized = "<p" + attributes.joinToString("") { " " + it.normalized } + ">text</p>"
            val swing = swingView(written)
            val declared = swingView(normalized)
            if (swing != declared && failures.size < 20) failures += "tag=${escape(written)}\n  swing=$swing\n  declared=$declared"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("only $verbatim of $cases tags were copied verbatim", verbatim > cases / 4)
    }

    private fun escape(s: String) = s.replace("\n", "\\n").replace("\r", "\\r")

    // ── performance ──

    @Test
    fun `a multi-megabyte description is sanitized in linear time`() {
        val image = "<img src=\"data:image/png;base64," + "A".repeat(4_000_000) + "\" alt=\"big\">"
        val markup = "<p style=\"font-weight:bold;color:#000\">Row <b>text</b> <a href='/x'>link</a></p>\n".repeat(2_000)
        val html = markup + image + markup
        val start = System.nanoTime()
        val out = sanitize(html)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("sanitizing took $elapsedMs ms", elapsedMs < 2_000)
        assertTrue(out.contains("A".repeat(1000)))
        assertFalse(out.contains("color:#000"))
    }
}
