package app.benbow.server

import app.benbow.core.api.AttachmentRef
import app.benbow.core.api.PurgeRecord
import app.benbow.core.api.PurgeRequest
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.Purging
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncRules
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Purges
import app.benbow.server.storage.SqliteSyncStore
import app.benbow.testing.TombstoneSimulation
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deleting for good (ADR-041, data-safety "Purge"): the plan, the commit, the quarantine, the undo, restores, the mirror. */
class PurgeTest {
    private val dir = Files.createTempDirectory("benbow-purge")
    private var db = Database.open(dir, "test")
    private var blobs = BlobStore(db, dir.resolve("media"))
    private val journal = dir.resolve("db").resolve(PURGE_JOURNAL)
    private var clock = 1_792_000_000_000L
    private var purges = Purges(db, blobs, journal, now = { clock })
    private var hlc = 1L
    private val week = Purges.QUARANTINE_MILLIS

    private fun rules() = SyncRules(SqliteSyncStore(db, "phone"))

    private fun file(size: Int, seed: Int): Pair<String, ByteArray> {
        val bytes = Random(seed).nextBytes(size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val upload = blobs.start(sha, size.toLong(), "video/mp4", "f$seed.mp4", clock, null) as BlobStore.Start.Upload
        blobs.append(upload.id, 0, bytes)
        return sha to bytes
    }

    private fun meta(id: String, sha: String, kind: String = "video") = AttachmentMeta(id, sha, 1, "video/mp4", kind, name = "$id.mp4")

    private fun entry(id: String, vararg links: AttachmentMeta, trashed: Boolean = false) {
        rules().push(
            buildList {
                add(CreateEntry("c-$id", id, "journal", "2026-10-21", 1))
                links.forEach { add(PutAttachment("a-$id-${it.id}", id, it, 1.0, hlc = Hlc(hlc++, 0, "phone"))) }
                if (trashed) add(SetField("t-$id", id, "deleted", "true", Hlc(hlc++, 0, "phone")))
            },
        )
    }

    private fun link(entry: String, id: String) = SqliteSyncStore(db).entry(entry)!!.attachments.getValue(id)

    private fun ready(request: PurgeRequest) = assertIs<Purges.Preview.Ready>(purges.preview(request, "phone")).preview

    private fun purgeTrashedEntry(): Triple<String, String, ByteArray> {
        val (photo, _) = file(2_000, 1)
        val (video, bytes) = file(9_000, 2)
        entry("live", meta("p1", photo, "image"))
        entry("old", meta("p2", photo, "image"), meta("v", video), trashed = true)
        val preview = ready(PurgeRequest(entries = listOf("old")))
        assertEquals(listOf(video), preview.files.map { it.sha256 }, "only the video goes")
        assertEquals(listOf(photo to true), preview.staying.map { it.sha256 to it.live }, "the photo stays: a live entry holds it")
        assertEquals(9_000, preview.bytes)
        val done = assertIs<Purges.Commit.Done>(purges.commit(preview.planId)).done
        return Triple(done.purgeId, video, bytes)
    }

    @Test
    fun onlyTheTrashCanBePurged() {
        val (sha, _) = file(100, 3)
        entry("live", meta("a", sha))
        assertIs<Purges.Preview.Refused>(purges.preview(PurgeRequest(entries = listOf("live")), null))
        assertIs<Purges.Preview.Refused>(purges.preview(PurgeRequest(attachments = listOf(AttachmentRef("live", "a"))), null))
        assertIs<Purges.Preview.Refused>(purges.preview(PurgeRequest(unreferenced = listOf(sha)), null))
        assertNotNull(blobs.blob(sha))
    }

    @Test
    fun aCommitDoesExactlyThePlanAndTheFileWaitsInTheQuarantine() {
        val (id, video, bytes) = purgeTrashedEntry()
        val old = SqliteSyncStore(db).entry("old")!!
        assertTrue(Purging.isPurged(old) && old.attachments.values.all { it.purged && !it.present })
        assertFalse(link("live", "p1").purged, "the live entry's photo is untouched")
        assertNull(blobs.blob(video), "the server serves it no more")
        val quarantined = Files.walk(blobs.root.resolve(Purges.QUARANTINE)).use { s ->
            s.filter { Files.isRegularFile(it) }.toList()
        }.single()
        assertContentEquals(bytes, quarantined.readBytes())
        val line = Json.decodeFromString(PurgeRecord.serializer(), journal.readLines().single())
        assertEquals(id, line.id)
        assertEquals(listOf(video), line.files.map { it.sha256 })
        assertEquals(0, blobs.adoptUntracked(purges.pendingPaths()), "the quarantine is never adopted")
        assertEquals(listOf(id), purges.waiting().map { it.id })
    }

    @Test
    fun anythingThatMovedSinceThePreviewStopsTheCommit() {
        val (video, _) = file(9_000, 2)
        entry("old", meta("v", video), trashed = true)
        val preview = ready(PurgeRequest(entries = listOf("old")))
        // Meanwhile another device attaches the same video to a live entry: it must stay.
        entry("new", meta("v2", video))
        assertEquals(Purges.Commit.Changed, purges.commit(preview.planId))
        assertNotNull(blobs.blob(video))
        assertFalse(link("old", "v").purged, "nothing was done")
        assertEquals(Purges.Commit.Expired, purges.commit("no-such-plan"))
        val again = ready(PurgeRequest(entries = listOf("old")))
        assertEquals(emptyList(), again.files)
        clock += Purges.PLAN_MILLIS + 1
        assertEquals(Purges.Commit.Expired, purges.commit(again.planId))
    }

    @Test
    fun aLateReturnIsAbsorbedAndAnUploadOfThePurgedFileIsRefused() {
        val (_, video, _) = purgeTrashedEntry()
        rules().push(listOf(PutAttachment("back", "old", meta("v", video), 1.0, present = true, hlc = Hlc(Long.MAX_VALUE / 2, 0, "phone"))))
        assertTrue(link("old", "v").purged)
        assertTrue(purges.refusesUpload(video))
        // Attached again to a live entry, the file is wanted again: uploads go through.
        entry("again", meta("v3", video))
        assertFalse(purges.refusesUpload(video))
    }

    @Test
    fun whenTheQuarantineEndsAFileGoesUnlessItWasAttachedAgain() {
        val (_, video, bytes) = purgeTrashedEntry()
        clock += week - 1
        purges.expire()
        assertEquals(1, purges.waiting().size, "a day short of the week: still waiting")
        // Attached again before the end: it comes back to the media folder at its old place.
        entry("again", meta("v3", video))
        clock += 2
        purges.expire()
        val back = assertNotNull(blobs.blob(video))
        assertContentEquals(bytes, blobs.file(back).readBytes())
        assertEquals(emptyList(), purges.waiting())
    }

    @Test
    fun anUnreferencedFileIsDeletedForGoodAfterItsWeek() {
        val (_, video, _) = purgeTrashedEntry()
        val copy = Files.walk(blobs.root.resolve(Purges.QUARANTINE)).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.single()
        clock += week + 1
        purges.expire()
        assertFalse(copy.exists())
        assertNull(blobs.blob(video))
        assertFalse(purges.undo(purges.waiting().firstOrNull()?.id ?: "x"), "nothing left to undo")
    }

    @Test
    fun theUndoBringsLinksEntryAndFileBackAndIsJournaled() {
        val (id, video, bytes) = purgeTrashedEntry()
        val before = SqliteSyncStore(db).entry("old")!!.attachments.getValue("v")
        assertTrue(purges.undo(id))
        val old = SqliteSyncStore(db).entry("old")!!
        assertFalse(Purging.isPurged(old))
        assertFalse(old.attachments.getValue("v").purged)
        assertTrue(old.attachments.getValue("v").hlc > before.hlc, "a fresh clock")
        assertContentEquals(bytes, blobs.file(blobs.blob(video)!!).readBytes())
        assertTrue(Json.decodeFromString(PurgeRecord.serializer(), journal.readLines().last()).undo)
        assertFalse(purges.undo(id), "once")
        // The entry is in the trash again, as it was: back from there as always.
        assertEquals("true", old.fields["deleted"]?.value)
    }

    @Test
    fun aRestoreToASnapshotFromBeforeThePurgeDoesItAgain() {
        val (video, _) = file(9_000, 2)
        entry("old", meta("v", video), trashed = true)
        val snapshot = dir.resolve("snapshot.sqlite")
        db.locked { c -> c.createStatement().use { it.execute("VACUUM INTO '${snapshot.toString().replace("\\", "/")}'") } }
        val preview = ready(PurgeRequest(entries = listOf("old")))
        assertIs<Purges.Commit.Done>(purges.commit(preview.planId))

        // The database goes back to the snapshot; the media folder, its quarantine and the journal stay as they were.
        db.close()
        val file = dir.resolve("db").resolve("benbow.sqlite")
        listOf("-wal", "-shm").forEach { dir.resolve("db").resolve("benbow.sqlite$it").deleteIfExists() }
        snapshot.copyTo(file, overwrite = true)
        db = Database.open(dir, "test")
        blobs = BlobStore(db, dir.resolve("media"))
        purges = Purges(db, blobs, journal, now = { clock })
        assertFalse(link("old", "v").purged, "the snapshot is from before")
        purges.replay()
        assertTrue(link("old", "v").purged && Purging.isPurged(SqliteSyncStore(db).entry("old")!!), "done again from the journal")
        assertNull(blobs.blob(video))
        assertEquals(0, blobs.adoptUntracked(purges.pendingPaths()))
        assertEquals(1, purges.waiting().size)
    }

    @Test
    fun aCrashBeforeTheFilesMovedIsFinishedAtTheNextStart() {
        val (_, video, bytes) = purgeTrashedEntry()
        val copy = Files.walk(blobs.root.resolve(Purges.QUARANTINE)).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.single()
        // As if the server died right after the commit: the file still at home, its move not marked.
        val home = blobs.root.resolve(Json.decodeFromString(PurgeRecord.serializer(), journal.readLines().single()).files.single().path)
        Files.move(copy, home)
        db.locked { c -> c.createStatement().use { it.executeUpdate("UPDATE purged_files SET moved_at = NULL") } }
        assertEquals(0, blobs.adoptUntracked(purges.pendingPaths()), "never adopted while it is on its way out")
        purges.replay()
        purges.replay() // idempotent
        assertContentEquals(bytes, copy.readBytes())
        assertFalse(home.exists())
        assertNull(blobs.blob(video))
    }

    @Test
    fun theMirrorDeletesOnlyWhatTheJournalNamesAfterTheQuarantineWithTheHashMatching() {
        val mirror = dir.resolve("mirror")
        val (_, video, bytes) = purgeTrashedEntry()
        val path = Json.decodeFromString(PurgeRecord.serializer(), journal.readLines().single()).files.single().path
        val copy = mirror.resolve("media").resolve(path).also { it.parent.createDirectories() }
        copy.writeBytes(bytes)
        val stranger = mirror.resolve("media").resolve("2026/10/other.jpg").also { it.parent.createDirectories() }
        stranger.writeBytes(byteArrayOf(1, 2, 3))
        purges.mirrorDeletions(mirror)
        assertTrue(copy.exists(), "not before the quarantine ended")
        clock += week + 1
        purges.expire()
        purges.mirrorDeletions(mirror)
        assertFalse(copy.exists())
        assertTrue(stranger.exists(), "a file the journal doesn't name is never touched")
        assertNull(blobs.blob(video))
    }

    @Test
    fun aMirrorCopyThatDoesNotMatchIsLeftAlone() {
        val mirror = dir.resolve("mirror")
        purgeTrashedEntry()
        val path = Json.decodeFromString(PurgeRecord.serializer(), journal.readLines().single()).files.single().path
        val copy = mirror.resolve("media").resolve(path).also { it.parent.createDirectories() }
        copy.writeBytes(byteArrayOf(9, 9, 9))
        clock += week + 1
        purges.expire()
        purges.mirrorDeletions(mirror)
        assertTrue(copy.exists())
    }

    @Test
    fun purgedLinksStayPurgedOnTheSqliteStoreToo() {
        val simulation = TombstoneSimulation { SqliteSyncStore(Database.inMemory()) }
        repeat(60) { simulation.run(seed = it.toLong()) }
        assertTrue(simulation.purges > 20)
    }
}
