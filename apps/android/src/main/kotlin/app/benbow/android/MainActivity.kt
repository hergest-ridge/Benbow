package app.benbow.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.benbow.app.AppLaunch
import app.benbow.app.BenbowApp
import app.benbow.app.displayName
import app.benbow.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val services get() = (application as BenbowApplication).services

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        Shortcuts.publish(this)
        // With the lock on, Recents shows a blank card instead of the last screen (screenshots still work).
        lifecycleScope.launch { services.lock.minutes.collect { setRecentsScreenshotEnabled(it == null) } }
        // Status and navigation bar icons follow the app's light or dark choice, not only the system's.
        lifecycleScope.launch {
            services.appearance.collect {
                val bars = when (it.mode) {
                    ThemeMode.System -> SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                    ThemeMode.Light -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    ThemeMode.Dark -> SystemBarStyle.dark(Color.TRANSPARENT)
                }
                enableEdgeToEdge(bars, bars)
            }
        }
        setContent { BenbowApp(services) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            Shortcuts.ACTION_NEW_ENTRY -> services.launch.value = AppLaunch.NewEntry

            ACTION_ANSWER_PROMPT -> services.launch.value = AppLaunch.AnswerPrompt

            // "Открыть с помощью" on an .ics file: its import preview (7.3d).
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                services.importCalendarFile(displayName(uri) ?: "calendar.ics") {
                    contentResolver.openInputStream(uri) ?: error("Can't read $uri")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        SyncWorker.scheduleSoon(this)
        BenbowWidget.refresh(this, services)
    }
}
