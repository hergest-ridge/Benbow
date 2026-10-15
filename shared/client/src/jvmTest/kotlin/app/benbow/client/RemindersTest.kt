package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class RemindersTest {
    private val now = LocalDateTime(2026, 10, 13, 20, 0)

    private fun reminder(at: String, repeat: String? = null, done: Boolean = false) = ReminderItem("r", "Записать день", at, repeat, done)

    @Test
    fun oneOffsRingOnceAndRepeatsRollForward() {
        assertEquals(LocalDateTime(2026, 10, 13, 21, 0), reminder("2026-10-13T21:00").nextAt(now))
        assertEquals(null, reminder("2026-10-13T19:00").nextAt(now), "a past one-off is over")
        assertEquals(LocalDateTime(2026, 10, 14, 19, 0), reminder("2026-10-01T19:00", ReminderItem.DAILY).nextAt(now))
        assertEquals(LocalDateTime(2026, 10, 13, 21, 0), reminder("2026-10-01T21:00", ReminderItem.DAILY).nextAt(now))
        assertEquals(LocalDateTime(2026, 10, 15, 9, 0), reminder("2026-10-01T09:00", ReminderItem.WEEKLY).nextAt(now), "Thursdays")
        assertEquals(null, reminder("2026-10-13T21:00", ReminderItem.DAILY, done = true).nextAt(now))
    }

    @Test
    fun upcomingIsSoonestFirstWithinTheWindow() {
        val list = listOf(
            ReminderItem("a", "Позже", "2026-10-20T10:00", null, false),
            ReminderItem("b", "Скоро", "2026-10-13T21:00", null, false),
            ReminderItem("c", "Через два месяца", "2026-12-20T10:00", null, false),
        )
        assertEquals(listOf("b", "a"), list.upcoming(now, TimeZone.UTC).map { it.id })
        assertEquals(
            LocalDateTime(2026, 10, 13, 21, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
            list.upcoming(now, TimeZone.UTC).first().atMillis,
        )
    }

    @Test
    fun remindersAreEntriesKeptOutOfTheJournal() = runTest {
        var time = 1_000L
        val repo = JournalRepository(BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema)), { time++ })
        val id = repo.createReminder("Записать день", "2026-10-13T21:00", ReminderItem.DAILY)
        assertEquals(listOf(ReminderItem(id, "Записать день", "2026-10-13T21:00", ReminderItem.DAILY, false)), repo.reminders().first())
        assertEquals(emptyList(), repo.journal().first())
        repo.setReminderDone(id, true)
        assertEquals(true, repo.reminders().first().single().done)
    }
}
