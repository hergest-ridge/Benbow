package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.backup.BackupJobs
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.PairingCodes
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

const val SERVER_VERSION = "0.2.0"
const val DEFAULT_PORT = 8765

fun main(args: Array<String>) {
    val dataDir = ServerConfig.resolveDataDir(args).createDirectories()
    // Java's NIO selector uses a unix-domain socket in %TEMP%, which fails on some Windows profiles
    // ("Unable to establish loopback connection"). Must be set before any networking code runs.
    if (System.getProperty("jdk.net.unixdomain.tmpdir") == null) {
        System.setProperty("jdk.net.unixdomain.tmpdir", dataDir.resolve("tmp").createDirectories().toString())
    }
    System.setProperty("benbow.logDir", dataDir.resolve("logs").toString()) // before the first logger
    val log = LoggerFactory.getLogger("benbow")
    val lock = lockDataDir(dataDir) ?: run {
        log.error("Another Benbow server is already using {}", dataDir)
        exitProcess(2)
    }
    val config = ServerConfig.load(dataDir)
    val db = Database.open(dataDir, SERVER_VERSION)
    val tls = TlsMaterial.ensure(dataDir, config.tlsPassword)
    log.info("Benbow server {} | data {} | store {} epoch {}", SERVER_VERSION, dataDir, db.storeId, db.epoch)
    log.info("HTTPS on {}:{} | certificate SHA-256 {}", config.host, config.port, tls.fingerprint)

    val backupJobs = BackupJobs(db, dataDir, config.backupMirror).also { it.start(config.backupIntervalMinutes) }
    val context =
        ServerContext(db, Devices(db), PairingCodes(), tls.fingerprint, dataDir, config.port, backupStatus = { backupJobs.status })
    val server = embeddedServer(
        factory = Netty,
        environment = applicationEnvironment { this.log = log },
        configure = {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = TlsMaterial.ALIAS,
                keyStorePassword = { config.tlsPassword.toCharArray() },
                privateKeyPassword = { config.tlsPassword.toCharArray() },
            ) {
                host = config.host
                port = config.port
            }
        },
        module = { benbowModule(context) },
    )
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Stopping")
            server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
            backupJobs.stop()
            db.close()
            lock.release()
        },
    )
    server.start(wait = true)
}

/** One server per data directory; a second instance would fight over the same store. */
private fun lockDataDir(dataDir: Path): FileLock? {
    val channel = FileChannel.open(dataDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    return channel.tryLock()
}
