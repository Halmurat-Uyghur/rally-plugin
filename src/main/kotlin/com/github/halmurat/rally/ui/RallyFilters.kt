package com.github.halmurat.rally.ui

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
