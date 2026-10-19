package app.benbow.server.peer

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.imap.IMAPFolder
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The shared mailbox over IMAP (ADR-039): letters are put straight into folders (APPEND), so no mail is sent and no
 * spam filter sees them; the inbox stays open and waits in IDLE, so a letter is noticed as it arrives. TLS always,
 * except [MailSettings.secure] = false for a test server in the same process.
 */
class ImapMailbox(settings: MailSettings) : Mailbox {
    private val protocol = if (settings.secure) "imaps" else "imap"
    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.connectiontimeout", "15000")
            put("mail.$protocol.timeout", "$READ_TIMEOUT_MS") // longer than an IDLE, which reads nothing while it waits
            put("mail.$protocol.writetimeout", "30000")
            // The open inbox never takes the store's own connection, and that one is kept between rounds, so an APPEND
            // doesn't log in again (each login is a round trip to the provider and counts against its limits).
            put("mail.$protocol.separatestoreconnection", "true")
            put("mail.$protocol.connectionpooltimeout", "$POOL_TIMEOUT_MS")
            if (settings.secure) put("mail.imaps.ssl.checkserveridentity", "true")
        },
    )
    private val log = LoggerFactory.getLogger(ImapMailbox::class.java)

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
    private var watched: IMAPFolder? = null
    private val idling = AtomicBoolean(false)
    private val woken = AtomicBoolean(false)
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "benbow-peers-idle").apply { isDaemon = true } }
    private var idleWorks = true

    @Synchronized
    override fun ensureFolder(name: String) {
        if (name in known) return
        timed("folder $name") {
            val folder = store.getFolder(name)
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
        }
        known += name
    }

    override fun items(folder: String): List<MailItem> = timed("read $folder") {
        val f = watch(folder)
        f.messageCount // a NOOP, so messages that came during an IDLE are known
        val messages = f.messages
        f.fetch(
            messages,
            FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(UIDFolder.FetchProfileItem.UID)
            },
        )
        messages.filterNot { it.isSet(Flags.Flag.DELETED) }.map { MailItem(f.getUID(it), it.subject.orEmpty(), text(it)) }
    }

    override fun subjects(folder: String): Set<String> = timed("list $folder") {
        val f = store.getFolder(folder)
        f.open(Folder.READ_ONLY)
        try {
            val messages = f.messages
            f.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
            messages.filterNot { it.isSet(Flags.Flag.DELETED) }.mapNotNull { it.subject }.toSet()
        } finally {
            f.close(false)
        }
    }

    override fun append(folder: String, subject: String, text: String) = timed("append to $folder") {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress("benbow@benbow.invalid"))
            setSubject(subject, "us-ascii")
            setText(text, "us-ascii")
            sentDate = Date()
        }
        store.getFolder(folder).appendMessages(arrayOf(message))
    }

    override fun delete(folder: String, uids: Collection<Long>) = timed("remove from $folder") {
        val f = watch(folder)
        val messages = f.getMessagesByUID(uids.toLongArray()).filterNotNull()
        if (messages.isNotEmpty()) {
            f.setFlags(messages.toTypedArray(), Flags(Flags.Flag.DELETED), true)
            f.expunge()
        }
    }

    /**
     * IDLE on [folder] until the server tells of a change; a timer ends it after [timeoutMs] by touching the folder
     * from another thread (which sends DONE). A server without IDLE is asked again after at most 30 seconds.
     */
    override fun await(folder: String, timeoutMs: Long) {
        val f = watch(folder)
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
    private fun watch(name: String): IMAPFolder {
        watched?.takeIf { it.fullName == name && it.isOpen }?.let { return it }
        watched?.takeIf { it.isOpen }?.close(false)
        return (store.getFolder(name) as IMAPFolder).also {
            it.open(Folder.READ_WRITE)
            watched = it
        }
    }

    private fun text(message: Message): String = when (val content = message.content) {
        is String -> content
        else -> message.inputStream.use { it.readBytes().decodeToString() }
    }

    override fun close() {
        timer.shutdownNow()
        runCatching { watched?.takeIf { it.isOpen }?.close(false) }
        runCatching { store.close() }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 12 * 60_000L
        private const val POOL_TIMEOUT_MS = 10 * 60_000L
        private const val SLOW_MS = 3_000L
    }
}
