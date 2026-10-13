package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarTest {
    private var time = 1_000L
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val repo = JournalRepository(db, { time++ })

    @Test
    fun monthDaysCountEntriesAndAverageMood() = runTest {
        val a = repo.createNote("2026-10-05", "Утро")
        val b = repo.createNote("2026-10-05", "Вечер")
        repo.createNote("2026-10-20", "Без настроения")
        repo.createNote("2026-11-01", "Уже ноябрь")
        repo.setMood(a, 2)
        repo.setMood(b, -1)
        val days = repo.monthDays("2026-10-01", "2026-10-31").first()
        assertEquals(setOf("2026-10-05", "2026-10-20"), days.keys)
        assertEquals(DayInfo("2026-10-05", 2, 0.5), days["2026-10-05"])
        assertEquals(null, days.getValue("2026-10-20").mood)
    }

    @Test
    fun eventsLiveInTheCalendarNotInTheJournal() = runTest {
        repo.createEvent("2026-10-14", "Врач", "09:30")
        repo.createEvent("2026-10-14", "День рождения Маши", null)
        repo.createNote("2026-10-14", "Обычная запись")
        assertEquals(
            listOf(EventItem("x", "2026-10-14", "День рождения Маши", null), EventItem("x", "2026-10-14", "Врач", "09:30")),
            repo.events("2026-10-01", "2026-10-31").first().map { it.copy(id = "x") },
            "all-day first, then by time",
        )
        assertEquals(listOf("Обычная запись"), repo.journal().first().map { it.title })
        assertEquals(
            1,
            repo.monthDays("2026-10-01", "2026-10-31").first().getValue("2026-10-14").entries,
            "events aren't counted as writing",
        )
        assertEquals(1, repo.feed().first().size)
    }
}
