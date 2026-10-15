package app.benbow.app

import app.benbow.client.Due
import app.benbow.client.ReminderItem
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.LocalDate
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderClockTest {
    @Test
    fun aNewReminderReachesTheDeviceClock() = runBlocking {
        val scheduled = CopyOnWriteArrayList<List<Due>>()
        val services = AppServices(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
            Files.createTempDirectory("benbow-media").toFile(),
            "desktop",
            "Test PC",
            reminderClock = { scheduled += it },
        )
        val tomorrow = LocalDate.now().plusDays(1)
        services.repo.createReminder("Позвонить маме", "${tomorrow}T18:00", ReminderItem.WEEKLY)
        repeat(50) { if (scheduled.lastOrNull()?.isNotEmpty() != true) delay(100) }
        assertEquals(listOf("Позвонить маме"), scheduled.last().map { it.text })
    }
}
