package com.github.halmuratuyghur.rally.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RallyApiExceptionTest {

    @Test
    fun `toString does not leak response body`() {
        // Rally 401/403 bodies frequently echo session tokens. The original
        // toString concatenated responseBody, which then ended up in idea.log
        // via LOG.error("...", e). Only the message and status code should
        // appear in toString output now.
        val secretBody = """{"error": "expired", "session": "abc123-secret-token"}"""
        val ex = RallyApiException("Auth failed", 401, secretBody)
        val s = ex.toString()
        assertTrue("toString should mention the message", s.contains("Auth failed"))
        assertTrue("toString should mention the status code", s.contains("401"))
        assertFalse("toString should NOT contain the response body", s.contains("abc123-secret-token"))
        assertFalse("toString should NOT contain the secret session", s.contains("session"))
    }

    @Test
    fun `responseBody is still accessible programmatically`() {
        // The body is not in toString, but callers that explicitly want it for
        // diagnostics should still be able to read it via the field.
        val ex = RallyApiException("Bad request", 400, "missing field X")
        assertEquals("missing field X", ex.responseBody)
    }

    @Test
    fun `simple message constructor leaves status and body null`() {
        val ex = RallyApiException("Generic failure")
        assertNull(ex.statusCode)
        assertNull(ex.responseBody)
    }

    @Test
    fun `RallyConnectionException without cause has null cause`() {
        // The previous constructor wrapped a synthetic Exception when no cause
        // was given, which made callers walking exception.cause see a phantom
        // "Failed after N retries" inside RallyApiException — looking like a
        // real underlying error. Now cause should genuinely be null.
        val ex = RallyConnectionException("Failed after 3 retries")
        assertNull("cause should be null when none was supplied", ex.cause)
    }

    @Test
    fun `RallyConnectionException with cause preserves the underlying throwable`() {
        val underlying = java.net.SocketTimeoutException("connect timed out")
        val ex = RallyConnectionException("Failed to connect", underlying)
        assertSame("cause should be the supplied exception", underlying, ex.cause)
    }

    @Test
    fun `RallyAuthenticationException defaults to 401`() {
        val ex = RallyAuthenticationException("Bad token")
        assertEquals(401, ex.statusCode)
    }

    @Test
    fun `RallySecurityException is a RallyApiException`() {
        // requireSameHost throws RallySecurityException; the catch handlers
        // upstream catch RallyApiException, so the inheritance is part of the
        // contract.
        val ex = RallySecurityException("blocked host")
        assertNotNull("must be assignable to RallyApiException", ex as RallyApiException)
    }
}
