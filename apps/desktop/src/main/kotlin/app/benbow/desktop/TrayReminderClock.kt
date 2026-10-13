package app.benbow.desktop

import app.benbow.app.ReminderClock
import app.benbow.client.Due
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/** Reminders on desktop: a tray message while the app runs (no background service on the PC). */
class TrayReminderClock(private val scope: CoroutineScope, private val onRang: () -> Unit) : ReminderClock {
    private var job: Job? = null
    private val tray: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                color = Color(0xE8, 0xA8, 0x5A) // lantern amber
                fillOval(1, 1, 14, 14)
                dispose()
            }
        }
        TrayIcon(image, "Benbow").also { runCatching { SystemTray.getSystemTray().add(it) } }
    }

    @Synchronized
    override fun schedule(due: List<Due>) {
        job?.cancel()
        val next = due.firstOrNull() ?: return
        job = scope.launch {
            delay((next.atMillis - System.currentTimeMillis()).coerceAtLeast(0))
            due.filter {
                it.atMillis <= System.currentTimeMillis()
            }.forEach { tray?.displayMessage("Benbow", it.text, TrayIcon.MessageType.INFO) }
            onRang() // repeats move on; the list is scheduled again
        }
    }
}
