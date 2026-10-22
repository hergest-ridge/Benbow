package app.benbow.server.backup

import app.benbow.core.content.TagBook
import app.benbow.core.content.TagNames
import app.benbow.server.PURGE_JOURNAL
import app.benbow.server.ServerConfig
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.offsite.OffsiteCopy
import app.benbow.server.storage.Backups
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Purges
import app.benbow.server.storage.SqliteSyncStore
import app.benbow.server.storage.createUnder
import app.benbow.server.storage.writeMark
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
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
    /** The second disk (ADR-042): its folder, when the snapshots last got there, and why the last pass didn't. */
    val mirrorPath: String? = null,
    val mirrorDbAt: Long? = null,
    val mirrorProblem: String? = null,
    /** What doesn't stop a pass but should be seen (P2): little room left there, the same physical disk. */
    val mirrorWarnings: List<String> = emptyList(),
    /** Why the last pass of the copy outside the house failed (ADR-043); what it found is in [OffsiteCopy.last]. */
    val offsiteError: String? = null,
)

/** How far a pass is in copying media to the second disk (P3), for the admin page. */
data class MirrorProgress(val files: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long)

/**
 * Periodic safety jobs: a verified snapshot when anything changed, GFS pruning, a copy to the
 * second location, and the Markdown mirror of changed entries. Failures are logged and shown on
 * the admin page; they never stop the server. The calendar store gets the same snapshots in a step of its
 * own after the journal's: whatever happens there never stops or fails the journal's backup (ADR-027).
 */
