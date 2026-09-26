package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyApiException
import com.github.halmurat.rally.api.RallyDefect
import com.github.halmurat.rally.api.RallyTaskItem
import com.github.halmurat.rally.api.RallyTestCase
import com.github.halmurat.rally.api.RallyUser
import com.github.halmurat.rally.api.RallyUserNotFoundException
import com.github.halmurat.rally.api.RallyUserStory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the decisions behind the state-change and Start Working toolbar actions: when a state
 * change is confirmed, the Start Working button's state, how every owner-step skip is reported,
 * and that a failed state change never reads as "Started working".
 */
class RallyWorkActionsTest {

    // ── stateChangeConfirmation ──────────────────────────────────

    @Test
    fun `single ticket to a reversible state is not confirmed`() {
        assertNull(stateChangeConfirmation(listOf("US1"), 0, "Defined"))
        assertNull(stateChangeConfirmation(listOf("US1"), 0, "In-Progress"))
    }

    @Test
    fun `single ticket to Completed is always confirmed`() {
        assertEquals("Move US1 to 'Completed'?", stateChangeConfirmation(listOf("US1"), 0, "Completed"))
    }

    @Test
    fun `multiple tickets are confirmed with their ids`() {
        assertEquals(
            "Move 3 tickets to 'In-Progress'?\nUS1, US2, DE3",
            stateChangeConfirmation(listOf("US1", "US2", "DE3"), 0, "In-Progress")
        )
    }

    @Test
    fun `skipped test cases force a confirmation that names them`() {
        val q = stateChangeConfirmation(listOf("US1"), 2, "Defined")!!
        assertTrue(q, q.startsWith("Move US1 to 'Defined'?"))
        assertTrue(q, q.contains("2 selected test cases will be skipped"))
        assertTrue(
            stateChangeConfirmation(listOf("US1"), 1, "Defined")!!.contains("1 selected test case will be skipped")
        )
    }

    @Test
    fun `single ticket without a FormattedID falls back to a count`() {
        assertEquals("Move 1 ticket to 'Completed'?", stateChangeConfirmation(listOf(null), 0, "Completed"))
    }

    @Test
    fun `a failed state change names the tickets that didn't move`() {
        assertEquals(
            "Updated: 1, Failed: 2\nFailed to move: US2, DE3",
            stateChangeFailureMessage(1, listOf("US2", "DE3"))
        )
    }

    @Test
    fun `a failed state change lists at most ten tickets`() {
        val ids = (1..12).map { "US$it" }
        assertEquals(
            "Updated: 0, Failed: 12\nFailed to move: ${ids.take(10).joinToString(", ")}, … and 2 more",
            stateChangeFailureMessage(0, ids)
        )
    }

    @Test
    fun `state change status mentions skipped test cases only when there are some`() {
        assertEquals("Updated 3", stateChangeStatus(3, 0))
        assertEquals("Updated 1 (1 test case skipped)", stateChangeStatus(1, 1))
        assertEquals("Updated 1 (2 test cases skipped)", stateChangeStatus(1, 2))
    }

    // ── startWorkingButtonState ──────────────────────────────────

    private val story = RallyUserStory(ref = "r/1", formattedID = "US1")
    private val testCase = RallyTestCase(ref = "r/2", formattedID = "TC1")

    @Test
    fun `no git disables the button whatever is selected`() {
        val s = startWorkingButtonState(gitAvailable = false, inFlight = false, selection = listOf(testCase))
        assertFalse(s.enabled)
        assertEquals(START_WORKING_NO_GIT_TOOLTIP, s.tooltip)
    }

    @Test
    fun `a running Start Working disables the button`() {
        val s = startWorkingButtonState(gitAvailable = true, inFlight = true, selection = listOf(story))
        assertFalse(s.enabled)
        assertEquals(START_WORKING_BUSY_TOOLTIP, s.tooltip)
    }

    @Test
    fun `a multi-selection disables the button and says why`() {
        val s = startWorkingButtonState(gitAvailable = true, inFlight = false, selection = listOf(story, story))
        assertFalse(s.enabled)
        assertEquals(START_WORKING_MULTI_TOOLTIP, s.tooltip)
    }

    @Test
    fun `a test case disables the button`() {
        val s = startWorkingButtonState(gitAvailable = true, inFlight = false, selection = listOf(testCase))
        assertFalse(s.enabled)
        assertEquals(START_WORKING_TEST_CASE_TOOLTIP, s.tooltip)
        assertEquals(START_WORKING_TEST_CASE_TOOLTIP, s.accessibleDescription)
    }

    @Test
    fun `enabled button carries the HTML tooltip but a plain accessible description`() {
        for (selection in listOf(emptyList(), listOf(story))) {
            val s = startWorkingButtonState(gitAvailable = true, inFlight = false, selection = selection)
            assertTrue(s.enabled)
            assertEquals(START_WORKING_TOOLTIP, s.tooltip)
            assertEquals(START_WORKING_DESCRIPTION, s.accessibleDescription)
            assertFalse(s.accessibleDescription.contains("<"))
        }
    }

    // ── runOwnerStep ─────────────────────────────────────────────

    private val user = RallyUser(ref = "user/9", userName = "me@example.com", displayName = "Me")

    @Test
    fun `failed state change skips the lookup and reports it`() {
        val r = runOwnerStep(false, "me@example.com", { fail("no lookup"); user }, { fail("no assign") })
        assertNull(r.assigned)
        assertEquals("Owner not assigned because the state change failed", r.error)
    }

    @Test
    fun `blank username is a status note, not a warning`() {
        val r = runOwnerStep(true, " ", { fail("no lookup"); user }, { fail("no assign") })
        assertNull(r.assigned)
        assertNull(r.error)
        assertEquals("owner unchanged — no Username in Settings", r.note)
    }

