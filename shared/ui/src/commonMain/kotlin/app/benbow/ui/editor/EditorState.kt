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
import app.benbow.core.text.Align
import app.benbow.core.text.Atom
import app.benbow.core.text.BlockKeys
import app.benbow.core.text.BlockStyle
import app.benbow.core.text.ListKind
import app.benbow.core.text.Mark
import app.benbow.core.text.Paragraph
import app.benbow.core.text.RichText
import app.benbow.core.text.applyUserEdit
import app.benbow.core.text.insertAtom
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
        val result = before.applyUserEdit(start, end, inserted, typingMarks ?: before.marksForInsertion(start, end), keys)
        record(Snapshot(before, buffer.originalSelection), typing = inserted.length == 1 && inserted != "\n" && start == end)
        model = result.text
        typingMarks = result.typingMarks
        buffer.syncText(result.text.text, TextRange(result.selectionStart, result.selectionEnd), forceSelection = false)
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
        model = snapshot.model
        textFieldState.edit { syncText(snapshot.model.text, snapshot.selection, forceSelection = true) }
        refreshFlags()
    }

    private fun record(before: Snapshot, typing: Boolean) {
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
