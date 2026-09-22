package com.github.halmurat.rally.api

import com.github.halmurat.rally.testutil.FakeRallyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
