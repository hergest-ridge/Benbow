package app.benbow.client

import app.benbow.core.audio.TrackTags
import app.benbow.core.board.Board
import app.benbow.core.media.AttachmentKey
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.Hlc
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

// The media library (ADR-041, 7.18 M2): every attachment shown in an entry, read from attachment_ref with its entry
// joined, never parsing an entry's attachments per row. Taking one out is taking it off its entry: it waits in the
// trash with its file, and «Вернуть» puts it back.

/** The library's sections, by the kinds of attachment each shows; [Files] has every kind the others don't. */
enum class LibrarySection(private val kinds: Set<String>) {
    Visual(setOf("image", "video")),
    Sounds(setOf("audio", "voice")),
    Drawings(setOf("drawing")),
    Files(emptySet()),
    ;

    companion object {
        fun of(kind: String): LibrarySection = entries.firstOrNull { kind in it.kinds } ?: Files
    }
}

/**
 * One attachment in the library. [entryId] holds it; [postId] is the entry it belongs to (a comment's own entry), whose
 * [day], place ([board]) and [veil] it shows with. [attachment] is for showing only: its clock is not the link's.
 * [since]: when the link last changed (in the trash: when it was taken off).
 */
data class LibraryItem(
    val entryId: String,
    val postId: String,
    val day: String,
    val board: Boolean,
    val veil: Veil?,
    val attachment: AttachmentState,
    val track: TrackTags? = null,
    val since: Long? = null,
) {
    val key: AttachmentKey get() = AttachmentKey(entryId, attachment.meta.id)
    val section: LibrarySection get() = LibrarySection.of(attachment.meta.kind)
    val size: Long get() = attachment.meta.size
}

/** Every attachment shown in an entry, the newest entries first (their attachments in their order), veiled as their entries. */
fun JournalRepository.library(): Flow<List<LibraryItem>> = veiled(q.libraryItems(::libraryRow).asFlow().mapToList(context))

/** The trash's «Вложения»: attachments taken off entries that aren't in the trash, the latest taken off first. */
fun JournalRepository.takenOff(): Flow<List<LibraryItem>> = veiled(q.takenOff(::libraryRow).asFlow().mapToList(context))

/** What an entry in the trash holds: its files and its comments' (each file once), and since when it is there. */
data class TrashWeight(val bytes: Long, val since: Long?)

/** The trash's media (ADR-041): each trashed entry's weight and age, and what all of it weighs (each file once). */
data class TrashMedia(val entries: Map<String, TrashWeight> = emptyMap(), val bytes: Long = 0)

fun JournalRepository.mediaInTrash(): Flow<TrashMedia> = combine(
    q.trashFiles().asFlow().mapToList(context),
    q.trashSince().asFlow().mapToList(context),
    q.takenOff(::libraryRow).asFlow().mapToList(context),
) { files, since, off ->
    val byEntry = files.groupBy { it.id }
    val comments = files.filter { it.parent_id != null }.groupBy { it.parent_id!! }
    val entries = since.associate { row ->
        val held = byEntry[row.id].orEmpty() + comments[row.id].orEmpty()
        row.id to TrashWeight(held.distinctBy { it.sha256 }.sumOf { it.size }, row.since ?: row.updated_at)
    }
    val all = files.map { it.sha256 to it.size } + off.map { it.item.attachment.meta.sha256 to it.item.size }
    TrashMedia(entries, all.distinctBy { it.first }.sumOf { it.second })
}

/** Takes the attachments off their entries, all at once: they wait in the trash with their files. */
fun JournalRepository.removeAttachments(keys: Collection<AttachmentKey>) = db.transaction {
    keys.forEach { removeAttachment(it.entryId, it.attachmentId) }
}

/** «Вернуть»: the attachments back on their entries. */
fun JournalRepository.restoreAttachments(keys: Collection<AttachmentKey>) = db.transaction {
    keys.forEach { restoreAttachment(it.entryId, it.attachmentId) }
}

/** A row of libraryItems or takenOff (the same columns) with what its veil is made from. */
private class LibraryRow(val item: LibraryItem, val postFields: String)

private fun libraryRow(
    entryId: String,
    attachmentId: String,
    sha256: String,
    kind: String,
    mime: String,
    size: Long,
    name: String?,
    width: Long?,
    height: Long?,
    durationMs: Long?,
    capturedAt: Long?,
    previewSha256: String?,
    caption: String?,
    position: Double,
    changedAt: Long?,
    postId: String,
    day: String,
    space: String?,
    postFields: String,
    fields: String,
): LibraryRow {
    val meta =
        AttachmentMeta(attachmentId, sha256, size, mime, kind, name, width?.toInt(), height?.toInt(), durationMs, capturedAt, previewSha256)
    val item = LibraryItem(
        entryId = entryId,
        postId = postId,
        day = day,
        board = space == Board.BOARD,
        veil = null,
        attachment = AttachmentState(meta, position, caption, hlc = Hlc.ZERO),
        track = if (kind == "audio") TrackTags.of(decodeFields(fields))[attachmentId] else null,
        since = changedAt,
    )
    return LibraryRow(item, postFields)
}

/** Each row veiled as its entry (Бережно's rules, its own mark), each entry's fields read once. */
private fun JournalRepository.veiled(rows: Flow<List<LibraryRow>>): Flow<List<LibraryItem>> = combine(rows, gentleRules()) { list, rules ->
    val veils = HashMap<String, Veil?>()
    list.map { row ->
        val veil = veils.getOrPut(row.item.postId) {
            val fields = decodeFields(row.postFields)
            rules.veilOf(Veil.of(fields[VEIL_FIELD]), fields[VEIL_UNTIL_FIELD], row.item.day, tagsOf(fields).map { it.id })
        }
        row.item.copy(veil = veil)
    }
}
