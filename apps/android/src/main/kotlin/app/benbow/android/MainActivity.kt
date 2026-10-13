package app.benbow.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.benbow.app.AppLaunch
import app.benbow.app.BenbowApp

class MainActivity : ComponentActivity() {
    private val services get() = (application as BenbowApplication).services

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        Shortcuts.publish(this)
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
        }
    }

    override fun onStop() {
        super.onStop()
        SyncWorker.scheduleSoon(this)
        BenbowWidget.refresh(this, services)
    }
}
