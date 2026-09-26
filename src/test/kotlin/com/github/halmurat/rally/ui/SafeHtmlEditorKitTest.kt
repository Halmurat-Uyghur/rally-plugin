package com.github.halmurat.rally.ui

import com.github.halmurat.rally.testutil.FetchRecorder
import com.github.halmurat.rally.testutil.SwingRender
import com.github.halmurat.rally.testutil.SwingRender.onEdt
import com.github.halmurat.rally.util.DescriptionHtml
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.awt.Rectangle
import java.util.Base64
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.text.html.ImageView

/**
 * The detail pane's rendering boundary: whatever markup reaches [SafeHtmlEditorKit] — here
 * with NO sanitizer in front of it — must not make Swing contact a host, instantiate
 * components or act on the document, while ordinary descriptions render exactly as the stock
 * kit renders them and the `data:` images resolveInlineImages embeds actually show.
 */
class SafeHtmlEditorKitTest {

    private val recorder = FetchRecorder()

    @After
    fun tearDown() = recorder.close()

    // ── Positive controls: the harness sees the fetches the stock kit makes ─────────────
    // One per fetching ATTACKS case, in StockKitFetchControlTest (below this class).

    @Test
    fun `stock kit fetches nothing for the no-fetch cases either (why they have no positive control)`() {
        val cases = ATTACKS.filter { it.stock == Stock.NOTHING }
        assertEquals(listOf("link stylesheet in body", "frame src"), cases.map { it.name })
        val scoped = cases.map { it to recorder.newScope() }
        for ((attack, scope) in scoped) {
            // The stock kit throws on a stray <frame> (see the frameset test); only requests matter here.
            runCatching { SwingRender.render(SwingRender.htmlPane(), attack.html(scope)) }
        }
        Thread.sleep(FetchRecorder.QUIET_PERIOD_MS)
        onEdt { }
        for ((attack, scope) in scoped) assertEquals(attack.name, emptyList<String>(), recorder.requestsUnder(scope))
    }

    // ── No fetches, no live components ──────────────────────────────────────────────────

    @Test
    fun `safe kit makes no request for any resource-loading markup`() {
        assertNoFetches(ATTACKS) { html ->
            SwingRender.render(SwingRender.htmlPane(SafeHtmlEditorKit()), html)
        }
    }

    @Test
    fun `stock kit instantiates the class an object tag names (control)`() {
        val pane = SwingRender.htmlPane()
        SwingRender.render(pane, OBJECT_TAG)
        assertTrue(onEdt { SwingRender.descendants(pane).any { it is JLabel } })
    }

    @Test
    fun `safe kit creates no component for an object tag`() {
        val pane = SwingRender.htmlPane(SafeHtmlEditorKit())
        SwingRender.render(pane, OBJECT_TAG)
        assertEquals(emptyList<Any>(), onEdt { SwingRender.descendants(pane) })
    }

    @Test
    fun `stock kit turns isindex into a live search field (control)`() {
        val pane = SwingRender.htmlPane()
        SwingRender.render(pane, """<p>x</p><isindex action="${recorder.newScope()}search">""")
        assertTrue(onEdt { SwingRender.descendants(pane).any { it is JTextField } })
    }

    @Test
    fun `safe kit renders isindex as nothing`() {
        val pane = SwingRender.htmlPane(SafeHtmlEditorKit())
        SwingRender.render(pane, """<p>x</p><isindex action="${recorder.newScope()}search">""")
        assertEquals(emptyList<Any>(), onEdt { SwingRender.descendants(pane) })
    }

    @Test
    fun `safe kit never submits a form`() {
        assertFalse(SafeHtmlEditorKit().isAutoFormSubmission)
    }

    @Test
    fun `a frameset without rows or cols does not break rendering`() {
        // The stock factory throws RuntimeException for it — from inside setText, on the EDT.
        val pane = SwingRender.htmlPane(SafeHtmlEditorKit())
        SwingRender.render(pane, "<p>before</p><frameset><frame src='x.html'></frameset><p>after</p>")
        assertTrue(documentText(pane).contains("before"))
    }

