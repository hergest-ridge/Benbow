package app.benbow.desktop

import app.benbow.app.ReminderClock
import app.benbow.client.Due
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reminders on desktop: a tray notification while the app runs (it keeps running in the tray when the window closes). */
class TrayReminderClock(private val scope: CoroutineScope, private val notify: (String) -> Unit, private val onRang: () -> Unit) :
    ReminderClock {
    private var job: Job? = null

    @Synchronized
    override fun schedule(due: List<Due>) {
        job?.cancel()
        val next = due.firstOrNull() ?: return
        job = scope.launch {
            delay((next.atMillis - System.currentTimeMillis()).coerceAtLeast(0))
            due.filter { it.atMillis <= System.currentTimeMillis() }.forEach { notify(it.text) }
            onRang() // repeats move on; the list is scheduled again
        }
    }
}
