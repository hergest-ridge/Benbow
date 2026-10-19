package app.benbow.server

import app.benbow.client.Connection
import app.benbow.client.Pairing
import app.benbow.client.apiFor
import app.benbow.client.db.BenbowDb
import app.benbow.core.api.UploadStart
import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A phone's connection dies in the middle of a chunk: Android destroys an app's sockets when it goes to the
 * background (the server sees a reset), or Wi-Fi drops without a word. The phone's next try must go through
 * instead of waiting behind the dead request. On 2026-10-19 one such request held its upload until the
 * server restarted, and every file queued behind it waited for eleven hours.
 */
class UploadCutOffTest {
    private val dir = Files.createTempDirectory("benbow-cutoff")
    private val port = ServerSocket(0).use { it.localPort }

    @Test
    fun aChunkCutOffByAResetDoesNotHoldUpTheRetry() = runBlocking {
        for (protocol in listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)) retryAfter(protocol, reset = true, seed = protocol.ordinal)
    }

    @Test
    fun aChunkThatStallsDoesNotHoldUpTheRetry() = runBlocking {
        retryAfter(Protocol.HTTP_2, reset = false, seed = 7)
    }

    private suspend fun retryAfter(protocol: Protocol, reset: Boolean, seed: Int) = server().use { server ->
        val connection = pairPhone(server)
        val bytes = Random(seed).nextBytes(345_178)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val api = apiFor(connection)
        val id = api.startUpload(UploadStart(sha, bytes.size.toLong(), "image/jpeg", "117686.jpg")).id!!

        CutOff(protocol, connection.token).use { cut ->
            cut.send(id, bytes.copyOfRange(0, 100_000), declared = bytes.size.toLong())
            if (reset) cut.reset()
            val status = api.uploadChunk(id, 0, bytes)
            assertTrue(status.complete, "$protocol: the retry finished the file")
        }
        val blob = server.blobs.blob(sha)!!
        assertContentEquals(bytes, server.blobs.file(blob).readBytes(), "$protocol: exactly the phone's bytes")
    }

    private suspend fun pairPhone(server: Running): Connection {
        val phone = BenbowDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BenbowDb.Schema))
        val pairing = Pairing(phone)
        return pairing.pair(pairing.probe("127.0.0.1", port), server.ctx.pairing.issue().first, "Galaxy S24+", "android")
    }

    private inner class Running : AutoCloseable {
        val db = Database.open(dir, "test")
        val tls = TlsMaterial.ensure(dir, "secret")
        val blobs = BlobStore(db, dir.resolve("media"))
        val ctx = ServerContext(db, Devices(db), PairingCodes(), tls.fingerprint, dir, port, blobs = blobs)
        private val engine = embeddedServer(
            factory = Netty,
            environment = applicationEnvironment {},
            configure = {
                sslConnector(tls.keyStore, TlsMaterial.ALIAS, { "secret".toCharArray() }, { "secret".toCharArray() }) {
                    host = "127.0.0.1"
                    port = this@UploadCutOffTest.port
                }
            },
            module = { benbowModule(ctx) },
        ).start(wait = false)

        override fun close() {
            engine.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
            db.close()
        }
    }

    private fun server() = Running()

    /** OkHttp as on the phone, with a body that sends its start and then nothing more. */
    private inner class CutOff(private val protocol: Protocol, private val token: String) : AutoCloseable {
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val sent = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        private val client = OkHttpClient.Builder()
            .socketFactory(Recording(sockets))
            .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .protocols(if (protocol == Protocol.HTTP_2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1))
            .build()

        fun send(id: String, start: ByteArray, declared: Long) {
            val probe = client.newCall(Request.Builder().url("https://127.0.0.1:$port/api/v1/info").build()).execute()
            assertEquals(protocol, probe.protocol, "the connection speaks what the test claims")
            probe.close()
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun contentLength() = declared

                override fun writeTo(sink: BufferedSink) {
                    sink.write(start)
                    sink.flush()
                    sent.countDown()
                    release.await() // the rest never comes
                }
            }
            val request = Request.Builder()
                .url("https://127.0.0.1:$port/api/v1/uploads/$id")
                .header("Authorization", "Bearer $token")
                .header("Upload-Offset", "0")
                .patch(body)
                .build()
            thread(isDaemon = true) { runCatching { client.newCall(request).execute().close() } }
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            Thread.sleep(500) // the server is inside the chunk now
        }

        /** No FIN, no TLS close_notify: a reset, as from a socket the OS destroyed. */
        fun reset() = sockets.filterNot { it.isClosed }.forEach {
            it.setSoLinger(true, 0)
            it.close()
        }

        override fun close() {
            release.countDown()
            reset()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private class Recording(private val sockets: MutableList<Socket>) : SocketFactory() {
        private val default = getDefault()

        override fun createSocket(): Socket = default.createSocket().also { sockets += it }

        override fun createSocket(host: String, port: Int): Socket = default.createSocket(host, port).also { sockets += it }

        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            default.createSocket(host, port, local, localPort).also { sockets += it }

        override fun createSocket(host: InetAddress, port: Int): Socket = default.createSocket(host, port).also { sockets += it }

        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            default.createSocket(host, port, local, localPort).also { sockets += it }
    }
}
