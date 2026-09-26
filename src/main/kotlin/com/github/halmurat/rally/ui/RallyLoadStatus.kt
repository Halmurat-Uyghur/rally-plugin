package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyApiException
import com.github.halmurat.rally.api.RallyAuthenticationException
import com.github.halmurat.rally.api.RallyConnectionException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Status line for the ticket list (M3). [shownCount] is the currently displayed
 * (scope/state/search-filtered) count; [fetchedCount] is how many rows the last
 * load actually fetched; [totalAvailable] is Rally's TotalResultCount summed across
 * the queried types (-1 when unknown, e.g. server-search results). When the
 * workspace holds more than the fetch cap the line reads "200 of 934 loaded"
 * instead of silently implying completeness; a partial failure appends the
 * existing "(incomplete)" marker. Pure and top-level so it is unit-testable.
 */
internal fun buildLoadedStatusText(
    shownCount: Int,
    fetchedCount: Int,
    totalAvailable: Int,
    incomplete: Boolean
): String {
    val truncated = totalAvailable > fetchedCount
    val base = if (truncated) "$shownCount of $totalAvailable loaded" else "$shownCount loaded"
    return if (incomplete) "$base (incomplete)" else base
}

/** What a State-filter change does — see [stateChangeAction]. */
internal enum class StateChangeAction {
    /** Re-filter the loaded list in memory and re-render the "N loaded" status. */
    REFILTER,
    /** Re-filter, but leave the "Loading…" status for the in-flight load to replace. */
    REFILTER_KEEP_STATUS,
    /** The last load failed or never ran: re-filter what's shown and run the load again. */
    RELOAD,
}

/**
 * The State filter is applied client-side, so a State change normally re-filters the loaded
 * list with no network round trip (P6). But when the last load failed, never ran, or Rally
 * isn't configured, re-filtering alone would replace the error or "Not configured" status with
 * "0 loaded" (a bogus empty success) and never retry. In that case the rows on screen (possibly
 * from an earlier successful load) are re-filtered and the load runs again, as a State change
 * did before P6 (loadTickets() re-shows "Not configured" itself). While a load is in flight,
 * its completion re-renders the status.
 */
internal fun stateChangeAction(lastLoadSucceeded: Boolean, loading: Boolean): StateChangeAction = when {
    loading -> StateChangeAction.REFILTER_KEEP_STATUS
    !lastLoadSucceeded -> StateChangeAction.RELOAD
    else -> StateChangeAction.REFILTER
}

/**
 * Short status-bar label for a failed ticket load. Classified by exception type, not by
 * message text: the client's auth failures read "Authentication failed…" (no "401" in them),
 * and its connection failures wrap the JDK exception in [RallyConnectionException].
 */
internal fun loadErrorLabel(error: Throwable): String {
    val cause = if (error is ExecutionException || error is CompletionException) error.cause ?: error else error
    return when {
        cause is RallyAuthenticationException -> "Auth error"
        (cause as? RallyApiException)?.statusCode == 429 -> "Rate limited"
        cause is HttpTimeoutException || cause.cause is HttpTimeoutException -> "Timeout"
        cause is RallyConnectionException || cause is ConnectException || cause is UnknownHostException -> "Network error"
        else -> "Error"
    }
}
