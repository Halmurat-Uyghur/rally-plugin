package com.github.halmurat.rally.ui

import com.intellij.icons.AllIcons
import com.github.halmurat.rally.api.RallyAttachment
import com.github.halmurat.rally.api.RallyTaskItem
import com.github.halmurat.rally.api.RallyTestCase
import com.github.halmurat.rally.api.RallyTestCaseStep
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.regex.Pattern
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

// ── Detail-panel cell renderers ─────────────────────────────────────
//
// Extracted from RallyDetailPanel (MED-5) so the panel reads as wiring/logic
// while the per-row painting lives here. These are reusable rubber-stamp
// renderers (project convention: one shared instance, no per-paint allocation)
// and reference only package-level/static state — RallyColors, StatusBadge,
// rallyTextLabel (labels showing Rally strings never render them as HTML), the
// model types, and RallyDetailPanel.formatFileSize. None of them touch
// RallyDetailPanel instance state, so they are plain top-level classes.

// ── Test Case Cell Renderer ─────────────────────────────────

class TestCaseCellRenderer : ListCellRenderer<RallyTestCase> {
    private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(3, 6) }
    private val iconLabel = JLabel()
    private val textLabel = rallyTextLabel()
    private val methodBadge = StatusBadge()
    private val verdictBadge = StatusBadge()
    private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

    init {
        rightPanel.add(verdictBadge)
        rightPanel.add(methodBadge)
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
        methodBadge.update(method, RallyColors.forMethod(method))

        // update() hides the badge for blank verdicts; non-Pass/Fail verdicts
        // (e.g. Blocked) get the neutral chip, matching the old gray fallback.
        verdictBadge.update(value.lastVerdict, RallyColors.forState(value.lastVerdict))

        return panel
    }
}

// ── Task Cell Renderer ──────────────────────────────────────

class TaskCellRenderer : ListCellRenderer<RallyTaskItem> {
    private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(3, 6) }
    private val iconLabel = JLabel(AllIcons.FileTypes.Any_type)
    private val textLabel = rallyTextLabel()
    private val stateBadge = StatusBadge()
    private val ownerLabel = rallyTextLabel()
    private val todoLabel = JLabel()
    private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

    init {
        rightPanel.add(stateBadge)
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

        // update() hides the badge when state is blank (same as the old isVisible).
        stateBadge.update(value.state, RallyColors.forState(value.state))

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

class StepCellRenderer : ListCellRenderer<RallyTestCaseStep> {
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
    // stripHtml below means this text can't start with <html> today; the labels are pinned to
    // plain text anyway, so a change to that stripping can't turn a step into live HTML.
    private val inputLabel = rallyTextLabel()
    private val expectedLabel = rallyTextLabel().apply {
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

class AttachmentCellRenderer : ListCellRenderer<RallyAttachment> {
    private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(3, 6) }
    private val iconLabel = JLabel()
    private val textLabel = rallyTextLabel()
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

        val sizeText = RallyDetailPanel.formatFileSize(value.size)
        sizeLabel.isVisible = sizeText.isNotBlank()
        sizeLabel.text = sizeText
        sizeLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY

        return panel
    }
}
