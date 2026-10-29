package app.benbow.server

import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.PutRevision
import app.benbow.core.sync.SyncRules
import app.benbow.server.backup.BackupJobs
import app.benbow.server.backup.MirrorFolder
import app.benbow.server.backup.MirrorRestore
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.fonts.OwnFonts
import app.benbow.server.peer.PeerIdentity
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.SqliteSyncStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The mirror as a recovery kit and a server stood up again from it on a new PC (ADR-042, data-safety runbook "PC dead"). */
class RecoveryKitTest {
    private val body = """{"v":1,"blocks":[{"_key":"k1","_type":"block","style":"normal","children":""" +
        """[{"_type":"span","text":"Вечер у моря","marks":[]}],"markDefs":[]}]}"""
    private val data = Files.createTempDirectory("benbow-old-pc")
    private val mirror = Files.createTempDirectory("benbow-second-disk").resolve("Benbow-mirror")
    private val photo = Random(1).nextBytes(4_000)

    /** A server as it lived on the old PC: settings, certificate, peer keys, an entry, a photo, a calendar, a pass. */
    private fun oldServer(): Triple<String, String, BlobStore.Blob> {
        val config = ServerConfig.load(data)
        val fingerprint = TlsMaterial.ensure(data, config.tlsPassword).fingerprint
        PeerIdentity.ensure(data.resolve("peer"))
        OwnFonts(data.resolve(OwnFonts.DIR)).add("MyHand.ttf", Path.of("packaging/fonts/caveat.ttf").readBytes(), 1)
        data.resolve("db").createDirectories().resolve(PURGE_JOURNAL).writeText("")
        val db = Database.open(data, "test")
        val calendar = CalendarDatabase.open(data, "test", db.storeId)
        val blobs = BlobStore(db, data.resolve("media"))
        SyncRules(SqliteSyncStore(db)).push(
            listOf(CreateEntry("o1", "entry-1", "journal", "2026-10-21", 1), PutRevision("o2", "entry-1", "r1", emptyList(), body)),
        )
        val sha = MessageDigest.getInstance("SHA-256").digest(photo).joinToString("") { "%02x".format(it) }
        val upload = assertIs<BlobStore.Start.Upload>(blobs.start(sha, photo.size.toLong(), "image/jpeg", "p.jpg", 1_792_000_000_000, null))
        val blob = assertNotNull(assertIs<BlobStore.Chunk.Accepted>(blobs.append(upload.id, 0, photo)).blob)
        val jobs = BackupJobs(db, data, mirror, blobs, calendar, disks = TestDisks())
        jobs.runOnce()
        jobs.runCalendar()
        val storeId = db.storeId
        calendar.close()
        db.close()
        return Triple(storeId, fingerprint, blob)
    }

