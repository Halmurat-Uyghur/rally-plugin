package com.github.halmurat.rally.api

import com.github.halmurat.rally.testutil.FakeRallyServer
import com.github.halmurat.rally.ui.loadErrorLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failures keep the type that tells the user what to fix: a rejected API key stays an
 * authentication failure through the parallel list query, and Rally's HTTP-200-with-Errors
 * replies to the user and workspace lookups surface as errors, not as "user not found".
 */
class RallyApiClientErrorSurfacingTest {

    private val rally = FakeRallyServer()
    private val client = RallyApiClient(rally.baseUrl, "fake-key")

    private val queryError =
        """{"QueryResult":{"Errors":["Could not read: invalid workspace"],"Warnings":[],"TotalResultCount":0,"Results":[]}}"""

    @After
    fun tearDown() {
        client.apiExecutor.shutdownNow()
        rally.close()
    }

    @Test
    fun `a rejected API key fails the list load as an authentication failure`() {
        rally.route("/slm/webservice/v2.0/hierarchicalrequirement", 401, "")
        rally.route("/slm/webservice/v2.0/defect", 401, "")

        assertThrows(RallyAuthenticationException::class.java) { client.queryAllArtifactsParallel() }
    }

    @Test
    fun `exhausted rate limiting keeps its status through the parallel list query`() {
        val paths = listOf("/slm/webservice/v2.0/hierarchicalrequirement", "/slm/webservice/v2.0/defect")
        paths.forEach { path ->
            rally.route(path) { FakeRallyServer.Reply(429, "Too many requests", mapOf("Retry-After" to "1")) }
        }

        val error = assertThrows(RallyApiException::class.java) { client.queryAllArtifactsParallel() }

        assertEquals("Rate limited", loadErrorLabel(error))
        assertEquals(429, error.statusCode)
        paths.forEach { assertEquals("Initial request plus three retries", 4, rally.hitCount(it)) }
    }

    @Test
    fun `other failures of both types still read as one combined query failure`() {
        rally.route("/slm/webservice/v2.0/hierarchicalrequirement", 200, queryError)
        rally.route("/slm/webservice/v2.0/defect", 200, queryError)

        val e = assertThrows(RallyApiException::class.java) { client.queryAllArtifactsParallel() }
        assertTrue(e.message, e.message!!.startsWith("Query failed - User stories query failed"))
        assertTrue(e.message, e.message!!.contains("Defects query failed"))
    }

    @Test
    fun `a user lookup answered with Errors is an error, not user-not-found`() {
        rally.route("/slm/webservice/v2.0/user", 200, queryError)

        val e = assertThrows(RallyApiException::class.java) { client.getUserByUsername("me@example.com") }
        assertFalse(e is RallyUserNotFoundException)
        assertTrue(e.message, e.message!!.contains("invalid workspace"))
    }

    @Test
    fun `a user lookup with no results is user-not-found`() {
        assertThrows(RallyUserNotFoundException::class.java) { client.getUserByUsername("nobody@example.com") }
    }

    @Test
    fun `workspace check returns the name, and fails on an unreadable workspace`() {
        rally.route("/slm/webservice/v2.0/workspace/111", 200, """{"Workspace":{"Name":"Team WS"}}""")
        rally.route("/slm/webservice/v2.0/workspace/999", 200, FakeRallyServer.OBJECT_NOT_FOUND)

        assertEquals("Team WS", client.getWorkspaceName("111"))
        val e = assertThrows(RallyApiException::class.java) { client.getWorkspaceName("999") }
        assertTrue(e.message, e.message!!.contains("Cannot find object to read"))
    }
}
