package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.benbow.app.resources.Res
import app.benbow.app.resources.board_add_item
import app.benbow.app.resources.board_checked
import app.benbow.app.resources.board_move_item
import app.benbow.app.resources.board_remove_item
import app.benbow.app.resources.board_title_hint
import app.benbow.core.content.Block
import app.benbow.core.content.Checklists
import app.benbow.core.content.Document
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.text.BlockKeys
import app.benbow.ui.editor.LocalNoteFont
import app.benbow.ui.editor.inFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A note on the board that is a list, Keep's way (ADR-040, K4): under its title (the note's own field), the open boxes in their order, each with a
 * handle to move it, «Пункт списка» under them, then the ticked ones folded under «Отмечено: N» (with
 * [LocalTickedBelow] off, every box where it stands, the ticked ones crossed out, as on paper). Each box is a line of
 * its own: Enter starts the next (splitting the words at the cursor, a pasted text one box a line), Backspace at a box's
 * start joins it to the one above, × takes it away. The document keeps its order whatever the boxes show as: ticking
 * changes only `checked`, so ticks from two devices merge. [onChange] gets the whole document after every change.
 */
@Composable
internal fun ChecklistEditor(document: Document, onChange: (Document) -> Unit, modifier: Modifier = Modifier, focusFirst: Boolean = false) {
    val scope = rememberCoroutineScope()
    val below = LocalTickedBelow.current
    val model = remember { ListModel(document, scope, onChange, inPlace = !below) }
    LaunchedEffect(below) { model.inPlace = !below }
    val list = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val drag = remember(list, model) { RowDrag(list, model) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) } }
    LaunchedEffect(Unit) { if (focusFirst) model.open.firstOrNull()?.let { model.focusing = it to 0 } }
    // The box to focus may be out of sight (a new one at the end): bring it in first.
    LaunchedEffect(model.focusing) {
        val key = model.focusing?.first ?: return@LaunchedEffect
        val at = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        if (at == null) model.indexOf(key)?.let { list.scrollToItem(it) }
    }
    LazyColumn(modifier, state = list, contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 32.dp)) {
        items(model.movable, key = { it }) { key -> BoxRow(model, key, drag, Modifier.animateItem()) }
        item("add") { AddRow(model) }
        if (!model.inPlace && model.ticked.isNotEmpty()) {
            item("ticked") { TickedHeader(model, Modifier.animateItem()) }
            if (!model.folded) items(model.ticked, key = { it }) { key -> BoxRow(model, key, null, Modifier.animateItem()) }
        }
    }
}

/** One box: its words, whether it is ticked, and its block as it came (its marks stay while its words do). */
@Stable
private class BoxLine(val key: String, words: String, checked: Boolean, private val original: TextBlock? = null) {
    constructor(block: TextBlock) : this(block.key, Checklists.words(block), block.checked == true, block)

    val field = TextFieldState(words)
    var checked by mutableStateOf(checked)

    fun block(): TextBlock {
        val words = field.text.toString()
        val kept = original != null && words == Checklists.words(original)
        val base = original ?: TextBlock(key, listItem = Checklists.CHECK, level = 1)
        return base.copy(
            checked = checked,
            children = if (kept) original!!.children else listOf(Span(words)),
            markDefs = if (kept) original!!.markDefs else emptyList(),
        )
    }
}

@Stable
private class ListModel(document: Document, private val scope: CoroutineScope, private val onChange: (Document) -> Unit, inPlace: Boolean) {
    private val keys = BlockKeys()

    /** Every block in the document's order; pictures (and a title from an older note) keep their places. */
    private val order = mutableStateListOf<String>()
    val rows = mutableStateMapOf<String, BoxLine>()
    private val others = HashMap<String, Block>()
    var folded by mutableStateOf(false)

    /** The box to focus next and where its cursor goes. */
    var focusing by mutableStateOf<Pair<String, Int>?>(null)

    init {
        document.blocks.forEach { block ->
            order += block.key
            if (block is TextBlock && Checklists.isBox(block)) rows[block.key] = BoxLine(block) else others[block.key] = block
        }
    }

    /** The ticked boxes stay where they are ([LocalTickedBelow] off): one run of boxes, no «Отмечено». */
    var inPlace by mutableStateOf(inPlace)

    val open: List<String> get() = order.filter { rows[it]?.checked == false }
    val ticked: List<String> get() = order.filter { rows[it]?.checked == true }

    /** The boxes above «Пункт списка», each with its handle: the open ones, or every box when they stay in place. */
    val movable: List<String> get() = if (inPlace) order.filter { it in rows } else open

    /** The run [key] joins, moves and hands its focus on in: [movable], or the ticked ones under «Отмечено». */
    private fun runOf(key: String): List<String> = if (!inPlace && rows[key]?.checked == true) ticked else movable

    /** The box's place in the editor's list: the open boxes, «Пункт списка», the header, the ticked ones. */
    fun indexOf(key: String): Int? {
        movable.indexOf(key).takeIf { it >= 0 }?.let { return it }
        if (inPlace) return null
        return ticked.indexOf(key).takeIf { it >= 0 && !folded }?.let { open.size + 2 + it }
    }

    fun document(): Document = Document(order.mapNotNull { rows[it]?.block() ?: others[it] })

    fun changed() = onChange(document())

    fun later(action: () -> Unit) {
        scope.launch { action() }
    }

    fun toggle(key: String) {
        val row = rows[key] ?: return
        row.checked = !row.checked
        changed()
    }

    /** New boxes after [key] with [words] each (Enter, or a pasted text of several lines); the last one gets the cursor. */
    fun split(key: String, words: List<String>) {
        val at = order.indexOf(key)
        if (at < 0 || words.isEmpty()) return
        val checked = rows[key]?.checked == true
        val added = words.map { text -> keys.next().also { rows[it] = BoxLine(it, text, checked) } }
        order.addAll(at + 1, added)
        focusing = added.last() to 0
        changed()
    }

