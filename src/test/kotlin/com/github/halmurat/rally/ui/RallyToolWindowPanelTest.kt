package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyApiClient
import com.github.halmurat.rally.settings.RallySettings
import com.github.halmurat.rally.settings.RallySettingsListener
import com.github.halmurat.rally.testutil.FakeRallyServer
import com.github.halmurat.rally.util.literalHtmlMessage
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Drives the real [RallyToolWindowPanel] against [FakeRallyServer] to pin how the ticket load,
 * the client rebuild and the Project/Sprint dropdowns interact — the lists the Create dialogs
 * are given (audit F3), the refs a create then sends, and the footer sprint summary — and that
 * its dialogs show Rally text literally. Workspace A has projects A-Team and A-Other, each with
 * its own "Sprint 1"; workspace B has B-Team with "B Sprint".
 */
class RallyToolWindowPanelTest : BasePlatformTestCase() {

    private lateinit var rally: FakeRallyServer
    private lateinit var savedState: RallySettings.State
    private lateinit var savedApiKey: RallySettings.ApiKeyState
    private var savedTestDialog: TestDialog? = null
    private val settings: RallySettings get() = RallySettings.getInstance()

    /** Holds B's project and sprint lists back, so a test can look at the panel mid-reload. */
    private var holdWorkspaceBLists: CountDownLatch? = null
    /** Answers B's project list with Rally's HTTP-200-with-Errors instead. */
    private var failWorkspaceBProjects = false
    /** Holds A-Team's sprint list back. */
    private var holdATeamSprints: CountDownLatch? = null
    /** Holds A-Other's sprint list back. */
    private var holdAOtherSprints: CountDownLatch? = null
    /** Answers A-Other's sprint list with Rally's HTTP-200-with-Errors instead. */
    private var failAOtherSprints = false
    /** Holds B's current-sprint lookup (the sprint summary's) back. */
    private var holdWorkspaceBCurrentSprint: CountDownLatch? = null
    /** Holds the current-sprint lookups back, by "workspace|project ref" (as [currentSprints]). */
    private val currentSprintHolds = ConcurrentHashMap<String, CountDownLatch>()
    /** Holds A's project list back. */
    private var holdWorkspaceAProjects: CountDownLatch? = null
    /** Leaves A-Team out of A's project list. */
    @Volatile private var workspaceAHidesATeam = false
    /** Adds a project named "A-Team" (2002) to B's project list. */
    @Volatile private var workspaceBHasATeam = false
    /**
     * The sprint each current-sprint lookup finds, by "workspace|project ref" ("" for none), or
     * [FAIL] for Rally's HTTP-200-with-Errors. Unlisted: no sprint is active.
     */
    private val currentSprints = ConcurrentHashMap<String, String>()

    /** Every Messages dialog the panel showed, by its text; each is answered Yes (OK). */
    private val dialogs = CopyOnWriteArrayList<String>()
    /** The body of every user-story create POST. */
    private val storyCreates = CopyOnWriteArrayList<String>()
    /** The FormattedID of the one user story every ticket query returns. */
    private var storyId = "US1"
    /** When set, Rally rejects every user-story create with this error text. */
    private var storyCreateError: String? = null
    /** When set, Rally rejects every update of that story with this error text. */
    private var storyUpdateError: String? = null

    override fun setUp() {
        super.setUp()
        // Concurrent, as a real Rally is: a held answer (an old load's lookup still in flight)
        // must not stall the newer load a test then drives to completion.
        rally = FakeRallyServer(concurrent = true)
        routeRally()
        savedState = settings.state.copy()
        settings.state.serverUrl = rally.baseUrl
        settings.state.workspaceRef = WS_A
        settings.state.username = ""
        settings.state.selectedProject = ""
        settings.state.selectedIteration = ""
        // Let the startup PasswordSafe read land first, so the snapshot isn't a load that
        // completeApiKeyLoad below would then leave pending forever once it is put back.
        settings.awaitApiKey(10_000)
        savedApiKey = settings.saveApiKeyState()
        settings.completeApiKeyLoad("fake-key")
        savedTestDialog = TestDialogManager.setTestDialog { message -> dialogs.add(message); Messages.YES }
    }

    override fun tearDown() {
        try {
            holdWorkspaceBLists?.countDown()
            holdWorkspaceBCurrentSprint?.countDown()
            currentSprintHolds.values.forEach { it.countDown() }
            holdWorkspaceAProjects?.countDown()
            holdATeamSprints?.countDown()
            holdAOtherSprints?.countDown()
            TestDialogManager.setTestDialog(savedTestDialog ?: TestDialog.DEFAULT)
            settings.state.serverUrl = savedState.serverUrl
            settings.state.workspaceRef = savedState.workspaceRef
            settings.state.username = savedState.username
            settings.state.selectedProject = savedState.selectedProject
            settings.state.selectedIteration = savedState.selectedIteration
            settings.restoreApiKeyState(savedApiKey)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()   // disposes the panel before the server below it closes
        }
    }

    // ── A: a Settings change while My Tickets has no Username ────

