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
}
