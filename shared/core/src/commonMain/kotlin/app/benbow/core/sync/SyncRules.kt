package app.benbow.core.sync

/**
 * Server-side storage the sync rules run on. The real server implements it over SQLite with each
 * [transaction] being one SQL transaction (data-safety S2); tests use an in-memory version.
 */
interface SyncStore {
    fun <T> transaction(block: () -> T): T
    fun appliedResult(opId: String): OpResult?
    fun recordApplied(result: OpResult)
    fun entry(id: String): EntryState?
    fun putEntry(entry: EntryState)
    fun revision(id: String): RevisionRecord?
    fun putRevision(revision: RevisionRecord)
    /** Appends to the change log and returns the new sequence number. */
    fun appendChange(entryId: String): Long
    /** Entity ids changed after [cursor], oldest first, as (seq, entryId). */
    fun changesSince(cursor: Long, limit: Int): List<Pair<Long, String>>
    fun newRevisionId(): String
}

/**
 * The sync contract of docs/sync.md: idempotent ops (S4), append-only revisions (S1),
 * conflicts keep both heads (S8), LWW metadata by HLC.
 */
class SyncRules(
    private val store: SyncStore,
    private val merge: (base: String, ours: String, theirs: String) -> String? = ::mergeDocumentBodies,
) {
    fun push(ops: List<SyncOp>): List<OpResult> = ops.map { op ->
        store.transaction {
            store.appliedResult(op.opId)?.copy(status = OpStatus.Duplicate)
                ?: apply(op).also(store::recordApplied)
        }
    }

    fun pull(cursor: Long, limit: Int): PullResponse {
        val changes = store.changesSince(cursor, limit + 1)
        val page = changes.take(limit)
        val snapshots = page.map { it.second }.distinct().map { id ->
            val entry = store.entry(id) ?: error("change log points to missing entry $id")
            EntrySnapshot(entry, entry.head?.let { store.revision(it)?.body })
        }
        return PullResponse(snapshots, page.lastOrNull()?.first ?: cursor, changes.size > limit)
    }

    private fun apply(op: SyncOp): OpResult = when (op) {
        is CreateEntry -> {
            if (store.entry(op.entryId) == null) {
                store.putEntry(EntryState(op.entryId, op.kind, op.day, op.createdAt))
                store.appendChange(op.entryId)
            }
            OpResult(op.opId, OpStatus.Applied)
        }
        is SetField -> {
            val entry = store.entry(op.entryId) ?: return OpResult(op.opId, OpStatus.Rejected, "unknown entry")
            val current = entry.fields[op.field]
            if (current == null || op.hlc > current.hlc) {
                store.putEntry(entry.copy(fields = entry.fields + (op.field to LwwValue(op.value, op.hlc))))
                store.appendChange(op.entryId)
            }
            OpResult(op.opId, OpStatus.Applied)
        }
        is PutRevision -> putRevision(op)
    }

    private fun putRevision(op: PutRevision): OpResult {
        val entry = store.entry(op.entryId) ?: return OpResult(op.opId, OpStatus.Rejected, "unknown entry")
        if (store.revision(op.revisionId) != null) return OpResult(op.opId, OpStatus.Duplicate)
        // Stored before anything else: whatever happens next, the incoming text is never lost.
        store.putRevision(RevisionRecord(op.revisionId, op.entryId, op.parents, op.body, "edit"))
        val head = entry.head
        val result = when {
            head == null || head in op.parents -> {
                store.putEntry(entry.copy(head = op.revisionId, conflictHeads = entry.conflictHeads - op.parents.toSet()))
                OpResult(op.opId, OpStatus.Applied)
            }
            else -> mergeConcurrent(entry, head, op)
        }
        store.appendChange(op.entryId)
        return result
    }

    private fun mergeConcurrent(entry: EntryState, head: String, op: PutRevision): OpResult {
        val baseId = op.parents.firstOrNull()
        // diff3 is only sound when the base is an ancestor of the head; otherwise the head lacks the
        // base's changes and the merge would silently drop them. Keep both heads instead.
        val base = baseId?.takeIf { isAncestor(it, head) }?.let { store.revision(it)?.body }
        val ours = store.revision(head)?.body
        val merged = if (base != null && ours != null) merge(base, ours, op.body) else null
        if (merged == null) {
            store.putEntry(entry.copy(conflictHeads = (entry.conflictHeads + head + op.revisionId).distinct()))
            return OpResult(op.opId, OpStatus.Conflict)
        }
        val mergeId = store.newRevisionId()
        store.putRevision(RevisionRecord(mergeId, entry.id, listOf(head, op.revisionId), merged, "merge"))
        store.putEntry(entry.copy(head = mergeId))
        return OpResult(op.opId, OpStatus.Merged)
    }

    private fun isAncestor(candidate: String, of: String): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(of))
        while (queue.isNotEmpty() && seen.size < MAX_ANCESTRY_WALK) {
            val id = queue.removeFirst()
            if (id == candidate) return true
            if (seen.add(id)) store.revision(id)?.parents?.let(queue::addAll)
        }
        return false
    }

    private companion object {
        const val MAX_ANCESTRY_WALK = 10_000
    }
}