    @Test
    fun `unknown user is reported as not found`() {
        val thrown = runOwnerStep(true, "x@y", { throw RallyUserNotFoundException("No user found") }, { fail("no assign") })
        assertEquals("Owner not assigned: Rally user 'x@y' was not found", thrown.error)
        val noRef = runOwnerStep(true, "x@y", { RallyUser() }, { fail("no assign") })
        assertEquals("Owner not assigned: Rally user 'x@y' was not found", noRef.error)
    }

    @Test
    fun `lookup or assign failure is reported with its cause`() {
        val lookupFailed = runOwnerStep(true, "x@y", { throw RallyApiException("HTTP 500") }, { fail("no assign") })
        assertEquals("Owner assignment failed: HTTP 500", lookupFailed.error)
        val assignFailed = runOwnerStep(true, "x@y", { user }, { throw RallyApiException("denied") })
        assertNull(assignFailed.assigned)
        assertEquals("Owner assignment failed: denied", assignFailed.error)
    }

    @Test
    fun `successful assignment returns the user and writes its ref`() {
        var assignedRef: String? = null
        val r = runOwnerStep(true, "me@example.com", { user }, { assignedRef = it })
        assertEquals(user, r.assigned)
        assertNull(r.error)
        assertNull(r.note)
        assertEquals("user/9", assignedRef)
    }

    // ── startWorkingOutcome ──────────────────────────────────────

    @Test
    fun `failed state change never reads as started`() {
        val o = startWorkingOutcome("US1", "feature/US1", false, listOf("State change failed: 500"), null)
        assertTrue(o.warning!!.startsWith("Branch feature/US1 is checked out, but the Rally ticket US1 was not updated"))
        assertTrue(o.warning!!.contains("State change failed: 500"))
        assertEquals("Branch feature/US1 checked out — Rally update failed", o.status)
        assertFalse(o.status.contains("Working on"))
    }

    @Test
    fun `owner problems warn with issues`() {
        val o = startWorkingOutcome("US1", "feature/US1", true, listOf("Owner assignment failed: x"), null)
        assertEquals("Started working on US1 with issues:\n\nOwner assignment failed: x", o.warning)
        assertEquals("Working on US1 (with issues)", o.status)
    }

    @Test
    fun `clean run has no warning and an announced skip goes in the status only`() {
        assertEquals(StartWorkingOutcome(null, "Working on US1"), startWorkingOutcome("US1", "b", true, emptyList(), null))
        assertEquals(
            StartWorkingOutcome(null, "Working on US1 (owner unchanged — no Username in Settings)"),
            startWorkingOutcome("US1", "b", true, emptyList(), "owner unchanged — no Username in Settings")
        )
    }

    // ── createBlockedByConnectionChange ──────────────────────────

    @Test
    fun `a create picked from lists of the same connection goes ahead`() {
        assertFalse(createBlockedByConnectionChange(3, 3, "p/1", "i/1"))
    }

    @Test
    fun `a project or sprint picked before the connection changed blocks the create`() {
        assertTrue(createBlockedByConnectionChange(3, 4, "p/1", "i/1"))
        assertTrue(createBlockedByConnectionChange(3, 4, "p/1", null))
        assertTrue(createBlockedByConnectionChange(3, 4, null, "i/1"))
    }

    @Test
    fun `a create that names no project or sprint has nothing stale to send`() {
        assertFalse(createBlockedByConnectionChange(3, 4, null, null))
    }

    @Test
    fun `the blocked-create message says nothing was created and what to do`() {
        assertTrue(CREATE_CONNECTION_CHANGED_MESSAGE.contains("Nothing was created"))
        assertTrue(CREATE_CONNECTION_CHANGED_MESSAGE.contains("reopen Create"))
    }

    // ── Create balloons (HTML) ───────────────────────────────────

    @Test
    fun `the created balloon shows the FormattedID and any warning`() {
        assertEquals("Created US100", createdBalloonHtml("US100", null))
        assertEquals(
            "Created US100 (couldn't resolve user — created without owner)",
            createdBalloonHtml("US100", "couldn't resolve user — created without owner")
        )
    }

    @Test
    fun `the created balloon escapes a FormattedID with markup`() {
        assertEquals(
            "Created US1&lt;img src=http://x/y.png&gt; &amp; co",
            createdBalloonHtml("US1<img src=http://x/y.png> & co", null)
        )
    }

    @Test
    fun `the upload-failed balloon escapes the FormattedID and the error but keeps its line break`() {
        assertEquals(
            "Created US1&lt;b&gt;, but attachment upload failed: &lt;img src=x&gt; denied<br>" +
                "You can re-attach the file in the Rally web UI.",
            uploadFailedBalloonHtml("US1<b>", "<img src=x> denied")
        )
    }

    // ── withState / withOwner ────────────────────────────────────

    @Test
    fun `owner patch keeps the state patch`() {
        val patched = withOwner(withState(story, "In-Progress"), user) as RallyUserStory
        assertEquals("In-Progress", patched.scheduleState)
        assertEquals(user, patched.owner)
    }

    @Test
    fun `withState writes ScheduleState for stories and defects and State for tasks`() {
        assertEquals("Completed", (withState(RallyDefect(ref = "d"), "Completed") as RallyDefect).scheduleState)
        assertEquals("Completed", (withState(RallyTaskItem(ref = "t"), "Completed") as RallyTaskItem).state)
        assertEquals(testCase, withState(testCase, "Completed"))
        assertEquals(testCase, withOwner(testCase, user))
    }
}
