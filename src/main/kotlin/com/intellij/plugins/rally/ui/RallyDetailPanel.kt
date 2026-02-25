package com.intellij.plugins.rally.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.plugins.rally.api.*
import com.intellij.plugins.rally.export.RallyExporter
import com.intellij.plugins.rally.settings.RallySettings
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
import java.util.regex.Pattern
import javax.swing.*

class RallyDetailPanel(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(RallyDetailPanel::class.java)
        private const val TAB_TEST_CASES = 0
        private const val TAB_TASKS = 1
        private const val TAB_ATTACHMENTS = 2
        private const val TAB_TEST_STEPS = 3
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

    private var currentArtifactRef: String? = null
    private var currentArtifact: RallyArtifact? = null
    private var currentClient: RallyApiClient? = null

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
        val stepScrollPane = JBScrollPane(stepList)

        // Tabbed pane with 4 tabs
        tabbedPane.addTab("Test Cases", tcPanel)
        tabbedPane.addTab("Tasks", taskScrollPane)
        tabbedPane.addTab("Attachments", attachmentScrollPane)
        tabbedPane.addTab("Test Steps (0)", stepScrollPane)

        // Split: description (40%) / tabbed pane (60%) with thin dark divider
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, descScrollPane, tabbedPane)
        splitPane.resizeWeight = 0.4
        splitPane.border = null
        splitPane.dividerSize = 3
        splitPane.setUI(object : javax.swing.plaf.basic.BasicSplitPaneUI() {
            override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider {
                return object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    override fun paint(g: Graphics) {
                        g.color = JBColor(Color(80, 80, 80), Color(70, 70, 70))
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
        if (artifact == null || client == null) {
            clear()
            return
        }

        val artifactRef = artifact.ref ?: return
        currentArtifactRef = artifactRef
        currentArtifact = artifact
        currentClient = client

        // Update header
        val id = artifact.formattedID ?: "?"
        val name = artifact.name ?: "Untitled"
        headerLabel.text = "$id: $name"
        copyButton.isVisible = true
        browserButton.isVisible = true

        val state = artifact.scheduleState ?: artifact.state ?: "Unknown"
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
            val descFuture = CompletableFuture.supplyAsync {
                // Fetch description on demand if not included in list query
                var resolved = desc
                if (resolved.isNullOrBlank()) {
                    resolved = try { client.fetchDescription(artifactRef) } catch (e: Exception) {
                        LOG.warn("Failed to fetch description for $id", e)
                        null
                    }
                }
                // Resolve inline images
                if (!resolved.isNullOrBlank()) {
                    try { resolveInlineImages(resolved, client) } catch (e: Exception) {
                        LOG.warn("Failed to resolve inline images for $id", e)
                        resolved
                    }
                } else null
            }

            val tcFuture = CompletableFuture.supplyAsync {
                try { client.queryTestCases(artifactRef) } catch (e: Exception) {
                    LOG.warn("Failed to load test cases for $id", e)
                    null
                }
            }

            val taskFuture = CompletableFuture.supplyAsync {
                try { client.queryTasksForWorkProduct(artifactRef) } catch (e: Exception) {
                    LOG.warn("Failed to load tasks for $id", e)
                    null
                }
            }

            val attachFuture = CompletableFuture.supplyAsync {
                try { client.queryAttachments(id) } catch (e: Exception) {
                    LOG.warn("Failed to load attachments for $id", e)
                    null
                }
            }

            // Update UI as each completes
            descFuture.thenAccept { resolvedDesc ->
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    if (!resolvedDesc.isNullOrBlank()) {
                        descriptionPane.text = wrapHtml(resolvedDesc)
                    } else {
                        descriptionPane.text = wrapHtml("<i>No description</i>")
                    }
                    descriptionPane.caretPosition = 0
                }
            }

            tcFuture.thenAccept { testCases ->
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    testCaseListModel.clear()
                    if (testCases != null) {
                        testCases.forEach { testCaseListModel.addElement(it) }
                        val automated = testCases.count { it.method == "Automated" }
                        val manual = testCases.size - automated
                        testCaseSummaryLabel.text = "${testCases.size} total ($automated automated, $manual manual)"
                        tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases (${testCases.size})")
                    } else {
                        testCaseSummaryLabel.text = "Failed to load test cases"
                    }
                }
            }

            taskFuture.thenAccept { tasks ->
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    taskListModel.clear()
                    if (tasks != null) {
                        tasks.forEach { taskListModel.addElement(it) }
                        tabbedPane.setTitleAt(TAB_TASKS, "Tasks (${tasks.size})")
                    } else {
                        tabbedPane.setTitleAt(TAB_TASKS, "Tasks (!)")
                    }
                }
            }

            attachFuture.thenAccept { attachments ->
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    attachmentListModel.clear()
                    if (attachments != null) {
                        attachments.forEach { attachmentListModel.addElement(it) }
                        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (${attachments.size})")
                    } else {
                        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (!)")
                    }
                }
            }
        }
    }

    fun clear() {
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
        updateTabTitles(0, 0, 0)
    }

    private fun updateTabTitles(testCases: Int, tasks: Int, attachments: Int) {
        tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases ($testCases)")
        tabbedPane.setTitleAt(TAB_TASKS, "Tasks ($tasks)")
        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments ($attachments)")
        tabbedPane.setTitleAt(TAB_TEST_STEPS, "Test Steps (0)")
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

        stepListModel.clear()
        tabbedPane.setTitleAt(TAB_TEST_STEPS, "Test Steps (...)")
        tabbedPane.selectedIndex = TAB_TEST_STEPS

        ApplicationManager.getApplication().executeOnPooledThread {
            val steps = try {
                client.queryTestSteps(tcId)
            } catch (ex: Exception) {
                LOG.warn("Failed to load test steps for $tcId", ex)
                null
            }
            ApplicationManager.getApplication().invokeLater {
                if (currentArtifactRef != artifactRef) return@invokeLater
                stepListModel.clear()
                if (steps != null) {
                    steps.forEach { stepListModel.addElement(it) }
                    tabbedPane.setTitleAt(TAB_TEST_STEPS, "Test Steps (${steps.size})")
                } else {
                    tabbedPane.setTitleAt(TAB_TEST_STEPS, "Test Steps (!)")
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
        BrowserUtil.browse("$url/#/detail/testcase/$objectId")
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
        BrowserUtil.browse("$url/#/detail/task/$objectId")
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
        val fileName = selected.name ?: "attachment"

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
        BrowserUtil.browse("$url/#/detail/attachment/$objectId")
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun stateColor(state: String): Color = when (state) {
        "In-Progress" -> JBColor(Color(0, 128, 0), Color(100, 200, 100))
        "Completed" -> JBColor(Color(0, 0, 180), Color(100, 150, 255))
        "Accepted" -> JBColor.GRAY
        "Defined" -> JBColor(Color(200, 120, 0), Color(255, 180, 80))
        else -> JBColor.DARK_GRAY
    }

    private fun wrapHtml(html: String): String {
        return "<html><body style='font-family:sans-serif;font-size:11px;margin:4px;'>$html</body></html>"
    }

    /**
     * Download Rally inline images and replace src URLs with base64 data URIs
     * so JTextPane can display them without authentication.
     */
    private fun resolveInlineImages(html: String, client: RallyApiClient): String {
        // Match src attributes pointing to Rally attachment URLs
        val pattern = Pattern.compile(
            """src="((?:https?://[^/]+)?/slm/attachment/(\d+)/([^"]+))"""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        if (!matcher.find()) return html

        matcher.reset()
        val result = StringBuilder()
        val baseUrl = client.serverUrl.trimEnd('/')
        val normalizedBase = if (!baseUrl.startsWith("http")) "https://$baseUrl" else baseUrl

        while (matcher.find()) {
            val originalSrc = matcher.group(1)
            val objectId = matcher.group(2)
            val fileName = matcher.group(3)

            try {
                // Build full URL if relative
                val fullUrl = if (originalSrc.startsWith("http")) {
                    originalSrc
                } else {
                    "$normalizedBase$originalSrc"
                }

                val bytes = client.downloadAttachment(fullUrl)
                val base64 = Base64.getEncoder().encodeToString(bytes)

                // Guess content type from extension
                val ext = fileName.substringAfterLast('.', "png").lowercase()
                val contentType = when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "svg" -> "image/svg+xml"
                    "webp" -> "image/webp"
                    else -> "image/png"
                }

                val dataUri = "data:$contentType;base64,$base64"
                val replacement = matcher.group().replace(originalSrc, dataUri)
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement))
            } catch (e: Exception) {
                LOG.warn("Failed to download inline image OID=$objectId ($fileName)", e)
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group()))
            }
        }
        matcher.appendTail(result)
        return result.toString()
    }

    private fun formatFileSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return ""
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    // ── Test Case Cell Renderer ─────────────────────────────────

    private class TestCaseCellRenderer : ListCellRenderer<RallyTestCase> {
        override fun getListCellRendererComponent(
            list: JList<out RallyTestCase>,
            value: RallyTestCase,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(8, 0))
            panel.border = JBUI.Borders.empty(3, 6)

            if (isSelected) {
                panel.background = list.selectionBackground
                panel.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                panel.foreground = list.foreground
            }

            // Icon: checkmark for Automated, pencil for Manual
            val icon = if (value.method == "Automated") AllIcons.Actions.Checked else AllIcons.Actions.Edit
            val iconLabel = JLabel(icon)

            // FormattedID + Name
            val id = value.formattedID ?: "?"
            val name = value.name ?: "Untitled"
            val textLabel = JLabel("$id: $name")
            if (isSelected) textLabel.foreground = list.selectionForeground

            // Right side: Method badge + LastVerdict
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            rightPanel.isOpaque = false

            val method = value.method ?: "Manual"
            val methodLabel = JLabel(method)
            methodLabel.foreground = if (method == "Automated") {
                JBColor(Color(0, 128, 0), Color(100, 200, 100))
            } else {
                JBColor(Color(200, 120, 0), Color(255, 180, 80))
            }
            if (isSelected) methodLabel.foreground = list.selectionForeground

            val verdict = value.lastVerdict ?: ""
            if (verdict.isNotBlank()) {
                val verdictLabel = JLabel(verdict)
                verdictLabel.foreground = when (verdict) {
                    "Pass" -> JBColor(Color(0, 128, 0), Color(100, 200, 100))
                    "Fail" -> JBColor(Color(180, 0, 0), Color(255, 100, 100))
                    else -> JBColor.GRAY
                }
                if (isSelected) verdictLabel.foreground = list.selectionForeground
                rightPanel.add(verdictLabel)
            }

            rightPanel.add(methodLabel)

            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textLabel, BorderLayout.CENTER)
            panel.add(rightPanel, BorderLayout.EAST)

            return panel
        }
    }

    // ── Task Cell Renderer ──────────────────────────────────────

    private class TaskCellRenderer : ListCellRenderer<RallyTaskItem> {
        override fun getListCellRendererComponent(
            list: JList<out RallyTaskItem>,
            value: RallyTaskItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(8, 0))
            panel.border = JBUI.Borders.empty(3, 6)

            if (isSelected) {
                panel.background = list.selectionBackground
                panel.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                panel.foreground = list.foreground
            }

            // Icon
            val iconLabel = JLabel(AllIcons.FileTypes.Any_type)

            // FormattedID + Name
            val id = value.formattedID ?: "?"
            val name = value.name ?: "Untitled"
            val textLabel = JLabel("$id: $name")
            if (isSelected) textLabel.foreground = list.selectionForeground

            // Right side: State badge + Owner + ToDo
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            rightPanel.isOpaque = false

            val state = value.state ?: ""
            if (state.isNotBlank()) {
                val stateLabel = JLabel(state)
                stateLabel.foreground = when (state) {
                    "In-Progress" -> JBColor(Color(0, 128, 0), Color(100, 200, 100))
                    "Completed" -> JBColor(Color(0, 0, 180), Color(100, 150, 255))
                    "Defined" -> JBColor(Color(200, 120, 0), Color(255, 180, 80))
                    else -> JBColor.DARK_GRAY
                }
                if (isSelected) stateLabel.foreground = list.selectionForeground
                rightPanel.add(stateLabel)
            }

            val ownerName = value.owner?.refObjectName ?: value.owner?.displayName
            if (!ownerName.isNullOrBlank()) {
                val ownerLabel = JLabel(ownerName)
                ownerLabel.foreground = JBColor.GRAY
                if (isSelected) ownerLabel.foreground = list.selectionForeground
                rightPanel.add(ownerLabel)
            }

            val todo = value.toDo
            if (todo != null && todo > 0) {
                val todoLabel = JLabel("${todo}h left")
                todoLabel.foreground = JBColor.GRAY
                if (isSelected) todoLabel.foreground = list.selectionForeground
                rightPanel.add(todoLabel)
            }

            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textLabel, BorderLayout.CENTER)
            panel.add(rightPanel, BorderLayout.EAST)

            return panel
        }
    }

    // ── Test Step Cell Renderer ────────────────────────────────

    private class StepCellRenderer : ListCellRenderer<RallyTestCaseStep> {
        private val htmlTagPattern = Pattern.compile("<[^>]+>")

        override fun getListCellRendererComponent(
            list: JList<out RallyTestCaseStep>,
            value: RallyTestCaseStep,
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

            // Step number badge
            val stepNum = value.stepIndex ?: (index + 1)
            val badgeLabel = JLabel("#$stepNum")
            badgeLabel.font = badgeLabel.font.deriveFont(Font.BOLD)
            badgeLabel.preferredSize = Dimension(32, badgeLabel.preferredSize.height)
            if (isSelected) badgeLabel.foreground = list.selectionForeground

            // Center: input (primary) + expected result (secondary)
            val centerPanel = JPanel()
            centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
            centerPanel.isOpaque = false

            val inputText = stripHtml(value.input ?: "")
            val inputLabel = JLabel(inputText.ifBlank { "(no input)" })
            if (isSelected) inputLabel.foreground = list.selectionForeground
            centerPanel.add(inputLabel)

            val expectedText = stripHtml(value.expectedResult ?: "")
            if (expectedText.isNotBlank()) {
                val expectedLabel = JLabel("Expected: $expectedText")
                expectedLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY
                expectedLabel.font = expectedLabel.font.deriveFont(expectedLabel.font.size2D - 1f)
                centerPanel.add(expectedLabel)
            }

            panel.add(badgeLabel, BorderLayout.WEST)
            panel.add(centerPanel, BorderLayout.CENTER)

            return panel
        }

        private fun stripHtml(text: String): String {
            return htmlTagPattern.matcher(text).replaceAll("").trim()
        }
    }

    // ── Attachment Cell Renderer ─────────────────────────────────

    private inner class AttachmentCellRenderer : ListCellRenderer<RallyAttachment> {
        override fun getListCellRendererComponent(
            list: JList<out RallyAttachment>,
            value: RallyAttachment,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(8, 0))
            panel.border = JBUI.Borders.empty(3, 6)

            if (isSelected) {
                panel.background = list.selectionBackground
                panel.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                panel.foreground = list.foreground
            }

            // Icon: image icon for image types, generic file icon for others
            val isImage = value.contentType?.startsWith("image/") == true
            val icon = if (isImage) AllIcons.FileTypes.Image else AllIcons.FileTypes.Any_type
            val iconLabel = JLabel(icon)

            // Filename
            val fileName = value.name ?: "Unknown"
            val textLabel = JLabel(fileName)
            if (isSelected) textLabel.foreground = list.selectionForeground

            // Right side: file size
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            rightPanel.isOpaque = false

            val sizeText = formatFileSize(value.size)
            if (sizeText.isNotBlank()) {
                val sizeLabel = JLabel(sizeText)
                sizeLabel.foreground = JBColor.GRAY
                if (isSelected) sizeLabel.foreground = list.selectionForeground
                rightPanel.add(sizeLabel)
            }

            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textLabel, BorderLayout.CENTER)
            panel.add(rightPanel, BorderLayout.EAST)

            return panel
        }
    }
}
