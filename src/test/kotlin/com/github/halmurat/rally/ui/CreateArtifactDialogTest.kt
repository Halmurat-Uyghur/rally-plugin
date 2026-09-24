package com.github.halmurat.rally.ui

import com.github.halmurat.rally.api.RallyIteration
import com.github.halmurat.rally.api.RallyProject
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComboBoxWithWidePopup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.plaf.basic.BasicHTML

/**
 * The Create dialogs list Rally-controlled project and sprint names. Built (never shown) in the
 * platform, their dropdowns must render a name starting with "<html>" as the literal text: as
 * live HTML, Swing would lay out its markup and fetch any <img> it names (see rallyNameCombo).
 */
class CreateArtifactDialogTest : BasePlatformTestCase() {

    private val htmlName = "<html><b>x"
    private val projects = listOf(RallyProject(ref = "p/1", name = htmlName))
    private val sprints = listOf(RallyIteration(ref = "i/1", name = htmlName))

    fun testCreateUserStoryDropdownsRenderHtmlLookingNamesAsText() {
        assertNamesRenderAsText(CreateUserStoryDialog(project, projects, sprints, null, null))
    }

    fun testCreateDefectDropdownsRenderHtmlLookingNamesAsText() {
        assertNamesRenderAsText(CreateDefectDialog(project, projects, sprints, null, null))
    }

    private fun assertNamesRenderAsText(dialog: AbstractCreateArtifactDialog) {
        Disposer.register(testRootDisposable, dialog.disposable)
        val combos = dialog.nameCombosForTest()
        assertEquals(2, combos.size)
        for (combo in combos) {
            val installed = installedRenderer(combo)
            assertTrue("renderer: $installed", installed is RallyNameComboRenderer)
            // Row 0 is "All Projects" / "Unscheduled"; the Rally name is row 1 (the sprint has no dates).
            assertEquals(htmlName, combo.getItemAt(1))
            // Through the combo's own renderer chain, as its popup and display render the row.
            val label = combo.renderer.getListCellRendererComponent(JList(), combo.getItemAt(1), 1, false, false) as JLabel
            assertEquals(htmlName, label.text)
            assertNull("the name must not be rendered as HTML", label.getClientProperty(BasicHTML.propertyKey))
        }
    }

    /** The renderer set on [combo]: IntelliJ's ComboBox wraps it in an AdjustingListCellRenderer. */
    private fun installedRenderer(combo: ComboBox<String>): ListCellRenderer<*> {
        val renderer = combo.renderer
        return if (renderer is ComboBoxWithWidePopup<*>.AdjustingListCellRenderer) renderer.delegate else renderer
    }
}
