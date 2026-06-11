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

    @Test
    fun `same host on a different port is refused`() {
        // Host+scheme alone are not enough: a poisoned _ref or inline-image URL
        // pointing at another port of the same (shared, on-prem) host would still
        // receive the zsessionid API key.
        assertThrows(RallySecurityException::class.java) {
            client.requireSameHost("https://rally1.rallydev.com:8444/slm/webservice/v2.0/user")
        }
    }

    @Test
    fun `explicit default port on same host is allowed`() {
        // :443 on an https server URL with no explicit port is the same endpoint.
        client.requireSameHost("https://rally1.rallydev.com:443/slm/webservice/v2.0/defect/1")
    }

    @Test
    fun `scheme-relative url on the same host is allowed`() {
        // No scheme means the effective port falls back to the configured
        // scheme's default — removing that fallback would break these refs.
        client.requireSameHost("//rally1.rallydev.com/slm/attachment/1/a.png")
    }

    @Test
    fun `http client uses port 80 as its default`() {
        val plainClient = RallyApiClient("http://rally.internal.example", "fake-key")
        plainClient.requireSameHost("http://rally.internal.example:80/slm/webservice/v2.0/user")
        assertThrows(RallySecurityException::class.java) {
            plainClient.requireSameHost("http://rally.internal.example:8080/slm/webservice/v2.0/user")
        }
    }

    @Test
    fun `client configured with explicit port accepts that port and refuses the default`() {
        val portClient = RallyApiClient("https://rally.corp.example:8443", "fake-key")
        portClient.requireSameHost("https://rally.corp.example:8443/slm/webservice/v2.0/user")
        assertThrows(RallySecurityException::class.java) {
            // No explicit port resolves to 443 — a different endpoint than :8443.
            portClient.requireSameHost("https://rally.corp.example/slm/webservice/v2.0/user")
        }
    }
}
