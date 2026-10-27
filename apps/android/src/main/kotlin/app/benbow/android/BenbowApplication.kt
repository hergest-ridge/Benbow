package app.benbow.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.benbow.android.asr.SandboxTranscriber
import app.benbow.app.AndroidDictationMic
import app.benbow.app.AppServices
import app.benbow.app.installAndroidImages
import app.benbow.app.mediaIn
import app.benbow.client.KeystoreSecretBox
import app.benbow.client.SecretBox
import app.benbow.client.SpeechModelStore
import app.benbow.client.calendar.db.CalendarDb
import app.benbow.client.db.BenbowDb
import app.benbow.client.keepBeforeMigrating
import app.benbow.core.api.SpeechModelSpec
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

class BenbowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The speech recognizer's sandbox (AsrService) starts this class too, with no permissions: it needs none of this.
        if (Process.isIsolated()) return
        installAndroidImages(this)
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

    /** The process's one player (ADR-035), shown to the system by [PlaybackService]. */
    val audio: AndroidAudio by lazy { AndroidAudio(this) }

    /** One per process: the local DB, outbox and sync outlive activities. */
    val services: AppServices by lazy {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        AppServices(
            durableDriver(BenbowDb.Schema, "benbow.db"),
            // The calendar's own file (ADR-027), never mixed with the journal's.
            durableDriver(CalendarDb.Schema, "calendar.db"),
            mediaIn(File(filesDir, "media")),
            platform = "android",
            deviceName = name,
            reminderClock = AlarmReminderClock(this),
            transcriber = SandboxTranscriber(this),
            speechModel = { api -> SpeechModelStore(File(filesDir, "models/" + SpeechModelSpec.ID), api) },
            mic = AndroidDictationMic(this),
            audio = audio,
            updater = AndroidAppUpdater(this),
            labs = packageName != "app.benbow", // the dev and bench builds show unfinished work, the owner's app never
        ).also { if (it.labs) TimerNotifications.start(this, it) } // trackers' running timers (ADR-050)
    }

    private fun durableDriver(schema: SqlSchema<QueryResult.Value<Unit>>, name: String): AndroidSqliteDriver {
        // A copy before this build migrates the file: the outbox and drafts live only here, and updates may come
        // unwatched (ADR-042). Without one (a full disk) the migration still runs in its own transaction.
        runCatching { keepBeforeMigrating(getDatabasePath(name), schema.version) }
            .onFailure { Log.w("benbow", "No copy of $name before its migration", it) }
        return durableDriverOpen(schema, name)
    }

    private fun durableDriverOpen(schema: SqlSchema<QueryResult.Value<Unit>>, name: String) = AndroidSqliteDriver(
        schema = schema,
        context = this,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.enableWriteAheadLogging()
                // Drafts and the outboxes exist only on this device until synced: no lost commits on power loss.
                db.query("PRAGMA synchronous = FULL").close()
            }
        },
    )
}
