package app.benbow.server.storage

import app.benbow.core.api.DiskSpace
import app.benbow.core.api.StorageReport
import app.benbow.core.media.StorageCount
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
    private val purges: Purges? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
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
            waiting = purges?.waiting().orEmpty().sumOf { it.bytes },
            waitingUntil = purges?.waiting()?.maxOfOrNull { it.dueAt },
        )
    }

    private fun count(): StorageReport {
        val entries = db.locked(::readMediaEntries)
        return StorageCount.of(entries, blobs.all().associate { it.sha256 to it.size }).copy(counted = now())
    }

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
