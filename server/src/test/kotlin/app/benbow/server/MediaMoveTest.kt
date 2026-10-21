package app.benbow.server

import app.benbow.core.api.PurgeRecord
import app.benbow.core.api.PurgeRequest
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncRules
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.MediaFolder
import app.benbow.server.storage.MediaMove
import app.benbow.server.storage.Purges
import app.benbow.server.storage.Restore
import app.benbow.server.storage.SqliteSyncStore
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Moving the media folder (ADR-042, P4, data-safety "Moving the media"): drills for every way it can go. */
class MediaMoveTest {
    private class Crash : Error("the server died here")

    private val data = Files.createTempDirectory("benbow-move")
    private val old = data.resolve("media")
    private val disk = Files.createTempDirectory("benbow-new-disk")
    private val target = disk.resolve("Benbow-media")
    private var db = Database.open(data, "test")
    private var blobs = BlobStore(db, old)
    private val journal = data.resolve("db").resolve(PURGE_JOURNAL)
    private var clock = 1_792_000_000_000L
    private var purges = Purges(db, blobs, journal, now = { clock })
    private var hlc = 1L

    init {
        ServerConfig.load(data)
    }

    private fun mover(drill: (String) -> Unit = {}, disks: TestDisks = TestDisks(), mirror: Path? = null) =
        MediaMove(db, blobs, data, disks, mirror = { mirror }, runner = { it.run() }, drill = drill)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun file(seed: Int, size: Int = 2_000): Pair<String, ByteArray> {
        val bytes = Random(seed).nextBytes(size)
        val upload = assertIs<BlobStore.Start.Upload>(blobs.start(sha256(bytes), size.toLong(), "video/mp4", "f$seed.mp4", clock, null))
        assertNotNull(assertIs<BlobStore.Chunk.Accepted>(blobs.append(upload.id, 0, bytes)).blob)
        return sha256(bytes) to bytes
    }

    private fun configured(): Path = ServerConfig.load(data).mediaRoot ?: old

    /** Every one of [files] in the folder the settings name, byte for byte. */
    private fun complete(files: Map<String, ByteArray>) {
        for ((sha, bytes) in files) {
            val blob = assertNotNull(blobs.blob(sha), sha)
            assertContentEquals(bytes, configured().resolve(blob.path).readBytes(), blob.path)
        }
    }

    private fun restart() {
        db.close()
        db = Database.open(data, "test")
        blobs = BlobStore(db, configured())
        purges = Purges(db, blobs, journal, now = { clock })
    }

