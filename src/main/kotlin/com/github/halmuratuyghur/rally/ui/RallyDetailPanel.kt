package com.github.halmuratuyghur.rally.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.github.halmuratuyghur.rally.api.*
import com.github.halmuratuyghur.rally.export.RallyExporter
import com.github.halmuratuyghur.rally.settings.RallySettings
import com.github.halmuratuyghur.rally.util.RallyFileUtils
import com.github.halmuratuyghur.rally.util.RallyHtmlUtils
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import javax.swing.*

class RallyDetailPanel(private val project: Project) : com.intellij.openapi.Disposable {

    private val imageExecutor: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "rally-image-worker").apply { isDaemon = true }
    }

    companion object {
        private val LOG = Logger.getInstance(RallyDetailPanel::class.java)
        private const val TAB_TEST_CASES = 0
        private const val TAB_TASKS = 1
        private const val TAB_ATTACHMENTS = 2
        // Sentinel returned by the description-fetch supplyAsync when Rally rejects
        // the API key, so the EDT-side renderer can show an actionable error rather
        // than a generic "No description". The leading control char ensures Rally
        // HTML can never accidentally collide with this value.
        private const val DESC_AUTH_FAILED = "\u0001RALLY_AUTH_FAILED\u0001"
        private const val AUTH_ERROR_HTML =
            "<span style='color:#c00'><b>Authentication failed.</b> " +
            "Check your Rally API key in Settings → Tools → Rally.</span>"

        // Colors are defined in RallyColors object
        // Matches external src attributes (double- or single-quoted, protocol-relative included)
        // so JTextPane doesn't fetch them over the network before we neutralize the description.
        private val EXTERNAL_SRC_PATTERN = Pattern.compile(
            """src\s*=\s*(["'])(?:https?:)?//[^"']*\1""",
            Pattern.CASE_INSENSITIVE
        )

        fun formatFileSize(bytes: Long?): String {
            if (bytes == null || bytes <= 0) return ""
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }

    val component: JPanel = JPanel(BorderLayout())

    private val headerLabel = JBLabel("Select a ticket to view details")
    private val stateBadge = JBLabel()
    private val metadataLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 8, 4, 8)
    }
    private val descriptionPane = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        border = JBUI.Borders.empty(4)
    }

    // Test Cases
    private val testCaseListModel = DefaultListModel<RallyTestCase>()
    private val testCaseList = JBList(testCaseListModel)
    private val testCaseSummaryLabel = JBLabel("").apply { border = JBUI.Borders.empty(2, 4) }
    // Test Cases tab content (list + summary footer). A field so the tab-rebuild sites in
    // showArtifact()/clear() reuse it; rebuilding with a bare JBScrollPane(testCaseList)
    // detached testCaseSummaryLabel and silently dropped the "N total (…)" footer.
    private val tcPanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(testCaseList), BorderLayout.CENTER)
        add(testCaseSummaryLabel, BorderLayout.SOUTH)
    }

    // Tasks
    private val taskListModel = DefaultListModel<RallyTaskItem>()
    private val taskList = JBList(taskListModel)

    // Attachments
    private val attachmentListModel = DefaultListModel<RallyAttachment>()
    private val attachmentList = JBList(attachmentListModel)

    // Test Steps
    private val stepListModel = DefaultListModel<RallyTestCaseStep>()
    private val stepList = JBList(stepListModel)

    // Scroll panes are fields (like tcPanel) so the tab-restore sites in
    // showArtifact()/clear() reuse them instead of allocating fresh
    // JBScrollPane + viewport + scrollbar UI on every selection toggle.
    private val taskScrollPane = JBScrollPane(taskList)
    private val attachmentScrollPane = JBScrollPane(attachmentList)
    private val stepScrollPane = JBScrollPane(stepList)

    // Tabbed pane
    private val tabbedPane = JBTabbedPane()

    @Volatile private var currentArtifactRef: String? = null
    @Volatile private var currentArtifact: RallyArtifact? = null
    @Volatile private var currentClient: RallyApiClient? = null

    /** Incremented on every showArtifact/clear call; background workers check this to bail out early. */
    private val generation = AtomicLong(0)
    @Volatile private var disposed = false

    override fun dispose() {
        disposed = true
        generation.incrementAndGet()
        imageExecutor.shutdownNow()
    }

    // Header action buttons
    private val copyButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy FormattedID"
        isVisible = false
        isBorderPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(0, 4)
        addActionListener {
            val id = currentArtifact?.formattedID ?: return@addActionListener
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(id), null)
            toolTipText = "Copied $id!"
            javax.swing.Timer(2000) { toolTipText = "Copy FormattedID" }.apply { isRepeats = false; start() }
        }
    }

    private val browserButton = JButton(AllIcons.General.Web).apply {
        toolTipText = "Open in Browser"
        isVisible = false
        isBorderPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(0, 4)
        addActionListener {
            val artifact = currentArtifact ?: return@addActionListener
            val client = currentClient ?: return@addActionListener
            BrowserUtil.browse(client.buildWebUrl(artifact))
        }
    }

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        // Header
        val headerPanel = JPanel(BorderLayout(8, 0))
        headerPanel.border = JBUI.Borders.empty(6, 8)
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 13f)
        headerPanel.add(headerLabel, BorderLayout.CENTER)
        stateBadge.border = JBUI.Borders.empty(2, 8)
        val headerRightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        headerRightPanel.isOpaque = false
        headerRightPanel.add(copyButton)
        headerRightPanel.add(browserButton)
        headerRightPanel.add(stateBadge)
        headerPanel.add(headerRightPanel, BorderLayout.EAST)

        // Description
        val descScrollPane = JBScrollPane(descriptionPane)
        descScrollPane.border = BorderFactory.createTitledBorder("Description")

        // Test Cases tab
        testCaseList.cellRenderer = TestCaseCellRenderer()
        testCaseList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        testCaseList.emptyText.text = "No test cases"
        // tcPanel (list + summary footer) is built once as a field; see its declaration.

        // Tasks tab
        taskList.cellRenderer = TaskCellRenderer()
        taskList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        taskList.emptyText.text = "No tasks"

        // Attachments tab
        attachmentList.cellRenderer = AttachmentCellRenderer()
        attachmentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        attachmentList.emptyText.text = "No attachments"

        // Test Steps tab
        stepList.cellRenderer = StepCellRenderer()
        stepList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        stepList.emptyText.text = "Select a test case to view steps"

        // Tabbed pane with 3 standard tabs (Test Steps shown only when a test case is selected)
        tabbedPane.addTab("Test Cases", tcPanel)
        tabbedPane.addTab("Tasks", taskScrollPane)
        tabbedPane.addTab("Attachments", attachmentScrollPane)

        // Split: description (40%) / tabbed pane (60%) with thin dark divider
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, descScrollPane, tabbedPane)
        splitPane.resizeWeight = 0.4
        splitPane.border = null
        splitPane.dividerSize = 3
        splitPane.setUI(object : javax.swing.plaf.basic.BasicSplitPaneUI() {
            override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider {
                return object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    override fun paint(g: Graphics) {
                        g.color = RallyColors.DIVIDER
                        g.fillRect(0, 0, width, height)
                    }
                }
            }
        })

        val headerWrapper = JPanel(BorderLayout())
        headerWrapper.add(headerPanel, BorderLayout.NORTH)
        headerWrapper.add(metadataLabel, BorderLayout.SOUTH)

        component.add(headerWrapper, BorderLayout.NORTH)
        component.add(splitPane, BorderLayout.CENTER)
    }

    private fun setupListeners() {
        // Test case listeners
        testCaseList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showTestCaseContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showTestCaseContextMenu(e)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) loadTestSteps()
            }
        })

        // Task listeners
        taskList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showTaskContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showTaskContextMenu(e)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openTaskInBrowser()
            }
        })

        // Attachment listeners
        attachmentList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showAttachmentContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showAttachmentContextMenu(e)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) saveAttachmentToDisk()
            }
        })
    }

    fun showArtifact(artifact: RallyArtifact?, client: RallyApiClient?) {
        if (disposed) return
        if (artifact == null || client == null) {
            clear()
            return
        }
        // Bail if the caller passed a client whose thread pool has already been
        // shut down by a settings change or parent disposal — submitting tasks
        // to a dead executor would throw RejectedExecutionException from inside
        // every supplyAsync block below.
        if (!client.isAlive) {
            LOG.warn("showArtifact called with a disposed client; skipping")
            clear()
            return
        }

        val artifactRef = artifact.ref ?: return
        val gen = generation.incrementAndGet()
        currentArtifactRef = artifactRef
        currentArtifact = artifact
        currentClient = client

        // Restore standard tabs if previously showing a test case
        if (tabbedPane.tabCount != 3 || (tabbedPane.tabCount > 0 && tabbedPane.getTitleAt(0).startsWith("Test Steps"))) {
            tabbedPane.removeAll()
            tabbedPane.addTab("Test Cases", tcPanel)
            tabbedPane.addTab("Tasks", taskScrollPane)
            tabbedPane.addTab("Attachments", attachmentScrollPane)
        }

        // Update header
        val id = artifact.formattedID ?: "?"
        val name = artifact.name ?: "Untitled"
        headerLabel.text = "$id: $name"
        copyButton.isVisible = true
        browserButton.isVisible = true

        val state = if (artifact is RallyTestCase) {
            artifact.lastVerdict ?: "No Verdict"
        } else {
            artifact.scheduleState ?: artifact.state ?: "Unknown"
        }
        stateBadge.text = state
        stateBadge.foreground = stateColor(state)

        // Metadata strip
        metadataLabel.text = buildMetadataText(artifact)
        metadataLabel.isVisible = true

        // Show description if already available, otherwise show loading state
        val desc = artifact.description
        if (!desc.isNullOrBlank()) {
            descriptionPane.text = wrapHtml(desc)
            descriptionPane.caretPosition = 0
        } else {
            descriptionPane.text = wrapHtml("<i>Loading description...</i>")
        }

        if (artifact is RallyTestCase) {
            // For test cases: show description + test steps only
            tabbedPane.removeAll()
            tabbedPane.addTab("Test Steps", stepScrollPane)
            stepListModel.clear()

            ApplicationManager.getApplication().executeOnPooledThread {
                val descFuture = CompletableFuture.supplyAsync({
                    if (generation.get() != gen) return@supplyAsync null
                    var resolved = desc
                    if (resolved.isNullOrBlank()) {
                        try {
                            resolved = client.fetchDescription(artifactRef)
                        } catch (e: RallyAuthenticationException) {
                            LOG.warn("Auth failure fetching description for $id", e)
                            return@supplyAsync DESC_AUTH_FAILED
                        } catch (e: Exception) {
                            LOG.warn("Failed to fetch description for $id", e)
                        }
                    }
                    if (generation.get() != gen) return@supplyAsync null
                    val resolvedNonNull = resolved
                    if (!resolvedNonNull.isNullOrBlank()) {
                        try { resolveInlineImages(resolvedNonNull, client, gen) } catch (e: Exception) {
                            LOG.warn("Failed to resolve inline images for $id", e)
                            resolvedNonNull
                        }
                    } else null
                }, client.apiExecutor)

                val stepsFuture = CompletableFuture.supplyAsync({
                    if (generation.get() != gen) return@supplyAsync null
                    try { client.queryTestSteps(id) } catch (e: Exception) {
                        LOG.warn("Failed to load test steps for $id", e)
                        null
                    }
                }, client.apiExecutor)

                descFuture.thenAccept { resolvedDesc ->
                    ApplicationManager.getApplication().invokeLater {
                        if (generation.get() != gen || disposed) return@invokeLater
                        val text = when {
                            resolvedDesc == DESC_AUTH_FAILED -> AUTH_ERROR_HTML
                            !resolvedDesc.isNullOrBlank() -> resolvedDesc
                            else -> "<i>No description</i>"
                        }
                        descriptionPane.text = wrapHtml(text)
                        descriptionPane.caretPosition = 0
                    }
                }.exceptionally { t -> LOG.warn("Detail panel description update failed", t); null }

                stepsFuture.thenAccept { steps ->
                    ApplicationManager.getApplication().invokeLater {
                        if (generation.get() != gen || disposed) return@invokeLater
                        stepListModel.clear()
                        if (steps != null) {
                            steps.forEach { stepListModel.addElement(it) }
                            tabbedPane.setTitleAt(0, "Test Steps (${steps.size})")
                        } else {
                            tabbedPane.setTitleAt(0, "Test Steps (0)")
                        }
                    }
                }.exceptionally { t -> LOG.warn("Detail panel steps update failed", t); null }
            }
            return  // Skip the normal story/defect detail loading
        }

        // Reset to first tab and clear all lists
        tabbedPane.selectedIndex = TAB_TEST_CASES
        testCaseListModel.clear()
        testCaseSummaryLabel.text = "Loading..."
        taskListModel.clear()
        attachmentListModel.clear()
        stepListModel.clear()
        tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases (...)")
        tabbedPane.setTitleAt(TAB_TASKS, "Tasks (...)")
        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (...)")

        // Load description, test cases, tasks, and attachments in parallel
        ApplicationManager.getApplication().executeOnPooledThread {
            // Launch all four queries concurrently
            val descFuture = CompletableFuture.supplyAsync({
                if (generation.get() != gen) return@supplyAsync null
                var resolved = desc
                if (resolved.isNullOrBlank()) {
                    try {
                        resolved = client.fetchDescription(artifactRef)
                    } catch (e: RallyAuthenticationException) {
                        LOG.warn("Auth failure fetching description for $id", e)
                        return@supplyAsync DESC_AUTH_FAILED
                    } catch (e: Exception) {
                        LOG.warn("Failed to fetch description for $id", e)
                    }
                }
                if (generation.get() != gen) return@supplyAsync null
                val resolvedNonNull = resolved
                if (!resolvedNonNull.isNullOrBlank()) {
                    try { resolveInlineImages(resolvedNonNull, client, gen) } catch (e: Exception) {
                        LOG.warn("Failed to resolve inline images for $id", e)
                        resolvedNonNull
                    }
                } else null
            }, client.apiExecutor)

            val tcFuture = CompletableFuture.supplyAsync({
                if (generation.get() != gen) return@supplyAsync null
                try { client.queryTestCases(artifactRef) } catch (e: Exception) {
                    LOG.warn("Failed to load test cases for $id", e)
                    null
                }
            }, client.apiExecutor)

            val taskFuture = CompletableFuture.supplyAsync({
                if (generation.get() != gen) return@supplyAsync null
                try { client.queryTasksForWorkProduct(artifactRef) } catch (e: Exception) {
                    LOG.warn("Failed to load tasks for $id", e)
                    null
                }
            }, client.apiExecutor)

            val attachFuture = CompletableFuture.supplyAsync({
                if (generation.get() != gen) return@supplyAsync null
                try { client.queryAttachments(id) } catch (e: Exception) {
                    LOG.warn("Failed to load attachments for $id", e)
                    null
                }
            }, client.apiExecutor)

            // Update UI as each completes
            descFuture.thenAccept { resolvedDesc ->
                ApplicationManager.getApplication().invokeLater {
                    if (generation.get() != gen || disposed) return@invokeLater
                    val text = when {
                        resolvedDesc == DESC_AUTH_FAILED -> AUTH_ERROR_HTML
                        !resolvedDesc.isNullOrBlank() -> resolvedDesc
                        else -> "<i>No description</i>"
                    }
                    descriptionPane.text = wrapHtml(text)
                    descriptionPane.caretPosition = 0
                }
            }.exceptionally { t -> LOG.warn("Detail panel description update failed", t); null }

            tcFuture.thenAccept { testCases ->
                ApplicationManager.getApplication().invokeLater {
                    if (generation.get() != gen || disposed) return@invokeLater
                    testCaseListModel.clear()
                    if (testCases != null) {
                        testCaseListModel.addAll(testCases)
                        val automated = testCases.count { it.method == "Automated" }
                        val manual = testCases.size - automated
                        testCaseSummaryLabel.text = "${testCases.size} total ($automated automated, $manual manual)"
                        tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases (${testCases.size})")
                        tabbedPane.setToolTipTextAt(TAB_TEST_CASES, null)
                    } else {
                        testCaseSummaryLabel.text = "Failed to load test cases — see idea.log for details"
                        tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases (!)")
                        tabbedPane.setToolTipTextAt(TAB_TEST_CASES, "Failed to load test cases — see idea.log for details")
                    }
                }
            }.exceptionally { t -> LOG.warn("Detail panel test cases update failed", t); null }

            taskFuture.thenAccept { tasks ->
                ApplicationManager.getApplication().invokeLater {
                    if (generation.get() != gen || disposed) return@invokeLater
                    taskListModel.clear()
                    if (tasks != null) {
                        taskListModel.addAll(tasks)
                        tabbedPane.setTitleAt(TAB_TASKS, "Tasks (${tasks.size})")
                        tabbedPane.setToolTipTextAt(TAB_TASKS, null)
                    } else {
                        tabbedPane.setTitleAt(TAB_TASKS, "Tasks (!)")
                        tabbedPane.setToolTipTextAt(TAB_TASKS, "Failed to load tasks — see idea.log for details")
                    }
                }
            }.exceptionally { t -> LOG.warn("Detail panel tasks update failed", t); null }

            attachFuture.thenAccept { attachments ->
                ApplicationManager.getApplication().invokeLater {
                    if (generation.get() != gen || disposed) return@invokeLater
                    attachmentListModel.clear()
                    if (attachments != null) {
                        attachmentListModel.addAll(attachments)
                        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (${attachments.size})")
                        tabbedPane.setToolTipTextAt(TAB_ATTACHMENTS, null)
                    } else {
                        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (!)")
                        tabbedPane.setToolTipTextAt(TAB_ATTACHMENTS, "Failed to load attachments — see idea.log for details")
                    }
                }
            }.exceptionally { t -> LOG.warn("Detail panel attachments update failed", t); null }
        }
    }

    fun clear() {
        if (disposed) return
        generation.incrementAndGet()
        currentArtifactRef = null
        currentArtifact = null
        currentClient = null
        headerLabel.text = "Select a ticket to view details"
        stateBadge.text = ""
        metadataLabel.text = ""
        metadataLabel.isVisible = false
        copyButton.isVisible = false
        browserButton.isVisible = false
        descriptionPane.text = ""
        testCaseListModel.clear()
        testCaseSummaryLabel.text = ""
        taskListModel.clear()
        attachmentListModel.clear()
        stepListModel.clear()
        // Restore standard tabs if previously showing a test case
        if (tabbedPane.tabCount != 3 || (tabbedPane.tabCount > 0 && tabbedPane.getTitleAt(0).startsWith("Test Steps"))) {
            tabbedPane.removeAll()
            tabbedPane.addTab("Test Cases", tcPanel)
            tabbedPane.addTab("Tasks", taskScrollPane)
            tabbedPane.addTab("Attachments", attachmentScrollPane)
        }
        updateTabTitles(0, 0, 0)
    }

    private fun buildMetadataText(artifact: RallyArtifact): String {
        val parts = mutableListOf<String>()

        // Owner
        val ownerName = artifact.owner?.displayName ?: artifact.owner?.refObjectName
        if (ownerName != null) parts.add("Owner: $ownerName")

        // Type-specific fields
        when (artifact) {
            is RallyUserStory -> {
                artifact.planEstimate?.let { parts.add("Points: ${it.toInt()}") }
                artifact.iteration?.let { iter ->
                    val name = iter.name ?: iter.refObjectName
                    if (name != null) parts.add("Sprint: $name")
                }
                if (artifact.blocked == true) {
                    parts.add("BLOCKED" + (artifact.blockedReason?.let { ": $it" } ?: ""))
                }
                artifact.release?.let { rel ->
                    val name = rel.name ?: rel.refObjectName
                    if (name != null) parts.add("Release: $name")
                }
            }
            is RallyDefect -> {
                artifact.severity?.let { parts.add("Severity: $it") }
                artifact.priority?.let { parts.add("Priority: $it") }
                artifact.environment?.let { if (it.isNotBlank()) parts.add("Env: $it") }
                artifact.planEstimate?.let { parts.add("Points: ${it.toInt()}") }
                artifact.iteration?.let { iter ->
                    val name = iter.name ?: iter.refObjectName
                    if (name != null) parts.add("Sprint: $name")
                }
                if (artifact.blocked == true) {
                    parts.add("BLOCKED" + (artifact.blockedReason?.let { ": $it" } ?: ""))
                }
                artifact.release?.let { rel ->
                    val name = rel.name ?: rel.refObjectName
                    if (name != null) parts.add("Release: $name")
                }
            }
            is RallyTestCase -> {
                artifact.method?.let { parts.add("Method: $it") }
                artifact.lastVerdict?.let { parts.add("Last Verdict: $it") }
            }
        }

        return parts.joinToString("  |  ")
    }

    private fun updateTabTitles(testCases: Int, tasks: Int, attachments: Int) {
        if (tabbedPane.tabCount >= 3) {
            tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases ($testCases)")
            tabbedPane.setTitleAt(TAB_TASKS, "Tasks ($tasks)")
            tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments ($attachments)")
        }
    }

    // ── Test Case Context Menu ──────────────────────────────────

    private fun showTestCaseContextMenu(e: MouseEvent) {
        val index = testCaseList.locationToIndex(e.point)
        if (index < 0) return
        if (!testCaseList.isSelectedIndex(index)) {
            testCaseList.selectedIndex = index
        }

        val menu = JPopupMenu()
        menu.add(JMenuItem("View Test Steps").apply {
            icon = AllIcons.Actions.ListFiles
            addActionListener { loadTestSteps() }
        })
        menu.addSeparator()
        menu.add(JMenuItem("Export to JSON/Markdown").apply {
            icon = AllIcons.ToolbarDecorator.Export
            addActionListener { exportSelectedTestCases(json = true, markdown = true) }
        })
        menu.addSeparator()
        menu.add(JMenuItem("Open in Browser").apply {
            icon = AllIcons.General.Web
            addActionListener { openTestCaseInBrowser() }
        })
        menu.show(testCaseList, e.x, e.y)
    }

    private fun exportSelectedTestCases(json: Boolean, markdown: Boolean) {
        val selected = testCaseList.selectedValuesList
        if (selected.isEmpty()) return
        val client = currentClient ?: return

        val settings = RallySettings.getInstance()
        val outputDir = settings.exportDirectory.ifBlank {
            project.basePath?.let { "$it/rally_testcases" } ?: "rally_testcases"
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val exporter = RallyExporter(client)
            var success = 0
            for (tc in selected) {
                val tcId = tc.formattedID ?: continue
                try {
                    if (json) exporter.exportTestCaseJson(tcId, outputDir)
                    if (markdown) exporter.exportTestCaseMarkdown(tcId, outputDir)
                    success++
                } catch (e: Exception) {
                    LOG.error("Failed to export $tcId", e)
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                Messages.showMessageDialog(
                    project,
                    "Exported $success/${selected.size} test case(s) to:\n$outputDir",
                    "Rally - Export",
                    AllIcons.General.InspectionsOK
                )
            }
        }
    }

    private fun loadTestSteps() {
        val selected = testCaseList.selectedValue ?: return
        val tcId = selected.formattedID ?: return
        val client = currentClient ?: return
        val gen = generation.get()

        stepListModel.clear()

        // Add Test Steps tab dynamically if not already present
        var stepsTabIndex = -1
        for (i in 0 until tabbedPane.tabCount) {
            if (tabbedPane.getTitleAt(i).startsWith("Test Steps")) {
                stepsTabIndex = i
                break
            }
        }
        if (stepsTabIndex < 0) {
            tabbedPane.addTab("Test Steps (...)", stepScrollPane)
            stepsTabIndex = tabbedPane.tabCount - 1
        } else {
            tabbedPane.setTitleAt(stepsTabIndex, "Test Steps (...)")
        }
        tabbedPane.selectedIndex = stepsTabIndex

        ApplicationManager.getApplication().executeOnPooledThread {
            val steps = try {
                client.queryTestSteps(tcId)
            } catch (ex: Exception) {
                LOG.warn("Failed to load test steps for $tcId", ex)
                null
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed || generation.get() != gen) return@invokeLater
                // `generation` is NOT bumped when a different *linked* test case is
                // double-clicked (no showArtifact/clear happens), so guard on the
                // selected test case too: otherwise a slow TC-A response can render
                // its steps under a newly-selected TC-B.
                if (testCaseList.selectedValue?.formattedID != tcId) return@invokeLater
                stepListModel.clear()
                // Re-find the test steps tab index in case tabs changed
                var currentStepsTab = -1
                for (i in 0 until tabbedPane.tabCount) {
                    if (tabbedPane.getTitleAt(i).startsWith("Test Steps")) {
                        currentStepsTab = i
                        break
                    }
                }
                if (currentStepsTab >= 0) {
                    if (steps != null) {
                        stepListModel.addAll(steps)
                        tabbedPane.setTitleAt(currentStepsTab, "Test Steps (${steps.size})")
                        tabbedPane.setToolTipTextAt(currentStepsTab, null)
                    } else {
                        tabbedPane.setTitleAt(currentStepsTab, "Test Steps (!)")
                        tabbedPane.setToolTipTextAt(currentStepsTab, "Failed to load test steps — see idea.log for details")
                    }
                }
            }
        }
    }

    private fun openTestCaseInBrowser() {
        val selected = testCaseList.selectedValue ?: return
        val client = currentClient ?: return
        val objectId = selected.objectID ?: return
        val projectOid = getParentProjectOid()
        val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
        BrowserUtil.browse("${client.webBaseUrl}/#/${projectSegment}detail/testcase/$objectId")
    }

    // ── Task Context Menu ───────────────────────────────────────

    private fun showTaskContextMenu(e: MouseEvent) {
        val index = taskList.locationToIndex(e.point)
        if (index >= 0 && !taskList.isSelectedIndex(index)) {
            taskList.selectedIndex = index
        }

        val menu = JPopupMenu()
        menu.add(JMenuItem("Create Task").apply {
            icon = AllIcons.General.Add
            addActionListener { showCreateTaskDialog() }
        })
        if (index >= 0) {
            menu.addSeparator()
            menu.add(JMenuItem("Open in Browser").apply {
                icon = AllIcons.General.Web
                addActionListener { openTaskInBrowser() }
            })
        }
        menu.show(taskList, e.x, e.y)
    }

    private fun openTaskInBrowser() {
        val selected = taskList.selectedValue ?: return
        val client = currentClient ?: return
        val objectId = selected.objectID ?: return
        val projectOid = getParentProjectOid()
        val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
        BrowserUtil.browse("${client.webBaseUrl}/#/${projectSegment}detail/task/$objectId")
    }

    private fun showCreateTaskDialog() {
        val artifact = currentArtifact ?: return
        val artifactRef = artifact.ref ?: return
        val client = currentClient ?: return
        val artifactId = artifact.formattedID ?: "?"

        // Capture the generation alongside artifactRef, BEFORE the modal opens, so
        // the guard can't tag a task created against the old artifact with a newer
        // generation.
        val gen = generation.get()
        val dialog = CreateTaskDialog(artifactId)
        if (!dialog.showAndGet()) return

        val taskName = dialog.nameField.text.trim()
        val estimate = dialog.estimateField.text.trim().toDoubleOrNull()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val task = client.createTask(taskName, artifactRef, estimate = estimate)
                // Invalidate the cached task list for this work product so the next
                // detail-panel reopen re-fetches and includes the new task.
                client.clearTasksCache(artifactRef)
                ApplicationManager.getApplication().invokeLater {
                    // Generation check: if the user switched artifacts while createTask
                    // was in flight, this row belongs to the PREVIOUS artifact's Tasks
                    // tab — clearTasksCache above already guarantees it appears when
                    // that artifact is next opened.
                    if (disposed || generation.get() != gen) return@invokeLater
                    taskListModel.addElement(task)
                    val count = taskListModel.size()
                    for (i in 0 until tabbedPane.tabCount) {
                        if (tabbedPane.getTitleAt(i).startsWith("Tasks")) {
                            tabbedPane.setTitleAt(i, "Tasks ($count)")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to create task", e)
                ApplicationManager.getApplication().invokeLater {
                    if (disposed) return@invokeLater
                    Messages.showErrorDialog(project, "Failed to create task: ${e.message}", "Rally")
                }
            }
        }
    }

    private inner class CreateTaskDialog(artifactId: String) : DialogWrapper(project) {
        val nameField = com.intellij.ui.components.JBTextField()
        val estimateField = com.intellij.ui.components.JBTextField().apply {
            toolTipText = "Estimate in hours (optional)"
        }

        init {
            title = "Create Task for $artifactId"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            panel.border = JBUI.Borders.empty(8)
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 4, 4, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
            panel.add(JBLabel("Task Name:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(nameField, gbc)
            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
            panel.add(JBLabel("Estimate (hrs):"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(estimateField, gbc)

            panel.preferredSize = Dimension(400, 100)
            return panel
        }

        override fun doValidate(): ValidationInfo? {
            if (nameField.text.isNullOrBlank()) {
                return ValidationInfo("Task name is required", nameField)
            }
            return null
        }

        override fun getPreferredFocusedComponent(): JComponent = nameField
    }

    // ── Attachment Context Menu & Actions ────────────────────────

    private fun showAttachmentContextMenu(e: MouseEvent) {
        val index = attachmentList.locationToIndex(e.point)
        if (index < 0) return
        if (!attachmentList.isSelectedIndex(index)) {
            attachmentList.selectedIndex = index
        }

        val menu = JPopupMenu()
        menu.add(JMenuItem("Save to Disk").apply {
            icon = AllIcons.Actions.MenuSaveall
            addActionListener { saveAttachmentToDisk() }
        })
        menu.addSeparator()
        menu.add(JMenuItem("Open in Browser").apply {
            icon = AllIcons.General.Web
            addActionListener { openAttachmentInBrowser() }
        })
        menu.show(attachmentList, e.x, e.y)
    }

    private fun saveAttachmentToDisk() {
        val selected = attachmentList.selectedValue ?: return
        val client = currentClient ?: return
        val contentRef = selected.content?.ref ?: run {
            Messages.showErrorDialog(project, "No content reference for this attachment.", "Rally - Download Error")
            return
        }
        val fileName = RallyFileUtils.sanitizeFileName(selected.name ?: "attachment")

        // FileSaverDescriptor's only public constructor through 2024.x is the vararg
        // (title, description, vararg extensions) form, which newer platforms deprecate.
        // No alternative exists at sinceBuild=241, so suppress until the floor is raised.
        @Suppress("DEPRECATION")
        val descriptor = FileSaverDescriptor("Save Attachment", "Choose where to save the attachment")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(
            project.basePath ?: System.getProperty("user.home")
        )
        val fileWrapper = wrapper.save(baseDir, fileName) ?: return
        val targetFile = fileWrapper.file

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val base64Content = client.getAttachmentContent(contentRef)
                // Rally returns MIME base64 with embedded line breaks — the strict
                // decoder throws IllegalArgumentException on real attachments.
                val bytes = Base64.getMimeDecoder().decode(base64Content)
                targetFile.writeBytes(bytes)

                ApplicationManager.getApplication().invokeLater {
                    if (disposed || project.isDisposed) return@invokeLater
                    Messages.showMessageDialog(
                        project,
                        "Saved to: ${targetFile.absolutePath}",
                        "Rally - Attachment Saved",
                        AllIcons.General.InspectionsOK
                    )
                }
            } catch (e: Exception) {
                LOG.error("Failed to download attachment ${selected.name}", e)
                ApplicationManager.getApplication().invokeLater {
                    if (disposed || project.isDisposed) return@invokeLater
                    Messages.showErrorDialog(
                        project,
                        "Failed to download: ${e.message}",
                        "Rally - Download Error"
                    )
                }
            }
        }
    }

    private fun openAttachmentInBrowser() {
        val selected = attachmentList.selectedValue ?: return
        val client = currentClient ?: return
        val objectId = selected.objectID ?: return
        val projectOid = getParentProjectOid()
        val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
        BrowserUtil.browse("${client.webBaseUrl}/#/${projectSegment}detail/attachment/$objectId")
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun getParentProjectOid(): String? {
        val projectRef = when (val artifact = currentArtifact) {
            is RallyUserStory -> artifact.project?.ref
            is RallyDefect -> artifact.project?.ref
            else -> null
        }
        return projectRef?.trimEnd('/')?.substringAfterLast('/')
    }

    private fun stateColor(state: String): Color = when (state) {
        "In-Progress" -> RallyColors.IN_PROGRESS
        "Completed" -> RallyColors.COMPLETED
        "Accepted" -> JBColor.GRAY
        "Defined" -> RallyColors.DEFINED
        else -> JBColor.DARK_GRAY
    }

    private fun wrapHtml(html: String): String {
        // Use the IDE's label font size so HiDPI displays don't render the
        // description at 11 device pixels (effectively a 5-6pt font on Retina).
        // JBUI.scale converts logical pixels to scaled physical pixels.
        val fontSize = com.intellij.util.ui.JBUI.scaleFontSize(11f)
        // Neutralize any literal </body> or </html> in the Rally description so
        // HTMLEditorKit doesn't truncate the render at the embedded closing tag.
        // We can't fully sanitize the HTML here (Rally lets users author rich
        // descriptions), but escaping these two structural tags is enough to
        // keep our outer wrapper intact.
        val safe = html
            .replace("</body>", "&lt;/body&gt;", ignoreCase = true)
            .replace("</html>", "&lt;/html&gt;", ignoreCase = true)
        // Neutralize external http(s):// (and protocol-relative) image src attributes so
        // JTextPane never makes an off-host network fetch when rendering a Rally-authored
        // description (tracking pixels / SSRF-style leaks). This is the single chokepoint:
        // every non-empty descriptionPane.text assignment goes through wrapHtml, including the early
        // "description already loaded" path and descriptions with only external images
        // (which skip resolveInlineImages' own pass). data:…;base64 URIs produced by
        // resolveInlineImages are left intact — the pattern only matches http(s):// or //.
        val neutralized = EXTERNAL_SRC_PATTERN.matcher(safe).replaceAll("src=\"\"")
        return "<html><body style='font-family:sans-serif;font-size:${fontSize}px;margin:4px;'>$neutralized</body></html>"
    }

    /**
     * Download Rally inline images and replace src URLs with base64 data URIs
     * so JTextPane can display them without authentication.
     */
    /** Maximum inline images to download per description (prevents thread pool saturation). */
    private val maxInlineImages = 10

    private fun resolveInlineImages(html: String, client: RallyApiClient, gen: Long): String {
        val matcher = RallyHtmlUtils.INLINE_IMG_PATTERN.matcher(html)
        if (!matcher.find()) return html

        matcher.reset()
        val normalizedBase = client.webBaseUrl

        // Phase 1: Collect image matches (capped to prevent thread pool saturation)
        data class ImageMatch(val start: Int, val end: Int, val fullMatch: String,
                              val originalSrc: String, val objectId: String, val fileName: String)
        val matches = mutableListOf<ImageMatch>()
        while (matcher.find() && matches.size < maxInlineImages) {
            matches.add(ImageMatch(
                matcher.start(), matcher.end(), matcher.group(),
                matcher.group(2), matcher.group(3), matcher.group(4)
            ))
        }
        if (matches.isEmpty()) return html

        // Phase 2: Download images in parallel, bailing out early if selection changed
        val futures = matches.map { match ->
            CompletableFuture.supplyAsync({
                if (generation.get() != gen) return@supplyAsync null
                try {
                    val fullUrl = if (match.originalSrc.startsWith("http")) {
                        match.originalSrc
                    } else {
                        "$normalizedBase${match.originalSrc}"
                    }
                    val bytes = client.downloadAttachment(fullUrl)
                    if (generation.get() != gen) return@supplyAsync null
                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    val ext = match.fileName.substringAfterLast('.', "png").lowercase()
                    val contentType = when (ext) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "gif" -> "image/gif"
                        "svg" -> "image/svg+xml"
                        "webp" -> "image/webp"
                        else -> "image/png"
                    }
                    "data:$contentType;base64,$base64"
                } catch (e: Exception) {
                    LOG.warn("Failed to download inline image OID=${match.objectId} (${match.fileName})", e)
                    null
                }
            }, imageExecutor)
        }
        val results = futures.map { it.join() }
        if (generation.get() != gen) return html

        // Phase 3: Replace in reverse order to preserve string indices
        val sb = StringBuilder(html)
        for (i in matches.indices.reversed()) {
            val match = matches[i]
            val dataUri = results[i] ?: continue
            val replacement = match.fullMatch.replace(match.originalSrc, dataUri)
            sb.replace(match.start, match.end, replacement)
        }
        // External-src neutralization happens in wrapHtml — the documented single
        // chokepoint every descriptionPane.text assignment goes through — so a second
        // multi-MB regex pass here would be pure duplicate work.
        return sb.toString()
    }



    // ── Test Case Cell Renderer ─────────────────────────────────

    private class TestCaseCellRenderer : ListCellRenderer<RallyTestCase> {
        private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(3, 6) }
        private val iconLabel = JLabel()
        private val textLabel = JLabel()
        private val methodLabel = JLabel()
        private val verdictLabel = JLabel()
        private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

        init {
            rightPanel.add(verdictLabel)
            rightPanel.add(methodLabel)
            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textLabel, BorderLayout.CENTER)
            panel.add(rightPanel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out RallyTestCase>,
            value: RallyTestCase,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            panel.background = if (isSelected) list.selectionBackground else list.background

            iconLabel.icon = if (value.method == "Automated") AllIcons.Actions.Checked else AllIcons.Actions.Edit

            textLabel.text = "${value.formattedID ?: "?"}: ${value.name ?: "Untitled"}"
            textLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            val method = value.method ?: "Manual"
            methodLabel.text = method
            methodLabel.foreground = if (isSelected) list.selectionForeground else if (method == "Automated") {
                RallyColors.IN_PROGRESS
            } else {
                RallyColors.DEFINED
            }

            val verdict = value.lastVerdict ?: ""
            verdictLabel.isVisible = verdict.isNotBlank()
            verdictLabel.text = verdict
            verdictLabel.foreground = if (isSelected) list.selectionForeground else when (verdict) {
                "Pass" -> RallyColors.PASS
                "Fail" -> RallyColors.FAIL
                else -> JBColor.GRAY
            }

            return panel
        }
    }

    // ── Task Cell Renderer ──────────────────────────────────────

    private class TaskCellRenderer : ListCellRenderer<RallyTaskItem> {
        private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(3, 6) }
        private val iconLabel = JLabel(AllIcons.FileTypes.Any_type)
        private val textLabel = JLabel()
        private val stateLabel = JLabel()
        private val ownerLabel = JLabel()
        private val todoLabel = JLabel()
        private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

        init {
            rightPanel.add(stateLabel)
            rightPanel.add(ownerLabel)
            rightPanel.add(todoLabel)
            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textLabel, BorderLayout.CENTER)
            panel.add(rightPanel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out RallyTaskItem>,
            value: RallyTaskItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            panel.background = if (isSelected) list.selectionBackground else list.background

            textLabel.text = "${value.formattedID ?: "?"}: ${value.name ?: "Untitled"}"
            textLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            val state = value.state ?: ""
            stateLabel.isVisible = state.isNotBlank()
            stateLabel.text = state
            stateLabel.foreground = if (isSelected) list.selectionForeground else when (state) {
                "In-Progress" -> RallyColors.IN_PROGRESS
                "Completed" -> RallyColors.COMPLETED
                "Defined" -> RallyColors.DEFINED
                else -> JBColor.DARK_GRAY
            }

            val ownerName = value.owner?.refObjectName ?: value.owner?.displayName ?: ""
            ownerLabel.isVisible = ownerName.isNotBlank()
            ownerLabel.text = ownerName
            ownerLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY

            val todo = value.toDo
            todoLabel.isVisible = todo != null && todo > 0
            todoLabel.text = if (todo != null && todo > 0) "${todo}h left" else ""
            todoLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY

            return panel
        }
    }

    // ── Test Step Cell Renderer ────────────────────────────────

    private class StepCellRenderer : ListCellRenderer<RallyTestCaseStep> {
        companion object {
            private val htmlTagPattern = Pattern.compile("<[^>]+>")
        }
        private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(4, 6) }
        private val badgeLabel = JLabel().apply {
            font = font.deriveFont(Font.BOLD)
            preferredSize = Dimension(32, preferredSize.height)
        }
        private val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        private val inputLabel = JLabel()
        private val expectedLabel = JLabel().apply {
            font = font.deriveFont(font.size2D - 1f)
        }

        init {
            centerPanel.add(inputLabel)
            centerPanel.add(expectedLabel)
            panel.add(badgeLabel, BorderLayout.WEST)
            panel.add(centerPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out RallyTestCaseStep>,
            value: RallyTestCaseStep,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            panel.background = if (isSelected) list.selectionBackground else list.background

            badgeLabel.text = "#${value.stepIndex ?: (index + 1)}"
            badgeLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            val inputText = stripHtml(value.input ?: "")
            inputLabel.text = inputText.ifBlank { "(no input)" }
            inputLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            val expectedText = stripHtml(value.expectedResult ?: "")
            expectedLabel.isVisible = expectedText.isNotBlank()
            expectedLabel.text = if (expectedText.isNotBlank()) "Expected: $expectedText" else ""
            expectedLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY

            return panel
        }

        private fun stripHtml(text: String): String {
            return htmlTagPattern.matcher(text).replaceAll("").trim()
        }
    }

    // ── Attachment Cell Renderer ─────────────────────────────────

    private class AttachmentCellRenderer : ListCellRenderer<RallyAttachment> {
        private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(3, 6) }
        private val iconLabel = JLabel()
        private val textLabel = JLabel()
        private val sizeLabel = JLabel()
        private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

        init {
            rightPanel.add(sizeLabel)
            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textLabel, BorderLayout.CENTER)
            panel.add(rightPanel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out RallyAttachment>,
            value: RallyAttachment,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            panel.background = if (isSelected) list.selectionBackground else list.background

            val isImage = value.contentType?.startsWith("image/") == true
            iconLabel.icon = if (isImage) AllIcons.FileTypes.Image else AllIcons.FileTypes.Any_type

            textLabel.text = value.name ?: "Unknown"
            textLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            val sizeText = formatFileSize(value.size)
            sizeLabel.isVisible = sizeText.isNotBlank()
            sizeLabel.text = sizeText
            sizeLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY

            return panel
        }
    }
}