    fun testNewWorkspaceInMyTicketsWithoutUsernameReloadsListsWithoutTicketQuery() {
        val hooks = loadedPanel()
        hooks.scopeCombo.selectedItem = Scope.MY_TICKETS.displayName
        waitUntil { hooks.idle }
        assertEquals("Username not set", hooks.statusText)

        rally.clearRequests()
        applyWorkspace(WS_B)
        waitUntil { hooks.idle && projectQueries().any { workspaceOf(it) == WS_B } }

        assertTrue("B's projects were queried: ${rally.requests}", projectQueries().any { workspaceOf(it) == WS_B })
        assertEquals("no ticket query without an owner filter", emptyList<URI>(), ticketQueries())
        val inputs = hooks.createDialogInputs()
        assertEquals("Create offers only B's projects", listOf(ref("project", 2001)), inputs.projects.map { it.ref })
        assertEquals("Create offers only B's sprints", listOf(ref("iteration", 8001)), inputs.iterations.map { it.ref })
        assertEquals("Username not set", hooks.statusText)
        assertTrue(hooks.projectCombo.isEnabled)
        assertTrue(hooks.iterationCombo.isEnabled)
    }

    fun testRefreshInMyTicketsWithoutUsernameRequeriesListsWithoutTicketQuery() {
        val hooks = loadedPanel()
        hooks.scopeCombo.selectedItem = Scope.MY_TICKETS.displayName
        waitUntil { hooks.idle }

        rally.clearRequests()
        hooks.refresh()
        waitUntil { hooks.idle && hooks.projectCombo.isEnabled && hooks.iterationCombo.isEnabled }

        assertTrue("projects were re-queried: ${rally.requests}", projectQueries().isNotEmpty())
        assertEquals(emptyList<URI>(), ticketQueries())
        assertTrue("Refresh re-enables the Project dropdown", hooks.projectCombo.isEnabled)
        assertTrue("Refresh re-enables the Sprint dropdown", hooks.iterationCombo.isEnabled)
    }

    // ── B: a project switch while My Tickets has no Username ─────

    fun testProjectSwitchInMyTicketsWithoutUsernameReloadsSprints() {
        settings.state.selectedProject = "A-Team"
        settings.state.selectedIteration = "Sprint 1"
        val hooks = loadedPanel()
        assertEquals(ref("iteration", 5001), hooks.createDialogInputs().iterationRef)
        hooks.scopeCombo.selectedItem = Scope.MY_TICKETS.displayName
        waitUntil { hooks.idle }

        rally.clearRequests()
        hooks.projectCombo.selectedItem = "A-Other"
        waitUntil { hooks.idle }

        assertTrue(
            "A-Other's sprints were queried: ${rally.requests}",
            sprintListQueries().any { FakeRallyServer.queryParam(it, "project") == ref("project", 1002) }
        )
        assertEquals(emptyList<URI>(), ticketQueries())
        val inputs = hooks.createDialogInputs()
        assertEquals(ref("project", 1002), inputs.projectRef)
        assertEquals("Create offers A-Other's sprints", listOf(ref("iteration", 6001)), inputs.iterations.map { it.ref })
        assertEquals("the saved sprint resolves in the new project", ref("iteration", 6001), inputs.iterationRef)
        assertTrue(hooks.iterationCombo.isEnabled)

        // A Refresh in this state re-queries the sprints too, and re-enables their dropdown.
        hooks.refresh()
        waitUntil { hooks.idle && hooks.iterationCombo.isEnabled }
        assertTrue("Refresh re-enables the Sprint dropdown", hooks.iterationCombo.isEnabled)
        assertEquals(emptyList<URI>(), ticketQueries())
    }

    // ── B2: a sprint reload that is slow or fails ────────────────

    fun testCreateOffersNoSprintsWhileAProjectSwitchReloadsThem() {
        settings.state.selectedProject = "A-Team"
        settings.state.selectedIteration = "Sprint 1"
        val hooks = loadedPanel()
        assertEquals(ref("iteration", 5001), hooks.createDialogInputs().iterationRef)
        val hold = CountDownLatch(1).also { holdAOtherSprints = it }

        hooks.projectCombo.selectedItem = "A-Other"
        assertTrue(waitUntil { sprintListQueries().any { FakeRallyServer.queryParam(it, "project") == ref("project", 1002) } })
        val midReload = hooks.createDialogInputs()
        hold.countDown()

        assertEquals("A-Team's sprints while A-Other's load", emptyList<String?>(), midReload.iterations.map { it.ref })
        assertNull(midReload.iterationRef)
        assertTrue(waitUntil { hooks.idle })
        assertEquals("A-Other's sprints once loaded", listOf(ref("iteration", 6001)), hooks.createDialogInputs().iterations.map { it.ref })
    }

