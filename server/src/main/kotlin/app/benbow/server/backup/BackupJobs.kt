package app.benbow.server.backup

import app.benbow.core.content.TagNames
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.storage.Backups
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.SqliteSyncStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class BackupStatus(
    val lastSnapshotAt: Long?,
    val lastMirrorAt: Long?,
    val lastExportSeq: Long,
    val lastError: String?,
    /** The calendar's own snapshots (ADR-027); its failures are reported here, apart from the journal's. */
    val calendarSnapshotAt: Long? = null,
    val calendarError: String? = null,
)

/**
 * Periodic safety jobs: a verified snapshot when anything changed, GFS pruning, a copy to the
 * second location, and the Markdown mirror of changed entries. Failures are logged and shown on
 * the admin page; they never stop the server. The calendar store gets the same snapshots in a step of its
 * own after the journal's: whatever happens there never stops or fails the journal's backup (ADR-027).
 */
class BackupJobs(
    private val db: Database,
    private val dataDir: Path,
    private val mirror: Path?,
    private val blobs: BlobStore? = null,
    private val calendar: CalendarDatabase? = null,
) {
    private val log = LoggerFactory.getLogger("benbow.backup")
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "benbow-backup").apply { isDaemon = true } }
    private val exportStateFile = dataDir.resolve("export").resolve(".last-seq")

    /** The registry's tag names the mirror was last written with (ADR-032). */
    private val exportedNamesFile = dataDir.resolve("export").resolve(".tag-names.json")

    @Volatile
    var status = BackupStatus(
        db.backups?.history()?.lastOrNull()?.createdAt,
        null,
        readExportSeq(),
        null,
        calendar?.backups?.history()?.lastOrNull()?.createdAt,
    )
        private set

    fun start(intervalMinutes: Long) {
        executor.scheduleWithFixedDelay({ runSafely() }, 1, intervalMinutes, TimeUnit.MINUTES)
    }

    fun stop() {
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)
    }

    /** One pass now (admin page button); failures show up in [status] like scheduled runs. */
    fun runNow() = runSafely(reason = "manual")

    @Synchronized // the button and the schedule never run the jobs at the same time
    private fun runSafely(reason: String = "scheduled") {
        try {
            runOnce(reason = reason)
        } catch (e: Exception) {
            log.error("Backup run failed", e)
            status = status.copy(lastError = "${e::class.simpleName}: ${e.message}")
        }
        try {
            runCalendar(reason = reason)
        } catch (e: Exception) {
            log.error("Calendar backup failed", e)
            status = status.copy(calendarError = "${e::class.simpleName}: ${e.message}")
        }
    }

    /** One pass of all jobs; returns true when a new snapshot was taken. */
    fun runOnce(now: Long = System.currentTimeMillis(), reason: String = "scheduled"): Boolean {
        val backups = db.backups ?: return false
        val taken = db.locked { c ->
            val seq = c.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM change_log").use {
                    it.next()
                    it.getLong(1)
                }
            }
            val last = backups.history().lastOrNull { it.verified }
            if (last != null && last.seq >= seq) {
                null
            } else {
                backups.snapshot(c, reason, seq)
            }
        }
        if (taken != null) log.info("Snapshot {} verified ({} rows)", taken.file, taken.rows.values.sum())
        val removed = backups.prune(Retention.keep(backups.history(), now))
        if (removed.isNotEmpty()) log.info("Pruned {} old snapshots", removed.size)
        val mirrored = copyToMirror() && copyMediaToMirror()
        exportChanged()
        val corrupt = checkMedia()
        status = status.copy(
            lastSnapshotAt = backups.history().lastOrNull()?.createdAt,
            lastMirrorAt = if (mirrored) now else status.lastMirrorAt,
            lastError = corrupt.takeIf {
                it.isNotEmpty()
            }?.let { "Media files don't match their hash: ${it.joinToString { b -> b.path }}" },
        )
        return taken != null
    }

    /**
     * The calendar's pass: a verified snapshot when it changed, pruning, its copy to `<mirror>/calendar`, and the
     * `.ics` files in `export/calendars` (ADR-028).
     */
    fun runCalendar(now: Long = System.currentTimeMillis(), reason: String = "scheduled"): Boolean {
        val store = calendar ?: return false
        val backups = store.backups ?: return false
        val taken = store.locked { c ->
            val seq = store.latestCursor()
            val last = backups.history().lastOrNull { it.verified }
            if (last != null && last.seq >= seq) null else backups.snapshot(c, reason, seq)
        }
        if (taken != null) log.info("Calendar snapshot {} verified ({} rows)", taken.file, taken.rows.values.sum())
        backups.prune(Retention.keep(backups.history(), now))
        mirror?.let { copySnapshots(backups, it.resolve("calendar"), it.resolve("calendar").resolve("manifest.json")) }
        if (CalendarMirror(dataDir.resolve("export").resolve("calendars")).update(store)) log.info("Calendar mirror written")
        status = status.copy(calendarSnapshotAt = backups.history().lastOrNull()?.createdAt, calendarError = null)
        return taken != null
    }

    /** Copies snapshots missing in the mirror (verified by SHA-256) and prunes ones the source pruned. */
    private fun copyToMirror(): Boolean {
        val target = mirror ?: return false
        val backups = db.backups ?: return false
        copySnapshots(backups, target.resolve("db"), target.resolve("manifest.json"))
        return true
    }

    /** Verified copies of [backups]' snapshots in [dir] (a folder of their own), then their manifest. */
    private fun copySnapshots(backups: Backups, dir: Path, manifestCopy: Path) {
        if (!dir.isAbsolute) {
            error("backup.mirror must be an absolute path (use / in benbow.properties), got '$mirror'")
        }
        dir.createDirectories()
        val wanted = backups.history().filter { it.verified }.map { it.file }.toSet()
        for (name in wanted) {
            val source = backups.directory.resolve(name)
            val copy = dir.resolve(name)
            if (copy.exists() && copy.fileSize() == source.fileSize()) continue
            val tmp = dir.resolve("$name.tmp")
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
            check(sha256(tmp) == sha256(source)) { "Mirror copy of $name is corrupt" }
            Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        dir.listDirectoryEntries("*.sqlite").filter { it.name !in wanted }.forEach(Files::delete)
        Files.copy(backups.manifest, manifestCopy, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Copies media files missing in the mirror (immutable, so presence + hash is enough). Never deletes there. */
    private fun copyMediaToMirror(): Boolean {
        val store = blobs ?: return true
        val target = mirror ?: return false
        for (blob in store.all()) {
            val copy = target.resolve("media").resolve(blob.path)
            if (copy.exists() && copy.fileSize() == blob.size) continue
            copy.parent.createDirectories()
            val tmp = copy.resolveSibling("${copy.fileName}.tmp")
            Files.copy(store.file(blob), tmp, StandardCopyOption.REPLACE_EXISTING)
            check(BlobStore.sha256(tmp) == blob.sha256) { "Mirror copy of ${blob.path} is corrupt" }
            Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return true
    }

    /** Drops abandoned partial uploads and re-hashes a slice of the media files (data-safety S7 scrub). */
    private fun checkMedia(): List<BlobStore.Blob> {
        val store = blobs ?: return emptyList()
        val dropped = store.dropStaleUploads(STALE_UPLOAD_MS)
        if (dropped > 0) log.info("Dropped {} abandoned partial uploads", dropped)
        return store.scrub(SCRUB_AFTER_MS, SCRUB_BATCH).onEach {
            log.error("Media file {} doesn't match its SHA-256 {}", it.path, it.sha256)
        }
    }

    /**
     * Writes the Markdown mirror for entries changed since the last export. Notes list their tags by name (ADR-032), so
     * when the registry renames a tag every entry carrying it is written again.
     */
    private fun exportChanged() {
        val store = SqliteSyncStore(db)
        val since = readExportSeq()
        val changes = store.changesSince(since, Int.MAX_VALUE)
        if (changes.isEmpty()) return
        val names = store.entry(TagNames.REGISTRY_ID)?.let { registry ->
            TagNames.of(registry.fields.mapValues { it.value.value })
        }.orEmpty()
        val exported = readExportedNames()
        val renamed = (names.keys + exported.keys).filterTo(HashSet()) { names[it] != exported[it] }
        val ids = changes.map { it.second }.toMutableSet()
        if (renamed.isNotEmpty()) {
            ids += store.changesSince(0, Int.MAX_VALUE).map { it.second }.distinct().filter { id ->
                store.entry(id)?.fields?.keys.orEmpty().any {
                    it.startsWith(TagNames.TAG_PREFIX) &&
                        it.removePrefix(TagNames.TAG_PREFIX) in renamed
                }
            }
        }
        val mirror = MarkdownMirror(dataDir.resolve("export")) { sha -> blobs?.blob(sha)?.let(blobs::file) }
        ids.forEach { id ->
            val entry = store.entry(id) ?: return@forEach
            mirror.write(entry, entry.head?.let { store.revision(it)?.body }, names)
        }
        val seq = changes.last().first
        exportStateFile.parent.createDirectories()
        if (renamed.isNotEmpty()) exportedNamesFile.writeText(Json.encodeToString(namesSerializer, names))
        exportStateFile.writeText(seq.toString())
        status = status.copy(lastExportSeq = seq)
    }

    private fun readExportedNames(): Map<String, String> = if (exportedNamesFile.exists()) {
        runCatching { Json.decodeFromString(namesSerializer, exportedNamesFile.readText()) }.getOrDefault(emptyMap())
    } else {
        emptyMap()
    }

    private fun readExportSeq(): Long = if (exportStateFile.exists()) exportStateFile.readText().trim().toLongOrNull() ?: 0 else 0

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private val namesSerializer = MapSerializer(String.serializer(), String.serializer())

private const val STALE_UPLOAD_MS = 7L * 24 * 3_600_000
private const val SCRUB_AFTER_MS = 7L * 24 * 3_600_000
private const val SCRUB_BATCH = 200
