package app.benbow.server.peer

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A shared mailbox in memory that can lose, double and refuse what it carries. */
class MemoryMailbox : Mailbox {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val folders = mutableMapOf<String, MutableList<MailItem>>()
    private var nextUid = 1L

    /** Fails the next `delete` (a connection cut after the letters were kept). */
    var failNextDelete = false

    override fun ensureFolder(name: String) = lock.withLock {
        folders.getOrPut(name) { mutableListOf() }
        Unit
    }

    override fun items(folder: String) = lock.withLock { folders[folder].orEmpty().toList() }

    override fun subjects(folder: String) = lock.withLock { folders[folder].orEmpty().map { it.subject }.toSet() }

    override fun append(folder: String, subject: String, text: String) = lock.withLock {
        folders.getValue(folder) += MailItem(nextUid++, subject, text)
        changed.signalAll()
    }

    override fun delete(folder: String, uids: Collection<Long>) = lock.withLock {
        if (failNextDelete) {
            failNextDelete = false
            error("connection reset")
        }
        folders[folder]?.removeAll { it.uid in uids }
        Unit
    }

    override fun await(folder: String, timeoutMs: Long) = lock.withLock {
        changed.await(timeoutMs, TimeUnit.MILLISECONDS)
        Unit
    }

    override fun close() {}

    fun lose(folder: String) = lock.withLock { folders[folder]?.clear() }

    fun double(folder: String) = lock.withLock {
        folders.getValue(folder).toList().forEach {
            folders.getValue(folder) +=
                it.copy(uid = nextUid++)
        }
    }

    fun put(folder: String, text: String) = append(folder, "x", text)

    fun count(folder: String) = lock.withLock { folders[folder].orEmpty().size }
}

/** Two servers and one mailbox: every note arrives once, whatever the mailbox loses or doubles. */
class CourierTest {
    private var now = 1_000_000L
    private val box = MemoryMailbox()
    private val anna = Server("Аня")
    private val max = Server("Макс")

    private inner class Server(name: String) {
        val identity = PeerIdentity.generate()
        val db = PeerDatabase.inMemory()
        val courier = Courier(identity, db, box) { now }
        val inbox get() = courier.inbox

        fun note(to: Server, text: String) = db.enqueue(to.identity.card.id, Letter.NOTE, text, now)

        fun received(from: Server) = db.notes(from.identity.card.id, 100).filter { !it.outgoing }.map { it.text }.reversed()

        fun unacknowledged(to: Server) = db.notes(to.identity.card.id, 100).count { it.outgoing && it.ackedAt == null }
    }

    private fun meet() {
        anna.db.addContact("Макс", max.identity.card, now)
        max.db.addContact("Аня", anna.identity.card, now)
    }

    private fun exchange(rounds: Int = 3) = repeat(rounds) {
        anna.courier.round()
        max.courier.round()
    }

    @Test
    fun notesGoBothWaysAndAreAcknowledged() {
        meet()
        anna.note(max, "Привет")
        anna.note(max, "Как дела?")
        max.note(anna, "Привет, Аня")
        exchange()
        assertEquals(listOf("Привет", "Как дела?"), max.received(anna))
        assertEquals(listOf("Привет, Аня"), anna.received(max))
        assertEquals(0, anna.unacknowledged(max))
        assertEquals(0, max.unacknowledged(anna))
        assertEquals(0, box.count(anna.inbox) + box.count(max.inbox), "everything taken is removed")
    }

    @Test
    fun aDoubledLetterIsKeptOnce() {
        meet()
        anna.note(max, "Один раз")
        anna.courier.round()
        box.double(max.inbox)
        exchange()
        assertEquals(listOf("Один раз"), max.received(anna))
        assertEquals(0, anna.unacknowledged(max))
    }

    @Test
    fun aLostLetterIsSentAgainAfterTheWait() {
        meet()
        anna.note(max, "Потерянное")
        anna.courier.round()
        box.lose(max.inbox)
        exchange()
        assertEquals(emptyList(), max.received(anna))
        now += Courier.RESEND_AFTER_MS + 1
        exchange()
        assertEquals(listOf("Потерянное"), max.received(anna))
        assertEquals(0, anna.unacknowledged(max))
    }

    @Test
    fun aLetterWaitingForAnAbsentFriendIsNotSentAgain() {
        meet()
        anna.note(max, "Жду")
        anna.courier.round()
        now += Courier.RESEND_AFTER_MS + 1
        val round = anna.courier.round()
        assertEquals(0, round.resent)
        assertEquals(1, box.count(max.inbox))
        max.courier.round()
        anna.courier.round()
        assertEquals(listOf("Жду"), max.received(anna))
        assertEquals(0, anna.unacknowledged(max))
    }

    @Test
    fun aLostAcknowledgementBringsTheLetterAgainAndItIsKeptOnce() {
        meet()
        anna.note(max, "Дошло?")
        anna.courier.round()
        max.courier.round()
        box.lose(anna.inbox) // Max's acknowledgement
        anna.courier.round()
        assertEquals(1, anna.unacknowledged(max))
        now += Courier.RESEND_AFTER_MS + 1
        exchange()
        assertEquals(listOf("Дошло?"), max.received(anna))
        assertEquals(0, anna.unacknowledged(max))
    }

    @Test
    fun aCutBeforeRemovalLeavesNoDouble() {
        meet()
        anna.note(max, "Не задвоится")
        anna.courier.round()
        box.failNextDelete = true
        runCatching { max.courier.round() }
        exchange()
        assertEquals(listOf("Не задвоится"), max.received(anna))
        assertEquals(0, anna.unacknowledged(max))
    }

    @Test
    fun lettersFromAStrangerWaitForItsCard() {
        anna.db.addContact("Макс", max.identity.card, now) // Max hasn't added Anna yet
        anna.note(max, "Ты меня добавил?")
        anna.courier.round()
        val round = max.courier.round()
        assertEquals(1, round.waiting)
        assertEquals(1, box.count(max.inbox))
        max.db.addContact("Аня", anna.identity.card, now)
        exchange()
        assertEquals(listOf("Ты меня добавил?"), max.received(anna))
    }

    @Test
    fun garbageAndForgeriesAreRefusedAndRemoved() {
        meet()
        box.ensureFolder(max.inbox)
        box.put(max.inbox, "not an envelope")
        val impostor = PeerIdentity.generate()
        val forged = Letters.seal(impostor, max.identity.card, Letter("f", 1, Letter.NOTE, "Я Аня", sentAt = now))
            .replace(impostor.card.id, anna.identity.card.id)
        box.put(max.inbox, forged)
        val round = max.courier.round()
        assertEquals(2, round.refused)
        assertEquals(0, box.count(max.inbox))
        assertTrue(max.received(anna).isEmpty())
    }
}
