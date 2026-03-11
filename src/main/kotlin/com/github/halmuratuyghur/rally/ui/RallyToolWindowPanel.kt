package com.github.halmuratuyghur.rally.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.github.halmuratuyghur.rally.api.RallyApiClient
import com.github.halmuratuyghur.rally.api.RallyArtifact
import com.github.halmuratuyghur.rally.api.RallyDefect
import com.github.halmuratuyghur.rally.api.RallyTestCase
import com.github.halmuratuyghur.rally.api.RallyIteration
import com.github.halmuratuyghur.rally.api.RallyProject
import com.github.halmuratuyghur.rally.api.RallyUserStory
import com.github.halmuratuyghur.rally.export.RallyExporter
import com.github.halmuratuyghur.rally.settings.RallySettings

import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class RallyToolWindowPanel(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(RallyToolWindowPanel::class.java)
        private val SCOPE_OPTIONS = arrayOf(
            "All Tickets",
            "My Tickets",
            "User Stories",
            "Defects",
            "Test Cases",
            "Recent Activity"
        )
        private const val DIVIDER_THICKNESS = 3
        private val STATE_OPTIONS = arrayOf(
            "Any State",
            "Idea",
            "Defined",
            "In-Progress",
            "Completed",
            "Accepted",
            "Deployed",
            "Active"
        )
    }

    private val mainPanel = JPanel(BorderLayout())
    private val listModel = DefaultListModel<RallyArtifact>()
    private val artifactList = JBList(listModel)
    private val scopeCombo = ComboBox(SCOPE_OPTIONS)
    private val stateCombo = ComboBox(STATE_OPTIONS)
    private val projectCombo = ComboBox<String>().apply { isEnabled = false }
    private val iterationCombo = ComboBox<String>().apply { isEnabled = false }
    private val searchField = SearchTextField()
    private val searchDebounceTimer = javax.swing.Timer(300) { applySearchFilter() }.apply { isRepeats = false }
    private val statsLabel = JBLabel("0 items")
    private val sprintLabel = JBLabel("")
    private val statusLabel = JBLabel("Ready")
    private val startWorkingButton = JButton("Start Working", AllIcons.Actions.Execute).apply { isFocusable = false }


    private val detailPanel = RallyDetailPanel(project)
    private var mainSplitPane: JSplitPane? = null

    @Volatile private var allArtifacts: List<RallyArtifact> = emptyList()
    @Volatile private var currentClient: RallyApiClient? = null
    @Volatile private var loading = false
    @Volatile private var pendingReload = false
    @Volatile private var cachedProjects: List<RallyProject> = emptyList()
    @Volatile private var cachedIterations: List<RallyIteration> = emptyList()
    @Volatile private var projectsLoaded = false
    @Volatile private var iterationsLoaded = false
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
        checkInitialConfiguration()
    }

    private fun checkInitialConfiguration() {
        // Check configuration off-EDT so the PasswordSafe preload can complete
        ApplicationManager.getApplication().executeOnPooledThread {
            val configured = RallySettings.getInstance().isConfigured()
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                if (configured) {
                    loadTickets()
                } else {
                    showNotConfigured()
                }
            }
        }
    }

    private fun showNotConfigured() {
        statusLabel.icon = AllIcons.General.Error
        statusLabel.text = "Not configured"
        artifactList.emptyText.text = "Configure Rally in Settings → Tools → Rally"
    }

    private fun clearStatusIcon() {
        statusLabel.icon = null
    }

    fun getContent(): JComponent = mainPanel

    // ── UI Setup ─────────────────────────────────────────────────

    private fun setupUI() {
        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.add(createButton("Refresh", AllIcons.Actions.Refresh) { currentClient?.clearCache(); loadTickets() })
        toolbar.add(createButton("Create", AllIcons.General.Add) { showCreateUserStoryDialog() })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("Defined", AllIcons.Actions.MoveToButton) { changeState("Defined") })
        toolbar.add(createButton("In-Progress", AllIcons.Actions.Execute) { changeState("In-Progress") })
        toolbar.add(createButton("Completed", AllIcons.Actions.Checked) { changeState("Completed") })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("Export", AllIcons.ToolbarDecorator.Export) { exportSelectedArtifact() })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(startWorkingButton)

        startWorkingButton.addActionListener { startWorking() }

        toolbar.add(Box.createHorizontalGlue())
        toolbar.add(statsLabel)

        // Filter row
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        filterPanel.add(JBLabel("Scope:"))
        filterPanel.add(scopeCombo)
        filterPanel.add(JBLabel("State:"))
        filterPanel.add(stateCombo)
        filterPanel.add(JBLabel("Project:"))
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
        filterPanel.add(JBLabel("Sprint:"))
        iterationCombo.apply {
            preferredSize = java.awt.Dimension(250, preferredSize.height)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val name = value?.toString() ?: ""
                    // Look up iteration dates from cache (index 0 = "All Sprints", so offset by 1)
                    val iterIndex = if (index > 0) index - 1 else -1
                    if (iterIndex in cachedIterations.indices) {
                        val iter = cachedIterations[iterIndex]
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
        filterPanel.add(JBLabel("Search:"))
        searchField.preferredSize = java.awt.Dimension(200, searchField.preferredSize.height)
        filterPanel.add(searchField)

        // Top section
        val topPanel = JPanel(BorderLayout())
        topPanel.add(toolbar, BorderLayout.NORTH)
        topPanel.add(filterPanel, BorderLayout.SOUTH)

        // Artifact list
        artifactList.cellRenderer = ArtifactCellRenderer()
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

    private fun createButton(text: String, icon: Icon, action: () -> Unit): JButton {
        return JButton(text, icon).apply {
            isFocusable = false
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
            loadTickets()
        }

        // Project change — also reset iteration cache since iterations are project-scoped
        projectCombo.addActionListener {
            if (projectsLoaded) {
                val newProject = projectCombo.selectedItem as? String ?: return@addActionListener
                if (newProject == lastProject) return@addActionListener
                lastProject = newProject
                updateClientProjectRef(projectCombo.selectedIndex)
                RallySettings.getInstance().selectedProject = newProject
                iterationsLoaded = false
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
                detailPanel.showArtifact(selected, currentClient)
                val sp = mainSplitPane ?: return@addListSelectionListener
                if (selected != null) {
                    // Auto-expand detail panel if collapsed
                    val detailWidth = sp.width - sp.dividerLocation - sp.dividerSize
                    if (detailWidth < 100) {
                        sp.dividerSize = DIVIDER_THICKNESS
                        sp.dividerLocation = (sp.width * 0.55).toInt()
                    }
                    val isTc = selected is RallyTestCase
                    startWorkingButton.isEnabled = !isTc
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
            menu.addSeparator()
            menu.add(JMenuItem("Export to JSON/Markdown").apply { addActionListener { exportSelectedArtifact() } })
        }
        menu.show(artifactList, e.x, e.y)
    }

    // ── Data Loading ─────────────────────────────────────────────

    fun loadTickets() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfigured()) {
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
        val stateFilter = stateCombo.selectedItem as? String ?: "Any State"
        val pageSize = if (scope == "Recent Activity") 20 else settings.pageSize

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()

                // Load projects if not yet loaded or settings changed
                val snapshot = "${settings.serverUrl}|${settings.apiKey}|${settings.workspaceRef}"
                if (!projectsLoaded || snapshot != lastSettingsSnapshot) {
                    lastSettingsSnapshot = snapshot
                    loadProjects(client)
                    iterationsLoaded = false
                }

                // Determine effective project selection from saved settings + cached data
                // (avoids reading Swing state off-EDT)
                val savedProject = RallySettings.getInstance().selectedProject
                val effectiveProjectIndex = if (savedProject.isNotBlank() && savedProject != "All Projects") {
                    val idx = cachedProjects.indexOfFirst { it.name == savedProject }
                    if (idx >= 0) idx + 1 else 0  // +1 for "All Projects" offset
                } else 0
                updateClientProjectRef(effectiveProjectIndex)

                // Load iterations if not yet loaded (or reset after project change)
                if (!iterationsLoaded) {
                    loadIterations(client)
                }

                val savedIter = RallySettings.getInstance().selectedIteration
                val effectiveIter = if (savedIter.isNotBlank() && savedIter != "All Sprints" &&
                    cachedIterations.any { it.name == savedIter }) savedIter else ""
                val query = buildQuery(scope, effectiveIter, settings)
                val hasIterationFilter = effectiveIter.isNotBlank() && effectiveIter != "All Sprints"
                // Sprint summary can reuse main artifacts only when the main query has no extra
                // filters (owner, etc.) beyond the iteration — otherwise counts would be wrong.
                val mainQueryIsIterationOnly = hasIterationFilter && query == "(Iteration.Name = \"$effectiveIter\")"

                // Load artifacts; sprint summary runs in parallel only when it needs separate API calls
                val artifactsFuture = java.util.concurrent.CompletableFuture.supplyAsync({
                    if (scope == "Test Cases") {
                        client.queryAllTestCases(query, pageSize, maxResults = pageSize)
                    } else {
                        client.queryAllArtifacts(query, pageSize, scope = scope, maxResults = pageSize)
                    }
                }, client.apiExecutor)
                val sprintFuture = if (!hasIterationFilter) {
                    // No iteration selected — sprint summary needs its own API calls
                    java.util.concurrent.CompletableFuture.runAsync({
                        loadSprintSummary(client, settings, null)
                    }, client.apiExecutor)
                } else if (!mainQueryIsIterationOnly) {
                    // Iteration selected but main query has extra filters (e.g. owner) —
                    // sprint summary needs unfiltered iteration data
                    java.util.concurrent.CompletableFuture.runAsync({
                        val sprintArtifacts = client.queryIterationArtifacts(effectiveIter)
                        computeSprintSummaryFromArtifacts(effectiveIter, sprintArtifacts)
                    }, client.apiExecutor)
                } else null

                val artifacts = artifactsFuture.get()

                // When main query is iteration-only, reuse loaded artifacts for sprint summary
                if (mainQueryIsIterationOnly) {
                    computeSprintSummaryFromArtifacts(effectiveIter, artifacts)
                }

                // Client-side filtering for state and type
                val filtered = applyClientFilter(scope, stateFilter, artifacts)

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    allArtifacts = filtered
                    detailPanel.clear()
                    applySearchFilter()
                    loading = false
                    statusLabel.text = "${filtered.size} loaded"
                    if (filtered.isEmpty()) {
                        val projectName = projectCombo.selectedItem as? String ?: "All Projects"
                        val filterDesc = if (stateFilter == "Any State") scope else "$scope / $stateFilter"
                        artifactList.emptyText.text = "No tickets found for $filterDesc in project: $projectName"
                    }
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

            } catch (e: Exception) {
                LOG.error("Failed to load Rally tickets", e)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    loading = false
                    if (pendingReload) {
                        pendingReload = false
                        loadTickets()
                    } else {
                        statusLabel.icon = AllIcons.General.Error
                        statusLabel.text = "Error"
                        artifactList.emptyText.text = "Error: ${e.message}"
                    }
                }
            }
        }
    }

    private fun loadProjects(client: RallyApiClient) {
        try {
            val projects = client.queryProjects()
            cachedProjects = projects

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater

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
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                projectCombo.removeAllItems()
                projectCombo.addItem("All Projects")
                projectCombo.isEnabled = false
                // Leave projectsLoaded = false so next loadTickets() retries
            }
        }
    }

    private fun loadIterations(client: RallyApiClient) {
        try {
            val iterations = client.queryIterations()
            cachedIterations = iterations

            // Use invokeLater to populate combo on EDT without blocking the pooled thread
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater

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
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                iterationCombo.removeAllItems()
                iterationCombo.addItem("All Sprints")
                iterationCombo.isEnabled = false
                // Leave iterationsLoaded = false so next loadTickets() retries
            }
        }
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
        // workspace/project are passed as URL params by the API client, not as query conditions
        // State/type filtering is done client-side since ScheduleState vs State differs by type
        val conditions = mutableListOf<String>()

        if (scope == "My Tickets" && settings.username.isNotBlank()) {
            val safeUsername = RallyApiClient.escapeQueryValue(settings.username)
            conditions.add("(Owner.UserName = \"$safeUsername\")")
        }

        if (selectedIter.isNotBlank() && selectedIter != "All Sprints") {
            val safeIter = RallyApiClient.escapeQueryValue(selectedIter)
            conditions.add("(Iteration.Name = \"$safeIter\")")
        }

        val query = when (conditions.size) {
            0 -> null
            1 -> conditions[0]
            else -> conditions.reduce { acc, cond -> "($acc AND $cond)" }
        }
        LOG.info("Rally query: $query (scope=$scope, iteration='$selectedIter', username='${settings.username}')")
        return query
    }

    private fun applyClientFilter(scope: String, stateFilter: String, artifacts: List<RallyArtifact>): List<RallyArtifact> {
        // First apply scope (type) filter
        val scopeFiltered = when (scope) {
            "User Stories" -> artifacts.filter { it.type == "HierarchicalRequirement" }
            "Defects" -> artifacts.filter { it.type == "Defect" }
            "Test Cases" -> artifacts.filter { it.type == "TestCase" }
            else -> artifacts
        }

        // Then apply state filter
        return when (stateFilter) {
            "Active" -> scopeFiltered.filter {
                val state = it.scheduleState ?: it.state ?: ""
                state !in setOf("Accepted", "Completed", "Deployed", "Idea")
            }
            "Any State" -> scopeFiltered
            else -> scopeFiltered.filter {
                val state = it.scheduleState ?: it.state ?: ""
                state.equals(stateFilter, ignoreCase = true)
            }
        }
    }

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
    private fun loadSprintSummary(client: RallyApiClient, settings: RallySettings, preloadedArtifacts: List<RallyArtifact>?) {
        try {
            val iteration = client.queryCurrentIteration(
                if (settings.workspaceRef.isNotBlank()) settings.workspaceRef else null,
                getSelectedProjectRef()
            )

            if (iteration == null) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    sprintLabel.text = "No active sprint"
                }
                return
            }

            val sprintArtifacts = preloadedArtifacts ?: client.queryIterationArtifacts(iteration.name ?: "")
            updateSprintLabel(iteration, sprintArtifacts)
        } catch (e: Exception) {
            LOG.warn("Failed to load sprint summary", e)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                sprintLabel.text = "Sprint: unable to load"
            }
        }
    }

    /**
     * Compute sprint summary directly from already-loaded artifacts.
     * Used when an iteration is selected — avoids redundant API calls.
     */
    private fun computeSprintSummaryFromArtifacts(iterationName: String, artifacts: List<RallyArtifact>) {
        val iteration = cachedIterations.firstOrNull { it.name == iterationName }
        if (iteration == null) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                sprintLabel.text = "No active sprint"
            }
            return
        }
        updateSprintLabel(iteration, artifacts)
    }

    private fun updateSprintLabel(iteration: RallyIteration, artifacts: List<RallyArtifact>) {
        val stateCounts = mutableMapOf<String, Int>()
        var totalPoints = 0.0

        for (artifact in artifacts) {
            val state = artifact.scheduleState ?: artifact.state ?: "Unknown"
            stateCounts[state] = (stateCounts[state] ?: 0) + 1
            val points = when (artifact) {
                is RallyUserStory -> artifact.planEstimate
                is RallyDefect -> artifact.planEstimate
                else -> null
            }
            if (points != null) totalPoints += points
        }

        val countsText = stateCounts.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
        val startDate = iteration.startDate?.take(10) ?: ""
        val endDate = iteration.endDate?.take(10) ?: ""

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            sprintLabel.text = "Sprint: ${iteration.name} ($startDate to $endDate) | $countsText | ${totalPoints.toInt()} pts"
        }
    }

    // ── Search Filter ────────────────────────────────────────────

    private fun applySearchFilter() {
        val query = searchField.text.trim()

        if (query.isBlank()) {
            // Search cleared — restore full list and cancel any pending server search
            activeServerSearch = null
            updateListModel(allArtifacts)
            updateStats(allArtifacts)
            return
        }

        // Client-side filter first
        val filtered = allArtifacts.filter {
            it.formattedID?.contains(query, ignoreCase = true) == true ||
                    it.name?.contains(query, ignoreCase = true) == true
        }

        updateListModel(filtered)
        updateStats(filtered)

        // Server-side fallback: fire when client-side returns 0 results and query >= 3 chars
        if (filtered.isEmpty() && query.length >= 3) {
            activeServerSearch = query
            statusLabel.text = "Searching Rally..."
            val scope = scopeCombo.selectedItem as? String ?: "All Tickets"
            val stateFilter = stateCombo.selectedItem as? String ?: "Any State"
            val serverResultLimit = RallySettings.getInstance().pageSize.coerceIn(25, 100)

            val iterFilter = iterationCombo.selectedItem as? String ?: ""
            val ownerFilter = if (scope == "My Tickets") RallySettings.getInstance().username else ""

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val client = getClient()
                    val serverResults = client.searchArtifacts(query, scope, serverResultLimit, serverResultLimit)
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
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        // Only apply if this is still the active search (check current search field)
                        if (activeServerSearch == query && searchField.text.trim() == query) {
                            updateListModel(filteredResults)
                            updateStats(filteredResults)
                            statusLabel.text = if (filteredResults.isEmpty()) {
                                "No results found"
                            } else {
                                "${filteredResults.size} found via server search"
                            }
                            activeServerSearch = null
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        if (activeServerSearch == query) {
                            statusLabel.text = "Search failed: ${e.message}"
                            activeServerSearch = null
                        }
                    }
                }
            }
        }
    }

    private fun updateListModel(artifacts: List<RallyArtifact>) {
        val selectionListeners = artifactList.listSelectionListeners
        selectionListeners.forEach { artifactList.removeListSelectionListener(it) }
        listModel.clear()
        listModel.addAll(artifacts)
        selectionListeners.forEach { artifactList.addListSelectionListener(it) }
    }

    private fun updateStats(artifacts: List<RallyArtifact>) {
        var totalPoints = 0.0
        for (artifact in artifacts) {
            val points = when (artifact) {
                is RallyUserStory -> artifact.planEstimate
                is RallyDefect -> artifact.planEstimate
                else -> null
            }
            if (points != null) totalPoints += points
        }
        statsLabel.text = "${artifacts.size} items, ${totalPoints.toInt()} pts"
    }

    // ── Actions ──────────────────────────────────────────────────

    private fun showCreateUserStoryDialog() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfigured()) {
            Messages.showErrorDialog(project, "Configure Rally in Settings → Tools → Rally first.", "Rally")
            return
        }

        val dialog = CreateUserStoryDialog()
        if (!dialog.showAndGet()) return

        val name = dialog.nameField.text.trim()
        val description = dialog.descriptionArea.text.trim().ifBlank { null }
        val selectedProjectIndex = dialog.projectCombo.selectedIndex
        val selectedIterationIndex = dialog.iterationCombo.selectedIndex
        val assignToMe = dialog.assignToMeCheckbox.isSelected
        val attachment = dialog.attachmentFile

        statusLabel.text = "Creating..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()
                val projectRefForCreate = if (selectedProjectIndex > 0 && selectedProjectIndex - 1 < cachedProjects.size) {
                    cachedProjects[selectedProjectIndex - 1].ref
                } else {
                    getSelectedProjectRef()
                }
                val iterationRefForCreate = if (selectedIterationIndex > 0 && selectedIterationIndex - 1 < cachedIterations.size) {
                    cachedIterations[selectedIterationIndex - 1].ref
                } else {
                    null
                }
                val ownerRef = if (assignToMe && settings.username.isNotBlank()) {
                    try { client.getUserByUsername(settings.username).ref } catch (_: Exception) { null }
                } else {
                    null
                }

                val created = client.createUserStory(name, projectRefForCreate, ownerRef = ownerRef, description = description, iterationRef = iterationRefForCreate)
                val createdId = created.formattedID ?: "?"

                // Upload attachment if a file was selected
                if (attachment != null && created.ref != null) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        statusLabel.text = "Uploading attachment..."
                    }
                    client.uploadAttachment(created.ref, attachment.toPath())
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val attachMsg = if (attachment != null) " with attachment" else ""
                    statusLabel.text = "Created $createdId$attachMsg"
                    Messages.showInfoMessage(project, "Created user story: $createdId$attachMsg", "Rally")
                    // Optimistic update: prepend new item instead of full reload
                    allArtifacts = listOf(created as RallyArtifact) + allArtifacts
                    client.clearArtifactCache()
                    applySearchFilter()
                    // Select the newly created item
                    val index = listModel.indexOf(created)
                    if (index >= 0) artifactList.selectedIndex = index
                }
            } catch (e: Exception) {
                LOG.error("Failed to create user story", e)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    statusLabel.text = "Create failed"
                    Messages.showErrorDialog(project, "Failed to create user story: ${e.message}", "Rally - Error")
                }
            }
        }
    }

    private inner class CreateUserStoryDialog : DialogWrapper(project) {
        val nameField = JBTextField()
        val projectCombo = ComboBox<String>()
        val iterationCombo = ComboBox<String>()
        val assignToMeCheckbox = javax.swing.JCheckBox("Assign to me")
        val descriptionArea = JBTextArea(5, 40)
        val attachmentPathField = JBTextField()
        var attachmentFile: java.io.File? = null

        init {
            title = "Create User Story"
            // Populate project combo from cached projects
            projectCombo.addItem("All Projects")
            cachedProjects.forEach { projectCombo.addItem(it.name ?: "Unnamed") }

            // Pre-select current project from toolbar
            val currentProjectIndex = this@RallyToolWindowPanel.projectCombo.selectedIndex
            if (currentProjectIndex >= 0 && currentProjectIndex < projectCombo.itemCount) {
                projectCombo.selectedIndex = currentProjectIndex
            }

            // Populate iteration combo from cached iterations
            iterationCombo.addItem("Unscheduled")
            cachedIterations.forEach { iter ->
                val name = iter.name ?: "Unnamed"
                val start = iter.startDate?.take(10) ?: ""
                val end = iter.endDate?.take(10) ?: ""
                val label = if (start.isNotBlank() && end.isNotBlank()) "$name ($start → $end)" else name
                iterationCombo.addItem(label)
            }

            // Pre-select current iteration from toolbar
            val currentIterIndex = this@RallyToolWindowPanel.iterationCombo.selectedIndex
            if (currentIterIndex > 0 && currentIterIndex < iterationCombo.itemCount) {
                iterationCombo.selectedIndex = currentIterIndex
            }

            assignToMeCheckbox.isSelected = true

            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, 8))
            panel.border = JBUI.Borders.empty(8)

            // Form fields at top using GridBagLayout for aligned labels
            val formPanel = JPanel(java.awt.GridBagLayout())
            val gbc = java.awt.GridBagConstraints()
            gbc.insets = java.awt.Insets(0, 0, 6, 8)
            gbc.anchor = java.awt.GridBagConstraints.WEST

            // Row 0: Name
            gbc.gridx = 0; gbc.gridy = 0; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(JBLabel("Name:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            formPanel.add(nameField, gbc)

            // Row 1: Project
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(JBLabel("Project:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            formPanel.add(projectCombo, gbc)

            // Row 2: Sprint
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(JBLabel("Sprint:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            formPanel.add(iterationCombo, gbc)

            // Row 3: Assign to me
            gbc.gridx = 1; gbc.gridy = 3; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(assignToMeCheckbox, gbc)

            // Row 4: Attachment
            gbc.gridx = 0; gbc.gridy = 4; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(JBLabel("Attachment:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            attachmentPathField.isEditable = false
            val attachPanel = JPanel(BorderLayout(4, 0))
            attachPanel.add(attachmentPathField, BorderLayout.CENTER)
            val browseButton = JButton("Browse...")
            browseButton.addActionListener {
                val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor()
                    .withTitle("Select ZIP file to attach")
                    .withFileFilter { it.extension.equals("zip", ignoreCase = true) }
                val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null)
                if (chosen != null) {
                    attachmentFile = java.io.File(chosen.path)
                    attachmentPathField.text = chosen.name
                }
            }
            attachPanel.add(browseButton, BorderLayout.EAST)
            formPanel.add(attachPanel, gbc)

            panel.add(formPanel, BorderLayout.NORTH)

            // Description fills remaining space
            descriptionArea.lineWrap = true
            descriptionArea.wrapStyleWord = true
            val descPanel = JPanel(BorderLayout(0, 4))
            descPanel.add(JBLabel("Description:"), BorderLayout.NORTH)
            descPanel.add(JBScrollPane(descriptionArea), BorderLayout.CENTER)
            panel.add(descPanel, BorderLayout.CENTER)

            panel.preferredSize = java.awt.Dimension(500, 380)
            return panel
        }

        override fun doValidate(): ValidationInfo? {
            if (nameField.text.isNullOrBlank()) {
                return ValidationInfo("Name is required", nameField)
            }
            return null
        }

        override fun getPreferredFocusedComponent(): JComponent = nameField
    }

    private fun openInBrowser() {
        val selected = artifactList.selectedValue
        if (selected == null) {
            Messages.showInfoMessage(project, "Select a ticket first.", "Rally")
            return
        }
        val client = try { getClient() } catch (_: Exception) { return }
        val url = client.buildWebUrl(selected)
        LOG.info("Opening Rally URL: $url")
        BrowserUtil.browse(url)
    }

    private fun copyFormattedId() {
        val selected = artifactList.selectedValue ?: return
        val id = selected.formattedID ?: return
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(id), null)
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
            val exporter = RallyExporter(client)
            val artifactSuccess = java.util.concurrent.atomic.AtomicInteger(0)
            val artifactFailed = java.util.concurrent.atomic.AtomicInteger(0)
            val tcExported = java.util.concurrent.atomic.AtomicInteger(0)
            val tcFailed = java.util.concurrent.atomic.AtomicInteger(0)

            // Export all selected artifacts in parallel
            val futures = selected.mapNotNull { artifact ->
                val id = artifact.formattedID ?: return@mapNotNull null
                val ref = artifact.ref
                java.util.concurrent.CompletableFuture.runAsync({
                    try {
                        exporter.exportArtifactJson(id, outputDir)
                        exporter.exportArtifactMarkdown(id, outputDir)
                        artifactSuccess.incrementAndGet()
                    } catch (e: Exception) {
                        LOG.error("Failed to export $id", e)
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
                        }
                    }
                }, client.apiExecutor)
            }

            // Wait for all exports to complete
            java.util.concurrent.CompletableFuture.allOf(*futures.toTypedArray()).join()

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                statusLabel.text = "Exported ${artifactSuccess.get()} artifact(s), ${tcExported.get()} test case(s)"
                val summary = buildString {
                    append("Exported to:\n$outputDir\n\n")
                    append("Artifacts: ${artifactSuccess.get()} exported")
                    if (artifactFailed.get() > 0) append(", ${artifactFailed.get()} failed")
                    append("\nTest Cases: ${tcExported.get()} exported")
                    if (tcFailed.get() > 0) append(", ${tcFailed.get()} failed")
                }
                Messages.showMessageDialog(
                    project,
                    summary,
                    "Rally - Export",
                    AllIcons.General.InspectionsOK
                )
            }
            } catch (e: Exception) {
                LOG.warn("Export aborted", e)
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) statusLabel.text = "Export failed"
                }
            }
        }
    }



    private fun changeState(newState: String) {
        val selected = artifactList.selectedValuesList
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project, "Select one or more tickets first.", "Rally")
            return
        }

        val ids = selected.mapNotNull { it.formattedID }.joinToString(", ")
        val confirm = Messages.showYesNoDialog(
            project,
            "Move ${selected.size} ticket(s) to '$newState'?\n$ids",
            "Rally - Change State",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        statusLabel.text = "Updating..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
            val client = getClient()
            val results = java.util.concurrent.atomic.AtomicInteger(0)
            val failures = java.util.concurrent.atomic.AtomicInteger(0)
            val successfulRefs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                        LOG.error("Failed to update ${artifact.formattedID}", e)
                        failures.incrementAndGet()
                    }
                }, client.apiExecutor)
            }

            // Wait for all updates to complete
            java.util.concurrent.CompletableFuture.allOf(*futures.toTypedArray()).join()

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (failures.get() > 0) {
                    Messages.showWarningDialog(
                        project,
                        "Updated: ${results.get()}, Failed: ${failures.get()}",
                        "Rally - State Change"
                    )
                }
                // Optimistic update: patch in-memory list instead of full reload
                allArtifacts = allArtifacts.map { artifact ->
                    if (artifact.ref in successfulRefs) {
                        when (artifact) {
                            is RallyUserStory -> artifact.copy(scheduleState = newState)
                            is RallyDefect -> artifact.copy(scheduleState = newState)
                            else -> artifact
                        }
                    } else artifact
                }
                client.clearArtifactCache()
                applySearchFilter()
                statusLabel.text = "Updated ${results.get()}"
            }
            } catch (e: Exception) {
                LOG.warn("State change aborted", e)
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) statusLabel.text = "State change failed"
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

        val settings = RallySettings.getInstance()
        val username = settings.username

        val branchPrefixes = arrayOf("feature", "bugfix", "hotfix", "refactor", "chore", "test")
        val defaultPrefix = when (ticketType) {
            "Defect" -> "bugfix"
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
                val previewLabel = JBLabel("Branch: ${prefixCombo.selectedItem}/$ticketId")
                prefixCombo.addActionListener {
                    previewLabel.text = "Branch: ${prefixCombo.selectedItem}/$ticketId"
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

        val branchName = "${dialog.prefixCombo.selectedItem}/$ticketId"

        statusLabel.text = "Starting work on $ticketId..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
            val client = getClient()
            val errors = mutableListOf<String>()

            // 1. Create & checkout git branch (synchronized via latch)
            var branchSucceeded = false
            try {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repos = repoManager.repositories
                if (repos.isEmpty()) {
                    errors.add("No Git repository found in this project")
                } else {
                    val brancher = GitBrancher.getInstance(project)
                    val repo = repos.first()
                    val targetRepos = listOf(repo)
                    val existingBranches = repo.branches.localBranches.map { it.name }
                    val branchLatch = java.util.concurrent.CountDownLatch(1)
                    var branchError: String? = null
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            if (branchName in existingBranches) {
                                brancher.checkout(branchName, false, targetRepos, null)
                            } else {
                                brancher.createBranch(branchName, mapOf(repo to "HEAD"))
                                brancher.checkout(branchName, false, targetRepos, null)
                            }
                            // Verify checkout on EDT where VCS state is reliable
                            repo.update()
                            val currentBranch = repo.currentBranchName
                            if (currentBranch != branchName) {
                                branchError = "Checkout not confirmed (expected: $branchName, current: $currentBranch)"
                            }
                        } catch (e: Exception) {
                            branchError = e.message
                        } finally {
                            branchLatch.countDown()
                        }
                    }
                    val completed = branchLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    if (!completed) {
                        errors.add("Branch operation timed out")
                    } else if (branchError != null) {
                        errors.add("Branch operation failed: $branchError")
                    } else {
                        branchSucceeded = true
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to create branch $branchName", e)
                errors.add("Branch creation failed: ${e.message}")
            }

            // Only proceed with Rally state changes if branch was created successfully
            if (!branchSucceeded) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
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
                LOG.error("Failed to move $ticketId to In-Progress", e)
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
                    LOG.error("Failed to assign owner for $ticketId", e)
                    errors.add("Owner assignment failed: ${e.message}")
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater

                // Only update local state if Rally accepted the state change
                if (stateChangeSucceeded) {
                    allArtifacts = allArtifacts.map { artifact ->
                        if (artifact.ref == ticketRef) {
                            when (artifact) {
                                is RallyUserStory -> artifact.copy(scheduleState = "In-Progress")
                                is RallyDefect -> artifact.copy(scheduleState = "In-Progress")
                                else -> artifact
                            }
                        } else artifact
                    }
                }
                client.clearArtifactCache()
                applySearchFilter()

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
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) statusLabel.text = "Start working failed"
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
        activeServerSearch = null
        detailPanel.clear()
    }

    // ── Client ───────────────────────────────────────────────────

    private fun getClient(): RallyApiClient = synchronized(clientLock) {
        check(!disposed) { "RallyToolWindowPanel has been disposed" }
        val settings = RallySettings.getInstance()
        val newWorkspaceRef = settings.workspaceRef.ifBlank { null }
        if (currentClient == null ||
            currentClient?.serverUrl != settings.serverUrl ||
            currentClient?.apiKey != settings.apiKey ||
            currentClient?.workspaceRef != newWorkspaceRef
        ) {
            // Shut down the old client's thread pool to prevent thread leaks
            currentClient?.apiExecutor?.shutdown()
            currentClient = RallyApiClient(settings.serverUrl, settings.apiKey)
            // Reset caches when client changes
            projectsLoaded = false
            iterationsLoaded = false
        }
        // Workspace always comes from settings
        currentClient!!.workspaceRef = newWorkspaceRef
        // projectRef is managed by the project dropdown (updateClientProjectRef)
        currentClient!!
    }

    // ── Cell Renderer ────────────────────────────────────────────

    private class ArtifactCellRenderer : ListCellRenderer<RallyArtifact> {
        companion object {
            private val COLOR_IN_PROGRESS = JBColor(Color(0, 128, 0), Color(100, 200, 100))
            private val COLOR_COMPLETED = JBColor(Color(0, 0, 180), Color(100, 150, 255))
            private val COLOR_DEFINED = JBColor(Color(200, 120, 0), Color(255, 180, 80))
            private val COLOR_PASS = JBColor(Color(0, 128, 0), Color(100, 200, 100))
            private val COLOR_FAIL = JBColor(Color(180, 0, 0), Color(255, 100, 100))
        }

        private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(4, 6) }
        private val iconLabel = JLabel()
        private val textLabel = JLabel()
        private val stateLabel = JLabel()
        private val ownerLabel = JLabel()
        private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }

        init {
            rightPanel.add(ownerLabel)
            rightPanel.add(stateLabel)
            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textLabel, BorderLayout.CENTER)
            panel.add(rightPanel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out RallyArtifact>,
            value: RallyArtifact,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            panel.background = if (isSelected) list.selectionBackground else list.background

            iconLabel.icon = when (value.type) {
                "HierarchicalRequirement" -> AllIcons.Nodes.PpLib
                "Defect" -> AllIcons.General.Error
                "TestCase" -> AllIcons.RunConfigurations.TestState.Run
                else -> AllIcons.FileTypes.Any_type
            }

            textLabel.text = "${value.formattedID ?: "?"}: ${value.name ?: "Untitled"}"
            textLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            val state = if (value is RallyTestCase) {
                value.lastVerdict ?: "No Verdict"
            } else {
                value.scheduleState ?: value.state ?: "Unknown"
            }
            stateLabel.text = state
            stateLabel.foreground = if (isSelected) list.selectionForeground else when (state) {
                "Pass" -> COLOR_PASS
                "Fail" -> COLOR_FAIL
                "In-Progress" -> COLOR_IN_PROGRESS
                "Completed" -> COLOR_COMPLETED
                "Accepted" -> JBColor.GRAY
                "Defined" -> COLOR_DEFINED
                else -> JBColor.DARK_GRAY
            }

            ownerLabel.text = value.owner?.displayName ?: value.owner?.refObjectName ?: ""
            ownerLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY

            return panel
        }
    }
}

/**
 * Custom SplitPane UI that draws a thin dark line instead of the default thick divider.
 */
private class ThinDividerSplitPaneUI : javax.swing.plaf.basic.BasicSplitPaneUI() {
    override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider {
        return object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
            override fun paint(g: java.awt.Graphics) {
                g.color = JBColor(Color(80, 80, 80), Color(70, 70, 70))
                g.fillRect(0, 0, width, height)
            }
        }
    }
}
