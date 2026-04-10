package com.github.halmuratuyghur.rally.util

import java.util.regex.Pattern

/**
 * Shared HTML/image utilities for Rally content processing.
 */
object RallyHtmlUtils {
    /** Matches Rally inline image URLs in HTML src attributes. */
    val INLINE_IMG_PATTERN: Pattern = Pattern.compile(
        """src="((?:https?://[^/]+)?/slm/attachment/(\d+)/([^"]+))"""",
        Pattern.CASE_INSENSITIVE
    )
}
