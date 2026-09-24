package com.github.halmurat.rally.ui

import com.github.halmurat.rally.util.literalHtmlTooltip
import com.intellij.openapi.diagnostic.Logger
import java.awt.Graphics
import java.awt.Shape
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Base64
import java.util.Collections
import java.util.Enumeration
import java.util.IdentityHashMap
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.Document
import javax.swing.text.Element
import javax.swing.text.Position
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.View
import javax.swing.text.ViewFactory
import javax.swing.text.html.CSS
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.ImageView
import javax.swing.text.html.StyleSheet

/**
 * The detail pane's HTML kit: the stock [HTMLEditorKit] rendering, minus every path through
 * which Swing would load a resource or act on the markup it is given. Rally descriptions are
 * written by anyone who can edit a ticket, and regex sanitizing (RallyHtmlUtils) has repeatedly
 * missed markup Swing's lenient parser still accepts, so this kit is the fail-closed boundary:
 * whatever reaches it renders as text, tables, lists and `data:` images, and nothing else.
 *
 * What the stock kit would otherwise do with Rally-authored markup (JDK 17/21 javax.swing.text.html):
 *  - `<img src>` — ImageView resolves src against the document base and loads it. Here only
 *    base64 `data:image/…` sources get a URL ([DataImageView]), and only when their header
 *    declares a size the heap can take (see [MAX_DATA_IMAGE_PIXELS]); anything else draws the
 *    broken-image placeholder, as a blanked src did.
 *  - `<link rel=stylesheet>` and `@import` — StyleSheet.importStyleSheet opens the URL.
 *  - `background=` attributes, CSS `background-image` / `background: url()` — BoxPainter builds an
 *    ImageIcon for them, which loads synchronously (MediaTracker, no timeout): on the EDT, so a
 *    stalling host would freeze the IDE. `list-style-image` does the same in ListPainter.
 *    [ResourceFreeStyleSheet] hides both attributes from the painters. (StyleSheet.getBackgroundImage
 *    is package-private, so it can't be overridden; both of its callers are fed the painter's
 *    attribute set, which is what gets filtered.)
 *  - `<input>` (FormView: type=image loads src, submit loads the action URL), `<select>`,
 *    `<textarea>`, `<isindex>` (a live field whose Enter calls setPage with the typed text),
 *    `<object>` (ObjectView instantiates any Component class named by classid and sets its
 *    properties from `<param>`s), `<applet>`, `<frame>`/`<frameset>` (FrameView loads a page;
 *    FrameSetView throws from inside setText when a frameset — the parser implies one around a
 *    stray `<frame>` — has no rows/cols) — all render as nothing ([InertView]).
 *
 * Nothing else in the package opens a URL: HTMLDocument's `<base>` only moves the base those
 * paths resolve against, link hover/click only fires HyperlinkEvents (the detail panel acts on
 * its own retry link alone), `<style>` rules reach the style sheet (where `@import` is off), and
 * `<meta>`, `<title>` and unknown tags become HiddenTagViews, which load nothing. One path is
 * outside the package: tooltips — see [DataImageView.getToolTipText].
 */
class SafeHtmlEditorKit : HTMLEditorKit() {

    init {
        // FormView is never built (INPUT is inert), so nothing can submit; kept as belt and braces.
        isAutoFormSubmission = false
    }

    override fun getViewFactory(): ViewFactory = ResourceFreeViewFactory

    /**
     * HTMLEditorKit.createDefaultDocument, with the document's style sheet swapped for the
     * guarded one. The kit's shared default sheet is chained in exactly as the stock kit does,
     * so rendering is otherwise identical.
     */
    override fun createDefaultDocument(): Document {
        val styles = ResourceFreeStyleSheet()
        styles.addStyleSheet(styleSheet)
        val kitParser = parser
        return HTMLDocument(styles).apply {
            parser = kitParser
            asynchronousLoadPriority = 4
            tokenThreshold = 100
        }
    }
}

/** The stock tag-to-view mapping, with the resource-loading and live-component views replaced. */
private object ResourceFreeViewFactory : HTMLEditorKit.HTMLFactory() {

    private val INERT_TAGS: Set<Any?> = setOf(
        HTML.Tag.INPUT, HTML.Tag.SELECT, HTML.Tag.TEXTAREA, HTML.Tag.ISINDEX,
        HTML.Tag.OBJECT, HTML.Tag.APPLET, HTML.Tag.FRAME, HTML.Tag.FRAMESET,
    )

