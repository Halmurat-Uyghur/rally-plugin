package com.github.halmurat.rally.util

/** Escape `& < > "` so [text] shows literally inside HTML, attribute values included. */
fun escapeHtml(text: String): String {
    val sb = StringBuilder(text.length + 16)
    for (c in text) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * A tooltip that shows [text] (a Rally string) literally. IntelliJ's tooltip manager renders
 * tooltip text as HTML even without a leading `<html>`, so raw text such as a ticket named
 * `<img src=…>` would render — and load — as markup. Escaped inside `<html>…</html>`, it reads the
 * same in the IDE and under plain Swing, which would otherwise show the entities.
 */
fun literalHtmlTooltip(text: String): String = "<html>${escapeHtml(text)}</html>"

/**
 * A Messages dialog text that shows [text] literally, line breaks included. IntelliJ renders
 * dialog messages as HTML (AlertDialog, with a live-link listener), so server error text or a
 * Rally value carrying markup would render — and load any `<img>` it names — instead of reading
 * as written. Wrapped in `<html>`, the escaped text also reads correctly in the legacy dialog,
 * which only switches to HTML for an `<html>` prefix.
 */
fun literalHtmlMessage(text: String): String = "<html>${escapeHtml(text).replace("\n", "<br>")}</html>"

/**
 * The pure, IDE-free part of the detail panel's description rendering: the document shell a
 * Rally description is shown in, and the plain-text fallback used when Swing can't render it.
 * RallyDetailPanel reads the theme values from the IDE and delegates here, so tests can run
 * the real pipeline headless.
 */
object DescriptionHtml {

    /** The IDE-theme values the shell pins. Colors are 6 hex digits without a leading '#'. */
    data class Theme(val fontSizePx: Int, val foregroundHex: String, val linkHex: String, val errorHex: String)

    /**
     * Make a Rally-authored description fragment ready for the detail pane: author-color strip
     * and active-content neutralizing, inside the themed [shell].
     */
    fun wrap(html: String, theme: Theme): String {
        // Strip Rally's baked-in inline colors so the theme colors below win. Rally
        // descriptions carry colors authored for its light web UI; on a dark IDE theme
        // those render as white blocks and invisible dark-on-dark text (see
        // RallyHtmlUtils.stripInlineColors). Done before the src neutralizer — they
        // target disjoint attributes, so order is irrelevant.
        val decolored = RallyHtmlUtils.stripInlineColors(html)
        // Remove what JTextPane would act on rather than draw (forms, <base>, <object>,
        // frames) and blank every non-data: image source and CSS url(), so rendering a
        // Rally-authored description never contacts another host (tracking pixels, form
        // submission, off-host fetches via an injected <base>). This is the first line; the
        // pane's SafeHtmlEditorKit is the fail-closed boundary behind it for whatever markup
        // this pass misses. data:…;base64 URIs produced by resolveInlineImages are left intact.
        // The same pass drops the description's own <html>/<head>/<body> tags, which keeps the
        // shell intact: a string replace of "</body>" can't, since Swing also ends the body at
        // "</body >", "</BODY⏎>" or "</body x>" and cuts everything after them off.
        val neutralized = RallyHtmlUtils.neutralizeActiveContent(decolored)
        return shell(neutralized, theme)
    }

    /**
     * The document shell around an already-safe [body]. With inline colors stripped, the body
     * color cascades to every span, so the description renders in one consistent, readable
     * color on the pane's theme background. Swing anchors keep their own color and ignore the
     * body color, so they get their own rule; so does the `.rally-error` class the panel's
     * error messages use (a class, because stripInlineColors would remove an inline red).
     *
     * The theme-variable body color lives in the per-document inline `<body style>`, so it is
     * discarded with the document; the static `<style>` selectors can't be expressed inline.
     */
    fun shell(body: String, theme: Theme): String =
        "<html><head><style>" +
            "a{color:#${theme.linkHex};}" +
            ".rally-error{color:#${theme.errorHex};}" +
            "</style></head>" +
            "<body style='font-family:sans-serif;font-size:${theme.fontSizePx}px;margin:4px;color:#${theme.foregroundHex};'>" +
            "$body</body></html>"

    /**
     * The visible text of [html] as an HTML body: escaped, with a line break per block and
     * `<br>`, and nothing from `<head>`, `<style>`, `<script>` or `<title>`. The detail panel
     * shows this when Swing can't render a description — its CSS parser throws on some malformed
     * author styles, and its HTML parser recurses without bound on some markup — so the text is
     * still readable.
     *
     * The text comes from [RallyHtmlUtils.visibleTextLines], which never runs Swing's parser
     * (that parser is what overflowed, and would again). Entity references are decoded as Swing
     * decodes them, from its own DTD's table, and the decoded text is escaped again here: so
     * `&eacute;` shows `é`, `&lt;b&gt;` shows `<b>` as text, and nothing decoded can become markup.
     */
    fun plainText(html: String): String = RallyHtmlUtils.visibleTextLines(html).joinToString("<br>") { escapeHtml(it) }
}
