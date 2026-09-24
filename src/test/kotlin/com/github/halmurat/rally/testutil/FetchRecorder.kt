package com.github.halmurat.rally.testutil

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Loopback HTTP server that records every request it receives, for proving that a Swing
 * renderer does (positive control) or does not (the fix) load a URL named in Rally-authored
 * markup. Answers `.css` paths with an empty stylesheet and everything else with a 1x1 PNG,
 * so a fetch that does happen completes the way a real one would.
 *
 * Nothing JVM-global is installed: a URLStreamHandlerFactory can be set only once per JVM and
 * would leak into every other test class, so the renderer is pointed at a real socket instead.
 */
class FetchRecorder : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = ConcurrentLinkedQueue<String>()
    private val nextScope = AtomicInteger()

    init {
        server.createContext("/") { exchange ->
            requests.add(exchange.requestURI.path)
            val css = exchange.requestURI.path.endsWith(".css")
            val body = if (css) "p{}".toByteArray(StandardCharsets.UTF_8) else PNG
            exchange.responseHeaders.add("Content-Type", if (css) "text/css" else "image/png")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
    }

    /**
     * A fresh URL prefix (`http://127.0.0.1:<port>/s<n>/`) for one test case. Every case gets
     * its own: AWT's Toolkit caches images by URL, so a path reused across cases could be
     * served from that cache and hide a second fetch. Requests are counted per prefix, which
     * also catches derived fetches such as the macOS toolkit's `name@2x.png` probe.
     */
    fun newScope(): String = "http://127.0.0.1:${server.address.port}/s${nextScope.incrementAndGet()}/"

    /** Paths requested under [scope] (a value returned by [newScope]) so far. */
    fun requestsUnder(scope: String): List<String> {
        val prefix = java.net.URI(scope).path
        return requests.filter { it.startsWith(prefix) }
    }

    /** Every path requested so far, in arrival order. */
    fun allRequests(): List<String> = requests.toList()

    /**
     * Poll until something under [scope] has been requested, for at most [timeoutMs]. A positive
     * control keeps the default, [QUIET_PERIOD_MS]: the window a negative test waits for a request
     * that must not come is only meaningful if the fetches it guards against arrive within it.
     */
    fun awaitRequestUnder(scope: String, timeoutMs: Long = QUIET_PERIOD_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (requestsUnder(scope).isNotEmpty()) return true
            Thread.sleep(25)
        }
        return requestsUnder(scope).isNotEmpty()
    }

    override fun close() = server.stop(0)

    companion object {
        /** How long a negative test waits for a fetch that must not happen (image loads run on AWT's "Image Fetcher" threads). */
        const val QUIET_PERIOD_MS = 800L

        val PNG: ByteArray = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
    }
}
