package app.benbow.server.offsite

import app.benbow.server.PURGE_JOURNAL
import app.benbow.server.ServerConfig
import app.benbow.server.storage.Backups
import app.benbow.server.storage.MediaFolder
import app.benbow.server.storage.Restore
import app.benbow.server.storage.createUnder
import app.benbow.server.storage.markValue
import app.benbow.server.storage.readMark
import app.benbow.server.storage.writeMark
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

/**
 * `--restore-from-offsite <copy>` (ADR-043, as built 7.7 E5): a data folder on a new PC built from the copy outside the
 * house, when nothing at home is left. Opened with the code or, through `key.age`, the passphrase. The settings and keys
 * as they were (devices know the server by the same certificate); every snapshot there with its manifest and the newest
 * verified one put in place with a new epoch, the calendar the same way; the purge journal; every media file decrypted,
 * checked by age and placed where the restored database says (one it doesn't know waits in `restored/` and is adopted at
 * the first start). Only into a data folder with no database and an empty media folder; the copy is only read. The
 * restored server goes on writing the same copy with the same key; it has no second disk until one is chosen.
 */
object OffsiteRestore {
    class Result(val storeId: String, val epoch: Long, val snapshot: Path, val media: Path, val files: Int, val unknown: Int)

    /** The key the owner typed: the code itself, or the passphrase that opens the copy's `key.age`. */
    fun key(copy: Path, typed: String): OffsiteKey {
        // A code's file (an age identity file with comments) or the code typed, in groups or not.
        val line = typed.lines().map(String::trim).firstOrNull { it.uppercase().startsWith(OffsiteKey.PREFIX) }
        OffsiteKey.parse(line ?: typed)?.let { return it }
        val sealed = copy.resolve(OffsiteSetup.KEY_AGE)
        check(sealed.isRegularFile()) { "Это не код восстановления, а пароля у этой копии нет (в ней нет key.age)" }
        return try {
            OffsiteKey.unseal(sealed.readBytes(), typed)
        } catch (e: OffsiteKey.WrongPassphrase) {
            error("Ни код, ни пароль этой копии: key.age этим паролем не открывается")
        }
    }

