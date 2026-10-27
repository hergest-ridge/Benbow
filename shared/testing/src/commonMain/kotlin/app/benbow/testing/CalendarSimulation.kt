package app.benbow.testing

import app.benbow.core.calendar.CalendarChange
import app.benbow.core.calendar.CalendarKind
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.CalendarRecord
import app.benbow.core.calendar.CalendarRules
import app.benbow.core.calendar.CalendarStore
import app.benbow.core.calendar.FieldHistory
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.HlcClock
import app.benbow.core.sync.OpResult
import app.benbow.core.sync.OpStatus
import app.benbow.core.tracker.TrackerKind
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic simulation of the calendar channel (ADR-027), runnable against any [CalendarStore]: devices
 * with drifting clocks write the same few records (calendars, events and trackers' cells, ADR-050) while requests and
 * responses get lost. Invariants: no op is applied twice, nothing is rejected, every field ends with the value of its
 * latest write (by HLC), every value written is that or in the store's history, and every device converges.
 */
class CalendarSimulation(private val storeFactory: () -> CalendarStore) {
    val outcomes = HashMap<OpStatus, Int>()

    fun run(seed: Long, steps: Int = 80, clientCount: Int = 3) {
        val rnd = Random(seed)
        val store = CountingCalendarStore(storeFactory())
        val server = CalendarRules(store)
        val clients = List(clientCount) { SimCalendarClient("c$it", rnd, outcomes) }
        val written = mutableListOf<CalendarOp>()

        repeat(steps) {
            val client = clients.random(rnd)
            if (rnd.nextInt(10) < 6) written += client.put() else client.sync(server, lossy = true)
        }
        repeat(4) { clients.forEach { it.sync(server, lossy = false) } }

        clients.forEach { c -> assertTrue(c.outbox.isEmpty(), "outbox of ${c.name} drained (seed $seed)") }
        assertTrue(clients.none { it.rejected.isNotEmpty() }, "no op rejected (seed $seed)")
        store.applyCounts.forEach { (op, count) -> assertEquals(1, count, "op $op applied $count times (seed $seed)") }

        val latest = HashMap<Triple<String, String, String>, Pair<Hlc, String?>>()
        for (op in written) {
            for ((field, value) in op.fields) {
                val key = Triple(op.kind, op.id, field)
                if (latest[key]?.let { op.hlc > it.first } != false) latest[key] = op.hlc to value
            }
        }
        for ((key, expected) in latest) {
            val stored = store.record(key.first, key.second)?.fields?.get(key.third)
            assertEquals(expected.second, stored?.value, "$key keeps its latest write (seed $seed)")
            assertEquals(expected.first, stored?.hlc)
        }
        // Nothing written is discarded: what doesn't show is in the history (ADR-050).
        for (op in written) {
            for ((field, value) in op.fields) {
                val shown = store.record(op.kind, op.id)?.fields?.get(field)?.value == value
                val kept = store.history(op.kind, op.id).any { it.field == field && it.value == value }
                assertTrue(shown || kept, "${op.kind} ${op.id} $field = $value kept (seed $seed)")
            }
        }
        for (change in store.changesSince(0, Int.MAX_VALUE).distinctBy { it.kind to it.id }) {
            val record = store.record(change.kind, change.id)!!
            for (c in clients) {
                val local: Map<String, String?>? = c.records[change.kind to change.id]
                assertEquals(record.fields.mapValues { it.value.value }, local, "${c.name} converged (seed $seed)")
            }
        }
    }

    /** Asserts that lost responses (retries the server dedupes) occurred across the runs so far. */
    fun assertRetriesCovered() {
        assertTrue((outcomes[OpStatus.Duplicate] ?: 0) > 0, "no retried op in any history: $outcomes")
    }

    private class SimCalendarClient(val name: String, private val rnd: Random, private val outcomes: MutableMap<OpStatus, Int>) {
        private var time = 1_000L
        private val clock = HlcClock(name) { time }
        private var counter = 0
        private var cursor = 0L
        val outbox = ArrayDeque<CalendarOp>()
        val rejected = mutableListOf<OpResult>()
        val records = HashMap<Pair<String, String>, MutableMap<String, String?>>()

        /** Writes one or two fields of one of a few records every device shares, so writes collide. */
        fun put(): CalendarOp {
            time += rnd.nextLong(-50, 100) // drifting clock
            val kind = KINDS[rnd.nextInt(KINDS.size)]
            val id = "$kind-${rnd.nextInt(4)}"
            val fields = FIELDS.shuffled(rnd).take(1 + rnd.nextInt(2)).associateWith {
                if (rnd.nextInt(6) ==
                    0
                ) {
                    null
                } else {
                    "$name-${counter++}"
                }
            }
            val op = CalendarOp("$name-op-${counter++}", kind, id, fields, clock.next())
            records.getOrPut(kind to id) { HashMap() }.putAll(fields)
            outbox += op
            return op
        }

        fun sync(server: CalendarRules, lossy: Boolean) {
            fun lost() = lossy && rnd.nextInt(5) == 0
            if (outbox.isNotEmpty()) {
                if (lost()) return // request lost
                val results = server.push(outbox.toList())
                if (lost()) return // response lost: everything is sent again, the server dedupes
                for (result in results) {
                    outcomes[result.status] = (outcomes[result.status] ?: 0) + 1
                    check(outbox.removeFirst().opId == result.opId)
                    if (result.status == OpStatus.Rejected) rejected += result
                }
            }
            while (true) {
                if (lost()) return
                val page = server.pull(cursor, limit = 3)
                for (record in page.changes) {
                    record.fields.values.forEach { clock.observe(it.hlc) }
                    // Local writes win until the server has them.
                    if (outbox.any { it.kind == record.kind && it.id == record.id }) continue
                    records[record.kind to record.id] = record.fields.mapValuesTo(HashMap()) { it.value.value }
                }
                cursor = page.nextCursor
                if (!page.hasMore) return
            }
        }

        private companion object {
            val FIELDS = listOf("title", "start", "deleted")
            val KINDS = listOf(CalendarKind.CALENDAR, CalendarKind.EVENT, CalendarKind.EVENT, TrackerKind.CELL)
        }
    }
}

private class CountingCalendarStore(private val delegate: CalendarStore) : CalendarStore by delegate {
    val applyCounts = HashMap<String, Int>()

    override fun recordApplied(result: OpResult) {
        applyCounts[result.opId] = (applyCounts[result.opId] ?: 0) + 1
        delegate.recordApplied(result)
    }
}

/** Reference in-memory [CalendarStore] with the same contract the server's SQLite store must meet. */
class InMemoryCalendarStore : CalendarStore {
    private val records = LinkedHashMap<Pair<String, String>, CalendarRecord>()
    private val history = ArrayList<FieldHistory>()
    private val applied = HashMap<String, OpResult>()
    private val changes = ArrayList<CalendarChange>()

    override fun <T> transaction(block: () -> T): T = block()
    override fun appliedResult(opId: String): OpResult? = applied[opId]
    override fun recordApplied(result: OpResult) {
        applied[result.opId] = result
    }
    override fun record(kind: String, id: String): CalendarRecord? = records[kind to id]
    override fun putRecord(record: CalendarRecord) {
        records[record.kind to record.id] = record
    }
    override fun appendHistory(entry: FieldHistory) {
        history += entry
    }
    override fun history(kind: String, id: String) = history.filter { it.kind == kind && it.id == id }
    override fun appendChange(kind: String, id: String): Long {
        val seq = changes.size + 1L
        changes += CalendarChange(seq, kind, id)
        return seq
    }
    override fun changesSince(cursor: Long, limit: Int) = changes.filter { it.seq > cursor }.take(limit)
}
