package app.benbow.client.tracker

import app.benbow.client.calendar.CalendarClock
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.calendar.db.Tracker_record
import app.benbow.client.calendar.decodeValues
import app.benbow.client.calendar.putRecord
import app.benbow.client.calendar.recordState
import app.benbow.core.board.BoardRank
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerColumn
import app.benbow.core.tracker.TrackerExport
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerKind
import app.benbow.core.tracker.TrackerRecord
import app.benbow.core.tracker.TrackerSpans
import app.benbow.core.uuidV7
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

/**
 * Trackers on this device (ADR-050): records in the calendar's file beside calendars and events. Every write is one
 * local transaction that also queues its op (S5); the ops go to a server that keeps trackers and wait for one otherwise.
 */
class TrackerRepository(
    private val db: CalendarDb,
    private val now: () -> Long,
    private val context: CoroutineContext = Dispatchers.Default,
) {
    private val q get() = db.calendarQueries

    /** Trackers outside the trash, archived ones included, in their order. */
    fun trackers(): Flow<List<TrackerRecord>> = q.trackerRecords(TrackerKind.TRACKER).asFlow().mapToList(context).map { rows ->
        rows.map(::record).filter { !it.deleted }.sortedWith(TrackerRecord.byRank)
    }

    /** Trackers in the trash, for «Вернуть». */
    fun trashedTrackers(): Flow<List<TrackerRecord>> = q.trackerRecords(TrackerKind.TRACKER).asFlow().mapToList(context).map { rows ->
        rows.map(::record).filter { it.deleted }.sortedWith(TrackerRecord.byRank)
    }

    /** [tracker]'s columns outside the trash, archived ones included, in their order. */
    fun columns(tracker: String): Flow<List<TrackerColumn>> =
        q.trackerRecordsOf(TrackerKind.COLUMN, listOf(tracker)).asFlow().mapToList(context).map { rows ->
            rows.map(::record).filter { !it.deleted }.sortedWith(TrackerRecord.byRank).map(::TrackerColumn)
        }

    /** The cells of [columns] on [fromDay]..[toDay]. */
    fun cells(columns: Collection<String>, fromDay: String, toDay: String): Flow<List<TrackerRecord>> =
        q.trackerCellsBetween(columns = columns, fromDay = fromDay, toDay = toDay).asFlow().mapToList(context).map { rows ->
            rows.map(::record)
        }

    /** The spans of [columns] on any day of [fromDay]..[toDay], outside the trash; one over midnight is on both days. */
    fun spans(columns: Collection<String>, fromDay: String, toDay: String): Flow<List<TrackerRecord>> =
        q.trackerSpansBetween(columns = columns, fromDay = fromDay, toDay = toDay).asFlow().mapToList(context).map { rows ->
            rows.map(::record)
        }

    /** A new tracker at the end of the list with [columns] (each a column's fields) in that order; its id. */
    fun createTracker(fields: Map<String, String?>, columns: List<Map<String, String?>> = emptyList()): String {
        val id = uuidV7(now())
        db.transaction {
            val rank = BoardRank.between(lastRank(q.trackerRecords(TrackerKind.TRACKER).executeAsList()), null)
            db.putRecord(TrackerKind.TRACKER, id, fields + (TrackerField.RANK to rank), now)
            var columnRank: String? = null
            for (column in columns) {
                columnRank = BoardRank.between(columnRank, null)
                db.putRecord(
                    TrackerKind.COLUMN,
                    uuidV7(now()),
                    column + mapOf(ColumnField.TRACKER to id, ColumnField.RANK to columnRank),
                    now,
                )
            }
        }
        return id
    }

    /** Sets a tracker's fields (name, icon, color, its look, view, figures, calendar, archived…); null takes one off. */
    fun updateTracker(id: String, fields: Map<String, String?>) = put(TrackerKind.TRACKER, id, fields)

    /** To the trash with its columns and marks, which stay as they are: «Вернуть» brings it all back. */
    fun moveToTrash(tracker: String) = put(TrackerKind.TRACKER, tracker, mapOf(TrackerField.DELETED to "true"))

    fun restore(tracker: String) = put(TrackerKind.TRACKER, tracker, mapOf(TrackerField.DELETED to null))

    /** A new column at the end of [tracker]'s; its id. */
    fun addColumn(tracker: String, fields: Map<String, String?>): String {
        val id = uuidV7(now())
        db.transaction {
            val rank = BoardRank.between(lastRank(q.trackerRecordsOf(TrackerKind.COLUMN, listOf(tracker)).executeAsList()), null)
            db.putRecord(TrackerKind.COLUMN, id, fields + mapOf(ColumnField.TRACKER to tracker, ColumnField.RANK to rank), now)
        }
        return id
    }

    /** Sets a column's fields (header texts, icon, color, type, options, goal…); its cells are never rewritten. */
    fun updateColumn(id: String, fields: Map<String, String?>) = put(TrackerKind.COLUMN, id, fields)

    /**
     * Puts the tracker or column [id] (of [kind]) at [at] among [others], the records of its list as shown without it:
     * its rank between theirs (others get one first when they lack it), so a move rewrites nothing else.
     */
    fun place(kind: String, id: String, others: List<TrackerRecord>, at: Int) = db.transaction {
        BoardRank.place(id, others.map { it.id to it[TrackerField.RANK] }, at).forEach { (each, rank) ->
            db.putRecord(kind, each, mapOf(TrackerField.RANK to rank), now)
        }
    }

    /** [field] of [column]'s cell on [day]: a check's "true", a rating, an option's id, a note; null clears it. */
    fun setCell(column: String, day: String, value: String?, field: String = CellField.VALUE) = cell(column, day, mapOf(field to value))

    /** A tap adding [delta] to a count: this device's own part only, so taps on two devices both count. */
    fun addToCount(column: String, day: String, delta: Double = 1.0) = db.transaction {
        val id = TrackerCells.id(column, day)
        writeCell(column, day, TrackerCells.add(db.recordState(TrackerKind.CELL, id).first, device(), delta))
    }

    /** The count set to [total]: this device's part becomes the total less the others'. */
    fun setCount(column: String, day: String, total: Double) = db.transaction {
        val id = TrackerCells.id(column, day)
        writeCell(column, day, TrackerCells.setTotal(db.recordState(TrackerKind.CELL, id).first, device(), total))
    }

    /**
     * Starts [column]'s timer at [at] ("YYYY-MM-DDTHH:MM:SS"); only its start is kept, so it survives a closed app and
     * can be stopped elsewhere. A column set to `timer = one` stops its tracker's other running timers first.
     */
    fun startTimer(column: String, at: String) = db.transaction {
        val values = db.recordState(TrackerKind.COLUMN, column).first
        if (values[ColumnField.RUNNING] != null) return@transaction
        val tracker = values[ColumnField.TRACKER]
        if (values[ColumnField.TIMER] == "one" && tracker != null) {
            q.trackerRecordsOf(TrackerKind.COLUMN, listOf(tracker)).executeAsList()
                .filter { it.id != column && decodeValues(it.fields)[ColumnField.RUNNING] != null }
                .forEach { stopInTransaction(it.id, at) }
        }
        db.putRecord(TrackerKind.COLUMN, column, mapOf(ColumnField.RUNNING to at), now)
    }

    /** Stops [column]'s timer at [at]: its span, then the cleared start (one span even when two devices stop it). */
    fun stopTimer(column: String, at: String) = db.transaction { stopInTransaction(column, at) }

    /** A span of time put in by hand ([end] null: still going); its id. */
    fun addSpan(column: String, start: String, end: String?, note: String? = null): String {
        val id = uuidV7(now())
        put(TrackerKind.SPAN, id, mapOf(SpanField.COLUMN to column, SpanField.START to start, SpanField.END to end, SpanField.NOTE to note))
        return id
    }

    fun updateSpan(id: String, fields: Map<String, String?>) = put(TrackerKind.SPAN, id, fields)

    fun moveSpanToTrash(id: String) = put(TrackerKind.SPAN, id, mapOf(SpanField.DELETED to "true"))

    /** Writes that belong together (a night split in two) in one transaction: all of them land, or none. */
    fun together(writes: TrackerRepository.() -> Unit) = db.transaction { writes() }

    /** Every tracker outside the trash as files readable without the app, for the archive (`trackers/` in the ZIP). */
    fun exportFiles(): List<TrackerExport.File> = db.trackerExportFiles()

    private fun stopInTransaction(column: String, at: String) {
        val running = db.recordState(TrackerKind.COLUMN, column).first[ColumnField.RUNNING] ?: return
        val (span, clear) = TrackerSpans.stop(column, running, at)
        db.putRecord(TrackerKind.SPAN, span.id, span.fields, now)
        db.putRecord(TrackerKind.COLUMN, column, clear, now)
    }

    private fun cell(column: String, day: String, fields: Map<String, String?>) = db.transaction { writeCell(column, day, fields) }

    /** A cell's first write names its column and day too; later ones carry only what changed. */
    private fun writeCell(column: String, day: String, fields: Map<String, String?>) {
        val id = TrackerCells.id(column, day)
        val first = db.recordState(TrackerKind.CELL, id).first.isEmpty()
        db.putRecord(TrackerKind.CELL, id, (if (first) TrackerCells.create(column, day) else emptyMap()) + fields, now)
    }

    private fun put(kind: String, id: String, fields: Map<String, String?>) {
        if (fields.isNotEmpty()) db.transaction { db.putRecord(kind, id, fields, now) }
    }

    private fun device(): String = CalendarClock(db, now).node()

    private fun lastRank(rows: List<Tracker_record>): String? =
        rows.mapNotNull { decodeValues(it.fields)[TrackerField.RANK]?.takeIf(BoardRank::isValid) }.maxOrNull()
}

/** A row as the core's rules read it. */
internal fun record(row: Tracker_record) = TrackerRecord(row.id, decodeValues(row.fields))

/** Every tracker outside the trash as files ([TrackerExport]): `tracker.json`, a CSV per year, `spans.csv`. */
internal fun CalendarDb.trackerExportFiles(): List<TrackerExport.File> {
    val rows = calendarQueries.allTrackerRecords().executeAsList().groupBy({ it.kind }, ::record)
    fun of(kind: String) = rows[kind].orEmpty()
    return TrackerExport.files(of(TrackerKind.TRACKER), of(TrackerKind.COLUMN), of(TrackerKind.CELL), of(TrackerKind.SPAN))
}
