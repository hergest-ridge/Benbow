package app.benbow.server

import app.benbow.core.api.PurgeRequest
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncRules
import app.benbow.server.backup.BackupJobs
import app.benbow.server.backup.RecoveryKit
import app.benbow.server.offsite.AgeFiles
import app.benbow.server.offsite.OffsiteCopy
import app.benbow.server.offsite.OffsiteKey
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Purges
import app.benbow.server.storage.SqliteSyncStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pass of the copy outside the house (ADR-043, E2): what goes there, encrypted, once, and what never does. */
class OffsiteCopyTest {
    private val data = Files.createTempDirectory("benbow-offsite-data")
    private val db = Database.open(data, "test")
    private val blobs = BlobStore(db, data.resolve("media"))
    private var clock = 1_792_000_000_000L
    private val purges = Purges(db, blobs, data.resolve("db").resolve(PURGE_JOURNAL), now = { clock })
    private val root = Files.createTempDirectory("benbow-cloud").resolve("Benbow")
    private val copy = OffsiteCopy(db, data, root, blobs, purges = purges)
    private val key = OffsiteKey.generate().also { it.save(copy.keyFile) }
    private val jobs = BackupJobs(db, data, null, blobs, purges = purges)
    private var hlc = 1L

    init {
        ServerConfig.load(data)
    }

    private fun rules() = SyncRules(SqliteSyncStore(db, "phone"))

    private fun photo(seed: Int, size: Int = 3_000): BlobStore.Blob {
        val bytes = Random(seed).nextBytes(size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val upload = assertIs<BlobStore.Start.Upload>(blobs.start(sha, size.toLong(), "image/jpeg", "p$seed.jpg", clock, null))
        return assertIs<BlobStore.Chunk.Accepted>(blobs.append(upload.id, 0, bytes)).blob!!
    }

    private fun entry(id: String, vararg photos: BlobStore.Blob, trashed: Boolean = false) {
        rules().push(
            buildList {
                add(CreateEntry("c-$id", id, "journal", "2026-10-22", 1))
                photos.forEachIndexed { i, b ->
                    add(
                        PutAttachment(
                            "a-$id-$i",
                            id,
                            AttachmentMeta("p$i", b.sha256, 1, "image/jpeg", "image"),
                            1.0,
                            hlc = Hlc(hlc++, 0, "phone"),
                        ),
                    )
                }
                if (trashed) add(SetField("t-$id", id, "deleted", "true", Hlc(hlc++, 0, "phone")))
            },
        )
    }

    private fun mediaCopy(blob: BlobStore.Blob): Path = key.mediaName(blob.sha256).let {
        root.resolve("media").resolve(it.take(2)).resolve("$it.age")
    }

    private fun done(outcome: OffsiteCopy.Outcome) = assertIs<OffsiteCopy.Outcome.Done>(outcome).copy(check = null)

    private fun files(): List<Path> = Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.toList() }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { z ->
        generateSequence { z.nextEntry }.associate { it.name to z.readBytes() }
    }

    @Test
    fun aFirstPassFillsAnEncryptedCopyThatOpensWithTheKeyAlone() {
        val a = photo(1)
        val b = photo(2, 200_000)
        entry("e1", a, b)
        jobs.runOnce(clock)
        val outcome = assertIs<OffsiteCopy.Outcome.Done>(copy.pass(clock))
        assertEquals(OffsiteCopy.Outcome.Done(1, 2, true), outcome.copy(check = null))
        assertTrue(outcome.check!!.ok, outcome.check.toString())

        val mark = root.resolve(".benbow-offsite").readText()
        assertTrue("store=${db.storeId}" in mark && "recipient=${key.recipient}" in mark, mark)
        for (file in files().filter { it.name != ".benbow-offsite" }) {
            assertTrue(file.readBytes().copyOf(21).decodeToString() == "age-encryption.org/v1", "$file is encrypted")
        }
        val snapshot = db.backups!!.history().single()
        val home = db.backups!!.directory.resolve(snapshot.file)
        assertContentEquals(home.readBytes(), AgeFiles.decrypt(root.resolve("db").resolve("${snapshot.file}.age").readBytes(), key))
        assertContentEquals(db.backups!!.manifest.readBytes(), AgeFiles.decrypt(root.resolve("db/manifest.json.age").readBytes(), key))
        for (blob in listOf(a, b)) assertEquals(blob.sha256, AgeFiles.sha256(mediaCopy(blob), key))
        assertTrue(files().none { f -> listOf(a, b).any { it.sha256 in f.toString() } }, "no file's hash shows in the copy")
        val export = unzip(AgeFiles.decrypt(root.resolve("export.zip.age").readBytes(), key))
        assertTrue(export.keys.any { it.endsWith(".md") }, export.keys.toString())
        assertTrue(export.keys.none { it.substringAfterLast('/').startsWith(".") }, "the export's own state stays home")
        val config = unzip(AgeFiles.decrypt(root.resolve("config.zip.age").readBytes(), key))
        assertTrue(ServerConfig.FILE_NAME in config.keys, config.keys.toString())
        assertTrue(config.keys.none { "offsite" in it }, "the key never goes into its own copy")
    }

