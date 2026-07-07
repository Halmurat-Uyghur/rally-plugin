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
