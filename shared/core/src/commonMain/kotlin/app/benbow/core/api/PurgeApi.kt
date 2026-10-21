package app.benbow.core.api

import app.benbow.core.sync.AttachmentState
import kotlinx.serialization.Serializable

// Deleting for good from the trash (ADR-041, docs/data-safety.md "Purge"): two calls, never an outbox op. The server
// answers what it would delete (`POST /api/v1/purge/preview`), then does exactly that (`POST /api/v1/purge/commit`)
// or nothing: 409 `changed` when anything moved since the preview, 410 `expired` after ten minutes, 400 `not_in_trash`.

@Serializable
data class AttachmentRef(val entryId: String, val attachmentId: String)

/** What to delete for good: whole entries from the trash (with their comments), links taken off or in the trash. */
@Serializable
data class PurgeRequest(
    val entries: List<String> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
    /** Files no entry references (listed in «Место»). */
    val unreferenced: List<String> = emptyList(),
)

/** One file the server would delete: its weight and what it was. */
@Serializable
data class PurgeFileInfo(val sha256: String, val size: Long, val kind: String? = null, val name: String? = null)

/** A file a purge touches that stays: [live] when an entry outside the trash holds it, else others in the trash. */
@Serializable
data class StayingInfo(val sha256: String, val size: Long, val entries: List<String>, val live: Boolean)

/**
 * The server's answer to a preview: the [planId] to commit, the [entries] leaving (comments included) and the [links]
 * becoming purged, the [files] it deletes ([bytes] in all), those [staying] and why, links whose file [notArrived] yet
 * (their copies go from the devices holding them), and when the space is free ([freesOn], after the quarantine).
 */
@Serializable
data class PurgePreview(
    val planId: String,
    val entries: List<String>,
    val links: List<AttachmentRef>,
    val files: List<PurgeFileInfo>,
    val bytes: Long,
    val staying: List<StayingInfo> = emptyList(),
    val notArrived: List<String> = emptyList(),
    val freesOn: Long,
)

@Serializable
data class PurgeCommit(val planId: String)

/** Done: the purge's id (the admin page can undo it until [freesOn]) and the bytes that go then. */
@Serializable
data class PurgeDone(val purgeId: String, val bytes: Long, val freesOn: Long)

/**
 * A line of the purge journal (`db/purge-journal.jsonl`): everything a purge did, so it can be done again after a
 * restore and undone within the quarantine. [undo] lines take an earlier purge back.
 */
@Serializable
data class PurgeRecord(
    val id: String,
    val at: Long,
    val device: String? = null,
    val entries: List<String> = emptyList(),
    val links: List<PurgedLink> = emptyList(),
    val files: List<PurgedFile> = emptyList(),
    val undo: Boolean = false,
)

/** A link as it was before the purge, for the undo. */
@Serializable
data class PurgedLink(val entryId: String, val attachmentId: String, val before: AttachmentState)

/** A file the purge deleted: where it was in the media folder ([path]) and what it was. */
@Serializable
data class PurgedFile(val sha256: String, val size: Long, val path: String, val name: String? = null)
