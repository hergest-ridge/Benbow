package app.benbow.core.text

/*
 * Links while writing (ADR-038): an address pasted over selected words links them, one pasted on an empty plain line
 * becomes a card, one pasted in a sentence reads short (`habr.com/ru/…`); an address typed and followed by a space or a
 * line break is linked as typed; `[words](address)` turns into linked words like the other Markdown shortcuts.
 */

private const val LINK_GROUP = "link"

/** The address that ends where a space or a line break is being typed gets its link mark (the text stays as typed). */
internal fun RichText.autolinked(start: Int, end: Int, inserted: String): RichText {
    val at = when {
        start == end && (inserted == " " || inserted == "\n") -> start

        // A keyboard may commit the word it was composing together with the space.
        inserted.length >= 2 && inserted.last().let { it == ' ' || it == '\n' } && text.substring(start, end) == inserted.dropLast(1) -> end

        else -> return this
    }
    val i = paragraphIndexAt(at)
    val p = paragraphs[i]
    if (p.atom != null || p.style == BlockStyle.Code) return this
    val from = paragraphStarts[i]
    val before = text.substring(from, at)
    val tokenStart = from + before.indexOfLast { it.isWhitespace() } + 1
    val token = Links.trimTrailing(text.substring(tokenStart, at))
    val href = Links.parse(token) ?: return this
    val tokenEnd = tokenStart + token.length
    if (marks.any { it.start < tokenEnd && it.end > tokenStart && (it.mark.group == LINK_GROUP || it.mark == Mark.Code) }) return this
    return addMark(tokenStart, tokenEnd, Mark.Link(href))
}

/**
 * A pasted address, or null when [inserted] is anything else. [overSelection] is true only when the user had selected
 * the replaced words (a keyboard replacing the word it composes must never be read as linking it).
 */
internal fun RichText.linkPaste(
    start: Int,
    end: Int,
    inserted: String,
    typingMarks: Set<Mark>,
    keys: BlockKeys,
    overSelection: Boolean,
): EditResult? {
    if (inserted.length < 2 || '\n' in inserted) return null
    val href = Links.parse(inserted) ?: return null
    val i = paragraphIndexAt(start)
    val p = paragraphs[i]
    if (p.atom != null || p.style == BlockStyle.Code || paragraphIndexAt(end) != i || Mark.Code in typingMarks) return null
    if (end > start) {
        val selected = text.substring(start, end)
        if (!overSelection || selected.isBlank() || Links.parse(selected) != null) return null
        return EditResult(addMark(start, end, Mark.Link(href)), end)
    }
    if (paragraphText(i).isEmpty() && p.style == BlockStyle.Normal && p.list == null && p.callout == null) {
        return insertAtom(start, Atom.LinkCard(href), keys)
    }
    val shown = Links.display(href)
    val around = typingMarks.filterTo(mutableSetOf()) { it.group != LINK_GROUP }
    val edited = replace(start, end, shown, around + Mark.Link(href), keys)
    return EditResult(edited, start + shown.length, typingMarks = around)
}

/** Addresses inside pasted text (several words, lines) are linked as they stand. */
internal fun RichText.linkified(start: Int, inserted: String, typingMarks: Set<Mark>): RichText {
    if (inserted.length < 2 || Mark.Code in typingMarks || ("://" !in inserted && "www." !in inserted.lowercase())) return this
    return Links.find(inserted).fold(this) { t, (s, e, href) ->
        val from = start + s
        val to = start + e
        if (t.marks.any { it.start < to && it.end > from && it.mark.group == LINK_GROUP }) t else t.addMark(from, to, Mark.Link(href))
    }
}

private val MARKDOWN_LINK = Regex("""\[([^\[\]\n]+)]\(([^()\s]+)\)$""")

/** `[words](address)` as ")" is typed: the words stay, linked. */
internal fun RichText.markdownLink(cursor: Int, inserted: String, keys: BlockKeys): EditResult? {
    if (inserted != ")") return null
    val i = paragraphIndexAt(cursor)
    val p = paragraphs[i]
    if (p.atom != null || p.style == BlockStyle.Code) return null
    val start = paragraphStarts[i]
    val match = MARKDOWN_LINK.find(text.substring(start, cursor)) ?: return null
    val href = Links.fromField(match.groupValues[2]) ?: return null
    val label = match.groupValues[1]
    val from = start + match.range.first
    val around = marksCovering(from + 1).filterTo(mutableSetOf()) { it.group != LINK_GROUP }
    val edited = replace(from, cursor, label, around + Mark.Link(href), keys)
    return EditResult(edited, from + label.length, typingMarks = around)
}

/** The link mark covering [offset] (an offset at either end counts), or null. */
fun RichText.linkAt(offset: Int): MarkSpan? =
    marks.firstOrNull { it.mark.group == LINK_GROUP && it.start <= offset && offset <= it.end && it.end > it.start }

/** Puts [mark] (a link or an entry link) on `[start, end)`, replacing any link there; [label] replaces the words when given. */
fun RichText.setLink(start: Int, end: Int, mark: Mark, label: String?, keys: BlockKeys): EditResult {
    require(mark.group == LINK_GROUP)
    if (label != null && label.isNotEmpty() && label != text.substring(start, end)) {
        val around = (
            if (end >
                start
            ) {
                marksCovering(start)
            } else {
                marksForInsertion(start, start)
            }
            ).filterTo(mutableSetOf()) { it.group != LINK_GROUP }
        val edited = replace(start, end, label, around + mark, keys)
        return EditResult(edited, start + label.length, typingMarks = around)
    }
    return EditResult(addMark(start, end, mark), end)
}

/** Takes every link off `[start, end)`; the words stay. */
fun RichText.unlink(start: Int, end: Int): RichText = copy(marks = normalize(marks.subtract(start, end) { it.group == LINK_GROUP }))

/**
 * A card goes down one step (ADR-038): paragraph [index] holds the link in the text instead, its words the page's title
 * (or the short address).
 */
fun RichText.cardToText(index: Int, keys: BlockKeys): EditResult? {
    val card = paragraphs[index].atom as? Atom.LinkCard ?: return null
    val start = paragraphStarts[index]
    val words = card.title?.trim()?.replace('\n', ' ')?.takeIf { it.isNotEmpty() } ?: Links.display(card.href)
    val plain = withParagraph(index, paragraphs[index].copy(atom = null))
    return EditResult(plain.replace(start, start + 1, words, setOf(Mark.Link(card.href)), keys), start + words.length)
}

/** Paragraph [index]'s card with [change] applied (its look, what its page said), or null when it holds no card. */
fun RichText.withCard(index: Int, change: (Atom.LinkCard) -> Atom.LinkCard): RichText? {
    val card = paragraphs.getOrNull(index)?.atom as? Atom.LinkCard ?: return null
    return withParagraph(index, paragraphs[index].copy(atom = change(card)))
}
