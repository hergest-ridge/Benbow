package app.benbow.app

import app.benbow.client.ApiCache
import app.benbow.client.JournalRepository
import app.benbow.client.MediaStore
import app.benbow.client.Pairing
import app.benbow.client.ServerHistory
import app.benbow.client.Settings
import app.benbow.client.SyncController
import app.benbow.client.SyncEngine
import app.benbow.client.SyncResult
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Clock

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

    /** Things the user must hear about (shown as a snackbar). */
    val messages = MutableSharedFlow<AppMessage>(extraBufferCapacity = 8)

    init {
        repo.commitLeftoverDrafts()
        sync.start()
        scope.launch { repo.prepareSearch() }
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