    override fun create(elem: Element): View {
        // Decide on the tag exactly as HTMLFactory.create does: an element carrying
        // ElementNameAttribute is not treated as an HTML tag at all.
        val attrs = elem.attributes
        val tag = if (attrs.getAttribute(AbstractDocument.ElementNameAttribute) == null) {
            attrs.getAttribute(StyleConstants.NameAttribute)
        } else null
        return when (tag) {
            HTML.Tag.IMG -> DataImageView(elem)
            in INERT_TAGS -> InertView(elem)
            else -> super.create(elem)
        }
    }
}

/**
 * An ImageView that only ever loads the base64 `data:` images resolveInlineImages embeds —
 * never relative to the document base, never from the network — and only those that fit the
 * data-image limits (see [MAX_DATA_IMAGE_PIXELS]). A refused image gets no URL, so it draws as
 * the broken-image placeholder (or its alt text), like any image that fails to load.
 */
private class DataImageView(elem: Element) : ImageView(elem) {

    // ImageView asks for the URL again whenever it reloads the image; the header read and the
    // budget charge happen once, on the first ask.
    private var decided = false
    private var admittedUrl: URL? = null

    override fun getImageURL(): URL? {
        if (!decided) {
            admittedUrl = admit()
            decided = true
        }
        return admittedUrl
    }

    private fun admit(): URL? {
        val url = dataImageUrl(element.attributes.getAttribute(HTML.Attribute.SRC) as? String) ?: return null
        val pixels = declaredPixels(url) ?: return null
        if (!DataImageBudget.of(document).admit(element, pixels)) {
            LOG.warn("Not showing a description image: its $pixels px would exceed the description's $MAX_DESCRIPTION_IMAGE_PIXELS px budget")
            return null
        }
        return url
    }

    /**
     * The stock view returns the Rally-authored alt text, and IntelliJ's tooltip manager asks
     * every hovered component for a tooltip and renders the answer as HTML (with or without a
     * leading `<html>`) — so an alt of `<img src=…>` would load that image. Keep the tooltip,
     * shown literally.
     */
    override fun getToolTipText(x: Float, y: Float, allocation: Shape): String? {
        val alt = altText
        return if (alt.isNullOrEmpty()) alt else literalHtmlTooltip(alt)
    }
}

/**
 * The URL for an `<img src>` value, or null (the broken-image placeholder) unless it is a
 * base64 `data:image/…` URI. Built on [DataUrlHandler] directly, so it can't be resolved
 * against a `<base>` and needs no JVM-wide handler.
 */
internal fun dataImageUrl(src: String?): URL? {
    val spec = src?.trim() ?: return null
    if (!spec.startsWith("data:image/", ignoreCase = true)) return null
    val comma = spec.indexOf(',')
    if (comma < 0 || !spec.regionMatches(comma - BASE64_SUFFIX.length, BASE64_SUFFIX, 0, BASE64_SUFFIX.length, ignoreCase = true)) {
        return null
    }
    return try {
        URL(null, spec, DataUrlHandler)
    } catch (e: MalformedURLException) {
        null
    }
}

private const val BASE64_SUFFIX = ";base64"

// ── data: image limits ──────────────────────────────────────────────────────────────────
//
// AWT decodes an image into a raster sized by the dimensions its header declares — 4 bytes a
// pixel — however little pixel data follows: a ~330-character PNG whose header says 12000x12000
// pins ~550 MB, and three of them exhaust the IDE heap on AWT's "Image Fetcher" threads. Neither
// the inline-image cap (raw data: images in a description aren't counted) nor the download size
// (Rally inline images are resolved uncapped) bounds that, so the decision is made here, where
// every description image ends up: from the headers alone, before anything is decoded. What is
// charged must be the size AWT's own decoders (sun.awt.image) will allocate, which is not always
// the size ImageIO reports for the same bytes — see [measure].

/**
 * Longest base64 payload read at all: ~15 MB of image, well past any screenshot, and a bound on
 * the work (and memory) a single image can cost before its header is even looked at.
 */
private const val MAX_DATA_IMAGE_PAYLOAD_CHARS = 20_000_000

