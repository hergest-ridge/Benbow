package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Отчёт для отправки»: the journals and the server's state in one ZIP, and nothing of the journal or its secrets. */
class AdminReportTest {
    private val data = Files.createTempDirectory("benbow-report")
    private val db = Database.open(data, "test")

    @Test
    fun theReportHoldsLogsAndStateButNoSecretsAndOnlyThisComputerGetsIt() {
        ServerConfig.load(data)
        val password = Properties().apply { data.resolve(ServerConfig.FILE_NAME).inputStream().use(::load) }.getProperty("tls.password")
        data.resolve("logs").createDirectories().resolve("server.log").writeText("2026-10-23 14:07:15 INFO benbow - started\n")
        val devices = Devices(db).also { it.pair("Pixel", "android") }
        var loopback = true
        val ctx = ServerContext(
            db,
            devices,
            PairingCodes(),
            "AA:BB",
            data,
            8765,
            isLoopback = { loopback },
            blobs = BlobStore(db, data.resolve("media")),
        )
        testApplication {
            application { benbowModule(ctx) }
            val response = client.get("/admin/report.zip")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue("benbow-report-" in response.headers[HttpHeaders.ContentDisposition].orEmpty())
            val entries = ZipInputStream(response.readRawBytes().inputStream()).use { z ->
                generateSequence { z.nextEntry }.associate { it.name to z.readBytes().decodeToString() }
            }
            assertEquals(setOf("report.txt", "logs/server.log"), entries.keys)
            val report = entries.getValue("report.txt")
            assertTrue(SERVER_VERSION in report && db.storeId in report && "Pixel (android)" in report, report)
            assertTrue("tls.password=(hidden)" in report, report)
            assertFalse(password in report, "the keystore's password stays home")
            assertTrue("started" in entries.getValue("logs/server.log"))

            loopback = false
            assertEquals(HttpStatusCode.Forbidden, client.get("/admin/report.zip").status)
            assertFalse("Отчёт для отправки" in client.get("/").readRawBytes().decodeToString(), "not on the page others see")
        }
    }
}
