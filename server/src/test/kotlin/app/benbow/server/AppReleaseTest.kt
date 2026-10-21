package app.benbow.server

import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.core.api.ServerInfo
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android app in the server package (ADR-042, U1): checked at start, described in `info`, fetched by device token. */
class AppReleaseTest {
    private val apk = Random(1).nextBytes(300_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun release(sha256: String = sha, size: Int = apk.size, file: String = "benbow-0.13.0.apk"): Path {
        val dir = Files.createTempDirectory("benbow-android")
        dir.resolve("benbow-0.13.0.apk").writeBytes(apk)
        dir.resolve(AppRelease.MANIFEST).writeText(
            """{"version_code": 13000, "version_name": "0.13.0", "file": "$file", "sha256": "$sha256", "size": $size}""",
        )
        return dir
    }

    @Test
    fun aReleaseIsOfferedOnlyWhenItsApkChecks() {
        val app = assertNotNull(AppRelease.load(release()))
        assertEquals(13000, app.info.versionCode)
        assertEquals("0.13.0", app.info.versionName)
        assertEquals(sha, app.info.sha256)
        assertNull(AppRelease.load(release(sha256 = "0".repeat(64))), "a hash that doesn't match")
        assertNull(AppRelease.load(release(size = 5)), "a size that doesn't match")
        assertNull(AppRelease.load(release(file = "../elsewhere.apk")), "only a file beside the manifest")
        assertNull(AppRelease.load(Files.createTempDirectory("benbow-no-android")), "a package without the app")
        assertNull(AppRelease.load(null))
    }

    @Test
    fun phonesSeeTheAppInInfoAndFetchItByToken() {
        val dir = Files.createTempDirectory("benbow-app")
        val db = Database.open(dir, "test")
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", dir, 8765, app = AppRelease.load(release()))
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ContentNegotiation) { json() } }
            val info = client.get("/api/v1/info").body<ServerInfo>()
            assertEquals(13000, assertNotNull(info.android).versionCode)
            assertEquals(sha, info.android!!.sha256)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/app/android").status, "only for paired devices")

            val token = client.post("/api/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(ctx.pairing.issue().first, "S24+", "android"))
            }.body<PairResponse>().token
            val whole = client.get("/api/v1/app/android") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, whole.status)
            assertEquals("application/vnd.android.package-archive", whole.headers[HttpHeaders.ContentType])
            assertContentEquals(apk, whole.readRawBytes())
            val rest = client.get("/api/v1/app/android") {
                bearerAuth(token)
                header(HttpHeaders.Range, "bytes=100000-")
            }
            assertEquals(HttpStatusCode.PartialContent, rest.status, "a cut download goes on")
            assertContentEquals(apk.copyOfRange(100_000, apk.size), rest.readRawBytes())
        }
        db.close()
    }

    @Test
    fun aServerWithoutTheAppSaysSo() {
        val dir = Files.createTempDirectory("benbow-no-app")
        val db = Database.open(dir, "test")
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", dir, 8765, isLoopback = { true })
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ContentNegotiation) { json() } }
            assertNull(client.get("/api/v1/info").body<ServerInfo>().android)
            assertTrue("в этом пакете нет" in client.get("/").bodyAsText())
            assertTrue(SERVER_VERSION.matches(Regex("""\d+\.\d+\.\d+""")), SERVER_VERSION)
        }
        db.close()
    }
}
