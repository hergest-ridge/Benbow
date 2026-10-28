package app.benbow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import app.benbow.app.resources.trackers_timer_start
import app.benbow.app.resources.trackers_timer_stop
import app.benbow.client.tracker.TrackerRepository
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import app.benbow.ui.theme.BenbowPalette
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * A day's cell, drawn from the column's tokens: blank paper with a faint frame when empty; a check filled with the
 * column's ink; a count filling up towards its goal; a rating deeper with its level; a choice in its option's color with
 * its emoji; a note's first words. Numbers show only with the figures; a dot in the corner tells of a note to the day.
 * A tap answers by type ([tapOf]); a long press opens the exact number and the note. A time column's cell opens the
 * day's spans ([onSpans]) either way.
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
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val ink = column.record[ColumnField.COLOR]?.let { BenbowPalette.text(it, dark) } ?: colors.primary
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
    Box(
        modifier.fillMaxHeight()
            .padding(SheetSizes.CELL_GAP)
            .clip(shape)
            .border(1.dp, colors.outline.copy(alpha = SheetSizes.FRAME_ALPHA), shape)
            .then(
                if (enabled &&
                    known
                ) {
                    Modifier.hand().combinedClickable(onClick = tap, onLongClick = if (time) onSpans else onAsk)
                } else {
                    Modifier
                },
            )
            .testTag(TrackerCells.id(column.id, day)),
        contentAlignment = Alignment.Center,
    ) {
        val number = @Composable { text: String ->
            Text(text, color = colors.onSurface, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
        when (column.type) {
            ColumnType.Check -> if (value == "true") Box(Modifier.fillMaxSize().background(ink.copy(alpha = SheetSizes.MARK_ALPHA)))

            ColumnType.Count -> {
                val fill = column.fill(fields)
                if (fill > 0f) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fill)
                            .background(ink.copy(alpha = if (fill >= 1f) SheetSizes.MARK_ALPHA else SheetSizes.PART_ALPHA)),
                    )
                }
                val count = fields?.let(TrackerCells::count) ?: 0.0
                if (figures && count != 0.0) number(countText(count))
            }

            ColumnType.Scale -> value?.toIntOrNull()?.let { level ->
                Box(
                    Modifier.fillMaxSize().background(
                        ink.copy(
                            alpha =
                            SheetSizes.PART_ALPHA + (SheetSizes.MARK_ALPHA - SheetSizes.PART_ALPHA) * column.fill(fields),
                        ),
                    ),
                )
                if (figures) number("$level")
            }

            ColumnType.Choice -> column.options.find { it.id == value }?.let { option ->
                Box(
                    Modifier.fillMaxSize().background(
                        (
                            option.color?.let {
                                BenbowPalette.text(it, dark)
                            } ?: ink
                            ).copy(alpha = SheetSizes.PART_ALPHA),
                    ),
                )
                Text(option.icon?.let(::iconEmoji) ?: option.name.take(1), fontSize = 16.sp, maxLines = 1)
            }

            ColumnType.Note -> if (!value.isNullOrBlank()) {
                Box(Modifier.fillMaxSize().background(ink.copy(alpha = SheetSizes.PART_ALPHA)))
                Text(
                    value,
                    Modifier.padding(horizontal = 2.dp),
                    color = colors.onSurface,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Time without a window of hours: the day's time (its spans open on a tap).
            ColumnType.Time -> {
                val seconds = TrackerSpans.total(spans, day, column.dayBy)
                if (seconds > 0) {
                    Box(Modifier.fillMaxSize().background(ink.copy(alpha = SheetSizes.PART_ALPHA)))
                    if (figures) number(clockText(seconds))
                }
            }

            null -> Unit
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
            val tint = option?.color?.let { BenbowPalette.text(it, dark) } ?: ink
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
 * An hour of the sleep sheet: painted hours join into one bar with round ends; an empty hour is a faint dot; hours a
 * drag passes show a lighter bar until it ends. Taps and drags are the row's (DayRow); [onTap] is the hour's action for
 * accessibility and tests. [vertical]: the hours go down a day's column (a month across, ADR-051), the bar with them.
 */
@Composable
internal fun HourCell(
    column: TrackerColumn,
    painted: Boolean,
    first: Boolean,
    last: Boolean,
    previewed: Boolean,
    enabled: Boolean,
    tag: String,
    modifier: Modifier,
    vertical: Boolean = false,
    onTap: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = column.record[ColumnField.COLOR]?.let { BenbowPalette.text(it, colors.background.luminance() < 0.5f) } ?: colors.primary
    // The bar's thickness across the way the hours go, and its length along it.
    val bar = if (vertical) {
        Modifier.fillMaxHeight().fillMaxWidth(
            SheetSizes.BAR_HEIGHT,
        )
    } else {
        Modifier.fillMaxWidth().fillMaxHeight(SheetSizes.BAR_HEIGHT)
    }
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
        contentAlignment = Alignment.Center,
    ) {
        when {
            previewed -> Box(bar.background(ink.copy(alpha = SheetSizes.PART_ALPHA)))

            painted -> {
                val round = { at: Boolean -> if (at) SheetSizes.BAR_CORNER else 0.dp }
                val gap = { at: Boolean -> if (at) 1.dp else 0.dp }
                Box(
                    bar.then(
                        if (vertical) {
                            Modifier.padding(top = gap(first), bottom = gap(last))
                        } else {
                            Modifier.padding(start = gap(first), end = gap(last))
                        },
                    ).background(
                        ink.copy(alpha = SheetSizes.MARK_ALPHA),
                        if (vertical) {
                            RoundedCornerShape(
                                topStart = round(first),
                                topEnd = round(first),
                                bottomEnd = round(last),
                                bottomStart = round(last),
                            )
                        } else {
                            RoundedCornerShape(
                                topStart = round(first),
                                topEnd = round(last),
                                bottomEnd = round(last),
                                bottomStart = round(first),
                            )
                        },
                    ),
                )
            }

            else -> Box(Modifier.size(3.dp).background(colors.outline.copy(alpha = SheetSizes.DOT_ALPHA), CircleShape))
        }
    }
}

/** A time column's timer in its header: ▶ starts it now, ■ stops it; while it runs, its time ticks beside. */
@Composable
internal fun TimerButton(column: TrackerColumn, write: TrackerWrite) {
    val colors = MaterialTheme.colorScheme
    val ink = column.record[ColumnField.COLOR]?.let { BenbowPalette.text(it, colors.background.luminance() < 0.5f) } ?: colors.primary
    val running = column.running
    var now by remember { mutableStateOf(localDateTimeNow()) }
    if (running != null) {
        LaunchedEffect(running) {
            while (true) {
                now = localDateTimeNow()
                delay(TICK_MILLIS)
            }
        }
    }
    val label = stringResource(if (running == null) Res.string.trackers_timer_start else Res.string.trackers_timer_stop)
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .hand()
            .clickable { if (running == null) write { startTimer(column.id, localNow()) } else write { stopTimer(column.id, localNow()) } }
            .semantics { contentDescription = label }
            .testTag("timer:${column.id}")
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (running == null) Icons.Play else Icons.Stop, null, Modifier.size(16.dp), tint = ink)
        if (running != null) Text(tickText(runningSeconds(running, now)), color = ink, fontSize = 10.sp, maxLines = 1)
    }
}

/** A running timer's time is drawn again this often. */
private const val TICK_MILLIS = 1_000L

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