    fun testCreateOffersTheProjectsSprintsWhileARefreshRequeriesThem() {
        settings.state.selectedProject = "A-Team"
        settings.state.selectedIteration = "Sprint 1"
        val hooks = loadedPanel()
        rally.clearRequests()
        val hold = CountDownLatch(1).also { holdATeamSprints = it }

        hooks.refresh()
        assertTrue(waitUntil { sprintListQueries().any { FakeRallyServer.queryParam(it, "project") == ref("project", 1001) } })
        val midRefresh = hooks.createDialogInputs()   // Create opens while the same project's sprints re-query
        hold.countDown()

        assertEquals("A-Team's sprints while they re-query", listOf(ref("iteration", 5001)), midRefresh.iterations.map { it.ref })
        assertEquals("the toolbar sprint is still pre-selected", ref("iteration", 5001), midRefresh.iterationRef)
        assertTrue(waitUntil { hooks.idle })
    }

    fun testFailedSprintReloadAfterAProjectSwitchStopsFilteringByTheOldProjectsSprint() {
        settings.state.selectedProject = "A-Team"
        settings.state.selectedIteration = "Sprint 1"
        val hooks = loadedPanel()
        failAOtherSprints = true
        rally.clearRequests()

        hooks.projectCombo.selectedItem = "A-Other"
        assertTrue(waitUntil { !hooks.loadInFlight && ticketQueries().isNotEmpty() })

        // The Sprint dropdown is back to "All Sprints", so A-Team's "Sprint 1" — still the saved
        // sprint — must not go on filtering A-Other's tickets.
        assertEquals(
            "ticket queries filtered by a sprint the dropdown no longer shows",
            emptyList<URI>(), ticketQueries().filter { FakeRallyServer.queryParam(it, "query")?.contains("Iteration") == true }
        )
    }

    // ── C: a client rebuild drops the previous connection's lists ─

    fun testWorkspaceChangeDropsOldListsBeforeNewOnesArrive() {
        val hooks = loadedPanel()
        val hold = CountDownLatch(1).also { holdWorkspaceBLists = it }

        applyWorkspace(WS_B)
        waitUntil {
            rally.requests.any { workspaceOf(it) == WS_B } &&
                hooks.projectCombo.itemCount == 1 && hooks.iterationCombo.itemCount == 1
        }

        val midReload = hooks.createDialogInputs()
        assertEquals("A's projects are gone while B's load", emptyList<String?>(), midReload.projects.map { it.ref })
        assertEquals("A's sprints are gone while B's load", emptyList<String?>(), midReload.iterations.map { it.ref })
        assertEquals(listOf("All Projects"), comboItems(hooks.projectCombo))
        assertFalse(hooks.projectCombo.isEnabled)
        assertEquals(listOf("All Sprints"), comboItems(hooks.iterationCombo))
        assertFalse(hooks.iterationCombo.isEnabled)

        hold.countDown()
        waitUntil { hooks.idle && hooks.createDialogInputs().projects.isNotEmpty() }
        assertEquals(listOf(ref("project", 2001)), hooks.createDialogInputs().projects.map { it.ref })
    }

    fun testFailedListReloadAfterWorkspaceChangeLeavesNoOldLists() {
        settings.state.selectedProject = "A-Team"
        val hooks = loadedPanel()
        failWorkspaceBProjects = true

        rally.clearRequests()
        applyWorkspace(WS_B)
        // The project reload fails; the load goes on and commits B's sprints.
        waitUntil { !hooks.loadInFlight && hooks.iterationCombo.isEnabled }

        val inputs = hooks.createDialogInputs()
        assertEquals("no A project survives the failed reload", emptyList<String?>(), inputs.projects.map { it.ref })
        assertNull(inputs.projectRef)
        val aTeam = ref("project", 1001)
        assertTrue("B's sprints were queried: ${rally.requests}", sprintListQueries().isNotEmpty())
        assertTrue(
            "B's sprints load unscoped, not for A's project: ${rally.requests}",
            sprintListQueries().none { FakeRallyServer.queryParam(it, "project") == aTeam }
        )
        assertTrue("B's tickets were queried: ${rally.requests}", ticketQueries().isNotEmpty())
        assertTrue(
            "B's ticket query isn't scoped to A's project: ${rally.requests}",
            ticketQueries().none { FakeRallyServer.queryParam(it, "project") == aTeam }
        )
    }

    // ── D: Start Working follows a selection a reload drops ──────

    fun testStartWorkingReEnablesWhenReloadDropsTheSelection() {
        val hooks = loadedPanel()
        hooks.artifactList.selectedIndices = intArrayOf(0, 1)
        assertFalse(hooks.startWorkingButton.isEnabled)
        assertEquals(START_WORKING_MULTI_TOOLTIP, hooks.startWorkingButton.toolTipText)

        hooks.scopeCombo.selectedItem = Scope.MY_TICKETS.displayName   // empties the list
        waitUntil { hooks.idle }

        assertTrue(hooks.artifactList.selectedValuesList.isEmpty())
        assertTrue("nothing selected: the click explains what to select", hooks.startWorkingButton.isEnabled)
        assertEquals(START_WORKING_TOOLTIP, hooks.startWorkingButton.toolTipText)
    }

    // ── E: Create and the connection its dialog was opened under ─