/**
 * Longest side an image may declare: twice an 8K display's width. Within the pixel cap an image
 * could still be a 16,777,216x1 strip; no screenshot is, and it would lay out as a view far wider
 * (or taller) than any pane.
 */
private const val MAX_DATA_IMAGE_SIDE = 16_384

/**
 * Most pixels one image may declare: 2^24 = 16,777,216, a 4096x4096 image (a 5K screenshot,
 * 5120x2880 = 14.7M, fits). The raster AWT keeps is up to 4 bytes a pixel, 64 MB at the cap, but
 * a decode needs more while it runs: an interlaced PNG fills a whole int[] frame (4 more bytes a
 * pixel) before handing it over, and a progressive JPEG holds every DCT coefficient (~6 bytes a
 * pixel for three components, in native memory) for the whole decode. So an image at the cap
 * peaks at ~170 MB.
 */
private const val MAX_DATA_IMAGE_PIXELS = 16_777_216L

/**
 * Most pixels one description's images may declare together: ~160 MB of rasters kept for the
 * open description, and ~400 MB at the decode-time peak should they all decode at once (AWT
 * decodes on up to four "Image Fetcher" threads). Two images at the per-image cap fit; later
 * images past it are refused.
 */
private const val MAX_DESCRIPTION_IMAGE_PIXELS = 40_000_000L

/**
 * The pixel count [src]'s image declares, or null when the detail pane refuses to decode it at
 * all — the per-image part of the decision (see [declaredSize]); the per-description budget
 * is applied on top by the view.
 */
internal fun dataImagePixels(src: String?): Long? = dataImageSize(src)?.let { (width, height) -> width.toLong() * height }

/** The width and height [src]'s image is admitted at (see [declaredSize]), or null when it is refused. */
internal fun dataImageSize(src: String?): Pair<Int, Int>? = dataImageUrl(src)?.let(::declaredSize)

private fun declaredPixels(url: URL): Long? = declaredSize(url)?.let { (width, height) -> width.toLong() * height }

/**
 * The size AWT will allocate for [url]'s image (a [DataUrlHandler] URL, so the payload is read
 * exactly as [DataUrlConnection] serves it), or null when it must not be decoded: a payload over
 * [MAX_DATA_IMAGE_PAYLOAD_CHARS], a format AWT doesn't decode (it would only ever show a broken
 * image — SVG, WebP, BMP…), a header ImageIO can't read, headers that don't tell the size AWT
 * will allocate (see [measure]), or dimensions over [MAX_DATA_IMAGE_SIDE] / [MAX_DATA_IMAGE_PIXELS].
 * Only headers are read, and the payload is decoded only as far as they go: this runs during
 * layout, on the EDT. For a PNG that is every chunk's header, to the end of the payload (see
 * [pngAllocationSize]) — about 1 ms a MB, 20 ms at the payload cap, once per image.
 */
private fun declaredSize(url: URL): Pair<Int, Int>? {
    val path = url.path
    val comma = path.indexOf(',')
    if (comma < 0) return null
    val payloadChars = path.length - (comma + 1)
    if (payloadChars > MAX_DATA_IMAGE_PAYLOAD_CHARS) {
        LOG.warn("Not showing a description image: its $payloadChars-character payload is over the $MAX_DATA_IMAGE_PAYLOAD_CHARS limit")
        return null
    }
    val size = try {
        measure { Base64.getMimeDecoder().wrap(Latin1Stream(path, comma + 1)) }
    } catch (e: Exception) {
        null // unreadable: IOException, or the RuntimeExceptions ImageIO throws on malformed headers
    }
    val (width, height) = size ?: return null
    val pixels = width.toLong() * height
    if (width <= 0 || height <= 0 || width > MAX_DATA_IMAGE_SIDE || height > MAX_DATA_IMAGE_SIDE || pixels > MAX_DATA_IMAGE_PIXELS) {
        LOG.warn("Not showing a description image: it declares ${width}x$height px")
        return null
    }
    return size
}

