package com.github.halmuratuyghur.rally.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [RallyApiClient]'s companion-object helpers — pure functions that
 * don't require constructing a client (which would touch IntelliJ's Logger and
 * HttpConfigurable services and fail outside a platform-test fixture).
 */
class RallyApiClientCompanionTest {

    @Test
    fun `escapeQueryValue strips double quotes`() {
        assertEquals(
            "Story with quotes",
            RallyApiClient.escapeQueryValue("Story \"with\" quotes")
        )
    }

    @Test
    fun `escapeQueryValue strips backslashes`() {
        assertEquals(
            "pathfile",
            RallyApiClient.escapeQueryValue("path\\file")
        )
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
    fun `escapeQueryValue strips both quotes and backslashes together`() {
        // A mixed payload that previously could escape out of a "(Field = \"$value\")"
        // query template — no quotes or backslashes survive the escape.
        val escaped = RallyApiClient.escapeQueryValue("a\\b\"c\\d\"e")
        assertEquals("abcde", escaped)
    }

    @Test
    fun `escapeQueryValue is a defense for FormattedID lookups`() {
        // A user could paste "US1\" OR (1=1)" into a Find-by-ID field. After the
        // escape, no quote remains, so the surrounding (FormattedID = "...")
        // wrapper still produces a single bound condition.
        val escaped = RallyApiClient.escapeQueryValue("US1\" OR (1=1)")
        assertEquals("US1 OR (1=1)", escaped)
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
}
