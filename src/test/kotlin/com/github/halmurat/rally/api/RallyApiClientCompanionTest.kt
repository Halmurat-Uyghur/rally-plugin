package com.github.halmurat.rally.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [RallyApiClient]'s companion-object helpers — pure functions that
 * don't require constructing a client (which would touch IntelliJ's Logger and
 * HttpConfigurable services and fail outside a platform-test fixture).
 */
class RallyApiClientCompanionTest {

    // ── escapeQueryValue ─────────────────────────────────────────
    // Rally WSAPI documents backslash escapes for query values (Broadcom
    // TechDocs "Query Syntax → Escaping Special Characters"): '"' → \" and
    // '\' → \\. Escaping (rather than the old stripping) keeps quoted
    // sprint/story names searchable while still preventing breakout from a
    // "(Field = \"...\")" template — every quote in the output is preceded by
    // a backslash. Only these two are escaped: they're the only characters
    // that can terminate the quoted string, and other specials (apostrophes,
    // parens, angle brackets) have always passed through unescaped and worked.

    @Test
    fun `escapeQueryValue escapes double quotes`() {
        assertEquals(
            "Story \\\"with\\\" quotes",
            RallyApiClient.escapeQueryValue("Story \"with\" quotes")
        )
    }

    @Test
    fun `escapeQueryValue escapes backslashes`() {
        assertEquals(
            "path\\\\file",
            RallyApiClient.escapeQueryValue("path\\file")
        )
    }

    @Test
    fun `escapeQueryValue passes other special characters through`() {
        // Apostrophes, parens, angle brackets, etc. cannot break out of the
        // quoted value and have always round-tripped fine — escaping them with
        // WSAPI's parser-specific forms (\q, \l, \g) would risk breaking
        // searches that work today on servers that don't honor those forms.
        assertEquals("John's sprint", RallyApiClient.escapeQueryValue("John's sprint"))
        assertEquals("<b>bold</b>", RallyApiClient.escapeQueryValue("<b>bold</b>"))
        assertEquals("a^b{c[d?e(f)g*h", RallyApiClient.escapeQueryValue("a^b{c[d?e(f)g*h"))
    }

    @Test
    fun `escapeQueryValue handles empty string`() {
        assertEquals("", RallyApiClient.escapeQueryValue(""))
    }

    @Test
    fun `escapeQueryValue leaves safe text untouched`() {
        assertEquals(
            "US1234 - Plain Title",
            RallyApiClient.escapeQueryValue("US1234 - Plain Title")
        )
    }

    @Test
    fun `escapeQueryValue keeps quoted sprint names searchable`() {
        // The headline regression of the stripping approach: an iteration named
        // 'Sprint "Phoenix" 12' built a query for 'Sprint Phoenix 12' — zero
        // results for a sprint that has tickets. Escaped, the value round-trips.
        assertEquals(
            "Sprint \\\"Phoenix\\\" 12",
            RallyApiClient.escapeQueryValue("Sprint \"Phoenix\" 12")
        )
    }

    @Test
    fun `escapeQueryValue is a defense for FormattedID lookups`() {
        // A user could paste "US1\" OR (1=1)" into a Find-by-ID field. After the
        // escape, every quote is backslash-escaped, so the surrounding
        // (FormattedID = "...") wrapper still produces a single bound condition.
        assertEquals(
            "US1\\\" OR (1=1)",
            RallyApiClient.escapeQueryValue("US1\" OR (1=1)")
        )
    }

    @Test
    fun `escapeQueryValue neutralizes trailing backslash`() {
        // A lone trailing backslash must not be able to swallow the template's
        // closing quote: it doubles into a literal backslash instead.
        assertEquals("x\\\\", RallyApiClient.escapeQueryValue("x\\"))
    }

    @Test
    fun `escapeQueryValue handles adjacent backslash and quote`() {
        // Backslash-first ordering: the input backslash doubles, then the quote
        // gets its own escape — a reversed implementation would re-escape the
        // backslashes it just introduced.
        assertEquals("a\\\\\\\"b", RallyApiClient.escapeQueryValue("a\\\"b"))
    }

    @Test
    fun `buildSearchQuery uses contains for both Name and FormattedID`() {
        // Regression pin: 'FormattedID contains' was silently changed to '=' once
        // on this branch, breaking partial-ID server search ("1234" stopped
        // finding US1234). The template is load-bearing; keep it pinned.
        assertEquals(
            "((Name contains \"abc\") OR (FormattedID contains \"abc\"))",
            RallyApiClient.buildSearchQuery("abc")
        )
    }