/**
 * Width and height AWT's decoder will allocate for the image [payload] opens (every call opens a
 * fresh stream over the image's bytes), or null when its headers don't tell that, or AWT doesn't
 * decode the format. ImageIO has to read the header, but its answer is only used where it is
 * the one AWT acts on:
 *  - JPEG: both read the header with the JDK's libjpeg (jpeg_read_header), which takes the first
 *    frame header (SOF) and fails on a second one wherever it comes (JERR_SOF_DUPLICATE), before
 *    the first scan or after it.
 *  - PNG: ImageIO reads the first IHDR, AWT the last one before the pixel data; the chunks are
 *    walked the way AWT reads them, and the two answers must agree ([pngAllocationSize]).
 *  - GIF: AWT allocates the logical screen (bytes 6-9, little-endian), not the frame ImageIO
 *    measures; a frame is clipped to the screen. A zero screen side it takes from its first frame,
 *    as its own block walk finds it — not always ImageIO's frame 0 (AWT reads a Plain Text
 *    Extension as a sub-block chain, ImageIO reads 12 fixed bytes first, so a crafted one hides
 *    a 16000x16000 frame from ImageIO behind a 1x1 one) — so such a GIF is refused.
 */
private fun measure(payload: () -> InputStream): Pair<Int, Int>? {
    val input = BufferedInputStream(payload())
    val head = ByteArray(10)
    input.mark(head.size)
    val headLength = input.readNBytes(head, 0, head.size)
    input.reset()
    val format = ToolkitImageFormat.of(head, headLength) ?: return null
    val imageIoSize = imageIoSize(input, format.imageIoName) ?: return null
    return when (format) {
        ToolkitImageFormat.JPEG -> imageIoSize
        ToolkitImageFormat.PNG -> imageIoSize.takeIf { it == pngAllocationSize(payload()) }
        ToolkitImageFormat.GIF -> (le16(head, 6) to le16(head, 8)).takeIf { (width, height) -> width > 0 && height > 0 }
    }
}

/** Width and height of image 0 as ImageIO's [formatName] reader reads them from the header; null when there is no reader. */
private fun imageIoSize(input: InputStream, formatName: String): Pair<Int, Int>? {
    val reader = ImageIO.getImageReadersByFormatName(formatName).asSequence().firstOrNull() ?: return null
    return MemoryCacheImageInputStream(input).use { stream ->
        try {
            reader.setInput(stream, true, true)
            reader.getWidth(0) to reader.getHeight(0)
        } finally {
            reader.dispose()
        }
    }
}

/**
 * The size AWT's PNG decoder (sun.awt.image.PNGImageDecoder) will allocate for the PNG [input]
 * holds, or null when its chunks don't give one clear answer, or would make the decoder allocate
 * for data that isn't there.
 *
 * The decoder's getData() handles every chunk up to the first IDAT that has data (an empty IDAT
 * doesn't stop it), and each IHDR overwrites the width and height; produceImage() then calls
 * setDimensions once, before reading any pixels. So AWT sizes the image by the LAST IHDR before
 * that point, while ImageIO reports the first: a 1x1 IHDR followed by a 16000x16000 one was
 * charged 1 px and pinned ~1 GB. Exactly one IHDR is allowed there, 13 bytes long (AWT rejects any
 * other length). With no IDAT that has data, the decoder handles every chunk to the end of the
 * stream, IHDRs past IEND included, and so does this walk. IHDRs after the first IDAT with data
 * can't enlarge the allocation: the image has been sized by then, and setDimensions is never
 * called again.
 *
 * Every chunk must also be whole, to the end of the stream: the decoder reads each chunk into one
 * buffer, growing it to the length the chunk declares before it knows whether that many bytes
 * follow (PNGImageDecoder.need), and it does so for every chunk it reads — past the first IDAT, and
 * past IEND, for as long as the compressed pixel data asks for more — so a chunk declaring 400 MB
 * costs 400 MB, however few bytes follow. A negative length is refused too (the decoder throws on
 * it). CRCs aren't checked: a bad one only makes AWT's decoder stop sooner.
 */
