package com.github.halmurat.rally.ui

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
    /** Nothing valid to re-filter: run the load again. */
    RELOAD,
}

/**
 * The State filter is applied client-side, so a State change normally re-filters the loaded
 * list with no network round trip (P6). But when the last load failed, never ran, or Rally
 * isn't configured, there is no list to re-filter: doing so would replace the error or
 * "Not configured" message with "0 loaded" (a bogus empty success) and never retry. In that
 * case the load runs again, as a State change did before P6 (loadTickets() re-shows
 * "Not configured" itself). While a load is in flight, its completion re-renders the status.
 */
internal fun stateChangeAction(lastLoadSucceeded: Boolean, loading: Boolean): StateChangeAction = when {
    loading -> StateChangeAction.REFILTER_KEEP_STATUS
    !lastLoadSucceeded -> StateChangeAction.RELOAD
    else -> StateChangeAction.REFILTER
}
