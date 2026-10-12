package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.content.Document
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.Span
import app.benbow.core.content.TextBlock
import app.benbow.core.content.toPlainText
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.HlcClock
import app.benbow.core.sync.PutRevision
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncOp
import app.benbow.core.uuidV7
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

data class DaySummary(val day: String, val entries: Int)

/** An op the server refused (data-safety S5): kept and shown until the user retries it. */
data class RejectedOp(val opId: String, val entryId: String, val error: String?)

data class EntrySummary(val id: String, val day: String, val createdAt: Long, val title: String, val preview: String, val conflict: Boolean)

/** What the editor shows: the draft when there is one, otherwise the head body. */
data class EntryDetail(
    val id: String,
    val kind: String,
    val day: String,
    val createdAt: Long,
    val body: String,
    val headRevision: String?,
    val fields: Map<String, String?>,
    val conflictHeads: List<String>,
    val hasDraft: Boolean,
) {
    val inTrash: Boolean get() = fields[TRASH_FIELD] == "true"
}

internal const val TRASH_FIELD = "deleted"

private val fieldsSerializer = MapSerializer(String.serializer(), String.serializer().nullable)

/**
 * Offline-first journal data. Every write is one local transaction that also queues the sync op
 * (docs/sync.md#client-local-state), so nothing typed is lost when the server is unreachable.
 */
class JournalRepository(
    private val db: BenbowDb,
    private val now: () -> Long,
    private val context: CoroutineContext = Dispatchers.Default,
    private val random: Random = Random.Default,
) {
    private val q get() = db.benbowQueries
    private val clock = ClientClock(db, now)

    /** All entries outside the trash: newest day first, each day in the order it was written. */
    fun journal(): Flow<List<EntrySummary>> = q.journal(::summaryOf).asFlow().mapToList(context)

    fun days(): Flow<List<DaySummary>> = journal().map { entries ->
        entries.groupBy { it.day }.map { (day, items) -> DaySummary(day, items.size) }
    }

    fun entriesForDay(day: String): Flow<List<EntrySummary>> = q.entriesForDay(day, ::summaryOf).asFlow().mapToList(context)

    /** Most recently trashed first. */
    fun trash(): Flow<List<EntrySummary>> = q.trash(::summaryOf).asFlow().mapToList(context)

    fun entry(id: String): Flow<EntryDetail?> = combine(
        q.selectEntry(id).asFlow().mapToOneOrNull(context),
        q.selectDraft(id).asFlow().mapToOneOrNull(context),
    ) { row, draft ->
        row?.let {
            EntryDetail(
                id = it.id,
                kind = it.kind,
                day = it.day,
                createdAt = it.created_at,
                body = draft?.body ?: it.body,
                headRevision = it.head_rev,
                fields = decodeFields(it.fields),
                conflictHeads = ClientJson.decodeFromString(it.conflict_heads),
                hasDraft = draft != null,
            )
        }
    }

    fun createEntry(day: String, kind: String = "journal", body: String = emptyBody(random)): String {
        val time = now()
        val id = newId()
        val revision = newId()
        val (title, preview) = summarize(body)
        db.transaction {
            q.upsertEntry(id, kind, day, time, revision, body, title, preview, "[]", "{}", time)
            enqueue(CreateEntry(newId(), id, kind, day, time))
            enqueue(PutRevision(newId(), id, revision, emptyList(), body))
        }
        return id
    }

    /** Cheap autosave target for the editor (data-safety S12); not synced until [commitDraft]. */
    fun saveDraft(id: String, body: String) {
        db.transaction {
            val entry = q.selectEntry(id).executeAsOneOrNull() ?: return@transaction
            val base = q.selectDraft(id).executeAsOneOrNull()?.base_rev ?: entry.head_rev
            q.upsertDraft(id, body, base, now())
        }
    }

    /** Quick capture: every line of [text] becomes a paragraph. */
    fun createNote(day: String, text: String): String = createEntry(day, kind = "note", body = textBody(text, random))

    /** Turns the draft into a revision (docs/sync.md#revisions-from-drafts). Returns true if one was created. */
    fun commitDraft(id: String): Boolean = db.transactionWithResult { commitDraftInTransaction(id) }

    /** Drafts left behind by a crash or a killed app would never sync; called at startup, before any editor opens. */
    fun commitLeftoverDrafts(): Int = db.transactionWithResult { q.allDrafts().executeAsList().count { commitDraftInTransaction(it) } }

    /**
     * Makes [body] (an older or a concurrent version) the new head. Any draft is committed first, so
     * nothing typed is lost, and the new revision lists every conflicting head as a parent: picking a
     * version is how the user resolves a conflict (docs/sync.md#revision-conflicts-server).
     */
    fun restoreVersion(id: String, body: String) {
        db.transaction {
            commitDraftInTransaction(id)
            val entry = q.selectEntry(id).executeAsOneOrNull() ?: return@transaction
            val conflicts: List<String> = ClientJson.decodeFromString(entry.conflict_heads)
            if (body == entry.body && conflicts.isEmpty()) return@transaction
            val revision = newId()
            enqueue(PutRevision(newId(), id, revision, (listOfNotNull(entry.head_rev) + conflicts).distinct(), body))
            putHead(id, revision, body)
            q.clearConflicts(id)
        }
    }

    fun setField(id: String, field: String, value: String?) {
        db.transaction {
            val entry = q.selectEntry(id).executeAsOneOrNull() ?: return@transaction
            enqueue(SetField(newId(), id, field, value, clock.next()))
            q.updateFields(ClientJson.encodeToString(fieldsSerializer, decodeFields(entry.fields) + (field to value)), now(), id)
        }
    }

    fun moveToTrash(id: String) = setField(id, TRASH_FIELD, "true")

    fun restore(id: String) = setField(id, TRASH_FIELD, null)

    /** Ops not yet acknowledged by the server, and ops it rejected (shown to the user, never dropped). */
    fun pendingCount(): Flow<Long> = q.pendingCount().asFlow().mapToOne(context)

    fun rejectedCount(): Flow<Long> = q.rejectedCount().asFlow().mapToOne(context)

    fun rejectedOps(): Flow<List<RejectedOp>> =
        q.rejectedOps().asFlow().mapToList(context).map { rows -> rows.map { RejectedOp(it.op_id, it.entry_id, it.last_error) } }

    /** Puts rejected ops back in the queue (e.g. after a server update fixed the cause). */
    fun retryRejected() {
        q.retryRejected()
    }

    private fun commitDraftInTransaction(id: String): Boolean {
        val draft = q.selectDraft(id).executeAsOneOrNull() ?: return false
        val entry = q.selectEntry(id).executeAsOneOrNull() ?: return false
        q.deleteDraft(id)
        if (draft.body == entry.body) return false
        val revision = newId()
        enqueue(PutRevision(newId(), id, revision, listOfNotNull(draft.base_rev), draft.body))
        putHead(id, revision, draft.body)
        return true
    }

    private fun putHead(id: String, revision: String, body: String) {
        val (title, preview) = summarize(body)
        q.updateHead(revision, body, title, preview, now(), id)
    }

    private fun enqueue(op: SyncOp) = q.enqueue(op.opId, op.entryId, ClientJson.encodeToString(SyncOp.serializer(), op), now())

    private fun newId() = uuidV7(now(), random)

    companion object {
        fun emptyBody(random: Random = Random.Default): String = textBody("", random)

        fun textBody(text: String, random: Random = Random.Default): String = DocumentCodec.encode(
            Document(text.trim().lines().map { TextBlock(uuidV7(0, random).takeLast(8), children = listOf(Span(it))) }),
        )
    }
}

