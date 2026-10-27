package app.benbow.client.tracker

import app.benbow.client.Connection
import app.benbow.client.FakeApi
import app.benbow.client.FakeServer
import app.benbow.client.calendar.CalendarRepository
import app.benbow.client.calendar.CalendarSync
import app.benbow.client.calendar.CalendarSyncResult
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.core.calendar.CalendarProtocol
import app.benbow.core.tracker.CellField
import app.benbow.core.tracker.ColumnField
import app.benbow.core.tracker.SpanField
import app.benbow.core.tracker.TrackerCells
import app.benbow.core.tracker.TrackerExport
import app.benbow.core.tracker.TrackerField
import app.benbow.core.tracker.TrackerKind
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trackers on the device (ADR-050): records in the calendar's file, sent on its channel to a server that keeps them. */
class TrackerClientTest {
    private var time = 1_000L
    private val now = { time++ }
    private val connection = Connection("https://server", "AA", "token", "store-1", 1, "phone")
    private val day = "2026-10-27"

    private fun calendarDb() = CalendarDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema))

    private inner class Device(var api: FakeApi) {
        val db = calendarDb()
        val trackers = TrackerRepository(db, now)
        val calendar = CalendarRepository(db, now)
        private val channel = CalendarSync(db, now)

        suspend fun sync() = channel.sync(api, connection)

        suspend fun count(column: String) =
            TrackerCells.count(trackers.cells(listOf(column), day, day).first().singleOrNull()?.fields.orEmpty())
    }

    private fun column(name: String, type: String, vararg fields: Pair<String, String>) =
        mapOf(ColumnField.NAME to name, ColumnField.TYPE to type) + fields

    @Test
    fun trackersAndTheirColumnsKeepTheirOrderAndGoToTheTrashWhole() = runTest {
        val repo = TrackerRepository(calendarDb(), now)
        val habits = repo.createTracker(mapOf(TrackerField.NAME to "Привычки"), listOf(column("Зарядка", "check"), column("Вода", "count")))
        val sleep = repo.createTracker(mapOf(TrackerField.NAME to "Сон"))
        repo.addColumn(habits, column("Чтение", "check"))
        assertEquals(listOf("Привычки", "Сон"), repo.trackers().first().map { it[TrackerField.NAME] })
        assertEquals(listOf("Зарядка", "Вода", "Чтение"), repo.columns(habits).first().map { it.name })

        repo.moveToTrash(sleep)
        assertEquals(listOf("Привычки"), repo.trackers().first().map { it[TrackerField.NAME] })
        assertEquals(listOf("Сон"), repo.trashedTrackers().first().map { it[TrackerField.NAME] })
        repo.restore(sleep)
        assertEquals(2, repo.trackers().first().size)
        repo.updateTracker(habits, mapOf("background" to "sand", "look_paper" to "dots"))
        assertEquals("dots", repo.trackers().first().first()["look_paper"], "a tracker takes a note's look (ADR-047)")
    }

    @Test
    fun aMovedColumnOrTrackerTakesARankBetweenItsNewNeighbours() = runTest {
        val repo = TrackerRepository(calendarDb(), now)
        val tracker = repo.createTracker(mapOf(TrackerField.NAME to "Привычки"), listOf("А", "Б", "В").map { column(it, "check") })
        val before = repo.columns(tracker).first()
        val moving = before[2]
        repo.place(TrackerKind.COLUMN, moving.id, before.filter { it.id != moving.id }.map { it.record }, 0)
        assertEquals(listOf("В", "А", "Б"), repo.columns(tracker).first().map { it.name })

        val other = repo.createTracker(mapOf(TrackerField.NAME to "Сон"))
        val list = repo.trackers().first()
        repo.place(TrackerKind.TRACKER, other, list.filter { it.id != other }, 0)
        assertEquals(listOf("Сон", "Привычки"), repo.trackers().first().map { it[TrackerField.NAME] })
    }

    @Test
    fun cellsComeByDayAndASpanOverMidnightShowsOnBothDays() = runTest {
        val repo = TrackerRepository(calendarDb(), now)
        val tracker = repo.createTracker(
            mapOf(TrackerField.NAME to "Сон"),
            listOf(column("Сон", "time", ColumnField.DAY_BY to "end"), column("Вода", "count")),
        )
        val (sleep, water) = repo.columns(tracker).first().map { it.id }
        repeat(2) { repo.addToCount(water, day) }
        repo.setCell(water, "2026-10-28", "много", CellField.NOTE)
        val cells = repo.cells(listOf(water), day, day).first()
        assertEquals(listOf(TrackerCells.id(water, day)), cells.map { it.id })
        assertEquals(2.0, TrackerCells.count(cells.single().fields))
        assertEquals(mapOf(CellField.COLUMN to water, CellField.DAY to day), cells.single().fields.filterKeys { !it.startsWith("add:") })
        assertEquals(2, repo.cells(listOf(water), "2026-10-26", "2026-10-28").first().size)
        repo.setCount(water, day, 5.0)
        assertEquals(5.0, TrackerCells.count(repo.cells(listOf(water), day, day).first().single().fields))

        repo.addSpan(sleep, "2026-10-26T23:10:00", "2026-10-27T07:05:00")
        assertEquals(1, repo.spans(listOf(sleep), "2026-10-26", "2026-10-26").first().size, "over midnight: on both days")
        assertEquals(1, repo.spans(listOf(sleep), day, day).first().size)
        assertEquals(0, repo.spans(listOf(sleep), "2026-10-28", "2026-10-30").first().size)
    }

    @Test
    fun writesTogetherLandAllOrNone() = runTest {
        val repo = TrackerRepository(calendarDb(), now)
        val tracker = repo.createTracker(mapOf(TrackerField.NAME to "Сон"), listOf(column("Сон", "time")))
        val sleep = repo.columns(tracker).first().single().id
        val night = repo.addSpan(sleep, "2026-10-26T23:00:00", "2026-10-27T07:00:00")
        val awake = runCatching {
            repo.together {
                updateSpan(night, mapOf(SpanField.END to "2026-10-27T03:00:00"))
                error("the app went away between the two")
            }
        }
        assertTrue(awake.isFailure)
        assertEquals(listOf("2026-10-27T07:00:00"), repo.spans(listOf(sleep), day, day).first().map { it[SpanField.END] })

        repo.together {
            updateSpan(night, mapOf(SpanField.END to "2026-10-27T03:00:00"))
            addSpan(sleep, "2026-10-27T04:00:00", "2026-10-27T07:00:00")
        }
        val ends = repo.spans(listOf(sleep), day, day).first().map { it[SpanField.END] }
        assertEquals(setOf("2026-10-27T03:00:00", "2026-10-27T07:00:00"), ends.toSet())
    }

    @Test
    fun tapsOnTwoDevicesOfflineAllCountAndADaysCheckIsOneRecord() = runTest {
        val server = FakeServer()
        val phone = Device(FakeApi(server))
        val desktop = Device(FakeApi(server))
        val tracker = phone.trackers.createTracker(
            mapOf(TrackerField.NAME to "Привычки"),
            listOf(column("Вода", "count"), column("Прогулка", "check")),
        )
        phone.sync()
        desktop.sync()
        val (water, walk) = desktop.trackers.columns(tracker).first().map { it.id }

        repeat(3) { phone.trackers.addToCount(water, day) }
        repeat(2) { desktop.trackers.addToCount(water, day) }
        phone.trackers.setCell(walk, day, "true")
        desktop.trackers.setCell(walk, day, "true")
        phone.sync()
        desktop.sync()
        phone.sync()

        assertEquals(5.0, phone.count(water))
        assertEquals(5.0, desktop.count(water))
        val walks = server.calendarStore.changesSince(0, Int.MAX_VALUE).filter { it.kind == TrackerKind.CELL }.map { it.id }.toSet()
        assertEquals(setOf(TrackerCells.id(water, day), TrackerCells.id(walk, day)), walks, "one record per column and day")
    }

    @Test
    fun aTimerStartedOnThePhoneAndStoppedOnBothLeavesOneSpan() = runTest {
        val server = FakeServer()
        val phone = Device(FakeApi(server))
        val desktop = Device(FakeApi(server))
        val tracker = phone.trackers.createTracker(mapOf(TrackerField.NAME to "Время"), listOf(column("Работа", "time")))
        val work = phone.trackers.columns(tracker).first().single().id
        phone.trackers.startTimer(work, "${day}T09:00:00")
        phone.sync()
        desktop.sync()
        assertEquals("${day}T09:00:00", desktop.trackers.columns(tracker).first().single().running, "started elsewhere, seen here")

        desktop.trackers.stopTimer(work, "${day}T10:30:00")
        phone.trackers.stopTimer(work, "${day}T10:31:00") // offline: it doesn't know yet
        desktop.sync()
        phone.sync()
        desktop.sync()

        for (device in listOf(phone, desktop)) {
            assertEquals(1, device.trackers.spans(listOf(work), day, day).first().size)
            assertNull(device.trackers.columns(tracker).first().single().running)
        }
    }

    @Test
    fun runningTimersAreListedUntilStopped() = runTest {
        val repo = TrackerRepository(calendarDb(), now)
        val tracker = repo.createTracker(mapOf(TrackerField.NAME to "Время"), listOf(column("Работа", "time"), column("Чтение", "time")))
        val (work, reading) = repo.columns(tracker).first().map { it.id }
        assertEquals(emptyList(), repo.runningColumns().first())
        repo.startTimer(reading, "${day}T09:00:00")
        assertEquals(listOf(reading), repo.runningColumns().first().map { it.id })
        repo.updateColumn(reading, mapOf(ColumnField.ARCHIVED to "true"))
        assertEquals(emptyList(), repo.runningColumns().first(), "an archived column's timer waits unseen")
        repo.startTimer(work, "${day}T10:00:00")
        repo.stopTimer(work, "${day}T11:00:00")
        assertEquals(emptyList(), repo.runningColumns().first())
    }

    @Test
    fun startingAnotherTimerOfAOneAtATimeTrackerStopsTheFirst() = runTest {
        val repo = TrackerRepository(calendarDb(), now)
        val tracker = repo.createTracker(
            mapOf(TrackerField.NAME to "Время"),
            listOf(column("Работа", "time", ColumnField.TIMER to "one"), column("Чтение", "time", ColumnField.TIMER to "one")),
        )
        val (work, reading) = repo.columns(tracker).first().map { it.id }
        repo.startTimer(work, "${day}T09:00:00")
        repo.startTimer(reading, "${day}T11:00:00")
        assertEquals(listOf(null, "${day}T11:00:00"), repo.columns(tracker).first().map { it.running })
        assertEquals(listOf("${day}T11:00:00"), repo.spans(listOf(work), day, day).first().map { it["end"] })
    }

    @Test
    fun anOlderServerGetsNoTrackersOpsAndTheyWaitForOneThatKeepsThem() = runTest {
        val server = FakeServer().apply { calendarKinds = emptyList() }
        val phone = Device(FakeApi(server))
        phone.calendar.createEvent(day, "Врач", null)
        phone.trackers.createTracker(mapOf(TrackerField.NAME to "Привычки"), listOf(column("Вода", "count")))

        val first = assertIs<CalendarSyncResult.Done>(phone.sync())
        assertEquals(2, first.held, "the tracker and its column wait")
        assertEquals(listOf("event"), server.calendarLog.map { it.kind }, "the event went")

        server.calendarKinds = CalendarProtocol.KINDS.sorted() // the server was updated
        val second = assertIs<CalendarSyncResult.Done>(phone.sync())
        assertEquals(0, second.held)
        assertEquals(listOf("event", "tracker", "column"), server.calendarLog.map { it.kind })
    }

    @Test
    fun aRestoredStoreGetsTheTrackersBackFromTheDevice() = runTest {
        val server = FakeServer()
        val phone = Device(FakeApi(server))
        val tracker = phone.trackers.createTracker(mapOf(TrackerField.NAME to "Привычки"), listOf(column("Вода", "count")))
        val water = phone.trackers.columns(tracker).first().single().id
        phone.trackers.addToCount(water, day)
        phone.sync()

        phone.api.server = server.calendarRestoredTo(emptyList(), calendarEpoch = 2)
        phone.sync()
        val restored = phone.api.server.calendarStore
        assertTrue(restored.record(TrackerKind.TRACKER, tracker) != null)
        val cell = restored.record(TrackerKind.CELL, TrackerCells.id(water, day))?.fields.orEmpty().mapValues { it.value.value }
        assertEquals(1.0, TrackerCells.count(cell), "the tap came back with the device's clock")
    }

    @Test
    fun theExportHasAFolderPerTrackerOutsideTheTrash() = runTest {
        val db = calendarDb()
        val repo = TrackerRepository(db, now)
        val tracker = repo.createTracker(mapOf(TrackerField.NAME to "Привычки"), listOf(column("Прогулка", "check")))
        repo.setCell(repo.columns(tracker).first().single().id, day, "true")
        repo.createTracker(mapOf(TrackerField.NAME to "Старый", TrackerField.DELETED to "true"))
        val files = repo.exportFiles().map { it.path }
        assertEquals(listOf("trackers/Привычки/" + TrackerExport.JSON_FILE, "trackers/Привычки/2026.csv"), files)
        val archived = CalendarRepository(db, now).trackerFiles().map { it.path }
        assertEquals(files, archived, "the archive reads them through the calendar's file")
    }
}
