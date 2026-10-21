package app.benbow.server.storage

import app.benbow.server.ServerConfig
import app.benbow.server.backup.Disks
import app.benbow.server.backup.probeWrite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Moving the media folder to another place (ADR-042, as built 7.19 P4), never by moving files. Every file is copied while
 * the server runs, through a temporary file synced to disk and checked by SHA-256. Then, with all file work held off for a
 * moment ([BlobStore.switching]), what changed meanwhile is caught up (new uploads, files gone to the purge quarantine,
 * uploads in progress) and what left is removed from the copy; the copy is checked against the old folder and the database,
 * marked as this store's, and `media.root` is written in one atomic step. Only then does the server use it. A crash at any
 * point leaves the settings naming the old folder or the new one, each complete. The old folder is left as it was, its
 * mark saying where it went, so it is never taken for the media folder again; deleting it is the owner's, later (P6).
 * The state is `db/media-move.json`: a move that was copying goes on after a restart, one that failed waits for the owner.
 */
class MediaMove(
    private val db: Database,
    private val blobs: BlobStore,
    private val dataDir: Path,
    private val disks: Disks = Disks.System,
    /** The second disk now: the media must not move onto it. */
    private val mirror: () -> Path? = { null },
    /** Where the copying runs; tests run it in place. */
    private val runner: (Runnable) -> Unit = background(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Called at each step of a move ("file", "copied", "checked", "marked", "configured"): drills stop a move there. */
    private val drill: (String) -> Unit = {},
) {
    /** The move under way (copying, or stopped by an error), and the folders earlier moves left behind. */
    @Serializable
    data class Moves(val current: Move? = null, val left: List<Left> = emptyList())

    @Serializable
    data class Move(val from: String, val to: String, val startedAt: Long, val error: String? = null)

    @Serializable
    data class Left(val path: String, val movedTo: String, val at: Long)

    data class Progress(val files: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long)

    sealed interface Verdict {
        /** The folder fits: [files] files of [bytes] are still to copy there, where [free] bytes are free. */
        data class Fine(val path: Path, val files: Int, val bytes: Long, val free: Long, val warnings: List<String>) : Verdict

        data class Refused(val reason: String) : Verdict
    }

    private val log = LoggerFactory.getLogger("benbow.media")
    private val stateFile = dataDir.resolve("db").resolve(STATE)
    private val json = Json { prettyPrint = true }

    /** Between deciding to switch and having switched, and cancelling: never both. */
    private val steps = Any()

    @Volatile private var cancelled = false

    /** The copy under way, for the admin page. */
    @Volatile
    var progress: Progress? = null
        private set

    fun moves(): Moves = runCatching { json.decodeFromString(Moves.serializer(), stateFile.readText()) }.getOrNull() ?: Moves()

    /** What a move to [text] would copy, or why not; nothing is made or started (the admin page asks before [start]). */
    fun preview(text: String): Verdict = synchronized(steps) {
        if (moves().current != null) Verdict.Refused("перенос уже идёт") else check(text, probe = false)
    }

    /** Checks [text] as the new media folder and, when it fits, starts copying there. */
    fun start(text: String): Verdict {
        var started: Move? = null
        val verdict = synchronized(steps) {
            if (moves().current != null) return Verdict.Refused("перенос уже идёт")
            check(text, probe = true).also { verdict ->
                if (verdict is Verdict.Fine) {
                    writeMark(verdict.path, MOVING, "Benbow media folder, being filled by a move", db.storeId)
                    val move = Move(blobs.root.toString(), verdict.path.toString(), now())
                    save(moves().copy(current = move))
                    log.info("Moving the media from {} to {}", move.from, move.to)
                    cancelled = false
                    started = move
                }
            }
        }
        started?.let { move -> runner(Runnable { run(move) }) }
        return verdict
    }

    /** After a restart: a move that was copying goes on; one whose settings already name the new folder is finished. */
    fun resume() {
        val move = moves().current ?: return
        if (move.error != null) return // waits for the owner: try again or cancel
        if (ServerConfig.load(dataDir).mediaRoot?.let { same(it, Path.of(move.to)) } == true) {
            synchronized(steps) { switched(move) }
            return
        }
        if (!same(blobs.root, Path.of(move.from))) {
            save(moves().copy(current = move.copy(error = "папка медиа в настройках уже другая, не та, откуда начался перенос")))
            return
        }
        runner(Runnable { run(move) })
    }

    /** A move stopped by an error, again from where it stopped. */
    fun retry() {
        val again = synchronized(steps) {
            val move = moves().current?.takeIf { it.error != null } ?: return
            move.copy(error = null).also {
                save(moves().copy(current = it))
                cancelled = false
            }
        }
        runner(Runnable { run(again) })
    }

    /** Stops a move before its switch. What was copied stays in its folder; nothing is deleted. */
    fun cancel() = synchronized(steps) {
        val move = moves().current ?: return
        cancelled = true
        save(moves().copy(current = null))
        log.info("Media move to {} cancelled; the copy stays there", move.to)
    }

    private fun run(move: Move) {
        try {
            if (!copyAll(move)) return
            drill("copied")
            finish(move)
        } catch (e: Exception) {
            log.error("Media move to {} stopped", move.to, e)
            synchronized(steps) {
                if (moves().current == move) save(moves().copy(current = move.copy(error = "${e::class.simpleName}: ${e.message}")))
            }
        } finally {
            progress = null
        }
    }

    /** Every file of the old folder into the new one, outside the lock; false when cancelled. */
    private fun copyAll(move: Move): Boolean {
        val from = Path.of(move.from)
        val to = Path.of(move.to)
        val known = blobs.all().associate { it.path to it.sha256 }
        val todo = tree(from, uploads = false).filter { (rel, size) -> !holds(to.resolve(rel), size) }
        val total = todo.values.sum()
        var bytes = 0L
        var files = 0
        for ((rel, size) in todo) {
            if (cancelled) return false
            progress = Progress(files, todo.size, bytes, total)
            val source = from.resolve(rel)
            if (!source.isRegularFile()) continue // moved or deleted meanwhile: the switch catches up
            val sha = copyDurably(source, to, rel)
            known[rel]?.takeIf { it != sha }?.let { log.warn("Media file {} doesn't match its SHA-256 {}; copied as it is", rel, it) }
            files++
            bytes += size
            drill("file")
        }
        return !cancelled
    }

    /** The switch, with all file work held off: catch up, check, mark, settings. */
    private fun finish(move: Move) {
        val from = Path.of(move.from)
        val to = Path.of(move.to)
        blobs.switching(to) {
            synchronized(steps) {
                if (cancelled || moves().current != move) return@switching false
                val sources = tree(from, uploads = true)
                for ((rel, size) in sources) {
                    if (rel.startsWith("${MediaFolder.UPLOADS}/") || !holds(to.resolve(rel), size)) copyDurably(from.resolve(rel), to, rel)
                }
                // What left the old folder meanwhile (a file into the quarantine, its end, a dropped upload) leaves the copy:
                // a file staying at its old place would come back as one the database doesn't know.
                for ((rel, _) in tree(to, uploads = true, temporary = true)) if (rel !in sources) to.resolve(rel).deleteIfExists()
                val absent = sources.filter { (rel, size) -> !holds(to.resolve(rel), size) }.keys +
                    blobs.all().filter { holds(from.resolve(it.path), it.size) && !holds(to.resolve(it.path), it.size) }.map { it.path }
                check(absent.isEmpty()) { "в новой папке не хватает ${absent.size} файлов: ${absent.take(3).joinToString()}" }
                drill("checked")
                writeMark(to, MediaFolder.MARK, "Benbow media folder", db.storeId)
                drill("marked")
                ServerConfig.update(dataDir, mapOf("media.root" to to.toString().replace('\\', '/')))
                drill("configured")
                switched(move)
                log.info("Media moved to {}; {} is left as it was", to, from)
                true
            }
        }
    }

    /** After the settings name the new folder: the old one marked as moved, the state, the export written again. */
    private fun switched(move: Move) {
        val from = Path.of(move.from)
        if (readMark(from, MediaFolder.MARK) == db.storeId) {
            writeMark(from, MediaFolder.MARK, "Benbow media folder, moved", db.storeId, mapOf(MediaFolder.MOVED_TO to move.to))
        }
        Path.of(move.to).resolve(MOVING).deleteIfExists()
        save(Moves(current = null, left = moves().left + Left(move.from, move.to, now())))
        // The Markdown mirror links files by their place: every note is written again at the next backup pass.
        dataDir.resolve("export").resolve(".last-seq").deleteIfExists()
    }

    /** Whether [text] may become the media folder; a folder that doesn't exist is made (one level) for the write probe. */
    private fun check(text: String, probe: Boolean): Verdict {
        if (!blobs.ready()) return Verdict.Refused("папка медиа сейчас недоступна: ${blobs.folder.describe()}")
        val typed = text.trim().trim('"').trim()
        if (typed.isEmpty()) return Verdict.Refused("впишите путь к папке")
        if (typed.startsWith("\\\\") || typed.startsWith("//")) return Verdict.Refused("сетевые папки не подходят")
        val path = runCatching { Path.of(typed) }.getOrNull()?.takeIf { it.isAbsolute }?.normalize()
            ?: return Verdict.Refused("нужен полный путь, например D:\\Benbow-media")
        val parent = path.parent ?: return Verdict.Refused("укажите папку, а не весь диск: например ${path}Benbow-media")
        if (path.exists() && !path.isDirectory()) return Verdict.Refused("$path — это файл, а не папка")
        if (!path.exists() && !parent.isDirectory()) return Verdict.Refused("папки $parent нет: диск не подключён?")
        if (disks.streaming(path)) return Verdict.Refused("это потоковый диск Google Диска: файлы на нём живут в облаке")
        val fileSystem = disks.fileSystem(path).orEmpty()
        if (fileSystem.uppercase() !in FILE_SYSTEMS) {
            return Verdict.Refused("файловая система ${fileSystem.ifEmpty { "неизвестна" }} не подходит: нужна NTFS или ReFS")
        }
        val others = listOfNotNull("нынешняя папка медиа" to blobs.root, "папка данных" to dataDir, mirror()?.let { "второй диск" to it })
        others.firstOrNull { (_, other) -> path.startsWith(other) || other.startsWith(path) }?.let {
            return Verdict.Refused("новая папка не может быть внутри места, где ${it.first}, или содержать его")
        }
        mirror()?.let { copy ->
            val volume = disks.volume(path)
            if (volume != null && volume == disks.volume(copy)) {
                return Verdict.Refused("там второй диск: медиафайлы и их копия оказались бы на одном диске")
            }
        }
        val ours = readMark(path, MOVING) == db.storeId
        if (!ours && path.exists() && path.listDirectoryEntries().isNotEmpty()) {
            return Verdict.Refused("в папке уже что-то лежит: выберите пустую или впишите новую, например ${path.resolve("Benbow-media")}")
        }
        val todo = tree(blobs.root, uploads = true).filter { (rel, size) -> !holds(path.resolve(rel), size) }
        val need = todo.values.sum()
        val free = disks.free(path) ?: 0
        if (free < need + RESERVE) return Verdict.Refused("не хватит места: нужно около ${gb(need + RESERVE)} ГБ, свободно ${gb(free)} ГБ")
        if (probe) probeWrite(path)?.let { return Verdict.Refused(it) }
        val warnings = buildList {
            val disk = disks.physical(path)
            val copy = mirror()
            if (disk != null && copy != null && disks.physical(copy) == disk) {
                add("это другой раздел того же физического диска, где второй диск: от поломки диска это не защищает")
            }
        }
        return Verdict.Fine(path, todo.size, need, free, warnings)
    }

    /**
     * [source] to [rel] under [to] through a temporary file synced to disk and read back for its SHA-256; returns the hash.
     * Folders are made one level at a time ([createUnder]), so a new folder that vanished fails here.
     */
    private fun copyDurably(source: Path, to: Path, rel: String): String {
        val dir = createUnder(to, rel.substringBeforeLast('/', ""))
        val target = dir.resolve(rel.substringAfterLast('/'))
        val tmp = dir.resolve("${target.fileName}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            Files.newInputStream(source).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    val chunk = ByteBuffer.wrap(buffer, 0, n)
                    while (chunk.hasRemaining()) out.write(chunk)
                }
            }
            out.force(true)
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (BlobStore.sha256(tmp) != sha) {
            tmp.deleteIfExists()
            throw IOException("the copy of $rel doesn't read back as written")
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return sha
    }

    /** Files under [root] by their `/` path and size: no marks, no write probes; uploads and leftovers only when asked. */
    private fun tree(root: Path, uploads: Boolean, temporary: Boolean = false): Map<String, Long> {
        val files = LinkedHashMap<String, Long>()
        if (!root.isDirectory()) return files
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (!uploads && dir == root.resolve(MediaFolder.UPLOADS)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    val skip = name == MediaFolder.MARK || name == MOVING || name.startsWith(".benbow-write-test-") ||
                        (!temporary && name.endsWith(".tmp"))
                    if (attrs.isRegularFile && !skip) files[root.relativize(file).joinToString("/")] = attrs.size()
                    return FileVisitResult.CONTINUE
                }

                // A file moved or deleted while walking: whatever changed is caught up under the lock.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return files
    }

    private fun holds(file: Path, size: Long) = file.isRegularFile() && file.fileSize() == size

    private fun same(a: Path, b: Path) = a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()

    private fun save(moves: Moves) {
        stateFile.parent.createDirectories()
        val tmp = stateFile.resolveSibling("$STATE.tmp")
        tmp.writeText(json.encodeToString(Moves.serializer(), moves))
        Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)

    companion object {
        const val STATE = "media-move.json"

        /** The mark of a folder a move is filling: its own to resume, never a media folder yet. */
        const val MOVING = ".benbow-moving"
        private val FILE_SYSTEMS = setOf("NTFS", "REFS")
        private const val RESERVE = 1_073_741_824L

        private fun background(): (Runnable) -> Unit {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "benbow-media-move").apply { isDaemon = true } }
            return executor::execute
        }
    }
}
