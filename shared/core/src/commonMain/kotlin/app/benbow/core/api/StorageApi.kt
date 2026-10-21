package app.benbow.core.api

import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/storage` (ADR-041, 7.18 M4): what the server's files weigh, each file once, counted by the server from
 * every entry and every file it holds. [kinds] and [states] split [bytes] (their keys in [StorageKind] and
 * [StorageState]); [months] are the months of the entries holding the files, oldest first, by kind.
 */
@Serializable
data class StorageReport(
    val files: Int = 0,
    val bytes: Long = 0,
    val kinds: Map<String, Long> = emptyMap(),
    val states: Map<String, Long> = emptyMap(),
    val months: List<StorageMonth> = emptyList(),
    /** The disk of the media folder; null when the server can't tell. */
    val disk: DiskSpace? = null,
    /** The disk of the backup mirror (the second disk); null when there is none or it is missing. */
    val mirror: DiskSpace? = null,
    /** The journal's database file and its write-ahead log. */
    val database: Long = 0,
    /** The verified snapshots of the database (`backups/db`). */
    val snapshots: Long = 0,
    /** When the server counted it (ms). */
    val counted: Long = 0,
)

/** One month ("YYYY-MM") of the entries that hold files: bytes by kind. */
@Serializable
data class StorageMonth(val month: String, val kinds: Map<String, Long>)

@Serializable
data class DiskSpace(val total: Long, val free: Long)

/** What a file is, as the storage view groups it: an attachment's kind, or a picture standing for something. */
object StorageKind {
    const val VIDEO = "video"
    const val IMAGE = "image"
    const val SOUND = "sound"
    const val DRAWING = "drawing"
    const val FILE = "file"

    /** Previews of attachments and the pictures of link cards. */
    const val PREVIEW = "preview"

    val ALL = listOf(VIDEO, IMAGE, SOUND, DRAWING, FILE, PREVIEW)

    /** The storage kind of an attachment of [kind] (voice notes are sounds too). */
    fun of(kind: String): String = when (kind) {
        "video" -> VIDEO
        "image" -> IMAGE
        "audio", "voice" -> SOUND
        "drawing" -> DRAWING
        else -> FILE
    }
}

/** How alive a file is: shown somewhere, only in the trash, only on links taken off, or held by nothing at all. */
object StorageState {
    const val LIVE = "live"
    const val TRASH = "trash"
    const val REMOVED = "removed"
    const val UNREFERENCED = "unreferenced"

    val ALL = listOf(LIVE, TRASH, REMOVED, UNREFERENCED)
}
