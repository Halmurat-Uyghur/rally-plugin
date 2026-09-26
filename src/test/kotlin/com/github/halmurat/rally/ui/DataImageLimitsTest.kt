package com.github.halmurat.rally.ui

import com.github.halmurat.rally.testutil.AwtDecode
import com.github.halmurat.rally.testutil.FetchRecorder
import com.github.halmurat.rally.testutil.SwingRender
import com.github.halmurat.rally.testutil.SwingRender.onEdt
import com.github.halmurat.rally.testutil.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.Reference
import javax.swing.JEditorPane
import javax.swing.text.html.ImageView

/**
 * A `data:` image is decoded by AWT into a raster sized by the dimensions its header declares
 * (4 bytes a pixel), however little pixel data it carries — so the safe kit decides from the
 * header, before any decode, whether an image may be shown at all. The size it decides by has to
 * be the one AWT's own decoder will allocate, which the "control" asserts check case by case
 * (AwtDecode) and DataImageAwtDifferentialTest checks over generated images.
 */
class DataImageLimitsTest {

    // ── The builder's images are real images (control) ──────────────────────────────────

    @Test
    fun `a small hand-built PNG decodes (control for the builder)`() {
        val pane = render(img(TestImages.png(20, 20, rows = 20)))
        assertTrue("the hand-built PNG should decode", SwingRender.awaitLoadedImage(pane))
    }

    @Test
    fun `a 1x1 GIF decodes (control for the builder)`() {
        val pane = render(img(TestImages.gif(), "image/gif"))
        assertTrue("the GIF should decode", SwingRender.awaitLoadedImage(pane))
    }

    // ── Per-image limits ────────────────────────────────────────────────────────────────

    @Test
    fun `a PNG declaring 12000x12000 is not decoded and costs no heap`() {
        // 4 rows of pixel data in a few hundred characters: decoded, it would pin a ~550 MB raster.
        val bomb = TestImages.png(12_000, 12_000)
        assertTrue("${bomb.size} bytes", bomb.size < 500)
        val before = usedHeapAfterGc()
        val pane = render(img(bomb))
        Thread.sleep(FetchRecorder.QUIET_PERIOD_MS) // AWT decodes on its "Image Fetcher" threads
        SwingRender.paint(pane)
        val view = imageViews(pane).single()
        val url = onEdt { view.imageURL }
        val image = onEdt { view.image }
        val grownMb = (usedHeapAfterGc() - before) / MB // the pane (and any image it holds) is still reachable
        assertNull("the image must get no URL (heap grew by $grownMb MB)", url)
        assertNull("the image must not be loaded", image)
        assertTrue("heap grew by $grownMb MB", grownMb < 100)
        Reference.reachabilityFence(pane)
    }

    @Test
    fun `images over the per-image caps get no URL, those at the caps do`() {
        // Header-only PNGs (no IDAT): the decision is made from the header, and an admitted one
        // fails in AWT's decoder before it allocates anything.
        val sizes = listOf(
            16_384 to 1 to true,
            16_385 to 1 to false,
            1 to 16_385 to false,
            4_096 to 4_096 to true, // 16,777,216 px: exactly the per-image cap
            4_097 to 4_096 to false,
            5_120 to 2_880 to true, // a 5K screenshot
            5_000 to 5_000 to false, // 25,000,000 px: the old cap, which left no room for decode-time transients
        )
        for ((size, admitted) in sizes) {
            val (w, h) = size
            val pane = render(img(TestImages.png(w, h, rows = 0)))
            val url = onEdt { imageViews(pane).single().imageURL }
            assertEquals("${w}x$h admitted", admitted, url != null)
        }
    }

    @Test
    fun `every format AWT decodes is measured by its header, whatever the media type says`() {
        assertEquals(200L, dataImagePixels(TestImages.dataUri(TestImages.png(20, 10, rows = 10))))
        assertEquals(600L, dataImagePixels(TestImages.dataUri(TestImages.jpeg(30, 20), "image/jpeg")))
        assertEquals(1L, dataImagePixels(TestImages.dataUri(TestImages.gif(), "image/gif")))
        assertEquals("a JPEG labeled PNG", 600L, dataImagePixels(TestImages.dataUri(TestImages.jpeg(30, 20), "image/png")))
        assertNull(
            "a JPEG whose frame header declares 20000x20000",
            dataImagePixels(TestImages.dataUri(TestImages.jpeg(8, 8, declaredWidth = 20_000, declaredHeight = 20_000), "image/jpeg"))
        )
    }

