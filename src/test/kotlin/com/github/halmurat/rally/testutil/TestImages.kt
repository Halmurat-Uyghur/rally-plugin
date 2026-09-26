package com.github.halmurat.rally.testutil

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Hand-built image bytes for the data-image limit tests. A PNG header (IHDR) can declare any
 * size while the pixel data stays tiny — the shape of a decompression-bomb description image —
 * and the chunk CRCs are real unless a test corrupts them, so both ImageIO and AWT's own decoder
 * accept the file. PNGs are built chunk by chunk ([pngOf]) so a test can lay them out the way
 * no encoder would: several IHDRs, chunks before the IHDR, lying lengths, bad CRCs.
 */
object TestImages {

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * An 8-bit RGBA PNG whose header declares [width]x[height] and whose IDAT carries only the
     * first [rows] rows (all transparent). `rows = 0` leaves the IDAT out: the header reads fine,
     * but AWT's decoder fails before it allocates a raster, so such an image costs nothing to show.
     */
    fun png(width: Int, height: Int, rows: Int = minOf(height, 4)): ByteArray =
        pngOf(listOfNotNull(ihdr(width, height), if (rows > 0) idat(width, rows) else null, iend()))

    /**
     * One chunk for [pngOf]. Its length field is [data]'s size and its CRC the real one unless
     * [length] / [crc] say otherwise — so a chunk can lie about its length or be corrupt.
     */
    class PngChunk(val type: String, val data: ByteArray, val length: Int = data.size, val crc: Int? = null) {
        fun withBadCrc(): PngChunk = PngChunk(type, data, length, crc = realCrc() xor 0x5A5A5A5A)

        fun realCrc(): Int = CRC32().apply { update(type.toByteArray(Charsets.US_ASCII)); update(data) }.value.toInt()

        override fun toString(): String = "$type(${data.size}${if (length != data.size) ", declares $length" else ""}${if (crc != null) ", bad CRC" else ""})"
    }

    /** The PNG signature followed by [chunks], exactly as given: any order, any number of IHDRs. */
    fun pngOf(chunks: List<PngChunk>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        DataOutputStream(out).apply {
            for (chunk in chunks) {
                writeInt(chunk.length)
                write(chunk.type.toByteArray(Charsets.US_ASCII))
                write(chunk.data)
                writeInt(chunk.crc ?: chunk.realCrc())
            }
        }
        return out.toByteArray()
    }

