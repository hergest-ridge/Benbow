package app.benbow.server.peer

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/**
 * Keeps the link with friends' servers going (ADR-039): one worker thread that connects to the shared mailbox, runs a
 * [Courier] round, waits in IDLE and starts over, reconnecting with growing pauses after a failure; and one thread that
 * sends a queued letter at once. Off until the mailbox is set on the admin page.
 */
class PeerLink(
    val db: PeerDatabase,
    val identity: PeerIdentity,
    private val connect: (MailSettings) -> Mailbox = ::ImapMailbox,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = IDLE_MS,
    /** `<data>/peer`: where files are sealed, opened and kept; null where files aren't needed (tests). */
    peerDir: Path? = null,
    private val sweepMs: Long = SWEEP_MS,
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
    private var mailbox: Mailbox? = null

    @Volatile
    private var courier: Courier? = null
    private var worker: Thread? = null
    private val sender = Executors.newSingleThreadScheduledExecutor { Thread(it, "benbow-peers-send").apply { isDaemon = true } }

    /** Files through the Drive folder; their sweep has a thread of its own, since opening a large file takes a while. */
    val files: FileCarrier? = peerDir?.let { FileCarrier(db, identity, it, clock) }
    private val sweeper = Executors.newSingleThreadScheduledExecutor { Thread(it, "benbow-peers-files").apply { isDaemon = true } }

    init {
        if (files != null) sweeper.scheduleWithFixedDelay(::sweep, sweepMs, sweepMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Seals [source] into the Drive folder for [contact] and announces it by letter. Throws with a message for a person
     * when the folder isn't set or isn't there.
     */
    fun sendFile(contact: String, name: String, source: InputStream) {
        val carrier = requireNotNull(files) { "файлы на этом сервере выключены" }
        val root = requireNotNull(db.driveFolder()) { "сначала укажите папку Google Диска" }
        require(root.isDirectory()) { "папки $root нет: проверьте, что Google Диск запущен" }
        val friend = requireNotNull(db.contacts().firstOrNull { it.id == contact }) { "такого контакта нет" }
        val file = carrier.send(DriveFolder(root), friend, name, source)
        db.enqueue(contact, Letter.FILE, Json.encodeToString(file), clock())
        hotUntil = clock() + HOT_MS
        sendSoon(attempt = 0)
    }

    private fun sweep() {
        val carrier = files ?: return
        val root = db.driveFolder()?.takeIf { it.isDirectory() } ?: return
        try {
            val taken = carrier.sweep(DriveFolder(root))
            for ((contact, id) in taken) db.enqueue(contact, Letter.FILE_DONE, id, clock())
            if (taken.isNotEmpty()) {
                log.info("Files taken in through Drive: {}", taken.size)
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
                val c = Courier(identity, db, box, clock).also { courier = it }
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

        /** How often the Drive folder is looked at for files that came through. */
        const val SWEEP_MS = 10_000L
    }
}
