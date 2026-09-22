package com.github.halmurat.rally.api

import com.github.halmurat.rally.testutil.FakeRallyServer
import com.github.halmurat.rally.testutil.FakeRallyServer.Reply
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.util.Base64

/**
 * Proxy authentication and how Rally 401s surface once an Authenticator is attached to the
 * HttpClient. With an Authenticator present, the JDK throws IOException for a Basic challenge
 * it can't answer (or a 401 without a challenge header) instead of returning the response, so
 * the client must map those to non-retried authentication failures instead of retrying them
 * as connection errors.
 */
class RallyApiClientAuthTest {

    private val rally = FakeRallyServer()
    private val closeables = mutableListOf<AutoCloseable>(rally)
    private val clients = mutableListOf<RallyApiClient>()
    private val originalProxySelector: ProxySelector? = ProxySelector.getDefault()

    private val storyPath = "/slm/webservice/v2.0/hierarchicalrequirement/1"
    private val found = """{"HierarchicalRequirement":{"Description":"<p>ok</p>"}}"""

    @After
    fun tearDown() {
        Authenticator.setDefault(null)
        ProxySelector.setDefault(originalProxySelector)
        clients.forEach { it.apiExecutor.shutdownNow() }
        closeables.forEach { it.close() }
    }

    private fun newClient(): RallyApiClient =
        RallyApiClient(rally.baseUrl, "fake-key").also { clients += it }

    /** Route every request from clients built after this call through [proxy]. */
    private fun useProxy(proxy: FakeRallyServer) {
        val address = InetSocketAddress("127.0.0.1", java.net.URI(proxy.baseUrl).port)
        ProxySelector.setDefault(ProxySelector.of(address))
    }

    /** Stand-in for the IDE's own proxy authenticator (what Authenticator.getDefault() is in the IDE). */
    private fun ideAuthenticatorAnswering(user: String, password: String) {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, password.toCharArray())
        })
    }

    private fun basic(user: String, password: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    @Test
    fun `Rally 401 without a challenge header is an authentication failure and is not retried`() {
        rally.route(storyPath, 401, "")
        val client = newClient()

        assertThrows(RallyAuthenticationException::class.java) {
            client.fetchDescriptionStrict(rally.apiBase + "/hierarchicalrequirement/1")
        }
        assertEquals(1, rally.hitCount(storyPath))
    }

    @Test
    fun `Rally 401 with a Basic challenge is an authentication failure and is not retried`() {
        rally.route(storyPath) { Reply(401, "", mapOf("WWW-Authenticate" to "Basic realm=\"Rally ALM\"")) }
        val client = newClient()

        assertThrows(RallyAuthenticationException::class.java) {
            client.fetchDescriptionStrict(rally.apiBase + "/hierarchicalrequirement/1")
        }
        assertEquals(1, rally.hitCount(storyPath))
    }

    @Test
    fun `Rally's own challenges never get the IDE's credentials`() {
        // Only PROXY challenges are delegated: a Basic challenge from Rally must not be
        // answered with whatever the IDE's default authenticator would hand out.
        rally.route(storyPath) { exchange ->
            if (exchange.requestHeaders.containsKey("Authorization")) Reply(200, found)
            else Reply(401, "", mapOf("WWW-Authenticate" to "Basic realm=\"Rally ALM\""))
        }
        ideAuthenticatorAnswering("alice", "s3cret")
        val client = newClient()

        assertThrows(RallyAuthenticationException::class.java) {
            client.fetchDescriptionStrict(rally.apiBase + "/hierarchicalrequirement/1")
        }
        assertEquals(1, rally.hitCount(storyPath))
    }

    @Test
    fun `declined proxy credentials read as a proxy failure, not a bad API key`() {
        // Cancelled IDE proxy prompt / no stored proxy credentials: the IDE authenticator
        // returns null and the JDK throws "No credentials provided" — the same message as for
        // an unanswered Rally challenge, so the client must remember which side declined.
        val proxy = FakeRallyServer().also { closeables += it }
        proxy.route(storyPath) { Reply(407, "", mapOf("Proxy-Authenticate" to "Basic realm=\"corp\"")) }
        useProxy(proxy)
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? = null
        })
        val client = newClient()

        val e = assertThrows(RallyApiException::class.java) {
            client.fetchDescriptionStrict(rally.apiBase + "/hierarchicalrequirement/1")
        }
        assertFalse("reported as ${e.javaClass.simpleName}", e is RallyAuthenticationException)
        assertTrue(e.message, e.message!!.contains("proxy", ignoreCase = true))
    }

    @Test
    fun `authenticating proxy gets the IDE's stored proxy credentials`() {
        val proxy = FakeRallyServer().also { closeables += it }
        proxy.route(storyPath) { exchange ->
            if (exchange.requestHeaders.getFirst("Proxy-Authorization") == basic("alice", "s3cret")) Reply(200, found)
            else Reply(407, "", mapOf("Proxy-Authenticate" to "Basic realm=\"corp\""))
        }
        useProxy(proxy)
        ideAuthenticatorAnswering("alice", "s3cret")
        val client = newClient()

        assertEquals("<p>ok</p>", client.fetchDescriptionStrict(rally.apiBase + "/hierarchicalrequirement/1"))
    }

    @Test
    fun `rejected proxy credentials fail once instead of being retried`() {
        // A stale proxy password must not be replayed by our retry loop on top of the
        // JDK's own auth retries — that multiplies failed logins (account-lockout risk).
        val proxy = FakeRallyServer().also { closeables += it }
        proxy.route(storyPath) { Reply(407, "", mapOf("Proxy-Authenticate" to "Basic realm=\"corp\"")) }
        useProxy(proxy)
        ideAuthenticatorAnswering("alice", "stale")
        val client = newClient()

        val e = assertThrows(RallyApiException::class.java) {
            client.fetchDescriptionStrict(rally.apiBase + "/hierarchicalrequirement/1")
        }
        assertTrue(e.message, e.message!!.contains("proxy", ignoreCase = true))
        // One exchange only (the JDK's own attempts: 5 requests with the default retry limit),
        // not one per retry-loop attempt (4 exchanges = 20 requests before the fix).
        assertTrue("proxy saw ${proxy.hitCount(storyPath)} requests", proxy.hitCount(storyPath) <= 5)
    }
}
