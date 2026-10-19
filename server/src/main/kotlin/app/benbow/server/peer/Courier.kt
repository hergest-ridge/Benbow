package app.benbow.server.peer

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/** A message in the shared mailbox: its number there, its subject (the letter's id) and its text (the envelope). */
data class MailItem(val uid: Long, val subject: String, val text: String)

/** Where letters wait between the servers (ADR-039). One folder per recipient; anyone with access sees only envelopes. */
interface Mailbox : AutoCloseable {
    fun ensureFolder(name: String)

    fun items(folder: String): List<MailItem>

    fun subjects(folder: String): Set<String>

    fun append(folder: String, subject: String, text: String)

    fun delete(folder: String, uids: Collection<Long>)

    /** Returns when something may have arrived in [folder] or [timeoutMs] passed. [close] ends it at once. */
    fun await(folder: String, timeoutMs: Long)

    /** Ends an [await] in progress (or the next one, if it comes first). */
    fun wake() {}
}

/** The mailbox refused the login: retrying with the same password could get the mailbox locked, so nobody retries. */
class LoginRefused(message: String) : Exception(message)

/** What one round did, for the admin page. */
data class Round(
    val received: Int = 0,
    val acknowledged: Int = 0,
    val sent: Int = 0,
    val resent: Int = 0,
    val refused: Int = 0,
    val waiting: Int = 0,
) {
    /** Letters went one way or the other: a conversation may be going on. */
    val moved get() = received + acknowledged + sent + resent > 0
}

/**
 * Carries letters between this server and its contacts through a [Mailbox] (ADR-039). Each round takes in what came
 * (kept before it is acknowledged, acknowledged before it is removed), then sends what waits and sends again what was
 * lost: an unacknowledged letter that left the recipient's folder without an answer. Any step may fail and be repeated;
 * a letter kept twice or acknowledged twice changes nothing.
 */
class Courier(
    private val me: PeerIdentity,
    private val db: PeerDatabase,
    private val mailbox: Mailbox,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(Courier::class.java)

    val inbox: String = folderOf(me.card)

    /** Takes in what came, then sends what waits. Rounds and sends never overlap. */
    @Synchronized
    fun round(): Round = logged("round") {
        val contacts = db.contacts().associateBy { it.id }
        mailbox.ensureFolder(inbox)
        val taken = take(contacts)
        val (sent, resent) = send(contacts)
        taken.copy(sent = sent, resent = resent)
    }

    /** Sends what waits without looking at the inbox: right after a letter is queued. */
    @Synchronized
    fun sendDue(): Round = logged("send") {
        val (sent, resent) = send(db.contacts().associateBy { it.id })
        Round(sent = sent, resent = resent)
    }

    /** One line per pass that moved letters, so the log tells how long they took. */
    private inline fun logged(what: String, block: () -> Round): Round {
        val start = System.nanoTime()
        return block().also {
            if (it != Round()) log.info("Letters {}: {} in {} ms", what, it, (System.nanoTime() - start) / 1_000_000)
        }
    }

    private fun send(contacts: Map<String, Contact>): Pair<Int, Int> {
        var sent = 0
        var resent = 0
        for (contact in contacts.values) {
            val due = db.due(contact.id, sentBefore = clock() - RESEND_AFTER_MS)
            if (due.isEmpty()) continue
            val folder = folderOf(contact.card)
            mailbox.ensureFolder(folder)
            val waiting = if (due.any { it.sentAt != null }) mailbox.subjects(folder) else emptySet()
            for (out in due) {
                if (out.sentAt != null && out.letterId in waiting) continue // still there: the friend's server is away
                val letter = Letter(out.letterId, out.seq, out.kind, out.text, sentAt = clock())
                mailbox.append(folder, out.letterId, Letters.seal(me, contact.card, letter))
                db.markSent(contact.id, out.seq, clock())
                if (out.sentAt == null) sent++ else resent++
            }
        }
        return sent to resent
    }

    private fun take(contacts: Map<String, Contact>): Round {
        val toAcknowledge = mutableMapOf<String, MutableList<Long>>()
        val done = mutableListOf<Long>()
        var received = 0
        var acknowledged = 0
        var refused = 0
        var waiting = 0
        for (item in mailbox.items(inbox)) {
            when (val opened = Letters.open(me, item.text) { contacts[it]?.card }) {
                is Opened.Ok -> {
                    val from = opened.from.id
                    if (opened.letter.kind == Letter.ACK) {
                        acknowledged += db.acknowledge(from, opened.letter.acks, clock())
                    } else {
                        if (db.receive(from, opened.letter, clock())) received++
                        files(from, opened.letter)
                        toAcknowledge.getOrPut(from) { mutableListOf() } += opened.letter.seq
                    }
                    done += item.uid
                }

                is Opened.Unknown -> waiting++

                is Opened.Refused -> {
                    log.warn("Refused a letter in {}: {}", inbox, opened.reason)
                    refused++
                    done += item.uid
                }
            }
        }
        for ((contact, seqs) in toAcknowledge) {
            val card = contacts.getValue(contact).card
            val ack = Letter(UUID.randomUUID().toString(), 0, Letter.ACK, acks = seqs.sorted(), sentAt = clock())
            val folder = folderOf(card)
            mailbox.ensureFolder(folder)
            mailbox.append(folder, ack.id, Letters.seal(me, card, ack))
        }
        if (done.isNotEmpty()) mailbox.delete(inbox, done)
        return Round(received = received, acknowledged = acknowledged, refused = refused, waiting = waiting)
    }

    /** A file's announcement (its body comes through Drive), or the friend's word that a file of ours arrived. */
    private fun files(from: String, letter: Letter) {
        val text = letter.text ?: return
        when (letter.kind) {
            Letter.FILE -> runCatching { Json.decodeFromString<SharedFile>(text) }
                .onSuccess { db.announceFile(from, it, clock()) }
                .onFailure { log.warn("A file letter from {} didn't parse", from) }

            Letter.FILE_DONE -> db.fileDone(from, text, clock())
        }
    }

    companion object {
        /** A letter unacknowledged this long and gone from the recipient's folder is sent again. */
        const val RESEND_AFTER_MS = 10 * 60_000L

        /** Neutral folder names: only the start of the recipient's card id. */
        fun folderOf(card: PeerCard) = "Benbow-" + card.id.take(12)
    }
}
