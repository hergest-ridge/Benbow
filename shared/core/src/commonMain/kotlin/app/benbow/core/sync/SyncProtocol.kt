package app.benbow.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Client → server operations (docs/sync.md#op-types-v1). Every op is idempotent by [opId]. */
@Serializable
sealed interface SyncOp {
    val opId: String
    val entryId: String
}

@Serializable
@SerialName("entry.create")
data class CreateEntry(
    override val opId: String,
    override val entryId: String,
    val kind: String,
    val day: String,
    val createdAt: Long,
) : SyncOp

@Serializable
@SerialName("entry.revision.put")
data class PutRevision(
    override val opId: String,
    override val entryId: String,
    val revisionId: String,
    val parents: List<String>,
    val body: String,
) : SyncOp

@Serializable
@SerialName("entry.meta.set")
data class SetField(
    override val opId: String,
    override val entryId: String,
    val field: String,
    val value: String?,
    val hlc: Hlc,
) : SyncOp

@Serializable
enum class OpStatus { Applied, Duplicate, Merged, Conflict, Rejected }

@Serializable
data class OpResult(val opId: String, val status: OpStatus, val message: String? = null)

/** Last-writer-wins value ordered by [hlc]. */
@Serializable
data class LwwValue(val value: String?, val hlc: Hlc)

@Serializable
data class EntryState(
    val id: String,
    val kind: String,
    val day: String,
    val createdAt: Long,
    val head: String? = null,
    val conflictHeads: List<String> = emptyList(),
    val fields: Map<String, LwwValue> = emptyMap(),
)

@Serializable
data class RevisionRecord(val id: String, val entryId: String, val parents: List<String>, val body: String, val origin: String)

@Serializable
data class EntrySnapshot(val entry: EntryState, val headBody: String?)

@Serializable
data class PullResponse(val changes: List<EntrySnapshot>, val nextCursor: Long, val hasMore: Boolean)
