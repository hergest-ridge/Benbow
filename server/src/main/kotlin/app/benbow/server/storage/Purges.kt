package app.benbow.server.storage

import app.benbow.core.api.AttachmentRef
import app.benbow.core.api.PurgeDone
import app.benbow.core.api.PurgeFileInfo
import app.benbow.core.api.PurgePreview
import app.benbow.core.api.PurgeRecord
import app.benbow.core.api.PurgeRequest
import app.benbow.core.api.PurgedFile
import app.benbow.core.api.PurgedLink
import app.benbow.core.api.StayingInfo
import app.benbow.core.api.UnreferencedFile
import app.benbow.core.media.AttachmentKey
import app.benbow.core.media.MediaRefs
import app.benbow.core.media.MediaState
import app.benbow.core.media.PurgePlan
import app.benbow.core.media.PurgeSelection
import app.benbow.core.sync.EntryState
import app.benbow.core.sync.Purging
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Deleting for good (ADR-041, docs/data-safety.md "Purge"), the server's whole part of it.
 *
 * A preview counts every reference over all entries ([MediaRefs]) and keeps its plan for [PLAN_MILLIS]. The commit counts
 * again with the database's lock held and does that plan only if nothing in it changed (else [Commit.Changed]), in one
 * transaction: the links purged and whole entries marked ([Purging]), the change log, the purge's row and its files
 * leaving the blob table for the quarantine queue. Then the journal line goes to [journal] (fsync'd) and the files move
 * to `<media>/.purged/<day>/`. After [quarantineMillis] a file is deleted only if nothing references it by then (one
 * attached again goes back); the backup mirror deletes its copy only after that, by the journal, the hash matching.
 */
class Purges(
    private val db: Database,
    private val blobs: BlobStore,
    private val journal: Path,
    private val now: () -> Long = System::currentTimeMillis,
    private val quarantineMillis: Long = QUARANTINE_MILLIS,
) {
    private val log = LoggerFactory.getLogger(Purges::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val plans = ConcurrentHashMap<String, Plan>()
    private val store = SqliteSyncStore(db, null)

    private class Plan(val request: PurgeRequest, val device: String?, val made: Long, val ready: PurgePlan.Ready)

    sealed interface Preview {
        data class Ready(val preview: PurgePreview) : Preview

        /** Something asked for isn't in the trash, isn't known, or an "unreferenced" file is referenced. */
        data class Refused(val reason: String) : Preview
    }

    sealed interface Commit {
        data class Done(val done: PurgeDone) : Commit

        /** Something the plan named moved since the preview: nothing was done; preview again. */
        data object Changed : Commit

        data object Expired : Commit

        data class Refused(val reason: String) : Commit
    }

    fun preview(request: PurgeRequest, device: String?): Preview {
        // A file held by nothing may still wait for its link (it can come first): only after thirty days.
        if (request.unreferenced.any { (placedAt(it) ?: 0) > now() - UNREFERENCED_MILLIS }) return Preview.Refused("too_new")
        val ready = when (val plan = db.locked { c -> plan(c, request) }) {
            is PurgePlan.Refused -> return Preview.Refused(refusal(plan))
            is PurgePlan.Ready -> plan
        }
        plans.entries.removeIf { now() - it.value.made > PLAN_MILLIS }
        val id = UUID.randomUUID().toString()
        plans[id] = Plan(request, device, now(), ready)
        return Preview.Ready(describe(id, ready))
    }

    @Synchronized
    fun commit(planId: String): Commit {
        val plan = plans[planId]?.takeIf { now() - it.made <= PLAN_MILLIS } ?: return Commit.Expired
        val id = UUID.randomUUID().toString()
        val at = now()
        val record = db.transaction { c ->
            // Counted again under the lock: exactly what the preview showed, or nothing.
            val fresh = when (val again = plan(c, plan.request)) {
                is PurgePlan.Refused -> return@transaction null
                is PurgePlan.Ready -> again
            }
            if (!sameAs(fresh, plan.ready)) return@transaction null
            apply(c, id, at, plan.device, fresh)
        } ?: return if (plan(plan.request).let { it is PurgePlan.Refused }) Commit.Refused("not_in_trash") else Commit.Changed
        plans.remove(planId)
        appendJournal(record)
        moveQueued()
        val bytes = record.files.sumOf { it.size }
        log.info(
            "Purge {}: {} entries, {} links, {} files ({} bytes)",
            id,
            record.entries.size,
            record.links.size,
            record.files.size,
            bytes,
        )
        return Commit.Done(PurgeDone(id, record.files.sumOf { it.size }, at + quarantineMillis))
    }

    private fun plan(request: PurgeRequest): PurgePlan = db.locked { c -> plan(c, request) }

    private fun plan(c: Connection, request: PurgeRequest): PurgePlan {
        val entries = readMediaEntries(c)
        val gone = c.createStatement().use { st ->
            st.executeQuery("SELECT id FROM entries WHERE json_extract(fields, '$.${Purging.FIELD}.value') = 'true'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
        val selection = PurgeSelection(
            entries = request.entries.filterTo(LinkedHashSet()) { it !in gone },
            attachments = request.attachments.mapTo(LinkedHashSet()) { AttachmentKey(it.entryId, it.attachmentId) },
            unreferenced = request.unreferenced.toSet(),
        )
        val sizes = blobSizes(c)
        return MediaRefs(entries).plan(selection) { sizes[it] }
    }

    private fun refusal(plan: PurgePlan.Refused): String = when {
        plan.entries.isNotEmpty() -> "not_in_trash"
        plan.attachments.isNotEmpty() -> "not_in_trash"
        else -> "referenced"
    }

    /** Two plans do the same: the same entries, links and files. */
    private fun sameAs(a: PurgePlan.Ready, b: PurgePlan.Ready) = a.entries.toSet() == b.entries.toSet() &&
        a.links.toSet() == b.links.toSet() &&
        a.files.map { it.sha256 }.toSet() == b.files.map { it.sha256 }.toSet()

    private fun describe(id: String, ready: PurgePlan.Ready): PurgePreview {
        val known = blobs.all().associateBy { it.sha256 }
        val metas = db.locked(::readMediaEntries).flatMap { e -> e.attachments.values.map { it.meta } }
        val metaOf = { sha: String -> metas.firstOrNull { it.sha256 == sha } }
        val (here, missing) = ready.files.partition { it.sha256 in known }
        return PurgePreview(
            planId = id,
            entries = ready.entries,
            links = ready.links.map { AttachmentRef(it.entryId, it.attachmentId) },
            files = here.map { f ->
                val meta = metaOf(f.sha256)
                PurgeFileInfo(f.sha256, known.getValue(f.sha256).size, meta?.kind, meta?.name)
            },
            bytes = here.sumOf { known.getValue(it.sha256).size },
            staying = ready.staying.map { s ->
                val size = s.size ?: known[s.sha256]?.size ?: 0
                StayingInfo(s.sha256, size, s.keptBy.map { it.entryId }.distinct(), s.keptBy.any { it.state == MediaState.Live })
            },
            notArrived = missing.map { it.sha256 },
            freesOn = now() + quarantineMillis,
        )
    }

    /** The commit's writes, inside its transaction; returns its journal line. */
    private fun apply(c: Connection, id: String, at: Long, device: String?, plan: PurgePlan.Ready): PurgeRecord {
        val links = plan.links.groupBy({ it.entryId }, { it.attachmentId })
        val before = ArrayList<PurgedLink>()
        for (entryId in (plan.entries + links.keys).distinct()) {
            val entry = store.entry(entryId) ?: continue
            val whole = entryId in plan.entries
            val going = if (whole) entry.attachments.keys else links[entryId].orEmpty()
            going.forEach { link -> entry.attachments[link]?.takeIf { !it.purged }?.let { before += PurgedLink(entryId, link, it) } }
            store.putEntry(Purging.apply(entry, going, whole, Purging.clockFor(entry, at)))
            store.appendChange(entryId)
        }
        val names = readMediaEntries(c).flatMap { it.attachments.values }.associate { it.meta.sha256 to it.meta.name }
        val files = plan.files.mapNotNull { f ->
            blobs.blob(f.sha256)?.let { b -> b to PurgedFile(b.sha256, b.size, b.path, names[b.sha256]) }
        }
        val record = PurgeRecord(id, at, device, plan.entries, before, files.map { it.second })
        c.prepareStatement("INSERT INTO purges(id, created_at, device_id, record) VALUES (?, ?, ?, ?)").use { st ->
            st.setString(1, id)
            st.setLong(2, at)
            st.setString(3, device)
            st.setString(4, json.encodeToString(PurgeRecord.serializer(), record))
            st.executeUpdate()
        }
        files.forEach { (blob, _) -> queue(c, id, at, blob, moved = null) }
        return record
    }

    /** A file leaves the blob table for the quarantine queue (where it moves once the commit is in). */
    private fun queue(c: Connection, purgeId: String, at: Long, blob: BlobStore.Blob, moved: Long?) {
        c.prepareStatement(
            "INSERT OR IGNORE INTO purged_files(sha256, purge_id, size, mime, path, quarantine, due_at, moved_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setString(1, blob.sha256)
            st.setString(2, purgeId)
            st.setLong(3, blob.size)
            st.setString(4, blob.mime)
            st.setString(5, blob.path)
            st.setString(6, "$QUARANTINE/${DAY.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))}/${blob.path}")
            st.setLong(7, at + quarantineMillis)
            if (moved != null) st.setLong(8, moved) else st.setNull(8, java.sql.Types.INTEGER)
            st.executeUpdate()
        }
        c.prepareStatement("DELETE FROM blobs WHERE sha256 = ?").use { st ->
            st.setString(1, blob.sha256)
            st.executeUpdate()
        }
    }

    private fun blobSizes(c: Connection): Map<String, Long> = c.createStatement().use { st ->
        st.executeQuery("SELECT sha256, size FROM blobs").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) } }
    }

    /** Files committed for the quarantine that haven't moved yet (after a crash too): moved now. Idempotent. */
    @Synchronized
    fun moveQueued() {
        for (row in rows("moved_at IS NULL AND deleted_at IS NULL AND back_at IS NULL")) {
            val from = blobs.root.resolve(row.path)
            val to = blobs.root.resolve(row.quarantine)
            when {
                from.exists() -> {
                    to.parent.createDirectories()
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }

                to.exists() -> Unit

                // moved before a crash, not marked yet

                else -> log.warn("Purged file {} was in neither {} nor {}", row.sha256, row.path, row.quarantine)
            }
            mark(row, "moved_at")
        }
    }

    /**
     * Quarantines that ended (at [now], or now for one freed at once): a file nothing references any more is deleted;
     * one referenced again goes back to the media folder (or, when it was uploaded again meanwhile, its copy here goes).
     */
    @Synchronized
    fun expire() {
        val due = rows("moved_at IS NOT NULL AND deleted_at IS NULL AND back_at IS NULL AND due_at <= ?", now().toString())
        if (due.isEmpty()) return
        val held = MediaRefs(db.locked(::readMediaEntries)).held
        for (row in due) {
            val copy = blobs.root.resolve(row.quarantine)
            when {
                row.sha256 !in held -> {
                    copy.deleteIfExists()
                    mark(row, "deleted_at")
                    log.info("Deleted for good: {} ({} bytes, purge {})", row.path, row.size, row.purgeId)
                }

                blobs.blob(row.sha256) != null -> {
                    copy.deleteIfExists()
                    mark(row, "back_at")
                }

                else -> {
                    putBack(row)
                    log.info("Attached again before its quarantine ended, back: {}", row.path)
                }
            }
        }
    }

    /** «Освободить сейчас» on the admin page: the purge's quarantine ends now. */
    @Synchronized
    fun freeNow(purgeId: String) {
        db.transaction { c ->
            c.prepareStatement(
                "UPDATE purged_files SET due_at = ? WHERE purge_id = ? AND deleted_at IS NULL AND back_at IS NULL",
            ).use { st ->
                st.setLong(1, now())
                st.setString(2, purgeId)
                st.executeUpdate()
            }
        }
        expire()
    }

    /**
     * Takes a purge back within its quarantine (the admin page; the only way out of `purged`): its files back in the
     * media folder, its links as they were with a fresh clock, its entries unmarked, an undo line in the journal.
     * Refused once any of its files is gone for good.
     */
    @Synchronized
    fun undo(purgeId: String): Boolean {
        val record = record(purgeId) ?: return false
        val files = rowsOf(purgeId)
        if (files.any { it.deletedAt != null } || undone(purgeId)) return false
        files.filter { it.backAt == null }.forEach(::putBack)
        undoInDatabase(record)
        appendJournal(PurgeRecord(record.id, now(), undo = true))
        log.info("Purge {} undone", purgeId)
        return true
    }

    private fun undoInDatabase(record: PurgeRecord) = db.transaction { c ->
        val links = record.links.groupBy { it.entryId }
        for (entryId in (record.entries + links.keys).distinct()) {
            val entry = store.entry(entryId) ?: continue
            val before = links[entryId].orEmpty().associate { it.attachmentId to it.before }
            store.putEntry(Purging.undo(entry, before, entryId in record.entries, Purging.clockFor(entry, now())))
            store.appendChange(entryId)
        }
        c.prepareStatement("UPDATE purges SET undone_at = ? WHERE id = ?").use { st ->
            st.setLong(1, now())
            st.setString(2, record.id)
            st.executeUpdate()
        }
    }

    /** A quarantined file back in the media folder and the blob table. */
    private fun putBack(row: FileRow) {
        val copy = blobs.root.resolve(row.quarantine)
        val home = blobs.root.resolve(row.path)
        if (blobs.blob(row.sha256) != null) {
            copy.deleteIfExists() // the same file was uploaded again meanwhile: it lives there
            mark(row, "back_at")
            return
        }
        if (copy.exists() && !home.exists()) {
            home.parent.createDirectories()
            Files.move(copy, home, StandardCopyOption.ATOMIC_MOVE)
        }
        db.transaction { c ->
            c.prepareStatement(
                "INSERT OR IGNORE INTO blobs(sha256, size, mime, path, created_at, verified_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { st ->
                st.setString(1, row.sha256)
                st.setLong(2, row.size)
                st.setString(3, row.mime)
                st.setString(4, row.path)
                st.setLong(5, now())
                st.setLong(6, now())
                st.executeUpdate()
            }
            c.prepareStatement("UPDATE purged_files SET back_at = ? WHERE sha256 = ? AND purge_id = ?").use { st ->
                st.setLong(1, now())
                st.setString(2, row.sha256)
                st.setString(3, row.purgeId)
                st.executeUpdate()
            }
        }
    }

    /**
     * At every start, before anything is served: every journal line the database lacks is done again (a restore went back
     * to a snapshot from before it), and every purge the database has but the journal lacks (a crash between the commit
     * and the line) gets its line. Then files committed but not moved move. Idempotent.
     */
    @Synchronized
    fun replay() {
        val lines = if (journal.exists()) {
            journal.readLines().filter { it.isNotBlank() }.mapNotNull {
                runCatching { json.decodeFromString(PurgeRecord.serializer(), it) }.getOrNull()
            }
        } else {
            emptyList()
        }
        val known = lines.filter { !it.undo }.map { it.id }.toSet()
        for (line in lines) {
            if (line.undo) {
                val record = record(line.id)
                if (record != null && !undone(line.id)) {
                    rowsOf(line.id).filter { it.backAt == null && it.deletedAt == null }.forEach(::putBack)
                    undoInDatabase(record)
                    log.warn("Purge {} undone again after a restore", line.id)
                }
            } else if (record(line.id) == null) {
                again(line)
                log.warn("Purge {} done again after a restore", line.id)
            }
        }
        db.locked { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT record FROM purges ORDER BY created_at").use { rs ->
                    buildList { while (rs.next()) add(json.decodeFromString(PurgeRecord.serializer(), rs.getString(1))) }
                }
            }
        }.filter { it.id !in known }.forEach(::appendJournal)
        moveQueued()
    }

    /** A purge the restored database doesn't know, done again from its journal line. */
    private fun again(record: PurgeRecord) = db.transaction { c ->
        val links = record.links.groupBy({ it.entryId }, { it.attachmentId })
        for (entryId in (record.entries + links.keys).distinct()) {
            val entry = store.entry(entryId) ?: continue
            val whole = entryId in record.entries
            val going = if (whole) entry.attachments.keys else links[entryId].orEmpty()
            store.putEntry(Purging.apply(entry, going, whole, Purging.clockFor(entry, now())))
            store.appendChange(entryId)
        }
        c.prepareStatement("INSERT INTO purges(id, created_at, device_id, record) VALUES (?, ?, ?, ?)").use { st ->
            st.setString(1, record.id)
            st.setLong(2, record.at)
            st.setString(3, record.device)
            st.setString(4, json.encodeToString(PurgeRecord.serializer(), record))
            st.executeUpdate()
        }
        for (file in record.files) {
            val blob = blobs.blob(file.sha256)
            if (blob != null) {
                queue(c, record.id, record.at, blob, moved = null)
            } else {
                // Not in the restored table: it is in the quarantine already, or gone for good.
                val held = BlobStore.Blob(file.sha256, file.size, "application/octet-stream", file.path)
                queue(c, record.id, record.at, held, moved = now())
            }
        }
    }

    /**
     * The backup mirror follows (ADR-041): a copy there goes only once the journal names it, its quarantine has ended
     * with the file deleted here, and its hash matches; anything else on the mirror is never touched.
     */
    @Synchronized
    fun mirrorDeletions(mirror: Path) {
        for (row in rows("deleted_at IS NOT NULL AND mirror_deleted_at IS NULL")) {
            val copy = mirror.resolve("media").resolve(row.path)
            when {
                !copy.exists() -> Unit

                BlobStore.sha256(copy) == row.sha256 -> {
                    copy.deleteIfExists()
                    log.info("Deleted for good from the mirror: {}", row.path)
                }

                else -> log.warn("Mirror copy {} doesn't match purged {}: left as it is", row.path, row.sha256)
            }
            mark(row, "mirror_deleted_at")
        }
    }

    /** Files no entry references, placed thirty days ago or more, the heaviest first («Место»'s «Без записи»). */
    fun unreferenced(): List<UnreferencedFile> = db.locked { c ->
        val held = MediaRefs(readMediaEntries(c)).held
        c.createStatement().use { st ->
            st.executeQuery("SELECT sha256, size, mime, path, created_at FROM blobs ORDER BY size DESC").use { rs ->
                buildList {
                    while (rs.next()) {
                        val sha = rs.getString(1)
                        val since = rs.getLong(5)
                        if (sha !in held &&
                            since <= now() - UNREFERENCED_MILLIS
                        ) {
                            add(UnreferencedFile(sha, rs.getLong(2), rs.getString(3), rs.getString(4), since))
                        }
                    }
                }
            }
        }
    }

    private fun placedAt(sha256: String): Long? = db.locked { c ->
        c.prepareStatement("SELECT created_at FROM blobs WHERE sha256 = ?").use { st ->
            st.setString(1, sha256)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

    /** Refuses an upload of a file whose every link is purged (the device drops its copy). */
    fun refusesUpload(sha256: String): Boolean {
        val purged = db.locked { c ->
            c.prepareStatement("SELECT 1 FROM purged_files WHERE sha256 = ? AND back_at IS NULL LIMIT 1").use { st ->
                st.setString(1, sha256)
                st.executeQuery().use { it.next() }
            }
        }
        return purged && sha256 !in MediaRefs(db.locked(::readMediaEntries)).held
    }

    /** Paths in the media folder the start's adoption must leave alone: committed purges whose files haven't moved yet. */
    fun pendingPaths(): Set<String> = rows("moved_at IS NULL AND deleted_at IS NULL AND back_at IS NULL").mapTo(HashSet()) { it.path }

    /** A purge still in its quarantine, for the admin page and «Место». */
    data class Waiting(val id: String, val at: Long, val dueAt: Long, val files: Int, val bytes: Long, val names: List<String>)

    fun waiting(): List<Waiting> = rows("moved_at IS NOT NULL AND deleted_at IS NULL AND back_at IS NULL").groupBy {
        it.purgeId
    }.map { (id, files) ->
        val record = record(id)
        val names = record?.files?.mapNotNull { it.name }.orEmpty()
        Waiting(id, record?.at ?: 0, files.minOf { it.dueAt }, files.size, files.sumOf { it.size }, names)
    }.sortedBy { it.dueAt }

    private class FileRow(
        val sha256: String,
        val purgeId: String,
        val size: Long,
        val mime: String,
        val path: String,
        val quarantine: String,
        val dueAt: Long,
        val deletedAt: Long?,
        val backAt: Long?,
    )

    private fun rowsOf(purgeId: String): List<FileRow> = rows("purge_id = ?", purgeId)

    /** Rows of the queue matching [where], a fixed condition with [arg] as its only parameter (if any). */
    private fun rows(where: String, arg: String? = null): List<FileRow> = db.locked { c ->
        c.prepareStatement(
            "SELECT sha256, purge_id, size, mime, path, quarantine, due_at, deleted_at, back_at FROM purged_files WHERE $where",
        ).use { st ->
            if (arg != null) st.setString(1, arg)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            FileRow(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getLong(7),
                                rs.getLong(8).takeIf { !rs.wasNull() }, rs.getLong(9).takeIf { !rs.wasNull() },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun mark(row: FileRow, column: String) = db.transaction { c ->
        c.prepareStatement("UPDATE purged_files SET $column = ? WHERE sha256 = ? AND purge_id = ?").use { st ->
            st.setLong(1, now())
            st.setString(2, row.sha256)
            st.setString(3, row.purgeId)
            st.executeUpdate()
        }
    }

    private fun record(id: String): PurgeRecord? = db.locked { c ->
        c.prepareStatement("SELECT record FROM purges WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) json.decodeFromString(PurgeRecord.serializer(), rs.getString(1)) else null }
        }
    }

    private fun undone(id: String): Boolean = db.locked { c ->
        c.prepareStatement("SELECT undone_at FROM purges WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> rs.next() && rs.getLong(1).let { !rs.wasNull() } }
        }
    }

    /** One line, flushed to the disk before the answer goes. */
    @Synchronized
    private fun appendJournal(record: PurgeRecord) {
        journal.parent.createDirectories()
        FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = (json.encodeToString(PurgeRecord.serializer(), record) + "\n").toByteArray()
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    /** The entries a purge left, for tests and the admin page. */
    internal fun entry(id: String): EntryState? = store.entry(id)

    companion object {
        const val QUARANTINE = ".purged"
        const val QUARANTINE_MILLIS = 7L * 24 * 3_600_000
        const val PLAN_MILLIS = 10L * 60_000

        /** A file held by nothing is offered for deleting only after this long (it may come before its link). */
        const val UNREFERENCED_MILLIS = 30L * 24 * 3_600_000
        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
