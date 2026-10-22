package app.benbow.server

import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SyncRules
import app.benbow.server.backup.BackupJobs
import app.benbow.server.offsite.AgeFiles
import app.benbow.server.offsite.OffsiteCopy
import app.benbow.server.offsite.OffsiteKey
import app.benbow.server.offsite.OffsiteRestore
import app.benbow.server.offsite.OffsiteSetup
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.SqliteSyncStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Standing the server up again from the copy outside the house (ADR-043, E5), when nothing at home is left. */
class OffsiteRestoreTest {
    private val home = Files.createTempDirectory("benbow-home")
    private val db = Database.open(home, "test")
    private val blobs = BlobStore(db, home.resolve("media"))
    private val copyRoot = Files.createTempDirectory("benbow-cloud").resolve("Benbow")
    private val copy = OffsiteCopy(db, home, copyRoot, blobs)
    private val key = OffsiteKey.generate().also { it.save(copy.keyFile) }
    private val photos = ArrayList<BlobStore.Blob>()
    private val newPc = Files.createTempDirectory("benbow-new-pc")

    init {
        ServerConfig.load(home)
        home.resolve("tls").createDirectories().resolve("server.keystore").writeBytes(Random(1).nextBytes(2_000))
        val metas = (1..3).map { seed ->
            val bytes = Random(seed).nextBytes(10_000 * seed)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val upload =
                assertIs<BlobStore.Start.Upload>(blobs.start(sha, bytes.size.toLong(), "image/jpeg", "p$seed.jpg", 1_792_000_000_000, null))
            photos += assertIs<BlobStore.Chunk.Accepted>(blobs.append(upload.id, 0, bytes)).blob!!
            AttachmentMeta("p$seed", sha, 1, "image/jpeg", "image")
        }
        SyncRules(SqliteSyncStore(db, "phone")).push(
            listOf(CreateEntry("c", "e", "journal", "2026-10-22", 1)) +
                metas.mapIndexed { i, m -> PutAttachment("a$i", "e", m, 1.0, hlc = Hlc(i + 1L, 0, "phone")) },
        )
        BackupJobs(db, home, null, blobs).runOnce()
        assertIs<OffsiteCopy.Outcome.Done>(copy.pass())
    }

    private fun restore(typed: String = key.secret, media: Path? = null) =
        OffsiteRestore.run(newPc.resolve("data"), copyRoot, OffsiteRestore.key(copyRoot, typed), media)

    @Test
    fun theServerStandsUpAgainFromTheCopyAlone() {
        val media = newPc.resolve("media")
        val result = restore(media = media)
        val data = newPc.resolve("data")
        assertEquals(db.storeId, result.storeId)
        assertEquals(3, result.files)
        assertEquals(0, result.unknown)
        val restored = Database.open(data, "test")
        assertEquals(db.storeId, restored.storeId)
        assertEquals(3, SqliteSyncStore(restored).entry("e")!!.attachments.size)
        for (blob in photos) assertEquals(blob.sha256, BlobStore.sha256(media.resolve(blob.path)), "${blob.path} in its place")
        assertContentEquals(
            home.resolve("tls").resolve("server.keystore").readBytes(),
            data.resolve("tls").resolve("server.keystore").readBytes(),
        )
        val config = ServerConfig.load(data)
        assertEquals(copyRoot.toString().replace('\\', '/'), config.backupOffsite.toString().replace('\\', '/'), "the copy goes on")
        assertEquals(null, config.backupMirror, "no second disk until one is chosen")
        assertEquals(key, OffsiteKey.load(data.resolve("keys").resolve(OffsiteCopy.KEY_FILE)))

        // The restored server goes on writing the same copy: nothing there is written again.
        val again = OffsiteCopy(restored, data, copyRoot, BlobStore(restored, media))
        assertEquals(0, assertIs<OffsiteCopy.Outcome.Done>(again.pass()).files)
    }

    @Test
    fun theCodeOpensItFromItsFileOrTypedAndAPassphraseThroughKeyAge() {
        val file = "# Benbow: код восстановления копии вне дома\n# Хранилище: x\n${key.secret}\n"
        assertEquals(key, OffsiteRestore.key(copyRoot, file))
        assertEquals(key, OffsiteRestore.key(copyRoot, key.grouped().lowercase()))
        assertTrue("нет key.age" in assertFailsWith<IllegalStateException> { OffsiteRestore.key(copyRoot, "Ёжик в тумане 1975") }.message!!)
        copyRoot.resolve(OffsiteSetup.KEY_AGE).writeBytes(key.seal("Ёжик в тумане 1975", workFactor = 10))
        assertEquals(key, OffsiteRestore.key(copyRoot, "Ёжик в тумане 1975"))
        assertTrue("не открывается" in assertFailsWith<IllegalStateException> { OffsiteRestore.key(copyRoot, "не тот пароль") }.message!!)
        assertTrue("другой копии" in assertFailsWith<IllegalStateException> { restore(OffsiteKey.generate().secret) }.message!!)
        assertTrue(!newPc.resolve("data").resolve("db").exists(), "a wrong key changes nothing")
    }

    @Test
    fun onlyIntoAnEmptyPlaceAndAFileTheDatabaseDoesntKnowIsKept() {
        newPc.resolve("data").resolve("db").createDirectories().resolve("benbow.sqlite").writeText("a server is here")
        assertTrue("already holds a server" in assertFailsWith<IllegalStateException> { restore() }.message!!)
        Files.delete(newPc.resolve("data").resolve("db").resolve("benbow.sqlite"))
        val busy = newPc.resolve("busy").createDirectories().also { it.resolve("x.jpg").writeText("x") }
        assertTrue("not empty" in assertFailsWith<IllegalStateException> { restore(media = busy) }.message!!)

        // A photo that came after the last snapshot: in the copy, not in the restored database.
        val late = Random(9).nextBytes(5_000)
        val sha = MessageDigest.getInstance("SHA-256").digest(late).joinToString("") { "%02x".format(it) }
        val name = key.mediaName(sha)
        AgeFiles.encrypt(late, copyRoot.resolve("media").resolve(name.take(2)).createDirectories().resolve("$name.age"), key)
        val result = restore(media = newPc.resolve("media"))
        assertEquals(1, result.unknown)
        assertContentEquals(late, newPc.resolve("media").resolve("restored").resolve(sha).readBytes())
        assertTrue(newPc.resolve("media").resolve(".benbow-media").readText().contains(db.storeId))
    }
}
