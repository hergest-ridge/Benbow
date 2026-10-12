package app.benbow.core.text

/**
 * Editor document model: plain [text] with `\n` between paragraphs, one [Paragraph] per line and
 * normalized [marks]. The UI renders it; the codec maps it to Benbow Document JSON.
 */
data class RichText(
    val text: String,
    val paragraphs: List<Paragraph>,
    val marks: List<MarkSpan> = emptyList(),
) {
    init {
        require(paragraphs.size == text.count { it == '\n' } + 1) {
            "Expected ${text.count { it == '\n' } + 1} paragraphs, got ${paragraphs.size}"
        }
    }

    val paragraphStarts: IntArray by lazy {
        val starts = IntArray(paragraphs.size)
        var p = 1
        text.forEachIndexed { i, c -> if (c == '\n') starts[p++] = i + 1 }
        starts
    }

    fun paragraphIndexAt(offset: Int): Int {
        var lo = 0
        var hi = paragraphStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (paragraphStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** End offset of paragraph [index], excluding its trailing `\n`. */
    fun paragraphEnd(index: Int): Int =
        if (index + 1 < paragraphs.size) paragraphStarts[index + 1] - 1 else text.length

    fun paragraphText(index: Int): String = text.substring(paragraphStarts[index], paragraphEnd(index))

    fun paragraphIndices(start: Int, end: Int): IntRange = paragraphIndexAt(start)..paragraphIndexAt(end)

    fun marksCovering(offset: Int): Set<Mark> =
        marks.filter { it.start <= offset && offset < it.end }.mapTo(mutableSetOf()) { it.mark }

    /** Marks that text typed over `[start, end)` should get, before any explicit user toggles. */
    fun marksForInsertion(start: Int, end: Int): Set<Mark> {
        if (end > start) return marksCovering(start)
        if (start == 0 || text[start - 1] == '\n') return marksCovering(start).filterTo(mutableSetOf()) { it.inclusive }
        return marks.filter { it.start < start && (start < it.end || (start == it.end && it.mark.inclusive)) }
            .mapTo(mutableSetOf()) { it.mark }
    }

    /** True if every non-newline character in `[start, end)` carries [mark]. */
    fun hasMark(start: Int, end: Int, mark: Mark): Boolean {
        val spans = marks.filter { it.mark == mark }
        for (i in start until end) {
            if (text[i] != '\n' && spans.none { it.start <= i && i < it.end }) return false
        }
        return end > start
    }

    /** Replaces `[start, end)` with [inserted]; the inserted text gets exactly [insertedMarks]. */
    fun replace(start: Int, end: Int, inserted: String, insertedMarks: Set<Mark>, keys: BlockKeys): RichText {
        require(start in 0..end && end <= text.length) { "Bad range [$start, $end) for length ${text.length}" }
        return RichText(
            text = text.substring(0, start) + inserted + text.substring(end),
            paragraphs = replaceParagraphs(start, end, inserted, keys),
            marks = replaceMarks(start, end, inserted.length, insertedMarks),
        )
    }

    private fun replaceParagraphs(start: Int, end: Int, inserted: String, keys: BlockKeys): List<Paragraph> {
        val first = paragraphIndexAt(start)
        val last = paragraphIndexAt(end)
        val base = paragraphs[first]
        val newlines = inserted.count { it == '\n' }
        val result = ArrayList<Paragraph>(paragraphs.size - (last - first) + newlines)
        result.addAll(paragraphs.subList(0, first))
        if (newlines == 0) {
            result.add(base)
        } else {
            // Enter at the very start of a non-empty paragraph pushes it down with its formatting intact.
            val splitAtStart = start == paragraphStarts[first] && inserted.endsWith('\n') && end < paragraphEnd(last)
            if (splitAtStart) {
                repeat(newlines) { result.add(base.continuation(keys.next())) }
                result.add(base)
            } else {
                result.add(base)
                repeat(newlines) { result.add(base.continuation(keys.next())) }
            }
        }
        result.addAll(paragraphs.subList(last + 1, paragraphs.size))
        return result
    }

    private fun replaceMarks(start: Int, end: Int, insertedLength: Int, insertedMarks: Set<Mark>): List<MarkSpan> {
        val removed = end - start
        fun afterDelete(p: Int) = when {
            p <= start -> p
            p >= end -> p - removed
            else -> start
        }
        var spans = marks.mapNotNull { span ->
            val s = afterDelete(span.start).let { if (it >= start) it + insertedLength else it }
            val e = afterDelete(span.end).let { if (it > start) it + insertedLength else it }
            if (e > s) MarkSpan(span.mark, s, e) else null
        }
        if (insertedLength > 0) {
            val insertedEnd = start + insertedLength
            spans = spans.subtract(start, insertedEnd) { true } + insertedMarks.map { MarkSpan(it, start, insertedEnd) }
        }
        return normalize(spans)
    }

    fun withParagraph(index: Int, paragraph: Paragraph): RichText =
        copy(paragraphs = paragraphs.toMutableList().also { it[index] = paragraph })

    fun updateParagraphs(start: Int, end: Int, transform: (Paragraph) -> Paragraph): RichText {
        val range = paragraphIndices(start, end)
        return copy(paragraphs = paragraphs.mapIndexed { i, p -> if (i in range) transform(p) else p })
    }

    /** Adds [mark] to `[start, end)`, replacing other marks of the same group there. */
    fun addMark(start: Int, end: Int, mark: Mark): RichText {
        if (end <= start) return this
        val group = mark.group
        val base = if (group != null) marks.subtract(start, end) { it.group == group } else marks
        return copy(marks = normalize(base + MarkSpan(mark, start, end)))
    }

    fun removeMark(start: Int, end: Int, mark: Mark): RichText =
        copy(marks = normalize(marks.subtract(start, end) { it == mark }))

    fun toggleMark(start: Int, end: Int, mark: Mark): RichText =
        if (hasMark(start, end, mark)) removeMark(start, end, mark) else addMark(start, end, mark)

    /** Toggles [style]; headings and code blocks drop list formatting. */
    fun toggleBlockStyle(start: Int, end: Int, style: BlockStyle): RichText {
        val all = paragraphIndices(start, end).all { paragraphs[it].style == style }
        val target = if (all) BlockStyle.Normal else style
        return updateParagraphs(start, end) { p ->
            val dropsList = target == BlockStyle.Code || target == BlockStyle.H1 || target == BlockStyle.H2 || target == BlockStyle.H3
            p.copy(style = target, list = if (dropsList) null else p.list)
        }
    }

    fun toggleList(start: Int, end: Int, kind: ListKind): RichText {
        val all = paragraphIndices(start, end).all { paragraphs[it].list == kind }
        return updateParagraphs(start, end) { p ->
            if (all) {
                p.copy(list = null, level = 1, checked = false)
            } else {
                p.copy(list = kind, style = if (p.isHeading || p.style == BlockStyle.Code) BlockStyle.Normal else p.style)
            }
        }
    }

    fun toggleChecked(index: Int): RichText = withParagraph(index, paragraphs[index].let { it.copy(checked = !it.checked) })

    fun indent(start: Int, end: Int, delta: Int): RichText =
        updateParagraphs(start, end) { p -> if (p.list != null) p.copy(level = (p.level + delta).coerceIn(1, MAX_LEVEL)) else p }

    fun setAlign(start: Int, end: Int, align: Align): RichText = updateParagraphs(start, end) { it.copy(align = align) }

    fun toggleCallout(start: Int, end: Int, tone: String, icon: String, keys: BlockKeys): RichText {
        val range = paragraphIndices(start, end)
        val key = paragraphs[range.first].callout?.key
        val all = key != null && range.all { paragraphs[it].callout?.key == key }
        val callout = if (all) null else Callout(keys.next(), tone, icon)
        return updateParagraphs(start, end) { it.copy(callout = callout) }
    }

    /** Splits [marks] into non-overlapping runs, each with the full set of marks that covers it. */
    fun markRuns(): List<MarkRun> {
        if (marks.isEmpty()) return emptyList()
        val bounds = marks.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        val runs = ArrayList<MarkRun>()
        for (i in 0 until bounds.size - 1) {
            val s = bounds[i]
            val e = bounds[i + 1]
            val active = marks.filter { it.start <= s && e <= it.end }.mapTo(mutableSetOf()) { it.mark }
            if (active.isNotEmpty()) runs.add(MarkRun(s, e, active))
        }
        return runs
    }

    companion object {
        const val MAX_LEVEL = 6

        fun empty(keys: BlockKeys = BlockKeys()) = RichText("", listOf(Paragraph(keys.next())))
    }
}

data class MarkRun(val start: Int, val end: Int, val marks: Set<Mark>)

/** Removes `[start, end)` from spans matching [predicate]. */
internal fun List<MarkSpan>.subtract(start: Int, end: Int, predicate: (Mark) -> Boolean): List<MarkSpan> =
    flatMap { span ->
        if (!predicate(span.mark) || span.end <= start || span.start >= end) {
            listOf(span)
        } else {
            listOfNotNull(
                MarkSpan(span.mark, span.start, start).takeIf { it.end > it.start },
                MarkSpan(span.mark, end, span.end).takeIf { it.end > it.start },
            )
        }
    }

/** Merges overlapping or touching spans of equal marks, drops empty ones, sorts deterministically. */
internal fun normalize(spans: List<MarkSpan>): List<MarkSpan> =
    spans.filter { it.end > it.start }
        .groupBy { it.mark }
        .flatMap { (mark, group) ->
            val sorted = group.sortedBy { it.start }
            val merged = ArrayList<MarkSpan>()
            for (span in sorted) {
                val last = merged.lastOrNull()
                if (last != null && span.start <= last.end) {
                    merged[merged.size - 1] = MarkSpan(mark, last.start, maxOf(last.end, span.end))
                } else {
                    merged.add(span)
                }
            }
            merged
        }
        .sortedWith(compareBy<MarkSpan>({ it.start }, { it.end }, { it.mark.toString() }))
