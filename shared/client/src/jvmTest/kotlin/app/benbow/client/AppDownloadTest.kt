package app.benbow.client

import app.benbow.client.db.BenbowDb
import app.benbow.core.api.AndroidApp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's update from the paired server (ADR-042, U2): resumed after cuts, checked, and remembered from `info`. */
class AppDownloadTest {
    private val apk = Random(3).nextBytes(50_000)
    private val info = AndroidApp(13001, "0.13.1", sha256(apk), apk.size.toLong(), "app.benbow")

    @Test
    fun aCutDownloadGoesOnAndOnlyACheckedFileIsHandedOn() = runTest {
        val server = FakeServer().apply { app = apk }
        val api = FakeApi(server).apply { appChunk = 7_000 } // every request drops after 7 KB
        val dir = Files.createTempDirectory("benbow-update").toFile()
        File(dir, "benbow-13000.apk").writeBytes(byteArrayOf(1)) // an earlier version's file
        var seen = 0L
        val file = AppDownload(dir) { api }.fetch(info) { done, _ -> seen = done }
        assertContentEquals(apk, file.readBytes())
        assertEquals(apk.size.toLong(), seen)
        assertEquals(listOf("benbow-13001.apk"), dir.list()!!.toList(), "the earlier one is gone, no part left")
        assertEquals(file, AppDownload(dir) { error("no network needed") }.fetch(info), "kept once checked")
    }

    @Test
    fun aFileThatDoesNotMatchIsThrownAway() = runTest {
        val server = FakeServer().apply { app = Random(4).nextBytes(apk.size) } // the right size, other bytes
        val dir = Files.createTempDirectory("benbow-update").toFile()
        assertFailsWith<IllegalStateException> { AppDownload(dir) { FakeApi(server) }.fetch(info) }
        assertTrue(dir.list()!!.isEmpty(), "nothing that doesn't check stays")
        server.app = apk
        assertContentEquals(apk, AppDownload(dir) { FakeApi(server) }.fetch(info).readBytes())
    }

    @Test
    fun aSyncRemembersWhatAppTheServerOffers() = runTest {
        val server = FakeServer()
        val db = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        Settings(db).connection = Connection("https://pc:8765", "AA", "token", server.storeId, server.epoch, "phone")
        var time = 1L
        val engine = SyncEngine(db, { FakeApi(server) }, { time++ })
        engine.sync()
        assertNull(Settings(db).serverApp)
        server.appInfo = info
        engine.sync()
        assertEquals(info, Settings(db).serverApp)
        server.appInfo = null
        engine.sync()
        assertNull(Settings(db).serverApp, "a server that no longer carries it offers none")
        assertFalse(info.versionName.isEmpty())
    }
}
