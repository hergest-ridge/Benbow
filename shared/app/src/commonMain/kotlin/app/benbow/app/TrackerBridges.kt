package app.benbow.app

import app.benbow.client.IoDispatcher
import app.benbow.core.tracker.TrackerField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

// Trackers seen from outside the app's screens (ADR-050, B6/B8): a running timer's notification and its «Стоп» on
// Android. The platform code gets ready words and times; the records stay behind TrackerRepository.

/** A timer running on a tracker, as an ongoing notification shows it: its column's emoji and name, its tracker's. */
class RunningTimer(val columnId: String, val title: String, val tracker: String, val startedAtMillis: Long)

/** Timers running on trackers outside the trash and the archive; none where trackers don't show (the owner's app). */
fun AppServices.runningTimers(): Flow<List<RunningTimer>> {
    if (!trackersShown) return flowOf(emptyList())
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

/** Now on this device's clock, as spans and timers keep it. */
internal fun localNow(): String = timeOf(localDateTimeNow())

internal fun localDateTimeNow(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
