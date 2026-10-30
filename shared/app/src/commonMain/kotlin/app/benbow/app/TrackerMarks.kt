package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.months_genitive
import app.benbow.app.resources.save
import app.benbow.app.resources.trackers_cell_count
import app.benbow.app.resources.trackers_cell_note
import app.benbow.app.resources.trackers_cell_text
import app.benbow.app.resources.trackers_clear
import app.benbow.client.tracker.TrackerRepository
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import app.benbow.ui.theme.BenbowTheme
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * A day's cell, drawn from the column's tokens in the tracker's mark style (ADR-058): blank paper with a faint frame when
 * empty (or the grid's lines); a check filled with the column's ink; a count filling up towards its goal; a rating
 * deeper with its level; a choice in its option's color with its emoji; a note's first words. Numbers show only with the
 * figures; a dot in the corner tells of a note to the day. A tap answers by type ([tapOf]); a long press opens the exact
 * number and the note; a right click clears the day ([dayClear]) with «Вернуть». A time column's cell opens the day's
 * spans ([onSpans]) either way. A mouse lights the cell under it.
 */
@Composable
internal fun DayCell(
    column: TrackerColumn,
    day: String,
    fields: Map<String, String?>?,
    spans: List<TrackerRecord>,
    figures: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    write: TrackerWrite,
    onSpans: () -> Unit,
    onAsk: () -> Unit,
    strongBottom: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val look = LocalSheetLook.current
    val dark = BenbowTheme.colors.dark
    val ink = columnInk(column)
    val grid = sheetGrid(ink)
    val shape = RoundedCornerShape(SheetSizes.CELL_CORNER)
    val value = fields?.get(CellField.VALUE)
    var picking by remember { mutableStateOf(false) }
    val known = column.type != null
    val time = column.type == ColumnType.Time
    val tap: () -> Unit = {
        when (val answer = if (time) null else tapOf(column, fields)) {
            null -> onSpans()
            is CellTap.Set -> write { setCell(column.id, day, answer.value) }
            CellTap.Add -> write { addToCount(column.id, day) }
            CellTap.Pick -> picking = true
            CellTap.Ask -> onAsk()
            CellTap.None -> Unit
        }
    }
    // What «Вернуть» puts back is read from the store in the same transaction as the clearing, never from what the
    // screen showed a moment before (a tap may have landed since).
    val clear by rememberUpdatedState {
        write {
            together {
                dayClear(column, day, cellNow(column.id, day), spans)?.let { cleared ->
                    clearDay(column.id, day, cleared)
                    look.onCleared { unclearDay(column.id, day, cleared) }
                }
            }
        }
    }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val mark = cellMark(column, fields, spans, day, ink, dark)
    val layer = colors.onSurface.copy(alpha = SheetSizes.HOVER_ALPHA)
    Box(
        modifier.fillMaxHeight()
            .gridCell(grid, strongBottom = strongBottom)
            .padding(if (grid == null) SheetSizes.CELL_GAP else SheetSizes.GRID_GAP)
            .clip(shape)
            .then(if (grid == null) Modifier.border(1.dp, colors.outline.copy(alpha = SheetSizes.FRAME_ALPHA), shape) else Modifier)
            .drawBehind {
                mark?.let { (color, strength, height) ->
                    val inset = if (look.mark in RAISED) SheetSizes.RAISED_INSET.toPx() else 0f
                    val room = Rect(inset, inset, size.width - inset, size.height - inset)
                    val rect = Rect(room.left, room.bottom - room.height * height, room.right, room.bottom)
                    val corner = if (look.mark in RAISED) minOf(rect.width, rect.height) / 2 else SheetSizes.CELL_CORNER.toPx()
                    drawMark(look.mark, color, rect, vertical = false, corner = corner, strength = strength)
                }
                if (hovered && enabled && known) drawHover(Rect(Offset.Zero, size), SheetSizes.CELL_CORNER.toPx(), ink, layer)
            }
            .then(
                if (enabled && known) {
                    Modifier.onSecondaryClick { clear() }.hoverable(hover).hand()
                        .combinedClickable(onClick = tap, onLongClick = if (time) onSpans else onAsk)
                } else {
                    Modifier
                },
            )
            .testTag(TrackerCells.id(column.id, day)),
        contentAlignment = Alignment.Center,
    ) {
        val number = @Composable { text: String ->
            Text(text, color = colors.onSurface, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
        when (column.type) {
            ColumnType.Count -> {
                val count = fields?.let(TrackerCells::count) ?: 0.0
                if (figures && count != 0.0) number(countText(count))
            }

            ColumnType.Scale -> value?.toIntOrNull()?.let { level -> if (figures) number("$level") }

            ColumnType.Choice -> column.options.find { it.id == value }?.let { option ->
                Text(option.icon?.let(::iconEmoji) ?: option.name.take(1), fontSize = 18.sp, maxLines = 1)
            }

            ColumnType.Note -> if (!value.isNullOrBlank()) {
                Text(
                    value,
                    Modifier.padding(horizontal = 2.dp),
                    color = colors.onSurface,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Time without a window of hours: the day's time (its spans open on a tap).
            ColumnType.Time -> {
                val seconds = TrackerSpans.total(spans, day, column.dayBy)
                if (seconds > 0 && figures) number(clockText(seconds))
            }

            ColumnType.Check, null -> Unit
        }
        if (!fields?.get(CellField.NOTE).isNullOrBlank()) {
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(SheetSizes.NOTE_DOT).background(ink, CircleShape))
        }
        DropdownMenu(picking, { picking = false }) {
            ValueRow(column, value, ink, dark) { picked ->
                picking = false
                write { setCell(column.id, day, picked) }
            }
        }
    }
}

/** What a cell's mark is: its color, how strong (a part of a full mark), and how high it fills from the bottom. */
private data class CellMark(val color: Color, val strength: Float, val height: Float = 1f)

@Composable
private fun cellMark(
    column: TrackerColumn,
    fields: Map<String, String?>?,
    spans: List<TrackerRecord>,
    day: String,
    ink: Color,
    dark: Boolean,
): CellMark? {
    val value = fields?.get(CellField.VALUE)
    val part = SheetSizes.PART_ALPHA / SheetSizes.MARK_ALPHA
    return when (column.type) {
        ColumnType.Check -> if (value == "true") CellMark(ink, 1f) else null

        ColumnType.Count -> column.fill(fields).takeIf { it > 0f }?.let { CellMark(ink, if (it >= 1f) 1f else part, it) }

        ColumnType.Scale -> value?.toIntOrNull()?.let { CellMark(ink, part + (1f - part) * column.fill(fields)) }

        ColumnType.Choice -> column.options.find { it.id == value }?.let { option ->
            CellMark(option.color?.let { BenbowTheme.colors.tokens.mark(it, dark) } ?: ink, part)
        }

        ColumnType.Note -> if (value.isNullOrBlank()) null else CellMark(ink, part)

        ColumnType.Time -> if (TrackerSpans.total(spans, day, column.dayBy) > 0) CellMark(ink, part) else null

        null -> null
    }
}

/** Styles drawn raised or glowing: a little room around them in a cell, for the shadow or the glow. */
private val RAISED = setOf(MarkStyle.Tube, MarkStyle.Glossy, MarkStyle.Neon, MarkStyle.Beads, MarkStyle.Snake)

/**
 * A mouse's right click (owner, ADR-058), seen before any tap or long press below it, which then let that press be: a
 * finger has no right click, so nothing changes on a phone.
 */
internal fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                action()
            }
        }
    }
}

