package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyIteration
import com.intellij.openapi.ui.ComboBox
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

// Renderers of the dropdowns that list Rally-controlled names: the toolbar Project and Sprint
// dropdowns and the Create dialogs' Project and Sprint dropdowns. DefaultListCellRenderer is a
// JLabel: a name starting with "<html>" would become live HTML, laid out by Swing, which also
// fetches any <img> it names — an outbound request from the developer's machine that bypasses
// the API client's host checks. "html.disable" makes the label show the literal text instead;
// it is set in init because JLabel builds its HTML view when text is set. Tooltips are rendered
// as HTML by the IDE, and the dropdown's popup list shows the renderer's tooltip text rather
// than the renderer itself, so "html.disable" doesn't reach them: they carry the name escaped,
// and screen readers get it plain ([describeLiterally]).

/** A dropdown of Rally-controlled names (the Create dialogs' Project and Sprint dropdowns). */
internal fun rallyNameCombo(): ComboBox<String> = ComboBox<String>().apply { renderer = RallyNameComboRenderer() }

/** Renderer of a dropdown whose rows are Rally-controlled names (the toolbar Project dropdown, [rallyNameCombo]). */
internal class RallyNameComboRenderer : DefaultListCellRenderer() {
    init {
        putClientProperty("html.disable", true)
    }

    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        describeLiterally(value?.toString())
        return comp
    }
}

/**
 * Renderer of the toolbar Sprint dropdown: adds each sprint's date range, looked up in
 * [iterations] (the panel's cached list, whose order matches the dropdown's rows).
 */
internal class SprintComboRenderer(private val iterations: () -> List<RallyIteration>) : DefaultListCellRenderer() {
    init {
        putClientProperty("html.disable", true)
    }

    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val name = value?.toString() ?: ""
        // Snapshot the list into a local — a project switch on a background thread could
        // replace it out from under us between the bounds check and the index access.
        val snapshot = iterations()
        // Look up iteration dates from cache (index 0 = "All Sprints", so offset by 1)
        val iter = snapshot.getOrNull(if (index > 0) index - 1 else -1)
        val start = iter?.startDate?.take(10) ?: ""
        val end = iter?.endDate?.take(10) ?: ""
        if (start.isNotBlank() && end.isNotBlank()) {
            text = "$name  ($start → $end)"
            // Also the screen-reader name: "to" reads better than the arrow.
            describeLiterally("$name: $start to $end")
        } else {
            // Every row sets its tooltip and accessible text: the renderer is shared, so a row
            // that skipped this would show those of whichever row was rendered before it.
            describeLiterally(name)
        }
        return comp
    }
}
