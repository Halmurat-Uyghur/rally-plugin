package com.github.halmurat.rally.testutil

import java.awt.Toolkit
import java.awt.image.ColorModel
import java.awt.image.ImageConsumer
import java.util.Collections
import java.util.Hashtable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What AWT's own Toolkit decoders (the ones behind ImageView) make of image bytes — the oracle
 * the data-image limit tests compare the safe kit's header measurement against.
 */
object AwtDecode {

    /**
     * Every size the decoder AWT picks for [bytes] passes to setDimensions — what an
     * ImageRepresentation (a ToolkitImage's consumer) sizes its raster by — in call order; empty
     * when the decode fails before it gets that far, which allocates no raster. The consumer here
     * keeps no pixels, so asking about an image that declares 16000x16000 costs nothing.
     */
    fun declaredSizes(bytes: ByteArray, timeoutMs: Long = 10_000): List<Pair<Int, Int>> {
        val sizes = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val done = CountDownLatch(1)
        val consumer = object : ImageConsumer {
            override fun setDimensions(width: Int, height: Int) {
                sizes.add(width to height)
            }

            override fun imageComplete(status: Int) {
                // SINGLEFRAMEDONE ends one frame of a multi-frame GIF; anything else ends the decode.
                if (status != ImageConsumer.SINGLEFRAMEDONE) done.countDown()
            }

            override fun setProperties(props: Hashtable<*, *>?) {}
            override fun setColorModel(model: ColorModel?) {}
            override fun setHints(hintflags: Int) {}
            override fun setPixels(x: Int, y: Int, w: Int, h: Int, model: ColorModel?, pixels: ByteArray?, off: Int, scansize: Int) {}
            override fun setPixels(x: Int, y: Int, w: Int, h: Int, model: ColorModel?, pixels: IntArray?, off: Int, scansize: Int) {}
        }
        val source = Toolkit.getDefaultToolkit().createImage(bytes).source
        source.startProduction(consumer)
        try {
            check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "AWT's decoder didn't finish within $timeoutMs ms" }
        } finally {
            source.removeConsumer(consumer)
        }
        return synchronized(sizes) { sizes.toList() }
    }
}
