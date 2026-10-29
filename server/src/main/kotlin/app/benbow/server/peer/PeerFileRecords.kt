package app.benbow.server.peer

import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

/**
 * A file this server sent. Through a carrier (ADR-055) it is sealed first, then cut into [parts] and announced; through
 * the Drive folder ([via] = `drive`, before ADR-055) its body waited there whole until the friend's server had it.
 */
data class FileOut(
    val id: String,
    val contact: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val doneAt: Long?,
    val removedAt: Long?,
    val via: String,
    val sealedSize: Long?,
    val partSize: Long?,
    /** Each part's SHA-256; null until the file is cut and announced. */
    val parts: List<String>?,
    val dropped: Int,
    val got: Int,
    val lastError: String?,
)

/** A file announced by a friend's letter: received once all its parts came, opened and matched its fingerprint. */
data class FileIn(
    val id: String,
    val contact: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val key: String,
    val announcedAt: Long,
    val receivedAt: Long?,
    val path: String?,
    val lastError: String?,
    val via: String,
    val sealedSize: Long?,
    val partSize: Long?,
    /** Each part's SHA-256; null for a file through the Drive folder, which came whole. */
    val parts: List<String>?,
    val got: Int,
)

/** A part sent: when it was last dropped, and when the friend's server said it has it. */
data class PartOut(val n: Int, val droppedAt: Long, val gotAt: Long?)

/** For what files' letters carry: a newer server's extra fields are passed over (ADR-044). */
internal val fileJson = Json { ignoreUnknownKeys = true }

private fun hashes(text: String?): List<String>? = text?.let { fileJson.decodeFromString<List<String>>(it) }

private const val OUT_COLUMNS = "id, contact, name, size, sha256, created_at, done_at, removed_at, via, sealed_size, part_size, " +
    "part_hashes, (SELECT COUNT(*) FROM parts_out p WHERE p.file = f.id), " +
    "(SELECT COUNT(*) FROM parts_out p WHERE p.file = f.id AND p.got_at IS NOT NULL), last_error"
private const val IN_COLUMNS = "id, contact, name, size, sha256, file_key, announced_at, received_at, path, last_error, via, " +
    "sealed_size, part_size, part_hashes, (SELECT COUNT(*) FROM parts_in p WHERE p.file = f.id)"

private fun ResultSet.fileOut() = FileOut(
    getString(1), getString(2), getString(3), getLong(4), getString(5), getLong(6), longOrNull(7), longOrNull(8), getString(9),
    longOrNull(10), longOrNull(11), hashes(getString(12)), getInt(13), getInt(14), getString(15),
)

private fun ResultSet.fileIn() = FileIn(
    getString(1), getString(2), getString(3), getLong(4), getString(5), getString(6), getLong(7), longOrNull(8), getString(9),
    getString(10), getString(11), longOrNull(12), longOrNull(13), hashes(getString(14)), getInt(15),
)

private fun <T> Connection.query(sql: String, vararg args: Any?, row: ResultSet.() -> T): List<T> = prepareStatement(sql).use { st ->
    args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
    st.executeQuery().use { rs -> rs.all(row) }
}

