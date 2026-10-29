package app.benbow.server.peer

import jakarta.activation.DataHandler
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.util.ByteArrayDataSource
import org.eclipse.angus.mail.imap.IMAPFolder
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A shared mailbox over IMAP as a carrier (ADR-039, ADR-055). Items are put straight into folders (APPEND), so no mail
 * is sent and no spam filter sees them: letters as plain text in `Benbow-<id12>` (as servers before ADR-055 wrote and
 * read them), parts of files as an attachment in `Benbow-<id12>-parts`. This server's letters folder stays open and waits
 * in IDLE, so a letter is noticed as it arrives; its parts folder stays open beside it on a connection of its own. TLS
 * always, except [MailSettings.secure] = false for a test server in the same process.
 */
class ImapCarrier(settings: MailSettings) : Carrier {
    override val traits = CarrierTraits(maxItem = MAX_ITEM, dailyBudget = DAILY_BUDGET, pushes = true, whole = true)

    private val protocol = if (settings.secure) "imaps" else "imap"
    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.connectiontimeout", "15000")
            put("mail.$protocol.timeout", "$READ_TIMEOUT_MS") // longer than an IDLE, which reads nothing while it waits
            put("mail.$protocol.writetimeout", "120000") // a part of a file is ~11 MB on the wire
            // The open folders never take the store's own connection, and that one is kept between rounds, so an APPEND
            // doesn't log in again (each login is a round trip to the provider and counts against its limits).
            put("mail.$protocol.separatestoreconnection", "true")
            put("mail.$protocol.connectionpooltimeout", "$POOL_TIMEOUT_MS")
            // A message's Message-ID is made from this address; without it Jakarta Mail asks for the PC's own name, which
            // takes five seconds on some Windows networks and was paid at every APPEND.
            put("mail.from", SENDER)
            if (settings.secure) put("mail.imaps.ssl.checkserveridentity", "true")
        },
    )
    private val log = LoggerFactory.getLogger(ImapCarrier::class.java)

    /** Logs IMAP steps slower than three seconds (Gmail takes one or two for most): letters usually wait on the provider. */
    private inline fun <T> timed(what: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            if (ms >= SLOW_MS) log.warn("IMAP {} took {} ms", what, ms) else log.debug("IMAP {} took {} ms", what, ms)
        }
    }
    private val store: Store = session.getStore(protocol).apply {
        try {
            connect(settings.host, settings.port, settings.user, settings.password)
        } catch (e: AuthenticationFailedException) {
            throw LoginRefused(e.message ?: "authentication failed")
        }
    }
    private val known = mutableSetOf<String>()

    /** This server's own folders, kept open: the letters one is where IDLE waits. */
    private val open = mutableMapOf<String, IMAPFolder>()
    private var watched: IMAPFolder? = null
    private val idling = AtomicBoolean(false)
    private val woken = AtomicBoolean(false)
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "benbow-peers-idle").apply { isDaemon = true } }
    private var idleWorks = true

    override fun drop(to: Address, name: String, data: ByteArray) {
        val folder = folderOf(to)
        ensureFolder(folder)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(SENDER))
            setSubject(name, "us-ascii")
            sentDate = Date()
            if (to.box == Box.LETTERS) {
                setText(data.decodeToString(), "us-ascii") // an envelope is ASCII JSON
            } else {
                dataHandler = DataHandler(ByteArrayDataSource(data, "application/octet-stream"))
                setHeader("Content-Transfer-Encoding", "base64")
            }
        }
        timed("append to $folder") { store.getFolder(folder).appendMessages(arrayOf(message)) }
    }

    override fun waiting(at: Address): List<Dropped> = timed("read ${folderOf(at)}") {
        val f = own(at)
        f.messageCount // a NOOP, so messages that came during an IDLE are known
        val messages = f.messages
        f.fetch(
            messages,
            FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(UIDFolder.FetchProfileItem.UID)
                add(FetchProfile.Item.SIZE)
            },
        )
        messages.filterNot { it.isSet(Flags.Flag.DELETED) }
            .map { Dropped(f.getUID(it).toString(), it.subject.orEmpty(), it.size.toLong().coerceAtLeast(0)) }
    }

    override fun read(at: Address, item: Dropped): ByteArray = timed("fetch ${item.name}") {
        val f = own(at)
        val message = f.getMessageByUID(item.handle.toLong()) ?: error("${item.name} is gone from ${folderOf(at)}")
        if (at.box == Box.LETTERS) text(message).encodeToByteArray() else message.inputStream.use { it.readBytes() }
    }

    override fun remove(at: Address, items: Collection<Dropped>) {
        if (items.isEmpty()) return
        timed("remove from ${folderOf(at)}") {
            val f = own(at)
            val messages = f.getMessagesByUID(items.map { it.handle.toLong() }.toLongArray()).filterNotNull()
            if (messages.isNotEmpty()) {
                f.setFlags(messages.toTypedArray(), Flags(Flags.Flag.DELETED), true)
                f.expunge()
            }
        }
    }

    override fun names(at: Address): Set<String> = timed("list ${folderOf(at)}") {
        val f = store.getFolder(folderOf(at))
        try {
            f.open(Folder.READ_ONLY)
        } catch (_: FolderNotFoundException) {
            return@timed emptySet()
        }
        try {
            val messages = f.messages
            f.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
            messages.filterNot { it.isSet(Flags.Flag.DELETED) }.mapNotNull { it.subject }.toSet()
        } finally {
            f.close(false)
        }
    }

    /**
     * IDLE on [at] until the server tells of a change; a timer ends it after [timeoutMs] by touching the folder from
     * another thread (which sends DONE). A server without IDLE is asked again after at most 30 seconds.
     */
    override fun await(at: Address, timeoutMs: Long) {
        val f = own(at).also { watched = it }
        if (!idleWorks) return Thread.sleep(minOf(timeoutMs, 30_000))
        idling.set(true) // before the wake check: a wake() in between sees it and interrupts
        if (woken.getAndSet(false)) return idling.set(false)
        val stop = timer.schedule({ interruptIdle(f) }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            f.idle(true)
        } catch (e: MessagingException) {
            if (e.message?.contains("IDLE not supported", ignoreCase = true) != true) throw e
            idleWorks = false
        } finally {
            idling.set(false)
            stop.cancel(false)
        }
    }

    override fun wake() {
        woken.set(true)
        val f = watched ?: return
        if (idling.get()) timer.execute { interruptIdle(f) }
    }

    /** Touches the folder until the IDLE has ended: a touch that came just before it began would be lost. */
    private fun interruptIdle(f: IMAPFolder) {
        repeat(10) {
            if (!idling.get()) return
            runCatching { f.messageCount }
            Thread.sleep(1_500)
        }
    }

    @Synchronized
    private fun ensureFolder(name: String) {
        if (name in known) return
        timed("folder $name") {
            val folder = store.getFolder(name)
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
        }
        known += name
    }

    /** This server's folder for [at], made if missing and kept open (each open folder holds a connection of its own). */
    @Synchronized
    private fun own(at: Address): IMAPFolder {
        val name = folderOf(at)
        open[name]?.takeIf { it.isOpen }?.let { return it }
        ensureFolder(name)
        return (store.getFolder(name) as IMAPFolder).also {
            it.open(Folder.READ_WRITE)
            open[name] = it
        }
    }

    private fun text(message: Message): String = when (val content = message.content) {
        is String -> content
        else -> message.inputStream.use { it.readBytes().decodeToString() }
    }

    override fun close() {
        timer.shutdownNow()
        synchronized(this) { open.values.forEach { runCatching { if (it.isOpen) it.close(false) } } }
        runCatching { store.close() }
    }

    companion object {
        /** A part's bytes before base64 (about 11 MB in the message): well under what providers take in one message. */
        const val MAX_ITEM = 8L * 1024 * 1024

        /** Gmail allows 500 MB of IMAP uploads a day for the whole account, both servers together, and stops IMAP past it. */
        const val DAILY_BUDGET = 150L * 1024 * 1024
        private const val READ_TIMEOUT_MS = 12 * 60_000L
        private const val POOL_TIMEOUT_MS = 10 * 60_000L
        private const val SLOW_MS = 3_000L
        private const val SENDER = "benbow@benbow.invalid"

        /** Neutral folder names: only the start of the owner's card id (the letters' name is the one before ADR-055). */
        fun folderOf(at: Address) = if (at.box == Box.LETTERS) "Benbow-${at.short}" else "Benbow-${at.short}-parts"
    }
}
