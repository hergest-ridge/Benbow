package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.api.installCard
import app.benbow.server.api.qrSvg
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The first install from the server (ADR-042, U4): the QR code, the public page and the APK without a token. */
class AppInstallTest {
    private val apk = Random(5).nextBytes(200_000)

    private fun release(): Path {
        val dir = Files.createTempDirectory("benbow-android")
        dir.resolve("benbow-0.13.0.apk").writeBytes(apk)
        val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        dir.resolve(AppRelease.MANIFEST).writeText(
            """{"version_code": 13000, "version_name": "0.13.0", "package": "app.benbow", "file": "benbow-0.13.0.apk", "sha256": "$sha", "size": ${apk.size}}""",
        )
        return dir
    }

    private fun context(withApp: Boolean, loopback: Boolean = false): ServerContext {
        val dir = Files.createTempDirectory("benbow-install")
        val db = Database.open(dir, "test")
        return ServerContext(
            db,
            Devices(db),
            PairingCodes(),
            "AA:BB",
            dir,
            8765,
            isLoopback = { loopback },
            app = if (withApp) AppRelease.load(release()) else null,
        )
    }

    /** The SVG's modules drawn as pixels and read back by ZXing's own reader. */
    private fun decode(svg: String): String {
        val size = Regex("""viewBox="0 0 (\d+) (\d+)"""").find(svg)!!.groupValues[1].toInt()
        val scale = 4
        val pixels = IntArray(size * scale * size * scale) { 0xFFFFFF }
        Regex("""M(\d+) (\d+)h1v1h-1z""").findAll(svg).forEach { m ->
            val (x, y) = m.destructured
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[(y.toInt() * scale + dy) * size * scale + x.toInt() * scale + dx] = 0
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size * scale, size * scale, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun theQrCodeReadsBackAsTheInstallPagesAddress() {
        val url = "https://192.168.1.5:8765/install"
        assertEquals(url, decode(qrSvg(url)))
        val card = installCard(context(withApp = true), listOf("192.168.1.5", "10.0.0.7"))
        assertEquals(url, decode(card.substringAfter("<div class=\"qr\">").substringBefore("</div>")))
        assertTrue("10.0.0.7" in card, "the other addresses are named")
        assertEquals("", installCard(context(withApp = false), listOf("192.168.1.5")), "nothing to install, no card")
    }

    @Test
    fun aPhoneWithoutTheAppGetsThePageAndTheApkWithoutAToken() {
        val ctx = context(withApp = true)
        testApplication {
            application { benbowModule(ctx) }
            val page = client.get("/install") { header(HttpHeaders.Host, "192.168.1.5:8765") }
            assertEquals(HttpStatusCode.OK, page.status)
            val text = page.bodyAsText()
            assertTrue("Скачать Benbow 0.13.0" in text && "192.168.1.5" in text && "8765" in text, text.take(400))
            assertFalse(ctx.db.storeId in text, "nothing of the journal on a public page")
            val file = client.get("/install/benbow.apk")
            assertEquals(HttpStatusCode.OK, file.status)
            assertEquals("application/vnd.android.package-archive", file.headers[HttpHeaders.ContentType])
            assertTrue("benbow-0.13.0.apk" in file.headers[HttpHeaders.ContentDisposition].orEmpty())
            assertContentEquals(apk, file.readRawBytes())
        }
    }

    @Test
    fun aServerWithoutTheAppSaysSoOnItsPage() {
        val ctx = context(withApp = false)
        testApplication {
            application { benbowModule(ctx) }
            assertTrue("нет приложения" in client.get("/install").bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.get("/install/benbow.apk").status)
        }
    }
}
