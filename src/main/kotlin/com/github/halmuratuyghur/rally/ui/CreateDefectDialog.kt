package com.github.halmuratuyghur.rally.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.github.halmuratuyghur.rally.api.RallyIteration
import com.github.halmuratuyghur.rally.api.RallyProject
import com.intellij.ui.components.JBLabel
import javax.swing.JPanel

/**
 * Create Defect dialog. Extends the shared form with Severity and Priority rows
 * (inserted between Sprint and Assign-to-me) and a taller preferred size.
 */
class CreateDefectDialog(
    project: Project,
    projects: List<RallyProject>,
    iterations: List<RallyIteration>,
    preselectProjectRef: String?,
    preselectIterationRef: String?
) : AbstractCreateArtifactDialog(
    project, "Create Defect", projects, iterations, preselectProjectRef, preselectIterationRef
) {
    private val severityCombo = ComboBox(arrayOf("", "Crash/Data Loss", "Major Problem", "Minor Problem", "Cosmetic"))
    private val priorityCombo = ComboBox(arrayOf("", "Resolve Immediately", "High Attention", "Normal", "Low"))

    init {
        init()
    }

    override fun extraRows(panel: JPanel, gbc: java.awt.GridBagConstraints, startGridY: Int): Int {
        // Severity
        gbc.gridx = 0; gbc.gridy = startGridY; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel("Severity:"), gbc)
        gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(severityCombo, gbc)

        // Priority
        gbc.gridx = 0; gbc.gridy = startGridY + 1; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel("Priority:"), gbc)
        gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(priorityCombo, gbc)

        return startGridY + 2
    }

    override fun preferredDialogSize(): java.awt.Dimension = java.awt.Dimension(500, 440)

    /** Selected severity, or null when the blank default is chosen. */
    val severity: String? get() = (severityCombo.selectedItem as? String)?.ifBlank { null }

    /** Selected priority, or null when the blank default is chosen. */
    val priority: String? get() = (priorityCombo.selectedItem as? String)?.ifBlank { null }
}
