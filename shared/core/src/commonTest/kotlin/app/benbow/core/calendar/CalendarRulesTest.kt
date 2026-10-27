package app.benbow.core.calendar

import app.benbow.core.sync.Hlc
import app.benbow.core.sync.OpStatus
import app.benbow.core.tracker.TrackerKind
import app.benbow.testing.CalendarSimulation
import app.benbow.testing.InMemoryCalendarStore
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarRulesTest {
    private val store = InMemoryCalendarStore()
    private val rules = CalendarRules(store)

    private fun put(op: String, fields: Map<String, String?>, wall: Long, kind: String = CalendarKind.EVENT, id: String = "e1") =
        CalendarOp(op, kind, id, fields, Hlc(wall, 0, "phone"))

    @Test
    fun randomHistoriesConvergeToTheLatestWrites() = runTest {
        val simulation = CalendarSimulation(::InMemoryCalendarStore)
        checkAll(Arb.long()) { seed -> simulation.run(seed) }
        simulation.assertRetriesCovered()
    }

    @Test
    fun eachFieldKeepsItsLatestWrite() {
        rules.push(listOf(put("a", mapOf("title" to "Врач", "start" to "2026-10-14T09:30"), wall = 10)))
        rules.push(listOf(put("b", mapOf("title" to "Стоматолог"), wall = 5))) // older: loses
        rules.push(listOf(put("c", mapOf("start" to "2026-10-15"), wall = 20)))
        val fields = store.record(CalendarKind.EVENT, "e1")!!.fields.mapValues { it.value.value }
        assertEquals(mapOf("title" to "Врач", "start" to "2026-10-15"), fields)
        assertEquals(3, store.changesSince(0, 10).size, "a losing write is logged too, so the device re-pulls the winner")
    }

    @Test
    fun whatDoesntShowStaysInTheHistory() {
        rules.push(listOf(put("a", mapOf("title" to "Врач", "start" to "2026-10-14"), wall = 10)))
        rules.push(listOf(put("b", mapOf("title" to "Стоматолог"), wall = 5))) // older: loses, and is kept
        rules.push(listOf(put("c", mapOf("title" to "Окулист", "start" to "2026-10-14"), wall = 20))) // replaces "Врач"
        rules.push(listOf(put("d", mapOf("title" to "Окулист"), wall = 30))) // the same value: nothing to keep
        val kept = store.history(CalendarKind.EVENT, "e1").map { Triple(it.field, it.value, it.hlc.wall) }
        assertEquals(listOf(Triple("title", "Стоматолог", 5L), Triple("title", "Врач", 10L)), kept)
    }

    @Test
    fun trackersRecordsGoInBesideTheCalendars() {
        val ops = TrackerKind.all.mapIndexed { i, kind -> put("t$i", mapOf("name" to kind), wall = i.toLong() + 1, kind = kind, id = "x") }
        assertEquals(List(4) { OpStatus.Applied }, rules.push(ops).map { it.status })
        assertEquals(setOf("calendar", "event", "tracker", "column", "cell", "span"), CalendarProtocol.KINDS)
    }

    @Test
    fun repeatsAreDuplicatesAndBadOpsAreRejectedNotDropped() {
        val op = put("a", mapOf("title" to "Врач"), wall = 1)
        assertEquals(OpStatus.Applied, rules.push(listOf(op)).single().status)
        assertEquals(OpStatus.Duplicate, rules.push(listOf(op)).single().status)
        val bad = rules.push(listOf(put("b", mapOf("title" to "x"), wall = 2, kind = "entry"), put("c", emptyMap(), wall = 3)))
        assertEquals(listOf(OpStatus.Rejected, OpStatus.Rejected), bad.map { it.status })
        assertEquals(null, store.record("entry", "e1"))
    }

    @Test
    fun pullPagesThroughChangedRecords() {
        rules.push((1..5).map { put("op$it", mapOf("title" to "t$it"), wall = it.toLong(), id = "e${it % 3}") })
        val first = rules.pull(0, 2)
        assertEquals(listOf("e1", "e2"), first.changes.map { it.id })
        assertEquals(true, first.hasMore)
        val rest = rules.pull(first.nextCursor, 10)
        assertEquals(listOf("e0", "e1", "e2"), rest.changes.map { it.id })
        assertEquals(false, rest.hasMore)
        assertEquals(rest.nextCursor, rules.pull(rest.nextCursor, 10).nextCursor)
    }
}
