package com.github.halmurat.rally.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.github.halmurat.rally.api.RallyUserStory

/**
 * Tests for the [Scope] / [StateFilter] enums (MED-13) — the single source of truth for the
 * tool-window filter display strings. A drift between an enum's displayName and the combo
 * string would silently disable a filter, so these pin the round-trip.
 */
class RallyFiltersTest {

    @Test
    fun `every Scope round-trips through fromDisplay`() {
        for (scope in Scope.entries) {
            assertEquals(scope, Scope.fromDisplay(scope.displayName))
        }
    }

    @Test
    fun `every StateFilter round-trips through fromDisplay`() {
        for (state in StateFilter.entries) {
            assertEquals(state, StateFilter.fromDisplay(state.displayName))
        }
    }

    @Test
    fun `fromDisplay returns null for unknown or null input`() {
        assertNull(Scope.fromDisplay("Not A Scope"))
        assertNull(Scope.fromDisplay(null))
        assertNull(StateFilter.fromDisplay(""))
        assertNull(StateFilter.fromDisplay(null))
    }

    @Test
    fun `scope display strings are the documented filter options`() {
        assertEquals(
            listOf("All Tickets", "My Tickets", "User Stories", "Defects", "Test Cases", "Recent Activity"),
            Scope.entries.map { it.displayName }
        )
    }

    @Test
    fun `state filter display strings are the documented options`() {
        assertEquals(
            listOf("Any State", "Idea", "Defined", "In-Progress", "Completed", "Accepted", "Active"),
            StateFilter.entries.map { it.displayName }
        )
    }

    @Test
    fun `active filter excludes accepted completed and idea`() {
        assertEquals(setOf("Accepted", "Completed", "Idea"), activeExcludedStates)
    }

    @Test
    fun `selectionIndicesByRef finds surviving refs by index`() {
        val a = RallyUserStory(ref = "r/1", formattedID = "US1")
        val b = RallyUserStory(ref = "r/2", formattedID = "US2")
        val c = RallyUserStory(ref = "r/3", formattedID = "US3")
        assertArrayEquals(intArrayOf(0, 2), selectionIndicesByRef(listOf(a, b, c), setOf("r/1", "r/3")))
    }

    @Test
    fun `selectionIndicesByRef is empty when refs are gone or empty`() {
        val a = RallyUserStory(ref = "r/1")
        assertArrayEquals(intArrayOf(), selectionIndicesByRef(listOf(a), setOf("r/999")))
        assertArrayEquals(intArrayOf(), selectionIndicesByRef(listOf(a), emptySet()))
        assertArrayEquals(intArrayOf(), selectionIndicesByRef(emptyList(), setOf("r/1")))
    }

    @Test
    fun `selectionIndicesByRef skips artifacts with null refs`() {
        val a = RallyUserStory(ref = null)
        val b = RallyUserStory(ref = "r/2")
        assertArrayEquals(intArrayOf(1), selectionIndicesByRef(listOf(a, b), setOf("r/2")))
    }
}