    private fun listing(dir: Path): Map<String, Pair<Long, Long>> = Files.walk(dir).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList()
    }.associate { dir.relativize(it).toString() to (it.fileSize() to it.getLastModifiedTime().toMillis()) }

    @Test
    fun theMirrorHoldsEverythingToStandTheServerUpAgain() {
        val (storeId, _, _) = oldServer()
        for (file in listOf("benbow.properties", "tls/server.keystore", "peer/identity.json")) {
            assertContentEquals(data.resolve(file).readBytes(), mirror.resolve("config").resolve(file).readBytes(), file)
        }
        assertTrue(mirror.resolve("db").resolve(PURGE_JOURNAL).exists())
        assertTrue(mirror.resolve(OwnFonts.DIR).resolve(OwnFonts.REGISTRY).exists(), "the owner's own fonts (ADR-052)")
        assertEquals(1, mirror.resolve("calendar").listDirectoryEntries("*.sqlite").size)
        val notes = Files.walk(mirror.resolve("export")).use { p -> p.filter { it.name.endsWith(".md") }.toList() }
        assertTrue(notes.any { "Вечер у моря" in it.readText() }, notes.toString())
        assertTrue(storeId in mirror.resolve("README.txt").readText())

        // The export there follows the one here, deletions included: it is derived.
        val gone = data.resolve("export").resolve("old-note.md").also { it.writeText("x") }
        val db = Database.open(data, "test")
        SyncRules(SqliteSyncStore(db)).push(listOf(CreateEntry("o3", "entry-2", "journal", "2026-10-21", 2)))
        Thread.sleep(1_100) // snapshot names have second resolution
        BackupJobs(db, data, mirror, disks = TestDisks()).runOnce()
        assertTrue(mirror.resolve("export").resolve("old-note.md").exists())
        gone.deleteExisting()
        BackupJobs(db, data, mirror, disks = TestDisks()).runOnce()
        assertFalse(mirror.resolve("export").resolve("old-note.md").exists())
        db.close()
    }

    @Test
    fun aServerStandsUpAgainFromTheMirrorOnANewPc() {
        val (storeId, fingerprint, blob) = oldServer()
        val identity = PeerIdentity.ensure(data.resolve("peer")).card
        val before = listing(mirror)
        val newPc = Files.createTempDirectory("benbow-new-pc")
        val media = newPc.resolve("D").resolve("Benbow-media")

        val result = MirrorRestore.run(newPc.resolve("data"), mirror, media)
        assertEquals(before, listing(mirror), "the mirror is only read")
        assertEquals(storeId, result.storeId)
        assertEquals(1, result.files)

        val dataDir = newPc.resolve("data")
        val config = ServerConfig.load(dataDir)
        assertEquals(mirror, config.backupMirror)
        assertEquals(media.toAbsolutePath(), config.mediaRoot)
        assertEquals(fingerprint, TlsMaterial.ensure(dataDir, config.tlsPassword).fingerprint, "devices know it by the same certificate")
        assertEquals(identity, PeerIdentity.ensure(dataDir.resolve("peer")).card, "friends know it by the same keys")
        assertEquals("own:caveat", OwnFonts(dataDir.resolve(OwnFonts.DIR)).all().single().token, "the owner's fonts came back")
        assertTrue(
            OwnFonts(
                dataDir.resolve(OwnFonts.DIR),
            ).file(OwnFonts(data.resolve(OwnFonts.DIR)).all().single().files.single().sha256)!!.exists(),
        )
        val db = Database.open(dataDir, "test")
        assertEquals(storeId, db.storeId)
        assertTrue(db.epoch > 1, "a new epoch: devices reconcile")
        assertNotNull(SqliteSyncStore(db).entry("entry-1"))
        CalendarDatabase.open(dataDir, "test", storeId).close()
        val blobs = BlobStore(db, media)
        assertTrue(blobs.ready())
        assertContentEquals(photo, blobs.file(assertNotNull(blobs.blob(blob.sha256))).readBytes())

        // Its first pass into the same mirror keeps the old snapshots: they came with it.
        val old = mirror.resolve("db").listDirectoryEntries("*.sqlite").map { it.name }.toSet()
        SyncRules(SqliteSyncStore(db)).push(listOf(CreateEntry("n1", "entry-new", "journal", "2026-10-22", 3)))
        Thread.sleep(1_100)
        val jobs = BackupJobs(db, dataDir, mirror, blobs, disks = TestDisks())
        assertTrue(jobs.runOnce())
        val now = mirror.resolve("db").listDirectoryEntries("*.sqlite").map { it.name }.toSet()
        assertTrue(now.containsAll(old), "kept: $old, now: $now")
        assertEquals(old.size + 1, now.size)
        assertEquals(null, jobs.status.mirrorProblem)
        db.close()
    }

    @Test
    fun aRestoreGoesOnlyIntoAnEmptyPlaceFromAMirrorOfItsOwn() {
        oldServer()
        val newPc = Files.createTempDirectory("benbow-new-pc")
        assertFailsWith<IllegalStateException>("a data folder that holds a server") { MirrorRestore.run(data, mirror) }
        val full = newPc.resolve("media").createDirectories().also { it.resolve("IMG_1.jpg").writeBytes(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException>("a media folder with files") { MirrorRestore.run(newPc.resolve("a"), mirror, full) }
        assertFalse(newPc.resolve("a").resolve("db").exists())
        val stranger = Files.createTempDirectory("benbow-not-a-mirror")
        assertFailsWith<IllegalStateException>("no mark") { MirrorRestore.run(newPc.resolve("b"), stranger) }
        mirror.resolve("config").toFile().deleteRecursively() // a mirror written before 0.12.1
        val e = assertFailsWith<IllegalStateException> { MirrorRestore.run(newPc.resolve("c"), mirror) }
        assertTrue("config" in e.message.orEmpty(), e.message)
    }

    @Test
    fun aMirrorOnTheSameVolumeGetsNothingAndWarningsAreSaid() {
        val db = Database.open(data, "test")
        SyncRules(SqliteSyncStore(db)).push(listOf(CreateEntry("o1", "entry-1", "journal", "2026-10-21", 1)))
        val sameVolume = TestDisks(volumes = mapOf(data to "C", mirror.parent to "C"))
        val refused = BackupJobs(db, data, mirror, disks = sameVolume)
        assertTrue(refused.runOnce(), "the snapshot here is taken all the same")
        assertFalse(mirror.exists())
        assertTrue("том же диске" in refused.status.mirrorProblem.orEmpty(), refused.status.mirrorProblem)

        val partitions = TestDisks(physical = mapOf(data to 3, mirror.parent to 3), freeBytes = 1_000_000_000)
        val warned = BackupJobs(db, data, mirror, disks = partitions)
        warned.runOnce()
        assertTrue(mirror.resolve(MirrorFolder.MARK).exists(), "a warning doesn't stop the copy")
        val warnings = warned.status.mirrorWarnings.joinToString()
        assertTrue("свободно 0,9 ГБ" in warnings || "свободно 0.9 ГБ" in warnings, warnings)
        assertTrue("физического диска" in warnings, warnings)
        db.close()
    }
}
