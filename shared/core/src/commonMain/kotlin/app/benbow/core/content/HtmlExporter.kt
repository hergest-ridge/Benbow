package app.benbow.core.content

import app.benbow.core.text.Links
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

/**
 * Benbow Document → HTML (content-format.md#derived-forms): every block style, list, callout and mark has a tag or
 * a class, so nothing is lost; spoilers keep their text under a class. Colors come from the app's palette as CSS
 * values ([color] for text, [highlight] and [tint] for backgrounds), so the core needs no theme.
 */
class HtmlExporter(
    private val color: (token: String) -> String? = { null },
    private val highlight: (token: String) -> String? = { null },
    private val tint: (token: String) -> String? = { null },
    private val attachmentUrl: (ref: String) -> String = { "benbow:attachment/$it" },
    private val entryUrl: (entryId: String, blockKey: String?) -> String = { id, key -> "benbow:entry/$id" + (key?.let { "#$it" } ?: "") },
    /** Where a card's picture (a blob's SHA-256) is, or null to leave it out. */
    private val imageUrl: (sha256: String) -> String? = { null },
) {
    fun export(document: Document): String {
        val out = StringBuilder()
        val lists = ArrayList<String>() // open list tags, outermost first
        var callout: CalloutRef? = null
        fun closeLists(depth: Int) {
            while (lists.size > depth) out.append("</li></").append(lists.removeAt(lists.lastIndex)).append(">\n")
        }
        for (block in document.blocks) {
            val blockCallout = (block as? TextBlock)?.callout ?: (block as? CodeBlock)?.callout
            if (blockCallout?.key != callout?.key) {
                closeLists(0)
                if (callout != null) out.append("</div>\n")
                if (blockCallout != null) {
                    val style = tint(blockCallout.tone)?.let { " style=\"background:$it\"" }.orEmpty()
                    out.append("<div class=\"callout tone-${attr(blockCallout.tone)}\"$style><span class=\"callout-icon\">")
                        .append(escape(blockCallout.icon)).append("</span>\n")
                }
                callout = blockCallout
            }
            val item = (block as? TextBlock)?.listItem
            if (block is TextBlock && item != null) {
                val depth = (block.level ?: 1).coerceAtLeast(1)
                val tag = if (item == "number") "ol" else "ul"
                closeLists(depth)
                if (lists.size == depth && lists.last() != tag) closeLists(depth - 1)
                if (lists.size == depth) {
                    out.append("</li>\n<li")
                } else {
                    // A jump of more than one level gets empty items in between, so the markup stays nested and valid.
                    while (lists.size < depth) {
                        lists.add(tag)
                        out.append("<").append(tag).append(if (item == "check") " class=\"checklist\"" else "").append(">\n<li")
                        if (lists.size < depth) out.append(">")
                    }
                }
                out.append(alignAttr(block)).append(">")
                if (item ==
                    "check"
                ) {
                    out.append("<input type=\"checkbox\" disabled").append(
                        if (block.checked ==
                            true
                        ) {
                            " checked"
                        } else {
                            ""
                        },
                    ).append("> ")
                }
                out.append(inline(block))
                continue
            }
            closeLists(0)
            out.append(blockHtml(block)).append("\n")
        }
        closeLists(0)
        if (callout != null) out.append("</div>\n")
        return out.toString()
    }

    private fun blockHtml(block: Block): String = when (block) {
        is TextBlock -> {
            val tag = when (block.style) {
                "h1", "h2", "h3" -> block.style
                else -> "p"
            }
            val inner = "<$tag${alignAttr(block)}>${inline(block)}</$tag>"
            if (block.style == "blockquote") "<blockquote>$inner</blockquote>" else inner
        }

        is CodeBlock -> "<pre><code" + (block.language?.let { " class=\"language-${attr(it)}\"" } ?: "") + ">" + escape(block.code) +
            "</code></pre>"

        is DividerBlock -> "<hr class=\"divider-${attr(block.variant ?: "line")}\">"

        is AttachmentBlock -> "<figure><img src=\"${attr(attachmentUrl(block.ref))}\" alt=\"${attr(block.caption.orEmpty())}\">" +
            (block.caption?.let { "<figcaption>${escape(it)}</figcaption>" } ?: "") + "</figure>"

        is LinkCardBlock -> linkCard(block)

        is UnknownBlock -> "<!-- benbow:unknown ${block.raw.toString().replace("--", "\\u002d\\u002d")} -->"
    }

    /** A card as a link around the page's picture, title, description and site (the picture only when [imageUrl] knows it). */
    private fun linkCard(block: LinkCardBlock): String = buildString {
        val title = block.title?.takeIf { it.isNotBlank() } ?: Links.display(block.href)
        append("<a class=\"link-card\" href=\"").append(attr(block.href)).append("\">")
        block.image?.let(imageUrl)?.let { append("<img src=\"").append(attr(it)).append("\" alt=\"\">") }
        append("<span class=\"link-site\">").append(escape(block.site ?: Links.host(block.href))).append("</span>")
        append("<strong>").append(escape(title)).append("</strong>")
        block.description?.let { append("<span class=\"link-about\">").append(escape(it)).append("</span>") }
        append("</a>")
    }

    private fun alignAttr(block: TextBlock): String = when (block.align) {
        "center" -> " style=\"text-align:center\""
        "end" -> " style=\"text-align:right\""
        else -> ""
    }

    /** Each span gets its own complete tags (outer to inner), so the markup is always well formed. */
    private fun inline(block: TextBlock): String {
        val defs = block.markDefs.associateBy { it.key }
        return block.children.joinToString("") { span ->
            val text = escape(span.text).replace(SOFT_BREAK.toString(), "<br>")
            if (text.isEmpty()) return@joinToString ""
            val tags = tags(span, defs)
            tags.joinToString("") { it.first } + text + tags.asReversed().joinToString("") { it.second }
        }
    }

    private fun tags(span: Span, defs: Map<String, MarkDef>): List<Pair<String, String>> = buildList {
        val annotations = span.marks.mapNotNull { defs[it] }
        annotations.filterIsInstance<MarkDef.Link>().forEach { add("<a href=\"${attr(it.href)}\">" to "</a>") }
        annotations.filterIsInstance<MarkDef.EntryLink>().forEach {
            add("<a href=\"${attr(entryUrl(it.entryId, it.blockKey))}\">" to "</a>")
        }
        annotations.filterIsInstance<MarkDef.Color>().forEach { def ->
            val css = color(def.token)?.let { " style=\"color:$it\"" }.orEmpty()
            add("<span class=\"color-${attr(def.token)}\"$css>" to "</span>")
        }
        annotations.filterIsInstance<MarkDef.Highlight>().forEach { def ->
            val css = highlight(def.token)?.let { " style=\"background:$it\"" }.orEmpty()
            add("<mark class=\"highlight-${attr(def.token)}\"$css>" to "</mark>")
        }
        annotations.filterIsInstance<MarkDef.Size>().forEach { add("<span class=\"size-${attr(it.step)}\">" to "</span>") }
        if ("spoiler" in span.marks) add("<span class=\"spoiler\">" to "</span>")
        if ("underline" in span.marks) add("<u>" to "</u>")
        if ("strong" in span.marks) add("<strong>" to "</strong>")
        if ("em" in span.marks) add("<em>" to "</em>")
        if ("strike-through" in span.marks) add("<s>" to "</s>")
        if ("code" in span.marks) add("<code>" to "</code>")
    }

    companion object {
        fun escape(text: String): String = buildString {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }

        private fun attr(text: String) = escape(text)

        /** Print-friendly styles for the classes above (light, a reading serif, A4 margins). */
        const val CSS = """
@page { margin: 18mm 16mm; }
@media screen { body { padding: 1.5em 1.2em; } }
body { font-family: "Literata", Georgia, "Times New Roman", serif; font-size: 12pt; line-height: 1.55; color: #1d1b18; max-width: 42em; margin: 0 auto; }
h1, h2, h3 { font-family: system-ui, "Segoe UI", Roboto, sans-serif; line-height: 1.25; margin: 1.2em 0 0.4em; }
h1 { font-size: 1.6em; } h2 { font-size: 1.3em; } h3 { font-size: 1.1em; }
p { margin: 0.4em 0; }
blockquote { margin: 0.6em 0; padding-left: 1em; border-left: 3px solid #c9bfb2; color: #4a453f; }
blockquote p { margin: 0; }
ul, ol { margin: 0.3em 0; padding-left: 1.6em; }
ul.checklist { list-style: none; padding-left: 0.4em; }
.callout { border-radius: 10px; padding: 0.6em 0.9em; margin: 0.6em 0; background: #f3efe9; }
.callout-icon { float: left; margin-right: 0.5em; }
pre { background: #f3efe9; border-radius: 8px; padding: 0.7em; white-space: pre-wrap; font-size: 0.9em; }
code { font-family: "JetBrains Mono", Consolas, monospace; }
hr { border: none; border-top: 1px solid #c9bfb2; margin: 1.2em 0; }
hr.divider-dots { border-top: 2px dotted #c9bfb2; }
hr.divider-wave { border-top: 2px dashed #c9bfb2; }
mark { background: #fbe9a6; padding: 0 0.1em; }
.size-small { font-size: 0.85em; } .size-large { font-size: 1.25em; } .size-xlarge { font-size: 1.5em; }
.spoiler { background: #1d1b18; color: #1d1b18; border-radius: 3px; }
img { max-width: 100%; border-radius: 8px; }
figure { margin: 0.8em 0; break-inside: avoid; }
figcaption { font-size: 0.85em; color: #6b655d; }
a { color: #2b6c99; }
a.link-card { display: block; text-decoration: none; color: inherit; border: 1px solid #e5ded4; border-left: 3px solid #2b6c99; padding: 0.5em 0.8em; margin: 0.6em 0; break-inside: avoid; }
a.link-card img { display: block; max-height: 12em; margin-bottom: 0.4em; }
a.link-card strong { display: block; font-family: system-ui, "Segoe UI", Roboto, sans-serif; }
.link-site { display: block; color: #2b6c99; font-family: system-ui, "Segoe UI", Roboto, sans-serif; font-size: 0.8em; }
.link-about { display: block; color: #4a453f; font-size: 0.9em; }
.meta { color: #6b655d; font-family: system-ui, "Segoe UI", Roboto, sans-serif; font-size: 0.85em; margin: 0.2em 0; }
.tags span { display: inline-block; background: #efe9df; border-radius: 6px; padding: 0.1em 0.55em; margin: 0 0.3em 0.3em 0; font-family: system-ui, "Segoe UI", Roboto, sans-serif; font-size: 0.85em; }
.file { color: #4a453f; }
.comment { border-top: 1px solid #e5ded4; padding: 0.4em 0; break-inside: avoid; }
"""

        /**
         * A complete page: [head] goes above the document (date, tags), [tail] below it (pictures, comments); [style]
         * after the page's own (a note's font, [noteFont]).
         */
        fun page(title: String, head: String, body: String, tail: String, style: String = ""): String = buildString {
            append("<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>").append(escape(title)).append("</title><style>").append(CSS).append(style).append("</style></head><body>\n")
            append(head).append(body).append(tail)
            append("</body></html>\n")
        }

        /**
         * A note's own font for its words and headings on paper (ADR-047, ADR-052), its [files] (weight to bytes) carried
         * in the page so it prints the same anywhere, set [scale] times the page's size. It holds the document wrapped in
         * `<div class="note">`; the date, tags and comments keep the page's fonts.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun noteFont(files: List<Pair<Int, ByteArray>>, scale: Float): String = buildString {
            for ((weight, bytes) in files) {
                append("@font-face { font-family: \"Benbow Note\"; font-weight: $weight; src: url(data:font/ttf;base64,")
                append(Base64.encode(bytes)).append("); }\n")
            }
            append(".note, .note h1, .note h2, .note h3 { font-family: \"Benbow Note\", Georgia, serif; }\n")
            append(".note { font-size: ${(scale * 1000).roundToInt() / 1000.0}em; }\n")
        }
    }
}
