package com.github.halmurat.rally.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import com.github.halmurat.rally.api.RallyConnectionException
import com.github.halmurat.rally.api.RallyAuthenticationException
import com.github.halmurat.rally.api.RallyApiException

/**
 * Pins the "N loaded" status line (M3): the server-side total appears whenever the
 * fetch was truncated at page size, so a capped list can't masquerade as complete.
 */
class RallyLoadStatusTest {

    @Test
    fun `complete load shows plain count`() {
        assertEquals("200 loaded", buildLoadedStatusText(200, 200, 200, incomplete = false))
    }

    @Test
    fun `truncated load shows shown of total`() {
        assertEquals("200 of 934 loaded", buildLoadedStatusText(200, 200, 934, incomplete = false))
    }

    @Test
    fun `truncated load with client-side filter keeps the total`() {
        assertEquals("37 of 934 loaded", buildLoadedStatusText(37, 200, 934, incomplete = false))
    }

    @Test
    fun `unknown total (-1) shows plain count`() {
        assertEquals("50 loaded", buildLoadedStatusText(50, 50, -1, incomplete = false))
    }

    @Test
    fun `partial failure appends incomplete marker`() {
        assertEquals("10 loaded (incomplete)", buildLoadedStatusText(10, 10, -1, incomplete = true))
        assertEquals("10 of 30 loaded (incomplete)", buildLoadedStatusText(10, 10, 30, incomplete = true))
    }

    @Test
    fun `empty result shows zero loaded`() {
        assertEquals("0 loaded", buildLoadedStatusText(0, 0, 0, incomplete = false))
    }

    // ── State-filter change (client-side filter, P6) ─────────────

    @Test
    fun `state change after a successful load re-filters in memory`() {
        assertEquals(StateChangeAction.REFILTER, stateChangeAction(lastLoadSucceeded = true, loading = false))
    }

    @Test
    fun `state change after a failed or never-run load reloads instead of showing an empty result`() {
        // Re-filtering an empty list would replace "Network error" / "Not configured" with
        // "0 loaded" — a bogus successful-but-empty result — and never retry.
        assertEquals(StateChangeAction.RELOAD, stateChangeAction(lastLoadSucceeded = false, loading = false))
    }

    @Test
    fun `state change during a load re-filters but leaves the Loading status alone`() {
        assertEquals(StateChangeAction.REFILTER_KEEP_STATUS, stateChangeAction(lastLoadSucceeded = true, loading = true))
        assertEquals(StateChangeAction.REFILTER_KEEP_STATUS, stateChangeAction(lastLoadSucceeded = false, loading = true))
    }

    // ── loadErrorLabel ───────────────────────────────────────────

    @Test
    fun `auth failure is labeled by type although its message has no status code`() {
        assertEquals("Auth error", loadErrorLabel(RallyAuthenticationException("Authentication failed. Please check your API key.")))
    }

    @Test
    fun `connection failures and raw network exceptions are network errors`() {
        assertEquals("Network error", loadErrorLabel(RallyConnectionException("Failed to connect to Rally server: null")))
        assertEquals("Network error", loadErrorLabel(java.net.UnknownHostException("rally.example")))
    }

    @Test
    fun `a timed-out request is a timeout, also when wrapped`() {
        val timeout = RallyConnectionException("Failed to connect", java.net.http.HttpTimeoutException("request timed out"))
        assertEquals("Timeout", loadErrorLabel(timeout))
        assertEquals("Timeout", loadErrorLabel(java.util.concurrent.ExecutionException(timeout)))
    }

    @Test
    fun `an exhausted 429 is rate limited and anything else is a plain error`() {
        assertEquals("Rate limited", loadErrorLabel(RallyApiException("Rally API request failed with status 429", 429, null)))
        assertEquals("Error", loadErrorLabel(RallyApiException("Rally query error (paged query): bad field")))
    }
}