    fun testCreateFromADialogOpenedWhileTheApiKeyLoadedIsSent() {
        // A slow keychain: no client, no lists yet. The toolbar project is saved, though.
        settings.state.selectedProject = "A-Team"
        settings.restoreApiKeyState(RallySettings.ApiKeyState.LOADING)
        val hooks = newPanel()
        assertTrue(waitUntil { hooks.statusText == "Waiting for API key..." })

        val inputs = hooks.createDialogInputs()   // Create opens: nothing to pick but "All Projects"
        assertEquals(emptyList<String?>(), inputs.projects.map { it.ref })
        settings.completeApiKeyLoad("fake-key")   // the keychain answers while the dialog is open
        assertTrue(waitUntil { hooks.idle && hooks.createDialogInputs().projects.isNotEmpty() })

        hooks.createUserStory(inputs, "New story", dialogProjectRef = null, dialogIterationRef = null)

        assertTrue("the create was sent: ${rally.requests} dialogs=$dialogs", waitUntil { hooks.statusText.startsWith("Created") })
        assertEquals(emptyList<String>(), dialogs)
        assertEquals(1, storyCreates.size)
        // The dialog had no list to offer, so its "All Projects" is the toolbar's project — A-Team,
        // from the lists the same (first) connection loaded while it was open.
        assertEquals(ref("project", 1001), createdProjectRef(storyCreates[0]))
        assertEquals(
            "building the first client is no connection change",
            inputs.listsGeneration, hooks.createDialogInputs().listsGeneration
        )
    }

    fun testCreateFromADialogOpenedDuringTheFirstProjectLoadNamesTheToolbarProject() {
        settings.state.selectedProject = "A-Team"
        val hold = CountDownLatch(1).also { holdWorkspaceAProjects = it }
        val hooks = newPanel()
        assertTrue(waitUntil { projectQueries().any { workspaceOf(it) == WS_A } })

        val inputs = hooks.createDialogInputs()   // Create opens while the project list loads
        assertEquals(emptyList<String?>(), inputs.projects.map { it.ref })
        assertNull(inputs.projectRef)
        // ... and the list commits while the (modal) dialog is open: only cachedProjects, written
        // off the EDT; the dropdown repopulates once the dialog closes.
        val dialog = Any()
        LaterInvocator.enterModal(dialog)
        try {
            hold.countDown()
            // The ticket query follows the serial project load, so the list is in by then.
            assertTrue(waitUntil { ticketQueries().isNotEmpty() })
            assertEquals(
                "the list is in cachedProjects", setOf(ref("project", 1001), ref("project", 1002)),
                hooks.createDialogInputs().projects.map { it.ref }.toSet()
            )
            assertFalse("the dropdown waits for the dialog to close", "A-Team" in comboItems(hooks.projectCombo))
        } finally {
            LaterInvocator.leaveModal(dialog)
        }

        hooks.createUserStory(inputs, "New story", dialogProjectRef = null, dialogIterationRef = null)

        assertEventually({ "status: ${hooks.statusText} dialogs=$dialogs" }) { hooks.statusText.startsWith("Created") }
        assertEquals("\"All Projects\" with no list offered is the toolbar's project", ref("project", 1001), createdProjectRef(storyCreates.single()))
        // Checked only after the create: pumping the held repopulation before it would hide a
        // create that read the toolbar project from the dropdown instead of cachedProjects.
        assertTrue("the dropdown repopulates once the dialog closes", waitUntil { "A-Team" in comboItems(hooks.projectCombo) })
    }

    fun testCreateFromADialogOpenedDuringTheFirstProjectLoadNamesNoProjectOfTheNextConnection() {
        settings.state.selectedProject = "A-Team"
        workspaceBHasATeam = true   // B has a project of the same name
        val hold = CountDownLatch(1).also { holdWorkspaceAProjects = it }
        val hooks = newPanel()
        assertTrue(waitUntil { projectQueries().any { workspaceOf(it) == WS_A } })

        val inputs = hooks.createDialogInputs()   // Create opens while A's first project list loads
        assertEquals(emptyList<String?>(), inputs.projects.map { it.ref })
        applyWorkspace(WS_B)                      // Settings applied: B's reload waits for A's load
        hold.countDown()
        val bProjects = setOf(ref("project", 2001), ref("project", 2002))
        assertTrue(waitUntil { hooks.idle && hooks.createDialogInputs().projects.map { it.ref }.toSet() == bProjects })

        hooks.createUserStory(inputs, "New story", dialogProjectRef = null, dialogIterationRef = null)

        // The dialog had nothing of A's to send, and B's "A-Team" is not what it was opened on.
        assertEventually({ "status: ${hooks.statusText} dialogs=$dialogs" }) { hooks.statusText.startsWith("Created") }
        assertNull("no project from the lists of a connection loaded after the dialog opened", createdProjectRef(storyCreates.single()))
    }