private fun Connection.update(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { st ->
    args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
    st.executeUpdate()
}

/** A file sealed for [contact], waiting to be cut and announced on its carrier [via]; its key stays here until then. */
fun PeerDatabase.addFileOut(contact: String, file: SharedFile, via: String, sealedSize: Long, now: Long) = transaction { c ->
    c.update(
        "INSERT INTO files_out(id, contact, name, size, sha256, created_at, via, sealed_size, file_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        file.id, contact, file.name, file.size, file.sha256, now, via, sealedSize, file.key,
    )
}

fun PeerDatabase.filesOut(contact: String, limit: Int): List<FileOut> = transaction { c ->
    c.query("SELECT $OUT_COLUMNS FROM files_out f WHERE contact = ? ORDER BY created_at DESC LIMIT ?", contact, limit) { fileOut() }
}

/** Files going [via] a carrier that the friend doesn't have yet, oldest first. */
fun PeerDatabase.outInFlight(via: String): List<FileOut> = transaction { c ->
    c.query("SELECT $OUT_COLUMNS FROM files_out f WHERE via = ? AND done_at IS NULL ORDER BY created_at", via) { fileOut() }
}

/** The key of a file not yet announced (it is forgotten once the friend has the file). */
fun PeerDatabase.fileKey(id: String): String? = transaction { c ->
    c.query("SELECT file_key FROM files_out WHERE id = ?", id) { getString(1) }.firstOrNull()
}

/** The file is cut into parts and its announcement queued, both or neither. */
fun PeerDatabase.cutFileOut(file: FileOut, partSize: Long, parts: List<String>, letter: String, now: Long) = transaction { c ->
    c.update("UPDATE files_out SET part_size = ?, part_hashes = ? WHERE id = ?", partSize, fileJson.encodeToString(parts), file.id)
    enqueue(file.contact, Letter.FILE_PARTS, letter, now)
}

fun PeerDatabase.markOutError(id: String, message: String?) = transaction { c ->
    c.update("UPDATE files_out SET last_error = ? WHERE id = ?", message?.take(300), id)
}

fun PeerDatabase.partsOut(file: String): List<PartOut> = transaction { c ->
    c.query("SELECT n, dropped_at, got_at FROM parts_out WHERE file = ? ORDER BY n", file) { PartOut(getInt(1), getLong(2), longOrNull(3)) }
}

fun PeerDatabase.partDropped(file: String, n: Int, now: Long) = transaction { c ->
    c.update(
        "INSERT INTO parts_out(file, n, dropped_at) VALUES (?, ?, ?) ON CONFLICT(file, n) DO UPDATE SET dropped_at = excluded.dropped_at",
        file,
        n,
        now,
    )
}

/** The friend's server has these parts; only the friend the file went to can say so. Returns how many are new. */
fun PeerDatabase.partsGot(contact: String, file: String, parts: Collection<Int>, now: Long): Int = transaction { c ->
    val count = c.query("SELECT json_array_length(part_hashes) FROM files_out WHERE id = ? AND contact = ?", file, contact) {
        longOrNull(1)
    }.firstOrNull() ?: return@transaction 0
    parts.filter { it in 0 until count }.sumOf { n ->
        c.update(
            "INSERT INTO parts_out(file, n, dropped_at, got_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(file, n) DO UPDATE SET got_at = excluded.got_at WHERE got_at IS NULL",
            file,
            n,
            now,
            now,
        )
    }
}

/** The friend's server has the file; only that friend can say so. */
fun PeerDatabase.fileDone(contact: String, id: String, now: Long) = transaction { c ->
    c.update("UPDATE files_out SET done_at = ? WHERE id = ? AND contact = ? AND done_at IS NULL", now, id, contact)
}

/** Files the friend has received whose sealed copy (in the Drive folder, or this server's own) is still there. */
fun PeerDatabase.doneNotRemoved(via: String): List<FileOut> = transaction { c ->
    c.query("SELECT $OUT_COLUMNS FROM files_out f WHERE via = ? AND done_at IS NOT NULL AND removed_at IS NULL", via) { fileOut() }
}

fun PeerDatabase.markRemoved(id: String, now: Long) = transaction { c ->
    c.update("UPDATE files_out SET removed_at = ?, file_key = NULL WHERE id = ?", now, id)
}

fun PeerDatabase.outIds(): Set<String> = transaction { c -> c.query("SELECT id FROM files_out") { getString(1) }.toSet() }

/** Bytes dropped through [carrier] since [since]: what its daily budget has left. */
fun PeerDatabase.spent(carrier: String, since: Long): Long = transaction { c ->
    c.query("SELECT COALESCE(SUM(bytes), 0) FROM spend WHERE carrier = ? AND at > ?", carrier, since) { getLong(1) }.single()
}

/** When the oldest spending since [since] stops counting (what it frees is then usable again). */
fun PeerDatabase.firstSpent(carrier: String, since: Long): Long? = transaction { c ->
    c.query("SELECT MIN(at) FROM spend WHERE carrier = ? AND at > ?", carrier, since) { longOrNull(1) }.single()
}

fun PeerDatabase.spend(carrier: String, bytes: Long, now: Long) = transaction { c ->
    c.update("INSERT INTO spend(at, carrier, bytes) VALUES (?, ?, ?)", now, carrier, bytes)
    c.update("DELETE FROM spend WHERE at < ?", now - 2 * DAY_MS)
}

internal const val DAY_MS = 24 * 60 * 60_000L

/** Keeps a file's announcement through the Drive folder (a `file` letter); the same one twice changes nothing. */
fun PeerDatabase.announceFile(contact: String, file: SharedFile, now: Long) = transaction { c ->
    c.update(
        "INSERT OR IGNORE INTO files_in(id, contact, name, size, sha256, file_key, announced_at, via) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        file.id, contact, file.name, file.size, file.sha256, file.key, now, VIA_DRIVE,
    )
}

/** Keeps a file's announcement in parts (a `file-parts` letter); the same one twice changes nothing. */
fun PeerDatabase.announceParts(contact: String, file: PartedFile, now: Long) = transaction { c ->
    c.update(
        "INSERT OR IGNORE INTO files_in(id, contact, name, size, sha256, file_key, announced_at, via, sealed_size, part_size, " +
            "part_hashes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        file.id, contact, file.name, file.size, file.sha256, file.key, now, file.via, file.sealedSize, file.partSize,
        fileJson.encodeToString(file.parts),
    )
}

/** `file-parts` letters kept while this server didn't know the kind (ADR-044): announced now. */
fun PeerDatabase.announceKeptParts(now: Long): Int = transaction { c ->
    c.query("SELECT contact, text FROM inbox WHERE kind = ?", Letter.FILE_PARTS) { getString(1) to getString(2) }
        .sumOf { (contact, text) ->
            runCatching { fileJson.decodeFromString<PartedFile>(text) }.getOrNull()?.let { announceParts(contact, it, now) } ?: 0
        }
}

/** Files announced [via] a carrier and not received yet, oldest first. */
fun PeerDatabase.pendingIn(via: String): List<FileIn> = transaction { c ->
    c.query("SELECT $IN_COLUMNS FROM files_in f WHERE via = ? AND received_at IS NULL ORDER BY announced_at", via) { fileIn() }
}

fun PeerDatabase.filesIn(contact: String, limit: Int): List<FileIn> = transaction { c ->
    c.query("SELECT $IN_COLUMNS FROM files_in f WHERE contact = ? ORDER BY announced_at DESC LIMIT ?", contact, limit) { fileIn() }
}

fun PeerDatabase.receivedIn(id: String): Boolean = transaction { c ->
    c.query("SELECT 1 FROM files_in WHERE id = ? AND received_at IS NOT NULL", id) { true }.isNotEmpty()
}

fun PeerDatabase.partsIn(file: String): Set<Int> = transaction { c ->
    c.query("SELECT n FROM parts_in WHERE file = ?", file) { getInt(1) }.toSet()
}

/** A part written to the file being put together; kept once. */
fun PeerDatabase.partIn(file: String, n: Int, now: Long) = transaction { c ->
    c.update("INSERT OR IGNORE INTO parts_in(file, n, got_at) VALUES (?, ?, ?)", file, n, now)
}

/** The parts of [file] got and not yet told to the sender, and the letter that tells it, queued with them marked. */
fun PeerDatabase.tellParts(file: FileIn, now: Long): Int = transaction { c ->
    val untold = c.query("SELECT n FROM parts_in WHERE file = ? AND told_at IS NULL ORDER BY n", file.id) { getInt(1) }
    if (untold.isEmpty()) return@transaction 0
    c.update("UPDATE parts_in SET told_at = ? WHERE file = ? AND told_at IS NULL", now, file.id)
    enqueue(file.contact, Letter.FILE_GOT, fileJson.encodeToString(PartsGot(file.id, untold)), now)
    untold.size
}

/** The file is kept at [path]: its parts are forgotten and the sender told, both or neither. */
fun PeerDatabase.markReceived(file: FileIn, path: Path, now: Long, tell: Boolean) = transaction { c ->
    c.update("UPDATE files_in SET received_at = ?, path = ?, last_error = NULL WHERE id = ?", now, path.toString(), file.id)
    c.update("DELETE FROM parts_in WHERE file = ?", file.id)
    if (tell) enqueue(file.contact, Letter.FILE_DONE, file.id, now)
}

fun PeerDatabase.markInError(id: String, message: String?) = transaction { c ->
    c.update("UPDATE files_in SET last_error = ? WHERE id = ?", message?.take(300), id)
}

/** How this server sends files (ADR-055): [VIA_MAIL] unless chosen otherwise in «Связь». */
fun PeerDatabase.filesVia(): String = transaction { c ->
    c.query("SELECT value FROM settings WHERE key = 'files.via'") { getString(1) }.firstOrNull()
} ?: VIA_MAIL

fun PeerDatabase.saveFilesVia(via: String) = transaction { c ->
    require(via in setOf(VIA_MAIL, VIA_PARTS_MAIL, VIA_FOLDER)) { "нет такого пути: $via" }
    c.update("INSERT OR REPLACE INTO settings(key, value) VALUES ('files.via', ?)", via)
}

/** The folder the Google Drive desktop app mirrors, or Syncthing keeps (ADR-039, ADR-055); null until set on the admin page. */
fun PeerDatabase.driveFolder(): Path? = transaction { c ->
    c.query("SELECT value FROM settings WHERE key = 'drive.folder'") { getString(1) }.firstOrNull()
}?.takeIf { it.isNotBlank() }?.let(Path::of)

fun PeerDatabase.saveDriveFolder(path: String) = transaction { c ->
    c.update("INSERT OR REPLACE INTO settings(key, value) VALUES ('drive.folder', ?)", path)
}
