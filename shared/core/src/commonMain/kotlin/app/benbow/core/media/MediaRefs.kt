package app.benbow.core.media

import app.benbow.core.sync.AttachmentState

/** What [MediaRefs] needs of an entry: [bodies] are its head and conflict heads as stored (canonical Document JSON). */
data class MediaEntry(
    val id: String,
    val trashed: Boolean,
    val parent: String? = null,
    val attachments: Map<String, AttachmentState> = emptyMap(),
    val bodies: List<String> = emptyList(),
)

/** How a reference names its file: an attachment's own file, its preview, or a SHA-256 in the entry's text. */
enum class MediaRole { File, Preview, Text }

/**
 * [Live]: shown. [Trash]: its entry, or the entry it comments on, is in the trash. [Removed]: the link was taken off its
 * entry (`present = false`) and waits in the trash. [Purged]: deleted for good, it holds nothing.
 */
enum class MediaState { Live, Trash, Removed, Purged }

data class AttachmentKey(val entryId: String, val attachmentId: String)

/** [attachmentId] is null for [MediaRole.Text]; [size] is the attachment's for [MediaRole.File], else unknown here. */
data class MediaRef(
    val sha256: String,
    val entryId: String,
    val attachmentId: String?,
    val role: MediaRole,
    val state: MediaState,
    val size: Long? = null,
) {
    val holds: Boolean get() = state != MediaState.Purged
}

/** What to delete for good: whole entries (their comments go with them), links, files no entry references. */
data class PurgeSelection(
    val entries: Set<String> = emptySet(),
    val attachments: Set<AttachmentKey> = emptySet(),
    val unreferenced: Set<String> = emptySet(),
)

/** A file a purge deletes; [size] null when nobody knows it (the server always does). */
data class PurgeFile(val sha256: String, val size: Long?)

/** A file a purge would touch that stays, because [keptBy] still hold it. */
data class StayingFile(val sha256: String, val size: Long?, val keptBy: List<MediaRef>)

sealed interface PurgePlan {
    /** Nothing is purged: these aren't in the trash (or aren't known), or these "unreferenced" files are referenced. */
    data class Refused(val entries: List<String>, val attachments: List<AttachmentKey>, val referenced: List<String>) : PurgePlan

    /** [entries] leave (comments included), [links] become purged, [files] are deleted, [staying] are kept. */
    data class Ready(
        val entries: List<String>,
        val links: List<AttachmentKey>,
        val files: List<PurgeFile>,
        val staying: List<StayingFile>,
    ) : PurgePlan {
        val bytes: Long get() = files.sumOf { it.size ?: 0 }
    }
}

/**
 * Who holds each media file, by SHA-256 (ADR-041, docs/data-safety.md "Purge"). Every attachment link that isn't
 * purged holds its file and its preview, whether it is shown, taken off its entry or in the trash; an entry's head
 * and conflict heads hold every SHA-256 they name (link cards' pictures, and whatever a later block may name, so a
 * body this build can't read still holds its files). The server counts with it for every purge preview and clients
 * for their estimates, so both agree on what a purge frees. Counting a name that isn't a file only keeps a file.
 */
class MediaRefs(entries: Collection<MediaEntry>) {
    private val byId = entries.associateBy { it.id }
    private val comments = entries.filter { it.parent != null }.groupBy { it.parent!! }

    val refs: List<MediaRef> = entries.flatMap(::refsOf)
    private val bySha = refs.groupBy { it.sha256 }

    /** Files some reference holds; a file on the server outside this set is unreferenced. */
    val held: Set<String> = refs.filter { it.holds }.mapTo(HashSet()) { it.sha256 }

    /** Every reference to [sha256], holding or not. */
    fun of(sha256: String): List<MediaRef> = bySha[sha256].orEmpty()

    /** In the trash itself, or a comment on an entry in the trash. */
    fun inTrash(entry: MediaEntry): Boolean {
        val seen = HashSet<String>()
        var current: MediaEntry? = entry
        while (current != null && seen.add(current.id)) {
            if (current.trashed) return true
            current = current.parent?.let(byId::get)
        }
        return false
    }

