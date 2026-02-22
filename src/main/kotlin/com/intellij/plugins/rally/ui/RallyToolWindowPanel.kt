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
import com.intellij.plugins.rally.api.RallyUserStory
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
        private val FILTER_OPTIONS = arrayOf(
            "All Tickets",
            "My Tickets",
            "My In-Progress",
            "My Defined",
            "My Idea",
            "My Completed",
            "Active Tickets",
            "My User Stories",
            "My Defects",
            "Recent Activity"
        )
    }

    private val mainPanel = JPanel(BorderLayout())
    private val listModel = DefaultListModel<RallyArtifact>()
    private val artifactList = JBList(listModel)
    private val filterCombo = ComboBox(FILTER_OPTIONS)
    private val searchField = SearchTextField()
    private val statsLabel = JBLabel("0 items")
    private val sprintLabel = JBLabel("")
    private val statusLabel = JBLabel("Ready")

    private var allArtifacts: List<RallyArtifact> = emptyList()
    private var currentClient: RallyApiClient? = null
    private var loading = false

    init {
        setupUI()
        setupListeners()
    }

    fun getContent(): JComponent = mainPanel

    // ── UI Setup ─────────────────────────────────────────────────

    private fun setupUI() {
        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.add(createButton("Refresh", AllIcons.Actions.Refresh) { loadTickets() })
        toolbar.add(createButton("Open in Browser", AllIcons.General.Web) { openInBrowser() })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(2, 24) })
        toolbar.add(createButton("In-Progress", AllIcons.Actions.Execute) { changeState("In-Progress") })
        toolbar.add(createButton("Completed", AllIcons.Actions.Checked) { changeState("Completed") })
        toolbar.add(createButton("Defined", AllIcons.Actions.MoveToButton) { changeState("Defined") })
        toolbar.add(Box.createHorizontalGlue())
        toolbar.add(statsLabel)

        // Filter row
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        filterPanel.add(JBLabel("Filter:"))
        filterPanel.add(filterCombo)
        filterPanel.add(JBLabel("Search:"))
        searchField.preferredSize = java.awt.Dimension(250, searchField.preferredSize.height)
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

        // Bottom sprint + status
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = JBUI.Borders.empty(2, 6)
        bottomPanel.add(sprintLabel, BorderLayout.CENTER)
        bottomPanel.add(statusLabel, BorderLayout.EAST)

        // Assemble
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun createButton(text: String, icon: Icon, action: () -> Unit): JButton {
        return JButton(text, icon).apply {
            isFocusable = false
            addActionListener { action() }
        }
    }

    private fun setupListeners() {
        // Filter change
        filterCombo.addActionListener { loadTickets() }

        // Search as you type
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                applySearchFilter()
            }
        })

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
        menu.show(artifactList, e.x, e.y)
    }

    // ── Data Loading ─────────────────────────────────────────────

    fun loadTickets() {
        val settings = RallySettings.getInstance()
        if (!settings.isConfigured()) {
            statusLabel.text = "Not configured"
            artifactList.emptyText.text = "Configure Rally in Settings → Tools → Rally"
            return
        }

        if (loading) return
        loading = true
        statusLabel.text = "Loading..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = getClient()
                val filter = filterCombo.selectedItem as? String ?: "My Tickets"
                val query = buildQuery(filter, settings)
                val pageSize = if (filter == "Recent Activity") 20 else settings.pageSize

                val artifacts = client.queryAllArtifacts(query, pageSize)

                // Client-side filtering for state/type-based filters
                val filtered = applyClientFilter(filter, artifacts)

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    allArtifacts = filtered
                    applySearchFilter()
                    loading = false
                    statusLabel.text = "${filtered.size} loaded"
                    if (filtered.isEmpty()) {
                        artifactList.emptyText.text = "No tickets found for filter: $filter"
                    }
                }

                // Also load sprint summary
                loadSprintSummary(client, settings)

            } catch (e: Exception) {
                LOG.error("Failed to load Rally tickets", e)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    loading = false
                    statusLabel.text = "Error"
                    artifactList.emptyText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun buildQuery(filter: String, settings: RallySettings): String? {
        // workspace/project are passed as URL params by the API client, not as query conditions
        // State/type filtering is done client-side since ScheduleState vs State differs by type
        val isMyFilter = filter.startsWith("My ")
        if (isMyFilter && settings.username.isNotBlank()) {
            return "(Owner.UserName = \"${settings.username}\")"
        }
        if (filter == "Active Tickets" && settings.username.isNotBlank()) {
            return "(Owner.UserName = \"${settings.username}\")"
        }
        return null
    }

    private fun applyClientFilter(filter: String, artifacts: List<RallyArtifact>): List<RallyArtifact> {
        return when (filter) {
            "Active Tickets" -> artifacts.filter {
                val state = it.scheduleState ?: it.state ?: ""
                state !in setOf("Accepted", "Completed", "Deployed", "Idea")
            }
            "My In-Progress" -> artifacts.filter {
                val state = it.scheduleState ?: it.state ?: ""
                state.equals("In-Progress", ignoreCase = true)
            }
            "My Defined" -> artifacts.filter {
                val state = it.scheduleState ?: it.state ?: ""
                state.equals("Defined", ignoreCase = true)
            }
            "My Idea" -> artifacts.filter {
                val state = it.scheduleState ?: it.state ?: ""
                state.equals("Idea", ignoreCase = true)
            }
            "My Completed" -> artifacts.filter {
                val state = it.scheduleState ?: it.state ?: ""
                state.equals("Completed", ignoreCase = true)
            }
            "My User Stories" -> artifacts.filter {
                it.type == "HierarchicalRequirement"
            }
            "My Defects" -> artifacts.filter {
                it.type == "Defect"
            }
            else -> artifacts
        }
    }

    private fun loadSprintSummary(client: RallyApiClient, settings: RallySettings) {
        try {
            val iteration = client.queryCurrentIteration(
                if (settings.workspaceRef.isNotBlank()) settings.workspaceRef else null,
                if (settings.projectRef.isNotBlank()) settings.projectRef else null
            )

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
        }
        // Always update workspace/project refs from settings
        currentClient!!.workspaceRef = settings.workspaceRef.ifBlank { null }
        currentClient!!.projectRef = settings.projectRef.ifBlank { null }
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
