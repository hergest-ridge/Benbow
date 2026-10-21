package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.Sha256
import app.benbow.core.api.KnownEntry
import app.benbow.core.api.PairResponse
import app.benbow.core.api.PushResponse
import app.benbow.core.api.ReconcileResponse
import app.benbow.core.api.RevisionInfo
import app.benbow.core.api.ServerInfo
import app.benbow.core.api.SpeechModelInfo
import app.benbow.core.api.UploadStart
import app.benbow.core.api.UploadStatus
import app.benbow.core.calendar.CalendarOp
import app.benbow.core.calendar.CalendarPullResponse
import app.benbow.core.sync.PullResponse
import app.benbow.core.sync.RevisionRecord
import app.benbow.core.sync.SyncOp
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media in the browser (ADR-029): blobs in IndexedDB, uploaded in slices, cached downloads. */
class BrowserMediaTest {
    /** The server's side of uploads and downloads, as far as the media store uses it. */
    private class BlobServer : BenbowApi {
        val blobs = mutableMapOf<String, ByteArray>()
        val chunks = mutableListOf<Int>()
        private val uploads = mutableMapOf<String, Pair<UploadStart, ByteArray>>()

        override suspend fun startUpload(file: UploadStart): UploadStatus {
            if (file.sha256 in blobs) return UploadStatus(null, file.size, true)
            uploads[file.sha256] = file to ByteArray(0)
            return UploadStatus(file.sha256, 0, false)
        }

        override suspend fun uploadChunk(id: String, offset: Long, data: ByteArray): UploadStatus {
            val (start, sofar) = uploads.getValue(id)
            check(offset == sofar.size.toLong())
            chunks += data.size
            val now = sofar + data
            if (now.size.toLong() < start.size) {
                uploads[id] = start to now
                return UploadStatus(id, now.size.toLong(), false)
            }
            if (Sha256.hex(now) != start.sha256) throw ChecksumMismatchException()
            blobs[start.sha256] = now
            uploads.remove(id)
            return UploadStatus(null, now.size.toLong(), true)
        }

        override suspend fun downloadBlob(sha256: String, write: suspend (ByteReadChannel) -> Unit): String {
            write(ByteReadChannel(blobs[sha256] ?: throw ApiException(404, "not_found", "No such file")))
            return "video/mp4"
        }

        override suspend fun info(): ServerInfo = TODO()

        override suspend fun pair(code: String, deviceName: String, platform: String): PairResponse = TODO()

        override suspend fun push(ops: List<SyncOp>): PushResponse = TODO()

        override suspend fun pull(cursor: Long, limit: Int): PullResponse = TODO()

        override suspend fun revisions(entryId: String): List<RevisionInfo> = TODO()

        override suspend fun revision(id: String): RevisionRecord = TODO()

        override suspend fun reconcile(entries: List<KnownEntry>): ReconcileResponse = TODO()

        override suspend fun calendarPush(ops: List<CalendarOp>): PushResponse = TODO()

        override suspend fun calendarPull(cursor: Long, limit: Int): CalendarPullResponse = TODO()

        override suspend fun speechModel(): SpeechModelInfo = TODO()

        override suspend fun downloadSpeechFile(name: String, from: Long, write: suspend (ByteReadChannel, Boolean) -> Unit) = TODO()

        override suspend fun downloadApp(from: Long, write: suspend (ByteReadChannel, Boolean) -> Unit) = TODO()
    }

    private class Device(val shelf: MemoryShelf, val db: BenbowDb, val server: BlobServer, var clock: Long = 1_000) {
        val media = BrowserMedia(db, { server }, { clock }, shelf, chunkSize = 1000)
        val repo = JournalRepository(db, { clock })
    }

    private suspend fun device(shelf: MemoryShelf = MemoryShelf(), server: BlobServer = BlobServer()): Device {
        val db = BenbowDb(BrowserSqlDriver.open("benbow.db", BenbowDb.Schema, shelf))
        Settings(db).connection = Connection("https://pc:8765", "", "token", "store", 1, "device")
        return Device(shelf, db, server)
    }

    private val video = ByteArray(2_500) { (it * 7 % 251).toByte() }
    private val sha = Sha256.hex(video)

    @Test
    fun anAttachedFileIsStoredBeforeTheEntryRefersToIt() = runTest {
        val phone = device()
        val file = phone.media.import(BlobSource(blobOf(video.toUint8Array(), "video/mp4")), "video/mp4", "walk.mp4", 5)
        assertEquals(sha, file.sha256, "the browser's SHA-256 matches ours")
        assertEquals(video.size.toLong(), file.size)
        assertContentEquals(video, readBlob(assertNotNull(phone.shelf.files["media/$sha"])), "in IndexedDB once import returns")
        assertFalse(phone.media.unsaved)
    }

    @Test
    fun aCapturedFileUploadsInSlicesAndSurvivesAReload() = runTest {
        val shelf = MemoryShelf()
        val server = BlobServer()
        val phone = device(shelf, server)
        val entry = phone.repo.createNote("2026-10-15", "Прогулка")
        phone.repo.attach(entry, phone.media.import(byteSourceOf(video), "video/mp4", "walk.mp4", 5), "video")
        assertTrue(phone.media.awaitingUpload(sha).first())

        // The tab was closed before the upload: the next visit finds the file in IndexedDB and sends it.
        val again = Device(shelf, phone.db, server)
        assertNull(again.media.localFile(sha), "not in memory after a reload")
        assertEquals(1, again.media.uploadPending())
        assertEquals(listOf(1000, 1000, 500), server.chunks, "read from the blob in slices")
        assertContentEquals(video, server.blobs[sha])
        assertFalse(again.media.awaitingUpload(sha).first())
        assertContentEquals(video, again.media.file(sha).readBytes())
    }

    @Test
    fun aDownloadIsVerifiedAndKept() = runTest {
        val server = BlobServer().apply { blobs[sha] = video }
        val pc = device(server = server)
        val file = pc.media.file(sha)
        assertEquals(video.size.toLong(), file.size)
        assertContentEquals(video, readBlob(assertNotNull(pc.shelf.files["media/$sha"])))

        server.blobs[sha] = video.copyOf().also { it[0] = 99 } // corrupted on the server
        val other = device(server = server)
        assertFailsWith<IllegalStateException> { other.media.file(sha) }
        assertNull(other.shelf.files["media/$sha"], "a file that doesn't match its hash is never kept")
    }

    @Test
    fun evictionKeepsWhatTheServerMayNotHave() = runTest {
        val server = BlobServer().apply { blobs[sha] = video }
        val pc = device(server = server)
        val big = ByteArray(1_200_000) { (it % 13).toByte() }
        val entry = pc.repo.createNote("2026-10-15", "Фото")
        val mine = pc.media.import(byteSourceOf(big), "image/png", "photo.png", 5)
        pc.repo.attach(entry, mine, "image")
        val downloaded = ByteArray(1_100_000) { (it % 17).toByte() }.also { server.blobs[Sha256.hex(it)] = it }
        pc.media.file(Sha256.hex(downloaded))

        assertEquals(1_100_000, pc.media.evict(capBytes = 0), "only the download: the photo hasn't reached the server")
        assertNull(pc.db.benbowQueries.selectMedia(Sha256.hex(downloaded)).executeAsOneOrNull())
        assertNotNull(pc.shelf.files["media/${mine.sha256}"])
    }
}
