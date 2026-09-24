package com.github.halmurat.rally.util

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

    /**
     * Match the complete tag, including '>' inside quoted attribute values. Otherwise an
     * alt/title value can hide later src/style attributes from the per-tag sanitizers.
     * Possessive runs keep long tags and inline data URIs from causing backtracking.
     */
    private val TAG_PATTERN = Regex("""<(?:[^"'<>]++|"[^"]*"|'[^']*')++>""")

    /**
     * A `color:` or `background-color:` declaration inside a style attribute value.
     * The `(?<![\w-])` lookbehind keeps the bare-`color` alternative from matching the
     * "color" tail of `background-color` / `border-color` / `text-decoration-color`,
     * so only the two properties we care about are removed and other `*-color`
     * properties survive. Value runs to the next `;` or end of the style body.
     */
    private val STYLE_COLOR_DECL = Regex("""(?i)(?<![\w-])(?:background-color|color)\s*:\s*[^;]*;?""")

    /**
     * The `background` shorthand — removed only when it carries no `url(...)`, so a CSS
     * image background (rare in Rally, but possible) is preserved rather than blanked.
     */
    private val STYLE_BACKGROUND_SHORTHAND = Regex("""(?i)(?<![\w-])background\s*:\s*(?![^;]*url\()[^;]*;?""")

    /** A `style="..."` / `style='...'` attribute: group 1 = quote char, group 2 = body. */
    private val STYLE_ATTR = Regex("""(?i)style\s*=\s*(["'])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Presentational color attributes (`color="..."`, `bgcolor="..."`) on `<font>`,
     * `<td>`, `<table>`, etc. Requires a leading whitespace so it only matches a whole
     * attribute, and handles double-quoted, single-quoted, and unquoted values. The
     * unquoted branch stops at quotes so a pathological attribute *value* containing
     * ` color=x"` can sacrifice only that fragment — never eat the closing quote and
     * unbalance the tag.
     */
    private val PRESENTATIONAL_COLOR_ATTR =
        Regex("""(?i)\s(?:bgcolor|color)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>"']+)""")

    /**
     * A whole embedded stylesheet: a complete `<style>…</style>` element or a `<link>`
     * tag. Style rule bodies live *outside* tag interiors, so the per-tag passes below
     * can't see them — and `body{background:#fff}` would re-introduce exactly the
     * white-block bug this strip exists to fix. `<link>` makes Swing fetch an off-host
     * stylesheet synchronously (the same vector class the detail panel's external-src
     * neutralizer closes) and restyles text too. Neither element has a legitimate
     * rendering use inside a description fragment. An unclosed `<style>` is left alone
     * — fail-open, like the other pragmatic patterns here.
     */
    private val EMBEDDED_STYLESHEET = Regex("""(?is)<style\b[^>]*>.*?</style\s*>|<link\b[^>]*>""")

    /**
     * Strip Rally's baked-in inline text/background colors so the detail panel can
     * render descriptions in the IDE theme's colors instead. Rally rich-text is often
     * pasted from Word/browsers and carries colors authored for Rally's light web UI
     * (`background-color:#fff`, `color:#000`, `bgcolor=`, `<font color>`); on a dark IDE
     * theme those produce jarring white blocks and invisible dark-on-dark text.
     *
     * Stripping is scoped to tag interiors — plus whole `<style>`/`<link>` elements,
     * whose effects live outside any tag interior — so visible text and `src`/`data:`
     * image URIs are never altered. Intentional highlighting is dropped by design —
     * readability and theme consistency win over preserving the original (usually
     * accidental) colors.
     */
    fun stripInlineColors(html: String): String {
        if (html.isEmpty()) return html
        val noSheets = EMBEDDED_STYLESHEET.replace(html, "")
        return TAG_PATTERN.replace(noSheets) { tagMatch ->
            var tag = tagMatch.value
            // Drop presentational color/bgcolor attributes outright.
            tag = PRESENTATIONAL_COLOR_ATTR.replace(tag, "")
            // Strip color/background declarations from any style attribute's value.
            tag = STYLE_ATTR.replace(tag) { styleMatch ->
                val quote = styleMatch.groupValues[1]
                var body = styleMatch.groupValues[2]
                body = STYLE_COLOR_DECL.replace(body, "")
                body = STYLE_BACKGROUND_SHORTHAND.replace(body, "")
                "style=$quote$body$quote"
            }
            tag
        }
    }

    /**
     * Elements whose content must go with them: Swing renders `<select>`/`<textarea>` as live
     * form controls, and `<script>` text would otherwise show up as description text.
     */
    private val ACTIVE_ELEMENT_WITH_CONTENT =
        Regex("""(?is)<(script|select|textarea|object|applet|iframe|frameset)\b[^>]*>.*?</\1\s*>""")

    /**
     * Tags Swing's HTMLEditorKit acts on rather than just draws: `<base>` re-bases every relative
     * URL onto another host, `<form>` + `<input>` submit to their `action` URL
     * (FormView → setPage), `<object>` instantiates an arbitrary Swing component with
     * attribute-supplied properties, `<frame>` loads a nested page, `<meta>` can carry an
     * http-equiv. Opening and closing tags both go; the text between stays.
     */
    private val ACTIVE_TAG = Regex(
        """(?i)</?(?:base|meta|form|input|button|select|option|textarea|object|param|applet|embed|iframe|frame|frameset|script)\b[^>]*>"""
    )

    /** An `src` attribute whose value isn't a `data:` URI (quoted or unquoted). */
    private val NON_DATA_SRC =
        Regex("""(?i)(\ssrc\s*=\s*)(?:"(?!\s*data:)[^"]*"|'(?!\s*data:)[^']*'|(?!["'])(?!data:)[^\s>]+)""")

    /** The legacy `background="img.png"` attribute on body/table/td, which Swing fetches. */
    private val BACKGROUND_ATTR = Regex("""(?i)\sbackground\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>"']+)""")

    /** A CSS declaration carrying a `url(...)` (background, background-image, list-style…). */
    private val STYLE_URL_DECL = Regex("""(?i)[\w-]+\s*:[^;]*url\s*\([^;]*;?""")

    /**
     * Make a Rally-authored description safe to render in the detail panel's JTextPane, so the
     * pane never contacts another host or runs anything: active tags are removed, every
     * non-`data:` image source is blanked (resolveInlineImages has already turned the Rally
     * images it could fetch into `data:` URIs; anything left would load off-host, or relative
     * to an injected base), and CSS/attribute image URLs are dropped. Descriptions are
     * authored by any Rally user who can edit the ticket, so this is an allow-by-omission
     * pass over the few things Swing's HTML kit acts on. Scoped to tag interiors (plus the
     * elements dropped whole) so visible text is never altered.
     */
    fun neutralizeActiveContent(html: String): String {
        if (html.isEmpty()) return html
        val noActiveElements = ACTIVE_TAG.replace(ACTIVE_ELEMENT_WITH_CONTENT.replace(html, ""), "")
        return TAG_PATTERN.replace(noActiveElements) { tagMatch ->
            var tag = NON_DATA_SRC.replace(tagMatch.value) { "${it.groupValues[1]}\"\"" }
            tag = BACKGROUND_ATTR.replace(tag, "")
            STYLE_ATTR.replace(tag) { styleMatch ->
                val quote = styleMatch.groupValues[1]
                "style=$quote${STYLE_URL_DECL.replace(styleMatch.groupValues[2], "")}$quote"
            }
        }
    }
}
