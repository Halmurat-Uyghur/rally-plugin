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
