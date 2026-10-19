package app.benbow.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import app.benbow.core.speech.fitPhrase
import app.benbow.core.text.Align
import app.benbow.core.text.Atom
import app.benbow.core.text.BlockKeys
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.EditResult
import app.benbow.core.text.Links
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.Paragraph
import app.benbow.core.text.RichText
import app.benbow.core.text.applyUserEdit
import app.benbow.core.text.atomRemovedBy
import app.benbow.core.text.cardToText
import app.benbow.core.text.insertAtom
import app.benbow.core.text.linkAt
import app.benbow.core.text.removeAtom
import app.benbow.core.text.setLink
import app.benbow.core.text.unlink
import app.benbow.core.text.withCard
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Displayed in place of each paragraph break; restored to '\n' on copy (see [EditorClipboard]). */
internal val PARAGRAPH_BREAK_GLYPH: Char = Char(0x200B) // ZERO WIDTH SPACE

/** Displayed after an empty last paragraph so it can carry a paragraph style; dropped on copy. */
internal val TAIL_GLYPH: Char = Char(0x2060) // WORD JOINER

/**
 * Editor state. [model] is the source of truth; [textFieldState] holds the same plain text.
 * Formatting is rendered by [outputTransformation], which Compose re-runs whenever [model] or the
 * styles change. Undo is ours because the built-in one only restores text, not formatting.
 */
@Stable
class EditorState(initial: RichText, private val keys: BlockKeys = BlockKeys()) {
    val textFieldState = TextFieldState(initial.text)

    var model: RichText by mutableStateOf(initial)
        private set

    /** Marks for the next typed text after a toggle at a collapsed cursor; null = derive from position. */
    internal var typingMarks: Set<Mark>? by mutableStateOf(null)

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    internal var styles: EditorStyles? by mutableStateOf(null)

    /** The link sheet is wanted for this (ADR-038); the app shows it and calls [applyLink] or clears this. */
    var linkRequest: LinkRequest? by mutableStateOf(null)

    /** The atom (by paragraph key) a tap or the first Backspace selected; the second Backspace removes it. */
    var selectedAtom: String? by mutableStateOf(null)
        internal set

    /** What was just taken out in one tap or key; the app offers «Вернуть» ([undoRemoval]) while nothing else changed. */
    var removal: Removal? by mutableStateOf(null)
        private set

    /** The cursor was put somewhere (a tap, arrows) rather than carried by typing: the link bubble may show. */
    internal var cursorPlaced by mutableStateOf(false)

    private var edits = 0

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastTyping: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastRangeSelection: TimeSource.Monotonic.ValueTimeMark? = null

    private class Snapshot(val model: RichText, val selection: TextRange)

