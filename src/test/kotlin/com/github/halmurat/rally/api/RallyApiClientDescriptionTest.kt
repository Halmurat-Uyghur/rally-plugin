package com.github.halmurat.rally.api

import com.github.halmurat.rally.testutil.FakeRallyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The detail panel's [RallyApiClient.fetchDescription] must tell "no description" apart from
 * "the fetch failed": a failure is thrown (and not cached) so the panel can say it couldn't
 * load the description instead of showing "No description". The failure cases answer 500:
 * it is outside the retryable statuses, so they don't sleep through backoff.
 */
class RallyApiClientDescriptionTest {

    private val rally = FakeRallyServer()
    private val client = RallyApiClient(rally.baseUrl, "fake-key")

    private val storyPath = "/slm/webservice/v2.0/hierarchicalrequirement/1"
    private val storyRef = rally.apiBase + "/hierarchicalrequirement/1"

    @After
    fun tearDown() {
        client.apiExecutor.shutdownNow()
        rally.close()
    }

    @Test
    fun `returns the description`() {
        rally.route(storyPath, 200, """{"HierarchicalRequirement":{"Description":"<p>ok</p>"}}""")
        assertEquals("<p>ok</p>", client.fetchDescription(storyRef))
    }

    @Test
    fun `returns null and caches when the artifact has no description`() {
        rally.route(storyPath, 200, """{"HierarchicalRequirement":{"Description":null}}""")
        assertNull(client.fetchDescription(storyRef))
        assertNull(client.fetchDescription(storyRef))
        assertEquals(1, rally.hitCount(storyPath))
    }

    @Test
    fun `throws on an HTTP error and does not cache the failure`() {
        rally.route(storyPath, 500, """{"error":"boom"}""")
        assertThrows(RallyApiException::class.java) { client.fetchDescription(storyRef) }

        rally.route(storyPath, 200, """{"HierarchicalRequirement":{"Description":"<p>back</p>"}}""")
        assertEquals("<p>back</p>", client.fetchDescription(storyRef))
        assertEquals(2, rally.hitCount(storyPath))
    }

    @Test
    fun `a rejected API key surfaces as an authentication failure`() {
        // The detail panel shows its "check your API key" message only for this subtype.
        rally.route(storyPath, 401, "")
        assertThrows(RallyAuthenticationException::class.java) { client.fetchDescription(storyRef) }
        assertEquals(1, rally.hitCount(storyPath))
    }

    @Test
    fun `throws when the artifact no longer exists`() {
        rally.route(storyPath, 200, FakeRallyServer.OBJECT_NOT_FOUND)
        assertThrows(RallyApiException::class.java) { client.fetchDescription(storyRef) }
    }
}
