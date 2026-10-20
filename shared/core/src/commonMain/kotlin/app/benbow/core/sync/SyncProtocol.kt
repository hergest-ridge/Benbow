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
data class CreateEntry(override val opId: String, override val entryId: String, val kind: String, val day: String, val createdAt: Long) :
    SyncOp

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
data class SetField(override val opId: String, override val entryId: String, val field: String, val value: String?, val hlc: Hlc) : SyncOp

/**
 * Links a media file to an entry, or changes the link (docs/sync.md#blobs). Last writer wins per
 * (entry, attachment) by [hlc]; removing is `present = false` (S6). The file itself travels separately,
 * by its SHA-256, and may arrive later than the link. Protocol 2.
 */
@Serializable
@SerialName("entry.attachment.put")
data class PutAttachment(
    override val opId: String,
    override val entryId: String,
    val attachment: AttachmentMeta,
    val position: Double,
    val caption: String? = null,
    val present: Boolean = true,
    val hlc: Hlc,
) : SyncOp

/** What a media file is; fixed once created (a preview may be added later). */
@Serializable
data class AttachmentMeta(
    val id: String,
    val sha256: String,
    val size: Long,
    val mime: String,
    /** image | video | audio | voice | file */
    val kind: String,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("captured_at") val capturedAt: Long? = null,
    @SerialName("preview_sha256") val previewSha256: String? = null,
)

@Serializable
data class AttachmentState(
    val meta: AttachmentMeta,
    val position: Double,
    val caption: String? = null,
    val present: Boolean = true,
    val hlc: Hlc,
    /** Deleted for good from the trash (ADR-041): the link holds no file any more. Only the server's purge sets it (7.18 M5). */
    val purged: Boolean = false,
)

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
    val attachments: Map<String, AttachmentState> = emptyMap(),
)

@Serializable
data class RevisionRecord(val id: String, val entryId: String, val parents: List<String>, val body: String, val origin: String)

@Serializable
data class EntrySnapshot(val entry: EntryState, val headBody: String?)

@Serializable
data class PullResponse(val changes: List<EntrySnapshot>, val nextCursor: Long, val hasMore: Boolean)
