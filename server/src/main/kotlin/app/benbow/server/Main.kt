package app.benbow.server

import app.benbow.server.api.ServerContext
import app.benbow.server.api.benbowModule
import app.benbow.server.backup.BackupJobs
import app.benbow.server.calendar.CalendarDatabase
import app.benbow.server.peer.Letter
import app.benbow.server.peer.Letters
import app.benbow.server.peer.Opened
import app.benbow.server.peer.PeerDatabase
import app.benbow.server.peer.PeerIdentity
import app.benbow.server.peer.PeerLink
import app.benbow.server.storage.BlobStore
import app.benbow.server.storage.CALENDAR_HIGH_WATER
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

const val SERVER_VERSION = "0.11.1"
const val DEFAULT_PORT = 8765

fun main(args: Array<String>) {
    if ("--self-check" in args) return selfCheck()
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
    val restoreCalendar = args.indexOf("--restore-calendar")
    val restore = if (restoreCalendar >= 0) restoreCalendar else args.indexOf("--restore")
    if (restore >= 0) {
        val target = if (restoreCalendar >= 0) Restore.CALENDAR else Restore.JOURNAL
        val result = Restore.run(dataDir, args.getOrNull(restore + 1) ?: "latest", target)
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
    // The calendar is a store of its own (ADR-027): if its file can't be opened, the journal still runs.
    val calendarHighWater = HighWater(dataDir, CALENDAR_HIGH_WATER)
    val calendar = try {
        CalendarDatabase.open(dataDir, SERVER_VERSION, db.storeId).also { opened ->
            val behind = calendarHighWater.check(opened)
            if (behind) log.warn("The calendar file is behind what it reached before. New calendar epoch {}.", opened.epoch)
        }
    } catch (e: Exception) {
        log.error("The calendar store didn't open; the journal works without it. Restore it with restore-calendar.cmd", e)
        null
    }
    // The link with friends' servers (ADR-039) is a store of its own too, and off until a mailbox is set.
    val peers = try {
        val peerDir = dataDir.resolve("peer")
        PeerLink(PeerDatabase.open(dataDir), PeerIdentity.ensure(peerDir), peerDir = peerDir).also { it.start() }
    } catch (e: Exception) {
        log.error("The link with friends' servers didn't open; the journal works without it", e)
        null
    }
    val tls = TlsMaterial.ensure(dataDir, config.tlsPassword)
    log.info("Benbow server {} | data {} | store {} epoch {}", SERVER_VERSION, dataDir, db.storeId, db.epoch)
    log.info("HTTPS on {}:{} | certificate SHA-256 {}", config.host, config.port, tls.fingerprint)

    val blobs = BlobStore(db, config.mediaRoot ?: dataDir.resolve("media"))
    blobs.adoptUntracked().takeIf { it > 0 }?.let { log.warn("Recorded {} media files the database didn't know (after a restore?)", it) }
    val backupJobs = BackupJobs(db, dataDir, config.backupMirror, blobs, calendar).also { it.start(config.backupIntervalMinutes) }
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
            blobs = blobs,
            speech = SpeechModel(dataDir.resolve("models")),
            calendar = calendar,
            calendarHighWater = calendarHighWater,
            webRoot = webRoot(),
            peers = peers,
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
            peers?.close()
            highWater.record(db)
            db.close()
            calendar?.let {
                calendarHighWater.record(it)
                it.close()
            }
            lock.release()
        },
    )
    server.start(wait = true)
}

/** The web client's files: the package's `web` folder beside `lib`, or where `benbow.web` points (`:server:run`). */
private fun webRoot(): Path? = System.getProperty("benbow.web")?.let(Path::of)
    ?: runCatching {
        Path.of(ServerContext::class.java.protectionDomain.codeSource.location.toURI()).parent.parent.resolve("web")
    }.getOrNull()

/** One server per data directory; a second instance would fight over the same store. */
private fun lockDataDir(dataDir: Path): FileLock? {
    val channel = FileChannel.open(dataDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    return channel.tryLock()
}

/**
 * Touches what the server needs beyond java.base (HTTP client, SQLite's native library, bzip2/tar) without opening any
 * data, so `packageServer` can prove the bundled runtime has every module.
 */
private fun selfCheck() {
    java.net.http.HttpClient.newHttpClient()
    Database.inMemory().close()
    CalendarDatabase.inMemory().close()
    PeerDatabase.inMemory().close()
    val (a, b) = PeerIdentity.generate() to PeerIdentity.generate()
    val letter = Letter("self-check", 1, Letter.NOTE, "ok", sentAt = 0)
    check(Letters.open(b, Letters.seal(a, b.card, letter)) { a.card } == Opened.Ok(a.card, letter)) { "sealed letters" }
    jakarta.mail.Session.getInstance(java.util.Properties()).getStore("imaps")
    org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream::class.java.name
    javax.net.ssl.SSLContext.getDefault()
    println("self-check ok")
}
