package app.benbow.server.peer

import app.benbow.server.SERVER_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two servers and one mailbox: every note arrives once, whatever the mailbox loses or doubles. */
class CourierTest {
    private var now = 1_000_000L
    private val box = MemoryCarrier()
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
        box.failNextRemove = true
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

    @Test
    fun aLetterInANewerFormatWaitsForTheUpdateInsteadOfGoingRound() {
        meet()
        val today = Letters.seal(anna.identity, max.identity.card, Letter("n", 1, Letter.NOTE, "из будущего", sentAt = now))
        val tomorrow = today.replace("\"v\":${Letters.VERSION},", "\"v\":${Letters.VERSION + 1},")
        box.put(max.inbox, tomorrow)
        val round = max.courier.round()
        assertEquals(1, round.newer)
        assertEquals(setOf(anna.identity.card.id), round.newerFrom)
        assertEquals(0, round.refused, "not refused, not removed")
        assertEquals(1, box.count(max.inbox))
        repeat(2) { max.courier.round() }
        assertEquals(1, box.count(max.inbox), "it stays there round after round")
    }

    @Test
    fun lettersSayTheSendersVersion() {
        meet()
        anna.note(max, "Привет")
        exchange()
        assertEquals(SERVER_VERSION, max.db.seen(anna.identity.card.id)?.version)
        assertEquals(SERVER_VERSION, anna.db.seen(max.identity.card.id)?.version, "acknowledgements say it too")
        assertEquals(null, anna.db.seen("someone-else"))
    }
}
