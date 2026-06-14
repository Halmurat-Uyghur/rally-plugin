package com.github.halmurat.rally.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RallyApiClient]'s instance helpers that don't make network calls:
 * - [RallyApiClient.normalizeRef] (ref-form normalization)
 * - the reentrant bulk-mode depth counter (MED-7)
 * - the consolidated OperationResult/CreateResult JSON parsers (MED-11)
 *
 * Constructing the client is safe headlessly (see [RallyApiClientHostTest] — the constructor
 * swallows the HttpConfigurable NPE and the Logger returns a DefaultLogger).
 */
class RallyApiClientResultParsingTest {

    private val client = RallyApiClient("https://rally1.rallydev.com", "fake-key")

    // ── normalizeRef ─────────────────────────────────────────────

    @Test
    fun `normalizeRef passes a full URL through unchanged`() {
        val url = "https://rally1.rallydev.com/slm/webservice/v2.0/defect/123"
        assertEquals(url, client.normalizeRef("defect", url))
    }

    @Test
    fun `normalizeRef expands a leading-slash ref path`() {
        val out = client.normalizeRef("defect", "/defect/456")
        assertTrue(out.startsWith("https://rally1.rallydev.com/slm/webservice/"))
        assertTrue(out.endsWith("/defect/456"))
    }

    @Test
    fun `normalizeRef expands a bare id using the type`() {
        val out = client.normalizeRef("hierarchicalrequirement", "789")
        assertTrue(out.startsWith("https://rally1.rallydev.com/slm/webservice/"))
        assertTrue(out.endsWith("/hierarchicalrequirement/789"))
    }

    // ── bulk-mode depth (MED-7) ──────────────────────────────────

    @Test
    fun `bulk mode is reentrant and floors at zero`() {
        assertFalse(client.bulkModeActive())
        client.enterBulkMode()
        assertTrue(client.bulkModeActive())
        // Nested enter/exit must keep it active until the outermost exit.
        client.enterBulkMode()
        client.exitBulkMode()
        assertTrue(client.bulkModeActive())
        client.exitBulkMode()
        assertFalse(client.bulkModeActive())
        // An extra exit must not drive the depth negative (and re-activate on the next enter).
        client.exitBulkMode()
        assertFalse(client.bulkModeActive())
        client.enterBulkMode()
        assertTrue(client.bulkModeActive())
        client.exitBulkMode()
        assertFalse(client.bulkModeActive())
    }

    // ── checkOperationResult (MED-11) ────────────────────────────

    @Test
    fun `checkOperationResult passes when Errors is empty`() {
        client.checkOperationResult("""{"OperationResult":{"Errors":[]}}""", "update state")
    }

    @Test
    fun `checkOperationResult throws on a non-empty Errors array`() {
        val ex = assertThrows(RallyApiException::class.java) {
            client.checkOperationResult("""{"OperationResult":{"Errors":["Not authorized"]}}""", "update state")
        }
        assertTrue(ex.message!!.contains("update state"))
        assertTrue(ex.message!!.contains("Not authorized"))
    }

    @Test
    fun `checkOperationResult throws when the wrapper is missing`() {
        assertThrows(RallyApiException::class.java) {
            client.checkOperationResult("""{"Something":{}}""", "update state")
        }
    }

    // ── parseCreateResult / parseCreateResultObject (MED-11) ─────

    @Test
    fun `parseCreateResultObject returns the created Object`() {
        val obj = client.parseCreateResultObject(
            """{"CreateResult":{"Errors":[],"Object":{"FormattedID":"US42"}}}""",
            "create user story"
        )
        assertEquals("US42", obj.get("FormattedID").asString)
    }

    @Test
    fun `parseCreateResult deserializes the Object into the requested type`() {
        val story = client.parseCreateResult<RallyUserStory>(
            """{"CreateResult":{"Errors":[],"Object":{"_ref":"/hierarchicalrequirement/1","FormattedID":"US42"}}}""",
            "create user story"
        )
        assertEquals("US42", story.formattedID)
        assertEquals("/hierarchicalrequirement/1", story.ref)
    }

    @Test
    fun `parseCreateResult throws on a non-empty Errors array`() {
        val ex = assertThrows(RallyApiException::class.java) {
            client.parseCreateResult<RallyUserStory>(
                """{"CreateResult":{"Errors":["Name is required"],"Object":{}}}""",
                "create user story"
            )
        }
        assertTrue(ex.message!!.contains("create user story"))
        assertTrue(ex.message!!.contains("Name is required"))
    }

    @Test
    fun `parseCreateResultObject throws when CreateResult or Object is missing`() {
        assertThrows(RallyApiException::class.java) {
            client.parseCreateResultObject("""{"Nope":{}}""", "create defect")
        }
        assertThrows(RallyApiException::class.java) {
            client.parseCreateResultObject("""{"CreateResult":{"Errors":[]}}""", "create defect")
        }
    }
}