    @Test
    fun `a GIF is measured by its logical screen, which AWT allocates`() {
        val pane = render(img(TestImages.gif(screenWidth = 16_000, screenHeight = 16_000), "image/gif"))
        assertNull(onEdt { imageViews(pane).single().imageURL })
    }

    @Test
    fun `a GIF frame larger than its logical screen is charged the screen, which is all AWT allocates`() {
        val gif = TestImages.gif(screenWidth = 2, screenHeight = 3, frameWidth = 40, frameHeight = 30)
        assertEquals("control: AWT sizes it by the logical screen", listOf(2 to 3), AwtDecode.declaredSizes(gif))
        assertEquals(2 to 3, dataImageSize(TestImages.dataUri(gif, "image/gif")))
    }

    @Test
    fun `a GIF with no logical screen is refused - AWT would size it by a frame ImageIO doesn't see`() {
        val gif = TestImages.gifWithSplitFirstFrame(awtWidth = 16_000, awtHeight = 16_000)
        assertEquals("control: AWT allocates the frame its own block walk finds", listOf(16_000 to 16_000), AwtDecode.declaredSizes(gif))
        assertNull(dataImagePixels(TestImages.dataUri(gif, "image/gif")))
    }

    @Test
    fun `a GIF is sized from its header alone - a cut-off extension chain is still admitted at its logical screen`() {
        // ImageIO can't read past a comment chain the stream ends inside; the logical screen is
        // all AWT sizes by, and for a stream that ends before its first frame it allocates nothing.
        val whole = TestImages.gifWithCommentChain(1_000)
        assertEquals("control: the whole GIF is sized by its logical screen", 1 to 1, dataImageSize(TestImages.dataUri(whole, "image/gif")))
        val cutOff = whole.copyOf(whole.size / 2) // ends inside the comment chain, before any frame
        assertEquals("control: AWT fails before allocating anything", emptyList<Pair<Int, Int>>(), AwtDecode.declaredSizes(cutOff))
        assertEquals(1 to 1, dataImageSize(TestImages.dataUri(cutOff, "image/gif")))
    }

    @Test
    fun `a GIF with megabytes of extension blocks before its first frame is sized from its header in bounded time`() {
        // JDK 17's GIFImageReader (IntelliJ 2024.1's runtime) reads every extension block before
        // frame 0 and copies the whole chain again per 255-byte sub-block (JDK-8270915): ~10 s for
        // this one, on the EDT, per image — so ImageIO must not be asked about a GIF at all.
        val uri = TestImages.dataUri(TestImages.gifWithCommentChain(7_500_000), "image/gif") // ~10M chars, half the payload cap
        dataImageSize(TestImages.dataUri(TestImages.gif(), "image/gif")) // warm-up: class loading isn't what is timed
        val start = System.nanoTime()
        val size = dataImageSize(uri)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals("sized by the 1x1 logical screen", 1 to 1, size)
        assertTrue("sizing it took $ms ms on Java ${System.getProperty("java.version")}", ms < 3_000)
    }

