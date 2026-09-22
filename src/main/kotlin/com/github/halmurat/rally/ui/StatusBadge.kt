package com.github.halmurat.rally.ui

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent

/**
 * A small rounded chip that paints an opaque pastel fill behind a state name.
 *
 * Designed for reuse inside shared cell-renderer instances (project convention:
 * no per-paint allocations), so all visual state flows through [update]. The chip
 * keeps its own colors on selected rows — the opaque pastel fill makes the chip's
 * contrast independent of the selection highlight, unlike the colored-text-on-selection-blue
 * clash the plain labels had.
 */
class StatusBadge : JComponent(), Accessible {
    private var text: String = ""
    private var colors: StateColors = RallyColors.NEUTRAL

    init {
        font = JBUI.Fonts.label(11f)
        isOpaque = false
    }

    /**
     * Setter for text + colors; blank text hides the badge entirely.
     *
     * This intentionally does NOT schedule a repaint/revalidate: it is called once per
     * visible row from inside shared cell renderers (rubber-stamp pattern), where the
     * CellRendererPane stamps the badge immediately and a self-scheduled repaint would
     * just churn the EDT queue. When the badge lives in a real container, the caller is
     * responsible for repaint scheduling — use [refresh] for that.
     *
     * A text change does [invalidate] (a flag flip, nothing queued): the renderer pane's
     * validate() only re-lays out containers already marked invalid, so without it a row
     * would reuse the previous row's chip bounds and clip the new text.
     */
    fun update(text: String?, colors: StateColors) {
        val newText = text ?: ""
        if (newText != this.text) invalidate()
        this.text = newText
        this.colors = colors
        isVisible = this.text.isNotBlank()
        getAccessibleContext().accessibleName = this.text
    }

    /**
     * Schedule a layout + repaint after [update]. Call this only when this badge lives in
     * a real component hierarchy (e.g. the detail-panel header), not when used as a
     * rubber-stamp cell renderer (where the renderer pane paints it directly).
     */
    fun refresh() {
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        if (text.isEmpty()) return Dimension(0, 0)
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(16), fm.height + JBUI.scale(4))
    }

    override fun paintComponent(g: Graphics) {
        if (text.isEmpty()) return
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(8)
        g2.color = colors.background
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        g2.color = colors.foreground
        GraphicsUtil.setupAntialiasing(g2)
        g2.font = font
        val fm = g2.fontMetrics
        g2.drawString(
            text,
            (width - fm.stringWidth(text)) / 2,
            (height - fm.height) / 2 + fm.ascent
        )
    }

    /** The replaced JLabels exposed the state text to screen readers; keep that. */
    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.LABEL
            }
        }
        return accessibleContext
    }

    /** Re-derive the fixed-size label font after a LaF/font-size switch. */
    override fun updateUI() {
        super.updateUI()
        font = JBUI.Fonts.label(11f)
    }
}
