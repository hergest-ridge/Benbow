package app.benbow.server.peer

import app.benbow.server.SERVER_VERSION
import org.slf4j.LoggerFactory
import java.util.UUID

/** What one round did, for the admin page. */
data class Round(
    val received: Int = 0,
    val acknowledged: Int = 0,
    val sent: Int = 0,
    val resent: Int = 0,
    val refused: Int = 0,
    val waiting: Int = 0,
    /** Letters in a format newer than this server's, left in the mailbox until it is updated, and from whom. */
    val newer: Int = 0,
    val newerFrom: Set<String> = emptySet(),
) {
    /** Letters went one way or the other: a conversation may be going on. */
    val moved get() = received + acknowledged + sent + resent > 0
}

/**
 * Carries letters between this server and its contacts through a [Carrier] (ADR-039, ADR-055). Each round takes in what came
 * (kept before it is acknowledged, acknowledged before it is removed), then sends what waits and sends again what was
 * lost: an unacknowledged letter that left the recipient's folder without an answer. Any step may fail and be repeated;
 * a letter kept twice or acknowledged twice changes nothing.
 */
class Courier(
    private val me: PeerIdentity,
    private val db: PeerDatabase,
    private val carrier: Carrier,
    /** A letter about a file came: the pass that moves files' parts is due. Before [clock], so `Courier(…) { now }` sets the clock. */
    private val onFiles: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(Courier::class.java)

    val inbox: Address = Address.letters(me.card)

    /** Takes in what came, then sends what waits. Rounds and sends never overlap. */
    @Synchronized
    fun round(): Round = logged("round") {
        val contacts = db.contacts().associateBy { it.id }
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
            val box = Address.letters(contact.card)
            val waiting = if (due.any { it.sentAt != null }) carrier.names(box) else emptySet()
            for (out in due) {
                if (out.sentAt != null && out.letterId in waiting) continue // still there: the friend's server is away
                val letter = Letter(out.letterId, out.seq, out.kind, out.text, sentAt = clock(), server = SERVER_VERSION)
                carrier.drop(box, out.letterId, Letters.seal(me, contact.card, letter).encodeToByteArray())
                db.markSent(contact.id, out.seq, clock())
                if (out.sentAt == null) sent++ else resent++
            }
        }
        return sent to resent
    }

    private fun take(contacts: Map<String, Contact>): Round {
        val toAcknowledge = mutableMapOf<String, MutableList<Long>>()
        val done = mutableListOf<Dropped>()
        var received = 0
        var acknowledged = 0
        var refused = 0
        var waiting = 0
        val newer = HashSet<String>()
        var newerCount = 0
        for (item in carrier.waiting(inbox)) {
            val text = carrier.read(inbox, item).decodeToString()
            when (val opened = Letters.open(me, text) { contacts[it]?.card }) {
                is Opened.Ok -> {
                    val from = opened.from.id
                    opened.letter.server?.let { db.noteSeen(from, it, clock()) }
                    if (opened.letter.kind == Letter.ACK) {
                        acknowledged += db.acknowledge(from, opened.letter.acks, clock())
                    } else {
                        if (db.receive(from, opened.letter, clock())) received++
                        files(from, opened.letter)
                        toAcknowledge.getOrPut(from) { mutableListOf() } += opened.letter.seq
                    }
                    done += item
                }

                is Opened.Unknown -> waiting++

                is Opened.Newer -> {
                    newerCount++
                    newer += opened.from
                }

                is Opened.Refused -> {
                    log.warn("Refused a letter in {}: {}", inbox, opened.reason)
                    refused++
                    done += item
                }
            }
        }
        for ((contact, seqs) in toAcknowledge) {
            val card = contacts.getValue(contact).card
            val ack = Letter(UUID.randomUUID().toString(), 0, Letter.ACK, acks = seqs.sorted(), sentAt = clock(), server = SERVER_VERSION)
            carrier.drop(Address.letters(card), ack.id, Letters.seal(me, card, ack).encodeToByteArray())
        }
        carrier.remove(inbox, done)
        if (newerCount > 0) log.warn("{} letters in a newer format than this server's wait in {}: update it", newerCount, inbox)
        return Round(
            received = received,
            acknowledged = acknowledged,
            refused = refused,
            waiting = waiting,
            newer = newerCount,
            newerFrom = newer,
        )
    }

    /** Letters about files (ADR-039, ADR-055): announcements, parts the friend has, a file the friend has whole. */
    private fun files(from: String, letter: Letter) {
        val text = letter.text ?: return
        when (letter.kind) {
            Letter.FILE -> parse<SharedFile>(from, text)?.let { db.announceFile(from, it, clock()) }
            Letter.FILE_PARTS -> parse<PartedFile>(from, text)?.let { db.announceParts(from, it, clock()) }
            Letter.FILE_GOT -> parse<PartsGot>(from, text)?.let { db.partsGot(from, it.id, it.parts, clock()) }
            Letter.FILE_DONE -> db.fileDone(from, text, clock())
            else -> return
        }
        onFiles()
    }

    private inline fun <reified T> parse(from: String, text: String): T? = runCatching { fileJson.decodeFromString<T>(text) }
        .onFailure { log.warn("A file letter from {} didn't parse", from) }
        .getOrNull()

    companion object {
        /** A letter unacknowledged this long and gone from the recipient's box is sent again. */
        const val RESEND_AFTER_MS = 10 * 60_000L
    }
}