    @Test
    fun `formats AWT can't decode get no URL`() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'/>".toByteArray()
        val pane = render(img(svg, "image/svg+xml") + img(TestImages.bmp(), "image/bmp") + img("not an image".toByteArray()))
        assertEquals(listOf(null, null, null), onEdt { imageViews(pane).map { it.imageURL } })
    }

    // ── PNG: measured the way AWT's decoder allocates ───────────────────────────────────

    @Test
    fun `a PNG with a 1x1 IHDR and then a 16000x16000 one is refused`() {
        // ImageIO reads the first IHDR; AWT's decoder keeps the last one before the image data.
        val png = TestImages.pngOf(listOf(TestImages.ihdr(1, 1), TestImages.ihdr(16_000, 16_000), TestImages.idat(1, 1), TestImages.iend()))
        assertEquals("control: AWT allocates the second IHDR", listOf(16_000 to 16_000), AwtDecode.declaredSizes(png))
        assertNull(dataImagePixels(TestImages.dataUri(png)))
        assertNull("the pane's view gets no URL for it", onEdt { imageViews(render(img(png))).single().imageURL })
    }

    @Test
    fun `a PNG with a 16000x16000 IHDR and then a 1x1 one is refused`() {
        val png = TestImages.pngOf(listOf(TestImages.ihdr(16_000, 16_000), TestImages.ihdr(1, 1), TestImages.idat(1, 1), TestImages.iend()))
        assertEquals("control: AWT allocates the second IHDR", listOf(1 to 1), AwtDecode.declaredSizes(png))
        assertNull(dataImagePixels(TestImages.dataUri(png)))
    }

    @Test
    fun `two IHDRs within the caps are refused too - ImageIO's answer isn't the size AWT allocates`() {
        val png = TestImages.pngOf(listOf(TestImages.ihdr(2, 2), TestImages.ihdr(64, 64), TestImages.idat(64, 1), TestImages.iend()))
        assertEquals("control", listOf(64 to 64), AwtDecode.declaredSizes(png))
        assertNull(dataImagePixels(TestImages.dataUri(png)))
        // Even two that agree: one IHDR is the rule, not an ImageIO reader that happens to read the right one.
        val twins = TestImages.pngOf(listOf(TestImages.ihdr(2, 2), TestImages.ihdr(2, 2), TestImages.idat(2, 2), TestImages.iend()))
        assertNull(dataImagePixels(TestImages.dataUri(twins)))
    }

    @Test
    fun `an IHDR after an empty IDAT still sizes AWT's image, so it counts`() {
        // AWT's getData() only stops at an IDAT that has data.
        val png = TestImages.pngOf(listOf(
            TestImages.ihdr(1, 1), TestImages.PngChunk("IDAT", ByteArray(0)), TestImages.ihdr(16_000, 16_000),
            TestImages.idat(1, 1), TestImages.iend(),
        ))
        assertEquals("control", listOf(16_000 to 16_000), AwtDecode.declaredSizes(png))
        assertNull(dataImagePixels(TestImages.dataUri(png)))
    }

    @Test
    fun `without an IDAT every IHDR counts, even past IEND`() {
        // With no image data AWT's decoder handles every chunk to the end before sizing the image.
        val png = TestImages.pngOf(listOf(TestImages.ihdr(1, 1), TestImages.iend(), TestImages.ihdr(16_000, 16_000)))
        assertEquals("control", listOf(16_000 to 16_000), AwtDecode.declaredSizes(png))
        assertNull(dataImagePixels(TestImages.dataUri(png)))
    }

    @Test
    fun `an IHDR after the first IDAT with data changes nothing AWT allocates, so it is ignored`() {
        val png = TestImages.pngOf(listOf(TestImages.ihdr(2, 2), TestImages.idat(2, 2), TestImages.ihdr(16_000, 16_000), TestImages.iend()))
        assertEquals("control: sized by the first IHDR", listOf(2 to 2), AwtDecode.declaredSizes(png))
        assertEquals(4L, dataImagePixels(TestImages.dataUri(png)))
    }

    @Test
    fun `a single IHDR after an ancillary chunk is refused - ImageIO wants IHDR first, though AWT accepts it`() {
        val png = TestImages.pngOf(listOf(TestImages.text(), TestImages.ihdr(3, 2), TestImages.idat(3, 2), TestImages.iend()))
        assertEquals("control: AWT reads it", listOf(3 to 2), AwtDecode.declaredSizes(png))
        assertNull(dataImagePixels(TestImages.dataUri(png)))
    }

    @Test
    fun `an IHDR whose length isn't 13 is refused`() {
        val png = TestImages.pngOf(listOf(TestImages.ihdr(3, 2, dataLength = 12), TestImages.idat(3, 2), TestImages.iend()))
        assertEquals("control: AWT rejects it before sizing anything", emptyList<Pair<Int, Int>>(), AwtDecode.declaredSizes(png))
        assertNull(dataImagePixels(TestImages.dataUri(png)))
    }

    @Test
    fun `the PNG chunk walk answers as AWT's decoder does where ImageIO refuses first`() {
        // The walk is the AWT half of the PNG check; ImageIO refuses both of these on its own.
        val textFirst = TestImages.pngOf(listOf(TestImages.text(), TestImages.ihdr(3, 2), TestImages.idat(3, 2), TestImages.iend()))
        assertEquals(AwtDecode.declaredSizes(textFirst).single(), pngAllocationSize(textFirst.inputStream()))
        val shortIhdr = TestImages.pngOf(listOf(TestImages.ihdr(3, 2, dataLength = 12), TestImages.idat(3, 2), TestImages.iend()))
        assertNull("AWT rejects an IHDR that isn't 13 bytes long", pngAllocationSize(shortIhdr.inputStream()))
    }

    @Test
    fun `a PNG with a chunk that runs past the end of the data, or declares a negative length, is refused`() {
        // AWT's decoder buffers each chunk whole, growing its buffer to the length the chunk
        // declares: this one would cost it ~400 MB, though only 20 KB follow.
        val lying = TestImages.PngChunk("zzZz", ByteArray(20_000), length = 400_000_000)
        val pixels = TestImages.idat(64, 64).data
        val firstHalf = TestImages.PngChunk("IDAT", pixels.copyOf(pixels.size / 2))
        val secondHalf = TestImages.PngChunk("IDAT", pixels.copyOfRange(pixels.size / 2, pixels.size))
        val cases = mapOf(
            "between two IDATs" to listOf(TestImages.ihdr(64, 64), firstHalf, lying, secondHalf, TestImages.iend()),
            "after IEND" to listOf(TestImages.ihdr(64, 64), firstHalf, TestImages.iend(), lying),
            "before the IHDR" to listOf(lying, TestImages.ihdr(64, 64), TestImages.idat(64, 64), TestImages.iend()),
            // Last and declaring -4: skipped as 0 bytes, it would leave only its CRC — a clean end of the stream.
            "a negative length" to listOf(TestImages.ihdr(64, 64), TestImages.idat(64, 64), TestImages.PngChunk("zzZz", ByteArray(0), length = -4)),
        )
        for ((name, chunks) in cases) {
            assertNull(name, dataImagePixels(TestImages.dataUri(TestImages.pngOf(chunks))))
        }
        val honest = listOf(TestImages.ihdr(64, 64), firstHalf, TestImages.PngChunk("zzZz", ByteArray(20_000)), secondHalf, TestImages.iend())
        assertEquals("control: the same image with complete chunks", 4096L, dataImagePixels(TestImages.dataUri(TestImages.pngOf(honest))))
    }

    @Test
    fun `a payload over the length cap is refused without decoding`() {
        // A valid 1x1 PNG followed by ~15 MB of trailing bytes: AWT would show it (after decoding
        // all of it); over the cap, it is refused. Asked of the gate directly: rendering a 20 MB
        // attribute through Swing's parser takes most of a minute.
        val png = TestImages.png(1, 1, rows = 1)
        assertEquals("control: trailing bytes alone don't refuse it", 1L, dataImagePixels(TestImages.dataUri(png + ByteArray(1_000))))
        val huge = TestImages.dataUri(png + ByteArray(15_000_100))
        assertTrue(huge.substringAfter(',').length > 20_000_000)
        assertNull(dataImagePixels(huge))
    }

    // ── Per-description budget ──────────────────────────────────────────────────────────

    @Test
    fun `images past the description's pixel budget get no URL`() {
        // Three 16,000,000 px images: the first two fit the 40,000,000 px budget, the third doesn't.
        val pane = render(List(3) { "<p>" + img(TestImages.png(4_000, 4_000, rows = 0)) + "</p>" }.joinToString(""))
        assertEquals(listOf(true, true, false), onEdt { imageViews(pane).map { it.imageURL != null } })
    }

    @Test
    fun `rebuilding the views does not spend the budget again`() {
        // A theme switch re-creates every view of the open description.
        val pane = render(List(2) { "<p>" + img(TestImages.png(4_000, 4_000, rows = 0)) + "</p>" }.joinToString(""))
        assertEquals(listOf(true, true), onEdt { imageViews(pane).map { it.imageURL != null } })
        onEdt { pane.updateUI() }
        SwingRender.paint(pane)
        assertEquals(listOf(true, true), onEdt { imageViews(pane).map { it.imageURL != null } })
    }

    @Test
    fun `each description gets a fresh budget`() {
        val pane = onEdt { RallyDetailPanel.newDescriptionPane() }
        val html = List(2) { "<p>" + img(TestImages.png(4_000, 4_000, rows = 0)) + "</p>" }.joinToString("")
        repeat(2) {
            onEdt { RallyDetailPanel.setDescriptionHtml(pane, "<html><body>$html</body></html>", THEME) }
            SwingRender.paint(pane)
            assertEquals("render ${it + 1}", listOf(true, true), onEdt { imageViews(pane).map { v -> v.imageURL != null } })
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private fun img(bytes: ByteArray, mediaType: String = "image/png") = """<img alt="pic" src="${TestImages.dataUri(bytes, mediaType)}">"""

    private fun render(body: String): JEditorPane {
        val pane = SwingRender.htmlPane(SafeHtmlEditorKit())
        SwingRender.render(pane, "<p>x</p>$body")
        return pane
    }

    private fun imageViews(pane: JEditorPane): List<ImageView> = SwingRender.views(pane).filterIsInstance<ImageView>()

    private fun usedHeapAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        return rt.totalMemory() - rt.freeMemory()
    }

    private companion object {
        const val MB = 1024L * 1024L
        val THEME = SafeHtmlEditorKitTest.THEME
    }
}
