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
 */
data class LibraryItem(
    val entryId: String,
    val postId: String,
    val day: String,
    val board: Boolean,
    val veil: Veil?,
    val attachment: AttachmentState,
    val track: TrackTags? = null,
) {
    val key: AttachmentKey get() = AttachmentKey(entryId, attachment.meta.id)
    val section: LibrarySection get() = LibrarySection.of(attachment.meta.kind)
    val size: Long get() = attachment.meta.size
}

/** Every attachment shown in an entry, the newest entries first (their attachments in their order), veiled as their entries. */
fun JournalRepository.library(): Flow<List<LibraryItem>> = combine(q.libraryItems().asFlow().mapToList(context), gentleRules()) {
        rows,
        rules,
    ->
    val veils = HashMap<String, Veil?>()
    rows.map { row ->
        val veil = veils.getOrPut(row.post_id) {
            val fields = decodeFields(row.post_fields)
            rules.veilOf(Veil.of(fields[VEIL_FIELD]), fields[VEIL_UNTIL_FIELD], row.day, tagsOf(fields).map { it.id })
        }
        val meta = AttachmentMeta(
            row.attachment_id,
            row.sha256,
            row.size,
            row.mime,
            row.kind,
            row.name,
            row.width?.toInt(),
            row.height?.toInt(),
            row.duration_ms,
            row.captured_at,
            row.preview_sha256,
        )
        LibraryItem(
            entryId = row.entry_id,
            postId = row.post_id,
            day = row.day,
            board = row.space == Board.BOARD,
            veil = veil,
            attachment = AttachmentState(meta, row.position, row.caption, hlc = Hlc.ZERO),
            track = if (row.kind == "audio") TrackTags.of(decodeFields(row.fields))[row.attachment_id] else null,
        )
    }
}

/** Takes the attachments off their entries, all at once: they wait in the trash with their files. */
fun JournalRepository.removeAttachments(keys: Collection<AttachmentKey>) = db.transaction {
    keys.forEach { removeAttachment(it.entryId, it.attachmentId) }
}

/** «Вернуть»: the attachments back on their entries. */
fun JournalRepository.restoreAttachments(keys: Collection<AttachmentKey>) = db.transaction {
    keys.forEach { restoreAttachment(it.entryId, it.attachmentId) }
}