    private fun listing(dir: Path): Map<String, Long> = Files.walk(dir).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString() != MediaFolder.MARK }.toList()
    }.associate { dir.relativize(it).toString() to it.fileSize() }

    @Test
    fun aMoveCopiesEverythingAndSwitchesInOneStep() {
        val files = (1..5).associate { file(it) }
        val partial = Random(9).nextBytes(5_000)
        val upload = assertIs<BlobStore.Start.Upload>(blobs.start(sha256(partial), 5_000, "video/mp4", "v.mp4", clock, null))
        blobs.append(upload.id, 0, partial.copyOfRange(0, 2_000))
        val before = listing(old)

        assertIs<MediaMove.Verdict.Fine>(mover().start(target.toString()))
        assertEquals(target, configured())
        assertEquals(target, blobs.root)
        assertTrue(blobs.ready())
        complete(files)
        val done = assertNotNull(assertIs<BlobStore.Chunk.Accepted>(blobs.append(upload.id, 2_000, partial.copyOfRange(2_000, 5_000))).blob)
        assertContentEquals(partial, target.resolve(done.path).readBytes(), "the upload under way goes on in the new folder")

        assertEquals(before, listing(old), "the old folder is left as it was")
        assertIs<MediaFolder.State.Moved>(BlobStore(db, old).folder.check(), "and never taken for the media folder again")
        val moves = mover().moves()
        assertNull(moves.current)
        assertEquals(listOf(old.toString()), moves.left.map { it.path })
        assertFalse(target.resolve(MediaMove.MOVING).exists())
    }

    @Test
    fun aFolderIsCheckedBeforeAnythingStarts() {
        file(1)
        fun refused(text: String, says: String, disks: TestDisks = TestDisks(), mirror: Path? = null) {
            val verdict = assertIs<MediaMove.Verdict.Refused>(mover(disks = disks, mirror = mirror).start(text), text)
            assertTrue(says in verdict.reason, "$text: ${verdict.reason}")
            assertEquals(old, configured())
            assertNull(mover().moves().current)
        }
        refused("", "впишите")
        refused("media", "полный путь")
        refused("\\\\nas\\media", "сетевые")
        refused(disk.root.toString(), "весь диск")
        refused(old.resolve("inner").toString(), "внутри")
        refused(data.resolve("media2").toString(), "внутри")
        refused(disk.resolve("ex").toString(), "exFAT", TestDisks(fileSystems = mapOf(disk to "exFAT")))
        refused(disk.resolve("drive").toString(), "потоковый", TestDisks(streaming = setOf(disk)))
        refused(disk.resolve("small").toString(), "не хватит места", TestDisks(freeBytes = 10))
        val mirror = Files.createTempDirectory("benbow-mirror-disk").resolve("mirror")
        refused(target.toString(), "второй диск", TestDisks(volumes = mapOf(disk to "H", mirror.parent to "H")), mirror)
        val full = disk.resolve("full").createDirectories().also { it.resolve("IMG_1.jpg").writeBytes(byteArrayOf(1)) }
        refused(full.toString(), "уже что-то лежит")
        for (name in listOf("ex", "drive", "small")) assertFalse(disk.resolve(name).exists(), "$name: nothing made for a refusal")

        Files.move(old, data.resolve("unplugged"))
        refused(target.toString(), "недоступна")
        Files.move(data.resolve("unplugged"), old)
        assertFailsWith<Crash> { mover(drill = { throw Crash() }).start(target.toString()) }
        val again = assertIs<MediaMove.Verdict.Refused>(mover().start(disk.resolve("other").toString()))
        assertTrue("уже идёт" in again.reason)
    }

    @Test
    fun uploadsAndPurgesDuringTheCopyAreCaughtUpAtTheSwitch() {
        val files = (1..3).associate { file(it) }
        val purged = files.keys.first()
        SyncRules(SqliteSyncStore(db, "phone")).push(
            listOf(
                CreateEntry("c", "old", "journal", "2026-10-22", 1),
                PutAttachment(
                    "a",
                    "old",
                    AttachmentMeta("v", purged, 1, "video/mp4", "video", name = "v.mp4"),
                    1.0,
                    hlc = Hlc(hlc++, 0, "phone"),
                ),
                SetField("t", "old", "deleted", "true", Hlc(hlc++, 0, "phone")),
            ),
        )
        var added: Pair<String, ByteArray>? = null
        val drill = { step: String ->
            if (step == "copied") {
                added = file(7) // placed in the old folder after its copy
                val plan = assertIs<Purges.Preview.Ready>(purges.preview(PurgeRequest(entries = listOf("old")), "phone")).preview
                assertIs<Purges.Commit.Done>(purges.commit(plan.planId)) // into the old folder's quarantine
            }
        }
        assertIs<MediaMove.Verdict.Fine>(mover(drill).start(target.toString()))
        assertEquals(target, configured())
        complete(mapOf(added!!) + files.filterKeys { it != purged })
        val home = Json.decodeFromString(PurgeRecord.serializer(), journal.readLines().single()).files.single().path
        assertFalse(target.resolve(home).exists(), "not at its old place: it would come back as a file nobody knows")
        assertEquals(0, blobs.adoptUntracked(purges.pendingPaths()))
        assertEquals(1, purges.waiting().size, "it waits in the new folder's quarantine")
        clock += Purges.QUARANTINE_MILLIS + 1
        purges.expire()
        assertEquals(emptyList(), purges.waiting())
        assertFalse(Files.walk(target.resolve(Purges.QUARANTINE)).use { s -> s.anyMatch { Files.isRegularFile(it) } })
    }

    private fun crashAt(step: String) {
        val files = (1..4).associate { file(it) }
        val at = if (step == "file") 2 else 1 // while copying: with one file already over
        var seen = 0
        assertFailsWith<Crash> { mover(drill = { if (it == step && ++seen == at) throw Crash() }).start(target.toString()) }
        restart()
        complete(files) // whatever the settings name now holds everything
        assertTrue(blobs.ready())
        mover().resume() // the next start
        restart()
        assertEquals(target, configured())
        complete(files)
        assertNull(mover().moves().current)
        assertIs<MediaFolder.State.Moved>(BlobStore(db, old).folder.check())
        assertEquals(0, blobs.adoptUntracked())
    }

    @Test
    fun aCrashWhileCopying() = crashAt("file")

    @Test
    fun aCrashBeforeTheSwitch() = crashAt("copied")

    @Test
    fun aCrashAfterTheCheck() = crashAt("checked")

    @Test
    fun aCrashAfterTheNewFolderIsMarked() = crashAt("marked")

    @Test
    fun aCrashRightAfterTheSettingsChanged() = crashAt("configured")

    @Test
    fun theNewDiskGoneMidCopyStopsTheMoveAndTheOldFolderGoesOn() {
        val files = (1..4).associate { file(it) }
        val unplugged = disk.resolveSibling("${disk.fileName}-unplugged")
        var copied = 0
        mover(drill = { if (it == "file" && ++copied == 1) Files.move(disk, unplugged) }).start(target.toString())
        assertNotNull(mover().moves().current?.error)
        assertEquals(old, configured())
        assertEquals(old, blobs.root)
        val later = file(8)
        mover().resume()
        assertNotNull(mover().moves().current?.error, "a stopped move waits for the owner")

        Files.move(unplugged, disk)
        mover().retry()
        assertEquals(target, configured())
        complete(files + later)
    }

    @Test
    fun aFullDiskMidCopyStopsTheMoveWithItsReason() {
        val files = (1..3).associate { file(it) }
        var full = true
        mover(drill = { if (it == "file" && full) throw IOException("There is not enough space on the disk") }).start(target.toString())
        assertTrue("not enough space" in mover().moves().current?.error.orEmpty())
        assertEquals(old, configured())
        full = false
        mover().retry()
        assertEquals(target, configured())
        complete(files)
    }

    @Test
    fun aCancelledMoveSwitchesNothingAndKeepsItsCopy() {
        val files = (1..3).associate { file(it) }
        lateinit var move: MediaMove
        var copied = 0
        move = mover(drill = { if (it == "file" && ++copied == 1) move.cancel() })
        move.start(target.toString())
        assertEquals(old, configured())
        assertNull(mover().moves().current)
        assertTrue(target.resolve(MediaMove.MOVING).exists(), "what was copied stays, nothing is deleted")
        assertIs<MediaMove.Verdict.Fine>(mover().start(target.toString()), "the same folder takes the move again")
        assertEquals(target, configured())
        complete(files)
    }

    @Test
    fun aRestoreAfterTheMoveKeepsEveryFile() {
        val files = (1..3).associate { file(it) }
        db.locked { c -> db.backups!!.snapshot(c, "before-move") }
        mover().start(target.toString())
        val later = file(9) // placed in the new folder, after the snapshot
        db.close()
        Restore.run(data, "latest")
        db = Database.open(data, "test")
        blobs = BlobStore(db, configured())
        assertEquals(target, blobs.root)
        assertTrue(blobs.ready())
        assertEquals(1, blobs.adoptUntracked(), "the file the snapshot lacks is known again")
        complete(files + later)
    }
}
