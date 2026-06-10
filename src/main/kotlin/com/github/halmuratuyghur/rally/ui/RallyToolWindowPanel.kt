package com.github.halmuratuyghur.rally.ui

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
import com.github.halmuratuyghur.rally.api.RallyApiClient
import com.github.halmuratuyghur.rally.api.RallyArtifact
import com.github.halmuratuyghur.rally.api.RallyDefect
import com.github.halmuratuyghur.rally.api.RallyTestCase
import com.github.halmuratuyghur.rally.api.RallyIteration
import com.github.halmuratuyghur.rally.api.RallyProject
import com.github.halmuratuyghur.rally.api.RallyTaskItem
import com.github.halmuratuyghur.rally.api.RallyUser
import com.github.halmuratuyghur.rally.api.RallyUserStory
import com.github.halmuratuyghur.rally.export.RallyExporter
import com.github.halmuratuyghur.rally.settings.RallySettings
import com.github.halmuratuyghur.rally.util.RallyGitOps

import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
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
    /** Cached at construction time so the EDT-side selection listener doesn't reflectively probe Class.forName on every selection change. */
    private val gitAvailable: Boolean = RallyGitOps.isAvailable()
    private val startWorkingButton = JButton("Start Working", AllIcons.Actions.Execute).apply {
        isFocusable = true
        // Start Working creates a git branch; if the IDE ships without Git4Idea,
        // there's nothing the button can do — keep it visible but disabled so the
        // affordance is obvious.
        if (!gitAvailable) {
            isEnabled = false
            toolTipText = "Git integration is not available in this IDE"
        }
    }
    private val finishWorkingButton = JButton("Finish Working", AllIcons.Actions.Checked).apply { isFocusable = true }


    private val detailPanel = RallyDetailPanel(project)
    private var mainSplitPane: JSplitPane? = null

    @Volatile private var allArtifacts: List<RallyArtifact> = emptyList()
    // Sprint-summary state. Metadata (name/dates/velocity) comes from the resolved
    // iteration; the counts/points are derived from `displayedArtifacts` so they track
    // the active Scope/State/Search filter. `sprintIteration` is written on a pooled
    // thread and read on the EDT (hence @Volatile); `displayedArtifacts` is EDT-confined.
    @Volatile private var sprintIteration: RallyIteration? = null
    private var displayedArtifacts: List<RallyArtifact> = emptyList()
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
        // Check configuration off-EDT so the PasswordSafe preload can complete
        ApplicationManager.getApplication().executeOnPooledThread {
            val configured = RallySettings.getInstance().isConfigured()
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
        val createButton = createButton("Create", AllIcons.General.Add) {}
        createButton.addActionListener {
            val menu = JPopupMenu()
            menu.add(JMenuItem("User Story").apply { addActionListener { showCreateUserStoryDialog() } })
            menu.add(JMenuItem("Defect").apply { addActionListener { showCreateDefectDialog() } })
            menu.show(createButton, 0, createButton.height)
        }
        toolbar.add(createButton)
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("Defined", AllIcons.Actions.MoveToButton) { changeState("Defined") })
        toolbar.add(createButton("In-Progress", AllIcons.Actions.Execute) { changeState("In-Progress") })
        toolbar.add(createButton("Completed", AllIcons.Actions.Checked) { changeState("Completed") })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("Export", AllIcons.ToolbarDecorator.Export) { exportSelectedArtifact() })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(startWorkingButton)
        toolbar.add(finishWorkingButton)

        startWorkingButton.addActionListener { startWorking() }
        finishWorkingButton.addActionListener { finishWorking() }

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

    private fun createButton(text: String, icon: Icon, action: () -> Unit): JButton {
        return JButton(text, icon).apply {
            isFocusable = true
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
                    // Start Working stays disabled when Git4Idea is absent regardless of selection
                    // — clicking it has nowhere to go without git operations.
                    startWorkingButton.isEnabled = !isTc && gitAvailable
                    finishWorkingButton.isEnabled = !isTc
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
        val stateFilter = stateCombo.selectedItem as? String ?: "Any State"
        val pageSize = if (scope == "Recent Activity") 20 else settings.pageSize

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()

                // Load projects if not yet loaded or settings changed.
                // Use a non-sensitive SHA-256 fingerprint of the API key instead of String.hashCode()
                // so two distinct keys can't collide and mask a credential change.
                val snapshot = "${settings.serverUrl}|${apiKeyFingerprint(settings.apiKey)}|${settings.workspaceRef}"
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

                // Load iterations if not yet loaded (or reset after project change).
                // When no saved sprint must be validated against the list (the default,
                // and the state after every project switch), the iteration list only
                // feeds the dropdown — run it concurrently with the artifact fetch
                // instead of paying a serial round trip before it.
                val savedIter = RallySettings.getInstance().selectedIteration
                val needsIterationValidation = savedIter.isNotBlank() && savedIter != "All Sprints"
                var iterationsFuture: java.util.concurrent.CompletableFuture<Void>? = null
                if (!iterationsLoaded) {
                    if (needsIterationValidation) {
                        loadIterations(client)
                    } else {
                        iterationsFuture = java.util.concurrent.CompletableFuture.runAsync({
                            loadIterations(client)
                        }, client.apiExecutor)
                    }
                }

                val effectiveIter = if (needsIterationValidation &&
                    cachedIterations.any { it.name == savedIter }) savedIter else ""
                val query = buildQuery(scope, effectiveIter, settings)
                val hasIterationFilter = effectiveIter.isNotBlank() && effectiveIter != "All Sprints"

                // Load artifacts. The sprint summary only needs the iteration's metadata now
                // (its counts/points are derived from the filtered list in renderSprintSummary),
                // so the only background work is finding the current iteration when no specific
                // sprint is selected.
                val artifactsFuture = java.util.concurrent.CompletableFuture.supplyAsync({
                    if (scope == "Test Cases") {
                        client.queryAllTestCases(query, pageSize, maxResults = pageSize)
                    } else {
                        client.queryAllArtifacts(query, pageSize, scope = scope, maxResults = pageSize)
                    }
                }, client.apiExecutor)
                val sprintFuture = if (!hasIterationFilter) {
                    // "All Sprints" — resolve the current iteration (by today's date) for metadata.
                    java.util.concurrent.CompletableFuture.runAsync({
                        loadSprintSummary(client, settings)
                    }, client.apiExecutor)
                } else null

                val artifacts = artifactsFuture.get()

                // A specific sprint is selected — resolve its metadata from cache (no API call).
                if (hasIterationFilter) {
                    resolveSelectedSprint(effectiveIter)
                }

                // Client-side filtering for state and type
                val filtered = applyClientFilter(scope, stateFilter, artifacts)

                invokeLaterIfAlive {
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

                // loadIterations handles its own errors and UI updates; join so this
                // background cycle doesn't end with the iterations call still in
                // flight (keeps an immediate follow-up loadTickets from doubling up).
                if (iterationsFuture != null) {
                    try { iterationsFuture.get() } catch (e: Exception) {
                        LOG.warn("Failed to load iterations", e)
                    }
                }

            } catch (e: Exception) {
                LOG.error("Failed to load Rally tickets", e)
                invokeLaterIfAlive {
                    loading = false
                    if (pendingReload) {
                        pendingReload = false
                        loadTickets()
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
                        artifactList.emptyText.text = "$errorMsg: ${cause.message}"
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

    private fun loadProjects(client: RallyApiClient) {
        try {
            val projects = client.queryProjects()
            cachedProjects = projects

            invokeLaterIfAlive {
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
                projectCombo.removeAllItems()
                projectCombo.addItem("All Projects")
                projectCombo.isEnabled = false
                // Leave projectsLoaded = false so next loadTickets() retries
                notifyLoadFailure("projects", e)
            }
        }
    }

    private fun loadIterations(client: RallyApiClient) {
        try {
            val iterations = client.queryIterations()
            cachedIterations = iterations

            // Populate combo on EDT without blocking the pooled thread
            invokeLaterIfAlive {
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
        LOG.info("Rally query built (scope=$scope, iteration='$selectedIter', conditions=${conditions.size})")
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
                state !in setOf("Accepted", "Completed", "Idea")
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

    private fun applySearchFilter() {
        if (disposed) return
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
                    invokeLaterIfAlive {
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
                    LOG.warn("Server search failed for query '$query'", e)
                    invokeLaterIfAlive {
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

    /**
     * Patch already-updated artifacts (fresh copies live in [allArtifacts]) into the
     * visible list model in place. Unlike applySearchFilter()'s clear()+addAll()
     * rebuild, this fires one contentsChanged event per row and preserves the JList
     * selection — optimistic updates shouldn't collapse the detail panel, drop a
     * multi-select, or re-measure every row. Rows filtered out of the current view
     * are simply absent from the model and skipped, same as before.
     */
    private fun patchArtifactsInModel(refs: Collection<String>) {
        if (refs.isEmpty()) return
        val byRef = HashMap<String, RallyArtifact>()
        for (a in allArtifacts) {
            val r = a.ref ?: continue
            if (r in refs) byRef[r] = a
        }
        var patched = false
        for (i in 0 until listModel.size()) {
            val ref = listModel.getElementAt(i).ref ?: continue
            if (ref in refs && ref !in byRef) {
                // The row came from a server-side search and has no fresh copy in
                // allArtifacts to patch in. Fall back to a full re-filter, which
                // re-fires the search against the just-cleared cache — the one case
                // where the old rebuild path recovered better than an in-place patch.
                applySearchFilter()
                return
            }
            val replacement = byRef[ref] ?: continue
            listModel.setElementAt(replacement, i)
            patched = true
        }
        if (patched) updateStats(java.util.Collections.list(listModel.elements()))
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
        statsLabel.text = "${artifacts.size} items, ${Math.round(totalPoints)} pts"
        // Keep the footer sprint summary in sync with the filtered list.
        displayedArtifacts = artifacts
        renderSprintSummary()
    }

    // ── Actions ──────────────────────────────────────────────────

    private fun showCreateDefectDialog() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfiguredOrLoading()) {
            Messages.showErrorDialog(project, "Configure Rally in Settings \u2192 Tools \u2192 Rally first.", "Rally")
            return
        }

        val dialog = CreateDefectDialog()
        if (!dialog.showAndGet()) return

        val name = dialog.nameField.text.trim()
        val description = dialog.descriptionArea.text.trim().ifBlank { null }
        val selectedProjectIndex = dialog.projectCombo.selectedIndex
        val selectedIterationIndex = dialog.iterationCombo.selectedIndex
        val assignToMe = dialog.assignToMeCheckbox.isSelected
        val attachment = dialog.attachmentFile
        val severity = (dialog.severityCombo.selectedItem as? String)?.ifBlank { null }
        val priority = (dialog.priorityCombo.selectedItem as? String)?.ifBlank { null }

        statusLabel.text = "Creating..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()
                // Snapshot the volatile caches so a background reload mid-create
                // can't replace the list between the size check and the index access.
                val projectsSnapshot = cachedProjects
                val iterationsSnapshot = cachedIterations
                val projectRefForCreate = if (selectedProjectIndex > 0 && selectedProjectIndex - 1 < projectsSnapshot.size) {
                    projectsSnapshot[selectedProjectIndex - 1].ref
                } else {
                    getSelectedProjectRef()
                }
                val iterationRefForCreate = if (selectedIterationIndex > 0 && selectedIterationIndex - 1 < iterationsSnapshot.size) {
                    iterationsSnapshot[selectedIterationIndex - 1].ref
                } else {
                    null
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

                val created = client.createDefect(name, projectRefForCreate, ownerRef = ownerRef, description = description, iterationRef = iterationRefForCreate, severity = severity, priority = priority)
                val createdId = created.formattedID ?: "?"

                // Upload attachment if a file was selected
                if (attachment != null && created.ref != null) {
                    invokeLaterIfAlive {
                        statusLabel.text = "Uploading attachment..."
                    }
                    client.uploadAttachment(created.ref, attachment.toPath())
                }

                invokeLaterIfAlive {
                    val attachMsg = if (attachment != null) " with attachment" else ""
                    val warningMsg = assignWarning?.let { " ($it)" } ?: ""
                    val messageType = if (assignWarning != null) MessageType.WARNING else MessageType.INFO
                    statusLabel.text = "Created $createdId$attachMsg$warningMsg"
                    val balloon = JBPopupFactory.getInstance()
                        .createHtmlTextBalloonBuilder("Created $createdId$attachMsg$warningMsg", messageType, null)
                        .setFadeoutTime(3000)
                        .createBalloon()
                    balloon.show(RelativePoint.getSouthWestOf(statusLabel), Balloon.Position.above)
                    // Optimistic update: prepend new item instead of full reload
                    allArtifacts = listOf(created as RallyArtifact) + allArtifacts
                    client.clearArtifactCache()
                    applySearchFilter()
                    // Select the newly created item
                    val index = listModel.indexOf(created)
                    if (index >= 0) artifactList.selectedIndex = index
                }
            } catch (e: Exception) {
                LOG.error("Failed to create defect", e)
                invokeLaterIfAlive {
                    statusLabel.text = "Create failed"
                    Messages.showErrorDialog(project, "Failed to create defect: ${e.message}", "Rally - Error")
                }
            }
        }
    }

    private inner class CreateDefectDialog : DialogWrapper(project) {
        val nameField = JBTextField()
        val projectCombo = ComboBox<String>()
        val iterationCombo = ComboBox<String>()
        val severityCombo = ComboBox(arrayOf("", "Crash/Data Loss", "Major Problem", "Minor Problem", "Cosmetic"))
        val priorityCombo = ComboBox(arrayOf("", "Resolve Immediately", "High Attention", "Normal", "Low"))
        val assignToMeCheckbox = javax.swing.JCheckBox("Assign to me")
        val descriptionArea = JBTextArea(5, 40)
        val attachmentPathField = JBTextField()
        var attachmentFile: java.io.File? = null

        init {
            title = "Create Defect"
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
                val label = if (start.isNotBlank() && end.isNotBlank()) "$name ($start \u2192 $end)" else name
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

            // Row 3: Severity
            gbc.gridx = 0; gbc.gridy = 3; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(JBLabel("Severity:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            formPanel.add(severityCombo, gbc)

            // Row 4: Priority
            gbc.gridx = 0; gbc.gridy = 4; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(JBLabel("Priority:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            formPanel.add(priorityCombo, gbc)

            // Row 5: Assign to me
            gbc.gridx = 1; gbc.gridy = 5; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(assignToMeCheckbox, gbc)

            // Row 6: Attachment
            gbc.gridx = 0; gbc.gridy = 6; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
            formPanel.add(JBLabel("Attachment:"), gbc)
            gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            attachmentPathField.isEditable = false
            val attachPanel = JPanel(BorderLayout(4, 0))
            attachPanel.add(attachmentPathField, BorderLayout.CENTER)
            val browseButton = JButton("Browse...")
            browseButton.addActionListener {
                // Use NoJars so a .zip is selectable as a single leaf file rather than
                // navigable like a jar. createSingleFileDescriptor() (no-arg) is deprecated;
                // this variant is the supported replacement and exists since 2024.1.
                val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
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

            panel.preferredSize = java.awt.Dimension(500, 440)
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

    private fun showCreateUserStoryDialog() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfiguredOrLoading()) {
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
                // Snapshot the volatile caches so a background reload mid-create
                // can't replace the list between the size check and the index access.
                val projectsSnapshot = cachedProjects
                val iterationsSnapshot = cachedIterations
                val projectRefForCreate = if (selectedProjectIndex > 0 && selectedProjectIndex - 1 < projectsSnapshot.size) {
                    projectsSnapshot[selectedProjectIndex - 1].ref
                } else {
                    getSelectedProjectRef()
                }
                val iterationRefForCreate = if (selectedIterationIndex > 0 && selectedIterationIndex - 1 < iterationsSnapshot.size) {
                    iterationsSnapshot[selectedIterationIndex - 1].ref
                } else {
                    null
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

                val created = client.createUserStory(name, projectRefForCreate, ownerRef = ownerRef, description = description, iterationRef = iterationRefForCreate)
                val createdId = created.formattedID ?: "?"

                // Upload attachment if a file was selected
                if (attachment != null && created.ref != null) {
                    invokeLaterIfAlive {
                        statusLabel.text = "Uploading attachment..."
                    }
                    client.uploadAttachment(created.ref, attachment.toPath())
                }

                invokeLaterIfAlive {
                    val attachMsg = if (attachment != null) " with attachment" else ""
                    val warningMsg = assignWarning?.let { " ($it)" } ?: ""
                    val messageType = if (assignWarning != null) MessageType.WARNING else MessageType.INFO
                    statusLabel.text = "Created $createdId$attachMsg$warningMsg"
                    val balloon = JBPopupFactory.getInstance()
                        .createHtmlTextBalloonBuilder("Created $createdId$attachMsg$warningMsg", messageType, null)
                        .setFadeoutTime(3000)
                        .createBalloon()
                    balloon.show(RelativePoint.getSouthWestOf(statusLabel), Balloon.Position.above)
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
                invokeLaterIfAlive {
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
                // Use NoJars so a .zip is selectable as a single leaf file rather than
                // navigable like a jar. createSingleFileDescriptor() (no-arg) is deprecated;
                // this variant is the supported replacement and exists since 2024.1.
                val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
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
            client.enterBulkMode()
            try {
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

            invokeLaterIfAlive {
                statusLabel.text = "Exported ${artifactSuccess.get()} artifact(s), ${tcExported.get()} test case(s)"
                // createHtmlTextBalloonBuilder treats its argument as HTML, so an export
                // directory containing '<' or '&' would break rendering or inject markup.
                // Escape every interpolated value before substituting <br> for newlines.
                val safeOutputDir = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(outputDir)
                val summary = buildString {
                    append("Exported to:\n$safeOutputDir\n\n")
                    append("Artifacts: ${artifactSuccess.get()} exported")
                    if (artifactFailed.get() > 0) append(", ${artifactFailed.get()} failed")
                    append("\nTest Cases: ${tcExported.get()} exported")
                    if (tcFailed.get() > 0) append(", ${tcFailed.get()} failed")
                }
                val balloon = JBPopupFactory.getInstance()
                    .createHtmlTextBalloonBuilder(summary.replace("\n", "<br>"), MessageType.INFO, null)
                    .setFadeoutTime(5000)
                    .createBalloon()
                balloon.show(RelativePoint.getSouthWestOf(statusLabel), Balloon.Position.above)
            }
            } finally {
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

        val currentPoints = when (selected) {
            is RallyUserStory -> selected.planEstimate
            is RallyDefect -> selected.planEstimate
            else -> null
        }

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
                    allArtifacts = allArtifacts.map { artifact ->
                        if (artifact.ref == ref) {
                            when (artifact) {
                                is RallyUserStory -> artifact.copy(planEstimate = points)
                                is RallyDefect -> artifact.copy(planEstimate = points)
                                else -> artifact
                            }
                        } else artifact
                    }
                    client.clearArtifactCache()
                    // In-place patch preserves the selection, so no restore dance needed.
                    patchArtifactsInModel(listOf(ref))
                    statusLabel.text = "Updated ${selected.formattedID} points"
                    // Refresh detail panel metadata with the updated artifact
                    allArtifacts.firstOrNull { it.ref == ref }?.let { detailPanel.showArtifact(it, client) }
                }
            } catch (e: Exception) {
                LOG.error("Failed to update points", e)
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

            invokeLaterIfAlive {
                if (failures.get() > 0) {
                    Messages.showWarningDialog(
                        project,
                        "Updated: ${results.get()}, Failed: ${failures.get()}",
                        "Rally - State Change"
                    )
                }
                // Optimistic update: patch in-memory list instead of full reload.
                // Tasks use State, not ScheduleState — the earlier else-branch left
                // tasks showing stale state until the next manual refresh.
                allArtifacts = allArtifacts.map { artifact ->
                    if (artifact.ref in successfulRefs) {
                        when (artifact) {
                            is RallyUserStory -> artifact.copy(scheduleState = newState)
                            is RallyDefect -> artifact.copy(scheduleState = newState)
                            is RallyTaskItem -> artifact.copy(state = newState)
                            else -> artifact
                        }
                    } else artifact
                }
                client.clearArtifactCache()
                patchArtifactsInModel(successfulRefs)
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
                    LOG.error("Failed to create branch $branchName", e)
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

            invokeLaterIfAlive {
                // Only update local state if Rally accepted the state change
                if (stateChangeSucceeded) {
                    allArtifacts = allArtifacts.map { artifact ->
                        if (artifact.ref == ticketRef) {
                            when (artifact) {
                                is RallyUserStory -> artifact.copy(scheduleState = "In-Progress")
                                is RallyDefect -> artifact.copy(scheduleState = "In-Progress")
                                is RallyTaskItem -> artifact.copy(state = "In-Progress")
                                else -> artifact
                            }
                        } else artifact
                    }
                }
                client.clearArtifactCache()
                if (stateChangeSucceeded) patchArtifactsInModel(listOf(ticketRef))

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

    private fun finishWorking() {
        val selected = artifactList.selectedValue
        if (selected == null) {
            Messages.showInfoMessage(project, "Select a ticket first.", "Rally")
            return
        }
        if (selected is RallyTestCase) return

        val ticketId = selected.formattedID ?: return
        val ticketRef = selected.ref ?: return
        val ticketType = selected.type ?: return

        val confirm = Messages.showYesNoDialog(
            project,
            "Finish working on $ticketId?\n\nThis will move the ticket to Completed.",
            "Rally - Finish Working",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        statusLabel.text = "Finishing $ticketId..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()
                client.updateArtifactState(ticketRef, ticketType, "Completed")

                invokeLaterIfAlive {
                    allArtifacts = allArtifacts.map { artifact ->
                        if (artifact.ref == ticketRef) {
                            when (artifact) {
                                is RallyUserStory -> artifact.copy(scheduleState = "Completed")
                                is RallyDefect -> artifact.copy(scheduleState = "Completed")
                                is RallyTaskItem -> artifact.copy(state = "Completed")
                                else -> artifact
                            }
                        } else artifact
                    }
                    client.clearArtifactCache()
                    patchArtifactsInModel(listOf(ticketRef))
                    statusLabel.text = "Finished $ticketId"
                }
            } catch (e: Exception) {
                LOG.error("Failed to finish working on $ticketId", e)
                invokeLaterIfAlive {
                    statusLabel.text = "Finish failed"
                    Messages.showErrorDialog(project, "Failed to finish working: ${e.message}", "Rally")
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
        detailPanel.dispose()
    }

    // ── Client ───────────────────────────────────────────────────

    private fun getClient(): RallyApiClient {
        // Read settings BEFORE acquiring the lock — apiKey getter may block
        // up to 2s on PasswordSafe, which would prevent dispose() from acquiring clientLock.
        val settings = RallySettings.getInstance()
        val serverUrl = settings.serverUrl
        val apiKey = settings.apiKey
        val workspaceRef = settings.workspaceRef.ifBlank { null }

        return synchronized(clientLock) {
            check(!disposed) { "RallyToolWindowPanel has been disposed" }
            if (currentClient?.matchesSettings(serverUrl, apiKey, workspaceRef) != true) {
                // Shut down the old client's thread pool to prevent thread leaks
                currentClient?.apiExecutor?.shutdown()
                currentClient = RallyApiClient(serverUrl, apiKey)
                // Reset caches when client changes
                projectsLoaded = false
                iterationsLoaded = false
            }
            // Workspace always comes from settings
            currentClient!!.workspaceRef = workspaceRef
            // projectRef is managed by the project dropdown (updateClientProjectRef)
            currentClient!!
        }
    }

    // ── Cell Renderer ────────────────────────────────────────────

    private class ArtifactCellRenderer : ListCellRenderer<RallyArtifact> {

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

            // Show blocked indicator
            val isBlocked = when (value) {
                is RallyUserStory -> value.blocked == true
                is RallyDefect -> value.blocked == true
                else -> false
            }
            if (isBlocked) {
                textLabel.text = "\u26D4 ${textLabel.text}"
            }

            textLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            val state = if (value is RallyTestCase) {
                value.lastVerdict ?: "No Verdict"
            } else {
                value.scheduleState ?: value.state ?: "Unknown"
            }
            stateLabel.text = state
            stateLabel.foreground = if (isSelected) list.selectionForeground else when (state) {
                "Pass" -> RallyColors.PASS
                "Fail" -> RallyColors.FAIL
                "In-Progress" -> RallyColors.IN_PROGRESS
                "Completed" -> RallyColors.COMPLETED
                "Accepted" -> JBColor.GRAY
                "Defined" -> RallyColors.DEFINED
                else -> JBColor.DARK_GRAY
            }

            ownerLabel.text = value.owner?.displayName ?: value.owner?.refObjectName ?: ""
            ownerLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY

            panel.toolTipText = "${value.formattedID}: ${value.name}"

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
                g.color = RallyColors.DIVIDER
                g.fillRect(0, 0, width, height)
            }
        }
    }
}