    /** A new open box after the last one, focused. */
    fun add() {
        val key = keys.next()
        rows[key] = BoxLine(key, "", false)
        order.add(movable.lastOrNull()?.let { order.indexOf(it) + 1 } ?: order.size, key)
        focusing = key to 0
        changed()
    }

    fun remove(key: String) {
        if (key !in rows) return
        val part = runOf(key)
        val at = part.indexOf(key)
        val next = part.getOrNull(at - 1) ?: part.getOrNull(at + 1)
        order.remove(key)
        rows.remove(key)
        next?.let { focusing = it to (rows[it]?.field?.text?.length ?: 0) }
        changed()
    }

    /** Backspace at a box's start: its words join the box above it, the cursor where they meet. */
    fun join(key: String) {
        val row = rows[key] ?: return
        val part = runOf(key)
        val above = part.getOrNull(part.indexOf(key) - 1)?.let { rows[it] } ?: return
        val at = above.field.text.length
        val words = row.field.text.toString()
        above.field.edit { append(words) }
        order.remove(key)
        rows.remove(key)
        focusing = above.key to at
        changed()
    }

    /** Carrying: [from] takes the place of [to] (stored when let go). */
    fun move(from: String, to: String) {
        val fromAt = order.indexOf(from)
        val toAt = order.indexOf(to)
        if (fromAt < 0 || toAt < 0) return
        order.removeAt(fromAt)
        order.add(toAt, from)
    }
}

/** A box carried by its handle: it follows the finger or the mouse over the others, which make way. */
@Stable
private class RowDrag(private val list: LazyListState, private val model: ListModel, private val tick: () -> Unit) {
    var key by mutableStateOf<String?>(null)
        private set
    private var top by mutableFloatStateOf(0f)
    private var over: Any? = null

    fun start(key: String) {
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        this.key = key
        top = info.offset.toFloat()
        over = null
    }

    fun by(dy: Float) {
        val carried = key ?: return
        top += dy
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == carried } ?: return
        val center = top + info.size / 2f
        val movable = model.movable
        val under = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != carried && it.key in movable && center >= it.offset && center < it.offset + it.size
        }?.key
        if (under != over) over = null
        if (under == null || over != null) return
        model.move(carried, under as String)
        tick()
        over = under
    }

    /** How far the carried box is drawn from where the list has put it. */
    fun shift(key: String): Float = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { top - it.offset } ?: 0f

    fun end() {
        if (key == null) return
        key = null
        model.changed()
    }
}

@Composable
private fun BoxRow(model: ListModel, key: String, drag: RowDrag?, modifier: Modifier) {
    val row = model.rows[key] ?: return
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val splitter = remember(row) {
        InputTransformation {
            val text = asCharSequence().toString()
            if ('\n' in text) {
                val lines = text.split('\n')
                replace(0, length, lines.first())
                model.later { model.split(key, lines.drop(1)) }
            }
        }
    }
    LaunchedEffect(row) { snapshotFlow { row.field.text.toString() }.drop(1).collect { model.changed() } }
    LaunchedEffect(model.focusing) {
        val (target, at) = model.focusing ?: return@LaunchedEffect
        if (target != key) return@LaunchedEffect
        requester.requestFocus()
        row.field.edit { selection = TextRange(at.coerceIn(0, length)) }
        model.focusing = null
    }
    val carried = drag != null && drag.key == key
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp)
            .zIndex(if (carried) 1f else 0f)
            .graphicsLayer {
                if (carried) {
                    translationY = drag?.shift(key) ?: 0f
                    shadowElevation = 6.dp.toPx()
                    shape = RoundedCornerShape(8.dp)
                }
            }
            .background(if (carried) colors.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (drag != null) {
            Icon(
                Icons.DragHandle,
                stringResource(Res.string.board_move_item),
                Modifier.size(40.dp).padding(8.dp).hand().pointerInput(key) {
                    detectDragGestures(
                        onDragStart = { drag.start(key) },
                        onDragEnd = drag::end,
                        onDragCancel = drag::end,
                    ) { change, amount ->
                        change.consume()
                        drag.by(amount.y)
                    }
                },
                tint = colors.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Box(Modifier.size(40.dp).clip(CircleShape).hand().clickable { model.toggle(key) }, contentAlignment = Alignment.Center) {
            Icon(
                if (row.checked) Icons.CheckBox else Icons.CheckBoxBlank,
                null,
                Modifier.size(24.dp),
                tint = if (row.checked) colors.onSurfaceVariant else colors.onSurface,
            )
        }
        BasicTextField(
            row.field,
            Modifier.weight(1f).padding(start = 4.dp).focusRequester(requester).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && row.field.selection == TextRange.Zero)
                        .also { if (it) model.join(key) }
                },
            inputTransformation = splitter,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = if (row.checked) colors.onSurfaceVariant else colors.onSurface,
                textDecoration = if (row.checked) TextDecoration.LineThrough else null,
            ).inFont(LocalNoteFont.current),
            cursorBrush = SolidColor(colors.primary),
        )
        if (focused) {
            IconButton({ model.remove(key) }, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.board_remove_item)) }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun AddRow(model: ListModel) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).hand().clickable(onClick = model::add)
            .padding(start = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Add, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.board_add_item), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TickedHeader(model: ListModel, modifier: Modifier) {
    Box(modifier) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).hand()
                .clickable { model.folded = !model.folded }.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.ExpandMore,
                null,
                Modifier.size(24.dp).rotate(if (model.folded) -90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.board_checked, model.ticked.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
