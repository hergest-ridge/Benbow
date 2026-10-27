package app.benbow.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.benbow.app.AppServices
import app.benbow.app.RunningTimer
import app.benbow.app.runningTimers
import app.benbow.app.stopTimerNow
import kotlinx.coroutines.launch

/**
 * Trackers' running timers as ongoing notifications (ADR-050, B6): the time ticks in the shade without the app (the
 * system's chronometer from the timer's start), «Стоп» stops it from there. A timer started on another device shows
 * once this one has synced; one stopped anywhere goes away.
 */
internal object TimerNotifications {
    const val ACTION_STOP = "app.benbow.TIMER_STOP"
    const val EXTRA_COLUMN = "column"
    private const val CHANNEL = "timers"
    private const val TAG = "timer:"
    private const val ID = 7

    /** Follows the running timers for as long as the process lives. */
    fun start(context: Context, services: AppServices) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.timers_channel), NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        services.scope.launch { services.runningTimers().collect { show(context, it) } }
    }

    fun cancel(context: Context, column: String) = NotificationManagerCompat.from(context).cancel(TAG + column, ID)

    private fun show(context: Context, timers: List<RunningTimer>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val live = timers.map { TAG + it.columnId }.toSet()
        manager.activeNotifications.filter { it.tag?.startsWith(TAG) == true && it.tag !in live }.forEach { manager.cancel(it.tag, it.id) }
        // Without the permission the timers run quietly (their sheet still ticks).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val compat = NotificationManagerCompat.from(context)
            for (timer in timers) compat.notify(TAG + timer.columnId, ID, notification(context, timer))
        }
    }

    private fun notification(context: Context, timer: RunningTimer): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            timer.columnId.hashCode(),
            Intent(context, TimerStopReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_COLUMN, timer.columnId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(timer.title)
            .setContentText(timer.tracker)
            .setWhen(timer.startedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.timer_stop), stop)
            .build()
    }
}

/** «Стоп» in a timer's notification: the span is written, then the notification goes. */
class TimerStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val column = intent.getStringExtra(TimerNotifications.EXTRA_COLUMN) ?: return
        val services = (context.applicationContext as BenbowApplication).services
        val pending = goAsync()
        services.scope.launch {
            try {
                services.stopTimerNow(column)
                TimerNotifications.cancel(context, column)
            } finally {
                pending.finish()
            }
        }
    }
}
