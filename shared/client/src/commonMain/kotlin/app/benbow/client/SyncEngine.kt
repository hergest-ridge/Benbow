package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.KnownEntry
import app.benbow.core.api.Protocol
import app.benbow.core.api.ServerInfo
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.EntrySnapshot
import app.benbow.core.sync.OpStatus
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.PutRevision
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncOp
import app.benbow.core.uuidV7
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
 * Reconcile when the server went back in time, then push, then pull (docs/sync.md). The outbox loses an op only when the server answered for it;
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
            val restored = info.epoch != connection.epoch || (info.cursor ?: Long.MAX_VALUE) < settings.cursor
            val healed = if (restored) reconcile(api, connection, info) else 0
            val (pushed, rejected) = pushAll(api)
            SyncResult.Done(healed + pushed, pullAll(api), rejected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /**
     * The server was restored from a backup (docs/sync.md#store-identity): this device's cache re-uploads
     * what the server lacks. Missing entries are recreated; a missing head revision goes back without
     * parents, so if the server moved on from an older head the two are kept as a conflict for the user
     * to pick (S8) instead of one silently winning. Heads still in the outbox go with their real parents.
     * Field values go back with their original clocks. Only then is the cursor reset for a full pull.
     */
    private suspend fun reconcile(api: BenbowApi, connection: Connection, info: ServerInfo): Int {
        val entries = q.allEntries().executeAsList()
        val missingEntries = HashSet<String>()
        val missingRevisions = HashSet<String>()
        for (chunk in entries.chunked(RECONCILE_BATCH)) {
            val answer = api.reconcile(chunk.map { KnownEntry(it.id, it.head_rev) })
            missingEntries += answer.missingEntries
            missingRevisions += answer.missingRevisions
        }
        val queued = q.pendingOps(Long.MAX_VALUE).executeAsList()
            .map { ClientJson.decodeFromString(SyncOp.serializer(), it.op) }
            .filterIsInstance<PutRevision>()
            .mapTo(HashSet()) { it.revisionId }
        val ops = buildList {
            for (entry in entries) {
                val missing = entry.id in missingEntries
                if (missing) add(CreateEntry(newOpId(), entry.id, entry.kind, entry.day, entry.created_at))
                val head = entry.head_rev
                if (head != null && (missing || head in missingRevisions) && head !in queued) {
                    add(PutRevision(newOpId(), entry.id, head, emptyList(), entry.body))
                }
                val values = decodeFields(entry.fields)
                decodeClocks(entry.field_clocks).forEach { (field, hlc) -> add(SetField(newOpId(), entry.id, field, values[field], hlc)) }
                decodeAttachments(entry.attachments).values.forEach { a ->
                    add(PutAttachment(newOpId(), entry.id, a.meta, a.position, a.caption, a.present, a.hlc))
                }
            }
        }
        for (batch in ops.chunked(BATCH)) {
            val results = api.push(batch).results
            // Kept visible like any rejected op (S5).
            results.filter { it.status == OpStatus.Rejected }.forEach { result ->
                val op = batch.first { it.opId == result.opId }
                db.transaction {
                    q.enqueue(op.opId, op.entryId, ClientJson.encodeToString(SyncOp.serializer(), op), now())
                    q.reject(result.message, op.opId)
                }
            }
        }
        db.transaction {
            settings.connection = connection.copy(epoch = info.epoch) // also resets the cursor: change numbers were reused
            q.recheckUploads() // the restored server may lack files it once confirmed
        }
        return ops.size
    }

    private fun newOpId() = uuidV7(now())

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
        val media = mediaSummary(entry.attachments)
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
            ClientJson.encodeToString(clocksSerializer, entry.fields.mapValues { it.value.hlc }),
            ClientJson.encodeToString(attachmentsSerializer, entry.attachments),
            media.first.toLong(),
            media.second,
        )
        q.updateParent(entry.fields[PARENT_FIELD]?.value, entry.id)
        Moments.apply(db, entry.id, entry.fields.mapValues { it.value.value })
        SearchIndex.update(db, entry.id)
    }

    private companion object {
        const val BATCH = 200
        const val RECONCILE_BATCH = 1_000
    }
}
