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
    fun entryDaysAreTheGridsDaysOverTheWholeJournal() = runTest {
        repo.createNote("2019-03-14", "Первая")
        repo.createNote("2026-10-05", "Утро")
        repo.createNote("2026-10-05", "Вечер")
        val gone = repo.createNote("2026-10-07", "Удалю")
        repo.createComment(repo.createNote("2026-10-09", "С комментарием"), "2026-10-12", "Позже") // a comment isn't a dot
        repo.moveToTrash(gone)
        assertEquals(listOf("2026-10-09", "2026-10-05", "2019-03-14"), repo.entryDays().first())
        assertEquals(repo.monthDays("2026-10-01", "2026-10-31").first().keys, setOf("2026-10-05", "2026-10-09"))
    }
}
