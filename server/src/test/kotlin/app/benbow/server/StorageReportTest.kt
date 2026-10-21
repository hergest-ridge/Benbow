package app.benbow.server

import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.StorageReport
import app.benbow.core.sync.AttachmentMeta
import app.benbow.core.sync.CreateEntry
import app.benbow.core.sync.Hlc
import app.benbow.core.sync.PutAttachment
import app.benbow.core.sync.SetField
import app.benbow.core.sync.SyncRules
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.api.spaceSection
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.SqliteSyncStore
import app.benbow.server.storage.StorageReports
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
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/** «Место» (ADR-041, 7.18 M4): the server counts its files once each, by kind, life and month, and says so to devices. */
class StorageReportTest {
    private val dir = Files.createTempDirectory("benbow-storage")
    private val db = Database.open(dir, "test")
    private val blobs = BlobStore(db, dir.resolve("media"))
    private val reports = StorageReports(db, blobs, dir, mirror = { null })
    private var clock = 1L

    private fun hlc() = Hlc(clock++, 0, "test")

    /** A file of [size] random bytes placed on the server; its SHA-256. */
    private fun file(size: Int, seed: Int): String {
        val bytes = Random(seed).nextBytes(size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val upload = blobs.start(sha, size.toLong(), "application/octet-stream", "f$seed.bin", null, null) as BlobStore.Start.Upload
        blobs.append(upload.id, 0, bytes)
        return sha
    }

    private fun entry(
        id: String,
        day: String,
        vararg links: Pair<String, AttachmentMeta>,
        trashed: Boolean = false,
        present: Boolean = true,
    ) {
        val ops = buildList {
            add(CreateEntry("c-$id", id, "journal", day, 1))
            links.forEach { (attachment, meta) -> add(PutAttachment("a-$id-$attachment", id, meta, 1.0, present = present, hlc = hlc())) }
            if (trashed) add(SetField("t-$id", id, "deleted", "true", hlc()))
        }
        SyncRules(SqliteSyncStore(db, "device")).push(ops)
    }

    private fun meta(id: String, sha: String, size: Int, kind: String, preview: String? = null) =
        AttachmentMeta(id, sha, size.toLong(), "application/octet-stream", kind, previewSha256 = preview)

    @Test
    fun eachFileCountsOnceByKindLifeAndMonthAndDevicesGetIt() {
        val video = file(5_000, 1)
        val frame = file(300, 2)
        val voice = file(1_200, 3)
        val orphan = file(70, 4)
        entry("live", "2026-10-21", "v" to meta("v", video, 5_000, "video", preview = frame))
        entry("old", "2026-09-03", "w" to meta("w", video, 5_000, "video"), trashed = true)
        entry("off", "2026-08-15", "s" to meta("s", voice, 1_200, "voice"), present = false)

        val report = reports.report()
        assertEquals(4, report.files)
        assertEquals(6_570, report.bytes)
        assertEquals(mapOf("video" to 5_000L, "preview" to 300L, "sound" to 1_200L, "file" to 70L), report.kinds)
        assertEquals(mapOf("live" to 5_300L, "removed" to 1_200L, "unreferenced" to 70L), report.states)
        assertEquals(listOf("2026-08", "2026-10"), report.months.map { it.month })
        assertTrue(report.disk!!.total > 0 && report.database > 0, "the disk and the database are measured")
        assertTrue(orphan.isNotEmpty())

        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", dir, 8765, blobs = blobs, storage = reports)
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ClientContentNegotiation) { json() } }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/storage").status)
            val token = client.post("/api/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(ctx.pairing.issue().first, "S24+", "android"))
            }.body<PairResponse>().token
            val served = client.get("/api/v1/storage") { bearerAuth(token) }.body<StorageReport>()
            assertEquals(report.kinds, served.kinds)
            assertEquals(report.states, served.states)
        }
    }

    @Test
    fun theCountIsKeptUntilTheJournalOrTheFilesChange() {
        val first = reports.report()
        assertEquals(0, first.files)
        assertEquals(first.counted, reports.report().counted, "nothing changed: the same count")
        file(100, 9)
        assertEquals(1, reports.report().files)
    }

    @Test
    fun theAdminPageSaysEveryNumberInWords() {
        val sha = file(2_048, 5)
        entry("e", "2026-10-21", "p" to meta("p", sha, 2_048, "image"))
        val html = spaceSection(reports)
        assertTrue("Фото" in html && "2${Char(0x00A0)}КБ" in html && "В записях" in html && "<svg class=\"bar\"" in html, html)
        assertTrue("style=" !in html, "the admin page's CSP allows no inline styles")
    }
}
