package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.Protocol
import app.benbow.core.sync.EntrySnapshot
import app.benbow.core.sync.OpStatus
import app.benbow.core.sync.SyncOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

sealed interface SyncResult {
    data class Done(val pushed: Int, val pulled: Int, val rejected: Int) : SyncResult
    data object NotPaired : SyncResult

    /** The server is a different store than the one this device paired with (S11): nothing is sent. */
    data class WrongServer(val pairedStore: String, val serverStore: String) : SyncResult
    data class Incompatible(val serverMin: Int, val serverMax: Int) : SyncResult
    data class Failed(val reason: String) : SyncResult
}

/**
 * Push, then pull (docs/sync.md). The outbox loses an op only when the server answered for it;
 * a lost request or response just means a retry, which the server dedupes. Pulled state never
 * overwrites an entry with pending local ops, and never touches drafts.
 */
class SyncEngine(private val db: BenbowDb, private val apiFor: (Connection) -> BenbowApi, private val now: () -> Long) {
    private val q get() = db.benbowQueries
    private val settings = Settings(db)
    private val clock = ClientClock(db, now)
    private val mutex = Mutex()

    suspend fun sync(): SyncResult = mutex.withLock {
        val connection = settings.connection ?: return SyncResult.NotPaired
        val api = apiFor(connection)
        try {
            val info = api.info()
            if (info.storeId != connection.storeId) return SyncResult.WrongServer(connection.storeId, info.storeId)
            if (Protocol.MAX !in info.protocolMin..info.protocolMax) return SyncResult.Incompatible(info.protocolMin, info.protocolMax)
            val (pushed, rejected) = pushAll(api)
            SyncResult.Done(pushed, pullAll(api), rejected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private suspend fun pushAll(api: BenbowApi): Pair<Int, Int> {
        var pushed = 0
        var rejected = 0
        while (true) {
            val batch = q.pendingOps(BATCH.toLong()).executeAsList()
            if (batch.isEmpty()) return pushed to rejected
            val ops = batch.map { ClientJson.decodeFromString(SyncOp.serializer(), it.op) }
            val results = api.push(ops).results
            db.transaction {
                for (result in results) {
                    if (result.status == OpStatus.Rejected) {
                        q.reject(result.message, result.opId)
                        rejected++
                    } else {
                        q.acknowledge(result.opId)
                    }
                }
            }
            pushed += results.size
            if (results.size < batch.size) return pushed to rejected // the rest stays queued for the next sync
        }
    }

    private suspend fun pullAll(api: BenbowApi): Int {
        var pulled = 0
        while (true) {
            val page = api.pull(settings.cursor, BATCH)
            db.transaction {
                page.changes.forEach(::apply)
                settings.cursor = page.nextCursor
            }
            pulled += page.changes.size
            if (!page.hasMore) return pulled
        }
    }

    private fun apply(snapshot: EntrySnapshot) {
        val entry = snapshot.entry
        entry.fields.values.forEach { clock.observe(it.hlc) }
        if (q.pendingCountFor(entry.id).executeAsOne() > 0) return
        val existing = q.selectEntry(entry.id).executeAsOneOrNull()
        val body = snapshot.headBody ?: existing?.body ?: JournalRepository.emptyBody()
        val (title, preview) = summarize(body)
        q.upsertEntry(
            entry.id,
            entry.kind,
            entry.day,
            entry.createdAt,
            entry.head,
            body,
            title,
            preview,
            ClientJson.encodeToString(ListSerializer(String.serializer()), entry.conflictHeads),
            ClientJson.encodeToString(
                MapSerializer(String.serializer(), String.serializer().nullable),
                entry.fields.mapValues {
                    it.value.value
                },
            ),
            now(),
        )
    }

    private companion object {
        const val BATCH = 200
    }
}
