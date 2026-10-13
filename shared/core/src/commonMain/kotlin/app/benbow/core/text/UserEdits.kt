package app.benbow.core.text

/**
 * Result of a user edit. [typingMarks] overrides the marks of the next typed text (null = derive
 * from the position), e.g. so text after a `**bold**` shortcut is not bold.
 */
data class EditResult(
    val text: RichText,
    val selectionStart: Int,
    val selectionEnd: Int = selectionStart,
    val typingMarks: Set<Mark>? = null,
)

/**
 * Applies a keyboard/IME edit: replaces `[start, end)` with [inserted] and runs the editor rules
 * (atoms stay whole, list exit on Enter, unformat on Backspace, Markdown-style shortcuts).
 */
fun RichText.applyUserEdit(start: Int, end: Int, inserted: String, typingMarks: Set<Mark>, keys: BlockKeys): EditResult =
    atomEdit(start, end, inserted, typingMarks, keys) ?: ordinaryEdit(start, end, inserted, typingMarks, keys).keepAtoms(keys)

private fun RichText.ordinaryEdit(start: Int, end: Int, inserted: String, typingMarks: Set<Mark>, keys: BlockKeys): EditResult {
    if (inserted == "\n" && start == end) {
        val i = paragraphIndexAt(start)
        val p = paragraphs[i]
        val exits = p.list != null || p.style == BlockStyle.Quote || p.style == BlockStyle.Code || p.callout != null
        if (exits && paragraphText(i).isEmpty()) return EditResult(withParagraph(i, p.exitOneLevel()), start)
    }
    if (inserted.isEmpty() && end == start + 1 && text[start] == '\n') {
        val i = paragraphIndexAt(end)
        val p = paragraphs[i]
        val ownCallout = p.callout != null && p.callout.key != paragraphs[i - 1].callout?.key
        if (p.list != null || p.style != BlockStyle.Normal || ownCallout) {
            return EditResult(withParagraph(i, p.exitOneLevel()), end)
        }
    }
    val edited = replace(start, end, inserted, typingMarks, keys)
    val cursor = start + inserted.length
    return edited.blockShortcut(cursor, inserted, keys)
        ?: edited.inlineShortcut(cursor, inserted, keys)
        ?: EditResult(edited, cursor)
}

private val blockShortcuts: Map<String, (Paragraph) -> Paragraph> = mapOf(
    "# " to { p -> p.copy(style = BlockStyle.H1) },
    "## " to { p -> p.copy(style = BlockStyle.H2) },
    "### " to { p -> p.copy(style = BlockStyle.H3) },
    "- " to { p -> p.copy(list = ListKind.Bullet) },
    "* " to { p -> p.copy(list = ListKind.Bullet) },
    "1. " to { p -> p.copy(list = ListKind.Number) },
    "[] " to { p -> p.copy(list = ListKind.Check) },
    "[ ] " to { p -> p.copy(list = ListKind.Check) },
    "> " to { p -> p.copy(style = BlockStyle.Quote) },
)

private fun RichText.blockShortcut(cursor: Int, inserted: String, keys: BlockKeys): EditResult? {
    if (inserted != " ") return null
    val i = paragraphIndexAt(cursor)
    val p = paragraphs[i]
    if (p.style != BlockStyle.Normal || p.list != null) return null
    val start = paragraphStarts[i]
    val transform = blockShortcuts[text.substring(start, cursor)] ?: return null
    return EditResult(replace(start, cursor, "", emptySet(), keys).withParagraph(i, transform(p)), start)
}

private class InlineRule(val delimiter: String, pattern: String, val mark: Mark) {
    val regex = Regex(pattern)
}

private val inlineRules = listOf(
    InlineRule("**", """\*\*([^*\n]+)\*\*$""", Mark.Bold),
    InlineRule("~~", """~~([^~\n]+)~~$""", Mark.Strike),
    InlineRule("`", """`([^`\n]+)`$""", Mark.Code),
    InlineRule("*", """\*([^*\n]+)\*$""", Mark.Italic),
)

private fun RichText.inlineShortcut(cursor: Int, inserted: String, keys: BlockKeys): EditResult? {
    if (inserted.length != 1 || inserted[0] !in "*~`") return null
    val start = paragraphStarts[paragraphIndexAt(cursor)]
    val prefix = text.substring(start, cursor)
    for (rule in inlineRules) {
        val match = rule.regex.find(prefix) ?: continue
        val openAt = start + match.range.first
        // "*" must not steal the first closing star of "**bold**".
        if (rule.delimiter == "*" && openAt > start && text[openAt - 1] == '*') continue
        val inner = match.groupValues[1]
        val d = rule.delimiter.length
        val stripped = replace(cursor - d, cursor, "", emptySet(), keys).replace(openAt, openAt + d, "", emptySet(), keys)
        val innerEnd = openAt + inner.length
        val result = stripped.addMark(openAt, innerEnd, rule.mark)
        return EditResult(result, innerEnd, typingMarks = result.marksForInsertion(innerEnd, innerEnd) - rule.mark)
    }
    return null
}
