package com.github.halmurat.rally.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.github.halmurat.rally.api.RallyArtifact
import com.github.halmurat.rally.api.RallyDefect
import com.github.halmurat.rally.api.RallyType
import com.github.halmurat.rally.api.RallyUserStory
import com.github.halmurat.rally.api.effectiveState
import com.github.halmurat.rally.util.literalHtmlTooltip
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Single-instance (rubber-stamp) renderer for the ticket [JList]. Reuses one panel +
 * shared sub-components across every row so the list's fixedCellHeight measurement and
 * per-row paints don't allocate.
 */
internal class ArtifactCellRenderer : ListCellRenderer<RallyArtifact> {

    private val panel = JPanel(BorderLayout(8, 0)).apply { border = JBUI.Borders.empty(4, 6) }
    private val iconLabel = JLabel()
    private val textLabel = rallyTextLabel()
    private val stateBadge = StatusBadge()
    private val ownerLabel = rallyTextLabel()
    private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }

    init {
        rightPanel.add(ownerLabel)
        rightPanel.add(stateBadge)
        panel.add(iconLabel, BorderLayout.WEST)
        panel.add(textLabel, BorderLayout.CENTER)
        panel.add(rightPanel, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out RallyArtifact>,
        value: RallyArtifact,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        panel.background = if (isSelected) list.selectionBackground else list.background

        iconLabel.icon = when (value.type) {
            RallyType.USER_STORY -> AllIcons.Nodes.PpLib
            RallyType.DEFECT -> AllIcons.General.Error
            RallyType.TEST_CASE -> AllIcons.RunConfigurations.TestState.Run
            else -> AllIcons.FileTypes.Any_type
        }

        val title = "${value.formattedID ?: "?"}: ${value.name ?: "Untitled"}"
        textLabel.text = title

        // Show blocked indicator
        val isBlocked = when (value) {
            is RallyUserStory -> value.blocked == true
            is RallyDefect -> value.blocked == true
            else -> false
        }
        if (isBlocked) {
            textLabel.text = "⛔ ${textLabel.text}"
        }

        textLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

        val state = value.effectiveState
        // The badge keeps its own colors on selected rows: its opaque pastel fill
        // is its local background, so contrast is selection-independent.
        stateBadge.update(state, RallyColors.forState(state))

        ownerLabel.text = value.owner?.displayName ?: value.owner?.refObjectName ?: ""
        ownerLabel.foreground = if (isSelected) list.selectionForeground else JBColor.GRAY

        // Set on every render: the panel is shared by all rows, and the list's accessible row
        // reads the renderer's name.
        panel.describeLiterally(title)

        return panel
    }
}

/**
 * A label for text that is, or can start with, a Rally string (IDs, names, owner and file
 * names). A JLabel whose text starts with `<html>` renders it as live HTML (BasicHTML) — and
 * loads any `<img>` in it, synchronously, on the EDT — so these labels have HTML switched off.
 * Set once at construction: BasicHTML reads the property when the text changes.
 */
internal fun rallyTextLabel(): JLabel = JLabel().apply { putClientProperty("html.disable", true) }

/**
 * Describe this renderer component by [text] (a Rally string, or null for none): as its tooltip,
 * escaped ([literalHtmlTooltip] — the IDE renders tooltips as HTML), and to screen readers as the
 * plain text. The accessible description would otherwise fall back to that escaped-HTML tooltip,
 * and macOS reads the description as the name when there is none, so markup and entities would be
 * read out.
 */
internal fun JComponent.describeLiterally(text: String?) {
    toolTipText = text?.let(::literalHtmlTooltip)
    accessibleContext.accessibleName = text
    accessibleContext.accessibleDescription = text
}
