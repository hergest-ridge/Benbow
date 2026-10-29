package app.benbow.server

import app.benbow.core.api.FontCatalog
import app.benbow.core.api.PairRequest
import app.benbow.core.api.PairResponse
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.fonts.FontFile
import app.benbow.server.fonts.FontPack
import app.benbow.server.fonts.Fonts
import app.benbow.server.fonts.OwnFonts
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.Deflater
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Fonts from the server (ADR-052, 7.20 L4-L5): the pack in the package, the owner's own, the catalog and the files. */
class FontsTest {
    private val packDir = Path.of("packaging/fonts")
    private val caveat = packDir.resolve("caveat.ttf").readBytes()
    private val caveatBold = packDir.resolve("caveat-bold.ttf").readBytes()

    @Test
    fun thePackHasEveryFontTheOwnerPickedWithRussianLetters() {
        val pack = FontPack(packDir)
        assertEquals(33, pack.fonts.size, "every font of pack.json reads")
        val tokens = pack.fonts.map { it.info.token }
        assertEquals(tokens.distinct(), tokens)
        val bundled =
            listOf("neucha", "pangolin", "marmelad", "amatic-sc", "cormorant-infant", "philosopher", "poiret-one", "jetbrains-mono")
        assertTrue(tokens.none { it in bundled }, "the app's own eight aren't in the pack")
        for (font in pack.fonts.map { it.info }) {
            assertTrue(font.cyrillic, "${font.token} has Russian letters")
            assertTrue(font.scale in 0.9f..1.4f, "${font.token}: ${font.scale}")
            assertTrue(font.group in setOf("diary", "serif", "sans", "accent", "mono"), font.group)
            assertEquals(400, font.files.first().weight)
            font.files.forEach { assertTrue(pack.file(it.sha256)!!.exists()) }
        }
        assertEquals(7, pack.fonts.count { it.info.group == "diary" }, "the handwritten ones")
        assertEquals(emptyList(), FontPack(null).fonts)
        assertEquals(emptyList(), FontPack(Files.createTempDirectory("benbow-no-pack")).fonts)
    }

    @Test
    fun aFontIsReadAndAWoffUnpackedWhileOtherFilesAreRefused() {
        val regular = assertIs<FontFile.Read.Font>(FontFile.read(caveat)).font
        assertEquals("Caveat", regular.family)
        assertEquals(400, regular.weight)
        assertTrue(regular.cyrillic && regular.latin && !regular.italic)
        assertTrue(regular.xHeight > 0f)
        assertEquals(700, assertIs<FontFile.Read.Font>(FontFile.read(caveatBold)).font.weight)

        val unpacked = assertIs<FontFile.Read.Font>(FontFile.read(woff(caveat))).font
        assertEquals("Caveat", unpacked.family)
        assertTrue(unpacked.cyrillic)
        assertEquals(caveat.size, unpacked.bytes.size, "the same tables, laid out again")

        val woff2 = "wOF2".toByteArray() + ByteArray(100)
        assertTrue("WOFF2" in assertIs<FontFile.Read.Refused>(FontFile.read(woff2)).reason)
        assertTrue(".ttc" in assertIs<FontFile.Read.Refused>(FontFile.read("ttcf".toByteArray() + ByteArray(100))).reason)
        assertIs<FontFile.Read.Refused>(FontFile.read(Random(1).nextBytes(5_000)))
        assertIs<FontFile.Read.Refused>(FontFile.read(caveat.copyOf(2_000)), "a cut file")
        assertIs<FontFile.Read.Refused>(FontFile.read(ByteArray(FontFile.MAX_SIZE + 1)))
    }

    @Test
    fun ownFontsGrowByFamilyAndNothingIsDeleted() {
        val dir = Files.createTempDirectory("benbow-own-fonts")
        val own = OwnFonts(dir, today = { LocalDate.of(2026, 10, 29) })
        val first = assertIs<OwnFonts.Added.Done>(own.add("MyHand.ttf", caveat, 1))
        assertEquals("own:caveat", first.font.token)
        val both = assertIs<OwnFonts.Added.Done>(own.add("MyHand-Bold.ttf", caveatBold, 2)).font
        assertEquals(listOf(400, 700), both.files.map { it.weight }, "the bold joins the same font")
        assertEquals(1, own.all().size)
        assertFalse(assertIs<OwnFonts.Added.Done>(own.add("MyHand.ttf", caveat, 3)).replaced, "the same file again changes nothing")

        // Another file of the same weight takes its place; the old one waits in removed/.
        val replaced = assertIs<OwnFonts.Added.Done>(own.add("MyHand.woff", woff(caveat), 4))
        assertTrue(replaced.replaced)
        val removed = dir.resolve(OwnFonts.REMOVED).resolve("2026-10-29")
        assertEquals(1, removed.listDirectoryEntries().size)
        assertTrue(own.file(first.font.files.single().sha256) == null, "a replaced file isn't served")

        val italic = assertIs<OwnFonts.Added.Refused>(own.add("MyHand-Italic.ttf", italic(caveat), 5))
        assertTrue("курсив" in italic.reason, italic.reason)
        assertIs<OwnFonts.Added.Refused>(own.add("notes.txt", "hello".toByteArray(), 6))

        assertTrue(own.setScale("own:caveat", 1.3f))
        assertEquals(1.3f, own.all().single().scale)
        assertTrue(own.remove("own:caveat"))
        assertEquals(emptyList(), own.all())
        assertEquals(3, removed.listDirectoryEntries().size, "every file it had is kept")
        assertEquals("мой-почерк-2", OwnFonts.slug("  Мой почерк (2) "))
    }