    fun testCreateFromADialogThatOfferedProjectsKeepsItsAllProjectsPick() {
        // The saved project isn't in the list, so the toolbar — and the dialog — show "All Projects".
        settings.state.selectedProject = "A-Team"
        workspaceAHidesATeam = true
        val hooks = newPanel()
        assertTrue(waitUntil { hooks.idle && hooks.createDialogInputs().projects.isNotEmpty() })
        val inputs = hooks.createDialogInputs()
        assertEquals(listOf(ref("project", 1002)), inputs.projects.map { it.ref })
        assertNull(inputs.projectRef)

        // A list reload (a Refresh started just before) commits the saved project while it is open.
        workspaceAHidesATeam = false
        hooks.refresh()
        assertTrue(waitUntil { hooks.idle && hooks.createDialogInputs().projectRef == ref("project", 1001) })

        hooks.createUserStory(inputs, "New story", dialogProjectRef = null, dialogIterationRef = null)

        assertEventually({ "status: ${hooks.statusText} dialogs=$dialogs" }) { hooks.statusText.startsWith("Created") }
        assertNull(
            "\"All Projects\" picked from a list the dialog offered names no project, as the user saw it",
            createdProjectRef(storyCreates.single())
        )
    }

    fun testCreateFromADialogOpenedBeforeAConnectionChangeIsRefused() {
        val hooks = loadedPanel()
        val inputs = hooks.createDialogInputs()   // Create opens on workspace A's lists
        applyWorkspace(WS_B)                      // Settings applied while it is open
        waitUntil { hooks.idle && hooks.createDialogInputs().projects.map { it.ref } == listOf(ref("project", 2001)) }

        hooks.createUserStory(inputs, "New story", dialogProjectRef = ref("project", 1001), dialogIterationRef = null)

        assertTrue("status: ${hooks.statusText}", waitUntil { hooks.statusText == "Create cancelled" })
        assertEquals("nothing was posted: ${rally.requests}", 0, rally.hitCount("$API/hierarchicalrequirement/create"))
        assertEquals(listOf(literalHtmlMessage(CREATE_CONNECTION_CHANGED_MESSAGE)), dialogs)
    }

    // ── F: the footer never shows another connection's or project's sprint ─

    fun testFailedCurrentSprintLookupAfterAWorkspaceChangeNeverShowsTheOldSprint() {
        currentSprints["$WS_A|"] = "A Current"
        val hooks = loadedPanel()
        assertTrue(waitUntil { hooks.sprintText.contains("A Current") })
        currentSprints["$WS_B|"] = FAIL

        applyWorkspace(WS_B)
        assertTrue(
            "footer: ${hooks.sprintText}",
            waitUntil { hooks.idle && hooks.createDialogInputs().projects.isNotEmpty() && hooks.sprintText == SPRINT_FAILED }
        )
        hooks.stateCombo.selectedItem = StateFilter.DEFINED.displayName   // re-filters in memory
        assertEquals(SPRINT_FAILED, hooks.sprintText)
    }

    fun testFailedCurrentSprintLookupAfterAProjectSwitchNeverShowsTheOldProjectsSprint() {
        settings.state.selectedProject = "A-Team"
        currentSprints["$WS_A|${ref("project", 1001)}"] = "Team Current"
        currentSprints["$WS_A|${ref("project", 1002)}"] = FAIL
        val hooks = loadedPanel()
        assertTrue(waitUntil { hooks.sprintText.contains("Team Current") })

        hooks.projectCombo.selectedItem = "A-Other"
        assertTrue("footer: ${hooks.sprintText}", waitUntil { hooks.idle && hooks.sprintText == SPRINT_FAILED })
        hooks.stateCombo.selectedItem = StateFilter.DEFINED.displayName
        assertEquals(SPRINT_FAILED, hooks.sprintText)
    }

    fun testWorkspaceChangeClearsTheOldSprintBeforeTheNewOneResolves() {
        currentSprints["$WS_A|"] = "A Current"
        val hooks = loadedPanel()
        assertTrue(waitUntil { hooks.sprintText.contains("A Current") })
        val hold = CountDownLatch(1).also { holdWorkspaceBCurrentSprint = it }

        applyWorkspace(WS_B)
        assertTrue(waitUntil { currentSprintQueries().any { workspaceOf(it) == WS_B } })
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse("footer mid-reload: ${hooks.sprintText}", hooks.sprintText.contains("A Current"))
        hooks.stateCombo.selectedItem = StateFilter.DEFINED.displayName   // re-filters while B's resolves
        assertFalse("footer after a re-filter: ${hooks.sprintText}", hooks.sprintText.contains("A Current"))

        hold.countDown()
        assertTrue("footer: ${hooks.sprintText}", waitUntil { hooks.idle && hooks.sprintText == "No active sprint" })
    }

    // A load releases `loading` once its tickets arrive, before its current-sprint lookup is
    // joined, and a client rebuild doesn't cancel that lookup (shutdown() lets it run). So a newer
    // load can resolve its sprint while an older one's lookup is still out; the older answer,
    // arriving last, must not replace the newer footer — neither on screen nor in what a later
    // re-filter renders.

