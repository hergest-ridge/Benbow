package app.benbow.core.content

/** Plain text for search, previews and notifications (content-format.md#derived-forms). */
fun Document.toPlainText(includeSpoilers: Boolean = true): String = blocks.joinToString("\n") { block ->
    when (block) {
        is TextBlock -> block.children
            .filter { includeSpoilers || "spoiler" !in it.marks }
            .joinToString("") { it.text }
            .replace(SOFT_BREAK, '\n')
        is CodeBlock -> block.code
        is AttachmentBlock -> block.caption.orEmpty()
        is DividerBlock, is UnknownBlock -> ""
    }
}

enum class MarkdownFlavor {
    /** Obsidian-friendly: HTML spans for color/size/underline/spoiler, `==highlight==`, callouts. */
    Rich,

    /** Only what CommonMark + GFM can express. */
    Clean,
}

/**
 * Markdown export (lossy by design). [colorHex] resolves palette tokens for the rich flavor,
 * [attachmentUrl] and [entryUrl] turn references into links relative to the export location.
 */
class MarkdownExporter(
    private val flavor: MarkdownFlavor = MarkdownFlavor.Rich,
    private val colorHex: (token: String) -> String? = { null },
    private val attachmentUrl: (ref: String) -> String = { "benbow:attachment/$it" },
    private val entryUrl: (entryId: String, blockKey: String?) -> String = { id, key -> "benbow:entry/$id" + (key?.let { "#$it" } ?: "") },
) {
    fun export(document: Document): String {
        val out = StringBuilder()
        var previous: Block? = null
        for (block in document.blocks) {
            if (previous != null) out.append(separator(previous, block))
            out.append(blockMarkdown(block, previous))
            previous = block
        }
        return out.toString()
    }

    private fun rich() = flavor == MarkdownFlavor.Rich

    private fun separator(previous: Block, block: Block): String {
        val prevCallout = (previous as? TextBlock)?.callout?.key ?: (previous as? CodeBlock)?.callout?.key
        val callout = (block as? TextBlock)?.callout?.key ?: (block as? CodeBlock)?.callout?.key
        if (callout != null && callout == prevCallout) return "\n>\n"
        val bothListItems = (previous as? TextBlock)?.listItem != null && (block as? TextBlock)?.listItem != null
        return if (bothListItems) "\n" else "\n\n"
    }

    private fun blockMarkdown(block: Block, previous: Block?): String = when (block) {
        is TextBlock -> textBlock(block, previous)
        is CodeBlock -> calloutWrap(block.callout, previous, codeFence(block))
        is DividerBlock -> "---"
        is AttachmentBlock -> "![${escape(block.caption.orEmpty())}](${attachmentUrl(block.ref)})"
        is UnknownBlock -> if (rich()) "<!-- benbow:unknown ${block.raw.toString().replace("--", "\\u002d\\u002d")} -->" else ""
    }

    private fun textBlock(block: TextBlock, previous: Block?): String {
        var line = inline(block)
        if (line.isNotEmpty()) line = escapeLineStart(line)
        line = when (block.style) {
            "h1" -> "# $line"
            "h2" -> "## $line"
            "h3" -> "### $line"
            "blockquote" -> line.lines().joinToString("\n") { "> $it" }
            else -> line
        }
        block.listItem?.let { item ->
            val indent = "  ".repeat((block.level ?: 1) - 1)
            val marker = when (item) {
                "number" -> "1. "
                "check" -> if (block.checked == true) "- [x] " else "- [ ] "
                else -> "- "
            }
            line = indent + marker + line
        }
        if (rich() && (block.align == "center" || block.align == "end")) {
            val align = if (block.align == "center") "center" else "right"
            line = "<div align=\"$align\">\n\n$line\n\n</div>"
        }
        return calloutWrap(block.callout, previous, line)
    }

    /** Callout blocks become `> [!note] icon` quotes (rich) or plain quotes (clean); the header only on the first block. */
    private fun calloutWrap(callout: CalloutRef?, previous: Block?, body: String): String {
        if (callout == null) return body
        val prevKey = (previous as? TextBlock)?.callout?.key ?: (previous as? CodeBlock)?.callout?.key
        val quoted = body.lines().joinToString("\n") { "> $it" }
        return if (prevKey == callout.key || !rich()) quoted else "> [!note] ${callout.icon}\n$quoted"
    }

    private fun codeFence(block: CodeBlock): String {
        var fence = "```"
        while (block.code.contains(fence)) fence += "`"
        return "$fence${block.language.orEmpty()}\n${block.code}\n$fence"
    }

    // ---- inline ----

    private class Marker(val id: String, val open: String, val close: String)

    /**
     * Opens/closes markers only where marks change. Edge whitespace stays outside markers, because
     * CommonMark ignores `*`, `**`, `~~` next to a space.
     */
    private fun inline(block: TextBlock): String {
        val defs = block.markDefs.associateBy { it.key }
        val out = StringBuilder()
        val open = ArrayList<Marker>()
        var pendingTrail = ""
        for (span in block.children) {
            if (span.text.isEmpty()) continue
            val code = "code" in span.marks
            val body = if (code) span.text else escape(span.text).replace(SOFT_BREAK.toString(), "  \n")
            val core = if (code) body else body.trim(' ')
            val lead = if (code) "" else body.takeWhile { it == ' ' }
            val trail = if (code || core.isEmpty()) "" else body.takeLastWhile { it == ' ' }
            if (core.isEmpty()) {
                pendingTrail += lead
                continue
            }
            val target = markers(span, defs)
            val keep = open.zip(target).takeWhile { (a, b) -> a.id == b.id }.size
            for (m in open.subList(keep, open.size).asReversed()) out.append(m.close)
            while (open.size > keep) open.removeAt(open.size - 1)
            out.append(pendingTrail).append(lead)
            for (m in target.subList(keep, target.size)) {
                out.append(m.open)
                open.add(m)
            }
            out.append(core)
            pendingTrail = trail
        }
        for (m in open.asReversed()) out.append(m.close)
        return out.append(pendingTrail).toString()
    }

    /** Markers in a fixed outer-to-inner order so shared prefixes stay open across spans. */
    private fun markers(span: Span, defs: Map<String, MarkDef>): List<Marker> {
        val result = ArrayList<Marker>()
        val annotations = span.marks.mapNotNull { defs[it] }
        annotations.filterIsInstance<MarkDef.Link>().forEach { result.add(Marker("link:${it.href}", "[", "](${it.href})")) }
        annotations.filterIsInstance<MarkDef.EntryLink>().forEach {
            result.add(Marker("entry:${it.entryId}:${it.blockKey}", "[", "](${entryUrl(it.entryId, it.blockKey)})"))
        }
        if (rich()) {
            annotations.filterIsInstance<MarkDef.Color>().forEach { def ->
                colorHex(def.token)?.let { result.add(Marker("color:$it", "<span style=\"color:$it\">", "</span>")) }
            }
            annotations.filterIsInstance<MarkDef.Size>().forEach {
                val em = when (it.step) {
                    "small" -> "0.85em"
                    "large" -> "1.25em"
                    else -> "1.5em"
                }
                result.add(Marker("size:${it.step}", "<span style=\"font-size:$em\">", "</span>"))
            }
            if ("spoiler" in span.marks) result.add(Marker("spoiler", "<span class=\"spoiler\">", "</span>"))
            if (annotations.any { it is MarkDef.Highlight }) result.add(Marker("highlight", "==", "=="))
            if ("underline" in span.marks) result.add(Marker("underline", "<u>", "</u>"))
        }
        if ("strong" in span.marks) result.add(Marker("strong", "**", "**"))
        if ("em" in span.marks) result.add(Marker("em", "*", "*"))
        if ("strike-through" in span.marks) result.add(Marker("strike", "~~", "~~"))
        if ("code" in span.marks) {
            val fence = if (span.text.contains('`')) "``" else "`"
            val pad = if (fence.length > 1) " " else ""
            result.add(Marker("code", fence + pad, pad + fence))
        }
        return result
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            if (c in "\\`*_[]<>~=|") append('\\')
            append(c)
        }
    }

    /** Characters that would start a block construct at the beginning of a line. */
    private fun escapeLineStart(line: String): String = when {
        line.startsWith("#") || line.startsWith("-") || line.startsWith("+") -> "\\$line"
        Regex("""^\d+[.)]""").containsMatchIn(line) -> line.replaceFirst(Regex("""^(\d+)([.)])"""), "$1\\\\$2")
        else -> line
    }
}
