@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.app

import app.benbow.client.Due
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Reminders in the browser ring while the tab is open (ADR-029), like the desktop's tray: [ring] shows a system
 * notification where the browser allows it, otherwise the app says it. A background tab's timers may run up to a minute
 * late (the browser slows them down).
 */
class BrowserReminderClock(private val scope: CoroutineScope, private val ring: (String) -> Unit, private val onRang: () -> Unit) :
    ReminderClock {
    private var job: Job? = null

    override fun schedule(due: List<Due>) {
        job?.cancel()
        val next = due.firstOrNull() ?: return
        job = scope.launch {
            delay((next.atMillis - now()).coerceAtLeast(0))
            due.filter { it.atMillis <= now() }.forEach { ring(it.text) }
            onRang() // repeats move on; the list is scheduled again
        }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()
}

/** A system notification, when this site may show them; false when it may not. */
fun showNotification(text: String): Boolean = js(
    """(() => {
        if (!('Notification' in window) || Notification.permission !== 'granted') return false;
        new Notification('Benbow', { body: text, icon: 'favicon.svg', tag: 'benbow-reminder-' + Date.now() });
        return true;
    })()""",
)

/** Asks once (on the click that adds a reminder): the browser only lets a page ask from a user's action. */
internal fun askForNotifications(): Unit = js(
    "{ if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission(); }",
)