class BackupJobs(
    private val db: Database,
    private val dataDir: Path,
    mirror: Path?,
    private val blobs: BlobStore? = null,
    private val calendar: CalendarDatabase? = null,
    /** Deleting for good (ADR-041): its quarantine ends in these passes, and the mirror follows by its journal. */
    private val purges: Purges? = null,
    /** Which disk each folder is on (ADR-042); tests put folders on disks of their own. */
    private val disks: Disks = Disks.System,
    /** The encrypted copy outside the house (ADR-043): its pass comes after the journal's and the calendar's. */
    val offsite: OffsiteCopy? = null,
) {
    private val log = LoggerFactory.getLogger("benbow.backup")
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "benbow-backup").apply { isDaemon = true } }
    private val exportStateFile = dataDir.resolve("export").resolve(".last-seq")

    /** The tag registry's names, merges and trash the mirror was last written with (ADR-032). */
    private val exportedTagsFile = dataDir.resolve("export").resolve(".tags.json")

    /** What the second disk must not share a disk with (ADR-042); the media folder as it is now (it may move, P4). */
    private fun apart() = listOfNotNull("база" to dataDir, blobs?.let { "медиафайлы" to it.root })

    /** The second disk now: its folder with its mark and last passes (ADR-042). Changed on the admin page, no restart. */
    private inner class Target(val path: Path) {
        val folder = MirrorFolder(path, db.storeId, dataDir.resolve("backups").resolve(MirrorFolder.PASSES), ::filledBefore, ::apart, disks)
    }

    @Volatile private var target: Target? = mirror?.let { Target(it) }

    /** Serializes choosing a folder and turning the mirror off, apart from the passes (which may take long). */
    private val choosing = Any()

    @Volatile
    var status = BackupStatus(
        db.backups?.history()?.lastOrNull()?.createdAt,
        target?.folder?.passes()?.lastAt,
        readExportSeq(),
        null,
        calendar?.backups?.history()?.lastOrNull()?.createdAt,
        mirrorPath = mirror?.toString(),
        mirrorDbAt = target?.folder?.passes()?.dbAt,
    )
        private set

    /** Copying media to the second disk right now, or null. */
    @Volatile
    var progress: MirrorProgress? = null
        private set

    /** The second disk's folder now, or null. */
    fun mirror(): Path? = target?.path

    fun start(intervalMinutes: Long) {
        executor.scheduleWithFixedDelay({ runSafely() }, 1, intervalMinutes, TimeUnit.MINUTES)
    }

    fun stop() {
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)
    }

    /** One pass now, on this thread; failures show up in [status] like scheduled runs. */
    fun runNow() = runSafely(reason = "manual")

    /** One pass in the background, after any running one (the admin page's button: a first copy takes a while). */
    fun runSoon(): Future<*> = executor.submit { runSafely(reason = "manual") }

    /**
     * Makes [path] the second disk (null: none) from now on, without a restart (ADR-042, P3). A pass under way finishes with
     * the folder it began with; the old folder is left as it is. With a folder, a pass starts in the background at once.
     */
    fun changeMirror(path: Path?): Future<*> {
        val next = path?.let { Target(it) }
        target = next
        val passes = next?.folder?.passes()
        updateStatus {
            it.copy(
                mirrorPath = path?.toString(),
                lastMirrorAt = passes?.lastAt,
                mirrorDbAt = passes?.dbAt,
                mirrorProblem = null,
                mirrorWarnings = emptyList(),
            )
        }
        return if (path != null) runSoon() else CompletableFuture.completedFuture(null)
    }

    /**
     * «Второй диск» on the admin page (ADR-042, P3): checks the folder ([MirrorChoice]), marks it as this store's, writes
     * `backup.mirror` in one atomic step and switches to it. Nothing is changed when the check fails.
     */
    fun chooseMirror(text: String): MirrorChoice.Verdict = synchronized(choosing) {
        val verdict = MirrorChoice(db.storeId, apart(), disks, ::firstPassBytes, ::filledBefore).check(text)
        if (verdict is MirrorChoice.Verdict.Fine) {
            writeMark(verdict.path, MirrorFolder.MARK, "Benbow backup mirror", db.storeId)
            ServerConfig.update(dataDir, mapOf("backup.mirror" to verdict.path.toString().replace('\\', '/')))
            log.info("Second disk: {} (was {})", verdict.path, mirror())
            changeMirror(verdict.path)
        }
        verdict
    }

    /** No second disk from now on; its folder stays as it is. */
    fun turnOffMirror() {
        synchronized(choosing) {
            ServerConfig.update(dataDir, mapOf("backup.mirror" to ""))
            log.info("Second disk turned off (was {})", mirror())
            changeMirror(null)
        }
    }

    private fun updateStatus(change: (BackupStatus) -> BackupStatus) = synchronized(this) { status = change(status) }

    @Synchronized // the button and the schedule never run the jobs at the same time
    private fun runSafely(reason: String = "scheduled") {
        try {
            runOnce(reason = reason)
        } catch (e: Exception) {
            log.error("Backup run failed", e)
            updateStatus { it.copy(lastError = "${e::class.simpleName}: ${e.message}") }
        }
        try {
            runCalendar(reason = reason)
        } catch (e: Exception) {
            log.error("Calendar backup failed", e)
            updateStatus { it.copy(calendarError = "${e::class.simpleName}: ${e.message}") }
        }
        // Whatever happens outside the house never stops or fails the copies at home.
        try {
            offsite?.pass()
        } catch (e: Exception) {
            log.error("Copy outside the house failed", e)
            updateStatus { it.copy(offsiteError = "${e::class.simpleName}: ${e.message}") }
            return
        }
        updateStatus { it.copy(offsiteError = null) }
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
        // Only into this store's folder on the second disk (ADR-042), the one there was when the pass began; its media
        // only while the media folder is there.
        val t = target
        val mirrorState = t?.folder?.check()
        val dest = t?.path?.takeIf { mirrorState == MirrorFolder.State.Ready }
        val copied = dest != null && copyToMirror(dest)
        val mirrored = dest != null && copied && copyMediaToMirror(dest)
        // Quarantines that ended (ADR-041), then the mirror's copies of those files: by the journal, the hash matching.
        purges?.expire()
        if (dest != null && mirrored) purges?.mirrorDeletions(dest)
        exportChanged()
        // The rest of the recovery kit, the export just written included (P2).
        if (t != null && dest != null && copied) {
            RecoveryKit(dataDir, dest, db.storeId).copy()
            t.folder.record(now, if (mirrored) now else null)
        }
        val warnings = if (dest != null && copied) mirrorWarnings(dest) else null
        val corrupt = checkMedia()
        updateStatus { s ->
            // The mirror changed while this pass ran: what it found belongs to the old folder.
            val same = target === t
            s.copy(
                lastSnapshotAt = backups.history().lastOrNull()?.createdAt,
                lastMirrorAt = if (same && mirrored) now else s.lastMirrorAt,
                mirrorDbAt = if (same && copied) now else s.mirrorDbAt,
                mirrorProblem = when {
                    !same -> s.mirrorProblem
                    t == null || mirrorState == null -> null
                    mirrorState != MirrorFolder.State.Ready -> t.folder.describe(mirrorState)
                    copied && !mirrored -> "снимки базы скопированы, медиафайлы нет: папка медиа недоступна"
                    else -> null
                },
                mirrorWarnings = if (same && warnings != null) warnings else s.mirrorWarnings,
                lastError = corrupt.takeIf {
                    it.isNotEmpty()
                }?.let { "Media files don't match their hash: ${it.joinToString { b -> b.path }}" },
            )
        }
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
        val t = target
        if (t != null && t.folder.check() == MirrorFolder.State.Ready) copySnapshots(backups, t.path, "calendar", "calendar/manifest.json")
        if (CalendarMirror(dataDir.resolve("export").resolve("calendars")).update(store)) log.info("Calendar mirror written")
        updateStatus { it.copy(calendarSnapshotAt = backups.history().lastOrNull()?.createdAt, calendarError = null) }
        return taken != null
    }

    /** Copies snapshots missing in the mirror (verified by SHA-256) and prunes ones the source pruned. */
    private fun copyToMirror(target: Path): Boolean {
        val backups = db.backups ?: return false
        copySnapshots(backups, target, "db", "manifest.json")
        // The purge journal goes with the snapshots: a store rebuilt from the mirror does its purges again (ADR-041).
        val journal = dataDir.resolve("db").resolve(PURGE_JOURNAL)
        if (journal.exists()) {
            val copy = target.resolve("db").resolve(PURGE_JOURNAL)
            val tmp = copy.resolveSibling("$PURGE_JOURNAL.tmp")
            Files.copy(journal, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return true
    }

    /** Verified copies of [backups]' snapshots in [target]'s [folder] (one of their own), then their manifest. */
    private fun copySnapshots(backups: Backups, target: Path, folder: String, manifest: String) {
        val dir = createUnder(target, folder)
        val manifestCopy = target.resolve(manifest)
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

    /**
     * Copies media files missing in the mirror (immutable, so presence + hash is enough). Never deletes there. False while
     * the media folder is gone (ADR-042).
     */
    private fun copyMediaToMirror(target: Path): Boolean {
        val store = blobs ?: return true
        if (!store.ready()) return false
        val missing = store.all().filter { blob ->
            target.resolve("media").resolve(blob.path).let {
                !it.exists() ||
                    it.fileSize() != blob.size
            }
        }
        val total = missing.sumOf { it.size }
        var bytes = 0L
        try {
            missing.forEachIndexed { done, blob ->
                progress = MirrorProgress(done, missing.size, bytes, total)
                val copy = target.resolve("media").resolve(blob.path)
                createUnder(target, "media/" + blob.path.substringBeforeLast('/', ""))
                val tmp = copy.resolveSibling("${copy.fileName}.tmp")
                Files.copy(store.file(blob), tmp, StandardCopyOption.REPLACE_EXISTING)
                check(BlobStore.sha256(tmp) == blob.sha256) { "Mirror copy of ${blob.path} is corrupt" }
                Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                bytes += blob.size
            }
        } finally {
            progress = null
        }
        return true
    }

    /** What doesn't stop a pass but should be seen (ADR-042): little room left, the same physical disk as the data or media. */
    private fun mirrorWarnings(target: Path): List<String> = buildList {
        disks.free(target)?.takeIf { it < LOW_SPACE }?.let { add("на втором диске свободно ${"%.1f".format(it / GB)} ГБ") }
        val disk = disks.physical(target)
        if (disk != null) {
            apart().firstOrNull { disks.physical(it.second) == disk }?.let {
                add("папка $target на другом разделе того же физического диска, что и ${it.first}: от поломки диска это не защищает")
            }
        }
    }

    /** Whether [dir] is a mirror this store filled before its folder had a mark: a snapshot or a media file of its own. */
    private fun filledBefore(dir: Path): Boolean {
        val snapshots = db.backups?.let { b -> b.history().filter { it.verified }.map { b.directory.resolve(it.file) } }.orEmpty()
        val snapshot = snapshots.any { s ->
            runCatching { MirrorFolder.holds(dir.resolve("db").resolve(s.fileName), s.fileSize()) }.getOrDefault(false)
        }
        return snapshot || blobs?.all()?.takeLast(50)?.any { MirrorFolder.holds(dir.resolve("media").resolve(it.path), it.size) } == true
    }

    /** What a first pass into an empty folder would copy: the media, the snapshots, the export. */
    private fun firstPassBytes(): Long {
        fun size(dir: Path): Long = if (!dir.isDirectory()) {
            0
        } else {
            Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { it.fileSize() }.sum() }
        }
        return (blobs?.stats()?.second ?: 0) + size(dataDir.resolve("backups")) + size(dataDir.resolve("export"))
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
     * Writes the Markdown mirror for entries changed since the last export. Notes list their tags as they read (ADR-032),
     * so when the registry renames, merges or trashes a tag every entry carrying it is written again.
     */
    private fun exportChanged() {
        val store = SqliteSyncStore(db)
        val since = readExportSeq()
        val changes = store.changesSince(since, Int.MAX_VALUE)
        if (changes.isEmpty()) return
        val fields = store.entry(TagNames.REGISTRY_ID)?.fields.orEmpty().mapValues { it.value.value }
        val registry = fields.filterKeys(TagNames::shapesTags)
        val tags = TagNames.of(registry)
        val renamed = TagNames.changed(TagNames.of(readExportedTags()), tags)
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
            mirror.write(entry, entry.head?.let { store.revision(it)?.body }, tags)
        }
        // The tags' own files follow the registry (and are made once for a mirror that has none yet).
        if (TagNames.REGISTRY_ID in ids || !dataDir.resolve("export").resolve(TagBook.JSON_FILE).exists()) {
            mirror.writeTags(fields, writtenTagNames(store))
        }
        val seq = changes.last().first
        exportStateFile.parent.createDirectories()
        if (renamed.isNotEmpty()) exportedTagsFile.writeText(Json.encodeToString(registrySerializer, registry))
        exportStateFile.writeText(seq.toString())
        updateStatus { it.copy(lastExportSeq = seq) }
    }

    /** Tag id to the name its entries hold most (tags from before ids have no name of their own). */
    private fun writtenTagNames(store: SqliteSyncStore): Map<String, String> {
        val counts = HashMap<String, MutableMap<String, Int>>()
        store.changesSince(0, Int.MAX_VALUE).map { it.second }.distinct().forEach { id ->
            store.entry(id)?.takeIf { it.kind != "system" }?.fields?.forEach { (key, value) ->
                val name = value.value ?: return@forEach
                if (key.startsWith(TagNames.TAG_PREFIX)) {
                    counts.getOrPut(key.removePrefix(TagNames.TAG_PREFIX)) {
                        HashMap()
                    }.merge(name, 1, Int::plus)
                }
            }
        }
        return counts.mapValues { (_, names) ->
            names.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
        }
    }

    private fun readExportedTags(): Map<String, String?> = if (exportedTagsFile.exists()) {
        runCatching { Json.decodeFromString(registrySerializer, exportedTagsFile.readText()) }.getOrDefault(emptyMap())
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

private val registrySerializer = MapSerializer(String.serializer(), String.serializer().nullable)

private const val STALE_UPLOAD_MS = 7L * 24 * 3_600_000
private const val SCRUB_AFTER_MS = 7L * 24 * 3_600_000
private const val SCRUB_BATCH = 200
private const val GB = 1_073_741_824.0
private const val LOW_SPACE = 5L * 1_073_741_824