@Suppress("UNUSED_PARAMETER")
private fun summaryOf(id: String, day: String, createdAt: Long, title: String, preview: String, conflictHeads: String) =
    EntrySummary(id, day, createdAt, title, preview, conflictHeads != "[]")

/** Title = the first paragraph, preview = the rest as one line (spoilers hidden). */
internal fun summarize(body: String): Pair<String, String> {
    val document = try {
        DocumentCodec.decode(body)
    } catch (_: DocumentFormatException) {
        return "" to ""
    }
    val title = (document.blocks.firstOrNull() as? TextBlock)?.children?.joinToString("") { it.text }.orEmpty().trim()
    val rest = document.toPlainText(includeSpoilers = false).trim().removePrefix(title)
    return title.take(TITLE_MAX) to rest.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(PREVIEW_MAX)
}

private const val TITLE_MAX = 120
private const val PREVIEW_MAX = 200

internal fun decodeFields(json: String): Map<String, String?> = ClientJson.decodeFromString(fieldsSerializer, json)

/** HLC whose last value survives restarts (kept in the local settings). */
internal class ClientClock(private val db: BenbowDb, now: () -> Long) {
    private val settings = Settings(db)
    private val node = settings.get("device.id") ?: settings.get(LOCAL_NODE) ?: uuidV7(now()).also { settings.put(LOCAL_NODE, it) }
    private val clock = HlcClock(node, settings.get(Settings.HLC)?.let { ClientJson.decodeFromString(Hlc.serializer(), it) }, now)

    fun next(): Hlc = clock.next().also(::persist)

    fun observe(remote: Hlc) {
        clock.observe(remote)
        persist(clock.current)
    }

    private fun persist(hlc: Hlc) = settings.put(Settings.HLC, ClientJson.encodeToString(Hlc.serializer(), hlc))

    private companion object {
        const val LOCAL_NODE = "device.local_node"
    }
}
