package app.benbow.server

import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.SyncRules
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.backup.BackupJobs
import app.benbow.server.backup.MirrorChoice
import app.benbow.server.backup.MirrorFolder
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.SqliteSyncStore
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Choosing the second disk on the admin page (ADR-042, P3): checked before anything is saved, switched without a restart. */
class MirrorChoiceTest {
    private val data = Files.createTempDirectory("benbow-choose")
    private val db = Database.open(data, "test")
    private val blobs = BlobStore(db, data.resolve("media"))
    private val disk = Files.createTempDirectory("benbow-other-disk")
    private val photo = Random(1).nextBytes(3_000)
    private var changes = 0

    init {
        ServerConfig.load(data)
        change()
        val sha = MessageDigest.getInstance("SHA-256").digest(photo).joinToString("") { "%02x".format(it) }
        val upload = assertIs<BlobStore.Start.Upload>(blobs.start(sha, photo.size.toLong(), "image/jpeg", "p.jpg", 1_792_000_000_000, null))
        blobs.append(upload.id, 0, photo)
    }

    private fun change() {
        changes++
        SyncRules(SqliteSyncStore(db)).push(listOf(CreateEntry("o$changes", "entry-$changes", "journal", "2026-10-21", 1)))
    }

    private fun jobs(disks: TestDisks = TestDisks()) = BackupJobs(db, data, ServerConfig.load(data).backupMirror, blobs, disks = disks)

    private fun saved(): String = Properties().apply { data.resolve(ServerConfig.FILE_NAME).inputStream().use(::load) }
        .getProperty("backup.mirror")

    private fun listing(dir: Path): Map<String, Long> = Files.walk(dir).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "README.txt" }.toList()
    }.associate { dir.relativize(it).toString() to it.fileSize() }

    private fun refused(text: String, disks: TestDisks = TestDisks(), says: String) {
        val jobs = jobs(disks)
        val verdict = assertIs<MirrorChoice.Verdict.Refused>(jobs.chooseMirror(text), text)
        assertTrue(says in verdict.reason, "$text: ${verdict.reason}")
        assertEquals("", saved(), "nothing saved for $text")
        assertNull(jobs.mirror())
    }

    @Test
    fun aFolderIsCheckedBeforeAnythingIsSaved() {
        refused("", says = "впишите")
        refused("mirror", says = "полный путь")
        refused("\\\\nas\\backup", says = "сетевые")
        refused(disk.root.toString(), says = "весь диск")
        refused(disk.resolve("nope").resolve("mirror").toString(), says = "нет")
        refused(disk.resolve("fat").toString(), TestDisks(fileSystems = mapOf(disk to "FAT32")), says = "FAT32")
        refused(disk.resolve("drive").toString(), TestDisks(streaming = setOf(disk)), says = "потоковый")
        refused(data.resolve("mirror").toString(), says = "внутри")
        refused(data.parent.toString(), says = "внутри")
        refused(disk.resolve("same").toString(), TestDisks(volumes = mapOf(data to "C", disk to "C")), says = "тот же диск")
        refused(disk.resolve("big").toString(), TestDisks(freeBytes = 1_000), says = "не хватит места")
        val photos = disk.resolve("Фото").createDirectories().also { it.resolve("IMG_1.jpg").writeBytes(byteArrayOf(1)) }
        refused(photos.toString(), says = "уже что-то лежит")
        val foreign = disk.resolve("foreign").createDirectories().also { it.resolve(MirrorFolder.MARK).writeText("store=other\n") }
        refused(foreign.toString(), says = "другому хранилищу")
        for (name in listOf(
            "fat",
            "drive",
            "same",
            "big",
        )) {
            assertTrue(!disk.resolve(name).exists(), "$name: a folder made for a check is removed")
        }
    }

    @Test
    fun aChosenFolderIsSavedMarkedAndFilledWithoutARestart() {
        val jobs = jobs()
        val mirror = disk.resolve("Benbow-mirror")
        val fine = assertIs<MirrorChoice.Verdict.Fine>(jobs.chooseMirror("\"$mirror\""))
        assertEquals(mirror, fine.path)
        assertEquals(mirror.toString().replace('\\', '/'), saved())
        assertEquals(mirror, ServerConfig.load(data).backupMirror)
        assertTrue(mirror.resolve(MirrorFolder.MARK).exists())
        jobs.runSoon().get() // after the first pass the choice started
        assertEquals(mirror.toString(), jobs.status.mirrorPath)
        assertNotNull(jobs.status.lastMirrorAt)
        val copy = Files.walk(mirror.resolve("media")).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.single()
        assertContentEquals(photo, copy.readBytes())
        assertTrue(mirror.resolve("config").resolve(ServerConfig.FILE_NAME).exists())
        assertNull(jobs.progress)
    }

    @Test
    fun anotherFolderLeavesTheOldOneAsItIsAndOffStopsTheCopies() {
        val jobs = jobs()
        val a = disk.resolve("A")
        val b = disk.resolve("B")
        assertIs<MirrorChoice.Verdict.Fine>(jobs.chooseMirror(a.toString()))
        jobs.runSoon().get()
        val before = listing(a)
        assertIs<MirrorChoice.Verdict.Fine>(jobs.chooseMirror(b.toString()))
        change()
        Thread.sleep(1_100) // snapshot names have second resolution
        jobs.runSoon().get()
        assertEquals(before, listing(a), "the old folder is left as it is")
        assertTrue(listing(b).keys.any { it.startsWith("media") })
        assertEquals(b.toString(), jobs.status.mirrorPath)

        val filled = listing(b)
        jobs.turnOffMirror()
        assertEquals("", saved())
        assertNull(jobs.status.mirrorPath)
        change()
        Thread.sleep(1_100)
        jobs.runNow()
        assertEquals(filled, listing(b), "no copies go there once it is off")
        assertEquals(before, listing(a))
        assertNull(jobs(TestDisks()).mirror(), "and none after a restart")
    }

    @Test
    fun theAdminPageChoosesAFolderAndSaysWhyNot() {
        val jobs = jobs()
        val ctx = ServerContext(
            db,
            Devices(db),
            PairingCodes(),
            "AA:BB",
            data,
            8765,
            backupStatus = { jobs.status },
            isLoopback = { true },
            blobs = blobs,
            backupJobs = jobs,
        )
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { followRedirects = false }
            assertTrue("Второго диска нет" in client.get("/").bodyAsText())
            val wrong = client.submitForm("/admin/mirror", parameters { append("mirror-path", data.resolve("inside").toString()) })
            assertEquals(HttpStatusCode.BadRequest, wrong.status)
            assertTrue("не подходит" in wrong.bodyAsText())
            val mirror = disk.resolve("Benbow-mirror")
            val ok = client.submitForm("/admin/mirror", parameters { append("mirror-path", mirror.toString()) })
            assertEquals(HttpStatusCode.Found, ok.status)
            jobs.runSoon().get()
            assertTrue(mirror.toString() in client.get("/?part=mirror").bodyAsText())
            assertEquals(HttpStatusCode.Found, client.submitForm("/admin/mirror/off", parameters {}).status)
            assertNull(jobs.mirror())
        }
    }
}
