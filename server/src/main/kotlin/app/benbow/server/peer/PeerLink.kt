package app.benbow.server.peer

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

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
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "benbow-peers-send").apply { isDaemon = true } }

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
        sender.execute {
            courier?.let { runCatching { it.sendDue() }.onFailure { e -> log.warn("Sending failed; the next round retries", e) } }
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
                    status = Status.Online(since, round, clock())
                    box.await(c.inbox, idleMs)
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
        db.close()
    }

    companion object {
        /** Under the 29 minutes RFC 2177 allows, and short enough for servers that drop a quiet connection sooner. */
        const val IDLE_MS = 9 * 60_000L
        private const val FIRST_PAUSE_MS = 5_000L
        private const val LAST_PAUSE_MS = 5 * 60_000L
    }
}
