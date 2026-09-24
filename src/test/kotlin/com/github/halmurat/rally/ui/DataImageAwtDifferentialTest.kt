package com.github.halmurat.rally.ui

import com.github.halmurat.rally.testutil.AwtDecode
import com.github.halmurat.rally.testutil.TestImages
import com.github.halmurat.rally.testutil.TestImages.PngChunk
import com.github.halmurat.rally.testutil.TestImages.SofPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The data-image limits checked against the decoders that actually allocate: AWT's own
 * (sun.awt.image), which ImageView loads every description image through. The safe kit measures
 * an image from its header before anything is decoded; whenever it admits one, the size AWT's
 * decoder then passes to setDimensions (what the image's raster is allocated from) must be that
 * size exactly — never larger, or the pixel budget is spent on a number AWT doesn't honour.
 *
 * The images are small (at most 64 px a side) and AWT's answer is read by a consumer that keeps
 * no pixels, so a sample costs a few milliseconds whatever it declares.
 */
class DataImageAwtDifferentialTest {

    @Test
    fun `for random PNG chunk layouts, an admitted size is the size AWT allocates`() {
        val random = Random(SEED)
        val walkMismatches = mutableListOf<String>()
        var walkAnswered = 0
        val outcomes = List(PNG_SAMPLES) { i ->
            val chunks = randomPngChunks(random)
            val png = TestImages.pngOf(chunks)
            val label = "PNG sample $i (seed $SEED): $chunks"
            compare(png, "image/png", label).also { outcome ->
                // The chunk walk on its own, where ImageIO (which wants IHDR first) doesn't get a say.
                val walk = pngAllocationSize(png.inputStream())
                if (walk != null && outcome.awt.isNotEmpty()) walkAnswered++
                if (walk != null && outcome.awt.any { it != walk }) walkMismatches += "$label: walked to ${walk.first}x${walk.second}, AWT sizes it ${outcome.awt}"
            }
        }
        assertNoMismatch(outcomes)
        assertTrue("${walkMismatches.size} walks disagree with AWT:\n" + walkMismatches.take(10).joinToString("\n"), walkMismatches.isEmpty())
        assertTrue("walks AWT answered too: $walkAnswered", walkAnswered >= PNG_SAMPLES / 5)
        // The oracle must have been exercised, not just passed: plenty admitted, and plenty where
        // AWT's size differs from the first IHDR's (the size ImageIO reports).
        assertTrue("admitted: ${outcomes.count { it.admitted != null }}", outcomes.count { it.admitted != null } >= PNG_SAMPLES / 5)
        val firstIhdrDiffers = outcomes.count { o -> o.awt.isNotEmpty() && o.awt.last() != o.firstIhdr }
        assertTrue("AWT sized $firstIhdrDiffers samples by something other than the first IHDR", firstIhdrDiffers >= PNG_SAMPLES / 10)
    }

    @Test
    fun `JPEGs with an extra frame header - an admitted size is the size AWT allocates`() {
        val outcomes = mutableListOf<Outcome>()
        for (progressive in listOf(false, true)) {
            val jpeg = TestImages.jpeg(16, 16, progressive)
            val control = compare(jpeg, "image/jpeg", "plain ${kind(progressive)} JPEG")
            assertEquals("control: a plain ${kind(progressive)} JPEG is admitted", 16 to 16, control.admitted)
            assertEquals("control: and decoded at that size", listOf(16 to 16), control.awt)
            outcomes += control
            for (place in SofPlace.entries) {
                for ((w, h) in listOf(4_000 to 4_000, 64 to 8, 8 to 8)) {
                    val bytes = TestImages.withExtraSof(jpeg, w, h, place)
                    outcomes += compare(bytes, "image/jpeg", "${kind(progressive)} JPEG with a ${w}x$h SOF $place")
                }
            }
        }
        assertNoMismatch(outcomes)
    }

    @Test
    fun `GIFs - an admitted size is the size AWT allocates`() {
        val random = Random(SEED)
        val outcomes = mutableListOf<Outcome>()
        repeat(GIF_SAMPLES) { i ->
            val screenW = random.nextInt(65) // 0 means "no logical screen": AWT then uses its first frame's
            val screenH = random.nextInt(65)
            val frameW = 1 + random.nextInt(64)
            val frameH = 1 + random.nextInt(64)
            val gif = TestImages.gif(screenW, screenH, frameW, frameH)
            outcomes += compare(gif, "image/gif", "GIF sample $i (seed $SEED): screen ${screenW}x$screenH, frame ${frameW}x$frameH")
        }
        for ((w, h) in listOf(64 to 64, 2 to 64, 1 to 1)) {
            outcomes += compare(TestImages.gifWithSplitFirstFrame(w, h), "image/gif", "GIF whose first frame AWT and ImageIO disagree on, AWT's ${w}x$h")
        }
        assertNoMismatch(outcomes)
        assertTrue("admitted: ${outcomes.count { it.admitted != null }}", outcomes.count { it.admitted != null } >= GIF_SAMPLES / 2)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    /** What the safe kit admitted [bytes] at, what AWT's decoder sized them by, and whether those disagree. */
    private class Outcome(val label: String, val admitted: Pair<Int, Int>?, val awt: List<Pair<Int, Int>>, val firstIhdr: Pair<Int, Int>? = null) {
        val mismatch: String?
            get() {
                val (w, h) = admitted ?: return null // refused: nothing is decoded at all
                val wrong = awt.firstOrNull { it != admitted } ?: return null // none: AWT failed before sizing, so allocated nothing
                val (aw, ah) = wrong
                val how = if (aw.toLong() * ah > w.toLong() * h) "allocates more" else "sizes it differently"
                return "$label: admitted at ${w}x$h, but AWT $how: ${aw}x$ah"
            }
    }

    private fun compare(bytes: ByteArray, mediaType: String, label: String, firstIhdr: Pair<Int, Int>? = pngFirstIhdr(bytes)): Outcome {
        val uri = TestImages.dataUri(bytes, mediaType)
        val admitted = dataImageSize(uri)
        assertEquals("$label: pixels agree with the size", admitted?.let { (w, h) -> w.toLong() * h }, dataImagePixels(uri))
        return Outcome(label, admitted, AwtDecode.declaredSizes(bytes), firstIhdr)
    }

    private fun assertNoMismatch(outcomes: List<Outcome>) {
        val mismatches = outcomes.mapNotNull { it.mismatch }
        assertTrue("${mismatches.size} of ${outcomes.size} admitted at a size AWT doesn't allocate:\n" + mismatches.take(10).joinToString("\n"), mismatches.isEmpty())
    }

    /**
     * A PNG's chunks in a random layout: 0-3 IHDRs anywhere (the first often at the front, where
     * ImageIO reads it), 0-2 IDATs with data, sometimes an empty IDAT, ancillary chunks, an IEND
     * that may come early or not at all, and now and then a bad CRC or an IHDR of the wrong length.
     */
    private fun randomPngChunks(random: Random): List<PngChunk> {
        val sizes = List(random.nextInt(4)) { (1 + random.nextInt(64)) to (1 + random.nextInt(64)) }
        val ihdrs = sizes.map { (w, h) ->
            val length = if (random.nextInt(20) == 0) 12 + 2 * random.nextInt(2) else 13
            TestImages.ihdr(w, h, colorType = COLOR_TYPES[random.nextInt(COLOR_TYPES.size)], interlace = random.nextInt(2), dataLength = length)
        }
        val (dataW, dataH) = sizes.firstOrNull() ?: (8 to 8)
        val body = mutableListOf<PngChunk>()
        repeat(random.nextInt(3)) { body += ANCILLARY[random.nextInt(ANCILLARY.size)] }
        repeat(random.nextInt(3)) { body += TestImages.idat(dataW, dataH) }
        if (random.nextInt(4) == 0) body.add(random.nextInt(body.size + 1), PngChunk("IDAT", ByteArray(0)))
        when (random.nextInt(6)) {
            0 -> Unit // no IEND
            1 -> body.add(random.nextInt(body.size + 1), TestImages.iend())
            else -> body += TestImages.iend()
        }
        ihdrs.forEachIndexed { i, ihdr ->
            val at = if (i == 0 && random.nextBoolean()) 0 else random.nextInt(body.size + 1)
            body.add(at, ihdr)
        }
        if (body.isNotEmpty() && random.nextInt(8) == 0) {
            val at = random.nextInt(body.size)
            body[at] = body[at].withBadCrc()
        }
        return body
    }

    /** The first IHDR's size (what ImageIO reports), if the PNG starts with one. */
    private fun pngFirstIhdr(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || String(bytes, 12, 4, Charsets.ISO_8859_1) != "IHDR") return null
        return be32(bytes, 16) to be32(bytes, 20)
    }

    private fun be32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun kind(progressive: Boolean) = if (progressive) "progressive" else "baseline"

    private companion object {
        const val SEED = 20_260_924L
        const val PNG_SAMPLES = 400
        const val GIF_SAMPLES = 60

        /** Gray, RGB, palette (with no PLTE: AWT gives up before sizing), RGBA. */
        val COLOR_TYPES = intArrayOf(0, 2, 3, 6)

        val ANCILLARY = listOf(
            TestImages.text(),
            PngChunk("pHYs", ByteArray(9)),
            PngChunk("prVt", ByteArray(5) { it.toByte() }),
        )
    }
}
