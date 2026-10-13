package app.benbow.android

import android.Manifest
import android.app.AlarmManager
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
import app.benbow.app.ReminderClock
import app.benbow.client.Due

/**
 * Reminders on Android's clock: inexact alarms (a few minutes of leeway, no exact-alarm permission),
 * one per reminder, replaced on every change. The phone forgets alarms on reboot; [ReminderReceiver]
 * puts them back.
 */
class AlarmReminderClock(private val context: Context) : ReminderClock {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    override fun schedule(due: List<Due>) {
        val keep = due.map { it.id }.toSet()
        prefs.getStringSet(SCHEDULED, emptySet()).orEmpty().filter { it !in keep }.forEach { alarms.cancel(intent(it, "")) }
        due.forEach { alarms.setWindow(AlarmManager.RTC_WAKEUP, it.atMillis, WINDOW_MS, intent(it.id, it.text)) }
        prefs.edit().putStringSet(SCHEDULED, keep).apply()
    }

    private fun intent(id: String, text: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_RING).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_RING = "app.benbow.REMINDER"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
        const val CHANNEL = "reminders"
        private const val SCHEDULED = "scheduled"
        private const val WINDOW_MS = 5 * 60_000L

        fun createChannel(context: Context) {
            val channel =
                NotificationChannel(CHANNEL, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/** Shows a ringing reminder, then puts the next rings on the clock (repeats); after a reboot it only reschedules. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BenbowApplication
        if (intent.action == AlarmReminderClock.ACTION_RING) {
            val id = intent.getStringExtra(AlarmReminderClock.EXTRA_ID) ?: return
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, AlarmReminderClock.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(intent.getStringExtra(AlarmReminderClock.EXTRA_TEXT))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            // Without the permission the reminder stays silent (it is still listed in the app).
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
            }
        }
        app.services.rescheduleReminders() // also BOOT_COMPLETED: creating the services schedules everything
    }
}
