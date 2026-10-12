package app.benbow.server

import app.benbow.client.JournalRepository
import app.benbow.client.Pairing
import app.benbow.client.Settings
import app.benbow.client.SyncEngine
import app.benbow.client.SyncResult
import app.benbow.client.apiFor
import app.benbow.client.db.BenbowDb
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.SqliteSyncStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The real thing: HTTPS server with its self-signed certificate, client pairing, pinning and sync. */
class EndToEndTest {
    private val body = """{"v":1,"blocks":[
{"_key":"k1","_type":"block","style":"normal","children":[{"_type":"span","text":"Первая запись с телефона","marks":[]}],"markDefs":[]}
]}"""

    @Test
    fun phonePairsOverPinnedHttpsAndSyncs() = runBlocking {
        val dir = Files.createTempDirectory("benbow-e2e")
        val db = Database.open(dir, "test")
        val tls = TlsMaterial.ensure(dir, "secret")
        val port = ServerSocket(0).use { it.localPort }
        val ctx = ServerContext(db, Devices(db), PairingCodes(), tls.fingerprint, dir, port)
        val server = embeddedServer(
            factory = Netty,
            environment = applicationEnvironment {},
            configure = {
                sslConnector(tls.keyStore, TlsMaterial.ALIAS, { "secret".toCharArray() }, { "secret".toCharArray() }) {
                    host = "127.0.0.1"
                    this.port = port
                }
            },
            module = { benbowModule(ctx) },
        ).start(wait = false)
        try {
            val phoneDb = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
            val pairing = Pairing(phoneDb)
            val probe = pairing.probe("127.0.0.1", port)
            assertEquals(tls.fingerprint, probe.fingerprint, "the user compares this with the admin page")
            assertEquals(db.storeId, probe.info.storeId)

            val connection = pairing.pair(probe, ctx.pairing.issue().first, "Galaxy S24+", "android")
            val repo = JournalRepository(phoneDb, System::currentTimeMillis)
            val id = repo.createEntry("2026-10-13", body = body)
            val engine = SyncEngine(phoneDb, { apiFor(it) }, System::currentTimeMillis)
            assertEquals(SyncResult.Done(pushed = 2, pulled = 1, rejected = 0), engine.sync())

            val head = repo.entry(id).first()!!.headRevision!!
            assertEquals(body, SqliteSyncStore(db).revision(head)!!.body, "the server stored exactly what the phone wrote")
            assertTrue(ctx.devices.list().single().lastSeenAt != null)

            // A different certificate (e.g. someone impersonating the PC) is refused.
            Settings(phoneDb).connection = connection.copy(fingerprint = "00:".repeat(31) + "00")
            repo.createEntry("2026-10-13")
            assertIs<SyncResult.Failed>(engine.sync())
            assertEquals(2L, phoneDb.benbowQueries.pendingCount().executeAsOne(), "nothing left the phone")
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
            db.close()
        }
    }
}
