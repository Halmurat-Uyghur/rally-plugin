package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyApiClient
import com.github.halmurat.rally.api.RallyArtifact

/**
 * Single source of truth for the tool-window filter display strings (MED-13).
 *
 * Scope and state were previously passed around as untyped magic strings (e.g.
 * `"My Tickets"`, `"In-Progress"`) duplicated between the combo-box arrays and
 * the branching logic in [RallyToolWindowPanel]. These enums centralize those
 * strings so the panel derives its combo arrays from `entries.map { it.displayName }`
 * and branches on the typed enum (via [Scope.fromDisplay] / [StateFilter.fromDisplay])
 * instead of comparing raw strings.
 *
 * CRITICAL: each [displayName] must byte-match the current SCOPE_OPTIONS /
 * STATE_OPTIONS arrays exactly. The selected combo item is matched back to an
 * enum by string equality, so any drift (extra space, different casing/hyphen)
 * would silently break the corresponding UI filter.
 */
enum class Scope(val displayName: String) {
    ALL_TICKETS("All Tickets"),
    MY_TICKETS("My Tickets"),
    USER_STORIES("User Stories"),
    DEFECTS("Defects"),
    TEST_CASES("Test Cases"),
    RECENT_ACTIVITY("Recent Activity");

    companion object {
        fun fromDisplay(s: String?): Scope? = entries.firstOrNull { it.displayName == s }
    }
}

enum class StateFilter(val displayName: String) {
    ANY("Any State"),
    IDEA("Idea"),
    DEFINED("Defined"),
    IN_PROGRESS("In-Progress"),
    COMPLETED("Completed"),
    ACCEPTED("Accepted"),
    ACTIVE("Active");

    companion object {
        fun fromDisplay(s: String?): StateFilter? = entries.firstOrNull { it.displayName == s }
    }
}

/** States the "Active" filter excludes (everything else counts as active work). */
val activeExcludedStates: Set<String> = setOf("Accepted", "Completed", "Idea")

/**
 * Server-side query for the ticket list. Owner filter applies to "My Tickets" only;
 * the sprint filter is expressed per scope (H1): Rally's TestCase type has NO
 * Iteration attribute, so `(Iteration.Name = …)` sent to /testcase comes back as
 * HTTP 200 with a populated Errors array — which requireNoErrors correctly turns
 * into a failed load. Test cases are filtered through their linked work product
 * instead (`WorkProduct.Iteration.Name` is a valid dotted traversal on TestCase);
 * test cases with no WorkProduct won't match, which is the correct reading of
 * "test cases in this sprint". Pure and top-level so it is unit-testable.
 */
internal fun buildTicketQuery(scope: String?, selectedIter: String, username: String): String? {
    val conditions = mutableListOf<String>()

    if (Scope.fromDisplay(scope) == Scope.MY_TICKETS && username.isNotBlank()) {
        val safeUsername = RallyApiClient.escapeQueryValue(username)
        conditions.add("(Owner.UserName = \"$safeUsername\")")
    }

    if (selectedIter.isNotBlank() && selectedIter != "All Sprints") {
        val safeIter = RallyApiClient.escapeQueryValue(selectedIter)
        val iterationField =
            if (Scope.fromDisplay(scope) == Scope.TEST_CASES) "WorkProduct.Iteration.Name"
            else "Iteration.Name"
        conditions.add("($iterationField = \"$safeIter\")")
    }

    // Rally requires binary nesting for AND: ((a) AND (b))
    return when (conditions.size) {
        0 -> null
        1 -> conditions[0]
        else -> conditions.reduce { acc, cond -> "($acc AND $cond)" }
    }
}

/**
 * Indices of [artifacts] whose ref is in [refs] — used to restore the JList
 * selection after a model rebuild (M7). Pure and top-level so it is testable
 * without Swing.
 */
internal fun selectionIndicesByRef(artifacts: List<RallyArtifact>, refs: Set<String>): IntArray {
    if (refs.isEmpty()) return IntArray(0)
    return artifacts.withIndex()
        .filter { (_, artifact) -> artifact.ref != null && artifact.ref in refs }
        .map { it.index }
        .toIntArray()
}