    fun testLateCurrentSprintFailureFromTheOldConnectionKeepsTheNewSprint() {
        currentSprints["$WS_A|"] = FAIL
        currentSprints["$WS_B|"] = "B Current"
        val hold = holdCurrentSprint("$WS_A|")
        val hooks = loadedPanel()
        assertTrue(waitUntil { currentSprintQueries().any { workspaceOf(it) == WS_A } })
        val clientA = hooks.client!!

        applyWorkspace(WS_B)
        assertEventually({ "footer: ${hooks.sprintText}" }) { hooks.idle && onWorkspaceB(hooks) && hooks.sprintText.contains("B Current") }
        hold.countDown()   // A's lookup now fails, after B's resolved
        awaitLateAnswer(clientA)

        assertTrue("footer: ${hooks.sprintText}", hooks.sprintText.startsWith("Sprint: B Current"))
        hooks.stateCombo.selectedItem = StateFilter.DEFINED.displayName   // re-filters in memory
        assertTrue("footer after a re-filter: ${hooks.sprintText}", hooks.sprintText.startsWith("Sprint: B Current"))
    }

    fun testLateCurrentSprintFromTheOldConnectionNeverShowsUnderTheNewOne() {
        currentSprints["$WS_A|"] = "A Current"
        val hold = holdCurrentSprint("$WS_A|")
        val hooks = loadedPanel()
        assertTrue(waitUntil { currentSprintQueries().any { workspaceOf(it) == WS_A } })
        val clientA = hooks.client!!

        applyWorkspace(WS_B)   // B has no active sprint
        assertEventually({ "footer: ${hooks.sprintText}" }) { hooks.idle && onWorkspaceB(hooks) && hooks.sprintText == "No active sprint" }
        hold.countDown()   // A's sprint arrives after B's "none"
        awaitLateAnswer(clientA)

        assertEquals("No active sprint", hooks.sprintText)
        hooks.stateCombo.selectedItem = StateFilter.DEFINED.displayName
        assertEquals("footer after a re-filter", "No active sprint", hooks.sprintText)
    }

    fun testLateCurrentSprintFromTheOldConnectionIsDroppedWhenAnotherActionRebuildsTheClient() {
        currentSprints["$WS_A|"] = "A Current"
        val hold = holdCurrentSprint("$WS_A|")
        val hooks = loadedPanel()
        assertTrue(waitUntil { currentSprintQueries().any { workspaceOf(it) == WS_A } })
        val clientA = hooks.client!!

        // The workspace changes and a Create rebuilds the client before any reload has run (the
        // one a Settings apply queues can wait behind a load in flight): no newer load has taken
        // the footer, so only the rebuild itself can retire A's lookup.
        settings.state.workspaceRef = WS_B
        hooks.createUserStory(hooks.createDialogInputs(), "New story", dialogProjectRef = null, dialogIterationRef = null)
        assertEventually({ "status: ${hooks.statusText}" }) { hooks.statusText.startsWith("Created") }
        assertNotSame("the Create rebuilt the client", clientA, hooks.client)
        hold.countDown()   // A's sprint arrives after the rebuild dropped A's lists
        awaitLateAnswer(clientA)

        assertFalse("footer: ${hooks.sprintText}", hooks.sprintText.contains("A Current"))
        hooks.stateCombo.selectedItem = StateFilter.DEFINED.displayName
        assertFalse("footer after a re-filter: ${hooks.sprintText}", hooks.sprintText.contains("A Current"))
    }

    fun testLateCurrentSprintFromTheOldProjectNeverShowsAfterAProjectSwitch() {
        settings.state.selectedProject = "A-Team"
        val aTeam = "$WS_A|${ref("project", 1001)}"
        currentSprints[aTeam] = "Team Current"
        val hold = holdCurrentSprint(aTeam)
        val hooks = loadedPanel()
        assertTrue(waitUntil { currentSprintQueries().any { FakeRallyServer.queryParam(it, "project") == ref("project", 1001) } })

        hooks.projectCombo.selectedItem = "A-Other"   // A-Other has no active sprint
        assertEventually({ "footer: ${hooks.sprintText}" }) { hooks.idle && hooks.sprintText == "No active sprint" }
        hold.countDown()   // A-Team's sprint arrives after A-Other's "none"
        awaitLateAnswer(hooks.client!!)   // the same client: a project switch doesn't rebuild it

        assertEquals("No active sprint", hooks.sprintText)
        hooks.stateCombo.selectedItem = StateFilter.DEFINED.displayName
        assertEquals("footer after a re-filter", "No active sprint", hooks.sprintText)
    }

    // ── G: dialogs show Rally values and server text literally ───
    // IntelliJ renders Messages dialog text as HTML, so markup in a FormattedID or in Rally's
    // error text would render — and fetch the <img> it names — unless it is escaped.