    // ── data: images ────────────────────────────────────────────────────────────────────

    @Test
    fun `stock kit cannot show a data image (why the kit carries its own handler)`() {
        val pane = SwingRender.htmlPane()
        SwingRender.render(pane, """<img src="$DATA_PNG">""")
        assertNull(onEdt { imageViews(pane).single().imageURL })
    }

    @Test
    fun `safe kit renders a base64 data image`() {
        val pane = SwingRender.htmlPane(SafeHtmlEditorKit())
        SwingRender.render(pane, """<p>x</p><img src="  ${DATA_PNG.replace("data:image/png;base64", "DATA:image/png;BASE64")}  ">""")
        assertTrue("the data: image should decode", SwingRender.awaitLoadedImage(pane))
    }

    @Test
    fun `only base64 data images get a URL`() {
        val scope = recorder.newScope()
        val refused = listOf(
            "${scope}x.png",
            "rel.png",
            "//127.0.0.1/y.png",
            "data:text/html;base64,PGI+eDwvYj4=",
            "data:image/png,rawbytes",
            "data:image/png;base64",
            "",
        )
        val pane = SwingRender.htmlPane(SafeHtmlEditorKit())
        SwingRender.render(pane, refused.joinToString("") { """<img src="$it">""" } + """<img src="$DATA_PNG">""")
        val urls = onEdt { imageViews(pane).map { it.imageURL } }
        assertEquals(refused.size + 1, urls.size)
        urls.dropLast(1).forEachIndexed { i, url -> assertNull("src '${refused[i]}' must get no URL", url) }
        assertNotNull(urls.last())
        assertEquals("data", urls.last()!!.protocol)
    }

    @Test
    fun `an img alt tooltip shows the alt text literally`() {
        // IntelliJ's tooltip manager asks every hovered component for a tooltip and renders the
        // answer as HTML; for a description image that answer is its Rally-authored alt text.
        val pane = SwingRender.htmlPane(SafeHtmlEditorKit())
        SwingRender.render(pane, """<img alt='<html><b>bold</b> & "q"' src="$DATA_PNG">""")
        val tip = onEdt { imageViews(pane).single().getToolTipText(0f, 0f, Rectangle(0, 0, 10, 10)) }
        assertEquals("<html>&lt;html&gt;&lt;b&gt;bold&lt;/b&gt; &amp; &quot;q&quot;</html>", tip)
    }

    // ── Ordinary descriptions render exactly as before ──────────────────────────────────

