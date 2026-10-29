package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.FontCatalog
import app.benbow.core.api.FontFileInfo
import app.benbow.core.api.FontInfo
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fonts from the server (ADR-052): the catalog kept per store, a font's files fetched once, checked and kept. */
class ServerFontsTest {
    private val server = FakeServer()
    private val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
    private val bytes = Random(1).nextBytes(40_000)
    private val file = FontFileInfo(400, sha256(bytes), bytes.size.toLong())
    private val font = FontInfo("own:my-hand", "Мой почерк", "own", 1.2f, listOf(file))
    private var downloads = 0
    private var corrupt = false
    private var catalog: List<FontInfo>? = listOf(font)
    private val api = object : BenbowApi by FakeApi(server) {
        override suspend fun fonts(): FontCatalog = catalog?.let(::FontCatalog) ?: throw ApiException(404, "not_found", "HTTP 404")

        override suspend fun downloadFont(sha256: String): ByteArray {
            downloads++
            return if (corrupt) bytes.copyOf(100) else bytes
        }
    }

    private fun connect(store: String = server.storeId) {
        Settings(db).connection = Connection("https://pc:8765", "AA", "token", store, server.epoch, "phone")
    }

    @Test
    fun theCatalogIsKeptForItsServerOnly() = runTest {
        val shelf = FolderFontShelf(Files.createTempDirectory("benbow-fonts").toFile())
        connect()
        ServerFonts(db, { api }, shelf).refresh()
        assertEquals(listOf(font), ServerFonts(db, { api }, shelf).catalog.value, "read again after a restart, offline")
        connect("another-store")
        assertEquals(emptyList(), ServerFonts(db, { api }, shelf).catalog.value, "another server's fonts aren't this one's")

        connect()
        catalog = null // a server from before fonts
        val fonts = ServerFonts(db, { api }, shelf)
        fonts.refresh()
        assertEquals(emptyList(), fonts.catalog.value)
    }

    @Test
    fun aFontsFileIsFetchedOnceCheckedAndKept() = runTest {
        val dir = Files.createTempDirectory("benbow-fonts").toFile()
        connect()
        corrupt = true
        assertNull(ServerFonts(db, { api }, FolderFontShelf(dir)).file(file), "a file that doesn't match its hash isn't kept")
        assertEquals(0, dir.listFiles().orEmpty().count { it.extension == "ttf" })

        corrupt = false
        val fonts = ServerFonts(db, { api }, FolderFontShelf(dir))
        assertContentEquals(bytes, fonts.file(file)!!.readBytes())
        val before = downloads
        assertContentEquals(bytes, fonts.file(file)!!.readBytes())
        assertEquals(before, downloads, "kept on the device: not fetched again")

        Settings(db).connection = null
        assertContentEquals(bytes, ServerFonts(db, { api }, FolderFontShelf(dir)).file(file)!!.readBytes(), "and there offline")
        assertNull(ServerFonts(db, { api }, null).file(file), "no shelf, no files")
    }
}
