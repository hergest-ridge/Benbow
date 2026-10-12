package app.benbow.app

import app.benbow.client.ApiCache
import app.benbow.client.JournalRepository
import app.benbow.client.Pairing
import app.benbow.client.ServerHistory
import app.benbow.client.Settings
import app.benbow.client.SyncController
import app.benbow.client.SyncEngine
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Clock

/**
 * Everything the screens need, created once per process by the platform app (it supplies the SQLite
 * driver). Writes run in [scope], which outlives screens, so a closing editor still gets its commit.
 */
class AppServices(driver: SqlDriver, val platform: String, val deviceName: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    private val db = BenbowDb(driver)
    private val apis = ApiCache()
    val repo = JournalRepository(db, now)
    val pairing = Pairing(db)
    val history = ServerHistory(db, apis)
    val sync = SyncController(scope, SyncEngine(db, apis, now)::sync, repo.pendingCount(), repo.rejectedCount(), now)
    val settings get() = Settings(db)

    init {
        repo.commitLeftoverDrafts()
        sync.start()
    }

    /** The journal day new entries belong to ([DAY_START_HOUR]). */
    fun today(): String = journalToday().toString()
}
