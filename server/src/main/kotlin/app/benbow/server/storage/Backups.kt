package app.benbow.server.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class BackupRecord(
    val file: String,
    val reason: String,
    val createdAt: Long,
    val verified: Boolean,
    val rows: Map<String, Long>,
    /** Last change-log sequence included, to skip snapshots when nothing changed. */
    val seq: Long = 0,
)

/**
 * Verified SQLite snapshots (data-safety.md#backups, S9). A snapshot counts only after it was
 * reopened, passed `integrity_check` and matched the row counts of the source.
 */
class Backups(dataDir: Path) {
    val directory: Path = dataDir.resolve("backups").resolve("db")
    private val manifest: Path = dataDir.resolve("backups").resolve("manifest.json")
    private val json = Json { prettyPrint = true }

    /** Must be called with exclusive access to [connection] (no writes in between counting and copying). */
    fun snapshot(connection: Connection, reason: String, seq: Long = 0): BackupRecord {
        directory.createDirectories()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        // Two runs in one second (button + schedule) get distinct names; nothing is ever overwritten.
        val target = generateSequence(1) { it + 1 }
            .map { n -> directory.resolve(if (n == 1) "benbow-$stamp-$reason.sqlite" else "benbow-$stamp-$reason-$n.sqlite") }
            .first { !it.exists() }
        val expected = rowCounts(connection)
        connection.createStatement().use { it.execute("VACUUM INTO '${target.toString().replace("'", "''")}'") }
        val record = BackupRecord(target.fileName.toString(), reason, System.currentTimeMillis(), verify(target, expected), expected, seq)
        check(record.verified) { "Backup $target failed verification; refusing to continue" }
        append(record)
        return record
    }

    fun history(): List<BackupRecord> = if (manifest.exists()) json.decodeFromString(manifest.readText()) else emptyList()

    /** Deletes snapshot files not in [keep] and rewrites the manifest; never leaves zero verified snapshots. */
    fun prune(keep: Set<String>): List<String> {
        val records = history()
        val survivors = records.filter { it.file in keep }
        if (survivors.none { it.verified }) return emptyList()
        val removed = records.filter { it.file !in keep }
        removed.forEach { Files.deleteIfExists(directory.resolve(it.file)) }
        val tmp = manifest.resolveSibling("manifest.json.tmp")
        tmp.writeText(json.encodeToString(survivors))
        Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return removed.map { it.file }
    }

    fun file(record: BackupRecord): Path = directory.resolve(record.file)

    private fun verify(file: Path, expected: Map<String, Long>): Boolean =
        DriverManager.getConnection("jdbc:sqlite:file:${file.toUri().path}?mode=ro").use { c ->
            val integrity = c.createStatement().use { st ->
                st.executeQuery("PRAGMA integrity_check").use { rs ->
                    rs.next() &&
                        rs.getString(1) == "ok"
                }
            }
            integrity && rowCounts(c) == expected
        }

    private fun append(record: BackupRecord) {
        val tmp = manifest.resolveSibling("manifest.json.tmp")
        tmp.writeText(json.encodeToString(history() + record))
        Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun rowCounts(connection: Connection): Map<String, Long> {
        val tables = connection.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        return tables.associateWith { table ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM \"$table\"").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }
}
