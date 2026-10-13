package app.benbow.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.benbow.app.QuickCapture
import app.benbow.app.sharedContent

/** The quick capture sheet over other apps: shared text and files, the Quick Settings tile, the "Заметка" shortcut. */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val shared = sharedContent(this, intent)
        setContent { QuickCapture((application as BenbowApplication).services, shared, onClose = ::finish) }
    }

    override fun onStop() {
        super.onStop()
        SyncWorker.scheduleSoon(this)
        BenbowWidget.refresh(this, (application as BenbowApplication).services)
    }

    companion object {
        const val ACTION_CAPTURE = "app.benbow.action.CAPTURE"

        fun intent(context: Context): Intent = Intent(ACTION_CAPTURE).setClass(context, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** "Заметка" in Quick Settings: one tap from anywhere, even over the lock screen once unlocked. */
class CaptureTileService : TileService() {
    override fun onClick() {
        val open = PendingIntent.getActivity(this, 0, CaptureActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
        if (isLocked) unlockAndRun { startActivityAndCollapse(open) } else startActivityAndCollapse(open)
    }
}

/** Long-press on the launcher icon: "Заметка" (the sheet) and "Запись" (a new entry in the app). */
internal object Shortcuts {
    const val ACTION_NEW_ENTRY = "app.benbow.action.NEW_ENTRY"

    /** Dynamic, so debug and benchmark builds point at themselves; set once, not on every start. */
    fun publish(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        if (manager.dynamicShortcuts.size == 2) return
        val note = ShortcutInfo.Builder(context, "note")
            .setShortLabel(context.getString(R.string.shortcut_note))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_note))
            .setIntent(CaptureActivity.intent(context))
            .build()
        val entry = ShortcutInfo.Builder(context, "entry")
            .setShortLabel(context.getString(R.string.shortcut_entry))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_entry))
            .setIntent(Intent(ACTION_NEW_ENTRY).setClass(context, MainActivity::class.java))
            .build()
        manager.dynamicShortcuts = listOf(note, entry)
    }
}
