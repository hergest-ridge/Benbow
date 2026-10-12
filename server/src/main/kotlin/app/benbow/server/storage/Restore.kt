package app.benbow.server.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * `--restore <snapshot | latest>` (data-safety runbook): puts a snapshot back as the live database.
 * Runs with the server stopped. Nothing is deleted: the replaced files move to `db/replaced-<time>`.
 * The restored store gets an epoch past every epoch this data folder has seen, so paired devices
 * notice and re-upload what the snapshot lacks (docs/sync.md#store-identity).
 */
object Restore {
    class Result(val snapshot: Path, val storeId: String, val epoch: Long, val replacedInto: Path?, val otherStore: Boolean)

    fun run(dataDir: Path, which: String): Result {
        val backups = Backups(dataDir)
        val snapshot = if (which == "latest") {
            backups.history().filter { it.verified }.maxByOrNull { it.createdAt }?.let(backups::file)
                ?: error("No verified snapshot in ${backups.directory}")
        } else {
            Path.of(which)
        }
        check(snapshot.exists()) { "No such file: $snapshot" }
        val restored = inspect(snapshot) ?: error("$snapshot is not a healthy Benbow database (integrity check failed)")

        val dbDir = dataDir.resolve("db").createDirectories()
        val live = dbDir.resolve(DB_FILE)
        val current = if (live.exists()) inspect(live) else null // null also when the live file is corrupt
        val mark = HighWater(dataDir).read()
        val epoch = maxOf(restored.epoch, current?.epoch ?: 0, mark?.epoch ?: 0) + 1

        val files = listOf(DB_FILE, "$DB_FILE-wal", "$DB_FILE-shm").map(dbDir::resolve).filter { it.exists() }
        val kept = if (files.isEmpty()) {
            null
        } else {
            val stamp = "replaced-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val dir = generateSequence(1) { it + 1 }.map { n -> dbDir.resolve(if (n == 1) stamp else "$stamp-$n") }.first { !it.exists() }
            dir.createDirectories().also { target -> files.forEach { Files.move(it, target.resolve(it.fileName)) } }
        }
        Files.copy(snapshot, live)
        DriverManager.getConnection("jdbc:sqlite:$live").use { c ->
            c.prepareStatement("UPDATE store_meta SET value = ? WHERE key = 'epoch'").use {
                it.setString(1, epoch.toString())
                check(it.executeUpdate() == 1) { "store_meta.epoch missing in $snapshot" }
            }
        }
        HighWater(dataDir).write(HighWater.Mark(restored.storeId, epoch, restored.cursor))
        val otherStore = (current?.storeId ?: mark?.storeId)?.let { it != restored.storeId } == true
        return Result(snapshot, restored.storeId, epoch, kept, otherStore)
    }

    private class Info(val storeId: String, val epoch: Long, val cursor: Long)

    private fun inspect(file: Path): Info? = try {
        DriverManager.getConnection("jdbc:sqlite:$file", Properties().apply { setProperty("open_mode", "1") }).use { c ->
            c.createStatement().use { st ->
                val check = st.executeQuery("PRAGMA integrity_check").use { rs -> if (rs.next()) rs.getString(1) else null }
                if (check != "ok") return null
                fun meta(key: String) = st.executeQuery("SELECT value FROM store_meta WHERE key = '$key'").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
                val cursor = st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM change_log").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
                Info(meta("store_id") ?: return null, meta("epoch")?.toLong() ?: return null, cursor)
            }
        }
    } catch (_: SQLException) {
        null
    }

    private const val DB_FILE = "benbow.sqlite"
}
