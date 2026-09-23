package com.github.halmurat.rally.api

import com.github.halmurat.rally.testutil.FakeRallyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A just-created artifact's Attachments tab can query (and cache) its attachment list before
 * the create flow's upload finishes. The upload path must be able to evict that entry, or the
 * tab shows "Attachments (0)" for the cache TTL even though the upload succeeded.
 */
class RallyApiClientAttachmentsCacheTest {

    private val server = FakeRallyServer()
    private val client = RallyApiClient(server.baseUrl, "fake-key")
    private val attachmentPath = "/slm/webservice/v2.0/attachment"

    @After
    fun tearDown() {
        client.apiExecutor.shutdownNow()
        server.close()
    }

    @Test
    fun `attachment lists are cached per artifact`() {
        client.queryAttachments("US1")
        client.queryAttachments("US1")
        assertEquals(1, server.hitCount(attachmentPath))
    }

    @Test
    fun `clearAttachmentsCache forces the next query back to the server`() {
        client.queryAttachments("US1")
        client.clearAttachmentsCache("US1")
        client.queryAttachments("US1")
        assertEquals(2, server.hitCount(attachmentPath))
    }

    @Test
    fun `a query in flight when the cache is cleared does not re-cache its stale result`() {
        // The detail panel's pre-upload query can still be on the wire when the upload
        // finishes and clears the cache; its (empty) answer must not be stored afterwards.
        val requestArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.route(attachmentPath) {
            requestArrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            FakeRallyServer.Reply(200, FakeRallyServer.EMPTY_QUERY_RESULT)
        }
        val inFlight = CompletableFuture.supplyAsync { client.queryAttachments("US1") }
        requestArrived.await(10, TimeUnit.SECONDS)

        client.clearAttachmentsCache("US1")
        release.countDown()
        inFlight.get(10, TimeUnit.SECONDS)

        client.queryAttachments("US1")
        assertEquals(2, server.hitCount(attachmentPath))
    }
}