    @Test
    fun aSecondPassWritesNothingAndACutOneIsDoneAgain() {
        val a = photo(1)
        val b = photo(2, 70_000)
        entry("e1", a, b)
        jobs.runOnce(clock)
        copy.pass(clock)
        val old = FileTime.fromMillis(1_000_000_000_000)
        files().forEach { Files.setLastModifiedTime(it, old) }
        assertEquals(OffsiteCopy.Outcome.Done(0, 0, true), done(copy.pass(clock + 1)))
        assertTrue(files().all { Files.getLastModifiedTime(it) == old }, "nothing written again: no churn in the cloud")

        // A pass cut short: a file never finished, one cut, a temporary file left behind.
        Files.delete(mediaCopy(a))
        mediaCopy(b).writeBytes(mediaCopy(b).readBytes().copyOf(1_000))
        mediaCopy(a).resolveSibling("x.age.part").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(OffsiteCopy.Outcome.Done(0, 2, true), done(copy.pass(clock + 2)))
        assertEquals(a.sha256, AgeFiles.sha256(mediaCopy(a), key))
        assertEquals(b.sha256, AgeFiles.sha256(mediaCopy(b), key))
        assertTrue(files().none { it.name.endsWith(".part") })
    }

    @Test
    fun aFolderOfOtherThingsAnotherKeyOrAGoneFolderGetsNothing() {
        entry("e1", photo(1))
        jobs.runOnce(clock)
        root.createDirectories().resolve("отпуск.jpg").writeBytes(Random(3).nextBytes(100))
        assertTrue("чужие файлы" in assertIs<OffsiteCopy.Outcome.Skipped>(copy.pass(clock)).why)
        assertEquals(listOf("отпуск.jpg"), root.listDirectoryEntries().map { it.name })

        Files.delete(root.resolve("отпуск.jpg"))
        assertIs<OffsiteCopy.Outcome.Done>(copy.pass(clock))
        Files.delete(copy.keyFile)
        OffsiteKey.generate().save(copy.keyFile)
        val before = files().map { it to it.fileSize() }
        assertTrue("другим ключом" in assertIs<OffsiteCopy.Outcome.Skipped>(copy.pass(clock)).why)
        assertEquals(before, files().map { it to it.fileSize() })

        Files.move(root, root.resolveSibling("moved"))
        Files.delete(copy.keyFile)
        key.save(copy.keyFile)
        assertTrue("нет" in assertIs<OffsiteCopy.Outcome.Skipped>(copy.pass(clock)).why)
        assertFalse(root.exists(), "a folder filled once is never made again")

        Files.delete(copy.keyFile)
        assertEquals(OffsiteCopy.Outcome.NoKey, copy.pass(clock))
        copy.root = null
        assertEquals(OffsiteCopy.Outcome.Off, copy.pass(clock))
    }

    @Test
    fun purgesAreFollowedOnlyAfterTheirQuarantine() {
        val kept = photo(1)
        val gone = photo(2)
        entry("live", kept)
        entry("old", gone, trashed = true)
        jobs.runOnce(clock)
        copy.pass(clock)
        val preview = assertIs<Purges.Preview.Ready>(purges.preview(PurgeRequest(entries = listOf("old")), "phone")).preview
        assertIs<Purges.Commit.Done>(purges.commit(preview.planId))
        copy.pass(clock)
        assertTrue(mediaCopy(gone).exists(), "not before the quarantine ended")
        clock += Purges.QUARANTINE_MILLIS + 1
        purges.expire()
        copy.pass(clock)
        assertFalse(mediaCopy(gone).exists())
        assertTrue(mediaCopy(kept).exists())
    }

    @Test
    fun aMediaFileGoneBadAtHomeIsLeftOut() {
        val bad = photo(1)
        entry("e1", bad)
        blobs.file(bad).writeBytes(Random(9).nextBytes(bad.size.toInt()))
        jobs.runOnce(clock)
        assertEquals(OffsiteCopy.Outcome.Done(1, 0, false), done(copy.pass(clock)))
        assertFalse(mediaCopy(bad).exists(), "a file that doesn't match its hash never goes out as if it did")
    }

    @Test
    fun theBackupPassesRunItLastAndItsFailureStopsNothingAtHome() {
        entry("e1", photo(1))
        val withCopy = BackupJobs(db, data, null, blobs, purges = purges, offsite = copy)
        withCopy.runNow()
        assertIs<OffsiteCopy.Outcome.Done>(copy.last)
        assertEquals(null, withCopy.status.offsiteError)
        copy.keyFile.writeBytes("not a key".toByteArray())
        entry("e2")
        Thread.sleep(1_100) // snapshot names have second resolution
        withCopy.runNow()
        assertTrue("no age key" in withCopy.status.offsiteError.orEmpty(), withCopy.status.offsiteError)
        assertEquals(null, withCopy.status.lastError, "the snapshot at home was taken all the same")
        assertEquals(2, db.backups!!.history().size)
    }

    @Test
    fun theSecondDisksRecoveryKitHoldsTheKey() {
        val mirror = Files.createTempDirectory("benbow-mirror").resolve("m").createDirectories()
        RecoveryKit(data, mirror, db.storeId).copy()
        assertEquals(copy.keyFile.readText(), mirror.resolve("config").resolve("keys").resolve(OffsiteCopy.KEY_FILE).readText())
    }
}