internal fun pngAllocationSize(input: InputStream): Pair<Int, Int>? {
    val data = DataInputStream(BufferedInputStream(input))
    return try {
        data.skipNBytes(PNG_SIGNATURE_LENGTH) // checked by ToolkitImageFormat
        var ihdrCount = 0
        var size: Pair<Int, Int>? = null
        var sized = false // past the first IDAT with data: AWT has sized the image
        val header = ByteArray(PNG_CHUNK_HEADER_LENGTH)
        // Fewer bytes left than a chunk header ends the stream for the decoder, and for this walk.
        while (data.readNBytes(header, 0, header.size) == header.size) {
            val length = be32(header, 0)
            val type = be32(header, 4)
            if (length < 0) return null
            if (!sized && type == PNG_IHDR) {
                if (length != PNG_IHDR_LENGTH) return null
                ihdrCount++
                size = data.readInt() to data.readInt()
                data.skipNBytes(length - 8L + PNG_CRC_LENGTH)
            } else {
                data.skipNBytes(length + PNG_CRC_LENGTH)
            }
            if (type == PNG_IDAT && length > 0) sized = true
        }
        size.takeIf { ihdrCount == 1 }
    } catch (e: EOFException) {
        null // a chunk runs past the end of the data
    }
}

private const val PNG_SIGNATURE_LENGTH = 8L
private const val PNG_CHUNK_HEADER_LENGTH = 8 // length, then type
private const val PNG_CRC_LENGTH = 4L
private const val PNG_IHDR_LENGTH = 13
private const val PNG_IHDR = 0x49484452 // "IHDR"
private const val PNG_IDAT = 0x49444154 // "IDAT"

private fun be32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

private fun le16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

/**
 * The formats AWT's Toolkit decodes, recognized by the leading bytes it checks itself
 * (InputStreamImageSource.getDecoder — it ignores the media type). XBM, the fourth, has no ImageIO
 * reader to measure it with, so it is refused.
 */
private enum class ToolkitImageFormat(val imageIoName: String, private val magic: ByteArray, private val minLength: Int = magic.size) {
    PNG("png", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)),
    JPEG("jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),

    // "GIF8" is all AWT checks, but the logical screen size [measure] reads ends at byte 10.
    GIF("gif", byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte()), minLength = 10);

    companion object {
        fun of(head: ByteArray, length: Int): ToolkitImageFormat? = entries.firstOrNull { format ->
            length >= format.minLength && format.magic.indices.all { head[it] == format.magic[it] }
        }
    }
}

/**
 * [text] from [start] on, as the bytes String.getBytes(ISO_8859_1) gives — the conversion the
 * whole-payload decode in [DataUrlConnection] goes through — so the header read here is the one
 * AWT will decode.
 */
private class Latin1Stream(private val text: String, private var pos: Int) : InputStream() {
    override fun read(): Int = if (pos < text.length) latin1(text[pos++]) else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= text.length) return -1
        val n = minOf(len, text.length - pos)
        for (i in 0 until n) b[off + i] = latin1(text[pos++]).toByte()
        return n
    }

    private fun latin1(c: Char): Int = if (c.code <= 0xFF) c.code else '?'.code
}

/**
 * The pixels one document's `data:` images have been granted, against [MAX_DESCRIPTION_IMAGE_PIXELS];
 * kept as a document property. The detail pane renders every description into a fresh document
 * (RallyDetailPanel.setDescriptionHtml), so this is a per-description budget. Decisions are
 * remembered per element: rebuilding the views (a theme switch re-creates every view) gets the
 * same answers instead of spending the budget a second time and losing images it had shown.
 */
private class DataImageBudget {
    private val decisions = IdentityHashMap<Element, Boolean>()
    private var granted = 0L

    @Synchronized
    fun admit(element: Element, pixels: Long): Boolean = decisions.getOrPut(element) {
        val fits = granted + pixels <= MAX_DESCRIPTION_IMAGE_PIXELS
        if (fits) granted += pixels
        fits
    }

    companion object {
        private val KEY = Any()

        fun of(document: Document): DataImageBudget = synchronized(KEY) {
            document.getProperty(KEY) as? DataImageBudget ?: DataImageBudget().also { document.putProperty(KEY, it) }
        }
    }
}

private val LOG = Logger.getInstance(SafeHtmlEditorKit::class.java)

/**
 * Serves `data:<media type>;base64,<payload>` URLs from memory (RFC 2397, base64 form only).
 * Plain Swing has no data: handler — `new URL("data:…")` throws — so without this, ImageView
 * gets no URL for the images resolveInlineImages embeds and they never render. It is only
 * ever passed to `URL(null, spec, handler)`, never installed as a factory (which can be set
 * once per JVM, and would change URL handling for the whole IDE).
 */
internal object DataUrlHandler : URLStreamHandler() {

