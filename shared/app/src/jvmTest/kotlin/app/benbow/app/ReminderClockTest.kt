package app.benbow.app

import app.benbow.client.Due
import app.benbow.client.ReminderItem
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.time.LocalDate
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ReminderClockTest {
    @Test
    fun aNewReminderReachesTheDeviceClock() = runBlocking {
        val scheduled = Channel<List<Due>>(Channel.UNLIMITED)
        val services = AppServices(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema),
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CalendarDb.Schema),
            mediaIn(Files.createTempDirectory("benbow-media").toFile()),
            "desktop",
            "Test PC",
            reminderClock = { scheduled.trySend(it) },
        )
        val tomorrow = LocalDate.now().plusDays(1)
        services.repo.createReminder("Позвонить маме", "${tomorrow}T18:00", ReminderItem.WEEKLY)
        // The clock is set from the reminders' flow in the background: wait for that, not for a number of naps.
        val due = withTimeout(30.seconds) { scheduled.receiveAsFlow().first { it.isNotEmpty() } }
        assertEquals(listOf("Позвонить маме"), due.map { it.text })
    }
}
