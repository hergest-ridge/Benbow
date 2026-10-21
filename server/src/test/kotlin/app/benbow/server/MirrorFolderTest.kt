package app.benbow.server

import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.SyncRules
import app.benbow.server.backup.BackupJobs
import app.benbow.server.backup.MirrorFolder
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.SqliteSyncStore
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The second disk's mark and passes (ADR-042): copies go only into this store's folder, and a gone one is never made again. */
class MirrorFolderTest {
    private val data = Files.createTempDirectory("benbow-mirror-data")
    private val db = Database.open(data, "test")
    private val blobs = BlobStore(db, data.resolve("media"))
    private val mirror = Files.createTempDirectory("benbow-mirror-disk").resolve("Benbow-mirror")
    private var changes = 0

    private fun change() {
        changes++
        SyncRules(SqliteSyncStore(db)).push(listOf(CreateEntry("o$changes", "entry-$changes", "journal", "2026-10-21", 1)))
    }

    private fun photo(seed: Int): BlobStore.Blob {
        val bytes = Random(seed).nextBytes(1_500)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val upload = assertIs<BlobStore.Start.Upload>(blobs.start(sha, bytes.size.toLong(), "image/jpeg", "p.jpg", 1_792_000_000_000, null))
        return assertNotNull(assertIs<BlobStore.Chunk.Accepted>(blobs.append(upload.id, 0, bytes)).blob)
    }

    @Test
    fun theFirstPassMarksTheMirrorAndItsPassesOutliveARestart() {
        change()
        val blob = photo(1)
        val jobs = BackupJobs(db, data, mirror, blobs, disks = TestDisks())
        val now = System.currentTimeMillis()
        jobs.runOnce(now)
        assertTrue("store=${db.storeId}" in mirror.resolve(MirrorFolder.MARK).readText())
        assertTrue(mirror.resolve("media").resolve(blob.path).exists())
        assertEquals(now, jobs.status.lastMirrorAt)
        assertNull(jobs.status.mirrorProblem)
        assertEquals(now, BackupJobs(db, data, mirror, blobs, disks = TestDisks()).status.lastMirrorAt, "a restart doesn't forget it")
    }

    @Test
    fun aMirrorOnceFilledIsNeverMadeAgainWhenItsDiskIsGone() {
        change()
        val jobs = BackupJobs(db, data, mirror, blobs, disks = TestDisks())
        jobs.runOnce()
        Files.move(mirror, mirror.resolveSibling("unplugged"))
        change()
        Thread.sleep(1_100) // snapshot names have second resolution
        assertTrue(jobs.runOnce(), "the snapshot here is taken all the same")
        assertFalse(mirror.exists(), "nothing made the folder again")
        assertTrue("не подключён" in jobs.status.mirrorProblem.orEmpty(), jobs.status.mirrorProblem)
        assertNull(jobs.status.lastError)
    }

    @Test
    fun aFolderOfOtherThingsOrOfAnotherStoreGetsNoCopy() {
        mirror.createDirectories().resolve("отпуск.jpg").writeBytes(Random(2).nextBytes(100))
        change()
        val jobs = BackupJobs(db, data, mirror, blobs, disks = TestDisks())
        jobs.runOnce()
        assertEquals(listOf("отпуск.jpg"), mirror.listDirectoryEntries().map { it.name })
        assertTrue("чужие файлы" in jobs.status.mirrorProblem.orEmpty(), jobs.status.mirrorProblem)
        assertNull(jobs.status.lastMirrorAt)

        val foreign = Files.createTempDirectory("benbow-foreign")
        foreign.resolve(MirrorFolder.MARK).writeText("store=someone-else\n")
        val other = BackupJobs(db, data, foreign, blobs, disks = TestDisks())
        other.runOnce()
        assertEquals(listOf(MirrorFolder.MARK), foreign.listDirectoryEntries().map { it.name })
        assertTrue("другому хранилищу" in other.status.mirrorProblem.orEmpty(), other.status.mirrorProblem)
    }

    @Test
    fun aMirrorFromBeforeMarksIsKnownByItsSnapshots() {
        change()
        BackupJobs(db, data, mirror, blobs, disks = TestDisks()).runOnce()
        // As a 0.12 server left it: copies, but no mark and no record of its passes.
        mirror.resolve(MirrorFolder.MARK).deleteExisting()
        data.resolve("backups").resolve(MirrorFolder.PASSES).deleteExisting()
        change()
        Thread.sleep(1_100)
        val jobs = BackupJobs(db, data, mirror, blobs, disks = TestDisks())
        jobs.runOnce()
        assertTrue(mirror.resolve(MirrorFolder.MARK).exists())
        assertNull(jobs.status.mirrorProblem)
        assertEquals(2, mirror.resolve("db").listDirectoryEntries("*.sqlite").size)
    }

    @Test
    fun withoutTheMediaFolderTheSnapshotsStillGoToTheMirror() {
        change()
        photo(3)
        Files.move(data.resolve("media"), data.resolve("unplugged"))
        val jobs = BackupJobs(db, data, mirror, blobs, disks = TestDisks())
        jobs.runOnce()
        assertEquals(1, mirror.resolve("db").listDirectoryEntries("*.sqlite").size)
        assertFalse(mirror.resolve("media").exists())
        assertNull(jobs.status.lastMirrorAt)
        assertNotNull(jobs.status.mirrorDbAt)
        assertTrue("медиафайлы нет" in jobs.status.mirrorProblem.orEmpty(), jobs.status.mirrorProblem)
        assertNull(jobs.status.lastError, "a missing folder is not a corrupt file")
    }
}
