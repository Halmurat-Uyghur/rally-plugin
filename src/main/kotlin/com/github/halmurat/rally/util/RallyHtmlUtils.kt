package com.github.halmurat.rally.util

import java.util.regex.Pattern
import javax.swing.text.html.HTML
import javax.swing.text.html.parser.DTD
import javax.swing.text.html.parser.ParserDelegator

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

    // ── Description sanitizing ───────────────────────────────────
    //
    // Both passes below run the description through one tokenizer ([SwingTokenizer]) that
    // follows the rules of javax.swing.text.html.parser.Parser — the parser the detail pane's
    // JTextPane uses — including its error recovery for malformed markup, so the sanitizer sees
    // the same tags and attributes Swing will. Regexes over the raw markup can't do that: they
    // disagree with Swing about where a tag or an attribute value ends, and each disagreement
    // either hides an attribute from the pass or rewrites text inside a quoted value. The
    // plain-text fallback ([visibleTextLines]) reads descriptions with the same tokenizer, which,
    // unlike Swing's parser, can't recurse.
    //
    // Output invariants that make the rewrite safe to parse again:
    //  - every '<' in the output starts a tag the tokenizer emitted: text never carries a raw
    //    '<' (Swing drops a bare one anyway), so removing a tag can never join the fragments
    //    around it into a new tag;
    //  - text is copied verbatim, except the content of elements dropped together with it;
    //  - a tag the policy leaves alone, that parsed without error recovery and whose unquoted
    //    values hold no entity reference is copied byte-for-byte: every boundary in it is one
    //    Swing finds character by character, so Swing reads the copy exactly as the tokenizer
    //    did. Anything else is re-emitted in a normalized form (quoted values, one space between
    //    attributes) that Swing reads exactly as the tokenizer did.

    /**
     * Strip Rally's baked-in inline text/background colors so the detail panel can
     * render descriptions in the IDE theme's colors instead. Rally rich-text is often
     * pasted from Word/browsers and carries colors authored for Rally's light web UI
     * (`background-color:#fff`, `color:#000`, `bgcolor=`, `<font color>`, `<body text=>`); on a
     * dark IDE theme those produce jarring white blocks and invisible dark-on-dark text.
     *
     * Removes `color`/`bgcolor`/`text` attributes (Swing's CSS.translateHTMLToCSS reads HTML 3.2's
     * `text=` as the text color on any element, not only on `<body>`, so `<p text=#000000>` paints
     * black); `color`, `background-color` and a `background`
     * shorthand without `url(` from style attributes; and whole `<style>…</style>` elements
     * and `<link>` tags, whose rules restyle text from outside any tag. An unclosed `<style>` and
     * a lone `</style>` are left alone. What the pane shows as text and `src`/`data:` image URIs
     * are never altered (see the invariants above: a malformed tag is re-emitted normalized, and
     * a stray `<` that Swing discards anyway is dropped). Intentional highlighting is dropped by
     * design — readability and theme consistency win over preserving the original (usually
     * accidental) colors.
     */
    fun stripInlineColors(html: String): String {
        if (html.isEmpty()) return html
        return rewrite(html, COLOR_POLICY)
    }

    /**
     * Neutralize what Swing's HTML kit would act on in a Rally-authored description (any Rally
     * user who can edit the ticket authors it): tags that fetch, submit, embed or re-base
     * (`<base>`, forms and their controls, `<object>`/`<applet>`/`<embed>`, frames, `<meta>`,
     * `<script>`, `<isindex>`, `<link>`, plus the elements Swing reads as literal text), every
     * `src` that isn't a `data:` URI (resolveInlineImages has already turned the Rally images it
     * could fetch into `data:` URIs), `background` attributes, and style declarations that can
     * load an image or that Swing's CSS parser can't parse (an unbalanced quote there makes
     * JTextPane.setText throw and cuts the description off).
     *
     * It also removes markup Swing can't render inside the pane's document: the description's
     * own `<html>`, `<head>` and `<body>` tags (their content stays), because the description is
     * shown inside a document shell — an end tag in any spelling Swing accepts (`</body >`,
     * `</BODY⏎>`, `</html x>`) would end the shell's body and cut the rest off, and a stray
     * `<body>`'s attributes land on the next element; and the table sections `<thead>`, `<tbody>`
     * and `<tfoot>` (content stays), which Swing's HTML 3.2 DTD doesn't define: outside a table,
     * followed by an `<li>`, `<caption>`, `<title>`, another section and a few more, they send
     * Swing's parser into unbounded recursion (a StackOverflowError on any stack), and inside one
     * Swing lays the table out the same without them. `<noscript>` tags go too (content stays):
     * inside a `<dir>` or `<menu>` they can set off the same recursion.
     *
     * Defense in depth and display hygiene, not the network boundary: that is the detail pane's
     * fail-closed editor kit. This pass keeps descriptions from trying to reach another host in
     * the first place, and keeps them readable.
     */
    fun neutralizeActiveContent(html: String): String {
        if (html.isEmpty()) return html
        return rewrite(html, ACTIVE_CONTENT_POLICY)
    }

    /** What a pass does with a tag's elements and attributes. */
    private class TagPolicy(
        /** Elements removed together with everything up to their next end tag. */
        val dropWithContent: Set<String>,
        /** Elements whose start and end tags are removed (content stays); also the fallback when a [dropWithContent] element has no end tag. */
        val dropTags: Set<String>,
        val attribute: (Attribute) -> AttributeEdit,
    )

    private sealed class AttributeEdit {
        object Keep : AttributeEdit()
        object Remove : AttributeEdit()
        /** Replace the value; [emitted] is written after `name=`, quotes included. */
        class Value(val emitted: String) : AttributeEdit()
    }

    private val COLOR_POLICY = TagPolicy(
        dropWithContent = setOf("style"),
        dropTags = setOf("link"),
    ) { attribute ->
        when (attribute.name) {
            // Swing maps text= to the CSS color of whatever element carries it.
            "color", "bgcolor", "text" -> AttributeEdit.Remove
            "style" -> filterStyle(attribute, ::isColorDeclaration)
            else -> AttributeEdit.Keep
        }
    }

    private val ACTIVE_CONTENT_POLICY = TagPolicy(
        // Swing renders <select>/<textarea> as live controls, <object>/<applet>/<iframe> embed,
        // and <script> text would otherwise show up as description text.
        dropWithContent = setOf("script", "select", "textarea", "object", "applet", "iframe", "frameset"),
        dropTags = setOf(
            // <base> re-bases every relative URL; <form>+<input> submit to their action URL
            // (FormView → setPage), as does <isindex>; <object> instantiates an arbitrary Swing
            // component from attribute-supplied properties; frames load nested pages; <meta> can
            // carry an http-equiv; <link> fetches a stylesheet.
            "base", "meta", "form", "input", "button", "select", "option", "textarea", "object", "param",
            "applet", "embed", "iframe", "frame", "frameset", "script", "isindex", "link",
            // Swing's DTD gives these literal (CDATA) content, so Swing doesn't tokenize what
            // follows them the way this pass does; dropping the tags keeps both views the same.
            "style", "plaintext", "xmp", "listing", "noframes", "nohotjava", "animate",
            // The description is shown inside the pane's own document: its document tags would
            // end that document's body early or restyle the next element.
            "html", "head", "body",
            // Unknown to Swing's DTD; outside a table they can send its parser into unbounded
            // recursion, and a table renders the same without them.
            "thead", "tbody", "tfoot",
            // Inside a <dir> or <menu>, a <noscript> can send Swing's parser into unbounded
            // recursion too (Parser.legalElementContext; reached by more element chains since
            // JDK 21). Swing runs no scripts, so its content shows either way.
            "noscript",
        ),
    ) { attribute ->
        when (attribute.name) {
            "src" -> if (attribute.isInlineDataUri()) AttributeEdit.Keep else AttributeEdit.Value("\"\"")
            "background" -> AttributeEdit.Remove
            "style" -> filterStyle(attribute, ::isActiveDeclaration)
            else -> AttributeEdit.Keep
        }
    }

    /**
     * The text [html] shows, one entry per line: what the detail panel falls back to when Swing
     * can't render a description (DescriptionHtml.plainText). It is read from this file's
     * tokenizer, not from Swing's parser, because that parser is one of the things that fails:
     * some markup (a `<tbody>` outside a table followed by an `<li>`, say) sends it into unbounded
     * recursion, which overflows any stack. The tokenizer is a single forward loop.
     *
     * - A line ends at every start and end tag of an element Swing treats as a block or as
     *   breaking the flow (`<p>`, `<li>`, `<td>`, `<br>`, …), as HTML.getTag(name) reports it —
     *   the same test the pane's own views use. Unknown elements don't break a line.
     * - The content of `<head>`, `<style>`, `<script>` and `<title>` is skipped, and ends where
     *   Swing's parser ends it in a document's head and body: `<style>` and `<script>` content is
     *   literal text up to their next end tag (without one, it hides nothing, as in [rewrite]); a
     *   `<title>` holds only text and ends at its end tag or the next start tag; a `<head>` holds
     *   only head elements and ends at its end tag or the first start tag of anything else. Swing
     *   also ignores some of these tags where its DTD doesn't allow them (a `<head>` in the body,
     *   a `<title>` inside a list or table) and shows their text; this skips it anyway. A
     *   description's own head tags never get here (the sanitizer drops them).
     * - Entity references are decoded the way Swing's parser decodes them in content: the same
     *   extent ([decodeEntity]) and the names of Swing's own DTD ([TextEntities]), so `&eacute;`
     *   reads `é`, `&#146;` reads `’` and an unknown `&zz;` stays as written.
     * - Runs of spaces, tabs and line breaks become one space (as rendered HTML shows them),
     *   lines are trimmed, and empty lines are left out.
     *
     * Swing's literal-content elements (`<xmp>`, `<plaintext>`, …) are read as markup, like the
     * tokenizer reads them everywhere else; the sanitizer drops their tags.
     */
    internal fun visibleTextLines(html: String): List<String> {
        val tokens = SwingTokenizer(html).tokenize()
        val literalEnds = endTagPositions(tokens, LITERAL_HIDDEN_ELEMENTS)
        val lines = ArrayList<String>()
        val line = StringBuilder()
        fun breakLine() {
            val text = collapseSpaces(line).trim()
            if (text.isNotEmpty()) lines += text
            line.setLength(0)
        }
        var inHead = false
        var inTitle = false
        var i = 0
        while (i < tokens.size) {
            when (val token = tokens[i]) {
                is Text -> if (!inHead && !inTitle) appendDecodedText(html, token.start, token.end, line)
                is Ignored -> Unit
                is StartTag -> {
                    inTitle = token.name == "title"
                    inHead = token.name == "head" || (inHead && token.name in HEAD_ELEMENTS)
                    if (token.name in LITERAL_HIDDEN_ELEMENTS) {
                        val close = firstAfter(literalEnds[token.name], i)
                        if (close >= 0) {
                            i = close + 1
                            continue
                        }
                    } else if (token.name !in HIDDEN_ELEMENTS && endsLine(token.name)) {
                        breakLine()
                    }
                }
                is EndTag -> when (token.name) {
                    "title" -> inTitle = false
                    "head" -> inHead = false
                    else -> if (token.name !in HIDDEN_ELEMENTS && endsLine(token.name)) breakLine()
                }
            }
            i++
        }
        breakLine()
        return lines
    }

    /** Elements whose content a reader never sees; their tags don't break a line either. */
    private val HIDDEN_ELEMENTS = setOf("head", "title", "style", "script")

    /** Hidden elements whose content Swing's DTD makes literal text, up to their end tag. */
    private val LITERAL_HIDDEN_ELEMENTS = setOf("style", "script")

    /** What a `<head>` can hold in Swing's HTML 3.2 DTD (its content and inclusions). */
    private val HEAD_ELEMENTS = setOf("title", "isindex", "base", "nextid", "meta", "style", "link", "script", "noscript")

    private fun endsLine(name: String): Boolean = HTML.getTag(name)?.let { it.isBlock || it.breaksFlow() } ?: false

    /**
     * Append the text between two tags, [start] to [end] of [s], to [out] with its entity
     * references decoded. A reference never runs past [end]: it consumes name characters, digits,
     * `#` and one `;` or line break, never the `<` that ends the text.
     */
    private fun appendDecodedText(s: String, start: Int, end: Int, out: StringBuilder) {
        var copied = start
        var i = start
        while (i < end) {
            if (s[i] == '&') {
                out.append(s, copied, i)
                i = decodeEntity(s, i, out, TextEntities)
                copied = i
            } else {
                i++
            }
        }
        out.append(s, copied, end)
    }

    /** [text] with every run of the whitespace Swing collapses in content replaced by one space. */
    private fun collapseSpaces(text: CharSequence): String {
        val out = StringBuilder(text.length)
        var space = false
        for (c in text) {
            if (isSpace(c)) {
                space = true
            } else {
                if (space) out.append(' ')
                space = false
                out.append(c)
            }
        }
        if (space) out.append(' ')
        return out.toString()
    }

    /** Tokenize [html] the way Swing does and re-emit it under [policy] (see the invariants above). */
    private fun rewrite(html: String, policy: TagPolicy): String {
        val tokens = SwingTokenizer(html).tokenize()
        val endTags = endTagPositions(tokens, policy.dropWithContent)
        val out = StringBuilder(html.length)
        var i = 0
        while (i < tokens.size) {
            when (val token = tokens[i]) {
                is Text -> out.append(html, token.start, token.end)
                is Ignored -> Unit // comments, declarations and stray markup Swing drops too
                is StartTag -> {
                    if (token.name in policy.dropWithContent) {
                        val close = firstAfter(endTags[token.name], i)
                        if (close >= 0) {
                            i = close + 1
                            continue
                        }
                    }
                    if (token.name !in policy.dropTags) emitStartTag(html, token, policy, out)
                }
                is EndTag -> if (token.name !in policy.dropTags) {
                    if (token.anomalous) out.append("</").append(token.rawName).append('>')
                    else out.append(html, token.start, token.end)
                }
            }
            i++
        }
        return out.toString()
    }

    /**
     * The token indexes of the end tags of [names], per name and ascending, so finding "the next
     * end tag" is a binary search ([firstAfter]) rather than a scan: many unclosed elements must
     * not go quadratic.
     */
    private fun endTagPositions(tokens: List<Token>, names: Set<String>): Map<String, List<Int>> {
        val endTags = HashMap<String, MutableList<Int>>()
        tokens.forEachIndexed { index, token ->
            if (token is EndTag && token.name in names) endTags.getOrPut(token.name) { ArrayList() }.add(index)
        }
        return endTags
    }

    /** The first element of the ascending [positions] greater than [index], or -1. */
    private fun firstAfter(positions: List<Int>?, index: Int): Int {
        if (positions == null) return -1
        var low = 0
        var high = positions.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (positions[mid] <= index) low = mid + 1 else high = mid
        }
        return if (low < positions.size) positions[low] else -1
    }

    private fun emitStartTag(html: String, tag: StartTag, policy: TagPolicy, out: StringBuilder) {
        val edits = tag.attributes.map(policy.attribute)
        if (!tag.anomalous && edits.all { it === AttributeEdit.Keep }) {
            out.append(html, tag.start, tag.end)
            return
        }
        out.append('<').append(tag.rawName)
        for ((attribute, edit) in tag.attributes.zip(edits)) {
            when (edit) {
                AttributeEdit.Remove -> Unit
                AttributeEdit.Keep -> {
                    out.append(' ').append(attribute.rawName)
                    if (attribute.hasValue) {
                        out.append('=')
                        attribute.appendNormalizedValue(out)
                    }
                }
                is AttributeEdit.Value -> out.append(' ').append(attribute.rawName).append('=').append(edit.emitted)
            }
        }
        out.append(if (tag.selfClosing) "/>" else ">")
    }

    // ── Style attributes ─────────────────────────────────────────

    /** Properties whose value Swing (or a later CSS engine) may load as an image or resource. */
    private val RESOURCE_PROPERTIES = setOf(
        "background-image", "list-style-image", "border-image", "border-image-source", "mask", "mask-image",
        "content", "cursor", "behavior", "-moz-binding",
    )

    /**
     * Fragments that mark a declaration as able to reference a resource: `url(`, including
     * entity-hidden and CSS-escaped forms (`\`), bare absolute URLs (Swing's CSS.getURL accepts
     * one without `url()`), image functions, legacy `expression()`, rule blocks and comments.
     */
    private val RESOURCE_MARKERS = listOf("url", "://", "\\", "expression", "{", "}", "image", "/*")

    /** One `property: value;` of a style attribute: its raw span and its entity-decoded text. */
    private class Declaration(val start: Int, val end: Int, val text: String) {
        val property: String get() = text.substringBefore(':').trim().lowercase()
    }

    private fun isColorDeclaration(declaration: Declaration): Boolean = when (declaration.property) {
        "color", "background-color" -> true
        // The shorthand stays when it carries a url(...) image, so stripping colors doesn't also
        // blank a CSS image background (removing images is neutralizeActiveContent's job).
        "background" -> !declaration.text.lowercase().contains("url(")
        else -> false
    }

    private fun isActiveDeclaration(declaration: Declaration): Boolean {
        if (declaration.property in RESOURCE_PROPERTIES) return true
        val lower = declaration.text.lowercase()
        // An unbalanced quote, parenthesis or bracket makes Swing's CSS parser throw inside
        // JTextPane.setText, which cuts the description off.
        return RESOURCE_MARKERS.any { lower.contains(it) } || !isBalanced(declaration.text)
    }

    /** Whether [css] closes its quotes and its `(`/`[` blocks in order, as Swing's CSS parser requires. */
    private fun isBalanced(css: String): Boolean {
        var quote = NO_QUOTE
        val open = StringBuilder() // the open blocks, innermost last
        for (c in css) {
            if (quote != NO_QUOTE) {
                if (c == quote) quote = NO_QUOTE
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(', '[' -> open.append(c)
                ')', ']' -> {
                    if (open.isEmpty() || open.last() != (if (c == ')') '(' else '[')) return false
                    open.setLength(open.length - 1)
                }
            }
        }
        return quote == NO_QUOTE && open.isEmpty()
    }

    /**
     * Drop the declarations [drops] selects from a style attribute, re-emitting the kept ones'
     * raw text unchanged. The result is checked again because dropping a declaration joins its
     * neighbours: if that join reads differently (say, a numeric entity without `;` absorbing
     * the next declaration's digits), the whole style goes rather than something unchecked.
     */
    private fun filterStyle(attribute: Attribute, drops: (Declaration) -> Boolean): AttributeEdit {
        if (!attribute.hasValue) return AttributeEdit.Keep
        val css = attribute.content()
        val declarations = splitDeclarations(css)
        if (declarations.none(drops)) return AttributeEdit.Keep
        val kept = StringBuilder()
        for (declaration in declarations) if (!drops(declaration)) kept.append(css, declaration.start, declaration.end)
        val safe = if (splitDeclarations(kept.toString()).any(drops)) "" else kept.toString()
        val delimiter = attribute.delimiter
        return AttributeEdit.Value("$delimiter$safe$delimiter")
    }

    /**
     * Split a style value into declarations on `;` outside quotes, parentheses and brackets (so
     * the `;` of `&quot;` or of `data:image/png;base64,…` doesn't split one), decoding entities
     * first the way Swing's parser does before its CSS parser ever sees the value.
     */
    private fun splitDeclarations(css: String): List<Declaration> {
        val result = ArrayList<Declaration>(4)
        val decoded = StringBuilder()
        var start = 0
        var quote = NO_QUOTE
        var depth = 0
        var i = 0
        while (i < css.length) {
            val from = decoded.length
            if (css[i] == '&') {
                i = decodeEntity(css, i, decoded, StyleEntities)
            } else {
                decoded.append(css[i])
                i++
            }
            var boundary = false
            for (k in from until decoded.length) {
                val c = decoded[k]
                if (quote != NO_QUOTE) {
                    if (c == quote) quote = NO_QUOTE
                    continue
                }
                when (c) {
                    '"', '\'' -> quote = c
                    '(', '[' -> depth++
                    ')', ']' -> if (depth > 0) depth--
                    ';' -> if (depth == 0) boundary = true
                }
            }
            if (boundary) {
                result += Declaration(start, i, decoded.toString())
                decoded.setLength(0)
                start = i
            }
        }
        if (start < css.length) result += Declaration(start, css.length, decoded.toString())
        return result
    }

    /** What entity references stand for, for [decodeEntity]. */
    private interface EntityTable {
        /** The text of the reference named [name] (`#name` for `&#name`), or null when it names nothing. */
        fun named(name: String): String?

        /** The character a numeric reference to [code], 130–159, stands for. */
        fun cp1252(code: Int): Char
    }

    /**
     * For style values, where only ASCII can change how a declaration splits or what it names.
     * Only `quot`/`amp`/`lt`/`gt` decode to ASCII in Swing's DTD (it has no `apos`), so any other
     * name is kept as written — which also keeps its `;` a declaration boundary, the way Swing's
     * CSS parser sees an unknown reference.
     */
    private object StyleEntities : EntityTable {
        override fun named(name: String): String? = when (name.lowercase()) {
            "quot" -> "\""
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> "\u00A0"
            else -> null
        }

        // 130–159 map to (non-ASCII) Cp1252 characters in Swing; a stand-in keeps them
        // structurally inert here too.
        override fun cp1252(code: Int): Char = '\uFFFD'
    }

    /**
     * For visible text ([visibleTextLines]): exactly what the pane shows. Names come from the
     * entity table of Swing's own HTML 3.2 DTD — the one its parser decodes descriptions with
     * (HTML 4's full set, and `#SPACE`-style names) — read as a table, never by running the
     * parser. Should that DTD not load, a name is kept as written, which is what Swing shows for
     * a name it doesn't know.
     */
    private object TextEntities : EntityTable {
        private val dtd: DTD? by lazy {
            try {
                ParserDelegator() // loads the default DTD Swing parses HTML with
                DTD.getDTD("html32")
            } catch (_: Exception) {
                null
            }
        }

        override fun named(name: String): String? = dtd?.getEntity(name)?.takeIf { it.isGeneral }?.let { String(it.data) }

        override fun cp1252(code: Int): Char = SWING_CP1252[code - 130]
    }

    /**
     * Parser.cp1252Map: what Swing reads numeric references 130–159 as — Windows-1252, except
     * that 141–144 and 157–158 stay themselves.
     */
    private const val SWING_CP1252 =
        "\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u008D\u008E\u008F\u0090" +
            "\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u009D\u009E\u0178"

    /**
     * Decode the entity reference at [amp] into [out] the way Swing's Parser.parseEntityReference
     * does, with names and the cp1252 range resolved by [table], and return the index after it.
     */
    private fun decodeEntity(s: String, amp: Int, out: StringBuilder, table: EntityTable): Int {
        var j = amp + 1
        if (j < s.length && s[j] == '#') {
            j++
            val first = if (j < s.length) s[j] else ' '
            if (first.isAsciiDigit() || first == 'x' || first == 'X') {
                var code = 0 // wraps on overflow, exactly like Swing's int accumulator
                if (first.isAsciiDigit()) {
                    while (j < s.length && s[j].isAsciiDigit()) code = code * 10 + (s[j++] - '0')
                } else {
                    j++
                    while (j < s.length && hexValue(s[j]) >= 0) code = code * 16 + hexValue(s[j++])
                }
                appendNumericReference(code, out, table)
                return skipReferenceTerminator(s, j)
            }
            if (j >= s.length || !isAsciiLetter(s[j].code)) {
                out.append("&#")
                return amp + 2
            }
            // "&#name": Swing looks up the name "#name".
            return appendNamedReference(s, amp, amp + 1, identifierEnd(s, j), out, table)
        }
        if (j >= s.length || !isAsciiLetter(s[j].code)) {
            out.append('&')
            return amp + 1
        }
        return appendNamedReference(s, amp, j, identifierEnd(s, j), out, table)
    }

    /**
     * The named reference at [amp], its name running from [nameStart] to [nameEnd]. Swing looks
     * the name up as written, then lower-cased, and keeps a reference to a name it doesn't know
     * as written (with its `;`, but not a line break that terminated it).
     */
    private fun appendNamedReference(s: String, amp: Int, nameStart: Int, nameEnd: Int, out: StringBuilder, table: EntityTable): Int {
        val name = s.substring(nameStart, nameEnd)
        val text = table.named(name) ?: table.named(name.lowercase())
        if (text != null) {
            out.append(text)
        } else {
            out.append(s, amp, nameEnd)
            if (nameEnd < s.length && s[nameEnd] == ';') out.append(';')
        }
        return skipReferenceTerminator(s, nameEnd)
    }

    /** Swing consumes one `;`, or a line break, after a reference. */
    private fun skipReferenceTerminator(s: String, j: Int): Int = when {
        j >= s.length -> j
        s[j] == ';' || s[j] == '\n' -> j + 1
        s[j] == '\r' -> if (j + 1 < s.length && s[j + 1] == '\n') j + 2 else j + 1
        else -> j
    }

    private fun appendNumericReference(code: Int, out: StringBuilder, table: EntityTable) {
        if (code >= 0xFFFF) {
            try {
                out.append(Character.toChars(code))
            } catch (_: IllegalArgumentException) {
                // Swing drops an out-of-range reference entirely.
            }
        } else {
            out.append(if (code < 130 || code > 159) code.toChar() else table.cp1252(code))
        }
    }

    private fun hexValue(c: Char): Int {
        val lower = Character.toLowerCase(c)
        return when (lower) {
            in '0'..'9' -> lower - '0'
            in 'a'..'f' -> lower - 'a' + 10
            else -> -1
        }
    }

    // ── Tokenizer ────────────────────────────────────────────────

    private const val EOF = -1
    private const val NO_QUOTE = '\u0000'

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun isAsciiLetter(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    private fun isIdentifierPart(c: Char) =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'

    /** End of the identifier (a letter, then letters, digits, `.`, `-`, `_`) starting at [start]. */
    private fun identifierEnd(s: String, start: Int): Int {
        var p = start + 1
        while (p < s.length && isIdentifierPart(s[p])) p++
        return p
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** Element name as Swing keys it: lower-cased, with `<image>` read as `img`. */
    private fun elementName(rawName: String): String = rawName.lowercase().let { if (it == "image") "img" else it }

    private sealed class Token(val start: Int, val end: Int)

    /** Character data, copied verbatim; never contains '<'. */
    private class Text(start: Int, end: Int) : Token(start, end)

    /** Markup Swing consumes without producing a tag: comments, declarations, stray `<`. */
    private class Ignored(start: Int, end: Int) : Token(start, end)

    private class StartTag(
        start: Int,
        end: Int,
        val rawName: String,
        val name: String,
        val attributes: List<Attribute>,
        val selfClosing: Boolean,
        /** Parsed through Swing's error recovery: never copied byte-for-byte. */
        val anomalous: Boolean,
    ) : Token(start, end)

    private class EndTag(start: Int, end: Int, val rawName: String, val name: String, val anomalous: Boolean) :
        Token(start, end)

    private class Attribute(
        private val source: String,
        val rawName: String,
        /** Lower-cased name, the key Swing stores the attribute under. */
        val name: String,
        /** Raw value span, quotes included; -1 for a valueless attribute. */
        private val valueStart: Int,
        private val valueEnd: Int,
        /** The opening quote of a quoted value, or [NO_QUOTE]. */
        private val quote: Char,
        /**
         * A quoted value running to the end of input, or an unquoted one containing quote
         * characters or an entity reference (see [SwingTokenizer.value]): never copied verbatim.
         */
        val irregular: Boolean,
    ) {
        val hasValue: Boolean get() = valueStart >= 0

        /** The delimiter a rewritten value is written with. */
        val delimiter: Char get() = if (quote == NO_QUOTE) '"' else quote

        private val contentStart: Int get() = if (quote == NO_QUOTE) valueStart else valueStart + 1

        /** A terminated quoted value ends before its closing quote. */
        private val contentEnd: Int get() = if (quote != NO_QUOTE && !irregular) valueEnd - 1 else valueEnd

        /** The value as Swing keeps it, entities still encoded (Swing drops quote characters inside an unquoted value). */
        fun content(): String {
            if (!hasValue) return ""
            val raw = source.substring(contentStart, contentEnd)
            return if (quote == NO_QUOTE && irregular) raw.filterNot { it == '"' || it == '\'' } else raw
        }

        /** True when Swing would see a `data:` URI (no entity to decode into something else). */
        fun isInlineDataUri(): Boolean {
            if (!hasValue) return false
            if (quote == NO_QUOTE && irregular) return content().let { it.indexOf('&') < 0 && startsWithData(it, 0, it.length) }
            for (k in contentStart until contentEnd) if (source[k] == '&') return false
            return startsWithData(source, contentStart, contentEnd)
        }

        private fun startsWithData(s: String, from: Int, to: Int): Boolean {
            var p = from
            while (p < to && s[p] <= ' ') p++ // java.net.URL trims the same way
            return to - p >= 5 && s.regionMatches(p, "data:", 0, 5, ignoreCase = true)
        }

        /** Write the value for a re-emitted tag: always quoted and terminated. */
        fun appendNormalizedValue(out: StringBuilder) {
            if (quote != NO_QUOTE && !irregular) out.append(source, valueStart, valueEnd)
            else out.append(delimiter).append(content()).append(delimiter)
        }
    }

    /**
     * A linear-time tokenizer that mirrors javax.swing.text.html.parser.Parser (JDK 17) in
     * non-strict mode: parseContent/parseTag/parseAttributeSpecificationList/
     * parseAttributeValue/parseComment, including where Swing ends a malformed tag, its
     * quoted attribute names, its in-tag `--` comments and its re-parse of an unterminated
     * comment from the comment's first line break. Swing's context-dependent content modes
     * (script and CDATA elements) are not modelled: [ACTIVE_CONTENT_POLICY] drops those tags.
     * Nor is the extent of an entity reference inside an unquoted value: such a value is
     * re-emitted quoted instead (see [value]).
     */
    private class SwingTokenizer(private val s: String) {
        private val n = s.length
        private val tokens = ArrayList<Token>()

        // Memo for commentEnd: tag parsing only moves forward, so a search result can be
        // reused and repeated unterminated comments can't make the scan quadratic.
        private var memoFrom = Int.MAX_VALUE
        private var memoAt = -1
        private var memoEnd = -1

        fun tokenize(): List<Token> {
            var i = 0
            while (i < n) {
                val lt = s.indexOf('<', i)
                if (lt < 0) {
                    tokens += Text(i, n)
                    break
                }
                if (lt > i) tokens += Text(i, lt)
                i = markup(lt)
            }
            return tokens
        }

        private fun at(i: Int): Int = if (i < n) s[i].code else EOF

        private fun skipSpace(from: Int): Int {
            var p = from
            while (p < n && isSpace(s[p])) p++
            return p
        }

        private fun ignore(start: Int, end: Int): Int {
            tokens += Ignored(start, end)
            return end
        }

        /** Every '<' goes through here (Parser.parseTag); returns where content resumes. */
        private fun markup(lt: Int): Int {
            val c = at(lt + 1)
            return when {
                c == '!'.code -> bang(lt)
                c == '/'.code -> endTag(lt)
                isAsciiLetter(c) -> startTag(lt)
                // "<>" is an SGML empty start tag of the most recent element: nothing to keep.
                c == '>'.code -> ignore(lt, lt + 2)
                // Any other character (or EOF): Swing drops the '<' and reads on as content.
                else -> ignore(lt, lt + 1)
            }
        }

        private fun bang(lt: Int): Int {
            if (at(lt + 2) == '-'.code) {
                // Non-strict: "<!-" opens a comment; an optional second '-' is skipped.
                val contentStart = if (at(lt + 3) == '-'.code) lt + 4 else lt + 3
                return ignore(lt, afterComment(contentStart))
            }
            // Any other declaration (DOCTYPE, CDATA, ...) runs through the next '>'.
            val gt = s.indexOf('>', lt + 2)
            return ignore(lt, if (gt < 0) n else gt + 1)
        }

        /**
         * Where parsing resumes after a comment whose content starts at [contentStart]: after the
         * first `-->`/`--!>`; unterminated, after its first line break (Swing's
         * handleEOFInComment re-parses the rest as HTML), or at EOF when it has none.
         */
        private fun afterComment(contentStart: Int): Int {
            val end = commentEnd(contentStart)
            if (end >= 0) return end
            var p = contentStart
            while (p < n && s[p] != '\n' && s[p] != '\r') p++
            return when {
                p >= n -> n
                s[p] == '\r' && at(p + 1) == '\n'.code -> p + 2
                else -> p + 1
            }
        }

        private fun commentEnd(from: Int): Int {
            if (from >= memoFrom && (memoEnd < 0 || from <= memoAt)) return memoEnd
            var k = s.indexOf("--", from)
            var end = -1
            while (k >= 0) {
                val c = at(k + 2)
                if (c == '>'.code) {
                    end = k + 3
                    break
                }
                if (c == '!'.code && at(k + 3) == '>'.code) {
                    end = k + 4
                    break
                }
                k = s.indexOf("--", k + 1)
            }
            memoFrom = from
            memoAt = k
            memoEnd = end
            return end
        }

        private fun endTag(lt: Int): Int {
            val c = at(lt + 2)
            // "</>" is an empty end tag; "</<", "</ ", "</" + EOF: Swing drops the "</".
            if (c == '>'.code) return ignore(lt, lt + 3)
            if (!isAsciiLetter(c)) return ignore(lt, lt + 2)
            val nameEnd = identifierEnd(s, lt + 2)
            val rawName = s.substring(lt + 2, nameEnd)
            val p = skipSpace(nameEnd)
            val end: Int
            val anomalous: Boolean
            when (at(p)) {
                '>'.code -> {
                    end = p + 1
                    anomalous = false
                }
                '<'.code, EOF -> {
                    end = p
                    anomalous = true
                }
                else -> {
                    // Swing skips to a line break (kept as content) or through a '>'.
                    var q = p
                    while (q < n && s[q] != '\n' && s[q] != '>') q++
                    end = if (q < n && s[q] == '>') q + 1 else q
                    anomalous = true
                }
            }
            tokens += EndTag(lt, end, rawName, elementName(rawName), anomalous)
            return end
        }

        private fun startTag(lt: Int): Int {
            val nameEnd = identifierEnd(s, lt + 1)
            val rawName = s.substring(lt + 1, nameEnd)
            val name = elementName(rawName)
            val attributes = ArrayList<Attribute>(2)
            var anomalous = false
            var p = nameEnd
            var inTagCommentEnd = -1
            loop@ while (true) {
                p = skipSpace(p)
                val c = at(p)
                when {
                    c == EOF || c == '/'.code || c == '>'.code || c == '<'.code -> break@loop
                    c == '-'.code -> {
                        anomalous = true
                        if (at(p + 1) == '-'.code) {
                            // An in-tag comment; the '>' of its terminator ends the tag.
                            inTagCommentEnd = afterComment(p + 2)
                            break@loop
                        }
                        // A lone '-': Swing discards the character after it too, whatever it is.
                        p = minOf(p + 2, n)
                    }
                    isAsciiLetter(c) -> p = attribute(p, attributes, quotedName = false)
                    c == '"'.code -> {
                        // A quoted attribute name; without one, Swing skips a single character.
                        anomalous = true
                        val q = skipSpace(p + 1)
                        p = if (isAsciiLetter(at(q))) attribute(q, attributes, quotedName = true) else minOf(q + 1, n)
                    }
                    c == '='.code -> {
                        anomalous = true
                        val q = skipSpace(p + 1)
                        if (attributes.isEmpty()) {
                            // "<x =v>": an attribute named after the element.
                            p = value(q, name, name, attributes)
                        } else {
                            // The value is parsed and discarded, and the attribute list ends.
                            p = value(q, name, name, null)
                            break@loop
                        }
                    }
                    else -> {
                        // ',' and any other stray character is skipped.
                        anomalous = true
                        p++
                    }
                }
            }
            var selfClosing = false
            val end: Int
            when {
                inTagCommentEnd >= 0 -> end = inTagCommentEnd
                at(p) == '/'.code -> if (at(p + 1) == '>'.code) {
                    end = p + 2
                    selfClosing = true
                } else {
                    // "<b/x": Swing consumes the '/' (an SGML null end tag) and reads on as content.
                    end = p + 1
                    anomalous = true
                }
                at(p) == '>'.code -> end = p + 1
                else -> {
                    // '<' starts the next tag; EOF; or content after a discarded value.
                    end = p
                    anomalous = true
                }
            }
            if (attributes.any { it.irregular }) anomalous = true
            tokens += StartTag(lt, end, rawName, name, attributes, selfClosing, anomalous)
            return end
        }

        /** Parse `name[=value]` from the name at [start]; returns where the attribute list continues. */
        private fun attribute(start: Int, into: MutableList<Attribute>, quotedName: Boolean): Int {
            val nameEnd = identifierEnd(s, start)
            val rawName = s.substring(start, nameEnd)
            var q = nameEnd
            if (quotedName && at(q) == '"'.code) q++
            q = skipSpace(q)
            if (at(q) != '='.code) {
                into += Attribute(s, rawName, rawName.lowercase(), -1, -1, NO_QUOTE, irregular = false)
                return q
            }
            return value(skipSpace(q + 1), rawName, rawName.lowercase(), into)
        }

        /**
         * Parser.parseAttributeValue from [v]; adds the attribute to [into] unless it's null.
         *
         * Everywhere else Swing decides where a value ends one character at a time, exactly as
         * this loop does. The exception is an entity reference: Swing's parseEntityReference
         * consumes one `;` or one line break after it as its terminator, so in an unquoted value
         * `alt=x&amp⏎title="…"` Swing's value runs on past the line break where this loop ends
         * it, and reads different attributes from the same bytes. Rather than model that extent
         * here, an unquoted value holding a `&` counts as irregular: its tag is re-emitted with
         * the value quoted, and in a quoted value a reference can't reach the closing quote (it
         * consumes only name characters, digits, `#`, `x` and that one terminator), so Swing
         * reads the re-emitted tag exactly as tokenized. The cost is that such a tag's bytes
         * change (`href=/q?a=1&b=2` comes back as `href="/q?a=1&b=2"`, which Swing reads the
         * same way).
         */
        private fun value(v: Int, rawName: String, name: String, into: MutableList<Attribute>?): Int {
            val c = at(v)
            if (c == '"'.code || c == '\''.code) {
                val close = s.indexOf(c.toChar(), v + 1)
                val end = if (close < 0) n else close + 1
                into?.add(Attribute(s, rawName, name, v, end, c.toChar(), irregular = close < 0))
                return end
            }
            // Unquoted: ends at whitespace (consumed), '<' or '>'. A quote character is dropped
            // by Swing, and ends the value when a space follows it.
            var r = v
            var resume = -1
            var irregular = false
            while (r < n) {
                val ch = s[r]
                if (ch == ' ' || ch == '\t' || ch == '\n') {
                    resume = r + 1
                    break
                }
                if (ch == '\r') {
                    resume = if (at(r + 1) == '\n'.code) r + 2 else r + 1
                    break
                }
                if (ch == '<' || ch == '>') break
                if (ch == '&') irregular = true
                if (ch == '"' || ch == '\'') {
                    irregular = true
                    if (at(r + 1) == ' '.code) {
                        r++
                        break
                    }
                }
                r++
            }
            into?.add(Attribute(s, rawName, name, v, r, NO_QUOTE, irregular))
            return if (resume < 0) r else resume
        }
    }
}
