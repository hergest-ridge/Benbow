package app.benbow.desktop

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.application
import app.benbow.app.AppServices
import app.benbow.app.BenbowDesktop
import app.benbow.app.mediaIn
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.InetAddress
import java.util.Properties

fun main() {
    val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Benbow").apply { mkdirs() }
    // Drafts and the outbox exist only here until synced (data-safety S3 applies to the client too).
    val properties = Properties().apply {
        put("journal_mode", "WAL")
        put("synchronous", "FULL")
    }
    val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "benbow.db").absolutePath}", properties, BenbowDb.Schema)
    // The calendar's own file (ADR-027), never mixed with the journal's.
    val calendarDriver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "calendar.db").absolutePath}", properties, CalendarDb.Schema)
    lateinit var services: AppServices
    val tray = TrayState()
    val clock = TrayReminderClock(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        notify = { tray.sendNotification(Notification("Benbow", it)) },
    ) { services.rescheduleReminders() }
    services = AppServices(
        driver,
        calendarDriver,
        mediaStore = mediaIn(File(dir, "media")),
        platform = "desktop",
        deviceName = System.getenv("COMPUTERNAME") ?: InetAddress.getLocalHost().hostName,
        mediaCacheBytes = 10L * 1024 * 1024 * 1024, // the PC has room; originals open without waiting
        reminderClock = clock,
        audio = JavaFxAudio(),
    )
    application { BenbowDesktop(services, tray) }
}
