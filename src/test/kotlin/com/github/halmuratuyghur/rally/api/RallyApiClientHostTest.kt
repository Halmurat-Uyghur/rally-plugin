package com.github.halmuratuyghur.rally.api

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [RallyApiClient.requireSameHost] — the SSRF guard that stops a poisoned
 * `_ref`/attachment URL from leaking the zsessionid API key to another host or
 * downgrading https → http.
 *
 * Constructing the client touches IntelliJ's Logger (returns a DefaultLogger headlessly)
 * and HttpConfigurable (NPEs without an Application, but the constructor swallows that and
 * falls back to a direct connection), so the client builds fine in a plain JUnit JVM.
 */
class RallyApiClientHostTest {

    private val client = RallyApiClient("https://rally1.rallydev.com", "fake-key")

    @Test
    fun `same host and scheme is allowed`() {
        client.requireSameHost("https://rally1.rallydev.com/slm/webservice/v2.0/hierarchicalrequirement/123")
    }

    @Test
    fun `relative url with no host is allowed`() {
        client.requireSameHost("/slm/attachment/456/screenshot.png")
    }

    @Test
    fun `different host is refused`() {
        assertThrows(RallySecurityException::class.java) {
            client.requireSameHost("https://attacker.example.com/slm/webservice/v2.0/user")
        }
    }

    @Test
    fun `https to http downgrade is refused`() {
        assertThrows(RallySecurityException::class.java) {
            client.requireSameHost("http://rally1.rallydev.com/slm/webservice/v2.0/user")
        }
    }

    @Test
    fun `malformed url is refused`() {
        assertThrows(RallySecurityException::class.java) {
            client.requireSameHost("ht!tp://%%%not-a-url")
        }
    }

    @Test
    fun `host comparison is case-insensitive`() {
        // Same host with different casing must be treated as the same host, not refused.
        client.requireSameHost("https://RALLY1.RallyDev.COM/slm/webservice/v2.0/defect/1")
    }
}
