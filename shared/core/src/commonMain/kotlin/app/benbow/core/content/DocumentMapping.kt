package app.benbow.core.content

import app.benbow.core.text.ATOM_CHAR
import app.benbow.core.text.Align
import app.benbow.core.text.Atom
import app.benbow.core.text.BlockKeys
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.Callout
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.MarkSpan
import app.benbow.core.text.Paragraph
import app.benbow.core.text.RichText
import app.benbow.core.text.SizeStep
import app.benbow.core.text.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Editor model → canonical document. Consecutive code paragraphs with the same callout form one code block. */
fun RichText.toDocument(): Document {
    val blocks = ArrayList<Block>()
    var i = 0
    while (i < paragraphs.size) {
        val p = paragraphs[i]
        val atom = p.atom
        if (atom != null) {
            blocks.add(atom.toBlock(p.key))
            i++
        } else if (p.style == BlockStyle.Code) {
            var j = i
            while (j + 1 < paragraphs.size &&
                paragraphs[j + 1].let { it.style == BlockStyle.Code && it.atom == null && it.callout == p.callout }
            ) {
                j++
            }
            blocks.add(CodeBlock(p.key, (i..j).joinToString("\n") { paragraphText(it) }, p.language, p.callout?.toRef()))
            i = j + 1
        } else {
            blocks.add(textBlock(i))
            i++
        }
    }
    return DocumentCodec.canonicalize(Document(blocks))
}

/** Document → editor model. Extra code-block lines get fresh paragraph keys (only the block key is stored). */
fun Document.toRichText(keys: BlockKeys = BlockKeys()): RichText {
    if (blocks.isEmpty()) return RichText.empty(keys)
    val text = StringBuilder()
    val paragraphs = ArrayList<Paragraph>()
    val marks = ArrayList<MarkSpan>()
    fun start(paragraph: Paragraph) {
        if (paragraphs.isNotEmpty()) text.append('\n')
        paragraphs.add(paragraph)
    }
    for (block in blocks) {
        when (block) {
            is TextBlock -> {
                start(block.toParagraph())
                val defs = block.markDefs.associateBy { it.key }
                for (span in block.children) {
                    val from = text.length
                    text.append(span.text.replace('\n', SOFT_BREAK))
                    span.marks.forEach { name -> markFor(name, defs)?.let { marks.add(MarkSpan(it, from, text.length)) } }
                }
            }

            is CodeBlock -> block.code.split('\n').forEachIndexed { line, code ->
                start(
                    Paragraph(
                        key = if (line == 0) block.key else keys.next(),
                        style = BlockStyle.Code,
                        callout = block.callout?.toCallout(),
                        language = if (line == 0) block.language else null,
                    ),
                )
                text.append(code)
            }

            is DividerBlock -> atomParagraph(block.key, Atom.Divider(block.variant), ::start, text)

            is AttachmentBlock -> atomParagraph(block.key, Atom.Attachment(block.ref, block.width, block.caption), ::start, text)

            is AttachmentRowBlock -> atomParagraph(block.key, Atom.AttachmentRow(block.refs), ::start, text)

            is LinkCardBlock -> atomParagraph(
                block.key,
                Atom.LinkCard(block.href, block.title, block.description, block.site, block.image, block.view == "large", block.fetched),
                ::start,
                text,
            )

            is UnknownBlock -> atomParagraph(block.key, Atom.Unknown(block.raw.toString()), ::start, text)
        }
    }
    return RichText(text.toString(), paragraphs, normalize(marks))
}

private fun atomParagraph(key: String, atom: Atom, start: (Paragraph) -> Unit, text: StringBuilder) {
    start(Paragraph(key, atom = atom))
    text.append(ATOM_CHAR)
}

private fun Atom.toBlock(key: String): Block = when (this) {
    is Atom.Divider -> DividerBlock(key, variant)
    is Atom.Attachment -> AttachmentBlock(key, ref, width, caption)
    is Atom.AttachmentRow -> AttachmentRowBlock(key, refs)
    is Atom.LinkCard -> LinkCardBlock(key, href, title, description, site, image, if (large) "large" else null, fetched)
    is Atom.Unknown -> UnknownBlock(key, Json.parseToJsonElement(json).jsonObject)
}

