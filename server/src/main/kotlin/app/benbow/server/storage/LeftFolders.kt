package app.benbow.server.storage

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The folders a media move left behind (ADR-042, as built 7.19 P6): kept a week as a spare copy, then deleted only on the
 * owner's word and only after a check, done again right before deleting, finds every file safe: the same bytes (SHA-256)
 * at the same place in the media folder now, or a file the owner deleted for good (the purge's records name it); uploads
 * that were under way at the move go too. Only a folder whose mark says it is this store's and moved; never one that is or
 * holds the media folder, the data folder or the mirror. Anything that doesn't check stops it all: nothing is deleted.
 */
class LeftFolders(
    private val db: Database,
    private val blobs: BlobStore,
    private val dataDir: Path,
    private val move: MediaMove,
    private val mirror: () -> Path? = { null },
    private val runner: (Runnable) -> Unit = background(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What a check found in [path]: [files] files of [bytes] safe to delete ([purged] of them deleted for good), or why not. */
    data class Report(
        val path: String,
        val files: Int,
        val bytes: Long,
        val purged: Int,
        val problems: List<String>,
        val deleted: Boolean = false,
    )

    data class Progress(val path: String, val deleting: Boolean, val files: Int, val totalFiles: Int)

    private val log = LoggerFactory.getLogger("benbow.media")

    @Volatile
    var report: Report? = null
        private set

    @Volatile
    var progress: Progress? = null
        private set

    /** When [left] may be deleted: a week after its move. */
    fun deletableFrom(left: MediaMove.Left): Long = left.at + WAIT_MILLIS

    fun due(left: MediaMove.Left): Boolean = now() >= deletableFrom(left)

    /** Checks [path] in the background; the result shows as [report]. */
    fun check(path: String) = start(path, delete = false)

    /** Checks [path] again in the background and, when everything is safe, deletes it. */
    fun delete(path: String) = start(path, delete = true)

    /** Takes a folder that is already gone off the list. */
    fun forget(path: String) {
        val left = move.moves().left.firstOrNull { it.path == path } ?: return
        if (Path.of(left.path).exists()) return
        move.forgetLeft(path)
        if (report?.path == path) report = null
    }

    @Synchronized
    private fun start(path: String, delete: Boolean) {
        val left = move.moves().left.firstOrNull { it.path == path } ?: return
        if (progress != null) return
        progress = Progress(path, delete, 0, 0)
        runner(
            Runnable {
                try {
                    report = run(left, delete)
                } catch (e: Exception) {
                    log.error("Checking the old media folder {} failed", path, e)
                    report = Report(path, 0, 0, 0, listOf("${e::class.simpleName}: ${e.message}"))
                } finally {
                    progress = null
                }
            },
        )
    }

    private fun run(left: MediaMove.Left, delete: Boolean): Report {
        val found = verify(left, delete)
        if (!delete || found.problems.isNotEmpty()) return found
        val folder = Path.of(left.path)
        deleteTree(folder)
        move.forgetLeft(left.path)
        log.info("Old media folder {} deleted: {} files, {} bytes", folder, found.files, found.bytes)
        return found.copy(deleted = true)
    }

    /** Every file of the old folder, each with a safe answer, or the problems that keep the folder. */
    private fun verify(left: MediaMove.Left, deleting: Boolean): Report {
        val old = Path.of(left.path)
        fun refused(why: String) = Report(left.path, 0, 0, 0, listOf(why))
        if (now() < deletableFrom(left)) return refused("неделя после переноса ещё не прошла")
        if (!old.isDirectory()) return refused("папки нет")
        if (readMark(old, MediaFolder.MARK) != db.storeId || markValue(old, MediaFolder.MARK, MediaFolder.MOVED_TO) == null) {
            return refused("в папке нет метки перенесённой папки этого хранилища: она не трогается")
        }
        val current = blobs.root
        val keep = listOfNotNull(current, dataDir, mirror())
        if (keep.any { it.startsWith(old) } || old.startsWith(current) || mirror()?.let { old.startsWith(it) } == true) {
            return refused("в этой папке сейчас медиафайлы, данные или второй диск")
        }
        val purged = purgedPaths()
        val files = files(old)
        val problems = ArrayList<String>()
        var bytes = 0L
        var gone = 0
        files.forEachIndexed { i, rel ->
            progress = Progress(left.path, deleting, i, files.size)
            val file = old.resolve(rel)
            val copy = current.resolve(rel)
            when {
                rel.startsWith("${MediaFolder.UPLOADS}/") -> Unit

                // a part of an upload the move carried on in the new folder
                copy.isRegularFile() -> if (BlobStore.sha256(copy) != BlobStore.sha256(file)) problems += "$rel: в новой папке другой файл"

                rel in purged -> gone++

                // deleted for good by the owner (ADR-041)
                else -> problems += "$rel: в новой папке его нет"
            }
            bytes += file.fileSize()
        }
        return Report(left.path, files.size, bytes, gone, problems)
    }

    /** Every place the purges' records name, at home or in the quarantine: what the owner deleted for good. */
    private fun purgedPaths(): Set<String> = db.locked { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT path, quarantine FROM purged_files").use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(rs.getString(1))
                        add(rs.getString(2))
                    }
                }
            }
        }
    }

    /** The old folder's files by their `/` path, its mark left out. */
    private fun files(root: Path): List<String> = Files.walk(root).use { paths ->
        paths.filter {
            Files.isRegularFile(it) && it.fileName.toString() != MediaFolder.MARK
        }.map { root.relativize(it).joinToString("/") }.toList()
    }

    /** Files first, then folders from the deepest, the mark and the folder itself last: a stop halfway keeps it marked. */
    private fun deleteTree(root: Path) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.fileName.toString() != MediaFolder.MARK || file.parent != root) Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    if (dir != root) Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        root.resolve(MediaFolder.MARK).deleteIfExists()
        Files.delete(root)
    }

    companion object {
        const val WAIT_MILLIS = 7L * 24 * 3_600_000

        private fun background(): (Runnable) -> Unit {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "benbow-left-folders").apply { isDaemon = true } }
            return executor::execute
        }
    }
}
