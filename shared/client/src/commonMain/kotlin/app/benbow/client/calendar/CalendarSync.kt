package app.benbow.client.calendar

import app.benbow.client.BenbowApi
import app.benbow.client.ClientJson
import app.benbow.client.Connection
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.clocksSerializer
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.CalendarProtocol
import app.benbow.core.calendar.CalendarRecord
import app.benbow.core.sync.OpStatus
import app.benbow.core.uuidV7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CalendarSyncResult {
    /** [held]: ops of kinds the server doesn't take yet (trackers' ops to a server before ADR-050), waiting here. */
    data class Done(val pushed: Int, val pulled: Int, val rejected: Int, val held: Int = 0) : CalendarSyncResult

    /** The server has no calendar store (older than 0.6.0, or its file didn't open): events wait on this device. */
    data object Unsupported : CalendarSyncResult

    /** The server, or this device's calendar cache, belongs to another store (S11): nothing is sent. */
    data object WrongServer : CalendarSyncResult

    data class Incompatible(val serverProtocol: Int) : CalendarSyncResult

    data class Failed(val reason: String) : CalendarSyncResult
}

/**
 * The calendar's sync, on its own channel after the journal's (ADR-027, docs/sync.md#calendar-store): heal a
 * restored server, push, pull. The outbox loses an op only when the server answered for it; pulled records never
 * overwrite one with pending local ops.
 */
class CalendarSync(private val db: CalendarDb, private val now: () -> Long) {
    private val q get() = db.calendarQueries
    private val clock = CalendarClock(db, now)
    private val mutex = Mutex()

    suspend fun sync(api: BenbowApi, connection: Connection): CalendarSyncResult = mutex.withLock {
        try {
            val info = api.info()
            if (info.storeId != connection.storeId) return CalendarSyncResult.WrongServer
            val server = info.calendar ?: return CalendarSyncResult.Unsupported
            if (server.protocol != CalendarProtocol.VERSION) return CalendarSyncResult.Incompatible(server.protocol)
            // A server before trackers names no kinds: it takes calendars and events (ADR-050).
            val kinds = server.kinds.ifEmpty { CalendarKind.all.toList() }.toSet()
            val store = get(STORE)
            if (store != null && store != connection.storeId) return CalendarSyncResult.WrongServer
            val epoch = get(EPOCH)?.toLongOrNull()
            val restored = epoch != null && (epoch != server.epoch || server.cursor < cursor())
            val healed = if (restored) repushAll(api, kinds) else 0
            if (store == null || restored) {
                db.transaction {
                    q.putValue(STORE, connection.storeId)
                    q.putValue(EPOCH, server.epoch.toString())
                    q.putValue(CURSOR, "0") // change numbers of a restored store were reused: pull everything
                }
            }
            val (pushed, rejected) = pushAll(api, kinds)
            val held = q.pendingCount().executeAsOne() - q.pendingCountOf(kinds).executeAsOne()
            CalendarSyncResult.Done(healed + pushed, pullAll(api), rejected, held.toInt())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CalendarSyncResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /**
     * The calendar store went back in time: every value this device holds goes back with its original clock, one op
     * per record and clock, of the [kinds] the server takes. Last-writer-wins keeps whatever the server has that is newer.
     */
    private suspend fun repushAll(api: BenbowApi, kinds: Set<String>): Int {
        val records = q.allEvents().executeAsList().map { Triple(CalendarKind.EVENT, it.id, it.fields to it.field_clocks) } +
            q.allCalendars().executeAsList().map { Triple(CalendarKind.CALENDAR, it.id, it.fields to it.field_clocks) } +
            q.allTrackerRecords().executeAsList().filter { it.kind in kinds }.map { Triple(it.kind, it.id, it.fields to it.field_clocks) }
        val ops = records.flatMap { (kind, id, state) ->
            val values = ClientJson.decodeFromString(valuesSerializer, state.first)
            ClientJson.decodeFromString(clocksSerializer, state.second).entries.groupBy({ it.value }, { it.key }).map { (hlc, fields) ->
                CalendarOp(uuidV7(now()), kind, id, fields.associateWith { values[it] }, hlc)
            }
        }
        for (batch in ops.chunked(BATCH)) {
            val results = api.calendarPush(batch).results
            // Kept visible like any rejected op (S5).
            results.filter { it.status == OpStatus.Rejected }.forEach { result ->
                val op = batch.first { it.opId == result.opId }
                db.transaction {
                    q.enqueue(op.opId, op.kind, op.id, ClientJson.encodeToString(CalendarOp.serializer(), op), now())
                    q.reject(result.message, op.opId)
                }
            }
        }
        return ops.size
    }

    /** Pushes the pending ops of the [kinds] the server takes; the others stay queued for a server that takes them. */
    private suspend fun pushAll(api: BenbowApi, kinds: Set<String>): Pair<Int, Int> {
        var pushed = 0
        var rejected = 0
        while (true) {
            val batch = q.pendingOpsOf(kinds, BATCH.toLong()).executeAsList()
            if (batch.isEmpty()) return pushed to rejected
            val results = api.calendarPush(batch.map { ClientJson.decodeFromString(CalendarOp.serializer(), it.op) }).results
            db.transaction {
                for (result in results) {
                    if (result.status == OpStatus.Rejected) {
                        q.reject(result.message, result.opId)
                        rejected++
                    } else {
                        q.acknowledge(result.opId)
                    }
                }
            }
            pushed += results.size
            if (results.size < batch.size) return pushed to rejected // the rest stays queued for the next sync
        }
    }

    private suspend fun pullAll(api: BenbowApi): Int {
        var pulled = 0
        while (true) {
            val page = api.calendarPull(cursor(), BATCH)
            db.transaction {
                // The newest clock of the page is enough to keep this device's writes after everything it saw.
                page.changes.flatMap { it.fields.values }.maxByOrNull { it.hlc }?.let { clock.observe(it.hlc) }
                page.changes.forEach(::apply)
                q.putValue(CURSOR, page.nextCursor.toString())
            }
            pulled += page.changes.size
            if (!page.hasMore) return pulled
        }
    }

    private fun apply(record: CalendarRecord) {
        if (q.pendingCountFor(record.kind, record.id).executeAsOne() > 0) return // local writes win until the server has them
        db.storeRecord(record.kind, record.id, record.fields.mapValues { it.value.value }, record.fields.mapValues { it.value.hlc }, now())
    }

    private fun get(key: String): String? = q.getValue(key).executeAsOneOrNull()

    private fun cursor(): Long = get(CURSOR)?.toLongOrNull() ?: 0

    internal companion object {
        const val STORE = "sync.store_id"
        const val EPOCH = "sync.epoch"
        const val CURSOR = "sync.cursor"
        const val BATCH = 200
    }
}
