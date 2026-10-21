package app.benbow.server

import app.benbow.core.api.PurgeRequest
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncRules
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.LeftFolders
import app.benbow.server.storage.MediaFolder
import app.benbow.server.storage.MediaMove
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.Purges
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
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The folder a media move left (ADR-042, P6): a week as a spare copy, then deleted only after every file checks. */
class LeftFolderTest {
    private val data = Files.createTempDirectory("benbow-left")
    private val old = data.resolve("media")
    private val target = Files.createTempDirectory("benbow-new-disk").resolve("Benbow-media")
    private val db = Database.open(data, "test")
    private val blobs = BlobStore(db, old)
    private var clock = 1_792_000_000_000L
    private val files: Map<String, ByteArray>
    private val move = MediaMove(db, blobs, data, TestDisks(), runner = { it.run() }, now = { clock })
    private val left = LeftFolders(db, blobs, data, move, runner = { it.run() }, now = { clock })
    private val week = LeftFolders.WAIT_MILLIS

    init {
        ServerConfig.load(data)
        files = (1..4).associate { seed ->
            val bytes = Random(seed).nextBytes(2_000)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val upload = assertIs<BlobStore.Start.Upload>(blobs.start(sha, 2_000, "video/mp4", "f$seed.mp4", clock, null))
            blobs.append(upload.id, 0, bytes)
            sha to bytes
        }
        assertIs<MediaMove.Verdict.Fine>(move.start(target.toString()))
        assertEquals(target, blobs.root)
    }

    private fun listing(dir: Path): Map<String, Long> = Files.walk(dir).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList()
    }.associate { dir.relativize(it).toString() to it.fileSize() }

    private fun problems() = assertNotNull(left.report).problems

    @Test
    fun aLeftFolderWaitsItsWeek() {
        left.delete(old.toString())
        assertTrue(problems().single().contains("неделя"))
        assertTrue(old.exists())
        clock += week + 1
        left.check(old.toString())
        assertEquals(emptyList(), problems())
        assertTrue(old.exists(), "a check deletes nothing")
    }

    @Test
    fun afterItsWeekAnIntactFolderIsDeletedAndTheMediaStay() {
        val now = listing(target)
        clock += week + 1
        left.delete(old.toString())
        val report = assertNotNull(left.report)
        assertTrue(report.deleted, report.problems.toString())
        assertEquals(files.size, report.files)
        assertFalse(old.exists())
        assertEquals(emptyList(), move.moves().left)
        assertEquals(now, listing(target), "the media folder is untouched")
        assertTrue(blobs.ready())
        for ((sha, bytes) in files) assertTrue(bytes.contentEquals(blobs.file(blobs.blob(sha)!!).readBytes()))
    }

    @Test
    fun aCopyThatDiffersOrIsMissingKeepsTheWholeFolder() {
        val (first, second) = files.keys.take(2).map { blobs.blob(it)!! }
        blobs.file(first).writeBytes(Random(99).nextBytes(2_000)) // the live copy went bad
        blobs.file(second).deleteExisting() // or went missing
        val before = listing(old)
        clock += week + 1
        left.delete(old.toString())
        assertEquals(2, problems().size, problems().toString())
        assertFalse(assertNotNull(left.report).deleted)
        assertEquals(before, listing(old), "nothing deleted: the old copies are the good ones")
        assertEquals(1, move.moves().left.size)
    }

    @Test
    fun filesDeletedForGoodMayGoWithIt() {
        val purges = Purges(db, blobs, data.resolve("db").resolve(PURGE_JOURNAL), now = { clock })
        val sha = files.keys.first()
        SyncRules(SqliteSyncStore(db, "phone")).push(
            listOf(
                CreateEntry("c", "e", "journal", "2026-10-22", 1),
                PutAttachment("a", "e", AttachmentMeta("v", sha, 1, "video/mp4", "video", name = "v.mp4"), 1.0, hlc = Hlc(1, 0, "phone")),
                SetField("t", "e", "deleted", "true", Hlc(2, 0, "phone")),
            ),
        )
        val plan = assertIs<Purges.Preview.Ready>(purges.preview(PurgeRequest(entries = listOf("e")), "phone")).preview
        assertIs<Purges.Commit.Done>(purges.commit(plan.planId))
        clock += week + 1
        purges.expire() // gone for good from the media folder; its old copy is still in the left one
        left.delete(old.toString())
        val report = assertNotNull(left.report)
        assertTrue(report.deleted, report.problems.toString())
        assertEquals(1, report.purged)
        assertFalse(old.exists())
    }

    @Test
    fun onlyAFolderMarkedAsThisStoresMovedOneIsTouched() {
        clock += week + 1
        old.resolve(MediaFolder.MARK).deleteExisting()
        val before = listing(old)
        left.delete(old.toString())
        assertTrue(problems().single().contains("метки"), problems().toString())
        assertEquals(before, listing(old))
    }

    @Test
    fun noMoveGoesIntoALeftFolder() {
        val second = Files.createTempDirectory("benbow-third-disk").resolve("Benbow-media")
        assertIs<MediaMove.Verdict.Fine>(move.start(second.toString())) // now the first new folder is left too
        val verdict = assertIs<MediaMove.Verdict.Refused>(move.preview(target.resolve("again").toString()))
        assertTrue("оставленная прежним переносом" in verdict.reason, verdict.reason)
    }

    @Test
    fun aFolderAlreadyGoneIsTakenOffTheList() {
        left.forget(old.toString())
        assertEquals(1, move.moves().left.size, "a folder that is there stays listed")
        Files.move(old, data.resolve("elsewhere"))
        left.forget(old.toString())
        assertEquals(emptyList(), move.moves().left)
    }

    @Test
    fun theAdminPageWaitsChecksAndDeletes() {
        val ctx = ServerContext(
            db,
            Devices(db),
            PairingCodes(),
            "AA:BB",
            data,
            8765,
            isLoopback = { true },
            blobs = blobs,
            mediaMove = move,
            leftFolders = left,
        )
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { followRedirects = false }
            assertTrue("можно будет с" in client.get("/?part=media").bodyAsText())
            clock += week + 1
            assertTrue("Проверить и удалить" in client.get("/?part=media").bodyAsText())
            val form = parameters { append("left-path", old.toString()) }
            assertEquals(HttpStatusCode.Found, client.submitForm("/admin/media/left/check", form).status)
            val checked = client.get("/?part=media").bodyAsText()
            assertTrue("Проверено 4 файла" in checked && "Удалить старую папку" in checked, checked)
            assertEquals(HttpStatusCode.Found, client.submitForm("/admin/media/left/delete", form).status)
            assertTrue("удалена" in client.get("/?part=media").bodyAsText())
            assertFalse(old.exists())
            val stranger = parameters { append("left-path", data.toString()) }
            client.submitForm("/admin/media/left/delete", stranger)
            assertTrue(data.exists(), "a path that isn't a left folder is never acted on")
        }
    }
}
