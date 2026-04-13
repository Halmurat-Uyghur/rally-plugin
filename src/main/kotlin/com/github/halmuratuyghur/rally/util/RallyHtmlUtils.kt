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
     *   4 — file name
     */
    val INLINE_IMG_PATTERN: Pattern = Pattern.compile(
        """src=(["'])((?:https?://[^/]+)?/slm/attachment/(\d+)/([^"']+))\1""",
        Pattern.CASE_INSENSITIVE
    )
}