    internal val inputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() = onUserInput(this)
    }

    internal val outputTransformation = object : OutputTransformation {
        override fun TextFieldBuffer.transformOutput() = render(this)
    }

    val selection: TextRange get() = textFieldState.selection

    val currentParagraph: Paragraph get() = model.paragraphs[model.paragraphIndexAt(selection.min)]

    val activeMarks: Set<Mark>
        get() {
            val sel = selection
            if (sel.collapsed) return typingMarks ?: model.marksForInsertion(sel.min, sel.min)
            return model.marks.mapTo(mutableSetOf()) { it.mark }.filterTo(mutableSetOf()) { model.hasMark(sel.min, sel.max, it) }
        }

    fun toggleMark(mark: Mark) {
        val sel = selection
        if (sel.collapsed) {
            val current = typingMarks ?: model.marksForInsertion(sel.min, sel.min)
            typingMarks =
                if (mark in
                    current
                ) {
                    current - mark
                } else {
                    current.filterTo(mutableSetOf()) { it.group == null || it.group != mark.group } + mark
                }
            return
        }
        apply { m, s -> m.toggleMark(s.min, s.max, mark) }
    }

    fun toggleBlockStyle(style: BlockStyle) = apply { m, s -> m.toggleBlockStyle(s.min, s.max, style) }

    fun toggleList(kind: ListKind) = apply { m, s -> m.toggleList(s.min, s.max, kind) }

    fun indent(delta: Int) = apply { m, s -> m.indent(s.min, s.max, delta) }

    fun setAlign(align: Align) = apply { m, s -> m.setAlign(s.min, s.max, align) }

    fun toggleCallout(tone: String, icon: String) = apply { m, s -> m.toggleCallout(s.min, s.max, tone, icon, keys) }

    fun removeCallout() = apply { m, s -> m.updateParagraphs(s.min, s.max) { it.copy(callout = null) } }

    fun toggleChecked(paragraphIndex: Int) = apply { m, _ -> m.toggleChecked(paragraphIndex) }

    /** Puts a picture (or another atom) in its own paragraph at the cursor, or after the text when [atEnd]. */
    fun insertAtom(atom: Atom, atEnd: Boolean = false) {
        val before = model
        val at = if (atEnd) before.text.length else selection.min
        val result = before.insertAtom(at, atom, keys)
        record(Snapshot(before, selection), typing = false)
        model = result.text
        textFieldState.edit { syncText(result.text.text, TextRange(result.selectionStart), forceSelection = true) }
    }

    /** Points the atom of attachment [old] at [new] (a redrawn drawing), keeping its width and caption; undoable. */
    fun replaceAttachment(old: String, new: String) {
        val before = model
        val i = before.paragraphs.indexOfFirst { (it.atom as? Atom.Attachment)?.ref == old }.takeIf { it >= 0 } ?: return
        val atom = before.paragraphs[i].atom as Atom.Attachment
        record(Snapshot(before, selection), typing = false)
        model = before.withParagraph(i, before.paragraphs[i].copy(atom = atom.copy(ref = new)))
    }

    /** Takes the atom of attachment [ref] out of the text with its line; undoable. */
    fun removeAttachment(ref: String) {
        val before = model
        val i = before.paragraphs.indexOfFirst { (it.atom as? Atom.Attachment)?.ref == ref }.takeIf { it >= 0 } ?: return
        val start = before.paragraphStarts[i]
        val end = before.paragraphEnd(i)
        val (from, to) = when {
            i + 1 < before.paragraphs.size -> start to end + 1
            i > 0 -> start - 1 to end
            else -> start to end
        }
        val result = before.applyUserEdit(from, to, "", emptySet(), keys)
        record(Snapshot(before, selection), typing = false)
        model = result.text
        textFieldState.edit { syncText(result.text.text, TextRange(result.selectionStart), forceSelection = true) }
    }

    internal fun noteSelection(selection: TextRange, previous: TextRange) {
        if (!selection.collapsed || !previous.collapsed) lastRangeSelection = TimeSource.Monotonic.markNow()
    }

    /** True while text is selected or was a moment ago (a copy collapses the selection). */
    internal fun recentlyHadRangeSelection(): Boolean =
        !selection.collapsed || lastRangeSelection?.let { it.elapsedNow() < 3.seconds } == true

    /** Selected text from the model (real '\n', no display glyphs), or null for a collapsed selection. */
    fun selectedText(): String? = selection.takeIf { !it.collapsed }?.let { model.text.substring(it.min, it.max) }

    /** Replaces the selection like typed input (undoable); used by cut and future rich paste. */
    fun replaceSelection(text: String) {
        val sel = selection
        val before = model
        val result = before.applyUserEdit(sel.min, sel.max, text, typingMarks ?: before.marksForInsertion(sel.min, sel.max), keys)
        record(Snapshot(before, sel), typing = false)
        model = result.text
        typingMarks = result.typingMarks
        textFieldState.edit { syncText(result.text.text, TextRange(result.selectionStart, result.selectionEnd), forceSelection = true) }
    }

    /**
     * Puts a dictated phrase in like typed text (7.10): in place of the selection, or after the text when [atEnd],
     * fitted to what stands before it in its paragraph ([fitPhrase]); one undo step per phrase, with the marks typing
     * would get there (inside a spoiler it stays a spoiler).
     */
    fun insertDictated(phrase: String, atEnd: Boolean) {
        val before = model
        val sel = if (atEnd) TextRange(before.text.length) else selection
        val i = before.paragraphIndexAt(sel.min)
        val start = before.paragraphStarts[i]
        val lead = if (before.paragraphs[i].atom != null) "" else before.text.substring(start, sel.min)
        val piece = fitPhrase(lead, phrase)
        if (piece.isEmpty()) return
        val marks = (if (atEnd) null else typingMarks) ?: before.marksForInsertion(sel.min, sel.max)
        val result = before.applyUserEdit(sel.min, sel.max, piece, marks, keys)
        record(Snapshot(before, selection), typing = false)
        model = result.text
        typingMarks = result.typingMarks
        textFieldState.edit { syncText(result.text.text, TextRange(result.selectionStart, result.selectionEnd), forceSelection = true) }
    }

    /** Puts [text] in place of the atom of attachment [ref] (7.10e: a voice note gives way to its words); undoable. */
    fun replaceAttachmentWithText(ref: String, text: String): Boolean {
        val before = model
        val i = before.paragraphs.indexOfFirst { (it.atom as? Atom.Attachment)?.ref == ref }.takeIf { it >= 0 } ?: return false
        val words = text.trim().ifEmpty { return false }
        val start = before.paragraphStarts[i]
        val result = before.applyUserEdit(start, start + 1, words, emptySet(), keys)
        record(Snapshot(before, selection), typing = false)
        model = result.text
        textFieldState.edit { syncText(result.text.text, TextRange(result.selectionStart), forceSelection = true) }
        return true
    }

    /** Asks for the link sheet over the link at the cursor, or the selected words, or words to add at the cursor. */
    fun requestLink() {
        val sel = selection
        val m = model
        val span = m.linkAt(sel.min)?.takeIf { sel.collapsed || (it.start <= sel.min && sel.max <= it.end) }
        linkRequest = if (span != null) {
            LinkRequest(span.start, span.end, span.mark, m.text.substring(span.start, span.end))
        } else {
            LinkRequest(sel.min, sel.max, null, m.text.substring(sel.min, sel.max))
        }
    }

    /** Asks for the link sheet over card [key]: its address and title. */
    fun requestCardEdit(key: String) {
        val card = card(key)?.second ?: return
        linkRequest = LinkRequest(0, 0, Mark.Link(card.href), card.title.orEmpty(), cardKey = key)
    }

    /**
     * What the link sheet chose for [request]: [mark] over the words ([words] replace them when given and different), or
     * no link at all (null: the words stay). Undoable; taking a link off offers «Вернуть».
     */
    fun applyLink(request: LinkRequest, mark: Mark?, words: String?) {
        linkRequest = null
        val before = model
        if (request.cardKey != null) {
            val i = cardIndex(request.cardKey) ?: return
            val href = (mark as? Mark.Link)?.href
            val title = words?.trim()?.ifEmpty { null }
            val after = before.withCard(i) { card ->
                // Another address is another page: what the old one said goes, and the new one is read.
                if (href == null || href == card.href) card.copy(title = title) else Atom.LinkCard(href, title = title, large = card.large)
            } ?: return
            commit(before, EditResult(after, selection.min, selection.max))
            return
        }
        val start = request.start.coerceIn(0, before.text.length)
        val end = request.end.coerceIn(start, before.text.length)
        if (mark == null) {
            if (end > start && before.linkAt(start) != null) {
                commit(before, EditResult(before.unlink(start, end), selection.min, selection.max))
                removal = Removal(Removal.Kind.Link, edits)
            }
            return
        }
        val label =
            words?.takeIf { it.isNotBlank() }?.replace('\n', ' ')
                ?: (mark as? Mark.Link)?.href?.takeIf { end == start }?.let(Links::display)
        if (end == start && label == null) return
        commit(before, before.setLink(start, end, mark, label, keys))
    }

    /** Takes the link at [offset] off; its words stay. */
    fun unlinkAt(offset: Int) {
        val span = model.linkAt(offset) ?: return
        val before = model
        commit(before, EditResult(before.unlink(span.start, span.end), selection.min, selection.max))
        removal = Removal(Removal.Kind.Link, edits)
    }

    /** Card [key] and its paragraph index, or null when it is gone. */
    fun card(key: String): Pair<Int, Atom.LinkCard>? = cardIndex(key)?.let { i -> i to model.paragraphs[i].atom as Atom.LinkCard }

    private fun cardIndex(key: String): Int? = model.paragraphs.indexOfFirst {
        it.key == key && it.atom is Atom.LinkCard
    }.takeIf { it >= 0 }

    /** The big look or a row for card [key]; undoable. */
    fun setCardLarge(key: String, large: Boolean) {
        val i = cardIndex(key) ?: return
        val before = model
        commit(before, EditResult(before.withCard(i) { it.copy(large = large) } ?: return, selection.min, selection.max))
    }

    /** Card [key] goes down to a link in the text (its title as the words); «Вернуть» brings the card back. */
    fun cardToText(key: String) {
        val i = cardIndex(key) ?: return
        val before = model
        commit(before, before.cardToText(i, keys) ?: return, moveCursor = true)
        selectedAtom = null
        removal = Removal(Removal.Kind.CardToText, edits)
    }

    /** Atom [key] (a card, a picture, a divider) leaves the text with its line; «Вернуть» brings it back. */
    fun removeAtom(key: String) {
        val before = model
        val i = before.paragraphs.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return
        val card = before.paragraphs[i].atom is Atom.LinkCard
        commit(before, before.removeAtom(i, keys) ?: return, moveCursor = true)
        selectedAtom = null
        removal = Removal(if (card) Removal.Kind.Card else Removal.Kind.Atom, edits)
    }

    /**
     * Writes what card [key]'s page said into it. Not an undo step: undoing past the paste takes the card away, and
     * one that comes back without its words is filled again.
     */
    fun fillCard(key: String, change: (Atom.LinkCard) -> Atom.LinkCard): Boolean {
        val i = cardIndex(key) ?: return false
        model = model.withCard(i, change) ?: return false
        return true
    }

    /** «Вернуть» for [removal]: undoes it if nothing was edited since. */
    fun undoRemoval(removal: Removal): Boolean {
        if (removal.edit != edits || undoStack.isEmpty()) return false
        this.removal = null
        undo()
        return true
    }

    private fun commit(before: RichText, result: EditResult, moveCursor: Boolean = false) {
        record(Snapshot(before, selection), typing = false)
        model = result.text
        typingMarks = result.typingMarks
        val sel = if (moveCursor || result.text.text != before.text) TextRange(result.selectionStart, result.selectionEnd) else selection
        textFieldState.edit { syncText(result.text.text, sel, forceSelection = moveCursor || result.text.text != before.text) }
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(model, selection))
        restore(snapshot)
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(model, selection))
        restore(snapshot)
    }

    private fun onUserInput(buffer: TextFieldBuffer) {
        val before = model
        val (start, end, inserted) = buffer.singleChange() ?: return
        if (buffer.originalText.length != before.text.length) {
            // Should never happen; keep the text and drop formatting rather than lose input.
            val text = buffer.asCharSequence().toString()
            model = RichText(text, List(text.count { it == '\n' } + 1) { Paragraph(keys.next()) })
            return
        }
        // Backspace or Delete at an atom selects it first; the second press removes it, with «Вернуть» (ADR-038).
        val atomAt = before.atomRemovedBy(start, end, inserted)
        val atomKey = atomAt?.let { before.paragraphs[it].key }
        if (atomKey != null && selectedAtom != atomKey) {
            buffer.revertAllChanges()
            selectedAtom = atomKey
            return
        }
        val original = buffer.originalSelection
        val overSelection = !original.collapsed && original.min == start && original.max == end
        val result = before.applyUserEdit(start, end, inserted, typingMarks ?: before.marksForInsertion(start, end), keys, overSelection)
        record(Snapshot(before, original), typing = inserted.length == 1 && inserted != "\n" && start == end)
        model = result.text
        typingMarks = result.typingMarks
        buffer.syncText(result.text.text, TextRange(result.selectionStart, result.selectionEnd), forceSelection = false)
        selectedAtom = null
        if (atomAt != null) {
            removal = Removal(if (before.paragraphs[atomAt].atom is Atom.LinkCard) Removal.Kind.Card else Removal.Kind.Atom, edits)
        }
    }

    private fun apply(transform: (RichText, TextRange) -> RichText) {
        val sel = selection
        val before = model
        val after = transform(before, sel)
        if (after == before) return
        record(Snapshot(before, sel), typing = false)
        model = after
        if (after.text != before.text) textFieldState.edit { syncText(after.text, sel, forceSelection = false) }
    }

    private fun restore(snapshot: Snapshot) {
        lastTyping = null
        typingMarks = null
        selectedAtom = null
        model = snapshot.model
        textFieldState.edit { syncText(snapshot.model.text, snapshot.selection, forceSelection = true) }
        refreshFlags()
    }

    private fun record(before: Snapshot, typing: Boolean) {
        edits++
        val merge = typing && lastTyping?.let { it.elapsedNow() < 1.seconds } == true
        if (!merge) {
            undoStack.addLast(before)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        lastTyping = if (typing) TimeSource.Monotonic.markNow() else null
        redoStack.clear()
        refreshFlags()
    }

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** Rewrites the buffer text to [target] with a minimal replace (keeps IME state stable). */
    private fun TextFieldBuffer.syncText(target: String, selection: TextRange, forceSelection: Boolean) {
        val current = asCharSequence()
        if (!current.contentEquals(target)) {
            val (start, oldEnd, newEnd) = diffBounds(current, target)
            replace(start, oldEnd, target.substring(start, newEnd))
            this.selection = selection
        } else if (forceSelection) {
            this.selection = selection
        }
    }

    /**
     * Compose starts a new layout paragraph at every paragraph-style boundary *and* at every '
',
     * so a '
' right at a style boundary would show an extra empty line. Consecutive paragraphs with
     * equal styles therefore share one style range (their '
' stay real, and fewer layout paragraphs
     * keep typing fast); only the '
' between groups is displayed as a zero-width glyph (same
     * length, offsets map 1:1).
     */
    private fun render(buffer: TextFieldBuffer) {
        val st = styles ?: return
        val m = model
        if (buffer.length != m.text.length) return
        val starts = m.paragraphStarts
        val last = starts.size - 1
        val sizeMarks = m.marks.filter { it.mark is Mark.Size }
        val paragraphStyles = m.paragraphs.mapIndexed { i, p ->
            val s = starts[i]
            val e = m.paragraphEnd(i)
            val maxSize = sizeMarks.filter { it.start < e && it.end > s }
                .maxOfOrNull { st.sizeFactor((it.mark as Mark.Size).step) } ?: 1f
            st.paragraphStyle(p, maxSize)
        }
        var groupStart = 0
        for (i in 0..last) {
            if (i < last && paragraphStyles[i + 1] == paragraphStyles[i]) continue
            if (i < last) buffer.replace(starts[i + 1] - 1, starts[i + 1], PARAGRAPH_BREAK_GLYPH.toString())
            if (i == last && groupStart == last && starts[last] == m.text.length) buffer.append(TAIL_GLYPH)
            val end = if (i < last) starts[i + 1] else buffer.length
            buffer.addStyle(paragraphStyles[i], starts[groupStart], end)
            groupStart = i + 1
        }
        m.paragraphs.forEachIndexed { i, p ->
            val s = starts[i]
            val e = m.paragraphEnd(i)
            if (e > s) st.blockSpan(p)?.let { buffer.addStyle(it, s, e) }
        }
        m.markRuns().forEach { buffer.addStyle(st.runStyle(it.marks), it.start, it.end) }
    }

    private companion object {
        const val MAX_UNDO = 500
    }
}

