package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.MediaMove
import app.benbow.server.storage.PairingCodes
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Медиафайлы → Перенести» on the admin page (ADR-042, P5): the plan first, then the move, its stop and its cancel. */
class MediaMoveAdminTest {
    private val data = Files.createTempDirectory("benbow-move-page")
    private val db = Database.open(data, "test")
    private val blobs = BlobStore(db, data.resolve("media"))
    private val target = Files.createTempDirectory("benbow-new-disk").resolve("Benbow-media")
    private var failing = false

    init {
        ServerConfig.load(data)
        repeat(3) { seed ->
            val bytes = Random(seed).nextBytes(1_500)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val upload =
                assertIs<BlobStore.Start.Upload>(blobs.start(sha, bytes.size.toLong(), "image/jpeg", "p.jpg", 1_792_000_000_000, null))
            blobs.append(upload.id, 0, bytes)
        }
    }

    private val move = MediaMove(
        db,
        blobs,
        data,
        TestDisks(),
        runner = { it.run() },
        drill = { if (it == "file" && failing) throw IOException("There is not enough space on the disk") },
    )

    private fun page(block: suspend (HttpClient) -> Unit) {
        val ctx =
            ServerContext(db, Devices(db), PairingCodes(), "AA:BB", data, 8765, isLoopback = { true }, blobs = blobs, mediaMove = move)
        testApplication {
            application { benbowModule(ctx) }
            block(createClient { followRedirects = false })
        }
    }

    private suspend fun HttpClient.post(path: String, folder: String? = null) =
        submitForm(path, parameters { folder?.let { append("media-path", it) } })

    @Test
    fun theFolderIsCheckedAndShownBeforeTheMoveStarts() = page { client ->
        assertTrue("Перенести в другую папку" in client.get("/").bodyAsText())
        val wrong = client.post("/admin/media/check", data.resolve("media").resolve("inner").toString())
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertTrue("не подходит" in wrong.bodyAsText())

        val plan = client.post("/admin/media/check", target.toString()).bodyAsText()
        assertTrue("Скопируется 3 файла" in plan, plan.substringAfter("Перенести в").take(300))
        assertTrue("Начать перенос" in plan)
        assertTrue(!target.exists(), "a look makes nothing")
        assertNull(move.moves().current)

        assertEquals(HttpStatusCode.Found, client.post("/admin/media/move", target.toString()).status)
        assertEquals(target, ServerConfig.load(data).mediaRoot)
        val live = client.get("/?part=media").bodyAsText()
        assertTrue(target.toString() in live && "Перенесено" in live, live)
    }

    @Test
    fun aStoppedMoveCanGoOnOrBeCancelled() = page { client ->
        failing = true
        assertEquals(HttpStatusCode.Found, client.post("/admin/media/move", target.toString()).status)
        val stopped = client.get("/?part=media").bodyAsText()
        assertTrue("остановлен" in stopped && "not enough space" in stopped && "Продолжить" in stopped, stopped)
        assertEquals(data.resolve("media"), blobs.root)

        assertEquals(HttpStatusCode.Found, client.post("/admin/media/cancel").status)
        assertNull(move.moves().current)
        assertTrue("Перенести в другую папку" in client.get("/").bodyAsText(), "a new move can be asked for")

        // Into the same folder again (a cancelled move's copy is its own), stopped again, then let go on.
        assertEquals(HttpStatusCode.Found, client.post("/admin/media/move", target.toString()).status)
        assertTrue("остановлен" in client.get("/?part=media").bodyAsText())
        assertEquals(data.resolve("media"), ServerConfig.load(data).mediaRoot ?: data.resolve("media"))
        failing = false
        assertEquals(HttpStatusCode.Found, client.post("/admin/media/retry").status)
        assertEquals(target, ServerConfig.load(data).mediaRoot)
        assertEquals(target, blobs.root)
        assertNull(move.moves().current)
    }
}
