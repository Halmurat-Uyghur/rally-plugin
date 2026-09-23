package com.github.halmurat.rally.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.github.halmurat.rally.api.ArtifactQueryResult
import com.github.halmurat.rally.api.RallyApiClient
import com.github.halmurat.rally.api.RallyArtifact
import com.github.halmurat.rally.api.RallyType
import com.github.halmurat.rally.api.storyPoints
import com.github.halmurat.rally.api.effectiveStateOrEmpty
import com.github.halmurat.rally.api.RallyDefect
import com.github.halmurat.rally.api.RallyTestCase
import com.github.halmurat.rally.api.RallyIteration
import com.github.halmurat.rally.api.RallyProject
import com.github.halmurat.rally.api.RallyTaskItem
import com.github.halmurat.rally.api.RallyUser
import com.github.halmurat.rally.api.RallyUserStory
import com.github.halmurat.rally.export.RallyExporter
import com.github.halmurat.rally.settings.RallySettings
import com.github.halmurat.rally.settings.RallyApiKeyUnavailableException
import com.github.halmurat.rally.settings.RallySettingsListener
import com.github.halmurat.rally.util.RallyGitOps

import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class RallyToolWindowPanel(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(RallyToolWindowPanel::class.java)
        // Combo arrays derive from the Scope/StateFilter enums (RallyFilters.kt) so the
        // display strings have one source of truth and the filter branches compare against
        // typed enums instead of bare literals (MED-13).
        private val SCOPE_OPTIONS = Scope.entries.map { it.displayName }.toTypedArray()
        private const val DIVIDER_THICKNESS = 3
        private const val START_WORKING_TOOLTIP = "<html><b>Start working on the selected ticket</b><br>" +
            "1. Creates (or checks out) a git branch, e.g. <code>feature/US123</code> — you pick the prefix<br>" +
            "2. Moves the ticket to In-Progress<br>" +
            "3. Assigns it to you (the Username in Settings)<br>" +
            "The Rally ticket is left unchanged if the branch can't be checked out.</html>"
        private val STATE_OPTIONS = StateFilter.entries.map { it.displayName }.toTypedArray()
    }

    private val mainPanel = JPanel(BorderLayout())
    private val listModel = DefaultListModel<RallyArtifact>()
    private val artifactList = object : JBList<RallyArtifact>(listModel) {
        // Clamp every cell to the viewport width. Without this, BasicListUI lays rows out
        // at their (potentially huge) preferred width when a ticket name is long: a
        // horizontal scrollbar appears and the EAST column of the cell renderer (owner +
        // state badge) is pushed off the visible edge, forcing the user to scroll to see
        // the assignee. Returning true keeps each row at the visible width so the center
        // name truncates with an ellipsis (full name stays in the row tooltip) while the
        // owner/badge stay pinned to the right edge — and re-clamp automatically on resize.
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }
    private val scopeCombo = ComboBox(SCOPE_OPTIONS)
    private val stateCombo = ComboBox(STATE_OPTIONS)
    private val projectCombo = ComboBox<String>().apply { isEnabled = false }
    private val iterationCombo = ComboBox<String>().apply { isEnabled = false }
    private val searchField = SearchTextField()
    private val searchDebounceTimer = javax.swing.Timer(300) { applySearchFilter() }.apply { isRepeats = false }
    /**
     * Debounces selection → network-backed detail loads (M2). Header/badge update
     * instantly on selection (cheap, local data); the 3 API queries + description
     * fetch fire only after the selection has settled for 200 ms, so holding ↓
     * through the list costs zero API calls for skipped rows. Mirrors the search
     * field's 300 ms debounce.
     */
    private val selectionDebounceTimer = javax.swing.Timer(200) {
        if (!disposed) detailPanel.loadDetails()
    }.apply { isRepeats = false }
    private val statsLabel = JBLabel("0 items")
    private val sprintLabel = JBLabel("")
    private val statusLabel = JBLabel("Ready")
    /** Cached at construction time so the EDT-side selection listener doesn't reflectively probe Class.forName on every selection change. */
    private val gitAvailable: Boolean = RallyGitOps.isAvailable()
    private val startWorkingButton = JButton("Start Working", AllIcons.Actions.Execute).apply {
        isFocusable = true
        toolTipText = START_WORKING_TOOLTIP
        // Start Working creates a git branch; if the IDE ships without Git4Idea,
        // there's nothing the button can do — keep it visible but disabled so the
        // affordance is obvious.
        if (!gitAvailable) {
            isEnabled = false
            toolTipText = "Git integration is not available in this IDE"
        }
    }


    private val detailPanel = RallyDetailPanel(project)
    private var mainSplitPane: JSplitPane? = null

    @Volatile private var allArtifacts: List<RallyArtifact> = emptyList()
    // Sprint-summary state. Metadata (name/dates/velocity) comes from the resolved
    // iteration; the counts/points are derived from `displayedArtifacts` so they track
    // the active Scope/State/Search filter. `sprintIteration` is written on a pooled
    // thread and read on the EDT (hence @Volatile); `displayedArtifacts` is EDT-confined.
    @Volatile private var sprintIteration: RallyIteration? = null
    private var displayedArtifacts: List<RallyArtifact> = emptyList()
    // Whether the last loadTickets() returned a partial result (one of stories/defects failed).
    // Remembered so a later client-side state-filter change can re-render the "N loaded" status
    // with the right count AND keep the "(incomplete)" + warning marker (LOW-9 follow-up).
    private var lastLoadIncomplete = false
    /** How many rows the last loadTickets() actually fetched, and Rally's server-side
     *  total (-1 unknown) — kept so refreshLoadedStatus can re-render "X of Y loaded"
     *  after a client-side state-filter change (M3). */
    private var lastLoadFetched = 0
    private var lastLoadTotal = -1
    /** Whether the most recent load succeeded (EDT-confined). False before the first load,
     *  after a failed one (the rows of an earlier load may still be on screen), and while
     *  Rally isn't configured — the cases where a State change also retries the load
     *  ([stateChangeAction]) and the empty-list placeholder keeps its error text. */
    private var lastLoadSucceeded = false
    /** The list placeholder a failed load / "Not configured" set, restored by [updateEmptyText]
     *  after a search pass temporarily replaced it (EDT-confined). */
    private var loadFailurePlaceholder: String? = null
    /** Bumped on every [applySearchFilter] pass (EDT-confined); a server search applies its
     *  results only if no newer pass has run since it started. */
    private var searchGeneration = 0L
    @Volatile private var currentClient: RallyApiClient? = null
    @Volatile private var loading = false
    @Volatile private var pendingReload = false
    @Volatile private var cachedProjects: List<RallyProject> = emptyList()
    @Volatile private var cachedIterations: List<RallyIteration> = emptyList()
    @Volatile private var projectsLoaded = false
    @Volatile private var iterationsLoaded = false

    /**
     * Monotonic ticket for iteration loads. loadTickets() releases its `loading`
     * lock as soon as the artifact list arrives, while the iterations fetch may
     * still be in flight — so a project switch can start a new iterations load
     * while the previous project's is unfinished. Each load takes a generation
     * at submit time and discards its result if a newer generation exists,
     * preventing a slow stale response from repopulating cachedIterations (and
     * the Sprint dropdown the create dialogs index into) with the wrong
     * project's sprints.
     *
     * The generation is ALSO bumped at every invalidation point (project switch,
     * settings change, client rebuild — see [invalidateIterations]), not just at
     * submit time. Otherwise an in-flight load that nothing has superseded yet
     * would commit its now-wrong-project results, set iterationsLoaded=true, and
     * thereby suppress the pending reload's own iterations fetch — leaving the
     * old project's sprints in place indefinitely.
     */
    private val iterationLoadGeneration = java.util.concurrent.atomic.AtomicLong()

    /** Guards the generation-check + cachedIterations write so a stale load can't
     *  interleave its commit between a newer load's check and write. */
    private val iterationCommitLock = Any()

    /** Invalidate the iteration dropdown AND any in-flight load (see [iterationLoadGeneration]). */
    private fun invalidateIterations() {
        iterationsLoaded = false
        iterationLoadGeneration.incrementAndGet()
    }

    /**
     * Monotonic ticket for project loads — the twin of [iterationLoadGeneration],
     * guarding the same hazard: loadTickets() releases `loading` while the async
     * project fetch (M6) may still be in flight, so a settings change can start a
     * new load while the old one is unfinished. Without the generation check, the
     * stale load's commit would overwrite cachedProjects/the dropdown/projectsLoaded
     * with old-server data — and the settings-snapshot check would then suppress
     * any retry. Bumped at submit time and at the client-rebuild invalidation
     * point in [getClient].
     */
    private val projectLoadGeneration = java.util.concurrent.atomic.AtomicLong()

    /** Guards the generation-check + cachedProjects write so a stale load can't
     *  interleave its commit between a newer load's check and write. */
    private val projectCommitLock = Any()
    @Volatile private var lastSettingsSnapshot: String = ""
    private var lastScope: String = ""
    private var lastState: String = ""
    private var lastProject: String = ""
    private var lastIteration: String = ""
    @Volatile private var activeServerSearch: String? = null
    @Volatile private var disposed = false
    private val clientLock = Any()

    init {
        setupUI()
        setupListeners()
        // Reload when Settings → Tools → Rally is applied (L9). connect(this) ties the
        // subscription to this panel's Disposable, so it detaches on dispose.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(RallySettingsListener.TOPIC, object : RallySettingsListener {
                override fun settingsApplied() {
                    invokeLaterIfAlive { loadTickets() }
                }
            })
        checkInitialConfiguration()
    }

    /**
     * Executes [action] on the EDT, guarded against disposal.
     * Replaces the `invokeLater { if (project.isDisposed || disposed) return@invokeLater ... }` pattern.
     */
    private inline fun invokeLaterIfAlive(crossinline action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || disposed) return@invokeLater
            action()
        }
    }

    private fun checkInitialConfiguration() {
        // Check configuration off-EDT so the PasswordSafe preload can complete. The read can
        // outlast isConfigured()'s 2s wait (keychain access prompt, KeePass master password);
        // reading that as "Not configured" would stick until a manual Refresh, so keep waiting.
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = RallySettings.getInstance()
            if (settings.serverUrl.isNotBlank() && settings.awaitApiKey(2_000L) == null) {
                if (!settings.apiKeyLoadFailed) {
                    invokeLaterIfAlive { showApiKeyUnavailable(RallyApiKeyUnavailableException(loadFailed = false)) }
                }
                // No upper bound on purpose: an unanswered keychain prompt is a legitimate wait.
                // A read that throws sets apiKeyLoadFailed, which ends the wait.
                while (!disposed && !project.isDisposed && !Thread.currentThread().isInterrupted &&
                    !settings.apiKeyLoadFailed && settings.awaitApiKey(5_000L) == null) {
                    // keep waiting; the PasswordSafe read is still in flight
                }
                if (disposed || project.isDisposed) return@executeOnPooledThread
                if (settings.apiKeyLoadFailed) {
                    invokeLaterIfAlive { showApiKeyUnavailable(RallyApiKeyUnavailableException(loadFailed = true)) }
                    return@executeOnPooledThread
                }
            }
            val configured = settings.isConfigured()
            invokeLaterIfAlive {
                if (configured) {
                    loadTickets()
                } else {
                    showNotConfigured()
                }
            }
        }
    }

    private fun showNotConfigured() {
        lastLoadSucceeded = false
        statusLabel.icon = AllIcons.General.Error
        statusLabel.text = "Not configured"
        val placeholder = "Configure Rally in Settings → Tools → Rally"
        loadFailurePlaceholder = placeholder
        artifactList.emptyText.text = placeholder
    }

    /**
     * The API key is still loading (no error: the startup task loads the list once it lands)
     * or its read failed (warning: re-entering the key in Settings recovers).
     */
    private fun showApiKeyUnavailable(e: RallyApiKeyUnavailableException) {
        lastLoadSucceeded = false
        statusLabel.icon = if (e.loadFailed) AllIcons.General.Warning else null
        statusLabel.text = if (e.loadFailed) "API key unavailable" else "Waiting for API key..."
        val placeholder = e.message ?: ""
        loadFailurePlaceholder = placeholder
        artifactList.emptyText.text = placeholder
    }

    private fun clearStatusIcon() {
        statusLabel.icon = null
    }

    fun getContent(): JComponent = mainPanel

    // ── UI Setup ─────────────────────────────────────────────────

    private fun setupUI() {
        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.add(createButton("Refresh", AllIcons.Actions.Refresh,
            "Clear the cache and reload tickets from Rally") { currentClient?.clearCache(); loadTickets() })
        val createButton = createButton("Create", AllIcons.General.Add, "Create a new User Story or Defect in Rally") {}
        createButton.addActionListener {
            val menu = JPopupMenu()
            menu.add(JMenuItem("User Story").apply { addActionListener { showCreateUserStoryDialog() } })
            menu.add(JMenuItem("Defect").apply { addActionListener { showCreateDefectDialog() } })
            menu.show(createButton, 0, createButton.height)
        }
        toolbar.add(createButton)
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("Defined", AllIcons.Actions.MoveToButton,
            "Move the selected ticket(s) to Defined") { changeState("Defined") })
        toolbar.add(createButton("In-Progress", AllIcons.Actions.Execute,
            "Move the selected ticket(s) to In-Progress (state only — no branch, no owner change)") { changeState("In-Progress") })
        toolbar.add(createButton("Completed", AllIcons.Actions.Checked,
            "Move the selected ticket(s) to Completed") { changeState("Completed") })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("Export", AllIcons.ToolbarDecorator.Export,
            "Export the selected ticket(s) and their linked test cases to JSON and Markdown") { exportSelectedArtifact() })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(startWorkingButton)

        startWorkingButton.addActionListener { startWorking() }

        toolbar.add(Box.createHorizontalGlue())
        toolbar.add(statsLabel)

        // Filter row — labels link to their controls via labelFor so screen readers
        // can announce the field name when focus moves into a combo box.
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        filterPanel.add(JBLabel("Scope:").apply { labelFor = scopeCombo })
        filterPanel.add(scopeCombo)
        filterPanel.add(JBLabel("State:").apply { labelFor = stateCombo })
        filterPanel.add(stateCombo)
        filterPanel.add(JBLabel("Project:").apply { labelFor = projectCombo })
        projectCombo.apply {
            preferredSize = java.awt.Dimension(250, preferredSize.height)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    toolTipText = value?.toString()
                    return comp
                }
            }
            // Show full-width popup regardless of combo box width
            isSwingPopup = false
        }
        filterPanel.add(projectCombo)
        filterPanel.add(JBLabel("Sprint:").apply { labelFor = iterationCombo })
        iterationCombo.apply {
            preferredSize = java.awt.Dimension(250, preferredSize.height)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val name = value?.toString() ?: ""
                    // Snapshot cachedIterations into a local — a project switch on a
                    // background thread could replace the list out from under us
                    // between the bounds check and the index access.
                    val snapshot = cachedIterations
                    // Look up iteration dates from cache (index 0 = "All Sprints", so offset by 1)
                    val iterIndex = if (index > 0) index - 1 else -1
                    if (iterIndex in snapshot.indices) {
                        val iter = snapshot[iterIndex]
                        val start = iter.startDate?.take(10) ?: ""
                        val end = iter.endDate?.take(10) ?: ""
                        if (start.isNotBlank() && end.isNotBlank()) {
                            text = "$name  ($start → $end)"
                            toolTipText = "$name: $start to $end"
                        }
                    } else {
                        toolTipText = name
                    }
                    return comp
                }
            }
            isSwingPopup = false
        }
        filterPanel.add(iterationCombo)
        filterPanel.add(JBLabel("Search:").apply { labelFor = searchField.textEditor })
        searchField.preferredSize = java.awt.Dimension(200, searchField.preferredSize.height)
        searchField.textEditor.emptyText.text = "Search by name or ID..."
        filterPanel.add(searchField)

        // Top section
        val topPanel = JPanel(BorderLayout())
        topPanel.add(toolbar, BorderLayout.NORTH)
        topPanel.add(filterPanel, BorderLayout.SOUTH)

        // Artifact list
        artifactList.cellRenderer = ArtifactCellRenderer()
        // Pin the row height from one prototype render. Without fixedCellHeight,
        // BasicListUI calls the renderer + getPreferredSize() for EVERY element on
        // EVERY model event (each debounced keystroke, every refresh) to compute row
        // heights — O(n) nested-layout passes for rows that are all the same height.
        val prototype = RallyUserStory(
            formattedID = "US00000", name = "Prototype", scheduleState = "In-Progress",
            owner = RallyUser(displayName = "Prototype Owner")
        )
        artifactList.fixedCellHeight = artifactList.cellRenderer
            .getListCellRendererComponent(artifactList, prototype, 0, false, false)
            .preferredSize.height
        artifactList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        artifactList.emptyText.text = "No tickets loaded. Configure Rally in Settings → Tools → Rally, then click Refresh."
        val scrollPane = JBScrollPane(artifactList)

        // Split pane: ticket list (left) + detail panel (right)
        // Detail panel starts collapsed; expands when a ticket is selected
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollPane, detailPanel.component)
        splitPane.resizeWeight = 1.0
        splitPane.border = null
        splitPane.dividerSize = 0
        splitPane.setUI(ThinDividerSplitPaneUI())
        detailPanel.component.minimumSize = java.awt.Dimension(0, 0)
        mainSplitPane = splitPane
        // Push divider to the right edge after layout completes
        SwingUtilities.invokeLater { splitPane.dividerLocation = splitPane.width }

        // Bottom sprint + status
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = JBUI.Borders.empty(2, 6)
        bottomPanel.add(sprintLabel, BorderLayout.CENTER)
        bottomPanel.add(statusLabel, BorderLayout.EAST)

        // Assemble
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun createButton(text: String, icon: Icon, tooltip: String, action: () -> Unit): JButton {
        return JButton(text, icon).apply {
            isFocusable = true
            toolTipText = tooltip
            addActionListener { action() }
        }
    }

    private fun setupListeners() {
        // Scope/state change (with duplicate-selection guard)
        scopeCombo.addActionListener {
            val newScope = scopeCombo.selectedItem as? String ?: return@addActionListener
            if (newScope == lastScope) return@addActionListener
            lastScope = newScope
            loadTickets()
        }
        stateCombo.addActionListener {
            val newState = stateCombo.selectedItem as? String ?: return@addActionListener
            if (newState == lastState) return@addActionListener
            lastState = newState
            // State filtering is purely client-side (applyStateFilter over the already-loaded
            // scope list), so a state change re-filters in memory with NO network round trip
            // (P6/LOW-9) — unless there is no loaded list to re-filter (see stateChangeAction).
            // Scope/project/sprint changes still call loadTickets() because they can require a
            // different query.
            when (stateChangeAction(lastLoadSucceeded, loading)) {
                StateChangeAction.RELOAD -> {
                    // Re-filter what's on screen (rows of an earlier successful load survive a
                    // failed refresh) and retry; the error placeholder is kept (updateEmptyText).
                    applySearchFilter()
                    loadTickets()
                }
                StateChangeAction.REFILTER_KEEP_STATUS -> applySearchFilter()
                StateChangeAction.REFILTER -> {
                    applySearchFilter()
                    // applySearchFilter() updates statsLabel but not the "N loaded" status. Skip
                    // while a server search is pending — that path owns statusLabel
                    // ("Searching Rally...", "N found via server search").
                    if (activeServerSearch == null) refreshLoadedStatus()
                }
            }
        }

        // Project change — also reset iteration cache since iterations are project-scoped
        projectCombo.addActionListener {
            if (projectsLoaded) {
                val newProject = projectCombo.selectedItem as? String ?: return@addActionListener
                if (newProject == lastProject) return@addActionListener
                lastProject = newProject
                updateClientProjectRef(projectCombo.selectedIndex)
                RallySettings.getInstance().selectedProject = newProject
                invalidateIterations()
                lastIteration = ""
                loadTickets()
            }
        }

        // Iteration change
        iterationCombo.addActionListener {
            if (iterationsLoaded) {
                val newIteration = iterationCombo.selectedItem as? String ?: return@addActionListener
                if (newIteration == lastIteration) return@addActionListener
                lastIteration = newIteration
                RallySettings.getInstance().selectedIteration = newIteration
                loadTickets()
            }
        }

        // Search as you type (debounced — waits 300ms after last keystroke)
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                searchDebounceTimer.restart()
            }
        })

        // Selection listener for detail panel
        artifactList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = artifactList.selectedValue
                if (selected != null && detailPanel.isShowing(selected)) {
                    // Same object re-selected (e.g. selection restored after a model
                    // rebuild) — the panel is already rendering it; skip the reload
                    // to avoid tab flicker and duplicate loads.
                } else {
                    selectionDebounceTimer.stop()
                    detailPanel.showArtifactHeader(selected, currentClient)
                    if (selected != null) selectionDebounceTimer.restart()
                }
                val sp = mainSplitPane ?: return@addListSelectionListener
                if (selected != null) {
                    // Auto-expand detail panel if collapsed
                    val detailWidth = sp.width - sp.dividerLocation - sp.dividerSize
                    if (detailWidth < 100) {
                        sp.dividerSize = DIVIDER_THICKNESS
                        sp.dividerLocation = (sp.width * 0.55).toInt()
                    }
                    val isTc = selected is RallyTestCase
                    // Start Working stays disabled when Git4Idea is absent regardless of selection
                    // — clicking it has nowhere to go without git operations.
                    startWorkingButton.isEnabled = !isTc && gitAvailable
                } else {
                    // Auto-collapse detail panel when nothing is selected
                    sp.dividerSize = 0
                    sp.dividerLocation = sp.width
                }
            }
        }

        // Double-click to open in browser
        artifactList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    openInBrowser()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })

        // Enter key to open in browser (same as double-click)
        artifactList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    openInBrowser()
                }
            }
        })
    }

    // ── Context Menu ─────────────────────────────────────────────

    private fun showContextMenu(e: MouseEvent) {
        val index = artifactList.locationToIndex(e.point)
        if (index < 0) return
        if (!artifactList.isSelectedIndex(index)) {
            artifactList.selectedIndex = index
        }
        val selected = artifactList.selectedValue

        val menu = JPopupMenu()
        menu.add(JMenuItem("Open in Browser").apply { addActionListener { openInBrowser() } })
        menu.add(JMenuItem("Copy FormattedID").apply { addActionListener { copyFormattedId() } })
        if (selected !is RallyTestCase) {
            menu.addSeparator()
            menu.add(JMenuItem("Set In-Progress").apply { addActionListener { changeState("In-Progress") } })
            menu.add(JMenuItem("Set Completed").apply { addActionListener { changeState("Completed") } })
            menu.add(JMenuItem("Set Defined").apply { addActionListener { changeState("Defined") } })
            menu.add(JMenuItem("Edit Points").apply { addActionListener { editPoints() } })
            menu.addSeparator()
            menu.add(JMenuItem("Export to JSON/Markdown").apply { addActionListener { exportSelectedArtifact() } })
        }
        menu.show(artifactList, e.x, e.y)
    }

    // ── Data Loading ─────────────────────────────────────────────

    fun loadTickets() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfiguredOrLoading()) {
            showNotConfigured()
            return
        }

        if (loading) {
            pendingReload = true
            return
        }
        loading = true
        pendingReload = false
        clearStatusIcon()
        statusLabel.text = "Loading..."

        // Capture all UI state on the EDT before dispatching to background thread
        val scope = scopeCombo.selectedItem as? String ?: "My Tickets"
        val pageSize = if (Scope.fromDisplay(scope) == Scope.RECENT_ACTIVITY) 20 else settings.pageSize

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()

                // Load projects if not yet loaded or settings changed.
                // Use a non-sensitive SHA-256 fingerprint of the API key instead of String.hashCode()
                // so two distinct keys can't collide and mask a credential change.
                val snapshot = "${settings.serverUrl}|${apiKeyFingerprint(settings.apiKey)}|${settings.workspaceRef}"
                val savedProject = RallySettings.getInstance().selectedProject
                val projectScoped = savedProject.isNotBlank() && savedProject != "All Projects"
                var projectsFuture: java.util.concurrent.CompletableFuture<Void>? = null
                if (!projectsLoaded || snapshot != lastSettingsSnapshot) {
                    lastSettingsSnapshot = snapshot
                    // invalidateIterations() moved before the load safely: it only flips
                    // a flag and bumps a generation, so its order relative to loadProjects
                    // is immaterial.
                    invalidateIterations()
                    // The serial project load is only needed when a saved project NAME must
                    // be resolved to a ref before the artifact query can be scoped to it. In
                    // the default "All Projects" case the list feeds nothing but the dropdown,
                    // so it loads concurrently with the artifact fetch (M6) — the same pattern
                    // the iterations list uses. Saves a full RTT on cold open.
                    val generation = projectLoadGeneration.incrementAndGet()
                    if (projectScoped) {
                        loadProjects(client, generation)
                    } else {
                        projectsFuture = java.util.concurrent.CompletableFuture.runAsync({
                            loadProjects(client, generation)
                        }, client.apiExecutor)
                    }
                }

                // Determine effective project selection from saved settings + cached data
                // (avoids reading Swing state off-EDT)
                val effectiveProjectIndex = if (projectScoped) {
                    val idx = cachedProjects.indexOfFirst { it.name == savedProject }
                    if (idx >= 0) idx + 1 else 0  // +1 for "All Projects" offset
                } else 0
                updateClientProjectRef(effectiveProjectIndex)

                // Load iterations if not yet loaded (or reset after project change).
                // When no saved sprint must be validated against the list (the default,
                // and the state after every project switch), the iteration list only
                // feeds the dropdown — run it concurrently with the artifact fetch
                // instead of paying a serial round trip before it.
                val savedIter = RallySettings.getInstance().selectedIteration
                val needsIterationValidation = savedIter.isNotBlank() && savedIter != "All Sprints"
                var iterationsFuture: java.util.concurrent.CompletableFuture<Void>? = null
                if (!iterationsLoaded) {
                    val generation = iterationLoadGeneration.incrementAndGet()
                    if (needsIterationValidation) {
                        loadIterations(client, generation)
                    } else {
                        iterationsFuture = java.util.concurrent.CompletableFuture.runAsync({
                            loadIterations(client, generation)
                        }, client.apiExecutor)
                    }
                }

                val effectiveIter = if (needsIterationValidation &&
                    cachedIterations.any { it.name == savedIter }) savedIter else ""
                val query = buildQuery(scope, effectiveIter, settings)
                val hasIterationFilter = effectiveIter.isNotBlank() && effectiveIter != "All Sprints"

                // Sprint summary (apiExecutor) — submit FIRST so it runs concurrently with the
                // artifact fetch below. The summary only needs the iteration's metadata now
                // (its counts/points are derived from the filtered list in renderSprintSummary),
                // so the only background work is finding the current iteration when no specific
                // sprint is selected.
                val sprintFuture = if (!hasIterationFilter) {
                    // "All Sprints" — resolve the current iteration (by today's date) for metadata.
                    java.util.concurrent.CompletableFuture.runAsync({
                        loadSprintSummary(client, settings)
                    }, client.apiExecutor)
                } else null

                // Load artifacts. queryAllArtifactsParallel runs user stories + defects
                // concurrently (defects on apiExecutor, stories inline on THIS unbounded pooled
                // thread), halving the two serial round trips the old sequential story-then-defect
                // path paid on every cold load (MED-2/P1). It returns an ArtifactQueryResult carrying
                // any partial-failure reasons (MED-8). Run it directly on this executeOnPooledThread
                // thread — NOT on apiExecutor — because it blocks on one apiExecutor slot internally.
                val result: ArtifactQueryResult = if (Scope.fromDisplay(scope) == Scope.TEST_CASES) {
                    val page = client.queryAllTestCases(query, pageSize, maxResults = pageSize)
                    ArtifactQueryResult(page.items, totalAvailable = page.totalResultCount)
                } else {
                    client.queryAllArtifactsParallel(query, pageSize, scope = scope, maxResults = pageSize)
                }
                val artifacts = result.artifacts

                // A specific sprint is selected — resolve its metadata from cache (no API call).
                if (hasIterationFilter) {
                    resolveSelectedSprint(effectiveIter)
                }

                // Apply only the scope (type) filter now; the state filter is applied at display
                // time in applySearchFilter so a state-combo change re-filters in memory with no
                // re-fetch (P6/LOW-9).
                val scopeFiltered = applyScopeFilter(scope, artifacts)

                invokeLaterIfAlive {
                    allArtifacts = scopeFiltered
                    lastLoadSucceeded = true
                    lastLoadIncomplete = result.isPartial
                    lastLoadFetched = artifacts.size
                    lastLoadTotal = result.totalAvailable
                    detailPanel.clear()
                    applySearchFilter()
                    loading = false
                    // Count with the State filter selected NOW, not the one captured when the load
                    // started: a State change mid-load only re-filters. A partial result (one of
                    // stories/defects failed) is flagged "(incomplete)" (MED-8). A server search
                    // started by applySearchFilter() owns the status line.
                    if (activeServerSearch == null) refreshLoadedStatus()
                    // applySearchFilter() above already refreshes the empty placeholder when the
                    // displayed list is empty (updateEmptyText), covering search + state filters.
                    if (pendingReload) {
                        pendingReload = false
                        loadTickets()
                    }
                }

                // Wait for sprint summary to finish (it updates UI itself)
                if (sprintFuture != null) {
                    try { sprintFuture.get() } catch (e: Exception) {
                        LOG.warn("Failed to load sprint summary", e)
                    }
                }

                // loadIterations handles its own errors and UI updates; join so this
                // background task's lifetime covers all the work it spawned
                // (loadTickets cycles are serialized by the loading/pendingReload flags).
                if (iterationsFuture != null) {
                    try { iterationsFuture.get() } catch (e: Exception) {
                        LOG.warn("Failed to load iterations", e)
                    }
                }

                // loadProjects handles its own errors and UI updates; join so this
                // background task's lifetime covers all the work it spawned.
                if (projectsFuture != null) {
                    try { projectsFuture.get() } catch (e: Exception) {
                        LOG.warn("Failed to load projects", e)
                    }
                }

            } catch (e: Exception) {
                LOG.warn("Failed to load Rally tickets", e)
                invokeLaterIfAlive {
                    loading = false
                    lastLoadSucceeded = false
                    if (pendingReload) {
                        pendingReload = false
                        loadTickets()
                    } else if (e is RallyApiKeyUnavailableException) {
                        showApiKeyUnavailable(e)
                    } else {
                        statusLabel.icon = AllIcons.General.Error
                        val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
                        val errorMsg = when {
                            cause.message?.contains("401") == true || cause.message?.contains("403") == true -> "Auth error"
                            cause.message?.contains("429") == true -> "Rate limited"
                            cause is java.net.ConnectException || cause is java.net.UnknownHostException -> "Network error"
                            cause.message?.contains("timeout", ignoreCase = true) == true -> "Timeout"
                            else -> "Error"
                        }
                        statusLabel.text = errorMsg
                        val placeholder = "$errorMsg: ${cause.message}"
                        loadFailurePlaceholder = placeholder
                        artifactList.emptyText.text = placeholder
                    }
                }
            }
        }
    }

    /**
     * Non-sensitive fingerprint of the API key: first 16 hex chars of SHA-256.
     * Used only to detect credential changes — never logged or persisted.
     */
    private fun apiKeyFingerprint(key: String): String {
        if (key.isEmpty()) return ""
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(16)
            // Mask each byte to an unsigned int before formatting so negative bytes
            // render as 2 hex chars (e.g. 0x80) instead of 8 (e.g. ffffff80) — the
            // sign extension would otherwise blow past the 16-char fingerprint
            // contract and inflate the snapshot string used to detect credential changes.
            for (i in 0 until 8) sb.append("%02x".format(hash[i].toInt() and 0xff))
            sb.toString()
        } catch (_: Exception) {
            key.length.toString()
        }
    }

    private fun loadProjects(client: RallyApiClient, generation: Long) {
        try {
            val projects = client.queryProjects()
            // Check-and-commit atomically: without the lock, a stale load could pass
            // the check, get descheduled, and overwrite a newer load's list after it
            // committed — leaving cachedProjects out of sync with the dropdown.
            synchronized(projectCommitLock) {
                if (generation != projectLoadGeneration.get()) return  // stale — a newer load owns the dropdown
                cachedProjects = projects
            }

            invokeLaterIfAlive {
                if (generation != projectLoadGeneration.get()) return@invokeLaterIfAlive
                // Temporarily remove listener to avoid triggering loadTickets during population
                val listeners = projectCombo.actionListeners
                listeners.forEach { projectCombo.removeActionListener(it) }

                projectCombo.removeAllItems()
                projectCombo.addItem("All Projects")
                projects.forEach { projectCombo.addItem(it.name ?: "Unnamed") }
                projectCombo.isEnabled = true

                // Restore saved project selection
                val saved = RallySettings.getInstance().selectedProject
                if (saved.isNotBlank() && saved != "All Projects") {
                    val stillExists = projects.any { it.name == saved }
                    if (stillExists) {
                        projectCombo.selectedItem = saved
                    } else {
                        projectCombo.selectedIndex = 0
                    }
                } else {
                    projectCombo.selectedIndex = 0
                }

                projectsLoaded = true

                // Re-attach listeners
                listeners.forEach { projectCombo.addActionListener(it) }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to load projects", e)
            invokeLaterIfAlive {
                // A stale failure must not clobber a newer load's dropdown either.
                if (generation != projectLoadGeneration.get()) return@invokeLaterIfAlive
                projectCombo.removeAllItems()
                projectCombo.addItem("All Projects")
                projectCombo.isEnabled = false
                // Leave projectsLoaded = false so next loadTickets() retries
                notifyLoadFailure("projects", e)
            }
        }
    }

    private fun loadIterations(client: RallyApiClient, generation: Long) {
        try {
            // Dedupe by name: the combo is name-keyed and the create dialogs map
            // combo index → cachedIterations[index-1], so duplicate names (possible
            // workspace-wide under "All Projects") would make that mapping ambiguous.
            val iterations = client.queryIterations().distinctBy { it.name }
            // Check-and-commit atomically: without the lock, a stale load could pass
            // the check, get descheduled, and overwrite a newer load's list after it
            // committed — leaving cachedIterations out of sync with the dropdown.
            synchronized(iterationCommitLock) {
                if (generation != iterationLoadGeneration.get()) return  // stale — a newer load owns the dropdown
                cachedIterations = iterations
            }

            // Populate combo on EDT without blocking the pooled thread
            invokeLaterIfAlive {
                if (generation != iterationLoadGeneration.get()) return@invokeLaterIfAlive
                val listeners = iterationCombo.actionListeners
                listeners.forEach { iterationCombo.removeActionListener(it) }

                iterationCombo.removeAllItems()
                iterationCombo.addItem("All Sprints")
                iterations.forEach { iterationCombo.addItem(it.name ?: "Unnamed") }
                iterationCombo.isEnabled = true

                // Restore saved iteration selection
                val saved = RallySettings.getInstance().selectedIteration
                if (saved.isNotBlank() && saved != "All Sprints") {
                    val stillExists = iterations.any { it.name == saved }
                    if (stillExists) {
                        iterationCombo.selectedItem = saved
                    } else {
                        iterationCombo.selectedIndex = 0
                    }
                } else {
                    iterationCombo.selectedIndex = 0
                }

                iterationsLoaded = true

                listeners.forEach { iterationCombo.addActionListener(it) }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to load iterations", e)
            invokeLaterIfAlive {
                // A stale failure must not clobber a newer load's dropdown either.
                if (generation != iterationLoadGeneration.get()) return@invokeLaterIfAlive
                iterationCombo.removeAllItems()
                iterationCombo.addItem("All Sprints")
                iterationCombo.isEnabled = false
                // Leave iterationsLoaded = false so next loadTickets() retries
                notifyLoadFailure("iterations", e)
            }
        }
    }

    /**
     * Show a non-modal balloon when a metadata load (projects or iterations) fails,
     * so the user knows the dropdown is empty because of an error rather than because
     * Rally legitimately returned no items.
     */
    private fun notifyLoadFailure(what: String, e: Exception) {
        // createHtmlTextBalloonBuilder treats its argument as HTML, so any '<' or '&'
        // in the exception message would either break the balloon or, for server-
        // provided strings, inject unintended markup. Escape both the label and the
        // message body before interpolation.
        val safeWhat = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(what)
        val rawMessage = e.message ?: e.javaClass.simpleName
        val safeMessage = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(rawMessage)
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(
                "Failed to load $safeWhat: $safeMessage",
                MessageType.WARNING,
                null
            )
            .setFadeoutTime(5000)
            .createBalloon()
        balloon.show(RelativePoint.getSouthWestOf(statusLabel), Balloon.Position.above)
    }

    private fun updateClientProjectRef(selectedIndex: Int) {
        val client = currentClient ?: return
        if (selectedIndex <= 0) {
            // "All Projects" or nothing selected — no project filter
            client.projectRef = null
        } else {
            val projectIndex = selectedIndex - 1 // offset for "All Projects"
            if (projectIndex < cachedProjects.size) {
                client.projectRef = cachedProjects[projectIndex].ref
            }
        }
    }

    private fun buildQuery(scope: String, selectedIter: String, settings: RallySettings): String? {
        // workspace/project are passed as URL params by the API client, not as query conditions.
        // State/type filtering is done client-side since ScheduleState vs State differs by type.
        // Query construction lives in buildTicketQuery (RallyFilters.kt) so the per-scope
        // iteration-field choice (H1) is unit-tested.
        val query = buildTicketQuery(scope, selectedIter, settings.username)
        LOG.info("Rally query built (scope=$scope, iteration='$selectedIter', hasQuery=${query != null})")
        return query
    }

    /** Scope (type) filter only. Split out from state filtering so state changes can be
     *  applied client-side without re-fetching (P6/LOW-9). */
    private fun applyScopeFilter(scope: String, artifacts: List<RallyArtifact>): List<RallyArtifact> =
        when (Scope.fromDisplay(scope)) {
            Scope.USER_STORIES -> artifacts.filter { it.type == RallyType.USER_STORY }
            Scope.DEFECTS -> artifacts.filter { it.type == RallyType.DEFECT }
            Scope.TEST_CASES -> artifacts.filter { it.type == RallyType.TEST_CASE }
            else -> artifacts
        }

    /** State filter only (client-side; ScheduleState vs State differs by artifact type). */
    private fun applyStateFilter(stateFilter: String, artifacts: List<RallyArtifact>): List<RallyArtifact> =
        when (StateFilter.fromDisplay(stateFilter)) {
            StateFilter.ACTIVE -> artifacts.filter { it.effectiveStateOrEmpty !in activeExcludedStates }
            StateFilter.ANY -> artifacts
            else -> artifacts.filter { it.effectiveStateOrEmpty.equals(stateFilter, ignoreCase = true) }
        }

    /** Combined scope + state filter — used by the server-search path, which starts from
     *  raw results and needs both applied at once. */
    private fun applyClientFilter(scope: String, stateFilter: String, artifacts: List<RallyArtifact>): List<RallyArtifact> =
        applyStateFilter(stateFilter, applyScopeFilter(scope, artifacts))

    /**
     * Thread-safe project ref lookup using cached data + persisted settings.
     * Safe to call from any thread (does not read Swing component state).
     */
    private fun getSelectedProjectRef(): String? {
        val saved = RallySettings.getInstance().selectedProject
        if (saved.isBlank() || saved == "All Projects") return null
        return cachedProjects.firstOrNull { it.name == saved }?.ref
    }

    /**
     * Load sprint summary via API. Called only when no iteration is selected
     * (so we need to find the current sprint by date and fetch its artifacts separately).
     */
    private fun loadSprintSummary(client: RallyApiClient, settings: RallySettings) {
        try {
            val iteration = client.queryCurrentIteration(
                if (settings.workspaceRef.isNotBlank()) settings.workspaceRef else null,
                getSelectedProjectRef()
            )

            if (iteration == null) {
                sprintIteration = null
                invokeLaterIfAlive {
                    sprintLabel.text = "No active sprint"
                }
                return
            }

            setSprintIteration(iteration)
        } catch (e: Exception) {
            LOG.warn("Failed to load sprint summary", e)
            invokeLaterIfAlive {
                sprintLabel.text = "Sprint: unable to load"
            }
        }
    }

    /**
     * Resolve the selected sprint's metadata from the cached iteration list (no API call).
     * The summary counts are rendered from the filtered list in renderSprintSummary().
     */
    private fun resolveSelectedSprint(iterationName: String) {
        val iteration = cachedIterations.firstOrNull { it.name == iterationName }
        if (iteration == null) {
            sprintIteration = null
            invokeLaterIfAlive {
                sprintLabel.text = "No active sprint"
            }
            return
        }
        setSprintIteration(iteration)
    }

    /** Cache the resolved sprint's metadata, then re-render the summary from the filtered list. */
    private fun setSprintIteration(iteration: RallyIteration) {
        sprintIteration = iteration
        invokeLaterIfAlive { renderSprintSummary() }
    }

    /**
     * EDT-only. Renders the footer sprint summary so its state counts and points track the
     * active Scope/State/Search filter. Metadata (name, dates, days-left, velocity) comes from
     * the resolved iteration; counts come from the currently displayed list (see
     * [buildSprintSummaryLabel], unit-tested in RallySprintSummaryTest).
     */
    private fun renderSprintSummary() {
        val iteration = sprintIteration ?: return
        sprintLabel.text = buildSprintSummaryLabel(iteration, displayedArtifacts, java.time.LocalDate.now())
    }

    // ── Search Filter ────────────────────────────────────────────

    /**
     * Refresh the ticket list's empty placeholder to reflect the active scope/state/project.
     * Called whenever the displayed list ends up empty — including a client-side state-filter
     * change, which no longer goes through loadTickets() (P6/LOW-9), so the placeholder would
     * otherwise show a stale message from the previous filter.
     */
    private fun updateEmptyText() {
        // With no successful load there is no filtered result to describe: show (or restore,
        // after a server search replaced it) the load-error / "Not configured" placeholder.
        if (!lastLoadSucceeded) {
            loadFailurePlaceholder?.let { artifactList.emptyText.text = it }
            return
        }
        val scope = scopeCombo.selectedItem as? String ?: Scope.ALL_TICKETS.displayName
        val stateFilter = stateCombo.selectedItem as? String ?: StateFilter.ANY.displayName
        val projectName = projectCombo.selectedItem as? String ?: "All Projects"
        val filterDesc = if (StateFilter.fromDisplay(stateFilter) == StateFilter.ANY) scope else "$scope / $stateFilter"
        artifactList.emptyText.text = "No tickets found for $filterDesc in project: $projectName"
    }

    /**
     * Refresh the "N loaded" status line: N is the loaded list under the State filter selected
     * now (search text doesn't change it). A client-side state-filter change re-filters the
     * loaded list in memory without going through loadTickets() (P6/LOW-9), so without this the
     * status would keep the stale count set by the last load. The partial-failure marker is
     * preserved via [lastLoadIncomplete].
     */
    private fun refreshLoadedStatus() {
        val stateFilter = stateCombo.selectedItem as? String ?: StateFilter.ANY.displayName
        statusLabel.text = buildLoadedStatusText(
            applyStateFilter(stateFilter, allArtifacts).size, lastLoadFetched, lastLoadTotal, lastLoadIncomplete
        )
        statusLabel.icon = if (lastLoadIncomplete) AllIcons.General.Warning else null
    }

    private fun applySearchFilter() {
        if (disposed) return
        val query = searchField.text.trim()
        // Every pass supersedes any server search still in flight: it was started for an
        // older query/scope/state, so its results must not be applied (checked by generation).
        val generation = ++searchGeneration
        activeServerSearch = null

        // allArtifacts holds the scope-filtered (but NOT state-filtered) list, so the
        // active state filter is applied here at display time — this is what lets a
        // state-combo change re-filter in memory with no network round trip (P6/LOW-9).
        val stateFilter = stateCombo.selectedItem as? String ?: StateFilter.ANY.displayName
        val base = applyStateFilter(stateFilter, allArtifacts)

        if (query.isBlank()) {
            // Search cleared — restore the state-filtered list (any pending server search was
            // cancelled above)
            updateListModel(base)
            updateStats(base)
            if (base.isEmpty()) updateEmptyText()
            return
        }

        // Client-side filter first
        val filtered = base.filter {
            it.formattedID?.contains(query, ignoreCase = true) == true ||
                    it.name?.contains(query, ignoreCase = true) == true
        }

        updateListModel(filtered)
        updateStats(filtered)
        if (filtered.isEmpty()) updateEmptyText()

        // Server-side fallback: fire when client-side returns 0 results and query >= 3 chars
        if (filtered.isEmpty() && query.length >= 3) {
            activeServerSearch = query
            statusLabel.text = "Searching Rally..."
            val scope = scopeCombo.selectedItem as? String ?: "All Tickets"
            val stateFilter = stateCombo.selectedItem as? String ?: "Any State"
            val serverResultLimit = RallySettings.getInstance().pageSize.coerceIn(25, 100)

            val iterFilter = iterationCombo.selectedItem as? String ?: ""
            val ownerFilter = if (Scope.fromDisplay(scope) == Scope.MY_TICKETS) RallySettings.getInstance().username else ""

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val client = getClient()
                    val serverResults = client.searchArtifacts(query, scope, serverResultLimit, serverResultLimit).artifacts
                    // Apply the same client-side filters so server results respect active scope/state/owner/sprint
                    var filteredResults = applyClientFilter(scope, stateFilter, serverResults)
                    if (ownerFilter.isNotBlank()) {
                        filteredResults = filteredResults.filter {
                            it.owner?.userName?.equals(ownerFilter, ignoreCase = true) == true
                        }
                    }
                    if (iterFilter.isNotBlank() && iterFilter != "All Sprints") {
                        filteredResults = filteredResults.filter { artifact ->
                            val iterName = when (artifact) {
                                is RallyUserStory -> artifact.iteration?.name ?: artifact.iteration?.refObjectName
                                is RallyDefect -> artifact.iteration?.name ?: artifact.iteration?.refObjectName
                                else -> null
                            }
                            iterName?.equals(iterFilter, ignoreCase = true) == true
                        }
                    }
                    invokeLaterIfAlive {
                        // Only apply if no newer filter pass (query, scope, or State change) has run
                        // since this search started, and the field still holds its query (a keystroke
                        // inside the debounce window hasn't bumped the generation yet).
                        if (generation == searchGeneration && searchField.text.trim() == query) {
                            updateListModel(filteredResults)
                            updateStats(filteredResults)
                            // Set directly: updateEmptyText() keeps a failed load's error text,
                            // but this search just reached Rally.
                            if (filteredResults.isEmpty()) artifactList.emptyText.text = "No results for \"$query\""
                            statusLabel.text = if (filteredResults.isEmpty()) {
                                "No results found"
                            } else {
                                "${filteredResults.size} found via server search"
                            }
                            activeServerSearch = null
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Server search failed for query '$query'", e)
                    invokeLaterIfAlive {
                        if (generation == searchGeneration) {
                            statusLabel.text = "Search failed: ${e.message}"
                            activeServerSearch = null
                        }
                    }
                }
            }
        }
    }

    /**
     * Rebuild the visible list model. The previous selection is re-selected by ref
     * when still present (M7) — re-selecting fires the (reattached) listener, which
     * re-populates the detail panel or, for identical objects, hits the isShowing
     * short-circuit. When the selection is gone, the detail split is collapsed
     * explicitly: the listener never fires for the implicit deselection of a model
     * clear, so the auto-collapse otherwise never runs and a blank pane stays open.
     */
    private fun updateListModel(artifacts: List<RallyArtifact>) {
        val selectedRefs = artifactList.selectedValuesList.mapNotNull { it.ref }.toSet()
        val selectionListeners = artifactList.listSelectionListeners
        selectionListeners.forEach { artifactList.removeListSelectionListener(it) }
        listModel.clear()
        listModel.addAll(artifacts)
        selectionListeners.forEach { artifactList.addListSelectionListener(it) }

        val indices = selectionIndicesByRef(artifacts, selectedRefs)
        if (indices.isNotEmpty()) {
            artifactList.selectedIndices = indices
        } else if (selectedRefs.isNotEmpty()) {
            // Previous selection no longer in the list — mirror the listener's auto-collapse.
            detailPanel.clear()
            mainSplitPane?.let { sp ->
                sp.dividerSize = 0
                sp.dividerLocation = sp.width
            }
        }
    }

    /**
     * Patch already-updated artifacts into the visible list model in place. Unlike
     * applySearchFilter()'s clear()+addAll() rebuild, this fires one contentsChanged
     * event per row and preserves the JList selection — optimistic updates shouldn't
     * collapse the detail panel, drop a multi-select, or re-measure every row. Rows
     * filtered out of the current view are simply absent from the model and skipped.
     *
     * Fresh copies normally live in [allArtifacts]; rows that came from a server-side
     * search have no copy there, so [transform] (the same optimistic update the caller
     * applied to allArtifacts) is applied to the row itself. The previous fallback
     * re-ran applySearchFilter(), which cleared the model with selection listeners
     * detached and re-fired the async search — dropping the selection, skipping the
     * caller's detail-panel refresh, and overwriting its status text.
     */
    private fun patchArtifactsInModel(refs: Collection<String>, transform: (RallyArtifact) -> RallyArtifact) {
        if (refs.isEmpty()) return
        val byRef = HashMap<String, RallyArtifact>()
        for (a in allArtifacts) {
            val r = a.ref ?: continue
            if (r in refs) byRef[r] = a
        }
        var patched = false
        for (i in 0 until listModel.size()) {
            val element = listModel.getElementAt(i)
            val ref = element.ref ?: continue
            if (ref !in refs) continue
            listModel.setElementAt(byRef[ref] ?: transform(element), i)
            patched = true
        }
        if (patched) updateStats(java.util.Collections.list(listModel.elements()))
    }

    /**
     * Optimistic copy of an artifact with its state field updated — Stories and
     * Defects carry ScheduleState, Tasks carry State. Shared by changeState /
     * startWorking so the paths can't drift (the missing RallyTaskItem branch once
     * had to be fixed in each separately).
     */
    private fun withState(artifact: RallyArtifact, newState: String): RallyArtifact = when (artifact) {
        is RallyUserStory -> artifact.copy(scheduleState = newState)
        is RallyDefect -> artifact.copy(scheduleState = newState)
        is RallyTaskItem -> artifact.copy(state = newState)
        else -> artifact
    }

    private fun updateStats(artifacts: List<RallyArtifact>) {
        var totalPoints = 0.0
        for (artifact in artifacts) {
            val points = artifact.storyPoints
            if (points != null) totalPoints += points
        }
        statsLabel.text = "${artifacts.size} items, ${Math.round(totalPoints)} pts"
        // Keep the footer sprint summary in sync with the filtered list.
        displayedArtifacts = artifacts
        renderSprintSummary()
    }

    // ── Actions ──────────────────────────────────────────────────

    private fun showCreateDefectDialog() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfiguredOrLoading()) {
            Messages.showErrorDialog(project, "Configure Rally in Settings → Tools → Rally first.", "Rally")
            return
        }

        val dialog = CreateDefectDialog(
            project, cachedProjects, cachedIterations,
            getSelectedProjectRef(), getSelectedIterationRef()
        )
        if (!dialog.showAndGet()) return

        val name = dialog.artifactName
        val description = dialog.descriptionText
        // Dialog "All Projects" falls back to the toolbar's current project (original behavior).
        val projectRef = dialog.selectedProjectRef ?: getSelectedProjectRef()
        val iterationRef = dialog.selectedIterationRef
        val severity = dialog.severity
        val priority = dialog.priority

        executeCreate("defect", dialog.assignToMe, dialog.attachment) { client, ownerRef ->
            client.createDefect(
                name, projectRef, ownerRef = ownerRef, description = description,
                iterationRef = iterationRef, severity = severity, priority = priority
            )
        }
    }

    private fun showCreateUserStoryDialog() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfiguredOrLoading()) {
            Messages.showErrorDialog(project, "Configure Rally in Settings → Tools → Rally first.", "Rally")
            return
        }

        val dialog = CreateUserStoryDialog(
            project, cachedProjects, cachedIterations,
            getSelectedProjectRef(), getSelectedIterationRef()
        )
        if (!dialog.showAndGet()) return

        val name = dialog.artifactName
        val description = dialog.descriptionText
        val projectRef = dialog.selectedProjectRef ?: getSelectedProjectRef()
        val iterationRef = dialog.selectedIterationRef

        executeCreate("user story", dialog.assignToMe, dialog.attachment) { client, ownerRef ->
            client.createUserStory(
                name, projectRef, ownerRef = ownerRef, description = description, iterationRef = iterationRef
            )
        }
    }

    /**
     * Shared off-EDT create flow for the two Create dialogs (de-duplicates ~250 lines that
     * were copy-pasted between the defect/user-story handlers — MED-10).
     *
     * Phase 1 (create) and Phase 2 (optional attachment upload) are deliberately SPLIT: once
     * the create call returns, the artifact exists in Rally, so success is shown and the
     * optimistic list-insert happens IMMEDIATELY — before the attachment upload is attempted.
     * A post-create upload failure is then reported as a distinct "created, but upload failed"
     * message instead of the old "Create failed", which hid the created artifact and invited a
     * duplicate create (HIGH-2). [createFn] performs the type-specific create call (its second
     * argument is the resolved owner ref); [typeLabel] names the artifact type for messages.
     */
    private fun executeCreate(
        typeLabel: String,
        assignToMe: Boolean,
        attachment: java.io.File?,
        createFn: (RallyApiClient, String?) -> RallyArtifact
    ) {
        val settings = RallySettings.getInstance()
        statusLabel.text = "Creating..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()
                // Re-check the attachment just before creating anything: doValidate()
                // sampled the size at OK-press time on the EDT, but the file can be
                // appended to or deleted before this task runs — discovering that
                // AFTER the create would orphan the new artifact.
                if (attachment != null && (!attachment.exists() || attachment.length() > RallyApiClient.MAX_UPLOAD_BYTES)) {
                    invokeLaterIfAlive {
                        statusLabel.text = "Create cancelled"
                        Messages.showErrorDialog(
                            project,
                            "Attachment '${attachment.name}' is missing or exceeds the " +
                                "${RallyApiClient.MAX_UPLOAD_BYTES / (1024 * 1024)} MB upload limit.",
                            "Rally"
                        )
                    }
                    return@executeOnPooledThread
                }

                var assignWarning: String? = null
                val ownerRef = if (assignToMe && settings.username.isNotBlank()) {
                    try {
                        client.getUserByUsername(settings.username).ref
                    } catch (e: Exception) {
                        LOG.warn("Failed to resolve user ${settings.username} for assign-to-me", e)
                        assignWarning = "couldn't resolve user — created without owner"
                        null
                    }
                } else {
                    null
                }

                // Phase 1: create. The artifact now exists in Rally — notify + optimistic
                // insert immediately, BEFORE the separately-failable upload (HIGH-2).
                val created = createFn(client, ownerRef)
                val createdId = created.formattedID ?: "?"
                invokeLaterIfAlive {
                    val warningMsg = assignWarning?.let { " ($it)" } ?: ""
                    val messageType = if (assignWarning != null) MessageType.WARNING else MessageType.INFO
                    client.clearArtifactCache()
                    // Optimistic update: prepend the new item instead of a full reload — but only
                    // if it belongs in the current view, and make it visible if a filter would hide
                    // it. Otherwise a successful create looks like a silent no-op and invites a
                    // duplicate. The new row's type comes from its concrete class (a fresh
                    // CreateResult may not echo _type/ScheduleState).
                    val scope = scopeCombo.selectedItem as? String ?: Scope.ALL_TICKETS.displayName
                    val matchesScope = when (Scope.fromDisplay(scope)) {
                        Scope.USER_STORIES -> created is RallyUserStory
                        Scope.DEFECTS -> created is RallyDefect
                        Scope.TEST_CASES -> created is RallyTestCase
                        else -> true
                    }
                    if (matchesScope) {
                        allArtifacts = listOf(created) + allArtifacts
                        // A fresh CreateResult doesn't echo ScheduleState/State, so any non-"Any
                        // State" filter would hide the new row. Relax it (client-side, no network)
                        // so the just-created ticket is actually shown. lastState is set first so
                        // the combo listener short-circuits: its stateChangeAction would RELOAD
                        // after a failed load, and a reload discards this optimistic insert.
                        val stateFilter = stateCombo.selectedItem as? String ?: StateFilter.ANY.displayName
                        if (StateFilter.fromDisplay(stateFilter) != StateFilter.ANY &&
                            applyStateFilter(stateFilter, listOf(created)).isEmpty()) {
                            lastState = StateFilter.ANY.displayName
                            stateCombo.selectedItem = StateFilter.ANY.displayName
                        }
                        applySearchFilter()
                        val index = listModel.indexOf(created)
                        if (index >= 0) artifactList.selectedIndex = index
                    }
                    // Announce the result LAST, after the re-filter above. The balloon is the
                    // durable confirmation.
                    statusLabel.text = "Created $createdId$warningMsg"
                    val balloon = JBPopupFactory.getInstance()
                        .createHtmlTextBalloonBuilder("Created $createdId$warningMsg", messageType, null)
                        .setFadeoutTime(3000)
                        .createBalloon()
                    balloon.show(RelativePoint.getSouthWestOf(statusLabel), Balloon.Position.above)
                    // Wrong-scope create (e.g. a User Story while viewing "Defects"): not shown
                    // here — the success balloon confirms it; it appears on the next refresh /
                    // scope switch (the cache was already cleared above).
                }

                // Phase 2: optional attachment upload. A failure here is NON-fatal — the
                // artifact is already created, so never report "Create failed".
                val createdRef = created.ref
                if (attachment != null && createdRef != null) {
                    invokeLaterIfAlive { statusLabel.text = "Uploading attachment..." }
                    try {
                        client.uploadAttachment(createdRef, attachment.toPath())
                        // The new row was selected in Phase 1, so its Attachments tab loaded (and
                        // cached an empty list) while the upload ran. Evict that entry — the epoch
                        // bump also stops a still-in-flight query from re-caching it — and re-run
                        // the detail loads if the new artifact is still on display.
                        created.formattedID?.let { client.clearAttachmentsCache(it) }
                        invokeLaterIfAlive {
                            statusLabel.text = "Created $createdId with attachment"
                            detailPanel.reloadIfShowing(createdRef)
                        }
                    } catch (e: Exception) {
                        LOG.warn("Attachment upload failed for $createdId", e)
                        invokeLaterIfAlive {
                            statusLabel.text = "Created $createdId — attachment upload failed"
                            val safeMsg = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(
                                e.message ?: e.javaClass.simpleName
                            )
                            val balloon = JBPopupFactory.getInstance()
                                .createHtmlTextBalloonBuilder(
                                    "Created $createdId, but attachment upload failed: $safeMsg<br>" +
                                        "You can re-attach the file in the Rally web UI.",
                                    MessageType.WARNING, null
                                )
                                .setFadeoutTime(6000)
                                .createBalloon()
                            balloon.show(RelativePoint.getSouthWestOf(statusLabel), Balloon.Position.above)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to create $typeLabel", e)
                invokeLaterIfAlive {
                    statusLabel.text = "Create failed"
                    Messages.showErrorDialog(project, "Failed to create $typeLabel: ${e.message}", "Rally - Error")
                }
            }
        }
    }

    /** Selected toolbar iteration's ref (null for "All Sprints"), read off the persisted selection. */
    private fun getSelectedIterationRef(): String? {
        val saved = RallySettings.getInstance().selectedIteration
        if (saved.isBlank() || saved == "All Sprints") return null
        return cachedIterations.firstOrNull { it.name == saved }?.ref
    }

    private fun openInBrowser() {
        val selected = artifactList.selectedValue
        if (selected == null) {
            Messages.showInfoMessage(project, "Select a ticket first.", "Rally")
            return
        }
        // A selected row implies a loaded list, hence a client; building the URL needs only the
        // server URL, so don't go through getClient() (which refuses while the key loads).
        val client = currentClient ?: try { getClient() } catch (_: Exception) { return }
        val url = client.buildWebUrl(selected)
        LOG.info("Opening Rally URL: $url")
        BrowserUtil.browse(url)
    }

    private fun copyFormattedId() {
        val selected = artifactList.selectedValue ?: return
        val id = selected.formattedID ?: return
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(id), null)
        statusLabel.text = "Copied $id"
    }

    private fun exportSelectedArtifact() {
        val selected = artifactList.selectedValuesList
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project, "Select one or more tickets to export.", "Rally")
            return
        }

        val settings = RallySettings.getInstance()
        val outputDir = settings.exportDirectory.ifBlank {
            project.basePath?.let { "$it/rally_testcases" } ?: "rally_testcases"
        }

        statusLabel.text = "Exporting..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
            val client = getClient()
            // Per-export orchestration pool (M1). Each per-artifact task is long-lived
            // (description + inline images + attachments + linked test cases, parked on
            // the exporter's download-pool joins throughout), so running them on the
            // shared 4-thread apiExecutor starved everything interactive — detail tabs,
            // Refresh, state changes — for the duration of a 4+ ticket export. With a
            // dedicated pool, apiExecutor only ever serves short HTTP calls.
            val exportExecutor = java.util.concurrent.Executors.newFixedThreadPool(
                minOf(4, selected.size)
            ) { r -> Thread(r, "rally-export-orchestrator").apply { isDaemon = true } }
            val exporter = RallyExporter(client)
            client.enterBulkMode()
            try {
            val artifactSuccess = java.util.concurrent.atomic.AtomicInteger(0)
            val artifactFailed = java.util.concurrent.atomic.AtomicInteger(0)
            val tcExported = java.util.concurrent.atomic.AtomicInteger(0)
            val tcFailed = java.util.concurrent.atomic.AtomicInteger(0)
            // Artifacts whose linked-test-case lookup failed: their test cases were never
            // attempted, so they can't be counted in tcFailed — reported separately instead.
            val tcLookupFailed = java.util.concurrent.atomic.AtomicInteger(0)

            // Export all selected artifacts in parallel
            val futures = selected.mapNotNull { artifact ->
                val id = artifact.formattedID ?: return@mapNotNull null
                val ref = artifact.ref
                java.util.concurrent.CompletableFuture.runAsync({
                    try {
                        // Pass the in-memory artifact so the exporter resolves Description via a
                        // cheap ref GET instead of re-running a FormattedID search query (MED-3/P4).
                        exporter.exportArtifactJson(artifact, outputDir)
                        exporter.exportArtifactMarkdown(artifact, outputDir)
                        artifactSuccess.incrementAndGet()
                    } catch (e: Exception) {
                        LOG.warn("Failed to export $id", e)
                        artifactFailed.incrementAndGet()
                    }

                    // Also export linked test cases
                    if (ref != null) {
                        try {
                            val testCases = client.queryTestCases(ref)
                            for (tc in testCases) {
                                val tcId = tc.formattedID ?: continue
                                try {
                                    exporter.exportTestCaseJson(tcId, outputDir)
                                    exporter.exportTestCaseMarkdown(tcId, outputDir)
                                    tcExported.incrementAndGet()
                                } catch (e: Exception) {
                                    LOG.warn("Failed to export test case $tcId", e)
                                    tcFailed.incrementAndGet()
                                }
                            }
                        } catch (e: Exception) {
                            LOG.warn("Failed to query test cases for $id", e)
                            tcLookupFailed.incrementAndGet()
                        }
                    }
                }, exportExecutor)
            }

            // Wait for all exports to complete
            java.util.concurrent.CompletableFuture.allOf(*futures.toTypedArray()).join()

            val counts = ExportCounts(
                artifactsExported = artifactSuccess.get(),
                artifactsFailed = artifactFailed.get(),
                testCasesExported = tcExported.get(),
                testCasesFailed = tcFailed.get(),
                testCaseLookupsFailed = tcLookupFailed.get(),
                downloadsFailed = exporter.failedDownloadCount,
            )
            invokeLaterIfAlive {
                // createHtmlTextBalloonBuilder treats its argument as HTML, so an export
                // directory containing '<' or '&' would break rendering or inject markup.
                // Escape every interpolated value before substituting <br> for newlines.
                val safeOutputDir = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(outputDir)
                val summary = buildExportSummary(counts, safeOutputDir)
                statusLabel.text = summary.statusText
                val balloon = JBPopupFactory.getInstance()
                    .createHtmlTextBalloonBuilder(
                        summary.balloonText.replace("\n", "<br>"),
                        if (summary.anyFailed) MessageType.WARNING else MessageType.INFO,
                        null
                    )
                    // A warning stays until dismissed: the status bar alone is easy to miss.
                    .setFadeoutTime(if (summary.anyFailed) 0 else 5000)
                    .createBalloon()
                balloon.show(RelativePoint.getSouthWestOf(statusLabel), Balloon.Position.above)
            }
            } finally {
                exporter.close()
                exportExecutor.shutdown()
                client.exitBulkMode()
            }
            } catch (e: Exception) {
                LOG.warn("Export aborted", e)
                invokeLaterIfAlive {
                    statusLabel.text = "Export failed"
                }
            }
        }
    }




    private fun editPoints() {
        val selected = artifactList.selectedValue ?: return
        if (selected is RallyTestCase) return
        val ref = selected.ref ?: return
        val type = selected.type ?: return

        val currentPoints = selected.storyPoints

        val input = Messages.showInputDialog(
            project,
            "Enter story points for ${selected.formattedID}:",
            "Rally - Edit Points",
            null,
            currentPoints?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "",
            null
        ) ?: return

        val points = input.trim().toDoubleOrNull()
        if (points == null && input.trim().isNotEmpty()) {
            Messages.showErrorDialog(project, "Invalid number: $input", "Rally")
            return
        }

        statusLabel.text = "Updating points..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()
                client.updateArtifactField(ref, type, "PlanEstimate", points)
                // Optimistic UI update
                invokeLaterIfAlive {
                    val withPoints: (RallyArtifact) -> RallyArtifact = { artifact ->
                        when (artifact) {
                            is RallyUserStory -> artifact.copy(planEstimate = points)
                            is RallyDefect -> artifact.copy(planEstimate = points)
                            else -> artifact
                        }
                    }
                    allArtifacts = allArtifacts.map { if (it.ref == ref) withPoints(it) else it }
                    client.clearArtifactCache()
                    // In-place patch preserves the selection, so no restore dance needed.
                    patchArtifactsInModel(listOf(ref), withPoints)
                    statusLabel.text = "Updated ${selected.formattedID} points"
                    // Refresh detail panel metadata from the patched model row —
                    // selectedValue covers server-search rows too, which have no
                    // copy in allArtifacts (same pattern as changeState).
                    artifactList.selectedValue?.let { sel ->
                        if (sel.ref == ref) detailPanel.showArtifact(sel, client)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to update points", e)
                invokeLaterIfAlive {
                    statusLabel.text = "Update failed"
                    Messages.showErrorDialog(project, "Failed to update points: ${e.message}", "Rally")
                }
            }
        }
    }

    private fun changeState(newState: String) {
        val rawSelected = artifactList.selectedValuesList
        if (rawSelected.isEmpty()) {
            Messages.showInfoMessage(project, "Select one or more tickets first.", "Rally")
            return
        }
        // Test cases have no ScheduleState/State. The context menu already hides state
        // actions for them, but the toolbar buttons are global, so guard here too —
        // otherwise the Rally API rejects the write and the user sees a confusing error.
        val selected = rawSelected.filterNot { it is RallyTestCase }
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project, "State changes don't apply to test cases.", "Rally")
            return
        }

        // Single-item state changes are a one-click reversible action — confirming
        // every one of them gets in the way. Only ask when bulk-changing more than
        // one ticket so a stray multi-select doesn't move 50 stories at once.
        if (selected.size > 1) {
            val ids = selected.mapNotNull { it.formattedID }.joinToString(", ")
            val confirm = Messages.showYesNoDialog(
                project,
                "Move ${selected.size} tickets to '$newState'?\n$ids",
                "Rally - Change State",
                Messages.getQuestionIcon()
            )
            if (confirm != Messages.YES) return
        }

        statusLabel.text = "Updating..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
            val client = getClient()
            val results = java.util.concurrent.atomic.AtomicInteger(0)
            val failures = java.util.concurrent.atomic.AtomicInteger(0)
            val successfulRefs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            // Track WHICH tickets failed so the user can tell which to revisit, instead of
            // just a "Failed: N" count with no identifiers (MED-9).
            val failedIds = java.util.concurrent.ConcurrentLinkedQueue<String>()

            // Update all selected artifacts in parallel
            val futures = selected.mapNotNull { artifact ->
                val ref = artifact.ref ?: return@mapNotNull null
                val type = artifact.type ?: return@mapNotNull null
                java.util.concurrent.CompletableFuture.runAsync({
                    try {
                        client.updateArtifactState(ref, type, newState)
                        successfulRefs.add(ref)
                        results.incrementAndGet()
                    } catch (e: Exception) {
                        LOG.warn("Failed to update ${artifact.formattedID}", e)
                        failedIds.add(artifact.formattedID ?: ref)
                        failures.incrementAndGet()
                    }
                }, client.apiExecutor)
            }

            // Wait for all updates to complete
            java.util.concurrent.CompletableFuture.allOf(*futures.toTypedArray()).join()

            invokeLaterIfAlive {
                if (failures.get() > 0) {
                    // List the failed IDs (truncated) so the user knows exactly which tickets
                    // didn't move and need attention (MED-9).
                    val ids = failedIds.toList()
                    val shown = ids.take(10).joinToString(", ")
                    val suffix = if (ids.size > 10) ", … and ${ids.size - 10} more" else ""
                    Messages.showWarningDialog(
                        project,
                        "Updated: ${results.get()}, Failed: ${failures.get()}\nFailed to move: $shown$suffix",
                        "Rally - State Change"
                    )
                }
                // Optimistic update: patch in-memory list instead of full reload.
                allArtifacts = allArtifacts.map { if (it.ref in successfulRefs) withState(it, newState) else it }
                client.clearArtifactCache()
                patchArtifactsInModel(successfulRefs) { withState(it, newState) }
                // The selection survives the in-place patch, so refresh the open
                // detail panel if its artifact was among the updated rows — otherwise
                // the header badge keeps showing the pre-update state.
                artifactList.selectedValue?.let { sel ->
                    if (sel.ref in successfulRefs) detailPanel.showArtifact(sel, client)
                }
                statusLabel.text = "Updated ${results.get()}"
            }
            } catch (e: Exception) {
                LOG.warn("State change aborted", e)
                invokeLaterIfAlive {
                    statusLabel.text = "State change failed"
                }
            }
        }
    }

    private fun startWorking() {
        val selected = artifactList.selectedValue
        if (selected == null) {
            Messages.showInfoMessage(project, "Select a ticket first.", "Rally")
            return
        }

        val ticketId = selected.formattedID
        val ticketRef = selected.ref
        val ticketType = selected.type
        if (ticketId == null || ticketRef == null || ticketType == null) {
            Messages.showErrorDialog(project, "Selected ticket is missing required data.", "Rally")
            return
        }

        // The FormattedID is Rally-controlled; sanitize it before it flows into a git branch
        // ref so it can't inject ref-unsafe characters (LOW-50). Standard IDs like "US123"
        // are unaffected.
        val safeBranchId = ticketId.replace(Regex("[^A-Za-z0-9._-]"), "-")

        val settings = RallySettings.getInstance()
        val username = settings.username

        val branchPrefixes = arrayOf("feature", "bugfix", "hotfix", "refactor", "chore", "test")
        val defaultPrefix = when (ticketType) {
            RallyType.DEFECT -> "bugfix"
            else -> "feature"
        }

        // Show Start Working dialog with branch prefix picker
        val dialog = object : DialogWrapper(project, false) {
            val prefixCombo = ComboBox(branchPrefixes).apply { selectedItem = defaultPrefix }

            init {
                title = "Rally - Start Working on $ticketId"
                setOKButtonText("Start")
                init()
            }

            override fun createCenterPanel(): javax.swing.JComponent {
                val previewLabel = JBLabel("Branch: ${prefixCombo.selectedItem}/$safeBranchId")
                prefixCombo.addActionListener {
                    previewLabel.text = "Branch: ${prefixCombo.selectedItem}/$safeBranchId"
                }

                return javax.swing.JPanel(java.awt.GridBagLayout()).apply {
                    val gbc = java.awt.GridBagConstraints().apply {
                        fill = java.awt.GridBagConstraints.HORIZONTAL
                        insets = java.awt.Insets(4, 4, 4, 4)
                    }

                    gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
                    add(javax.swing.JLabel("Branch prefix:"), gbc)
                    gbc.gridx = 1; gbc.weightx = 1.0
                    add(prefixCombo, gbc)

                    gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0
                    add(previewLabel, gbc)

                    gbc.gridy = 2
                    add(javax.swing.JLabel("<html><br>This will also:<ul>" +
                            "<li>Move ticket to In-Progress</li>" +
                            "<li>Assign you as owner</li>" +
                            "</ul></html>"), gbc)
                }
            }
        }

        if (!dialog.showAndGet()) return

        val branchName = "${dialog.prefixCombo.selectedItem}/$safeBranchId"

        statusLabel.text = "Starting work on $ticketId..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
            val client = getClient()
            val errors = mutableListOf<String>()

            // 1. Create & checkout git branch — skipped cleanly if Git4Idea isn't installed.
            var branchSucceeded = false
            if (!RallyGitOps.isAvailable()) {
                errors.add("Git4Idea is not available in this IDE — branch creation skipped")
            } else {
                try {
                    val branchError = RallyGitOps.createOrCheckoutBranch(project, branchName)
                    if (branchError != null) {
                        errors.add(branchError)
                    } else {
                        branchSucceeded = true
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to create branch $branchName", e)
                    errors.add("Branch creation failed: ${e.message}")
                }
            }

            // Only proceed with Rally state changes if branch was created successfully
            if (!branchSucceeded) {
                invokeLaterIfAlive {
                    Messages.showErrorDialog(
                        project,
                        "Could not create/checkout branch $branchName:\n\n${errors.joinToString("\n")}\n\nRally ticket state was not changed.",
                        "Rally - Start Working"
                    )
                    statusLabel.text = "Start working failed"
                }
                return@executeOnPooledThread
            }

            // 2. Move ticket to In-Progress
            var stateChangeSucceeded = false
            try {
                client.updateArtifactState(ticketRef, ticketType, "In-Progress")
                stateChangeSucceeded = true
            } catch (e: Exception) {
                LOG.warn("Failed to move $ticketId to In-Progress", e)
                errors.add("State change failed: ${e.message}")
            }

            // 3. Assign owner
            if (username.isNotBlank()) {
                try {
                    val user = client.getUserByUsername(username)
                    val userRef = user.ref
                    if (userRef != null) {
                        client.updateArtifactOwner(ticketRef, ticketType, userRef)
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to assign owner for $ticketId", e)
                    errors.add("Owner assignment failed: ${e.message}")
                }
            }

            invokeLaterIfAlive {
                // Only update local state if Rally accepted the state change
                if (stateChangeSucceeded) {
                    allArtifacts = allArtifacts.map { if (it.ref == ticketRef) withState(it, "In-Progress") else it }
                }
                client.clearArtifactCache()
                if (stateChangeSucceeded) {
                    patchArtifactsInModel(listOf(ticketRef)) { withState(it, "In-Progress") }
                    // Keep the open detail panel's header in sync (see changeState).
                    artifactList.selectedValue?.let { sel ->
                        if (sel.ref == ticketRef) detailPanel.showArtifact(sel, client)
                    }
                }

                if (errors.isNotEmpty()) {
                    Messages.showWarningDialog(
                        project,
                        "Started working on $ticketId with issues:\n\n${errors.joinToString("\n")}",
                        "Rally - Start Working"
                    )
                }
                statusLabel.text = if (errors.isEmpty()) "Working on $ticketId"
                    else "Working on $ticketId (with issues)"
            }
            } catch (e: Exception) {
                LOG.warn("Start working aborted", e)
                invokeLaterIfAlive {
                    statusLabel.text = "Start working failed"
                }
            }
        }
    }

    override fun dispose() {
        synchronized(clientLock) {
            disposed = true
            currentClient?.clearCache()
            currentClient?.apiExecutor?.shutdownNow()
            currentClient = null
        }
        searchDebounceTimer.stop()
        selectionDebounceTimer.stop()
        activeServerSearch = null
        detailPanel.dispose()
    }

    // ── Client ───────────────────────────────────────────────────

    private fun getClient(): RallyApiClient {
        // Read settings BEFORE acquiring the lock — apiKey getter may block
        // up to 2s on PasswordSafe, which would prevent dispose() from acquiring clientLock.
        val settings = RallySettings.getInstance()
        val serverUrl = settings.serverUrl
        // A still-loading (or unreadable) key must not become a client with an empty key: every
        // call would then fail as a bogus "Auth error". Report it as what it is instead. Never
        // wait on the EDT.
        val keyWaitMs = if (ApplicationManager.getApplication().isDispatchThread) 0L else 2_000L
        val apiKey = settings.awaitApiKey(keyWaitMs)
            ?: throw RallyApiKeyUnavailableException(settings.apiKeyLoadFailed)
        val workspaceRef = settings.workspaceRef.ifBlank { null }

        return synchronized(clientLock) {
            check(!disposed) { "RallyToolWindowPanel has been disposed" }
            if (currentClient?.matchesSettings(serverUrl, apiKey, workspaceRef) != true) {
                // Shut down the old client's thread pool to prevent thread leaks
                currentClient?.apiExecutor?.shutdown()
                currentClient = RallyApiClient(serverUrl, apiKey)
                // Reset caches when client changes. Bumping the project generation
                // also invalidates any in-flight async project load against the OLD
                // client (see projectLoadGeneration) — mirrors invalidateIterations.
                projectsLoaded = false
                projectLoadGeneration.incrementAndGet()
                invalidateIterations()
            }
            // Workspace always comes from settings
            currentClient!!.workspaceRef = workspaceRef
            // projectRef is managed by the project dropdown (updateClientProjectRef)
            currentClient!!
        }
    }
}
