package com.intellij.plugins.rally.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.plugins.rally.api.RallyApiClient
import com.intellij.plugins.rally.api.RallyArtifact
import com.intellij.plugins.rally.api.RallyDefect
import com.intellij.plugins.rally.api.RallyIteration
import com.intellij.plugins.rally.api.RallyProject
import com.intellij.plugins.rally.api.RallyUserStory
import com.intellij.plugins.rally.export.RallyExporter
import com.intellij.plugins.rally.settings.RallySettings
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class RallyToolWindowPanel(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(RallyToolWindowPanel::class.java)
        private val SCOPE_OPTIONS = arrayOf(
            "All Tickets",
            "My Tickets",
            "User Stories",
            "Defects",
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
    private val statsLabel = JBLabel("0 items")
    private val sprintLabel = JBLabel("")
    private val statusLabel = JBLabel("Ready")

    private val detailPanel = RallyDetailPanel(project)
    private var mainSplitPane: JSplitPane? = null

    private var allArtifacts: List<RallyArtifact> = emptyList()
    private var currentClient: RallyApiClient? = null
    private var loading = false
    private var cachedProjects: List<RallyProject> = emptyList()
    private var cachedIterations: List<RallyIteration> = emptyList()
    private var projectsLoaded = false
    private var iterationsLoaded = false
    private var lastSettingsSnapshot: String = ""

    init {
        setupUI()
        setupListeners()
        checkInitialConfiguration()
    }

    private fun checkInitialConfiguration() {
        if (!RallySettings.getInstance().isConfigured()) {
            showNotConfigured()
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
        toolbar.add(createButton("Open in Browser", AllIcons.General.Web) { openInBrowser() })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("Defined", AllIcons.Actions.MoveToButton) { changeState("Defined") })
        toolbar.add(createButton("In-Progress", AllIcons.Actions.Execute) { changeState("In-Progress") })
        toolbar.add(createButton("Completed", AllIcons.Actions.Checked) { changeState("Completed") })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("Export", AllIcons.ToolbarDecorator.Export) { exportSelectedArtifact() })
        toolbar.add(createButton("Bulk Export", AllIcons.Actions.Download) { bulkExport() })
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
        // Scope/state change
        scopeCombo.addActionListener { loadTickets() }
        stateCombo.addActionListener { loadTickets() }

        // Project change — also reset iteration cache since iterations are project-scoped
        projectCombo.addActionListener {
            if (projectsLoaded) {
                updateClientProjectRef()
                RallySettings.getInstance().selectedProject =
                    projectCombo.selectedItem as? String ?: ""
                iterationsLoaded = false
                loadTickets()
            }
        }

        // Iteration change
        iterationCombo.addActionListener {
            if (iterationsLoaded) {
                RallySettings.getInstance().selectedIteration =
                    iterationCombo.selectedItem as? String ?: ""
                loadTickets()
            }
        }

        // Search as you type
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                applySearchFilter()
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

        val menu = JPopupMenu()
        menu.add(JMenuItem("Open in Browser").apply { addActionListener { openInBrowser() } })
        menu.add(JMenuItem("Copy FormattedID").apply { addActionListener { copyFormattedId() } })
        menu.addSeparator()
        menu.add(JMenuItem("Set In-Progress").apply { addActionListener { changeState("In-Progress") } })
        menu.add(JMenuItem("Set Completed").apply { addActionListener { changeState("Completed") } })
        menu.add(JMenuItem("Set Defined").apply { addActionListener { changeState("Defined") } })
        menu.addSeparator()
        menu.add(JMenuItem("Export to JSON/Markdown").apply { addActionListener { exportSelectedArtifact() } })
        menu.show(artifactList, e.x, e.y)
    }

    // ── Data Loading ─────────────────────────────────────────────

    fun loadTickets() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfigured()) {
            showNotConfigured()
            return
        }

        if (loading) return
        loading = true
        clearStatusIcon()
        statusLabel.text = "Loading..."

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

                // Ensure projectRef is set from dropdown before querying iterations or artifacts
                updateClientProjectRef()

                // Load iterations if not yet loaded (or reset after project change)
                if (!iterationsLoaded) {
                    loadIterations(client)
                }

                val scope = scopeCombo.selectedItem as? String ?: "My Tickets"
                val stateFilter = stateCombo.selectedItem as? String ?: "Any State"
                val query = buildQuery(scope, settings)
                val pageSize = if (scope == "Recent Activity") 20 else settings.pageSize

                // Load artifacts and sprint summary in parallel
                val artifactsFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                    client.queryAllArtifacts(query, pageSize)
                }
                val sprintFuture = java.util.concurrent.CompletableFuture.runAsync {
                    loadSprintSummary(client, settings)
                }

                val artifacts = artifactsFuture.get()

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
                }

                // Wait for sprint summary to finish (it updates UI itself)
                try { sprintFuture.get() } catch (_: Exception) {}

            } catch (e: Exception) {
                LOG.error("Failed to load Rally tickets", e)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    loading = false
                    statusLabel.icon = AllIcons.General.Error
                    statusLabel.text = "Error"
                    artifactList.emptyText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun loadProjects(client: RallyApiClient) {
        try {
            val projects = client.queryProjects()
            cachedProjects = projects

            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait

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
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                projectCombo.removeAllItems()
                projectCombo.addItem("All Projects")
                projectCombo.isEnabled = false
                projectsLoaded = true
            }
        }
    }

    private fun loadIterations(client: RallyApiClient) {
        try {
            val rawIterations = client.queryIterations()
            // Deduplicate by name — Rally returns the same iteration per project
            val iterations = rawIterations.distinctBy { it.name }
            cachedIterations = iterations

            // Use invokeAndWait so the combo is fully populated before
            // loadTickets() continues to build the query from the selection
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait

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
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                iterationCombo.removeAllItems()
                iterationCombo.addItem("All Sprints")
                iterationCombo.isEnabled = false
                iterationsLoaded = true
            }
        }
    }

    private fun updateClientProjectRef() {
        val client = currentClient ?: return
        val selectedIndex = projectCombo.selectedIndex
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

    private fun buildQuery(scope: String, settings: RallySettings): String? {
        // workspace/project are passed as URL params by the API client, not as query conditions
        // State/type filtering is done client-side since ScheduleState vs State differs by type
        val conditions = mutableListOf<String>()

        if (scope == "My Tickets" && settings.username.isNotBlank()) {
            conditions.add("(Owner.UserName = \"${settings.username}\")")
        }

        val selectedIter = iterationCombo.selectedItem as? String ?: ""
        if (selectedIter.isNotBlank() && selectedIter != "All Sprints") {
            conditions.add("(Iteration.Name = \"$selectedIter\")")
        }

        val query = when (conditions.size) {
            0 -> null
            1 -> conditions[0]
            else -> conditions.reduce { acc, cond -> "(($acc) AND ($cond))" }
        }
        LOG.info("Rally query: $query (scope=$scope, iteration='$selectedIter', username='${settings.username}')")
        return query
    }

    private fun applyClientFilter(scope: String, stateFilter: String, artifacts: List<RallyArtifact>): List<RallyArtifact> {
        // First apply scope (type) filter
        val scopeFiltered = when (scope) {
            "User Stories" -> artifacts.filter { it.type == "HierarchicalRequirement" }
            "Defects" -> artifacts.filter { it.type == "Defect" }
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

    private fun getSelectedProjectRef(): String? {
        val selectedIndex = projectCombo.selectedIndex
        if (selectedIndex <= 0) return null
        val projectIndex = selectedIndex - 1
        return if (projectIndex < cachedProjects.size) cachedProjects[projectIndex].ref else null
    }

    private fun loadSprintSummary(client: RallyApiClient, settings: RallySettings) {
        try {
            // Use selected iteration if one is picked, otherwise fall back to current date-based iteration
            val selectedIter = iterationCombo.selectedItem as? String ?: ""
            val iteration = if (selectedIter.isNotBlank() && selectedIter != "All Sprints") {
                cachedIterations.firstOrNull { it.name == selectedIter }
            } else {
                client.queryCurrentIteration(
                    if (settings.workspaceRef.isNotBlank()) settings.workspaceRef else null,
                    getSelectedProjectRef()
                )
            }

            if (iteration == null) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    sprintLabel.text = "No active sprint"
                }
                return
            }

            val sprintArtifacts = client.queryIterationArtifacts(iteration.name ?: "")
            val stateCounts = mutableMapOf<String, Int>()
            var totalPoints = 0.0

            for (artifact in sprintArtifacts) {
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
        } catch (e: Exception) {
            LOG.warn("Failed to load sprint summary", e)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                sprintLabel.text = "Sprint: unable to load"
            }
        }
    }

    // ── Search Filter ────────────────────────────────────────────

    private fun applySearchFilter() {
        val query = searchField.text.trim()
        val filtered = if (query.isBlank()) {
            allArtifacts
        } else {
            allArtifacts.filter {
                it.formattedID?.contains(query, ignoreCase = true) == true ||
                        it.name?.contains(query, ignoreCase = true) == true
            }
        }
        listModel.clear()
        filtered.forEach { listModel.addElement(it) }
        updateStats(filtered)
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

    private fun openInBrowser() {
        val selected = artifactList.selectedValue
        if (selected == null) {
            Messages.showInfoMessage(project, "Select a ticket first.", "Rally")
            return
        }
        val url = getClient().buildWebUrl(selected)
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
            val client = getClient()
            val exporter = RallyExporter(client)
            var artifactSuccess = 0
            var artifactFailed = 0
            var tcExported = 0
            var tcFailed = 0

            for (artifact in selected) {
                val id = artifact.formattedID ?: continue
                try {
                    exporter.exportArtifactJson(id, outputDir)
                    exporter.exportArtifactMarkdown(id, outputDir)
                    artifactSuccess++
                } catch (e: Exception) {
                    LOG.error("Failed to export $id", e)
                    artifactFailed++
                }

                // Also export linked test cases
                val ref = artifact.ref ?: continue
                try {
                    val testCases = client.queryTestCases(ref)
                    for (tc in testCases) {
                        val tcId = tc.formattedID ?: continue
                        try {
                            exporter.exportTestCaseJson(tcId, outputDir)
                            exporter.exportTestCaseMarkdown(tcId, outputDir)
                            tcExported++
                        } catch (e: Exception) {
                            LOG.warn("Failed to export test case $tcId", e)
                            tcFailed++
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to query test cases for $id", e)
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                statusLabel.text = "Exported $artifactSuccess artifact(s), $tcExported test case(s)"
                val summary = buildString {
                    append("Exported to:\n$outputDir\n\n")
                    append("Artifacts: $artifactSuccess exported")
                    if (artifactFailed > 0) append(", $artifactFailed failed")
                    append("\nTest Cases: $tcExported exported")
                    if (tcFailed > 0) append(", $tcFailed failed")
                }
                Messages.showMessageDialog(
                    project,
                    summary,
                    "Rally - Export",
                    Messages.getInformationIcon()
                )
            }
        }
    }

    private fun bulkExport() {
        if (allArtifacts.isEmpty()) {
            Messages.showInfoMessage(project, "No tickets loaded. Load tickets first, then bulk export.", "Rally")
            return
        }

        val settings = RallySettings.getInstance()
        val outputDir = settings.exportDirectory.ifBlank {
            project.basePath?.let { "$it/rally_export" } ?: "rally_export"
        }

        val scope = scopeCombo.selectedItem as? String ?: "All Tickets"
        val stateFilter = stateCombo.selectedItem as? String ?: "Any State"
        val filterDesc = if (stateFilter == "Any State") scope else "$scope / $stateFilter"

        val confirm = Messages.showYesNoDialog(
            project,
            "Bulk export ${allArtifacts.size} artifacts ($filterDesc) to:\n$outputDir\n\nThis exports a single consolidated file for AI analysis.",
            "Rally - Bulk Export",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        val total = allArtifacts.size
        statusLabel.text = "Exporting 0/$total..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()
                val exporter = RallyExporter(client)
                val timestamp = java.time.LocalDate.now().toString()
                val fileName = "rally_bulk_${timestamp}"

                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) statusLabel.text = "Exporting JSON 0/$total..."
                }
                val jsonCount = exporter.bulkExportJson(allArtifacts, outputDir, fileName) { processed ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) statusLabel.text = "Exporting JSON $processed/$total..."
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) statusLabel.text = "Exporting Markdown 0/$total..."
                }
                exporter.bulkExportMarkdown(allArtifacts, outputDir, fileName) { processed ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) statusLabel.text = "Exporting Markdown $processed/$total..."
                    }
                }

                // Verify files actually exist before declaring success
                val jsonFile = java.io.File(outputDir, "$fileName.json")
                val mdFile = java.io.File(outputDir, "$fileName.md")

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (jsonFile.exists() && mdFile.exists()) {
                        statusLabel.text = "Exported $jsonCount artifacts"
                        Messages.showMessageDialog(
                            project,
                            "Bulk exported $jsonCount artifacts to:\n${jsonFile.absolutePath}\n${mdFile.absolutePath}\n\nFeed these files to AI for defect pattern analysis.",
                            "Rally - Bulk Export",
                            Messages.getInformationIcon()
                        )
                    } else {
                        statusLabel.text = "Export failed — files not written"
                        Messages.showErrorDialog(
                            project,
                            "Export completed but files were not found at:\n$outputDir\n\nCheck that the directory is writable.",
                            "Rally - Export Error"
                        )
                    }
                }
            } catch (e: Exception) {
                LOG.error("Bulk export failed", e)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    statusLabel.text = "Export failed"
                    Messages.showErrorDialog(project, "Bulk export failed: ${e.message}", "Rally - Error")
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
            val client = getClient()
            var success = 0
            var failed = 0

            for (artifact in selected) {
                try {
                    val ref = artifact.ref ?: continue
                    val type = artifact.type ?: continue
                    client.updateArtifactState(ref, type, newState)
                    success++
                } catch (e: Exception) {
                    LOG.error("Failed to update ${artifact.formattedID}", e)
                    failed++
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (failed > 0) {
                    Messages.showWarningDialog(
                        project,
                        "Updated: $success, Failed: $failed",
                        "Rally - State Change"
                    )
                }
                client.clearCache() // Invalidate after state change
                loadTickets() // Refresh
            }
        }
    }

    // ── Client ───────────────────────────────────────────────────

    private fun getClient(): RallyApiClient {
        val settings = RallySettings.getInstance()
        if (currentClient == null ||
            currentClient?.serverUrl != settings.serverUrl ||
            currentClient?.apiKey != settings.apiKey
        ) {
            currentClient = RallyApiClient(settings.serverUrl, settings.apiKey)
            // Reset caches when client changes
            projectsLoaded = false
            iterationsLoaded = false
        }
        // Workspace always comes from settings
        currentClient!!.workspaceRef = settings.workspaceRef.ifBlank { null }
        // projectRef is managed by the project dropdown (updateClientProjectRef)
        return currentClient!!
    }

    // ── Cell Renderer ────────────────────────────────────────────

    private class ArtifactCellRenderer : ListCellRenderer<RallyArtifact> {
        override fun getListCellRendererComponent(
            list: JList<out RallyArtifact>,
            value: RallyArtifact,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(8, 0))
            panel.border = JBUI.Borders.empty(4, 6)

            if (isSelected) {
                panel.background = list.selectionBackground
                panel.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                panel.foreground = list.foreground
            }

            // Type icon
            val icon = when (value.type) {
                "HierarchicalRequirement" -> AllIcons.Nodes.PpLib
                "Defect" -> AllIcons.General.Error
                "Task" -> AllIcons.FileTypes.Any_type
                else -> AllIcons.FileTypes.Any_type
            }
            val iconLabel = JLabel(icon)

            // FormattedID + Name
            val id = value.formattedID ?: "?"
            val name = value.name ?: "Untitled"
            val textLabel = JLabel("$id: $name")
            if (isSelected) textLabel.foreground = list.selectionForeground

            // State badge
            val state = value.scheduleState ?: value.state ?: "Unknown"
            val stateLabel = JLabel(state)
            stateLabel.foreground = when (state) {
                "In-Progress" -> JBColor(Color(0, 128, 0), Color(100, 200, 100))
                "Completed" -> JBColor(Color(0, 0, 180), Color(100, 150, 255))
                "Accepted" -> JBColor.GRAY
                "Defined" -> JBColor(Color(200, 120, 0), Color(255, 180, 80))
                else -> JBColor.DARK_GRAY
            }
            if (isSelected) stateLabel.foreground = list.selectionForeground

            // Owner
            val ownerName = value.owner?.displayName ?: value.owner?.refObjectName ?: ""
            val ownerLabel = JLabel(ownerName)
            ownerLabel.foreground = JBColor.GRAY
            if (isSelected) ownerLabel.foreground = list.selectionForeground

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
            rightPanel.isOpaque = false
            rightPanel.add(ownerLabel)
            rightPanel.add(stateLabel)

            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textLabel, BorderLayout.CENTER)
            panel.add(rightPanel, BorderLayout.EAST)

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
