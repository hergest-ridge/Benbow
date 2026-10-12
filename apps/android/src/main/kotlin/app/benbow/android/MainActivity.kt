package app.benbow.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.benbow.app.BenbowApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { BenbowApp((application as BenbowApplication).services) }
    }

    override fun onStop() {
        super.onStop()
        SyncWorker.scheduleSoon(this)
    }
}