    fun run(dataDir: Path, copy: Path, key: OffsiteKey, mediaRoot: Path? = null): Result {
        val storeId = readMark(copy, OffsiteFolder.MARK)?.takeIf { it.isNotEmpty() }
            ?: error("$copy has no ${OffsiteFolder.MARK}: it is not a Benbow copy outside the house")
        check(markValue(copy, OffsiteFolder.MARK, "recipient") == key.recipient) { "Этот код или пароль от другой копии" }
        check(copy.resolve("config.zip.age").isRegularFile()) { "$copy has no config.zip.age with the settings and the certificate" }
        check(!dataDir.resolve("db").resolve("benbow.sqlite").exists()) {
            "$dataDir already holds a server: stop it and move that folder away, or restore it with restore-backup.cmd"
        }
        val media = mediaRoot ?: dataDir.resolve("media")
        check(!media.exists() || (media.isDirectory() && media.listDirectoryEntries().isEmpty())) { "The media folder $media is not empty" }

        // Settings and keys first: this PC's media folder, this copy going on, no second disk yet; the rest as it was.
        dataDir.createDirectories()
        val config = unzip(AgeFiles.decrypt(copy.resolve("config.zip.age").readBytes(), key))
        val props = Properties().apply {
            config[ServerConfig.FILE_NAME]?.inputStream()?.use(::load)
                ?: error("config.zip.age has no settings")
        }
        props.setProperty("media.root", mediaRoot?.toAbsolutePath()?.toString()?.replace('\\', '/') ?: "")
        props.setProperty("backup.mirror", "")
        props.setProperty("backup.offsite", copy.toAbsolutePath().toString().replace('\\', '/'))
        val tmp = dataDir.resolve("${ServerConfig.FILE_NAME}.tmp")
        tmp.outputStream().use { props.store(it, "Benbow server settings, restored from the copy outside the house $copy") }
        Files.move(tmp, dataDir.resolve(ServerConfig.FILE_NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        for ((relative, bytes) in config - ServerConfig.FILE_NAME) {
            write(createUnder(dataDir, relative.substringBeforeLast('/', "")).resolve(relative.substringAfterLast('/')), bytes)
        }
        key.save(dataDir.resolve("keys").resolve(OffsiteCopy.KEY_FILE))
        copy.resolve("db").resolve("$PURGE_JOURNAL.age").takeIf { it.isRegularFile() }?.let {
            AgeFiles.decrypt(it, createUnder(dataDir, "db").resolve(PURGE_JOURNAL), key)
        }

        // Snapshots with their manifest, then the newest verified one as the live database; the calendar the same way.
        snapshots(copy.resolve("db"), Backups(dataDir), key)
        val restored = Restore.run(dataDir, "latest")
        check(restored.storeId == storeId) { "The snapshots in $copy belong to store ${restored.storeId}, not to $storeId" }
        val calendar = Backups.calendar(dataDir)
        if (snapshots(copy.resolve("calendar"), calendar, key) > 0) Restore.run(dataDir, "latest", Restore.CALENDAR)

        // Every media file, decrypted and placed where the database says; the folder marked as this store's.
        media.createDirectories()
        val paths = DriverManager.getConnection(
            "jdbc:sqlite:file:${dataDir.resolve("db").resolve("benbow.sqlite").toUri().path}?mode=ro",
        ).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT sha256, path FROM blobs").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
                }
            }
        }
        var files = 0
        var unknown = 0
        val source = copy.resolve("media")
        val all = if (source.isDirectory()) {
            Files.walk(source).use { s -> s.filter { Files.isRegularFile(it) && it.name.endsWith(".age") }.toList() }
        } else {
            emptyList()
        }
        val landing = media.resolve(".restoring")
        for (file in all) {
            landing.createDirectories()
            val open = landing.resolve(file.name)
            val sha = AgeFiles.decrypt(file, open, key)
            val path = paths[sha] ?: "restored/$sha".also { unknown++ }
            Files.move(
                open,
                createUnder(media, path.substringBeforeLast('/', "")).resolve(path.substringAfterLast('/')),
                StandardCopyOption.ATOMIC_MOVE,
            )
            files++
        }
        landing.deleteIfExists()
        writeMark(media, MediaFolder.MARK, "Benbow media folder", storeId)
        return Result(storeId, restored.epoch, restored.snapshot, media, files, unknown)
    }

    /** [from]'s snapshots decrypted into [to] with its manifest, naming only those there; how many. */
    private fun snapshots(from: Path, to: Backups, key: OffsiteKey): Int {
        val manifest = from.resolve("manifest.json.age")
        if (!manifest.isRegularFile()) return 0
        to.directory.createDirectories()
        write(to.manifest, AgeFiles.decrypt(manifest.readBytes(), key))
        val present = to.history().filter { from.resolve("${it.file}.age").isRegularFile() }
        for (record in present) {
            AgeFiles.decrypt(from.resolve("${record.file}.age"), to.file(record), key)
            check(!record.verified || to.verify(to.file(record), record.rows)) { "Snapshot ${record.file} from the copy doesn't check out" }
        }
        // A manifest line whose file the copy didn't have would make "latest" point at nothing: keep only those present.
        to.prune(present.map { it.file }.toSet())
        return present.count { it.verified }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { z ->
        generateSequence { z.nextEntry }.filterNot { it.isDirectory }.associate { it.name to z.readBytes() }
    }

    /** Through a synced temporary file and one atomic move. */
    private fun write(file: Path, bytes: ByteArray) {
        val tmp = file.resolveSibling("${file.name}.tmp")
        java.io.FileOutputStream(tmp.toFile()).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
