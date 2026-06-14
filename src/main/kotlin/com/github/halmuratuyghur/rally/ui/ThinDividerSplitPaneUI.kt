package com.github.halmuratuyghur.rally.ui

/**
 * Custom SplitPane UI that draws a thin dark line instead of the default thick divider.
 */
internal class ThinDividerSplitPaneUI : javax.swing.plaf.basic.BasicSplitPaneUI() {
    override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider {
        return object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
            override fun paint(g: java.awt.Graphics) {
                g.color = RallyColors.DIVIDER
                g.fillRect(0, 0, width, height)
            }
        }
    }
}