    @Test
    fun devicesReadTheCatalogAndFetchAFontsFiles() {
        val dir = Files.createTempDirectory("benbow-fonts-api")
        val db = Database.open(dir, "test")
        val own = OwnFonts(dir.resolve(OwnFonts.DIR))
        val mine = assertIs<OwnFonts.Added.Done>(own.add("MyHand.ttf", caveat, 1)).font
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", dir, 8765, fonts = Fonts(FontPack(packDir), own))
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ContentNegotiation) { json() } }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/fonts").status, "only for paired devices")
            val token = client.post("/api/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(ctx.pairing.issue().first, "S24+", "android"))
            }.body<PairResponse>().token
            val catalog = client.get("/api/v1/fonts") { bearerAuth(token) }.body<FontCatalog>()
            assertEquals(34, catalog.fonts.size)
            assertEquals("own", catalog.fonts.last().group)
            val file = mine.files.single()
            val fetched = client.get("/api/v1/fonts/files/${file.sha256}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertContentEquals(caveat, fetched.readRawBytes())
            val caveatPack = catalog.fonts.first { it.token == "caveat" }.files.first()
            assertEquals(
                caveatPack.size,
                client.get("/api/v1/fonts/files/${caveatPack.sha256}") {
                    bearerAuth(token)
                }.readRawBytes().size.toLong(),
            )
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/fonts/files/${"0".repeat(64)}") { bearerAuth(token) }.status)
        }
        db.close()
    }

    @Test
    fun aServerWithoutFontsHasAnEmptyCatalog() {
        val dir = Files.createTempDirectory("benbow-no-fonts")
        val db = Database.open(dir, "test")
        val ctx = ServerContext(db, Devices(db), PairingCodes(), "AA:BB", dir, 8765)
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { install(ContentNegotiation) { json() } }
            val token = client.post("/api/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(ctx.pairing.issue().first, "S24+", "android"))
            }.body<PairResponse>().token
            assertEquals(emptyList(), client.get("/api/v1/fonts") { bearerAuth(token) }.body<FontCatalog>().fonts)
        }
        db.close()
    }

    @Test
    fun theAdminPageAddsAFontAndShowsEachInItsOwnLetters() {
        val dir = Files.createTempDirectory("benbow-fonts-admin")
        val db = Database.open(dir, "test")
        val own = OwnFonts(dir.resolve(OwnFonts.DIR))
        val ctx =
            ServerContext(db, Devices(db), PairingCodes(), "AA:BB", dir, 8765, isLoopback = { true }, fonts = Fonts(FontPack(packDir), own))
        testApplication {
            application { benbowModule(ctx) }
            val client = createClient { followRedirects = false }
            fun upload(name: String, bytes: ByteArray) = formData {
                append("font", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$name\"") })
            }
            val added = client.submitFormWithBinaryData("/admin/fonts", upload("Мой почерк.ttf", caveat))
            assertEquals(HttpStatusCode.Found, added.status)
            assertEquals("/?done=font#fonts", added.headers[HttpHeaders.Location])
            assertEquals("own:caveat", own.all().single().token)

            val refused = client.submitFormWithBinaryData("/admin/fonts", upload("font.woff2", "wOF2".toByteArray() + ByteArray(64)))
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue("WOFF2" in refused.bodyAsText())

            val page = client.get("/").bodyAsText()
            assertTrue("id=\"fonts\"" in page && "Мой почерк.ttf" in page && "Набор Benbow" in page)
            val faces = client.get("/admin/fonts/faces.css").bodyAsText()
            assertEquals(
                ctx.fonts!!.catalog().fonts.sumOf {
                    it.files.size
                },
                Regex("@font-face").findAll(faces).count(),
                "each file, bold ones too",
            )
            val sha = own.all().single().files.single().sha256
            assertContentEquals(caveat, client.get("/admin/fonts/files/$sha").readRawBytes())
        }
        db.close()
    }

    /** [ttf] as a WOFF 1.0, each table packed with zlib. */
    private fun woff(ttf: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(ttf)
        val count = b.getShort(4).toInt()
        class Table(val tag: Int, val checksum: Int, val data: ByteArray, val packed: ByteArray)
        val tables = (0 until count).map { i ->
            val r = 12 + i * 16
            val data = ttf.copyOfRange(b.getInt(r + 8), b.getInt(r + 8) + b.getInt(r + 12))
            val deflater = Deflater().apply {
                setInput(data)
                finish()
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            Table(b.getInt(r), b.getInt(r + 4), data, out.toByteArray().takeIf { it.size < data.size } ?: data)
        }
        val header = 44 + 20 * count
        val woff = ByteBuffer.allocate(header + tables.sumOf { (it.packed.size + 3) and 3.inv() })
        woff.putInt(0x774F4646).putInt(b.getInt(0)).putInt(woff.capacity()).putShort(count.toShort()).putShort(0).putInt(ttf.size)
            .putShort(1).putShort(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
        var offset = header
        for (t in tables) {
            woff.putInt(t.tag).putInt(offset).putInt(t.packed.size).putInt(t.data.size).putInt(t.checksum)
            offset += (t.packed.size + 3) and 3.inv()
        }
        for (t in tables) {
            woff.put(t.packed)
            repeat(((t.packed.size + 3) and 3.inv()) - t.packed.size) { woff.put(0) }
        }
        return woff.array()
    }

    /** [ttf] marked italic in its OS/2 table. */
    private fun italic(ttf: ByteArray): ByteArray {
        val copy = ttf.copyOf()
        val b = ByteBuffer.wrap(copy)
        val os2 = (0 until b.getShort(4).toInt()).map { 12 + it * 16 }.first { String(copy, it, 4) == "OS/2" }.let { b.getInt(it + 8) }
        b.putShort(os2 + 62, (b.getShort(os2 + 62).toInt() or 1).toShort())
        return copy
    }
}
