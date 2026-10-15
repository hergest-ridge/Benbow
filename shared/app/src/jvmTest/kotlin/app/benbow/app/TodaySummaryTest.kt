package app.benbow.app

import app.benbow.client.Prompts
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodaySummaryTest {
    private val services = AppServices(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
        Files.createTempDirectory("benbow-media").toFile(),
        "desktop",
        "Test PC",
    )

    @Test
    fun theDayAtAGlanceForTheWidget() = runTest {
        assertEquals("Сегодня пока без записей", services.todaySummary().status, "no guilt, just a fact")
        val morning = services.repo.createNote(services.today(), "Утро")
        services.repo.setMood(morning, 1)
        services.repo.createNote(services.today(), "Вечер")

        val today = services.todaySummary()
        assertEquals("2 записи сегодня · 🙂", today.status)
        assertEquals(Prompts.ofTheDay(services.today()), today.prompt)
        assertTrue(today.date.matches(Regex("""\p{Lu}\p{Ll}+, \d{1,2} \p{Ll}+""")), today.date)
    }
}