    /** What purging [selection] would do. [sizeOf] gives a file's size where it is known (the server's blobs). */
    fun plan(selection: PurgeSelection, sizeOf: (String) -> Long? = { null }): PurgePlan {
        val entriesOut = selection.entries.filter { id -> byId[id]?.let { !inTrash(it) } ?: true }
        val linksOut = selection.attachments.filter { stateOf(it) !in WAITING }
        val referenced = selection.unreferenced.filter { it in held }
        if (entriesOut.isNotEmpty() || linksOut.isNotEmpty() || referenced.isNotEmpty()) {
            return PurgePlan.Refused(entriesOut, linksOut, referenced)
        }
        val leaving = withComments(selection.entries)
        // In the order of refs, so a plan lists its files the same way every time.
        val going = refs.filterTo(LinkedHashSet()) { ref ->
            ref.holds &&
                (ref.entryId in leaving || ref.attachmentId?.let { AttachmentKey(ref.entryId, it) in selection.attachments } == true)
        }
        val touched = LinkedHashSet<String>().apply {
            going.mapTo(this) { it.sha256 }
            addAll(selection.unreferenced)
        }
        val files = mutableListOf<PurgeFile>()
        val staying = mutableListOf<StayingFile>()
        for (sha in touched) {
            val size = sizeOf(sha) ?: of(sha).firstNotNullOfOrNull { it.size }
            val keptBy = of(sha).filter { it.holds && it !in going }
            if (keptBy.isEmpty()) files += PurgeFile(sha, size) else staying += StayingFile(sha, size, keptBy)
        }
        val links = going.mapNotNullTo(LinkedHashSet()) { ref -> ref.attachmentId?.let { AttachmentKey(ref.entryId, it) } }
        return PurgePlan.Ready(leaving.toList(), links.sortedWith(compareBy({ it.entryId }, { it.attachmentId })), files, staying)
    }

    private fun stateOf(key: AttachmentKey): MediaState? {
        val entry = byId[key.entryId] ?: return null
        return entry.attachments[key.attachmentId]?.let { stateOf(entry, it) }
    }

    private fun stateOf(entry: MediaEntry, link: AttachmentState): MediaState = when {
        link.purged -> MediaState.Purged
        !link.present -> MediaState.Removed
        inTrash(entry) -> MediaState.Trash
        else -> MediaState.Live
    }

    private fun withComments(ids: Set<String>): Set<String> {
        val all = LinkedHashSet<String>()
        val queue = ArrayDeque(ids)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (all.add(id)) comments[id]?.forEach { queue += it.id }
        }
        return all
    }

    private fun refsOf(entry: MediaEntry): List<MediaRef> = buildList {
        for ((id, link) in entry.attachments) {
            val state = stateOf(entry, link)
            add(MediaRef(link.meta.sha256, entry.id, id, MediaRole.File, state, link.meta.size))
            link.meta.previewSha256?.let { add(MediaRef(it, entry.id, id, MediaRole.Preview, state)) }
        }
        val textState = if (inTrash(entry)) MediaState.Trash else MediaState.Live
        entry.bodies.flatMapTo(LinkedHashSet(), ::namedFiles).forEach { add(MediaRef(it, entry.id, null, MediaRole.Text, textState)) }
    }

    companion object {
        private val WAITING = setOf(MediaState.Trash, MediaState.Removed)

        /** Every run of exactly 64 lowercase hex digits in [text]: the SHA-256s it may name. */
        fun namedFiles(text: String): Set<String> {
            val found = LinkedHashSet<String>()
            var start = -1
            for (i in 0..text.length) {
                val hex = i < text.length && text[i].let { it in '0'..'9' || it in 'a'..'f' }
                if (hex && start < 0) start = i
                if (!hex && start >= 0) {
                    if (i - start == 64) found += text.substring(start, i)
                    start = -1
                }
            }
            return found
        }
    }
}
