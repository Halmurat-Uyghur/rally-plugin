package com.github.halmurat.rally.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the server-side ticket query per scope (H1). TestCase has no Iteration
 * attribute, so the sprint filter must traverse the linked work product there —
 * `(Iteration.Name = …)` on /testcase is a guaranteed 200-with-Errors failure.
 */
class RallyTicketQueryTest {

    @Test
    fun `no filters yields null query`() {
        assertNull(buildTicketQuery("All Tickets", "", ""))
    }

    @Test
    fun `All Sprints adds no iteration condition`() {
        assertNull(buildTicketQuery("Test Cases", "All Sprints", ""))
    }

    @Test
    fun `sprint filter on regular scopes uses Iteration Name`() {
        assertEquals(
            "(Iteration.Name = \"Sprint 42\")",
            buildTicketQuery("All Tickets", "Sprint 42", "")
        )
    }

    @Test
    fun `sprint filter on Test Cases scope traverses the work product`() {
        assertEquals(
            "(WorkProduct.Iteration.Name = \"Sprint 42\")",
            buildTicketQuery("Test Cases", "Sprint 42", "")
        )
    }

    @Test
    fun `my tickets owner filter combines with sprint via binary AND nesting`() {
        assertEquals(
            "((Owner.UserName = \"me@x.com\") AND (Iteration.Name = \"Sprint 42\"))",
            buildTicketQuery("My Tickets", "Sprint 42", "me@x.com")
        )
    }

    @Test
    fun `owner filter only applies to My Tickets scope`() {
        assertNull(buildTicketQuery("All Tickets", "", "me@x.com"))
    }

    @Test
    fun `iteration values are escaped`() {
        assertEquals(
            "(Iteration.Name = \"Sprint \\\"42\\\"\")",
            buildTicketQuery("All Tickets", "Sprint \"42\"", "")
        )
    }
}
