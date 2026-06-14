package com.github.halmurat.rally.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.github.halmurat.rally.api.RallyApiClient
import com.github.halmurat.rally.api.RallyIteration
import com.github.halmurat.rally.api.RallyProject
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shared base for the Create User Story / Create Defect dialogs.
 *
 * Owns the form fields, combo population + toolbar pre-selection, the attachment-size
 * validation, and the common Name/Project/Sprint/Assign/Attachment/Description layout.
 * Subclasses insert any extra rows (e.g. Severity/Priority for defects) via [extraRows].
 *
 * The resolved values are exposed as read-only properties computed from the combo
 * selection against the [projects]/[iterations] lists passed in at construction — this
 * removes the fragile `cachedIterations[comboIndex - 1]` index arithmetic the caller
 * previously did against the panel's volatile caches.
 */
abstract class AbstractCreateArtifactDialog(
    project: Project,
    title: String,
    private val projects: List<RallyProject>,
    private val iterations: List<RallyIteration>,
    preselectProjectRef: String?,
    preselectIterationRef: String?
) : DialogWrapper(project) {

    private val owningProject: Project = project

    protected val nameField = JBTextField()
    protected val projectCombo = ComboBox<String>()
    protected val iterationCombo = ComboBox<String>()
    protected val assignToMeCheckbox = javax.swing.JCheckBox("Assign to me")
    protected val descriptionArea = JBTextArea(5, 40)
    protected val attachmentPathField = JBTextField()
    private var attachmentFile: File? = null

    init {
        this.title = title
        // Populate project combo from the passed list
        projectCombo.addItem("All Projects")
        projects.forEach { projectCombo.addItem(it.name ?: "Unnamed") }

        // Pre-select current project from toolbar (matched by ref against the list)
        if (preselectProjectRef != null) {
            val idx = projects.indexOfFirst { it.ref == preselectProjectRef }
            if (idx >= 0) projectCombo.selectedIndex = idx + 1  // +1 for "All Projects"
        }

        // Populate iteration combo from the passed list
        iterationCombo.addItem("Unscheduled")
        iterations.forEach { iter ->
            val name = iter.name ?: "Unnamed"
            val start = iter.startDate?.take(10) ?: ""
            val end = iter.endDate?.take(10) ?: ""
            val label = if (start.isNotBlank() && end.isNotBlank()) "$name ($start → $end)" else name
            iterationCombo.addItem(label)
        }

        // Pre-select current iteration from toolbar (matched by ref against the list)
        if (preselectIterationRef != null) {
            val idx = iterations.indexOfFirst { it.ref == preselectIterationRef }
            if (idx >= 0) iterationCombo.selectedIndex = idx + 1  // +1 for "Unscheduled"
        }

        assignToMeCheckbox.isSelected = true
    }

    /**
     * Insert subclass-specific form rows starting at [startGridY]. Default: none.
     * Returns the next available gridy so the base layout can continue below them.
     */
    protected open fun extraRows(panel: JPanel, gbc: java.awt.GridBagConstraints, startGridY: Int): Int = startGridY

    /** Subclasses may override to set a taller dialog. */
    protected open fun preferredDialogSize(): java.awt.Dimension = java.awt.Dimension(500, 380)

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

        // Subclass rows (e.g. Severity/Priority) inserted between Sprint and Assign-to-me
        val nextY = extraRows(formPanel, gbc, 3)

        // Assign to me
        gbc.gridx = 1; gbc.gridy = nextY; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
        formPanel.add(assignToMeCheckbox, gbc)

        // Attachment
        gbc.gridx = 0; gbc.gridy = nextY + 1; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
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
            val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, owningProject, null)
            if (chosen != null) {
                attachmentFile = File(chosen.path)
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

        panel.preferredSize = preferredDialogSize()
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isNullOrBlank()) {
            return ValidationInfo("Name is required", nameField)
        }
        // Block OK on oversized attachments HERE, before anything is created:
        // the upload only runs after the artifact create succeeds, so a
        // client-side size failure at that point leaves a created-but-
        // unattached artifact behind (and a retry duplicates it).
        val attachLen = attachmentFile?.length() ?: 0L
        if (attachLen > RallyApiClient.MAX_UPLOAD_BYTES) {
            val oneMb = 1024L * 1024
            return ValidationInfo(
                // Round up so a just-over-limit file doesn't display as "50 MB exceeds 50 MB".
                "Attachment is ${(attachLen + oneMb - 1) / oneMb} MB — Rally's upload limit is " +
                    "${RallyApiClient.MAX_UPLOAD_BYTES / oneMb} MB",
                attachmentPathField
            )
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    // ── Resolved values (read after OK) ──────────────────────────

    /** Trimmed artifact name from the Name field. */
    val artifactName: String get() = nameField.text.trim()

    /**
     * Selected project ref, or null for "All Projects" (index 0). Computed against the
     * passed [projects] list so the caller no longer indexes the panel's volatile cache.
     */
    val selectedProjectRef: String?
        get() {
            val idx = projectCombo.selectedIndex
            return if (idx > 0 && idx - 1 < projects.size) projects[idx - 1].ref else null
        }

    /** Selected iteration ref, or null for "Unscheduled" (index 0). */
    val selectedIterationRef: String?
        get() {
            val idx = iterationCombo.selectedIndex
            return if (idx > 0 && idx - 1 < iterations.size) iterations[idx - 1].ref else null
        }

    /** Trimmed description, or null when blank. */
    val descriptionText: String? get() = descriptionArea.text.trim().ifBlank { null }

    val assignToMe: Boolean get() = assignToMeCheckbox.isSelected

    val attachment: File? get() = attachmentFile
}
