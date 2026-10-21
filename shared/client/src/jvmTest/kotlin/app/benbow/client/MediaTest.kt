package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaTest {
    private var time = 1_000L
    private val photo = Random(3).nextBytes(5_000)

    private inner class Device(server: FakeServer, name: String) {
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val api = FakeApi(server)
        val repo = JournalRepository(db, { time++ })
        val engine = SyncEngine(db, { api }, { time++ })
        val media = MediaStore(Files.createTempDirectory("benbow-media-$name").toFile(), db, { api }, { time++ }, chunkSize = 1_024)

        init {
            Settings(db).connection = Connection("https://pc:8765", "AA", "token-$name", server.storeId, server.epoch, "device-$name")
        }

        suspend fun attachPhoto(entry: String) =
            repo.attach(entry, media.import(photo.inputStream(), "image/jpeg", "IMG_1.jpg", 1_000), "image", 4000, 3000)

        fun waitingFiles() = db.benbowQueries.pendingUploadCount().executeAsOne()
    }

    @Test
    fun attachedPhotoSyncsAndUploadsInChunks() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val entry = phone.repo.createEntry("2026-10-13")
        val id = phone.attachPhoto(entry)
        assertEquals(listOf(id), phone.repo.entry(entry).first()!!.attachments.map { it.meta.id }, "shown right away, offline")
        assertEquals(1, phone.repo.journal().first().single().mediaCount)
        assertEquals(sha256(photo), phone.repo.journal().first().single().cover)

        phone.engine.sync()
        assertEquals(1, phone.media.uploadPending())
        assertEquals(5, phone.api.chunksAccepted)
        assertContentEquals(photo, server.blobs[sha256(photo)])
        assertEquals(sha256(photo), server.store.entry(entry)!!.attachments.getValue(id).meta.sha256)
        assertEquals(0, phone.waitingFiles())
        assertEquals(0, phone.media.uploadPending(), "nothing twice")
    }

    @Test
    fun anInterruptedUploadResumesWhereTheServerStopped() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        phone.attachPhoto(phone.repo.createEntry("2026-10-13"))
        phone.api.failAfterChunks = 2
        assertFailsWith<IOException> { phone.media.uploadPending() }
        assertEquals(1, phone.waitingFiles(), "kept until the server confirms it")

        phone.api.failAfterChunks = null
        assertEquals(1, phone.media.uploadPending())
        assertEquals(5, phone.api.chunksAccepted, "the first two chunks weren't sent again")
        assertContentEquals(photo, server.blobs[sha256(photo)])
    }

    /** A file the server doesn't answer for (2026-10-19: one held 65 others back for hours) is skipped for now. */
    @Test
    fun oneStuckFileDoesNotHoldBackTheOthers() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val entry = phone.repo.createEntry("2026-10-19")
        val drawing = phone.media.import(Random(4).nextBytes(3_000).inputStream(), "image/jpeg", "117686.jpg", 900)
        phone.repo.attach(entry, drawing, "image", 800, 600)
        phone.attachPhoto(entry)
        phone.engine.sync()
        phone.api.stuck = setOf(drawing.sha256)

        assertFailsWith<IOException>("the sync still reports it") { phone.media.uploadPending() }
        assertContentEquals(photo, server.blobs[sha256(photo)], "the file queued behind it went through")
        assertEquals(1, phone.waitingFiles(), "the stuck one is kept for the next try")

        phone.api.stuck = emptySet()
        assertEquals(1, phone.media.uploadPending())
        assertEquals(0, phone.waitingFiles())
    }

    @Test
    fun anotherDeviceDownloadsAndVerifies() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val entry = phone.repo.createEntry("2026-10-13")
        phone.attachPhoto(entry)
        phone.engine.sync()
        phone.media.uploadPending()

        val desktop = Device(server, "desktop")
        desktop.engine.sync()
        val attachment = desktop.repo.entry(entry).first()!!.attachments.single()
        assertEquals(null, desktop.media.localFile(attachment.meta.sha256))
        desktop.api.corruptDownloads = true
        assertFailsWith<IllegalStateException> { desktop.media.file(attachment.meta.sha256) }
        assertEquals(null, desktop.media.localFile(attachment.meta.sha256), "a bad download is never kept")
        desktop.api.corruptDownloads = false
        assertContentEquals(photo, desktop.media.file(attachment.meta.sha256).readBytes())
        assertEquals(0, desktop.waitingFiles(), "a download is not something to upload")
    }

    @Test
    fun theCacheNeverDropsWhatTheServerHasNotSafelyGot() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val video = Random(9).nextBytes(1_500_000)
        val entry = phone.repo.createEntry("2026-10-13")
        val file = phone.media.import(video.inputStream(), "video/mp4", "VID.mp4", null)
        phone.repo.attach(entry, file, "video", preview = phone.media.import(photo.inputStream(), "image/jpeg", null, null))
        assertEquals(0, phone.media.evict(capBytes = 0), "not on the server yet")

        phone.engine.sync()
        phone.media.uploadPending()
        assertEquals(0, phone.media.evict(capBytes = 0), "confirmed, but the server's backup may not have copied it yet")
        assertEquals(video.size.toLong(), phone.media.evict(capBytes = 0, settleMs = 0))
        assertEquals(null, phone.media.localFile(file.sha256))
        assertTrue(phone.media.localFile(sha256(photo)) != null, "previews stay so lists never wait")
        assertContentEquals(video, phone.media.file(file.sha256).readBytes(), "fetched again when needed")
    }

    /** «Место» (ADR-041): what the device keeps and what «Освободить» may let go, by the cache's own rule. */
    @Test
    fun usageSaysWhatMayGoAndFreeingLetsGoExactlyThat() = runTest {
        val server = FakeServer()
        val big = Random(11).nextBytes(1_300_000)
        server.blobs[sha256(big)] = big
        val desktop = Device(server, "desktop")
        desktop.media.file(sha256(big)) // a download: the server has it
        val captured = desktop.media.import(Random(12).nextBytes(1_100_000).inputStream(), "video/mp4", "VID.mp4", null)
        desktop.repo.attach(desktop.repo.createEntry("2026-10-21"), captured, "video") // not on the server yet
        assertEquals(MediaUsage(2_400_000, 1_300_000), desktop.media.usage().first())
        assertEquals(1_300_000L, desktop.media.evict(capBytes = 0))
        assertEquals(MediaUsage(1_100_000, 0), desktop.media.usage().first())
    }

    @Test
    fun downloadsGoLeastRecentlyViewedFirst() = runTest {
        val server = FakeServer()
        val big = List(2) { Random(it).nextBytes(1_200_000) }
        big.forEach { server.blobs[sha256(it)] = it }
        val desktop = Device(server, "desktop")
        val (old, recent) = big.map { desktop.media.file(sha256(it)) }
        desktop.media.localFile(recent.name) // viewed again
        assertEquals(1_200_000L, desktop.media.evict(capBytes = 1_500_000))
        assertTrue(!old.exists() && recent.exists())
    }

    @Test
    fun aServerOutOfSpaceKeepsTheFileAndTriesAgain() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        phone.attachPhoto(phone.repo.createEntry("2026-10-13"))
        phone.api.uploadsRefused = ApiException(507, "no_space", "Not enough space")
        assertFailsWith<ApiException> { phone.media.uploadPending() }
        assertEquals(1, phone.waitingFiles())
        phone.api.uploadsRefused = null
        assertEquals(1, phone.media.uploadPending())
    }

    @Test
    fun removingHidesTheAttachmentEverywhere() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val entry = phone.repo.createEntry("2026-10-13")
        val id = phone.attachPhoto(entry)
        phone.engine.sync()
        phone.repo.removeAttachment(entry, id)
        phone.engine.sync()
        assertEquals(false, server.store.entry(entry)!!.attachments.getValue(id).present)
        assertTrue(phone.repo.entry(entry).first()!!.attachments.isEmpty())
        assertEquals(0, phone.repo.journal().first().single().mediaCount)
    }

    @Test
    fun aRestoredServerGetsLinksAndFilesBack() = runTest {
        val server = FakeServer()
        val phone = Device(server, "phone")
        val entry = phone.repo.createEntry("2026-10-13")
        phone.engine.sync()
        val backup = server.restoredCopy(epoch = 2, withFiles = false) // the media folder was lost too
        val id = phone.attachPhoto(entry)
        phone.engine.sync()
        phone.media.uploadPending()

        phone.api.server = backup
        phone.engine.sync()
        assertTrue(backup.store.entry(entry)!!.attachments.containsKey(id), "the link came back")
        assertEquals(1, phone.media.uploadPending(), "and the file is uploaded again")
        assertContentEquals(photo, backup.blobs[sha256(photo)])
    }
}
