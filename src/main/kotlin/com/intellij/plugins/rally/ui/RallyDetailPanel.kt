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
import java.util.regex.Pattern
import javax.swing.*

class RallyDetailPanel(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(RallyDetailPanel::class.java)
        private const val TAB_TEST_CASES = 0
        private const val TAB_TASKS = 1
        private const val TAB_ATTACHMENTS = 2
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

    // Tabbed pane
    private val tabbedPane = JBTabbedPane()

    private var currentArtifactRef: String? = null
    private var currentArtifact: RallyArtifact? = null
    private var currentClient: RallyApiClient? = null

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
        headerPanel.add(stateBadge, BorderLayout.EAST)

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

        // Tabbed pane with 3 tabs
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
                if (e.clickCount == 2) openTestCaseInBrowser()
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

        val state = artifact.scheduleState ?: artifact.state ?: "Unknown"
        stateBadge.text = state
        stateBadge.foreground = stateColor(state)

        // Update description — show text immediately, resolve images in background
        val desc = artifact.description
        if (!desc.isNullOrBlank()) {
            descriptionPane.text = wrapHtml(desc)
            descriptionPane.caretPosition = 0
        } else {
            descriptionPane.text = wrapHtml("<i>No description</i>")
        }

        // Clear all lists and set loading state
        testCaseListModel.clear()
        testCaseSummaryLabel.text = "Loading..."
        taskListModel.clear()
        attachmentListModel.clear()
        updateTabTitles(0, 0, 0)

        // Load test cases, tasks, and attachments in parallel on pooled thread
        ApplicationManager.getApplication().executeOnPooledThread {
            // Resolve inline images in description
            if (!desc.isNullOrBlank()) {
                try {
                    val resolvedDesc = resolveInlineImages(desc, client)
                    if (resolvedDesc != desc) {
                        ApplicationManager.getApplication().invokeLater {
                            if (currentArtifactRef != artifactRef) return@invokeLater
                            descriptionPane.text = wrapHtml(resolvedDesc)
                            descriptionPane.caretPosition = 0
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to resolve inline images for $id", e)
                }
            }

            // Load test cases
            try {
                val testCases = client.queryTestCases(artifactRef)
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    testCaseListModel.clear()
                    testCases.forEach { testCaseListModel.addElement(it) }
                    val automated = testCases.count { it.method == "Automated" }
                    val manual = testCases.size - automated
                    testCaseSummaryLabel.text = "${testCases.size} total ($automated automated, $manual manual)"
                    tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases (${testCases.size})")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load test cases for $id", e)
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    testCaseSummaryLabel.text = "Failed to load test cases"
                }
            }

            // Load tasks
            try {
                val tasks = client.queryTasksForWorkProduct(artifactRef)
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    taskListModel.clear()
                    tasks.forEach { taskListModel.addElement(it) }
                    tabbedPane.setTitleAt(TAB_TASKS, "Tasks (${tasks.size})")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load tasks for $id", e)
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    tabbedPane.setTitleAt(TAB_TASKS, "Tasks (!)")
                }
            }

            // Load attachments
            try {
                val attachments = client.queryAttachments(id)
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    attachmentListModel.clear()
                    attachments.forEach { attachmentListModel.addElement(it) }
                    tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (${attachments.size})")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load attachments for $id", e)
                ApplicationManager.getApplication().invokeLater {
                    if (currentArtifactRef != artifactRef) return@invokeLater
                    tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments (!)")
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
        descriptionPane.text = ""
        testCaseListModel.clear()
        testCaseSummaryLabel.text = ""
        taskListModel.clear()
        attachmentListModel.clear()
        updateTabTitles(0, 0, 0)
    }

    private fun updateTabTitles(testCases: Int, tasks: Int, attachments: Int) {
        tabbedPane.setTitleAt(TAB_TEST_CASES, "Test Cases ($testCases)")
        tabbedPane.setTitleAt(TAB_TASKS, "Tasks ($tasks)")
        tabbedPane.setTitleAt(TAB_ATTACHMENTS, "Attachments ($attachments)")
    }

    // ── Test Case Context Menu ──────────────────────────────────

    private fun showTestCaseContextMenu(e: MouseEvent) {
        val index = testCaseList.locationToIndex(e.point)
        if (index < 0) return
        if (!testCaseList.isSelectedIndex(index)) {
            testCaseList.selectedIndex = index
        }

        val menu = JPopupMenu()
        menu.add(JMenuItem("Mark Automated").apply {
            icon = AllIcons.Actions.Checked
            addActionListener { markSelectedTestCases("Method", "Automated") }
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

    private fun markSelectedTestCases(field: String, value: String) {
        val selected = testCaseList.selectedValuesList
        if (selected.isEmpty()) return
        val client = currentClient ?: return
        val artifactRef = currentArtifactRef

        val fieldLabel = if (field == "Method") "Automated" else "Automatable = Yes"
        val ids = selected.mapNotNull { it.formattedID }.joinToString(", ")
        val confirm = Messages.showYesNoDialog(
            project,
            "Set $fieldLabel on ${selected.size} test case(s)?\n$ids",
            "Rally - Update Test Cases",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        ApplicationManager.getApplication().executeOnPooledThread {
            var success = 0
            var failed = 0
            for (tc in selected) {
                try {
                    val ref = tc.ref ?: continue
                    client.updateTestCaseField(ref, field, value)
                    success++
                } catch (e: Exception) {
                    LOG.error("Failed to update ${tc.formattedID}", e)
                    failed++
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (failed > 0) {
                    Messages.showWarningDialog(
                        project,
                        "Updated: $success, Failed: $failed",
                        "Rally - Test Case Update"
                    )
                }
                // Refresh
                if (currentArtifactRef == artifactRef) {
                    showArtifact(currentArtifact, currentClient)
                }
            }
        }
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
                    Messages.getInformationIcon()
                )
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
                        Messages.getInformationIcon()
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
