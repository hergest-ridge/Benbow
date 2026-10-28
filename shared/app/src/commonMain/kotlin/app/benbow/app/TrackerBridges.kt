package app.benbow.app

import app.benbow.client.IoDispatcher
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.ColumnType
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerSpans
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

// Trackers seen from outside the app's screens (ADR-050, B6/B8): a running timer's notification and its «Стоп» on
// Android. The platform code gets ready words and times; the records stay behind TrackerRepository.

/** A timer running on a tracker, as an ongoing notification shows it: its column's emoji and name, its tracker's. */
class RunningTimer(val columnId: String, val title: String, val tracker: String, val startedAtMillis: Long)

/** Timers running on trackers outside the trash and the archive. */
fun AppServices.runningTimers(): Flow<List<RunningTimer>> {
    return combine(trackers.trackers(), trackers.runningColumns()) { list, columns ->
        val shown = list.filter { !it.archived }.associateBy { it.id }
        columns.mapNotNull { column ->
            val tracker = column.tracker?.let(shown::get) ?: return@mapNotNull null
            val since = column.running?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
            RunningTimer(
                column.id,
                columnTitle(column, tracker[TrackerField.NAME].orEmpty()),
                trackerTitle(tracker, ""),
                since.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
            )
        }
    }
}

/** Stops [columnId]'s timer now (a notification's «Стоп»); done when it returns. */
suspend fun AppServices.stopTimerNow(columnId: String) {
    withContext(IoDispatcher) { trackers.stopTimer(columnId, localNow()) }
}

/** A column's mark today on a widget: its words (an emoji, a number), whether it is marked, whether a tap marks it. */
class WidgetMark(val columnId: String, val text: String, val marked: Boolean, val tickable: Boolean)

/** A tracker on a widget: its emoji and name, and today's marks of its first columns. */
class WidgetTracker(val id: String, val title: String, val marks: List<WidgetMark>)

/**
 * Today's marks of the first [limit] trackers outside the archive, for Android's widget (ADR-050, B8). The numbers
 * follow each tracker's eye.
 */
suspend fun AppServices.trackersToday(limit: Int = WIDGET_TRACKERS): List<WidgetTracker> = withContext(IoDispatcher) {
    val day = "${trackerToday()}"
    trackers.trackers().first().filter { !it.archived }.take(limit).map { tracker ->
        val columns = trackers.columns(tracker.id).first().filter { !it.archived }.take(WIDGET_COLUMNS)
        val cells = trackers.cells(columns.map { it.id }, day, day).first().associate { it.id to it.fields }
        val timed = columns.filter { it.type == ColumnType.Time }.map { it.id }
        val spans = if (timed.isEmpty()) emptyList() else trackers.spans(timed, day, day).first()
        val figures = tracker[TrackerField.FIGURES] != FIGURES_HIDDEN
        WidgetTracker(
            tracker.id,
            trackerTitle(tracker, ""),
            columns.map { column ->
                val own = spans.filter { it[SpanField.COLUMN] == column.id }
                val mark = markText(column, cells[TrackerCells.id(column.id, day)], TrackerSpans.total(own, day, column.dayBy), figures)
                val icon = column.record[ColumnField.ICON]?.let(::iconEmoji) ?: column.short.take(1)
                WidgetMark(column.id, mark ?: icon, mark != null, column.type == ColumnType.Check || column.type == ColumnType.Count)
            },
        )
    }
}

/** A widget's tap on a check or a count today: the check flips, the count gets one more; done when it returns. */
suspend fun AppServices.tickToday(columnId: String) {
    withContext(IoDispatcher) {
        val column = trackers.column(columnId) ?: return@withContext
        val day = "${trackerToday()}"
        when (column.type) {
            ColumnType.Check -> {
                val marked = trackers.cells(listOf(columnId), day, day).first().singleOrNull()?.get(CellField.VALUE) == "true"
                trackers.setCell(columnId, day, if (marked) null else "true")
            }

            ColumnType.Count -> trackers.addToCount(columnId, day)

            else -> Unit
        }
    }
}

/** Now on this device's clock, as spans and timers keep it. */
internal fun localNow(): String = timeOf(localDateTimeNow())

internal fun localDateTimeNow(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

/**
 * Trackers' today: the clock's date, the day a timer's span and the sleep sheet's midnight count by (ADR-052), not the
 * journal's day from [DAY_START_HOUR].
 */
internal fun trackerToday(): LocalDate = localDateTimeNow().date

/** How many trackers, and columns of each, a widget shows. */
private const val WIDGET_TRACKERS = 3
private const val WIDGET_COLUMNS = 6
