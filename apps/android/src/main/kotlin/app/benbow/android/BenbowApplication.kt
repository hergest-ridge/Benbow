package app.benbow.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.sqlite.db.SupportSQLiteDatabase
import app.benbow.android.asr.SandboxTranscriber
import app.benbow.app.AppServices
import app.benbow.client.KeystoreSecretBox
import app.benbow.client.SecretBox
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

class BenbowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The speech recognizer's sandbox (AsrService) starts this class too, with no permissions: it needs none of this.
        if (Process.isIsolated()) return
        SecretBox.current = KeystoreSecretBox()
        AlarmReminderClock.createChannel(this)
        SyncWorker.schedulePeriodic(this)
        // Back on a network (Wi-Fi at home, airplane mode off): sync without waiting for the timer.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = services.sync.requestSync()
            },
        )
    }

    /** One per process: the local DB, outbox and sync outlive activities. */
    val services: AppServices by lazy {
        val driver = AndroidSqliteDriver(
            schema = BenbowDb.Schema,
            context = this,
            name = "benbow.db",
            callback = object : AndroidSqliteDriver.Callback(BenbowDb.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.enableWriteAheadLogging()
                    // Drafts and the outbox exist only on this device until synced: no lost commits on power loss.
                    db.query("PRAGMA synchronous = FULL").close()
                }
            },
        )
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        AppServices(
            driver,
            File(filesDir, "media"),
            platform = "android",
            deviceName = name,
            reminderClock = AlarmReminderClock(this),
            transcriber = SandboxTranscriber(this),
            modelsDir = File(filesDir, "models"),
        )
    }
}
