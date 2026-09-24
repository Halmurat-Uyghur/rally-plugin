package com.github.halmurat.rally.testutil

import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.EditorKit
import javax.swing.text.View
import javax.swing.text.html.ImageView

/**
 * Headless Swing rendering helpers for the HTML-safety tests: build, lay out and paint
 * components on the EDT, so every load a view makes at creation, layout or paint time
 * (CSS background images load synchronously while painters are built) actually runs.
 */
object SwingRender {

    /** Run [block] on the EDT and return its result, rethrowing what it threw (unwrapped). */
    fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        try {
            SwingUtilities.invokeAndWait { result = runCatching(block) }
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
        return result!!.getOrThrow()
    }

    /** A read-only HTML pane: [kit] when given, otherwise the stock kit Swing picks for text/html. */
    fun htmlPane(kit: EditorKit? = null): JTextPane = onEdt {
        JTextPane().apply {
            if (kit != null) editorKit = kit else contentType = "text/html"
            isEditable = false
        }
    }

    /** Set [html] on [pane], then lay it out and paint it (see [paint]). */
    fun render(pane: JEditorPane, html: String, width: Int = 600, height: Int = 400): BufferedImage = onEdt {
        pane.text = html
        paint(pane, width, height)
    }

    /** Size [c], lay out its whole subtree and paint it into an offscreen image. */
    fun paint(c: JComponent, width: Int = 600, height: Int = 400): BufferedImage = onEdt {
        c.setSize(width, height)
        layOut(c)
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
            val g = image.createGraphics()
            try { c.paint(g) } finally { g.dispose() }
        }
    }

    /**
     * doLayout() the whole subtree. validate() is a no-op on a component without a peer, and a
     * rubber-stamp renderer panel never gets one outside a real list.
     */
    private fun layOut(c: Container) {
        c.doLayout()
        c.components.forEach { if (it is Container) layOut(it) }
    }

    /** Every component below [c] (not including [c]). */
    fun descendants(c: Container): List<Component> =
        c.components.flatMap { child -> listOf(child) + if (child is Container) descendants(child) else emptyList() }

    /** The pane's view tree, depth first. */
    fun views(pane: JEditorPane): List<View> = onEdt {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            out.add(v)
            for (i in 0 until v.viewCount) walk(v.getView(i))
        }
        walk(pane.ui.getRootView(pane))
        out
    }

    /** True once some ImageView in the pane holds a decoded image (loads finish on AWT's fetcher threads). */
    fun awaitLoadedImage(pane: JEditorPane, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val loaded = onEdt {
                views(pane).any { it is ImageView && (it.image?.getWidth(null) ?: -1) > 0 }
            }
            if (loaded || System.currentTimeMillis() > deadline) return loaded
            Thread.sleep(25)
        }
    }

    /** Pixels that differ between two same-sized images. */
    fun diffPixels(a: BufferedImage, b: BufferedImage): Int {
        require(a.width == b.width && a.height == b.height) { "size mismatch" }
        var diff = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) diff++
        return diff
    }

    /** Pixels that differ from the bottom-right (background) pixel, i.e. something was drawn. */
    fun inkPixels(image: BufferedImage): Int {
        val bg = image.getRGB(image.width - 1, image.height - 1)
        var ink = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) != bg) ink++
        return ink
    }
}
