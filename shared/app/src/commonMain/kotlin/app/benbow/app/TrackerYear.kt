package app.benbow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.months_nominative
import app.benbow.app.resources.trackers_year_total
import app.benbow.app.resources.untitled
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerFigures
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import app.benbow.ui.theme.BenbowTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * A year in pixels for one column (ADR-050, B7): the months across, their days down, each day a small square in the
 * column's ink as full as its cell (a choice in its option's color), drawn in one pass so a long year stays smooth. The
 * column is picked above; a tap on a day opens its month. Its figure for the year so far, behind the eye.
 */
@Composable
internal fun YearPage(
    services: AppServices,
    columns: List<TrackerColumn>,
    chosen: String?,
    year: Int,
    today: LocalDate,
    figures: Boolean,
    onChoose: (String) -> Unit,
    onMonth: (LocalDate) -> Unit,
) {
    val column = columns.firstOrNull { it.id == chosen } ?: columns.firstOrNull() ?: return
    val untitled = stringResource(Res.string.untitled)
    val cellRecords by remember(column.id, year) {
        services.trackers.cells(listOf(column.id), "$year-01-01", "$year-12-31")
    }.collectAsStateWithLifecycle(emptyList())
    val spanRecords by remember(column.id, year) {
        if (column.type ==
            ColumnType.Time
        ) {
            services.trackers.spans(listOf(column.id), "${year - 1}-12-31", "${year + 1}-01-01")
        } else {
            flowOf(emptyList<TrackerRecord>())
        }
    }.collectAsStateWithLifecycle(emptyList())
    val days = remember(year) { yearDays(year) }
    val byDay = remember(cellRecords) { cellRecords.associate { (it[CellField.DAY] ?: it.id.substringAfterLast(':')) to it.fields } }
    val colors = MaterialTheme.colorScheme
    val dark = BenbowTheme.colors.dark
    val tokens = BenbowTheme.colors.tokens
    val ink = column.record[ColumnField.COLOR]?.let { tokens.mark(it, dark) } ?: colors.primary
    val empty = colors.outline.copy(alpha = SheetSizes.FRAME_ALPHA)
    // Each day's color once per change of its records, not on every frame.
    val pixels = remember(byDay, spanRecords, column, ink, dark, tokens) {
        days.map { day ->
            day ?: return@map null
            val date = "$day"
            val fields = byDay[date]
            val seconds = if (column.type == ColumnType.Time) TrackerSpans.total(spanRecords, date, column.dayBy) else 0L
            val fill = pixelFill(column, fields, seconds)
            val option = if (column.type == ColumnType.Choice) column.options.find { it.id == fields?.get(CellField.VALUE) } else null
            val tint = option?.color?.let { tokens.mark(it, dark) } ?: ink
            if (fill > 0f) tint.copy(alpha = SheetSizes.PART_ALPHA + (SheetSizes.MARK_ALPHA - SheetSizes.PART_ALPHA) * fill) else empty
        }
    }
    val months = stringArrayResource(Res.array.months_nominative)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            columns.forEach { each ->
                FilterChip(each.id == column.id, {
                    onChoose(each.id)
                }, { Text(columnTitle(each, untitled), maxLines = 1) }, modifier = Modifier.hand())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Box(Modifier.width(DAY_LABELS))
            months.forEach { name ->
                Text(
                    name.take(1),
                    Modifier.weight(1f),
                    color = colors.onSurfaceVariant,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(DAY_LABELS)) {
                (1..31).forEach { day ->
                    Box(Modifier.height(PIXEL), contentAlignment = Alignment.CenterEnd) {
                        if (day == 1 ||
                            day % 5 == 0
                        ) {
                            Text("$day", color = colors.onSurfaceVariant, fontSize = 8.sp, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
            }
            Canvas(
                Modifier.weight(1f).height(PIXEL * 31).testTag("year:${column.id}").pointerInput(year, column.id) {
                    detectTapGestures { at ->
                        val month = (at.x / size.width * 12).toInt().coerceIn(0, 11)
                        val day = (at.y / size.height * 31).toInt().coerceIn(0, 30)
                        days[month * 31 + day]?.let(onMonth)
                    }
                },
            ) {
                val w = size.width / 12
                val h = size.height / 31
                val gap = minOf(w, h) * GAP
                val corner = CornerRadius(minOf(w, h) * ROUND)
                pixels.forEachIndexed { i, color ->
                    if (color == null) return@forEachIndexed
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(i / 31 * w + gap, i % 31 * h + gap),
                        size = Size(w - 2 * gap, h - 2 * gap),
                        cornerRadius = corner,
                    )
                }
            }
        }
        if (figures) {
            val past = days.filterNotNull().filter { it <= today }.map { "$it" }
            val figure = figureText(column, TrackerFigures.of(column, byDay, spanRecords, past))
            if (figure.isNotEmpty()) {
                Text(
                    stringResource(Res.string.trackers_year_total, figure),
                    Modifier.padding(start = DAY_LABELS, top = 12.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** A day's row in the year: its squares stay square on a phone, flat on a wide window. */
private val PIXEL = 16.dp
private val DAY_LABELS = 24.dp

/** The gap around a pixel and its corners' round, as parts of its side. */
private const val GAP = 0.08f
private const val ROUND = 0.2f
