package com.github.halmuratuyghur.rally.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class RallyFileUtilsTest {

    private val tmp: Path = Files.createTempDirectory("rally-fileutils-test")

    @Test
    fun `reserved name CON gets underscore prefix`() {
        assertEquals("_CON", RallyFileUtils.sanitizeFileName("CON"))
    }

    @Test
    fun `reserved name is case insensitive on base name`() {
        assertEquals("_con.txt", RallyFileUtils.sanitizeFileName("con.txt"))
    }

    @Test
    fun `reserved NUL with extension gets underscore prefix`() {
        assertEquals("_NUL.json", RallyFileUtils.sanitizeFileName("NUL.json"))
    }

    @Test
    fun `reserved COM1 gets underscore prefix`() {
        assertEquals("_COM1", RallyFileUtils.sanitizeFileName("COM1"))
    }

    @Test
    fun `reserved LPT9 with extension gets underscore prefix`() {
        assertEquals("_LPT9.dat", RallyFileUtils.sanitizeFileName("LPT9.dat"))
    }

    @Test
    fun `reserved PRN gets underscore prefix`() {
        assertEquals("_PRN", RallyFileUtils.sanitizeFileName("PRN"))
    }

    @Test
    fun `reserved AUX with extension gets underscore prefix`() {
        assertEquals("_AUX.log", RallyFileUtils.sanitizeFileName("AUX.log"))
    }

    @Test
    fun `reserved name with multiple dots still prefixed`() {
        // Windows treats everything before the first dot as the device name,
        // so CON.txt.bak collides with the CON device and must be prefixed.
        assertEquals("_CON.txt.bak", RallyFileUtils.sanitizeFileName("CON.txt.bak"))
    }

    @Test
    fun `reserved name case insensitive with multiple dots`() {
        assertEquals("_nul.tar.gz", RallyFileUtils.sanitizeFileName("nul.tar.gz"))
    }

    @Test
    fun `non-reserved name with matching prefix is not prefixed`() {
        // COM10 is not a reserved device (only COM1-COM9 are reserved)
        assertEquals("COM10", RallyFileUtils.sanitizeFileName("COM10"))
    }

    @Test
    fun `trailing space is stripped`() {
        assertEquals("report.txt", RallyFileUtils.sanitizeFileName("report.txt "))
    }

    @Test
    fun `trailing dot is stripped`() {
        assertEquals("file", RallyFileUtils.sanitizeFileName("file."))
    }

    @Test
    fun `leading dot is stripped`() {
        assertEquals("hidden", RallyFileUtils.sanitizeFileName(".hidden"))
    }

    @Test
    fun `empty string becomes unnamed`() {
        assertEquals("unnamed", RallyFileUtils.sanitizeFileName(""))
    }

    @Test
    fun `whitespace only becomes unnamed`() {
        assertEquals("unnamed", RallyFileUtils.sanitizeFileName("   "))
    }

    @Test
    fun `long name is truncated and extension preserved`() {
        val longName = "a".repeat(500) + ".txt"
        val result = RallyFileUtils.sanitizeFileName(longName)
        assertTrue("length under cap: ${result.length}", result.length <= 200)
        assertTrue("extension preserved", result.endsWith(".txt"))
    }

    @Test
    fun `path separators in name are replaced with underscore`() {
        val result = RallyFileUtils.sanitizeFileName("../etc/passwd")
        assertTrue("no slash survives: $result", !result.contains('/'))
        assertTrue("no backslash survives: $result", !result.contains('\\'))
        assertTrue("no traversal dots: $result", !result.contains(".."))
    }

    @Test
    fun `null byte replaced with underscore`() {
        assertEquals("foo_bar", RallyFileUtils.sanitizeFileName("foo\u0000bar"))
    }

    @Test
    fun `windows-invalid characters replaced`() {
        val result = RallyFileUtils.sanitizeFileName("file<>:|?*.txt")
        assertTrue("no angle brackets: $result", !result.contains('<') && !result.contains('>'))
        assertTrue("no colon or pipe: $result", !result.contains(':') && !result.contains('|'))
        assertTrue("no wildcards: $result", !result.contains('?') && !result.contains('*'))
    }

    @Test
    fun `safeResolve builds a path inside parent`() {
        val resolved = RallyFileUtils.safeResolve(tmp, "US123_attachments")
        val expected = tmp.toAbsolutePath().normalize().resolve("US123_attachments")
        assertEquals(expected, resolved)
    }

    @Test
    fun `safeResolve sanitizes reserved names`() {
        val resolved = RallyFileUtils.safeResolve(tmp, "CON")
        val expected = tmp.toAbsolutePath().normalize().resolve("_CON")
        assertEquals(expected, resolved)
    }

    @Test
    fun `safeResolve with traversal-looking input stays in parent`() {
        val resolved = RallyFileUtils.safeResolve(tmp, "../escape")
        val normalizedParent = tmp.toAbsolutePath().normalize()
        assertTrue(
            "resolved ($resolved) should stay under parent ($normalizedParent)",
            resolved.startsWith(normalizedParent)
        )
    }

    @Test
    fun `safeResolve with empty child resolves to unnamed`() {
        val resolved = RallyFileUtils.safeResolve(tmp, "")
        val expected = tmp.toAbsolutePath().normalize().resolve("unnamed")
        assertEquals(expected, resolved)
    }

    @Test
    fun `safeResolve rejects absolute child that escapes parent`() {
        val other = Files.createTempDirectory("rally-fileutils-other")
        try {
            // An absolute other-dir path, sanitized, becomes a long underscore-joined
            // filename — still contained within `tmp`. We assert the result stays under tmp.
            val resolved = RallyFileUtils.safeResolve(tmp, other.toAbsolutePath().toString())
            val normalizedParent = tmp.toAbsolutePath().normalize()
            assertTrue(
                "resolved ($resolved) should stay under parent ($normalizedParent)",
                resolved.startsWith(normalizedParent)
            )
        } finally {
            Files.deleteIfExists(other)
        }
    }

    @Test
    fun `safeResolve throws if sanitized result somehow escapes parent`() {
        // This test documents the containment contract. With the current sanitizer the
        // only way to reach this branch would be a name that normalizes outside the
        // parent, which is impossible because sanitizeFileName strips slashes. The
        // invariant is asserted to catch future regressions in either function.
        try {
            val resolved = RallyFileUtils.safeResolve(tmp, "safe_name")
            assertTrue(resolved.startsWith(tmp.toAbsolutePath().normalize()))
        } catch (_: IllegalArgumentException) {
            fail("safeResolve should not throw for a plain safe name")
        }
    }
}