    @Test
    fun `benign descriptions render identically to the stock kit`() {
        for (html in BENIGN) {
            val stockPane = SwingRender.htmlPane()
            val safePane = SwingRender.htmlPane(SafeHtmlEditorKit())
            val stock = SwingRender.render(stockPane, html)
            val safe = SwingRender.render(safePane, html)
            assertEquals("document text for $html", documentText(stockPane), documentText(safePane))
            assertTrue("something should be drawn for $html", SwingRender.inkPixels(stock) > 0)
            assertEquals("pixels differing for $html", 0, SwingRender.diffPixels(stock, safe))
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    /**
     * Render every attack (each under its own URL scope) through [render], give AWT's image
     * fetcher threads time to act, repaint, and fail listing every case that made a request.
     */
    private fun assertNoFetches(attacks: List<Attack>, render: (String) -> Unit) {
        val scoped = attacks.map { Triple(it.name, recorder.newScope(), it.html) }
        val errors = mutableListOf<String>()
        for ((name, scope, html) in scoped) {
            try { render(html(scope)) } catch (e: Throwable) { errors.add("$name threw $e") }
        }
        Thread.sleep(FetchRecorder.QUIET_PERIOD_MS)
        onEdt { } // drain repaint requests queued by image callbacks
        val fetched = scoped.mapNotNull { (name, scope, _) ->
            recorder.requestsUnder(scope).takeIf { it.isNotEmpty() }?.let { "$name -> $it" }
        }
        if (errors.isNotEmpty() || fetched.isNotEmpty()) {
            fail((errors + fetched).joinToString("\n", prefix = "Unsafe rendering:\n"))
        }
    }

    private fun documentText(pane: javax.swing.JEditorPane): String = onEdt {
        pane.document.let { it.getText(0, it.length) }
    }

    private fun imageViews(pane: javax.swing.JEditorPane): List<ImageView> =
        SwingRender.views(pane).filterIsInstance<ImageView>()

    companion object {
        val DATA_PNG = "data:image/png;base64," + Base64.getEncoder().encodeToString(FetchRecorder.PNG)

        val THEME = DescriptionHtml.Theme(fontSizePx = 13, foregroundHex = "bbbbbb", linkHex = "589df6", errorHex = "ff5261")

        const val OBJECT_TAG = """<p>x</p><object classid="javax.swing.JLabel"><param name="text" value="injected"></object>"""

        /**
         * Markup Swing's stock kit loads a resource for (or acts on), keyed by a per-case URL scope
         * ending in '/'. Each case says what the stock kit does with it, and every [Stock.FETCHES]
         * case has its positive control in [StockKitFetchControlTest], so a negative result from
         * the safe kit means the kit blocked the fetch, not that the case never fetched anything.
         */
        val ATTACKS: List<Attack> = listOf(
            fetch("img, quoted src") { u -> """<p>x</p><img src="${u}q.png">""" },
            fetch("img, unquoted src") { u -> """<p>x</p><img src=${u}u.png>""" },
            fetch("img, apostrophe in an unquoted alt") { u -> """<img src="${u}a.png" alt=Bob's>""" },
            fetch("img, tag cut off by the next tag") { u -> """<img src="${u}c.png" <p>after</p>""" },
            fetch("img, src glued to the previous attribute") { u -> """<img alt="x"src="${u}g.png">""" },
            fetch("img, unmatched quote in a later attribute") { u -> """<img src="${u}uq.png" title=x"><p class="c">hi</p>""" },
            fetch("img, description ends inside the tag") { u -> """text <img src="${u}end.png"""" },
            fetch("input type=image") { u -> """<input type=image src="${u}i.png">""" },
            fetch("link stylesheet in head") { u -> """<html><head><link rel=stylesheet href="${u}h.css"></head><body><p>x</p></body></html>""" },
            Attack("link stylesheet in body", Stock.NOTHING) { u -> """<p>x</p><link rel="stylesheet" type="text/css" href="${u}b.css">""" },
            fetch("style @import url()") { u -> """<style>@import url(${u}i.css);</style><p>x</p>""" },
            fetch("style @import string") { u -> """<style>@import "${u}s.css";</style><p>x</p>""" },
            fetch("background= on body") { u -> """<html><body background="${u}body.png"><p>x</p></body></html>""" },
            fetch("background= on table") { u -> """<table background="${u}t.png"><tr><td>x</td></tr></table>""" },
            fetch("background= on td") { u -> """<table><tr><td background="${u}td.png">x</td></tr></table>""" },
            fetch("style background-image url()") { u -> """<p style="background-image:url(${u}bi.png)">x</p>""" },
            fetch("style bare background-image") { u -> """<p style="background-image:${u}bare.png">x</p>""" },
            fetch("style background shorthand url()") { u -> """<div style="background:url(${u}sh.png)">x</div>""" },
            fetch("stylesheet rule background-image") { u -> """<style>p{background-image:url(${u}rule.png)}</style><p>x</p>""" },
            fetch("list-style-image url()") { u -> """<ul style="list-style-image:url(${u}l.png)"><li>a</li></ul>""" },
            fetch("bare list-style-image") { u -> """<ul style="list-style-image:${u}lb.png"><li>a</li></ul>""" },
            fetch("base href + relative img") { u -> """<base href="$u"><img src="rel.png">""" },
            Attack("object", Stock.LIVE_COMPONENT) { _ -> OBJECT_TAG },
            // The stock kit throws on it from inside setText instead (see the frameset test).
            Attack("frame src", Stock.NOTHING) { u -> """<frame src="${u}f.html">""" },
            fetch("frameset with a frame") { u -> """<frameset rows="*"><frame src="${u}fs.html"></frameset>""" },
            Attack("isindex action", Stock.LIVE_COMPONENT) { u -> """<p>x</p><isindex action="${u}search">""" },
        )

        private fun fetch(name: String, html: (String) -> String) = Attack(name, Stock.FETCHES, html)

        /**
         * Representative image-free descriptions, each in the detail pane's document shell both
         * as-is and through the full wrap (sanitizer included).
         */
        val BENIGN: List<String> = listOf(
            "<h2>Title</h2><p>Plain <b>bold</b> <i>italic</i> <u>under</u> <a href='https://x.invalid/'>link</a></p>",
            "<ul><li>one</li><li>two <b>bold</b></li></ul><ol><li>first</li><li>second</li></ol>",
            "<table border=1 cellpadding=4 style='border-collapse:collapse'><tr><th>head</th><td>cell</td></tr>" +
                "<tr><td style='border:2px solid #888'>x</td><td>y</td></tr></table>",
            "<div style='margin:10px;padding:6px;border:2px solid #888'>boxed</div><p style='margin-left:30px'>indented</p>" +
                "<ul style='list-style-type:square'><li>square</li></ul><blockquote>quote</blockquote><pre>code  here</pre><hr>",
            "<span class='rally-error'><b>Couldn't load the description.</b></span> <a href='rally:retry'>Retry</a>",
        ).flatMap { body -> listOf(DescriptionHtml.shell(body, THEME), DescriptionHtml.wrap(body, THEME)) }
    }

    /** What Swing's stock kit does with an [Attack]'s markup. */
    enum class Stock {
        /** Loads a URL the markup names, while rendering. */
        FETCHES,

        /** Builds a live component (the object and isindex controls above). */
        LIVE_COMPONENT,

        /** Nothing observable — the stock kit doesn't fetch it either; kept as a regression case. */
        NOTHING,
    }

    /** A piece of Rally-authored markup, built around a URL scope, and what the stock kit does with it. */
    data class Attack(val name: String, val stock: Stock, val html: (String) -> String) {
        override fun toString() = name
    }
}

/**
 * Positive controls for [SafeHtmlEditorKitTest.ATTACKS]: every case marked as fetching really
 * makes the stock HTMLEditorKit request its URL, within the same window
 * ([FetchRecorder.QUIET_PERIOD_MS]) the negative tests wait for a request that must not come.
 */
@RunWith(Parameterized::class)
class StockKitFetchControlTest(private val attack: SafeHtmlEditorKitTest.Attack) {

    private val recorder = FetchRecorder()

    @After
    fun tearDown() = recorder.close()

    @Test
    fun `stock kit fetches it`() {
        val scope = recorder.newScope()
        // Only the request matters. The stock FrameView, for one, throws after it has fetched
        // the frame (the recorder serves it as an image, which gets a non-HTML kit).
        runCatching { SwingRender.render(SwingRender.htmlPane(), attack.html(scope)) }
        assertTrue(
            "the stock kit should fetch for '${attack.name}' within ${FetchRecorder.QUIET_PERIOD_MS} ms",
            recorder.awaitRequestUnder(scope, FetchRecorder.QUIET_PERIOD_MS)
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fetchingCases(): List<SafeHtmlEditorKitTest.Attack> =
            SafeHtmlEditorKitTest.ATTACKS.filter { it.stock == SafeHtmlEditorKitTest.Stock.FETCHES }
    }
}
