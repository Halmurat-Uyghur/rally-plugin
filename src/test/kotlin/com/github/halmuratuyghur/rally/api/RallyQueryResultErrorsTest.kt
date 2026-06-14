package com.github.halmuratuyghur.rally.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [requireNoErrors] (HIGH-1) and [ArtifactQueryResult] (MED-8).
 *
 * HIGH-1: Rally WSAPI 2.0 returns HTTP 200 with a populated Errors array for field /
 * permission / scoping errors. Before this fix every direct-query method read `safeResults`
 * directly and silently returned an empty list. [requireNoErrors] turns that into a thrown
 * exception so the UI can surface it instead of showing a misleading "(0)".
 */
class RallyQueryResultErrorsTest {

    @Test
    fun `requireNoErrors passes when errors is null`() {
        QueryResultData<RallyUserStory>(results = emptyList(), errors = null).requireNoErrors("tasks")
    }

    @Test
    fun `requireNoErrors passes when errors is empty`() {
        QueryResultData<RallyUserStory>(results = emptyList(), errors = emptyList()).requireNoErrors("tasks")
    }

    @Test
    fun `requireNoErrors throws when errors is non-empty`() {
        val ex = assertThrows(RallyApiException::class.java) {
            QueryResultData<RallyUserStory>(errors = listOf("Could not read field Foo")).requireNoErrors("test cases")
        }
        // Context + the server error message are both surfaced for diagnosis.
        assertTrue(ex.message!!.contains("test cases"))
        assertTrue(ex.message!!.contains("Could not read field Foo"))
    }

    @Test
    fun `requireNoErrors joins multiple errors`() {
        val ex = assertThrows(RallyApiException::class.java) {
            QueryResultData<RallyDefect>(errors = listOf("E1", "E2")).requireNoErrors("iterations")
        }
        assertTrue(ex.message!!.contains("E1"))
        assertTrue(ex.message!!.contains("E2"))
    }

    // ── ArtifactQueryResult (MED-8) ──────────────────────────────

    @Test
    fun `ArtifactQueryResult is not partial without reasons`() {
        val r = ArtifactQueryResult(listOf(RallyUserStory(formattedID = "US1")))
        assertFalse(r.isPartial)
        assertEquals(1, r.artifacts.size)
    }

    @Test
    fun `ArtifactQueryResult is partial when a reason is present`() {
        val r = ArtifactQueryResult(emptyList(), listOf("Defects query failed: timeout"))
        assertTrue(r.isPartial)
        assertEquals(listOf("Defects query failed: timeout"), r.partialFailureReasons)
    }
}