private fun RichText.textBlock(index: Int): TextBlock {
    val p = paragraphs[index]
    val start = paragraphStarts[index]
    val end = paragraphEnd(index)
    val local = marks.filter { it.start < end && it.end > start }
        .map { MarkSpan(it.mark, maxOf(it.start, start) - start, minOf(it.end, end) - start) }
    val paragraphText = text.substring(start, end)
    val bounds = (listOf(0, paragraphText.length) + local.flatMap { listOf(it.start, it.end) }).distinct().sorted()
    val defKeys = LinkedHashMap<Mark, String>()
    val spans = ArrayList<Span>()
    for (k in 0 until bounds.size - 1) {
        val a = bounds[k]
        val b = bounds[k + 1]
        val active = local.filter { it.start <= a && b <= it.end }.map { it.mark }
        spans.add(Span(paragraphText.substring(a, b), active.map { decoratorName(it) ?: defKeys.getOrPut(it) { "m${defKeys.size + 1}" } }))
    }
    return TextBlock(
        key = p.key,
        style = when (p.style) {
            BlockStyle.H1 -> "h1"
            BlockStyle.H2 -> "h2"
            BlockStyle.H3 -> "h3"
            BlockStyle.Quote -> "blockquote"
            BlockStyle.Normal, BlockStyle.Code -> STYLE_NORMAL
        },
        listItem = p.list?.let {
            when (it) {
                ListKind.Bullet -> "bullet"
                ListKind.Number -> "number"
                ListKind.Check -> "check"
            }
        },
        level = p.level.takeIf { p.list != null },
        checked = p.checked.takeIf { p.list == ListKind.Check },
        align = when (p.align) {
            Align.Start -> null
            Align.Center -> "center"
            Align.End -> "end"
        },
        callout = p.callout?.toRef(),
        children = spans.ifEmpty { listOf(Span("")) },
        markDefs = defKeys.map { (mark, key) -> mark.toDef(key) },
    )
}

private fun TextBlock.toParagraph() = Paragraph(
    key = key,
    style = when (style) {
        "h1" -> BlockStyle.H1
        "h2" -> BlockStyle.H2
        "h3" -> BlockStyle.H3
        "blockquote" -> BlockStyle.Quote
        else -> BlockStyle.Normal
    },
    list = when (listItem) {
        "bullet" -> ListKind.Bullet
        "number" -> ListKind.Number
        "check" -> ListKind.Check
        else -> null
    },
    level = level ?: 1,
    checked = checked ?: false,
    align = when (align) {
        "center" -> Align.Center
        "end" -> Align.End
        else -> Align.Start
    },
    callout = callout?.toCallout(),
)

private fun decoratorName(mark: Mark): String? = when (mark) {
    Mark.Bold -> "strong"
    Mark.Italic -> "em"
    Mark.Underline -> "underline"
    Mark.Strike -> "strike-through"
    Mark.Code -> "code"
    Mark.Spoiler -> "spoiler"
    else -> null
}

private fun markFor(name: String, defs: Map<String, MarkDef>): Mark? = when (name) {
    "strong" -> Mark.Bold

    "em" -> Mark.Italic

    "underline" -> Mark.Underline

    "strike-through" -> Mark.Strike

    "code" -> Mark.Code

    "spoiler" -> Mark.Spoiler

    else -> when (val def = defs[name]) {
        is MarkDef.Link -> Mark.Link(def.href)
        is MarkDef.EntryLink -> Mark.EntryLink(def.entryId, def.blockKey)
        is MarkDef.Color -> Mark.Color(def.token)
        is MarkDef.Highlight -> Mark.Highlight(def.token)
        is MarkDef.Size -> Mark.Size(SizeStep.entries.first { it.name.lowercase() == def.step })
        null -> null
    }
}

private fun Mark.toDef(key: String): MarkDef = when (this) {
    is Mark.Link -> MarkDef.Link(key, href)
    is Mark.EntryLink -> MarkDef.EntryLink(key, entryId, blockKey)
    is Mark.Color -> MarkDef.Color(key, token)
    is Mark.Highlight -> MarkDef.Highlight(key, token)
    is Mark.Size -> MarkDef.Size(key, step.name.lowercase())
    else -> error("$this is a decorator")
}

private fun Callout.toRef() = CalloutRef(key, tone, icon)

private fun CalloutRef.toCallout() = Callout(key, tone, icon)