    fun testStateChangeDialogsShowAFormattedIdWithMarkupLiterally() {
        val id = "US1<img src=http://evil.invalid/id.png>"
        storyId = id
        storyUpdateError = "Concurrency conflict"
        val hooks = loadedPanel()
        val model = hooks.artifactList.model
        hooks.artifactList.selectedIndex = (0 until model.size).first { model.getElementAt(it).formattedID == id }

        hooks.changeState("Completed")   // always confirmed; the update then fails

        assertTrue("dialogs: $dialogs", waitUntil { dialogs.size == 2 })
        assertEquals(literalHtmlMessage(stateChangeConfirmation(listOf(id), 0, "Completed")!!), dialogs[0])
        assertEquals(literalHtmlMessage("Updated: 0, Failed: 1\nFailed to move: $id"), dialogs[1])
        assertFalse(dialogs.toString(), dialogs.any { it.contains("<img") })
    }

    fun testCreateFailureDialogShowsRallysErrorTextLiterally() {
        storyCreateError = "<img src=http://evil.invalid/err.png>Project is closed"
        val hooks = loadedPanel()

        hooks.createUserStory(hooks.createDialogInputs(), "New story", dialogProjectRef = null, dialogIterationRef = null)

        assertTrue("dialogs: $dialogs", waitUntil { dialogs.isNotEmpty() })
        assertEquals("Create failed", hooks.statusText)
        val message = dialogs.single()
        assertTrue(message, message.startsWith("<html>Failed to create user story: "))
        assertTrue(message, message.contains("&lt;img src=http://evil.invalid/err.png&gt;Project is closed"))
        assertFalse(message, message.contains("<img"))
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** A panel that finished its first load (All Tickets) of workspace A. */
    private fun loadedPanel(): RallyToolWindowPanel.TestHooks {
        val hooks = newPanel()
        assertTrue(
            "initial load: ${rally.requests}",
            waitUntil { hooks.idle && hooks.createDialogInputs().projects.isNotEmpty() && hooks.artifactList.model.size == 2 }
        )
        return hooks
    }

    private fun newPanel(): RallyToolWindowPanel.TestHooks {
        val panel = RallyToolWindowPanel(project)
        Disposer.register(testRootDisposable, panel)
        return panel.testHooks()
    }

    private fun applyWorkspace(workspace: String) {
        settings.state.workspaceRef = workspace
        ApplicationManager.getApplication().messageBus.syncPublisher(RallySettingsListener.TOPIC).settingsApplied()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()   // runs the reload the listener queued
    }

    /** Pump the EDT until [condition] holds (false on timeout: the assertions then say what's wrong). */
    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (condition()) return true
            Thread.sleep(10)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return condition()
    }

    /** [waitUntil] [condition], failing with [message] — built after the wait, so it shows the final state. */
    private fun assertEventually(message: () -> String, condition: () -> Boolean) {
        val met = waitUntil(condition = condition)
        assertTrue(message(), met)
    }

    private fun comboItems(combo: javax.swing.JComboBox<String>) = (0 until combo.itemCount).map { combo.getItemAt(it) }

    /** Hold the current-sprint lookup for [key] ("workspace|project ref") until the latch opens. */
    private fun holdCurrentSprint(key: String) = CountDownLatch(1).also { currentSprintHolds[key] = it }

    /** B's reload committed its project list. */
    private fun onWorkspaceB(hooks: RallyToolWindowPanel.TestHooks) =
        hooks.createDialogInputs().projects.map { it.ref } == listOf(ref("project", 2001))

