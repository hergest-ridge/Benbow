package app.benbow.server.peer

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/**
 * Keeps the link with friends' servers going (ADR-039, ADR-055): one worker thread that connects to the carrier, runs a
 * [Courier] round, waits in IDLE and starts over, reconnecting with growing pauses after a failure; one thread that
 * sends a queued letter at once; and one that moves files' parts on a carrier connection of their own, so a large part
 * never holds letters up. Off until the mailbox is set on the admin page.
 */
class PeerLink(
    val db: PeerDatabase,
    val identity: PeerIdentity,
    private val connect: (MailSettings) -> Carrier = ::ImapCarrier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = IDLE_MS,
    /** `<data>/peer`: where files are sealed, opened and kept; null where files aren't needed (tests). */
    peerDir: Path? = null,
    private val sweepMs: Long = SWEEP_MS,
    /** The carrier for files' parts: the same mailbox for now, a second one later (ADR-055). */
    private val connectParts: (MailSettings) -> Carrier = connect,
) : AutoCloseable {
    sealed interface Status {
        data object Off : Status

        data object Connecting : Status

        data class Online(val since: Long, val lastRound: Round?, val lastRoundAt: Long?) : Status

        /** [retryAt] null: not again until the settings change (a refused login). */
        data class Failed(val message: String, val at: Long, val retryAt: Long?) : Status
    }

    private val log = LoggerFactory.getLogger(PeerLink::class.java)

    @Volatile
    var status: Status = Status.Off
        private set

    @Volatile
    private var running = false

    @Volatile
    private var mailbox: Carrier? = null

    @Volatile
    private var courier: Courier? = null
    private var worker: Thread? = null
    private val sender = Executors.newSingleThreadScheduledExecutor { Thread(it, "benbow-peers-send").apply { isDaemon = true } }

    /** Files in parts through the mailbox (ADR-055); their passes have a thread of their own. */
    val transfers: Transfers? = peerDir?.let { Transfers(db, identity, it, clock) }

    /** Files from servers before ADR-055, through the Drive folder. */
    private val drive: DriveFiles? = peerDir?.let { DriveFiles(db, identity, it, clock) }
    private val sweeper = Executors.newSingleThreadScheduledExecutor { Thread(it, "benbow-peers-files").apply { isDaemon = true } }

    /** The parts' carrier and its failures: used on [sweeper]'s thread only. */
    private var parts: Carrier? = null
    private var partsRetryAt = 0L
    private var partsPause = FIRST_PAUSE_MS

    @Volatile
    private var partsRefused = false

    init {
        if (peerDir != null) sweeper.scheduleWithFixedDelay(::moveFiles, sweepMs, sweepMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Seals [source] for [contact]; the next pass cuts it into parts, announces it by letter and sends the parts through
     * the mailbox. Throws with a message for a person when files can't go.
     */
    fun sendFile(contact: String, name: String, source: InputStream) {
        val files = requireNotNull(transfers) { "файлы на этом сервере выключены" }
        require(db.mailSettings()?.complete == true) { "сначала настройте почтовый ящик в «Связи»" }
        val friend = requireNotNull(db.contacts().firstOrNull { it.id == contact }) { "такого контакта нет" }
        files.seal(friend, name, source, VIA_MAIL)
        filesSoon()
    }

    /** A pass over files now rather than at the next tick: a letter about one came, or one was sealed. */
    private fun filesSoon() {
        runCatching { sweeper.execute(::moveFiles) }
    }

    /** Never throws: a scheduled task that throws is never run again. */
    private fun moveFiles() {
        sweepDrive()
        val files = transfers ?: return
        try {
            if (!files.busy(VIA_MAIL)) return closeParts()
            val settings = db.mailSettings()?.takeIf { it.complete } ?: return
            if (partsRefused || clock() < partsRetryAt) return
            val carrier = parts ?: connectParts(settings).also { parts = it }
            val pass = files.pass(carrier, VIA_MAIL, "mail:${settings.user}@${settings.host}")
            partsPause = FIRST_PAUSE_MS
            if (pass.moved) log.info("Files: {}", pass)
            if (pass.letters > 0) {
                hotUntil = clock() + HOT_MS
                sendSoon(attempt = 0)
            }
        } catch (e: LoginRefused) {
            partsRefused = true
            closeParts()
            log.warn("The mailbox refused the login for files ({}); not retrying until the settings change", e.message)
        } catch (e: Exception) {
            closeParts()
            partsRetryAt = clock() + partsPause
            log.warn("Moving files failed; again in {} s", partsPause / 1000, e)
            partsPause = (partsPause * 2).coerceAtMost(LAST_PAUSE_MS)
        }
    }

    private fun closeParts() {
        parts?.let { runCatching { it.close() } }
        parts = null
    }

    private fun sweepDrive() {
        val files = drive ?: return
        val root = db.driveFolder()?.takeIf { it.isDirectory() } ?: return
        try {
            if (!files.busy()) return
            val taken = files.sweep(DriveFolder(root))
            if (taken > 0) {
                log.info("Files taken in through Drive: {}", taken)
                hotUntil = clock() + HOT_MS
                sendSoon(attempt = 0)
            }
        } catch (e: Exception) {
            log.warn("The Drive folder sweep failed; again in {} s", sweepMs / 1000, e)
        }
    }

    @Synchronized
    fun start() {
        val settings = db.mailSettings()?.takeIf { it.complete } ?: return
        running = true
        worker = Thread({ loop(settings) }, "benbow-peers").apply {
            isDaemon = true
            start()
        }
    }

    /** After the mailbox settings changed: a new connection with them. */
    @Synchronized
    fun restart() {
        halt()
        partsRefused = false
        runCatching {
            sweeper.execute {
                partsRetryAt = 0
                closeParts()
            }
        }
        start()
    }

    /** Queues a test note to [contact] and sends it right away if the mailbox is reachable. */
    fun sendNote(contact: String, text: String) {
        db.enqueue(contact, Letter.NOTE, text, clock())
        hotUntil = clock() + HOT_MS
        sendSoon(attempt = 0)
    }

    /**
     * Until then the worker asks the mailbox every few seconds instead of waiting in IDLE: some providers (Gmail) tell
     * of a new message in a label half a minute late, too slow for a conversation in progress.
     */
    @Volatile
    private var hotUntil = 0L

    /**
     * Sends what waits on the sending thread. A failed try is repeated a few times within a minute, since the worker may
     * sit in IDLE for minutes before its own round would send it.
     */
    private fun sendSoon(attempt: Int) {
        sender.execute {
            val c = courier ?: return@execute
            runCatching { c.sendDue() }
                .onSuccess { mailbox?.wake() } // the worker leaves IDLE and watches closely for the answer
                .onFailure { e ->
                    val delay = RETRY_DELAYS_MS.getOrNull(attempt)
                    val next = if (delay == null) "the next round retries" else "again in $delay ms"
                    log.warn("Sending failed (try {}); {}", attempt + 1, next, e)
                    if (delay != null) sender.schedule({ sendSoon(attempt + 1) }, delay, TimeUnit.MILLISECONDS)
                }
        }
    }

    /** A round now, e.g. after a contact was added: letters that waited for its card can be read. */
    fun check() {
        sender.execute { courier?.let { runCatching { it.round() }.onFailure { e -> log.warn("Round failed", e) } } }
    }

    private fun loop(settings: MailSettings) {
        var pause = FIRST_PAUSE_MS
        while (running) {
            try {
                status = Status.Connecting
                val box = connect(settings).also { mailbox = it }
                val c = Courier(identity, db, box, onFiles = ::filesSoon, clock = clock).also { courier = it }
                val since = clock()
                status = Status.Online(since, null, null)
                pause = FIRST_PAUSE_MS
                while (running) {
                    val round = c.round()
                    if (round.moved) hotUntil = clock() + HOT_MS
                    status = Status.Online(since, round, clock())
                    if (clock() < hotUntil) Thread.sleep(HOT_POLL_MS) else box.await(c.inbox, idleMs)
                }
            } catch (e: LoginRefused) {
                log.warn("The mailbox refused the login ({}); not retrying until the settings change", e.message)
                status = Status.Failed(e.message ?: "login refused", clock(), retryAt = null)
                return
            } catch (e: Exception) {
                if (!running) break
                status = Status.Failed(e.message ?: e.javaClass.simpleName, clock(), clock() + pause)
                log.warn("The link with friends' servers failed; again in {} s", pause / 1000, e)
                try {
                    Thread.sleep(pause)
                } catch (_: InterruptedException) {
                    break
                }
                pause = (pause * 2).coerceAtMost(LAST_PAUSE_MS)
            } finally {
                courier = null
                mailbox?.let { runCatching { it.close() } }
                mailbox = null
            }
        }
        if (!running) status = Status.Off
    }

    @Synchronized
    private fun halt() {
        running = false
        mailbox?.let { runCatching { it.close() } } // ends an IDLE in progress
        worker?.interrupt()
        worker?.join(5_000)
        worker = null
        status = Status.Off
    }

    override fun close() {
        halt()
        sender.shutdownNow()
        sweeper.shutdownNow()
        sweeper.awaitTermination(5, TimeUnit.SECONDS)
        closeParts()
        db.close()
    }

    companion object {
        /** Under the 29 minutes RFC 2177 allows, and short enough for servers that drop a quiet connection sooner. */
        const val IDLE_MS = 9 * 60_000L
        private const val FIRST_PAUSE_MS = 5_000L
        private const val LAST_PAUSE_MS = 5 * 60_000L
        private val RETRY_DELAYS_MS = listOf(3_000L, 10_000L, 30_000L)
        private const val HOT_MS = 2 * 60_000L
        private const val HOT_POLL_MS = 3_000L

        /** How often files' parts are moved while some are on their way (and the Drive folder looked at). */
        const val SWEEP_MS = 10_000L
    }
}