/** A rating's levels or a choice's options in a row, in their colors; the one set is ringed, and «×» clears it. */
@Composable
internal fun ValueRow(column: TrackerColumn, value: String?, ink: Color, dark: Boolean, onPick: (String?) -> Unit) {
    val values = pickValues(column)
    Row(
        Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.forEachIndexed { i, each ->
            val option = column.options.find { it.id == each }
            val tint = option?.color?.let { BenbowTheme.colors.tokens.mark(it, dark) } ?: ink
            val level = if (column.type == ColumnType.Scale) (i + 1f) / values.size else 1f
            Box(
                Modifier.size(SheetSizes.PICK_SIZE)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = SheetSizes.PART_ALPHA + (SheetSizes.MARK_ALPHA - SheetSizes.PART_ALPHA) * level))
                    .then(if (each == value) Modifier.border(2.dp, tint, CircleShape) else Modifier)
                    .hand()
                    .clickable { onPick(each) }
                    .then(if (option != null) Modifier.semantics { contentDescription = option.name } else Modifier)
                    .testTag("pick:$each"),
                contentAlignment = Alignment.Center,
            ) {
                Text(option?.icon?.let(::iconEmoji) ?: option?.name?.take(1) ?: each, fontSize = if (option != null) 18.sp else 14.sp)
            }
        }
        if (value != null) {
            IconButton({ onPick(null) }, Modifier.hand()) { Icon(Icons.Close, stringResource(Res.string.trackers_clear)) }
        }
    }
}

