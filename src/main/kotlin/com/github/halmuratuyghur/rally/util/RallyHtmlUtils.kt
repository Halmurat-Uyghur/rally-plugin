package com.github.halmuratuyghur.rally.util

import java.util.regex.Pattern

/**
 * Shared HTML/image utilities for Rally content processing.
 */
object RallyHtmlUtils {
    /**
     * Matches Rally inline image URLs in HTML src attributes (double- or single-quoted).
     *
     * Capture groups:
     *   1 — quote char (used only for backreference matching)
     *   2 — original src URL (absolute or site-relative)
     *   3 — attachment ObjectID
     *   4 — file name (any chars except the opening quote, so apostrophes are
     *       fine inside double-quoted src and vice versa; '<'/'>' are excluded
     *       so an unterminated quote in malformed HTML can't swallow the
     *       following tags and the next image's opening quote)
     */
    val INLINE_IMG_PATTERN: Pattern = Pattern.compile(
        """src=(["'])((?:https?://[^/]+)?/slm/attachment/(\d+)/((?:(?!\1)[^<>])+))\1""",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Build a downloadable URL for a Rally inline image. Descriptions carry
     * filenames both raw (spaces, quotes — URI-illegal, throws inside the
     * download layer's URI parse) and pre-encoded (%20). Use the raw URL when
     * it already parses so pre-encoded names aren't double-encoded; otherwise
     * percent-encode the filename segment the same way the attachment download
     * path does.
     */
    fun inlineImageUrl(base: String, objectId: String, fileName: String): String {
        val raw = "$base/slm/attachment/$objectId/$fileName"
        return try {
            java.net.URI(raw)
            raw
        } catch (_: Exception) {
            val encoded = java.net.URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
            "$base/slm/attachment/$objectId/$encoded"
        }
    }
}
