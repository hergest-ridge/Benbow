package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The web client at /app/ (ADR-029): served from the package's web folder, compressed and cached sensibly. */
class WebAppTest {
    private fun withServer(webRoot: Path?, block: suspend ApplicationTestBuilder.() -> Unit) {
        val db = Database.inMemory()
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", null, 8765, webRoot = webRoot)
        testApplication {
            application { benbowModule(ctx) }
            block()
        }
        db.close()
    }

    private fun webFolder(): Path = Files.createTempDirectory("benbow-web").also { dir ->
        dir.resolve("index.html").writeText("<!doctype html><title>Benbow</title>")
        val wasm = ByteArray(4096) { (it % 7).toByte() }
        dir.resolve("0123456789abcdef0123.wasm").writeBytes(wasm)
        dir.resolve("0123456789abcdef0123.wasm.gz").toFile().outputStream().let(::GZIPOutputStream).use { it.write(wasm) }
    }

    @Test
    fun theAppIsServedAtSlashApp() = withServer(webFolder()) {
        val client = createClient { followRedirects = false }
        assertEquals("/app/", client.get("/app").headers[HttpHeaders.Location])

        val index = client.get("/app/")
        assertEquals(HttpStatusCode.OK, index.status)
        assertTrue(index.bodyAsText().contains("<title>Benbow</title>"))
        assertEquals("no-cache", index.headers[HttpHeaders.CacheControl], "an update shows on the next reload")

        val wasm = client.get("/app/0123456789abcdef0123.wasm") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(ContentType.Application.Wasm, wasm.headers[HttpHeaders.ContentType]?.let(ContentType::parse))
        assertEquals("gzip", wasm.headers[HttpHeaders.ContentEncoding])
        assertEquals("max-age=31536000, public", wasm.headers[HttpHeaders.CacheControl], "named by its content: cached for good")
    }

    @Test
    fun unchangedFilesAreNotSentAgain() = withServer(webFolder()) {
        val first = client.get("/app/index.html")
        val again = client.get("/app/index.html") { header(HttpHeaders.IfModifiedSince, first.headers[HttpHeaders.LastModified]!!) }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test
    fun aPackageWithoutTheWebClientSaysSo() = withServer(null) {
        val response = client.get("/app/")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("no web client"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/info").status, "the API is untouched")
    }
}
