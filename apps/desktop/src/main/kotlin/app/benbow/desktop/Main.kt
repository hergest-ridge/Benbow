package app.benbow.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.benbow.app.AppServices
import app.benbow.app.BenbowApp
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
    val services = AppServices(
        driver,
        mediaDir = File(dir, "media"),
        platform = "desktop",
        deviceName = System.getenv("COMPUTERNAME") ?: InetAddress.getLocalHost().hostName,
        mediaCacheBytes = 10L * 1024 * 1024 * 1024, // the PC has room; originals open without waiting
    )
    application {
        Window(onCloseRequest = ::exitApplication, title = "Benbow") {
            BenbowApp(services)
        }
    }
}
