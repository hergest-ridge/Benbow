package app.benbow.server

import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.UploadStart
import app.benbow.core.api.UploadStatus
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SyncRules
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.backup.BackupJobs
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.SqliteSyncStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.appendBytes
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlobTest {
    private val dir = Files.createTempDirectory("benbow-blobs")
    private val db = Database.open(dir, "test")
    private val blobs = BlobStore(db, dir.resolve("media"))
    private val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 123)
    private val sha = sha256(bytes)
    private val mb = 1024 * 1024

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun withServer(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) {
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", dir, 8765, blobs = blobs)
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ContentNegotiation) { json() } }
            val token = client.post("/api/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(ctx.pairing.issue().first, "S24+", "android"))
            }.body<PairResponse>().token
            block(client, token)
        }
    }

    private suspend fun HttpClient.start(token: String, sha256: String = sha, size: Long = bytes.size.toLong()) = post("/api/v1/uploads") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(UploadStart(sha256, size, "video/mp4", "VID_20261013.mp4", capturedAt = 1_791_839_667_000))
    }

    private suspend fun HttpClient.chunk(token: String, id: String, offset: Int, data: ByteArray): HttpResponse =
        patch("/api/v1/uploads/$id") {
            bearerAuth(token)
            header("Upload-Offset", offset)
            setBody(data)
        }

    @Test
    fun chunkedUploadIsVerifiedPlacedAndServedWithRanges() = withServer { client, token ->
        val upload = client.start(token).body<UploadStatus>()
        assertEquals(0, upload.offset)
        val id = upload.id!!
        assertEquals(mb.toLong(), client.chunk(token, id, 0, bytes.copyOfRange(0, mb)).body<UploadStatus>().offset)

        val stale = client.chunk(token, id, 0, bytes.copyOfRange(0, mb))
        assertEquals(HttpStatusCode.Conflict, stale.status, "a repeated chunk is refused with the offset to resume from")
        assertEquals(mb.toLong(), stale.body<UploadStatus>().offset)
        assertEquals(mb.toLong(), client.start(token).body<UploadStatus>().offset, "announcing again resumes the open upload")

        val done = client.chunk(token, id, mb, bytes.copyOfRange(mb, bytes.size)).body<UploadStatus>()
        assertTrue(done.complete)
        val blob = blobs.blob(sha)!!
        assertTrue(Regex("""\d{4}/\d{2}/\d{4}-\d{2}-\d{2}_\d{6}_${sha.take(8)}\.mp4""").matches(blob.path), blob.path)
        assertContentEquals(bytes, blobs.file(blob).readBytes())

        assertEquals(HttpStatusCode.OK, client.head("/api/v1/blobs/$sha") { bearerAuth(token) }.status)
        val range = client.get("/api/v1/blobs/$sha") {
            bearerAuth(token)
            header(HttpHeaders.Range, "bytes=100-199")
        }
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertContentEquals(bytes.copyOfRange(100, 200), range.readRawBytes())
        assertEquals(UploadStatus(null, bytes.size.toLong(), complete = true), client.start(token).body<UploadStatus>(), "dedup by hash")
        assertEquals(HttpStatusCode.NotFound, client.head("/api/v1/blobs/${"0".repeat(64)}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/blobs/$sha").status)
    }

    @Test
    fun aMissingMediaFolderAnswers503UntilItIsBack() = withServer { client, token ->
        val id = client.start(token).body<UploadStatus>().id!!
        assertTrue(client.chunk(token, id, 0, bytes).body<UploadStatus>().complete)
        val media = dir.resolve("media")
        val unplugged = dir.resolve("unplugged")
        Files.move(media, unplugged) // the disk went away (ADR-042)
        val download = client.get("/api/v1/blobs/$sha") { bearerAuth(token) }
        assertEquals(HttpStatusCode.ServiceUnavailable, download.status, "not 404: the file isn't lost, its disk is away")
        assertTrue("media_unavailable" in download.bodyAsText())
        val other = byteArrayOf(1, 2, 3)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.start(token, sha256 = sha256(other), size = 3).status, "the device keeps it")
        assertTrue(!media.exists())
        Files.move(unplugged, media)
        assertContentEquals(bytes, client.get("/api/v1/blobs/$sha") { bearerAuth(token) }.readRawBytes())
    }

    @Test
    fun bytesPastTheRecordedOffsetAfterACrashAreDropped() = withServer { client, token ->
        val id = client.start(token).body<UploadStatus>().id!!
        client.chunk(token, id, 0, bytes.copyOfRange(0, mb))
        // The server died while writing the next chunk: garbage on disk, offset never recorded.
        dir.resolve("media/.uploads/$id.part").appendBytes(Random(1).nextBytes(5000))
        assertTrue(client.chunk(token, id, mb, bytes.copyOfRange(mb, bytes.size)).body<UploadStatus>().complete)
        assertContentEquals(bytes, blobs.file(blobs.blob(sha)!!).readBytes())
    }

    @Test
    fun aFileThatDoesNotMatchItsHashIsRefused() = withServer { client, token ->
        val id = client.start(token).body<UploadStatus>().id!!
        val wrong = bytes.copyOf().also { it[42] = (it[42] + 1).toByte() }
        assertEquals(HttpStatusCode.UnprocessableEntity, client.chunk(token, id, 0, wrong).status)
        assertNull(blobs.blob(sha))
        assertEquals(0, client.start(token).body<UploadStatus>().offset, "starts over")
        assertEquals(HttpStatusCode.BadRequest, client.start(token, sha256 = "not-a-hash").status)
    }

    @Test
    fun mediaIsMirroredAndRotIsFound() = withServer { client, token ->
        val id = client.start(token).body<UploadStatus>().id!!
        client.chunk(token, id, 0, bytes)
        val blob = blobs.blob(sha)!!
        val mirror = Files.createTempDirectory("benbow-mirror")
        BackupJobs(db, dir, mirror, blobs, disks = TestDisks()).runOnce()
        assertContentEquals(bytes, mirror.resolve("media").resolve(blob.path).readBytes())

        blobs.file(blob).writeBytes(bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertEquals(listOf(blob), blobs.scrub(olderThanMillis = -1, limit = 10))
        assertTrue(blobs.file(blob).exists(), "never deleted, only reported")
    }

    @Test
    fun filesPlacedAfterARestoredSnapshotAreAdopted() = withServer { client, token ->
        val id = client.start(token).body<UploadStatus>().id!!
        client.chunk(token, id, 0, bytes)
        val placed = blobs.blob(sha)!!
        db.transaction { it.createStatement().execute("DELETE FROM blobs") } // what the older snapshot knew
        assertEquals(1, blobs.adoptUntracked())
        assertEquals(placed, blobs.blob(sha))
        assertEquals(0, blobs.adoptUntracked())
    }

    @Test
    fun theExportLinksAFileOnceItArrives() = withServer { client, token ->
        val meta = AttachmentMeta("a1", sha, bytes.size.toLong(), "image/jpeg", "image", "IMG_1.jpg")
        SyncRules(SqliteSyncStore(db)).push(
            listOf(
                CreateEntry("op1", "e1", "journal", "2026-10-13", 1),
                PutAttachment("op2", "e1", meta, 1.0, hlc = Hlc(1, 0, "phone")),
            ),
        )
        val jobs = BackupJobs(db, dir, null, blobs)
        jobs.runOnce()
        val note = dir.resolve("export/2026/10").listDirectoryEntries("*.md").single()
        assertTrue("not uploaded yet" in note.readText())

        val id = client.start(token).body<UploadStatus>().id!!
        client.chunk(token, id, 0, bytes)
        jobs.runOnce()
        val link = Regex("""!\[IMG_1\.jpg]\(<(.+)>\)""").find(note.readText())!!.groupValues[1]
        assertContentEquals(bytes, note.parent.resolve(link).readBytes(), "a relative link that opens the file")
    }

    @Test
    fun abandonedUploadsAreDropped() = withServer { client, token ->
        val id = client.start(token).body<UploadStatus>().id!!
        client.chunk(token, id, 0, bytes.copyOfRange(0, mb))
        assertEquals(1, blobs.dropStaleUploads(maxAgeMillis = -1))
        assertTrue(!dir.resolve("media/.uploads/$id.part").exists())
    }
}
