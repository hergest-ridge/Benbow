package app.benbow.server.backup

import app.benbow.server.PURGE_JOURNAL
import app.benbow.server.ServerConfig
import app.benbow.server.storage.Backups
import app.benbow.server.storage.MediaFolder
import app.benbow.server.storage.Restore
import app.benbow.server.storage.createUnder
import app.benbow.server.storage.readMark
import app.benbow.server.storage.writeMark
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream

/**
 * `--restore-from-mirror <mirror> [--media <folder>]` (data-safety runbook "PC dead", ADR-042, as built 7.19 P2): a data
 * folder on a new PC built from the second disk. The settings and keys as they were (devices know the server by the same
 * certificate), every snapshot with its manifest (so the next pass doesn't prune the mirror's history) and the newest
 * verified one put in place with a new epoch, the calendar the same way, the purge journal (done again at the first
 * start), and every media file copied and checked by SHA-256. Only into a data folder with no database and an empty
 * media folder; the mirror is only read. The settings then point at this mirror and this media folder.
 */
object MirrorRestore {
    class Result(val storeId: String, val epoch: Long, val snapshot: Path, val media: Path, val files: Int, val bytes: Long)

    fun run(dataDir: Path, mirror: Path, mediaRoot: Path? = null): Result {
        val storeId = readMark(mirror, MirrorFolder.MARK)?.takeIf { it.isNotEmpty() }
            ?: error("$mirror has no ${MirrorFolder.MARK}: it is not a Benbow mirror")
        val journal = Backups(mirror.resolve("db"), mirror.resolve("manifest.json"))
        check(journal.history().any { it.verified && journal.file(it).exists() }) { "No verified snapshot in $mirror\\db" }
        val settings = mirror.resolve(RecoveryKit.CONFIG_DIR).resolve(ServerConfig.FILE_NAME)
        val keystore = mirror.resolve(RecoveryKit.CONFIG_DIR).resolve("tls").resolve("server.keystore")
        check(settings.isRegularFile() && keystore.isRegularFile()) {
            "$mirror has no ${RecoveryKit.CONFIG_DIR}\\ with the settings and the certificate (written by server 0.12.1 and later)"
        }
        check(!dataDir.resolve("db").resolve("benbow.sqlite").exists()) {
            "$dataDir already holds a server: stop it and move that folder away, or restore it with restore-backup.cmd"
        }
        val media = mediaRoot ?: dataDir.resolve("media")
        check(!media.exists() || (media.isDirectory() && media.listDirectoryEntries().isEmpty())) { "The media folder $media is not empty" }

        // Settings first: this PC's mirror and media folder, everything else as it was (the keystore's password too).
        val props = Properties().apply { settings.inputStream().use(::load) }
        props.setProperty("backup.mirror", mirror.toAbsolutePath().toString().replace('\\', '/'))
        props.setProperty("media.root", mediaRoot?.toAbsolutePath()?.toString()?.replace('\\', '/') ?: "")
        dataDir.createDirectories()
        val tmp = dataDir.resolve("${ServerConfig.FILE_NAME}.tmp")
        tmp.outputStream().use { props.store(it, "Benbow server settings, restored from $mirror") }
        Files.move(tmp, dataDir.resolve(ServerConfig.FILE_NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        for (relative in RecoveryKit.CONFIG.drop(1)) {
            val source = mirror.resolve(RecoveryKit.CONFIG_DIR).resolve(relative)
            if (source.isRegularFile()) {
                RecoveryKit.copyChecked(
                    source,
                    createUnder(dataDir, relative.substringBeforeLast('/')).resolve(source.name),
                )
            }
        }
        mirror.resolve("db").resolve(PURGE_JOURNAL).takeIf { it.isRegularFile() }?.let {
            RecoveryKit.copyChecked(it, createUnder(dataDir, "db").resolve(PURGE_JOURNAL))
        }

        // Snapshots with their manifests, then the newest verified one as the live database.
        copySnapshots(journal, Backups(dataDir))
        val restored = Restore.run(dataDir, "latest")
        check(restored.storeId == storeId) { "The snapshots in $mirror belong to store ${restored.storeId}, not to $storeId" }
        val calendar = Backups(mirror.resolve("calendar"), mirror.resolve("calendar").resolve("manifest.json"), "calendar")
        if (calendar.history().any { it.verified && calendar.file(it).exists() }) {
            copySnapshots(calendar, Backups.calendar(dataDir))
            Restore.run(dataDir, "latest", Restore.CALENDAR)
        }

        // Every media file, checked; the folder marked as this store's (P1).
        media.createDirectories()
        var files = 0
        var bytes = 0L
        val source = mirror.resolve("media")
        if (source.isDirectory()) {
            val all = Files.walk(source).use { paths ->
                paths.filter { Files.isRegularFile(it) && !it.name.endsWith(".tmp") }.toList()
            }
            for (file in all) {
                val relative = source.relativize(file).joinToString("/")
                RecoveryKit.copyChecked(file, createUnder(media, relative.substringBeforeLast('/', "")).resolve(file.name), always = true)
                files++
                bytes += file.fileSize()
            }
        }
        writeMark(media, MediaFolder.MARK, "Benbow media folder", storeId)
        return Result(storeId, restored.epoch, restored.snapshot, media, files, bytes)
    }

    /** Every snapshot of [from] that is there, checked, and its manifest naming only those. */
    private fun copySnapshots(from: Backups, to: Backups) {
        to.directory.createDirectories()
        val present = from.history().filter { from.file(it).isRegularFile() }
        for (record in present) RecoveryKit.copyChecked(from.file(record), to.directory.resolve(record.file))
        val tmp = to.manifest.resolveSibling("${to.manifest.name}.tmp")
        tmp.outputStream().use { out -> from.manifest.inputStream().use { it.copyTo(out) } }
        Files.move(tmp, to.manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // A manifest line whose file the mirror didn't have would make "latest" point at nothing: keep only those present.
        val kept = to.history().filter { record -> present.any { it.file == record.file } }.map { it.file }.toSet()
        to.prune(kept)
    }
}
