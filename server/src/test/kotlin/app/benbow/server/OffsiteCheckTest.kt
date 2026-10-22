package app.benbow.server

import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SyncRules
import app.benbow.server.backup.BackupJobs
import app.benbow.server.offsite.AgeFiles
import app.benbow.server.offsite.OffsiteCopy
import app.benbow.server.offsite.OffsiteFolder
import app.benbow.server.offsite.OffsiteKey
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.SqliteSyncStore
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The check of the copy outside the house (ADR-043, E3): opened with the key, compared with home, bad files written again. */
class OffsiteCheckTest {
    private val data = Files.createTempDirectory("benbow-offsite-check")
    private val db = Database.open(data, "test")
    private val blobs = BlobStore(db, data.resolve("media"))
    private val root = Files.createTempDirectory("benbow-cloud").resolve("Benbow")
    private val copy = OffsiteCopy(db, data, root, blobs)
    private val key = OffsiteKey.generate().also { it.save(copy.keyFile) }
    private val clock = 1_792_000_000_000L
    private val day = OffsiteCopy.CHECK_EVERY
    private val photos = ArrayList<BlobStore.Blob>()

    init {
        ServerConfig.load(data)
    }

    /** An entry with [count] photos, then a snapshot and the export at home. */
    private fun fill(count: Int) {
        val metas = (1..count).map { seed ->
            val bytes = Random(seed).nextBytes(5_000 + seed)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val upload = assertIs<BlobStore.Start.Upload>(blobs.start(sha, bytes.size.toLong(), "image/jpeg", "p$seed.jpg", clock, null))
            photos += assertIs<BlobStore.Chunk.Accepted>(blobs.append(upload.id, 0, bytes)).blob!!
            AttachmentMeta("p$seed", sha, 1, "image/jpeg", "image")
        }
        SyncRules(SqliteSyncStore(db, "phone")).push(
            listOf(CreateEntry("c", "e", "journal", "2026-10-22", 1)) +
                metas.mapIndexed { i, m -> PutAttachment("a$i", "e", m, 1.0, hlc = Hlc(i + 1L, 0, "phone")) },
        )
        BackupJobs(db, data, null, blobs).runOnce(clock)
    }

    private fun mediaCopy(blob: BlobStore.Blob): Path = key.mediaName(blob.sha256).let {
        root.resolve("media").resolve(it.take(2)).resolve("$it.age")
    }

    private fun snapshotCopy(): Path = root.resolve("db").resolve("${db.backups!!.history().last().file}.age")

    private fun passes() =
        Json.decodeFromString(OffsiteFolder.Passes.serializer(), data.resolve("backups").resolve(OffsiteFolder.PASSES).readText())

    /** One byte changed in the middle: the size stays, so only a check can tell. */
    private fun spoil(file: Path) = file.writeBytes(file.readBytes().apply { this[size / 2] = (this[size / 2] + 1).toByte() })

    private fun check(at: Long) = assertNotNull(assertIs<OffsiteCopy.Outcome.Done>(copy.pass(at)).check, "a check ran at $at")

    @Test
    fun aGoodCopyChecksOutAfterNewSnapshotsAndOnceADay() {
        fill(2)
        val first = check(clock)
        assertTrue(first.ok, first.toString())
        assertTrue(first.checked >= 6, "the snapshot, its manifest, the export, the settings and both photos: ${first.checked}")
        assertEquals(clock, passes().checkedAt)
        assertNull(assertIs<OffsiteCopy.Outcome.Done>(copy.pass(clock + 1)).check, "nothing new, checked today")
        assertTrue(check(clock + day).ok)
        assertEquals(clock + day, passes().checkedAt)
    }

    @Test
    fun aChangedByteThereIsFoundRemovedAndWrittenAgainFromHome() {
        fill(3)
        copy.pass(clock)
        spoil(mediaCopy(photos[0]))
        spoil(root.resolve("export.zip.age"))
        val found = check(clock + day)
        assertFalse(found.ok)
        assertFalse(found.held)
        assertEquals(2, found.bad.size, found.toString())
        assertFalse(mediaCopy(photos[0]).exists() || root.resolve("export.zip.age").exists(), "removed, to be written again")
        assertEquals(clock, passes().checkedAt, "the last good check stays the last good check")
        assertTrue("записаны заново" in passes().checkProblem.orEmpty(), passes().checkProblem)

        val again = assertIs<OffsiteCopy.Outcome.Done>(copy.pass(clock + day + 1))
        assertEquals(1, again.files)
        assertTrue(again.check!!.ok, "checked again at once, since the last one wasn't good: ${again.check}")
        assertEquals(photos[0].sha256, AgeFiles.sha256(mediaCopy(photos[0]), key))
        assertNull(passes().checkProblem)
    }

    @Test
    fun aSnapshotThereThatIsNotTheOneAtHomeIsFound() {
        fill(1)
        copy.pass(clock)
        // Something of the same size that opens fine: only comparing with home tells.
        val snapshot = snapshotCopy()
        val home = db.backups!!.directory.resolve(db.backups!!.history().last().file)
        AgeFiles.encrypt(Random(5).nextBytes(home.fileSize().toInt()), snapshot, key)
        val found = check(clock + day)
        assertEquals(listOf("db/${snapshot.fileName}"), found.bad)
        assertFalse(snapshot.exists())
        assertTrue(assertIs<OffsiteCopy.Outcome.Done>(copy.pass(clock + day + 1)).check!!.ok)
        assertEquals(BlobStore.sha256(home), AgeFiles.sha256(snapshot, key))
    }

    @Test
    fun manyBadAtOnceIsSaidAndNothingIsRemoved() {
        fill(6)
        copy.pass(clock)
        photos.forEach { spoil(mediaCopy(it)) }
        spoil(snapshotCopy())
        val found = check(clock + day)
        assertTrue(found.held, found.toString())
        assertTrue(photos.all { mediaCopy(it).exists() } && snapshotCopy().exists(), "a disk or a sync gone wrong: nothing removed")
        assertTrue("ничего не удалено" in passes().checkProblem.orEmpty(), passes().checkProblem)
    }
}
