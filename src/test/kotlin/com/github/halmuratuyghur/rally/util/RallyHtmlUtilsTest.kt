package com.github.halmuratuyghur.rally.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RallyHtmlUtilsTest {

    private val pattern = RallyHtmlUtils.INLINE_IMG_PATTERN

    @Test
    fun `matches absolute Rally inline image src with double quotes`() {
        val html = """<img src="https://rally1.rallydev.com/slm/attachment/12345/screenshot.png"/>"""
        val matcher = pattern.matcher(html)
        assertTrue("pattern should match", matcher.find())
        assertEquals("https://rally1.rallydev.com/slm/attachment/12345/screenshot.png", matcher.group(2))
        assertEquals("12345", matcher.group(3))
        assertEquals("screenshot.png", matcher.group(4))
    }

    @Test
    fun `matches absolute Rally inline image src with single quotes`() {
        val html = """<img src='https://rally1.rallydev.com/slm/attachment/678/diagram.png'/>"""
        val matcher = pattern.matcher(html)
        assertTrue("pattern should match", matcher.find())
        assertEquals("678", matcher.group(3))
        assertEquals("diagram.png", matcher.group(4))
    }

    @Test
    fun `matches site-relative Rally inline image src`() {
        // Rally sometimes emits descriptions with site-relative attachment URLs.
        val html = """<img src="/slm/attachment/9999/file.jpg"/>"""
        val matcher = pattern.matcher(html)
        assertTrue("pattern should match site-relative", matcher.find())
        assertEquals("9999", matcher.group(3))
        assertEquals("file.jpg", matcher.group(4))
    }

    @Test
    fun `does not match non-Rally external image`() {
        val html = """<img src="https://example.com/image.png"/>"""
        val matcher = pattern.matcher(html)
        assertFalse("pattern should not match arbitrary external src", matcher.find())
    }

    @Test
    fun `does not match Rally URL without slm attachment path`() {
        val html = """<img src="https://rally1.rallydev.com/slm/webservice/v2.0/foo/bar"/>"""
        val matcher = pattern.matcher(html)
        assertFalse("pattern should require /slm/attachment/", matcher.find())
    }

    @Test
    fun `pattern is case insensitive on the src attribute`() {
        val html = """<img SRC="/slm/attachment/1/foo.png"/>"""
        val matcher = pattern.matcher(html)
        assertTrue("uppercase SRC should still match", matcher.find())
    }

    @Test
    fun `matches filename containing apostrophe inside double quotes`() {
        // Regression: a [^"']+ filename group stopped at the apostrophe and the
        // backreference then required an immediate closing quote, so find() failed
        // and the image rendered broken / kept its remote authenticated URL.
        val html = """<img src="/slm/attachment/123/John's screenshot.png"/>"""
        val matcher = pattern.matcher(html)
        assertTrue("apostrophe inside double-quoted src should match", matcher.find())
        assertEquals("123", matcher.group(3))
        assertEquals("John's screenshot.png", matcher.group(4))
    }

    @Test
    fun `matches filename containing double quote inside single quotes`() {
        val html = """<img src='/slm/attachment/55/say "cheese".png'/>"""
        val matcher = pattern.matcher(html)
        assertTrue("double quote inside single-quoted src should match", matcher.find())
        assertEquals("55", matcher.group(3))
        assertEquals("say \"cheese\".png", matcher.group(4))
    }

    @Test
    fun `unterminated quote does not swallow a following valid image`() {
        // Malformed HTML: the first src never closes its quote. The filename group
        // must not run across tags and consume the next valid image's opening quote.
        val html = """<img src="/slm/attachment/7/broken.png><p>text</p><img src="/slm/attachment/8/ok.png"/>"""
        val matcher = pattern.matcher(html)
        val found = mutableListOf<String>()
        while (matcher.find()) found.add(matcher.group(3))
        assertEquals(listOf("8"), found)
    }

    @Test
    fun `inlineImageUrl keeps an already-legal url raw`() {
        assertEquals(
            "https://r.example/slm/attachment/7/shot.png",
            RallyHtmlUtils.inlineImageUrl("https://r.example", "7", "shot.png")
        )
    }

    @Test
    fun `inlineImageUrl keeps pre-encoded filenames untouched`() {
        // Re-encoding would double-encode %20 into %2520 and break the download.
        assertEquals(
            "https://r.example/slm/attachment/7/my%20shot.png",
            RallyHtmlUtils.inlineImageUrl("https://r.example", "7", "my%20shot.png")
        )
    }

    @Test
    fun `inlineImageUrl encodes URI-illegal filenames`() {
        // The regex can now match filenames with spaces/quotes, but the download
        // layer rejects URI-illegal characters — without encoding, the matched
        // image still fails to download (URI() throws inside requireSameHost).
        assertEquals(
            "https://r.example/slm/attachment/123/John%27s%20screenshot.png",
            RallyHtmlUtils.inlineImageUrl("https://r.example", "123", "John's screenshot.png")
        )
        assertEquals(
            "https://r.example/slm/attachment/55/say%20%22cheese%22.png",
            RallyHtmlUtils.inlineImageUrl("https://r.example", "55", "say \"cheese\".png")
        )
    }

    @Test
    fun `multiple matches in a single description`() {
        val html = """
            <p>First: <img src="/slm/attachment/1/a.png"/></p>
            <p>Second: <img src="/slm/attachment/2/b.jpg"/></p>
            <p>Third:  <img src='/slm/attachment/3/c.gif'/></p>
        """.trimIndent()
        val matcher = pattern.matcher(html)
        val seen = mutableListOf<Pair<String, String>>()
        while (matcher.find()) {
            seen.add(matcher.group(3) to matcher.group(4))
        }
        assertEquals(3, seen.size)
        assertEquals("1" to "a.png", seen[0])
        assertEquals("2" to "b.jpg", seen[1])
        assertEquals("3" to "c.gif", seen[2])
    }

    // ── stripInlineColors ────────────────────────────────────────

    @Test
    fun `stripInlineColors removes color and background-color from a style attribute`() {
        val out = RallyHtmlUtils.stripInlineColors(
            """<span style="font-weight:bold;color:#000;background-color:#fff">Hi</span>"""
        )
        assertTrue("non-color declarations are kept", out.contains("font-weight:bold"))
        assertFalse("text color removed", out.contains("#000"))
        assertFalse("background color removed", out.contains("#fff"))
        assertTrue("text content untouched", out.contains(">Hi</span>"))
    }

    @Test
    fun `stripInlineColors removes the TC2609759-style white-on-dark span`() {
        // Regression for the screenshot: a span with an explicit white background and
        // black text rendered as a jarring white block on the dark IDE theme.
        val out = RallyHtmlUtils.stripInlineColors(
            """<span style="background-color:#ffffff;color:#000000">TC2609759: Pricing</span>"""
        )
        assertTrue("text preserved", out.contains("TC2609759: Pricing"))
        assertFalse("white background gone", out.contains("#ffffff"))
        assertFalse("black text color gone", out.contains("#000000"))
    }

    @Test
    fun `stripInlineColors removes presentational color and bgcolor attributes`() {
        assertEquals(
            """<font face="Arial">x</font>""",
            RallyHtmlUtils.stripInlineColors("""<font color="red" face="Arial">x</font>""")
        )
        assertEquals(
            "<td>y</td>",
            RallyHtmlUtils.stripInlineColors("<td bgcolor='white'>y</td>")
        )
        assertEquals(
            "<font>z</font>",
            RallyHtmlUtils.stripInlineColors("<font color=red>z</font>")
        )
    }

    @Test
    fun `stripInlineColors preserves border-color and background-image`() {
        val out = RallyHtmlUtils.stripInlineColors(
            """<div style="border-color:red;background-image:url(data:image/png;base64,AAAA);color:blue">d</div>"""
        )
        assertTrue("border-color kept", out.contains("border-color:red"))
        assertTrue("background image kept", out.contains("background-image:url(data:image/png;base64,AAAA)"))
        assertFalse("bare text color removed", out.contains("blue"))
    }

    @Test
    fun `stripInlineColors leaves image src and data URIs untouched`() {
        val dataUri = RallyHtmlUtils.stripInlineColors(
            """<img src="data:image/png;base64,iVBOR" style="color:red"/>"""
        )
        assertTrue("data URI src preserved", dataUri.contains("""src="data:image/png;base64,iVBOR""""))
        assertFalse("inline color removed", dataUri.contains("color:red"))

        val attachment = RallyHtmlUtils.stripInlineColors(
            """<img src="/slm/attachment/12/a.png" bgcolor="white"/>"""
        )
        assertTrue("attachment src preserved", attachment.contains("""src="/slm/attachment/12/a.png""""))
        assertFalse("bgcolor attribute removed", attachment.contains("bgcolor"))
    }

    @Test
    fun `stripInlineColors does not touch color words in visible text`() {
        // The whole point of scoping to tag interiors: a description that literally
        // mentions "color:" or "bgcolor=" in its prose must be left verbatim.
        val html = "<p>My favorite color: blue, set bgcolor=white in the legacy config.</p>"
        assertEquals(html, RallyHtmlUtils.stripInlineColors(html))
    }

    @Test
    fun `stripInlineColors is case insensitive`() {
        val style = RallyHtmlUtils.stripInlineColors(
            """<SPAN STYLE="COLOR:#000;BACKGROUND-COLOR:#FFF">t</SPAN>"""
        )
        assertFalse(style.contains("#000"))
        assertFalse(style.contains("#FFF"))
        assertTrue(style.contains(">t</SPAN>"))

        assertEquals(
            "<td>x</td>",
            RallyHtmlUtils.stripInlineColors("""<td BGCOLOR="white">x</td>""")
        )
    }

    @Test
    fun `stripInlineColors keeps class attributes`() {
        // The detail panel's auth-error HTML relies on a `class` (not inline color)
        // surviving the strip so its wrapHtml `.rally-error` stylesheet rule still colors it.
        val html = "<span class='rally-error'><b>Authentication failed.</b></span>"
        assertEquals(html, RallyHtmlUtils.stripInlineColors(html))
    }

    @Test
    fun `stripInlineColors leaves color-free html unchanged`() {
        val html = "<p>Plain <b>text</b> with a <a href='/x'>link</a>.</p>"
        assertEquals(html, RallyHtmlUtils.stripInlineColors(html))
        assertEquals("", RallyHtmlUtils.stripInlineColors(""))
    }

    @Test
    fun `stripInlineColors removes embedded style blocks`() {
        // <style> rule bodies live outside tag interiors, so the attribute passes can't
        // see them — the whole element must go, or body{background:#fff} would
        // re-introduce the exact white-block bug the strip exists to fix.
        assertEquals(
            "<p>x</p>",
            RallyHtmlUtils.stripInlineColors("<style>body{background:#fff;color:#000}</style><p>x</p>")
        )
        assertEquals(
            "<p>multi</p>",
            RallyHtmlUtils.stripInlineColors(
                "<STYLE type=\"text/css\">\np { color: red; }\n.x { background: white; }\n</STYLE><p>multi</p>"
            )
        )
        assertEquals(
            "<p>a</p><p>b</p>",
            RallyHtmlUtils.stripInlineColors(
                "<style>p{color:red}</style><p>a</p><style>p{color:blue}</style><p>b</p>"
            )
        )
    }

    @Test
    fun `stripInlineColors removes link stylesheet tags but keeps anchors`() {
        // A linked stylesheet both restyles text and makes Swing fetch an off-host URL
        // synchronously — the same vector class the external-src neutralizer closes.
        assertEquals(
            """<a href="/page">go</a>""",
            RallyHtmlUtils.stripInlineColors(
                """<link rel="stylesheet" href="http://example.com/x.css"><a href="/page">go</a>"""
            )
        )
    }

    @Test
    fun `stripInlineColors leaves unclosed style and lone closer alone`() {
        // Fail-open like the file's other pragmatic patterns: an unterminated <style>
        // must not let the lazy block pattern eat the rest of the description.
        assertEquals(
            "<style>p{color:red}<p>rest</p>",
            RallyHtmlUtils.stripInlineColors("<style>p{color:red}<p>rest</p>")
        )
        assertEquals("</style><p>x</p>", RallyHtmlUtils.stripInlineColors("</style><p>x</p>"))
    }

    @Test
    fun `stripInlineColors cannot unbalance a tag via an unquoted attribute value`() {
        // Pathological: an attribute value containing " color=…". The unquoted-value
        // branch must stop at a quote so it can't eat src's closing quote and unbalance
        // the tag — the value fragment is sacrificed (documented pragmatism), but the
        // tag stays well-formed.
        assertEquals(
            """<img src="/slm/attachment/1/my">""",
            RallyHtmlUtils.stripInlineColors("""<img src="/slm/attachment/1/my color=red.png">""")
        )
    }
}