    // ── retryAfterMillis ─────────────────────────────────────────

    @Test
    fun `retryAfterMillis parses delta-seconds`() {
        assertEquals(5000L, RallyApiClient.retryAfterMillis("5"))
    }

    @Test
    fun `retryAfterMillis trims whitespace`() {
        assertEquals(7000L, RallyApiClient.retryAfterMillis(" 7 "))
    }

    @Test
    fun `retryAfterMillis returns null for RFC 7231 http-date`() {
        // CDNs/LBs may send an HTTP-date instead of delta-seconds; this must
        // fall back to exponential backoff, not throw NumberFormatException.
        assertNull(RallyApiClient.retryAfterMillis("Wed, 10 Jun 2026 12:00:00 GMT"))
    }

    @Test
    fun `retryAfterMillis returns null for null header`() {
        assertNull(RallyApiClient.retryAfterMillis(null))
    }

    @Test
    fun `retryAfterMillis returns null for zero and negative values`() {
        assertNull(RallyApiClient.retryAfterMillis("0"))
        assertNull(RallyApiClient.retryAfterMillis("-3"))
    }

    @Test
    fun `retryAfterMillis caps delta-seconds at one day`() {
        assertEquals(86_400_000L, RallyApiClient.retryAfterMillis("90000"))
        // The cap is also what keeps the *1000 conversion from overflowing Long.
        assertEquals(86_400_000L, RallyApiClient.retryAfterMillis("${Long.MAX_VALUE}"))
    }

    // ── buildFieldUpdateBody ─────────────────────────────────────

    @Test
    fun `buildFieldUpdateBody serializes explicit null to clear a field`() {
        // Default Gson (serializeNulls=false) silently drops the name/value
        // pair, turning "clear PlanEstimate" into an empty no-op update body.
        assertEquals(
            """{"HierarchicalRequirement":{"PlanEstimate":null}}""",
            RallyApiClient.buildFieldUpdateBody("HierarchicalRequirement", "PlanEstimate", null)
        )
    }

    @Test
    fun `buildFieldUpdateBody serializes numeric values`() {
        assertEquals(
            """{"HierarchicalRequirement":{"PlanEstimate":8.0}}""",
            RallyApiClient.buildFieldUpdateBody("HierarchicalRequirement", "PlanEstimate", 8.0)
        )
    }

    @Test
    fun `buildFieldUpdateBody serializes string values`() {
        assertEquals(
            """{"Defect":{"Severity":"Major Problem"}}""",
            RallyApiClient.buildFieldUpdateBody("Defect", "Severity", "Major Problem")
        )
    }

    @Test
    fun `decodeBody gunzips when content-encoding is gzip`() {
        val original = """{"QueryResult":{"Results":[]}}"""
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(original.toByteArray(Charsets.UTF_8)) }
        assertEquals(original, RallyApiClient.decodeBody(baos.toByteArray(), "gzip"))
    }

    @Test
    fun `decodeBody is case-insensitive for the encoding token`() {
        val original = "plain"
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(original.toByteArray(Charsets.UTF_8)) }
        assertEquals(original, RallyApiClient.decodeBody(baos.toByteArray(), "GZIP"))
    }

    @Test
    fun `decodeBody passes plain utf8 through when no encoding`() {
        val original = """{"User":{"UserName":"a@b.c"}}"""
        assertEquals(original, RallyApiClient.decodeBody(original.toByteArray(Charsets.UTF_8), null))
    }

    @Test
    fun `decodeBody passes plain utf8 through for identity encoding`() {
        assertEquals("x", RallyApiClient.decodeBody("x".toByteArray(Charsets.UTF_8), "identity"))
    }

    @Test
    fun `decodeBody falls back to raw utf8 when gzip header lies`() {
        // Proxies/LBs sometimes label a plain error body as gzip; the decode must
        // fall back so the status-code error path stays meaningful.
        val notActuallyGzip = """{"QueryResult":{}}"""
        assertEquals(
            notActuallyGzip,
            RallyApiClient.decodeBody(notActuallyGzip.toByteArray(Charsets.UTF_8), "gzip")
        )
    }
}
