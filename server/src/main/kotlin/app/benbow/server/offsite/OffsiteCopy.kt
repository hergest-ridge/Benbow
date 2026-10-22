package app.benbow.server.offsite

import app.benbow.server.PURGE_JOURNAL
import app.benbow.server.ServerConfig
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.storage.Backups
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Purges
import app.benbow.server.storage.createUnder
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** How far a pass is in encrypting media into the copy outside the house, for the admin page. */
data class OffsiteProgress(val files: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long)

/**
 * The copy outside the house (ADR-043, as built 7.7 E2): what the second disk gets, each file age-encrypted to the
 * store's key (data-safety "Off-site copy"). Snapshots and the calendar's keep their names (`db/<name>.age`); the purge
 * journal goes with them; media go under keyed names (`media/<2>/<name>.age`), never deleted but after a purge's
 * quarantine; the export and the settings go as one ZIP each, written again only when they change. Every file passes
 * through a temporary name and a decrypted check ([AgeFiles]), so a cut pass is simply done again.
 */
class OffsiteCopy(
    private val db: Database,
    private val dataDir: Path,
    @Volatile var root: Path?,
    private val blobs: BlobStore? = null,
    private val calendar: CalendarDatabase? = null,
    private val purges: Purges? = null,
) {
    private val log = LoggerFactory.getLogger("benbow.offsite")
    val keyFile: Path = dataDir.resolve("keys").resolve(KEY_FILE)

    /** Encrypting media right now, or null. */
    @Volatile
    var progress: OffsiteProgress? = null
        private set

    /** What a pass found, for the admin page. */
    sealed interface Outcome {
        data object Off : Outcome

        data object NoKey : Outcome

        data class Skipped(val why: String) : Outcome

        /** [media] false while the media folder is gone: the snapshots went, the media wait. [check]: when one ran. */
        data class Done(val snapshots: Int, val files: Int, val media: Boolean, val check: OffsiteCheck.Result? = null) : Outcome
    }

    @Volatile
    var last: Outcome = Outcome.Off
        private set

    fun key(): OffsiteKey? = OffsiteKey.load(keyFile)

    private val passphraseFile = dataDir.resolve("backups").resolve("offsite-passphrase.txt")

    /** When the passphrase was last set or typed right on the admin page (E4); a file of its own, apart from the passes. */
    fun passphraseAt(): Long? = runCatching { passphraseFile.readText().trim().toLong() }.getOrNull()

    fun notePassphrase(at: Long) {
        passphraseFile.parent.createDirectories()
        val tmp = passphraseFile.resolveSibling("${passphraseFile.fileName}.tmp")
        tmp.writeText(at.toString())
        Files.move(tmp, passphraseFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    internal fun folder(key: OffsiteKey): OffsiteFolder? = root?.let {
        OffsiteFolder(it, db.storeId, key.recipient, dataDir.resolve("backups").resolve(OffsiteFolder.PASSES))
    }

    /** One pass; runs after the backup passes, on their thread. */
    @Synchronized
    fun pass(now: Long = System.currentTimeMillis()): Outcome {
        val outcome = passNow(now)
        last = outcome
        return outcome
    }

    private fun passNow(now: Long): Outcome {
        if (root == null) return Outcome.Off
        val key = key() ?: return Outcome.NoKey
        val folder = folder(key) ?: return Outcome.Off
        val state = folder.check()
        if (state != OffsiteFolder.State.Ready) return Outcome.Skipped(folder.describe(state))
        val target = folder.root
        dropParts(target)
        var passes = folder.passes()
        val written = passes.written.toMutableMap()

        var snapshots = 0
        db.backups?.let { snapshots += snapshots(it, target, "db", key) }
        calendar?.backups?.let { snapshots += snapshots(it, target, "calendar", key) }
        dataDir.resolve("db").resolve(PURGE_JOURNAL).takeIf { it.exists() }?.let {
            whenChanged(written, "db/$PURGE_JOURNAL", sha(it)) {
                AgeFiles.encrypt(it, createUnder(target, "db").resolve("$PURGE_JOURNAL.age"), key)
            }
        }
        passes = passes.copy(dbAt = now, written = written.toMap())
        folder.record(passes)

        val media = media(target, key)
        val export = dataDir.resolve("export")
        val exported = if (export.isDirectory()) {
            Files.walk(export).use { paths -> paths.filter { Files.isRegularFile(it) && !it.name.startsWith(".") }.sorted().toList() }
        } else {
            emptyList()
        }
        archive(export, exported, written, "export.zip", target, key)
        archive(dataDir, CONFIG.map(dataDir::resolve).filter { it.isRegularFile() }, written, "config.zip", target, key)
        passes = passes.copy(lastAt = if (media.done) now else passes.lastAt, written = written.toMap())
        if (snapshots + media.files > 0) log.info("Copy outside the house: {} snapshots, {} media files", snapshots, media.files)

        // The check (E3): after new snapshots went there, and once a day anyway; until one finds everything, every pass.
        val check = if (snapshots > 0 || passes.checkedAt == null || now - passes.checkedAt >= CHECK_EVERY) {
            val folders = listOfNotNull(db.backups?.let { "db" to it }, calendar?.backups?.let { "calendar" to it }).toMap()
            val scratch = dataDir.resolve("backups").resolve("offsite-check.sqlite")
            OffsiteCheck(target, key, scratch).run(folders, written, blobs?.takeIf { it.ready() }?.all().orEmpty())
        } else {
            null
        }
        if (check != null) {
            passes = passes.copy(
                written = written.toMap(),
                checkedAt = if (check.ok) now else passes.checkedAt,
                checkProblem = problem(check),
            )
            if (!check.ok) log.warn("Copy outside the house: {}", passes.checkProblem)
        }
        folder.record(passes)
        return Outcome.Done(snapshots, media.files, media.done, check)
    }

    /** What a check found wrong, for the admin page; null when nothing. */
    private fun problem(check: OffsiteCheck.Result): String? = when {
        check.ok -> null

        check.held -> "не открылись ${check.bad.size} файлов из ${check.checked}: ничего не удалено. Проверьте диск или синхронизацию папки"

        else -> listOfNotNull(
            check.bad.takeIf { it.isNotEmpty() }?.let { "не открылись как должны и будут записаны заново: ${it.joinToString()}" },
            check.missing.takeIf { it.isNotEmpty() }?.let { "нет файлов: ${it.joinToString()}" },
        ).joinToString("; ")
    }

    /** [backups]' verified snapshots in `<target>/<folder>/<name>.age`, the ones pruned at home pruned there, then the manifest. */
    private fun snapshots(backups: Backups, target: Path, folder: String, key: OffsiteKey): Int {
        val dir = createUnder(target, folder)
        val wanted = backups.history().filter { it.verified }.map { it.file }.toSet()
        var copied = 0
        for (name in wanted) {
            val source = backups.directory.resolve(name)
            val copy = dir.resolve("$name.age")
            if (!source.isRegularFile() || (copy.isRegularFile() && copy.fileSize() == AgeFiles.encryptedSize(source.fileSize()))) continue
            AgeFiles.encrypt(source, copy, key)
            copied++
        }
        val gone = dir.listDirectoryEntries("*.sqlite.age").filter { it.name.removeSuffix(".age") !in wanted }
        gone.forEach(Files::delete)
        if (copied > 0 || gone.isNotEmpty() || !dir.resolve("manifest.json.age").exists()) {
            AgeFiles.encrypt(backups.manifest, dir.resolve("manifest.json.age"), key)
        }
        return copied
    }

    private class Media(val files: Int, val done: Boolean)

    /** Media missing there (by name and size), encrypted; then the ones deleted for good after their quarantine. */
    private fun media(target: Path, key: OffsiteKey): Media {
        val store = blobs ?: return Media(0, true)
        if (!store.ready()) return Media(0, false)
        val all = store.all()
        fun copyOf(sha: String) = key.mediaName(sha).let { target.resolve("media").resolve(it.take(2)).resolve("$it.age") }
        val missing = all.filter { blob ->
            copyOf(blob.sha256).let { !it.isRegularFile() || it.fileSize() != AgeFiles.encryptedSize(blob.size) }
        }
        val total = missing.sumOf { it.size }
        var bytes = 0L
        var files = 0
        try {
            for (blob in missing) {
                progress = OffsiteProgress(files, missing.size, bytes, total)
                val copy = copyOf(blob.sha256)
                createUnder(target, "media/" + copy.parent.name)
                // A file gone bad at home is left out (the scrub reports it); anything else stops the pass.
                try {
                    AgeFiles.encrypt(store.file(blob), copy, key, expect = blob.sha256)
                    files++
                } catch (e: AgeFiles.SourceChanged) {
                    log.error("Not copied outside the house: {}", e.message)
                }
                bytes += blob.size
            }
        } finally {
            progress = null
        }
        // Files deleted for good (ADR-041) go here too, once their quarantine has ended, unless they came back since.
        val kept = all.mapTo(HashSet()) { it.sha256 }
        purges?.deletedForGood()?.filter { it !in kept }?.forEach { sha ->
            val copy = copyOf(sha)
            if (!copy.exists()) return@forEach
            if (runCatching { AgeFiles.sha256(copy, key) }.getOrNull() == sha) {
                copy.deleteIfExists()
                log.info("Deleted for good from the copy outside the house: {}", sha)
            } else {
                log.warn("The copy outside the house of purged {} doesn't open to it: left as it is", sha)
            }
        }
        return Media(files, missing.size == files)
    }

    /** [files] (named from [dir]) as one ZIP, encrypted to `<target>/<name>.age` when its contents changed since the last pass. */
    private fun archive(dir: Path, files: List<Path>, written: MutableMap<String, String>, name: String, target: Path, key: OffsiteKey) {
        if (files.isEmpty()) return
        val zip = dataDir.resolve("backups").resolve("$name.tmp")
        try {
            FileOutputStream(zip.toFile()).use { out ->
                ZipOutputStream(out).use { z ->
                    for (file in files) {
                        val entry = ZipEntry(dir.relativize(file).joinToString("/"))
                        entry.lastModifiedTime = Files.getLastModifiedTime(file)
                        z.putNextEntry(entry)
                        Files.copy(file, z)
                        z.closeEntry()
                    }
                }
            }
            whenChanged(written, name, sha(zip)) { AgeFiles.encrypt(zip, target.resolve("$name.age"), key) }
        } finally {
            zip.deleteIfExists()
        }
    }

    private inline fun whenChanged(written: MutableMap<String, String>, name: String, sha: String, write: () -> Unit) {
        if (written[name] == sha) return
        write()
        written[name] = sha
    }

    /** What a pass cut short left behind. */
    private fun dropParts(target: Path) {
        Files.walk(target, 3).use { paths -> paths.filter { it.name.endsWith(".part") }.toList() }.forEach { it.deleteIfExists() }
    }

    private fun sha(file: Path): String = Files.newInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        HexFormat.of().formatHex(digest.digest())
    }

    companion object {
        const val KEY_FILE = "offsite-key.txt"
        const val CHECK_EVERY = 24L * 3_600_000

        /** The settings a server is stood up again with (as in the second disk's recovery kit, without the key itself). */
        val CONFIG = setOf(ServerConfig.FILE_NAME, "tls/server.keystore", "peer/identity.json")
    }
}