    /**
     * Keep everything after `data:` as the opaque path: there is no authority, and base64's
     * '/' and '+' must not be split into path segments or a query. (URL's constructor has
     * already cut a '#fragment', which a base64 payload never contains.)
     */
    override fun parseURL(u: URL, spec: String, start: Int, limit: Int) {
        setURL(u, "data", null, -1, null, null, spec.substring(start, limit), null, null)
    }

    override fun openConnection(u: URL): URLConnection = DataUrlConnection(u)
}

private class DataUrlConnection(url: URL) : URLConnection(url) {

    // "image/png;base64,iVBOR…" — media type (plus any parameters) before the comma, payload after.
    private val header = url.path.substringBefore(',', missingDelimiterValue = "")
    private val payload = url.path.substringAfter(',', missingDelimiterValue = "")

    override fun connect() {
        connected = true
    }

    override fun getContentType(): String =
        header.substringBefore(';').trim().lowercase(Locale.ROOT).ifEmpty { "text/plain" }

    override fun getInputStream(): InputStream {
        if (!url.path.contains(',')) throw IOException("Malformed data: URL (no ',' before the payload)")
        if (!header.endsWith(BASE64_SUFFIX, ignoreCase = true)) throw IOException("Only base64 data: URLs are supported")
        connect()
        return try {
            // MIME decoder: tolerates the line breaks and spaces wrapped base64 often carries.
            ByteArrayInputStream(Base64.getMimeDecoder().decode(payload))
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid base64 in data: URL", e)
        }
    }
}

/**
 * A style sheet that never loads anything: no linked or `@import`ed sheets, and painters that
 * can't see background or list-bullet images. Everything else — including the rules the
 * wrapper's `<style>` adds — behaves as in [StyleSheet].
 */
private class ResourceFreeStyleSheet : StyleSheet() {

    /** Where both `<link rel=stylesheet>` (HTMLDocument) and CSS `@import` (addRule) end up. */
    override fun importStyleSheet(url: URL?) {}

    override fun getBoxPainter(a: AttributeSet): BoxPainter =
        super.getBoxPainter(AttributeHidingSet(a, CSS.Attribute.BACKGROUND_IMAGE))

    override fun getListPainter(a: AttributeSet): ListPainter =
        super.getListPainter(AttributeHidingSet(a, CSS.Attribute.LIST_STYLE_IMAGE))
}

/**
 * A live, read-only view of [delegate] in which [hidden] is not defined. Live rather than a
 * copy because the painters keep the set (CSSBorder reads it again at paint time) and view
 * attribute sets resolve through their parents on every lookup.
 */
private class AttributeHidingSet(private val delegate: AttributeSet, private val hidden: Any) : AttributeSet {
    override fun getAttribute(key: Any?): Any? = if (key == hidden) null else delegate.getAttribute(key)
    override fun isDefined(attrName: Any?): Boolean = attrName != hidden && delegate.isDefined(attrName)
    override fun containsAttribute(name: Any?, value: Any?): Boolean = name != hidden && delegate.containsAttribute(name, value)
    override fun containsAttributes(attributes: AttributeSet): Boolean =
        attributes.attributeNames.toList().all { containsAttribute(it, attributes.getAttribute(it)) }
    override fun getAttributeNames(): Enumeration<*> = Collections.enumeration(names())
    override fun getAttributeCount(): Int = names().size
    override fun isEqual(attr: AttributeSet): Boolean = attributeCount == attr.attributeCount && containsAttributes(attr)
    override fun copyAttributes(): AttributeSet = SimpleAttributeSet(delegate).apply { removeAttribute(hidden) }
    override fun getResolveParent(): AttributeSet? = delegate.resolveParent

    private fun names(): List<Any?> = delegate.attributeNames.toList().filter { it != hidden }
}

/** A zero-size view that paints nothing and builds no component — for elements Swing would make live. */
private class InertView(elem: Element) : View(elem) {
    override fun getPreferredSpan(axis: Int): Float = 0f
    override fun paint(g: Graphics, allocation: Shape) {}
    override fun modelToView(pos: Int, a: Shape, b: Position.Bias): Shape = a.bounds.apply { width = 0 }
    override fun viewToModel(x: Float, y: Float, a: Shape, biasReturn: Array<Position.Bias>): Int {
        biasReturn[0] = Position.Bias.Forward
        return startOffset
    }
}
