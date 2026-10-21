package app.benbow.server.storage

import app.benbow.core.api.DiskSpace
import app.benbow.core.api.StorageReport
import app.benbow.core.media.MediaEntry
import app.benbow.core.media.StorageCount
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.LwwValue
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/**
 * The server's side of «Место» (ADR-041, 7.18 M4): every entry and every file it holds counted with [StorageCount],
 * kept until the journal or the files change; the disks and the backups are read at each ask (they change without
 * either). Counting reads every entry with its head (and conflict heads): for one owner well under a second.
 */
class StorageReports(
    private val db: Database,
    private val blobs: BlobStore,
    private val dataDir: Path?,
    private val mirror: Path?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: Pair<Any, StorageReport>? = null

    fun report(): StorageReport {
        val key = db.latestCursor() to blobs.stats()
        val counted = cached?.takeIf { it.first == key }?.second ?: count().also { cached = key to it }
        return counted.copy(
            disk = space(blobs.root),
            mirror = mirror?.takeIf { it.exists() }?.let(::space),
            database =
            dataDir?.let { dir -> listOf("benbow.sqlite", "benbow.sqlite-wal").sumOf { size(dir.resolve("db").resolve(it)) } } ?: 0,
            snapshots = dataDir?.resolve("backups")?.resolve("db")?.let(::folderSize) ?: 0,
        )
    }

    private fun count(): StorageReport {
        val entries = db.locked { c ->
            val rows = c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT e.id, e.day, e.fields, e.attachments, e.conflict_heads, r.body FROM entries e " +
                        "LEFT JOIN revisions r ON r.id = e.head_rev",
                ).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                            )
                        }
                    }
                }
            }
            rows.map { row ->
                val fields = json.decodeFromString<Map<String, LwwValue>>(row.fields)
                val conflicts = json.decodeFromString<List<String>>(row.conflictHeads).mapNotNull { id ->
                    c.prepareStatement("SELECT body FROM revisions WHERE id = ?").use { st ->
                        st.setString(1, id)
                        st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
                }
                MediaEntry(
                    id = row.id,
                    trashed = fields["deleted"]?.value == "true",
                    parent = fields["parent"]?.value,
                    attachments = json.decodeFromString<Map<String, AttachmentState>>(row.attachments),
                    bodies = listOfNotNull(row.head) + conflicts,
                    day = row.day,
                )
            }
        }
        return StorageCount.of(entries, blobs.all().associate { it.sha256 to it.size }).copy(counted = now())
    }

    private class Row(
        val id: String,
        val day: String,
        val fields: String,
        val attachments: String,
        val conflictHeads: String,
        val head: String?,
    )

    /** The disk [path] is on (its nearest existing folder: the media folder may not be made yet). */
    private fun space(path: Path): DiskSpace? = runCatching {
        val existing = generateSequence(path.toAbsolutePath()) { it.parent }.first { it.exists() }
        val store = Files.getFileStore(existing)
        DiskSpace(store.totalSpace, store.usableSpace)
    }.getOrNull()

    private fun size(file: Path): Long = if (file.isRegularFile()) file.fileSize() else 0

    private fun folderSize(folder: Path): Long = if (!folder.exists()) {
        0
    } else {
        Files.walk(folder).use { paths -> paths.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum() }
    }
}
