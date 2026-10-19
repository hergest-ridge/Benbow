package app.benbow.server.peer

import java.nio.file.Path
import java.sql.ResultSet

/** A file this server sent: its body waits in the Drive folder until the friend's server says it has it. */
data class FileOut(
    val id: String,
    val contact: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val doneAt: Long?,
    val removedAt: Long?,
)

/** A file announced by a friend's letter: received once its body came through Drive and matched its fingerprint. */
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
)

private const val OUT_COLUMNS = "id, contact, name, size, sha256, created_at, done_at, removed_at"
private const val IN_COLUMNS = "id, contact, name, size, sha256, file_key, announced_at, received_at, path, last_error"

private fun ResultSet.fileOut() =
    FileOut(getString(1), getString(2), getString(3), getLong(4), getString(5), getLong(6), longOrNull(7), longOrNull(8))

private fun ResultSet.fileIn() = FileIn(
    getString(1), getString(2), getString(3), getLong(4), getString(5), getString(6), getLong(7), longOrNull(8), getString(9),
    getString(10),
)

fun PeerDatabase.addFileOut(contact: String, file: SharedFile, now: Long) = transaction { c ->
    c.prepareStatement("INSERT INTO files_out(id, contact, name, size, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)").use { st ->
        st.setString(1, file.id)
        st.setString(2, contact)
        st.setString(3, file.name)
        st.setLong(4, file.size)
        st.setString(5, file.sha256)
        st.setLong(6, now)
        st.executeUpdate()
    }
}

fun PeerDatabase.filesOut(contact: String, limit: Int): List<FileOut> = transaction { c ->
    c.prepareStatement("SELECT $OUT_COLUMNS FROM files_out WHERE contact = ? ORDER BY created_at DESC LIMIT ?").use { st ->
        st.setString(1, contact)
        st.setInt(2, limit)
        st.executeQuery().use { rs -> rs.all { fileOut() } }
    }
}

/** The friend's server has the file; only that friend can say so. */
fun PeerDatabase.fileDone(contact: String, id: String, now: Long) = transaction { c ->
    c.prepareStatement("UPDATE files_out SET done_at = ? WHERE id = ? AND contact = ? AND done_at IS NULL").use { st ->
        st.setLong(1, now)
        st.setString(2, id)
        st.setString(3, contact)
        st.executeUpdate()
    }
}

/** Bodies the friend has received, still in the Drive folder. */
fun PeerDatabase.doneNotRemoved(): List<FileOut> = transaction { c ->
    c.createStatement().use { st ->
        st.executeQuery("SELECT $OUT_COLUMNS FROM files_out WHERE done_at IS NOT NULL AND removed_at IS NULL").use { rs ->
            rs.all { fileOut() }
        }
    }
}

fun PeerDatabase.markRemoved(id: String, now: Long) = transaction { c ->
    c.prepareStatement("UPDATE files_out SET removed_at = ? WHERE id = ?").use { st ->
        st.setLong(1, now)
        st.setString(2, id)
        st.executeUpdate()
    }
}

/** Keeps a file's announcement; the same announcement twice changes nothing. */
fun PeerDatabase.announceFile(contact: String, file: SharedFile, now: Long) = transaction { c ->
    c.prepareStatement(
        "INSERT OR IGNORE INTO files_in(id, contact, name, size, sha256, file_key, announced_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).use { st ->
        st.setString(1, file.id)
        st.setString(2, contact)
        st.setString(3, file.name)
        st.setLong(4, file.size)
        st.setString(5, file.sha256)
        st.setString(6, file.key)
        st.setLong(7, now)
        st.executeUpdate()
    }
}

fun PeerDatabase.pendingIn(): List<FileIn> = transaction { c ->
    c.createStatement().use { st ->
        st.executeQuery("SELECT $IN_COLUMNS FROM files_in WHERE received_at IS NULL ORDER BY announced_at").use { rs ->
            rs.all { fileIn() }
        }
    }
}

fun PeerDatabase.filesIn(contact: String, limit: Int): List<FileIn> = transaction { c ->
    c.prepareStatement("SELECT $IN_COLUMNS FROM files_in WHERE contact = ? ORDER BY announced_at DESC LIMIT ?").use { st ->
        st.setString(1, contact)
        st.setInt(2, limit)
        st.executeQuery().use { rs -> rs.all { fileIn() } }
    }
}

fun PeerDatabase.markReceived(id: String, path: Path, now: Long) = transaction { c ->
    c.prepareStatement("UPDATE files_in SET received_at = ?, path = ?, last_error = NULL WHERE id = ?").use { st ->
        st.setLong(1, now)
        st.setString(2, path.toString())
        st.setString(3, id)
        st.executeUpdate()
    }
}

fun PeerDatabase.markInError(id: String, message: String) = transaction { c ->
    c.prepareStatement("UPDATE files_in SET last_error = ? WHERE id = ?").use { st ->
        st.setString(1, message.take(300))
        st.setString(2, id)
        st.executeUpdate()
    }
}

/** The folder the Google Drive desktop app mirrors (ADR-039); null until set on the admin page. */
fun PeerDatabase.driveFolder(): Path? = transaction { c ->
    c.prepareStatement("SELECT value FROM settings WHERE key = 'drive.folder'").use { st ->
        st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
}?.takeIf { it.isNotBlank() }?.let(Path::of)

fun PeerDatabase.saveDriveFolder(path: String) = transaction { c ->
    c.prepareStatement("INSERT OR REPLACE INTO settings(key, value) VALUES ('drive.folder', ?)").use { st ->
        st.setString(1, path)
        st.executeUpdate()
    }
}
