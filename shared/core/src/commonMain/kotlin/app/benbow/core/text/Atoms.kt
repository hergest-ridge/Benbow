package app.benbow.core.text

/*
 * Atom paragraphs (pictures, dividers, blocks this build doesn't know) stay whole while the user types around them
 * (editor.md#text-mapping): an atom always sits alone in its paragraph as one ATOM_CHAR. Removing one only takes it
 * out of the text: a picture's file stays among the entry's attachments.
 */

/** Puts [atom] in its own paragraph at [at], splitting the paragraph there; there is always a line after it to type on. */
fun RichText.insertAtom(at: Int, atom: Atom, keys: BlockKeys): EditResult {
    val i = paragraphIndexAt(at)
    val own = Paragraph(keys.next(), atom = atom)
    return if (at == paragraphStarts[i]) {
        val edited = replace(at, at, "$ATOM_CHAR\n", emptySet(), keys)
        EditResult(edited.copy(paragraphs = paragraphs.toMutableList().apply { add(i, own) }), at + 2)
    } else {
        val edited = replace(at, at, "\n$ATOM_CHAR\n", emptySet(), keys)
        val list = paragraphs.toMutableList().apply {
            add(i + 1, own)
            add(i + 2, paragraphs[i].continuation(keys.next()))
        }
        EditResult(edited.copy(paragraphs = list), at + 3)
    }
}

/** Offsets of the atom paragraphs' characters, with their atoms, in document order. */
fun RichText.atoms(): List<Pair<Int, Atom>> = paragraphs.mapIndexedNotNull { i, p -> p.atom?.let { paragraphStarts[i] to it } }

/**
 * Edits right next to an atom, or null for an ordinary edit: typing just before or after one goes onto its own line,
 * Backspace or Delete across the line break after an atom (or on its character) removes the atom whole, and across the
 * break before one only swallows an empty line above it.
 */
internal fun RichText.atomEdit(start: Int, end: Int, inserted: String, typingMarks: Set<Mark>, keys: BlockKeys): EditResult? {
    if (start == end && inserted.isNotEmpty() && inserted != "\n") {
        val i = paragraphIndexAt(start)
        if (paragraphs[i].atom == null) return null
        return if (start == paragraphStarts[i]) {
            EditResult(replace(start, start, inserted + "\n", typingMarks, keys), start + inserted.length)
        } else {
            val edited = replace(start, start, "\n" + inserted, emptySet(), keys).let {
                it.addMarks(start + 1, start + 1 + inserted.length, typingMarks)
            }
            EditResult(edited, start + 1 + inserted.length)
        }
    }
    if (inserted.isNotEmpty() || end != start + 1) return null
    val i = paragraphIndexAt(start)
    return when {
        text[start] == ATOM_CHAR && paragraphs[i].atom != null -> removeParagraph(i, keys)
        text[start] != '\n' -> null
        paragraphs[i].atom != null -> removeParagraph(i, keys)
        paragraphs[i + 1].atom != null -> if (paragraphText(i).isEmpty()) removeParagraph(i, keys) else EditResult(this, start)
        else -> null
    }
}

/** Removes paragraph [index] with one of its line breaks; the cursor lands where it was. */
private fun RichText.removeParagraph(index: Int, keys: BlockKeys): EditResult {
    val last = index == paragraphs.size - 1
    val start = if (last && index > 0) paragraphStarts[index] - 1 else paragraphStarts[index]
    val end = if (last) text.length else paragraphStarts[index + 1]
    if (paragraphs.size == 1) return EditResult(RichText("", listOf(paragraphs[0].copy(atom = null))), 0)
    val edited = replace(start, end, "", emptySet(), keys)
    return EditResult(edited.copy(paragraphs = paragraphs.filterIndexed { i, _ -> i != index }), start)
}

private fun RichText.addMarks(start: Int, end: Int, marks: Set<Mark>): RichText = marks.fold(this) { t, m -> t.addMark(start, end, m) }

/**
 * After any other edit: an atom paragraph holds exactly its character (text that landed in it moves to lines of its
 * own; with the character gone the atom is gone), and a stray ATOM_CHAR anywhere else is dropped.
 */
internal fun EditResult.keepAtoms(keys: BlockKeys): EditResult {
    var t = text
    var selStart = selectionStart
    var selEnd = selectionEnd
    fun shift(at: Int, by: Int) {
        if (selStart > at) selStart = (selStart + by).coerceAtLeast(at)
        if (selEnd > at) selEnd = (selEnd + by).coerceAtLeast(at)
    }
    for (i in t.paragraphs.indices.reversed()) {
        val p = t.paragraphs[i]
        val s = t.paragraphStarts[i]
        val body = t.paragraphText(i)
        if (p.atom == null) {
            for (k in body.indices.reversed()) {
                if (body[k] != ATOM_CHAR) continue
                t = t.replace(s + k, s + k + 1, "", emptySet(), keys)
                shift(s + k, -1)
            }
            continue
        }
        if (body == ATOM_CHAR.toString()) continue
        val at = body.indexOf(ATOM_CHAR)
        if (at < 0) {
            t = t.withParagraph(i, p.copy(atom = null))
            continue
        }
        val pos = s + at
        if (at + 1 < body.length) {
            t = t.replace(pos + 1, pos + 1, "\n", emptySet(), keys)
                .let { it.copy(paragraphs = t.paragraphs.toMutableList().apply { add(i + 1, p.continuation(keys.next())) }) }
            shift(pos, 1)
        }
        if (at > 0) {
            val before = t.paragraphs
            t = t.replace(pos, pos, "\n", emptySet(), keys)
                .let {
                    it.copy(
                        paragraphs = before.toMutableList().apply {
                            set(i, p.copy(key = keys.next(), atom = null))
                            add(i + 1, p)
                        },
                    )
                }
            shift(pos, 1)
        }
    }
    return if (t === text) this else copy(text = t, selectionStart = selStart, selectionEnd = selEnd)
}
