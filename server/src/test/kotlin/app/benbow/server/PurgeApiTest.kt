package app.benbow.server

import app.benbow.core.api.ApiError
import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.PurgeCommit
import app.benbow.core.api.PurgeDone
import app.benbow.core.api.PurgePreview
import app.benbow.core.api.PurgeRequest
import app.benbow.core.api.UploadStart
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
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.Purges
import app.benbow.server.storage.SqliteSyncStore
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/** `POST /api/v1/purge/preview` and `/commit` (ADR-041) as devices see them, and the upload of a purged file refused. */
class PurgeApiTest {
    private val dir = Files.createTempDirectory("benbow-purge-api")
    private val db = Database.open(dir, "test")
    private val blobs = BlobStore(db, dir.resolve("media"))
    private val purges = Purges(db, blobs, dir.resolve("db").resolve(PURGE_JOURNAL))

    @Test
    fun aDeviceAsksCommitsAndCanNotSendTheFileAgain() {
        val bytes = Random(5).nextBytes(4_000)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val upload = blobs.start(sha, 4_000, "video/mp4", "v.mp4", null, null) as BlobStore.Start.Upload
        blobs.append(upload.id, 0, bytes)
        val meta = AttachmentMeta("v", sha, 4_000, "video/mp4", "video")
        SyncRules(SqliteSyncStore(db, "phone")).push(
            listOf(
                CreateEntry("c", "old", "journal", "2026-10-21", 1),
                PutAttachment("a", "old", meta, 1.0, hlc = Hlc(1, 0, "phone")),
                CreateEntry("c2", "live", "journal", "2026-10-21", 1),
                SetField("t", "old", "deleted", "true", Hlc(2, 0, "phone")),
            ),
        )
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", dir, 8765, blobs = blobs, purges = purges)
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ClientContentNegotiation) { json() } }
            val token = client.post("/api/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(ctx.pairing.issue().first, "S24+", "android"))
            }.body<PairResponse>().token
            suspend fun preview(request: PurgeRequest) = client.post("/api/v1/purge/preview") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            assertEquals(HttpStatusCode.BadRequest, preview(PurgeRequest(entries = listOf("live"))).status, "not in the trash")
            val plan = preview(PurgeRequest(entries = listOf("old"))).body<PurgePreview>()
            assertEquals(listOf(sha), plan.files.map { it.sha256 })
            val done = client.post("/api/v1/purge/commit") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(PurgeCommit(plan.planId))
            }
            assertEquals(4_000, done.body<PurgeDone>().bytes)
            val again = client.post("/api/v1/purge/commit") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(PurgeCommit(plan.planId))
            }
            assertEquals(HttpStatusCode.Gone, again.status, "a plan is done once")
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/blobs/$sha") { bearerAuth(token) }.status)
            val refused = client.post("/api/v1/uploads") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(UploadStart(sha, 4_000, "video/mp4"))
            }
            assertEquals(HttpStatusCode.Gone, refused.status)
            assertEquals("purged", refused.body<ApiError>().error)
        }
    }
}