    /**
     * A held current-sprint lookup was just released on [client]: wait until its apiExecutor
     * runs nothing — the lookup has then committed its answer or dropped it, and posted any EDT
     * update — and run those updates. (The lookup keeps its worker busy from the request until
     * it returns, so an idle pool can't be seen before the answer is handled.)
     */
    private fun awaitLateAnswer(client: RallyApiClient) {
        val pool = client.apiExecutor as ThreadPoolExecutor
        assertTrue("the late answer was handled", waitUntil { pool.activeCount == 0 })
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun requestsTo(type: String) = rally.requests.filter { it.path == "$API/$type" }
    private fun projectQueries() = requestsTo("project")
    /** The sprint-list queries (the current-sprint lookup for the summary carries a query). */
    private fun sprintListQueries() = requestsTo("iteration").filter { FakeRallyServer.queryParam(it, "query") == null }
    private fun currentSprintQueries() = requestsTo("iteration").filter { FakeRallyServer.queryParam(it, "query") != null }
    private fun ticketQueries() = requestsTo("hierarchicalrequirement") + requestsTo("defect") + requestsTo("testcase")

    private fun workspaceOf(uri: URI): String? =
        FakeRallyServer.queryParam(uri, "workspace")?.substringAfterLast("/workspace/")

    private fun ref(type: String, oid: Int) = "${rally.apiBase}/$type/$oid"

    /** The Project ref a user-story create POST [body] names, or null for none. */
    private fun createdProjectRef(body: String): String? =
        JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("HierarchicalRequirement").get("Project")?.asString

    /** [values] as a JSON array of strings (they carry no quote or backslash). */
    private fun jsonStrings(values: List<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }

    private fun routeRally() {
        fun reply(vararg items: String) = FakeRallyServer.Reply(200, FakeRallyServer.queryResult(items.toList()))
        fun project(oid: Int, name: String) =
            """{"_ref":"${ref("project", oid)}","ObjectID":"$oid","Name":"$name","State":"Open"}"""
        fun sprint(oid: Int, name: String) =
            """{"_ref":"${ref("iteration", oid)}","ObjectID":"$oid","Name":"$name",""" +
                """"StartDate":"2026-01-05T00:00:00.000Z","EndDate":"2026-01-16T00:00:00.000Z"}"""

        rally.route("$API/project") { exchange ->
            when (workspaceOf(exchange.requestURI)) {
                WS_A -> {
                    holdWorkspaceAProjects?.await(10, TimeUnit.SECONDS)
                    if (workspaceAHidesATeam) {
                        reply(project(1002, "A-Other"))
                    } else {
                        reply(project(1001, "A-Team"), project(1002, "A-Other"))
                    }
                }
                WS_B -> {
                    holdWorkspaceBLists?.await(10, TimeUnit.SECONDS)
                    when {
                        failWorkspaceBProjects -> FakeRallyServer.Reply(200, NOT_AUTHORIZED)
                        workspaceBHasATeam -> reply(project(2001, "B-Team"), project(2002, "A-Team"))
                        else -> reply(project(2001, "B-Team"))
                    }
                }
                else -> reply()
            }
        }
        rally.route("$API/iteration") { exchange ->
            val uri = exchange.requestURI
            // The sprint summary's current-sprint lookup (see currentSprints).
            if (FakeRallyServer.queryParam(uri, "query") != null) {
                val workspace = workspaceOf(uri)
                if (workspace == WS_B) holdWorkspaceBCurrentSprint?.await(10, TimeUnit.SECONDS)
                val project = FakeRallyServer.queryParam(uri, "project") ?: ""
                currentSprintHolds["$workspace|$project"]?.await(10, TimeUnit.SECONDS)
                return@route when (val name = currentSprints["$workspace|$project"]) {
                    null -> reply()
                    FAIL -> FakeRallyServer.Reply(200, NOT_AUTHORIZED)
                    else -> reply(sprint(9001, name))
                }
            }
            when (FakeRallyServer.queryParam(uri, "project")) {
                ref("project", 1001) -> {
                    holdATeamSprints?.await(10, TimeUnit.SECONDS)
                    reply(sprint(5001, "Sprint 1"))
                }
                ref("project", 1002) -> {
                    holdAOtherSprints?.await(10, TimeUnit.SECONDS)
                    if (failAOtherSprints) FakeRallyServer.Reply(200, NOT_AUTHORIZED) else reply(sprint(6001, "Sprint 1"))
                }
                ref("project", 2001) -> reply(sprint(8001, "B Sprint"))
                null -> when (workspaceOf(uri)) {
                    WS_A -> reply(sprint(5001, "Sprint 1"), sprint(6001, "Sprint 1"))
                    WS_B -> {
                        holdWorkspaceBLists?.await(10, TimeUnit.SECONDS)
                        reply(sprint(8001, "B Sprint"))
                    }
                    else -> reply()
                }
                else -> reply()
            }
        }
        rally.route("$API/hierarchicalrequirement") {
            reply("""{"_ref":"${ref("hierarchicalrequirement", 7001)}","FormattedID":"$storyId","Name":"Story","ScheduleState":"Defined"}""")
        }
        rally.route("$API/hierarchicalrequirement/7001") {
            val errors = storyUpdateError?.let { listOf(it) } ?: emptyList()
            FakeRallyServer.Reply(200, """{"OperationResult":{"Errors":${jsonStrings(errors)},"Warnings":[]}}""")
        }
        rally.route("$API/defect") {
            reply("""{"_ref":"${ref("defect", 7002)}","FormattedID":"DE1","Name":"Defect","ScheduleState":"Defined"}""")
        }
        rally.route("$API/hierarchicalrequirement/create") { exchange ->
            storyCreates.add(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            storyCreateError?.let { error ->
                return@route FakeRallyServer.Reply(200, """{"CreateResult":{"Errors":${jsonStrings(listOf(error))},"Warnings":[]}}""")
            }
            FakeRallyServer.Reply(
                200,
                """{"CreateResult":{"Errors":[],"Warnings":[],"Object":""" +
                    """{"_ref":"${ref("hierarchicalrequirement", 7100)}","FormattedID":"US100","Name":"New story"}}}"""
            )
        }
        Disposer.register(testRootDisposable) { rally.close() }
    }

    private companion object {
        const val API = "/slm/webservice/v2.0"
        const val WS_A = "111"
        const val WS_B = "222"
        /** A [currentSprints] value: the lookup fails. */
        const val FAIL = "<fail>"
        /** The footer after a failed current-sprint lookup. */
        const val SPRINT_FAILED = "Sprint: unable to load"
        /** Rally's HTTP-200-with-Errors answer to a query. */
        const val NOT_AUTHORIZED =
            """{"QueryResult":{"Errors":["Not authorized"],"Warnings":[],"TotalResultCount":0,"Results":[]}}"""
    }
}
