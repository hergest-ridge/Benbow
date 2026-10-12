package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.backup.BackupJobs
import app.benbow.server.storage.Database
import app.benbow.server.storage.Devices
import app.benbow.server.storage.HighWater
import app.benbow.server.storage.PairingCodes
import app.benbow.server.storage.Restore
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

const val SERVER_VERSION = "0.3.0"
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
        log.error("Another Benbow server is already using {}. Stop it first (service: sc stop BenbowServer).", dataDir)
        exitProcess(2)
    }
    val restore = args.indexOf("--restore")
    if (restore >= 0) {
        val result = Restore.run(dataDir, args.getOrNull(restore + 1) ?: "latest")
        log.info(
            "Restored {} as the live database: store {}, new epoch {}. Previous files: {}",
            result.snapshot,
            result.storeId,
            result.epoch,
            result.replacedInto ?: "none",
        )
        if (result.otherStore) {
            log.warn(
                "The snapshot belongs to a different store than the one it replaced; devices paired with that one won't sync",
            )
        }
        lock.release()
        return
    }
    val config = ServerConfig.load(dataDir)
    val db = Database.open(dataDir, SERVER_VERSION)
    val highWater = HighWater(dataDir)
    if (highWater.check(db)) {
        log.warn(
            "The database is behind what this store reached before (copied back by hand?). New epoch {}: devices will reconcile.",
            db.epoch,
        )
    }
    val tls = TlsMaterial.ensure(dataDir, config.tlsPassword)
    log.info("Benbow server {} | data {} | store {} epoch {}", SERVER_VERSION, dataDir, db.storeId, db.epoch)
    log.info("HTTPS on {}:{} | certificate SHA-256 {}", config.host, config.port, tls.fingerprint)

    val backupJobs = BackupJobs(db, dataDir, config.backupMirror).also { it.start(config.backupIntervalMinutes) }
    val context =
        ServerContext(
            db = db,
            devices = Devices(db),
            pairing = PairingCodes(),
            tlsFingerprint = tls.fingerprint,
            dataDir = dataDir,
            port = config.port,
            backupStatus = { backupJobs.status },
            backupNow = backupJobs::runNow,
            highWater = highWater,
        )
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
            highWater.record(db)
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
