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
}
