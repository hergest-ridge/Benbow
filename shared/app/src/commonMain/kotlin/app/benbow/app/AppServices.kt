package app.benbow.app

import app.benbow.client.ApiCache
import app.benbow.client.ArchiveExport
import app.benbow.client.Due
import app.benbow.client.ExportProgress
import app.benbow.client.ExportReport
import app.benbow.client.JournalRepository
import app.benbow.client.MediaStore
import app.benbow.client.Pairing
import app.benbow.client.ReminderItem
import app.benbow.client.ServerHistory
import app.benbow.client.Settings
import app.benbow.client.SyncController
import app.benbow.client.SyncEngine
import app.benbow.client.SyncResult
import app.benbow.client.db.BenbowDb
import app.benbow.client.upcoming
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** Rings reminders on this device; [schedule] replaces whatever was scheduled before. */
fun interface ReminderClock {
    fun schedule(due: List<Due>)
}

/** Where the app should go when something outside opens it (a launcher shortcut, the widget). */
enum class AppLaunch { NewEntry, AnswerPrompt }

/** The archive export, which runs in the app scope so leaving the screen doesn't stop it. */
sealed interface ExportState {
    data object Idle : ExportState

    data class Running(val progress: ExportProgress?) : ExportState

    data class Done(val report: ExportReport) : ExportState

    data class Failed(val reason: String) : ExportState
}

sealed interface AppMessage {
    data class AttachFailed(val reason: String) : AppMessage

    data object Copied : AppMessage
}

/**
 * Everything the screens need, created once per process by the platform app (it supplies the SQLite
 * driver). Writes run in [scope], which outlives screens, so a closing editor still gets its commit.
 */
class AppServices(
    driver: SqlDriver,
    mediaDir: File,
    val platform: String,
    val deviceName: String,
    /** Large media files kept on this device beyond what still has to reach the server. */
    private val mediaCacheBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Rings reminders on this device (Android alarms, a tray message on desktop); none in tests. */
    private val reminderClock: ReminderClock? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    private val db = BenbowDb(driver)
    private val apis = ApiCache()
    val repo = JournalRepository(db, now)
    val pairing = Pairing(db)
    val history = ServerHistory(db, apis)
    val media = MediaStore(mediaDir, db, apis, now)
    private val engine = SyncEngine(db, apis, now)
    val sync = SyncController(
        scope = scope,
        sync = ::syncAll,
        pending = repo.pendingCount(),
        rejected = repo.rejectedCount(),
        pendingMedia = media.pendingCount(),
        now = now,
    )
    val settings get() = Settings(db)

    val lock = AppLock(Settings(db), now)

    val appearance = MutableStateFlow(Appearance.load(Settings(db)))

    fun setAppearance(value: Appearance) {
        value.save(Settings(db))
        appearance.value = value
    }

    val export = MutableStateFlow<ExportState>(ExportState.Idle)

    /** Writes the whole journal as a ZIP ([ArchiveExport]) after committing any left-over drafts, so it holds what's on screen. */
    fun exportArchive(open: () -> OutputStream, withMedia: Boolean) {
        if (export.value is ExportState.Running) return
        export.value = ExportState.Running(null)
        scope.launch {
            export.value = try {
                repo.commitLeftoverDrafts()
                ExportState.Done(ArchiveExport(db, media).write(open(), withMedia, today()) { export.value = ExportState.Running(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** Things the user must hear about (shown as a snackbar). */
    val messages = MutableSharedFlow<AppMessage>(extraBufferCapacity = 8)

    /** Set by the platform when it is opened for something; the app takes it and clears it. */
    val launch = MutableStateFlow<AppLaunch?>(null)

    @Volatile
    private var reminders: List<ReminderItem> = emptyList()

    init {
        repo.commitLeftoverDrafts()
        sync.start()
        scope.launch { repo.prepareSearch() }
        if (reminderClock != null) {
            scope.launch {
                repo.reminders().collect {
                    reminders = it
                    rescheduleReminders()
                }
            }
        }
    }

    /** Puts the next rings on the clock; called on every change and after a repeating reminder rang. */
    fun rescheduleReminders() {
        reminderClock?.schedule(reminders.upcoming(LocalDateTime.now()))
    }

    /** Text first (small, the outbox), then files captured here; a failed upload retries with backoff. */
    private suspend fun syncAll(): SyncResult {
        val result = engine.sync()
        if (result !is SyncResult.Done) return result
        return try {
            media.uploadPending()
            media.evict(mediaCacheBytes)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed("upload: ${e.message ?: e::class.simpleName}")
        }
    }

    /** The journal day new entries belong to ([DAY_START_HOUR]). */
    fun today(): String = journalToday().toString()
}
