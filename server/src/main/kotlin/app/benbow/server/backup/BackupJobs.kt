package app.benbow.server.backup

import app.benbow.core.content.TagBook
import app.benbow.core.content.TagNames
import app.benbow.server.PURGE_JOURNAL
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.storage.Backups
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Purges
import app.benbow.server.storage.SqliteSyncStore
import app.benbow.server.storage.createUnder
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
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
    /** The second disk (ADR-042): its folder, when the snapshots last got there, and why the last pass didn't. */
    val mirrorPath: String? = null,
    val mirrorDbAt: Long? = null,
    val mirrorProblem: String? = null,
    /** What doesn't stop a pass but should be seen (P2): little room left there, the same physical disk. */
    val mirrorWarnings: List<String> = emptyList(),
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
    /** Deleting for good (ADR-041): its quarantine ends in these passes, and the mirror follows by its journal. */
    private val purges: Purges? = null,
    /** Which disk each folder is on (ADR-042); tests put folders on disks of their own. */
    private val disks: Disks = Disks.System,
) {
    private val log = LoggerFactory.getLogger("benbow.backup")
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "benbow-backup").apply { isDaemon = true } }
    private val exportStateFile = dataDir.resolve("export").resolve(".last-seq")

    /** The tag registry's names, merges and trash the mirror was last written with (ADR-032). */
    private val exportedTagsFile = dataDir.resolve("export").resolve(".tags.json")

    /** What the second disk must not share a disk with (ADR-042). */
    private val apart = listOfNotNull("база" to dataDir, blobs?.let { "медиафайлы" to it.root })

    /** The second disk's folder with its mark and its last passes (ADR-042). */
    private val mirrorFolder = mirror?.let {
        MirrorFolder(it, db.storeId, dataDir.resolve("backups").resolve(MirrorFolder.PASSES), ::filledBefore, apart, disks)
    }

    @Volatile
    var status = BackupStatus(
        db.backups?.history()?.lastOrNull()?.createdAt,
        mirrorFolder?.passes()?.lastAt,
        readExportSeq(),
        null,
        calendar?.backups?.history()?.lastOrNull()?.createdAt,
        mirrorPath = mirror?.toString(),
        mirrorDbAt = mirrorFolder?.passes()?.dbAt,
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
        // Only into this store's folder on the second disk (ADR-042); its media only while the media folder is there.
        val mirrorState = mirrorFolder?.check()
        val copied = mirrorState == MirrorFolder.State.Ready && copyToMirror()
        val mirrored = copied && copyMediaToMirror()
        // Quarantines that ended (ADR-041), then the mirror's copies of those files: by the journal, the hash matching.
        purges?.expire()
        if (mirrored) mirror?.let { purges?.mirrorDeletions(it) }
        exportChanged()
        // The rest of the recovery kit, the export just written included (P2).
        if (copied) mirror?.let { RecoveryKit(dataDir, it, db.storeId).copy() }
        if (copied) mirrorFolder?.record(now, if (mirrored) now else null)
        val corrupt = checkMedia()
        status = status.copy(
            lastSnapshotAt = backups.history().lastOrNull()?.createdAt,
            lastMirrorAt = if (mirrored) now else status.lastMirrorAt,
            mirrorDbAt = if (copied) now else status.mirrorDbAt,
            mirrorProblem = when {
                mirrorFolder == null || mirrorState == null -> null
                mirrorState != MirrorFolder.State.Ready -> mirrorFolder.describe(mirrorState)
                copied && !mirrored -> "снимки базы скопированы, медиафайлы нет: папка медиа недоступна"
                else -> null
            },
            mirrorWarnings = if (copied) mirrorWarnings() else status.mirrorWarnings,
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
        if (mirrorFolder?.check() == MirrorFolder.State.Ready) copySnapshots(backups, "calendar", "calendar/manifest.json")
        if (CalendarMirror(dataDir.resolve("export").resolve("calendars")).update(store)) log.info("Calendar mirror written")
        status = status.copy(calendarSnapshotAt = backups.history().lastOrNull()?.createdAt, calendarError = null)
        return taken != null
    }

    /** Copies snapshots missing in the mirror (verified by SHA-256) and prunes ones the source pruned. */
    private fun copyToMirror(): Boolean {
        val target = mirror ?: return false
        val backups = db.backups ?: return false
        copySnapshots(backups, "db", "manifest.json")
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

    /** Verified copies of [backups]' snapshots in the mirror's [folder] (one of their own), then their manifest. */
    private fun copySnapshots(backups: Backups, folder: String, manifest: String) {
        val target = mirror ?: return
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
    private fun copyMediaToMirror(): Boolean {
        val store = blobs ?: return true
        val target = mirror ?: return false
        if (!store.ready()) return false
        for (blob in store.all()) {
            val copy = target.resolve("media").resolve(blob.path)
            if (copy.exists() && copy.fileSize() == blob.size) continue
            createUnder(target, "media/" + blob.path.substringBeforeLast('/', ""))
            val tmp = copy.resolveSibling("${copy.fileName}.tmp")
            Files.copy(store.file(blob), tmp, StandardCopyOption.REPLACE_EXISTING)
            check(BlobStore.sha256(tmp) == blob.sha256) { "Mirror copy of ${blob.path} is corrupt" }
            Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return true
    }

    /** What doesn't stop a pass but should be seen (ADR-042): little room left, the same physical disk as the data or media. */
    private fun mirrorWarnings(): List<String> {
        val target = mirror ?: return emptyList()
        return buildList {
            disks.free(target)?.takeIf { it < LOW_SPACE }?.let { add("на втором диске свободно ${"%.1f".format(it / GB)} ГБ") }
            val disk = disks.physical(target)
            if (disk != null) {
                apart.firstOrNull { disks.physical(it.second) == disk }?.let {
                    add("папка $target на другом разделе того же физического диска, что и ${it.first}: от поломки диска это не защищает")
                }
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
        status = status.copy(lastExportSeq = seq)
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