/**
 * What the link sheet edits (ADR-038): the words `[start, end)` (empty: words to add at the cursor) with their [current]
 * link, or card [cardKey]'s address and title ([words]).
 */
data class LinkRequest(val start: Int, val end: Int, val current: Mark?, val words: String, val cardKey: String? = null)

/** Something taken out in one tap or key; [edit] is the editor's edit count right after it. */
class Removal(val kind: Kind, internal val edit: Int) {
    enum class Kind { Atom, Card, Link, CardToText }
}

/** The edit as one replace `[start, end)` of the original text with the returned string. */
@OptIn(ExperimentalFoundationApi::class) // ChangeList; the prefix/suffix diff below is the fallback.
private fun TextFieldBuffer.singleChange(): Triple<Int, Int, String>? {
    val new = asCharSequence()
    if (changes.changeCount == 1) {
        val range = changes.getRange(0)
        val original = changes.getOriginalRange(0)
        return Triple(original.min, original.max, new.subSequence(range.min, range.max).toString())
    }
    val old = originalText
    val (start, oldEnd, newEnd) = diffBounds(old, new)
    if (start == oldEnd && start == newEnd) return null
    return Triple(start, oldEnd, new.subSequence(start, newEnd).toString())
}

/** Common prefix/suffix diff: returns (start, endInOld, endInNew). */
private fun diffBounds(old: CharSequence, new: CharSequence): Triple<Int, Int, Int> {
    val min = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < min && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < min - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return Triple(prefix, old.length - suffix, new.length - suffix)
}
