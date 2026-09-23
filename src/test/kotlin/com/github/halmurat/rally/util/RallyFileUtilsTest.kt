package com.github.halmurat.rally.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class RallyFileUtilsTest {

    // JUnit 4 instantiates the test class once per @Test method, so without an
    // explicit cleanup the property initializer below would leak one temp dir
    // per test. @Before/@After tear down per-test instead.
    private lateinit var tmp: Path

    @Before
    fun createTempDir() {
        tmp = Files.createTempDirectory("rally-fileutils-test")
    }

    @After
    fun deleteTempDir() {
        if (!::tmp.isInitialized) return
        Files.walk(tmp).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

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
    fun `safeResolve sanitizes an absolute path argument into a contained filename`() {
        // An absolute other-dir path, sanitized, becomes a single underscore-joined
        // filename — still a direct child of `tmp`, never an escape.
        val other = Files.createTempDirectory("rally-fileutils-other")
        try {
            val resolved = RallyFileUtils.safeResolve(tmp, other.toAbsolutePath().toString())
            val normalizedParent = tmp.toAbsolutePath().normalize()
            assertTrue(
                "resolved ($resolved) should stay under parent ($normalizedParent)",
                resolved.startsWith(normalizedParent)
            )
            assertEquals("must collapse to a single child segment", normalizedParent, resolved.parent)
        } finally {
            Files.deleteIfExists(other)
        }
    }

    @Test
    fun `safeResolve neutralizes traversal sequences into a contained direct child`() {
        // The real defense: traversal-style child names must never resolve outside the
        // parent. sanitizeFileName strips separators and dots, so each input collapses to
        // a single contained segment. (This is why the require() throw branch is, by design,
        // unreachable through the public API — the sanitizer prevents escapes upstream.)
        val normalizedParent = tmp.toAbsolutePath().normalize()
        for (evil in listOf("../../etc/passwd", "..\\..\\windows\\system32", "../sibling", "/abs/evil", "....//evil")) {
            val resolved = RallyFileUtils.safeResolve(tmp, evil)
            assertTrue(
                "'$evil' resolved to $resolved which escapes $normalizedParent",
                resolved.startsWith(normalizedParent)
            )
            assertEquals("'$evil' should resolve to a direct child of the parent", normalizedParent, resolved.parent)
        }
    }
}
