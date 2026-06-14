package com.github.halmurat.rally.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RallyExporter]'s pure formatting helpers (escapeMarkdown,
 * stripHtml). Both live on the companion object so this test file doesn't
 * have to construct an exporter (which needs a real RallyApiClient and
 * platform services).
 */
class RallyExporterFormatTest {

    // ── inlineImageLocalName ─────────────────────────────────────

    @Test
    fun `inlineImageLocalName keys on the attachment objectId`() {
        // A per-call counter restarted at 1 for every downloadInlineImages call,
        // so the description's first image and each test step's first image all
        // mapped to "$artifactId.$ext" and silently overwrote one another.
        assertEquals("TC123_111.png", RallyExporter.inlineImageLocalName("TC123", "111", "screenshot.png"))
        assertEquals("TC123_222.png", RallyExporter.inlineImageLocalName("TC123", "222", "screenshot.png"))
    }

    @Test
    fun `inlineImageLocalName lowercases the extension and defaults to png`() {
        assertEquals("US1_7.jpg", RallyExporter.inlineImageLocalName("US1", "7", "photo.JPG"))
        assertEquals("US1_7.png", RallyExporter.inlineImageLocalName("US1", "7", "no-extension"))
    }

    // ── escapeMarkdown ───────────────────────────────────────────

    @Test
    fun `escapeMarkdown leaves plain text alone`() {
        assertEquals("Plain story title", RallyExporter.escapeMarkdown("Plain story title"))
    }

    @Test
    fun `escapeMarkdown escapes pipes`() {
        // A pipe inside an artifact name used to corrupt nearby table rows in the
        // bulk markdown export.
        assertEquals("Login \\| Logout flow", RallyExporter.escapeMarkdown("Login | Logout flow"))
    }

    @Test
    fun `escapeMarkdown escapes asterisks`() {
        // Without the escape, "**bold**" inside an artifact name turned half the
        // surrounding text into emphasis.
        assertEquals("\\*important\\* fix", RallyExporter.escapeMarkdown("*important* fix"))
    }

    @Test
    fun `escapeMarkdown escapes underscores`() {
        assertEquals("user\\_name field", RallyExporter.escapeMarkdown("user_name field"))
    }

    @Test
    fun `escapeMarkdown escapes square brackets`() {
        assertEquals("Track \\[bug\\]", RallyExporter.escapeMarkdown("Track [bug]"))
    }

    @Test
    fun `escapeMarkdown escapes backticks`() {
        assertEquals("call \\`foo()\\`", RallyExporter.escapeMarkdown("call `foo()`"))
    }

    @Test
    fun `escapeMarkdown escapes hashes`() {
        assertEquals("issue \\#42", RallyExporter.escapeMarkdown("issue #42"))
    }

    @Test
    fun `escapeMarkdown escapes backslashes first`() {
        // Order matters: backslash must be escaped before the others so we don't
        // double-escape the introduced backslashes.
        val result = RallyExporter.escapeMarkdown("path\\to\\file")
        assertEquals("path\\\\to\\\\file", result)
    }

    @Test
    fun `escapeMarkdown handles empty string`() {
        assertEquals("", RallyExporter.escapeMarkdown(""))
    }

    @Test
    fun `escapeMarkdown handles a name with every special char`() {
        val raw = "*x* _y_ [z] | # `t` \\ end"
        val escaped = RallyExporter.escapeMarkdown(raw)
        // None of the unescaped tokens should remain
        assertFalse("no bare asterisk", "*x*" in escaped)
        assertFalse("no bare underscore", "_y_" in escaped)
        assertFalse("no bare brackets", "[z]" in escaped)
        assertFalse("no bare pipe", " | " in escaped)
        assertFalse("no bare hash", " # " in escaped)
        assertFalse("no bare backtick", "`t`" in escaped)
        // The original content should still be present in escaped form
        assertTrue("contains escaped asterisk", "\\*x\\*" in escaped)
        assertTrue("contains escaped underscore", "\\_y\\_" in escaped)
        assertTrue("contains escaped brackets", "\\[z\\]" in escaped)
    }

    // ── stripHtml ────────────────────────────────────────────────

    @Test
    fun `stripHtml removes simple tags`() {
        assertEquals(
            "Hello world",
            RallyExporter.stripHtml("<p>Hello world</p>")
        )
    }

    @Test
    fun `stripHtml decodes amp entity`() {
        assertEquals("Tom & Jerry", RallyExporter.stripHtml("Tom &amp; Jerry"))
    }

    @Test
    fun `stripHtml decodes nbsp entity`() {
        assertEquals("a b", RallyExporter.stripHtml("a&nbsp;b"))
    }

    @Test
    fun `stripHtml decodes lt and gt entities`() {
        assertEquals("a < b > c", RallyExporter.stripHtml("a &lt; b &gt; c"))
    }

    @Test
    fun `stripHtml decodes quot entity`() {
        assertEquals("She said \"hi\"", RallyExporter.stripHtml("She said &quot;hi&quot;"))
    }

    @Test
    fun `stripHtml converts li to dash bullet`() {
        val html = "<ul><li>first</li><li>second</li></ul>"
        val result = RallyExporter.stripHtml(html)
        assertTrue("first bullet present", "- first" in result)
        assertTrue("second bullet present", "- second" in result)
    }

    @Test
    fun `stripHtml converts br to newline`() {
        // BR becomes a newline; consecutive BRs collapse via the multi-newline regex.
        val result = RallyExporter.stripHtml("line1<br/>line2<br>line3")
        assertTrue("contains line1", "line1" in result)
        assertTrue("contains line2", "line2" in result)
        assertTrue("contains line3", "line3" in result)
        assertTrue("uses newlines as separators", result.contains("\n"))
    }

    @Test
    fun `stripHtml collapses 3 or more newlines to 2`() {
        val raw = "first\n\n\n\nsecond"
        val result = RallyExporter.stripHtml(raw)
        assertEquals("first\n\nsecond", result)
    }

    @Test
    fun `stripHtml on empty input returns empty string`() {
        assertEquals("", RallyExporter.stripHtml(""))
    }

    @Test
    fun `stripHtml on whitespace-only input returns empty string`() {
        assertEquals("", RallyExporter.stripHtml("   \n\t  "))
    }

    @Test
    fun `stripHtml strips img tags`() {
        // Inline images aren't useful for plain-text consumption, so they should
        // be stripped along with everything else.
        val html = """Description with <img src="https://x/y.png"/> embedded image."""
        val result = RallyExporter.stripHtml(html)
        assertFalse("img tag should be removed", "<img" in result)
        assertTrue("surrounding text preserved", "Description with" in result)
    }
}
