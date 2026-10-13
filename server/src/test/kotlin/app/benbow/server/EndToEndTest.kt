package app.benbow.server

import app.benbow.client.JournalRepository
import app.benbow.client.MediaStore
import app.benbow.client.Pairing
import app.benbow.client.Settings
import app.benbow.client.SyncEngine
import app.benbow.client.SyncResult
import app.benbow.client.apiFor
import app.benbow.client.db.BenbowDb
import app.benbow.core.api.UploadStart
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.HighWater
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.Restore
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
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The real thing: HTTPS server with its self-signed certificate, client pairing, pinning and sync. */
class EndToEndTest {
    private val body = """{"v":1,"blocks":[
{"_key":"k1","_type":"block","style":"normal","children":[{"_type":"span","text":"Первая запись с телефона","marks":[]}],"markDefs":[]}
]}"""

    private val dir = Files.createTempDirectory("benbow-e2e")
    private val port = ServerSocket(0).use { it.localPort }

    /** The server as Main runs it: data folder, TLS, high-water mark. */
    private inner class RunningServer : AutoCloseable {
        val db = Database.open(dir, "test")
        val tls = TlsMaterial.ensure(dir, "secret")
        val highWater = HighWater(dir).also { it.check(db) }
        val blobs = BlobStore(db, dir.resolve("media"))
        val ctx = ServerContext(db, Devices(db), PairingCodes(), tls.fingerprint, dir, port, highWater = highWater, blobs = blobs)
        private val engine = embeddedServer(
            factory = Netty,
            environment = applicationEnvironment {},
            configure = {
                sslConnector(tls.keyStore, TlsMaterial.ALIAS, { "secret".toCharArray() }, { "secret".toCharArray() }) {
                    host = "127.0.0.1"
                    port = this@EndToEndTest.port
                }
            },
            module = { benbowModule(ctx) },
        ).start(wait = false)

        override fun close() {
            engine.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
            highWater.record(db)
            db.close()
        }
    }

    private fun phone() = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))

    @Test
    fun phonePairsOverPinnedHttpsAndSyncs() = runBlocking {
        RunningServer().use { running ->
            val db = running.db
            val tls = running.tls
            val ctx = running.ctx
            val phoneDb = phone()
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
        }
    }

    /** A video-sized file in real (8 MB) chunks over real HTTPS, then fetched and verified by another device. */
    @Test
    fun photoUploadsInChunksAndAnotherDeviceDownloadsIt() = runBlocking<Unit> {
        RunningServer().use { running ->
            val bytes = Random(5).nextBytes(9_000_000)
            val phoneDb = phone()
            val pairing = Pairing(phoneDb)
            pairing.pair(pairing.probe("127.0.0.1", port), running.ctx.pairing.issue().first, "Galaxy S24+", "android")
            val media = MediaStore(
                Files.createTempDirectory("phone-media").toFile(),
                phoneDb,
                { apiFor(it) },
                System::currentTimeMillis,
            )
            val repo = JournalRepository(phoneDb, System::currentTimeMillis)
            val entry = repo.createEntry("2026-10-13")
            val video = media.import(bytes.inputStream(), "video/mp4", "VID_1.mp4", null)
            repo.attach(entry, video, "video")
            assertIs<SyncResult.Done>(SyncEngine(phoneDb, { apiFor(it) }, System::currentTimeMillis).sync())
            // A previous run was cut off after the first chunk (app killed, Wi-Fi lost): the next one resumes there.
            val api = apiFor(Settings(phoneDb).connection!!)
            val open = api.startUpload(UploadStart(video.sha256, video.size, video.mime, video.name))
            assertEquals(1_000_000, api.uploadChunk(open.id!!, 0, bytes.copyOfRange(0, 1_000_000)).offset)
            assertEquals(1, media.uploadPending())
            val sha = repo.entry(entry).first()!!.attachments.single().meta.sha256
            assertContentEquals(bytes, running.blobs.file(running.blobs.blob(sha)!!).readBytes())

            val desktopDb = phone()
            val desktopPairing = Pairing(desktopDb)
            desktopPairing.pair(desktopPairing.probe("127.0.0.1", port), running.ctx.pairing.issue().first, "PC", "desktop")
            SyncEngine(desktopDb, { apiFor(it) }, System::currentTimeMillis).sync()
            val desktopMedia =
                MediaStore(Files.createTempDirectory("desktop-media").toFile(), desktopDb, { apiFor(it) }, System::currentTimeMillis)
            assertContentEquals(bytes, desktopMedia.file(sha).readBytes())
        }
    }

    /** Restore drill (data-safety runbook): the PC's database is restored from last night's backup. */
    @Test
    fun restoredServerIsHealedByThePhone() = runBlocking<Unit> {
        val phoneDb = phone()
        val repo = JournalRepository(phoneDb, System::currentTimeMillis)
        val engine = SyncEngine(phoneDb, { apiFor(it) }, System::currentTimeMillis)
        val yesterday: String
        val today: String
        val lostEdit: String
        RunningServer().use { running ->
            val pairing = Pairing(phoneDb)
            pairing.pair(pairing.probe("127.0.0.1", port), running.ctx.pairing.issue().first, "Galaxy S24+", "android")
            yesterday = repo.createEntry("2026-10-12", body = body)
            assertIs<SyncResult.Done>(engine.sync())
            running.db.locked { running.db.backups!!.snapshot(it, "nightly", running.db.latestCursor()) }

            today = repo.createEntry("2026-10-13")
            repo.saveDraft(yesterday, body.replace("Первая запись", "Дописанная запись"))
            repo.commitDraft(yesterday)
            assertIs<SyncResult.Done>(engine.sync())
            assertEquals(0L, phoneDb.benbowQueries.pendingCount().executeAsOne(), "all acknowledged, then the PC's disk dies")
            lostEdit = repo.entry(yesterday).first()!!.headRevision!!
        }

        assertEquals(2, Restore.run(dir, "latest").epoch)

        RunningServer().use { running ->
            assertEquals(2, running.db.epoch)
            assertIs<SyncResult.Done>(engine.sync())
            val store = SqliteSyncStore(running.db)
            assertTrue(store.entry(today)?.head != null, "the entry written after the backup is back")
            assertEquals(body.replace("Первая запись", "Дописанная запись"), store.revision(lostEdit)!!.body, "so is the lost edit")
            assertTrue(lostEdit in store.entry(yesterday)!!.conflictHeads, "kept next to the backup's head for the user to pick")
            assertEquals(2, repo.entry(yesterday).first()!!.conflictHeads.size, "and the phone shows the choice")
            assertEquals(2L, Settings(phoneDb).connection!!.epoch)
            assertIs<SyncResult.Done>(engine.sync())
        }
    }
}
