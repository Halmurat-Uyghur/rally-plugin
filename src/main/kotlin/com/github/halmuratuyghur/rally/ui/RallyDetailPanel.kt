package com.github.halmuratuyghur.rally.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.github.halmuratuyghur.rally.api.*
import com.github.halmuratuyghur.rally.export.RallyExporter
import com.github.halmuratuyghur.rally.settings.RallySettings
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

class RallyDetailPanel(private val project: Project) {

    private val imageExecutor: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "rally-image-worker").apply { isDaemon = true }
    }

    companion object {
        private val LOG = Logger.getInstance(RallyDetailPanel::class.java)
        private const val TAB_TEST_CASES = 0
        private const val TAB_TASKS = 1
        private const val TAB_ATTACHMENTS = 2

        // Pre-allocated colors to avoid creating JBColor instances on every cell render
        private val COLOR_IN_PROGRESS = JBColor(Color(0, 128, 0), Color(100, 200, 100))
        private val COLOR_COMPLETED = JBColor(Color(0, 0, 180), Color(100, 150, 255))
        private val COLOR_DEFINED = JBColor(Color(200, 120, 0), Color(255, 180, 80))
        private val COLOR_PASS = JBColor(Color(0, 128, 0), Color(100, 200, 100))
        private val COLOR_FAIL = JBColor(Color(180, 0, 0), Color(255, 100, 100))
        private val COLOR_DIVIDER = JBColor(Color(80, 80, 80), Color(70, 70, 70))
        private val EXTERNAL_SRC_PATTERN = Pattern.compile("""src="https?://[^"]*"""", Pattern.CASE_INSENSITIVE)
        private val INLINE_IMG_PATTERN = Pattern.compile(
            """src="((?:https?://[^/]+)?/slm/attachment/(\d+)/([^"]+))"""",
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
    private val descriptionPane = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        border = JBUI.Borders.empty(4)
    }

    // Test Cases
    private val testCaseListModel = DefaultListModel<RallyTestCase>()
    private val testCaseList = JBList(testCaseListModel)
    private val testCaseSummaryLabel = JBLabel("")

    // Tasks
    private val taskListModel = DefaultListModel<RallyTaskItem>()
    private val taskList = JBList(taskListModel)

    // Attachments
    private val attachmentListModel = DefaultListModel<RallyAttachment>()
    private val attachmentList = JBList(attachmentListModel)

    // Test Steps
    private val stepListModel = DefaultListModel<RallyTestCaseStep>()
    private val stepList = JBList(stepListModel)

    // Tabbed pane
    private val tabbedPane = JBTabbedPane()

    @Volatile private var currentArtifactRef: String? = null
    @Volatile private var currentArtifact: RallyArtifact? = null
    @Volatile private var currentClient: RallyApiClient? = null

    /** Incremented on every showArtifact/clear call; background workers check this to bail out early. */
    private val generation = AtomicLong(0)
    @Volatile private var disposed = false

    fun dispose() {
        disposed = true
        generation.incrementAndGet()
        imageExecutor.shutdownNow()
    }

    // Header action buttons
    private val copyButton = JLabel(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy FormattedID"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        border = JBUI.Borders.empty(0, 4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val id = currentArtifact?.formattedID ?: return
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(java.awt.datatransfer.StringSelection(id), null)
            }
        })
    }

    private val browserButton = JLabel(AllIcons.General.Web).apply {
        toolTipText = "Open in Browser"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        border = JBUI.Borders.empty(0, 4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val artifact = currentArtifact ?: return
                val client = currentClient ?: return
                BrowserUtil.browse(client.buildWebUrl(artifact))
            }
        })
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
        val tcScrollPane = JBScrollPane(testCaseList)
        val tcPanel = JPanel(BorderLayout())
        tcPanel.add(tcScrollPane, BorderLayout.CENTER)
        tcPanel.add(testCaseSummaryLabel, BorderLayout.SOUTH)
        testCaseSummaryLabel.border = JBUI.Borders.empty(2, 4)

        // Tasks tab
        taskList.cellRenderer = TaskCellRenderer()
        taskList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        taskList.emptyText.text = "No tasks"
        val taskScrollPane = JBScrollPane(taskList)

        // Attachments tab
        attachmentList.cellRenderer = AttachmentCellRenderer()
        attachmentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        attachmentList.emptyText.text = "No attachments"
        val attachmentScrollPane = JBScrollPane(attachmentList)

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
                        g.color = COLOR_DIVIDER
                        g.fillRect(0, 0, width, height)
                    }
                }
            }
        })

        component.add(headerPanel, BorderLayout.NORTH)
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

        val artifactRef = artifact.ref ?: return
        val gen = generation.incrementAndGet()
        currentArtifactRef = artifactRef
        currentArtifact = artifact
        currentClient = client

        // Restore standard tabs if previously showing a test case
        if (tabbedPane.tabCount != 3 || (tabbedPane.tabCount > 0 && tabbedPane.getTitleAt(0).startsWith("Test Steps"))) {
            tabbedPane.removeAll()
            tabbedPane.addTab("Test Cases", JBScrollPane(testCaseList))
            tabbedPane.addTab("Tasks", JBScrollPane(taskList))
            tabbedPane.addTab("Attachments", JBScrollPane(attachmentList))
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
            tabbedPane.addTab("Test Steps", JBScrollPane(stepList))
            stepListModel.clear()

            ApplicationManager.getApplication().executeOnPooledThread {
                val descFuture = CompletableFuture.supplyAsync({
                    if (generation.get() != gen) return@supplyAsync null
                    var resolved = desc
                    if (resolved.isNullOrBlank()) {
                        resolved = try { client.fetchDescription(artifactRef) } catch (e: Exception) { null }
                    }
                    if (generation.get() != gen) return@supplyAsync null
                    val resolvedNonNull = resolved
                    if (!resolvedNonNull.isNullOrBlank()) {
                        try { resolveInlineImages(resolvedNonNull, client, gen) } catch (_: Exception) { resolvedNonNull }
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
                        descriptionPane.text = wrapHtml(resolvedDesc ?: "<i>No description</i>")
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
        updateTabTitles(0, 0, 0)

        // Load description, test cases, tasks, and attachments in parallel
        ApplicationManager.getApplication().executeOnPooledThread {
            // Launch all four queries concurrently
            val descFuture = CompletableFuture.supplyAsync({
                if (generation.get() != gen) return@supplyAsync null
                var resolved = desc
                if (resolved.isNullOrBlank()) {
                    resolved = try { client.fetchDescription(artifactRef) } catch (e: Exception) {
                        LOG.warn("Failed to fetch description for $id", e)
                        null
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
                    if (!resolvedDesc.isNullOrBlank()) {
                        descriptionPane.text = wrapHtml(resolvedDesc)
                    } else {
                        descriptionPane.text = wrapHtml("<i>No description</i>")
                    }
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
                    } else {
                        testCaseSummaryLabel.text = "Failed to load test cases"
                        tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases (!)")
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
                    } else {
                        tabbedPane.setTitleAt(TAB_TASKS, "Tasks (!)")
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
                    } else {
                        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (!)")
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
            tabbedPane.addTab("Test Cases", JBScrollPane(testCaseList))
            tabbedPane.addTab("Tasks", JBScrollPane(taskList))
            tabbedPane.addTab("Attachments", JBScrollPane(attachmentList))
        }
        updateTabTitles(0, 0, 0)
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
        val artifactRef = currentArtifactRef
        val requestedTcId = tcId  // capture to detect stale responses

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
            tabbedPane.addTab("Test Steps (...)", JBScrollPane(stepList))
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
                if (disposed || currentArtifactRef != artifactRef) return@invokeLater
                // Also check that the selected test case hasn't changed
                val currentTcId = testCaseList.selectedValue?.formattedID
                if (currentTcId != requestedTcId) return@invokeLater
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
                    } else {
                        tabbedPane.setTitleAt(currentStepsTab, "Test Steps (!)")
                    }
                }
            }
        }
    }

    private fun openTestCaseInBrowser() {
        val selected = testCaseList.selectedValue ?: return
        val client = currentClient ?: return
        val objectId = selected.objectID ?: return
        val baseUrl = client.serverUrl.trimEnd('/')
        val url = if (!baseUrl.startsWith("http")) "https://$baseUrl" else baseUrl
        val projectOid = getParentProjectOid()
        val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
        BrowserUtil.browse("$url/#/${projectSegment}detail/testcase/$objectId")
    }

    // ── Task Context Menu ───────────────────────────────────────

    private fun showTaskContextMenu(e: MouseEvent) {
        val index = taskList.locationToIndex(e.point)
        if (index < 0) return
        if (!taskList.isSelectedIndex(index)) {
            taskList.selectedIndex = index
        }

        val menu = JPopupMenu()
        menu.add(JMenuItem("Open in Browser").apply {
            icon = AllIcons.General.Web
            addActionListener { openTaskInBrowser() }
        })
        menu.show(taskList, e.x, e.y)
    }

    private fun openTaskInBrowser() {
        val selected = taskList.selectedValue ?: return
        val client = currentClient ?: return
        val objectId = selected.objectID ?: return
        val baseUrl = client.serverUrl.trimEnd('/')
        val url = if (!baseUrl.startsWith("http")) "https://$baseUrl" else baseUrl
        val projectOid = getParentProjectOid()
        val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
        BrowserUtil.browse("$url/#/${projectSegment}detail/task/$objectId")
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
        val fileName = (selected.name ?: "attachment").replace(Regex("[/\\\\]"), "_")

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
                val bytes = Base64.getDecoder().decode(base64Content)
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
        val baseUrl = client.serverUrl.trimEnd('/')
        val url = if (!baseUrl.startsWith("http")) "https://$baseUrl" else baseUrl
        val projectOid = getParentProjectOid()
        val projectSegment = if (projectOid != null) "${projectOid}d/" else ""
        BrowserUtil.browse("$url/#/${projectSegment}detail/attachment/$objectId")
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
        "In-Progress" -> COLOR_IN_PROGRESS
        "Completed" -> COLOR_COMPLETED
        "Accepted" -> JBColor.GRAY
        "Defined" -> COLOR_DEFINED
        else -> JBColor.DARK_GRAY
    }

    private fun wrapHtml(html: String): String {
        return "<html><body style='font-family:sans-serif;font-size:11px;margin:4px;'>$html</body></html>"
    }

    /**
     * Download Rally inline images and replace src URLs with base64 data URIs
     * so JTextPane can display them without authentication.
     */
    /** Maximum inline images to download per description (prevents thread pool saturation). */
    private val maxInlineImages = 10

    private fun resolveInlineImages(html: String, client: RallyApiClient, gen: Long): String {
        val matcher = INLINE_IMG_PATTERN.matcher(html)
        if (!matcher.find()) return html

        matcher.reset()
        val baseUrl = client.serverUrl.trimEnd('/')
        val normalizedBase = if (!baseUrl.startsWith("http")) "https://$baseUrl" else baseUrl

        // Phase 1: Collect image matches (capped to prevent thread pool saturation)
        data class ImageMatch(val start: Int, val end: Int, val fullMatch: String,
                              val originalSrc: String, val objectId: String, val fileName: String)
        val matches = mutableListOf<ImageMatch>()
        while (matcher.find() && matches.size < maxInlineImages) {
            matches.add(ImageMatch(
                matcher.start(), matcher.end(), matcher.group(),
                matcher.group(1), matcher.group(2), matcher.group(3)
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
        val resolved = sb.toString()
        // Neutralize any remaining external http(s) src attributes to prevent JTextPane network fetches
        return EXTERNAL_SRC_PATTERN.matcher(resolved).replaceAll("""src="" """)
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
                COLOR_IN_PROGRESS
            } else {
                COLOR_DEFINED
            }

            val verdict = value.lastVerdict ?: ""
            verdictLabel.isVisible = verdict.isNotBlank()
            verdictLabel.text = verdict
            verdictLabel.foreground = if (isSelected) list.selectionForeground else when (verdict) {
                "Pass" -> COLOR_PASS
                "Fail" -> COLOR_FAIL
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
                "In-Progress" -> COLOR_IN_PROGRESS
                "Completed" -> COLOR_COMPLETED
                "Defined" -> COLOR_DEFINED
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
        private val htmlTagPattern = Pattern.compile("<[^>]+>")
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

            badgeLabel.text = "#${index + 1}"
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
