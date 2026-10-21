package app.benbow.server.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream

/**
 * Media files addressed by SHA-256 (ADR-009, data-safety S7). An upload grows in `<media>/.uploads`,
 * is fsynced after every chunk, checked against its announced hash and size, then atomically renamed
 * to a human-readable path. Placed files are never changed or deleted here.
 */
class BlobStore(private val db: Database, val root: Path, private val now: () -> Long = System::currentTimeMillis) {
    data class Blob(val sha256: String, val size: Long, val mime: String, val path: String)

    sealed interface Start {
        data class Exists(val blob: Blob) : Start

        data class Upload(val id: String, val offset: Long) : Start
    }

    sealed interface Chunk {
        /** [blob] is set once the last byte arrived and the file was verified and placed. */
        data class Accepted(val offset: Long, val blob: Blob?) : Chunk

        data class WrongOffset(val expected: Long) : Chunk

        data object TooLarge : Chunk

        /** The finished file doesn't match the announced hash or size; the upload is dropped, the client starts over. */
        data object Corrupt : Chunk

        data object NotFound : Chunk
    }

    class InsufficientSpace(message: String) : Exception(message)

    private val uploads = root.resolve(".uploads")
    private val locks = ConcurrentHashMap<String, Any>()

    fun blob(sha256: String): Blob? = db.locked { c ->
        c.prepareStatement("SELECT sha256, size, mime, path FROM blobs WHERE sha256 = ?").use { st ->
            st.setString(1, sha256)
            st.executeQuery().use { rs -> if (rs.next()) Blob(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)) else null }
        }
    }

    fun file(blob: Blob): Path = root.resolve(blob.path)

    /** Begins an upload, or resumes the one already open for this hash. */
    fun start(sha256: String, size: Long, mime: String, name: String?, capturedAt: Long?, deviceId: String?): Start {
        require(SHA256.matches(sha256)) { "sha256 must be 64 lowercase hex characters" }
        require(size in 1..MAX_SIZE) { "size must be 1..$MAX_SIZE" }
        require(MIME.matches(mime)) { "bad mime type" }
        blob(sha256)?.let { return Start.Exists(it) }
        return db.transaction { c ->
            c.prepareStatement("SELECT id, received FROM uploads WHERE sha256 = ? AND size = ?").use { st ->
                st.setString(1, sha256)
                st.setLong(2, size)
                st.executeQuery().use { rs -> if (rs.next()) return@transaction Start.Upload(rs.getString(1), rs.getLong(2)) }
            }
            uploads.createDirectories()
            val free = Files.getFileStore(uploads).usableSpace
            if (free < size + RESERVE) throw InsufficientSpace("Not enough space for ${size / MB} MB in $root (${free / MB} MB free)")
            val id = UUID.randomUUID().toString()
            c.prepareStatement(
                "INSERT INTO uploads(id, sha256, size, mime, name, captured_at, received, device_id, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
            ).use { st ->
                st.setString(1, id)
                st.setString(2, sha256)
                st.setLong(3, size)
                st.setString(4, mime)
                st.setString(5, name?.take(200))
                if (capturedAt != null) st.setLong(6, capturedAt) else st.setNull(6, java.sql.Types.INTEGER)
                st.setString(7, deviceId)
                st.setLong(8, now())
                st.setLong(9, now())
                st.executeUpdate()
            }
            Start.Upload(id, 0)
        }
    }

    /**
     * Appends [data] at [offset] (must equal what was received so far). The chunk is already in memory: the
     * upload's lock never waits on a network connection (a dead one held it for hours, see ChunkBody).
     */
    fun append(id: String, offset: Long, data: ByteArray): Chunk = synchronized(locks.computeIfAbsent(id) { Any() }) {
        val upload = upload(id) ?: return Chunk.NotFound
        if (offset != upload.received) return Chunk.WrongOffset(upload.received)
        if (data.size > MAX_CHUNK || upload.received + data.size > upload.size) return Chunk.TooLarge
        val part = uploads.resolve("$id.part")
        FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.truncate(upload.received) // bytes written after the last recorded offset (crash) are dropped
            channel.position(upload.received)
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        val received = upload.received + data.size
        db.transaction { c ->
            c.prepareStatement("UPDATE uploads SET received = ?, updated_at = ? WHERE id = ?").use { st ->
                st.setLong(1, received)
                st.setLong(2, now())
                st.setString(3, id)
                st.executeUpdate()
            }
        }
        if (received < upload.size) return Chunk.Accepted(received, null)
        place(upload.copy(received = received), part)
    }

    /** Partial uploads nobody touched for [maxAgeMillis]; the device still has the original and starts again. */
    fun dropStaleUploads(maxAgeMillis: Long): Int {
        val stale = db.locked { c ->
            c.prepareStatement("SELECT id FROM uploads WHERE updated_at < ?").use { st ->
                st.setLong(1, now() - maxAgeMillis)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        stale.forEach { id -> synchronized(locks.computeIfAbsent(id) { Any() }) { drop(id, uploads.resolve("$id.part")) } }
        return stale.size
    }

    /** Re-hashes up to [limit] files not verified for [olderThanMillis] (data-safety S7 scrub); returns the corrupt ones. */
    fun scrub(olderThanMillis: Long, limit: Int): List<Blob> {
        val due = db.locked { c ->
            c.prepareStatement("SELECT sha256, size, mime, path FROM blobs WHERE verified_at < ? ORDER BY verified_at LIMIT ?").use { st ->
                st.setLong(1, now() - olderThanMillis)
                st.setInt(2, limit)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Blob(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4))) }
                }
            }
        }
        return due.filter { blob ->
            val file = file(blob)
            val ok = file.exists() && file.fileSize() == blob.size && sha256(file) == blob.sha256
            if (ok) {
                db.transaction { c ->
                    c.prepareStatement("UPDATE blobs SET verified_at = ? WHERE sha256 = ?").use { st ->
                        st.setLong(1, now())
                        st.setString(2, blob.sha256)
                        st.executeUpdate()
                    }
                }
            }
            !ok
        }
    }

    /**
     * Files on disk the database doesn't know, e.g. placed after the snapshot a restore went back to:
     * hashed and recorded again, so devices find them instead of uploading duplicates. Returns how many.
     * Never the uploads in progress, the quarantine of purged files (ADR-041), or the [purging] paths a committed
     * purge is about to move there.
     */
    fun adoptUntracked(purging: Set<String> = emptySet()): Int {
        if (!root.exists()) return 0
        val known = all().mapTo(HashSet()) { it.path } + purging
        val quarantine = root.resolve(Purges.QUARANTINE)
        val untracked = Files.walk(root).use { paths ->
            paths.filter {
                Files.isRegularFile(it) && !it.startsWith(uploads) && !it.startsWith(quarantine) && !it.fileName.toString().endsWith(".tmp")
            }
                .map { root.relativize(it).joinToString("/") }
                .filter { it !in known }
                .toList()
        }
        var adopted = 0
        for (path in untracked) {
            val file = root.resolve(path)
            val sha = sha256(file)
            if (blob(sha) != null) continue // a duplicate of a known file; left alone
            val mime =
                EXTENSIONS.entries.firstOrNull { it.value == path.substringAfterLast('.').lowercase() }?.key ?: "application/octet-stream"
            db.transaction { c ->
                c.prepareStatement(
                    "INSERT INTO blobs(sha256, size, mime, path, created_at, verified_at) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { st ->
                    st.setString(1, sha)
                    st.setLong(2, file.fileSize())
                    st.setString(3, mime)
                    st.setString(4, path)
                    st.setLong(5, now())
                    st.setLong(6, now())
                    st.executeUpdate()
                }
            }
            adopted++
        }
        return adopted
    }

    fun all(): List<Blob> = db.locked { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT sha256, size, mime, path FROM blobs ORDER BY created_at").use { rs ->
                buildList { while (rs.next()) add(Blob(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4))) }
            }
        }
    }

    /** Placed files and their total size, for the admin page. */
    fun stats(): Pair<Long, Long> = db.locked { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*), COALESCE(SUM(size), 0) FROM blobs").use { rs ->
                rs.next()
                rs.getLong(1) to rs.getLong(2)
            }
        }
    }

    private data class Upload(
        val id: String,
        val sha256: String,
        val size: Long,
        val mime: String,
        val name: String?,
        val capturedAt: Long?,
        val received: Long,
    )

    private fun upload(id: String): Upload? = db.locked { c ->
        c.prepareStatement("SELECT id, sha256, size, mime, name, captured_at, received FROM uploads WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) return@locked null
                Upload(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getLong(6).takeIf { !rs.wasNull() },
                    rs.getLong(7),
                )
            }
        }
    }

    private fun place(upload: Upload, part: Path): Chunk {
        if (part.fileSize() != upload.size || sha256(part) != upload.sha256) {
            drop(upload.id, part)
            return Chunk.Corrupt
        }
        blob(upload.sha256)?.let { existing ->
            drop(upload.id, part) // the same content arrived through another upload meanwhile
            return Chunk.Accepted(upload.size, existing)
        }
        val relative = humanPath(upload)
        val target = root.resolve(relative)
        target.parent.createDirectories()
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
        val blob = Blob(upload.sha256, upload.size, upload.mime, relative)
        db.transaction { c ->
            c.prepareStatement("INSERT INTO blobs(sha256, size, mime, path, created_at, verified_at) VALUES (?, ?, ?, ?, ?, ?)").use { st ->
                st.setString(1, blob.sha256)
                st.setLong(2, blob.size)
                st.setString(3, blob.mime)
                st.setString(4, blob.path)
                st.setLong(5, now())
                st.setLong(6, now())
                st.executeUpdate()
            }
            c.prepareStatement("DELETE FROM uploads WHERE id = ?").use { st ->
                st.setString(1, upload.id)
                st.executeUpdate()
            }
            // Entries pointing at this file changed in effect: devices learn it's available, the export links it.
            c.prepareStatement("INSERT INTO change_log(entry_id, at) SELECT id, ? FROM entries WHERE instr(attachments, ?) > 0").use { st ->
                st.setLong(1, now())
                st.setString(2, blob.sha256)
                st.executeUpdate()
            }
        }
        return Chunk.Accepted(upload.size, blob)
    }

    private fun drop(id: String, part: Path) {
        db.transaction { c ->
            c.prepareStatement("DELETE FROM uploads WHERE id = ?").use { st ->
                st.setString(1, id)
                st.executeUpdate()
            }
        }
        part.deleteIfExists()
    }

    /** `YYYY/MM/YYYY-MM-DD_HHMMSS_<sha8>.<ext>` (ADR-009): browsable without the app. */
    private fun humanPath(upload: Upload): String {
        val time = Instant.ofEpochMilli(upload.capturedAt ?: now()).atZone(ZoneId.systemDefault())
        val ext = upload.name?.substringAfterLast('.', "")?.lowercase()?.takeIf { EXT.matches(it) } ?: EXTENSIONS[upload.mime] ?: "bin"
        val base = "${time.format(FOLDER)}/${time.format(FILE)}_${upload.sha256.take(8)}"
        return generateSequence(1) { it + 1 }.map { n ->
            if (n ==
                1
            ) {
                "$base.$ext"
            } else {
                "$base-$n.$ext"
            }
        }.first { !root.resolve(it).exists() }
    }

    companion object {
        const val MAX_SIZE = 64L * 1024 * 1024 * 1024
        const val MAX_CHUNK = 16L * 1024 * 1024
        private const val MB = 1024 * 1024
        private const val RESERVE = 1024L * MB
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val MIME = Regex("[a-z]+/[a-z0-9.+-]+")
        private val EXT = Regex("[a-z0-9]{1,8}")
        private val FOLDER = DateTimeFormatter.ofPattern("yyyy/MM")
        private val FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        private val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/gif" to "gif",
            "video/mp4" to "mp4",
            "video/quicktime" to "mov",
            "video/webm" to "webm",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",
            "audio/ogg" to "ogg",
            "audio/mpeg" to "mp3",
            "application/pdf" to "pdf",
        )

        fun sha256(file: Path): String {
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
}
