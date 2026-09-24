package com.github.halmurat.rally.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The partial-failure balloon. Notification content is HTML in the IDE (it is built with
 * HtmlChunk.raw), and the failure reasons carry server text — exception messages quoting Rally's
 * error bodies — so markup in them must show as text, not render (or load an `<img>`).
 */
class RallyApiClientPartialFailureNotificationTest {

    private val client = RallyApiClient("https://rally1.rallydev.com", "fake-key")

    @After
    fun tearDown() {
        client.apiExecutor.shutdownNow()
    }

    @Test
    fun `failure reasons are escaped, one per line`() {
        val content = client.partialFailureNotificationContent(
            listOf(
                "Defects query failed: <img src='https://x.invalid/p.png'>",
                "User stories query failed: a & b",
            )
        )
        assertEquals(
            "Some results couldn't be loaded:<br>" +
                "Defects query failed: &lt;img src='https://x.invalid/p.png'&gt;<br>" +
                "User stories query failed: a &amp; b",
            content
        )
        assertFalse(content.contains("<img"))
    }
}
