package com.github.halmurat.rally.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.github.halmurat.rally.api.*
import com.github.halmurat.rally.export.RallyExporter
import com.github.halmurat.rally.settings.RallySettings
import com.github.halmurat.rally.util.RallyFileUtils
import com.github.halmurat.rally.util.RallyHtmlUtils
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
        // Uses a `class` (not an inline `style='color:...'`) because wrapHtml runs the
        // description through RallyHtmlUtils.stripInlineColors, which would otherwise strip
        // the red. The `.rally-error` rule is defined in wrapHtml's <style> block, so the
        // error keeps its emphasis while Rally-authored colors are normalized away.
        private const val AUTH_ERROR_HTML =
            "<span class='rally-error'><b>Authentication failed.</b> " +
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
    private val stateBadge = StatusBadge()
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
        // shutdown(), NOT shutdownNow(): shutdownNow() drains queued-but-unstarted
        // image tasks, so their CompletableFutures never complete and the
        // resolveInlineImages join() would park a shared app-pool thread forever.
        // With shutdown(), queued tasks run as instant generation-check no-ops
        // (generation was just bumped above) and every future completes.
        imageExecutor.shutdown()
    }

    // Header action buttons
    private val copyButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy FormattedID"
        isVisible = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        // Zero the LaF's default button margin (~14px horizontally on some themes) so the
        // copy/browser icons sit snugly together; keep a hair of padding via the border.
        margin = JBUI.emptyInsets()
        border = JBUI.Borders.empty(0, 2)
        addActionListener {
            val id = currentArtifact?.formattedID ?: return@addActionListener
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(id), null)
            showCopiedBalloon("Copied $id", this)
        }
    }

    private val browserButton = JButton(AllIcons.General.Web).apply {
        toolTipText = "Open in Browser"
        isVisible = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        margin = JBUI.emptyInsets()
        border = JBUI.Borders.empty(0, 2)
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
        // Copy + browser icons grouped with no gap so they read as one cluster; a wider
        // gap then separates that cluster from the state badge.
        val actionButtonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        actionButtonsPanel.isOpaque = false
        actionButtonsPanel.add(copyButton)
        actionButtonsPanel.add(browserButton)
        val headerRightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        headerRightPanel.isOpaque = false
        headerRightPanel.add(actionButtonsPanel)
        headerRightPanel.add(stateBadge)
        headerPanel.add(headerRightPanel, BorderLayout.EAST)

        // Description
        val descScrollPane = JBScrollPane(descriptionPane)
        descScrollPane.border = BorderFactory.createTitledBorder("Description")

        // Pin each list's row height from a single prototype render (LOW-64/LOW-65),
        // mirroring the main artifact list. Without fixedCellHeight, BasicListUI
        // re-runs the renderer + getPreferredSize() for every element on every model
        // event to compute uniform row heights. Each prototype exercises a populated
        // StatusBadge (state/verdict/method) where the renderer paints one, so the
        // pinned height accounts for the chip and never clips it.

        // Test Cases tab
        testCaseList.cellRenderer = TestCaseCellRenderer()
        testCaseList.fixedCellHeight = pinCellHeight(
            testCaseList,
            RallyTestCase(formattedID = "TC0000", name = "Prototype", method = "Automated", lastVerdict = "Pass")
        )
        testCaseList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        testCaseList.emptyText.text = "No test cases"
        // tcPanel (list + summary footer) is built once as a field; see its declaration.

        // Tasks tab
        taskList.cellRenderer = TaskCellRenderer()
        taskList.fixedCellHeight = pinCellHeight(
            taskList,
            RallyTaskItem(formattedID = "TA0000", name = "Prototype", state = "In-Progress",
                owner = RallyUser(displayName = "Prototype Owner"), toDo = 1.0)
        )
        taskList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        taskList.emptyText.text = "No tasks"

        // Attachments tab
        attachmentList.cellRenderer = AttachmentCellRenderer()
        attachmentList.fixedCellHeight = pinCellHeight(
            attachmentList,
            RallyAttachment(name = "prototype.png", contentType = "image/png", size = 1024)
        )
        attachmentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        attachmentList.emptyText.text = "No attachments"

        // Test Steps tab
        stepList.cellRenderer = StepCellRenderer()
        stepList.fixedCellHeight = pinCellHeight(
            stepList,
            RallyTestCaseStep(stepIndex = 1, input = "Prototype input", expectedResult = "Prototype result")
        )
        stepList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        stepList.emptyText.text = "Select a test case to view steps"

        // Tabbed pane with 3 standard tabs (Test Steps shown only when a test case is selected)
        tabbedPane.addTab("Test Cases", tcPanel)
        tabbedPane.addTab("Tasks", taskScrollPane)
        tabbedPane.addTab("Attachments", attachmentScrollPane)

        // Split: description (top, 40%) / tabbed pane (bottom). OnePixelSplitter renders a
        // thin divider but exposes a wide invisible drag zone, so the boundary is easy to
        // grab — a raw 3px JSplitPane divider tucked under the description's titled border
        // was technically draggable but practically impossible to hit.
        val splitPane = OnePixelSplitter(true, 0.4f)
        splitPane.firstComponent = descScrollPane
        splitPane.secondComponent = tabbedPane

        val headerWrapper = JPanel(BorderLayout())
        headerWrapper.add(headerPanel, BorderLayout.NORTH)
        headerWrapper.add(metadataLabel, BorderLayout.SOUTH)

        component.add(headerWrapper, BorderLayout.NORTH)
        component.add(splitPane, BorderLayout.CENTER)
    }

    /**
     * Measure one prototype row's preferred height for [list]'s already-assigned renderer,
     * for use as [JBList.fixedCellHeight] (LOW-64/LOW-65). The prototype must populate every
     * field the renderer lays out (including the StatusBadge chip) so the pinned height never
     * clips. Mirrors the main artifact list's prototype-measurement pattern.
     */
    private fun <T> pinCellHeight(list: JBList<T>, prototype: T): Int =
        list.cellRenderer
            .getListCellRendererComponent(list, prototype, 0, false, false)
            .preferredSize.height

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

    /**
     * Cheap, synchronous part of showing an artifact (M2): header, badge, metadata,
     * tab reset, and the already-loaded-description fast path. Bumps the generation so
     * in-flight loads for the previous selection cancel. Network-backed loads are NOT
     * started — call [loadDetails] for that; the tool window debounces it behind a
     * short Timer so arrow-key scrolling costs zero API calls for skipped rows.
     */
    fun showArtifactHeader(artifact: RallyArtifact?, client: RallyApiClient?) {
        if (disposed) return
        if (artifact == null || client == null) {
            clear()
            return
        }
        // Bail if the caller passed a client whose thread pool has already been
        // shut down by a settings change or parent disposal — loadDetails() would
        // otherwise submit tasks to a dead executor.
        if (!client.isAlive) {
            LOG.warn("showArtifactHeader called with a disposed client; skipping")
            clear()
            return
        }

        val artifactRef = artifact.ref ?: return
        generation.incrementAndGet()
        currentArtifactRef = artifactRef
        currentArtifact = artifact
        currentClient = client

        // Update header
        val id = artifact.formattedID ?: "?"
        val name = artifact.name ?: "Untitled"
        headerLabel.text = "$id: $name"
        copyButton.isVisible = true
        browserButton.isVisible = true

        // effectiveState (api package) is the single source of truth: TestCase → lastVerdict
        // (fallback "No Verdict"), everything else → ScheduleState ?: State ?: "Unknown".
        val state = artifact.effectiveState
        stateBadge.update(state, RallyColors.forState(state))
        // Header badge lives in a real container, so update() (a pure setter) must be
        // followed by refresh() to schedule the layout + repaint.
        stateBadge.refresh()

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
            // For test cases: description + test steps only
            tabbedPane.removeAll()
            tabbedPane.addTab("Test Steps (...)", stepScrollPane)
            stepListModel.clear()
            return
        }

        // Restore standard tabs if previously showing a test case
        if (tabbedPane.tabCount != 3 || (tabbedPane.tabCount > 0 && tabbedPane.getTitleAt(0).startsWith("Test Steps"))) {
            tabbedPane.removeAll()
            tabbedPane.addTab("Test Cases", tcPanel)
            tabbedPane.addTab("Tasks", taskScrollPane)
            tabbedPane.addTab("Attachments", attachmentScrollPane)
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
    }

    /**
     * Start the network-backed detail loads (test cases / tasks / attachments /
     * description) for the artifact last passed to [showArtifactHeader]. Debounced
     * by the tool window's selection Timer (M2); also called synchronously via
     * [showArtifact] by the state-change/edit paths.
     */
    fun loadDetails() {
        if (disposed) return
        val artifact = currentArtifact ?: return
        val client = currentClient ?: return
        val artifactRef = currentArtifactRef ?: return
        if (!client.isAlive) return
        val gen = generation.get()
        val id = artifact.formattedID ?: "?"
        val desc = artifact.description

        if (artifact is RallyTestCase) {
            ApplicationManager.getApplication().executeOnPooledThread {
                // Submit the steps query to apiExecutor FIRST so it runs in parallel
                // with the description work below.
                val stepsFuture = CompletableFuture.supplyAsync({
                    if (generation.get() != gen) return@supplyAsync null
                    try { client.queryTestSteps(id) } catch (e: Exception) {
                        LOG.warn("Failed to load test steps for $id", e)
                        null
                    }
                }, client.apiExecutor)

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

                loadAndRenderDescription(desc, id, artifactRef, client, gen)
            }
            return
        }

        // Load description, test cases, tasks, and attachments in parallel
        ApplicationManager.getApplication().executeOnPooledThread {
            // Launch tc/task/attach queries concurrently via apiExecutor.
            // Description loads via loadAndRenderDescription (see its KDoc for threading rationale).
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

            loadAndRenderDescription(desc, id, artifactRef, client, gen)
        }
    }

    fun showArtifact(artifact: RallyArtifact?, client: RallyApiClient?) {
        showArtifactHeader(artifact, client)
        loadDetails()
    }

    /** True when [artifact] is the exact object the panel is already showing (identity, not ref). */
    fun isShowing(artifact: RallyArtifact): Boolean = artifact === currentArtifact

    /**
     * Fetch (if needed), image-resolve, wrap, and render an artifact description.
     * Runs on the CALLER's pooled thread (effectively unbounded pool) rather than
     * client.apiExecutor: fetchDescription + resolveInlineImages block on image
     * downloads, and parking one of apiExecutor's 4 shared workers on that join
     * starved fresh loads during rapid ticket switching. wrapHtml also runs here
     * so its full-string scans stay off the EDT.
     */
    private fun loadAndRenderDescription(desc: String?, id: String, artifactRef: String,
                                         client: RallyApiClient, gen: Long) {
        try {
            val resolvedDesc: String? = run {
                if (generation.get() != gen) return@run null
                var resolved = desc
                if (resolved.isNullOrBlank()) {
                    try {
                        resolved = client.fetchDescription(artifactRef)
                    } catch (e: RallyAuthenticationException) {
                        LOG.warn("Auth failure fetching description for $id", e)
                        return@run DESC_AUTH_FAILED
                    } catch (e: Exception) {
                        LOG.warn("Failed to fetch description for $id", e)
                    }
                }
                if (generation.get() != gen) return@run null
                val resolvedNonNull = resolved
                if (!resolvedNonNull.isNullOrBlank()) {
                    try { resolveInlineImages(resolvedNonNull, client, gen) } catch (e: Exception) {
                        LOG.warn("Failed to resolve inline images for $id", e)
                        resolvedNonNull
                    }
                } else null
            }
            if (generation.get() == gen && !disposed) {
                val text = when {
                    resolvedDesc == DESC_AUTH_FAILED -> AUTH_ERROR_HTML
                    !resolvedDesc.isNullOrBlank() -> resolvedDesc
                    else -> "<i>No description</i>"
                }
                val wrapped = wrapHtml(text)
                ApplicationManager.getApplication().invokeLater {
                    if (generation.get() != gen || disposed) return@invokeLater
                    descriptionPane.text = wrapped
                    descriptionPane.caretPosition = 0
                }
            }
        } catch (t: Exception) {
            LOG.warn("Detail panel description update failed", t)
        }
    }

    fun clear() {
        if (disposed) return
        generation.incrementAndGet()
        currentArtifactRef = null
        currentArtifact = null
        currentClient = null
        headerLabel.text = "Select a ticket to view details"
        // Header badge lives in a real container: update() is a pure setter, so refresh()
        // schedules the layout + repaint that hides the now-blank chip.
        stateBadge.update(null, RallyColors.NEUTRAL)
        stateBadge.refresh()
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

    /** Brief, self-fading "Copied …" balloon anchored above [anchor] — visible clipboard feedback. */
    private fun showCopiedBalloon(message: String, anchor: JComponent) {
        if (disposed) return
        // createHtmlTextBalloonBuilder treats its argument as HTML; FormattedIDs are safe
        // but escape defensively so an unexpected value can never inject markup.
        val safe = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(message)
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(safe, MessageType.INFO, null)
            .setFadeoutTime(1500)
            .createBalloon()
        balloon.show(RelativePoint.getCenterOf(anchor), Balloon.Position.above)
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
            var success = 0
            RallyExporter(client).use { exporter ->
                for (tc in selected) {
                    val tcId = tc.formattedID ?: continue
                    try {
                        if (json) exporter.exportTestCaseJson(tcId, outputDir)
                        if (markdown) exporter.exportTestCaseMarkdown(tcId, outputDir)
                        success++
                    } catch (e: Exception) {
                        LOG.warn("Failed to export $tcId", e)
                    }
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
        if (selected.objectID == null && selected.content?.ref == null) {
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
                // Raw-bytes endpoint first, base64 Content ref fallback (M4) —
                // see RallyApiClient.downloadAttachmentBytes.
                val bytes = client.downloadAttachmentBytes(selected)
                    ?: throw RallyApiException("No content available for this attachment")
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
                LOG.warn("Failed to download attachment ${selected.name}", e)
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
        // Strip Rally's baked-in inline colors so the theme colors below win. Rally
        // descriptions carry colors authored for its light web UI; on a dark IDE theme
        // those render as white blocks and invisible dark-on-dark text (see
        // RallyHtmlUtils.stripInlineColors). Done before the src neutralizer — they
        // target disjoint attributes, so order is irrelevant.
        val decolored = RallyHtmlUtils.stripInlineColors(safe)
        // Neutralize external http(s):// (and protocol-relative) image src attributes so
        // JTextPane never makes an off-host network fetch when rendering a Rally-authored
        // description (tracking pixels / SSRF-style leaks). This is the single chokepoint:
        // every non-empty descriptionPane.text assignment goes through wrapHtml, including the early
        // "description already loaded" path and descriptions with only external images
        // (which skip resolveInlineImages' own pass). data:…;base64 URIs produced by
        // resolveInlineImages are left intact — the pattern only matches http(s):// or //.
        val neutralized = EXTERNAL_SRC_PATTERN.matcher(decolored).replaceAll("src=\"\"")
        // Pin text + link colors to the current IDE theme. With inline colors stripped
        // above, the body color cascades to every span, so the description renders in one
        // consistent, readable color on the pane's theme background (and adapts to a Light
        // theme too). Swing anchors keep their own color and ignore the body color, so they
        // get their own rule. ColorUtil.toHex returns 6 hex digits with no leading '#'.
        // These UIManager-backed reads are cheap and thread-safe; wrapHtml runs on a pooled
        // thread for freshly fetched descriptions and on the EDT for the already-loaded fast
        // path (showArtifact) — both are fine. An open description won't recolor live on a
        // theme switch; reselecting the ticket re-renders it.
        val fg = ColorUtil.toHex(UIUtil.getLabelForeground())
        val link = ColorUtil.toHex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        // The error red must be theme-derived too — a hardcoded #c00 is dim on dark
        // themes, the same low-contrast problem this whole strip exists to fix.
        val err = ColorUtil.toHex(JBColor.namedColor("Label.errorForeground", JBColor.RED))
        // The theme-variable body color lives in the per-document inline <body style>, so it
        // is discarded with the document on the next setText and can never accumulate stale
        // entries in the shared HTMLEditorKit stylesheet across ticket selections / theme
        // switches. The static <style> selectors (link, error class) can't be expressed
        // inline; they're idempotent, so repeated identical inserts are harmless.
        return "<html><head><style>" +
            "a{color:#$link;}" +
            ".rally-error{color:#$err;}" +
            "</style></head>" +
            "<body style='font-family:sans-serif;font-size:${fontSize}px;margin:4px;color:#$fg;'>" +
            "$neutralized</body></html>"
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
                    // inlineImageUrl percent-encodes URI-illegal filenames (spaces,
                    // quotes) that the regex now matches; raw they'd throw inside
                    // the download layer's URI parse and render broken.
                    val base = if (match.originalSrc.startsWith("http")) {
                        match.originalSrc.substringBefore("/slm/attachment/")
                    } else {
                        normalizedBase
                    }
                    val fullUrl = RallyHtmlUtils.inlineImageUrl(base, match.objectId, match.fileName)
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
        // chokepoint every non-empty descriptionPane.text assignment goes through — so a second
        // multi-MB regex pass here would be pure duplicate work.
        return sb.toString()
    }
}