    /** An IHDR declaring [width]x[height] ([colorType] 6 = RGBA; 8 bits a sample); [dataLength] other than 13 cuts or pads it. */
    fun ihdr(width: Int, height: Int, colorType: Int = 6, interlace: Int = 0, dataLength: Int = 13): PngChunk {
        val data = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeInt(width)
                writeInt(height)
                writeByte(8) // bit depth
                writeByte(colorType)
                writeByte(0) // compression
                writeByte(0) // filter
                writeByte(interlace)
            }
        }.toByteArray()
        return PngChunk("IHDR", data.copyOf(dataLength))
    }

    /** An IDAT holding [rows] zeroed (unfiltered) rows of a [width]-pixel RGBA image, zlib-compressed. */
    fun idat(width: Int, rows: Int): PngChunk {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw, Deflater(Deflater.BEST_COMPRESSION)).use { z ->
            val row = ByteArray(1 + width * 4) // filter byte 0, then zeroed pixels
            repeat(rows) { z.write(row) }
        }
        return PngChunk("IDAT", raw.toByteArray())
    }

    fun iend(): PngChunk = PngChunk("IEND", ByteArray(0))

    /** A tEXt chunk: an ancillary chunk both decoders accept anywhere. */
    fun text(key: String = "Comment", value: String = "x"): PngChunk =
        PngChunk("tEXt", (key + "\u0000" + value).toByteArray(Charsets.ISO_8859_1))

    /**
     * A [frameWidth]x[frameHeight] GIF whose logical screen declares [screenWidth]x[screenHeight].
     * AWT's GIF decoder allocates the whole logical screen, while ImageIO's getWidth/getHeight
     * report the frame.
     */
    fun gif(screenWidth: Int = 1, screenHeight: Int = 1, frameWidth: Int = 1, frameHeight: Int = 1): ByteArray {
        val bytes = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_BYTE_INDEXED), "gif", it)
        }.toByteArray()
        // Logical Screen Descriptor: width and height, little-endian, right after "GIF89a".
        bytes[6] = screenWidth.toByte(); bytes[7] = (screenWidth shr 8).toByte()
        bytes[8] = screenHeight.toByte(); bytes[9] = (screenHeight shr 8).toByte()
        return bytes
    }

    /**
     * A GIF with a 0x0 logical screen whose first frame AWT and ImageIO find in different places.
     * With no logical screen, AWT sizes the image by its own first frame. A Plain Text Extension
     * whose first sub-block is empty ends right there for AWT, which reads its extensions as
     * length-prefixed sub-block chains, so the next bytes are an image descriptor declaring
     * [awtWidth]x[awtHeight]. ImageIO reads 12 fixed text-grid fields over that descriptor and
     * takes the 1x1 frame after it as frame 0.
     */
    fun gifWithSplitFirstFrame(awtWidth: Int, awtHeight: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0)) // logical screen 0x0, no global color table
        out.write(byteArrayOf(0x21, 0x01, 0x00)) // Plain Text Extension, first sub-block length 0
        // AWT: image descriptor + LZW code size + a 1-byte data sub-block. ImageIO: the 12 text-grid bytes.
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, awtWidth.toByte(), (awtWidth shr 8).toByte(), awtHeight.toByte(), (awtHeight shr 8).toByte(), 0, 2, 1))
        out.write(byteArrayOf(0)) // ImageIO: the (empty) text sub-blocks end
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0, 0x3B)) // a 1x1 frame, then the trailer
        return out.toByteArray()
    }

    /**
     * A 1x1 GIF whose first frame sits behind a Comment Extension carrying [commentBytes] bytes in
     * 255-byte sub-blocks — the shape of a GIF with megabytes of metadata before its image (the
     * XMP bloat behind JDK-8270915). AWT sizes it by the logical screen without reading the comment.
     */
    fun gifWithCommentChain(commentBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(commentBytes + commentBytes / 255 + 32)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(1, 0, 1, 0, 0, 0, 0)) // logical screen 1x1, no global color table
        out.write(byteArrayOf(0x21, 0xFE.toByte())) // Comment Extension
        val block = ByteArray(255) { 'x'.code.toByte() }
        var left = commentBytes
        while (left > 0) {
            val n = minOf(block.size, left)
            out.write(n)
            out.write(block, 0, n)
            left -= n
        }
        out.write(0) // the sub-block chain ends
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0, 0x3B)) // a 1x1 frame, then the trailer
        return out.toByteArray()
    }

    /**
     * A baseline JPEG of [width]x[height]; with [declaredWidth]/[declaredHeight] its frame header
     * (SOF0) is patched to declare that size instead.
     */
    fun jpeg(width: Int, height: Int, declaredWidth: Int = width, declaredHeight: Int = height): ByteArray {
        val bytes = jpeg(width, height, progressive = false)
        // SOF: marker (2), length (2), precision (1), then height and width, big-endian.
        val sof = jpegSofOffset(bytes)
        bytes[sof + 5] = (declaredHeight shr 8).toByte(); bytes[sof + 6] = declaredHeight.toByte()
        bytes[sof + 7] = (declaredWidth shr 8).toByte(); bytes[sof + 8] = declaredWidth.toByte()
        return bytes
    }

    /** A [width]x[height] JPEG as ImageIO writes it, baseline (SOF0) or progressive (SOF2, several scans). */
    fun jpeg(width: Int, height: Int, progressive: Boolean): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 7 and 0xFF shl 16) or (y * 5 and 0xFF shl 8) or 0x40)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam
                if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** Where [withExtraSof] puts the extra frame header. */
    enum class SofPlace { BEFORE_FIRST_SOF, AFTER_FIRST_SOF, AFTER_FIRST_SCAN }

    /**
     * [jpeg] with a copy of its frame header (SOF) that declares [width]x[height] inserted at
     * [place]. AFTER_FIRST_SCAN lands between two scans of a progressive JPEG, before EOI in a
     * baseline one.
     */
    fun withExtraSof(jpeg: ByteArray, width: Int, height: Int, place: SofPlace): ByteArray {
        val sof = jpegSofOffset(jpeg)
        val sofEnd = sof + 2 + be16(jpeg, sof + 2)
        val extra = jpeg.copyOfRange(sof, sofEnd)
        extra[5] = (height shr 8).toByte(); extra[6] = height.toByte()
        extra[7] = (width shr 8).toByte(); extra[8] = width.toByte()
        val at = when (place) {
            SofPlace.BEFORE_FIRST_SOF -> sof
            SofPlace.AFTER_FIRST_SOF -> sofEnd
            SofPlace.AFTER_FIRST_SCAN -> markerAfterFirstScan(jpeg)
        }
        return jpeg.copyOfRange(0, at) + extra + jpeg.copyOfRange(at, jpeg.size)
    }

    /** Offset of the first frame header (SOF0-SOF15 but DHT, JPG and DAC), walking the marker segments. */
    private fun jpegSofOffset(jpeg: ByteArray): Int {
        var i = 2 // after SOI
        while (true) {
            check(jpeg[i] == 0xFF.toByte()) { "not at a marker: $i" }
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return i
            check(marker != 0xDA) { "no SOF before the first scan" }
            i += 2 + be16(jpeg, i + 2)
        }
    }

    /** Offset of the first marker after the first scan's entropy-coded data (the next scan's tables, or EOI). */
    private fun markerAfterFirstScan(jpeg: ByteArray): Int {
        var i = 2
        while (jpeg[i + 1] != 0xDA.toByte()) i += 2 + be16(jpeg, i + 2)
        i += 2 + be16(jpeg, i + 2) // past the SOS header
        while (true) {
            // In entropy-coded data FF is followed by a stuffed 00 or a restart marker (D0-D7).
            val next = jpeg[i + 1].toInt() and 0xFF
            if (jpeg[i] == 0xFF.toByte() && next != 0 && next !in 0xD0..0xD7) return i
            i++
        }
    }

    private fun be16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** A BMP: ImageIO reads it, AWT's Toolkit can't decode it. */
    fun bmp(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "bmp", it)
    }.toByteArray()

    fun dataUri(bytes: ByteArray, mediaType: String = "image/png"): String =
        "data:$mediaType;base64," + Base64.getEncoder().encodeToString(bytes)
}
