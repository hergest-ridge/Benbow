package app.benbow.app

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.untitled
import app.benbow.app.resources.weekdays_short
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerFigures
import app.benbow.core.tracker.TrackerRecord
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * A tracker's week (ADR-050, B7): the week strip, its seven days across and the columns down, each cell answering as on
 * the month's sheet (a day's time opens its spans); the week's figures at each row's end, behind the eye.
 */
@Composable
internal fun WeekPage(
    services: AppServices,
    columns: List<TrackerColumn>,
    days: List<LocalDate>,
    today: LocalDate,
    figures: Boolean,
    write: TrackerWrite,
) {
    val ids = remember(columns) { columns.map { it.id } }
    val timed = remember(columns) { columns.filter { it.type == ColumnType.Time }.map { it.id } }
    val cellRecords by remember(ids, days.first()) {
        services.trackers.cells(ids, "${days.first()}", "${days.last()}")
    }.collectAsStateWithLifecycle(emptyList())
    val spanRecords by remember(timed, days.first()) {
        if (timed.isEmpty()) {
            flowOf(emptyList<TrackerRecord>())
        } else {
            services.trackers.spans(timed, "${days.first().minus(1, DateTimeUnit.DAY)}", "${days.last().plus(1, DateTimeUnit.DAY)}")
        }
    }.collectAsStateWithLifecycle(emptyList())
    val cells = remember(cellRecords) { cellRecords.associate { it.id to it.fields } }
    val spans = remember(spanRecords) { spanRecords.groupBy { it[SpanField.COLUMN].orEmpty() } }
    val weekdays = stringArrayResource(Res.array.weekdays_short)
    val untitled = stringResource(Res.string.untitled)
    val past = remember(days, today) { days.filter { it <= today }.map { "$it" } }
    var asked by remember { mutableStateOf<Pair<TrackerColumn, LocalDate>?>(null) }
    var spansOf by remember { mutableStateOf<Pair<TrackerColumn, LocalDate>?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(SheetSizes.HEADER_HEIGHT).padding(horizontal = SheetSizes.SIDE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(WEEK_LABEL))
            days.forEachIndexed { i, day ->
                val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        weekdays.getOrElse(i) { "" },
                        color = if (weekend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "${day.day}",
                        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (figures) Box(Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            columns.forEach { column ->
                val own = spans[column.id].orEmpty()
                Row(
                    Modifier.fillMaxWidth().height(SheetSizes.ROW_HEIGHT).padding(horizontal = SheetSizes.SIDE),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        columnTitle(column, untitled),
                        Modifier.width(WEEK_LABEL).padding(end = 4.dp),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    days.forEach { day ->
                        Box(
                            Modifier.weight(1f).height(SheetSizes.ROW_HEIGHT)
                                .then(
                                    if (day ==
                                        today
                                    ) {
                                        Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = SheetSizes.TODAY_ALPHA))
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            DayCell(
                                column,
                                "$day",
                                cells[TrackerCells.id(column.id, "$day")],
                                own,
                                figures,
                                enabled = day <= today,
                                modifier = Modifier.fillMaxWidth(),
                                write = write,
                                onSpans = { spansOf = column to day },
                                onAsk = { asked = column to day },
                            )
                        }
                    }
                    if (figures) {
                        val byDay = past.mapNotNull { day -> cells[TrackerCells.id(column.id, day)]?.let { day to it } }.toMap()
                        Text(
                            figureText(column, TrackerFigures.of(column, byDay, own, past)),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
    asked?.let { (column, day) ->
        CellDialog(column, "$day", cells[TrackerCells.id(column.id, "$day")], write) { asked = null }
    }
    spansOf?.let { (column, day) ->
        DaySpansDialog(column, day, spans[column.id].orEmpty(), write) { spansOf = null }
    }
}

/** The week's title: "26 октября – 1 ноября", the year added when it isn't this one. */
internal fun weekTitle(days: List<LocalDate>, months: List<String>): String =
    "${dateLabel("${days.first()}", months)} – ${dateLabel("${days.last()}", months)}"

/** The column names' width on the week strip. */
private val WEEK_LABEL = 92.dp