/**
 * An hour of the sleep sheet as a target: its strip draws the hours and their snakes ([HourStrip]) and takes the taps
 * and drags; [onTap] is the hour's action for accessibility and tests. [vertical]: the hours go down a day's column.
 */
@Composable
internal fun HourCell(enabled: Boolean, tag: String, modifier: Modifier, vertical: Boolean = false, onTap: () -> Unit) {
    Box(
        modifier.then(if (vertical) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
            .then(
                if (enabled) {
                    Modifier.semantics {
                        onClick {
                            onTap()
                            true
                        }
                    }
                } else {
                    Modifier
                },
            )
            .testTag(tag),
    )
}

/**
 * A long press on a day's cell: a count's exact number, a note column's text, or a note to the day for the others.
 * Only what changed is written, in one transaction.
 */
@Composable
internal fun CellDialog(column: TrackerColumn, day: String, fields: Map<String, String?>?, write: TrackerWrite, onDismiss: () -> Unit) {
    val was = fields.orEmpty()
    val count = column.type == ColumnType.Count
    val text = column.type == ColumnType.Note
    val counted = TrackerCells.count(was)
    var number by remember { mutableStateOf(if (count && counted != 0.0) countText(counted) else "") }
    var value by remember { mutableStateOf(was[CellField.VALUE].orEmpty()) }
    var note by remember { mutableStateOf(was[CellField.NOTE].orEmpty()) }
    val total = if (number.isBlank()) 0.0 else number.trim().replace(',', '.').toDoubleOrNull()
    val months = stringArrayResource(Res.array.months_genitive)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${column.name} · ${dateLabel(day, months)}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (count) {
                    OutlinedTextField(
                        number,
                        { number = it },
                        label = { Text(stringResource(Res.string.trackers_cell_count)) },
                        suffix = column.unit?.let { unit -> @Composable { Text(unit) } },
                        isError = total == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.testTag("cell-count"),
                    )
                }
                if (text) {
                    OutlinedTextField(value, { value = it }, label = { Text(stringResource(Res.string.trackers_cell_text)) }, maxLines = 4)
                } else {
                    OutlinedTextField(note, { note = it }, label = { Text(stringResource(Res.string.trackers_cell_note)) }, maxLines = 4)
                }
            }
        },
        confirmButton = {
            TextButton({
                write {
                    together {
                        if (count && total != null && total != counted) setCount(column.id, day, total)
                        if (text && value.trim() != was[CellField.VALUE].orEmpty()) setCell(column.id, day, value.trim().ifEmpty { null })
                        if (!text && note.trim() != was[CellField.NOTE].orEmpty()) {
                            setCell(column.id, day, note.trim().ifEmpty { null }, CellField.NOTE)
                        }
                    }
                }
                onDismiss()
            }, Modifier.hand(total != null), enabled = total != null) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** A tap's change to a time column's spans. */
internal fun TrackerRepository.editSpan(column: String, edit: SpanEdit) {
    when (edit) {
        is SpanEdit.Add -> addSpan(column, edit.start, edit.end)
        is SpanEdit.Change -> updateSpan(edit.id, edit.fields)
        is SpanEdit.Trash -> moveSpanToTrash(edit.id)
    }
}
